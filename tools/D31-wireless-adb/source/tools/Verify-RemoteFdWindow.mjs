// D31只读FD窗口：-P 5042，显式完整TCP序列号，最多5轮，每轮起点间隔60秒。
// 用法：node Verify-RemoteFdWindow.mjs --serial <完整序列号> --expected-sha256 <摘要>
//       --expected-version <版本号> --new-capture <全新私有目录> [--rounds 1..5] [--adb <路径>]
// 离线验证：node Verify-RemoteFdWindow.mjs --self-test --new-capture <全新本机测试目录>
// 单次adb shell短命令，无stdin脚本传输；只读取已存在的文件和/proc。
// 不调用app_process、HTTP、安装、设置或重启命令。
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {createHash, randomBytes} from 'node:crypto';
import {execFile, execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';

const SELF = fileURLToPath(import.meta.url);
const BB = '/system/bin/busybox';
const ROOT = '/data/local/d31-remote';
const CORE = ROOT + '/runtime/state';
const TOTAL_MS = 360000, ADB_MS = 8000, INTERVAL_MS = 60000, OUTPUT_LIMIT = 4 * 1048576;
const realClock = {now: () => Date.now(), monotonic: () => performance.now(), sleep: ms => new Promise(resolve => setTimeout(resolve, ms))};
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
class ObservationError extends Error { constructor(code) { super(code); this.code = code; } }
const requireThat = (condition, code) => { if (!condition) throw new ObservationError(code); };
const quote = value => "'" + value.replaceAll("'", "'\\''") + "'";

function newDirectory(value) {
  requireThat(typeof value === 'string' && value.length > 0, 'NEW_CAPTURE_REQUIRED');
  const directory = path.resolve(value);
  requireThat(!fs.existsSync(directory), 'CAPTURE_ALREADY_EXISTS');
  fs.mkdirSync(path.dirname(directory), {recursive: true});
  fs.mkdirSync(directory, {mode: 0o700}); return directory;
}
function save(directory, name, value) {
  const bytes = Buffer.isBuffer(value) ? value : Buffer.from(typeof value === 'string' ? value : JSON.stringify(value, null, 2) + '\n');
  const fd = fs.openSync(path.join(directory, name), 'wx', 0o600);
  try { fs.writeFileSync(fd, bytes); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
}
function inventory(directory) {
  return fs.readdirSync(directory).sort().map(name => {
    const bytes = fs.readFileSync(path.join(directory, name)); return {path: name, bytes: bytes.length, sha256: sha(bytes)};
  });
}
function validateOptions(options) {
  const parts = /^(\d{1,3}(?:\.\d{1,3}){3}):(\d{1,5})$/.exec(options.serial || '');
  requireThat(parts && parts[1].split('.').every(v => Number(v) <= 255) && Number(parts[2]) >= 1 && Number(parts[2]) <= 65535, 'FULL_D31_TCP_SERIAL_REQUIRED');
  requireThat(/^[a-f0-9]{64}$/.test(options.expectedSha256 || ''), 'EXPECTED_SHA256_REQUIRED');
  requireThat(Number.isSafeInteger(options.expectedVersion) && options.expectedVersion > 0, 'EXPECTED_VERSION_REQUIRED');
  requireThat(Number.isSafeInteger(options.rounds) && options.rounds >= 1 && options.rounds <= 5, 'ROUND_LIMIT');
}

// 所有命令均为固定只读片段；只把已验证的PID和APK路径加入命令。
export function sectionsFor(anchor = null) {
  const sections = [
    ['DEVICE', 'getprop ro.product.device'], ['MODEL', 'getprop ro.product.model'], ['SDK', 'getprop ro.build.version.sdk'],
    ['UID', 'id -u'], ['EPOCH', 'date +%s'], ['UPTIME', `${BB} cat /proc/uptime`],
    ['BOOT', `${BB} cat /proc/sys/kernel/random/boot_id`], ['CORE_PIDS', `${BB} pidof d31-elfremote`],
    ['ACTIVE', `${BB} cat ${ROOT}/runtime/active.json`], ['HEALTH', `${BB} cat ${CORE}/health.json`],
    ['PID', `${BB} cat ${CORE}/remote.pid`],
  ];
  if (anchor) {
    requireThat(/^[1-9][0-9]{0,9}$/.test(anchor.pid), 'CORE_PID_INVALID');
    requireThat(/^\/data\/local\/d31-remote\/releases\/[a-f0-9]{64}\/remote\.apk$/.test(anchor.apkPath)
      || anchor.apkPath === '/system/priv-app/D31ElfRemote/D31ElfRemote.apk', 'ACTIVE_PATH_INVALID');
  }
  const pid = anchor ? anchor.pid : '${fdw_pid}';
  sections.push(['PROC_STAT', `${BB} cat /proc/${pid}/stat`]);
  if (anchor) sections.push(
    ['PROC_STATUS', `${BB} cat /proc/${pid}/status`], ['LIMITS', `${BB} cat /proc/${pid}/limits`],
    ['FD_NAMES', `${BB} ls --color=never -1 /proc/${pid}/fd`], ['FD_LINKS', `${BB} ls --color=never -ln /proc/${pid}/fd`],
    ['SCAN', `${BB} cat ${ROOT}/faults/scan.json`], ['STATUS', `${BB} cat ${CORE}/status.json`],
    ['MAPS', `${BB} head -c 2097153 /proc/${pid}/maps`], ['APK_SHA', `${BB} sha256sum ${quote(anchor.apkPath)}`],
  );
  sections.push(['HEALTH_AFTER', `${BB} cat ${CORE}/health.json`], ['ACTIVE_AFTER', `${BB} cat ${ROOT}/runtime/active.json`],
    ['PID_AFTER', `${BB} cat ${CORE}/remote.pid`], ['PROC_STAT_AFTER', `${BB} cat /proc/${pid}/stat`],
    ['CORE_PIDS_AFTER', `${BB} pidof d31-elfremote`], ['BOOT_AFTER', `${BB} cat /proc/sys/kernel/random/boot_id`],
    ['UPTIME_AFTER', `${BB} cat /proc/uptime`], ['EPOCH_AFTER', 'date +%s']);
  return sections;
}
export function shellFor(sections, nonce, anchor = null) {
  requireThat(/^[a-f0-9]{32}$/.test(nonce), 'NONCE_INVALID');
  let script = `n=${nonce};f=0;s(){ printf '\\nFDW_%s_BEGIN_%s\\n' "$n" "$1";(eval "$2") 2>&1;r=$?;printf '\\nFDW_%s_END_%s_%s\\n' "$n" "$1" "$r";[ "$r" -eq 0 ]||f=1;};\n`;
  if (!anchor) script += `fdw_pid=$(${BB} cat ${CORE}/remote.pid)\ncase "$fdw_pid" in ''|*[!0-9]*) fdw_pid=0 ;; esac\n`;
  for (const [name, command] of sections) {
    requireThat(/^[A-Z_]+$/.test(name), 'SECTION_NAME_INVALID');
    script += `s ${name} ${quote(command)}\n`;
  }
  script += `printf '\\nFDW_%s_EXIT_%s\\n' "$n" "$f";exit "$f"\n`;
  requireThat(Buffer.byteLength(script, 'utf8') < 3500, 'SHELL_COMMAND_LENGTH_LIMIT');
  return script;
}
export function parseFrame(stdout, nonce, names) {
  // 与既有只读宿主一致，派生解析去除旧ADB的CR；原始字节另存。
  // 不删除命令回显或交错文本，仍严格验证分节顺序和nonce。
  const text = stdout.toString('latin1').replaceAll('\r', '');
  const sections = {}; let offset = 0;
  for (const name of names) {
    const begin = `\nFDW_${nonce}_BEGIN_${name}\n`, at = text.indexOf(begin, offset);
    requireThat(at >= offset && !text.slice(offset, at).trim(), 'FRAME_ORDER_INVALID');
    const contentAt = at + begin.length;
    const end = new RegExp(`\\nFDW_${nonce}_END_${name}_([0-9]{1,3})\\n`, 'g'); end.lastIndex = contentAt;
    const match = end.exec(text); requireThat(match && Number(match[1]) <= 255, 'FRAME_END_MISSING');
    sections[name] = {bytes: Buffer.from(text.slice(contentAt, match.index), 'latin1'), exit: Number(match[1])};
    offset = end.lastIndex;
  }
  const ending = new RegExp(`^\\s*FDW_${nonce}_EXIT_([01])\\s*$`).exec(text.slice(offset));
  requireThat(ending, 'NONCE_EXIT_MISSING');
  const expected = Object.values(sections).some(s => s.exit !== 0) ? 1 : 0;
  requireThat(Number(ending[1]) === expected, 'NONCE_EXIT_INCONSISTENT');
  return {sections, remoteExit: expected};
}
const sectionText = (frame, name) => {
  const section = frame.sections[name]; requireThat(section?.exit === 0, name + '_READ_FAILED');
  return section.bytes.toString('utf8').trim();
};
function sectionJson(frame, name) {
  const text = sectionText(frame, name); requireThat(Buffer.byteLength(text) <= 65536, name + '_SIZE_LIMIT');
  try { return JSON.parse(text); } catch { throw new ObservationError(name + '_JSON_INVALID'); }
}
export function parseProcStat(text) {
  const end = text.lastIndexOf(')');
  const start = /^([1-9][0-9]*) \(/.exec(text);
  requireThat(start && end >= start[0].length, 'PROC_STAT_INVALID');
  const fields = text.slice(end + 1).trim().split(/\s+/);
  // /proc/stat字段3从state开始；starttime是字段22，不按进程名中的空格拆列。
  requireThat(fields.length >= 20 && /^[A-Za-z]$/.test(fields[0]) && /^[0-9]+$/.test(fields[19]), 'PROC_STARTTIME_INVALID');
  return {pid: start[1], starttime: fields[19], processState: fields[0]};
}
function matchingMaps(text, apkPath) {
  const encoded = apkPath.slice(1).replaceAll('/', '@') + '@classes.dex';
  return text.split('\n').some(line => {
    const match = /^\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+(.+)$/.exec(line.trim());
    if (!match) return false;
    return match[1] === apkPath || match[1].startsWith('/data/dalvik-cache/')
      && /^[A-Za-z0-9_]+\//.test(match[1].slice(19)) && match[1].slice(19).split('/').slice(1).join('/') === encoded;
  });
}
function identity(frame, options, anchor = null) {
  requireThat(sectionText(frame, 'DEVICE') === 'hct6735_66_m0' && sectionText(frame, 'MODEL') === 'hct6737t_66_m0'
    && sectionText(frame, 'SDK') === '23' && sectionText(frame, 'UID') === '0', 'D31_PLATFORM_MISMATCH');
  const pid = sectionText(frame, 'PID'), afterPid = sectionText(frame, 'PID_AFTER');
  requireThat(/^[1-9][0-9]{0,9}$/.test(pid) && afterPid === pid, 'CORE_PID_CHANGED');
  requireThat(sectionText(frame, 'CORE_PIDS') === pid && sectionText(frame, 'CORE_PIDS_AFTER') === pid, 'CORE_NOT_UNIQUE');
  const before = parseProcStat(sectionText(frame, 'PROC_STAT')), after = parseProcStat(sectionText(frame, 'PROC_STAT_AFTER'));
  requireThat(before.pid === pid && after.pid === pid && before.starttime === after.starttime, 'CORE_STARTTIME_CHANGED');
  const boot = sectionText(frame, 'BOOT');
  requireThat(/^[a-f0-9-]{36}$/.test(boot) && sectionText(frame, 'BOOT_AFTER') === boot, 'BOOT_ID_CHANGED');
  const active = sectionJson(frame, 'ACTIVE'), activeAfter = sectionJson(frame, 'ACTIVE_AFTER');
  const health = sectionJson(frame, 'HEALTH'), healthAfter = sectionJson(frame, 'HEALTH_AFTER');
  for (const a of [active, activeAfter]) requireThat(a.sha256 === options.expectedSha256 && a.versionCode === options.expectedVersion
    && a.path === active.path, 'ACTIVE_MISMATCH');
  requireThat(active.path === `${ROOT}/releases/${options.expectedSha256}/remote.apk`
    || active.path === '/system/priv-app/D31ElfRemote/D31ElfRemote.apk', 'ACTIVE_PATH_INVALID');
  for (const h of [health, healthAfter]) requireThat(h.apk_sha256 === options.expectedSha256 && h.version_code === options.expectedVersion
    && String(h.pid) === pid && h.uid === 0 && Number.isSafeInteger(h.time_ms), 'HEALTH_IDENTITY_MISMATCH');
  requireThat(typeof health.instance === 'string' && health.instance.length > 0 && healthAfter.instance === health.instance, 'CORE_INSTANCE_CHANGED');
  const result = {pid, starttime: before.starttime, boot, instance: health.instance, apkPath: active.path};
  if (anchor) requireThat(Object.keys(result).every(key => result[key] === anchor[key]), 'BOUND_CORE_CHANGED');
  return result;
}

export function runAdb(executable, args, {timeoutMs}) {
  return new Promise(resolve => {
    const child = execFile(executable, args, {timeout: timeoutMs, killSignal: 'SIGKILL', maxBuffer: OUTPUT_LIMIT, encoding: 'buffer', windowsHide: true},
      (error, stdout, stderr) => resolve({stdout: stdout || Buffer.alloc(0), stderr: stderr || Buffer.alloc(0),
        hostExit: error ? (typeof error.code === 'number' ? error.code : null) : 0,
        error: error ? {code: error.code ?? null, errno: error.errno ?? null, syscall: error.syscall ?? null,
          signal: error.signal ?? null, killed: !!error.killed, message: error.message} : null}));
    child.stdin.end();
  });
}
function fdListingText(frame, name) {
  // BusyBox给编号及链接目标加SGR颜色；仅在FD派生解析中消除显示码。
  const text = sectionText(frame, name).replace(/\x1b\[(?:0|0;0|1;36|1;35|1;31|0;35)m/g, '');
  requireThat(!/[\x00-\x08\x0b-\x1f\x7f]/.test(text), name + '_CONTROL_INVALID');
  return text;
}
function observations(frame, previous) {
  const value = {fdCount: null, bootIdFdCount: null, fdListsAgree: false, fdComplete: false,
    scanCapturedAtMs: null, scanAdvanced: null, scanComparison: 'NOT_CHECKED', errors: []};
  const read = (label, action) => { try { return action(); } catch (error) { value.errors.push(error.code || label + '_INVALID'); return null; } };
  const names = read('FD_NAMES', () => {
    const text = fdListingText(frame, 'FD_NAMES'); const ids = text ? text.split(/\s+/) : [];
    requireThat(ids.length > 0 && ids.length <= 65536 && ids.every(id => /^[0-9]+$/.test(id)) && new Set(ids).size === ids.length, 'FD_NAMES_INVALID');
    value.fdCount = ids.length; return ids;
  });
  const links = read('FD_LINKS', () => {
    const lines = fdListingText(frame, 'FD_LINKS').split('\n').filter(line => line.trim() && !/^total\s+[0-9]+$/.test(line.trim()));
    const entries = lines.map(line => /\s([0-9]+) -> (.*)$/.exec(line));
    requireThat(entries.length > 0 && entries.length <= 65536 && entries.every(Boolean)
      && new Set(entries.map(e => e[1])).size === entries.length, 'FD_LINKS_INVALID');
    value.bootIdFdCount = entries.filter(e => e[2] === '/proc/sys/kernel/random/boot_id').length; return entries;
  });
  if (names && links) {
    const set = new Set(names); value.fdListsAgree = names.length === links.length && links.every(e => set.has(e[1]));
    value.fdComplete = value.fdListsAgree;
    if (!value.fdListsAgree) value.errors.push('FD_LISTS_CHANGED_DURING_READ');
  }
  value.openFileLimit = read('LIMITS', () => {
    const match = /^Max open files\s+(\d+|unlimited)\s+(\d+|unlimited)\s+/m.exec(sectionText(frame, 'LIMITS'));
    requireThat(match, 'FD_LIMIT_MISSING'); return {soft: match[1], hard: match[2]};
  });
  read('SCAN', () => {
    const scan = sectionJson(frame, 'SCAN'); requireThat(Number.isSafeInteger(scan.capturedAtMs) && scan.capturedAtMs >= 0, 'SCAN_TIMESTAMP_MISSING');
    value.scanCapturedAtMs = scan.capturedAtMs;
    if (previous?.scanCapturedAtMs != null) {
      value.scanAdvanced = scan.capturedAtMs > previous.scanCapturedAtMs;
      value.scanComparison = value.scanAdvanced ? 'ADVANCED' : scan.capturedAtMs === previous.scanCapturedAtMs ? 'UNCHANGED' : 'REGRESSED';
    } else value.scanComparison = 'BASELINE';
  });
  value.uptimeSeconds = read('UPTIME', () => {
    const text = sectionText(frame, 'UPTIME_AFTER'); requireThat(/^\d+(?:\.\d+)?\s+\d+(?:\.\d+)?$/.test(text), 'UPTIME_INVALID');
    return Number(text.split(/\s+/)[0]);
  });
  read('HEALTH', () => {
    const h = sectionJson(frame, 'HEALTH_AFTER'), seconds = sectionText(frame, 'EPOCH_AFTER');
    requireThat(/^[0-9]+$/.test(seconds), 'DEVICE_TIME_INVALID');
    // date只有秒精度，保留年龄区间；边界不确定时不宣称新鲜。
    value.healthAgeMinMs = Number(seconds) * 1000 - h.time_ms;
    value.healthAgeMaxMs = value.healthAgeMinMs + 999;
    value.healthFresh = value.healthAgeMaxMs >= 0 && value.healthAgeMaxMs < 20000;
    value.localReady = h.local_ready === true; value.reportAcknowledged = h.report_acknowledged === true;
  });
  read('STATUS', () => {
    const status = sectionJson(frame, 'STATUS');
    value.statusPhase = typeof status.phase === 'string' && /^[a-z_]{1,40}$/.test(status.phase) ? status.phase : 'UNRECOGNIZED';
    value.statusHttp = Number.isInteger(status.http_status) ? status.http_status : null;
  });
  return value;
}

// 供只读原件回放复用实时观察的全部身份、映射和摘要门；不进行IO。
export function analyzeFrame(frame, options, anchor = null, previous = null) {
  const bound = identity(frame, options, anchor);
  if (!anchor) return {anchor: bound, sample: null};
  const maps = sectionText(frame, 'MAPS');
  requireThat(frame.sections.MAPS.bytes.length <= 2097152 && matchingMaps(maps, bound.apkPath), 'CORE_MAPS_MISMATCH');
  requireThat(sectionText(frame, 'APK_SHA') === options.expectedSha256 + '  ' + bound.apkPath, 'ACTIVE_FILE_HASH_MISMATCH');
  return {anchor: bound, sample: {identityBound: true, ...observations(frame, previous)}};
}

export async function observe(options, {execute = runAdb, clock = realClock} = {}) {
  options = {adb: 'C:/Dev/android-sdk/platform-tools/adb.exe', rounds: 5, ...options}; validateOptions(options);
  const directory = newDirectory(options.newCapture), start = clock.monotonic();
  save(directory, 'host.mjs', fs.readFileSync(SELF));
  save(directory, 'target-private.json', {...options, adbPort: 5042, startedAt: new Date(clock.now()).toISOString(),
    totalBudgetMs: TOTAL_MS, adbBudgetMs: ADB_MS, intervalMs: INTERVAL_MS});
  let sequence = 0, anchor = null, stop = 'WINDOW_COMPLETE'; const samples = [];
  const remaining = () => TOTAL_MS - (clock.monotonic() - start);
  const capture = async (roundIndex, bound) => {
    requireThat(remaining() > 0, 'WINDOW_TIME_BUDGET');
    const label = String(sequence++).padStart(2, '0'), nonce = randomBytes(16).toString('hex');
    const sections = sectionsFor(bound), command = shellFor(sections, nonce, bound);
    const timeoutMs = Math.max(1, Math.min(ADB_MS, Math.floor(remaining())));
    const args = ['-P', '5042', '-s', options.serial, 'shell', command];
    const commandBytes = Buffer.from(command, 'utf8');
    const before = clock.monotonic();
    const commandFile = label + '-command-private.bin';
    save(directory, commandFile, commandBytes);
    save(directory, label + '-request-private.json', {roundIndex, nonce, args, timeoutMs,
      commandFile, commandBytes: commandBytes.length, commandSha256: sha(commandBytes), transport: 'SHELL_COMPACT_ARGUMENT',
      wallTime: new Date(clock.now()).toISOString(), elapsedMs: before - start});
    let output;
    try { output = await execute(options.adb, args, {timeoutMs, nonce, sections, roundIndex, anchor: bound}); }
    catch (error) { output = {stdout: error.stdout || Buffer.alloc(0), stderr: error.stderr || Buffer.alloc(0), hostExit: null,
      error: {code: error.code ?? null, errno: error.errno ?? null, message: error.message}}; }
    output.stdout = Buffer.from(output.stdout); output.stderr = Buffer.from(output.stderr);
    save(directory, label + '-stdout-private.bin', output.stdout); save(directory, label + '-stderr-private.bin', output.stderr);
    const receipt = {roundIndex, nonce, hostExit: output.hostExit, elapsedMs: clock.monotonic() - before,
      hostError: output.error, hostErrno: output.error?.errno ?? null,
      nonceValid: false, remoteExit: null,
      remoteErrno: null, remoteErrnoScope: 'SHELL_ERROR_TEXT_AND_SECTION_EXIT_ONLY',
      remoteCompletionConfirmed: false, sectionExits: {}};
    let frame;
    try {
      frame = parseFrame(output.stdout, nonce, sections.map(s => s[0]));
      receipt.nonceValid = true; receipt.remoteExit = frame.remoteExit;
      receipt.remoteCompletionConfirmed = true;
      for (const [name, section] of Object.entries(frame.sections)) {
        receipt.sectionExits[name] = section.exit;
        save(directory, label + '-' + name.toLowerCase() + '-private.bin', section.bytes);
      }
    } catch (error) { receipt.parseError = error.code || 'FRAME_INVALID'; }
    save(directory, label + '-result-private.json', receipt);
    const ordinaryRemoteFailure = frame && output.hostExit === frame.remoteExit && output.hostExit === 1
      && output.error?.code === 1 && !output.error?.killed && !output.error?.signal;
    requireThat((!output.error && output.hostExit === 0) || ordinaryRemoteFailure,
      output.error?.killed ? 'ADB_TIMEOUT' : 'ADB_FAILED');
    requireThat(frame, receipt.parseError || 'FRAME_INVALID');
    requireThat(remaining() >= 0, 'WINDOW_TIME_BUDGET');
    return frame;
  };
  try {
    anchor = analyzeFrame(await capture(-1, null), options).anchor; save(directory, 'bound-core-private.json', anchor);
    const firstRound = clock.monotonic();
    for (let round = 0; round < options.rounds; round++) {
      const wait = Math.max(0, firstRound + round * INTERVAL_MS - clock.monotonic());
      requireThat(wait + 1 < remaining(), 'WINDOW_TIME_BUDGET');
      if (wait) await clock.sleep(wait);
      const frame = await capture(round, anchor);
      const sample = {round: round + 1, elapsedMs: clock.monotonic() - start,
        ...analyzeFrame(frame, options, anchor, samples.at(-1)).sample};
      samples.push(sample); save(directory, `sample-${round + 1}.json`, sample);
    }
  } catch (error) {
    stop = error instanceof ObservationError ? error.code : 'HOST_OBSERVATION_FAILED';
    save(directory, 'failure-private.json', {code: stop, errno: error.errno ?? null,
      message: error.message, stack: error.stack, elapsedMs: clock.monotonic() - start});
  }
  const counts = samples.filter(s => s.fdComplete).map(s => s.fdCount);
  const bootCounts = samples.filter(s => s.fdComplete).map(s => s.bootIdFdCount);
  const summary = {schemaVersion: 1, stop, expectedVersion: options.expectedVersion, roundsRequested: options.rounds,
    roundsObserved: samples.length, elapsedMs: clock.monotonic() - start, adbCalls: sequence,
    fdSamplesComplete: samples.length === options.rounds && counts.length === samples.length,
    fdMin: counts.length ? Math.min(...counts) : null, fdMax: counts.length ? Math.max(...counts) : null,
    fdDelta: counts.length >= 2 ? counts.at(-1) - counts[0] : null,
    bootIdFdDelta: bootCounts.length >= 2 ? bootCounts.at(-1) - bootCounts[0] : null,
    scanAdvances: samples.filter(s => s.scanAdvanced === true).length,
    observationErrors: samples.reduce((n, s) => n + s.errors.length, 0), samples,
    scope: 'SAME_CORE_BOUNDED_READ_ONLY_WINDOW', leakFixProven: false, businessHealthProven: false,
    deviceChanged: false, adbServerRestarted: false};
  save(directory, 'summary.json', summary); save(directory, 'artifacts-sha256.json', inventory(directory));
  return summary;
}

function parseArgs(argv) {
  const values = {}; let selfTest = false;
  for (let i = 0; i < argv.length;) {
    if (argv[i] === '--self-test') { requireThat(!selfTest, 'ARGUMENT_DUPLICATE'); selfTest = true; i++; continue; }
    const key = argv[i].replace(/^--/, '');
    requireThat(argv[i].startsWith('--') && ['serial', 'expected-sha256', 'expected-version', 'new-capture', 'rounds', 'adb'].includes(key)
      && !(key in values) && argv[i + 1] && !argv[i + 1].startsWith('--'), 'ARGUMENT_INVALID');
    values[key] = argv[i + 1]; i += 2;
  }
  requireThat(values['new-capture'], 'NEW_CAPTURE_REQUIRED');
  if (selfTest) requireThat(Object.keys(values).length === 1, 'SELF_TEST_ARGUMENTS');
  return {selfTest, options: {serial: values.serial, expectedSha256: values['expected-sha256'], expectedVersion: Number(values['expected-version']),
    newCapture: values['new-capture'], rounds: values.rounds === undefined ? 5 : Number(values.rounds),
    ...(values.adb ? {adb: values.adb} : {})}};
}

async function selfTest(newCapture) {
  const directory = newDirectory(newCapture); const results = [];
  const expectedSha256 = 'c'.repeat(64), apk = `${ROOT}/releases/${expectedSha256}/remote.apk`;
  const base = {serial: '192.0.2.1:5555', expectedSha256, expectedVersion: 118, rounds: 5};
  function fixture(change = () => {}) {
    let tick = 0; const calls = [], sleeps = [];
    const clock = {now: () => 1800000000000 + tick, monotonic: () => tick, sleep: async ms => { sleeps.push(ms); tick += ms; }};
    const execute = async (executable, args, request) => {
      calls.push({args, ...request}); tick += 10;
      const health = {version_code: 118, apk_sha256: expectedSha256, pid: 321, uid: 0, time_ms: 1800000000000 + Math.floor(tick / 1000) * 1000,
        instance: 'synthetic-instance', local_ready: true, report_acknowledged: true};
      const stat = '321 (name with ) spaces) S ' + [...Array(18).fill('0'), '9876', ...Array(8).fill('0')].join(' ');
      const values = {DEVICE: 'hct6735_66_m0', MODEL: 'hct6737t_66_m0', SDK: '23', UID: '0', EPOCH: String(1800000000 + Math.floor(tick / 1000)),
        UPTIME: `${100 + tick / 1000} 50.00`, BOOT: '00000000-0000-0000-0000-000000000001', CORE_PIDS: '321',
        ACTIVE: JSON.stringify({sha256: expectedSha256, versionCode: 118, path: apk}), HEALTH: JSON.stringify(health), PID: '321', PROC_STAT: stat,
        PROC_STATUS: 'Name:\td31-elfremote\nFDSize:\t64', LIMITS: 'Max open files            1024                 1024                 files',
        FD_NAMES: '0\n1\n2', FD_LINKS: 'lr-x------ 1 0 0 64 Sep 12 00:00 0 -> /dev/null\nlr-x------ 1 0 0 64 Sep 12 00:00 1 -> /proc/sys/kernel/random/boot_id\nlr-x------ 1 0 0 64 Sep 12 00:00 2 -> socket:[123]',
        SCAN: JSON.stringify({capturedAtMs: 1800000000000 + Math.max(0, request.roundIndex) * 60000}), STATUS: JSON.stringify({phase: 'report_sent', http_status: 200}),
        MAPS: `0000-1000 r--p 0000 00:00 1 /data/dalvik-cache/arm64/${apk.slice(1).replaceAll('/', '@')}@classes.dex`, APK_SHA: expectedSha256 + '  ' + apk};
      for (const key of ['EPOCH', 'UPTIME', 'BOOT', 'CORE_PIDS', 'ACTIVE', 'HEALTH', 'PID', 'PROC_STAT']) values[key + '_AFTER'] = values[key];
      const exits = {}; change({values, exits, request, advance: ms => { tick += ms; }});
      let stdout = '';
      for (const [name] of request.sections) stdout += `\nFDW_${request.nonce}_BEGIN_${name}\n${values[name]}\n\nFDW_${request.nonce}_END_${name}_${exits[name] || 0}\n`;
      stdout += `\nFDW_${request.nonce}_EXIT_${Object.values(exits).some(Boolean) ? 1 : 0}\n`;
      return {stdout: Buffer.from(stdout), stderr: Buffer.alloc(0), hostExit: 0, error: null};
    };
    return {clock, execute, calls, sleeps};
  }
  async function check(name, action) {
    try { await action(); results.push({name, passed: true}); }
    catch (error) { results.push({name, passed: false, error: error.code || error.message}); }
  }
  await check('五轮同核心、60秒间隔、总时限和严格5042序列号', async () => {
    const f = fixture(), result = await observe({...base, newCapture: path.join(directory, 'window')}, f);
    assert.equal(result.stop, 'WINDOW_COMPLETE'); assert.equal(result.roundsObserved, 5); assert.equal(result.fdDelta, 0);
    assert.equal(result.samples[0].bootIdFdCount, 1); assert.equal(result.scanAdvances, 4); assert.equal(result.leakFixProven, false);
    assert.equal(f.calls.length, 6); assert.ok(f.calls.every(c => c.timeoutMs <= 8000 && JSON.stringify(c.args.slice(0, 4)) === JSON.stringify(['-P', '5042', '-s', base.serial])));
    assert.equal(f.sleeps.length, 4); assert.ok(result.elapsedMs <= TOTAL_MS);
    for (let i = 1; i < result.samples.length; i++) assert.equal(result.samples[i].elapsedMs - result.samples[i - 1].elapsedMs, 60000);
    const summary = fs.readFileSync(path.join(directory, 'window/summary.json'), 'utf8'); assert.ok(!summary.includes(base.serial) && !summary.includes(expectedSha256));
    const sh = 'C:/Program Files/Git/bin/sh.exe';
    if (fs.existsSync(sh)) for (const c of [f.calls[0], f.calls[1]]) execFileSync(sh, ['-n', '-c', c.args[5]], {timeout: 4000, windowsHide: true});
  });
  await check('短shell参数、最坏轮次小于3500字节且写前归档', async () => {
    const nonce = 'a'.repeat(32);
    for (const anchor of [null, {pid: '9999999999', apkPath: apk},
      {pid: '9999999999', apkPath: '/system/priv-app/D31ElfRemote/D31ElfRemote.apk'}]) {
      const sections = sectionsFor(anchor), command = shellFor(sections, nonce, anchor);
      assert.ok(Buffer.byteLength(command, 'utf8') < 3500);
      assert.equal(command.split(nonce).length - 1, 1);
      for (const [name, part] of sections) assert.ok(command.includes(`s ${name} ${quote(part)}\n`));
    }
    assert.throws(() => shellFor([['LARGE', 'x'.repeat(3500)]], nonce, {}), /SHELL_COMMAND_LENGTH_LIMIT/);
    const f = fixture(), original = f.execute;
    f.execute = async (executable, args, request) => {
      assert.equal(args[4], 'shell'); assert.equal(args.length, 6); assert.equal(request.stdin, undefined);
      assert.ok(Buffer.byteLength(args[5], 'utf8') < 3500);
      assert.equal(args[5], shellFor(request.sections, request.nonce, request.anchor));
      const label = String(f.calls.length).padStart(2, '0');
      const dir = path.join(directory, 'short-command');
      assert.equal(fs.readFileSync(path.join(dir, label + '-command-private.bin'), 'utf8'), args[5]);
      const receipt = JSON.parse(fs.readFileSync(path.join(dir, label + '-request-private.json')));
      assert.equal(receipt.commandSha256, sha(args[5]));
      assert.equal(receipt.transport, 'SHELL_COMPACT_ARGUMENT');
      return original(executable, args, request);
    };
    const r = await observe({...base, rounds: 1, newCapture: path.join(directory, 'short-command')}, f);
    assert.equal(r.stop, 'WINDOW_COMPLETE'); assert.equal(r.roundsObserved, 1);
  });
  await check('回显及交错污染严格拒绝，原件保全且不自动重试', async () => {
    for (const kind of ['echo', 'interleaved']) {
      const f = fixture(), original = f.execute; let raw;
      f.execute = async (...args) => {
        const output = await original(...args);
        const echo = args[1][5].replaceAll('\n', '\r\r\n');
        const text = output.stdout.toString();
        raw = Buffer.from(kind === 'echo' ? echo + text
          : text.replace(`\nFDW_${args[2].nonce}_BEGIN_MODEL\n`, echo + `\nFDW_${args[2].nonce}_BEGIN_MODEL\n`));
        return {...output, stdout: raw};
      };
      const dir = path.join(directory, 'pty-' + kind);
      const r = await observe({...base, newCapture: dir}, f);
      assert.equal(r.stop, 'FRAME_ORDER_INVALID'); assert.equal(r.adbCalls, 1);
      assert.equal(r.roundsObserved, 0);
      assert.deepEqual(fs.readFileSync(path.join(dir, '00-stdout-private.bin')), raw);
      assert.equal(f.calls[0].args[4], 'shell');
    }
  });
  await check('本机shell实际执行分节函数、引号和失败退出码', async () => {
    const nonce = 'b'.repeat(32), anchor = {pid: '9999999999', apkPath: apk};
    const sections = sectionsFor(anchor).map(([name], i) => [name,
      i === 1 ? "printf '%s' 'synthetic failure' >&2;exit 7" : `printf '%s' ${quote("synthetic ' " + name)}`]);
    const command = shellFor(sections, nonce, anchor);
    const r = await runAdb('C:/Program Files/Git/bin/sh.exe', ['-c', command], {timeoutMs: 4000});
    assert.equal(r.hostExit, 1); assert.equal(r.error.code, 1);
    for (const stdout of [r.stdout, Buffer.from(r.stdout.toString().replaceAll('\n', '\r\n')),
      Buffer.from(r.stdout.toString().replaceAll('\n', '\r\r\n'))]) {
      const frame = parseFrame(stdout, nonce, sections.map(s => s[0]));
      assert.equal(frame.remoteExit, 1); assert.equal(frame.sections.MODEL.exit, 7);
      assert.equal(frame.sections.MODEL.bytes.toString(), 'synthetic failure');
      assert.equal(frame.sections.DEVICE.bytes.toString(), "synthetic ' DEVICE");
      assert.equal(Object.keys(frame.sections).length, sections.length);
    }
  });
  await check('同PID被复用时starttime变化停止，不跟随新核心', async () => {
    const f = fixture(({values, request}) => { if (request.roundIndex === 1) values.PROC_STAT_AFTER = values.PROC_STAT_AFTER.replace('9876', '9877'); });
    const r = await observe({...base, newCapture: path.join(directory, 'pid-reuse')}, f);
    assert.equal(r.stop, 'CORE_STARTTIME_CHANGED'); assert.equal(r.roundsObserved, 1); assert.equal(f.calls.length, 3);
  });
  await check('BusyBox彩色FD编号及目标正常计数，不掩盖未知控制码或重复项', async () => {
    for (const kind of ['colors', 'control', 'unknown-sgr', 'duplicate']) {
      const f = fixture(({values}) => {
        values.FD_NAMES = values.FD_NAMES.split('\n').map(id => '\x1b[1;36m' + id + '\x1b[0m').join('\n');
        values.FD_LINKS = values.FD_LINKS.replace(/ ([0-9]+) -> (.*)/g,
          (_, id, target) => ' \x1b[1;36m' + id + '\x1b[0m -> \x1b[1;35m' + target + '\x1b[0m');
        if (kind === 'control') values.FD_NAMES += '\x1b[2J';
        if (kind === 'unknown-sgr') values.FD_NAMES += '\x1b[999m';
        if (kind === 'duplicate') values.FD_NAMES += '\n\x1b[1;36m0\x1b[0m';
      });
      const r = await observe({...base, rounds: 1, newCapture: path.join(directory, 'fd-' + kind)}, f);
      assert.equal(r.fdSamplesComplete, kind === 'colors');
      if (kind === 'colors') {
        assert.equal(r.samples[0].fdCount, 3); assert.equal(r.samples[0].bootIdFdCount, 1);
        assert.equal(r.observationErrors, 0);
      } else assert.ok(r.samples[0].errors.includes(kind === 'duplicate' ? 'FD_NAMES_INVALID' : 'FD_NAMES_CONTROL_INVALID'));
    }
  });
  await check('重复核心、错误版本和活动摘要均拒绝绑定', async () => {
    for (const type of ['pid', 'version', 'sha']) {
      const f = fixture(({values}) => { if (type === 'pid') values.CORE_PIDS = '321 322'; else values.ACTIVE = values.ACTIVE.replace(type === 'version' ? '118' : expectedSha256, type === 'version' ? '117' : 'd'.repeat(64)); });
      const r = await observe({...base, newCapture: path.join(directory, 'bad-' + type)}, f); assert.equal(r.roundsObserved, 0); assert.notEqual(r.stop, 'WINDOW_COMPLETE');
    }
  });
  await check('FD读取errno原文与退出码保全，扫描不推进不伪装成功', async () => {
    const f = fixture(({values, exits, request}) => { if (request.roundIndex >= 0) { values.FD_LINKS = 'ls: Permission denied'; exits.FD_LINKS = 1; values.SCAN = '{"capturedAtMs":1}'; } });
    const r = await observe({...base, rounds: 2, newCapture: path.join(directory, 'read-failure')}, f);
    assert.equal(r.fdSamplesComplete, false); assert.equal(r.samples[1].scanAdvanced, false); assert.equal(r.samples[0].bootIdFdCount, null);
    const receipt = JSON.parse(fs.readFileSync(path.join(directory, 'read-failure/01-result-private.json')));
    assert.equal(receipt.sectionExits.FD_LINKS, 1); assert.equal(receipt.remoteExit, 1); assert.equal(receipt.remoteErrno, null);
    assert.equal(fs.readFileSync(path.join(directory, 'read-failure/01-fd_links-private.bin'), 'utf8').trim(), 'ls: Permission denied');
  });
  await check('ADB超时保全半输出和宿主errno，不继续调用', async () => {
    const f = fixture(); f.execute = async () => ({stdout: Buffer.from('partial'), stderr: Buffer.from('synthetic timeout'), hostExit: null,
      error: {code: 'ETIMEDOUT', errno: 'ETIMEDOUT', killed: true}});
    const r = await observe({...base, newCapture: path.join(directory, 'timeout')}, f); assert.equal(r.stop, 'ADB_TIMEOUT'); assert.equal(r.adbCalls, 1);
    assert.equal(fs.readFileSync(path.join(directory, 'timeout/00-stdout-private.bin'), 'utf8'), 'partial');
    assert.equal(JSON.parse(fs.readFileSync(path.join(directory, 'timeout/00-result-private.json'))).hostErrno, 'ETIMEDOUT');
  });
  await check('ADB传递远端非零状态仍保全可读分节', async () => {
    const f = fixture(({values, exits, request}) => {
      if (request.roundIndex >= 0) { values.SCAN = 'cat: No such file or directory'; exits.SCAN = 1; }
    });
    const original = f.execute;
    f.execute = async (...args) => {
      const output = await original(...args);
      if (args[2].roundIndex >= 0) { output.hostExit = 1; output.error = {code: 1, killed: false, signal: null}; }
      return output;
    };
    const r = await observe({...base, rounds: 2, newCapture: path.join(directory, 'remote-failure')}, f);
    assert.equal(r.roundsObserved, 2); assert.equal(r.fdSamplesComplete, true);
    assert.equal(r.samples[0].scanCapturedAtMs, null); assert.ok(r.samples[0].errors.includes('SCAN_READ_FAILED'));
  });
  await check('秒精度健康时间、过期与未来时间不冒充新鲜', async () => {
    for (const [name, offset, fresh] of [['same-second', 500, true], ['stale', -21000, false], ['future', 2000, false]]) {
      const f = fixture(({values}) => {
        const h = JSON.parse(values.HEALTH_AFTER); h.time_ms += offset; values.HEALTH_AFTER = JSON.stringify(h);
      });
      const r = await observe({...base, rounds: 1, newCapture: path.join(directory, name)}, f);
      assert.equal(r.samples[0].healthFresh, fresh);
    }
  });
  await check('进程名空格括号与精确Dalvik映射判据', async () => {
    assert.equal(parseProcStat('321 (x ) y) R ' + [...Array(18).fill('0'), '12345'].join(' ')).starttime, '12345');
    const prefix = '0000-1000 r--p 0000 00:00 1 /data/dalvik-cache/arm64/';
    const encoded = apk.slice(1).replaceAll('/', '@') + '@classes.dex';
    assert.equal(matchingMaps(prefix + encoded, apk), true); assert.equal(matchingMaps(prefix + encoded + '.wrong', apk), false);
    assert.equal(matchingMaps(prefix + 'wrong' + encoded, apk), false);
  });
  await check('nonce缺失拒绝，超过五轮及复用目录在执行前拒绝', async () => {
    assert.throws(() => parseFrame(Buffer.from('unmarked'), 'a'.repeat(32), ['PID']), /FRAME_ORDER_INVALID/);
    await assert.rejects(observe({...base, rounds: 6, newCapture: path.join(directory, 'six')}, fixture()), /ROUND_LIMIT/);
    await assert.rejects(observe({...base, newCapture: path.join(directory, 'window')}, fixture()), /CAPTURE_ALREADY_EXISTS/);
  });
  await check('单调预算耗尽后不再启动ADB', async () => {
    const f = fixture(({advance}) => advance(TOTAL_MS));
    const r = await observe({...base, newCapture: path.join(directory, 'budget')}, f); assert.equal(r.stop, 'WINDOW_TIME_BUDGET'); assert.equal(r.adbCalls, 1);
  });
  save(directory, 'self-test.json', {passed: results.filter(r => r.passed).length, failed: results.filter(r => !r.passed).length, results,
    deviceContacted: false, adbExecuted: false});
  console.log(JSON.stringify({tests: results.length, passed: results.filter(r => r.passed).length, failed: results.filter(r => !r.passed).length,
    deviceContacted: false}));
  return results.every(r => r.passed) ? 0 : 1;
}

export async function main(argv) {
  const {selfTest: testMode, options} = parseArgs(argv);
  if (testMode) return selfTest(options.newCapture);
  const result = await observe(options);
  console.log(JSON.stringify(result));
  return result.stop === 'WINDOW_COMPLETE' && result.fdSamplesComplete && result.observationErrors === 0 ? 0 : 1;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).then(code => { process.exitCode = code; }).catch(error => {
    console.error(JSON.stringify({state: 'STOPPED', code: error instanceof ObservationError ? error.code : 'HOST_EXECUTION_FAILED'})); process.exitCode = 1;
  });
}
