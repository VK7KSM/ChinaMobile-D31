import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { resolve, join, dirname, delimiter } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

// 仅离线冻结、编译与JUnit；没有ADB、联网、Gradle或设备写入入口。
const args = process.argv.slice(2);
const options = {};
for (let i = 0; i < args.length; i++) {
  const key = args[i];
  if (key === '--include-root-bridge') { options[key] = true; continue; }
  if (!['--output', '--java-home', '--android-jar', '--json-jar', '--junit-jar', '--hamcrest-jar', '--frozen-classes'].includes(key)
      || !args[i + 1] || args[i + 1].startsWith('--') || options[key]) throw new Error('参数缺失、重复或未知');
  options[key] = args[++i];
}
for (const key of ['--output', '--java-home', '--android-jar', '--json-jar', '--junit-jar', '--hamcrest-jar', '--frozen-classes']) {
  if (!options[key]) throw new Error(`缺少参数：${key}`);
  options[key] = resolve(options[key]);
}
const suffix = process.platform === 'win32' ? '.exe' : '';
const javac = join(options['--java-home'], 'bin', `javac${suffix}`);
const java = join(options['--java-home'], 'bin', `java${suffix}`);
for (const path of [java, javac, ...['--android-jar', '--json-jar', '--junit-jar', '--hamcrest-jar'].map(k => options[k])]) {
  if (!statSync(path).isFile()) throw new Error('编译依赖必须是明确的文件');
}
if (!existsSync(join(options['--frozen-classes'], 'net/elfradio/d31bootstrap/RemoteRuntimeInventory.class'))) {
  throw new Error('冻结class目录缺少已验收的RemoteRuntimeInventory');
}
const output = options['--output'];
if (existsSync(output)) throw new Error('证据目录必须全新，禁止覆盖');
mkdirSync(output, { recursive: true });
const project = fileURLToPath(new URL('../', import.meta.url));
const packagePath = 'net/elfradio/d31bootstrap';
const files = [];
for (const kind of ['main', 'test']) {
  const relative = `app/src/${kind}/java/${packagePath}/management`;
  for (const name of readdirSync(join(project, relative)).sort()) {
    if (/^Network.*\.java$/.test(name) && name !== 'NetworkStatus.java') files.push(`${relative}/${name}`);
  }
  if (options['--include-root-bridge']) {
    files.push(`app/src/${kind}/java/${packagePath}/RemoteNetworkAccess${kind === 'test' ? 'Test' : ''}.java`);
  }
}
const manifest = [];
const frozen = files.map(relative => {
  const bytes = readFileSync(join(project, relative));
  const target = join(output, 'source', relative);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, bytes, { flag: 'wx' });
  manifest.push({ path: relative, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') });
  return target;
});
writeFileSync(join(output, 'source-manifest.json'), JSON.stringify(manifest, null, 2), { flag: 'wx' });
const classes = join(output, 'classes'), temporary = join(output, 'tmp');
mkdirSync(classes); mkdirSync(temporary);
const classpath = [classes, ...['--json-jar', '--junit-jar', '--hamcrest-jar', '--android-jar', '--frozen-classes'].map(k => options[k])].join(delimiter);
function run(binary, arguments_, name) {
  const result = spawnSync(binary, arguments_, { encoding: 'utf8', timeout: 60000, maxBuffer: 8 * 1024 * 1024, windowsHide: true });
  writeFileSync(join(output, `${name}.stdout.txt`), result.stdout ?? '', { flag: 'wx' });
  writeFileSync(join(output, `${name}.stderr.txt`), result.stderr ?? '', { flag: 'wx' });
  writeFileSync(join(output, `${name}.result.json`), JSON.stringify({ status: result.status, signal: result.signal, error: result.error?.code ?? null }, null, 2), { flag: 'wx' });
  if (result.status !== 0 || result.error) throw new Error(`${name}失败；原始输出已保留在新证据目录`);
  return result.stdout;
}
run(javac, ['--release', '8', '-encoding', 'UTF-8', '-cp', classpath, '-d', classes, ...frozen], 'compile');
const tests = files.filter(p => p.includes('/test/') && p.endsWith('Test.java'))
  .map(p => p.split('/java/')[1].replaceAll('/', '.').slice(0, -5));
const result = run(java, [`-Djava.io.tmpdir=${temporary}`, '-cp', classpath, 'org.junit.runner.JUnitCore', ...tests], 'junit');
if (!/OK \(\d+ tests?\)/.test(result)) throw new Error('缺少JUnit完成结果');
writeFileSync(join(output, 'verification.json'), JSON.stringify({
  status: '离线通过', evidence: '冻结源码、独立JVM和注入平台；不代表Android真机或Web通过',
  tests: Number(result.match(/OK \((\d+) tests?\)/)[1]), sourceFiles: manifest.length,
  network_write: false, deviceActions: false,
}, null, 2), { flag: 'wx' });
process.stdout.write(`${result.trim()}\n证据目录：${output}\n`);
