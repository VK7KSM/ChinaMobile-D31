import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {randomBytes, randomUUID} from 'node:crypto';
import {isDeepStrictEqual as equal} from 'node:util';
import {QueueStore, hash, requireThat as need} from './fault-transfer/QueueStore.mjs';
import {WebTransport} from './fault-transfer/WebTransport.mjs';

const read = p => JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, ''));
const terminal = new Set(['success', 'failed', 'rejected', 'expired', 'cancelled']);
const quote = s => "'" + s.replaceAll("'", "'\\''") + "'";
const baseline = '/system/bin/getprop ro.build.fingerprint\n/system/bin/cat /proc/sys/kernel/random/boot_id\n/system/bin/cat /data/local/d31-remote/runtime/active.json';
const io = () => ({signal: AbortSignal.timeout(25000)});

export function validateParent(task, request) {
  need(task?.id === request.id && task.type === request.type && task.state === 'success', 'PARENT_NOT_SUCCESS');
  need(task.device_id === undefined || task.device_id === request.device_id, 'PARENT_DEVICE_CHANGED');
  need(task.params === undefined || equal(task.params, request.params), 'PARENT_PARAMS_CHANGED');
  const r = task.result;
  if (request.type === 'root_exec') need(r?.action === 'completed' && r.exit_code === 0
    && r.truncated === false && typeof r.text === 'string', 'ROOT_RESULT_INCOMPLETE');
  else need(r?.action === 'uploaded' && /^[a-f0-9]{64}$/.test(r.sha256)
    && Number.isSafeInteger(r.bytes) && r.bytes > 0 && r.bytes <= 1048576, 'FILE_RESULT_INVALID');
  return r;
}

export function validateBaseline(text, options) {
  const lines = text.trim().split(/\r?\n/);
  need(lines.length >= 3 && /^[^\s]{1,512}$/.test(lines[0])
    && /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(lines[1]), 'BASELINE_INVALID');
  const active = JSON.parse(lines.slice(2).join('\n'));
  need(active.versionCode === options.version && active.sha256 === options.apkSha256
    && active.path === '/data/local/d31-remote/releases/' + options.apkSha256 + '/remote.apk', 'ACTIVE_CHANGED');
  return {build: lines[0], boot: lines[1], version: active.versionCode, sha256: active.sha256, path: active.path};
}

export function validateReport(bytes, receipt, returned) {
  need(bytes.length === receipt.bytes && bytes.length === returned.bytes
    && hash(bytes) === receipt.sha256 && hash(bytes) === returned.sha256, 'REPORT_DIGEST_MISMATCH');
  const report = JSON.parse(bytes);
  need(report.operation === 'runtime_inventory' && report.productConfiguration?.catalog === 'd31-product-runtime-1'
    && Array.isArray(report.productConfiguration.facts), 'PRODUCT_REPORT_MISSING');
  return report;
}

/** 原远程任务及文件回传的有界只读流程；中断后查询同一编号。 */
export async function collect(options, {transport, now = Date.now,
  wait = ms => new Promise(resolve => setTimeout(resolve, ms))} = {}) {
  need(/^[A-Za-z0-9_-]{1,96}$/.test(options.deviceId) && typeof options.deviceName === 'string'
    && options.deviceName.length > 0 && /^[a-f0-9]{64}$/.test(options.apkSha256)
    && Number.isSafeInteger(options.version) && options.version > 0 && typeof options.versionName === 'string'
    && options.state, 'EXPLICIT_TARGET_REQUIRED');
  if (!options.execute) return {status: 'LOCAL_PREVIEW', deviceActions: 0, tasks: ['identity', 'runtime', 'get_file', 'identity']};
  const store = new QueueStore(options.state, {python: options.python || 'python'});
  return store.withLock(async () => {
    const binding = {deviceId: options.deviceId, deviceName: options.deviceName, version: options.version,
      versionName: options.versionName, apkSha256: options.apkSha256};
    let state = store.load();
    if (state) need(equal(state.binding, binding), 'STATE_BINDING_CHANGED');
    else { state = {binding, diagnosticId: randomBytes(32).toString('hex'), steps: {}}; store.save(state); }
    const t = transport || new WebTransport({session: read(options.session)});
    const matches = (await t.json('/api/devices', null, io())).devices.filter(d => d.id === options.deviceId
      && d.name === options.deviceName && d.model_id === 'mdl_d31');
    need(matches.length === 1 && matches[0].ready === true && matches[0].app_version === options.versionName, 'TARGET_NOT_READY');
    const save = () => store.save(state);
    async function task(label, type, params) {
      let step = state.steps[label];
      if (!step) {
        step = state.steps[label] = {request: {id: 'product-' + randomUUID(), device_id: options.deviceId, type,
          params, expires_at: now() + 600000}}; save();
      }
      need(step.request.type === type && equal(step.request.params, params), 'STEP_CHANGED');
      const until = now() + 150000;
      while (!step.result && now() < until) {
        const found = await t.queryTask(options.deviceId, step.request.id, io());
        if (found && terminal.has(found.state)) { step.result = found; save(); break; }
        if (!found && !step.accepted) {
          need(now() < step.request.expires_at, 'ORIGINAL_REQUEST_EXPIRED');
          step.intent = true; save();
          step.enqueue = await t.enqueue(step.request, io()); step.accepted = true; save();
        }
        await wait(2000);
      }
      need(step.result, 'PENDING_QUERY_ORIGINAL_TASK');
      return validateParent(step.result, step.request);
    }
    const root = command => ({cwd: '/', command, timeout: 90});
    const before = validateBaseline((await task('before', 'root_exec', root(baseline))).text, options);
    const reportPath = '/data/local/d31-remote/diagnostics/' + state.diagnosticId + '/report.json';
    const command = 'CLASSPATH=' + quote(before.path)
      + ' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteDiagnosticCommand '
      + quote(state.diagnosticId) + ' ' + quote('{"operation":"runtime"}');
    const receipt = JSON.parse((await task('diagnostic', 'root_exec', root(command))).text);
    need(receipt.id === state.diagnosticId && receipt.state === 'completed' && receipt.path === reportPath
      && /^[a-f0-9]{64}$/.test(receipt.sha256) && Number.isSafeInteger(receipt.bytes)
      && receipt.bytes > 0 && receipt.bytes <= 1048576, 'DIAGNOSTIC_RECEIPT_INVALID');
    const returned = await task('file', 'get_file', {path: reportPath, allow_cellular: false});
    need(returned.bytes === receipt.bytes && returned.sha256 === receipt.sha256, 'RETURN_BINDING_CHANGED');
    const file = path.resolve(options.state, 'product-runtime-private.json');
    if (!fs.existsSync(file)) {
      const temporary = file + '.' + randomUUID() + '.part';
      await t.download(options.deviceId, state.steps.file.request.id,
        {filename: temporary, bytes: returned.bytes, signal: AbortSignal.timeout(60000), onBytes() {}});
      validateReport(fs.readFileSync(temporary), receipt, returned);
      fs.renameSync(temporary, file);
    }
    const report = validateReport(fs.readFileSync(file), receipt, returned);
    const after = validateBaseline((await task('after', 'root_exec', root(baseline))).text, options);
    need(equal(before, after), 'OBSERVATION_IDENTITY_CHANGED');
    const active = report.active;
    need(active?.state === 'OBSERVED' && active.metadata?.sha256 === before.sha256
      && active.metadata?.versionCode === before.version && active.metadata?.path === before.path, 'REPORT_ACTIVE_CHANGED');
    state.verified = {reportSha256: receipt.sha256, identity: before, atomicSnapshot: false}; save();
    if (!state.received || state.received.cleanup_pending === true) {
      // 先回读本地保全原件，随后才清理本次传输的服务器暂存。
      validateReport(fs.readFileSync(file), receipt, returned);
      state.received = await t.received(options.deviceId, state.steps.file.request.id,
        {size: returned.bytes, sha256: returned.sha256}, io()); save();
    }
    return {status: 'CAPTURE_VERIFIED', observed: report.productConfiguration.observed,
      total: report.productConfiguration.total, cleanupPending: state.received.cleanup_pending === true,
      systemConsistency: 'NOT_ASSESSED', repairExecuted: false};
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const options = read(process.argv[2]); options.execute = process.argv.includes('--execute');
  console.log(JSON.stringify(await collect(options)));
}
