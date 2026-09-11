import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {main, validateLifecycle} from './Verify-RemoteAudioLifecycle.mjs';

function receipt() {
  return {query_completed: true, operation: 'local_audio_capture', managed_media: false,
    capture_started: true, version_code: 124, result: {
      schemaVersion: 1, operation: 'local_audio_capture', diagnostic_id: 'case-1', state: 'COMPLETED',
      managed_media: false, app_pid: 100, app_uid: 10001, audio_session_id: 7,
      duration_ms: 2500, sample_rate_hz: 16000, channels: 1, encoding: 'PCM_16BIT',
      frame_definition: 'MONO_PCM_SAMPLE', bytes_read: 8000, frames_read: 4000, read_calls: 25,
      read_error: 0, error_code: '', started_elapsed_ms: 1000, capture_started_elapsed_ms: 1200,
      finished_elapsed_ms: 3800, audio_record_created: true, initialized: true, initial_record_state: 1,
      recording_started: true, release_completed: true, release_verified: true, worker_finished: true,
      stop_completed: true, stop_error: '', release_error: '', record_state_after_release: 0,
      recording_state_after_release: 1, audio_persisted: false, network_started: false, factory_release_pending: false,
      input_lifecycle: {session_id: 'case-1', state: 'closed', managed_media: false,
        atomic_reservation: false, continuation_eligible: false, stop_required: false,
        release_confirmed: true, io_bound: true, ownership_observed: true, input_io_handle: 9,
        native_start_attempted: true, native_start_failed: false, native_stop_returned: true,
        native_release_returned: true, reader_finished: true, completion_allowed: true, stop_reason: 'STOP_REQUESTED'}
    }};
}
test('接受同次有界诊断的真实归属和释放字段', () => {
  assert.equal(validateLifecycle(receipt(), 'case-1', 124).lifecycleVerified, true);
});
test('普通采音成功不能代替新归属与释放验收', () => {
  for (const key of ['io_bound', 'ownership_observed', 'release_confirmed', 'native_stop_returned',
    'native_release_returned', 'reader_finished', 'completion_allowed']) {
    const value = receipt(); value.result.input_lifecycle[key] = false;
    assert.throws(() => validateLifecycle(value, 'case-1', 124));
  }
  const old = receipt(); delete old.result.input_lifecycle;
  assert.throws(() => validateLifecycle(old, 'case-1', 124));
});
test('拒绝跨会话、未知输入及未结束状态', () => {
  for (const patch of [{session_id: 'case-2'}, {input_io_handle: 0}, {input_io_handle: '9'},
    {state: 'active'}, {continuation_eligible: true}, {atomic_reservation: true}, {stop_reason: 'EVIDENCE_STALE'}]) {
    const value = receipt(); Object.assign(value.result.input_lifecycle, patch);
    assert.throws(() => validateLifecycle(value, 'case-1', 124));
  }
  assert.throws(() => validateLifecycle(receipt(), 'case-1', 122));
  const retained = receipt(); retained.result.factory_release_pending = true;
  assert.throws(() => validateLifecycle(retained, 'case-1', 124));
});

function host(t, mode = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'd31-audio-host-'));
  t.after(() => fs.rmSync(root, {recursive: true, force: true}));
  const capture = path.join(root, 'capture'), sha = 'a'.repeat(64);
  const boot = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
  const calls = [], partial = Buffer.from([0, 255, 13, 13, 10]);
  async function execute(binary, args, options) {
    assert.match(binary, /adb\.exe$/);
    assert.deepEqual(args.slice(0, 5), ['-P', '5042', '-s', '192.0.2.7:5555', 'shell']);
    const command = args[5], marker = command.match(/echo (D31_LIFECYCLE_[a-f0-9]+)_\$code/)[1];
    calls.push({command, timeout: options.timeout});
    let text = '', code = 0;
    if (command.includes('getprop ro.product.device')) text = `hct6735_66_m0\nhct6737t_66_m0\n23\nsynthetic-build\n${boot}`;
    else if (command.includes('active.json')) text = JSON.stringify({path: `/data/local/d31-remote/releases/${sha}/remote.apk`, sha256: sha, versionCode: 124});
    else if (command.includes('local_audio_capture')) {
      const id = command.match(/local_audio_capture [a-f0-9]{64} (lifecycle-[a-f0-9-]+) /)[1];
      const value = receipt(); value.result.diagnostic_id = id; value.result.input_lifecycle.session_id = id;
      if (mode.captureFails) { value.result.state = 'FAILED'; code = 1; }
      text = mode.malformed ? 'NOT_JSON' : JSON.stringify(value);
    } else if (command.includes('dumpsys activity services')) text = mode.serviceStuck ? 'synthetic running service' : '(nothing)';
    else if (command.includes('media.audio_flinger')) {
      if (mode.flingerFails) throw Object.assign(Error('synthetic timeout'), {stdout: partial, stderr: Buffer.from('synthetic stderr'), code: 137, killed: true});
      text = 'synthetic inputs';
    } else if (command.includes('media.audio_policy')) text = 'synthetic policy';
    else if (command.includes('/task/*/comm')) text = mode.threadsStuck ? 'd31-audio-input' : 'synthetic main';
    else if (command.includes('cat /proc/sys/kernel/random/boot_id')) text = mode.bootChanged ? 'bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee' : boot;
    else assert.fail('未预期命令：' + command);
    return {stdout: Buffer.from(`${text}\r\r\n\r\r\n${marker}_${code}\r\r\n`), stderr: Buffer.alloc(0)};
  }
  return {capture, partial, calls, run: () => main(['192.0.2.7:5555', '124', sha, capture], {execute, sleep: async () => {}, log() {}}),
    result: () => JSON.parse(fs.readFileSync(path.join(capture, 'result.json'), 'utf8'))};
}

test('服务未退出且转储失败仍保存后续线程、策略、版本和boot证据', async t => {
  const h = host(t, {serviceStuck: true, flingerFails: true, threadsStuck: true, bootChanged: true});
  await assert.rejects(h.run(), /按需媒体服务未退出/);
  const r = h.result(); assert.equal(r.completed, false); assert.equal(r.error, '按需媒体服务未退出');
  for (const label of ['04-services-after', '05-inputs-after', '05-threads-after', '07-boot-after']) assert.equal(r.finalEvidence[label].completed, false);
  for (const label of ['05-policy-after', '06-active-after']) assert.equal(r.finalEvidence[label].completed, true);
  for (const label of Object.keys(r.finalEvidence)) assert.ok(fs.existsSync(path.join(h.capture, label + '-stdout-private.bin')));
  assert.deepEqual(fs.readFileSync(path.join(h.capture, '05-inputs-after-stdout-private.bin')), h.partial);
  assert.equal(h.calls.filter(c => c.command.includes('local_audio_capture')).length, 1);
  assert.ok(h.calls.slice(-6).every(c => c.timeout === 8000));
});

test('采音失败后收尾全部成功也不能改写原失败', async t => {
  const h = host(t, {captureFails: true});
  await assert.rejects(h.run(), /设备采音诊断未成功/);
  const r = h.result(); assert.equal(r.completed, false); assert.match(r.error, /设备采音诊断未成功/);
  assert.ok(Object.values(r.finalEvidence).every(v => v.completed));
  assert.ok(fs.existsSync(path.join(h.capture, 'receipt-private.json')));
});

test('坏回执不能猜测PID，但其它收尾留证仍执行', async t => {
  const h = host(t, {malformed: true});
  await assert.rejects(h.run());
  const r = h.result(); assert.equal(r.completed, false);
  assert.equal(r.finalEvidence['05-threads-after'].completed, false);
  assert.ok(h.calls.every(c => !c.command.includes('/task/*/comm')));
  assert.equal(r.finalEvidence['07-boot-after'].completed, true);
});

test('正常采音保持原通过门并逐项记录收尾完成', async t => {
  const h = host(t); await h.run();
  const r = h.result(); assert.equal(r.completed, true); assert.equal(r.error, null);
  assert.equal(r.serviceExited, true); assert.equal(r.mediaThreadsExited, true);
  assert.ok(Object.values(r.finalEvidence).every(v => v.completed));
});
