import fs from 'node:fs';
import {randomUUID, createHash} from 'node:crypto';
import {isDeepStrictEqual} from 'node:util';
import {pathToFileURL} from 'node:url';
import {QueueStore, QueueError, requireThat as check} from './fault-transfer/QueueStore.mjs';
import {WebTransport} from './fault-transfer/WebTransport.mjs';
import {systemClock} from './fault-transfer/FaultTransferQueue.mjs';
import {normalizeContactsPageParams, normalizeContactsPageResult, validateContactsPageSnapshot}
  from './contracts/contacts-page-v1.mjs';

// 沿用第十一批的存储、会话传输、原号查询和忙槽模式；不执行旧root_exec验收链。
// 私有目录复用即恢复原意图；显式换目录才是新验收，未知提交绝不补交。
// 用法：node tools/Verify-RemoteContactsManagementWeb.mjs --state-dir <私有目录> --session <会话文件>
//       --device-name <设备名称> --expected-version <上报版本> [--max-ms 90000] [--max-requests 96] [--max-pages 16]
// 限额与总期限首次运行即冻结；仅验证正式分页及上报版本/能力，不另行声称APK或boot已核验。
const TYPE = 'contacts_page', KIND = 'D31_CONTACTS_MANAGEMENT_WEB_ACCEPTANCE';
const TERMINAL = ['success', 'failed', 'rejected', 'expired', 'cancelled'];
const BUSY = ['pending', 'claimed', 'running'];
const DEFAULTS = {maxMs: 90000, maxRequests: 96, pollMs: 1000, requestMs: 10000, maxPages: 16};
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const integer = (value, min, max) => Number.isSafeInteger(value) && value >= min && value <= max;
const sha256 = value => createHash('sha256').update(value).digest('hex');
const cleanCode = error => (error instanceof QueueError || /^CONTACTS_/.test(error?.code || ''))
  && /^[A-Z0-9_]{1,110}$/.test(error.code) ? error.code : 'CONTACTS_MANAGEMENT_VALIDATION_FAILED';

function targetOf(input) {
  check(object(input) && typeof input.deviceName === 'string' && input.deviceName.length > 0
    && input.deviceName.length <= 128 && !/[\x00-\x1f\x7f]/.test(input.deviceName), 'CONTACTS_MANAGEMENT_TARGET_INVALID');
  check(typeof input.expectedVersion === 'string' && /^[0-9A-Za-z._-]{1,64}$/.test(input.expectedVersion), 'CONTACTS_MANAGEMENT_VERSION_INVALID');
  return {deviceName: input.deviceName, expectedVersion: input.expectedVersion};
}
function limitsOf(input) {
  const limits = {...DEFAULTS, ...input};
  const max = {maxMs: 180000, maxRequests: 100, pollMs: 10000, requestMs: 20000, maxPages: 16};
  for (const [key, value] of Object.entries(limits)) check(integer(value, 1, max[key]), 'CONTACTS_MANAGEMENT_LIMIT_INVALID');
  check(limits.pollMs >= 500, 'CONTACTS_MANAGEMENT_LIMIT_INVALID');
  return limits;
}
function taskIdentity(task, intent) {
  check(object(task) && task.id === intent.request.id && task.type === TYPE
    && task.expires_at === intent.request.expires_at
    && (!('device_id' in task) || task.device_id === intent.request.device_id)
    && (!('params' in task) || isDeepStrictEqual(task.params, intent.request.params)), 'CONTACTS_MANAGEMENT_TASK_MISMATCH');
}
function taskResult(task, intent) {
  if (task?.kind === 'CONTACTS_NOT_ENQUEUED') {
    check(task.http_status === 409 && isDeepStrictEqual(task.request, intent.request)
      && object(task.body) && task.body.ok === false && task.body.not_enqueued === true
      && task.body.code === 'CONTACTS_SNAPSHOT_GONE' && !task.body.task, 'CONTACTS_MANAGEMENT_REJECTION_INVALID');
    const params = intent.request.params;
    return normalizeContactsPageResult({schema_version: 1, action: params.action, ok: false, read_only: true,
      source: 'nexui_messenger', contact_type: 'LOCAL', code: task.body.code,
      ...(params.action === 'open' ? {} : {snapshot_id: params.snapshot_id})}, params);
  }
  taskIdentity(task, intent);
  check(TERMINAL.includes(task.state) && object(task.result), 'CONTACTS_MANAGEMENT_RESULT_MISSING');
  const value = normalizeContactsPageResult(task.result.contacts_page, intent.request.params);
  check(value.ok ? task.state === 'success' : task.state !== 'success', 'CONTACTS_MANAGEMENT_STATE_MISMATCH');
  return value;
}

export class ContactsManagementTransport extends WebTransport {
  async enqueue(request, options) {
    const response = await this.response('/api/elfremote/task', request, options);
    if (response.status === 409) {
      const chunks = []; let size = 0, value;
      try {
        for await (const chunk of response.body) {
          size += chunk.length; check(size <= 262144, 'CONTACTS_MANAGEMENT_REJECTION_LIMIT'); chunks.push(chunk);
        }
        value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      } catch { throw await this.retryError(response, 'CONTACTS_MANAGEMENT_REJECTION_UNKNOWN'); }
      if (object(value) && value.ok === false && value.not_enqueued === true && value.code === 'CONTACTS_SNAPSHOT_GONE' && !value.task)
        return {kind: 'CONTACTS_NOT_ENQUEUED', http_status: 409, request: structuredClone(request), body: value};
      throw await this.retryError(response, 'HTTP_409');
    }
    // 复用既有会话、体积、401和退避解析；交回已取得的响应，不再发第二次请求。
    const received = Object.create(this);
    received.response = async () => response;
    return WebTransport.prototype.enqueue.call(received, request, options);
  }
}
function savedTask(store, intent) {
  check(object(intent.receipt) && /^[a-f0-9]{64}$/.test(intent.receipt.sha256)
    && integer(intent.receipt.observedAt, 1, Number.MAX_SAFE_INTEGER), 'CONTACTS_MANAGEMENT_RECEIPT_INVALID');
  const filename = store.file(intent.receipt.file);
  check(fs.statSync(filename).size <= 262144, 'CONTACTS_MANAGEMENT_RECEIPT_LIMIT');
  const bytes = fs.readFileSync(filename);
  check(sha256(bytes) === intent.receipt.sha256, 'CONTACTS_MANAGEMENT_RECEIPT_CHANGED');
  return JSON.parse(bytes.toString('utf8'));
}

export async function runContactsManagementWeb({store, transport, target: inputTarget, limits: inputLimits = {},
  clock = systemClock, checkpoint = () => {}}) {
  const target = targetOf(inputTarget), limits = limitsOf(inputLimits);
  return store.withLock(async () => {
    let state = store.load(), requests = 0, status = 'BLOCKED', code, stateValidated = false;
    const began = clock.monotonic(), wall = clock.now();
    let lastMono = began;
    const now = () => {
      const actual = clock.now(), mono = clock.monotonic();
      check(integer(actual, 1, Number.MAX_SAFE_INTEGER) && Number.isFinite(mono), 'CONTACTS_MANAGEMENT_CLOCK_INVALID');
      if (state && (actual < state.lastActualWall || mono < lastMono)) state.ttlInvalid = true;
      lastMono = Math.max(lastMono, mono);
      return Math.max(actual, wall + Math.floor(Math.max(0, mono - began)), state?.lastWall || 0);
    };
    const save = label => {
      state.lastWall = now(); state.lastActualWall = clock.now(); store.save(state); checkpoint(label, structuredClone(state));
    };
    const budget = () => {
      store.assertLocked();
      const at = now();
      if (state.retryAt > at) throw new QueueError('CONTACTS_MANAGEMENT_RETRY_BACKOFF', true);
      check(at < state.deadlineAt, 'CONTACTS_MANAGEMENT_TIME_BUDGET');
      check(state.requests < limits.maxRequests, 'CONTACTS_MANAGEMENT_REQUEST_BUDGET');
    };
    const artifact = (kind, value) => {
      const bytes = Buffer.from(JSON.stringify(value));
      check(bytes.length <= 262144, 'CONTACTS_MANAGEMENT_RECEIPT_LIMIT');
      return {file: store.writeNew(`contacts-management-${kind}-private-${randomUUID()}.json`, bytes), sha256: sha256(bytes)};
    };
    const call = async action => {
      budget(); state.requests++; requests++; save('api-intent');
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), Math.max(1, Math.min(limits.requestMs, state.deadlineAt - now())));
      try { return await action({signal: controller.signal}); }
      catch (error) {
        if (error instanceof QueueError && error.retryable) {
          state.retryAt = Math.max(state.retryAt, integer(error.retryAt, 1, Number.MAX_SAFE_INTEGER) ? error.retryAt : 0, now() + limits.pollMs);
          save('retry-backoff');
        }
        throw error;
      } finally { clearTimeout(timeout); }
    };
    try {
      if (!state) {
        state = {schemaVersion: 1, kind: KIND, target, limits, steps: {}, requests: 0, retryAt: 0,
          lastWall: wall, lastActualWall: wall, deadlineAt: wall + limits.maxMs, ttlInvalid: false};
        save('workflow-intent');
      }
      check(state.schemaVersion === 1 && state.kind === KIND && isDeepStrictEqual(state.target, target)
        && isDeepStrictEqual(state.limits, limits) && object(state.steps) && Object.keys(state.steps).length <= limits.maxPages + 4
        && integer(state.requests, 0, limits.maxRequests) && integer(state.retryAt, 0, Number.MAX_SAFE_INTEGER)
        && integer(state.lastWall, 1, Number.MAX_SAFE_INTEGER) && integer(state.deadlineAt, 1, Number.MAX_SAFE_INTEGER)
        && integer(state.lastActualWall, 1, Number.MAX_SAFE_INTEGER)
        && typeof state.ttlInvalid === 'boolean', 'CONTACTS_MANAGEMENT_STATE_MISMATCH');
      stateValidated = true;
      if (clock.now() < state.lastActualWall) { state.ttlInvalid = true; save('clock-regressed'); }
      for (const [key, intent] of Object.entries(state.steps)) {
        check(/^(open|page-\d{1,4}|repeat-first|close|closed-cursor)$/.test(key) && object(intent)
          && object(intent.request) && typeof intent.submitted === 'boolean'
          && integer(intent.nextQueryAt, 0, Number.MAX_SAFE_INTEGER), 'CONTACTS_MANAGEMENT_INTENT_INVALID');
        normalizeContactsPageParams(intent.request.params);
      }
      const currentDevice = async () => {
        const reported = await call(options => transport.json('/api/devices', null, options));
        artifact('devices', reported);
        check(Array.isArray(reported.devices), 'CONTACTS_MANAGEMENT_DEVICE_LIST_INVALID');
        const matches = reported.devices.filter(d => object(d) && d.name === target.deviceName && d.model_id === 'mdl_d31');
        check(matches.length === 1, 'CONTACTS_MANAGEMENT_TARGET_NOT_UNIQUE');
        const d = matches[0];
        check(typeof d.id === 'string' && /^[A-Za-z0-9_-]{1,96}$/.test(d.id)
          && (!state.deviceId || d.id === state.deviceId) && d.ready === true && d.enabled !== false
          && d.app_version === target.expectedVersion && d.managed_contacts_page_v1 === true, 'CONTACTS_MANAGEMENT_CAPABILITY');
        return d;
      };
      if (!state.deviceId) { state.deviceId = (await currentDevice()).id; save('target-bound'); }
      check(typeof state.deviceId === 'string' && /^[A-Za-z0-9_-]{1,96}$/.test(state.deviceId), 'CONTACTS_MANAGEMENT_DEVICE_INVALID');

      const ttl = () => {
        const at = now();
        check(!state.ttlInvalid && integer(state.openSentAt, 1, Number.MAX_SAFE_INTEGER)
          && state.snapshotDeadline === state.openSentAt + 120000 && at < state.snapshotDeadline, 'CONTACTS_MANAGEMENT_SNAPSHOT_DEADLINE');
      };
      const step = async (key, params) => {
        params = normalizeContactsPageParams(params);
        const withinSnapshot = key !== 'open';
        let intent = state.steps[key];
        if (!intent) {
          budget(); if (withinSnapshot) ttl();
          check(Object.values(state.steps).every(s => !s.submitted || s.receipt), 'CONTACTS_MANAGEMENT_OTHER_TASK_UNRESOLVED');
          intent = state.steps[key] = {request: {device_id: state.deviceId, id: 'd31-contacts-page-' + randomUUID(), type: TYPE, params,
            expires_at: Math.min(state.deadlineAt, withinSnapshot ? state.snapshotDeadline : now() + 120000)}, submitted: false, nextQueryAt: 0};
          save(`intent:${key}`);
        }
        check(intent.request.device_id === state.deviceId && intent.request.type === TYPE
          && /^d31-contacts-page-[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(intent.request.id)
          && isDeepStrictEqual(intent.request.params, params) && integer(intent.request.expires_at, 1, state.deadlineAt)
          && (!withinSnapshot || intent.request.expires_at <= state.snapshotDeadline), 'CONTACTS_MANAGEMENT_INTENT_MISMATCH');
        if (intent.receipt) {
          check(intent.receipt.observedAt < state.deadlineAt, 'CONTACTS_MANAGEMENT_TIME_BUDGET');
          return taskResult(savedTask(store, intent), intent);
        }
        check(Object.entries(state.steps).every(([other, s]) => other === key || !s.submitted || s.receipt), 'CONTACTS_MANAGEMENT_OTHER_TASK_UNRESOLVED');
        for (;;) {
          budget();
          const wait = intent.nextQueryAt - now();
          if (wait > 0) {
            check(now() + wait < state.deadlineAt, 'CONTACTS_MANAGEMENT_TIME_BUDGET');
            await clock.sleep(wait);
          }
          intent.nextQueryAt = now() + limits.pollMs; save(`query-intent:${key}`);
          let remote = await call(options => transport.queryTask(state.deviceId, intent.request.id, options));
          artifact('query', {key, task: remote});
          if (remote === null) {
            if (intent.submitted) throw new QueueError('CONTACTS_MANAGEMENT_SUBMISSION_UNKNOWN', true);
            check(!state.ttlInvalid && now() < intent.request.expires_at, 'CONTACTS_MANAGEMENT_INTENT_EXPIRED');
            if (withinSnapshot) ttl();
            const current = (await currentDevice()).task;
            check(object(current) && typeof current.id === 'string' && typeof current.type === 'string'
              && typeof current.state === 'string', 'CONTACTS_MANAGEMENT_SLOT_INVALID');
            const empty = current.id === '' && current.type === '' && current.state === '';
            check(empty || (current.id.length > 0 && current.type.length > 0 && [...BUSY, ...TERMINAL].includes(current.state)), 'CONTACTS_MANAGEMENT_SLOT_INVALID');
            if (BUSY.includes(current.state)) throw new QueueError('CONTACTS_MANAGEMENT_BUSY_SLOT', true);
            // 原号查询与忙槽报告矛盾时，不用POST覆盖已存在但暂时查不到的任务。
            check(empty || current.id !== intent.request.id, 'CONTACTS_MANAGEMENT_LOOKUP_CONTRADICTION');
            budget(); check(!state.ttlInvalid && now() < intent.request.expires_at, 'CONTACTS_MANAGEMENT_INTENT_EXPIRED');
            if (withinSnapshot) ttl();
            intent.submitted = true; intent.submittedAt = now();
            if (key === 'open') { state.openSentAt = intent.submittedAt; state.snapshotDeadline = intent.submittedAt + 120000; }
            save(`submit-intent:${key}`);
            remote = await call(options => transport.enqueue(intent.request, options));
            checkpoint(`after-submit:${key}`, structuredClone(state));
            artifact('enqueue', {key, task: remote});
          }
          if (remote?.kind !== 'CONTACTS_NOT_ENQUEUED') taskIdentity(remote, intent);
          // 本地未记录提交、服务端却已有同号任务时，保全原件但不收养或重建此任务。
          check(intent.submitted, 'CONTACTS_MANAGEMENT_UNOWNED_TASK');
          if (remote?.kind === 'CONTACTS_NOT_ENQUEUED' || TERMINAL.includes(remote.state)) {
            intent.receipt = {...artifact('terminal', remote), observedAt: now()}; save(`terminal:${key}`);
            check(intent.receipt.observedAt < state.deadlineAt, 'CONTACTS_MANAGEMENT_TIME_BUDGET');
            return taskResult(remote, intent);
          }
          check(BUSY.includes(remote.state), 'CONTACTS_MANAGEMENT_TASK_STATE');
        }
      };
      const success = value => { check(value.ok === true, value.code || 'CONTACTS_MANAGEMENT_OPERATION_FAILED'); return value; };
      const descriptor = success(await step('open', {action: 'open', source: 'LOCAL'}));
      if (state.descriptor) check(isDeepStrictEqual(state.descriptor, descriptor), 'CONTACTS_MANAGEMENT_DESCRIPTOR_CHANGED');
      else { state.descriptor = descriptor; save('snapshot-verified'); }
      let offset = 0, pages = 0, first;
      for (;;) {
        check(pages < limits.maxPages, 'CONTACTS_MANAGEMENT_PAGE_BUDGET');
        const page = success(await step(`page-${offset}`, {action: 'page', snapshot_id: descriptor.snapshot_id, offset, limit: 1}));
        validateContactsPageSnapshot(descriptor, page);
        if (!first) first = page;
        offset = page.next_offset; pages++;
        state.pageCount = pages; state.recordCount = offset;
        if (!page.has_more) break;
      }
      const same = success(await step('repeat-first', {action: 'page', snapshot_id: descriptor.snapshot_id, offset: 0, limit: 1}));
      validateContactsPageSnapshot(descriptor, same);
      check(isDeepStrictEqual(first, same), 'CONTACTS_MANAGEMENT_REPEAT_CHANGED');
      state.pageCount = pages; state.recordCount = offset;
      const closed = success(await step('close', {action: 'close', snapshot_id: descriptor.snapshot_id}));
      check(closed.snapshot_closed === true, 'CONTACTS_MANAGEMENT_CLOSE_UNCONFIRMED');
      const gone = await step('closed-cursor', {action: 'page', snapshot_id: descriptor.snapshot_id, offset: 0, limit: 1});
      check(gone.ok === false && gone.code === 'CONTACTS_SNAPSHOT_GONE', 'CONTACTS_MANAGEMENT_CLOSED_CURSOR_ACCEPTED');
      check(state.steps['closed-cursor'].receipt.observedAt < state.snapshotDeadline, 'CONTACTS_MANAGEMENT_GONE_AFTER_TTL');
      state.closedCursorRejectedBeforeEnqueue = savedTask(store, state.steps['closed-cursor']).kind === 'CONTACTS_NOT_ENQUEUED';
      // 完成后再运行仅核对私有原件，不产生API调用或重新采集。
      if (!state.done) { state.done = true; save('done'); }
      status = 'PASSED'; code = 'COMPLETE';
    } catch (error) {
      code = cleanCode(error); status = error instanceof QueueError && error.retryable ? 'PAUSED' : 'BLOCKED';
      if (stateValidated) { state.lastWall = now(); state.lastActualWall = clock.now(); store.save(state); }
    }
    const summary = {status, code, requests, totalRequests: state?.requests || 0, pageCount: state?.pageCount || 0,
      recordCount: state?.recordCount || 0, crossPageExercised: (state?.pageCount || 0) > 1,
      managementContactsVerified: status === 'PASSED', source: 'LOCAL', adbUsed: false, automaticResubmit: false,
      closedCursorRejectedBeforeEnqueue: state?.closedCursorRejectedBeforeEnqueue === true,
      personalContentInSummary: false};
    store.artifact('contacts-management-summary', summary);
    return summary;
  });
}

export async function main(argv) {
  const args = {}, names = ['state-dir', 'session', 'device-name', 'expected-version', 'python', 'max-pages', 'max-ms', 'max-requests'];
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i].replace(/^--/, '');
    check(argv[i].startsWith('--') && names.includes(key) && !(key in args) && argv[i + 1] && !argv[i + 1].startsWith('--'), 'CONTACTS_MANAGEMENT_CLI_ARGUMENTS');
    args[key] = argv[i + 1];
  }
  for (const key of ['state-dir', 'session', 'device-name', 'expected-version']) check(args[key], 'CONTACTS_MANAGEMENT_CLI_REQUIRED');
  const target = targetOf({deviceName: args['device-name'], expectedVersion: args['expected-version']});
  check(fs.statSync(args.session).isFile() && fs.statSync(args.session).size <= 1048576, 'CONTACTS_MANAGEMENT_SESSION_SIZE');
  const session = JSON.parse(fs.readFileSync(args.session, 'utf8').replace(/^\uFEFF/, ''));
  const limits = {};
  for (const [flag, key] of [['max-pages', 'maxPages'], ['max-ms', 'maxMs'], ['max-requests', 'maxRequests']])
    if (args[flag]) limits[key] = Number(args[flag]);
  limitsOf(limits);
  const result = await runContactsManagementWeb({store: new QueueStore(args['state-dir'], {python: args.python || 'python'}),
    transport: new ContactsManagementTransport({session}), target, limits});
  console.log(JSON.stringify(result)); return result.status === 'PASSED' ? 0 : 2;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)
  main(process.argv.slice(2)).then(code => { process.exitCode = code; }).catch(error => {
    console.error(JSON.stringify({status: 'BLOCKED', code: cleanCode(error)})); process.exitCode = 1;
  });
