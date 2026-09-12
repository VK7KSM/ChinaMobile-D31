import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';
import {QueueStore, QueueError} from './fault-transfer/QueueStore.mjs';
import {runNetworkWeb, NetworkWebTransport, publicSummary, main} from './Verify-RemoteNetworkConfirmationWeb.mjs';
import {frozenWeb} from './fixtures/network-confirmation-v1.mjs';

const controlPlaneUrl = new URL('../../FreePBX_VPNnode_Web/elfRemote/control-plane.js', import.meta.url);
const confirmationUrl = new URL('../../FreePBX_VPNnode_Web/network-confirmation.js', import.meta.url);
const hasWeb = fs.existsSync(controlPlaneUrl) && fs.existsSync(confirmationUrl);
// 缺源码仅跳过末尾交叉测试；源码存在但导入失败必须报错，不能伪装成可选缺失。
const actualWeb = hasWeb ? {...await import(controlPlaneUrl.href), ...await import(confirmationUrl.href)} : null;

const APK = 'a'.repeat(64), BOOT = '00000000-0000-4000-8000-000000000001';
const NONCE = '00000000-0000-4000-8000-000000000002', COOKIE = 'private-fixture-cookie';
const target = {deviceName: '合成D31', expectedVersion: '134-fixture-full', apkSha256: APK, value: false};
class LocalStore extends QueueStore {
  // 仅替换已由公共测试覆盖的系统锁，持久化、哈希及原子文件仍用真实QueueStore。
  async withLock(action) {
    assert.equal(this.locked, false); this.locked = true; this.lockAlive = () => true;
    try { return await action(); } finally { this.locked = false; }
  }
}
function fixture(t, mode = 'good', value = false, web = frozenWeb) {
  const {enqueueRepairTask, applyRepairProgress, publicRepair, grantNetworkConfirmation, networkAcceptanceAllowed} = web;
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'network-web-'));
  t.after(() => fs.rmSync(directory, {recursive: true}));
  let store = new LocalStore(directory), now = 1800000000000, mono = 0, injected = false, pending = false;
  const selected = {...target, value}, posts = [], gets = [], times = [];
  const advance = ms => { now += ms; mono += ms; };
  const clock = {now: () => now, monotonic: () => mono, sleep: async ms => advance(ms)};
  const device = {id: 'synthetic-private-device', name: selected.deviceName, model_id: 'mdl_d31', app_version: selected.expectedVersion,
    managed_system_settings: true, managed_network_confirmation_v1: true, network_write: false, ready: true,
    token_sha256: 'b'.repeat(64), task: null};
  const privateGate = () => JSON.stringify({device_id: mode === 'gate-wrong-device' ? 'other' : device.id,
    apk_sha256: mode === 'gate-wrong-apk' ? 'c'.repeat(64) : APK, expires_at: mode === 'gate-expired' ? now : now + 60000});
  const terminal = () => {
    const task = device.task;
    if (task.state === 'pending') {
      applyRepairProgress(device, task.id, 'claimed', '', null, now);
      applyRepairProgress(device, task.id, 'running', '', null, now);
    }
    const binding = {task_id: task.network.local_task_id, apk_sha256: APK, key: 'wifi_enabled',
      before: mode === 'unchanged' ? value : !value, target: value, boot_id: BOOT,
      started_elapsed: 100000, deadline_elapsed: 160000, window_ms: 60000};
    const result = {version: 1, status: mode === 'unchanged' ? 'UNCHANGED' : mode === 'rolled' ? 'ROLLED_BACK' : 'CONFIRMED',
      binding, observed_elapsed: 102000, current_enabled: mode === 'rolled' ? !value : value,
      cleanup_complete: true, restored: mode === 'rolled'};
    if (result.status === 'CONFIRMED') {
      grantNetworkConfirmation(device, task, {task_id: task.id, state: 'running', network_confirmation: {
        version: 1, request_digest: task.request_digest, ...binding, last_elapsed: 100500, issued_elapsed: 101000, nonce: NONCE}}, now);
      result.confirmation_nonce = NONCE;
    }
    applyRepairProgress(device, task.id, mode === 'rolled' ? 'failed' : 'success', '', {network_transaction: result}, now);
  };
  const json = (value, status = 200, headers = {}) => new Response(JSON.stringify(value), {status, headers: {'Content-Type': 'application/json', ...headers}});
  const transport = new NetworkWebTransport({session: {cookies: [{name: 'elf_admin', domain: 'v.elfradio.net', value: COOKIE}]}, now: clock.now,
    fetchImpl: async (urlText, options) => {
      const url = new URL(urlText); advance(10); times.push(now);
      assert.equal(url.origin, 'https://v.elfradio.net'); assert.equal(options.redirect, 'error');
      assert.ok(options.signal instanceof AbortSignal); assert.equal(options.headers.Cookie, 'elf_admin=' + COOKIE);
      if (mode === 'retry-preflight' && !injected) { injected = true; return json({}, 429, {'Retry-After': '10'}); }
      if (url.pathname === '/api/devices') {
        gets.push(url.pathname);
        const d = {...device, task: device.task ? publicRepair(device.task) : {id: '', type: '', state: ''}};
        delete d.token_sha256;
        if (mode === 'no-capability') d.managed_network_confirmation_v1 = false;
        if (mode === 'string-capability') d.managed_network_confirmation_v1 = 'true';
        if (mode === 'wrong-version') d.app_version = 'other';
        if (mode === 'wrong-model') d.model_id = 'mdl_d22';
        if (mode === 'no-system') d.managed_system_settings = false;
        if (mode === 'busy') d.task = {id: 'other-task', type: 'root_exec', state: 'running'};
        if (mode === 'unknown-slot') d.task = {id: 'other-task', type: 'root_exec', state: 'unknown'};
        return json({devices: mode === 'duplicate' ? [d, {...d}] : [d]});
      }
      if (url.pathname === '/api/elfremote/tasks') {
        assert.equal(options.method, 'GET'); gets.push(url.pathname + url.search);
        assert.equal(url.searchParams.get('device_id'), device.id);
        assert.equal(url.searchParams.get('task_id'), store.load().request.id);
        assert.equal([...url.searchParams].length, 2, '只能查询原任务，禁止全历史');
        if (!device.task) return json({ok: false, msg: '未找到该任务'}, 404);
        if (mode === 'retry-query' && !injected) { injected = true; return json({}, 503, {'Retry-After': '10'}); }
        if (pending && mode !== 'forever') { pending = false; terminal(); }
        return json({ok: true, task: publicRepair(device.task)});
      }
      assert.equal(url.pathname, '/api/elfremote/task'); assert.equal(options.method, 'POST');
      const request = JSON.parse(options.body), persisted = store.load();
      assert.equal(persisted.submitted, true); assert.deepEqual(persisted.request, request);
      assert.equal(posts.length, 0, '同一个capture只能提交一次');
      assert.equal(request.type, 'system_config'); assert.equal(request.params.value, value);
      assert.equal(request.params.group, 'wifi'); assert.equal(request.params.key, 'enabled');
      assert.equal(request.params.network_transaction.apk_sha256, APK);
      assert.equal(request.action, undefined); posts.push(request);
      const gate = networkAcceptanceAllowed(device, request.params, privateGate(), now);
      if (!gate) return json({ok: false}, 409);
      if (mode === 'lost-before-accept') throw Error(COOKIE);
      const accepted = await enqueueRepairTask(device, request, now, undefined, {allowNetworkAcceptance: gate});
      assert.equal(accepted.ok, true); assert.equal(device.network_write, false);
      assert.equal(device.task.request_digest, persisted.requestDigest, '必须匹配独立冻结向量或真实服务端摘要');
      pending = ['pending', 'forever', 'lost-after-accept', 'retry-post', 'retry-query'].includes(mode);
      if (!pending) terminal();
      if (mode === 'lost-after-accept') throw Error(COOKIE);
      if (mode === 'retry-post') return json({}, 503, {'Retry-After': '10'});
      return json({ok: true, task: publicRepair(device.task)}, 200, mode === 'retry-200' ? {'Retry-After': '10'} : {});
    }});
  return {store, directory, transport, device, posts, gets, times, clock, advance, target: selected,
    restart() { store = new LocalStore(directory); this.store = store; },
    invoke(extra = {}) { return runNetworkWeb({store, transport, target: selected, clock, ...extra}); }};
}

for (const value of [false, true]) test(`独立宿主冻结合同：单任务目标${value}，不改network_write`, async t => {
  const f = fixture(t, 'good', value), result = await f.invoke();
  assert.equal(result.status, 'PASSED'); assert.equal(result.code, 'CONFIRMED');
  assert.equal(result.confirmationVerified, true); assert.equal(result.target.currentEnabled, value);
  assert.equal(result.target.deviceId, f.device.id); assert.equal(result.target.taskId, f.posts[0].id);
  assert.equal(f.posts.length, 1); assert.equal(f.device.network_write, false);
  assert.equal(result.adbUsed, false); assert.equal(result.automaticResubmit, false);
  const summary = JSON.stringify(publicSummary(result));
  for (const secret of [COOKIE, APK, BOOT, NONCE, f.device.id, target.deviceName, f.posts[0].id]) assert.equal(summary.includes(secret), false);
  for (const file of fs.readdirSync(f.directory)) assert.equal(fs.readFileSync(path.join(f.directory, file), 'utf8').includes(COOKIE), false);
  for (let i = 1; i < f.times.length; i++) assert.ok(f.times[i] - f.times[i - 1] >= 2000);
  f.restart(); assert.equal((await f.invoke()).status, 'PASSED'); assert.equal(f.posts.length, 1);
});
test('UNCHANGED单独标记，不冒充管理链路确认', async t => {
  const f = fixture(t, 'unchanged'), r = await f.invoke();
  assert.equal(r.status, 'UNCHANGED'); assert.equal(r.confirmationVerified, false); assert.equal(r.unchanged, true);
});
test('paired=false只影响离线保留，在线D31正常执行单次网络确认', async t => {
  const f = fixture(t); f.device.paired = false;
  const r = await f.invoke(); assert.equal(r.status, 'PASSED'); assert.equal(r.confirmationVerified, true);
  assert.equal(f.posts.length, 1); assert.equal(f.device.paired, false); assert.equal(f.device.network_write, false);
});
test('实际回滚为失败，可按原capture再次核查但不重发', async t => {
  const f = fixture(t, 'rolled'), r = await f.invoke();
  assert.equal(r.status, 'FAILED'); assert.equal(r.code, 'ROLLED_BACK'); assert.equal(r.target.currentEnabled, true);
  f.restart(); assert.equal((await f.invoke()).status, 'FAILED'); assert.equal(f.posts.length, 1);
});
for (const mode of ['no-capability', 'string-capability', 'wrong-version', 'wrong-model', 'no-system', 'busy', 'unknown-slot', 'duplicate']) {
  test(`${mode}在POST之前拒绝`, async t => {
    const f = fixture(t, mode), result = await f.invoke(); assert.notEqual(result.status, 'PASSED'); assert.equal(f.posts.length, 0);
  });
}
for (const mode of ['gate-wrong-device', 'gate-wrong-apk', 'gate-expired']) test(`${mode}私有门拒绝后也不重试POST`, async t => {
  const f = fixture(t, mode); assert.equal((await f.invoke()).code, 'HTTP_409');
  f.advance(3000); f.restart(); assert.equal((await f.invoke()).code, 'NETWORK_WEB_SUBMISSION_UNKNOWN'); assert.equal(f.posts.length, 1);
});
for (const mode of ['lost-before-accept', 'lost-after-accept']) test(`${mode}断线重启后只查原号`, async t => {
  const f = fixture(t, mode); assert.equal((await f.invoke()).status, 'PAUSED');
  const gets = f.gets.length; f.advance(3000); f.restart();
  const r = await f.invoke(); assert.equal(r.status, mode === 'lost-after-accept' ? 'PASSED' : 'PAUSED');
  assert.equal(f.posts.length, 1); assert.ok(f.gets.slice(gets).every(g => g.startsWith('/api/elfremote/tasks?')));
});
test('submit-intent后崩溃：无论404多少次都不补交', async t => {
  const f = fixture(t);
  const r = await f.invoke({checkpoint: label => { if (label === 'submit-intent') throw new QueueError('CRASH_FIXTURE', true); }});
  assert.equal(r.status, 'PAUSED'); assert.equal(f.posts.length, 0);
  f.restart(); assert.equal((await f.invoke()).code, 'NETWORK_WEB_SUBMISSION_UNKNOWN');
  assert.equal((await f.invoke()).code, 'NETWORK_WEB_SUBMISSION_UNKNOWN'); assert.equal(f.posts.length, 0);
});
test('已提交后能力撤回、改名、版本改变不阻断原号查询', async t => {
  const f = fixture(t, 'lost-after-accept'); await f.invoke(); f.advance(3000);
  f.device.name = '后续改名'; f.device.app_version = 'changed'; f.device.managed_network_confirmation_v1 = false;
  f.restart(); assert.equal((await f.invoke()).status, 'PASSED'); assert.equal(f.posts.length, 1);
});
for (const mode of ['retry-preflight', 'retry-post', 'retry-query']) test(`${mode}尊重跨运行Retry-After，不发送额外预检`, async t => {
  const f = fixture(t, mode); assert.equal((await f.invoke()).status, 'PAUSED');
  const before = f.times.length; f.restart();
  assert.equal((await f.invoke()).code, 'NETWORK_WEB_RETRY_BACKOFF'); assert.equal(f.times.length, before);
  f.advance(9999); assert.equal((await f.invoke()).code, 'NETWORK_WEB_RETRY_BACKOFF'); assert.equal(f.times.length, before);
  f.advance(1); assert.equal((await f.invoke()).status, 'PASSED'); assert.equal(f.posts.length, 1);
});
test('成功响应的Retry-After也保存，续查不得提前请求', async t => {
  const f = fixture(t, 'retry-200'); assert.equal((await f.invoke()).status, 'PASSED');
  const count = f.times.length; f.restart(); assert.equal((await f.invoke()).code, 'NETWORK_WEB_RETRY_BACKOFF'); assert.equal(f.times.length, count);
});
test('请求总数跨capture续跑累计，不能重启绕过上限', async t => {
  const f = fixture(t, 'forever'), limits = {maxRequests: 5};
  assert.equal((await f.invoke({limits})).code, 'NETWORK_WEB_REQUEST_LIMIT');
  assert.equal(f.times.length, 5); f.restart();
  assert.equal((await f.invoke({limits})).code, 'NETWORK_WEB_REQUEST_LIMIT'); assert.equal(f.times.length, 5); assert.equal(f.posts.length, 1);
});
test('最多180秒，不用重新运行重置累计活动时间预算', async t => {
  const f = fixture(t, 'forever'), limits = {maxMs: 6000};
  assert.equal((await f.invoke({limits})).code, 'NETWORK_WEB_TIME_LIMIT');
  const count = f.times.length; f.restart(); assert.equal((await f.invoke({limits})).code, 'NETWORK_WEB_TIME_LIMIT'); assert.equal(f.times.length, count);
});
test('capture参数不可改变目标、APK或候选版本，也不创建第二任务', async t => {
  const f = fixture(t); await f.invoke(); const count = f.times.length;
  for (const changed of [{...f.target, value: true}, {...f.target, apkSha256: 'c'.repeat(64)}, {...f.target, expectedVersion: 'other'}]) {
    assert.equal((await f.invoke({target: changed})).code, 'NETWORK_WEB_CAPTURE_MISMATCH');
  }
  assert.equal(f.times.length, count); assert.equal(f.posts.length, 1);
});
test('本地时钟倒退失败关闭，不靠旧截止续期', async t => {
  const f = fixture(t, 'lost-after-accept'); await f.invoke(); const count = f.times.length;
  f.advance(-1000); assert.equal((await f.invoke()).code, 'NETWORK_WEB_CLOCK_REGRESSED'); assert.equal(f.times.length, count);
});
test('各终态错误字段均不通过；修复服务器视图后同capture可续查', async t => {
  const changes = [
    task => { task.id = 'other'; }, task => { task.type = 'root_exec'; }, task => { task.expires_at++; },
    task => { task.network.request_digest = 'c'.repeat(64); }, task => { task.network.confirmation_issued = false; },
    task => { task.result = {exit_code: 0}; },
    ...['task_id', 'apk_sha256', 'key', 'boot_id', 'started_elapsed', 'deadline_elapsed', 'window_ms', 'before', 'target'].map(key => task => {
      const b = task.network.result.binding;
      b[key] = typeof b[key] === 'number' ? b[key] + 1 : typeof b[key] === 'boolean' ? !b[key] : 'wrong';
      task.network.binding = structuredClone(b); task.result.network_transaction = structuredClone(task.network.result);
    }),
    ...[{observed_elapsed: 160000}, {observed_elapsed: 99999}, {observed_elapsed: '102000'}, {observed_elapsed: 102000.5},
      {current_enabled: true}, {current_enabled: 'false'}, {cleanup_complete: false}, {cleanup_complete: 'true'},
      {restored: true}, {confirmation_nonce: 'bad'}, {status: 'AWAITING_CONFIRM'}, {version: '1'}].map(change => task => {
      Object.assign(task.network.result, change); task.result.network_transaction = structuredClone(task.network.result);
    }),
  ];
  for (const change of changes) {
    const f = fixture(t, 'lost-after-accept'); await f.invoke(); f.advance(3000);
    const query = f.transport.queryTask.bind(f.transport);
    f.transport.queryTask = async (...args) => { const task = await query(...args); change(task); return task; };
    assert.notEqual((await f.invoke()).status, 'PASSED'); assert.equal(f.posts.length, 1);
    f.transport.queryTask = query; f.advance(3000); assert.equal((await f.invoke()).status, 'PASSED'); assert.equal(f.posts.length, 1);
  }
});
test('已经观察的运行绑定不允许终态跨boot重新绑定', async t => {
  const f = fixture(t, 'pending'), query = f.transport.queryTask.bind(f.transport); let first = true;
  f.transport.queryTask = async (...args) => {
    const task = await query(...args);
    if (task && first) { first = false; task.state = 'running'; task.network.binding.boot_id = '00000000-0000-4000-8000-000000000009'; }
    return task;
  };
  assert.equal((await f.invoke()).code, 'NETWORK_WEB_REBIND'); assert.equal(f.posts.length, 1);
});
test('非法CLI布尔参数在读取session及联网前拒绝', async () => {
  const args = ['--session', 'missing-session', '--capture', 'unused', '--device-name', 'fixture', '--expected-version', '134', '--apk-sha', APK];
  for (const value of ['TRUE', '1', 'yes', 'null']) await assert.rejects(main([...args, '--target', value]), {code: 'NETWORK_WEB_BOOLEAN_REQUIRED'});
});
test('传输忽略Abort时宿主仍有界，遗留Promise结束前禁止新请求', async t => {
  const f = fixture(t); let release, signal, calls = 0;
  f.transport.json = async (_route, _body, options) => {
    calls++; signal = options.signal;
    return await new Promise(resolve => { release = resolve; });
  };
  const start = performance.now(), limits = {requestMs: 40};
  assert.equal((await f.invoke({limits})).code, 'NETWORK_WEB_REQUEST_TIMEOUT');
  assert.ok(performance.now() - start < 1000); assert.equal(signal.aborted, true);
  f.advance(3000); f.restart();
  assert.equal((await f.invoke({limits})).code, 'NETWORK_WEB_TRANSPORT_UNSETTLED'); assert.equal(calls, 1);
  release({devices: []}); await new Promise(resolve => setImmediate(resolve));
});
test('HTTP返回后正文阻塞同样被超时取消，不输出Cookie', async t => {
  const f = fixture(t); let cancel, signal;
  f.transport.fetch = async (_url, options) => {
    signal = options.signal;
    return new Response(new ReadableStream({start(controller) {
      cancel = () => controller.error(Error(COOKIE));
      options.signal.addEventListener('abort', cancel, {once: true});
    }}), {status: 200});
  };
  const r = await f.invoke({limits: {requestMs: 40}});
  assert.equal(r.status, 'PAUSED'); assert.equal(signal.aborted, true);
  assert.equal(JSON.stringify(publicSummary(r)).includes(COOKIE), false); assert.equal(f.posts.length, 0);
  await new Promise(resolve => setImmediate(resolve));
});
test('提交意图落盘耗尽总预算时不再写出POST，续跑仍不得重发', async t => {
  const f = fixture(t), limits = {maxMs: 5000};
  const r = await f.invoke({limits, checkpoint: label => { if (label === 'submit-intent') f.advance(6000); }});
  assert.equal(r.code, 'NETWORK_WEB_TIME_LIMIT'); assert.equal(f.posts.length, 0);
  f.restart(); assert.equal((await f.invoke({limits})).code, 'NETWORK_WEB_TIME_LIMIT'); assert.equal(f.posts.length, 0);
});
test('默认原任务严格不超过100请求和180秒，参数不能提高上限', async t => {
  const f = fixture(t, 'forever');
  const r = await f.invoke(); assert.ok(r.requests <= 100); assert.ok(f.clock.monotonic() <= 180000);
  assert.equal(r.code, 'NETWORK_WEB_TIME_LIMIT'); assert.equal(f.posts.length, 1);
  for (const limits of [{maxRequests: 101}, {maxMs: 180001}, {pollMs: 999}, {requestMs: 10001}]) {
    await assert.rejects(f.invoke({limits}), {code: 'NETWORK_WEB_LIMIT_INVALID'});
  }
});

for (const [mode, value, paired] of [['good', false, true], ['good', true, true], ['good', false, false],
  ['unchanged', false, true], ['rolled', false, true], ['gate-wrong-device', false, true],
  ['gate-wrong-apk', false, true], ['gate-expired', false, true]]) {
  test(`可选Web源码交叉：${mode}，target=${value}，paired=${paired}`,
    {skip: hasWeb ? false : '独立checkout没有Web源码，宿主35项仍完整运行'}, async t => {
      const live = fixture(t, mode, value, actualWeb), frozen = fixture(t, mode, value);
      live.device.paired = paired; frozen.device.paired = paired;
      const actual = await live.invoke(), expected = await frozen.invoke();
      assert.deepEqual(publicSummary(actual), publicSummary(expected));
      assert.equal(actual.target.requestDigest, expected.target.requestDigest);
      assert.equal(live.posts.length, 1); assert.equal(live.device.network_write, false);
      if (mode.startsWith('gate-')) {
        assert.equal(actual.code, 'HTTP_409'); assert.equal(live.device.task, null);
        return;
      }
      assert.equal(actual.status, mode === 'unchanged' ? 'UNCHANGED' : mode === 'rolled' ? 'FAILED' : 'PASSED');
      // 随机原号各不相同，只归一化由原号派生的本地摘要，再比较冻结合同公开字段。
      const view = (task, web) => {
        const p = structuredClone(web.publicRepair(task));
        delete p.network.binding.task_id; delete p.network.result.binding.task_id;
        return {type: p.type, state: p.state, expires_at: p.expires_at, network: p.network};
      };
      assert.deepEqual(view(live.device.task, actualWeb), view(frozen.device.task, frozenWeb));
    });
}
