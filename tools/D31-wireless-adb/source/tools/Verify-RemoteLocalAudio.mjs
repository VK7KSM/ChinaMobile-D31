import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {randomUUID} from 'node:crypto';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {WebTransport} from './fault-transfer/WebTransport.mjs';
import {validateTarget} from './fault-transfer/FaultTransferQueue.mjs';

const SERVICES = ['media.audio_flinger', 'media.audio_policy'];
const TERMINAL = ['success', 'failed', 'rejected', 'expired', 'cancelled'];
const ID = /^[A-Za-z0-9_-]{1,128}$/;
const BOOT = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const installedPattern = /^\/data\/app\/net\.elfradio\.d31bootstrap-[0-9]+\/base\.apk$/;
const requireThat = (value, message) => { if (!value) { const error = Error(message); error.safe = true; throw error; } };
const integer = value => Number.isSafeInteger(value) && value >= 0;

// 仅接受本脚本生成的唯一设备边界；两份转储仍不是原子快照。
export function parseDeviceOutput(raw, marker) {
  const text = raw.replace(/\r/g, '').trimEnd();
  for (const suffix of ['BEGIN', 'END'])
    requireThat(text.split('\n').filter(line => line === marker + '_' + suffix).length === 1, '设备边界标记缺失或重复');
  requireThat(text.split('\n').filter(line => line.startsWith(marker + '_EXIT_')).length === 1, '设备退出标记缺失或重复');
  const match = new RegExp(`^([0-9]+\\.[0-9]{2}) [0-9]+\\.[0-9]{2}\\n${marker}_BEGIN\\n([\\s\\S]*)\\n${marker}_END\\n([0-9]+\\.[0-9]{2}) [0-9]+\\.[0-9]{2}\\n${marker}_EXIT_([0-9]+)$`).exec(text);
  requireThat(match, '设备时间或边界格式不符');
  const began = Math.round(Number(match[1]) * 1000), ended = Math.round(Number(match[3]) * 1000);
  requireThat(integer(began) && integer(ended) && ended >= began, '设备单调时间不符');
  return {text: match[2], exitCode: Number(match[4]), deviceStartedElapsedMs: began,
    deviceFinishedElapsedMs: ended, deviceFinishedUpperElapsedMs: ended + 10};
}

export function validateReceipt(response, id, versionCode) {
  const v = response?.result;
  requireThat(response?.query_completed === true && response.operation === 'local_audio_capture'
    && response.managed_media === false && response.capture_started === true && response.version_code === versionCode
    && v?.schemaVersion === 1 && v.operation === 'local_audio_capture' && v.diagnostic_id === id
    && v.state === 'COMPLETED' && v.managed_media === false, '诊断身份、版本或终态不符');
  requireThat(integer(v.app_pid) && v.app_pid > 0 && integer(v.app_uid) && v.app_uid >= 10000
    && integer(v.audio_session_id) && v.audio_session_id > 0, '缺少真实APP及音频session身份');
  requireThat(v.duration_ms === 2500 && v.sample_rate_hz === 16000 && v.channels === 1
    && v.encoding === 'PCM_16BIT' && v.frame_definition === 'MONO_PCM_SAMPLE'
    && integer(v.bytes_read) && v.bytes_read > 0 && v.bytes_read <= 80000
    && integer(v.frames_read) && v.frames_read * 2 === v.bytes_read
    && integer(v.read_calls) && v.read_calls > 0 && v.read_error === 0 && v.error_code === '', '采样配置或读取结果不符');
  requireThat(integer(v.started_elapsed_ms) && integer(v.capture_started_elapsed_ms)
    && integer(v.finished_elapsed_ms) && v.started_elapsed_ms <= v.capture_started_elapsed_ms
    && v.capture_started_elapsed_ms < v.finished_elapsed_ms
    && v.finished_elapsed_ms - v.started_elapsed_ms <= 8750, '诊断单调起止或有界结束不符');
  requireThat(v.audio_record_created === true && v.initialized === true && v.initial_record_state === 1
    && v.recording_started === true && v.release_completed === true && v.release_verified === true
    && v.worker_finished === true && v.stop_completed === true && v.stop_error === '' && v.release_error === ''
    && v.record_state_after_release === 0 && v.recording_state_after_release === 1
    && v.audio_persisted === false && v.network_started === false, '真实创建、释放或无保存合同不符');
  return v;
}

// 依赖注入仅供离线替身核验；命令行始终使用明确序列号及5042。
export async function verifyLocalAudio(argv, deps = {}) {
  requireThat(argv.length === 6, '需要会话、全新目录、设备名称、版本、活动APK及完整序列号');
  const [sessionPath, capture, deviceName, expectedVersion, activeApk, serial] = argv;
  const target = validateTarget({deviceName, expectedVersion, activeApk});
  requireThat(sessionPath && capture && !fs.existsSync(capture)
    && /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$/.test(serial || ''), '捕获目录或D31序列号不符');
  const [host, port] = serial.split(':');
  requireThat(host.split('.').every(n => Number(n) <= 255) && Number(port) >= 1 && Number(port) <= 65535, 'D31地址或端口不符');
  const session = JSON.parse(fs.readFileSync(sessionPath, 'utf8').replace(/^\uFEFF/, ''));
  const transport = deps.transport || new WebTransport({session});
  const execute = deps.execute || promisify(execFile);
  const now = deps.now || (() => performance.now());
  const sleep = deps.sleep || (ms => new Promise(resolve => setTimeout(resolve, ms)));
  fs.mkdirSync(capture, {recursive: true});
  fs.copyFileSync(new URL(import.meta.url), path.join(capture, 'host.mjs'), fs.constants.COPYFILE_EXCL);
  const save = (name, value) => fs.writeFileSync(path.join(capture, name), JSON.stringify(value, null, 2), {flag: 'wx'});
  const write = (name, value) => fs.writeFileSync(path.join(capture, name), value, {flag: 'wx'});
  const options = (until = Infinity) => {
    const remaining = Math.min(20000, until - now());
    requireThat(remaining >= 1, '宿主观察预算耗尽');
    return {signal: AbortSignal.timeout(Math.ceil(remaining))};
  };
  async function readDevice(label, command, until = Infinity) {
    const marker = 'D31_META_' + randomUUID().replace(/-/g, '');
    const wrapped = `cat /proc/uptime || exit 91; echo ${marker}_BEGIN; ( ${command}\n); result=$?; echo; echo ${marker}_END; cat /proc/uptime || exit 92; echo ${marker}_EXIT_$result`;
    const status = {completed: false, command, hostStartedMonotonicMs: now(), hostStartedAt: new Date().toISOString(),
      deviceTimeSource: 'PROC_UPTIME_10MS_RESOLUTION', atomicSnapshot: false};
    let raw, stderr;
    try {
      const remaining = Math.min(8000, until - now());
      requireThat(remaining >= 1, '设备读取预算耗尽');
      const result = await execute('C:/Dev/android-sdk/platform-tools/adb.exe', ['-P', '5042', '-s', serial, 'shell', wrapped],
        {windowsHide: true, timeout: Math.ceil(remaining), maxBuffer: 524288, encoding: 'buffer'});
      raw = result.stdout; stderr = result.stderr;
      const parsed = parseDeviceOutput(raw.toString('utf8'), marker);
      Object.assign(status, {deviceExitCode: parsed.exitCode, deviceStartedElapsedMs: parsed.deviceStartedElapsedMs,
        deviceFinishedElapsedMs: parsed.deviceFinishedElapsedMs, deviceFinishedUpperElapsedMs: parsed.deviceFinishedUpperElapsedMs});
      requireThat(parsed.exitCode === 0 && !stderr.toString('utf8').trim(), '设备命令失败或有错误输出');
      write(label + '-derived-private.txt', parsed.text);
      status.completed = true;
      return {...parsed, completed: true};
    } catch (error) {
      raw ??= error.stdout; stderr ??= error.stderr;
      status.hostExitCode = Number.isInteger(error.code) ? error.code : null;
      status.hostTimedOutOrKilled = error.killed === true;
      status.hostBufferExceeded = error.code === 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER';
      status.reason = error.safe ? error.message : 'HOST_READ_FAILED';
      // 不回显进程异常对象，避免异常消息携带原始设备数据。
      return {completed: false};
    } finally {
      if (raw !== undefined) write(label + '-raw-private.txt', raw);
      if (stderr !== undefined) write(label + '-stderr-private.txt', stderr);
      status.rawAvailable = raw !== undefined;
      status.stderrAvailable = stderr !== undefined;
      status.hostFinishedMonotonicMs = now();
      save(label + '-status.json', status);
    }
  }
  async function required(label, command, until) {
    const result = await readDevice(label, command, until);
    requireThat(result.completed, '设备只读核验失败，参见该步私有证据');
    return result;
  }
  const baseline = await required('baseline', 'getprop ro.product.device; getprop ro.product.model; getprop ro.build.version.sdk; cat /data/local/d31-remote/runtime/active.json');
  const lines = baseline.text.trim().split('\n');
  const active = JSON.parse(lines.slice(3).join('\n'));
  requireThat(lines[0] === 'hct6735_66_m0' && lines[1] === 'hct6737t_66_m0' && lines[2] === '23'
    && active.path === activeApk && /^[a-f0-9]{64}$/.test(active.sha256)
    && activeApk === `/data/local/d31-remote/releases/${active.sha256}/remote.apk`
    && active.versionName === expectedVersion && integer(active.versionCode) && active.versionCode > 0, 'ADB设备或活动版本不符');
  // RemoteState的device_id是受限ASCII字符串；只提取该字段，token不离开设备。
  const identity = (await required('registered-id', `busybox sed -n 's/.*"device_id"[[:space:]]*:[[:space:]]*"\\([A-Za-z0-9_-]\\{1,128\\}\\)".*/\\1/p' /data/local/d31-remote/runtime/state/identity.json`)).text.trim();
  requireThat(ID.test(identity), '本地注册身份不符');
  const boot = (await required('boot-before', 'cat /proc/sys/kernel/random/boot_id')).text.trim();
  requireThat(BOOT.test(boot), '启动标识不符');
  const installed = (await required('installed-path', 'pm path net.elfradio.d31bootstrap')).text.trim().replace(/^package:/, '');
  requireThat(installedPattern.test(installed), 'APP安装路径不符');
  const installedHash = (await required('installed-hash', `busybox sha256sum '${installed}'`)).text.trim().split(/\s+/);
  requireThat(installedHash.length === 2 && installedHash[0] === active.sha256 && installedHash[1] === installed, 'APP安装摘要不符');
  const resolved = await transport.resolveTarget(target, options());
  requireThat(resolved.id === identity, 'Web与ADB不是同一注册设备');
  const inventory = await transport.json('/api/devices', null, options());
  const matches = inventory.devices?.filter(value => value.id === identity);
  requireThat(matches?.length === 1, 'Web设备身份不唯一');
  const device = matches[0];
  requireThat(device.name === deviceName && device.model_id === 'mdl_d31' && device.ready === true
    && device.app_version === expectedVersion && device.managed_media === false, 'Web目标状态或能力不符');
  save('device-before-private.json', device);
  const diagnosticId = 'local-audio-' + randomUUID();
  const request = {device_id: device.id, id: 'd31-local-audio-' + randomUUID(), type: 'root_exec',
    params: {cwd: '/', timeout: 30, command: `CLASSPATH='${activeApk}' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteMediaCommand local_audio_capture ${active.sha256} ${diagnosticId} 2500`},
    expires_at: Date.now() + 180000};
  save('request-private.json', request);
  const deadline = now() + 120000;
  const groups = [], after = [];
  let stop = false, collector, task, receipt, failure, submitted = false, sameBoot = false;
  let failureReason = '';
  const checkTask = value => requireThat(value && value.id === request.id && value.type === request.type,
    'Web返回的任务编号或类型不符');
  async function collect() {
    for (let count = 0; count < 10 && !stop && now() < deadline; count++) {
      const group = [];
      for (const service of SERVICES) group.push(await readDevice(`${String(count + 1).padStart(2, '0')}-${service}`, 'dumpsys ' + service, deadline));
      groups.push(group);
      if (!stop && count < 9) await sleep(Math.min(1000, Math.max(0, deadline - now())));
    }
  }
  try {
    // 先启动有界观察，再并行提交和查询；HTTP等待不吞掉2500毫秒采样窗口。
    collector = collect().catch(() => { failure = true; stop = true; failureReason = '观察原件归档失败'; });
    submitted = true;
    const enqueued = await transport.enqueue(request, options(deadline));
    save('enqueue-private.json', enqueued); checkTask(enqueued);
    let query = 0;
    while (now() < deadline) {
      task = await transport.queryTask(device.id, request.id, options(deadline));
      save(`query-${String(++query).padStart(3, '0')}-private.json`, task);
      if (task) { checkTask(task); if (TERMINAL.includes(task.state)) break; }
      await sleep(Math.min(1000, Math.max(0, deadline - now())));
    }
    requireThat(task?.state === 'success' && task.result?.exit_code === 0 && task.result.truncated !== true
      && typeof task.result.text === 'string' && task.result.text.length <= 16000, '原采音任务未成功或被截断，不自动重发');
    const response = JSON.parse(task.result.text); save('parsed-private.json', response);
    receipt = validateReceipt(response, diagnosticId, active.versionCode);
  } catch (error) {
    failure = true; failureReason = error.safe ? error.message : '任务查询或回执解析失败';
  } finally {
    stop = true;
    // 仅等待已启动的有界ADB调用；不强杀远端APP，不重发采音任务。
    if (collector) { try { await collector; } catch { failure = true; } }
    save('task-private.json', task || null);
    const finalDeadline = now() + 30000;
    if (submitted) {
      for (const service of SERVICES) after.push(await readDevice('after-' + service, 'dumpsys ' + service, finalDeadline));
      const endingBoot = await readDevice('boot-after', 'cat /proc/sys/kernel/random/boot_id', finalDeadline);
      sameBoot = endingBoot.completed && endingBoot.text.trim() === boot;
      save('after-scope.json', {scope: receipt ? 'AFTER_RECEIPT_NOT_ATOMIC' : 'AFTER_FAILURE_END_NOT_CONFIRMED', sameBoot,
        attempted: after.length, completed: after.filter(value => value.completed).length});
    }
  }
  try { if (receipt && sameBoot) {
    const finalDeadline = now() + 16000;
    const pid = (await required('app-pid-after', 'busybox pidof net.elfradio.d31bootstrap', finalDeadline)).text.trim();
    requireThat(pid === String(receipt.app_pid), '回执APP进程与ADB当前进程不符');
    const status = (await required('app-status-after', `cat /proc/${receipt.app_pid}/status`, finalDeadline)).text;
    const uid = /^Uid:\s+([0-9]+)\s+([0-9]+)\s+([0-9]+)\s+([0-9]+)\s*$/m.exec(status);
    requireThat(uid && uid.slice(1).every(value => Number(value) === receipt.app_uid), '回执APP身份与ADB不符');
    requireThat(receipt.started_elapsed_ms >= baseline.deviceStartedElapsedMs
      && after.every(value => value.completed && value.deviceFinishedUpperElapsedMs >= receipt.finished_elapsed_ms), '回执与设备观察时间不符');
  } else { failure = true; failureReason ||= '回执缺失或启动周期未确认'; }
  } catch (error) { failure = true; failureReason = error.safe ? error.message : '退出后身份核对失败'; }
  const completeGroups = groups.filter(group => group.length === 2 && group.every(value => value.completed));
  const overlapping = !receipt || !sameBoot ? [] : completeGroups.filter(group => group.every(value =>
    value.deviceStartedElapsedMs >= receipt.capture_started_elapsed_ms
      && value.deviceFinishedUpperElapsedMs <= receipt.finished_elapsed_ms));
  const summary = {passed: !failure && after.length === 2 && after.every(value => value.completed),
    failureReason,
    scope: 'SINGLE_LOCAL_CAPTURE_RECEIPT_AND_POST_OBSERVATION_NOT_LONG_TERM',
    durationRequestedMs: 2500, bytesRead: receipt?.bytes_read ?? null, framesRead: receipt?.frames_read ?? null,
    releaseCompleted: receipt?.release_verified === true, audioPersisted: receipt?.audio_persisted ?? null,
    mediaNetworkStarted: receipt?.network_started ?? null, sameBoot,
    metadataGroupsAttempted: groups.length, metadataGroupsComplete: completeGroups.length,
    metadataGroupsWithinDiagnosticEnvelope: overlapping.length,
    metadataGaps: groups.reduce((n, group) => n + group.filter(value => !value.completed).length, 0)
      + after.filter(value => !value.completed).length,
    ownershipAssessment: 'REQUIRES_SEPARATE_NATIVE_METADATA_REVIEW', atomicSnapshot: false,
    observationScope: 'DIAGNOSTIC_ENVELOPE_INCLUDES_START_AND_RELEASE_NOT_ACTIVE_PROOF'};
  save('result.json', summary);
  requireThat(summary.passed, '验收未通过，保留原任务与原始证据；不自动重发');
  return summary;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    await verifyLocalAudio(process.argv.slice(2));
    console.log('单次本地采音回执及退出后观察通过；输入归属、持续媒体和长期稳定性未验收。');
  } catch (error) {
    console.error(error.safe ? error.message : '本地采音验收失败，请检查本次私有证据；未自动重发任务。'); process.exitCode = 1;
  }
}
