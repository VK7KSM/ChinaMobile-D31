import fs from 'node:fs';
import {randomUUID, createHash} from 'node:crypto';
import {isDeepStrictEqual} from 'node:util';
import {pathToFileURL} from 'node:url';
import {QueueStore, QueueError, requireThat as check} from './fault-transfer/QueueStore.mjs';
import {WebTransport} from './fault-transfer/WebTransport.mjs';
import {validateTarget, systemClock} from './fault-transfer/FaultTransferQueue.mjs';
import {validateOpen, validatePage, validateClose} from './Verify-RemoteContactsPage.mjs';
import {parseOutput} from './Verify-RemoteContactsBridge.mjs';

const TERMINAL = ['success', 'failed', 'rejected', 'expired', 'cancelled'];
const BUSY = ['pending', 'claimed', 'running'];
const sha256 = value => createHash('sha256').update(value).digest('hex');
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value);
const cleanCode = error => error instanceof QueueError && /^[A-Z0-9_]{1,80}$/.test(error.code)
  ? error.code : 'CONTACTS_WEB_VALIDATION_FAILED';
const DEFAULTS = {maxMs: 90000, maxRequests: 96, pollMs: 1000, requestMs: 10000, maxPages: 16};

function targetOf(input) {
  const target = validateTarget(input);
  check(Number.isSafeInteger(input.versionCode) && input.versionCode > 128, 'CONTACTS_WEB_VERSION_INVALID');
  return {...target, versionCode: input.versionCode};
}
function limitsOf(input) {
  const limits = {...DEFAULTS, ...input};
  const max = {maxMs: 180000, maxRequests: 256, pollMs: 10000, requestMs: 20000, maxPages: 64};
  for (const [key, value] of Object.entries(limits)) check(Number.isSafeInteger(value) && value >= 1 && value <= max[key], 'CONTACTS_WEB_LIMIT_INVALID');
  check(limits.pollMs >= 500, 'CONTACTS_WEB_LIMIT_INVALID');
  return limits;
}

export function preflightCommand(target) {
  return 'id -u\ngetprop ro.build.version.sdk\ngetprop ro.product.device\ngetprop ro.product.model\n'
    + 'getprop ro.build.fingerprint\ncat /proc/sys/kernel/random/boot_id\n'
    + 'cat /data/local/d31-remote/runtime/active.json\nprintf \'\\n\'\n'
    + `busybox sha256sum '${target.activeApk}'\n`
    + 'installed=$(pm path net.elfradio.d31bootstrap); installed=${installed#package:}\n'
    + 'case "$installed" in /*.apk) busybox sha256sum "$installed";; *) exit 125;; esac';
}
export function validatePreflight(text, target) {
  const lines = text.trim().split('\n');
  // 文件本身已有末尾换行时，显式分隔符只会在JSON后多出这一空行。
  if (lines.length === 10 && lines[7] === '') lines.splice(7, 1);
  check(lines.length === 9 && lines[0] === '0' && lines[1] === '23' && lines[2] === 'hct6735_66_m0'
    && lines[3] === 'hct6737t_66_m0' && lines[4].includes(':6.0/') && uuid(lines[5]), 'CONTACTS_WEB_DEVICE_IDENTITY');
  const active = JSON.parse(lines[6]), hash = target.activeApk.split('/')[5];
  check(active.package === 'net.elfradio.d31bootstrap' && active.path === target.activeApk
    && active.sha256 === hash && active.versionCode === target.versionCode, 'CONTACTS_WEB_ACTIVE_IDENTITY');
  check(lines[7].split(/\s+/)[0] === hash && lines[8].split(/\s+/)[0] === hash, 'CONTACTS_WEB_APK_HASH');
  return lines[5];
}

function taskResult(task, step) {
  check(task && task.id === step.request.id && task.type === 'root_exec'
    && task.expires_at === step.request.expires_at, 'CONTACTS_WEB_TASK_MISMATCH');
  const result = task.result;
  check(result && result.truncated === false && typeof result.text === 'string' && result.text.length <= 16000
    && result.stage === 'command' && result.action === 'completed'
    && Number.isInteger(result.exit_code)
    && ((task.state === 'success' && result.exit_code === 0) || (task.state === 'failed' && result.exit_code !== 0)),
  'CONTACTS_WEB_COMMAND_INCOMPLETE');
  const parsed = parseOutput(Buffer.from(result.text, 'utf8'), step.marker);
  check(parsed.exitCode === result.exit_code, 'CONTACTS_WEB_EXIT_MISMATCH');
  return parsed;
}
function receiptTask(store, step) {
  check(step.receipt && /^[a-f0-9]{64}$/.test(step.receipt.sha256), 'CONTACTS_WEB_RECEIPT_MISSING');
  const bytes = fs.readFileSync(store.file(step.receipt.file));
  check(sha256(bytes) === step.receipt.sha256, 'CONTACTS_WEB_RECEIPT_CHANGED');
  return JSON.parse(bytes.toString('utf8'));
}

// 每一阶段只允许一次POST；提交意图已落盘但结果未知时，即使404也只查原号。
export async function runContactsWeb({store, transport, target: inputTarget, limits: inputLimits = {},
  clock = systemClock, checkpoint = () => {}}) {
  const target = targetOf(inputTarget), limits = limitsOf(inputLimits), hash = target.activeApk.split('/')[5];
  return store.withLock(async () => {
    let state = store.load(), requests = 0;
    const began = clock.monotonic();
    const save = label => {
      state.lastWall = Math.max(state.lastWall || 0, clock.now());
      store.save(state); checkpoint(label, structuredClone(state));
    };
    const budget = () => {
      store.assertLocked();
      if ((state?.retryAt || 0) > clock.now()) throw new QueueError('CONTACTS_WEB_RETRY_BACKOFF', true);
      if (clock.monotonic() - began >= limits.maxMs) throw new QueueError('CONTACTS_WEB_ROUND_TIME', true);
      if (requests >= limits.maxRequests) throw new QueueError('CONTACTS_WEB_ROUND_REQUESTS', true);
    };
    const call = async action => {
      budget(); requests++;
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), Math.max(1, Math.min(limits.requestMs, limits.maxMs - (clock.monotonic() - began))));
      try { return await action({signal: controller.signal}); }
      catch (error) {
        if (error instanceof QueueError && error.retryable) {
          const retryAt = Number.isSafeInteger(error.retryAt) && error.retryAt > 0 ? error.retryAt : 0;
          state.retryAt = Math.max(state.retryAt || 0, retryAt, clock.now() + limits.pollMs);
          save('retry-backoff');
        }
        throw error;
      } finally { clearTimeout(timeout); }
    };
    const artifact = (kind, value) => {
      const bytes = Buffer.from(JSON.stringify(value));
      check(bytes.length <= 262144, 'CONTACTS_WEB_RECEIPT_LIMIT');
      const file = store.writeNew(`contacts-${kind}-private-${randomUUID()}.json`, bytes);
      return {file, sha256: sha256(bytes)};
    };
    let status = 'PAUSED', stop;
    try {
      if (!state) {
        state = {schemaVersion: 1, kind: 'D31_CONTACTS_WEB_ACCEPTANCE', target, maxPages: limits.maxPages,
          operationId: 'contacts-web-' + randomUUID(), steps: {}, lastWall: clock.now()};
        save('workflow-intent');
      }
      check(state.schemaVersion === 1 && state.kind === 'D31_CONTACTS_WEB_ACCEPTANCE'
        && isDeepStrictEqual(state.target, target) && state.maxPages === limits.maxPages
        && /^contacts-web-[a-f0-9-]{36}$/.test(state.operationId) && state.steps && typeof state.steps === 'object', 'CONTACTS_WEB_STATE_MISMATCH');
      check(state.retryAt === undefined || (Number.isSafeInteger(state.retryAt) && state.retryAt >= 0), 'CONTACTS_WEB_RETRY_STATE_INVALID');
      if (clock.now() < state.lastWall) { state.ttlInvalid = true; save('clock-regressed'); }
      // 复用现有D31唯一名称、型号、就绪、版本及文件返回能力门。
      const resolved = await call(options => transport.resolveTarget(target, options));
      if (state.deviceId) check(state.deviceId === resolved.id, 'CONTACTS_WEB_DEVICE_CHANGED');
      else { state.deviceId = resolved.id; save('target-bound'); }
      const currentDevice = async () => {
        const reported = await call(options => transport.json('/api/devices', null, options));
        check(Array.isArray(reported.devices), 'CONTACTS_WEB_DEVICE_LIST_INVALID');
        const peers = reported.devices.filter(device => device && device.id === state.deviceId);
        check(peers.length === 1 && peers[0].name === target.deviceName && peers[0].model_id === 'mdl_d31'
          && peers[0].managed_exec_tasks === true && peers[0].managed_file_return === true && peers[0].ready === true
          && String(peers[0].app_version) === target.expectedVersion
          && reported.devices.filter(device => device && device.name === target.deviceName && device.model_id === 'mdl_d31').length === 1,
        'CONTACTS_WEB_EXEC_CAPABILITY');
        return peers[0];
      };
      await currentDevice();

      const ttlAvailable = () => !state.ttlInvalid && state.openSentAt !== undefined
        && clock.now() >= state.openSentAt && clock.now() - state.openSentAt < 100000;
      const step = async (key, command, {withinSnapshot = false} = {}) => {
        let intent = state.steps[key];
        if (!intent) {
          if (withinSnapshot && !ttlAvailable()) throw new QueueError('CONTACTS_WEB_SNAPSHOT_DEADLINE');
          const marker = 'D31_CONTACTS_WEB_' + randomUUID().replace(/-/g, '');
          const wrapped = `( ${command}\n); contacts_code=$?; echo; echo ${marker}_$contacts_code; exit "$contacts_code"`;
          const expires = withinSnapshot ? state.openSentAt + 100000 : clock.now() + 120000;
          intent = state.steps[key] = {marker, command, request: {device_id: state.deviceId,
            id: 'd31-contacts-' + randomUUID(), type: 'root_exec', params: {cwd: '/', timeout: 20, command: wrapped}, expires_at: expires},
          submitted: false, nextQueryAt: 0};
          save(`intent:${key}`);
        }
        check(intent.command === command && intent.request.device_id === state.deviceId && intent.request.type === 'root_exec'
          && /^d31-contacts-[a-f0-9-]{36}$/.test(intent.request.id)
          && /^D31_CONTACTS_WEB_[a-f0-9]{32}$/.test(intent.marker)
          && isDeepStrictEqual(intent.request.params, {cwd: '/', timeout: 20,
            command: `( ${command}\n); contacts_code=$?; echo; echo ${intent.marker}_$contacts_code; exit "$contacts_code"`})
          && Number.isSafeInteger(intent.request.expires_at) && typeof intent.submitted === 'boolean', 'CONTACTS_WEB_INTENT_MISMATCH');
        if (intent.receipt) return taskResult(receiptTask(store, intent), intent);
        // 未解决的其它命令不能让出忙槽，不能借后续close取消或覆盖原操作。
        check(Object.entries(state.steps).every(([other, value]) => other === key || !value.submitted || value.receipt), 'CONTACTS_WEB_OTHER_TASK_UNRESOLVED');
        for (;;) {
          budget();
          const wait = intent.nextQueryAt - clock.now();
          if (wait > 0) {
            if (wait > limits.maxMs - (clock.monotonic() - began)) throw new QueueError('CONTACTS_WEB_POLL_BACKOFF', true);
            await clock.sleep(wait);
          }
          intent.nextQueryAt = clock.now() + limits.pollMs; save(`query-intent:${key}`);
          let remote = await call(options => transport.queryTask(state.deviceId, intent.request.id, options));
          artifact('query', {key, task: remote});
          if (remote === null) {
            if (intent.submitted) throw new QueueError('CONTACTS_WEB_SUBMISSION_UNKNOWN', true);
            check(!state.ttlInvalid && clock.now() < intent.request.expires_at, 'CONTACTS_WEB_INTENT_EXPIRED');
            if (withinSnapshot && !ttlAvailable()) throw new QueueError('CONTACTS_WEB_SNAPSHOT_DEADLINE');
            const current = (await currentDevice()).task;
            check(current && typeof current === 'object' && !Array.isArray(current)
              && typeof current.id === 'string' && typeof current.type === 'string' && typeof current.state === 'string',
            'CONTACTS_WEB_TASK_SLOT_INVALID');
            const empty = current.id === '' && current.type === '' && current.state === '';
            check(empty || (current.id.length > 0 && current.type.length > 0 && [...BUSY, ...TERMINAL].includes(current.state)),
              'CONTACTS_WEB_TASK_SLOT_INVALID');
            if (BUSY.includes(current.state)) throw new QueueError('CONTACTS_WEB_BUSY_SLOT', true);
            check(!state.ttlInvalid && clock.now() < intent.request.expires_at, 'CONTACTS_WEB_INTENT_EXPIRED');
            if (withinSnapshot && !ttlAvailable()) throw new QueueError('CONTACTS_WEB_SNAPSHOT_DEADLINE');
            // 必须在POST前同步；崩溃发生于此后不能靠404猜测允许重发。
            intent.submitted = true; intent.submittedAt = clock.now();
            if (key === 'open') state.openSentAt = intent.submittedAt;
            save(`submit-intent:${key}`);
            remote = await call(options => transport.enqueue(intent.request, options));
            checkpoint(`after-submit:${key}`, structuredClone(state));
            artifact('enqueue', {key, task: remote});
          }
          check(remote && remote.id === intent.request.id && remote.type === 'root_exec'
            && remote.expires_at === intent.request.expires_at, 'CONTACTS_WEB_TASK_MISMATCH');
          if (TERMINAL.includes(remote.state)) {
            intent.receipt = artifact('terminal', remote); save(`terminal:${key}`);
            return taskResult(remote, intent);
          }
          check(BUSY.includes(remote.state), 'CONTACTS_WEB_TASK_STATE');
          if (clock.now() > intent.request.expires_at + 60000) throw new QueueError('CONTACTS_WEB_TASK_STILL_UNRESOLVED', true);
        }
      };
      const contactCommand = args => `[ "$(cat /proc/sys/kernel/random/boot_id)" = '${state.boot}' ] || exit 126\n`
        + `CLASSPATH='${target.activeApk}' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.management.ContactsPageCommand ${args}`;
      const cleanup = async () => {
        if (!state.descriptor) return;
        const response = await step('close', contactCommand(`close ${hash} ${state.descriptor.snapshot_id}`));
        const value = JSON.parse(response.text);
        if (response.exitCode === 1 && value.ok === false && value.state === 'CONTACTS_SNAPSHOT_GONE' && !value.page) {
          state.failure ||= 'CONTACTS_WEB_SNAPSHOT_ALREADY_GONE'; state.closeGone = true; save('close-expired'); return;
        }
        validateClose(value, response.exitCode, hash, state.descriptor.snapshot_id);
        state.closeConfirmed = true; save('closed');
        const refused = await step('closed-cursor', contactCommand(`page ${hash} ${state.descriptor.snapshot_id} 0 1`));
        const rejected = JSON.parse(refused.text);
        check(refused.exitCode === 1 && rejected.ok === false && rejected.state === 'CONTACTS_SNAPSHOT_GONE'
          && rejected.kind === 'NEXUI_APP_LOCAL_PAGE' && !rejected.page, 'CONTACTS_WEB_CLOSED_CURSOR_ACCEPTED');
        state.closedCursorRejected = true; save('closed-cursor-verified');
      };
      try {
        if (!state.failure) {
          const identity = await step('identity', preflightCommand(target));
          check(identity.exitCode === 0, 'CONTACTS_WEB_IDENTITY_COMMAND_FAILED');
          const boot = validatePreflight(identity.text, target);
          if (state.boot) check(state.boot === boot, 'CONTACTS_WEB_BOOT_CHANGED'); else { state.boot = boot; save('identity-verified'); }
          const opened = await step('open', contactCommand(`open ${hash} ${state.operationId}`));
          const descriptor = validateOpen(JSON.parse(opened.text), opened.exitCode, hash, target.versionCode, state.operationId, state.boot);
          if (state.descriptor) check(isDeepStrictEqual(state.descriptor, descriptor), 'CONTACTS_WEB_DESCRIPTOR_CHANGED');
          else { state.descriptor = descriptor; save('snapshot-verified'); }
          let offset = 0, pages = 0, first;
          for (;;) {
            check(pages < limits.maxPages, 'CONTACTS_WEB_PAGE_BUDGET');
            const response = await step(`page-${offset}`, contactCommand(`page ${hash} ${descriptor.snapshot_id} ${offset} 1`), {withinSnapshot: true});
            const page = validatePage(JSON.parse(response.text), response.exitCode, hash, descriptor, offset, 1);
            if (!first) first = page;
            offset = page.next_offset; pages++;
            if (!page.has_more) break;
          }
          const repeated = await step('repeat-first', contactCommand(`page ${hash} ${descriptor.snapshot_id} 0 1`), {withinSnapshot: true});
          const same = validatePage(JSON.parse(repeated.text), repeated.exitCode, hash, descriptor, 0, 1);
          check(isDeepStrictEqual(first, same), 'CONTACTS_WEB_FIRST_PAGE_CHANGED');
          state.pageCount = pages; state.recordCount = offset; state.readComplete = true; save('pages-verified');
        }
      } catch (error) {
        if (error instanceof QueueError && error.retryable) throw error;
        state.failure = cleanCode(error); save('validation-failed');
      }
      await cleanup();
      if (!state.failure && state.readComplete && state.closeConfirmed && state.closedCursorRejected) {
        const final = await step('final-identity', preflightCommand(target));
        check(final.exitCode === 0 && validatePreflight(final.text, target) === state.boot, 'CONTACTS_WEB_FINAL_IDENTITY');
        state.done = true; save('done'); status = 'PASSED';
      } else status = 'FAILED';
      stop = state.failure || (state.done ? 'COMPLETE' : 'CONTACTS_WEB_NOT_COMPLETE');
    } catch (error) {
      stop = cleanCode(error);
      status = error instanceof QueueError && error.retryable ? 'PAUSED' : 'BLOCKED';
    }
    const summary = {status, code: stop, requests, pageCount: state?.pageCount || 0, recordCount: state?.recordCount || 0,
      source: 'LOCAL', crossPageExercised: (state?.pageCount || 0) > 1, closeConfirmed: state?.closeConfirmed === true,
      closedCursorRejected: state?.closedCursorRejected === true, ordinaryWebCommandVerified: status === 'PASSED',
      webContactsListDeveloped: false, adbUsed: false, automaticResubmit: false, personalContentInSummary: false};
    store.artifact('contacts-summary', summary);
    return summary;
  });
}

export async function main(argv) {
  const args = {}, names = ['state-dir', 'session', 'device-name', 'expected-version', 'version-code', 'active-apk', 'python', 'max-pages', 'max-ms'];
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i].replace(/^--/, '');
    check(argv[i].startsWith('--') && names.includes(key) && !(key in args) && argv[i + 1] && !argv[i + 1].startsWith('--'), 'CONTACTS_WEB_CLI_ARGUMENTS');
    args[key] = argv[i + 1];
  }
  for (const key of ['state-dir', 'session', 'device-name', 'expected-version', 'version-code', 'active-apk']) check(args[key], 'CONTACTS_WEB_CLI_REQUIRED');
  const target = targetOf({deviceName: args['device-name'], expectedVersion: args['expected-version'], versionCode: Number(args['version-code']), activeApk: args['active-apk']});
  check(fs.statSync(args.session).size <= 1048576, 'CONTACTS_WEB_SESSION_SIZE');
  const session = JSON.parse(fs.readFileSync(args.session, 'utf8').replace(/^\uFEFF/, ''));
  const limits = {};
  if (args['max-pages']) limits.maxPages = Number(args['max-pages']);
  if (args['max-ms']) limits.maxMs = Number(args['max-ms']);
  const result = await runContactsWeb({store: new QueueStore(args['state-dir'], {python: args.python || 'python'}),
    transport: new WebTransport({session}), target, limits});
  console.log(JSON.stringify(result)); return result.status === 'PASSED' ? 0 : 2;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)
  main(process.argv.slice(2)).then(code => { process.exitCode = code; }).catch(error => {
    console.error(JSON.stringify({status: 'BLOCKED', code: cleanCode(error)})); process.exitCode = 1;
  });
