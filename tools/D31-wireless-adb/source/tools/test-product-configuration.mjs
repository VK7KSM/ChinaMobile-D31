import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {hash} from './fault-transfer/QueueStore.mjs';
import {collect, validateBaseline, validateParent, validateReport} from './Collect-ProductConfiguration.mjs';

const digest = 'a'.repeat(64);
const options = {deviceId: 'synthetic-device', deviceName: 'synthetic-name', version: 186,
  versionName: '1.34.14-candidate', apkSha256: digest};
const active = {versionCode: 186, sha256: digest, path: '/data/local/d31-remote/releases/' + digest + '/remote.apk'};
const baseline = 'synthetic/build\n00000000-0000-0000-0000-000000000000\n' + JSON.stringify(active);
const report = Buffer.from(JSON.stringify({operation: 'runtime_inventory', active: {state: 'OBSERVED', metadata: active},
  productConfiguration: {catalog: 'd31-product-runtime-1', facts: [], observed: 0, total: 0}}));
const fileResult = {action: 'uploaded', bytes: report.length, sha256: hash(report)};
const root = text => ({action: 'completed', text, exit_code: 0, truncated: false});

test('父任务拒绝失败、截断、错号、错设备和参数变化', () => {
  const request = {id: 'task', device_id: 'synthetic-device', type: 'root_exec', params: {command: 'fixture'}};
  const good = {id: request.id, device_id: request.device_id, type: request.type,
    state: 'success', params: request.params, result: root('done')};
  assert.equal(validateParent(good, request).text, 'done');
  for (const changed of [{id: 'other'}, {device_id: 'other'}, {type: 'get_file'}, {state: 'failed'},
    {params: {command: 'other'}}, {result: {...good.result, truncated: true}},
    {result: {...good.result, exit_code: 1}}, {result: {...good.result, action: 'pending'}}])
    assert.throws(() => validateParent({...good, ...changed}, request));
});

test('身份绑定校验实际版本、摘要、路径及格式', () => {
  assert.equal(validateBaseline(baseline, options).version, 186);
  for (const text of [baseline.replace('186', '184'), baseline.replace('synthetic/build', ''),
    baseline.replace('/remote.apk', '/other.apk'), baseline.replace(digest, 'b'.repeat(64)),
    baseline.replace('00000000-0000-0000-0000-000000000000', 'unknown')])
    assert.throws(() => validateBaseline(text, options));
});

test('回传需同时匹配诊断回执和文件父任务', () => {
  assert.equal(validateReport(report, fileResult, fileResult).operation, 'runtime_inventory');
  assert.throws(() => validateReport(report, {...fileResult, bytes: report.length + 1}, fileResult));
  assert.throws(() => validateReport(report, fileResult, {...fileResult, sha256: 'b'.repeat(64)}));
});

function transport({loseFirstResponse = false, corruptDownload = false, cleanupPending = false} = {}) {
  const tasks = new Map(); let lost = false, enqueued = 0, received = 0;
  return {
    get counts() { return {enqueued, received}; },
    async json() { return {devices: [{id: options.deviceId, name: options.deviceName,
      model_id: 'mdl_d31', ready: true, app_version: options.versionName}]}; },
    async queryTask(deviceId, id) { assert.equal(deviceId, options.deviceId); return tasks.get(id) || null; },
    async enqueue(request) {
      enqueued++; assert.equal(tasks.has(request.id), false);
      let result = fileResult;
      if (request.type === 'root_exec') {
        const id = request.params.command.match(/RemoteDiagnosticCommand '([a-f0-9]{64})'/)?.[1];
        result = root(id ? JSON.stringify({id, state: 'completed',
          path: '/data/local/d31-remote/diagnostics/' + id + '/report.json',
          bytes: report.length, sha256: hash(report)}) : baseline);
      }
      const task = {...request, state: 'success', result}; tasks.set(request.id, task);
      if (loseFirstResponse && !lost) { lost = true; throw Error('SIMULATED_RESPONSE_LOST'); }
      return task;
    },
    async download(deviceId, id, args) {
      assert.equal(tasks.get(id).type, 'get_file');
      fs.writeFileSync(args.filename, corruptDownload ? Buffer.from('corrupt') : report, {flag: 'wx'});
    },
    async received() { received++; return cleanupPending ? {ok: true, cleanup_pending: true} : {ok: true, purged: true}; }
  };
}

async function fixture(t, config = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'd31-product-test-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  const opts = {...options, state: path.join(directory, 'state'), execute: true};
  const access = transport(config);
  return {opts, access, run: () => collect(opts, {transport: access, wait: async () => {}})};
}

test('本地预览不读会话、不创建状态或设备任务', async () => {
  const r = await collect({...options, state: 'unused', session: 'missing'});
  assert.equal(r.status, 'LOCAL_PREVIEW'); assert.equal(r.deviceActions, 0);
});
test('四个原任务、回读保全后清理，续作不重采', async t => {
  const f = await fixture(t);
  assert.equal((await f.run()).status, 'CAPTURE_VERIFIED');
  assert.deepEqual(f.access.counts, {enqueued: 4, received: 1});
  assert.equal((await f.run()).repairExecuted, false);
  assert.deepEqual(f.access.counts, {enqueued: 4, received: 1});
});
test('入队响应丢失后查询原号，不重复创建任务', async t => {
  const f = await fixture(t, {loseFirstResponse: true});
  await assert.rejects(f.run(), /SIMULATED_RESPONSE_LOST/);
  assert.equal((await f.run()).status, 'CAPTURE_VERIFIED');
  assert.equal(f.access.counts.enqueued, 4);
});
test('下载损坏不清理服务器原件', async t => {
  const f = await fixture(t, {corruptDownload: true});
  await assert.rejects(f.run(), /REPORT_DIGEST_MISMATCH/);
  assert.equal(f.access.counts.received, 0);
});
test('待清理如实保留，不宣称全系统一致', async t => {
  const f = await fixture(t, {cleanupPending: true});
  const r = await f.run(); assert.equal(r.cleanupPending, true);
  assert.equal(r.systemConsistency, 'NOT_ASSESSED');
});
