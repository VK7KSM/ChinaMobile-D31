import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
import {QueueStore, QueueError} from './fault-transfer/QueueStore.mjs';
import {runContactsManagementWeb, ContactsManagementTransport, main} from './Verify-RemoteContactsManagementWeb.mjs';
import * as contract from './contracts/contacts-page-v1.mjs';

const SNAP = '00000000-0000-0000-0000-000000000001';
const PRIVATE = 'SYNTHETIC_PRIVATE_CONTACT', COOKIE = 'SYNTHETIC_COOKIE_ONLY';
const target = {deviceName: 'SYNTHETIC_D31', expectedVersion: '1.28.3-candidate'};
const common = action => ({schema_version: 1, action, ok: true, read_only: true, source: 'nexui_messenger', contact_type: 'LOCAL'});
function descriptor(count) {
  return {...common('open'), owner_package: 'com.starnet.dial', snapshot_id: SNAP, status: 'COMPLETED', sampled_at_ms: 1,
    record_count: count, end_observed: true, list_complete: true, contact_values_emitted: false,
    completion_scope: 'SELECTED_SOURCE_ALL_CONTACTS_REPLY', all_sources_complete: false,
    snapshot_consistency: 'NOT_PROVIDED_BY_VENDOR', retention_ms: 120000, max_page_records: 32,
    page_consistency: 'IMMUTABLE_RECEIVED_REPLY', storage: 'APP_PROCESS_MEMORY', cross_process_restart: false, cross_boot: false};
}
function failure(action, code = 'CONTACTS_SNAPSHOT_GONE') {
  return {...common(action), ok: false, code, ...(action === 'open' ? {} : {snapshot_id: SNAP})};
}
class LocalStore extends QueueStore {
  // 仅替换已独立测试的内核锁；原子文件、容量、摘要与恢复读取均使用原模块。
  async withLock(action) {
    assert.equal(this.locked, false); this.locked = true; this.lockAlive = () => true;
    try { return await action(); } finally { this.locked = false; }
  }
}
function fixture(t, mode = 'good', count = 2) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'contacts-management-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  const store = new LocalStore(directory), posts = [], gets = [], tasks = new Map();
  let activeStore = store, wall = 1800000000000, mono = 0.7, closed = false, injected = false;
  const advance = ms => { wall += ms; mono += ms; };
  const clock = {now: () => wall, monotonic: () => mono, sleep: async ms => advance(ms)};
  const json = (value, status = 200, headers = {}) => new Response(JSON.stringify(value), {status, headers});
  const terminal = request => {
    const p = request.params;
    let value;
    if (p.action === 'open') value = descriptor(count);
    else if (p.action === 'close') { closed = true; value = {...common('close'), snapshot_id: SNAP, snapshot_closed: true}; }
    else if (closed) value = failure('page');
    else {
      const items = p.offset < count ? [{mName: PRIVATE, mNumbers: ['SYNTHETIC_NUMBER'], vendor_unknown: {position: p.offset}}] : [];
      value = {...descriptor(count), action: 'page', items, offset: p.offset, next_offset: p.offset + items.length,
        has_more: p.offset + items.length < count, page_complete: p.offset + items.length === count,
        cursor_scope: 'SAME_SNAPSHOT_ONLY', contact_values_emitted: items.length > 0};
      if (mode === 'repeat-changed' && posts.filter(p => p.params.action === 'page' && p.params.offset === 0).length > 1) value.items[0].mName = 'CHANGED';
      if (mode === 'metadata-changed') value.sampled_at_ms++;
      if (mode === 'wrong-snapshot') value.snapshot_id = SNAP.replace(/1$/, '2');
      if (mode === 'error-page') value = failure('page', 'CONTACTS_TASK_FAILED');
      if (mode === 'oversized') value.items[0].mName = PRIVATE.repeat(10000);
      if (mode === 'legacy-result') return {...request, state: 'success', result: {text: JSON.stringify(value)}};
    }
    if (mode === 'open-personal' && p.action === 'open') value.items = [{mName: PRIVATE}];
    if (mode === 'close-gone' && p.action === 'close') value = failure('close');
    const result = {...request, state: value.ok ? 'success' : 'failed', result: {contacts_page: value}};
    if (mode === 'false-task-success' && !value.ok) result.state = 'success';
    if (mode === 'wrong-task') result.id = 'OTHER_TASK';
    if (mode === 'wrong-params') result.params = {...p, source: 'ALL'};
    return result;
  };
  const transport = new ContactsManagementTransport({session: {cookies: [{name: 'elf_admin', domain: 'v.elfradio.net', value: COOKIE}]}, now: clock.now,
    fetchImpl: async (text, options) => {
      advance(10); const url = new URL(text);
      assert.equal(url.origin, 'https://v.elfradio.net'); assert.equal(options.redirect, 'error');
      assert.ok(options.signal instanceof AbortSignal);
      if (options.method === 'GET') gets.push(url.pathname + url.search);
      if (mode === '401' || mode === '401-after-open' && posts.length > 0) return json({error: PRIVATE}, 401);
      if (mode === 'retry-devices' && !injected) { injected = true; return json({}, 429, {'Retry-After': '10'}); }
      if (url.pathname === '/api/devices') {
        let slot = [...tasks.values()].at(-1)?.task || {id: '', type: '', state: ''};
        if (mode === 'busy') slot = {id: 'OTHER_TASK', type: 'system_config', state: 'running'};
        if (mode === 'bad-slot') slot = null;
        if (mode === 'unknown-slot') slot = {id: 'OTHER_TASK', type: 'system_config', state: 'unknown'};
        if (mode === 'slow-slot' && posts.length > 0 && !injected) { injected = true; advance(120000); }
        if (mode === 'frozen-wall' && posts.length > 0 && !injected) { injected = true; mono += 120000; }
        const d = {id: 'synthetic-device', name: target.deviceName, model_id: 'mdl_d31', ready: true,
          app_version: target.expectedVersion, managed_contacts_page_v1: mode === 'no-capability' ? 'true' : true, task: slot};
        if (mode === 'version-changed' && posts.length > 0) d.app_version = 'other';
        if (mode === 'device-changed' && posts.length > 0) d.id = 'other';
        return json({devices: mode === 'duplicate-device' ? [d, {...d}] : [d]});
      }
      if (url.pathname === '/api/elfremote/tasks') {
        assert.ok(url.searchParams.get('task_id'), '不得读取全量历史');
        assert.equal(url.searchParams.get('device_id'), 'synthetic-device');
        const entry = tasks.get(url.searchParams.get('task_id'));
        if (!entry) return json({ok: false, msg: '未找到该任务'}, 404);
        if (entry.pending && mode !== 'forever-pending') { entry.pending = false; entry.task = terminal(entry.request); }
        return json({ok: true, task: entry.task});
      }
      assert.equal(url.pathname, '/api/elfremote/task'); assert.equal(options.method, 'POST');
      const request = JSON.parse(options.body), state = activeStore.load();
      const intent = Object.values(state.steps).find(s => s.request.id === request.id);
      assert.equal(intent?.submitted, true); assert.deepEqual(intent.request, request);
      assert.ok(state.requests > 0); assert.ok(state.requests <= 100);
      assert.equal(posts.some(p => p.id === request.id), false, '同号不得补交');
      assert.equal(request.type, 'contacts_page'); assert.ok(!('command' in request.params));
      assert.equal('source' in request.params, request.params.action === 'open');
      if (request.params.action === 'page') assert.equal(request.params.limit, 1);
      posts.push(request);
      if (closed && request.params.action === 'page' && mode !== 'device-gone' && mode !== 'false-task-success') {
        const body = {ok: false, not_enqueued: true, code: 'CONTACTS_SNAPSHOT_GONE'};
        if (mode === 'bad-409') body.not_enqueued = 'true';
        if (mode === '409-with-task') body.task = {id: request.id};
        return json(body, 409);
      }
      if (mode === 'not-accepted' && request.params.action === 'open') throw Error(PRIVATE);
      const entry = {request, task: terminal(request), pending: ['pending', 'forever-pending'].includes(mode)};
      if (entry.pending) entry.task = {...request, state: 'pending'};
      tasks.set(request.id, entry);
      if (['accepted-timeout', 'retry-open', 'retry-open-expired', 'malformed-open'].includes(mode) && request.params.action === 'open' && !injected) {
        injected = true;
        if (mode === 'accepted-timeout') throw Error(PRIVATE);
        if (mode === 'malformed-open') return new Response(PRIVATE, {status: 200});
        return json({}, 503, {'Retry-After': mode === 'retry-open-expired' ? '130' : new Date(wall + 10000).toUTCString()});
      }
      return json({ok: true, task: entry.task});
    }});
  const invoke = extra => { activeStore = extra?.store || store; return runContactsManagementWeb({store: activeStore, transport, target, clock, ...extra}); };
  return {directory, store, posts, gets, tasks, clock, advance, invoke,
    state: () => store.withLock(() => store.load())};
}

test('正式任务闭环及409明确未入队：持久意图、逐条分页、复读和私有回执', async t => {
  const f = fixture(t), result = await f.invoke();
  assert.equal(result.status, 'PASSED'); assert.equal(result.closedCursorRejectedBeforeEnqueue, true);
  assert.equal(result.recordCount, 2); assert.equal(result.pageCount, 2); assert.equal(f.posts.length, 6);
  assert.equal(result.totalRequests, f.posts.length + f.gets.length);
  const summary = JSON.stringify(result);
  for (const value of [PRIVATE, COOKIE, SNAP, target.deviceName, 'synthetic-device', 'SYNTHETIC_NUMBER']) assert.equal(summary.includes(value), false);
  const state = await f.state();
  const evidence = JSON.parse(fs.readFileSync(path.join(f.directory, state.steps['closed-cursor'].receipt.file)));
  assert.equal(evidence.http_status, 409); assert.equal(evidence.body.not_enqueued, true);
  assert.equal(f.tasks.has(state.steps['closed-cursor'].request.id), false);
  assert.ok(fs.readdirSync(f.directory).filter(n => n.includes('terminal-private')).some(n => fs.readFileSync(path.join(f.directory, n), 'utf8').includes(PRIVATE)));
  assert.ok(fs.readdirSync(f.directory).every(n => !fs.readFileSync(path.join(f.directory, n), 'utf8').includes(COOKIE)));
  f.advance(200000); const calls = f.posts.length + f.gets.length;
  assert.equal((await f.invoke()).status, 'PASSED'); assert.equal(f.posts.length + f.gets.length, calls);
});
test('设备任务failed中的GONE也可验证，但不声称服务端未入队', async t => {
  const f = fixture(t, 'device-gone'), result = await f.invoke();
  assert.equal(result.status, 'PASSED'); assert.equal(result.closedCursorRejectedBeforeEnqueue, false);
});
test('空列表闭环不冒充跨页验证', async t => {
  const result = await fixture(t, 'good', 0).invoke();
  assert.equal(result.status, 'PASSED'); assert.equal(result.recordCount, 0); assert.equal(result.crossPageExercised, false);
});
for (const mode of ['accepted-timeout', 'retry-open', 'malformed-open']) {
  test(`${mode}新实例只查询原号并继续，open只POST一次`, async t => {
    const f = fixture(t, mode); assert.notEqual((await f.invoke()).status, 'PASSED');
    const before = await f.state(), request = before.steps.open.request;
    f.advance(11000);
    assert.equal((await f.invoke({store: new LocalStore(f.directory)})).status, 'PASSED');
    assert.equal(f.posts.filter(p => p.params.action === 'open').length, 1);
    assert.deepEqual((await f.state()).steps.open.request, request);
  });
}
test('未接收POST且404保持未知，不发close或重新生成open', async t => {
  const f = fixture(t, 'not-accepted'); await f.invoke(); const before = await f.state();
  f.advance(1000);
  assert.equal((await f.invoke()).code, 'CONTACTS_MANAGEMENT_SUBMISSION_UNKNOWN');
  assert.equal(f.posts.length, 1); assert.deepEqual((await f.state()).steps.open.request, before.steps.open.request);
});
test('提交意图落盘后、POST之前退出，恢复也不得补交', async t => {
  const f = fixture(t);
  await f.invoke({checkpoint: label => { if (label === 'submit-intent:open') throw new QueueError('FIXTURE_STOP', true); }});
  assert.equal(f.posts.length, 0); f.advance(1000);
  assert.equal((await f.invoke()).code, 'CONTACTS_MANAGEMENT_SUBMISSION_UNKNOWN'); assert.equal(f.posts.length, 0);
});
test('真正新Node进程读取私有原意图，404只查询原编号', async t => {
  const f = fixture(t, 'not-accepted'); await f.invoke(); const state = await f.state();
  const runner = new URL('./Verify-RemoteContactsManagementWeb.mjs', import.meta.url).href;
  const storage = new URL('./fault-transfer/QueueStore.mjs', import.meta.url).href;
  const script = `import {runContactsManagementWeb} from ${JSON.stringify(runner)};
    import {QueueStore} from ${JSON.stringify(storage)};
    class Store extends QueueStore {async withLock(fn){this.locked=true;this.lockAlive=()=>true;try{return await fn();}finally{this.locked=false;}}}
    const input=JSON.parse(process.argv[1]), queries=[];
    const result=await runContactsManagementWeb({store:new Store(input.directory), target:input.target,
      clock:{now:()=>input.at,monotonic:()=>0,sleep:async()=>{}},
      transport:{queryTask:async(device,id)=>{queries.push(id);return null;},enqueue:async()=>{throw Error('禁止POST');},json:async()=>{throw Error('禁止新设备查询');}}});
    console.log(JSON.stringify({result,queries}));`;
  const output = execFileSync(process.execPath, ['--input-type=module', '-e', script,
    JSON.stringify({directory: f.directory, target, at: state.lastWall + 2000})], {encoding: 'utf8', timeout: 10000, windowsHide: true});
  const value = JSON.parse(output);
  assert.equal(value.result.code, 'CONTACTS_MANAGEMENT_SUBMISSION_UNKNOWN'); assert.deepEqual(value.queries, [state.steps.open.request.id]);
});
for (const mode of ['busy', 'bad-slot', 'unknown-slot', 'no-capability', 'duplicate-device', '401']) {
  test(`${mode}在POST前停止`, async t => {
    const f = fixture(t, mode); assert.notEqual((await f.invoke()).status, 'PASSED'); assert.equal(f.posts.length, 0);
    if (mode === '401') assert.equal(f.gets.length, 1);
  });
}
test('open后401当次立即停止，不再查询、翻页或关闭', async t => {
  const f = fixture(t, '401-after-open'), result = await f.invoke();
  assert.equal(result.code, 'SESSION_REJECTED'); assert.equal(f.posts.length, 1); assert.equal(f.gets.length, 4);
});
test('pending有界查询到终态才推进', async t => {
  const f = fixture(t, 'pending'); assert.equal((await f.invoke()).status, 'PASSED');
  assert.ok(f.gets.filter(p => p.includes('task_id=')).length > f.posts.length);
});
for (const mode of ['repeat-changed', 'metadata-changed', 'wrong-snapshot', 'error-page', 'oversized', 'legacy-result',
  'open-personal', 'close-gone', 'false-task-success', 'wrong-task', 'wrong-params', 'bad-409', '409-with-task', 'version-changed', 'device-changed']) {
  test(`${mode}不能当成成功或空列表`, async t => {
    const f = fixture(t, mode), result = await f.invoke();
    assert.notEqual(result.status, 'PASSED'); assert.equal(result.managementContactsVerified, false);
    assert.equal(f.posts.filter(p => p.params.action === 'open').length, 1);
  });
}
test('退避跨重跑持久生效且不发任何API请求', async t => {
  const f = fixture(t, 'retry-devices'); assert.equal((await f.invoke()).code, 'HTTP_429');
  const calls = f.gets.length; f.advance(9999);
  assert.equal((await f.invoke()).code, 'CONTACTS_MANAGEMENT_RETRY_BACKOFF'); assert.equal(f.gets.length, calls);
  f.advance(1); assert.equal((await f.invoke()).status, 'PASSED');
});
test('130秒退避不续期：只查原open，不发内容页或再次采集', async t => {
  const f = fixture(t, 'retry-open-expired'), limits = {maxMs: 180000};
  await f.invoke({limits}); const before = await f.state(); f.advance(130000);
  assert.equal((await f.invoke({limits})).code, 'CONTACTS_MANAGEMENT_SNAPSHOT_DEADLINE');
  assert.equal(f.posts.length, 1); assert.equal((await f.state()).snapshotDeadline, before.snapshotDeadline);
});
for (const mode of ['slow-slot', 'frozen-wall']) {
  test(`${mode}不能越过固定120秒提交分页`, async t => {
    const f = fixture(t, mode); assert.notEqual((await f.invoke({limits: {maxMs: 180000}})).status, 'PASSED');
    assert.equal(f.posts.length, 1);
  });
}
test('时钟倒退后即使再次前进，也不能延长期限或提交内容页', async t => {
  const f = fixture(t, 'accepted-timeout'); await f.invoke(); const before = await f.state();
  f.advance(-10000); await f.invoke(); f.advance(12000);
  assert.notEqual((await f.invoke()).status, 'PASSED'); assert.equal(f.posts.length, 1);
  assert.equal((await f.state()).snapshotDeadline, before.snapshotDeadline);
});
test('API最多100次且重启不重置计数，期限和配置不得续期', async t => {
  const f = fixture(t, 'forever-pending'), limits = {maxRequests: 8};
  const first = await f.invoke({limits}); assert.equal(first.code, 'CONTACTS_MANAGEMENT_REQUEST_BUDGET');
  assert.equal(first.totalRequests, 8); const calls = f.posts.length + f.gets.length;
  assert.equal((await f.invoke({limits})).totalRequests, 8); assert.equal(f.posts.length + f.gets.length, calls);
  assert.equal((await f.invoke({limits: {maxRequests: 9}})).code, 'CONTACTS_MANAGEMENT_STATE_MISMATCH');
  await assert.rejects(() => f.invoke({limits: {maxRequests: 101}}), /LIMIT_INVALID/);
});
test('总时长跨重跑不重置，耗尽后不再发API', async t => {
  const f = fixture(t, 'forever-pending'), limits = {maxMs: 2000};
  assert.equal((await f.invoke({limits})).code, 'CONTACTS_MANAGEMENT_TIME_BUDGET');
  f.advance(2000); const calls = f.posts.length + f.gets.length;
  assert.equal((await f.invoke({limits})).code, 'CONTACTS_MANAGEMENT_TIME_BUDGET'); assert.equal(f.posts.length + f.gets.length, calls);
});
test('原件摘要不符时停止，不新提交或重建同号', async t => {
  const f = fixture(t); await f.invoke(); const state = await f.state();
  fs.appendFileSync(path.join(f.directory, state.steps.open.receipt.file), ' ');
  const calls = f.posts.length + f.gets.length;
  assert.equal((await f.invoke()).code, 'CONTACTS_MANAGEMENT_RECEIPT_CHANGED'); assert.equal(f.posts.length + f.gets.length, calls);
});
test('未提交分页意图过期后保持原号和原期限，不重建或补交', async t => {
  const f = fixture(t, 'slow-slot'), limits = {maxMs: 180000};
  await f.invoke({limits}); const request = structuredClone((await f.state()).steps['page-0'].request);
  f.advance(1000);
  assert.equal((await f.invoke({limits})).code, 'CONTACTS_MANAGEMENT_INTENT_EXPIRED');
  assert.deepEqual((await f.state()).steps['page-0'].request, request); assert.equal(f.posts.length, 1);
});
test('响应超时通过AbortSignal中止且不自动重试', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'contacts-management-abort-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  let calls = 0, aborted = false;
  const transport = new ContactsManagementTransport({session: {cookies: [{name: 'elf_admin', domain: 'v.elfradio.net', value: COOKIE}]},
    fetchImpl: (_url, options) => new Promise((_resolve, reject) => {
      calls++; options.signal.addEventListener('abort', () => { aborted = true; reject(Error(PRIVATE)); }, {once: true});
    })});
  const result = await runContactsManagementWeb({store: new LocalStore(directory), transport, target, limits: {requestMs: 20}});
  assert.equal(result.code, 'NETWORK_UNAVAILABLE'); assert.equal(calls, 1); assert.equal(aborted, true);
  assert.equal(JSON.stringify(result).includes(PRIVATE), false);
});
test('非法CLI在加载会话或联网前拒绝', async () => {
  await assert.rejects(() => main(['--unknown', 'x']), /CLI_ARGUMENTS/);
  await assert.rejects(() => main([]), /CLI_REQUIRED/);
});
test('合同独立导入，不需要其他工作区文件', () => {
  const source = fs.readFileSync(new URL('./Verify-RemoteContactsManagementWeb.mjs', import.meta.url), 'utf8');
  assert.equal(source.includes('FreePBX_VPNnode_Web'), false);
  assert.deepEqual(contract.normalizeContactsPageParams({action: 'close', snapshot_id: SNAP}), {action: 'close', snapshot_id: SNAP});
  assert.throws(() => contract.normalizeContactsPageParams({action: 'page', source: 'LOCAL', snapshot_id: SNAP, offset: 0, limit: 1}));
});
test('可选本机Web合同交叉核验：成功、失败、字段和坏类型', {skip: !process.env.D31_CONTACTS_WEB_CONTRACT}, async () => {
  const web = await import(pathToFileURL(path.resolve(process.env.D31_CONTACTS_WEB_CONTRACT)).href);
  const cases = [];
  for (const p of [{action: 'open', source: 'LOCAL'}, {action: 'close', snapshot_id: SNAP}, {action: 'page', snapshot_id: SNAP, offset: 0, limit: 1}]) {
    const value = p.action === 'open' ? descriptor(1) : p.action === 'close' ? {...common('close'), snapshot_id: SNAP, snapshot_closed: true}
      : {...descriptor(1), action: 'page', offset: 0, next_offset: 1, items: [{mName: PRIVATE}], has_more: false, page_complete: true, cursor_scope: 'SAME_SNAPSHOT_ONLY', contact_values_emitted: true};
    cases.push([value, p], [failure(p.action), p]);
    for (const key of Object.keys(value)) { const bad = {...value}; delete bad[key]; cases.push([bad, p]); }
    cases.push([{...value, source: 'LOCAL'}, p], [{...value, ok: 'true'}, p], [value, {...p, extra: true}]);
  }
  const evaluate = (module, value, p) => { try { return {value: module.normalizeContactsPageResult(value, p)}; } catch (e) { return {code: e.code}; } };
  for (const [value, p] of cases) assert.deepEqual(evaluate(contract, value, p), evaluate(web, value, p));
});
