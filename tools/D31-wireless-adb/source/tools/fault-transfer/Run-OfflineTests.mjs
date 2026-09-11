import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';

const root = fileURLToPath(new URL('./', import.meta.url));
const output = fs.mkdtempSync(path.join(root, 'test-log-' + new Date().toISOString().replace(/[:.]/g, '-') + '-'));
const stdout = fs.openSync(path.join(output, 'stdout.log'), 'wx');
const stderr = fs.openSync(path.join(output, 'stderr.log'), 'wx');
const files = fs.readdirSync(root).filter(name => name.endsWith('.test.mjs')).sort().map(name => path.join(root, name));
const child = spawn(process.execPath, ['--test', '--test-reporter=tap', ...files], {stdio: ['ignore', stdout, stderr], windowsHide: true});
const timer = setTimeout(() => child.kill(), 120000);
child.once('error', () => { process.exitCode = 1; });
child.once('close', code => {
  clearTimeout(timer); fs.fsyncSync(stdout); fs.fsyncSync(stderr); fs.closeSync(stdout); fs.closeSync(stderr);
  console.log('离线测试日志：' + output);
  console.log('退出码：' + code);
  process.exitCode = code === 0 ? 0 : 1;
});
