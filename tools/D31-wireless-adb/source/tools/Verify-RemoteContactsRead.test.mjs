import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {main, validateRead} from './Verify-RemoteContactsRead.mjs';

const SHA = 'a'.repeat(64);
const BOOT = '00000000-0000-0000-0000-000000000001';
function receipt() {
  const value = {schemaVersion: 1, kind: 'NEXUI_APP_LOCAL_METADATA', operation: 'read_local_metadata',
    contact_type: 'LOCAL', ownerPackage: 'com.starnet.dial', bindFlags: 1, expectedApkSha256: SHA,
    state: 'LOCAL_METADATA_VERIFIED', ok: true, listComplete: true, remoteOutcomeKnown: true,
    app_pid: 123, app_uid: 10001, contactValuesEmitted: false, all_sources_complete: false,
    vendorServiceStopRequested: false, vendorLifecycleRestored: false, vendorStartupEffects: 'NOT_VERIFIED', elapsedMs: 500,
    local: {ok: true, read_only: true, source: 'nexui_messenger', owner_package: 'com.starnet.dial',
      contact_type: 'LOCAL', status: 'COMPLETED', end_observed: true, start_observed: true, list_complete: true,
      contact_values_emitted: false, all_sources_complete: false, completion_scope: 'SELECTED_SOURCE_ALL_CONTACTS_REPLY',
      snapshot_consistency: 'NOT_PROVIDED_BY_VENDOR', record_count: 2, frames_received: 3, received_chars: 300,
      effective_wait_budget_ms: 8000, snapshot_id: BOOT, sampled_at_ms: 1, elapsed_ms: 200,
      android_equivalence: 'NOT_VERIFIED', service_implementation: 'NOT_DECRYPTED', max_records: 4096,
      max_frames: 128, max_received_chars: 1048576, wait_budget_ms: 8000}};
  for (const field of ['readOnly', 'contactDataReadOnly', 'bridgeHandshake', 'appServiceStartRequested',
    'maintenanceGatePassed', 'activeApkHashMatched', 'appIdentityMatched', 'installedApkHashMatched',
    'componentMatched', 'vendorServiceStartRequested', 'vendorServiceStartAccepted', 'bindingRequested',
    'bindAccepted', 'messengerBinderVerified', 'contacts_requested', 'contactRequestSent', 'contactValuesRead',
    'replyChannelClosed', 'unbindAttempted', 'unbindConfirmed']) value[field] = true;
  return value;
}

test('完整LOCAL结束帧及清理事实才通过', () => {
  assert.equal(validateRead(receipt(), 0, SHA).localEndVerified, true);
  const empty = receipt(); empty.local.record_count = 0; empty.local.start_observed = false;
  assert.equal(validateRead(empty, 0, SHA).recordCount, 0);
});
test('旧绑定探测和没有END不能冒充读取通过', () => {
  for (const change of [v => v.kind = 'NEXUI_APP_BIND_CHECK', v => v.local.end_observed = false,
    v => v.local.status = 'TIMEOUT', v => v.local.list_complete = false, v => v.listComplete = false]) {
    const value = receipt(); change(value); assert.throws(() => validateRead(value, 0, SHA));
  }
  assert.throws(() => validateRead(receipt(), 1, SHA));
});
test('所有身份与清理门缺失或未知都拒绝', () => {
  for (const field of ['maintenanceGatePassed', 'activeApkHashMatched', 'installedApkHashMatched',
    'appIdentityMatched', 'replyChannelClosed', 'unbindConfirmed', 'contactRequestSent']) {
    const value = receipt(); value[field] = null; assert.throws(() => validateRead(value, 0, SHA));
  }
  assert.throws(() => validateRead(receipt(), 0, 'b'.repeat(64)));
});
test('预算溢出及联系人字段拒绝', () => {
  for (const change of [v => v.local.record_count = 4097, v => v.local.frames_received = 0,
    v => v.local.received_chars = 1048577, v => v.local.items = [{mName: 'PRIVATE_SYNTHETIC'}],
    v => v.mNumbers = ['PRIVATE_SYNTHETIC'], v => v.contactValuesEmitted = true]) {
    const value = receipt(); change(value); assert.throws(() => validateRead(value, 0, SHA));
  }
});

function busyReceipt() {
  const value = receipt(); delete value.expectedApkSha256; delete value.local;
  value.ok = false; value.state = 'CONTACTS_MAINTENANCE_BUSY';
  value.listComplete = false; value.remoteOutcomeKnown = false;
  return value;
}
test('根维护忙早退缺少摘要时保留真实拒绝码，仍然失败', () => {
  assert.throws(() => validateRead(busyReceipt(), 1, SHA), /^Error: CONTACTS_MAINTENANCE_BUSY$/);
});
test('维护忙不能掩盖错误回执来源或冲突摘要', () => {
  for (const change of [v => v.kind = 'NEXUI_APP_BIND_CHECK', v => v.operation = 'metadata',
    v => v.expectedApkSha256 = 'b'.repeat(64)]) {
    const value = busyReceipt(); change(value);
    assert.throws(() => validateRead(value, 1, SHA), /CONTACTS_HOST_RECEIPT_SCOPE/);
  }
});
test('成功仍需摘要，未知或矛盾失败不能公开任意state', () => {
  const success = receipt(); delete success.expectedApkSha256;
  assert.throws(() => validateRead(success, 0, SHA), /CONTACTS_HOST_RECEIPT_SCOPE/);
  const unknown = busyReceipt(); unknown.state = 'PRIVATE_SYNTHETIC';
  assert.throws(() => validateRead(unknown, 1, SHA), /CONTACTS_HOST_RECEIPT_SCOPE/);
  assert.throws(() => validateRead(busyReceipt(), 0, SHA), /CONTACTS_HOST_RECEIPT_SCOPE/);
});

function harness(mode) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'd31-local-host-'));
  const capture = path.join(root, 'capture'); const commands = [];
  const apk = `/data/local/d31-remote/releases/${SHA}/remote.apk`;
  const run = async (_exe, args, options) => {
    assert.deepEqual(args.slice(0, 4), ['-P', '5042', '-s', '127.0.0.1:5654']);
    assert.equal(options.timeout, 22000);
    const wrapped = args[5]; commands.push(wrapped);
    const marker = /echo (D31_CONTACTS_[a-f0-9]+)_\$code$/.exec(wrapped)[1];
    let text, exitCode = 0;
    if (wrapped.includes('read-local-metadata')) {
      if (mode === 'invalid') text = 'PRIVATE_SYNTHETIC_NON_JSON';
      else if (mode === 'timeout') throw Object.assign(Error('PRIVATE_SYNTHETIC_ERROR'), {stdout: Buffer.from('PRIVATE_PARTIAL'), stderr: Buffer.from('PRIVATE_STDERR')});
      else if (mode === 'busy') { text = JSON.stringify(busyReceipt()); exitCode = 1; }
      else text = JSON.stringify(receipt());
    } else if (wrapped.includes('id -u;')) text = `0\n23\nhct6735_66_m0\nhct6737t_66_m0\nvendor/device:6.0/test\n${BOOT}`;
    else if (wrapped.includes('active.json')) text = JSON.stringify({package: 'net.elfradio.d31bootstrap', path: apk, sha256: SHA, versionCode: 124});
    else if (wrapped.includes('sha256sum')) text = `${SHA}  /synthetic.apk`;
    else if (wrapped.includes('dumpsys package')) text = 'versionCode=124 targetSdk=23';
    else if (wrapped.includes('pm path')) text = 'package:/data/app/net.elfradio.d31bootstrap-1/base.apk';
    else text = '(nothing)';
    return {stdout: Buffer.from(`${text}\n${marker}_${exitCode}\n`), stderr: Buffer.alloc(0)};
  };
  return {capture, commands, run, invoke: () => main(['127.0.0.1:5654', '124', SHA, capture], {run, delay: async () => {}})};
}
test('宿主完整流程仅发一次读取，保留原件及严格通过结果', async () => {
  const h = harness('good'); const result = await h.invoke();
  assert.equal(result.passed, true);
  assert.equal(h.commands.filter(c => c.includes('read-local-metadata')).length, 1);
  assert.ok(fs.readdirSync(h.capture).some(name => name.includes('local-read-stdout-private.bin')));
  assert.equal(result.webVerified, false);
});
test('维护忙通过宿主错误路径保留原码和原件，不重发并继续只读后置', async () => {
  const h = harness('busy'); await assert.rejects(h.invoke(), /^Error: CONTACTS_MAINTENANCE_BUSY$/);
  const result = JSON.parse(fs.readFileSync(path.join(h.capture, 'result.json')));
  assert.equal(result.failure, 'CONTACTS_MAINTENANCE_BUSY');
  assert.equal(result.passed, false); assert.equal(result.automaticRetry, false);
  assert.equal(result.serviceExitConfirmed, false);
  assert.equal(JSON.parse(fs.readFileSync(path.join(h.capture, 'receipt-private.json'))).state, 'CONTACTS_MAINTENANCE_BUSY');
  assert.ok(fs.readdirSync(h.capture).some(n => n.includes('local-read-stdout-private.bin')));
  assert.equal(h.commands.filter(c => c.includes('read-local-metadata')).length, 1);
  assert.ok(h.commands.at(-1).includes('sha256sum'));
});
test('非JSON失败仍保存原始stdout且不重读', async () => {
  const h = harness('invalid'); await assert.rejects(h.invoke(), /CONTACTS_HOST_EVIDENCE_REQUIRED/);
  const name = fs.readdirSync(h.capture).find(n => n.includes('local-read-stdout-private.bin'));
  assert.match(fs.readFileSync(path.join(h.capture, name), 'utf8'), /PRIVATE_SYNTHETIC_NON_JSON/);
  assert.equal(h.commands.filter(c => c.includes('read-local-metadata')).length, 1);
  assert.equal(JSON.parse(fs.readFileSync(path.join(h.capture, 'result.json'))).passed, false);
});
test('超时保留部分双流，不打印私有异常，不重发，继续只读后置', async () => {
  const h = harness('timeout'); await assert.rejects(h.invoke(), /CONTACTS_HOST_EVIDENCE_REQUIRED/);
  const name = fs.readdirSync(h.capture).find(n => n.includes('local-read-stdout-private.bin'));
  assert.equal(fs.readFileSync(path.join(h.capture, name), 'utf8'), 'PRIVATE_PARTIAL');
  assert.equal(h.commands.filter(c => c.includes('read-local-metadata')).length, 1);
  assert.ok(h.commands.at(-1).includes('sha256sum'));
});
test('已有capture拒绝覆盖且不触发设备命令', async () => {
  const h = harness('good'); fs.mkdirSync(h.capture);
  await assert.rejects(h.invoke(), /EEXIST/); assert.equal(h.commands.length, 0);
});
