import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {hash, QueueStore} from './fault-transfer/QueueStore.mjs';
import {RecoverableTransport} from './network/RecoverableTransport.mjs';
import {collect, validateOptions, parseInventoryReceipt} from './Collect-SystemInventory.mjs';

const digest = 'a'.repeat(64);
const options = {deviceId: 'synthetic', deviceName: 'synthetic', version: 192, versionName: 'synthetic',
  apkSha256: digest, roots: ['/system'], state: 'unused'};

test('只预览无任务，范围不能交叠或进入个人数据', async () => {
  assert.equal((await collect(options)).deviceActions, 0);
  for (const roots of [['/data'], ['/system', '/system/bin'], ['/system/../data'], ['/system//bin'], ['/system/']])
    assert.throws(() => validateOptions({...options, roots}));
  await assert.rejects(collect({...options, execute: 'true'}), /EXECUTE_FLAG_INVALID/);
});

function fixture(t, mode = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'd31-inventory-'));
  t.after(() => fs.rmSync(dir, {recursive: true, force: true}));
  const tasks = new Map(), counts = {enqueue: 0, received: 0}, ranges = [];
  const bytes = Buffer.from('{"event":{"example":true},"sha256":"synthetic"}');
  let session, time = Date.now();
  const transport = {
    async json() { return {devices: [{id: 'synthetic', name: 'synthetic', model_id: 'mdl_d31', ready: true, app_version: 'synthetic'}]}; },
    async queryTask(device, id) { return tasks.get(id) || null; },
    async enqueue(request) {
      counts.enqueue++;
      let result;
      if (request.type === 'get_file') result = {action: 'uploaded', bytes: bytes.length, sha256: hash(bytes)};
      else {
        const command = request.params.command;
        let text;
        if (command.includes('getprop')) text = 'synthetic/build\n00000000-0000-0000-0000-000000000000\n' + JSON.stringify({versionCode: 192, sha256: digest,
          path: '/data/local/d31-remote/releases/' + digest + '/remote.apk'});
        else {
          session = command.match(/RemoteSystemInventoryCommand '([a-f0-9]{64})'/)[1];
          const init = command.includes('"operation":"init"');
          const args = JSON.parse(command.match(/ '(\{.*\})'$/)[1]);
          text = JSON.stringify({schemaVersion: 1, state: 'completed', session, nextSequence: init ? 0 : 1,
            initialCheckpoint: {config: {identity: args.identity, roots: args.roots,
              sessionBinding: digest + '-00000000-0000-0000-0000-000000000000'}, receipts: []},
            summary: {runnable: init, inventory: init ? 'INCOMPLETE' : 'CLOSED_WITHIN_PLAN'},
            ...(init ? {} : {path: '/data/local/d31-remote/system-inventory/' + session + '/event-00000.json', bytes: bytes.length, sha256: hash(bytes)})});
          if (mode.stdout && (mode.stdoutStage || 'init') === (init ? 'init' : 'step')) text = mode.stdout(text);
        }
        result = {action: 'completed', exit_code: 0, truncated: false, text};
      }
      const task = {...request, state: 'success', result}; tasks.set(request.id, task); return task;
    },
    async metadata(device, task) { return {file: {state: 'ready', device_id: device, task_id: task, sha256: hash(bytes), size: bytes.length}}; },
    async downloadResume(device, task, args) {
      // 使用真实Range续传及摘要校验，模拟只负责HTTP响应。
      return RecoverableTransport.prototype.downloadResume.call({base: 'https://fixture.invalid', fileRoute: () => '/file',
        async fetch(url, request) {
          const range = request.headers.Range || null; ranges.push(range);
          const start = range ? Number(range.match(/^bytes=(\d+)-$/)[1]) : 0;
          const data = mode.corrupt ? Buffer.alloc(bytes.length, 120) : bytes;
          let body = data.subarray(start);
          if (mode.interruptDownload) {
            mode.interruptDownload = false; let pulls = 0;
            body = new ReadableStream({pull(controller) {
              if (pulls++ === 0) controller.enqueue(data.subarray(start, start + 8));
              else controller.error(Error('合成中断'));
            }}, {highWaterMark: 0});
          }
          return new Response(body, {status: range ? 206 : 200, headers: {'content-length': String(bytes.length - start),
            ...(range ? {'content-range': `bytes ${start}-${bytes.length - 1}/${bytes.length}`} : {})}});
        }
      }, device, task, args);
    },
    async received(device, task, receipt) {
      counts.received++;
      assert.deepEqual(receipt, {size: bytes.length, sha256: hash(bytes)});
      assert.ok(fs.existsSync(path.join(dir, 'state/batch-00000.json')));
      const snapshots = fs.readdirSync(path.join(dir, 'state')).filter(n => /^state-\d{6}\.json$/.test(n)).sort();
      const saved = JSON.parse(fs.readFileSync(path.join(dir, 'state', snapshots.at(-1)))).state;
      assert.equal(saved.next, 1); assert.equal(saved.events.length, 1);
      if (mode.loseReceived) { mode.loseReceived = false; throw Error('LOST_RECEIVED'); }
      return {ok: true, purged: true};
    }
  };
  const run = () => collect({...options, state: path.join(dir, 'state'), execute: true}, {transport,
    now: () => time, wait: async ms => { time += ms; }});
  return {dir, counts, run, ranges, advance: () => { time += 3000; }};
}

test('实际持久队列收集后续作不重发，先保全后清理', async t => {
  const f = fixture(t);
  assert.equal((await f.run()).status, 'COLLECTED');
  assert.equal(f.counts.enqueue, 5);
  await f.run();
  assert.deepEqual(f.counts, {enqueue: 5, received: 1});
});

test('接收回执丢失后沿原任务续作，不重复扫描', async t => {
  const f = fixture(t, {loseReceived: true});
  await assert.rejects(f.run(), /LOST_RECEIVED/);
  assert.equal((await f.run()).status, 'COLLECTED');
  assert.deepEqual(f.counts, {enqueue: 5, received: 2});
});

test('取回字节损坏时不确认清理', async t => {
  const f = fixture(t, {corrupt: true});
  await assert.rejects(f.run(), /REMOTE_TASK_NEEDS_ATTENTION/);
  assert.equal(f.counts.received, 0);
});

test('真实下载中断保留part并沿同get_file发送Range续传', async t => {
  const f = fixture(t, {interruptDownload: true});
  assert.equal((await f.run()).status, 'PENDING');
  const sent = f.counts.enqueue; assert.equal(f.counts.received, 0); f.advance();
  assert.equal((await f.run()).status, 'COLLECTED');
  assert.deepEqual(f.ranges, [null, 'bytes=8-']);
  assert.equal(f.counts.enqueue, sent + 1); // 唯一新任务是最终身份回读。
});

test('received成功后队列保存失败，续作复用确认原件不重复确认或下载', async t => {
  const f = fixture(t); const save = QueueStore.prototype.save; let failed = false;
  QueueStore.prototype.save = function(state) {
    if (!failed && state.kind === 'system-inventory-v1' && state.events[0]?.acknowledgment) {
      failed = true; throw Error('合成确认后保存失败');
    }
    return save.call(this, state);
  };
  t.after(() => { QueueStore.prototype.save = save; });
  await assert.rejects(f.run(), /合成确认后保存失败/);
  assert.ok(fs.existsSync(path.join(f.dir, 'state/batch-00000-received.json')));
  assert.equal((await f.run()).status, 'COLLECTED');
  assert.deepEqual(f.counts, {enqueue: 5, received: 1}); assert.deepEqual(f.ranges, [null]);
  const receipt = JSON.parse(fs.readFileSync(path.join(f.dir, 'state/inventory-receipt.json')));
  assert.equal(receipt.events.length, 1);
});

test('确认原件写入失败后只重复幂等received，不重采或下载', async t => {
  const f = fixture(t); const write = QueueStore.prototype.writeNew; let failed = false;
  QueueStore.prototype.writeNew = function(name, data, committed) {
    if (!failed && committed === 'batch-00000-received.json') { failed = true; throw Error('合成确认原件写入失败'); }
    return write.call(this, name, data, committed);
  };
  t.after(() => { QueueStore.prototype.writeNew = write; });
  await assert.rejects(f.run(), /合成确认原件写入失败/);
  assert.equal((await f.run()).status, 'COLLECTED');
  assert.deepEqual(f.counts, {enqueue: 5, received: 2}); assert.deepEqual(f.ranges, [null]);
});

test('下载完成后主队列保存失败，续作复用子队列原件', async t => {
  const f = fixture(t); const save = QueueStore.prototype.save; let failed = false;
  QueueStore.prototype.save = function(state) {
    if (!failed && state.kind === 'system-inventory-v1' && state.steps['batch-00000-file']?.download) {
      failed = true; throw Error('合成下载后保存失败');
    }
    return save.call(this, state);
  };
  t.after(() => { QueueStore.prototype.save = save; });
  await assert.rejects(f.run(), /合成下载后保存失败/);
  assert.equal((await f.run()).status, 'COLLECTED');
  assert.deepEqual(f.counts, {enqueue: 5, received: 1}); assert.deepEqual(f.ranges, [null]);
});

test('严格stdout拒绝重复键、异常前后缀、不安全整数及错误状态类型', () => {
  const good = JSON.stringify({schemaVersion: 1, state: 'completed', session: digest, nextSequence: 0,
    summary: {runnable: true, inventory: 'INCOMPLETE'}});
  assert.equal(parseInventoryReceipt(' \n' + good + '\n').nextSequence, 0);
  for (const text of [good.replace('"state":', '"state":"failed","state":'),
    good.replace('"nextSequence":0', '"nextSequence":9007199254740993'),
    good.replace('"nextSequence":0', '"nextSequence":0.0'),
    good.replace('"runnable":true', '"runnable":"true"'), good.replace('INCOMPLETE', 'CLOSED_WITHIN_PLAN'),
    '错误\n' + good, good + '\n错误', good + good, 'null', '[]']) assert.throws(() => parseInventoryReceipt(text));
});

for (const stdoutStage of ['init', 'step']) test(stdoutStage + '异常stdout不进入下载或确认', async t => {
  const f = fixture(t, {stdoutStage, stdout: text => text + '\nException: synthetic'});
  await assert.rejects(f.run(), /INVENTORY_STDOUT_JSON/);
  assert.equal(f.counts.received, 0); assert.deepEqual(f.ranges, []);
});

test('init检查点必须绑定实际启动身份和请求范围', async t => {
  const f = fixture(t, {stdout: text => {
    const receipt = JSON.parse(text); receipt.initialCheckpoint.config.sessionBinding = 'other'; return JSON.stringify(receipt);
  }});
  await assert.rejects(f.run(), /INITIAL_CHECKPOINT_INVALID/);
  assert.equal(f.counts.received, 0); assert.deepEqual(f.ranges, []);
});

test('确认后独立原件被篡改，续作不得再次报告完成', async t => {
  const f = fixture(t); await f.run();
  fs.writeFileSync(path.join(f.dir, 'state/batch-00000.json'), 'changed');
  await assert.rejects(f.run(), /LOCAL_BATCH_CHANGED/);
  assert.equal(f.counts.received, 1);
});
