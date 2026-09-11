import fs from 'node:fs';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {parseOutput} from './Verify-RemoteContactsBridge.mjs';
import {validateReceipt} from './Verify-RemoteLocalAudio.mjs';

const check = (value, message) => { if (!value) throw Error(message); };

export function validateLifecycle(value, id, version) {
  const receipt = validateReceipt(value, id, version);
  const lifecycle = receipt.input_lifecycle;
  check(lifecycle?.session_id === id && lifecycle.state === 'closed'
    && lifecycle.managed_media === false && lifecycle.atomic_reservation === false
    && lifecycle.continuation_eligible === false && lifecycle.stop_required === false
    && lifecycle.release_confirmed === true && lifecycle.io_bound === true
    && lifecycle.ownership_observed === true
    && Number.isSafeInteger(lifecycle.input_io_handle) && lifecycle.input_io_handle > 0
    && lifecycle.native_start_attempted === true && lifecycle.native_start_failed === false
    && lifecycle.native_stop_returned === true && lifecycle.native_release_returned === true
    && lifecycle.reader_finished === true && lifecycle.completion_allowed === true
    && lifecycle.stop_reason === 'STOP_REQUESTED' && receipt.factory_release_pending === false,
  '实际输入归属或生命周期释放未通过');
  return {lifecycleVerified: true, releaseVerified: true, audioSaved: false, mediaNetworkStarted: false};
}

// 依赖注入仅供离线测试；命令行仍使用固定ADB与真实有界等待。
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
  async function read(label, command, timeout = 25000) {
    const marker = 'D31_LIFECYCLE_' + randomUUID().replace(/-/g, '');
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
      record.error = 'ADB调用失败或输出未完整结束';
      throw Error(record.error);
    } finally {
      for (const [stream, bytes] of [['stdout', stdout], ['stderr', stderr]])
        if (bytes !== undefined) fs.writeFileSync(path.join(capture, `${label}-${stream}-private.bin`), bytes, {flag: 'wx'});
      save(`${label}-metadata-private.json`, record);
    }
  }
  async function required(label, command, timeout) {
    const result = await read(label, command, timeout);
    check(result.exitCode === 0, '设备命令失败，参见私有原始输出');
    return result.text;
  }
  const apk = `/data/local/d31-remote/releases/${sha}/remote.apk`;
  const activeMatches = value => check(value.path === apk && value.sha256 === sha && value.versionCode === version,
    '设备活动版本不符');
  const baseline = (await required('01-build', 'getprop ro.product.device; getprop ro.product.model; getprop ro.build.version.sdk; getprop ro.build.fingerprint; cat /proc/sys/kernel/random/boot_id')).split('\n');
  check(baseline.length === 5 && baseline[0] === 'hct6735_66_m0' && baseline[1] === 'hct6737t_66_m0'
    && baseline[2] === '23', '目标不是已验证D31');
  activeMatches(JSON.parse(await required('02-active-before', 'cat /data/local/d31-remote/runtime/active.json')));
  const id = 'lifecycle-' + randomUUID();
  let result, failure, appPid;
  try {
    const response = await read('03-capture', `CLASSPATH='${apk}' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteMediaCommand local_audio_capture ${sha} ${id} 2500`);
    const receipt = JSON.parse(response.text);
    save('receipt-private.json', receipt);
    if (Number.isSafeInteger(receipt?.result?.app_pid) && receipt.result.app_pid > 0) appPid = receipt.result.app_pid;
    check(response.exitCode === 0, '设备采音诊断未成功，保留原始回执');
    result = validateLifecycle(receipt, id, version);
  } catch (error) { failure = error; }
  const finalEvidence = {};
  // 最多六项、每项8秒；任一采集或断言失败都不能跳过其它现场。
  async function collect(label, command, verify = () => {}) {
    try {
      verify(await required(label, command, 8000));
      finalEvidence[label] = {completed: true};
    } catch (error) {
      finalEvidence[label] = {completed: false, error: error.message};
      failure ??= error;
    }
  }
  await sleep(1500);
  await collect('04-services-after', 'dumpsys activity services net.elfradio.d31bootstrap/.media.AppMediaService', services => {
    check(services.includes('(nothing)'), '按需媒体服务未退出');
    if (result) result.serviceExited = true;
  });
  await collect('05-inputs-after', 'dumpsys media.audio_flinger');
  await collect('05-policy-after', 'dumpsys media.audio_policy');
  if (appPid) {
    await collect('05-threads-after', `if [ -d /proc/${appPid} ]; then for f in /proc/${appPid}/task/*/comm; do cat "$f" || exit 9; done; else echo APP_EXITED; fi`, threads => {
      check(!threads.split('\n').some(name => /^d31-(audio|local-audio|app-media)/.test(name)), '媒体观察或采音线程仍存在');
      if (result) result.mediaThreadsExited = true;
    });
  } else finalEvidence['05-threads-after'] = {completed: false, error: '没有可信回执PID，未检查线程'};
  await collect('06-active-after', 'cat /data/local/d31-remote/runtime/active.json', text => activeMatches(JSON.parse(text)));
  await collect('07-boot-after', 'cat /proc/sys/kernel/random/boot_id', text => check(text === baseline[4], '测试期间启动周期变化'));
  save('result.json', {completed: !failure, ...result, error: failure?.message ?? null,
    finalEvidence,
    scope: 'ADB_BOUNDED_DIAGNOSTIC_NOT_WEB_OR_CONTINUOUS_MEDIA'});
  if (failure) throw failure;
  (deps.log || console.log)(JSON.stringify(result));
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href)
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
