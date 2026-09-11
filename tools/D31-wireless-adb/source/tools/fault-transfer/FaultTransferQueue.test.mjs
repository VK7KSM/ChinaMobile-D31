import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync, spawn} from 'node:child_process';
import {QueueStore, QueueError, hash} from './QueueStore.mjs';
import {runRound, DEFAULT_LIMITS} from './FaultTransferQueue.mjs';
import {PackageVerifier} from './PackageVerifier.mjs';
import {WebTransport} from './WebTransport.mjs';

const ROOT = fileURLToPath(new URL('./', import.meta.url));
const RUN = fs.mkdtempSync(path.join(ROOT, 'offline-test-' + new Date().toISOString().replace(/[:.]/g, '-') + '-'));
const TARGET = {deviceName: 'SYNTHETIC_D31', expectedVersion: 'test', activeApk: `/data/local/d31-remote/releases/${'c'.repeat(64)}/remote.apk`};
const ID = 'a'.repeat(64);
const CLOCK = () => {
  let now = 1800000000000, tick = 0;
  return {now: () => now, monotonic: () => tick, sleep: async ms => { now += ms; tick += ms; }, advance: ms => { now += ms; tick += ms; }};
};
const CRASH = new Error('SYNTHETIC_PROCESS_CRASH');

// 复用既有测试的合成ZIP构造，真正运行既有验包器；不复制其判定逻辑。
function fixture(directory, corrupt = false) {
  fs.mkdirSync(directory, {recursive: true});
  const source = fileURLToPath(new URL('../faults/Test-FaultExportVerifier.py', import.meta.url));
  const program = `import importlib.util,sys,json
from pathlib import Path
s=importlib.util.spec_from_file_location('fixtures',sys.argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)
t=m.VerifierTests(); t.root=Path(sys.argv[2]); t.event='a'*64
t.files={'event.json':b'{}','state.json':b'{"phase":"PARTIAL","capture":"PARTIAL"}','export-seal.json':b'{}','attempt-1/fault-source.bin':b'synthetic-partial-bytes'}
change=(lambda name,value:(name,b'wrong') if name.endswith('.bin') else (name,value)) if sys.argv[3]=='bad' else None
p,r=t.fixture(change_entry=change); r.update(exportNumber=1,path='/data/local/d31-remote/faults/'+t.event+'/exports/export-1/bundle.zip',captureState='PARTIAL')
print(json.dumps(r))
`;
  const receipt = JSON.parse(execFileSync('python', ['-c', program, source, directory, corrupt ? 'bad' : 'good'], {encoding: 'utf8', env: {...process.env, PYTHONDONTWRITEBYTECODE: '1'}}));
  return {receipt, bytes: fs.readFileSync(path.join(directory, 'bundle.zip'))};
}
const FIXTURE = fixture(path.join(RUN, 'fixture'));
const BAD_FIXTURE = fixture(path.join(RUN, 'bad-fixture'), true);

class FakeTransport {
  constructor(fixtureValue = FIXTURE) {
    this.fixture = fixtureValue; this.tasks = new Map(); this.sends = []; this.queries = []; this.downloads = 0;
  }
  async resolveTarget() { return {id: 'SYNTHETIC_DEVICE'}; }
  async queryTask(device, id) { this.queries.push(id); return this.tasks.get(id) || null; }
  async enqueue(request) {
    this.sends.push(structuredClone(request));
    const old = this.tasks.get(request.id);
    if (old) return old;
    const receipt = this.fixture.receipt;
    let result;
    if (request.type === 'get_file') result = {action: 'uploaded', sha256: receipt.sha256, bytes: receipt.bytes};
    else {
      const args = request.params.command.split('FaultCommand ')[1].split(' ');
      let value;
      if (args[0] === 'index') value = {schemaVersion: 1, events: [{eventId: ID, category: 'TOMBSTONE', state: {phase: 'PARTIAL', capture: 'PARTIAL'}}], nextAfter: ID, hasMore: false};
      if (args[0] === 'export') value = receipt;
      if (args[0] === 'archive') value = {eventId: ID, state: 'ARCHIVED', sha256: receipt.sha256, bytes: receipt.bytes,
        manifestSha256: receipt.manifestSha256, originalsDeleted: false, releasedBytes: 0, activeSlotReleased: true};
      if (args[0] === 'query') value = {eventId: ID, export: {state: 'EXPORTED', archived: true, receipt}};
      result = {text: JSON.stringify(value), exit_code: 0, truncated: false};
    }
    const task = {id: request.id, type: request.type, state: 'success', result};
    this.tasks.set(request.id, task); return task;
  }
  async metadata(device, task) {
    return {ok: true, file: {device_id: device, task_id: task, state: 'ready', size: this.fixture.receipt.bytes, sha256: this.fixture.receipt.sha256}};
  }
  async download(device, task, options) {
    this.downloads++;
    fs.writeFileSync(options.filename, this.fixture.bytes, {flag: 'wx'}); options.onBytes(this.fixture.bytes.length);
  }
  stageSends(stage) { return this.sends.filter(r => stage === 'get_file' ? r.type === stage : r.params.command?.includes(`FaultCommand ${stage} `)); }
}

function context(name, transport = new FakeTransport()) {
  const directory = path.join(RUN, name);
  const options = {store: new QueueStore(directory), transport, verifier: new PackageVerifier(), target: TARGET, clock: CLOCK()};
  return {directory, options, transport, run: extras => runRound({...options, ...extras}),
    state: () => options.store.withLock(async () => options.store.load())};
}

test('真实逐项验包允许PARTIAL保全，只有独立归档查询后才完成', async () => {
  const c = context('partial'); const result = await c.run();
  assert.equal(result.archived, 1); assert.equal(result.blocked, false);
  const event = (await c.state()).events[0];
  assert.equal(event.summary.sourceLogComplete, false); assert.equal(event.summary.gapCount, 1);
  assert.equal(event.summary.fullIncidentWindow, false); assert.equal(event.summary.originalsDeleted, false);
  assert.equal(c.transport.stageSends('archive').length, 1); assert.equal(c.transport.stageSends('query').length, 1);
  assert.ok(fs.existsSync(path.join(c.directory, event.bundle)));
});

for (const point of ['intent:export', 'send-intent:export', 'after-send:export', 'task-result:export',
  'intent:getFile', 'after-send:getFile', 'download-intent', 'after-download', 'verify-intent', 'after-verify',
  'host-verified', 'intent:archive', 'after-send:archive', 'archive-awaiting-query', 'after-send:confirm', 'done']) {
  test(`崩溃恢复窗口 ${point} 保留同阶段原任务号`, async () => {
    const c = context('crash-' + point.replaceAll(':', '-'));
    await assert.rejects(c.run({checkpoint: label => { if (label === point) throw CRASH; }}), error => error === CRASH);
    const before = await c.state();
    const ids = Object.fromEntries(Object.entries(before.events[0]?.tasks || {}).map(([key, value]) => [key, value.request.id]));
    // 新store实例模拟进程重启，服务端任务仍保留。
    c.options.store = new QueueStore(c.directory);
    const result = await c.run(); assert.equal(result.archived, 1); assert.equal(result.blocked, false);
    const after = await c.options.store.withLock(async () => c.options.store.load());
    for (const [key, id] of Object.entries(ids)) assert.equal(after.events[0].tasks[key].request.id, id);
    for (const stage of ['export', 'get_file', 'archive', 'query']) assert.equal(c.transport.stageSends(stage).length, 1);
    if (point === 'after-download') assert.equal(c.transport.downloads, 1);
  });
}

test('发送已被服务端接受但连接断开，只查询原号不再次发送', async () => {
  const c = context('unknown-accepted'); const original = c.transport.enqueue.bind(c.transport); let once = true;
  c.transport.enqueue = async request => {
    const task = await original(request);
    if (once && request.params.command?.includes('FaultCommand export ')) { once = false; throw new QueueError('NETWORK_UNAVAILABLE', true); }
    return task;
  };
  const first = await c.run({limits: {maxRetries: 1}}); assert.equal(first.stop, 'ROUND_RETRY_BUDGET');
  const id = (await c.state()).events[0].tasks.export.request.id;
  const result = await c.run(); assert.equal(result.archived, 1);
  assert.ok(c.transport.queries.filter(q => q === id).length >= 2); assert.equal(c.transport.stageSends('export').length, 1);
});

test('发送未被接受且查询权威不存在，只补交原号原参数', async () => {
  const c = context('unknown-absent'); const original = c.transport.enqueue.bind(c.transport); let failed;
  c.transport.enqueue = async request => {
    if (!failed && request.params.command?.includes('FaultCommand export ')) {
      failed = structuredClone(request); throw new QueueError('NETWORK_UNAVAILABLE', true);
    }
    return original(request);
  };
  await c.run({limits: {maxRetries: 1}}); const result = await c.run(); assert.equal(result.archived, 1);
  assert.deepEqual(c.transport.stageSends('export')[0], failed);
});

test('原任务超期且不可查，阻断而不换号重新导出', async () => {
  const c = context('expired');
  await assert.rejects(c.run({checkpoint: label => { if (label === 'intent:export') throw CRASH; }}));
  c.options.clock.advance(600001);
  const result = await c.run(); assert.equal(result.stop, 'TASK_ABSENT_AFTER_EXPIRY'); assert.equal(result.blocked, true);
  assert.equal(c.transport.stageSends('export').length, 0);
});

test('内部摘要错误但外包摘要正确，也不归档', async () => {
  const c = context('inner-digest', new FakeTransport(BAD_FIXTURE));
  const result = await c.run(); assert.equal(result.stop, 'PACKAGE_VERIFY_FAILED');
  assert.equal(c.transport.stageSends('get_file').length, 1); assert.equal(c.transport.stageSends('archive').length, 0);
});

test('外包内容摘要错误不归档，保留下载原件', async () => {
  const c = context('outer-digest');
  c.transport.download = async (device, task, options) => {
    const bytes = Buffer.from(c.transport.fixture.bytes); bytes[0] ^= 1;
    fs.writeFileSync(options.filename, bytes, {flag: 'wx'}); options.onBytes(bytes.length);
  };
  const result = await c.run(); assert.equal(result.stop, 'DOWNLOADED_PACKAGE_MISMATCH');
  const state = await c.state(); assert.ok(fs.existsSync(path.join(c.directory, state.events[0].bundle)));
  assert.equal(c.transport.stageSends('archive').length, 0);
});

test('重启时本机包已丢失，旧验包成功不能授权归档', async () => {
  const c = context('lost-local');
  await assert.rejects(c.run({checkpoint: label => { if (label === 'host-verified') throw CRASH; }}));
  const event = (await c.state()).events[0]; fs.renameSync(path.join(c.directory, event.bundle), path.join(c.directory, 'preserved.zip'));
  assert.equal((await c.run()).stop, 'LOCAL_PACKAGE_MISMATCH'); assert.equal(c.transport.stageSends('archive').length, 0);
});

test('下载断网半件保留且计入字节预算；下一轮继续原get_file', async () => {
  const c = context('interrupted-download'); const original = c.transport.download.bind(c.transport); let once = true;
  c.transport.download = async (device, task, options) => {
    if (once) {
      once = false; fs.writeFileSync(options.filename, Buffer.from('partial'), {flag: 'wx'}); options.onBytes(7);
      throw new QueueError('DOWNLOAD_INTERRUPTED', true);
    }
    return original(device, task, options);
  };
  const first = await c.run({limits: {maxBytes: FIXTURE.bytes.length}});
  assert.equal(first.stop, 'ROUND_BYTE_BUDGET'); assert.equal(first.receivedDownloadBytes, 7);
  assert.equal(first.reservedDownloadBytes, FIXTURE.bytes.length);
  const half = (await c.state()).events[0].bundle;
  assert.equal((await c.run()).archived, 1); assert.equal(fs.statSync(path.join(c.directory, half)).size, 7);
  assert.equal(c.transport.stageSends('get_file').length, 1);
});

test('持续断网有每轮请求、重试和持久退避预算', async () => {
  const c = context('offline');
  await assert.rejects(c.run({checkpoint: label => { if (label === 'intent:export') throw CRASH; }}));
  c.transport.queryTask = async () => { throw new QueueError('NETWORK_UNAVAILABLE', true); };
  const result = await c.run({limits: {maxRetries: 2}});
  assert.equal(result.stop, 'ROUND_RETRY_BUDGET'); assert.equal(result.requests, 3);
  const state = await c.state(); assert.ok(state.nextAt > c.options.clock.now());
  assert.equal(c.transport.stageSends('export').length, 0);
});

test('总时限不会以新task重试仍运行的原任务', async () => {
  const c = context('time-budget'); const original = c.transport.enqueue.bind(c.transport);
  c.transport.enqueue = async request => {
    const task = await original(request);
    if (request.params.command?.includes('FaultCommand export ')) { task.state = 'running'; task.result = null; }
    return task;
  };
  const first = await c.run({limits: {maxMs: 2500}}); assert.equal(first.stop, 'ROUND_TIME_BUDGET');
  await c.run({limits: {maxMs: 2500}}); assert.equal(c.transport.stageSends('export').length, 1);
});

test('每轮请求预算在提交前耗尽仍保留写前意图', async () => {
  const c = context('request-budget'); const result = await c.run({limits: {maxRequests: 2}});
  assert.equal(result.stop, 'ROUND_REQUEST_BUDGET'); assert.equal(c.transport.sends.length, 0);
  const id = (await c.state()).index.index.request.id;
  await c.run(); assert.equal(c.transport.sends[0].id, id);
});

test('队列目标不匹配在任何网络调用前拒绝', async () => {
  const c = context('target'); await c.run();
  c.transport.resolveTarget = async () => { throw Error('不应调用'); };
  await assert.rejects(c.run({target: {...TARGET, expectedVersion: 'other'}}), /STATE_TARGET_MISMATCH/);
});

test('损坏最新状态不回退旧快照重新派任务', async () => {
  const c = context('corrupt-state'); await c.run();
  const names = fs.readdirSync(c.directory).filter(n => /^state-\d{6}\.json$/.test(n)).sort();
  const filename = path.join(c.directory, names.at(-1)); const data = JSON.parse(fs.readFileSync(filename));
  data.state.cursor = 'b'.repeat(64); fs.writeFileSync(filename, JSON.stringify(data));
  await assert.rejects(c.run(), /STATE_HASH_MISMATCH/);
});

test('系统文件锁拒绝另一宿主，释放后可恢复', async () => {
  const c = context('lock');
  await c.options.store.withLock(async () => {
    const other = new QueueStore(c.directory);
    await assert.rejects(other.withLock(async () => {}), /QUEUE_LOCK_BUSY/);
  });
  await new QueueStore(c.directory).withLock(async () => {});
});

test('真实宿主进程被终止后系统锁自动释放', async () => {
  const directory = path.join(RUN, 'killed-lock');
  const moduleUrl = new URL('./QueueStore.mjs', import.meta.url).href;
  const child = spawn(process.execPath, ['--input-type=module', '-e',
    `import {QueueStore} from ${JSON.stringify(moduleUrl)}; await new QueueStore(${JSON.stringify(directory)}).withLock(async()=>{console.log('READY');await new Promise(()=>{});});`], {stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true});
  await new Promise((resolve, reject) => {
    child.once('error', reject); child.once('exit', code => reject(Error(String(code))));
    child.stdout.once('data', () => resolve());
  });
  const closed = new Promise(resolve => child.once('close', resolve)); child.kill(); await closed;
  // 管道关闭与锁进程退出存在短窗口；有界重试仅限本机测试。
  let acquired = false;
  for (let i = 0; i < 20 && !acquired; i++) {
    try { await new QueueStore(directory).withLock(async () => {}); acquired = true; }
    catch { await new Promise(r => setTimeout(r, 20)); }
  }
  assert.equal(acquired, true);
});

const SESSION = {cookies: [{name: 'elf_admin', domain: 'v.elfradio.net', value: 'SYNTHETIC_ONLY'}]};
test('真实Web适配器区分任务不存在、设备不存在、断网及同号冲突', async () => {
  let response;
  const web = new WebTransport({session: SESSION, fetchImpl: async () => response});
  const options = {signal: new AbortController().signal};
  response = Response.json({ok: false, msg: '未找到该任务'}, {status: 404}); assert.equal(await web.queryTask('d', 't', options), null);
  response = Response.json({ok: false, msg: '未找到该设备'}, {status: 404}); await assert.rejects(web.queryTask('d', 't', options), /TASK_LOOKUP_NOT_AUTHORITATIVE/);
  response = Response.json({ok: false, reason: 'inflight'}, {status: 400}); await assert.rejects(web.enqueue({}, options), e => e.code === 'REMOTE_TASK_INFLIGHT' && e.retryable);
  response = Response.json({ok: false, reason: 'idempotency-conflict'}, {status: 400}); await assert.rejects(web.enqueue({}, options), e => e.code === 'REMOTE_IDEMPOTENCY_CONFLICT' && !e.retryable);
  web.fetch = async () => { throw Error('不输出响应或会话值'); }; await assert.rejects(web.queryTask('d', 't', options), /NETWORK_UNAVAILABLE/);
});

test('真实下载适配器流式上限拒绝超长内容且保留半件', async () => {
  const filename = path.join(RUN, 'too-long.zip'); let charged = 0;
  const web = new WebTransport({session: SESSION, fetchImpl: async () => new Response(new Uint8Array(9))});
  await assert.rejects(web.download('d', 't', {filename, bytes: 8, onBytes: n => { charged += n; }, signal: new AbortController().signal}), /DOWNLOAD_BYTE_LIMIT/);
  assert.equal(charged, 9); assert.equal(fs.statSync(filename).size, 0);
});

test('默认预算明确只处理少量事件', () => {
  assert.equal(DEFAULT_LIMITS.maxEvents, 2); assert.equal(DEFAULT_LIMITS.maxBytes, 16777216);
  assert.equal(hash(FIXTURE.bytes), FIXTURE.receipt.sha256);
});

test('完成后再次运行不重复取包或归档，事件预算保持有限', async () => {
  const c = context('completed-resume'); const first = await c.run({limits: {maxEvents: 1}});
  assert.equal(first.stop, 'ROUND_EVENT_BUDGET'); assert.equal(first.events, 1); assert.equal(first.preservedWithGaps, 1);
  const second = await c.run(); assert.equal(second.events, 0); assert.equal(second.archived, 1);
  assert.equal(c.transport.stageSends('export').length, 1); assert.equal(c.transport.stageSends('get_file').length, 1);
  assert.equal(c.transport.stageSends('archive').length, 1);
});

test('服务端元数据bytes形状可用，size与bytes矛盾拒绝', async () => {
  const good = context('metadata-bytes');
  const original = good.transport.metadata.bind(good.transport);
  good.transport.metadata = async (...args) => { const value = await original(...args); value.file.bytes = value.file.size; delete value.file.size; return value; };
  assert.equal((await good.run()).archived, 1);
  const bad = context('metadata-conflict');
  const originalBad = bad.transport.metadata.bind(bad.transport);
  bad.transport.metadata = async (...args) => { const value = await originalBad(...args); value.file.bytes = value.file.size + 1; return value; };
  assert.equal((await bad.run()).stop, 'SERVER_FILE_MISMATCH'); assert.equal(bad.transport.downloads, 0);
});

test('任务结果号不匹配或truncated不得继续到导出和归档', async () => {
  for (const bad of ['identity', 'truncated']) {
    const c = context('task-' + bad); const original = c.transport.enqueue.bind(c.transport);
    c.transport.enqueue = async request => {
      const task = await original(request);
      if (bad === 'identity') task.id = 'different'; else task.result.truncated = true;
      return task;
    };
    assert.equal((await c.run()).stop, bad === 'identity' ? 'TASK_IDENTITY_MISMATCH' : 'TASK_TERMINAL_FAILURE');
    assert.equal(c.transport.stageSends('export').length, 0); assert.equal(c.transport.stageSends('archive').length, 0);
  }
});

test('index分页预算保留游标，损坏索引项显式计缺口', async () => {
  const c = context('index-pages'); const original = c.transport.enqueue.bind(c.transport);
  c.transport.enqueue = async request => {
    const task = await original(request);
    if (request.params.command?.includes('FaultCommand index ')) {
      const cursor = request.params.command.split('FaultCommand index 1')[1].trim();
      const id = cursor ? String.fromCharCode(cursor.charCodeAt(0) + 1).repeat(64) : ID;
      task.result.text = JSON.stringify({schemaVersion: 1, events: [{eventId: id, state: 'INDEX_CORRUPT'}], hasMore: true, nextAfter: id});
    }
    return task;
  };
  const result = await c.run({limits: {maxIndexPages: 2}});
  assert.equal(result.stop, 'ROUND_INDEX_BUDGET'); assert.equal(result.indexGaps, 2);
  assert.equal((await c.state()).cursor, 'b'.repeat(64)); assert.equal(c.transport.stageSends('export').length, 0);
});

test('字节预算不足时不开始下载且不提前归档', async () => {
  const c = context('small-byte-budget'); const result = await c.run({limits: {maxBytes: 1}});
  assert.equal(result.stop, 'ROUND_BYTE_BUDGET'); assert.equal(result.reservedDownloadBytes, 0);
  assert.equal(c.transport.downloads, 0); assert.equal(c.transport.stageSends('archive').length, 0);
});

test('本地容量上限拒绝新增且保留已有文件', async () => {
  const directory = path.join(RUN, 'disk-cap'); const store = new QueueStore(directory, {maxDiskBytes: 4194304});
  await store.withLock(async () => {
    store.load(); store.save({schemaVersion: 1});
    assert.throws(() => store.writeNew('too-large.bin', Buffer.alloc(4194304)), /STORE_CAPACITY_LIMIT/);
    assert.equal(fs.existsSync(path.join(directory, 'too-large.bin')), false);
    assert.ok(fs.existsSync(path.join(directory, 'state-000001.json')));
  });
});

console.log('离线合成证据目录：' + RUN);
