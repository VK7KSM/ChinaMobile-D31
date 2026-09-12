import fs from 'node:fs';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {randomUUID} from 'node:crypto';
import {parseOutput} from './Verify-RemoteContactsBridge.mjs';

// 显式真机验收：只切换Wi-Fi开关后重启，使用有线管理通道观察原事务自动恢复。
const [serial, versionText, sha, directory, rescueFlag] = process.argv.slice(2);
const check = (value, message) => { if (!value) throw Error(message); };
check((process.argv.length === 6 || process.argv.length === 7 && rescueFlag === '--restore-adbd-via-probe')
  && /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$/.test(serial || '')
  && serial.split(':')[0].split('.').every(n => Number(n) <= 255)
  && Number(serial.split(':')[1]) > 0 && Number(serial.split(':')[1]) <= 65535
  && /^[1-9][0-9]{0,8}$/.test(versionText || '') && Number(versionText) >= 130
  && /^[a-f0-9]{64}$/.test(sha || '') && directory, '需要完整D31地址、新版本、摘要及私有目录');
const capture = path.resolve(directory);
fs.mkdirSync(path.dirname(capture), {recursive: true}); fs.mkdirSync(capture);
fs.copyFileSync(new URL(import.meta.url), path.join(capture, 'host.mjs'), fs.constants.COPYFILE_EXCL);
const execute = promisify(execFile), adb = 'C:/Dev/android-sdk/platform-tools/adb.exe';
const save = (name, value) => fs.writeFileSync(path.join(capture, name), JSON.stringify(value, null, 2), {flag: 'wx'});
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
let sequence = 0;
async function command(label, args, marker) {
  const prefix = `${String(++sequence).padStart(3, '0')}-${label}`;
  save(prefix + '-request-private.json', {serial, args, time: new Date().toISOString()});
  let stdout, stderr;
  try {
    const response = await execute(adb, ['-P', '5042', ...args],
      {windowsHide: true, timeout: 15000, maxBuffer: 2097152, encoding: 'buffer'});
    stdout = response.stdout; stderr = response.stderr;
    if (!marker) return stdout.toString('utf8');
    const parsed = parseOutput(stdout, marker);
    check(parsed.exitCode === 0, '设备命令失败，保留现场'); return parsed.text;
  } catch (error) {
    stdout ??= error.stdout; stderr ??= error.stderr;
    save(prefix + '-failure.json', {failed: true, hostExitCode: Number.isInteger(error.code) ? error.code : null});
    throw Error('设备命令未完成，原始输出已保留');
  } finally {
    for (const [stream, bytes] of [['stdout', stdout], ['stderr', stderr]])
      if (bytes !== undefined) fs.writeFileSync(path.join(capture, prefix + '-' + stream + '-private.bin'), bytes, {flag: 'wx'});
  }
}
async function read(label, text) {
  const marker = 'D31_BOOT_' + randomUUID().replaceAll('-', '');
  return command(label, ['-s', serial, 'shell', `( ${text}\n); code=$?; echo; echo ${marker}_$code`], marker);
}
const apk = `/data/local/d31-remote/releases/${sha}/remote.apk`;
const entry = `CLASSPATH='${apk}' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteNetworkAccess`;
const task = 'boot-' + randomUUID();
const wifi = `CLASSPATH='${apk}' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.management.NetworkWifiCommand get ${task} ${sha}`;
let rescuePosted = false, rescueComplete = false, rescueSequence = 0;
const rescueId = 'b10-adbd-' + randomUUID();
async function restoreAdbd() {
  if (!rescueFlag || rescueComplete) return;
  const origin = `http://${serial.split(':')[0]}:8765`;
  try {
    if (!rescuePosted) {
      const health = await fetch(origin + '/health', {signal: AbortSignal.timeout(3000)});
      if (!health.ok) return;
      await health.json();
      // 原厂开机停adbd为已记录策略；这里只启动本机服务，不改端口或持久设置。
      const commandText = 'test "$(getprop ro.product.device)" = hct6735_66_m0 && test "$(getprop ro.product.model)" = hct6737t_66_m0 || exit 8; '
        + 'n=0; while [ "$n" -lt 60 ]; do if [ "$(getprop sys.boot_completed)" = 1 ]; then sleep 2; '
        + 'if [ "$(getprop init.svc.adbd)" = stopped ]; then start adbd; fi; sleep 1; '
        + 'getprop init.svc.adbd; test "$(getprop init.svc.adbd)" = running; exit $?; fi; n=$((n+1)); sleep 1; done; exit 9';
      const body = {id: rescueId, command: commandText, timeout: 70};
      save('adbd-rescue-request-private.json', body); rescuePosted = true;
      const response = await fetch(origin + '/exec', {method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(body), signal: AbortSignal.timeout(5000)});
      save('adbd-rescue-start-private.json', {status: response.status, body: await response.text()});
    }
    const response = await fetch(origin + '/jobs/' + rescueId, {signal: AbortSignal.timeout(5000)});
    if (!response.ok) return;
    const result = await response.json();
    save(`adbd-rescue-${++rescueSequence}-private.json`, result);
    if (result.state === 'completed') {
      rescueComplete = true;
      check(result.exit_code === 0, '原厂adbd恢复未成功');
    }
  } catch { /* 已提交请求只查询原号，不自动重发修改。网络恢复守护与此管理通道独立。 */ }
}
let changed = false, restored = false;
try {
  const identity = (await read('identity', 'id -u; getprop ro.product.device; getprop ro.product.model; getprop ro.build.version.sdk; getprop ro.build.fingerprint; cat /proc/sys/kernel/random/boot_id')).split('\n');
  check(identity.length === 6 && identity[0] === '0' && identity[1] === 'hct6735_66_m0'
    && identity[2] === 'hct6737t_66_m0' && identity[3] === '23', '目标不是D31');
  const boot = identity[5];
  const active = JSON.parse(await read('active', 'cat /data/local/d31-remote/runtime/active.json'));
  check(active.sha256 === sha && active.versionCode === Number(versionText) && active.path === apk, '活动身份不符');
  const supervisor = JSON.parse(await read('supervisor', 'cat /data/local/d31-remote/runtime/updates/supervisor.json'));
  check(supervisor.version_code === Number(versionText), '固定监督未升级');
  check(await read('carrier', 'cat /sys/class/net/eth0/carrier') === '1', '有线未就绪');
  check(new RegExp('\\binet ' + serial.split(':')[0].replaceAll('.', '\\.') + '/')
    .test(await read('ethernet', 'ip -o -4 addr show dev eth0')), '当前地址不是有线通道');
  await read('unreserved', 'test ! -e /data/local/d31-remote/runtime/maintenance/repair.json');
  const original = await read('wifi-before', wifi);
  check(['ENABLED', 'DISABLED'].includes(original), 'Wi-Fi原像未知');
  const before = original === 'ENABLED';
  save('intent-private.json', {task, sha, boot, before, target: !before, windowMs: 120000, rebootRequested: true});
  await read('storage', `${entry} prepare ${sha}`);
  changed = true;
  const begin = JSON.parse(await read('begin', `${entry} begin ${task} ${sha} ${!before} 120000`));
  check(begin.state === 'AWAITING_CONFIRM' && begin.before === before && begin.target_verified === true, '未进入待确认状态');
  await read('journal-before', 'cat /data/local/d31-remote/runtime/network/wifi-enabled.journal');
  console.log('原网络事务已落盘并确认目标；现在只重启D31，随后等待固定监督自动恢复。');
  await command('reboot', ['-s', serial, 'reboot']);
  let currentBoot = boot;
  const deadline = performance.now() + 240000;
  while (performance.now() < deadline) {
    await delay(4000);
    await restoreAdbd();
    try {
      await command('connect', ['connect', serial]);
      currentBoot = await read('boot-after', 'cat /proc/sys/kernel/random/boot_id');
      if (currentBoot !== boot && /^[a-f0-9-]{36}$/.test(currentBoot)) break;
    } catch { /* 重启窗口仅重连同一D31，不重发begin，不重启ADB服务器。 */ }
  }
  check(currentBoot !== boot, '未证实新启动，保留原事务等待核查');
  let result, guard;
  while (performance.now() < deadline) {
    try {
      result = JSON.parse(await read('query', `${entry} query ${task} ${sha}`));
      if (['ROLLED_BACK', 'ORIGINAL_OBSERVED'].includes(result.state)) {
        const text = await read('guard-result', `if [ -f /data/local/d31-remote/runtime/network/${task}/guard-result.json ]; then cat /data/local/d31-remote/runtime/network/${task}/guard-result.json; else echo PENDING; fi`);
        if (text !== 'PENDING') { guard = JSON.parse(text); break; }
      }
      check(!['NEEDS_ATTENTION', 'ABORTED'].includes(result.state), '恢复需要人工核查');
    } catch (error) { if (result?.state === 'NEEDS_ATTENTION' || result?.state === 'ABORTED') throw error; }
    await delay(3000);
  }
  check(result?.task_id === task && result.before === before && result.target === !before
    && result.original_verified === true && result.recovery_required === false, '原值恢复未得到确认');
  check(guard?.guard_task === task && guard.guard_apk_sha256 === sha && guard.guard_armed_boot === boot
    && guard.guard_boot === currentBoot && guard.guard_deadline === begin.deadline_elapsed, '缺少新启动下原守护的回执');
  check(await read('wifi-after', wifi) === original, 'Wi-Fi最终状态不符');
  await read('reservation-after', 'test ! -e /data/local/d31-remote/runtime/maintenance/repair.json');
  await read('journal-after', 'cat /data/local/d31-remote/runtime/network/wifi-enabled.journal');
  await read('startup-dispatch', 'cat /data/local/d31-remote/runtime/updates/network-recovery.json');
  await read('health-after', 'cat /data/local/d31-remote/runtime/state/health.json');
  await read('logs-after', 'logcat -d -t 1000');
  restored = true;
  save('result.json', {passed: true, rebootObserved: true, originalBootGuardRestored: true,
    state: result.state, wifiRestored: true, reservationReleased: true, network_write: false,
    adbdRescueUsed: rescuePosted, cloudConfirmationTested: false});
  console.log('跨启动网络恢复通过：原事务守护回执、Wi-Fi原值及维护预留释放均已核实。');
} catch (error) {
  save('result.json', {passed: false, changed, restored, reason: error.message, automaticBeginRetry: false});
  throw error;
}
