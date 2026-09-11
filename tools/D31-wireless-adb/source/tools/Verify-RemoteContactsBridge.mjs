import fs from 'node:fs';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {randomUUID} from 'node:crypto';
import {pathToFileURL} from 'node:url';

const execute = promisify(execFile);
const check = (value, message) => { if (!value) throw Error(message); };

export function parseOutput(buffer, marker) {
  const text = buffer.toString('utf8').replace(/\r/g, '').trimEnd();
  const lines = text.split('\n');
  const last = lines.pop();
  check(lines.every(line => !line.startsWith(marker)), '设备退出标记重复');
  const match = new RegExp(`^${marker}_([0-9]+)$`).exec(last);
  check(match, '设备退出标记缺失');
  return {exitCode: Number(match[1]), text: lines.join('\n').trim()};
}

export function validateBinding(value, exitCode) {
  check(value?.schemaVersion === 1 && value.kind === 'NEXUI_APP_BIND_CHECK'
    && value.readOnly === true && value.bindFlags === 0
    && value.vendorServiceStartRequested === false && value.contactRequestSent === false
    && value.contacts_requested === false && value.contactValuesRead === false
    && value.contactValuesEmitted === false && value.listComplete === false,
  '通讯录只读范围不符');
  check(value.bridgeHandshake === true && value.appServiceStartRequested === true
    && Number.isSafeInteger(value.app_pid) && value.app_pid > 0
    && Number.isSafeInteger(value.app_uid) && value.app_uid >= 10000
    && value.maintenanceGatePassed === true && value.activeApkHashMatched === true
    && value.appIdentityMatched === true && value.installedApkHashMatched === true
    && value.componentMatched === true && value.bindingRequested === true
    && value.remoteOutcomeKnown === true && value.unbindAttempted === true
    && value.unbindConfirmed === true, '应用身份、实际绑定或清理未确认');
  if (value.state === 'BINDING_VERIFIED') {
    check(value.ok === true && value.bindAccepted === true
      && value.messengerBinderVerified === true && exitCode === 0, '原厂绑定成功证据不符');
    return {bridgeVerified: true, vendorBindingVerified: true, listVerified: false};
  }
  check(value.ok === false && exitCode === 1 && value.messengerBinderVerified === false
    && ((value.state === 'CONTACTS_BIND_REJECTED' && value.bindAccepted === false)
      || (value.state === 'CONTACTS_TIMEOUT' && value.bindAccepted === true)),
  '原厂服务状态未在本次验收范围内');
  return {bridgeVerified: true, vendorBindingVerified: false, listVerified: false};
}

export async function main(args) {
  const [serial, versionText, sha, directory] = args;
  check(args.length === 4 && /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$/.test(serial || '')
    && serial.split(':')[0].split('.').every(n => Number(n) <= 255)
    && Number(serial.split(':')[1]) > 0 && Number(serial.split(':')[1]) <= 65535
    && /^[1-9][0-9]{0,8}$/.test(versionText || '') && /^[a-f0-9]{64}$/.test(sha || ''),
  '需要完整D31序列号、版本号、摘要、全新目录');
  check(directory && !fs.existsSync(directory), '捕获目录必须全新');
  const capture = path.resolve(directory), version = Number(versionText);
  fs.mkdirSync(capture, {recursive: true});
  fs.copyFileSync(new URL(import.meta.url), path.join(capture, 'host.mjs'), fs.constants.COPYFILE_EXCL);
  const save = (name, value) => fs.writeFileSync(path.join(capture, name),
    JSON.stringify(value, null, 2), {flag: 'wx', mode: 0o600});
  async function read(label, command) {
    const marker = 'D31_CONTACTS_' + randomUUID().replace(/-/g, '');
    const wrapped = `( ${command}\n); code=$?; echo; echo ${marker}_$code`;
    let stdout, stderr;
    const record = {command, serial, time: new Date().toISOString(), completed: false};
    try {
      const response = await execute('C:/Dev/android-sdk/platform-tools/adb.exe',
        ['-P', '5042', '-s', serial, 'shell', wrapped],
        {windowsHide: true, timeout: 22000, maxBuffer: 524288, encoding: 'buffer'});
      stdout = response.stdout; stderr = response.stderr;
      const result = parseOutput(stdout, marker);
      record.deviceExitCode = result.exitCode; record.completed = true;
      return result;
    } catch (error) {
      stdout ??= error.stdout; stderr ??= error.stderr;
      record.error = '设备命令失败或未取得完整退出结果';
      throw Error(record.error);
    } finally {
      for (const [stream, bytes] of [['stdout', stdout], ['stderr', stderr]])
        if (bytes !== undefined) fs.writeFileSync(path.join(capture, `${label}-${stream}-private.bin`), bytes, {flag: 'wx'});
      save(`${label}-metadata-private.json`, record);
    }
  }
  async function required(label, command) {
    const result = await read(label, command);
    check(result.exitCode === 0, '只读基线命令未完成'); return result.text;
  }
  const apk = `/data/local/d31-remote/releases/${sha}/remote.apk`;
  const verifyActive = value => check(value.path === apk && value.sha256 === sha
    && value.versionCode === version, '活动版本或路径不符');
  const baseline = await required('01-build', 'getprop ro.product.device; getprop ro.product.model; getprop ro.build.fingerprint; cat /proc/sys/kernel/random/boot_id');
  const lines = baseline.split('\n');
  check(lines[0] === 'hct6735_66_m0' && lines[1] === 'hct6737t_66_m0'
    && lines.length === 4, '目标不是已登记D31');
  verifyActive(JSON.parse(await required('02-active-before', 'cat /data/local/d31-remote/runtime/active.json')));
  let result, failure;
  try {
    const response = await read('03-bind-check', `CLASSPATH='${apk}' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.management.ContactsAppCommand metadata ${sha}`);
    const value = JSON.parse(response.text); save('receipt-private.json', value);
    check(value.expectedApkSha256 === sha, '回执不属于本次APK');
    result = {...validateBinding(value, response.exitCode), state: value.state};
  } catch (error) { failure = error; }
  try {
    // 给已完成的按需服务一次退出派发窗口；不重试绑定或停止其它服务。
    await new Promise(resolve => setTimeout(resolve, 1500));
    const services = await required('04-service-after', 'dumpsys activity services net.elfradio.d31bootstrap/.management.ContactsAppService');
    check(services.includes('(nothing)'), '通讯录APP服务仍未退出');
    verifyActive(JSON.parse(await required('05-active-after', 'cat /data/local/d31-remote/runtime/active.json')));
    const boot = await required('06-boot-after', 'cat /proc/sys/kernel/random/boot_id');
    check(boot === lines[3], '测试期间启动周期改变');
    if (result) result.serviceExited = true;
  } catch (error) { failure ??= error; }
  save('result.json', {completed: !failure, ...result, error: failure?.message ?? null,
    contactsRead: false, networkChanged: false, webVerified: false});
  if (failure) throw failure;
  console.log(JSON.stringify(result));
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href)
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
