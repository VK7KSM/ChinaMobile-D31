import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {main, validateRollback} from './Verify-RemoteNetworkRollback.mjs';
const receipt = () => ({task_id: 'case-1', key: 'wifi_enabled', before: false, target: true,
  state: 'ROLLED_BACK', restored: true, original_verified: true, apply_attempted: true,
  rollback_attempted: true, rollback_returned: true, rollback_attempts: 1, recovery_required: false});
test('要求同任务实际写入和回退回读', () => {
  validateRollback(receipt(), 'case-1', false);
  for (const key of ['restored', 'original_verified', 'apply_attempted', 'rollback_attempted', 'rollback_returned'])
    assert.throws(() => validateRollback({...receipt(), [key]: false}, 'case-1', false));
});
test('观察到原值或其它事务不能冒充独立回退', () => {
  for (const patch of [{state: 'ORIGINAL_OBSERVED'}, {state: 'UNCHANGED'}, {task_id: 'case-2'},
    {before: true}, {target: false}, {rollback_attempts: 0}, {recovery_required: true}])
    assert.throws(() => validateRollback({...receipt(), ...patch}, 'case-1', false));
});

// 运行真实main及落盘路径；仅ADB执行器和时钟为替身，不连接设备。
function host(t, mode = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'd31-network-host-'));
  t.after(() => fs.rmSync(root, {recursive: true, force: true}));
  const capture = path.join(root, 'capture'), sha = 'a'.repeat(64);
  const boot = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
  const calls = [], partial = Buffer.from([0, 255, 13, 13, 10]);
  let task, elapsed = 0;
  const active = JSON.stringify({path: `/data/local/d31-remote/releases/${sha}/remote.apk`, sha256: sha, versionCode: 124});
  async function execute(binary, args, options) {
    assert.match(binary, /adb\.exe$/);
    assert.deepEqual(args.slice(0, 5), ['-P', '5042', '-s', '192.0.2.7:5555', 'shell']);
    assert.ok([8000, 25000].includes(options.timeout));
    const command = args[5], marker = command.match(/echo (D31_NETWORK_[a-f0-9]+)_\$code/)[1];
    calls.push({command, timeout: options.timeout}); elapsed += 10;
    let text = '', code = 0;
    const failTransport = () => { throw Object.assign(Error('synthetic transport'), {stdout: partial, stderr: Buffer.from('synthetic stderr'), code: 137, killed: true}); };
    if (command.includes('id -u;')) text = `0\nhct6735_66_m0\nhct6737t_66_m0\n23\nsynthetic-build\n${boot}`;
    else if (command.includes('active.json')) text = active;
    else if (command.includes('eth0/carrier')) text = '1';
    else if (command.includes('ip -o')) text = '2: eth0 inet 192.0.2.7/24';
    else if (command.includes('NetworkWifiCommand get')) text = 'DISABLED';
    else if (command.includes(' prepare ')) text = '{"state":"STORAGE_PREPARED"}';
    else if (command.includes(' begin ')) {
      task = command.match(/ begin (rollback-[a-f0-9-]+) /)[1];
      if (mode.begin === 'timeout') failTransport();
      text = mode.begin === 'malformed' ? 'NOT_JSON' : JSON.stringify({state: mode.begin === 'rejected' ? 'ABORTED' : 'AWAITING_CONFIRM', target_verified: true});
    } else if (command.includes(' query ')) {
      if (mode.queryFails) failTransport();
      text = JSON.stringify(mode.pending ? {task_id: task, state: 'AWAITING_CONFIRM'} : {...receipt(), task_id: task});
    } else if (command.includes('guard-result.json')) {
      if (mode.guardFails || mode.pending) { text = 'synthetic missing guard'; code = 1; }
      else text = JSON.stringify({...receipt(), task_id: task});
    } else if (command.includes('maintenance/repair.json')) {
      if (mode.reserved) { text = '{"kind":"network"}'; code = 9; }
    } else if (command.includes('heartbeat.json')) text = '{"synthetic":true}';
    else if (command.includes('cat /proc/sys/kernel/random/boot_id')) text = boot;
    else if (command.startsWith('( ps\n')) text = 'synthetic processes';
    else assert.fail('未预期命令：' + command);
    return {stdout: Buffer.from(`${text}\r\r\n\r\r\n${marker}_${code}\r\r\n`), stderr: Buffer.alloc(0)};
  }
  return {calls, capture, partial, run: () => main(['192.0.2.7:5555', '124', sha, capture], {
    execute, now: () => elapsed, sleep: async ms => { elapsed += ms; }, log() {}
  }), json: name => JSON.parse(fs.readFileSync(path.join(capture, name), 'utf8'))};
}

for (const begin of ['timeout', 'malformed', 'rejected']) {
  test(`begin ${begin}后保全晚到回退，原失败保持且不重发`, async t => {
    const h = host(t, {begin});
    await assert.rejects(h.run());
    const result = h.json('result.json'), intent = h.json('intent.json');
    assert.equal(result.passed, false); assert.equal(result.restored, false); assert.ok(result.failure);
    assert.equal(result.finalQuery.task_id, intent.task); assert.equal(result.finalGuard.task_id, intent.task);
    assert.equal(result.finalQuery.state, 'ROLLED_BACK'); assert.equal(result.finalGuard.state, 'ROLLED_BACK');
    assert.equal(Object.keys(result.finalEvidence).length, 9);
    assert.ok(Object.values(result.finalEvidence).every(v => v.completed));
    assert.equal(h.calls.filter(c => c.command.includes(' begin ')).length, 1);
    assert.equal(h.calls.filter(c => c.command.includes(' query ')).length, 1);
    assert.ok(h.calls.every(c => !/ (cancel|confirm) /.test(c.command)));
    assert.ok(h.calls.slice(-9).every(c => c.timeout === 8000));
    if (begin === 'timeout') {
      assert.deepEqual(fs.readFileSync(path.join(h.capture, '07-begin-stdout-private.bin')), h.partial);
      const metadata = h.json('07-begin-metadata-private.json');
      assert.equal(metadata.hostExitCode, 137); assert.equal(metadata.hostKilled, true); assert.match(metadata.marker, /^D31_NETWORK_/);
      assert.equal(result.failure, 'ADB调用失败或输出未完整结束');
    }
  });
}

test('收尾query和guard失败不跳过Wi-Fi、预留、心跳及身份留证', async t => {
  const h = host(t, {begin: 'rejected', queryFails: true, guardFails: true, reserved: true});
  await assert.rejects(h.run(), /事务没有进入真实等待确认状态/);
  const r = h.json('result.json');
  for (const label of ['15-final-query', '16-final-guard-result', '11-maintenance']) assert.equal(r.finalEvidence[label].completed, false);
  for (const label of ['10-wifi-after', '12-heartbeat', '12-final-evidence', '12-carrier', '13-boot', '14-active']) {
    assert.equal(r.finalEvidence[label].completed, true);
    assert.ok(fs.existsSync(path.join(h.capture, label + '-stdout-private.bin')));
  }
  assert.deepEqual(fs.readFileSync(path.join(h.capture, '15-final-query-stdout-private.bin')), h.partial);
  assert.match(fs.readFileSync(path.join(h.capture, '11-maintenance-stdout-private.bin'), 'utf8'), /"kind":"network"/);
  assert.equal(h.calls.filter(c => c.command.includes(' begin ')).length, 1);
  assert.ok(h.calls.every(c => !/ (cancel|confirm) /.test(c.command)));
});

test('正常独立回退仍须通过全部收尾检查', async t => {
  const h = host(t);
  await h.run();
  const r = h.json('result.json');
  assert.equal(r.passed, true); assert.equal(r.restored, true); assert.equal(r.failure, null);
  assert.equal(Object.keys(r.finalEvidence).length, 9);
  const broken = host(t, {reserved: true});
  await assert.rejects(broken.run());
  assert.equal(broken.json('result.json').passed, false);
  assert.equal(broken.json('result.json').finalEvidence['14-active'].completed, true);
});

test('提前收尾仍等待时只报告采样观察并要求后续只读检查', async t => {
  const h = host(t, {begin: 'timeout', pending: true, reserved: true});
  await assert.rejects(h.run());
  const r = h.json('result.json');
  assert.equal(r.passed, false); assert.equal(r.restored, false);
  assert.equal(r.finalQuery.state, 'AWAITING_CONFIRM');
  assert.equal(r.followupReadOnlyRequired, true);
  assert.equal(r.finalEvidenceScope, 'POINT_IN_TIME_READ_ONLY_OBSERVATIONS_NOT_FINAL_STATE_GUARANTEE');
  assert.equal(h.calls.filter(c => c.command.includes(' query ')).length, 1);
  assert.equal(h.calls.filter(c => c.command.includes(' begin ')).length, 1);
  assert.ok(h.calls.every(c => !/ (cancel|confirm) /.test(c.command)));
});
