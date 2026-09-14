import fs from 'node:fs';
import {pathToFileURL} from 'node:url';
import {QueueStore, hash, requireThat as need} from '../fault-transfer/QueueStore.mjs';
import {RecoverableTransport} from './RecoverableTransport.mjs';

const terminal = new Set(['success', 'failed', 'rejected', 'expired', 'cancelled']);
const equal = (a, b) => JSON.stringify(canonical(a)) === JSON.stringify(canonical(b));
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, canonical(value[k])]));
  return value;
}
function validate(task, request) {
  need(task?.id === request.id && task.type === request.type, 'TASK_BINDING_MISMATCH');
  need(task.device_id === undefined || task.device_id === request.device_id, 'DEVICE_BINDING_MISMATCH');
  if (task.params !== undefined && request.type === 'send_file') {
    // 服务端fileParams规范化覆盖开关，并从上传记录补入下载元数据。
    need(task.params !== null && task.params.path === request.params.path
      && task.params.transfer_id === request.params.transfer_id
      && task.params.overwrite === (request.params.overwrite === true), 'TASK_PARAMS_MISMATCH');
  } else need(task.params === undefined || equal(task.params, request.params), 'TASK_PARAMS_MISMATCH');
  need(typeof task.state === 'string', 'TASK_STATE_INVALID');
}

// 每项预算独立；宿主等待结束只保存待续，绝不取消远端事务。
export async function resumeBatch({store, transport, requests, submit = false, maxWaitMs = 30000,
  now = Date.now, wait = ms => new Promise(resolve => setTimeout(resolve, ms))}) {
  need(Array.isArray(requests) && requests.length > 0 && requests.length <= 32, 'REQUEST_LIST_INVALID');
  need(Number.isInteger(maxWaitMs) && maxWaitMs >= 1 && maxWaitMs <= 60000, 'WAIT_BUDGET_INVALID');
  const keys = new Set();
  for (const r of requests) {
    need(/^[A-Za-z0-9_-]{1,96}$/.test(r.id) && /^[A-Za-z0-9_-]{1,96}$/.test(r.device_id)
      && ['root_exec', 'get_file', 'send_file'].includes(r.type) && r.params && typeof r.params === 'object'
      && Number.isSafeInteger(r.expires_at), 'ORIGINAL_REQUEST_REQUIRED');
    const key = r.device_id + ':' + r.id; need(!keys.has(key), 'DUPLICATE_TASK'); keys.add(key);
  }
  return store.withLock(async () => {
    let state = store.load();
    const binding = hash(JSON.stringify(canonical(requests)));
    if (state) need(state.kind === 'network-resume-v1' && state.binding === binding, 'STATE_BINDING_CHANGED');
    else { state = {kind: 'network-resume-v1', binding, items: {}}; store.save(state); }
    const output = [];
    for (const request of requests) {
      const key = hash(request.device_id + ':' + request.id);
      const item = state.items[key] ||= {request, attempts: 0};
      const save = () => store.save(state);
      const until = now() + maxWaitMs;
      const io = () => ({signal: AbortSignal.timeout(Math.max(1, Math.min(20000, until - now())))});
      let status = 'PENDING';
      try {
        if (now() < (item.retryAt || 0)) { output.push({id: request.id, status}); continue; }
        while (!item.result && now() < until) {
          if (now() < (item.retryAt || 0)) {
            await wait(Math.min(item.retryAt - now(), until - now())); continue;
          }
          try {
          const task = await transport.queryTask(request.device_id, request.id, io());
          if (task !== null) {
            validate(task, request); item.accepted = true;
            if (terminal.has(task.state)) item.result = task;
            save();
          } else if (submit && !item.accepted && now() < request.expires_at && item.attempts < 3) {
            // 只有既有queryTask确认的权威404允许补投，编号和请求保持不变。
            item.attempts++; item.intent = true; save();
            const accepted = await transport.enqueue(request, io());
            validate(accepted, request); item.accepted = true;
            if (terminal.has(accepted.state)) item.result = accepted;
            save();
          } else { break; }
          item.retryAt = 0;
          } catch (error) {
            if (!error.retryable) throw error;
            item.error = error.code; item.retryAt = Math.max(now() + 2000, error.retryAt || 0); save();
            continue;
          }
          if (!item.result && now() < until) await wait(Math.min(2000, until - now()));
        }
        if (item.result) {
          status = item.result.state;
          if (item.downloaded) need(store.bundleMatches(item.downloaded.file, item.downloaded), 'LOCAL_FILE_CHANGED');
          if (status === 'success' && request.type === 'get_file' && !item.downloaded && now() < until) {
            const result = item.result.result;
            need(result?.action === 'uploaded' && Number.isSafeInteger(result.bytes) && result.bytes > 0
              && result.bytes <= 8388608 && /^[a-f0-9]{64}$/.test(result.sha256), 'FILE_RESULT_INVALID');
            const {file} = await transport.metadata(request.device_id, request.id, io());
            need(file?.state === 'ready' && file.device_id === request.device_id && file.task_id === request.id
              && file.sha256 === result.sha256 && file.size === result.bytes, 'FILE_METADATA_MISMATCH');
            store.capacity(result.bytes);
            const name = 'return-' + key + '.part';
            await transport.downloadResume(request.device_id, request.id, {filename: store.file(name),
              bytes: result.bytes, sha256: result.sha256, ...io()});
            item.downloaded = {file: name, bytes: result.bytes, sha256: result.sha256}; save();
          }
          if (status === 'success' && request.type === 'get_file' && !item.downloaded) status = 'PENDING_DOWNLOAD';
        }
        if (item.result || !item.error) { item.error = null; item.retryAt = 0; }
        save();
      } catch (error) {
        status = error.retryable ? 'PENDING' : 'NEEDS_ATTENTION';
        item.error = error.code || 'LOCAL_OPERATION_FAILED';
        item.retryAt = error.retryable ? Math.max(now() + 2000, error.retryAt || 0) : 0; save();
      }
      output.push({id: request.id, status, error: item.error || undefined, file: item.downloaded?.file});
    }
    return output;
  });
}

export async function main(argv) {
  const args = {}; let submit = false;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--submit-original') { need(!submit, 'ARGUMENT_INVALID'); submit = true; continue; }
    need(['--requests', '--state', '--session', '--python', '--wait-ms'].includes(argv[i])
      && !Object.hasOwn(args, argv[i]) && argv[i + 1] && !argv[i + 1].startsWith('--'), 'ARGUMENT_INVALID');
    args[argv[i]] = argv[++i];
  }
  for (const key of ['--requests', '--state', '--session']) need(args[key], 'ARGUMENT_REQUIRED');
  const read = file => { need(fs.statSync(file).size <= 1048576, 'INPUT_TOO_LARGE'); return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')); };
  const result = await resumeBatch({store: new QueueStore(args['--state'], {python: args['--python'] || 'python'}),
    transport: new RecoverableTransport({session: read(args['--session'])}), requests: read(args['--requests']),
    submit, maxWaitMs: Number(args['--wait-ms'] || 30000)});
  console.log(JSON.stringify(result));
  return result.some(item => item.status.startsWith('PENDING')) ? 2
    : result.some(item => !['success'].includes(item.status)) ? 1 : 0;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)
  main(process.argv.slice(2)).then(code => { process.exitCode = code; }).catch(error => {
    console.error(JSON.stringify({status: 'NEEDS_ATTENTION', code: error.code || 'LOCAL_OPERATION_FAILED'})); process.exitCode = 1;
  });
