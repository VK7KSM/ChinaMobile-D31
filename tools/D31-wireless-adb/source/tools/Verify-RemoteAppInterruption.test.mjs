import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {spawnSync} from 'node:child_process';
import {main, observeOwner, verifyOwner} from './Verify-RemoteAppInterruption.mjs';
import {parseOutput} from './Verify-RemoteContactsBridge.mjs';

// node --test tools/Verify-RemoteAppInterruption.test.mjs
// 默认完全离线：所有ADB调用均注入替身，shell仅读取新建合成目录；不发信号、不启动APP。
// 每次运行使用独立临时目录，不依赖私有捕获、不向源码目录写入测试结果。
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'd31-app-interruption-test-'));
const SHA = 'a'.repeat(64), BOOT = '00000000-0000-0000-0000-000000000001';
const REQUEST = '00000000-0000-0000-0000-000000000002';
function scenario(mode) {
  const directory = path.join(root, mode), calls = [];
  let record, captureResolve, captureReply, recoverCount = 0;
  const procStat = owner => `${owner.pid} (${owner.process_name}) S ${Array(18).fill('0').join(' ')} ${owner.start_time} 0`;
  const observation = uid => `UID:${uid}\nSTAT:${procStat(record.owner)}\nCMDHEX:${Buffer.from(record.owner.process_name).toString('hex')}000000`;
  const status = () => ({operation_id: record.operation_id, operation: 'local_audio_capture', apk_sha256: SHA,
    operation_request_id: REQUEST, record_boot_id: BOOT, current_boot_id: BOOT, state: record.state,
    reservation_released: record.state === 'RELEASED', release_reason: record.release_reason || '', managed_media: false, network_write: false});
  function complete() {
    record.state = 'RELEASED'; record.release_reason = 'MATCHED_RELEASE_RECEIPT';
    record.last_result = {operation: 'local_audio_capture', diagnostic_id: record.operation_id, duration_ms: 5000,
      operation_request_id: REQUEST, app_pid: 32002, app_uid: 10001, recording_started: true, audio_persisted: false, network_started: false};
  }
  async function execute(_binary, args, options) {
    assert.deepEqual(args.slice(0, 5), ['-P', '5042', '-s', '192.0.2.9:5555', 'shell']);
    assert.ok(options.timeout > 0 && options.timeout <= 25000);
    const command = args[5], label = command.match(/^# D31_APP_STEP (.+)\n/)[1];
    const marker = command.match(/echo (D31_INTERRUPT_[a-f0-9]+)_\$result$/)[1];
    calls.push({label, command});
    const reply = (text, exit = 0) => ({stdout: Buffer.from(`${text}\r\r\n\r\r\n${marker}_${exit}\r\r\n`), stderr: Buffer.alloc(0)});
    if (label === 'baseline') return reply(`0\n23\nhct6735_66_m0\nhct6737t_66_m0\nsynthetic:6.0/test\n${BOOT}`);
    if (label === 'active-before') return reply(JSON.stringify({path: `/data/local/d31-remote/releases/${SHA}/remote.apk`, sha256: SHA, versionCode: 128, package: 'net.elfradio.d31bootstrap'}));
    if (label === 'active-digest') return reply(`${SHA}  /synthetic/active.json`);
    if (label === 'installed-path') return reply('package:/data/app/net.elfradio.d31bootstrap-1/base.apk');
    if (label === 'installed-version') return reply('versionCode=128 targetSdk=23');
    if (label === 'capture') {
      const id = command.match(/local_audio_capture [a-f0-9]{64} ([A-Za-z0-9_-]+) 5000/)[1];
      const nice = command.match(/--nice-name='([^']+)'/)[1];
      record = {schema_version: 1, operation: 'local_audio_capture', operation_id: id, apk_sha256: SHA, boot_id: BOOT,
        params: {duration_ms: 5000}, state: 'ARMED', request_id: REQUEST,
        owner: {pid: 32001, uid: 0, start_time: '101', process_name: nice},
        app: {pid: 32002, uid: 10001, start_time: '102', process_name: 'net.elfradio.d31bootstrap'}};
      captureReply = reply; return new Promise(resolve => { captureResolve = resolve; });
    }
    if (label === 'observe-record' && mode === 'no-window') { complete(); captureResolve(captureReply('normal', 0)); }
    if (label.includes('record')) return reply(JSON.stringify(record));
    if (label === 'owner-before') {
      if (mode === 'wrong-owner') { complete(); captureResolve(captureReply('normal', 0)); }
      return reply(observation(mode === 'wrong-owner' ? 10001 : 0));
    }
    if (label === 'signal-root') {
      assert.match(command, /kill -TERM 32001/); assert.doesNotMatch(command, /kill -TERM 32002/);
      assert.match(command, /shift 19/); assert.match(command, /\[ "\$uid" = '0' \]/);
      assert.ok(command.includes(record.owner.process_name)); assert.match(command, /sha256sum/);
      fs.writeFileSync(path.join(directory, 'signal-shell-private.sh'), command, {flag: 'wx'});
      if (mode === 'live-reject') { complete(); captureResolve(captureReply('normal', 0)); return reply('guard refused', 89); }
      captureResolve(captureReply('', 143)); return reply(observation(0) + '\nROOT_TERM_SENT');
    }
    if (label === 'owner-after') return reply('ROOT_ABSENT');
    if (label === 'reservation-after-interruption') return reply(JSON.stringify({task_id: 'app-' + record.operation_id, plan_sha256: SHA, kind: 'app_operation'}));
    if (label === 'query-after-interruption') return reply(JSON.stringify(status()));
    if (label === 'recover-original') {
      assert.ok(command.endsWith(`RemoteAppOperation recover ${SHA} ${record.operation_id}\n); result=$?; echo; echo ${marker}_$result`));
      assert.ok(command.includes(`CLASSPATH='/data/local/d31-remote/releases/${SHA}/remote.apk'`));
      recoverCount++; if (recoverCount >= 2 || mode !== 'good') complete();
      const value = status(); if (mode === 'wrong-recovery') value.operation_id = 'other';
      return reply(JSON.stringify(value));
    }
    if (label === 'reservation-final') return reply('RESERVATION_ABSENT');
    if (label === 'preflight' || label.startsWith('continuity-')) return reply('');
    assert.fail('未知替身步骤：' + label);
  }
  return {calls, directory, run: () => main(['192.0.2.9:5555', '128', SHA, directory], {execute, sleep: async () => {}})};
}
test('根身份不符不得发信号，仍只用原号收尾', async () => {
  const h = scenario('wrong-owner'), value = await h.run();
  assert.equal(value.passed, false); assert.equal(value.interruptionSent, false); assert.equal(value.recoveryReleased, true);
  assert.equal(value.failure, 'INTERRUPTION_PROC_IDENTITY_REJECTED');
  assert.equal(h.calls.filter(c => c.label === 'signal-root').length, 0);
  assert.equal(h.calls.filter(c => c.label === 'capture').length, 1);
});
test('根中断后先证明预留仍在，再以原号恢复，保全CRCRLF原件', async () => {
  const h = scenario('good'), value = await h.run();
  assert.equal(value.passed, true); assert.equal(value.ownerDeathConfirmed, true);
  assert.equal(value.reservationRetainedAfterRootDeath, true); assert.equal(value.recoveryReleased, true);
  assert.equal(value.audioPersisted, false); assert.equal(value.mediaNetworkStarted, false);
  const labels = h.calls.map(c => c.label);
  assert.ok(labels.indexOf('reservation-after-interruption') < labels.indexOf('recover-original'));
  assert.equal(labels.filter(v => v === 'capture').length, 1); assert.equal(labels.filter(v => v === 'recover-original').length, 2);
  const raw = fs.readdirSync(h.directory).find(n => n.includes('observe-record-stdout-private.bin'));
  assert.ok(fs.readFileSync(path.join(h.directory, raw)).includes(Buffer.from('\r\r\n')));
});
test('设备现场身份复查拒绝不伪报发送成功', async () => {
  const h = scenario('live-reject'), value = await h.run();
  assert.equal(value.passed, false); assert.equal(value.signalOutcome, 'GUARD_REFUSED'); assert.equal(value.interruptionSent, false);
});
test('未取得ARMED窗口不发送信号，正常收尾仍不是中断通过', async () => {
  const h = scenario('no-window'), value = await h.run();
  assert.equal(value.passed, false); assert.equal(value.interruptionSent, false); assert.equal(value.recoveryReleased, true);
  assert.equal(value.failure, 'INTERRUPTION_ARMED_WINDOW_NOT_OBSERVED');
  assert.ok(h.calls.every(c => c.label !== 'signal-root'));
});
test('错操作恢复回执不能通过', async () => {
  const h = scenario('wrong-recovery'), value = await h.run();
  assert.equal(value.passed, false); assert.equal(value.recoveryReleased, false); assert.equal(value.cleanupFailure, 'INTERRUPTION_RECOVERY_BINDING');
});
test('参数缺失在任何设备动作前拒绝', async () => {
  let calls = 0; await assert.rejects(main([], {execute: async () => { calls++; }}), /INTERRUPTION_ARGUMENTS/); assert.equal(calls, 0);
});

const owner = {pid: 32001, uid: 0, start_time: '101', process_name: 'd31-int-synthetic'};
// Windows默认使用Git Bash，也可显式设置D31_TEST_SHELL；POSIX使用系统sh。
const bash = process.env.D31_TEST_SHELL || (process.platform === 'win32'
  ? (fs.existsSync('C:/Program Files/Git/bin/bash.exe') ? 'C:/Program Files/Git/bin/bash.exe' : 'bash')
  : '/bin/sh');
let index = 0;
function run(status, mode = '') {
  const dir = path.join(root, 'uid-case-' + (++index)); fs.mkdirSync(dir);
  if (status !== null) fs.writeFileSync(path.join(dir, 'status'), status);
  fs.writeFileSync(path.join(dir, 'stat'), `${owner.pid} (${owner.process_name}) S ${Array(18).fill('0').join(' ')} ${owner.start_time} 0\n`);
  fs.writeFileSync(path.join(dir, 'cmdline'), Buffer.from(owner.process_name + '\0\0'));
  const unixPath = dir.replaceAll('\\', '/').replace(/^([A-Za-z]):/, (_, drive) => '/' + drive.toLowerCase());
  let script = observeOwner(owner).replaceAll('/proc/32001', `'${unixPath.replaceAll("'", "'\\''")}'`)
    .replaceAll('/system/bin/busybox od', 'od').replaceAll('/system/bin/busybox tr', 'tr');
  if (mode === 'missing-od') script = script.replace('bytes=$(od ', 'bytes=$(false ');
  assert.ok(!script.includes('/proc/') && !script.includes('kill '));
  fs.writeFileSync(path.join(dir, 'executed.sh'), script);
  const result = spawnSync(bash, ['-s'], {input: script, encoding: 'utf8', timeout: 5000, windowsHide: true});
  fs.writeFileSync(path.join(dir, 'result.json'), JSON.stringify(result, null, 2));
  assert.ifError(result.error);
  return result;
}
test('合成CRCRLF错误回执保留缺stat及退出85，不依赖私有原件', () => {
  const marker = 'D31_INTERRUPT_' + '0'.repeat(32);
  const raw = Buffer.from('stat: applet not found\r\r\n\r\r\n' + marker + '_85\r\r\n');
  const parsed = parseOutput(raw, marker);
  assert.equal(parsed.exitCode, 85); assert.equal(parsed.text, 'stat: applet not found');
  assert.ok(raw.includes(Buffer.from('\r\r\n')));
  assert.doesNotMatch(observeOwner(owner), /busybox stat/);
});
test('内建read严格接受完整root四字段，保留starttime及cmdline校验', () => {
  const result = run('Name:\tsynthetic\nUid:\t0\t0\t0\t0\nGid:\t0\t0\t0\t0\n');
  assert.equal(result.status, 0, result.stderr); verifyOwner(result.stdout.trim(), owner, owner.process_name);
  assert.throws(() => verifyOwner(result.stdout.trim(), {...owner, start_time: '102'}, owner.process_name));
  assert.throws(() => verifyOwner(result.stdout.trim(), owner, 'd31-int-other'));
});
test('缺失Uid或status文件拒绝', () => {
  for (const value of ['Name:\tsynthetic\n', null]) assert.equal(run(value).status, 85);
});
test('任何非root身份字段拒绝', () => {
  for (const value of ['Uid: 10001 10001 10001 10001\n', 'Uid: 0 10001 0 0\n', 'Uid: 0 0 10001 0\n', 'Uid: 0 0 0 10001\n'])
    assert.equal(run(value).status, 85);
});
test('字段缺少、多余、乱码或重复都拒绝', () => {
  for (const value of ['Uid: 0 0 0\n', 'Uid: 0 0 0 0 0\n', 'Uid: root 0 0 0\n', 'Uid: 0 0 0 0\nUid: 0 0 0 0\n'])
    assert.equal(run(value).status, 85);
});
test('od失败不得被后续tr成功掩盖', () => {
  assert.equal(run('Uid: 0 0 0 0\n', 'missing-od').status, 86);
});
