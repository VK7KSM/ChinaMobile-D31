import fs from 'node:fs';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import WebSocket from 'ws';
import {WebTransport} from './fault-transfer/WebTransport.mjs';

// 仅生成后由主任务显式运行，不是产品入口或定时任务。
// node tools/Verify-RemoteMediaModes.mjs <私有会话.json> <全新capture目录>
//   <私有device.json> <完整ADB序列号> <准确Web名称> <118版本名称> <活动APK的SHA256> [adb.exe]
// 固定5042，不连接/重启ADB、不启动媒体客户端；意外创建只发送stop并终止验收。
// 合同依据：Web的media-capabilities-http.test.mjs、media-capabilities.js、
// worker.js媒体路由、media-relay.js的create/message/close；不猜HTTP关闭路径。
const MODES = Object.freeze(['ptt', 'call', 'microphone', 'video', 'photo', 'alarm']);
const REJECTION = '设备尚不支持此通信操作';
const BASE = 'https://v.elfradio.net';
const VERSION = 118;
const DEADLINE = 20000;
const ROOT = '/data/local/d31-remote/runtime';
const exec = promisify(execFile);
const hash = value => createHash('sha256').update(value).digest('hex');
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const requireThat = (condition, code) => { if (!condition) throw Error(code); };
const parse = (text, code) => {
  try { return JSON.parse(text.replace(/^\uFEFF/, '')); } catch { throw Error(code); }
};
const privateJson = filename => {
  const stat = fs.statSync(filename);
  requireThat(stat.isFile() && stat.size <= 1048576, 'PRIVATE_INPUT_SIZE');
  return parse(fs.readFileSync(filename, 'utf8'), 'PRIVATE_INPUT_JSON');
};
function redact(value) {
  if (Array.isArray(value)) return value.map(redact);
  if (!object(value)) return value;
  return Object.fromEntries(Object.entries(value).map(([key, item]) =>
    [key, /token|cookie|secret|password|authorization/i.test(key) ? '[REDACTED]' : redact(item)]));
}

// 原始字节只进私有文件；先落盘再解析，断流和非JSON也保留现场，不重发请求。
export async function captureHttp({transport, route, body, capture, label}) {
  const method = body ? 'POST' : 'GET';
  const record = (suffix, value) => fs.writeFileSync(path.join(capture, `${label}-${suffix}.json`),
    JSON.stringify(redact(value), null, 2), {flag: 'wx', mode: 0o600});
  record('request-private', {method, path: route, body});
  const rawFile = `${label}-body-private.bin`;
  const fd = fs.openSync(path.join(capture, rawFile), 'wx', 0o600);
  const metadata = {method, path: route, status: null, bytes: 0, observedBytes: 0,
    bodyFile: rawFile, bodyComplete: false, truncated: false, jsonParsed: false,
    requestAttempted: false, outcomeUnknown: method === 'POST'};
  const digest = createHash('sha256');
  const chunks = [];
  try {
    metadata.requestAttempted = true;
    const response = await transport.response(route, body, {signal: AbortSignal.timeout(DEADLINE)});
    metadata.status = response.status;
    metadata.contentType = response.headers?.get('content-type') ?? null;
    try {
      for await (const part of response.body) {
        const chunk = Buffer.from(part);
        metadata.observedBytes += chunk.length;
        const retained = chunk.subarray(0, Math.max(0, 1048576 - metadata.bytes));
        let offset = 0;
        while (offset < retained.length) {
          const written = fs.writeSync(fd, retained, offset, retained.length - offset);
          requireThat(written > 0, 'HTTP_EVIDENCE_WRITE_FAILED');
          digest.update(retained.subarray(offset, offset + written));
          metadata.bytes += written;
          offset += written;
        }
        chunks.push(retained);
        if (metadata.observedBytes > 1048576) {
          metadata.truncated = true;
          throw Error('HTTP_BODY_LIMIT');
        }
      }
    } catch (error) {
      throw Error(['HTTP_BODY_LIMIT', 'HTTP_EVIDENCE_WRITE_FAILED'].includes(error?.message)
        ? error.message : 'HTTP_RESPONSE_UNKNOWN');
    }
    metadata.bodyComplete = true;
    fs.fsyncSync(fd);
    const value = parse(Buffer.concat(chunks).toString('utf8'), 'HTTP_JSON_INVALID');
    metadata.jsonParsed = true;
    metadata.outcomeUnknown = method === 'POST' && !(object(value)
      && ((response.status === 400 && value.ok === false && value.msg === REJECTION
        && !Object.hasOwn(value, 'session_id'))
        || (typeof value.session_id === 'string' && value.session_id.length > 0 && value.session_id.length <= 200)));
    return {...metadata, bodySha256: digest.copy().digest('hex'), value};
  } catch (error) {
    metadata.error = /^[A-Z_0-9]{1,96}$/.test(error?.message || '') ? error.message : 'HTTP_CAPTURE_FAILED';
    throw Error(metadata.error);
  } finally {
    // 即使同步或关闭失败，也尝试保存元数据；磁盘错误不能变成通过。
    try { fs.fsyncSync(fd); }
    finally {
      try { fs.closeSync(fd); }
      finally { record('metadata', {...metadata, bodySha256: digest.digest('hex')}); }
    }
  }
}

async function main() {
  const args = process.argv.slice(2);
  const [sessionPath, captureArg, deviceJsonPath, serial, deviceName, versionName, expectedHash,
    adb = 'C:/Dev/android-sdk/platform-tools/adb.exe'] = args;
  requireThat(args.length === 7 || args.length === 8, 'ARGUMENTS_REQUIRED');
  requireThat(sessionPath && captureArg && deviceJsonPath && /^[A-Za-z0-9_.:-]{1,128}$/.test(serial || '')
    && !serial.startsWith('-') && deviceName?.trim() && deviceName.length <= 200 && !/[\0\r\n]/.test(deviceName)
    && versionName?.trim() && versionName.length <= 100 && !/[\0\r\n]/.test(versionName)
    && /^[a-f0-9]{64}$/.test(expectedHash || ''), 'ARGUMENTS_INVALID');
  const capture = path.resolve(captureArg);
  requireThat(!fs.existsSync(capture), 'CAPTURE_ALREADY_EXISTS');
  const local = privateJson(deviceJsonPath);
  requireThat(object(local) && /^[A-Za-z0-9_-]{1,96}$/.test(local.id || ''), 'LOCAL_DEVICE_ID_INVALID');
  const transport = new WebTransport({session: privateJson(sessionPath)});
  // 最后一层mkdir必须排他；并发同名目录不能继续写入原件。
  fs.mkdirSync(path.dirname(capture), {recursive: true});
  fs.mkdirSync(capture, {mode: 0o700});
  const save = (name, value) => fs.writeFileSync(path.join(capture, name), JSON.stringify(redact(value), null, 2),
    {flag: 'wx', mode: 0o600});
  let sequence = 0;
  const results = [];

  async function adbRun(label, adbArgs) {
    const at = Date.now();
    try {
      const result = await exec(adb, ['-P', '5042', '-s', serial, ...adbArgs],
        {encoding: 'utf8', timeout: DEADLINE, maxBuffer: 262144, windowsHide: true});
      save(`adb-${++sequence}-${label}.json`, {ok: true, elapsedMs: Date.now() - at});
      return result.stdout.trim();
    } catch {
      save(`adb-${++sequence}-${label}.json`, {ok: false, elapsedMs: Date.now() - at});
      // 不打印execFile异常对象，其中可能含序列号及identity.json的token。
      throw Error('ADB_READ_FAILED');
    }
  }
  const shell = (label, command) => adbRun(label, ['shell', command]);
  const read = async (label, file) => parse(await shell(label, `cat '${file}'`), 'DEVICE_JSON_INVALID');

  async function binding(label) {
    requireThat(await adbRun('state', ['get-state']) === 'device', 'ADB_DEVICE_NOT_READY');
    requireThat(await shell('uid', 'id -u') === '0', 'EXISTING_ROOT_ADB_REQUIRED');
    requireThat(await shell('sdk', 'getprop ro.build.version.sdk') === '23'
      && await shell('device', 'getprop ro.product.device') === 'hct6735_66_m0'
      && await shell('model', 'getprop ro.product.model') === 'hct6737t_66_m0', 'ADB_D31_IDENTITY_MISMATCH');
    const identity = await read('identity', `${ROOT}/state/identity.json`);
    requireThat(identity.device_id === local.id, 'LOCAL_ADB_DEVICE_ID_MISMATCH');
    // identity原文与token不保存，只保存完成比对所需的非凭据字段。
    const active = await read('active', `${ROOT}/active.json`);
    requireThat(active.package === 'net.elfradio.d31bootstrap' && active.versionCode === VERSION
      && active.versionName === versionName && active.sha256 === expectedHash
      && active.path === `/data/local/d31-remote/releases/${expectedHash}/remote.apk`, 'ACTIVE_ARCHIVE_MISMATCH');
    const archiveHash = await shell('active-hash', `busybox sha256sum '${active.path}'`);
    requireThat(archiveHash.split(/\s+/)[0] === expectedHash, 'ACTIVE_FILE_HASH_MISMATCH');
    const pkg = await shell('package', 'dumpsys package net.elfradio.d31bootstrap');
    requireThat(/\bversionCode=118(?:\s|$)/m.test(pkg)
      && pkg.split(/\r?\n/).some(line => line.trim() === `versionName=${versionName}`), 'INSTALLED_VERSION_MISMATCH');
    const globalName = await shell('name', 'settings get global device_name');
    const effectiveName = globalName && globalName !== 'null' ? globalName
      : await shell('fallback-name', 'settings get secure bluetooth_name');
    requireThat((effectiveName && effectiveName !== 'null' ? effectiveName : 'hct6737t_66_m0') === deviceName,
      'ADB_DEVICE_NAME_MISMATCH');
    const health = await read('health', `${ROOT}/state/health.json`);
    const deviceTime = Number(await shell('clock', 'date +%s')) * 1000;
    requireThat(Number.isFinite(deviceTime) && Number.isSafeInteger(health.time_ms)
      && deviceTime - health.time_ms >= -1000 && deviceTime - health.time_ms < 20000
      && health.version_code === VERSION && health.apk_sha256 === expectedHash && health.uid === 0
      && health.local_ready === true && health.report_acknowledged === true, 'CORE_HEALTH_MISMATCH');
    save(`${label}-binding-private.json`, {id: local.id, name: deviceName, model_id: 'mdl_d31',
      versionCode: VERSION, versionName, activeSha256: expectedHash, activeBytes: active.size,
      hardwareMatched: true, installedVersionMatched: true, activeHashMatched: true, coreHealthMatched: true});
  }

  async function http(route, body) {
    return captureHttp({transport, route, body, capture, label: `http-${++sequence}`});
  }

  async function checkList(label) {
    const response = await http('/api/devices');
    requireThat(response.status === 200 && response.value.ok !== false && Array.isArray(response.value.devices), 'WEB_LIST_INVALID');
    const byId = response.value.devices.filter(device => device.id === local.id);
    requireThat(byId.length === 1, 'WEB_ID_NOT_UNIQUE');
    const device = byId[0];
    save(`${label}-web-private.json`, {http: response.status, listBodySha256: response.bodySha256,
      device: {id: device.id, name: device.name, model_id: device.model_id, app_version: device.app_version,
        ready: device.ready, enabled: device.enabled, managed_media: device.managed_media,
        managed_media_modes: device.managed_media_modes, last_reported_at: device.last_reported_at}});
    requireThat(device.name === deviceName && device.model_id === 'mdl_d31' && device.ready === true
      && device.enabled === true && device.app_version === versionName, 'WEB_TARGET_MISMATCH');
    requireThat(device.managed_media === false && Object.hasOwn(device, 'managed_media_modes')
      && Array.isArray(device.managed_media_modes) && device.managed_media_modes.length === 0, 'MEDIA_CAPABILITIES_NOT_EMPTY');
    // 列表没有APK摘要字段，摘要由同ID的ADB active文件实测及健康记录绑定，不伪造Web摘要。
  }

  async function closeUnexpected(sessionId) {
    const events = [];
    let closeHttp = null;
    const closeChunks = [];
    const outcome = await new Promise(resolve => {
      let finished = false, socket;
      const finish = (ok, reason) => {
        if (finished) return; finished = true; clearTimeout(timer);
        try { socket?.terminate(); } catch { }
        resolve({ok, reason, events});
      };
      const timer = setTimeout(() => finish(false, 'CLOSE_DEADLINE'), DEADLINE);
      try {
        socket = new WebSocket(`${BASE.replace('https:', 'wss:')}/api/elfremote/media/browser?session_id=${encodeURIComponent(sessionId)}`,
          {headers: {Cookie: transport.cookie, Origin: BASE}, handshakeTimeout: DEADLINE, followRedirects: false, maxPayload: 16384});
        socket.on('open', () => {
          events.push({type: 'stop_sent', at: Date.now()});
          socket.send(JSON.stringify({type: 'stop'}), error => { if (error) finish(false, 'STOP_SEND_FAILED'); });
        });
        socket.on('message', raw => {
          if (events.length >= 128) { finish(false, 'CLOSE_MESSAGE_LIMIT'); return; }
          let message; try { message = JSON.parse(raw.toString()); } catch { finish(false, 'CLOSE_MESSAGE_INVALID'); return; }
          events.push({type: message.type === 'closed' ? 'closed' : 'other', at: Date.now()});
          if (message.type === 'closed') finish(true, 'SERVER_CLOSED_MESSAGE');
        });
        socket.on('unexpected-response', (_request, response) => {
          closeHttp = {status: response.statusCode, bytes: 0, observedBytes: 0,
            bodyComplete: false, truncated: false};
          let body = '', bytes = 0;
          response.on('data', chunk => {
            bytes += chunk.length;
            closeHttp.observedBytes = bytes;
            const retained = Buffer.from(chunk).subarray(0, Math.max(0, 16384 - closeHttp.bytes));
            closeChunks.push(retained);
            closeHttp.bytes += retained.length;
            closeHttp.truncated = bytes > 16384;
            if (bytes > 16384) { response.destroy(); finish(false, 'CLOSE_RESPONSE_LIMIT'); }
            else body += chunk.toString('utf8');
          });
          response.on('end', () => {
            closeHttp.bodyComplete = !closeHttp.truncated;
            let value; try { value = JSON.parse(body); } catch { finish(false, 'CLOSE_HTTP_INVALID'); return; }
            finish(response.statusCode === 400 && value.ok === false && value.msg === '通信已结束', 'CLOSE_HTTP_CHECK');
          });
          response.on('error', () => finish(false, 'CLOSE_HTTP_FAILED'));
        });
        socket.on('error', () => finish(false, 'CLOSE_SOCKET_FAILED'));
        socket.on('close', () => finish(false, 'CLOSE_WITHOUT_SERVER_RECEIPT'));
      } catch { finish(false, 'CLOSE_SETUP_FAILED'); }
    });
    if (closeHttp) {
      const raw = Buffer.concat(closeChunks);
      try {
        fs.writeFileSync(path.join(capture, 'unexpected-session-close-body-private.bin'), raw,
          {flag: 'wx', mode: 0o600});
      } finally {
        save('unexpected-session-close-http-private.json', {...closeHttp, bodySha256: hash(raw)});
      }
    }
    save('unexpected-session-close-private.json', {session_id: sessionId, ...outcome});
    return outcome;
  }

  try {
    save('scope.json', {kind: 'D31_MEDIA_MODE_REJECTION', expectedVersionCode: VERSION, modes: MODES,
      adbPort: 5042, deadlineMs: DEADLINE, retries: 0, binding: 'LOCAL_DEVICE_ID_ADB_ID_WEB_ID',
      expectedRejection: {status: 400, ok: false, msg: REJECTION}});
    await binding('before');
    await checkList('before');
    for (const [index, mode] of MODES.entries()) {
      const label = `${String(index + 1).padStart(2, '0')}-${mode}`;
      const request = {device_id: local.id, mode};
      save(`${label}-request-private.json`, {method: 'POST', path: '/api/elfremote/media/session', body: request});
      const response = await http('/api/elfremote/media/session', request);
      const sessionId = response.value?.session_id;
      if (typeof sessionId === 'string' && sessionId.length > 0 && sessionId.length <= 200) {
        // 即使保存创建回执失败，也必须先执行已查证的关闭协议；永远不继续下一模式。
        let cleanup;
        try { save(`${label}-response-private.json`, response); }
        finally { cleanup = await closeUnexpected(sessionId); }
        throw Error(cleanup.ok ? 'UNEXPECTED_SESSION_CLOSED' : 'UNEXPECTED_SESSION_CLOSE_UNCONFIRMED');
      }
      save(`${label}-response-private.json`, response);
      requireThat(response.status === 400 && object(response.value) && response.value.ok === false
        && response.value.msg === REJECTION && !Object.hasOwn(response.value, 'session_id'), 'MEDIA_REJECTION_MISMATCH');
      results.push({mode, status: 400, capabilityUnsupported: true});
      console.log(`媒体模式 ${mode}：能力拒绝已核对`);
    }
    await binding('after');
    await checkList('after');
    save('result.json', {passed: true, expectedVersionCode: VERSION, identityMatched: true,
      activeHashMatched: true, webHashFieldAvailable: false, managed_media: false, managed_media_modes: [],
      modes: results, sessionCreated: false, mediaTransportStarted: false});
    console.log('六模式能力拒绝验收通过；设备身份、版本和活动摘要一致。');
  } catch (error) {
    const reason = typeof error?.message === 'string' && /^[A-Z_0-9]{1,96}$/.test(error.message)
      ? error.message : 'CHECK_FAILED_PRIVATE_EVIDENCE_REQUIRED';
    save('result.json', {passed: false, reason, completedModes: results, continuedAfterFailure: false,
      unknownRequestOutcomeMustNotBeRetried: true});
    throw Error(reason);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main().catch(error => {
  const code = /^[A-Z_0-9]{1,96}$/.test(error?.message || '') ? error.message : 'MEDIA_CHECK_FAILED';
  console.error(`媒体能力验收未通过：${code}；保留私有证据，不自动重试。`);
  process.exitCode = 1;
});
