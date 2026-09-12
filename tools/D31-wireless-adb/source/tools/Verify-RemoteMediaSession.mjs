import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import WebSocket from 'ws';
import {WebTransport} from './fault-transfer/WebTransport.mjs';
import {captureHttp} from './Verify-RemoteMediaModes.mjs';

// 显式单会话验收；不重试创建，不把协议就绪当作听音通过。
const [sessionPath, capturePath, deviceName, version, mode] = process.argv.slice(2);
assert.ok(sessionPath && capturePath && deviceName && version && ['photo', 'alarm', 'microphone', 'video'].includes(mode));
assert.ok(!fs.existsSync(capturePath), '需要全新私有目录');
fs.mkdirSync(capturePath, {recursive: true});
const save = (name, value) => fs.writeFileSync(path.join(capturePath, name), JSON.stringify(value, null, 2), {flag: 'wx', mode: 0o600});
const transport = new WebTransport({session: JSON.parse(fs.readFileSync(sessionPath, 'utf8').replace(/^\uFEFF/, ''))});
const base = 'https://v.elfradio.net';
let sequence = 0, socket, created, device, interval, failure;
const frames = [];
async function api(route, body) {
  const response = await captureHttp({transport, route, body, capture: capturePath, label: String(++sequence).padStart(2, '0')});
  assert.equal(response.status, 200, '正式接口返回失败，原件已保存');
  assert.notEqual(response.value.ok, false);
  return response.value;
}
async function until(predicate, duration) {
  const deadline = Date.now() + duration;
  while (!predicate()) {
    if (failure) throw failure;
    if (Date.now() >= deadline) throw Error('MEDIA_ACCEPTANCE_TIMEOUT');
    if (frames.some(x => x.type === 'closed')) throw Error('MEDIA_CLOSED_BEFORE_RESULT');
    await new Promise(resolve => setTimeout(resolve, 100));
  }
}
const summary = {mode, scope: 'SERVER_SESSION_AND_TRACKS_ONLY', passed: false, browserUiTested: false,
  deviceCleanupVerified: false, audibleConfirmed: false, actualAudioReceived: false, actualVideoReceived: false};
try {
  const list = await api('/api/devices');
  const matches = list.devices.filter(d => d.name === deviceName && d.model_id === 'mdl_d31');
  assert.equal(matches.length, 1);
  device = matches[0];
  assert.equal(device.ready, true);
  assert.equal(device.app_version, version);
  assert.ok(Array.isArray(device.managed_media_modes) && device.managed_media_modes.includes(mode), '该模式尚未开放');
  save('device-private.json', device);
  const route = '/api/elfremote/media/session?device_id=' + encodeURIComponent(device.id);
  const before = await api(route);
  assert.equal(before.active, false, '不能抢占已有会话');
  created = await api('/api/elfremote/media/session', {device_id: device.id, mode, camera: 'front'});
  assert.ok(/^[a-f0-9-]{36}$/.test(created.session_id));
  save('session-private.json', created);
  socket = new WebSocket(base.replace('https:', 'wss:') + '/api/elfremote/media/browser?session_id=' + created.session_id,
    {headers: {Cookie: transport.cookie, Origin: base}, handshakeTimeout: 15000, maxPayload: 96000});
  socket.on('error', () => { failure = Error('MEDIA_SOCKET_FAILED'); });
  socket.on('message', (raw, binary) => {
    try {
      assert.equal(binary, false); assert.ok(frames.length < 200);
      const frame = JSON.parse(raw.toString());
      fs.appendFileSync(path.join(capturePath, 'wire-private.jsonl'), JSON.stringify({at: Date.now(), frame}) + '\n');
      frames.push(frame);
    } catch { failure = Error('MEDIA_WIRE_INVALID'); }
  });
  interval = setInterval(() => { if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({type: 'ping'})); }, 10000);
  await until(() => frames.some(x => x.type === 'ready'), 60000);
  summary.deviceReady = true;
  if (mode === 'photo') {
    await until(() => frames.some(x => x.type === 'result'), 60000);
    const photo = frames.find(x => x.type === 'result');
    assert.equal(photo.report_id, created.session_id);
    assert.ok(photo.captured_at && Number.isFinite(typeof photo.captured_at === 'number' ? photo.captured_at : Date.parse(photo.captured_at)));
    const response = await transport.response('/api/elfremote/report-photo?device_id=' + encodeURIComponent(device.id)
      + '&report_id=' + encodeURIComponent(photo.report_id), null, {signal: AbortSignal.timeout(15000)});
    assert.equal(response.status, 200); assert.ok(response.headers.get('content-type')?.startsWith('image/jpeg'));
    let size = 0; const parts = [];
    for await (const bytes of response.body) { size += bytes.length; assert.ok(size <= 262144); parts.push(bytes); }
    const image = Buffer.concat(parts);
    assert.ok(image.length >= 4 && image.readUInt16BE(0) === 0xffd8 && image.readUInt16BE(image.length - 2) === 0xffd9);
    fs.writeFileSync(path.join(capturePath, 'photo-private.jpg'), image, {flag: 'wx', mode: 0o600});
    summary.photoDownloaded = true; summary.photoBytes = image.length;
    summary.photoSha256 = createHash('sha256').update(image).digest('hex');
  } else if (mode === 'microphone' || mode === 'video') {
    await until(() => frames.some(x => x.type === 'tracks' && x.tracks?.some(t => t.trackName === 'audio')
      && (mode !== 'video' || x.tracks?.some(t => t.trackName === 'video'))), 20000);
    summary.audioTrackPublished = true;
    if (mode === 'video') summary.videoTrackPublished = true;
  }
  if (mode === 'photo') {
    // 一次照片上传完成后设备会自动关闭；回读JPEG可能晚于closed到达。
    await until(() => frames.some(x => x.type === 'closed'), 10000);
    summary.completedAutomatically = true;
  } else {
    await new Promise(resolve => setTimeout(resolve, 2000));
    assert.ok(socket.readyState === WebSocket.OPEN && !frames.some(x => x.type === 'closed'));
    socket.send(JSON.stringify({type: 'stop'}));
    await until(() => frames.some(x => x.type === 'closed'), 10000);
    summary.stopAcknowledged = true;
  }
  const after = await api(route);
  assert.equal(after.active, false);
  summary.serverSessionReleased = true;
  summary.passed = true;
} catch (error) {
  summary.error = /^[A-Z_]{1,80}$/.test(error.message || '') ? error.message : 'MEDIA_ACCEPTANCE_FAILED';
  // 断联或未知创建不换号重试；能连接时明确停止，之后由主线核对真实状态。
} finally {
  clearInterval(interval);
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({type: 'stop'}));
  if (socket) { socket.close(); await new Promise(resolve => setTimeout(resolve, 500)); socket.terminate(); }
  save('summary.json', summary);
  console.log(JSON.stringify(summary));
  if (!summary.passed) process.exitCode = 1;
}
