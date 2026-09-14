import fs from 'node:fs';
import {randomUUID} from 'node:crypto';
import {isDeepStrictEqual} from 'node:util';
import {pathToFileURL} from 'node:url';
import {QueueStore, QueueError, hash, requireThat as check} from './fault-transfer/QueueStore.mjs';
import {WebTransport, retryDeadline} from './fault-transfer/WebTransport.mjs';
import {validateTarget, systemClock} from './fault-transfer/FaultTransferQueue.mjs';

const KIND = 'D31_NETWORK_CONFIRMATION_WEB_V1';
const WINDOW = 60000;
const DEFAULTS = {maxRequests: 100, maxMs: 180000, requestMs: 10000, pollMs: 2000};
const BUSY = ['pending', 'claimed', 'running'];
const TERMINAL = ['success', 'failed', 'rejected', 'expired', 'cancelled'];
const BINDING = ['task_id', 'apk_sha256', 'key', 'before', 'target', 'boot_id', 'started_elapsed', 'deadline_elapsed', 'window_ms'];
const object = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const integer = v => Number.isSafeInteger(v) && v >= 0;
const hex = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
const uuid = v => typeof v === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(v);
const codeOf = e => e instanceof QueueError && /^[A-Z0-9_]{1,80}$/.test(e.code) ? e.code : 'NETWORK_WEB_FAILED';
const inFlight = new Map();

// 复用生产传输和会话工具；额外保留成功响应中的Retry-After，不改变公共工具。
export class NetworkWebTransport extends WebTransport {
  async response(route, body, options) {
    const response = await super.response(route, body, options);
    this.retryAt = Math.max(this.retryAt || 0, retryDeadline(response.headers.get('retry-after'), this.now()));
    return response;
  }
}

function targetOf(input) {
  check(object(input) && hex(input.apkSha256) && typeof input.value === 'boolean', 'NETWORK_WEB_TARGET_INVALID');
  return {...validateTarget({...input, activeApk: `/data/local/d31-remote/releases/${input.apkSha256}/remote.apk`}),
    apkSha256: input.apkSha256, value: input.value};
}
function limitsOf(input) {
  const limits = {...DEFAULTS, ...input};
  for (const [key, value] of Object.entries(limits)) check(integer(value) && value > 0
    && value <= ({...DEFAULTS, pollMs: 10000})[key], 'NETWORK_WEB_LIMIT_INVALID');
  check(limits.pollMs >= 1000, 'NETWORK_WEB_LIMIT_INVALID');
  return limits;
}
function paramsOf(target) {
  return {group: 'wifi', action: 'set', package: '', offset: 0, key: 'enabled', value: target.value,
    network_transaction: {version: 1, apk_sha256: target.apkSha256, confirm_within_ms: WINDOW}};
}
// 冻结Web规范化合同的摘要；离线测试与真实enqueueRepairTask的摘要交叉核验。
export function requestDigest(params) {
  const canonical = v => object(v) ? Object.fromEntries(Object.keys(v).sort().map(k => [k, canonical(v[k])])) : v;
  return hash(JSON.stringify(canonical({type: 'system_config', params})));
}
function bindingOf(value, state) {
  check(object(value) && Object.keys(value).length === BINDING.length && BINDING.every(k => Object.hasOwn(value, k))
    && value.task_id === hash(`${state.deviceId}:${state.request.id}`) && value.apk_sha256 === state.target.apkSha256
    && value.key === 'wifi_enabled' && typeof value.before === 'boolean' && value.target === state.target.value
    && uuid(value.boot_id) && integer(value.started_elapsed) && integer(value.deadline_elapsed)
    && value.window_ms === WINDOW && value.deadline_elapsed - value.started_elapsed === WINDOW, 'NETWORK_WEB_BINDING');
  if (state.binding) check(isDeepStrictEqual(state.binding, value), 'NETWORK_WEB_REBIND');
  return value;
}

export function validateTask(task, state) {
  check(object(task) && task.id === state.request.id && task.type === 'system_config'
    && task.expires_at === state.request.expires_at && [...BUSY, ...TERMINAL].includes(task.state), 'NETWORK_WEB_ORIGINAL_TASK');
  const n = task.network;
  check(object(n) && n.version === 1 && n.request_digest === state.requestDigest
    && typeof n.confirmation_issued === 'boolean', 'NETWORK_WEB_ORIGINAL_DIGEST');
  if (n.binding !== null) bindingOf(n.binding, state);
  if (n.confirmation_issued) check(integer(n.allowed_at) && integer(n.confirm_deadline_at)
    && n.allowed_at < n.confirm_deadline_at && n.confirm_deadline_at <= state.request.expires_at, 'NETWORK_WEB_ALLOW_WINDOW');
  else check(n.allowed_at === null && n.confirm_deadline_at === null, 'NETWORK_WEB_ALLOW_WINDOW');
  const result = task.result?.network_transaction;
  if (!TERMINAL.includes(task.state)) return {status: 'WAITING', binding: n.binding};
  // 服务端expired不是设备回退证据；无实际结构化终态时继续保留原号。
  if (!object(result)) throw new QueueError('NETWORK_WEB_TERMINAL_UNPROVEN', true);
  check(isDeepStrictEqual(n.result, result) && result.version === 1 && integer(result.observed_elapsed)
    && typeof result.cleanup_complete === 'boolean' && typeof result.restored === 'boolean'
    && (result.current_enabled === null || typeof result.current_enabled === 'boolean'), 'NETWORK_WEB_RESULT');
  if (result.status === 'NOT_STARTED') {
    check(task.state === 'rejected' && result.binding === null && n.binding === null && !n.confirmation_issued
      && result.mutation_started === false && result.cleanup_complete && !result.restored && result.current_enabled === null,
    'NETWORK_WEB_NOT_STARTED');
    return {status: 'FAILED', code: 'NETWORK_WEB_NOT_STARTED', binding: null};
  }
  const b = bindingOf(result.binding, state);
  check(isDeepStrictEqual(n.binding, b) && result.observed_elapsed >= b.started_elapsed, 'NETWORK_WEB_OBSERVATION');
  check(result.cleanup_complete === true, 'NETWORK_WEB_CLEANUP');
  if (result.status === 'CONFIRMED') {
    check(task.state === 'success' && n.confirmation_issued && b.before !== b.target
      && result.observed_elapsed < b.deadline_elapsed && result.current_enabled === b.target
      && !result.restored && uuid(result.confirmation_nonce), 'NETWORK_WEB_CONFIRMATION');
    // 公开视图不包含各nonce的issued_elapsed；服务端负责该下界和grant匹配，不虚构宿主已独立证明。
    return {status: 'PASSED', code: 'CONFIRMED', binding: b, currentEnabled: result.current_enabled};
  }
  if (result.status === 'UNCHANGED') {
    check(task.state === 'success' && b.before === b.target && result.current_enabled === b.target && !result.restored,
      'NETWORK_WEB_UNCHANGED');
    return {status: 'UNCHANGED', code: 'UNCHANGED', binding: b, currentEnabled: result.current_enabled};
  }
  check(task.state === 'failed' && ['ROLLED_BACK', 'ORIGINAL_OBSERVED', 'ABORTED'].includes(result.status), 'NETWORK_WEB_TERMINAL_UNPROVEN');
  if (result.status === 'ROLLED_BACK') check(result.current_enabled === b.before && result.restored, 'NETWORK_WEB_RESTORE_UNPROVEN');
  if (result.status === 'ORIGINAL_OBSERVED') check(result.current_enabled === b.before && !result.restored, 'NETWORK_WEB_ORIGINAL_UNPROVEN');
  return {status: 'FAILED', code: result.status, binding: b, currentEnabled: result.current_enabled};
}

// 一个capture只有一个原号和一个目标；反向恢复由主线另建capture，不自动发第二任务。
export async function runNetworkWeb({store, transport, target: inputTarget, limits: inputLimits = {}, clock = systemClock, checkpoint = () => {}}) {
  const target = targetOf(inputTarget), limits = limitsOf(inputLimits);
  return store.withLock(async () => {
    let state = store.load(), status = 'PAUSED', code = 'NETWORK_WEB_UNRESOLVED', outcome;
    const began = clock.monotonic(), spent = state?.spentMs || 0;
    let reserved = 0;
    const elapsed = () => {
      const delta = clock.monotonic() - began;
      check(Number.isFinite(delta) && delta >= 0, 'NETWORK_WEB_CLOCK_REGRESSED');
      return Math.ceil(delta);
    };
    const save = label => {
      state.spentMs = spent + elapsed() + reserved;
      state.lastWall = Math.max(state.lastWall || 0, clock.now());
      store.save(state); checkpoint(label, structuredClone(state));
    };
    const remaining = () => limits.maxMs - spent - elapsed();
    const budget = () => {
      store.assertLocked();
      check(!state.clockInvalid && clock.now() >= state.lastWall, 'NETWORK_WEB_CLOCK_REGRESSED');
      if (remaining() <= 0) throw new QueueError('NETWORK_WEB_TIME_LIMIT', true);
      if (state.requests >= limits.maxRequests) throw new QueueError('NETWORK_WEB_REQUEST_LIMIT', true);
      if (inFlight.has(store.directory)) throw new QueueError('NETWORK_WEB_TRANSPORT_UNSETTLED', true);
    };
    const artifact = (label, value) => {
      const bytes = Buffer.from(JSON.stringify(value));
      check(bytes.length <= 262144, 'NETWORK_WEB_ARTIFACT_LIMIT');
      return {file: store.writeNew(`network-${label}-private-${randomUUID()}.json`, bytes), sha256: hash(bytes)};
    };
    const call = async (action, beforeSend = () => {}) => {
      budget();
      // 长Retry-After立即退出；即使重新运行也不绕过已持久化退避。
      if (state.retryAt > clock.now()) throw new QueueError('NETWORK_WEB_RETRY_BACKOFF', true);
      const delay = Math.max(0, state.nextAt - clock.now());
      if (delay >= remaining()) throw new QueueError('NETWORK_WEB_TIME_LIMIT', true);
      if (delay) await clock.sleep(delay);
      budget();
      let requestMs = Math.max(1, Math.min(limits.requestMs, remaining()));
      reserved = requestMs; state.requests++; state.nextAt = clock.now() + limits.pollMs;
      save('request-reserved');
      beforeSend();
      // 落盘与提交意图也消耗同一预算，不能在慢磁盘之后重新取得完整HTTP窗口。
      if (remaining() <= 0) throw new QueueError('NETWORK_WEB_TIME_LIMIT', true);
      requestMs = Math.max(1, Math.min(requestMs, remaining()));
      const controller = new AbortController();
      let timer;
      const operation = Promise.resolve().then(() => action({signal: controller.signal}));
      inFlight.set(store.directory, operation);
      const clearFlight = () => { if (inFlight.get(store.directory) === operation) inFlight.delete(store.directory); };
      operation.then(clearFlight, clearFlight);
      try {
        return await Promise.race([operation, new Promise((_, reject) => {
          timer = setTimeout(() => { controller.abort(); reject(new QueueError('NETWORK_WEB_REQUEST_TIMEOUT', true)); }, requestMs);
        })]);
      } catch (error) {
        if (error instanceof QueueError && error.retryable) {
          state.retryAt = Math.max(state.retryAt, integer(error.retryAt) ? error.retryAt : 0, clock.now() + limits.pollMs);
        }
        throw error;
      } finally {
        clearTimeout(timer); controller.abort(); reserved = 0;
        state.retryAt = Math.max(state.retryAt, integer(transport.retryAt) ? transport.retryAt : 0);
        save('request-finished');
      }
    };
    try {
      if (!state) {
        state = {schemaVersion: 1, kind: KIND, target, limits, deviceId: null, request: null, requestDigest: requestDigest(paramsOf(target)),
          submitted: false, requests: 0, spentMs: 0, retryAt: 0, nextAt: 0, lastWall: clock.now()};
        save('workflow-intent');
      }
      check(state.schemaVersion === 1 && state.kind === KIND && isDeepStrictEqual(state.target, target)
        && isDeepStrictEqual(state.limits, limits) && typeof state.submitted === 'boolean'
        && integer(state.requests) && integer(state.spentMs) && integer(state.lastWall) && integer(state.retryAt) && integer(state.nextAt)
        && state.requestDigest === requestDigest(paramsOf(target)), 'NETWORK_WEB_CAPTURE_MISMATCH');
      if (clock.now() < state.lastWall) { state.clockInvalid = true; save('clock-regressed'); }
      if (state.request) {
        check(typeof state.deviceId === 'string' && /^[A-Za-z0-9_-]{1,96}$/.test(state.deviceId)
          && /^d31-network-[a-f0-9-]{36}$/.test(state.request.id) && integer(state.request.expires_at)
          && isDeepStrictEqual(state.request, {device_id: state.deviceId, id: state.request.id, type: 'system_config',
            params: paramsOf(target), expires_at: state.request.expires_at})
          && state.requestHash === hash(JSON.stringify(state.request)), 'NETWORK_WEB_INTENT_MISMATCH');
      } else check(state.deviceId === null && !state.submitted, 'NETWORK_WEB_INTENT_MISSING');
      if (!state.submitted) {
        const reported = await call(options => transport.json('/api/devices', null, options));
        check(Array.isArray(reported.devices), 'NETWORK_WEB_DEVICE_LIST');
        const matches = reported.devices.filter(d => d?.name === target.deviceName && d?.model_id === 'mdl_d31');
        check(matches.length === 1, 'NETWORK_WEB_DEVICE_NOT_UNIQUE');
        const device = matches[0];
        check(typeof device.id === 'string' && /^[A-Za-z0-9_-]{1,96}$/.test(device.id)
          && reported.devices.filter(d => d?.id === device.id).length === 1 && device.ready === true
          && device.enabled !== false && String(device.app_version) === target.expectedVersion
          && device.managed_system_settings === true && device.managed_network_confirmation_v1 === true
          && typeof device.network_write === 'boolean', 'NETWORK_WEB_CAPABILITY');
        // network_write=false仅交给服务器的私有设备/APK验收门裁定，宿主不改能力或白名单。
        artifact('preflight', device);
        if (state.deviceId) check(state.deviceId === device.id, 'NETWORK_WEB_DEVICE_CHANGED');
        else {
          state.deviceId = device.id;
          state.request = {device_id: device.id, id: 'd31-network-' + randomUUID(), type: 'system_config',
            params: paramsOf(target), expires_at: clock.now() + 120000};
          state.requestHash = hash(JSON.stringify(state.request)); save('task-intent');
        }
        const slot = device.task;
        check(object(slot) && typeof slot.id === 'string' && typeof slot.type === 'string' && typeof slot.state === 'string'
          && ((slot.id === '' && slot.type === '' && slot.state === '')
            || (slot.id && slot.type && [...BUSY, ...TERMINAL].includes(slot.state))), 'NETWORK_WEB_SLOT_INVALID');
        if (BUSY.includes(slot.state) && slot.id !== state.request.id) throw new QueueError('NETWORK_WEB_BUSY_SLOT', true);
      }
      for (;;) {
        let task = await call(options => transport.queryTask(state.deviceId, state.request.id, options));
        artifact('query', {task});
        if (task === null) {
          if (state.submitted) throw new QueueError('NETWORK_WEB_SUBMISSION_UNKNOWN', true);
          check(clock.now() < state.request.expires_at, 'NETWORK_WEB_INTENT_EXPIRED');
          task = await call(options => transport.enqueue(state.request, options), () => {
            check(clock.now() < state.request.expires_at, 'NETWORK_WEB_INTENT_EXPIRED');
            state.submitted = true; save('submit-intent');
          });
          artifact('enqueue', {task}); checkpoint('after-submit', structuredClone(state));
        } else if (!state.submitted) { state.submitted = true; save('original-observed'); }
        outcome = validateTask(task, state);
        if (outcome.binding && !state.binding) { state.binding = outcome.binding; save('binding-observed'); }
        if (outcome.status === 'WAITING') { save('awaiting-original'); continue; }
        state.lastTerminal = artifact('terminal', task); save('terminal-observed');
        status = outcome.status; code = outcome.code; break;
      }
    } catch (error) {
      code = codeOf(error); status = error instanceof QueueError && error.retryable ? 'PAUSED' : 'BLOCKED';
    }
    const result = {status, code, requests: state?.requests || 0, spentMs: state?.spentMs || 0,
      confirmationVerified: status === 'PASSED', unchanged: status === 'UNCHANGED', adbUsed: false, automaticResubmit: false,
      target: { ...(state?.target || target), deviceId: state?.deviceId || null, taskId: state?.request?.id || null,
        requestDigest: state?.requestDigest || null, binding: outcome?.binding || state?.binding || null,
        currentEnabled: outcome?.currentEnabled ?? null }};
    artifact('final', result);
    store.artifact('network-summary', publicSummary(result));
    return result;
  });
}

export function publicSummary(result) {
  return {status: result.status, code: result.code, requests: result.requests, spentMs: result.spentMs,
    confirmationVerified: result.confirmationVerified, unchanged: result.unchanged, adbUsed: false, automaticResubmit: false};
}
export async function main(argv) {
  const args = {}, names = ['session', 'capture', 'device-name', 'expected-version', 'apk-sha', 'target', 'python'];
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i].replace(/^--/, '');
    check(argv[i].startsWith('--') && names.includes(key) && !Object.hasOwn(args, key)
      && argv[i + 1] && !argv[i + 1].startsWith('--'), 'NETWORK_WEB_ARGUMENTS');
    args[key] = argv[i + 1];
  }
  for (const key of names.filter(n => n !== 'python')) check(args[key], 'NETWORK_WEB_ARGUMENTS_REQUIRED');
  check(args.target === 'true' || args.target === 'false', 'NETWORK_WEB_BOOLEAN_REQUIRED');
  const target = targetOf({deviceName: args['device-name'], expectedVersion: args['expected-version'], apkSha256: args['apk-sha'], value: args.target === 'true'});
  check(fs.statSync(args.session).size <= 1048576, 'NETWORK_WEB_SESSION_SIZE');
  const session = JSON.parse(fs.readFileSync(args.session, 'utf8').replace(/^\uFEFF/, ''));
  const result = await runNetworkWeb({store: new QueueStore(args.capture, {python: args.python || 'python'}),
    transport: new NetworkWebTransport({session}), target});
  console.log(JSON.stringify(publicSummary(result)));
  return result.status === 'PASSED' ? 0 : 2;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).then(code => { process.exitCode = code; }).catch(error => {
    console.error(JSON.stringify({status: 'BLOCKED', code: codeOf(error)})); process.exitCode = 1;
  });
}
