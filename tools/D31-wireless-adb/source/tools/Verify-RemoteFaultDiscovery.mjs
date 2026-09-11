import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {WebTransport} from './fault-transfer/WebTransport.mjs';
import {commandParams, validateTarget} from './fault-transfer/FaultTransferQueue.mjs';

const [sessionPath, capture, deviceName, expectedVersion, activeApk] = process.argv.slice(2);
const target = validateTarget({deviceName, expectedVersion, activeApk});
if (!sessionPath || !capture || fs.existsSync(capture)) throw Error('需要会话及全新目录');
const transport = new WebTransport({session: JSON.parse(fs.readFileSync(sessionPath, 'utf8').replace(/^\uFEFF/, ''))});
fs.mkdirSync(capture, {recursive: true});
const save = (name, value) => fs.writeFileSync(path.join(capture, name), JSON.stringify(value, null, 2), {flag: 'wx'});
const options = () => ({signal: AbortSignal.timeout(20000)});
const device = await transport.resolveTarget(target, options());
const results = [];
for (const [index, args] of [['discover'], ['pending', '16']].entries()) {
  const label = String(index + 1).padStart(2, '0');
  const request = {device_id: device.id, id: 'd31-discovery-' + randomUUID(), type: 'root_exec',
    params: commandParams(activeApk, args), expires_at: Date.now() + 180000};
  save(label + '-request-private.json', request);
  save(label + '-enqueue-private.json', await transport.enqueue(request, options()));
  let done = false;
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    const task = await transport.queryTask(device.id, request.id, options());
    if (task && ['success', 'failed', 'rejected', 'expired', 'cancelled'].includes(task.state)) {
      save(label + '-task-private.json', task);
      if (task.state !== 'success' || task.result?.exit_code !== 0 || task.result?.truncated)
        throw Error('原只读任务失败，保留结果，不重发');
      const result = JSON.parse(task.result.text);
      save(label + '-parsed-private.json', result); results.push(result); done = true; break;
    }
    await new Promise(resolve => setTimeout(resolve, 1500));
  }
  if (!done) throw Error('原任务尚未确认完成');
}
const [discovery, pending] = results;
if (discovery.kind !== 'FAULT_DISCOVERY_PROBE' || discovery.archiveModified !== false
    || pending.kind !== 'FAULT_PENDING_INDEX' || pending.verificationScope !== 'METADATA_ONLY'
    || Buffer.byteLength(JSON.stringify(pending)) > 8000) throw Error('发现/分页回执合同不符');
const sources = discovery.coverage.sources;
const nonempty = sources.filter(source => source.nonemptyEnumeration && source.enumerationComplete && source.accepted > 0);
if (!nonempty.length) throw Error('尚无真实非空目录完成枚举的证据');
const result = {passed: true, sourceDiscoveryOnly: true, archiveModified: false,
  sources: sources.map(({category, enumerated, matched, accepted, state, reason, enumerationComplete, elapsedMs, elapsedScope}) =>
    ({category, enumerated, matched, accepted, state, reason, enumerationComplete, elapsedMs, elapsedScope})),
  moreThan64Enumerated: nonempty.some(source => source.enumerated > 64),
  pendingCount: pending.events.length, pendingBytes: Buffer.byteLength(JSON.stringify(pending)), capacity: pending.capacity};
save('result.json', result); console.log(JSON.stringify(result));
