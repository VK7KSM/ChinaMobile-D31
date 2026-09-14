import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {QueueStore, QueueError} from './fault-transfer/QueueStore.mjs';
import {WebTransport} from './fault-transfer/WebTransport.mjs';
import {runContactsWeb, preflightCommand, validatePreflight} from './Verify-RemoteContactsPagesWeb.mjs';

const HASH = 'a'.repeat(64), BOOT = '00000000-0000-0000-0000-000000000001';
const SNAPSHOT = '00000000-0000-0000-0000-000000000002', REQUEST = '00000000-0000-0000-0000-000000000003';
const PRIVATE = 'SYNTHETIC_PRIVATE_CONTACT';
const target = {deviceName: 'SYNTHETIC-D31', expectedVersion: '1.28.1-candidate', versionCode: 132,
  activeApk: `/data/local/d31-remote/releases/${HASH}/remote.apk`};

for (const trailingNewline of [false, true]) {
  test(`真实shell预检：active.json${trailingNewline ? '有' : '无'}末尾换行`, () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'contacts-shell-'));
    const apk = Buffer.from('synthetic-apk-bytes');
    const hash = createHash('sha256').update(apk).digest('hex');
    const shellTarget = {...target, activeApk: `/data/local/d31-remote/releases/${hash}/remote.apk`};
    const active = JSON.stringify({package: 'net.elfradio.d31bootstrap', path: shellTarget.activeApk,
      sha256: hash, versionCode: shellTarget.versionCode});
    fs.writeFileSync(path.join(directory, 'active.json'), active + (trailingNewline ? '\n' : ''));
    fs.writeFileSync(path.join(directory, 'boot-id'), BOOT + '\n');
    fs.writeFileSync(path.join(directory, 'apk.bin'), apk);
    const shell = process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : '/bin/sh';
    // 仅替换Android专用命令及设备路径；cat、sha256sum和预检命令均由真实shell执行。
    const setup = `
id() { printf '0\\n'; }
getprop() {
  case "$1" in
    ro.build.version.sdk) printf '23\\n';;
    ro.product.device) printf 'hct6735_66_m0\\n';;
    ro.product.model) printf 'hct6737t_66_m0\\n';;
    ro.build.fingerprint) printf 'fixture/device:6.0/test\\n';;
    *) return 124;;
  esac
}
cat() {
  case "$1" in
    /data/local/d31-remote/runtime/active.json) command cat active.json;;
    /proc/sys/kernel/random/boot_id) command cat boot-id;;
    *) return 124;;
  esac
}
busybox() { [ "$1" = sha256sum ] || return 124; command sha256sum apk.bin; }
pm() { printf 'package:/data/app/net.elfradio.d31bootstrap-1/base.apk\\n'; }
`;
    const execute = command => execFileSync(shell, ['-s'], {cwd: directory,
      input: setup + command, encoding: 'utf8', timeout: 10000, windowsHide: true}).replace(/\r/g, '');
    try {
      const output = execute(preflightCommand(shellTarget));
      assert.equal(validatePreflight(output, shellTarget), BOOT);
      assert.ok(output.includes(active + (trailingNewline ? '\n\n' : '\n') + hash));
      if (!trailingNewline) {
        assert.notEqual(fs.readFileSync(path.join(directory, 'active.json')).at(-1), 10);
        const oldOutput = execute(preflightCommand(shellTarget).replace("printf '\\n'\n", ''));
        assert.ok(oldOutput.includes(active + hash));
        assert.throws(() => validatePreflight(oldOutput, shellTarget), /CONTACTS_WEB_DEVICE_IDENTITY/);
      }
    } finally { fs.rmSync(directory, {recursive: true, force: true}); }
  });
}
function snapshot(count = 2) {
  return {ok: true, read_only: true, source: 'nexui_messenger', owner_package: 'com.starnet.dial', contact_type: 'LOCAL',
    snapshot_id: SNAPSHOT, status: 'COMPLETED', sampled_at_ms: 1, elapsed_ms: 1, record_count: count, frames_received: 2,
    received_chars: 200, start_observed: true, end_observed: true, list_complete: true, contact_values_emitted: false,
    completion_scope: 'SELECTED_SOURCE_ALL_CONTACTS_REPLY', all_sources_complete: false, snapshot_consistency: 'NOT_PROVIDED_BY_VENDOR',
    android_equivalence: 'NOT_VERIFIED', service_implementation: 'NOT_DECRYPTED', max_records: 4096, max_frames: 128,
    max_received_chars: 1048576, wait_budget_ms: 8000, retention_ms: 120000, max_page_records: 32,
    page_consistency: 'IMMUTABLE_RECEIVED_REPLY', storage: 'APP_PROCESS_MEMORY', cross_process_restart: false, cross_boot: false};
}
function opened(id, count) {
  const local = {...snapshot(count), effective_wait_budget_ms: 8000};
  for (const key of ['retention_ms', 'max_page_records', 'page_consistency', 'storage', 'cross_process_restart', 'cross_boot']) delete local[key];
  const result = {schemaVersion: 1, kind: 'NEXUI_APP_LOCAL_METADATA', operation: 'read_local_pages', contact_type: 'LOCAL',
    ownerPackage: 'com.starnet.dial', bindFlags: 1, state: 'LOCAL_METADATA_VERIFIED', ok: true, listComplete: true,
    remoteOutcomeKnown: true, expectedApkSha256: HASH, app_pid: 123, app_uid: 10001, contactValuesEmitted: false,
    all_sources_complete: false, vendorServiceStopRequested: false, vendorLifecycleRestored: false, vendorStartupEffects: 'NOT_VERIFIED',
    elapsedMs: 10, local, page_snapshot: snapshot(count), operation_id: id, operation_request_id: REQUEST, operation_apk_sha256: HASH,
    app_operation: {operation_id: id, operation: 'read-local-pages', apk_sha256: HASH, operation_request_id: REQUEST,
      record_boot_id: BOOT, current_boot_id: BOOT, state: 'RELEASED', reservation_released: true,
      release_reason: 'MATCHED_RELEASE_RECEIPT', managed_media: false, network_write: false}};
  for (const key of ['readOnly', 'contactDataReadOnly', 'bridgeHandshake', 'appServiceStartRequested', 'maintenanceGatePassed',
    'activeApkHashMatched', 'appIdentityMatched', 'installedApkHashMatched', 'componentMatched', 'vendorServiceStartRequested',
    'vendorServiceStartAccepted', 'bindingRequested', 'bindAccepted', 'messengerBinderVerified', 'contacts_requested',
    'contactRequestSent', 'contactValuesRead', 'replyChannelClosed', 'unbindAttempted', 'unbindConfirmed']) result[key] = true;
  return result;
}
function pageReceipt(closed = false) {
  return {schemaVersion: 1, kind: 'NEXUI_APP_LOCAL_PAGE', state: 'CONTACTS_PAGE_VERIFIED', ok: true,
    readOnly: true, source: 'nexui_messenger', contact_type: 'LOCAL', ownerPackage: 'com.starnet.dial',
    vendorRequestSent: false, all_sources_complete: false, expectedApkSha256: HASH, snapshot_id: SNAPSHOT,
    snapshot_closed: closed, maintenanceGatePassed: true, activeApkHashMatched: true, bridgeHandshake: true,
    appServiceStartRequested: true, app_pid: 123, app_uid: 10001};
}
class LocalStore extends QueueStore {
  // 仅替换已独立验证的操作系统锁，不替换持久意图、摘要、原子提交或文件读取。
  async withLock(action) { assert.equal(this.locked, false); this.locked = true; this.lockAlive = () => true;
    try { return await action(); } finally { this.locked = false; } }
}
function fixture(mode = 'good', count = 2) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'contacts-web-'));
  const store = new LocalStore(directory), tasks = new Map(), posts = [], gets = [];
  const history = mode === 'huge-history' ? {tasks: [{result: {text: PRIVATE.repeat(20000)}}]} : {tasks: []};
  let activeStore = store;
  let now = 1800000000000, mono = 0, close = false, injected = false, openedAt;
  const advance = ms => { now += ms; mono += ms; };
  const clock = {now: () => now, monotonic: () => mono, sleep: async ms => advance(ms)};
  const json = (body, status = 200) => new Response(JSON.stringify(body), {status, headers: {'Content-Type': 'application/json'}});
  const terminal = request => {
    const command = request.params.command;
    const marker = /echo (D31_CONTACTS_WEB_[a-f0-9]+)_\$contacts_code/.exec(command)[1];
    let payload, code = 0;
    if (command.includes('ContactsPageCommand open')) {
      openedAt = now;
      payload = opened(/ open [a-f0-9]{64} ([A-Za-z0-9-]+)/.exec(command)[1], count);
      if (mode === 'wrong-operation') payload.app_operation.operation = 'read-local-metadata';
      if (mode === 'unreleased') payload.app_operation.reservation_released = false;
    } else if (command.includes('ContactsPageCommand close')) {
      if (mode === 'expired-open' || now - openedAt >= 120000) {
        payload = {kind: 'NEXUI_APP_LOCAL_PAGE', ok: false, state: 'CONTACTS_SNAPSHOT_GONE'}; code = 1;
      } else { close = true; payload = pageReceipt(true); }
    } else if (command.includes('ContactsPageCommand page')) {
      if (close) { payload = {kind: 'NEXUI_APP_LOCAL_PAGE', ok: false, state: 'CONTACTS_SNAPSHOT_GONE'}; code = 1; }
      else {
        const offset = Number(/ page [a-f0-9]{64} [a-f0-9-]{36} (\d+) 1/.exec(command)[1]);
        const items = offset < count ? [{mId: offset, mName: PRIVATE, mNumbers: ['PRIVATE_NUMBER']}] : [];
        payload = {...pageReceipt(), page: {...snapshot(count), items, offset, next_offset: offset + items.length,
          has_more: offset + items.length < count, page_complete: offset + items.length === count,
          cursor_scope: 'SAME_SNAPSHOT_ONLY', contact_values_emitted: items.length > 0}};
        if (mode === 'wrong-page') payload.page.snapshot_id = REQUEST;
        if (mode === 'changed-repeat' && posts.filter(p => p.params.command.includes(` ${SNAPSHOT} 0 1`)).length > 1)
          payload.page.items[0].mName = 'CHANGED';
      }
    } else payload = `0\n23\nhct6735_66_m0\nhct6737t_66_m0\nfixture/device:6.0/test\n${BOOT}\n`
      + JSON.stringify({package: 'net.elfradio.d31bootstrap', path: target.activeApk, sha256: HASH, versionCode: target.versionCode})
      + `\n${HASH}  ${target.activeApk}\n${HASH}  /data/app/net.elfradio.d31bootstrap-1/base.apk`;
    const text = `${typeof payload === 'string' ? payload : JSON.stringify(payload)}\n${mode === 'wrong-marker' ? 'OTHER_MARKER' : marker}_${code}`;
    return {id: request.id, type: 'root_exec', expires_at: request.expires_at, state: code ? 'failed' : 'success',
      result: {text, exit_code: code, truncated: mode === 'truncated' && command.includes('ContactsPageCommand page'), stage: 'command', action: 'completed'}};
  };
  const fetchImpl = async (urlText, options) => {
    advance(100); const url = new URL(urlText);
    assert.equal(url.origin, 'https://v.elfradio.net'); assert.equal(options.redirect, 'error');
    assert.ok(options.signal instanceof AbortSignal);
    if (options.method === 'GET') gets.push(url.pathname + url.search);
    if (mode === 'retry-identity' && !injected) {
      injected = true;
      return new Response('', {status: 429, headers: {'Retry-After': '10'}});
    }
    if (url.pathname === '/api/devices') {
      if (mode === 'slow-page-slot' && openedAt !== undefined && !injected) { injected = true; advance(100000); }
      const current = mode === 'busy' ? {id: 'another-task', type: 'root_exec', state: 'running'}
        : [...tasks.values()].at(-1)?.task || {id: '', type: '', state: '', result: null};
      const device = {id: 'fixture-d31', model_id: 'mdl_d31', name: target.deviceName,
        app_version: target.expectedVersion, ready: true, managed_exec_tasks: mode !== 'no-exec', managed_file_return: true, task: current};
      if (gets.filter(route => route === '/api/devices').length >= 3) {
        if (mode === 'slot-wrong-device') device.id = 'other-d31';
        if (mode === 'slot-wrong-version') device.app_version = 'wrong-version';
        if (mode === 'slot-missing') delete device.task;
        if (mode === 'slot-unknown') device.task = {...current, id: 'unknown-task', type: 'root_exec', state: 'unknown'};
      }
      const devices = [device];
      if (mode === 'other-device-busy') devices.unshift({...device, id: 'other-d31', name: 'OTHER',
        task: {id: 'another-task', type: 'root_exec', state: 'running'}});
      if (mode === 'slot-duplicate' && gets.filter(route => route === '/api/devices').length >= 3) devices.push({...device});
      return json({devices});
    }
    if (url.pathname === '/api/elfremote/tasks') {
      const id = url.searchParams.get('task_id');
      if (!id) {
        if (mode === 'huge-history') return json(history);
        assert.fail('不得读取无task_id的全量任务历史');
      }
      const item = tasks.get(id);
      if (!item) return json({ok: false, msg: '未找到该任务'}, 404);
      if (mode === 'lost-query') throw Error('SYNTHETIC_PRIVATE_NETWORK_ERROR');
      if (item.pending) { item.pending = false; item.task = terminal(item.request); }
      return json({ok: true, task: item.task});
    }
    assert.equal(url.pathname, '/api/elfremote/task'); assert.equal(options.method, 'POST');
    const request = JSON.parse(options.body);
    const state = activeStore.load();
    const intent = Object.values(state.steps).find(s => s.request.id === request.id);
    assert.ok(intent?.submitted, '必须先持久化提交意图'); assert.deepEqual(intent.request, request);
    assert.equal(posts.some(p => p.id === request.id), false, '不得重复提交原任务');
    assert.ok([...tasks.values()].every(t => !['pending', 'claimed', 'running'].includes(t.task.state)), '忙槽未结束禁止下一POST');
    posts.push(request);
    const isOpen = request.params.command.includes('ContactsPageCommand open');
    if (mode === 'not-accepted' && isOpen) throw Error(PRIVATE);
    const result = terminal(request);
    if (mode === 'expired-open' && isOpen) advance(121000);
    const pending = (mode === 'accepted-timeout' && isOpen) || mode === 'pending';
    tasks.set(request.id, {request, pending, task: pending ? {id: request.id, type: 'root_exec', expires_at: request.expires_at, state: 'pending'} : result});
    if (['retry-open', 'retry-open-expired'].includes(mode) && isOpen && !injected) {
      injected = true;
      return new Response('', {status: 503, headers: {'Retry-After': mode === 'retry-open' ? '10' : '130'}});
    }
    if (mode === 'accepted-timeout' && isOpen && !injected) { injected = true; throw Error(PRIVATE); }
    return json({ok: true, task: tasks.get(request.id).task});
  };
  const transport = new WebTransport({session: {cookies: [{name: 'elf_admin', domain: 'v.elfradio.net', value: 'fixture-only'}]}, fetchImpl, now: clock.now});
  return {store, tasks, posts, gets, directory, advance, clock, historyBytes: Buffer.byteLength(JSON.stringify(history)), invoke: extra => {
    activeStore = extra?.store || store;
    return runContactsWeb({store: activeStore, transport, target, clock, ...extra});
  }};
}

test('生产WebTransport接口闭环：先落盘意图，逐页复读关闭，个人值仅私有原件', async () => {
  const f = fixture(); const result = await f.invoke();
  assert.equal(result.status, 'PASSED'); assert.equal(result.ordinaryWebCommandVerified, true);
  assert.equal(result.webContactsListDeveloped, false); assert.equal(result.crossPageExercised, true);
  assert.equal(f.posts.filter(p => p.params.command.includes('ContactsPageCommand open')).length, 1);
  assert.equal(f.posts.length, 8);
  const summaries = fs.readdirSync(f.directory).filter(n => n.startsWith('contacts-summary-'));
  for (const name of summaries) {
    const text = fs.readFileSync(path.join(f.directory, name), 'utf8');
    for (const privateValue of [PRIVATE, 'PRIVATE_NUMBER', HASH, BOOT, SNAPSHOT, 'fixture-only']) assert.equal(text.includes(privateValue), false);
  }
  const terminal = fs.readdirSync(f.directory).filter(n => n.startsWith('contacts-terminal-private-'));
  assert.ok(terminal.some(n => fs.readFileSync(path.join(f.directory, n), 'utf8').includes(PRIVATE)));
  const before = f.posts.length; assert.equal((await f.invoke()).status, 'PASSED'); assert.equal(f.posts.length, before);
});
test('提交已接收但响应超时，恢复只查原号，不重复开快照', async () => {
  const f = fixture('accepted-timeout'); assert.equal((await f.invoke()).status, 'PAUSED');
  f.advance(1000);
  assert.equal((await f.invoke()).status, 'PASSED');
  assert.equal(f.posts.filter(p => p.params.command.includes('ContactsPageCommand open')).length, 1);
});
test('提交意图后失联且404仍不允许补交，查询原号保持未知', async () => {
  const f = fixture('not-accepted'); assert.equal((await f.invoke()).status, 'PAUSED');
  f.advance(1000);
  const count = f.posts.length, result = await f.invoke();
  assert.equal(result.code, 'CONTACTS_WEB_SUBMISSION_UNKNOWN'); assert.equal(f.posts.length, count);
  assert.equal(f.posts.some(p => p.params.command.includes('ContactsPageCommand close')), false);
});
test('POST前崩溃窗口按已持久意图查询，不猜测未写出', async () => {
  const f = fixture(); let once = false;
  const result = await f.invoke({checkpoint: label => {
    if (!once && label === 'submit-intent:open') { once = true; throw new QueueError('CRASH_FIXTURE', true); }
  }});
  assert.equal(result.status, 'PAUSED'); assert.equal(f.posts.length, 1);
  assert.equal((await f.invoke()).code, 'CONTACTS_WEB_SUBMISSION_UNKNOWN'); assert.equal(f.posts.length, 1);
});
test('已知其它任务占用忙槽时不POST，不取消他人任务', async () => {
  const f = fixture('busy'); assert.equal((await f.invoke()).code, 'CONTACTS_WEB_BUSY_SLOT'); assert.equal(f.posts.length, 0);
});
test('pending必须查询终态后才允许下一步', async () => {
  const f = fixture('pending'); assert.equal((await f.invoke()).status, 'PASSED');
  assert.ok(f.gets.filter(r => r.includes('task_id=')).length >= f.posts.length * 2);
});
test('未知查询失败禁止新步骤及close抢占原任务', async () => {
  const f = fixture('lost-query');
  // 在首个POST接收后模拟宿主退出，使恢复必须经查询。
  const first = await f.invoke({checkpoint: label => { if (label === 'after-submit:identity') throw new QueueError('CRASH_FIXTURE', true); }});
  assert.equal(first.status, 'PAUSED'); assert.equal((await f.invoke()).status, 'PAUSED'); assert.equal(f.posts.length, 1);
});
test('延迟越过TTL不会提交内容页或第二次open', async () => {
  const f = fixture('expired-open'); const result = await f.invoke({limits: {maxMs: 180000}});
  assert.equal(result.status, 'FAILED'); assert.equal(result.code, 'CONTACTS_WEB_SNAPSHOT_DEADLINE');
  assert.equal(f.posts.filter(p => p.params.command.includes('ContactsPageCommand open')).length, 1);
  assert.equal(f.posts.filter(p => p.params.command.includes('ContactsPageCommand page')).length, 0);
  assert.equal(f.posts.filter(p => p.params.command.includes('ContactsPageCommand close')).length, 1);
});
test('跨宿主时钟倒退不延长快照，不新增POST', async () => {
  const f = fixture('accepted-timeout'); await f.invoke(); const before = f.posts.length;
  f.advance(-10000); const result = await f.invoke();
  assert.notEqual(result.status, 'PASSED'); assert.equal(f.posts.length, before);
});
for (const mode of ['wrong-operation', 'unreleased', 'wrong-page', 'changed-repeat', 'wrong-marker', 'truncated']) {
  test(`${mode}不能冒充成功或借其它operation释放`, async () => {
    const f = fixture(mode); const result = await f.invoke(); assert.notEqual(result.status, 'PASSED');
    assert.equal(result.ordinaryWebCommandVerified, false);
    assert.ok(f.posts.every(p => !p.params.command.includes('RemoteAppOperation recover')));
    if (mode === 'wrong-operation' || mode === 'unreleased')
      assert.equal(f.posts.some(p => p.params.command.includes('ContactsPageCommand close')), false);
  });
}
test('缺少真实exec能力在任何POST前拒绝', async () => {
  const f = fixture('no-exec'); assert.equal((await f.invoke()).code, 'CONTACTS_WEB_EXEC_CAPABILITY'); assert.equal(f.posts.length, 0);
});
test('本地回执被改动时不继续推进新任务', async () => {
  const f = fixture(); await f.invoke(); const before = f.posts.length;
  const name = fs.readdirSync(f.directory).find(n => n.startsWith('contacts-terminal-private-'));
  fs.appendFileSync(path.join(f.directory, name), ' ');
  assert.notEqual((await f.invoke()).status, 'PASSED'); assert.equal(f.posts.length, before);
});

test('Retry-After持久化：重跑前不发身份、任务查询或POST，到期后继续', async () => {
  const f = fixture('retry-identity');
  assert.equal((await f.invoke()).code, 'HTTP_429');
  const retryAt = await f.store.withLock(() => f.store.load().retryAt);
  assert.equal(retryAt, f.clock.now() + 10000);
  const gets = f.gets.length;
  assert.equal((await f.invoke()).code, 'CONTACTS_WEB_RETRY_BACKOFF');
  f.advance(9999);
  assert.equal((await f.invoke()).code, 'CONTACTS_WEB_RETRY_BACKOFF');
  assert.equal(f.gets.length, gets); assert.equal(f.posts.length, 0);
  f.advance(1);
  assert.equal((await f.invoke()).status, 'PASSED');
});

test('POST响应503带退避：重启宿主后只查已保存原号，不重新开快照', async () => {
  const f = fixture('retry-open');
  assert.equal((await f.invoke()).code, 'HTTP_503');
  const state = await f.store.withLock(() => f.store.load());
  const id = state.steps.open.request.id, gets = f.gets.length, posts = f.posts.length;
  // 新实例从磁盘加载，不能依赖原运行的内存退避。
  const restarted = new LocalStore(f.directory);
  assert.equal((await f.invoke({store: restarted})).code, 'CONTACTS_WEB_RETRY_BACKOFF');
  assert.equal(f.gets.length, gets); assert.equal(f.posts.length, posts);
  f.advance(10000);
  assert.equal((await f.invoke({store: restarted})).status, 'PASSED');
  assert.ok(f.gets.slice(gets).some(route => route.includes(`task_id=${id}`)));
  assert.equal(f.posts.filter(request => request.id === id).length, 1);
});

test('退避超过120秒：仍只查原开快照任务，拒绝重新采集或提交内容页', async () => {
  const f = fixture('retry-open-expired'); await f.invoke();
  f.advance(130000);
  const result = await f.invoke();
  assert.equal(result.status, 'FAILED');
  assert.equal(f.posts.filter(p => p.params.command.includes('ContactsPageCommand open')).length, 1);
  assert.equal(f.posts.filter(p => p.params.command.includes('ContactsPageCommand page')).length, 0);
  assert.equal(f.posts.filter(p => p.params.command.includes('ContactsPageCommand close')).length, 1);
});

test('忙槽查询耗尽快照窗口后不能提交过期内容页', async () => {
  const f = fixture('slow-page-slot');
  const result = await f.invoke({limits: {maxMs: 180000}});
  assert.equal(result.status, 'FAILED');
  assert.equal(f.posts.filter(p => p.params.command.includes('ContactsPageCommand page')
    && !p.params.command.includes(' close ')).length, 1); // 唯一page是关闭后拒绝核验。
  const state = await f.store.withLock(() => f.store.load());
  assert.equal(Object.keys(state.steps).filter(key => key.startsWith('page-') && state.steps[key].submitted).length, 0);
});

test('空LOCAL只证明空列表闭环，不声明跨页已验收', async () => {
  const f = fixture('good', 0), result = await f.invoke();
  assert.equal(result.status, 'PASSED'); assert.equal(result.recordCount, 0);
  assert.equal(result.crossPageExercised, false); assert.equal(result.closedCursorRejected, true);
});

test('页数预算耗尽明确失败，关闭既有快照且不重新采集', async () => {
  const f = fixture('good', 2), result = await f.invoke({limits: {maxPages: 1}});
  assert.equal(result.status, 'FAILED'); assert.equal(result.code, 'CONTACTS_WEB_PAGE_BUDGET');
  assert.equal(result.closeConfirmed, true); assert.equal(result.ordinaryWebCommandVerified, false);
  assert.equal(f.posts.filter(p => p.params.command.includes('ContactsPageCommand open')).length, 1);
});

test('巨量历史超过响应上限仍完成闭环，绝不访问全历史接口', async () => {
  const f = fixture('huge-history');
  assert.ok(f.historyBytes > 262144);
  assert.equal((await f.invoke()).status, 'PASSED');
  assert.ok(f.gets.every(route => !route.startsWith('/api/elfremote/tasks?') || new URL(route, 'https://v.elfradio.net').searchParams.has('task_id')));
});

test('只检查严格匹配设备的当前普通忙槽，不受其他设备忙槽影响', async () => {
  const f = fixture('other-device-busy'); assert.equal((await f.invoke()).status, 'PASSED');
});

for (const mode of ['slot-wrong-device', 'slot-wrong-version', 'slot-duplicate', 'slot-missing', 'slot-unknown']) {
  test(`${mode}在忙槽检查时拒绝，不提交命令`, async () => {
    const f = fixture(mode);
    assert.notEqual((await f.invoke()).status, 'PASSED');
    assert.equal(f.posts.length, 0);
  });
}

test('旧历史读取失败后的过期未提交close：原号查询，不续期、不补交、不重开', async () => {
  const f = fixture();
  await f.invoke({checkpoint: label => {
    if (label === 'intent:page-0') throw new QueueError('WEB_JSON_LIMIT');
    if (label === 'query-intent:close') throw new QueueError('CRASH_FIXTURE', true);
  }});
  const before = await f.store.withLock(() => f.store.load());
  assert.equal(before.failure, 'WEB_JSON_LIMIT');
  assert.equal(before.steps.close.submitted, false);
  const closeRequest = structuredClone(before.steps.close.request), count = f.posts.length, gets = f.gets.length;
  f.advance(121000);
  const result = await f.invoke();
  const after = await f.store.withLock(() => f.store.load());
  assert.equal(result.code, 'CONTACTS_WEB_INTENT_EXPIRED');
  assert.equal(result.closeConfirmed, false); assert.equal(result.ordinaryWebCommandVerified, false);
  assert.deepEqual(after.steps.close.request, closeRequest);
  assert.equal(after.steps.close.submitted, false); assert.equal(f.posts.length, count);
  assert.ok(f.gets.slice(gets).some(route => route.includes(`task_id=${closeRequest.id}`)));
  assert.equal(f.posts.filter(p => p.params.command.includes('ContactsPageCommand open')).length, 1);
});
