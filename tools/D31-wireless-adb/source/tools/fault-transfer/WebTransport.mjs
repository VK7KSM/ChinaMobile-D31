import fs from 'node:fs';
import {sessionCookie} from '../remote-tool-session.mjs';
import {QueueError, requireThat} from './QueueStore.mjs';

export function retryDeadline(value, now) {
  if (typeof value !== 'string' || !value.trim()) return 0;
  value = value.trim();
  if (/^\d+$/.test(value)) {
    const at = now + Number(value) * 1000;
    return Number.isSafeInteger(at) ? at : Number.MAX_SAFE_INTEGER;
  }
  // 仅接受HTTP日期，避免把小数、负数等畸形秒数解析成日期。
  if (!/^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT$/.test(value)) return 0;
  const at = Date.parse(value);
  return Number.isSafeInteger(at) && at > now ? at : 0;
}

export class WebTransport {
  constructor({session, fetchImpl = globalThis.fetch, now = Date.now}) {
    this.cookie = sessionCookie(session); this.fetch = fetchImpl;
    this.now = now;
    this.base = 'https://v.elfradio.net';
  }
  async retryError(response, code) {
    const error = new QueueError(code, true);
    error.retryAt = retryDeadline(response.headers.get('retry-after'), this.now());
    try { await response.body?.cancel(); } catch {}
    return error;
  }
  async response(route, body, options) {
    try {
      return await this.fetch(this.base + route, {
        method: body ? 'POST' : 'GET', redirect: 'error',
        headers: {Cookie: this.cookie, Origin: this.base, 'Content-Type': 'application/json'},
        body: body ? JSON.stringify(body) : undefined, signal: options.signal,
      });
    } catch { throw new QueueError('NETWORK_UNAVAILABLE', true); }
  }
  async json(route, body, options, missingTask = false) {
    const response = await this.response(route, body, options);
    if (response.status === 429 || response.status >= 500 || response.status === 409)
      throw await this.retryError(response, `HTTP_${response.status}`);
    if (response.status === 401 || response.status === 403) throw new QueueError('SESSION_REJECTED');
    requireThat(response.ok || response.status === 400 || (missingTask && response.status === 404), `HTTP_${response.status}`);
    const chunks = []; let bytes = 0;
    try {
      for await (const chunk of response.body) {
        bytes += chunk.length;
        requireThat(bytes <= 262144, 'WEB_JSON_LIMIT'); chunks.push(chunk);
      }
    } catch (error) { if (error instanceof QueueError) throw error; throw new QueueError('NETWORK_BODY_FAILED', true); }
    let value;
    try { value = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
    catch { throw new QueueError('WEB_JSON_INVALID'); }
    if (response.status === 400 && value.reason === 'inflight') throw await this.retryError(response, 'REMOTE_TASK_INFLIGHT');
    if (response.status === 400 && value.reason === 'idempotency-conflict') throw new QueueError('REMOTE_IDEMPOTENCY_CONFLICT');
    requireThat(response.status !== 400, 'WEB_BAD_REQUEST');
    if (missingTask && response.status === 404) {
      requireThat(value.ok === false && value.msg === '未找到该任务', 'TASK_LOOKUP_NOT_AUTHORITATIVE');
      return null;
    }
    requireThat(value.ok !== false, 'WEB_REJECTED');
    return value;
  }
  async resolveTarget(target, options) {
    const value = await this.json('/api/devices', null, options);
    requireThat(Array.isArray(value.devices), 'DEVICE_LIST_INVALID');
    const matches = value.devices.filter(d => d.name === target.deviceName && d.model_id === 'mdl_d31');
    requireThat(matches.length === 1, 'TARGET_NOT_UNIQUE');
    const device = matches[0];
    requireThat(device.ready === true && String(device.app_version) === target.expectedVersion && device.managed_file_return === true, 'TARGET_NOT_READY_OR_VERSION');
    requireThat(typeof device.id === 'string' && /^[A-Za-z0-9_-]{1,96}$/.test(device.id), 'DEVICE_ID_INVALID');
    return {id: device.id};
  }
  taskRoute(deviceId, taskId) { return `/api/elfremote/tasks?device_id=${encodeURIComponent(deviceId)}&task_id=${encodeURIComponent(taskId)}`; }
  fileRoute(deviceId, taskId) { return `/api/elfremote/file-return?device_id=${encodeURIComponent(deviceId)}&task_id=${encodeURIComponent(taskId)}`; }
  async queryTask(deviceId, taskId, options) {
    const value = await this.json(this.taskRoute(deviceId, taskId), null, options, true);
    if (value === null) return null;
    requireThat(value.task && typeof value.task === 'object', 'TASK_RESPONSE_INVALID');
    return value.task;
  }
  async enqueue(request, options) {
    const value = await this.json('/api/elfremote/task', request, options);
    requireThat(value.ok === true && value.task, 'ENQUEUE_RESPONSE_INVALID'); return value.task;
  }
  async metadata(deviceId, taskId, options) {
    return this.json(this.fileRoute(deviceId, taskId), null, options);
  }
  async received(deviceId, taskId, {size, sha256}, options) {
    requireThat(/^[A-Za-z0-9_-]{1,96}$/.test(deviceId) && /^[A-Za-z0-9_-]{1,96}$/.test(taskId)
      && Number.isSafeInteger(size) && size > 0 && size <= 8388608 && /^[a-f0-9]{64}$/.test(sha256), 'RETURN_RECEIPT_INVALID');
    const route = `/api/elfremote/file-return/received?device_id=${encodeURIComponent(deviceId)}&task_id=${encodeURIComponent(taskId)}`;
    const value = await this.json(route, {size, sha256}, options);
    requireThat(value.ok === true && ((value.purged === true && value.cleanup_pending !== true)
      || (value.cleanup_pending === true && value.purged !== true)), 'RETURN_RECEIPT_RESPONSE_INVALID');
    return value;
  }
  async download(deviceId, taskId, {filename, bytes, signal, onBytes}) {
    const response = await this.response(this.fileRoute(deviceId, taskId) + '&download=1', null, {signal});
    if (response.status === 429 || response.status >= 500) throw await this.retryError(response, 'DOWNLOAD_UNAVAILABLE');
    requireThat(response.ok && response.status === 200, 'DOWNLOAD_HTTP_INVALID');
    if (response.headers.has('content-length')) requireThat(Number(response.headers.get('content-length')) === bytes, 'DOWNLOAD_LENGTH_HEADER');
    const fd = fs.openSync(filename, 'wx', 0o600); let received = 0;
    try {
      for await (const chunk of response.body) {
        received += chunk.length; onBytes(chunk.length);
        requireThat(received <= bytes, 'DOWNLOAD_BYTE_LIMIT');
        fs.writeFileSync(fd, chunk);
      }
      requireThat(received === bytes, 'DOWNLOAD_LENGTH_MISMATCH');
    } catch (error) { if (error instanceof QueueError) throw error; throw new QueueError('DOWNLOAD_INTERRUPTED', true); }
    finally { fs.fsyncSync(fd); fs.closeSync(fd); }
    return received;
  }
}
