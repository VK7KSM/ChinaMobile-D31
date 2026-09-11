import fs from 'node:fs';
import path from 'node:path';
import {createHash, randomUUID} from 'node:crypto';
import {sessionCookie} from './remote-tool-session.mjs';

const [sessionPath, capture, deviceName, version] = process.argv.slice(2);
if (!sessionPath || !capture || fs.existsSync(capture) || !deviceName || !version)
  throw Error('需要私有会话、全新目录、目标名称和已验证版本');
fs.mkdirSync(capture, {recursive:true});
const base = 'https://v.elfradio.net';
const headers = {Cookie:sessionCookie(JSON.parse(fs.readFileSync(sessionPath, 'utf8').replace(/^\uFEFF/, ''))), Origin:base};
const hash = b => createHash('sha256').update(b).digest('hex');
const save = (name, value) => fs.writeFileSync(path.join(capture, name), JSON.stringify(value, null, 2), {flag:'wx'});
async function api(route, body) {
  const response = await fetch(base + route, {headers:{...headers, 'Content-Type':'application/json'},
    method:body ? 'POST' : 'GET', body:body ? JSON.stringify(body) : undefined,
    redirect:'error', signal:AbortSignal.timeout(20000)});
  if (!response.ok) throw Error('接口HTTP ' + response.status);
  return response.json();
}
const devices = (await api('/api/devices')).devices.filter(d => d.name === deviceName && d.model_id === 'mdl_d31');
if (devices.length !== 1 || !devices[0].ready || devices[0].app_version !== version
    || !devices[0].managed_file_tasks || !devices[0].managed_file_return) throw Error('目标或实际文件能力不符');
const device = devices[0]; save('device-private.json', device);
let sequence = 0;
async function task(type, params) {
  const label = String(++sequence).padStart(2, '0');
  const request = {device_id:device.id, id:'d31-tree-' + randomUUID(), type, params, expires_at:Date.now()+600000};
  save(label + '-request-private.json', request);
  save(label + '-enqueue.json', await api('/api/elfremote/task', request));
  const deadline = Date.now() + 150000;
  while (Date.now() < deadline) {
    const result = await api('/api/elfremote/tasks?device_id=' + encodeURIComponent(device.id) + '&task_id=' + request.id);
    if (result.task && ['success','failed','rejected','cancelled','expired'].includes(result.task.state)) {
      save(label + '-result-private.json', result);
      if (result.task.state !== 'success' || result.task.result?.truncated) throw Error('任务未成功，保留原编号：' + label);
      console.log('步骤 ' + label + ' ' + type + ' 通过');
      return {request, result:result.task.result};
    }
    await new Promise(resolve => setTimeout(resolve, 2500));
  }
  throw Error('任务未取得终态，保留原编号，不重放');
}
const root = '/data/local/tmp/d31-file-tree-' + Date.now();
const quote = value => "'" + value.replaceAll("'", "'\\''") + "'";
const expected = new Map([['nested/中文 空格.txt', Buffer.from('D31 directory verification\n')], ['nested/empty.bin', Buffer.alloc(0)]]);
for (let i=0; i<12; i++) expected.set('entry-' + String(i).padStart(2,'0') + '.txt', Buffer.from('entry-' + i + '\n'));
save('scope.json', {root, deviceChanges:'仅创建合成验收目录及任务记录；保留原件，不自动清理'});
const commands = ['mkdir ' + quote(root), 'mkdir ' + quote(root + '/nested'), 'mkdir ' + quote(root + '/empty-directory')];
for (const [name, bytes] of expected) commands.push('printf %s ' + quote(bytes.toString()) + ' > ' + quote(root + '/' + name));
const created = await task('root_exec', {command:'set -e\n' + commands.join('\n'), cwd:'/', timeout:20});
if (created.result.exit_code !== 0) throw Error('合成目录创建失败');
const actual = new Map(), folders = []; let pages = 0;
async function collect(remote, relative) {
  let offset = 0, total;
  const seen = new Set();
  do {
    const {result} = await task('file_manage', {action:'list', path:remote, offset});
    if (result.exit_code !== 0) throw Error('目录读取失败');
    const listing = JSON.parse(result.text); pages++;
    if (listing.path !== remote || !Array.isArray(listing.entries) || (total !== undefined && total !== listing.total))
      throw Error('目录路径或分页总量不符');
    total = listing.total;
    for (const item of listing.entries) {
      if (!item.name || /[\\/\x00-\x1f]/.test(item.name) || ['.','..'].includes(item.name) || seen.has(item.name) || item.link)
        throw Error('目录条目不安全或重复');
      seen.add(item.name);
      if (!Number.isInteger(item.mode) || !Number.isInteger(item.uid) || !Number.isInteger(item.gid) || !Number.isFinite(item.modified_ms))
        throw Error('缺少新文件页面所需的真实属性');
      const rel = relative ? relative + '/' + item.name : item.name;
      if (item.directory) {folders.push(rel); await collect(remote + '/' + item.name, rel);}
      else actual.set(rel, item);
    }
    if (listing.next === null || listing.next === undefined || listing.next < 0) break;
    if (!Number.isInteger(listing.next) || listing.next <= offset || listing.next > total) throw Error('分页没有向前推进');
    offset = listing.next;
  } while (seen.size < total);
  if (seen.size !== total) throw Error('目录条目遗漏');
}
await collect(root, '');
if (actual.size !== expected.size || !folders.includes('empty-directory')) throw Error('递归条目数量不符');
for (const [name, bytes] of expected) if (actual.get(name)?.bytes !== bytes.length) throw Error('文件遗漏或大小不符：' + name);
// 验证目录内中文非空文件和空文件的最终电脑落盘；其余分页文件已核对属性。
for (const name of ['nested/中文 空格.txt', 'nested/empty.bin']) {
  const {request, result} = await task('get_file', {path:root + '/' + name, allow_cellular:true});
  const bytes = expected.get(name);
  if (result.action !== 'uploaded' || result.sha256 !== hash(bytes)) throw Error('上传回执摘要不符');
  const route = '/api/elfremote/file-return?device_id=' + encodeURIComponent(device.id) + '&task_id=' + request.id;
  const meta = (await api(route)).file;
  const response = await fetch(base + route + '&download=1', {headers, redirect:'error', signal:AbortSignal.timeout(30000)});
  if (!response.ok) throw Error('文件下载HTTP ' + response.status);
  const received = Buffer.from(await response.arrayBuffer());
  const local = path.join(capture, 'download', name); fs.mkdirSync(path.dirname(local), {recursive:true});
  fs.writeFileSync(local, received, {flag:'wx'});
  if (!received.equals(bytes) || meta.size !== received.length || meta.sha256 !== hash(received)) throw Error('最终文件或服务端元数据不符');
}
save('result.json', {passed:true, pages, files:actual.size, folders, downloaded:2, browserUiTested:false});
console.log('目录分页、递归属性、中文文件及空文件实际下载校验通过');
