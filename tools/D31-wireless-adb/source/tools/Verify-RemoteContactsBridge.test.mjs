import test from 'node:test';
import assert from 'node:assert/strict';
import {parseOutput, validateBinding} from './Verify-RemoteContactsBridge.mjs';

const receipt = () => ({schemaVersion: 1, kind: 'NEXUI_APP_BIND_CHECK', readOnly: true, bindFlags: 0,
  vendorServiceStartRequested: false, contactRequestSent: false, contacts_requested: false,
  contactValuesRead: false, contactValuesEmitted: false, listComplete: false,
  bridgeHandshake: true, appServiceStartRequested: true,
  app_pid: 1234, app_uid: 10042,
  maintenanceGatePassed: true, activeApkHashMatched: true, appIdentityMatched: true,
  installedApkHashMatched: true, componentMatched: true, bindingRequested: true,
  remoteOutcomeKnown: true, unbindAttempted: true, unbindConfirmed: true,
  state: 'BINDING_VERIFIED', ok: true, bindAccepted: true, messengerBinderVerified: true});

test('成功绑定也不能宣称取得列表', () => {
  assert.deepEqual(validateBinding(receipt(), 0), {bridgeVerified: true, vendorBindingVerified: true, listVerified: false});
});
test('无原厂服务的明确拒绝与成功分开', () => {
  const value = {...receipt(), state: 'CONTACTS_BIND_REJECTED', ok: false, bindAccepted: false, messengerBinderVerified: false};
  assert.equal(validateBinding(value, 1).vendorBindingVerified, false);
  assert.throws(() => validateBinding(value, 0));
});
test('接受后超时不报告原厂绑定通过', () => {
  assert.equal(validateBinding({...receipt(), state: 'CONTACTS_TIMEOUT', ok: false, messengerBinderVerified: false}, 1).vendorBindingVerified, false);
});
test('清理、身份、只读范围缺失均拒绝', () => {
  for (const key of ['bridgeHandshake', 'appServiceStartRequested', 'unbindConfirmed', 'appIdentityMatched', 'installedApkHashMatched', 'activeApkHashMatched', 'maintenanceGatePassed'])
    assert.throws(() => validateBinding({...receipt(), [key]: false}, 0), key);
  for (const key of ['contactRequestSent', 'contactValuesRead', 'listComplete', 'vendorServiceStartRequested'])
    assert.throws(() => validateBinding({...receipt(), [key]: true}, 0), key);
});
test('旧ADB换行保持退出状态；缺失或重复标记不通过', () => {
  assert.deepEqual(parseOutput(Buffer.from('{}\r\r\nMARK_1\r\r\n'), 'MARK'), {exitCode: 1, text: '{}'});
  assert.throws(() => parseOutput(Buffer.from('{}'), 'MARK'));
  assert.throws(() => parseOutput(Buffer.from('MARK_0\n{}\nMARK_0'), 'MARK'));
});
