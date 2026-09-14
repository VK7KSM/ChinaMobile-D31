import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {main, validateOpen, validatePage, validateClose} from './Verify-RemoteContactsPage.mjs';

const SHA = 'a'.repeat(64), BOOT = '00000000-0000-0000-0000-000000000001';
const ID = '00000000-0000-0000-0000-000000000002', REQUEST = '00000000-0000-0000-0000-000000000003';
const VERSION = 130, NAME = 'SYNTHETIC_PRIVATE_NAME', NUMBER = 'SYNTHETIC_PRIVATE_NUMBER';
function descriptor(count = 2) {
  return {ok: true, read_only: true, source: 'nexui_messenger', owner_package: 'com.starnet.dial', contact_type: 'LOCAL',
    snapshot_id: ID, status: 'COMPLETED', sampled_at_ms: 1, elapsed_ms: 1, record_count: count, frames_received: 2,
    received_chars: 200, start_observed: true, end_observed: true, list_complete: true, contact_values_emitted: false,
    completion_scope: 'SELECTED_SOURCE_ALL_CONTACTS_REPLY', all_sources_complete: false, snapshot_consistency: 'NOT_PROVIDED_BY_VENDOR',
    android_equivalence: 'NOT_VERIFIED', service_implementation: 'NOT_DECRYPTED', max_records: 4096, max_frames: 128,
    max_received_chars: 1048576, wait_budget_ms: 8000, retention_ms: 120000, max_page_records: 32,
    page_consistency: 'IMMUTABLE_RECEIVED_REPLY', storage: 'APP_PROCESS_MEMORY', cross_process_restart: false, cross_boot: false};
}
function open(id = 'test-pages', count = 2) {
  const meta = descriptor(count);
  const local = {...meta, effective_wait_budget_ms: 8000};
  for (const key of ['retention_ms', 'max_page_records', 'page_consistency', 'storage', 'cross_process_restart', 'cross_boot']) delete local[key];
  const value = {schemaVersion: 1, kind: 'NEXUI_APP_LOCAL_METADATA', operation: 'read_local_pages', contact_type: 'LOCAL',
    ownerPackage: 'com.starnet.dial', bindFlags: 1, state: 'LOCAL_METADATA_VERIFIED', ok: true, listComplete: true,
    remoteOutcomeKnown: true, expectedApkSha256: SHA, app_pid: 123, app_uid: 10001, contactValuesEmitted: false,
    all_sources_complete: false, vendorServiceStopRequested: false, vendorLifecycleRestored: false, vendorStartupEffects: 'NOT_VERIFIED',
    elapsedMs: 10, local, page_snapshot: meta, operation_id: id, operation_request_id: REQUEST, operation_apk_sha256: SHA,
    app_operation: {operation_id: id, operation: 'read-local-pages', apk_sha256: SHA, operation_request_id: REQUEST,
      record_boot_id: BOOT, current_boot_id: BOOT, state: 'RELEASED', reservation_released: true,
      release_reason: 'MATCHED_RELEASE_RECEIPT', managed_media: false, network_write: false}};
  for (const key of ['readOnly', 'contactDataReadOnly', 'bridgeHandshake', 'appServiceStartRequested', 'maintenanceGatePassed',
    'activeApkHashMatched', 'appIdentityMatched', 'installedApkHashMatched', 'componentMatched', 'vendorServiceStartRequested',
    'vendorServiceStartAccepted', 'bindingRequested', 'bindAccepted', 'messengerBinderVerified', 'contacts_requested',
    'contactRequestSent', 'contactValuesRead', 'replyChannelClosed', 'unbindAttempted', 'unbindConfirmed']) value[key] = true;
  return value;
}
function reply(closed = false) {
  return {schemaVersion: 1, kind: 'NEXUI_APP_LOCAL_PAGE', state: 'CONTACTS_PAGE_VERIFIED', ok: true,
    readOnly: true, source: 'nexui_messenger', contact_type: 'LOCAL', ownerPackage: 'com.starnet.dial',
    vendorRequestSent: false, all_sources_complete: false, expectedApkSha256: SHA, snapshot_id: ID, snapshot_closed: closed,
    maintenanceGatePassed: true, activeApkHashMatched: true, bridgeHandshake: true, appServiceStartRequested: true,
    app_pid: 123, app_uid: 10001};
}
function page(offset = 0, count = 2, limit = 1) {
  const size = Math.min(limit, count - offset), items = [];
  for (let i = 0; i < size; i++) items.push({mId: i + offset, mName: NAME, mNumbers: [NUMBER]});
  return {...reply(), page: {...descriptor(count), items, offset, next_offset: offset + size,
    has_more: offset + size < count, page_complete: offset + size === count, cursor_scope: 'SAME_SNAPSHOT_ONLY', contact_values_emitted: !!size}};
}

test('新操作类型必须真实绑定并保留既有清理门', () => {
  assert.equal(validateOpen(open(), 0, SHA, VERSION, 'test-pages', BOOT).record_count, 2);
  for (const mutate of [v => v.operation = 'read_local_metadata', v => v.app_operation.operation = 'read-local-metadata',
    v => v.app_operation.reservation_released = false, v => v.replyChannelClosed = false,
    v => v.page_snapshot.snapshot_id = REQUEST, v => v.page_snapshot.items = [{mName: NAME}],
    v => v.page_snapshot.contact_type = 'BLUETOOTH', v => v.page_snapshot.cross_boot = true,
    v => v.local.end_observed = false, v => v.page_snapshot.record_count = 4097]) {
    const v = open(); mutate(v); assert.throws(() => validateOpen(v, 0, SHA, VERSION, 'test-pages', BOOT));
  }
});
test('页的来源世代游标总数必须一致且不能停滞', () => {
  const meta = descriptor(); assert.equal(validatePage(page(), 0, SHA, meta, 0, 1).next_offset, 1);
  for (const mutate of [v => v.page.snapshot_id = REQUEST, v => v.page.contact_type = 'BLUETOOTH',
    v => v.page.sampled_at_ms = 2, v => v.page.record_count = 3, v => v.page.offset = 1,
    v => v.page.next_offset = 0, v => v.page.next_offset = 2, v => v.page.items = [],
    v => v.page.has_more = false, v => v.page.page_complete = true, v => v.page.items[0] = 3,
    v => v.page.retention_ms = 240000, v => v.vendorRequestSent = true, v => v.snapshot_closed = true,
    v => v.expectedApkSha256 = 'b'.repeat(64)]) {
    const v = page(); mutate(v); assert.throws(() => validatePage(v, 0, SHA, meta, 0, 1));
  }
});
test('空的完整快照可以结束，但缺失不能伪装为空', () => {
  assert.equal(validatePage(page(0, 0), 0, SHA, descriptor(0), 0, 1).page_complete, true);
  assert.throws(() => validatePage({ok: false, state: 'CONTACTS_SNAPSHOT_GONE'}, 1, SHA, descriptor(), 0, 1));
});
test('关闭必须有肯定回执且不能夹带内容', () => {
  validateClose(reply(true), 0, SHA, ID);
  assert.throws(() => validateClose(reply(false), 0, SHA, ID));
  assert.throws(() => validateClose({...reply(true), page: {}}, 0, SHA, ID));
});

function harness(mode = 'good', count = 2) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'contacts-page-host-')), capture = path.join(root, 'capture');
  const commands = [], printed = [], apk = `/data/local/d31-remote/releases/${SHA}/remote.apk`;
  let closed = false, pages = 0;
  const run = async (exe, args, options) => {
    assert.deepEqual(args.slice(0, 4), ['-P', '5042', '-s', '127.0.0.1:5654']);
    assert.equal(options.timeout, 22000); assert.equal(options.encoding, 'buffer');
    const text = args[5]; commands.push(text);
    const marker = /echo (D31_CONTACTS_PAGE_[a-f0-9]+)_\$code$/.exec(text)[1];
    let result, code = 0;
    if (text.includes('ContactsPageCommand open')) {
      const id = / open [a-f0-9]{64} ([A-Za-z0-9_-]+)/.exec(text)[1];
      result = open(id, count);
      if (mode === 'open-rejected') { result = {ok: false, state: 'APP_OPERATION_KIND_INVALID'}; code = 1; }
    } else if (text.includes('ContactsPageCommand page')) {
      pages++;
      const match = / page [a-f0-9]{64} [a-f0-9-]{36} ([0-9]+) ([0-9]+)/.exec(text);
      if (closed) { result = {ok: false, state: 'CONTACTS_SNAPSHOT_GONE'}; code = 1; }
      else if (mode === 'page-timeout' && pages === 2) throw Object.assign(Error(NAME), {stdout: Buffer.from(NUMBER), stderr: Buffer.from(NAME)});
      else if (mode === 'malformed' && pages === 1) result = NAME;
      else {
        result = page(Number(match[1]), count, Number(match[2]));
        if (mode === 'changed-repeat' && pages === count + 1) result.page.items[0].mName = 'different';
        if (mode === 'wrong-snapshot' && pages === 2) result.page.snapshot_id = REQUEST;
      }
    } else if (text.includes('ContactsPageCommand close')) {
      if (mode === 'close-failed') { result = {ok: false, state: 'CONTACTS_APP_DIED'}; code = 1; }
      else { result = reply(true); closed = true; }
    } else if (text.includes('id -u;')) result = `0\n23\nhct6735_66_m0\nhct6737t_66_m0\nvendor/device:6.0/test\n${BOOT}`;
    else if (text.includes('active.json')) result = {package: 'net.elfradio.d31bootstrap', path: apk, sha256: SHA, versionCode: VERSION};
    else if (text.includes('sha256sum')) result = `${SHA}  /synthetic.apk`;
    else if (text.includes('pm path')) result = 'package:/data/app/net.elfradio.d31bootstrap-1/base.apk';
    else result = '(nothing)';
    return {stdout: Buffer.from(`${typeof result === 'string' ? result : JSON.stringify(result)}\n${marker}_${code}\n`), stderr: Buffer.alloc(0)};
  };
  return {capture, commands, printed, invoke: () => main(['127.0.0.1:5654', String(VERSION), SHA, capture],
    {run, delay: async () => {}, output: text => printed.push(text)})};
}
test('宿主只建立一次快照，两页、复读、关闭、关闭后拒绝，摘要无个人值', async () => {
  const h = harness(); const result = await h.invoke();
  assert.equal(result.passed, true); assert.equal(result.crossPageExercised, true); assert.equal(result.recordCount, 2);
  assert.equal(h.commands.filter(v => v.includes('ContactsPageCommand open')).length, 1);
  assert.equal(h.commands.filter(v => v.includes('ContactsPageCommand close')).length, 1);
  const summary = fs.readFileSync(path.join(h.capture, 'result.json'), 'utf8');
  for (const text of [summary, ...h.printed]) for (const privateValue of [NAME, NUMBER, BOOT, ID, SHA]) assert.equal(text.includes(privateValue), false);
  const raw = fs.readdirSync(h.capture).filter(v => v.includes('-page-stdout-private.bin'));
  assert.equal(raw.length, 2); assert.ok(raw.some(v => fs.readFileSync(path.join(h.capture, v), 'utf8').includes(NAME)));
});
test('空列表验收明确未覆盖跨页，不伪称两页测试', async () => {
  const h = harness('good', 0); const result = await h.invoke();
  assert.equal(result.passed, true); assert.equal(result.crossPageExercised, false); assert.equal(result.recordCount, 0);
});
for (const mode of ['page-timeout', 'malformed', 'changed-repeat', 'wrong-snapshot', 'close-failed']) {
  test(`宿主${mode}保留私有原件并关闭同号快照，不重新采集`, async () => {
    const h = harness(mode); await assert.rejects(h.invoke());
    const summary = fs.readFileSync(path.join(h.capture, 'result.json'), 'utf8');
    assert.equal(JSON.parse(summary).passed, false); assert.equal(summary.includes(NAME), false); assert.equal(summary.includes(NUMBER), false);
    assert.equal(h.commands.filter(v => v.includes('ContactsPageCommand open')).length, 1);
    assert.equal(h.commands.filter(v => v.includes('ContactsPageCommand close')).length, 1);
    assert.equal(h.commands.some(v => /kill|reboot|reset|ContactsAppCommand read-local-metadata/.test(v)), false);
  });
}
test('公共操作未接线明确失败，不回退到metadata或绕过预留', async () => {
  const h = harness('open-rejected'); await assert.rejects(h.invoke());
  assert.equal(h.commands.filter(v => v.includes('ContactsPageCommand open')).length, 1);
  assert.equal(h.commands.some(v => v.includes('ContactsPageCommand page') || v.includes('ContactsPageCommand close')), false);
});
test('旧版本和非完整序列号在任何设备命令前拒绝', async () => {
  let calls = 0;
  for (const args of [['127.0.0.1:5654', '128', SHA, 'unused'], ['0', '130', SHA, 'unused'],
    ['127.0.0.1:5654', '130', SHA, 'unused', '33']]) {
    await assert.rejects(main(args, {run: async () => { calls++; }, output: () => {}}));
  }
  assert.equal(calls, 0);
});
