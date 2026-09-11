import fs from 'node:fs';
import path from 'node:path';
import {createHash, randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';

export const hash = bytes => createHash('sha256').update(bytes).digest('hex');
export class QueueError extends Error {
  constructor(code, retryable = false) { super(code); this.code = code; this.retryable = retryable; }
}
export function requireThat(value, code) { if (!value) throw new QueueError(code); }

// 锁由内核持有；宿主崩溃关闭管道后自动释放，不以陈旧PID或时间猜测锁归属。
const LOCK_PROGRAM = `import sys,os
f=open(sys.argv[1],'a+b')
if os.fstat(f.fileno()).st_size==0:
 f.write(b'0'); f.flush()
f.seek(0)
try:
 if os.name=='nt':
  import msvcrt
  msvcrt.locking(f.fileno(),msvcrt.LK_NBLCK,1)
 else:
  import fcntl
  fcntl.flock(f.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
except OSError:
 print('BUSY',flush=True); sys.exit(2)
print('LOCKED',flush=True)
sys.stdin.buffer.read()
f.close()
`;

export class QueueStore {
  constructor(directory, {python = 'python', maxDiskBytes = 268435456, maxSnapshots = 4096} = {}) {
    this.directory = path.resolve(directory); this.python = python;
    requireThat(Number.isSafeInteger(maxDiskBytes) && maxDiskBytes >= 4194304 && maxDiskBytes <= 1073741824, 'DISK_LIMIT_INVALID');
    requireThat(Number.isSafeInteger(maxSnapshots) && maxSnapshots > 0 && maxSnapshots <= 4096, 'SNAPSHOT_LIMIT_INVALID');
    this.maxDiskBytes = maxDiskBytes; this.maxSnapshots = maxSnapshots; this.locked = false;
    fs.mkdirSync(this.directory, {recursive: true, mode: 0o700});
    requireThat(fs.lstatSync(this.directory).isDirectory() && !fs.lstatSync(this.directory).isSymbolicLink(), 'STORE_NOT_DIRECTORY');
  }
  file(name) {
    requireThat(/^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,180}$/.test(name), 'LOCAL_NAME_INVALID');
    const filename = path.join(this.directory, name);
    if (fs.existsSync(filename)) requireThat(fs.lstatSync(filename).isFile() && !fs.lstatSync(filename).isSymbolicLink(), 'LOCAL_FILE_UNSAFE');
    return filename;
  }
  async withLock(action) {
    requireThat(!this.locked, 'QUEUE_LOCK_BUSY');
    const child = spawn(this.python, ['-u', '-c', LOCK_PROGRAM, this.file('queue.lock')], {stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true});
    let exited = false;
    const exit = new Promise(resolve => { child.once('close', () => { exited = true; resolve(); }); });
    // 避免解释器失败时向已关闭管道写入导致宿主未捕获异常。
    child.stdin.on('error', () => {});
    child.stderr.resume();
    try {
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new QueueError('LOCK_START_TIMEOUT')), 5000);
        let line = '';
        const finish = error => { clearTimeout(timer); error ? reject(error) : resolve(); };
        child.once('error', () => finish(new QueueError('PYTHON_UNAVAILABLE')));
        child.once('exit', () => finish(new QueueError('QUEUE_LOCK_BUSY')));
        child.stdout.on('data', data => {
          line += data.toString();
          if (line.includes('\n')) finish(line.trim() === 'LOCKED' ? null : new QueueError('QUEUE_LOCK_BUSY'));
        });
      });
      this.locked = true; this.lockAlive = () => !exited;
      return await action();
    } finally {
      this.locked = false;
      child.stdin.end();
      if (!exited) {
        const timer = setTimeout(() => child.kill(), 1000);
        await exit; clearTimeout(timer);
      }
    }
  }
  assertLocked() { requireThat(this.locked && this.lockAlive(), 'QUEUE_LOCK_LOST'); }
  inventory() {
    const names = fs.readdirSync(this.directory);
    requireThat(names.length <= 12000, 'STORE_FILE_COUNT_LIMIT');
    let bytes = 0;
    for (const name of names) bytes += fs.statSync(this.file(name)).size;
    return {names, bytes};
  }
  capacity(extra) {
    this.assertLocked();
    requireThat(this.inventory().bytes + extra + 1048576 <= this.maxDiskBytes, 'STORE_CAPACITY_LIMIT');
  }
  readJson(name, maxBytes = 1048576) {
    const filename = this.file(name);
    requireThat(fs.statSync(filename).size <= maxBytes, 'JSON_SIZE_LIMIT');
    try { return JSON.parse(fs.readFileSync(filename, 'utf8')); }
    catch { throw new QueueError('LOCAL_JSON_INVALID'); }
  }
  load() {
    this.assertLocked();
    const names = this.inventory().names.filter(n => /^state-\d{6}\.json$/.test(n)).sort();
    this.sequence = names.length ? Number(names.at(-1).slice(6, 12)) : 0;
    if (!names.length) {
      requireThat(!this.inventory().names.some(n => n !== 'queue.lock'), 'UNCOMMITTED_STORE_REQUIRES_REVIEW');
      return null;
    }
    const envelope = this.readJson(names.at(-1));
    requireThat(hash(JSON.stringify(envelope.state)) === envelope.sha256, 'STATE_HASH_MISMATCH');
    return envelope.state;
  }
  save(state) {
    this.assertLocked();
    requireThat(this.sequence < this.maxSnapshots, 'SNAPSHOT_LIMIT');
    const data = Buffer.from(JSON.stringify({state, sha256: hash(JSON.stringify(state))}));
    requireThat(data.length <= 1048576, 'STATE_SIZE_LIMIT');
    const name = `state-${String(this.sequence + 1).padStart(6, '0')}.json`;
    this.capacity(data.length);
    this.writeNew(`${name}.${randomUUID()}.tmp`, data, name);
    this.sequence++;
  }
  syncDirectory() {
    if (process.platform === 'win32') return;
    const fd = fs.openSync(this.directory, 'r');
    try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
  }
  writeNew(name, data, committedName = null) {
    this.assertLocked(); this.capacity(data.length);
    const filename = this.file(name);
    const fd = fs.openSync(filename, 'wx', 0o600);
    try { fs.writeFileSync(fd, data); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
    if (committedName) {
      requireThat(!fs.existsSync(this.file(committedName)), 'LOCAL_ALREADY_EXISTS');
      fs.renameSync(filename, this.file(committedName));
    }
    this.syncDirectory();
    return committedName || name;
  }
  artifact(label, data) { return this.writeNew(`${label}-${randomUUID()}.json`, Buffer.from(JSON.stringify(data))); }
  bundleMatches(name, receipt) {
    const filename = this.file(name);
    if (!fs.existsSync(filename)) return false;
    const stat = fs.statSync(filename);
    return stat.size === receipt.bytes && stat.size <= 8388608 && hash(fs.readFileSync(filename)) === receipt.sha256;
  }
}
