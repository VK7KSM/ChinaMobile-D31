import fs from 'node:fs';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {parseOutput} from './Verify-RemoteContactsBridge.mjs';
import {validateAppOperation} from './Verify-AppOperation.mjs';

// 显式运行才操作设备；沿用第八批四参数，新增严格LOCAL结束帧验收。
// node tools/Verify-RemoteContactsRead.mjs <完整D31序列号> <版本号> <活动SHA256> <全新私有目录>
const execute = promisify(execFile);
const check = (value, code) => { if (!value) throw Error(code); };
const safeCode = error => error?.message === 'CONTACTS_MAINTENANCE_BUSY'
  || /^CONTACTS_HOST_[A-Z0-9_]+$/.test(error?.message || '') ? error.message : 'CONTACTS_HOST_EVIDENCE_REQUIRED';
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const integer = (value, min, max) => Number.isSafeInteger(value) && value >= min && value <= max;

export function validateRead(value, exitCode, sha, version = 0, operationId, boot) {
  check(object(value) && value.schemaVersion === 1 && value.kind === 'NEXUI_APP_LOCAL_METADATA'
    && value.operation === 'read_local_metadata' && value.contact_type === 'LOCAL'
    && value.ownerPackage === 'com.starnet.dial' && value.bindFlags === 1
    && (value.expectedApkSha256 === undefined || value.expectedApkSha256 === sha), 'CONTACTS_HOST_RECEIPT_SCOPE');
  // 根取锁早退尚未附加摘要；仅保留已知拒绝码，不放宽任何成功门或自动重试。
  if (exitCode === 1 && value.ok === false && value.state === 'CONTACTS_MAINTENANCE_BUSY')
    throw Error('CONTACTS_MAINTENANCE_BUSY');
  check(value.expectedApkSha256 === sha, 'CONTACTS_HOST_RECEIPT_SCOPE');
  check(exitCode === 0 && value.ok === true && value.state === 'LOCAL_METADATA_VERIFIED'
    && value.listComplete === true && value.remoteOutcomeKnown === true, 'CONTACTS_HOST_READ_NOT_COMPLETE');
  for (const field of ['readOnly', 'contactDataReadOnly', 'bridgeHandshake', 'appServiceStartRequested',
    'maintenanceGatePassed', 'activeApkHashMatched', 'appIdentityMatched', 'installedApkHashMatched',
    'componentMatched', 'vendorServiceStartRequested', 'vendorServiceStartAccepted', 'bindingRequested',
    'bindAccepted', 'messengerBinderVerified', 'contacts_requested', 'contactRequestSent', 'contactValuesRead',
    'replyChannelClosed', 'unbindAttempted', 'unbindConfirmed'])
    check(value[field] === true, 'CONTACTS_HOST_IDENTITY_OR_CLEANUP');
  check(integer(value.app_pid, 1, 2147483647) && integer(value.app_uid, 10000, 19999)
    && value.contactValuesEmitted === false && value.all_sources_complete === false
    && value.vendorServiceStopRequested === false && value.vendorLifecycleRestored === false
    && value.vendorStartupEffects === 'NOT_VERIFIED', 'CONTACTS_HOST_BOUNDARY_MISMATCH');
  const local = value.local;
  check(object(local) && local.ok === true && local.read_only === true
    && local.source === 'nexui_messenger' && local.owner_package === 'com.starnet.dial'
    && local.contact_type === 'LOCAL' && local.status === 'COMPLETED' && local.end_observed === true
    && local.list_complete === true && local.contact_values_emitted === false
    && local.all_sources_complete === false && local.completion_scope === 'SELECTED_SOURCE_ALL_CONTACTS_REPLY'
    && local.snapshot_consistency === 'NOT_PROVIDED_BY_VENDOR', 'CONTACTS_HOST_LOCAL_END_MISSING');
  check(integer(local.record_count, 0, 4096) && integer(local.frames_received, 1, 128)
    && integer(local.received_chars, 2, 1048576) && integer(local.effective_wait_budget_ms, 1, 8000)
    && typeof local.start_observed === 'boolean' && /^[a-f0-9-]{36}$/.test(local.snapshot_id || '')
    && integer(local.sampled_at_ms, 1, Number.MAX_SAFE_INTEGER) && integer(local.elapsed_ms, 0, 8000)
    && integer(value.elapsedMs, 0, 12000) && local.android_equivalence === 'NOT_VERIFIED'
    && local.service_implementation === 'NOT_DECRYPTED' && local.max_records === 4096 && local.max_frames === 128
    && local.max_received_chars === 1048576 && local.wait_budget_ms === 8000, 'CONTACTS_HOST_LOCAL_BUDGET');
  let reservationVerified;
  try {
    reservationVerified = validateAppOperation(value.app_operation, {operation: 'read-local-metadata',
      id: operationId ?? value.operation_id, version, requestId: value.operation_request_id, sha, boot});
  } catch { throw Error('CONTACTS_HOST_RESERVATION_NOT_RELEASED'); }
  if (reservationVerified) check(value.operation_id === value.app_operation.operation_id
    && value.operation_apk_sha256 === sha, 'CONTACTS_HOST_OPERATION_BINDING');
  // 严格字段白名单防止未来误把原厂条目或个人值附加到本次元数据回执。
  const topKeys = new Set(('schemaVersion kind state ok readOnly ownerPackage bindFlags vendorServiceStartRequested '
    + 'contactRequestSent contacts_requested contactValuesRead contactValuesEmitted listComplete operation contact_type '
    + 'vendorServiceStopRequested all_sources_complete contactDataReadOnly vendorLifecycleRestored vendorStartupEffects '
    + 'appIdentityMatched installedApkHashMatched componentMatched vendorServiceStartAccepted bindingRequested bindAccepted '
    + 'messengerBinderVerified unbindAttempted unbindConfirmed replyChannelClosed remoteOutcomeKnown elapsedMs local '
    + 'app_pid app_uid appServiceStartRequested bridgeHandshake expectedApkSha256 maintenanceGatePassed activeApkHashMatched '
    + 'app_operation operation_id operation_request_id operation_apk_sha256').split(' '));
  const localKeys = new Set(('ok read_only source owner_package contact_type snapshot_id status sampled_at_ms elapsed_ms '
    + 'record_count frames_received received_chars start_observed end_observed list_complete contact_values_emitted '
    + 'completion_scope all_sources_complete snapshot_consistency android_equivalence service_implementation max_records '
    + 'max_frames max_received_chars wait_budget_ms effective_wait_budget_ms').split(' '));
  check(Object.keys(value).every(key => topKeys.has(key)) && Object.keys(local).every(key => localKeys.has(key)), 'CONTACTS_HOST_UNEXPECTED_FIELDS');
  check(Object.entries(value).every(([key, item]) => key === 'local' || key === 'app_operation' || !object(item) && !Array.isArray(item))
    && Object.values(local).every(item => !object(item) && !Array.isArray(item)), 'CONTACTS_HOST_PERSONAL_PAYLOAD_REJECTED');
  return {localEndVerified: true, listVerified: true, contactValuesEmitted: false, reservationVerified,
    recordCount: local.record_count, framesReceived: local.frames_received, source: 'LOCAL',
    completionScope: 'SELECTED_SOURCE_ALL_CONTACTS_REPLY', vendorStartupEffects: 'NOT_VERIFIED', vendorLifecycleRestored: false};
}

export async function main(args, {run = execute, delay = ms => new Promise(resolve => setTimeout(resolve, ms))} = {}) {
  const [serial, versionText, sha, directory] = args;
  check(args.length === 4 && /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$/.test(serial || '')
    && serial.split(':')[0].split('.').every(n => Number(n) <= 255)
    && integer(Number(serial.split(':')[1]), 1, 65535) && /^[1-9][0-9]{0,8}$/.test(versionText || '')
    && /^[a-f0-9]{64}$/.test(sha || '') && directory, 'CONTACTS_HOST_ARGUMENTS');
  const capture = path.resolve(directory), version = Number(versionText);
  fs.mkdirSync(path.dirname(capture), {recursive: true});
  fs.mkdirSync(capture, {mode: 0o700});
  const save = (name, value) => fs.writeFileSync(path.join(capture, name), JSON.stringify(value, null, 2), {flag: 'wx', mode: 0o600});
  fs.copyFileSync(new URL(import.meta.url), path.join(capture, 'Verify-RemoteContactsRead.mjs'), fs.constants.COPYFILE_EXCL);
  fs.copyFileSync(new URL('./Verify-RemoteContactsBridge.mjs', import.meta.url), path.join(capture, 'Verify-RemoteContactsBridge.mjs'), fs.constants.COPYFILE_EXCL);
  fs.copyFileSync(new URL('./Verify-AppOperation.mjs', import.meta.url), path.join(capture, 'Verify-AppOperation.mjs'), fs.constants.COPYFILE_EXCL);
  const operationId = 'contacts-' + randomUUID();
  let sequence = 0, readAttempted = false, result, failure, boot;
  async function command(label, commandText, requireZero = true) {
    const prefix = `${String(++sequence).padStart(2, '0')}-${label}`;
    const marker = 'D31_CONTACTS_' + randomUUID().replace(/-/g, '');
    const wrapped = `( ${commandText}\n); code=$?; echo; echo ${marker}_$code`;
    save(`${prefix}-request-private.json`, {serial, command: commandText, at: new Date().toISOString(), deadlineMs: 22000});
    const metadata = {completed: false}; let stdout, stderr;
    try {
      const response = await run('C:/Dev/android-sdk/platform-tools/adb.exe', ['-P', '5042', '-s', serial, 'shell', wrapped],
        {windowsHide: true, timeout: 22000, maxBuffer: 524288, encoding: 'buffer'});
      stdout = response.stdout; stderr = response.stderr;
      const parsed = parseOutput(stdout, marker);
      metadata.completed = true; metadata.deviceExitCode = parsed.exitCode;
      if (requireZero) check(parsed.exitCode === 0, 'CONTACTS_HOST_DEVICE_COMMAND_FAILED');
      return parsed;
    } catch (error) {
      stdout ??= error.stdout; stderr ??= error.stderr;
      metadata.error = safeCode(error); throw Error(metadata.error);
    } finally {
      // 先保存原始输出，JSON解析在本函数返回后进行；异常同样保全，不重发。
      try {
        for (const [stream, bytes] of [['stdout', stdout], ['stderr', stderr]]) {
          metadata[`${stream}Available`] = bytes !== undefined;
          fs.writeFileSync(path.join(capture, `${prefix}-${stream}-private.bin`), bytes ?? Buffer.alloc(0), {flag: 'wx', mode: 0o600});
        }
      } finally { save(`${prefix}-metadata-private.json`, metadata); }
    }
  }
  const apk = `/data/local/d31-remote/releases/${sha}/remote.apk`;
  async function identity(label) {
    const build = (await command(`${label}-build`, 'id -u; getprop ro.build.version.sdk; getprop ro.product.device; getprop ro.product.model; getprop ro.build.fingerprint; cat /proc/sys/kernel/random/boot_id')).text.split('\n');
    check(build.length === 6 && build[0] === '0' && build[1] === '23' && build[2] === 'hct6735_66_m0'
      && build[3] === 'hct6737t_66_m0' && build[4].includes(':6.0/') && /^[a-f0-9-]{36}$/.test(build[5]), 'CONTACTS_HOST_D31_IDENTITY');
    if (boot) check(boot === build[5], 'CONTACTS_HOST_BOOT_CHANGED'); else boot = build[5];
    const active = JSON.parse((await command(`${label}-active`, 'cat /data/local/d31-remote/runtime/active.json')).text);
    check(active.package === 'net.elfradio.d31bootstrap' && active.path === apk && active.sha256 === sha
      && active.versionCode === version, 'CONTACTS_HOST_ACTIVE_MISMATCH');
    check((await command(`${label}-active-hash`, `busybox sha256sum '${apk}'`)).text.split(/\s+/)[0] === sha, 'CONTACTS_HOST_ACTIVE_HASH');
    const packageText = (await command(`${label}-package`, 'dumpsys package net.elfradio.d31bootstrap')).text;
    check(new RegExp(`\\bversionCode=${version}(?:\\s|$)`, 'm').test(packageText), 'CONTACTS_HOST_INSTALLED_VERSION');
    const packagePath = (await command(`${label}-package-path`, 'pm path net.elfradio.d31bootstrap')).text;
    check(/^package:\/[A-Za-z0-9._/-]+\.apk$/.test(packagePath) && !packagePath.includes('/../'), 'CONTACTS_HOST_INSTALLED_PATH');
    check((await command(`${label}-installed-hash`, `busybox sha256sum '${packagePath.slice(8)}'`)).text.split(/\s+/)[0] === sha, 'CONTACTS_HOST_INSTALLED_HASH');
  }
  try {
    save('scope.json', {kind: 'D31_LOCAL_CONTACTS_METADATA', expectedVersionCode: version, expectedApkSha256: sha,
      adbPort: 5042, retries: 0, operationId, vendorStartAuthorized: true, vendorStopRequested: false, personalValuesRequestedForOutput: false});
    await identity('before');
    await command('vendor-before', 'dumpsys activity services com.starnet.dial');
    readAttempted = true;
    const response = await command('local-read', `CLASSPATH='${apk}' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.management.ContactsAppCommand read-local-metadata ${sha}${version >= 128 ? ' ' + operationId : ''}`, false);
    const value = JSON.parse(response.text); save('receipt-private.json', value);
    result = validateRead(value, response.exitCode, sha, version, version >= 128 ? operationId : undefined, boot);
  } catch (error) { failure = safeCode(error); }
  if (readAttempted) {
    // 后置只读取证，失败也不重新读取通讯录、不停止原厂或APP服务。
    await delay(1500);
    for (const inspect of [
      () => command('vendor-after', 'dumpsys activity services com.starnet.dial'),
      async () => {
        const services = await command('app-service-after', 'dumpsys activity services net.elfradio.d31bootstrap/.management.ContactsAppService');
        check(services.text.includes('(nothing)'), 'CONTACTS_HOST_APP_SERVICE_NOT_EXITED');
      },
      () => identity('after'),
    ]) {
      try { await inspect(); } catch (error) { failure ??= safeCode(error); }
    }
  }
  const summary = {passed: !failure && !!result, ...result, failure: failure ?? null, readAttempted, operationId,
    automaticRetry: false, webVerified: false, serviceExitConfirmed: !failure && !!result};
  save('result.json', summary);
  if (failure) throw Error(failure);
  console.log(JSON.stringify(summary));
  return summary;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href)
  main(process.argv.slice(2)).catch(error => { console.error(safeCode(error)); process.exitCode = 1; });
