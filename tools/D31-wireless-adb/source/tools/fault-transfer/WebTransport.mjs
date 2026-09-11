import fs from 'node:fs';
import {sessionCookie} from '../remote-tool-session.mjs';
import {QueueError, requireThat} from './QueueStore.mjs';

export class WebTransport {
  constructor({session, fetchImpl = globalThis.fetch}) {
    this.cookie = sessionCookie(session); this.fetch = fetchImpl;
    this.base = 'https://v.elfradio.net';
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
      throw new QueueError(`HTTP_${response.status}`, true);
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
    if (response.status === 400 && value.reason === 'inflight') throw new QueueError('REMOTE_TASK_INFLIGHT', true);
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
  async download(deviceId, taskId, {filename, bytes, signal, onBytes}) {
    const response = await this.response(this.fileRoute(deviceId, taskId) + '&download=1', null, {signal});
    if (response.status === 429 || response.status >= 500) throw new QueueError('DOWNLOAD_UNAVAILABLE', true);
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
