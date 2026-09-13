import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {QueueStore, hash, requireThat as need} from './fault-transfer/QueueStore.mjs';
import {WebTransport} from './fault-transfer/WebTransport.mjs';

const ownDir = path.dirname(fileURLToPath(import.meta.url));
const quote = value => "'" + value.replaceAll("'", "'\\''") + "'";
const terminal = new Set(['success', 'failed', 'rejected', 'expired', 'cancelled']);
const phases = new Set(['SUCCEEDED', 'REJECTED', 'ROLLED_BACK', 'NEEDS_ATTENTION']);
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));

export function validateLocal(options, now = Date.now()) {
  need(/^[a-f0-9]{64}$/.test(options.handoffSha256 || '') && /^[a-f0-9]{64}$/.test(options.apkSha256 || ''), 'EXPLICIT_DIGEST_REQUIRED');
  need(/^[A-Za-z0-9_-]{1,96}$/.test(options.deviceId || '') && Number.isSafeInteger(options.version) && options.version > 0, 'EXPLICIT_DEVICE_VERSION_REQUIRED');
  need(typeof options.versionName === 'string' && /^[A-Za-z0-9][A-Za-z0-9._+-]{0,95}$/.test(options.versionName), 'EXPLICIT_VERSION_NAME_REQUIRED');
  const args = [path.join(ownDir, 'baseline/check_start_sh_handoff.py'), '--handoff', path.resolve(options.handoff),
    '--sha256', options.handoffSha256, '--device-id', options.deviceId, '--java', options.java,
    '--classpath', options.classpath, '--now-ms', String(now), '--historical'];
  const result = spawnSync(options.python || 'python', args, {encoding: 'utf8', windowsHide: true, timeout: 70000, maxBuffer: 1048576});
  need(result.status === 0, 'LOCAL_HANDOFF_REJECTED');
  const value = JSON.parse(result.stdout);
  const bytes = fs.readFileSync(path.join(path.dirname(path.resolve(options.handoff)), value.payload.local));
  need(hash(bytes) === value.payload.sha256 && bytes.length === value.payload.bytes, 'PAYLOAD_CHANGED');
  return {value, bytes};
}

// 沿用WebTransport的鉴权、JSON边界及原任务查询；仅补已有上传PUT。
export class HandoffTransport extends WebTransport {
  async putPart(id, bytes, options) {
    let response;
    try {
      response = await this.fetch(this.base + '/api/elfremote/files/' + id + '/parts/0?sha256=' + hash(bytes), {
        method: 'PUT', redirect: 'error', signal: options.signal,
        headers: {Cookie: this.cookie, Origin: this.base, 'Content-Type': 'application/octet-stream', 'Content-Length': String(bytes.length)}, body: bytes,
      });
      need(response.ok, 'UPLOAD_PART_HTTP');
      const text = await response.text(); need(Buffer.byteLength(text) <= 262144, 'UPLOAD_JSON_LIMIT');
      const value = JSON.parse(text); need(value.ok === true, 'UPLOAD_PART_REJECTED'); return value;
    } catch { throw Error('UPLOAD_PART_UNCONFIRMED'); }
  }
}

function parent(task, request) {
  need(task && task.id === request.id && task.type === request.type, 'PARENT_ID_TYPE_MISMATCH');
  need(task.device_id === undefined || task.device_id === request.device_id, 'PARENT_DEVICE_MISMATCH');
  if (task.params && request.type === 'send_file') {
    for (const key of ['path', 'transfer_id', 'overwrite']) need(task.params[key] === request.params[key], 'PARENT_PARAMS_MISMATCH');
  }
  // 当前publicRepair不返回root_exec.params，不能依赖一个不存在的字段。
  if (task.params && request.type === 'root_exec') need(JSON.stringify(task.params) === JSON.stringify(request.params), 'PARENT_PARAMS_MISMATCH');
  need(typeof task.state === 'string', 'PARENT_STATE_MISSING');
  return task;
}

function rootResult(task) {
  need(task.state === 'success', 'ROOT_PARENT_FAILED');
  const result = task.result;
  need(result?.action === 'completed' && result.exit_code === 0 && result.truncated === false
    && typeof result.text === 'string', 'ROOT_OUTPUT_INCOMPLETE');
  return result.text;
}

function repairReceipt(task, value) {
  let result;
  try { result = JSON.parse(rootResult(task)); } catch (error) { throw error; }
  need(result.schema === 1 && result.task_id === value.taskId && result.plan_sha256 === value.planSha256, 'REPAIR_RECEIPT_BINDING');
  need(result.verification_scope === 'FILE_CONTENT_AND_METADATA' && result.runtime_effect === 'NOT_CHECKED'
    && result.system_consistency === 'NOT_ASSESSED' && typeof result.state?.phase === 'string'
    && Number.isSafeInteger(result.next_event) && result.next_event > 0 && result.next_event <= 256, 'REPAIR_RECEIPT_SCOPE');
  need(result.journal === '/data/local/d31-remote/repairs/' + value.taskId, 'REPAIR_JOURNAL_MISMATCH');
  return result;
}

export async function consume(options, {transport, transportFactory, now = Date.now,
  wait = ms => new Promise(resolve => setTimeout(resolve, ms)), simulation = false} = {}) {
  const {value, bytes} = validateLocal(options, now());
  const fresh = () => now() >= value.capturedAtMs && now() <= value.validUntilMs;
  const requireFresh = () => need(fresh(), 'EVIDENCE_EXPIRED_OR_FUTURE');
  const apk = '/data/local/d31-remote/releases/' + options.apkSha256 + '/remote.apk';
  const command = operation => 'CLASSPATH=' + quote(apk) + ' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteRepairCommand '
    + quote(JSON.stringify(value.requests[operation]));
  const preview = {deviceId: value.deviceId, taskId: value.taskId, handoffSha256: value.handoffSha256,
    planSha256: value.planSha256, preparedSha256: value.preparedSha256, evidenceSha256: value.evidenceSha256,
    fixture: value.fixture, fresh: fresh(), version: options.version, versionName: options.versionName, apk, payload: value.payload,
    order: ['active', 'boot', 'mkdir-input', 'upload', 'send_file', 'submit', 'run', 'query'],
    executed: false, closedLoopVerified: false};
  if (!options.execute) return preview;
  need(!value.fixture || simulation, 'SYNTHETIC_EVIDENCE_CANNOT_EXECUTE');
  need(options.state, 'PERSISTENT_STATE_DIRECTORY_REQUIRED');
  const store = new QueueStore(options.state, {python: options.python || 'python'});
  return store.withLock(async () => {
    const binding = {handoff: value.handoffSha256, device: value.deviceId, apk: options.apkSha256, version: options.version, versionName: options.versionName};
    let state = store.load();
    if (state) need(JSON.stringify(state.binding) === JSON.stringify(binding), 'STATE_BINDING_MISMATCH');
    else {
      if (!options.queryOnly) requireFresh();
      state = {binding, attempts: 0, steps: {}, upload: null}; store.save(state);
    }
    const save = () => store.save(state);
    const archive = (label, data) => store.artifact(label, data);
    const io = () => ({signal: AbortSignal.timeout(20000)});
    const t = transport || transportFactory();
    const deviceList = await t.json('/api/devices', null, io());
    const devices = deviceList.devices?.filter(d => d.id === value.deviceId);
    need(devices?.length === 1, 'EXACT_DEVICE_NOT_FOUND');
    const device = devices[0];
    need(device.model_id === 'mdl_d31' && device.ready === true && device.enabled !== false
      && device.app_version === options.versionName && device.managed_file_tasks === true, 'DEVICE_NOT_READY_OR_VERSION');
    archive('device-private', device);
    const resubmitted = new Set();

    async function task(label, type, params, mayCreate = true) {
      let step = state.steps[label];
      if (!step) {
        need(mayCreate, 'QUERY_REQUIRES_EXISTING_PARENT');
        if (!options.queryOnly) requireFresh();
        const request = {device_id: value.deviceId, id: 'd31-handoff-' + randomUUID(), type, params,
          expires_at: options.queryOnly ? now() + 600000 : Math.min(now() + 600000, value.validUntilMs)};
        step = state.steps[label] = {request, sha256: hash(Buffer.from(JSON.stringify(request))), intent: false}; save();
      }
      need(step.sha256 === hash(Buffer.from(JSON.stringify(step.request))) && step.request.device_id === value.deviceId
        && step.request.type === type && JSON.stringify(step.request.params) === JSON.stringify(params), 'PERSISTED_REQUEST_CHANGED');
      if (step.result) return parent(step.result, step.request);
      let current;
      if (step.intent) current = await t.queryTask(value.deviceId, step.request.id, io());
      else {
        if (!options.queryOnly) requireFresh();
        need(now() < step.request.expires_at, 'REQUEST_EXPIRED');
        step.intent = true; save();
        try { current = await t.enqueue(step.request, io()); archive('enqueue-private', current); }
        catch { current = await t.queryTask(value.deviceId, step.request.id, io()); }
      }
      if (current === null && !options.queryOnly && fresh() && now() < step.request.expires_at
          && !resubmitted.has(step.request.id)) {
        // queryTask只有确切权威404才返回null；原编号、原请求最多补投一次。
        resubmitted.add(step.request.id);
        try { current = await t.enqueue(step.request, io()); archive('reenqueue-private', current); }
        catch { current = await t.queryTask(value.deviceId, step.request.id, io()); }
      }
      need(current !== null, 'UNKNOWN_PARENT_NOT_RECREATED');
      const deadline = now() + 150000;
      for (let count = 0; count < 80; count++) {
        parent(current, step.request);
        if (terminal.has(current.state)) {
          step.result = current; save(); archive('parent-result-private', {ok: true, task: current}); return current;
        }
        need(now() < deadline, 'PARENT_PENDING_QUERY_ORIGINAL_ID');
        await wait(2000);
        current = await t.queryTask(value.deviceId, step.request.id, io());
        need(current !== null, 'UNKNOWN_PARENT_NOT_RECREATED');
      }
      throw Error('PARENT_PENDING_QUERY_ORIGINAL_ID');
    }
    // 过期后仍可查询已提交的父任务，但绝不继续下一条修改命令。
    if (!fresh() && !options.queryOnly) {
      for (const [label, step] of Object.entries(state.steps)) {
        if (step.intent && !step.result) await task(label, step.request.type, step.request.params, false);
      }
      throw Error('EVIDENCE_EXPIRED_RECOVERY_READ_ONLY');
    }
    const attempt = ++state.attempts; save();
    const root = (label, text, timeout = 30) => task(label, 'root_exec', {command: text, cwd: '/', timeout});
    async function queryResult(label, extra = {}) {
      const queried = await root(label, command('query'));
      const receipt = repairReceipt(queried, value);
      const result = {...preview, simulation, status: phases.has(receipt.state.phase)
        ? 'TERMINAL_REQUIRES_OFFLINE_VERIFY' : 'TRANSACTION_PENDING', ...extra,
        receipt, queryRequest: state.steps[label].request, queryResult: {ok: true, task: queried},
        postEvidence: {device_plan: null, events: null, after_bundle: null, after_file: null, boot: null}};
      archive('handoff-result-private', result); return result;
    }
    async function guard(label, checkBoot = true) {
      const active = JSON.parse(rootResult(await root('active-' + label, '/system/bin/cat /data/local/d31-remote/runtime/active.json')));
      need(active.versionCode === options.version && active.sha256 === options.apkSha256 && active.path === apk, 'ACTIVE_APK_MISMATCH');
      if (checkBoot) need(rootResult(await root('boot-' + label, '/system/bin/cat /proc/sys/kernel/random/boot_id')).trim() === value.bootId, 'BOOT_CHANGED');
    }
    await guard('entry-' + attempt, !options.queryOnly);
    if (options.queryOnly) {
      // 中断查询先续原父任务；完整结束后的显式查询才建立下一次只读观察。
      const pending = Object.keys(state.steps).find(label => /^query-only-\d+$/.test(label)
        && state.steps[label].intent && !state.steps[label].result);
      return queryResult(pending || 'query-only-' + attempt, {queryOnly: true, executed: false});
    }
    rootResult(await root('mkdir-input', 'umask 077; /system/bin/mkdir -p ' + quote(path.posix.dirname(value.payload.remote))));
    if (state.steps.send) {
      // 服务端可能已清理delivered上传块；先查询原send_file，不能先要求上传仍ready。
      await task('send', 'send_file', {transfer_id: state.upload.file.id, path: value.payload.remote, overwrite: false});
    } else {
      requireFresh();
      if (!state.upload) { state.upload = {initIntent: false}; save(); }
      if (!state.upload.file) {
        need(!state.upload.initIntent, 'UPLOAD_INIT_UNKNOWN_NO_AUTOMATIC_RECREATE');
        state.upload.initIntent = true; save();
        const response = await t.json('/api/elfremote/files', {device_id: value.deviceId, name: 'start-script', size: bytes.length}, io());
        archive('upload-init-private', response);
        need(response.ok === true && /^[a-f0-9]{32}$/.test(response.file?.id || ''), 'UPLOAD_INIT_INVALID');
        state.upload.file = response.file; save();
      }
      const id = state.upload.file.id;
      let m = (await t.json('/api/elfremote/files/' + id, null, io())).file;
      function metadata(file, ready = false) {
        need(file && file.id === id && file.device_id === value.deviceId && file.name === 'start-script'
          && file.size === bytes.length && file.chunk_size === 8388608 && file.expires_at > now(), 'UPLOAD_METADATA_MISMATCH');
        need(['uploading', 'ready'].includes(file.state), 'UPLOAD_STATE_INVALID');
        if (ready) need(file.state === 'ready' && file.sha256 === value.payload.sha256, 'UPLOAD_NOT_SEALED');
      }
      metadata(m);
      if (m.state === 'uploading') {
        requireFresh();
        // 原PUT按同编号、同摘要幂等；公开元数据不必暴露内部parts。
        await t.putPart(id, bytes, io());
        requireFresh();
        m = (await t.json('/api/elfremote/files/' + id + '/complete', {sha256: value.payload.sha256}, io())).file;
      }
      metadata(m, true); state.upload.file = m; save();
      const sent = await task('send', 'send_file', {transfer_id: id, path: value.payload.remote, overwrite: false});
      need(sent.state === 'success' && sent.result?.action === 'committed'
        && sent.result.sha256 === value.payload.sha256 && sent.result.bytes === bytes.length, 'SEND_FILE_NOT_COMMITTED');
    }
    const sent = state.steps.send.result;
    need(sent.state === 'success' && sent.result?.action === 'committed'
      && sent.result.sha256 === value.payload.sha256 && sent.result.bytes === bytes.length, 'SEND_FILE_NOT_COMMITTED');
    await guard('submit-' + attempt);
    const submitted = repairReceipt(await root('submit', command('submit')), value);
    if (phases.has(submitted.state.phase)) {
      // submit的终态不能充当query证据；仍执行原query，绝不再run。
      return queryResult('query', {alreadyTerminalNoRun: true, executed: false});
    }
    need(submitted.state.phase === 'PENDING', 'SUBMIT_PHASE_NOT_PENDING');
    await guard('run-' + attempt);
    const ran = repairReceipt(await root('run', command('run'), 120), value);
    // 只有完整run成功回执才走正常query；失败由显式query-only恢复，不自动再run。
    return queryResult('query', {executed: !simulation, runPhase: ran.state.phase});
  });
}

async function main() {
  const args = process.argv.slice(2), options = {};
  const flags = {'--execute': 'execute', '--query-only': 'queryOnly'};
  const fields = {'--handoff': 'handoff', '--handoff-sha256': 'handoffSha256', '--device-id': 'deviceId', '--apk-sha256': 'apkSha256',
    '--version': 'version', '--version-name': 'versionName', '--state': 'state', '--session': 'session', '--python': 'python', '--java': 'java', '--classpath': 'classpath'};
  for (let i = 0; i < args.length; i++) {
    if (flags[args[i]]) options[flags[args[i]]] = true;
    else { need(fields[args[i]] && args[i + 1] && !args[i + 1].startsWith('--'), 'CLI_ARGUMENT_INVALID'); options[fields[args[i]]] = args[++i]; }
  }
  options.version = Number(options.version);
  need(!options.queryOnly || options.execute, 'QUERY_REQUIRES_EXPLICIT_EXECUTE');
  const result = await consume(options, {transportFactory: () => {
    need(options.session, 'SESSION_REQUIRED_FOR_EXECUTE');
    return new HandoffTransport({session: read(options.session)});
  }});
  // 不在控制台打印身份、方案、原件、令牌或设备命令输出。
  console.log(JSON.stringify({status: result.status || (result.queryOnly ? 'QUERY_ONLY' : 'LOCAL_PREVIEW'),
    fresh: result.fresh, fixture: result.fixture, executed: result.executed, closedLoopVerified: false}));
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error('交接停止，原编号和原件保留：' + (/^[A-Z0-9_]+$/.test(error.message) ? error.message : 'HANDOFF_FAILED')); process.exitCode = 2; });
}
