import fs from 'node:fs';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {parseOutput} from './Verify-RemoteContactsBridge.mjs';

const check = (value, message) => { if (!value) throw Error(message); };
export function validateRollback(value, task, before) {
  check(value?.task_id === task && value.key === 'wifi_enabled' && value.before === before
    && value.target === !before && value.state === 'ROLLED_BACK' && value.restored === true
    && value.original_verified === true && value.apply_attempted === true
    && value.rollback_attempted === true && value.rollback_returned === true
    && Number.isSafeInteger(value.rollback_attempts) && value.rollback_attempts >= 1
    && value.rollback_attempts <= 2 && value.recovery_required === false,
  '未取得实际独立回退及原值回读');
}

// 仅显式运行才切换一个Wi-Fi开关；必须经当前有线地址访问，超时后不重发begin。
export async function main(args, deps = {}) {
  const [serial, versionText, sha, directory] = args;
  check(args.length === 4 && /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$/.test(serial || '')
    && serial.split(':')[0].split('.').every(n => Number(n) <= 255)
    && Number(serial.split(':')[1]) > 0 && Number(serial.split(':')[1]) <= 65535
    && /^[1-9][0-9]{0,8}$/.test(versionText || '') && /^[a-f0-9]{64}$/.test(sha || '')
    && directory && !fs.existsSync(directory), '需要完整D31序列号、版本、摘要、全新目录');
  const capture = path.resolve(directory), version = Number(versionText);
  fs.mkdirSync(capture, {recursive: true});
  fs.copyFileSync(new URL(import.meta.url), path.join(capture, 'host.mjs'), fs.constants.COPYFILE_EXCL);
  const save = (name, value) => fs.writeFileSync(path.join(capture, name), JSON.stringify(value, null, 2), {flag: 'wx'});
  const execute = deps.execute || promisify(execFile);
  const sleep = deps.sleep || (ms => new Promise(resolve => setTimeout(resolve, ms)));
  const now = deps.now || (() => performance.now());
  async function read(label, command, timeout = 25000) {
    const marker = 'D31_NETWORK_' + randomUUID().replace(/-/g, '');
    const wrapped = `( ${command}\n); code=$?; echo; echo ${marker}_$code`;
    let stdout, stderr;
    const record = {command, marker, serial, time: new Date().toISOString(), completed: false};
    try {
      const response = await execute('C:/Dev/android-sdk/platform-tools/adb.exe',
        ['-P', '5042', '-s', serial, 'shell', wrapped],
        {windowsHide: true, timeout, maxBuffer: 1048576, encoding: 'buffer'});
      stdout = response.stdout; stderr = response.stderr;
      const parsed = parseOutput(stdout, marker);
      record.deviceExitCode = parsed.exitCode; record.completed = true;
      return parsed;
    } catch (error) {
      stdout ??= error.stdout; stderr ??= error.stderr;
      record.hostExitCode = Number.isInteger(error.code) ? error.code : null;
      record.hostKilled = error.killed === true;
      record.error = 'ADB调用失败或输出未完整结束'; throw Error(record.error);
    } finally {
      for (const [stream, bytes] of [['stdout', stdout], ['stderr', stderr]])
        if (bytes !== undefined) fs.writeFileSync(path.join(capture, `${label}-${stream}-private.bin`), bytes, {flag: 'wx'});
      save(`${label}-metadata-private.json`, record);
    }
  }
  async function required(label, command, timeout) {
    const result = await read(label, command, timeout);
    check(result.exitCode === 0, '设备命令失败，保留原件，不重发修改'); return result.text;
  }
  const apk = `/data/local/d31-remote/releases/${sha}/remote.apk`;
  const entry = `CLASSPATH='${apk}' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteNetworkAccess`;
  const task = 'rollback-' + randomUUID();
  const wifi = `CLASSPATH='${apk}' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.management.NetworkWifiCommand get ${task} ${sha}`;
  const activeMatches = value => check(value.path === apk && value.sha256 === sha && value.versionCode === version, '活动版本不符');
  let before, boot, changed = false, restored = false, failure, last;
  try {
    const build = (await required('01-build', 'id -u; getprop ro.product.device; getprop ro.product.model; getprop ro.build.version.sdk; getprop ro.build.fingerprint; cat /proc/sys/kernel/random/boot_id')).split('\n');
    check(build.length === 6 && build[0] === '0' && build[1] === 'hct6735_66_m0'
      && build[2] === 'hct6737t_66_m0' && build[3] === '23', '目标不是已验证root D31');
    boot = build[5];
    activeMatches(JSON.parse(await required('02-active', 'cat /data/local/d31-remote/runtime/active.json')));
    check(await required('03-carrier', 'cat /sys/class/net/eth0/carrier') === '1', '有线连接未就绪');
    const addresses = await required('04-ethernet-address', 'ip -o -4 addr show dev eth0');
    check(new RegExp('\\binet ' + serial.split(':')[0].replace(/\./g, '\\.') + '/').test(addresses), '当前ADB不是D31有线地址');
    const original = await required('05-wifi-before', wifi);
    check(['ENABLED', 'DISABLED'].includes(original), 'Wi-Fi真实前像未知'); before = original === 'ENABLED';
    save('intent.json', {task, before, target: !before, windowMs: 20000, confirmSent: false, automaticBeginRetry: false});
    check(JSON.parse(await required('06-storage', `${entry} prepare ${sha}`)).state === 'STORAGE_PREPARED', '持久目录未准备');
    changed = true;
    const response = await read('07-begin', `${entry} begin ${task} ${sha} ${!before} 20000`);
    last = JSON.parse(response.text); save('begin-receipt.json', last);
    check(response.exitCode === 0 && last.state === 'AWAITING_CONFIRM' && last.target_verified === true,
      '事务没有进入真实等待确认状态，需检查回执');
    // begin的app_process已经退出；此后只查询，不调用cancel或提供确认，验证独立守护到期回退。
    const deadline = now() + 45000;
    for (let n = 0; now() < deadline; n++) {
      last = JSON.parse(await required(`08-query-${n}`, `${entry} query ${task} ${sha}`));
      if (['ROLLED_BACK', 'NEEDS_ATTENTION', 'ORIGINAL_OBSERVED', 'ABORTED'].includes(last.state)) break;
      await sleep(1000);
    }
    validateRollback(last, task, before); restored = true;
    let guardText = 'PENDING';
    for (let n = 0; n < 12 && guardText === 'PENDING'; n++) {
      guardText = await required(`09-guard-result-${n}`, `if [ -f /data/local/d31-remote/runtime/network/${task}/guard-result.json ]; then cat /data/local/d31-remote/runtime/network/${task}/guard-result.json; else echo PENDING; fi`);
      if (guardText === 'PENDING') await sleep(250);
    }
    check(guardText !== 'PENDING', '回退已记录，但守护结束回执尚未确认');
    const guard = JSON.parse(guardText);
    validateRollback(guard, task, before);
  } catch (error) { failure = error.message; }
  const finalEvidence = {};
  let finalQuery, finalGuard;
  // 最多九项、每项8秒；只观察原任务，晚到回退也不覆盖原失败或自动重发操作。
  async function collect(label, command, verify = () => {}) {
    try {
      verify(await required(label, command, 8000));
      finalEvidence[label] = {completed: true};
    } catch (error) {
      finalEvidence[label] = {completed: false, error: error.message};
      failure ??= error.message;
    }
  }
  if (changed) {
    await collect('15-final-query', `${entry} query ${task} ${sha}`, text => {
      finalQuery = JSON.parse(text); validateRollback(finalQuery, task, before);
    });
    await collect('16-final-guard-result', `cat /data/local/d31-remote/runtime/network/${task}/guard-result.json`, text => {
      finalGuard = JSON.parse(text); validateRollback(finalGuard, task, before);
    });
    await collect('10-wifi-after', wifi, text => check(text === (before ? 'ENABLED' : 'DISABLED'), 'Wi-Fi最终原值回读不符'));
    await collect('11-maintenance', 'if [ -e /data/local/d31-remote/runtime/maintenance/repair.json ]; then cat /data/local/d31-remote/runtime/maintenance/repair.json; exit 9; fi');
    await collect('12-heartbeat', `cat /data/local/d31-remote/runtime/network/${task}/heartbeat.json`);
  }
  await collect('12-final-evidence', 'ps');
  await collect('12-carrier', 'cat /sys/class/net/eth0/carrier', text => check(text === '1', '有线连接未就绪'));
  if (boot) await collect('13-boot', 'cat /proc/sys/kernel/random/boot_id', text => check(text === boot, '设备发生重启'));
  await collect('14-active', 'cat /data/local/d31-remote/runtime/active.json', text => activeMatches(JSON.parse(text)));
  save('result.json', {passed: !failure && restored, modificationAttempted: changed, restored,
    finalEvidence, finalQuery, finalGuard,
    finalEvidenceScope: 'POINT_IN_TIME_READ_ONLY_OBSERVATIONS_NOT_FINAL_STATE_GUARANTEE',
    followupReadOnlyRequired: changed && ['15-final-query', '16-final-guard-result', '10-wifi-after', '11-maintenance']
      .some(label => finalEvidence[label]?.completed !== true),
    failure: failure ?? null, last, webConfirmationVerified: false, rebootRecoveryVerified: false});
  if (failure) throw Error(failure);
  (deps.log || console.log)(JSON.stringify({passed: true, independentRollback: true, originalWifiVerified: true,
    webConfirmationVerified: false, rebootRecoveryVerified: false}));
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href)
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
