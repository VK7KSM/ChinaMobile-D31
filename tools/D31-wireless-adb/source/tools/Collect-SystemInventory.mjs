import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomBytes, randomUUID} from 'node:crypto';
import {isDeepStrictEqual as equal} from 'node:util';
import {QueueStore, hash, requireThat as need} from './fault-transfer/QueueStore.mjs';
import {RecoverableTransport} from './network/RecoverableTransport.mjs';
import {resumeBatch} from './network/Resume-RemoteTasks.mjs';
import {validateParent, validateBaseline} from './Collect-ProductConfiguration.mjs';

const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const quote = value => "'" + value.replaceAll("'", "'\\''") + "'";
const baselineCommand = '/system/bin/getprop ro.build.fingerprint\n/system/bin/cat /proc/sys/kernel/random/boot_id\n/system/bin/cat /data/local/d31-remote/runtime/active.json';
const io = () => ({signal: AbortSignal.timeout(30000)});

/** 严格单JSON回执：拒绝重复成员、异常前后缀、过深输入和非整数计数。 */
export function parseInventoryReceipt(text) {
  need(typeof text === 'string' && Buffer.byteLength(text) <= 65536, 'INVENTORY_STDOUT_LIMIT');
  let at = 0, nodes = 0;
  const space = () => { while (/[\x20\t\r\n]/.test(text[at] || '\0')) at++; };
  const string = () => {
    const found = /^"(?:[^"\\\x00-\x1f]|\\["\\/bfnrt]|\\u[0-9a-fA-F]{4})*"/.exec(text.slice(at));
    need(found, 'INVENTORY_STDOUT_JSON'); at += found[0].length; return JSON.parse(found[0]);
  };
  const value = depth => {
    need(depth <= 16 && ++nodes <= 8192, 'INVENTORY_STDOUT_LIMIT'); space();
    if (text[at] === '{' || text[at] === '[') {
      const object = text[at++] === '{', end = object ? '}' : ']', keys = new Set(); space();
      if (text[at] === end) { at++; return; }
      while (true) {
        if (object) {
          space(); const key = string(); need(!keys.has(key), 'INVENTORY_STDOUT_DUPLICATE'); keys.add(key);
          space(); need(text[at++] === ':', 'INVENTORY_STDOUT_JSON');
        }
        value(depth + 1); space(); if (text[at] === end) { at++; return; }
        need(text[at++] === ',', 'INVENTORY_STDOUT_JSON');
      }
    }
    if (text[at] === '"') { string(); return; }
    const token = /^(?:true|false|null|-?(?:0|[1-9][0-9]*))/.exec(text.slice(at));
    need(token, 'INVENTORY_STDOUT_JSON'); at += token[0].length;
    if (/^-?[0-9]/.test(token[0])) need(Number.isSafeInteger(Number(token[0])), 'INVENTORY_STDOUT_NUMBER');
  };
  value(0); space(); need(at === text.length, 'INVENTORY_STDOUT_JSON');
  const receipt = JSON.parse(text);
  need(receipt && !Array.isArray(receipt) && receipt.schemaVersion === 1 && receipt.state === 'completed'
    && /^[a-f0-9]{64}$/.test(receipt.session) && Number.isSafeInteger(receipt.nextSequence) && receipt.nextSequence >= 0,
  'INVENTORY_RECEIPT_INVALID');
  const summary = receipt.summary;
  need(summary && !Array.isArray(summary) && typeof summary.runnable === 'boolean'
    && ['INCOMPLETE', 'CLOSED_WITHIN_PLAN'].includes(summary.inventory)
    && !(summary.inventory === 'CLOSED_WITHIN_PLAN' && summary.runnable), 'INVENTORY_SUMMARY_INVALID');
  return receipt;
}

function validateAcknowledgment(value) {
  need(value?.ok === true && ((value.purged === true && value.cleanup_pending !== true)
    || (value.cleanup_pending === true && value.purged !== true)), 'RETURN_RECEIPT_RESPONSE_INVALID');
}

export function validateOptions(options) {
  need(options && /^[A-Za-z0-9_-]{1,96}$/.test(options.deviceId) && typeof options.deviceName === 'string'
    && Number.isSafeInteger(options.version) && options.version > 0 && typeof options.versionName === 'string'
    && /^[a-f0-9]{64}$/.test(options.apkSha256) && typeof options.state === 'string', 'EXPLICIT_TARGET_REQUIRED');
  need(Array.isArray(options.roots) && options.roots.length > 0 && options.roots.length <= 32, 'ROOTS_REQUIRED');
  const roots = [...options.roots].sort();
  const allowed = ['/system', '/vendor', '/data/local/d31-patches', '/data/local/d31-system-support',
    '/data/local/d31-startup-handover', '/data/local/d31-recovery-entry', '/data/local/d31-rescue',
    '/data/local/d31-startup-curtain', '/data/system/devices/keylayout'];
  for (let i = 0; i < roots.length; i++) {
    const root = roots[i];
    need(typeof root === 'string' && !root.includes('//') && !root.endsWith('/')
      && !root.split('/').some(p => p === '.' || p === '..') && /^\/[A-Za-z0-9_./+=-]+$/.test(root)
      && allowed.some(p => root === p || root.startsWith(p + '/')), 'ROOT_NOT_ALLOWED');
    need(!roots.slice(0, i).some(p => root === p || root.startsWith(p + '/')), 'OVERLAPPING_ROOTS');
  }
  need(options.execute === undefined || typeof options.execute === 'boolean', 'EXECUTE_FLAG_INVALID');
  return roots;
}

/** 一个调用至多推进明确数量的批次；等待结束保存原号，下次继续，不取消设备任务。 */
export async function collect(options, {transport, maxSteps = 4, now = Date.now, wait} = {}) {
  const roots = validateOptions(options);
  need(Number.isInteger(maxSteps) && maxSteps > 0 && maxSteps <= 32, 'STEP_BUDGET_INVALID');
  if (options.execute !== true) return {status: 'LOCAL_PREVIEW', roots, deviceActions: 0};
  const t = transport || new RecoverableTransport({session: read(options.session)});
  const store = new QueueStore(options.state, {python: options.python || 'python'});
  return store.withLock(async () => {
    const binding = {deviceId: options.deviceId, deviceName: options.deviceName, version: options.version,
      versionName: options.versionName, apkSha256: options.apkSha256, roots};
    let state = store.load();
    if (state) need(state.kind === 'system-inventory-v1' && equal(binding, state.binding), 'STATE_BINDING_CHANGED');
    else {
      state = {kind: 'system-inventory-v1', binding, session: randomBytes(32).toString('hex'), steps: {}, events: [], next: 0};
      store.save(state);
    }
    const save = () => store.save(state);
    const devices = (await t.json('/api/devices', null, io())).devices;
    need(Array.isArray(devices) && devices.filter(d => d.id === options.deviceId && d.name === options.deviceName
      && d.model_id === 'mdl_d31' && d.ready === true && d.app_version === options.versionName).length === 1, 'TARGET_NOT_READY');
    async function task(label, type, params) {
      let saved = state.steps[label];
      if (!saved) {
        saved = state.steps[label] = {request: {id: 'system-' + randomUUID(), device_id: options.deviceId, type,
          params, expires_at: now() + 600000}}; save();
      }
      need(saved.request.type === type && equal(saved.request.params, params), 'REQUEST_CHANGED');
      if (!saved.result) {
        const directory = path.join(options.state + '-tasks', label);
        const taskStore = new QueueStore(directory, {python: options.python || 'python'});
        const statuses = await resumeBatch({store: taskStore, transport: t, requests: [saved.request], submit: true, maxWaitMs: 60000, now, wait});
        const item = await taskStore.withLock(() => taskStore.load().items[hash(options.deviceId + ':' + saved.request.id)]);
        if (statuses[0].status.startsWith('PENDING')) return null;
        need(statuses[0].status === 'success', 'REMOTE_TASK_' + statuses[0].status);
        saved.result = item.result;
        if (type === 'get_file') {
          need(item.downloaded, 'LOCAL_RETURN_MISSING');
          saved.download = {path: path.resolve(directory, item.downloaded.file), ...item.downloaded};
        }
        save();
      }
      if (type === 'root_exec') return validateParent(saved.result, saved.request);
      const result = saved.result?.result;
      need(result?.action === 'uploaded' && Number.isSafeInteger(result.bytes) && result.bytes > 0
        && result.bytes <= 8388608 && /^[a-f0-9]{64}$/.test(result.sha256), 'RETURN_INVALID');
      return result;
    }
    async function acknowledge(event) {
      need(store.bundleMatches(event.file, event), 'LOCAL_BATCH_CHANGED');
      if (event.acknowledgment) { validateAcknowledgment(event.acknowledgment); return; }
      const label = event.file.replace(/\.json$/, ''), saved = state.steps[label + '-file'];
      need(saved?.request?.type === 'get_file', 'ACK_TASK_MISSING');
      const binding = {taskId: saved.request.id, file: event.file, bytes: event.bytes, sha256: event.sha256};
      const filename = label + '-received.json', target = store.file(filename);
      let record;
      if (fs.existsSync(target)) record = read(target);
      else {
        // 批次及待确认状态已持久化；丢响应时只重试原received，不重采或重新下载。
        const response = await t.received(options.deviceId, saved.request.id, {size: event.bytes, sha256: event.sha256}, io());
        validateAcknowledgment(response);
        record = {binding, response};
        store.writeNew('received-' + randomUUID() + '.tmp', Buffer.from(JSON.stringify(record)), filename);
        need(equal(read(target), record), 'LOCAL_ACK_CHANGED');
      }
      need(equal(record.binding, binding), 'LOCAL_ACK_CHANGED'); validateAcknowledgment(record.response);
      event.acknowledgment = record.response; save();
    }
    const rootTask = (label, command) => task(label, 'root_exec', {cwd: '/', command, timeout: 90});
    const invoke = (label, request) => rootTask(label, 'CLASSPATH=' + quote('/data/local/d31-remote/releases/' + options.apkSha256 + '/remote.apk')
      + ' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteSystemInventoryCommand '
      + quote(state.session) + ' ' + quote(JSON.stringify(request)));
    const before = await rootTask('identity', baselineCommand);
    if (!before) return {status: 'PENDING', stage: 'identity', events: state.events.length};
    const identity = validateBaseline(before.text, options);
    if (!state.identity) { state.identity = identity; save(); }
    need(equal(state.identity, identity), 'IDENTITY_CHANGED');
    if (!state.initialized) {
      const request = {operation: 'init', roots, identity: {snapshotId: 'system-' + state.session,
        baselineId: 'd31-system-comparison', baselineRevision: '1', firmwareId: 'D31-product', build: identity.build,
        context: {model: 'D31', hardwareClass: 'SVP3390', firmwareFamily: 'D31-factory', stage: 'RUNNING',
          network: 'NOT_CHECKED', sim: 'NOT_CHECKED', storage: 'NOT_CHECKED'}}};
      const response = await invoke('init', request);
      if (!response) return {status: 'PENDING', stage: 'init', events: 0};
      const receipt = parseInventoryReceipt(response.text);
      need(receipt.session === state.session && receipt.nextSequence === 0 && receipt.state === 'completed', 'INIT_RECEIPT_INVALID');
      need(receipt.initialCheckpoint?.config && Array.isArray(receipt.initialCheckpoint.receipts)
        && receipt.initialCheckpoint.receipts.length === 0
        && equal(receipt.initialCheckpoint.config.identity, request.identity)
        && equal(receipt.initialCheckpoint.config.roots, roots)
        && receipt.initialCheckpoint.config.sessionBinding === identity.sha256 + '-' + identity.boot, 'INITIAL_CHECKPOINT_INVALID');
      state.initialCheckpoint = receipt.initialCheckpoint;
      state.summary = receipt.summary; state.initialized = true; save();
    }
    for (const event of state.events) await acknowledge(event);
    for (let step = 0; step < maxSteps && state.summary.runnable === true; step++) {
      const index = state.next, label = 'batch-' + String(index).padStart(5, '0');
      const result = await invoke(label, {operation: 'step', sequence: index});
      if (!result) return {status: 'PENDING', stage: label, events: state.events.length};
      const receipt = parseInventoryReceipt(result.text);
      need(receipt.state === 'completed' && receipt.session === state.session && receipt.nextSequence === index + 1
        && receipt.path === '/data/local/d31-remote/system-inventory/' + state.session + '/event-' + String(index).padStart(5, '0') + '.json'
        && Number.isSafeInteger(receipt.bytes) && receipt.bytes > 0 && receipt.bytes <= 4194304
        && /^[a-f0-9]{64}$/.test(receipt.sha256), 'BATCH_RECEIPT_INVALID');
      const returned = await task(label + '-file', 'get_file', {path: receipt.path, allow_cellular: false});
      if (!returned) return {status: 'PENDING', stage: label + '-file', events: state.events.length};
      need(returned.bytes === receipt.bytes && returned.sha256 === receipt.sha256, 'BATCH_RETURN_CHANGED');
      const bytes = fs.readFileSync(state.steps[label + '-file'].download.path);
      need(bytes.length === receipt.bytes && hash(bytes) === receipt.sha256, 'LOCAL_BATCH_CHANGED');
      const local = label + '.json', file = store.file(local);
      if (!fs.existsSync(file)) store.writeNew('batch-' + randomUUID() + '.tmp', bytes, local);
      need(hash(fs.readFileSync(file)) === receipt.sha256, 'LOCAL_BATCH_CHANGED');
      // 先保存唯一批次和待确认状态；确认原件与队列更新分开，恢复不重复追加事件。
      const event = {file: local, bytes: receipt.bytes, sha256: receipt.sha256, receipt, acknowledgment: null};
      state.events.push(event);
      state.summary = receipt.summary; state.next = receipt.nextSequence; save();
      await acknowledge(event);
    }
    const output = {status: state.summary.inventory === 'CLOSED_WITHIN_PLAN' ? 'COLLECTED' : state.summary.runnable ? 'PENDING' : 'INCOMPLETE',
      deviceBinding: {id: options.deviceId, name: options.deviceName},
      identity, roots, initialCheckpoint: state.initialCheckpoint, events: state.events, summary: state.summary, systemConsistency: 'NOT_ASSESSED'};
    if (output.status !== 'PENDING') {
      const last = await rootTask('identity-after', baselineCommand);
      if (!last) return {status: 'PENDING', stage: 'identity-after', events: state.events.length};
      need(equal(identity, validateBaseline(last.text, options)), 'FINAL_IDENTITY_CHANGED');
      const file = store.file('inventory-receipt.json');
      if (!fs.existsSync(file)) store.writeNew('receipt-' + randomUUID() + '.tmp', Buffer.from(JSON.stringify(output, null, 2)), 'inventory-receipt.json');
      need(equal(read(file), output), 'LOCAL_RECEIPT_CHANGED');
    }
    return {status: output.status, events: state.events.length, summary: state.summary};
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const options = read(process.argv[2]);
  collect({...options, execute: process.argv.includes('--execute')}).then(result => console.log(JSON.stringify(result)))
    .catch(error => { console.error(error.code || error.message); process.exitCode = 1; });
}
