import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import {isDeepStrictEqual as equal} from 'node:util';
import {QueueStore, hash, requireThat as need} from './fault-transfer/QueueStore.mjs';
import {WebTransport} from './fault-transfer/WebTransport.mjs';
import {validateBaseline, validateParent} from './Collect-ProductConfiguration.mjs';

export const MAX_APK_BYTES = 8 * 1024 * 1024;
const PACKAGE = 'com.starnet.getnumber';
const read = p => JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, ''));
const terminal = new Set(['success', 'failed', 'rejected', 'expired', 'cancelled']);
const io = () => ({signal: AbortSignal.timeout(25000)});
const baseline = '/system/bin/getprop ro.build.fingerprint\n/system/bin/cat /proc/sys/kernel/random/boot_id\n/system/bin/cat /data/local/d31-remote/runtime/active.json';
const quote = value => "'" + value.replaceAll("'", "'\\''") + "'";

export function validateArchivePath(value) {
  need(typeof value === 'string' && value.length <= 512
    && /^\/(?:data\/app|system|vendor)\/(?:[A-Za-z0-9_][A-Za-z0-9_.+=-]*\/)*[A-Za-z0-9_][A-Za-z0-9_.+=-]*\.apk$/.test(value),
  'APK_PATH_UNSAFE');
  return value;
}

/** 固定包的单个基础APK；不猜split，不创建设备侧临时文件。 */
export function snapshotCommand(packageName, withIdentity = false) {
  need(packageName === PACKAGE, 'PACKAGE_NOT_ALLOWED');
  const command = [
    'set -eu',
    'bb=/system/bin/busybox',
    'listed=$(/system/bin/pm path ' + quote(packageName) + ')',
    'case "$listed" in package:*) ;; *) exit 21 ;; esac',
    'apk=${listed#package:}',
    'case "$apk" in *[!A-Za-z0-9_./+=-]*|*"//"*|*"/../"*|*"/./"*) exit 22 ;; esac',
    'case "$apk" in /data/app/*.apk|/system/*.apk|/vendor/*.apk) ;; *) exit 23 ;; esac',
    '[ "${#apk}" -le 512 ]',
    '[ -f "$apk" ] || exit 25',
    '[ ! -L "$apk" ] || exit 25',
    '[ "$("$bb" readlink -f "$apk")" = "$apk" ] || exit 25',
    'size=$(/system/bin/stat -c %s "$apk")',
    'case "$size" in ""|*[!0-9]*) exit 24 ;; esac',
    '[ "$size" -gt 0 ] || exit 24',
    '[ "$size" -le ' + MAX_APK_BYTES + ' ] || exit 24',
    'before=$(/system/bin/stat -c "%d|%i|%f|%s|%u|%g|%Y|%Z" "$apk")',
    'first=$("$bb" sha256sum "$apk")',
    'middle=$(/system/bin/stat -c "%d|%i|%f|%s|%u|%g|%Y|%Z" "$apk")',
    'second=$("$bb" sha256sum "$apk")',
    'after=$(/system/bin/stat -c "%d|%i|%f|%s|%u|%g|%Y|%Z" "$apk")',
    '[ "$before" = "$middle" ] || exit 26',
    '[ "$middle" = "$after" ] || exit 26',
    '[ "$first" = "$second" ] || exit 26',
    '[ "$listed" = "$(/system/bin/pm path ' + quote(packageName) + ')" ] || exit 26',
    '[ -f "$apk" ] || exit 25',
    '[ ! -L "$apk" ] || exit 25',
    '[ "$("$bb" readlink -f "$apk")" = "$apk" ] || exit 25',
    'digest=${first%% *}',
    'printf "%s\\n" D31_INSTALLED_ARCHIVE_V1 ' + quote('package=' + packageName) + ' "path=$apk" "stat=$before" "sha256=$digest" END_ARCHIVE'
  ].join('\n');
  return withIdentity ? command + '\nprintf "%s\\n" D31_IDENTITY_V1\n' + baseline : command;
}

export function validateSnapshot(text, options, withIdentity = false) {
  need(typeof text === 'string' && text.length <= 16384, 'SNAPSHOT_LIMIT');
  const lines = text.replace(/\r\n/g, '\n').replace(/\n$/, '').split('\n');
  need(lines.length >= 6 && lines[0] === 'D31_INSTALLED_ARCHIVE_V1'
    && lines[1] === 'package=' + options.packageName && options.packageName === PACKAGE
    && lines[2].startsWith('path=') && lines[3].startsWith('stat=') && lines[4].startsWith('sha256=')
    && lines[5] === 'END_ARCHIVE', 'SNAPSHOT_FORMAT_INVALID');
  const apkPath = validateArchivePath(lines[2].slice(5));
  const stat = lines[3].slice(5).split('|');
  // D31原生stat的设备号为十六进制；作为原始标识比较，不按十进制解释。
  need(stat.length === 8 && stat.every((value, i) => i === 0 ? /^[a-f0-9]{1,16}$/.test(value)
    : i === 2 ? /^[a-f0-9]{1,8}$/.test(value)
    : /^(0|[1-9][0-9]{0,19})$/.test(value)), 'APK_STAT_INVALID');
  need((parseInt(stat[2], 16) & 0xf000) === 0x8000, 'APK_NOT_REGULAR');
  const bytes = Number(stat[3]), sha256 = lines[4].slice(7);
  need(Number.isSafeInteger(bytes) && bytes > 0 && bytes <= MAX_APK_BYTES
    && /^[a-f0-9]{64}$/.test(sha256), 'APK_SIZE_OR_DIGEST_INVALID');
  const archive = {packageName: options.packageName, path: apkPath, bytes, sha256,
    stat: {device: stat[0], inode: stat[1], modeHex: stat[2], bytes: stat[3], uid: stat[4], gid: stat[5], mtime: stat[6], ctime: stat[7]}};
  if (!withIdentity) { need(lines.length === 6, 'SNAPSHOT_TRAILING_DATA'); return {archive}; }
  need(lines[6] === 'D31_IDENTITY_V1', 'FINAL_IDENTITY_MISSING');
  return {archive, identity: validateBaseline(lines.slice(7).join('\n'), options)};
}

/** 复用原ROOT父任务校验；文件回执独立限定8MiB，不放宽原工具的1MiB报告限制。 */
export function validateArchiveParent(task, request) {
  if (request.type === 'root_exec') return validateParent(task, request);
  need(request.type === 'get_file' && task?.id === request.id && task.type === request.type
    && task.state === 'success', 'PARENT_NOT_SUCCESS');
  need(task.device_id === undefined || task.device_id === request.device_id, 'PARENT_DEVICE_CHANGED');
  need(task.params === undefined || equal(task.params, request.params), 'PARENT_PARAMS_CHANGED');
  const result = task.result;
  need(result?.action === 'uploaded' && Number.isSafeInteger(result.bytes) && result.bytes > 0
    && result.bytes <= MAX_APK_BYTES && /^[a-f0-9]{64}$/.test(result.sha256), 'FILE_RESULT_INVALID');
  return result;
}

function localBytes(filename, archive, returned) {
  const before = fs.lstatSync(filename);
  need(before.isFile() && !before.isSymbolicLink() && before.size === archive.bytes
    && before.size === returned.bytes && before.size <= MAX_APK_BYTES, 'LOCAL_ARCHIVE_SIZE_OR_TYPE');
  const bytes = fs.readFileSync(filename), after = fs.lstatSync(filename);
  need(before.dev === after.dev && before.ino === after.ino && before.size === after.size
    && before.mtimeMs === after.mtimeMs && before.ctimeMs === after.ctimeMs
    && bytes.length === archive.bytes && hash(bytes) === archive.sha256 && hash(bytes) === returned.sha256,
  'LOCAL_ARCHIVE_CHANGED');
  return bytes;
}

/** 默认只预览；所有任务先持久化再入队，中断后只查询原号。 */
export async function collect(options, {transport, now = Date.now,
  wait = ms => new Promise(resolve => setTimeout(resolve, ms))} = {}) {
  need(options?.packageName === PACKAGE, 'PACKAGE_NOT_ALLOWED');
  need(/^[A-Za-z0-9_-]{1,96}$/.test(options.deviceId) && typeof options.deviceName === 'string'
    && options.deviceName.length > 0 && /^[a-f0-9]{64}$/.test(options.apkSha256)
    && Number.isSafeInteger(options.version) && options.version > 0 && typeof options.versionName === 'string'
    && options.versionName.length > 0 && typeof options.state === 'string' && options.state.length > 0,
  'EXPLICIT_TARGET_REQUIRED');
  need(options.execute === undefined || typeof options.execute === 'boolean', 'EXECUTE_FLAG_INVALID');
  if (options.execute !== true) return {status: 'LOCAL_PREVIEW', deviceActions: 0, packageName: PACKAGE,
    tasks: ['identity', 'snapshot', 'get_file', 'snapshot_and_identity'], files: ['installed.apk', 'archive-receipt.json']};
  const store = new QueueStore(options.state, {python: options.python || 'python'});
  return store.withLock(async () => {
    const binding = {deviceId: options.deviceId, deviceName: options.deviceName, version: options.version,
      versionName: options.versionName, apkSha256: options.apkSha256, packageName: options.packageName};
    let state = store.load();
    if (state) need(state.kind === 'installed-archive-v1' && equal(state.binding, binding), 'STATE_BINDING_CHANGED');
    else { state = {kind: 'installed-archive-v1', binding, steps: {}}; store.save(state); }
    const save = () => store.save(state);
    const t = transport || new WebTransport({session: read(options.session)});
    const devices = (await t.json('/api/devices', null, io())).devices;
    need(Array.isArray(devices), 'DEVICE_LIST_INVALID');
    const matches = devices.filter(d => d.id === options.deviceId && d.name === options.deviceName && d.model_id === 'mdl_d31');
    need(matches.length === 1 && matches[0].ready === true && matches[0].app_version === options.versionName, 'TARGET_NOT_READY');
    async function task(label, type, params) {
      let step = state.steps[label];
      if (!step) {
        step = state.steps[label] = {request: {id: 'archive-' + randomUUID(), device_id: options.deviceId, type,
          params, expires_at: now() + 600000}}; save();
      }
      need(step.request.type === type && step.request.device_id === options.deviceId && equal(step.request.params, params), 'STEP_CHANGED');
      const until = now() + 150000;
      while (!step.result && now() < until) {
        const found = await t.queryTask(options.deviceId, step.request.id, io());
        if (found && terminal.has(found.state)) { step.result = found; save(); break; }
        if (!found && !step.accepted) {
          need(now() < step.request.expires_at, 'ORIGINAL_REQUEST_EXPIRED');
          step.intent = true; save();
          step.enqueue = await t.enqueue(step.request, io()); step.accepted = true; save();
        }
        await wait(2000);
      }
      need(step.result, 'PENDING_QUERY_ORIGINAL_TASK');
      return validateArchiveParent(step.result, step.request);
    }
    const root = command => ({cwd: '/', command, timeout: 90});
    const before = validateBaseline((await task('identity', 'root_exec', root(baseline))).text, options);
    const archive = validateSnapshot((await task('snapshot', 'root_exec', root(snapshotCommand(PACKAGE)))).text, options).archive;
    const returned = await task('file', 'get_file', {path: archive.path, allow_cellular: false});
    need(returned.bytes === archive.bytes && returned.sha256 === archive.sha256, 'RETURN_BINDING_CHANGED');
    const apkFile = store.file('installed.apk');
    if (!fs.existsSync(apkFile)) {
      store.capacity(archive.bytes * 2);
      const part = store.file('archive-' + randomUUID() + '.part');
      await t.download(options.deviceId, state.steps.file.request.id,
        {filename: part, bytes: returned.bytes, signal: AbortSignal.timeout(60000), onBytes() {}});
      const bytes = localBytes(part, archive, returned);
      store.writeNew('installed-' + randomUUID() + '.tmp', bytes, 'installed.apk');
    }
    localBytes(apkFile, archive, returned);
    const after = validateSnapshot((await task('after', 'root_exec', root(snapshotCommand(PACKAGE, true)))).text, options, true);
    need(equal(archive, after.archive), 'INSTALLED_ARCHIVE_CHANGED');
    need(equal(before, after.identity), 'OBSERVATION_IDENTITY_CHANGED');
    const receipt = {schemaVersion: 1, kind: 'INSTALLED_PACKAGE_ARCHIVE', packageName: PACKAGE,
      localFile: 'installed.apk', bytes: archive.bytes, sha256: archive.sha256, archive,
      identity: before, taskIds: Object.fromEntries(Object.entries(state.steps).map(([name, step]) => [name, step.request.id])),
      consistency: 'BOUNDED_BEFORE_AFTER_MATCH', atomicSnapshot: false, signatureVerification: 'NOT_PERFORMED',
      cleanupReceiptLocation: 'queue-state', systemConsistency: 'NOT_ASSESSED', repairExecuted: false};
    const receiptFile = store.file('archive-receipt.json');
    if (!fs.existsSync(receiptFile)) store.writeNew('receipt-' + randomUUID() + '.tmp', Buffer.from(JSON.stringify(receipt, null, 2)), 'archive-receipt.json');
    need(equal(read(receiptFile), receipt), 'LOCAL_RECEIPT_CHANGED');
    state.verified = {bytes: archive.bytes, sha256: archive.sha256, receiptSha256: hash(fs.readFileSync(receiptFile))}; save();
    if (!state.received || state.received.cleanup_pending === true) {
      localBytes(apkFile, archive, returned);
      need(equal(read(receiptFile), receipt), 'LOCAL_RECEIPT_CHANGED');
      // 本地原件和回执保全后，复用原传输的8MiB确认接口。
      state.receivedIntent = true; save();
      const received = await t.received(options.deviceId, state.steps.file.request.id,
        {size: returned.bytes, sha256: returned.sha256}, io());
      need(received?.ok === true && ((received.purged === true && received.cleanup_pending !== true)
        || (received.cleanup_pending === true && received.purged !== true)), 'RETURN_RECEIPT_RESPONSE_INVALID');
      state.received = received; save();
    }
    return {status: 'CAPTURE_VERIFIED', packageName: PACKAGE, bytes: archive.bytes, sha256: archive.sha256,
      archiveFile: apkFile, receiptFile, cleanupPending: state.received.cleanup_pending === true,
      atomicSnapshot: false, systemConsistency: 'NOT_ASSESSED', repairExecuted: false};
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const options = read(process.argv[2]); options.execute = process.argv.includes('--execute');
  console.log(JSON.stringify(await collect(options)));
}
