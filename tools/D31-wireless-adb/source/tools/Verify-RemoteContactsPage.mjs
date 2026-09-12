import fs from 'node:fs';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify, isDeepStrictEqual} from 'node:util';
import {randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {parseOutput} from './Verify-RemoteContactsBridge.mjs';
import {validateRead} from './Verify-RemoteContactsRead.mjs';
import {validateAppOperation} from './Verify-AppOperation.mjs';

// 默认每页一条以验证跨页；仅原始私有证据含联系人值，控制台和result.json仅含脱敏统计。
// node Verify-RemoteContactsPage.mjs <D31完整序列号> <版本> <APK摘要> <全新私有目录> [每页条数]
const execute = promisify(execFile);
const check = (value, code) => { if (!value) throw Error(code); };
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const integer = (value, min, max) => Number.isSafeInteger(value) && value >= min && value <= max;
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value);
const safe = error => /^CONTACTS_PAGE_HOST_[A-Z0-9_]+$/.test(error?.message || '') ? error.message : 'CONTACTS_PAGE_HOST_EVIDENCE_REQUIRED';
const added = new Set('retention_ms max_page_records page_consistency storage cross_process_restart cross_boot'.split(' '));
const common = new Set(('ok read_only source owner_package contact_type snapshot_id status sampled_at_ms elapsed_ms record_count '
  + 'frames_received received_chars start_observed end_observed list_complete contact_values_emitted completion_scope '
  + 'all_sources_complete snapshot_consistency android_equivalence service_implementation max_records max_frames '
  + 'max_received_chars wait_budget_ms').split(' '));

function snapshot(value) {
  check(object(value) && value.ok === true && value.read_only === true && value.source === 'nexui_messenger'
    && value.owner_package === 'com.starnet.dial' && value.contact_type === 'LOCAL' && uuid(value.snapshot_id)
    && value.status === 'COMPLETED' && value.end_observed === true && value.list_complete === true
    && value.all_sources_complete === false && value.snapshot_consistency === 'NOT_PROVIDED_BY_VENDOR'
    && value.page_consistency === 'IMMUTABLE_RECEIVED_REPLY' && value.storage === 'APP_PROCESS_MEMORY'
    && value.cross_process_restart === false && value.cross_boot === false
    && value.retention_ms === 120000 && value.max_page_records === 32
    && value.completion_scope === 'SELECTED_SOURCE_ALL_CONTACTS_REPLY'
    && integer(value.record_count, 0, 4096), 'CONTACTS_PAGE_HOST_SNAPSHOT_SCOPE');
}
export function validateOpen(value, exitCode, sha, version, operationId, boot) {
  check(object(value) && value.operation === 'read_local_pages' && object(value.page_snapshot)
    && value.app_operation?.operation === 'read-local-pages', 'CONTACTS_PAGE_HOST_OPEN_SCOPE');
  validateAppOperation(value.app_operation, {operation: 'read-local-pages', id: operationId, version,
    requestId: value.operation_request_id, sha, boot});
  // 先验证新操作类型，再复用既有严格元数据/清理白名单；不改变原始回执。
  const metadata = structuredClone(value);
  delete metadata.page_snapshot;
  metadata.operation = 'read_local_metadata'; metadata.app_operation.operation = 'read-local-metadata';
  validateRead(metadata, exitCode, sha, version, operationId, boot);
  const descriptor = value.page_snapshot; snapshot(descriptor);
  check(descriptor.contact_values_emitted === false
    && Object.keys(descriptor).every(key => common.has(key) || added.has(key)), 'CONTACTS_PAGE_HOST_OPEN_PERSONAL_FIELDS');
  for (const key of common) check(isDeepStrictEqual(descriptor[key], value.local[key]), 'CONTACTS_PAGE_HOST_SNAPSHOT_MISMATCH');
  return structuredClone(descriptor);
}

function envelope(value, exitCode, sha, id) {
  check(object(value) && exitCode === 0 && value.ok === true && value.schemaVersion === 1
    && value.kind === 'NEXUI_APP_LOCAL_PAGE' && value.state === 'CONTACTS_PAGE_VERIFIED'
    && value.expectedApkSha256 === sha && value.snapshot_id === id && value.readOnly === true
    && value.source === 'nexui_messenger' && value.contact_type === 'LOCAL' && value.ownerPackage === 'com.starnet.dial'
    && value.vendorRequestSent === false && value.all_sources_complete === false
    && value.maintenanceGatePassed === true && value.activeApkHashMatched === true
    && value.bridgeHandshake === true && value.appServiceStartRequested === true
    && integer(value.app_pid, 1, 2147483647) && integer(value.app_uid, 10000, 19999), 'CONTACTS_PAGE_HOST_REPLY_SCOPE');
  const keys = new Set(('schemaVersion kind state ok readOnly source contact_type ownerPackage vendorRequestSent all_sources_complete '
    + 'snapshot_id snapshot_closed page app_pid app_uid appServiceStartRequested bridgeHandshake expectedApkSha256 '
    + 'maintenanceGatePassed activeApkHashMatched').split(' '));
  check(Object.keys(value).every(key => keys.has(key)), 'CONTACTS_PAGE_HOST_REPLY_FIELDS');
}
export function validatePage(value, exitCode, sha, descriptor, offset, limit) {
  envelope(value, exitCode, sha, descriptor.snapshot_id);
  const page = value.page; snapshot(page);
  check(value.snapshot_closed === false && object(page), 'CONTACTS_PAGE_HOST_PAGE_MISSING');
  const keys = new Set([...common, ...added, 'items', 'offset', 'next_offset', 'has_more', 'page_complete', 'cursor_scope']);
  check(Object.keys(page).every(key => keys.has(key)), 'CONTACTS_PAGE_HOST_PAGE_FIELDS');
  for (const key of [...common, ...added]) if (key !== 'contact_values_emitted')
    check(isDeepStrictEqual(page[key], descriptor[key]), 'CONTACTS_PAGE_HOST_SNAPSHOT_MISMATCH');
  check(Array.isArray(page.items) && page.items.every(object) && integer(page.items.length, 0, limit)
    && page.offset === offset && page.next_offset === offset + page.items.length
    && integer(page.next_offset, offset, descriptor.record_count)
    && page.has_more === (page.next_offset < descriptor.record_count)
    && page.page_complete === !page.has_more && page.cursor_scope === 'SAME_SNAPSHOT_ONLY'
    && page.contact_values_emitted === (page.items.length !== 0)
    && (!page.has_more || page.items.length > 0), 'CONTACTS_PAGE_HOST_CURSOR_INVALID');
  check(Buffer.byteLength(JSON.stringify(value)) <= 65536, 'CONTACTS_PAGE_HOST_SIZE_LIMIT');
  return page;
}
export function validateClose(value, exitCode, sha, id) {
  envelope(value, exitCode, sha, id);
  check(value.snapshot_closed === true && !Object.hasOwn(value, 'page'), 'CONTACTS_PAGE_HOST_CLOSE_UNCONFIRMED');
}

export async function main(args, {run = execute, delay = ms => new Promise(resolve => setTimeout(resolve, ms)), output = value => console.log(value)} = {}) {
  const [serial, versionText, sha, directory, sizeText = '1'] = args;
  check((args.length === 4 || args.length === 5) && /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$/.test(serial || '')
    && serial.split(':')[0].split('.').every(n => Number(n) <= 255) && integer(Number(serial.split(':')[1]), 1, 65535)
    && /^[1-9][0-9]{0,8}$/.test(versionText || '') && Number(versionText) > 128
    && /^[a-f0-9]{64}$/.test(sha || '') && directory && /^[1-9][0-9]?$/.test(sizeText)
    && integer(Number(sizeText), 1, 32), 'CONTACTS_PAGE_HOST_ARGUMENTS');
  const capture = path.resolve(directory), version = Number(versionText), limit = Number(sizeText);
  fs.mkdirSync(path.dirname(capture), {recursive: true}); fs.mkdirSync(capture, {mode: 0o700});
  const save = (name, value) => fs.writeFileSync(path.join(capture, name), JSON.stringify(value, null, 2), {flag: 'wx', mode: 0o600});
  for (const name of ['Verify-RemoteContactsPage.mjs', 'Verify-RemoteContactsBridge.mjs', 'Verify-RemoteContactsRead.mjs', 'Verify-AppOperation.mjs'])
    fs.copyFileSync(new URL(name, import.meta.url), path.join(capture, name), fs.constants.COPYFILE_EXCL);
  let sequence = 0, boot, descriptor, failure, closeConfirmed = false, readAttempted = false, pageCount = 0, recordCount = 0;
  const operationId = 'contacts-page-' + randomUUID(), apk = `/data/local/d31-remote/releases/${sha}/remote.apk`;
  const prefix = `CLASSPATH='${apk}' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.management.ContactsPageCommand`;
  async function command(label, text) {
    const name = `${String(++sequence).padStart(3, '0')}-${label}`, marker = 'D31_CONTACTS_PAGE_' + randomUUID().replace(/-/g, '');
    save(`${name}-request-private.json`, {serial, command: text, at: new Date().toISOString()});
    let stdout, stderr, status = {};
    try {
      const result = await run('C:/Dev/android-sdk/platform-tools/adb.exe', ['-P', '5042', '-s', serial, 'shell',
        `( ${text}\n); code=$?; echo; echo ${marker}_$code`], {windowsHide: true, timeout: 22000, maxBuffer: 524288, encoding: 'buffer'});
      stdout = result.stdout; stderr = result.stderr;
      const parsed = parseOutput(stdout, marker); status.deviceExitCode = parsed.exitCode;
      return parsed;
    } catch (error) { stdout ??= error.stdout; stderr ??= error.stderr; status.error = safe(error); throw Error(status.error); }
    finally {
      for (const [stream, bytes] of [['stdout', stdout], ['stderr', stderr]]) {
        status[stream + 'Available'] = bytes !== undefined;
        fs.writeFileSync(path.join(capture, `${name}-${stream}-private.bin`), bytes ?? Buffer.alloc(0), {flag: 'wx', mode: 0o600});
      }
      save(`${name}-metadata.json`, status);
    }
  }
  async function zero(label, text) {
    const result = await command(label, text); check(result.exitCode === 0, 'CONTACTS_PAGE_HOST_DEVICE_COMMAND_FAILED'); return result.text;
  }
  async function identity(label) {
    const values = (await zero(label + '-build', 'id -u; getprop ro.build.version.sdk; getprop ro.product.device; getprop ro.product.model; getprop ro.build.fingerprint; cat /proc/sys/kernel/random/boot_id')).split('\n');
    check(values.length === 6 && values[0] === '0' && values[1] === '23' && values[2] === 'hct6735_66_m0'
      && values[3] === 'hct6737t_66_m0' && values[4].includes(':6.0/') && uuid(values[5]), 'CONTACTS_PAGE_HOST_IDENTITY');
    if (boot) check(boot === values[5], 'CONTACTS_PAGE_HOST_BOOT_CHANGED'); else boot = values[5];
    const active = JSON.parse(await zero(label + '-active', 'cat /data/local/d31-remote/runtime/active.json'));
    check(active.package === 'net.elfradio.d31bootstrap' && active.path === apk && active.sha256 === sha && active.versionCode === version,
      'CONTACTS_PAGE_HOST_APK_IDENTITY');
    check((await zero(label + '-hash', `busybox sha256sum '${apk}'`)).split(/\s+/)[0] === sha, 'CONTACTS_PAGE_HOST_APK_HASH');
    const installed = await zero(label + '-package-path', 'pm path net.elfradio.d31bootstrap');
    check(/^package:\/[A-Za-z0-9._/-]+\.apk$/.test(installed) && !installed.includes('/../'), 'CONTACTS_PAGE_HOST_APK_IDENTITY');
    check((await zero(label + '-installed-hash', `busybox sha256sum '${installed.slice(8)}'`)).split(/\s+/)[0] === sha,
      'CONTACTS_PAGE_HOST_APK_HASH');
  }
  try {
    save('scope-private.json', {operationId, version, sha, pageSize: limit, maximumPages: 128, contentPrivateOnly: true, adbPort: 5042});
    await identity('before');
    await zero('vendor-before', 'dumpsys activity services com.starnet.dial');
    readAttempted = true;
    const opened = await command('open', `${prefix} open ${sha} ${operationId}`);
    const value = JSON.parse(opened.text); save('open-receipt-private.json', value);
    descriptor = validateOpen(value, opened.exitCode, sha, version, operationId, boot);
    let first;
    for (;;) {
      check(pageCount < 128, 'CONTACTS_PAGE_HOST_PAGE_LIMIT');
      const response = await command('page', `${prefix} page ${sha} ${descriptor.snapshot_id} ${recordCount} ${limit}`);
      const receipt = JSON.parse(response.text); const page = validatePage(receipt, response.exitCode, sha, descriptor, recordCount, limit);
      if (!first) first = structuredClone(page);
      pageCount++; recordCount = page.next_offset;
      if (!page.has_more) break;
    }
    const repeated = await command('repeat-first', `${prefix} page ${sha} ${descriptor.snapshot_id} 0 ${limit}`);
    const page = validatePage(JSON.parse(repeated.text), repeated.exitCode, sha, descriptor, 0, limit);
    check(isDeepStrictEqual(page, first), 'CONTACTS_PAGE_HOST_REPEAT_CHANGED');
    check(recordCount === descriptor.record_count, 'CONTACTS_PAGE_HOST_NOT_COMPLETE');
  } catch (error) { failure = safe(error); }
  finally {
    if (descriptor) try {
      const closed = await command('close', `${prefix} close ${sha} ${descriptor.snapshot_id}`);
      validateClose(JSON.parse(closed.text), closed.exitCode, sha, descriptor.snapshot_id); closeConfirmed = true;
      const stale = await command('closed-cursor', `${prefix} page ${sha} ${descriptor.snapshot_id} 0 ${limit}`);
      const value = JSON.parse(stale.text);
      check(stale.exitCode === 1 && value.ok === false && value.state === 'CONTACTS_SNAPSHOT_GONE' && !value.page,
        'CONTACTS_PAGE_HOST_CLOSED_CURSOR_ACCEPTED');
    } catch (error) { failure ??= safe(error); }
    if (readAttempted) {
      await delay(1500);
      for (const inspect of [
        () => zero('vendor-after', 'dumpsys activity services com.starnet.dial'),
        async () => check((await zero('service-after', 'dumpsys activity services net.elfradio.d31bootstrap/.management.ContactsAppService')).includes('(nothing)'), 'CONTACTS_PAGE_HOST_SERVICE_REMAINS'),
        () => identity('after'),
      ]) try { await inspect(); } catch (error) { failure ??= safe(error); }
    }
  }
  const summary = {passed: !failure && closeConfirmed, readAttempted, source: 'LOCAL', recordCount, pageCount,
    closeConfirmed, crossPageExercised: pageCount > 1, repeatFirstVerified: !failure && closeConfirmed,
    automaticRetry: false, personalValuesInSummary: false, webVerified: false, failure: failure ?? null};
  save('result.json', summary); output(JSON.stringify(summary));
  if (failure) throw Error(failure);
  return summary;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href)
  main(process.argv.slice(2)).catch(error => { console.error(safe(error)); process.exitCode = 1; });
