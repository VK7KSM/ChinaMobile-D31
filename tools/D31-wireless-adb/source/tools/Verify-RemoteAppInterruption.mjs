import fs from 'node:fs';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {randomUUID, createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {parseOutput} from './Verify-RemoteContactsBridge.mjs';

// 仅显式CLI运行才操作D31；只中断本次唯一命名的root采音CLI，不杀APP/核心/监督。
// node tools/Verify-RemoteAppInterruption.mjs <完整序列号> 128 <冻结APK摘要> <全新私有目录>
const check = (value, code) => { if (!value) throw Error(code); };
const safeError = error => /^INTERRUPTION_[A-Z0-9_]+$/.test(error?.message || '') ? error.message : 'INTERRUPTION_EVIDENCE_REQUIRED';
const hash = text => createHash('sha256').update(text).digest('hex');
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value);
const integer = value => Number.isSafeInteger(value) && value > 0;
const plain = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const ROOT = '/data/local/d31-remote/runtime/maintenance';
const PACKAGE = 'net.elfradio.d31bootstrap';

export function verifyRecord(record, {id, sha, boot, nice}) {
  check(record?.schema_version === 1 && record.operation_id === id && record.operation === 'local_audio_capture'
    && record.apk_sha256 === sha && record.boot_id === boot && plain(record.params)
    && Object.keys(record.params).length === 1 && record.params.duration_ms === 5000
    && ['PREPARED', 'ARMED', 'RELEASED'].includes(record.state), 'INTERRUPTION_RECORD_MISMATCH');
  check(integer(record.owner?.pid) && record.owner.uid === 0
    && /^[1-9][0-9]*$/.test(record.owner.start_time || '') && record.owner.process_name === nice,
  'INTERRUPTION_OWNER_MISMATCH');
  if (record.state === 'ARMED') check(uuid(record.request_id) && integer(record.app?.pid)
    && Number.isSafeInteger(record.app.uid) && record.app.uid >= 10000 && record.app.uid < 20000
    && /^[1-9][0-9]*$/.test(record.app.start_time || '') && record.app.process_name === PACKAGE
    && record.app.pid !== record.owner.pid, 'INTERRUPTION_APP_MISMATCH');
  return record;
}

export function verifyOwner(text, owner, nice) {
  const lines = text.split('\n');
  check(lines.length === 3 && /^UID:[0-9]+$/.test(lines[0]) && lines[1].startsWith('STAT:')
    && /^CMDHEX:[a-f0-9]+$/.test(lines[2]), 'INTERRUPTION_PROC_FORMAT');
  const stat = lines[1].slice(5), end = stat.lastIndexOf(')');
  const fields = stat.slice(end + 1).trim().split(/\s+/);
  check(end > 0 && stat.startsWith(`${owner.pid} (`) && fields.length >= 20 && fields[0] !== 'Z'
    && Number(lines[0].slice(4)) === 0 && owner.uid === 0 && fields[19] === owner.start_time
    && owner.process_name === nice && lines[2].slice(7).replace(/(?:00)+$/, '') === Buffer.from(nice).toString('hex'),
  'INTERRUPTION_PROC_IDENTITY_REJECTED');
}

export function verifyStatus(value, expected, requestId) {
  check(value?.operation_id === expected.id && value.operation === 'local_audio_capture'
    && value.apk_sha256 === expected.sha && value.record_boot_id === expected.boot && value.current_boot_id === expected.boot
    && value.managed_media === false && value.network_write === false
    && ['PREPARED', 'ARMED', 'RELEASED'].includes(value.state)
    && typeof value.reservation_released === 'boolean'
    && (!requestId || value.operation_request_id === requestId), 'INTERRUPTION_RECOVERY_BINDING');
  if (value.reservation_released) check(value.state === 'RELEASED'
    && ['MATCHED_RELEASE_RECEIPT', 'ORIGINAL_APP_PROCESS_ENDED', 'UNARMED_OWNER_ENDED', 'UNARMED_OWNER_ENDED_BEFORE_RESERVE'].includes(value.release_reason),
  'INTERRUPTION_RECOVERY_REASON');
  return value;
}

function reservationMatches(value, expected) {
  return plain(value) && Object.keys(value).length === 3 && value.kind === 'app_operation'
    && value.task_id === 'app-' + expected.id && value.plan_sha256 === expected.sha;
}

// 使用shell内建read解析内核Uid四字段，不依赖D31未编入的BusyBox stat。
export function observeOwner(owner) {
  return `uid=''; uid_fields=0
while read -r key real effective saved filesystem extra; do
  [ "$key" = 'Uid:' ] || continue
  [ -z "$extra" ] && [ "$real" = '0' ] && [ "$effective" = '0' ] && [ "$saved" = '0' ] && [ "$filesystem" = '0' ] || exit 85
  uid=$real; uid_fields=$((uid_fields + 1))
done < /proc/${owner.pid}/status
[ "$uid_fields" = '1' ] && [ "$uid" = '0' ] || exit 85
stat=$(cat /proc/${owner.pid}/stat) || exit 86
bytes=$(/system/bin/busybox od -An -v -tx1 /proc/${owner.pid}/cmdline) || exit 86
hex=$(printf '%s' "$bytes" | /system/bin/busybox tr -d ' \\n') || exit 86
case "$hex" in ''|*[!a-f0-9]*) exit 86;; esac
printf 'UID:%s\\nSTAT:%s\\nCMDHEX:%s\\n' "$uid" "$stat" "$hex"`;
}

// 可注入进程替身进行离线检查；真实CLI固定5042，所有实际命令均先写私有请求记录。
export async function main(args, deps = {}) {
  const [serial, versionText, sha, directory] = args;
  check(args.length === 4 && /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$/.test(serial || '')
    && serial.split(':')[0].split('.').every(v => Number(v) <= 255)
    && Number(serial.split(':')[1]) >= 1 && Number(serial.split(':')[1]) <= 65535
    && versionText === '128' && /^[a-f0-9]{64}$/.test(sha || '') && directory, 'INTERRUPTION_ARGUMENTS');
  const capture = path.resolve(directory);
  fs.mkdirSync(path.dirname(capture), {recursive: true}); fs.mkdirSync(capture, {mode: 0o700});
  const save = (name, value) => fs.writeFileSync(path.join(capture, name), JSON.stringify(value, null, 2), {flag: 'wx', mode: 0o600});
  for (const name of ['Verify-RemoteAppInterruption.mjs', 'Verify-RemoteContactsBridge.mjs'])
    fs.copyFileSync(new URL('./' + name, import.meta.url), path.join(capture, name), fs.constants.COPYFILE_EXCL);
  const execute = deps.execute || promisify(execFile), sleep = deps.sleep || (ms => new Promise(resolve => setTimeout(resolve, ms)));
  const now = deps.now || (() => performance.now());
  const id = 'interrupt-' + randomUUID(), nice = 'd31-int-' + randomUUID();
  const apk = `/data/local/d31-remote/releases/${sha}/remote.apk`, recordPath = `${ROOT}/app-operations/${id}.json`;
  const expected = {id, sha, nice, boot: null};
  const summary = {passed: false, operationId: id, durationMs: 5000, interruptionSent: false,
    signalCommandIssued: false, signalOutcome: 'NOT_ATTEMPTED',
    interruptionExecuted: false, ownerDeathConfirmed: false, reservationRetainedAfterRootDeath: false,
    recoveryReleased: false, captureStarted: null, audioPersisted: null, mediaNetworkStarted: null,
    scope: 'ONE_ROOT_CLI_SIGTERM_PERSISTENT_RESERVATION_NOT_CONTINUOUS_MEDIA', failure: null};
  save('scope-private.json', {...summary, serial, expectedVersionCode: 128, expectedApkSha256: sha, niceName: nice,
    target: 'THIS_ROOT_CLI_ONLY', signal: 'SIGTERM', sigkillAllowed: false, automaticCaptureRetry: false});
  let sequence = 0, captureJob, record, armed, installed, activeHash, requestId;
  async function read(label, command, timeout = 8000) {
    const prefix = `${String(++sequence).padStart(3, '0')}-${label}`, marker = 'D31_INTERRUPT_' + randomUUID().replace(/-/g, '');
    const wrapped = `# D31_APP_STEP ${label}\n( ${command}\n); result=$?; echo; echo ${marker}_$result`;
    const meta = {label, serial, command, marker, timeoutMs: timeout, startedAt: new Date().toISOString(), completed: false};
    save(prefix + '-request-private.json', meta);
    let stdout, stderr;
    try {
      const response = await execute('C:/Dev/android-sdk/platform-tools/adb.exe', ['-P', '5042', '-s', serial, 'shell', wrapped],
        {windowsHide: true, timeout, maxBuffer: 1048576, encoding: 'buffer'});
      stdout = response.stdout; stderr = response.stderr;
      const parsed = parseOutput(stdout, marker); meta.completed = true; meta.deviceExitCode = parsed.exitCode;
      return parsed;
    } catch (error) {
      stdout ??= error.stdout; stderr ??= error.stderr;
      meta.error = safeError(error); meta.hostExitCode = Number.isInteger(error.code) ? error.code : null; meta.hostKilled = error.killed === true;
      throw Error(meta.error);
    } finally {
      for (const [stream, bytes] of [['stdout', stdout], ['stderr', stderr]]) {
        meta[stream + 'Available'] = bytes !== undefined;
        fs.writeFileSync(path.join(capture, `${prefix}-${stream}-private.bin`), bytes ?? Buffer.alloc(0), {flag: 'wx', mode: 0o600});
      }
      save(prefix + '-result-private.json', meta);
    }
  }
  async function required(label, command, timeout) {
    const result = await read(label, command, timeout);
    check(result.exitCode === 0, 'INTERRUPTION_DEVICE_COMMAND_FAILED'); return result.text;
  }
  const shellHash = file => `$(/system/bin/busybox sha256sum '${file}' | /system/bin/busybox cut -d ' ' -f 1)`;
  function continuity() {
    return `[ "$(cat /proc/sys/kernel/random/boot_id)" = '${expected.boot}' ] || exit 81
[ "${shellHash('/data/local/d31-remote/runtime/active.json')}" = '${activeHash}' ] || exit 82
[ "${shellHash(apk)}" = '${sha}' ] || exit 83
[ "${shellHash(installed)}" = '${sha}' ] || exit 84
[ "$(pm path ${PACKAGE})" = 'package:${installed}' ] || exit 84`;
  }
  async function readRecord(label) {
    const value = await required(label, `if [ -f '${recordPath}' ] && [ ! -L '${recordPath}' ]; then cat '${recordPath}'; else echo RECORD_MISSING; fi`);
    if (value === 'RECORD_MISSING') return null;
    record = verifyRecord(JSON.parse(value), expected); return {record, text: value};
  }
  try {
    const build = (await required('baseline', 'id -u; getprop ro.build.version.sdk; getprop ro.product.device; getprop ro.product.model; getprop ro.build.fingerprint; cat /proc/sys/kernel/random/boot_id')).split('\n');
    check(build.length === 6 && build[0] === '0' && build[1] === '23' && build[2] === 'hct6735_66_m0'
      && build[3] === 'hct6737t_66_m0' && build[4].includes(':6.0/') && uuid(build[5]), 'INTERRUPTION_DEVICE_IDENTITY');
    expected.boot = build[5];
    const activeText = await required('active-before', 'cat /data/local/d31-remote/runtime/active.json');
    const active = JSON.parse(activeText);
    check(active.path === apk && active.sha256 === sha && active.versionCode === 128 && active.package === PACKAGE, 'INTERRUPTION_ACTIVE_MISMATCH');
    // active.json由现有原子JSON写入器生成，无结尾换行；设备摘要单独读取，避免宿主trim改变摘要。
    activeHash = (await required('active-digest', `busybox sha256sum /data/local/d31-remote/runtime/active.json`)).split(/\s+/)[0];
    check(/^[a-f0-9]{64}$/.test(activeHash), 'INTERRUPTION_ACTIVE_DIGEST');
    const packagePath = await required('installed-path', `pm path ${PACKAGE}`);
    check(/^package:\/data\/app\/net\.elfradio\.d31bootstrap-[0-9]+\/base\.apk$/.test(packagePath), 'INTERRUPTION_INSTALLED_PATH');
    installed = packagePath.slice(8);
    const packageText = await required('installed-version', `dumpsys package ${PACKAGE}`);
    check(/\bversionCode=128(?:\s|$)/m.test(packageText), 'INTERRUPTION_INSTALLED_VERSION');
    await required('preflight', `${continuity()}
if [ -e '${ROOT}/repair.json' ] || [ -L '${ROOT}/repair.json' ] || [ -e '${recordPath}' ] || [ -L '${recordPath}' ]; then exit 87; fi
${observeOwner({pid: '$$'})}
echo READY`);
    // 不在设备shell使用裸&；宿主并行等待原命令和独立只读观察。
    captureJob = read('capture', `${continuity()}
CLASSPATH='${apk}' /system/bin/app_process /system/bin --nice-name='${nice}' ${PACKAGE}.RemoteMediaCommand local_audio_capture ${sha} ${id} 5000`, 25000)
      .then(value => ({value}), error => ({error: safeError(error)}));
    const deadline = now() + 6000;
    for (let attempt = 0; attempt < 20 && now() < deadline; attempt++) {
      const found = await readRecord('observe-record');
      if (found?.record.state === 'ARMED') { armed = found; requestId = record.request_id; break; }
      if (found?.record.state === 'RELEASED') break;
      await sleep(150);
    }
    if (!armed) throw Error('INTERRUPTION_ARMED_WINDOW_NOT_OBSERVED');
    // ARM原件已由read()持久保存，另留原字节与解析副本供核对；只采用记录中的唯一root owner。
    fs.writeFileSync(path.join(capture, 'armed-record-device-json-private.txt'), armed.text, {flag: 'wx', mode: 0o600});
    save('armed-record-private.json', armed.record);
    verifyOwner(await required('owner-before', observeOwner(record.owner)), record.owner, nice);
    const owner = record.owner;
    summary.signalCommandIssued = true; summary.interruptionSent = null; summary.signalOutcome = 'REFUSED_OR_UNKNOWN';
    const signal = await read('signal-root', `${continuity()}
[ "${shellHash(recordPath)}" = '${hash(armed.text)}' ] || exit 88
${observeOwner(owner)}
[ "$uid" = '0' ] || exit 89
rest=\${stat##*) }; set -- $rest; [ "$#" -ge 20 ] || exit 90
[ "$1" != 'Z' ] || exit 90
shift 19; [ "$1" = '${owner.start_time}' ] || exit 90
name=$(/system/bin/busybox tr '\\000' '\\n' < /proc/${owner.pid}/cmdline) || exit 91
[ "$name" = '${nice}' ] || exit 91
kill -TERM ${owner.pid} || exit 92
echo ROOT_TERM_SENT`);
    if (signal.exitCode >= 81 && signal.exitCode <= 91) { summary.interruptionSent = false; summary.signalOutcome = 'GUARD_REFUSED'; }
    check(signal.exitCode === 0 && signal.text.split('\n').at(-1) === 'ROOT_TERM_SENT', 'INTERRUPTION_LIVE_GUARD_REJECTED_OR_UNKNOWN');
    summary.interruptionSent = true; summary.signalOutcome = 'SENT_CONFIRMED';
    const completion = await captureJob; save('capture-completion-private.json', completion);
    check(!completion.error, 'INTERRUPTION_CAPTURE_EXIT_UNKNOWN');
    const death = await required('owner-after', `if [ ! -d /proc/${owner.pid} ]; then echo ROOT_ABSENT; else cat /proc/${owner.pid}/stat; fi`);
    const end = death.lastIndexOf(')');
    check(death === 'ROOT_ABSENT' || end > 0 && death.startsWith(`${owner.pid} (`)
      && /^[0-9]+$/.test(death.slice(end + 1).trim().split(/\s+/)[19] || '')
      && death.slice(end + 1).trim().split(/\s+/)[19] !== owner.start_time, 'INTERRUPTION_OWNER_DEATH_UNKNOWN');
    summary.ownerDeathConfirmed = true; summary.interruptionExecuted = true;
    await required('continuity-after-interruption', continuity());
    const after = await readRecord('record-after-interruption');
    check(after?.record.state === 'ARMED' && after.record.request_id === requestId, 'INTERRUPTION_RECORD_RELEASED_EARLY');
    const reservation = JSON.parse(await required('reservation-after-interruption', `cat '${ROOT}/repair.json'`));
    check(reservationMatches(reservation, expected), 'INTERRUPTION_RESERVATION_NOT_RETAINED');
    const status = verifyStatus(JSON.parse(await required('query-after-interruption', `${continuity()}
CLASSPATH='${apk}' /system/bin/app_process /system/bin ${PACKAGE}.RemoteAppOperation query ${sha} ${id}`, 15000)), expected, requestId);
    check(status.state === 'ARMED' && status.reservation_released === false, 'INTERRUPTION_QUERY_RELEASED_EARLY');
    summary.reservationRetainedAfterRootDeath = true;
  } catch (error) { summary.failure = safeError(error); }
  finally {
    // 不补发采音、不杀APP；原CLI有界结束后，最多三次同号恢复，未知继续保留预留。
    if (captureJob) {
      try {
        save('capture-final-private.json', await captureJob);
        await required('continuity-before-cleanup', continuity());
        const found = await readRecord('record-before-cleanup');
        if (found) {
          let releaseClaimed = false;
          for (let attempt = 0; attempt < 3; attempt++) {
            const status = verifyStatus(JSON.parse(await required('recover-original', `${continuity()}
CLASSPATH='${apk}' /system/bin/app_process /system/bin ${PACKAGE}.RemoteAppOperation recover ${sha} ${id}`, 15000)), expected, requestId);
            if (status.reservation_released) { releaseClaimed = true; break; }
            await sleep(300);
          }
          check(releaseClaimed, 'INTERRUPTION_RECOVERY_UNCONFIRMED');
          const final = await readRecord('record-final');
          check(final?.record.state === 'RELEASED' && (!requestId || final.record.request_id === requestId), 'INTERRUPTION_FINAL_RECORD');
          await required('reservation-final', `if [ -e '${ROOT}/repair.json' ] || [ -L '${ROOT}/repair.json' ]; then cat '${ROOT}/repair.json'; exit 93; fi; echo RESERVATION_ABSENT`);
          summary.recoveryReleased = true;
          const value = final.record.last_result;
          if (value) {
            check(value.operation === 'local_audio_capture' && value.diagnostic_id === id && value.duration_ms === 5000
              && value.operation_request_id === final.record.request_id && value.app_pid === final.record.app?.pid && value.app_uid === final.record.app?.uid
              && value.audio_persisted === false && value.network_started === false, 'INTERRUPTION_FINAL_RECEIPT_SCOPE');
            summary.captureStarted = value.recording_started === true;
            summary.audioPersisted = value.audio_persisted; summary.mediaNetworkStarted = value.network_started;
          }
        } else throw Error('INTERRUPTION_RECORD_NEVER_CREATED');
      } catch (error) { summary.failure ??= safeError(error); summary.cleanupFailure = safeError(error); }
      try { await required('continuity-final', continuity()); } catch (error) { summary.failure ??= safeError(error); }
    }
    summary.passed = !summary.failure && summary.interruptionExecuted && summary.reservationRetainedAfterRootDeath && summary.recoveryReleased;
    summary.identityCheckAndSignalAtomic = false;
    save('result.json', summary);
  }
  return summary;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href)
  main(process.argv.slice(2)).then(result => { console.log(JSON.stringify(result)); if (!result.passed) process.exitCode = 1; })
    .catch(error => { console.error(safeError(error)); process.exitCode = 1; });
