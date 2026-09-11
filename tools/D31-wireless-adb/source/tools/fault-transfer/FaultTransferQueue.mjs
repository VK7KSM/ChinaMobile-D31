import fs from 'node:fs';
import {randomUUID} from 'node:crypto';
import {QueueError, requireThat} from './QueueStore.mjs';

const HEX = /^[a-f0-9]{64}$/;
const TERMINAL = ['success', 'failed', 'rejected', 'expired', 'cancelled'];
const PHASES = ['CHECK', 'EXPORT', 'GET_FILE', 'DOWNLOAD', 'VERIFY', 'ARCHIVE', 'CONFIRM', 'DONE'];
export const DEFAULT_LIMITS = Object.freeze({maxEvents: 2, maxBytes: 16777216, maxMs: 180000,
  maxRequests: 96, maxIndexPages: 8, maxCandidates: 16, maxRetries: 3, requestMs: 20000, pollMs: 1000, maxBackoffMs: 10000});
export const systemClock = {now: () => Date.now(), monotonic: () => performance.now(), sleep: ms => new Promise(r => setTimeout(r, ms))};

export function validateTarget(target) {
  requireThat(target && typeof target.deviceName === 'string' && target.deviceName.length > 0 && target.deviceName.length <= 128
    && !/[\x00-\x1f\x7f]/.test(target.deviceName), 'TARGET_NAME_INVALID');
  requireThat(typeof target.expectedVersion === 'string' && /^[0-9A-Za-z._-]{1,64}$/.test(target.expectedVersion), 'TARGET_VERSION_INVALID');
  requireThat(/^\/data\/local\/d31-remote\/releases\/[a-f0-9]{64}\/remote\.apk$/.test(target.activeApk || ''), 'ACTIVE_APK_INVALID');
  return {deviceName: target.deviceName, expectedVersion: target.expectedVersion, activeApk: target.activeApk};
}

export function commandParams(activeApk, args) {
  requireThat(args.every(a => /^[a-z0-9-]+$/.test(String(a))), 'COMMAND_ARGUMENT_INVALID');
  return {cwd: '/', timeout: 60, command: `CLASSPATH='${activeApk}' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.faults.FaultCommand ${args.join(' ')}`};
}

function limitsFor(input) {
  const limits = {...DEFAULT_LIMITS, ...input};
  const ceilings = {maxEvents: 3, maxBytes: 25165824, maxMs: 300000, maxRequests: 256,
    maxIndexPages: 32, maxCandidates: 128, maxRetries: 8, requestMs: 60000, pollMs: 10000, maxBackoffMs: 60000};
  for (const [key, value] of Object.entries(limits)) requireThat(Number.isSafeInteger(value) && value >= 1 && value <= ceilings[key], 'ROUND_LIMIT_INVALID');
  return limits;
}

function checkReceipt(receipt, eventId) {
  requireThat(receipt?.schemaVersion === 1 && receipt.kind === 'FAULT_EVENT_EXPORT' && receipt.state === 'EXPORTED'
    && receipt.eventId === eventId && Number.isSafeInteger(receipt.bytes) && receipt.bytes > 0 && receipt.bytes <= 8388608
    && HEX.test(receipt.sha256) && HEX.test(receipt.manifestSha256)
    && Number.isSafeInteger(receipt.exportNumber) && receipt.exportNumber >= 1 && receipt.exportNumber <= 2
    && receipt.path === `/data/local/d31-remote/faults/${eventId}/exports/export-${receipt.exportNumber}/bundle.zip`, 'EXPORT_RECEIPT_INVALID');
}

function checkState(state, target) {
  requireThat(state.schemaVersion === 1 && JSON.stringify(state.target) === JSON.stringify(target), 'STATE_TARGET_MISMATCH');
  requireThat(typeof state.deviceId === 'string' && /^[A-Za-z0-9_-]{1,96}$/.test(state.deviceId), 'STATE_DEVICE_INVALID');
  requireThat(state.cursor === '' || HEX.test(state.cursor), 'STATE_CURSOR_INVALID');
  requireThat(state.discovery === undefined || state.discovery === 'pending16', 'STATE_DISCOVERY_INVALID');
  if (state.page) {
    requireThat(state.discovery === 'pending16' && Array.isArray(state.page.entries) && state.page.entries.length <= 16
      && Number.isSafeInteger(state.page.offset) && state.page.offset >= 0 && state.page.offset <= state.page.entries.length, 'STATE_PAGE_INVALID');
    let previous = '';
    for (const entry of state.page.entries) {
      requireThat(HEX.test(entry.eventId) && entry.eventId > previous, 'STATE_PAGE_ORDER'); previous = entry.eventId;
    }
    requireThat(!previous || previous === state.cursor, 'STATE_PAGE_CURSOR');
  }
  requireThat(Array.isArray(state.events) && state.events.length <= 128, 'QUEUE_ENTRY_LIMIT');
  const ids = new Set();
  for (const event of state.events) {
    requireThat(HEX.test(event.eventId) && !ids.has(event.eventId) && PHASES.includes(event.phase) && event.tasks, 'STATE_EVENT_INVALID');
    ids.add(event.eventId);
    if (event.receipt) checkReceipt(event.receipt, event.eventId);
  }
}

// 一轮有界执行。transport必须遵守signal；不在模块导入时读取会话或启动任何任务。
export async function runRound({store, transport, verifier, target, limits: inputLimits = {}, discoveryOnly = false,
  clock = systemClock, checkpoint = () => {}}) {
  target = validateTarget(target);
  requireThat(typeof discoveryOnly === 'boolean', 'DISCOVERY_ONLY_INVALID');
  const limits = limitsFor(inputLimits);
  return store.withLock(async () => {
    let state = store.load();
    if (state) checkState(state, target);
    requireThat(!discoveryOnly || !state || state.discovery === 'pending16', 'DISCOVERY_ONLY_REQUIRES_PENDING_STATE');
    const started = clock.monotonic();
    const count = {events: 0, requests: 0, indexPages: 0, retries: 0, reservedDownloadBytes: 0, receivedDownloadBytes: 0,
      indexGaps: 0, nonTerminalSkipped: 0, alreadyArchivedSkipped: 0, candidatesChecked: 0, candidatesDeferred: 0};
    let active = null, stop = 'ROUND_COMPLETE';
    const remaining = () => limits.maxMs - (clock.monotonic() - started);
    const save = label => { store.save(state); checkpoint(label, structuredClone(state)); };
    const budget = () => {
      store.assertLocked();
      if (remaining() <= 0) throw new QueueError('ROUND_TIME_BUDGET', true);
    };
    const call = async action => {
      budget();
      if (count.requests >= limits.maxRequests) throw new QueueError('ROUND_REQUEST_BUDGET', true);
      count.requests++;
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), Math.max(1, Math.min(limits.requestMs, remaining())));
      try { return await action({signal: controller.signal}); }
      finally { clearTimeout(timer); }
    };
    const waitUntil = async at => {
      const ms = Math.max(0, at - clock.now());
      if (ms >= remaining()) throw new QueueError('ROUND_TIME_BUDGET', true);
      // 不长时间阻塞宿主；持久nextAt在重启后仍然生效。
      if (ms) await clock.sleep(ms);
      budget();
    };
    const resultOf = task => {
      requireThat(task.state === 'success' && task.result && task.result.truncated !== true, 'TASK_TERMINAL_FAILURE');
      if (task.type === 'root_exec') {
        requireThat(task.result.exit_code === 0 && typeof task.result.text === 'string' && task.result.text.length <= 16000, 'COMMAND_FAILED_OR_TRUNCATED');
        try { return JSON.parse(task.result.text); } catch { throw new QueueError('COMMAND_JSON_INVALID'); }
      }
      return task.result;
    };
    const taskStep = async (holder, key, type, params, beforeSend = () => {}) => {
      let op = holder[key];
      if (!op) {
        op = holder[key] = {request: {device_id: state.deviceId, id: 'd31-fault-' + randomUUID(), type, params, expires_at: clock.now() + 600000}, nextAt: 0};
        save(`intent:${key}`);
      }
      // 恢复时核对原请求全文，防止错目标或状态阶段错配。
      requireThat(op.request.device_id === state.deviceId && op.request.type === type && JSON.stringify(op.request.params) === JSON.stringify(params)
        && /^d31-fault-[a-f0-9-]{36}$/.test(op.request.id) && Number.isSafeInteger(op.request.expires_at), 'TASK_INTENT_MISMATCH');
      if (op.task) return resultOf(op.task);
      for (;;) {
        await waitUntil(op.nextAt);
        op.nextAt = clock.now() + limits.pollMs;
        save(`query-intent:${key}`);
        let task = await call(options => transport.queryTask(state.deviceId, op.request.id, options));
        if (task === null) {
          // 只有权威的“任务不存在”可用原编号、原参数、原有效期补交。
          // 过期后绝不换编号；历史清理也不得被误认为新的任务许可。
          requireThat(clock.now() < op.request.expires_at, 'TASK_ABSENT_AFTER_EXPIRY');
          op.sendAttempts = (op.sendAttempts || 0) + 1;
          save(`send-intent:${key}`);
          beforeSend();
          task = await call(options => transport.enqueue(op.request, options));
          checkpoint(`after-send:${key}`, structuredClone(state));
        }
        requireThat(task && task.id === op.request.id && task.type === type, 'TASK_IDENTITY_MISMATCH');
        if (TERMINAL.includes(task.state)) {
          requireThat(JSON.stringify(task).length <= 65536, 'TASK_RESULT_LIMIT');
          op.task = task; save(`task-result:${key}`);
          return resultOf(task);
        }
        requireThat(['pending', 'claimed', 'running'].includes(task.state), 'TASK_STATE_INVALID');
        requireThat(clock.now() <= op.request.expires_at + 60000, 'TASK_EXPIRED_UNCONFIRMED');
      }
    };
    const command = (holder, key, args, beforeSend) => taskStep(holder, key, 'root_exec', commandParams(target.activeApk, args), beforeSend);
    const verifyLocal = event => {
      requireThat(event.bundle && store.bundleMatches(event.bundle, event.receipt), 'LOCAL_PACKAGE_MISMATCH');
      const proof = store.readJson(event.verified);
      const r = event.receipt;
      requireThat(proof.state === 'HOST_PACKAGE_VERIFIED' && proof.eventId === event.eventId && proof.sha256 === r.sha256
        && proof.bytes === r.bytes && proof.manifestSha256 === r.manifestSha256 && proof.deviceArchiveExecuted === false
        && typeof proof.sourceLogComplete === 'boolean' && Array.isArray(proof.gaps) && proof.gapCount === proof.gaps.length
        && JSON.stringify(proof.archiveArgs) === JSON.stringify(['archive', event.eventId, r.sha256, String(r.bytes), r.manifestSha256]), 'HOST_PROOF_MISMATCH');
      return proof;
    };
    const processEvent = async event => {
      const id = event.eventId;
      while (event.phase !== 'DONE') {
        budget();
        if (event.phase === 'CHECK') {
          if (count.candidatesChecked >= limits.maxCandidates) throw new QueueError('ROUND_CANDIDATE_BUDGET', true);
          const detail = await command(event.tasks, 'inspect', ['query', id]);
          requireThat(detail?.eventId === id, 'CANDIDATE_IDENTITY_MISMATCH');
          event.inspectionFile = store.artifact(`inspection-${id}`, detail); count.candidatesChecked++;
          // 目录已删除或尚未终态的候选只跳过本次遍历，下次从头扫描仍会重新检查。
          if (detail.state === 'NOT_FOUND' || detail.state === 'INDEX_CORRUPT'
              || (detail.state && typeof detail.state === 'object' && !['COMPLETE', 'PARTIAL'].includes(detail.state.phase))) {
            count.candidatesDeferred++;
            state.events.splice(state.events.indexOf(event), 1); save('candidate-deferred'); return false;
          }
          requireThat(detail.schemaVersion === 1 && ['COMPLETE', 'PARTIAL'].includes(detail.state?.phase), 'CANDIDATE_DETAIL_INVALID');
          event.captureState = detail.state.capture; event.category = detail.category;
          event.phase = 'EXPORT'; save('candidate-confirmed');
          if (discoveryOnly) return false;
        } else if (event.phase === 'EXPORT') {
          const receipt = await command(event.tasks, 'export', ['export', id]); checkReceipt(receipt, id);
          event.receipt = receipt; event.receiptFile = store.artifact(`receipt-${id}`, receipt);
          event.phase = 'GET_FILE'; save('exported');
        } else if (event.phase === 'GET_FILE') {
          const result = await taskStep(event.tasks, 'getFile', 'get_file', {path: event.receipt.path, allow_cellular: false});
          requireThat(result.action === 'uploaded' && result.sha256 === event.receipt.sha256 && result.bytes === event.receipt.bytes, 'UPLOAD_RECEIPT_MISMATCH');
          event.phase = 'DOWNLOAD'; save('uploaded-not-archived');
        } else if (event.phase === 'DOWNLOAD') {
          // 下载完成而阶段落盘前崩溃时，用已经落盘的唯一文件名重新验摘要。
          if (!(event.bundle && store.bundleMatches(event.bundle, event.receipt))) {
            if (count.reservedDownloadBytes + event.receipt.bytes > limits.maxBytes) throw new QueueError('ROUND_BYTE_BUDGET', true);
            store.capacity(event.receipt.bytes + 1048576);
            const taskId = event.tasks.getFile.request.id;
            const metadata = await call(options => transport.metadata(state.deviceId, taskId, options));
            const file = metadata.file;
            requireThat(file && file.state === 'ready' && file.device_id === state.deviceId && file.task_id === taskId
              && file.sha256 === event.receipt.sha256 && (file.size ?? file.bytes) === event.receipt.bytes
              && (file.size === undefined || file.bytes === undefined || file.size === file.bytes), 'SERVER_FILE_MISMATCH');
            event.metadataFile = store.artifact(`server-${id}`, metadata);
            event.bundle = `bundle-${id}-${randomUUID()}.zip`;
            event.downloadAttempts = (event.downloadAttempts || 0) + 1;
            save('download-intent');
            count.reservedDownloadBytes += event.receipt.bytes;
            await call(options => transport.download(state.deviceId, taskId, {
              ...options, filename: store.file(event.bundle), bytes: event.receipt.bytes,
              onBytes: n => { count.receivedDownloadBytes += n; },
            }));
            store.syncDirectory(); checkpoint('after-download', structuredClone(state));
            requireThat(store.bundleMatches(event.bundle, event.receipt), 'DOWNLOADED_PACKAGE_MISMATCH');
          }
          event.phase = 'VERIFY'; save('downloaded-not-archived');
        } else if (event.phase === 'VERIFY') {
          requireThat(store.bundleMatches(event.bundle, event.receipt), 'LOCAL_PACKAGE_MISMATCH');
          store.capacity(262144);
          event.verified = `verified-${id}-${randomUUID()}.json`; save('verify-intent');
          await verifier.verify({bundle: store.file(event.bundle), receiptFile: store.file(event.receiptFile), eventId: id,
            output: store.file(event.verified), timeoutMs: Math.max(1, Math.min(20000, remaining()))});
          store.syncDirectory(); checkpoint('after-verify', structuredClone(state));
          verifyLocal(event); event.phase = 'ARCHIVE'; save('host-verified');
        } else if (event.phase === 'ARCHIVE') {
          // 每轮恢复后重新读取本机原件，绝不只相信上轮的布尔“已验证”。
          const proof = verifyLocal(event);
          const ack = await command(event.tasks, 'archive', proof.archiveArgs, () => verifyLocal(event));
          requireThat(ack.state === 'ARCHIVED' && ack.eventId === id && ack.sha256 === event.receipt.sha256
            && ack.bytes === event.receipt.bytes && ack.manifestSha256 === event.receipt.manifestSha256
            && ack.originalsDeleted === false && ack.releasedBytes === 0 && ack.activeSlotReleased === true, 'ARCHIVE_ACK_INVALID');
          event.archiveFile = store.artifact(`archive-${id}`, ack);
          event.phase = 'CONFIRM'; save('archive-awaiting-query');
        } else if (event.phase === 'CONFIRM') {
          const proof = verifyLocal(event);
          const checked = await command(event.tasks, 'confirm', ['query', id]);
          const exported = checked.export;
          requireThat(checked.eventId === id && exported?.archived === true && exported.state === 'EXPORTED'
            && exported.receipt?.sha256 === event.receipt.sha256 && exported.receipt.bytes === event.receipt.bytes
            && exported.receipt.manifestSha256 === event.receipt.manifestSha256, 'ARCHIVE_QUERY_MISMATCH');
          event.queryFile = store.artifact(`query-${id}`, checked);
          event.summary = {hostPackageVerified: true, archived: true, originalsDeleted: false,
            sourceLogComplete: proof.sourceLogComplete, gapCount: proof.gapCount,
            fullIncidentWindow: false, rootCauseEstablished: false};
          event.phase = 'DONE'; save('done');
        }
      }
      return true;
    };
    try {
      if (state?.blocked) { stop = state.blocked.code; }
      else {
        if (state?.nextAt) await waitUntil(state.nextAt);
        const device = await call(options => transport.resolveTarget(target, options));
        if (state) requireThat(device.id === state.deviceId, 'TARGET_DEVICE_CHANGED');
        else {
          state = {schemaVersion: 1, target, deviceId: device.id, discovery: 'pending16',
            cursor: '', events: [], index: {}, nextAt: 0, failures: 0};
          save('initialized');
        }
        while (count.events < limits.maxEvents) {
          budget();
          try {
            if (discoveryOnly && state.events.some(e => !['CHECK', 'EXPORT', 'DONE'].includes(e.phase)
                || (e.phase === 'EXPORT' && e.tasks.export))) { stop = 'DISCOVERY_TRANSFER_PENDING'; break; }
            active = state.events.find(e => discoveryOnly ? e.phase === 'CHECK' : e.phase !== 'DONE') || null;
            if (active) {
              if (await processEvent(active)) count.events++;
              active = null;
              state.failures = 0; state.nextAt = 0; save('event-complete');
              continue;
            }
            if (count.candidatesChecked >= limits.maxCandidates) { stop = 'ROUND_CANDIDATE_BUDGET'; break; }
            if (state.page && state.page.offset < state.page.entries.length) {
              const entry = state.page.entries[state.page.offset];
              const known = state.events.some(e => e.eventId === entry.eventId);
              if (!known && ['COMPLETE', 'PARTIAL'].includes(entry.phase)) {
                requireThat(state.events.length < 128, 'QUEUE_ENTRY_LIMIT');
                // ACK_RECORDED_UNVERIFIED不是本机保全证明；仍走详细查询和正常导出核验。
                state.events.push({eventId: entry.eventId, category: entry.category, captureState: entry.captureState,
                  phase: 'CHECK', tasks: {}});
              } else if (!known) {
                if (entry.phase === 'INDEX_CORRUPT' || entry.phase === 'UNKNOWN' || typeof entry.phase !== 'string') count.indexGaps++;
                else count.nonTerminalSkipped++;
              }
              state.page.offset++; save('page-entry-consumed'); continue;
            }
            if (state.endOfScan) { state.endOfScan = false; state.cursor = ''; delete state.page; save('scan-complete'); break; }
            if (count.indexPages >= limits.maxIndexPages) { stop = 'ROUND_INDEX_BUDGET'; break; }
            if (state.discovery === 'pending16') {
              const index = await command(state.index, 'index', state.cursor ? ['pending', '16', state.cursor] : ['pending', '16']);
              requireThat(index.schemaVersion === 1 && index.kind === 'FAULT_PENDING_INDEX' && index.verificationScope === 'METADATA_ONLY'
                && index.selection === 'ALL_RETAINED_HOST_SELECTS_PENDING' && Array.isArray(index.events)
                && index.events.length <= 16 && typeof index.hasMore === 'boolean'
                && Buffer.byteLength(state.index.index.task.result.text, 'utf8') <= 8000
                && Buffer.byteLength(JSON.stringify(index), 'utf8') <= 8000, 'PENDING_INVALID');
              let previous = state.cursor;
              for (const entry of index.events) {
                requireThat(entry && HEX.test(entry.eventId) && entry.eventId > previous, 'PENDING_CURSOR_INVALID'); previous = entry.eventId;
              }
              requireThat(index.events.length ? index.nextAfter === previous : !index.hasMore && index.nextAfter === '', 'PENDING_CURSOR_INVALID');
              state.lastIndexFile = store.artifact('pending', index);
              state.page = {entries: index.events, offset: 0};
              state.cursor = index.nextAfter || state.cursor; state.endOfScan = !index.hasMore;
              state.index = {}; count.indexPages++; save('index-consumed'); continue;
            }
            // 没有discovery标记的旧目录仍使用原index合同及原任务，不静默迁移未决请求。
            const index = await command(state.index, 'index', state.cursor ? ['index', '1', state.cursor] : ['index', '1']);
            requireThat(index.schemaVersion === 1 && Array.isArray(index.events) && index.events.length <= 1 && typeof index.hasMore === 'boolean', 'INDEX_INVALID');
            if (index.events.length) requireThat(HEX.test(index.nextAfter) && index.nextAfter > state.cursor && index.events[0].eventId === index.nextAfter, 'INDEX_CURSOR_INVALID');
            else requireThat(!index.hasMore, 'INDEX_NO_PROGRESS');
            state.lastIndexFile = store.artifact('index', index);
            for (const event of index.events) {
              requireThat(HEX.test(event.eventId), 'INDEX_EVENT_ID_INVALID');
              if (typeof event.state !== 'object' || event.state === null) count.indexGaps++;
              else if (!['COMPLETE', 'PARTIAL'].includes(event.state.phase)) count.nonTerminalSkipped++;
              if (event.export?.archived === true) count.alreadyArchivedSkipped++;
              if (['COMPLETE', 'PARTIAL'].includes(event.state?.phase) && event.export?.archived !== true
                  && !state.events.some(e => e.eventId === event.eventId)) {
                requireThat(state.events.length < 128, 'QUEUE_ENTRY_LIMIT');
                state.events.push({eventId: event.eventId, category: event.category, captureState: event.state.capture,
                  phase: 'EXPORT', tasks: {}});
              }
            }
            state.cursor = index.nextAfter || state.cursor; state.endOfScan = !index.hasMore;
            state.index = {}; count.indexPages++; save('index-consumed');
          } catch (error) {
            if (!(error instanceof QueueError)) throw error;
            if (!error.retryable || error.code.startsWith('ROUND_')) throw error;
            state.failures = Math.min(16, (state.failures || 0) + 1);
            state.nextAt = clock.now() + Math.min(limits.maxBackoffMs, limits.pollMs * 2 ** state.failures);
            state.lastError = {code: error.code, at: clock.now(), eventId: active?.eventId || null};
            save('retry-backoff'); count.retries++;
            if (count.retries >= limits.maxRetries) { stop = 'ROUND_RETRY_BUDGET'; break; }
            await waitUntil(state.nextAt);
          }
        }
        if (count.events >= limits.maxEvents) stop = 'ROUND_EVENT_BUDGET';
      }
    } catch (error) {
      // 注入的崩溃和未知本机异常原样退出，不把未执行阶段伪装成业务失败。
      if (!(error instanceof QueueError)) throw error;
      stop = error.code;
      if (state && !error.retryable) {
        state.blocked = {code: error.code, at: clock.now(), eventId: active?.eventId || null};
        save('blocked');
      } else if (state && !error.code.startsWith('ROUND_')) {
        state.failures = Math.min(16, (state.failures || 0) + 1);
        state.nextAt = clock.now() + Math.min(limits.maxBackoffMs, limits.pollMs * 2 ** state.failures);
        state.lastError = {code: error.code, at: clock.now(), eventId: active?.eventId || null};
        save('retry-backoff');
      }
    }
    const summary = {schemaVersion: 1, stop, ...count,
      discovery: state?.discovery || 'index1', discoveryOnly,
      bufferedEntries: state?.page ? state.page.entries.length - state.page.offset : 0,
      pending: state ? state.events.filter(e => e.phase !== 'DONE').length : 0,
      archived: state ? state.events.filter(e => e.phase === 'DONE').length : 0,
      preservedWithGaps: state ? state.events.filter(e => e.phase === 'DONE' && (e.summary.gapCount > 0 || !e.summary.sourceLogComplete)).length : 0,
      blocked: !!state?.blocked, fullIncidentWindow: false, rootCauseEstablished: false, originalsDeleted: false};
    if (state) store.artifact('round', summary);
    return summary;
  });
}
