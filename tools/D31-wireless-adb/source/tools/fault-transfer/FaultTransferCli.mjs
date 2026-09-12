import fs from 'node:fs';
import {pathToFileURL} from 'node:url';
import {QueueStore, QueueError, requireThat} from './QueueStore.mjs';
import {WebTransport} from './WebTransport.mjs';
import {PackageVerifier} from './PackageVerifier.mjs';
import {runRound, validateTarget} from './FaultTransferQueue.mjs';

export async function main(argv) {
  const args = {};
  const allowed = ['state-dir', 'session', 'device-name', 'expected-version', 'active-apk', 'python',
    'max-events', 'max-bytes', 'max-ms', 'max-requests', 'max-index-pages', 'max-candidates', 'max-retries'];
  let discoveryOnly = false, resumeSession = false, archive = false, receiptsOnly = false;
  for (let i = 0; i < argv.length;) {
    if (argv[i] === '--discovery-only') { requireThat(!discoveryOnly, 'CLI_ARGUMENT_INVALID'); discoveryOnly = true; i++; continue; }
    if (argv[i] === '--resume-session') { requireThat(!resumeSession, 'CLI_ARGUMENT_INVALID'); resumeSession = true; i++; continue; }
    if (argv[i] === '--archive') { requireThat(!archive, 'CLI_ARGUMENT_INVALID'); archive = true; i++; continue; }
    if (argv[i] === '--receipts-only') { requireThat(!receiptsOnly, 'CLI_ARGUMENT_INVALID'); receiptsOnly = true; i++; continue; }
    const key = argv[i].replace(/^--/, '');
    requireThat(argv[i].startsWith('--') && allowed.includes(key) && !(key in args) && argv[i + 1] && !argv[i + 1].startsWith('--'), 'CLI_ARGUMENT_INVALID');
    args[key] = argv[i + 1];
    i += 2;
  }
  for (const key of ['state-dir', 'session', 'device-name', 'expected-version', 'active-apk']) requireThat(args[key], 'CLI_REQUIRED_ARGUMENT');
  const target = validateTarget({deviceName: args['device-name'], expectedVersion: args['expected-version'], activeApk: args['active-apk']});
  requireThat(fs.statSync(args.session).size <= 1048576, 'SESSION_FILE_TOO_LARGE');
  const session = JSON.parse(fs.readFileSync(args.session, 'utf8').replace(/^\uFEFF/, ''));
  const limits = {};
  for (const key of ['max-events', 'max-bytes', 'max-ms', 'max-requests', 'max-index-pages', 'max-candidates', 'max-retries'])
    if (args[key] !== undefined) limits[key.replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = Number(args[key]);
  const python = args.python || 'python';
  const result = await runRound({store: new QueueStore(args['state-dir'], {python}), transport: new WebTransport({session}),
    verifier: new PackageVerifier({python}), target, limits, discoveryOnly, resumeSession, archive, receiptsOnly});
  console.log(JSON.stringify(result));
  return result.blocked ? 1 : result.stop === 'ROUND_COMPLETE' || result.stop === 'ROUND_EVENT_BUDGET' ? 0 : 2;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).then(code => { process.exitCode = code; }).catch(error => {
    // 不打印会话、响应正文或异常堆栈，私有结果仅留在显式指定目录。
    console.error(JSON.stringify({state: 'STOPPED', code: error instanceof QueueError ? error.code : 'LOCAL_EXECUTION_FAILED'}));
    process.exitCode = 1;
  });
}
