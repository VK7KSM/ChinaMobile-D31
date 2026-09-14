import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync, spawn} from 'node:child_process';
import {QueueStore, QueueError, hash} from './QueueStore.mjs';
import {runRound, DEFAULT_LIMITS} from './FaultTransferQueue.mjs';
import {PackageVerifier} from './PackageVerifier.mjs';
import {WebTransport, retryDeadline} from './WebTransport.mjs';

const ROOT = fileURLToPath(new URL('./', import.meta.url));
const RUN = fs.mkdtempSync(path.join(ROOT, 'batch7-offline-test-' + new Date().toISOString().replace(/[:.]/g, '-') + '-'));
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
      if (args[0] === 'pending') value = pendingPage([{eventId: ID, category: 'TOMBSTONE', phase: 'PARTIAL', captureState: 'PARTIAL',
        exportState: 'RECEIPT_RECORDED_UNVERIFIED', archiveState: 'ACK_RECORDED_UNVERIFIED'}], false);
      if (args[0] === 'export') value = receipt;
      if (args[0] === 'archive') value = {eventId: ID, state: 'ARCHIVED', sha256: receipt.sha256, bytes: receipt.bytes,
        manifestSha256: receipt.manifestSha256, originalsDeleted: false, releasedBytes: 0, activeSlotReleased: true};
      if (args[0] === 'query') value = {schemaVersion: 1, eventId: ID, category: 'TOMBSTONE', state: {phase: 'PARTIAL', capture: 'PARTIAL'},
        export: {state: 'EXPORTED', archived: true, receipt}};
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
  async received(device, task, proof) {
    this.deliveries ??= [];
    this.deliveries.push({device, task, ...proof});
    return {ok: true, purged: true};
  }
  stageSends(stage) { return this.sends.filter(r => stage === 'get_file' ? r.type === stage : r.params.command?.includes(`FaultCommand ${stage} `)); }
}

function pendingPage(events, hasMore) {
  return {schemaVersion: 1, kind: 'FAULT_PENDING_INDEX', verificationScope: 'METADATA_ONLY', selection: 'ALL_RETAINED_HOST_SELECTS_PENDING',
    events, nextAfter: events.at(-1)?.eventId || '', hasMore};
}

function context(name, transport = new FakeTransport(), pending = false) {
  const directory = path.join(RUN, name);
  // 旧归档测试显式选择原行为；新增默认测试验证保全和回执不自动归档。
  const options = {store: new QueueStore(directory), transport, verifier: new PackageVerifier(), target: TARGET, clock: CLOCK(), archive: true};
  let initialized = false;
  return {directory, options, transport, run: async extras => {
    // 原40项使用纯合成第六批状态，继续验证旧目录合同，不读取生产110证据。
    if (!initialized) {
      initialized = true;
      if (!pending) await options.store.withLock(async () => {
        options.store.load(); options.store.save({schemaVersion: 1, target: TARGET, deviceId: 'SYNTHETIC_DEVICE',
          cursor: '', events: [], index: {}, nextAt: 0, failures: 0});
      });
    }
    return runRound({...options, ...extras});
  },
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
test('服务器退避支持长秒数和HTTP日期，畸形值不当日期', () => {
  const now = Date.UTC(2026, 8, 12);
  assert.equal(retryDeadline('7200', now), now + 7200000);
  assert.equal(retryDeadline(new Date(now + 3600000).toUTCString(), now), now + 3600000);
  for (const value of [null, '', '-1', '0.5', 'bogus', new Date(now - 1000).toUTCString()])
    assert.equal(retryDeadline(value, now), 0);
  assert.equal(retryDeadline('999999999999999999999999', now), Number.MAX_SAFE_INTEGER);
});
test('任务和文件取回的限流期限透传且不创建下载半件', async () => {
  const now = 1800000000000;
  const web = new WebTransport({session: SESSION, now: () => now,
    fetchImpl: async () => new Response('limited', {status: 429, headers: {'Retry-After': '3600'}})});
  const options = {signal: new AbortController().signal};
  await assert.rejects(web.queryTask('d', 't', options), e => e.retryable && e.retryAt === now + 3600000);
  const filename = path.join(RUN, 'limited-not-created.zip');
  await assert.rejects(web.download('d', 't', {...options, filename, bytes: 1, onBytes: () => {}}),
    e => e.retryable && e.retryAt === now + 3600000);
  assert.equal(fs.existsSync(filename), false);
});
test('长退避持久化后新宿主在期限前零请求，不被本地上限截短', async () => {
  const c = context('server-backoff'); let calls = 0;
  const until = c.options.clock.now() + 3600000;
  c.transport.queryTask = async () => { calls++; const error = new QueueError('HTTP_429', true); error.retryAt = until; throw error; };
  const first = await c.run();
  assert.equal(first.stop, 'ROUND_TIME_BUDGET');
  assert.equal((await c.state()).nextAt, until);
  const priorCalls = calls;
  const next = await runRound({...c.options, store: new QueueStore(c.directory)});
  assert.equal(next.requests, 0); assert.equal(calls, priorCalls);
  assert.equal((await c.state()).nextAt, until);
});
test('首次解析目标限流也落盘，重开宿主不再联网', async () => {
  const c = context('initial-server-backoff', new FakeTransport(), true); let calls = 0;
  const until = c.options.clock.now() + 3600000;
  c.transport.resolveTarget = async () => { calls++; const error = new QueueError('HTTP_429', true); error.retryAt = until; throw error; };
  await c.run();
  assert.equal((await c.state()).initializing, true);
  assert.equal((await c.state()).nextAt, until);
  const result = await runRound({...c.options, store: new QueueStore(c.directory)});
  assert.equal(result.requests, 0); assert.equal(calls, 1);
});
test('初始化状态的错误类型在联网前拒绝，不允许重新绑定设备', async () => {
  for (const [i, bad] of [{initializing:'true'}, {initializing:1}, {initializing:true,deviceId:null,index:[]},
    {initializing:true,deviceId:null,index:true}].entries()) {
    const c = context('bad-initialization-'+i); let calls=0;
    await c.options.store.withLock(async () => {c.options.store.load(); c.options.store.save({schemaVersion:1,target:TARGET,
      deviceId:'SYNTHETIC_DEVICE',cursor:'',events:[],index:{},nextAt:0, ...bad});});
    c.transport.resolveTarget=async()=>{calls++;return {id:'OTHER'};};
    await assert.rejects(runRound(c.options), /STATE_INITIALIZATION_INVALID/);
    assert.equal(calls,0);
  }
});
test('显式新会话恢复仅解除会话拒绝，其他阻断不放行', async () => {
  const c = context('session-recovery'); const resolve = c.transport.resolveTarget.bind(c.transport);
  c.transport.resolveTarget = async () => { throw new QueueError('SESSION_REJECTED'); };
  assert.equal((await c.run()).blocked, true);
  c.transport.resolveTarget = resolve;
  assert.equal((await c.run()).requests, 0);
  assert.equal((await c.run({resumeSession:true})).archived, 1);
  await c.options.store.withLock(async () => {const s=c.options.store.load();s.blocked={code:'TASK_IDENTITY_MISMATCH'};c.options.store.save(s);});
  assert.equal((await c.run({resumeSession:true})).requests, 0);
});
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

test('新目录pending16后先详细query；未验证归档标记不跳过包保全', async () => {
  const c = context('pending-complete', new FakeTransport(), true);
  const result = await c.run(); assert.equal(result.archived, 1); assert.equal(result.candidatesChecked, 1);
  assert.equal(result.discovery, 'pending16');
  assert.deepEqual(c.transport.sends.map(r => r.type === 'get_file' ? 'get_file' : r.params.command.split('FaultCommand ')[1].split(' ')[0]),
    ['pending', 'query', 'export', 'get_file', 'archive', 'query']);
  const state = await c.state(); assert.equal(state.events[0].summary.sourceLogComplete, false);
  assert.ok(state.events[0].inspectionFile); assert.equal(c.transport.stageSends('index').length, 0);
});

test('discovery-only只发现与query；后续普通轮次复用已验候选再导出', async () => {
  const c = context('discovery-only', new FakeTransport(), true);
  const first = await c.run({discoveryOnly: true});
  assert.equal(first.candidatesChecked, 1); assert.equal(first.archived, 0); assert.equal(first.pending, 1);
  assert.equal(c.transport.downloads, 0);
  assert.equal(c.transport.stageSends('export').length, 0); assert.equal(c.transport.stageSends('get_file').length, 0);
  assert.equal(c.transport.stageSends('archive').length, 0);
  const id = (await c.state()).events[0].tasks.inspect.request.id;
  const result = await c.run(); assert.equal(result.archived, 1);
  assert.equal((await c.state()).events[0].tasks.inspect.request.id, id); assert.equal(c.transport.stageSends('query').length, 2);
});

for (const point of ['intent:index', 'after-send:index', 'index-consumed', 'page-entry-consumed',
  'intent:inspect', 'after-send:inspect', 'task-result:inspect', 'candidate-confirmed']) {
  test(`新发现崩溃窗口 ${point} 保留页内进度和原任务号`, async () => {
    const c = context('p7-' + point.replaceAll(':', '-'), new FakeTransport(), true);
    await assert.rejects(c.run({checkpoint: label => { if (label === point) throw CRASH; }}), error => error === CRASH);
    const previous = await c.state(); const indexId = previous.index.index?.request.id;
    const inspectionId = previous.events[0]?.tasks.inspect?.request.id;
    c.options.store = new QueueStore(c.directory);
    const result = await c.run(); assert.equal(result.archived, 1); assert.equal(result.blocked, false);
    if (indexId) assert.equal(c.transport.stageSends('pending')[0].id, indexId);
    if (inspectionId) assert.equal((await c.state()).events[0].tasks.inspect.request.id, inspectionId);
    assert.equal(c.transport.stageSends('pending').length, 1);
    assert.equal(c.transport.stageSends('query').length, 2); assert.equal(c.transport.stageSends('export').length, 1);
  });
}

test('旧目录未决index原号恢复，不改成pending或重复旧DONE', async () => {
  const c = context('legacy-live-index');
  await assert.rejects(c.run({checkpoint: label => { if (label === 'after-send:index') throw CRASH; }}));
  const id = (await c.state()).index.index.request.id;
  assert.equal((await c.run()).archived, 1);
  assert.ok(c.transport.queries.includes(id)); assert.equal(c.transport.stageSends('index').length, 1);
  await c.run(); assert.equal(c.transport.stageSends('export').length, 1); assert.equal(c.transport.stageSends('pending').length, 0);
  const snapshotsBefore = fs.readdirSync(c.directory).filter(n => n.startsWith('state-')).length;
  await assert.rejects(c.run({discoveryOnly: true}), /DISCOVERY_ONLY_REQUIRES_PENDING_STATE/);
  assert.equal(fs.readdirSync(c.directory).filter(n => n.startsWith('state-')).length, snapshotsBefore);
});

test('新候选query结果未知仍查原号；过期不换号，也不导出', async () => {
  for (const accepted of [true, false]) {
    const c = context('pending-unknown-' + accepted, new FakeTransport(), true);
    const original = c.transport.enqueue.bind(c.transport); let attempted;
    c.transport.enqueue = async request => {
      if (!attempted && request.params.command?.includes('FaultCommand query ')) {
        attempted = structuredClone(request);
        if (accepted) await original(request);
        throw new QueueError('NETWORK_UNAVAILABLE', true);
      }
      return original(request);
    };
    await c.run({limits: {maxRetries: 1}, discoveryOnly: true});
    if (accepted) {
      assert.equal((await c.run({discoveryOnly: true})).candidatesChecked, 1);
      assert.equal(c.transport.stageSends('query').length, 1);
      assert.equal((await c.state()).events[0].tasks.inspect.request.id, attempted.id);
    } else {
      c.options.clock.advance(600001);
      assert.equal((await c.run({discoveryOnly: true})).stop, 'TASK_ABSENT_AFTER_EXPIRY');
      assert.equal(c.transport.stageSends('query').length, 0);
    }
    assert.equal(c.transport.stageSends('export').length, 0);
  }
});

class CatalogTransport extends FakeTransport {
  constructor(ids, detail = id => ({schemaVersion: 1, eventId: id, state: {phase: 'CAPTURING'}})) {
    super(); this.ids = ids; this.detail = detail;
  }
  async enqueue(request) {
    const task = await super.enqueue(request);
    if (request.type !== 'root_exec') return task;
    const args = request.params.command.split('FaultCommand ')[1].split(' ');
    if (args[0] === 'pending') {
      const candidates = this.ids.filter(id => id > (args[2] || ''));
      task.result.text = JSON.stringify(pendingPage(candidates.slice(0, 16).map(eventId => ({eventId, category: 'TOMBSTONE', phase: 'PARTIAL',
        captureState: 'PARTIAL', archiveState: 'ACK_RECORDED_UNVERIFIED', exportState: 'RECEIPT_RECORDED_UNVERIFIED'})), candidates.length > 16));
    }
    if (args[0] === 'query' && args[1] !== ID) task.result.text = JSON.stringify(this.detail(args[1]));
    return task;
  }
}

test('32条未验证ACK跨8轮小预算推进到尾部，不因头部详情非终态而饥饿', async () => {
  const ids = [...Array.from({length: 31}, (_, i) => (i + 1).toString(16).padStart(64, '0')), ID];
  const c = context('catalog32', new CatalogTransport(ids), true);
  let checked = 0, deferred = 0;
  for (let round = 0; round < 8; round++) {
    c.options.store = new QueueStore(c.directory);
    const result = await c.run({limits: {maxCandidates: 4, maxIndexPages: 1}});
    assert.equal(result.blocked, false); checked += result.candidatesChecked; deferred += result.candidatesDeferred;
  }
  assert.equal(checked, 32); assert.equal(deferred, 31); assert.equal((await c.state()).events[0].phase, 'DONE');
  assert.equal(c.transport.stageSends('pending').length, 2); assert.equal(c.transport.stageSends('export').length, 1);
  for (const id of ids.slice(0, -1)) assert.equal(c.transport.stageSends('query').filter(r => r.params.command.endsWith(id)).length, 1);
});

test('128条中的127个本地DONE不占满扫描准入，尾部在第8页可达', async () => {
  const ids = [...Array.from({length: 127}, (_, i) => (i + 1).toString(16).padStart(64, '0')), ID];
  const c = context('catalog128', new CatalogTransport(ids), true);
  // 构造最小合法旧完成记录；这里只验证遍历，不把合成记录称作生产验包证明。
  await c.options.store.withLock(async () => {
    c.options.store.load(); c.options.store.save({schemaVersion: 1, discovery: 'pending16', target: TARGET, deviceId: 'SYNTHETIC_DEVICE',
      cursor: '', index: {}, events: ids.slice(0, -1).map(eventId => ({eventId, phase: 'DONE', tasks: {},
        summary: {gapCount: 1, sourceLogComplete: false}})), nextAt: 0, failures: 0});
  });
  for (let round = 0; round < 8; round++) {
    const result = await c.run({discoveryOnly: true, limits: {maxIndexPages: 1}});
    assert.equal(result.blocked, false);
    if (round < 7) assert.equal(result.candidatesChecked, 0); else assert.equal(result.candidatesChecked, 1);
  }
  assert.equal((await c.state()).events.length, 128); assert.equal(c.transport.stageSends('pending').length, 8);
  assert.equal(c.transport.stageSends('query').length, 1);
  // 128条已知记录后仍可再次完整扫描；只在真正新增第129条时限制容量。
  const next = await c.run({discoveryOnly: true}); assert.equal(next.blocked, false);
  assert.equal(c.transport.stageSends('query').length, 1); assert.equal(c.transport.stageSends('export').length, 0);
});

test('发现结束后新增的小ID在下一遍可见，不永久丢在游标之前', async () => {
  const lower = '0'.repeat(63) + '1'; const transport = new CatalogTransport([ID]);
  const c = context('new-low-id', transport, true);
  await c.run({discoveryOnly: true}); transport.ids = [lower, ID];
  const result = await c.run({discoveryOnly: true}); assert.equal(result.candidatesChecked, 1); assert.equal(result.candidatesDeferred, 1);
  assert.equal(transport.stageSends('query').filter(r => r.params.command.endsWith(lower)).length, 1);
});

test('整页已接收后预算停止，第二轮不重取页、不漏掉页内候选', async () => {
  const lower = '0'.repeat(63) + '1'; const transport = new CatalogTransport([lower, ID]);
  const c = context('page-buffer', transport, true);
  const first = await c.run({discoveryOnly: true, limits: {maxRequests: 3}});
  assert.equal(first.stop, 'ROUND_REQUEST_BUDGET');
  const state = await c.state(); assert.equal(state.page.offset, 1); assert.equal(state.page.entries.length, 2);
  const taskId = state.events[0].tasks.inspect.request.id;
  const result = await c.run({discoveryOnly: true}); assert.equal(result.candidatesChecked, 2);
  assert.ok(transport.queries.includes(taskId)); assert.equal(transport.stageSends('pending').length, 1);
  assert.equal((await c.state()).events[0].eventId, ID);
});

test('候选在详细query时已消失，本轮略过但下一遍仍重新检查', async () => {
  const id = '0'.repeat(63) + '1'; const c = context('disappeared', new CatalogTransport([id], eventId => ({eventId, state: 'NOT_FOUND'})), true);
  assert.equal((await c.run({discoveryOnly: true})).candidatesDeferred, 1);
  assert.equal((await c.state()).events.length, 0);
  assert.equal((await c.run({discoveryOnly: true})).candidatesDeferred, 1);
  assert.equal(c.transport.stageSends('query').length, 2); assert.equal(c.transport.stageSends('export').length, 0);
});

test('pending重复路径身份、倒序、游标失配或非元数据合同拒绝', async () => {
  const changes = [p => p.events.push({...p.events[0]}), p => { p.nextAfter = 'b'.repeat(64); },
    p => { p.events.unshift({...p.events[0], eventId: 'b'.repeat(64)}); }, p => { p.verificationScope = 'VERIFIED'; }];
  for (let i = 0; i < changes.length; i++) {
    const c = context('bad-pending-' + i, new FakeTransport(), true); const original = c.transport.enqueue.bind(c.transport);
    c.transport.enqueue = async request => {
      const task = await original(request);
      if (request.params.command?.includes('FaultCommand pending ')) { const value = JSON.parse(task.result.text); changes[i](value); task.result.text = JSON.stringify(value); }
      return task;
    };
    assert.equal((await c.run({discoveryOnly: true})).blocked, true);
    assert.equal(c.transport.stageSends('query').length, 0); assert.equal(c.transport.stageSends('export').length, 0);
  }
});

test('详细query的另一事件身份不能授权本候选导出', async () => {
  const c = context('wrong-detail', new FakeTransport(), true); const original = c.transport.enqueue.bind(c.transport);
  c.transport.enqueue = async request => {
    const task = await original(request);
    if (request.params.command?.includes('FaultCommand query ')) task.result.text = JSON.stringify({schemaVersion: 1, eventId: 'b'.repeat(64), state: {phase: 'COMPLETE'}});
    return task;
  };
  assert.equal((await c.run()).stop, 'CANDIDATE_IDENTITY_MISMATCH'); assert.equal(c.transport.stageSends('export').length, 0);
});

test('发现模式遇到既有导出未决任务时不发其它命令', async () => {
  const c = context('discovery-active-transfer', new FakeTransport(), true);
  await assert.rejects(c.run({checkpoint: label => { if (label === 'intent:export') throw CRASH; }}));
  const before = c.transport.sends.length;
  assert.equal((await c.run({discoveryOnly: true})).stop, 'DISCOVERY_TRANSFER_PENDING');
  assert.equal(c.transport.sends.length, before);
});

test('生产建议4请求停止再同目录1事件恢复，沿用原pending任务', async () => {
  const c = context('budget4-resume1', new FakeTransport(), true);
  const first = await c.run({limits: {maxRequests: 4, maxEvents: 1}});
  assert.equal(first.stop, 'ROUND_REQUEST_BUDGET'); assert.equal(first.events, 0);
  assert.equal(c.transport.stageSends('export').length, 0);
  const firstId = c.transport.stageSends('pending')[0].id;
  const candidateId = (await c.state()).events[0].tasks.inspect.request.id;
  const second = await c.run({limits: {maxEvents: 1, maxCandidates: 4, maxIndexPages: 2, maxRequests: 96, maxMs: 180000, maxBytes: 8388608}});
  assert.equal(second.stop, 'ROUND_EVENT_BUDGET'); assert.equal(second.events, 1);
  assert.equal(c.transport.stageSends('pending').length, 1); assert.equal(c.transport.stageSends('pending')[0].id, firstId);
  assert.equal((await c.state()).events[0].tasks.inspect.request.id, candidateId);
});

test('跨客户端版本或活动APK变化不改写旧意图且不发网络请求', async () => {
  const c = context('no-retarget', new FakeTransport(), true);
  await assert.rejects(c.run({checkpoint: label => { if (label === 'intent:index') throw CRASH; }}));
  const originalState = JSON.stringify(await c.state());
  c.transport.resolveTarget = async () => { throw Error('不应联网'); };
  for (const target of [{...TARGET, expectedVersion: 'new'}, {...TARGET, activeApk: TARGET.activeApk.replace('c'.repeat(64), 'd'.repeat(64))}]) {
    await assert.rejects(c.run({target}), /STATE_TARGET_MISMATCH/);
    assert.equal(JSON.stringify(await c.state()), originalState);
  }
});

test('pending原正文超过8000字节即使只有额外空白也拒绝', async () => {
  const c = context('pending-byte-limit', new FakeTransport(), true); const original = c.transport.enqueue.bind(c.transport);
  c.transport.enqueue = async request => {
    const task = await original(request);
    if (request.params.command?.includes('FaultCommand pending ')) task.result.text += ' '.repeat(8001 - Buffer.byteLength(task.result.text));
    return task;
  };
  assert.equal((await c.run({discoveryOnly: true})).stop, 'PENDING_INVALID');
  assert.equal(c.transport.stageSends('query').length, 0);
});

test('默认真实下载验包及状态持久后才发送回执，绝不自动归档', async () => {
  const c = context('delivered-default'); delete c.options.archive;
  const original = c.transport.received.bind(c.transport);
  c.transport.received = async (...args) => {
    const s = c.options.store.load(), event = s.events[0];
    assert.equal(event.phase, 'ARCHIVE'); assert.equal(event.summary.hostPackageVerified, true);
    assert.equal(event.delivery.state, 'PENDING'); assert.equal(event.delivery.attempts, 1);
    assert.ok(c.options.store.bundleMatches(event.bundle, event.receipt));
    assert.equal(c.options.store.readJson(event.verified).state, 'HOST_PACKAGE_VERIFIED');
    return original(...args);
  };
  const result = await c.run({limits: {maxEvents: 1}});
  assert.equal(result.preserved, 1); assert.equal(result.delivered, 1); assert.equal(result.deliveryPending, 0);
  assert.equal(result.awaitingArchive, 1); assert.equal(result.archived, 0); assert.equal(result.blocked, false);
  assert.equal(c.transport.stageSends('archive').length, 0);
  assert.equal(c.transport.downloads, 1);
  assert.equal((await c.state()).events[0].delivery.state, 'DELIVERED');
  await c.run({archive: true});
  assert.equal(c.transport.stageSends('archive').length, 1); assert.equal(c.transport.deliveries.length, 1);
});

for (const code of ['NETWORK_UNAVAILABLE', 'SESSION_REJECTED', 'HTTP_409', 'HTTP_503', 'RETURN_RECEIPT_RESPONSE_INVALID']) {
  test(`回执失败${code}只记待重试，本机仍已保全且不归档`, async () => {
    const c = context('delivery-failure-' + code); delete c.options.archive;
    let attempts = 0;
    c.transport.received = async () => { attempts++; throw new QueueError(code, true); };
    const first = await c.run({limits: {maxEvents: 1}});
    assert.equal(first.blocked, false); assert.equal(first.preserved, 1); assert.equal(first.deliveryPending, 1);
    const before = (await c.state()).events[0];
    assert.equal(before.summary.hostPackageVerified, true); assert.equal(before.delivery.lastError.code, code);
    c.options.clock.advance(20000);
    c.options.store = new QueueStore(c.directory);
    c.transport.received = async (device, task, data) => {
      attempts++; assert.equal(task, before.tasks.getFile.request.id);
      assert.deepEqual(data, {size: before.receipt.bytes, sha256: before.receipt.sha256});
      return {ok: true, cleanup_pending: true};
    };
    c.transport.metadata = async () => { throw Error('重试不得依赖已删的云副本'); };
    const second = await c.run();
    assert.equal(second.delivered, 1); assert.equal(second.archived, 0); assert.equal(second.blocked, false);
    const event = (await c.state()).events[0];
    assert.equal(event.delivery.cleanupPending, true); assert.equal(event.delivery.purged, false);
    assert.equal(attempts, 2); assert.equal(c.transport.downloads, 1); assert.equal(c.transport.stageSends('archive').length, 0);
  });
}

for (const point of ['host-verified', 'delivery-intent', 'after-delivery-send', 'delivery-accepted']) {
  test(`回执崩溃窗口${point}从本机恢复且只重用原回执`, async () => {
    const c = context('delivery-crash-' + point); delete c.options.archive;
    await assert.rejects(c.run({checkpoint: label => { if (label === point) throw CRASH; }}), error => error === CRASH);
    const before = (await c.state()).events[0];
    c.options.clock.advance(20000); c.options.store = new QueueStore(c.directory);
    c.transport.metadata = async () => { throw Error('云副本可能已删'); };
    const result = await c.run();
    assert.equal(result.delivered, 1); assert.equal(result.archived, 0); assert.equal(c.transport.downloads, 1);
    for (const delivery of c.transport.deliveries) assert.equal(delivery.task, before.tasks.getFile.request.id);
    assert.equal(c.transport.deliveries.length, point === 'after-delivery-send' ? 2 : 1);
    assert.equal(c.transport.stageSends('archive').length, 0);
  });
}

test('验包失败、持久化失败或回执重试前原件损坏均不得发送回执', async () => {
  const bad = context('delivery-bad-package', new FakeTransport(BAD_FIXTURE)); delete bad.options.archive;
  assert.equal((await bad.run()).blocked, true); assert.equal(bad.transport.deliveries?.length || 0, 0);
  const failedSave = context('delivery-failed-state-save'); delete failedSave.options.archive;
  const save = failedSave.options.store.save.bind(failedSave.options.store);
  failedSave.options.store.save = s => { if (s.events[0]?.phase === 'ARCHIVE') throw CRASH; return save(s); };
  await assert.rejects(failedSave.run(), error => error === CRASH);
  assert.equal(failedSave.transport.deliveries?.length || 0, 0);
  const tampered = context('delivery-tampered-retry'); delete tampered.options.archive;
  tampered.transport.received = async () => { throw new QueueError('NETWORK_UNAVAILABLE', true); };
  await tampered.run({limits: {maxEvents: 1}});
  const event = (await tampered.state()).events[0];
  fs.writeFileSync(path.join(tampered.directory, event.bundle), 'synthetic corruption');
  tampered.options.clock.advance(20000);
  let posts = 0; tampered.transport.received = async () => { posts++; return {ok: true, purged: true}; };
  assert.equal((await tampered.run()).stop, 'LOCAL_PACKAGE_MISMATCH'); assert.equal(posts, 0);
});

test('回执遵守服务端退避，重新运行不紧密重试', async () => {
  const c = context('delivery-backoff'); delete c.options.archive;
  const until = c.options.clock.now() + 60000; let attempts = 0;
  c.transport.received = async () => { attempts++; const error = new QueueError('HTTP_429', true); error.retryAt = until; throw error; };
  await c.run({limits: {maxEvents: 1}});
  assert.equal((await c.state()).events[0].delivery.nextAt, until);
  await c.run(); assert.equal(attempts, 1);
  c.options.clock.advance(60000); await c.run(); assert.equal(attempts, 2);
});

test('回执Web适配器使用管理员会话、原任务、固定端点与严格成功合同', async () => {
  let response = {ok: true, purged: true}, request;
  const web = new WebTransport({session: SESSION, fetchImpl: async (url, options) => {
    request = {url, options}; return Response.json(response);
  }});
  const proof = {size: 123, sha256: 'a'.repeat(64)}, options = {signal: new AbortController().signal};
  await web.received('device', 'task', proof, options);
  assert.equal(request.url, 'https://v.elfradio.net/api/elfremote/file-return/received?device_id=device&task_id=task');
  assert.equal(request.options.method, 'POST'); assert.equal(request.options.redirect, 'error');
  assert.ok(request.options.headers.Cookie); assert.equal(request.options.headers.Authorization, undefined);
  assert.deepEqual(JSON.parse(request.options.body), proof);
  for (response of [{ok: true}, {ok: false, purged: true}, {ok: true, purged: true, cleanup_pending: true}])
    await assert.rejects(web.received('device', 'task', proof, options));
  response = {ok: true, cleanup_pending: true}; assert.equal((await web.received('device', 'task', proof, options)).ok, true);
});

test('只重试回执不依赖设备在线、客户端版本或云端下载副本', async () => {
  const c = context('receipt-only'); delete c.options.archive;
  c.transport.received = async () => { throw new QueueError('HTTP_503', true); };
  await c.run({limits: {maxEvents: 1}}); c.options.clock.advance(20000);
  c.transport.resolveTarget = c.transport.queryTask = c.transport.enqueue = c.transport.metadata = c.transport.download = async () => {
    throw Error('只重试回执不得读取设备或重新派发任务');
  };
  let calls = 0; c.transport.received = async () => { calls++; return {ok: true, purged: true}; };
  const result = await c.run({receiptsOnly: true});
  assert.equal(result.delivered, 1); assert.equal(result.requests, 1); assert.equal(result.blocked, false);
  assert.equal(result.archived, 0); assert.equal(calls, 1);
});

test('真实流式下载适配器与逐项验包器完成持久保全后才调用Web回执', async () => {
  const c = context('receipt-real-stream'); delete c.options.archive;
  const observed = [];
  const web = new WebTransport({session: SESSION, fetchImpl: async (url, options) => {
    if (url.includes('/file-return/received?')) {
      const event = c.options.store.load().events[0];
      assert.equal(event.phase, 'ARCHIVE'); assert.equal(event.delivery.state, 'PENDING');
      assert.equal(c.options.store.readJson(event.verified).state, 'HOST_PACKAGE_VERIFIED');
      assert.equal(hash(fs.readFileSync(c.options.store.file(event.bundle))), FIXTURE.receipt.sha256);
      assert.deepEqual(JSON.parse(options.body), {size: FIXTURE.receipt.bytes, sha256: FIXTURE.receipt.sha256});
      observed.push('received'); return Response.json({ok: true, purged: true});
    }
    assert.ok(url.endsWith('&download=1')); observed.push('download');
    return new Response(FIXTURE.bytes, {headers: {'content-length': String(FIXTURE.bytes.length)}});
  }});
  c.transport.download = web.download.bind(web); c.transport.received = web.received.bind(web);
  const result = await c.run({limits: {maxEvents: 1}});
  assert.deepEqual(observed, ['download', 'received']); assert.equal(result.delivered, 1);
  assert.equal(result.preserved, 1); assert.equal(result.archived, 0);
});

console.log('离线合成证据目录：' + RUN);
