import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {randomUUID, randomBytes} from 'node:crypto';
import {isDeepStrictEqual as equal} from 'node:util';
import {QueueStore, QueueError, hash, requireThat as need} from './fault-transfer/QueueStore.mjs';
import {WebTransport} from './fault-transfer/WebTransport.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const TARGET = '/data/local/d31-system-support/start.sh';
const BOOT = '/system/bin/cat /proc/sys/kernel/random/boot_id';
const ACTIVE = '/system/bin/cat /data/local/d31-remote/runtime/active.json';
const terminal = new Set(['success', 'failed', 'rejected', 'expired', 'cancelled']);
const read = p => JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, ''));
const quote = s => "'" + s.replaceAll("'", "'\\''") + "'";
const stamp = value => {
  const time = typeof value === 'string' && value.endsWith('Z') ? Date.parse(value) : value;
  need(Number.isSafeInteger(time) && time >= 0, 'TASK_TIME_INVALID'); return time;
};

function local(data) {
  const p = spawnSync(data.options.python || 'python', ['-B', path.join(HERE, 'baseline/collect_start_sh_post_evidence.py')],
    {input: JSON.stringify(data), encoding: 'utf8', windowsHide: true, timeout: 180000, maxBuffer: 1048576});
  need(p.status === 0, /^[A-Z0-9_]+$/.test(p.stderr?.trim() || '') ? p.stderr.trim() : 'LOCAL_VERIFY_FAILED');
  return JSON.parse(p.stdout);
}

function parent(task, request, after) {
  need(task?.id === request.id && task.type === request.type, 'PARENT_ID_TYPE_MISMATCH');
  need(task.device_id === undefined || task.device_id === request.device_id, 'PARENT_DEVICE_MISMATCH');
  need(task.params === undefined || equal(task.params, request.params), 'PARENT_PARAMS_MISMATCH');
  need(typeof task.state === 'string', 'PARENT_STATE_MISSING');
  if (!terminal.has(task.state)) return false;
  need(task.state === 'success', 'PARENT_FAILED');
  need(stamp(task.started_at) > after && stamp(task.completed_at) >= stamp(task.started_at), 'POST_TASK_TIME_INVALID');
  const r = task.result;
  if (request.type === 'root_exec') {
    need(r?.action === 'completed' && r.exit_code === 0 && r.truncated === false && typeof r.text === 'string', 'ROOT_OUTPUT_INCOMPLETE');
  } else need(r?.action === 'uploaded' && /^[a-f0-9]{64}$/.test(r.sha256 || '')
    && Number.isSafeInteger(r.bytes) && r.bytes > 0 && r.bytes <= 8388608, 'FILE_RETURN_INVALID');
  return true;
}

// 独立延续已保存的query；没有submit/run、上传或修改目标的入口。
export async function collect(options, {transport, transportFactory, simulation = false, now = Date.now,
  wait = ms => new Promise(resolve => setTimeout(resolve, ms))} = {}) {
  const context = local({action: 'preflight', options});
  if (!options.execute) return {status: 'LOCAL_PREVIEW', eventCount: context.receipt.next_event, closedLoopVerified: false};
  need(!context.value.fixture || simulation, 'SYNTHETIC_EVIDENCE_CANNOT_EXECUTE');
  need(options.state, 'PERSISTENT_STATE_DIRECTORY_REQUIRED');
  const store = new QueueStore(options.state, {python: options.python || 'python'});
  return store.withLock(async () => {
    const binding = {handoff: options.handoffSha256, query: options.handoffResultSha256,
      apk: options.apkSha256, version: options.version, device: options.deviceId};
    let state = store.load();
    if (state) need(equal(state.binding, binding), 'STATE_BINDING_MISMATCH');
    else {
      const diagnosticId = randomBytes(32).toString('hex');
      const diagnosticRequest = {operation: 'manifest', scope: TARGET,
        identity: {...context.prepared.observationIdentity, snapshotId: 'readonly-' + randomUUID().replaceAll('-', '')}};
      state = {binding, diagnosticId, diagnosticRequest, steps: {}}; store.save(state);
    }
    const save = () => store.save(state);
    const io = () => ({signal: AbortSignal.timeout(25000)});
    const t = transport || transportFactory();
    async function task(label, type, params, after) {
      let step = state.steps[label];
      if (!step) {
        const request = {device_id: options.deviceId, id: 'd31-post-' + randomUUID(), type, params, expires_at: now() + 600000};
        step = state.steps[label] = {request, intent: false,
          requestFile: store.artifact(label + '-request-private', request)}; save();
      }
      need(equal(read(store.file(step.requestFile)), step.request) && step.request.type === type
        && step.request.device_id === options.deviceId && equal(step.request.params, params), 'PERSISTED_REQUEST_CHANGED');
      let current;
      if (step.resultFile) current = read(store.file(step.resultFile)).task;
      else {
        if (step.intent) current = await t.queryTask(options.deviceId, step.request.id, io());
        else {
          need(now() < step.request.expires_at, 'REQUEST_EXPIRED');
          step.intent = true; save();
          try { current = await t.enqueue(step.request, io()); }
          catch { current = await t.queryTask(options.deviceId, step.request.id, io()); }
        }
        // 权威明确未入队才补投同一请求；未知下载或未知查询不产生新编号。
        if (current === null) {
          need(now() < step.request.expires_at, 'UNKNOWN_PARENT_EXPIRED');
          try { current = await t.enqueue(step.request, io()); }
          catch { current = await t.queryTask(options.deviceId, step.request.id, io()); }
        }
        for (let i = 0; i < 60; i++) {
          need(current !== null, 'UNKNOWN_PARENT_RESUME_ORIGINAL_ID');
          const file = store.artifact(label + '-response-private', {ok: true, task: current});
          if (terminal.has(current.state)) { step.resultFile = file; save(); }
          if (parent(current, step.request, after)) break;
          need(i < 59, 'PARENT_PENDING_RESUME_ORIGINAL_ID');
          await wait(2000); current = await t.queryTask(options.deviceId, step.request.id, io());
        }
      }
      need(parent(current, step.request, after), 'PARENT_PENDING_RESUME_ORIGINAL_ID');
      return {step, result: current.result, ended: stamp(current.completed_at)};
    }
    const root = (label, command, after, timeout = 30) => task(label, 'root_exec', {command, cwd: '/', timeout}, after);
    async function deliver(label, step, r) {
      const binding = {deviceId: options.deviceId, taskId: step.request.id, path: step.request.params.path,
        size: r.bytes, sha256: r.sha256, file: step.file,
        requestSha256: hash(fs.readFileSync(store.file(step.requestFile))),
        resultSha256: hash(fs.readFileSync(store.file(step.resultFile)))};
      if (!step.delivery) {
        step.delivery = {binding, proof: store.artifact(label + '-local-verified-private', binding),
          state: 'PENDING', attempts: 0, nextAt: 0}; save();
      }
      const d = step.delivery;
      const verifyLocal = () => {
        need(equal(d.binding, binding) && equal(read(store.file(d.proof)), binding)
          && equal(read(store.file(step.requestFile)), step.request)
          && hash(fs.readFileSync(store.file(step.resultFile))) === binding.resultSha256
          && store.bundleMatches(step.file, r), 'DELIVERY_LOCAL_PROOF_MISMATCH');
      };
      verifyLocal();
      if (d.state === 'DELIVERED' || d.nextAt > now()) return;
      // 存储接收回执不代表修复验收；原字节及父任务证据先持久化并再回读。
      d.attempts++; d.nextAt = now() + 2000; save(); verifyLocal();
      let response;
      try {
        response = await t.received(binding.deviceId, binding.taskId, {size: binding.size, sha256: binding.sha256}, io());
        store.artifact(label + '-received-private', response);
        need(response?.ok === true && ((response.purged === true && response.cleanup_pending !== true)
          || (response.cleanup_pending === true && response.purged !== true)), 'RETURN_RECEIPT_RESPONSE_INVALID');
      } catch (error) {
        if (!(error instanceof QueueError)) throw error;
        d.lastError = {code: error.code, at: now()};
        d.nextAt = Math.max(now() + Math.min(60000, 2000 * 2 ** Math.min(5, d.attempts)),
          Number.isSafeInteger(error.retryAt) ? error.retryAt : 0); save(); return;
      }
      d.state = 'DELIVERED'; d.purged = response.purged === true;
      d.cleanupPending = response.cleanup_pending === true; d.nextAt = 0; delete d.lastError; save();
    }
    async function get(label, remote, after, limit) {
      const item = await task(label, 'get_file', {path: remote, allow_cellular: false}, after);
      const {step, result: r} = item;
      need(r.bytes <= limit, 'RETURN_SIZE_LIMIT');
      if (!step.file) {
        const meta = await t.metadata(options.deviceId, step.request.id, io());
        store.artifact(label + '-metadata-private', meta);
        need(meta.ok === true && meta.file?.device_id === options.deviceId && meta.file.task_id === step.request.id
          && meta.file.size === r.bytes && meta.file.sha256 === r.sha256, 'METADATA_MISMATCH');
        store.capacity(r.bytes);
        const temporary = label + '-' + randomUUID() + '.part';
        await t.download(options.deviceId, step.request.id, {filename: store.file(temporary), bytes: r.bytes,
          signal: AbortSignal.timeout(120000), onBytes: () => {}});
        need(store.bundleMatches(temporary, r), 'DOWNLOAD_DIGEST_MISMATCH');
        step.file = temporary; save();
      }
      need(store.bundleMatches(step.file, r), 'SAVED_DOWNLOAD_CHANGED');
      await deliver(label, step, r);
      return {...item, bytes: fs.readFileSync(store.file(step.file))};
    }
    function active(item) {
      const a = JSON.parse(item.result.text);
      need(a.versionCode === options.version && a.sha256 === options.apkSha256 && a.path === context.apk, 'ACTIVE_APK_MISMATCH');
    }
    function boot(item) { need(item.result.text.trim() === context.value.bootId, 'BOOT_CHANGED'); }
    active(await root('active-before', ACTIVE, context.queryEnd));
    boot(await root('boot-before', BOOT, context.queryEnd));
    const plan = await get('plan', context.journal + '/plan.json', context.queryEnd, 131072);
    let head = hash(plan.bytes), last;
    for (let i = 0; i < context.receipt.next_event; i++) {
      const event = await get('event-' + String(i).padStart(6, '0'), context.journal + '/' + String(i).padStart(6, '0') + '.json', context.queryEnd, 131072);
      const obj = JSON.parse(event.bytes);
      need(obj.sequence === i && obj.previous_sha256 === head && obj.plan_sha256 === context.value.planSha256, 'EVENT_CHAIN_MISMATCH');
      head = hash(event.bytes); last = obj.state;
    }
    need(equal(last, context.receipt.state), 'QUERY_EVENT_PREFIX_MISMATCH');
    const reportPath = '/data/local/d31-remote/diagnostics/' + state.diagnosticId + '/report.json';
    const diagnostic = await root('diagnostic', 'CLASSPATH=' + quote(context.apk)
      + ' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteDiagnosticCommand '
      + quote(state.diagnosticId) + ' ' + quote(JSON.stringify(state.diagnosticRequest)), context.queryEnd, 120);
    const receipt = JSON.parse(diagnostic.result.text);
    need(receipt.id === state.diagnosticId && receipt.path === reportPath && receipt.state === 'completed', 'DIAGNOSTIC_RECEIPT_INVALID');
    const observation = await get('observation', reportPath, diagnostic.ended, 8388608);
    need(receipt.sha256 === hash(observation.bytes) && receipt.bytes === observation.bytes.length, 'REPORT_DIGEST_MISMATCH');
    const m = JSON.parse(observation.bytes).manifest;
    for (const [key, value] of Object.entries(state.diagnosticRequest.identity)) need(equal(m[key], value), 'OBSERVATION_IDENTITY_CHANGED');
    need(m.snapshotId !== context.prepared.before.snapshotId && state.diagnosticId !== context.prepared.before.diagnosticId, 'CACHED_DIAGNOSTIC_REUSED');
    const after = await get('after_file', TARGET, observation.ended, 4096);
    boot(await root('boot', BOOT, after.ended));
    active(await root('active-after', ACTIVE, after.ended));
    const proofs = Object.fromEntries(Object.entries(state.steps).map(([label, step]) => [label, {
      request: store.file(step.requestFile), result: store.file(step.resultFile), ...(step.file ? {file: store.file(step.file)} : {}),
    }]));
    const collection = {diagnosticId: state.diagnosticId, diagnosticRequest: state.diagnosticRequest, proofs};
    store.artifact('collection-private', collection);
    // 每次离线复核使用新目录；异常中断不覆盖先前半成品，也不重跑修复或诊断。
    const output = path.join(path.dirname(store.directory), path.basename(store.directory) + '-verify-' + randomUUID());
    const verified = local({action: 'finalize', options, collection, output});
    const deliveries = Object.values(state.steps).filter(step => step.delivery).map(step => step.delivery);
    const returnCleanup = {delivered: deliveries.filter(d => d.state === 'DELIVERED').length,
      pending: deliveries.filter(d => d.state !== 'DELIVERED').length,
      cleanupPending: deliveries.filter(d => d.cleanupPending).length};
    const result = {...verified, simulation, executed: !simulation, returnCleanup};
    store.artifact('post-evidence-result-private', result); return result;
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [config, flag] = process.argv.slice(2);
    need(config && (flag === undefined || flag === '--execute') && process.argv.length <= 4, 'CLI_ARGUMENT_INVALID');
    const options = {...read(config), execute: flag === '--execute'};
    const result = await collect(options, {transportFactory: () => {
      need(options.session, 'SESSION_REQUIRED'); return new WebTransport({session: read(options.session)});
    }});
    console.log(JSON.stringify({status: result.status, closedLoopVerified: result.closedLoopVerified,
      fixture: result.fixture, executed: result.executed || false, returnCleanup: result.returnCleanup}));
  } catch (error) {
    console.error('后置证据停止，保留原号和原件：' + (/^[A-Z0-9_]+$/.test(error.message) ? error.message : 'POST_EVIDENCE_FAILED'));
    process.exitCode = 2;
  }
}
