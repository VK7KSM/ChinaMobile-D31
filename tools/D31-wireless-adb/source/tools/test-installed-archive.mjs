import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {hash} from './fault-transfer/QueueStore.mjs';
import {WebTransport} from './fault-transfer/WebTransport.mjs';
import {collect, MAX_APK_BYTES, snapshotCommand, validateSnapshot, validateArchivePath,
  validateArchiveParent} from './Collect-InstalledArchive.mjs';

const digest = 'a'.repeat(64);
const options = {deviceId: 'synthetic-device', deviceName: 'synthetic-name', version: 186,
  versionName: 'synthetic-version', apkSha256: digest, packageName: 'com.starnet.getnumber'};
const identity = 'synthetic/build\n00000000-0000-0000-0000-000000000000\n' + JSON.stringify({
  versionCode: 186, sha256: digest, path: '/data/local/d31-remote/releases/' + digest + '/remote.apk'});
const root = text => ({action: 'completed', text, exit_code: 0, truncated: false});
function snapshot(bytes, changes = {}) {
  const v = {path: '/system/vendor/3rd-app/getnumber.apk', inode: '42', mode: '81a4',
    size: bytes.length, sha: hash(bytes), ...changes};
  return `D31_INSTALLED_ARCHIVE_V1\npackage=${options.packageName}\npath=${v.path}\nstat=1|${v.inode}|${v.mode}|${v.size}|0|0|100|100\nsha256=${v.sha}\nEND_ARCHIVE`;
}

// 全部使用合成数据与假传输；确认接口仍执行真实WebTransport参数校验。
function fixture(t, config = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'd31-archive-test-'));
  t.after(() => fs.rmSync(dir, {recursive: true, force: true}));
  const opts = {...options, state: path.join(dir, 'state'), execute: true};
  const bytes = Buffer.alloc(config.size || 1483580, 65), tasks = new Map();
  const counts = {enqueue: 0, download: 0, received: 0};
  let lost = false, clock = 0;
  const transport = {
    async json(route) {
      assert.equal(route, '/api/devices');
      return {devices: [{id: opts.deviceId, name: opts.deviceName, model_id: 'mdl_d31',
        ready: true, app_version: opts.versionName}]};
    },
    async queryTask(device, id) {
      assert.equal(device, opts.deviceId);
      const value = tasks.get(id);
      return value && config.pending ? {...value, state: 'pending'} : value;
    },
    async enqueue(request) {
      assert.equal(tasks.has(request.id), false); counts.enqueue++;
      let result;
      if (request.type === 'get_file') result = {action: 'uploaded', bytes: bytes.length,
        sha256: config.badReturn ? 'b'.repeat(64) : hash(bytes)};
      else if (!request.params.command.includes('D31_INSTALLED_ARCHIVE_V1')) result = root(identity);
      else {
        const after = request.params.command.includes('D31_IDENTITY_V1');
        result = root(snapshot(bytes, after ? config.after : {}) + (after
          ? '\nD31_IDENTITY_V1\n' + (config.badIdentity ? identity.replace('synthetic/build', 'other/build') : identity) : ''));
      }
      tasks.set(request.id, {...request, state: 'success', result});
      if (config.loseEnqueue && !lost) { lost = true; throw Error('LOST_ENQUEUE'); }
      return {ok: true};
    },
    async download(device, id, args) {
      assert.equal(tasks.get(id).type, 'get_file'); counts.download++;
      fs.writeFileSync(args.filename, config.corrupt ? Buffer.from('bad') : bytes, {flag: 'wx'});
      if (config.interruptDownload) { config.interruptDownload = false; throw Error('LOST_DOWNLOAD'); }
    },
    async received(device, id, args, io) {
      assert.equal(tasks.get(id).type, 'get_file');
      assert.deepEqual(fs.readFileSync(path.join(opts.state, 'installed.apk')), bytes);
      const receipt = JSON.parse(fs.readFileSync(path.join(opts.state, 'archive-receipt.json')));
      assert.equal(receipt.sha256, hash(bytes));
      return WebTransport.prototype.received.call({async json(route, body) {
        assert.ok(route.startsWith('/api/elfremote/file-return/received?'));
        assert.deepEqual(body, {size: bytes.length, sha256: hash(bytes)}); counts.received++;
        if (config.loseReceived) { config.loseReceived = false; throw Error('LOST_RECEIVED'); }
        return config.cleanupPending ? {ok: true, cleanup_pending: true} : {ok: true, purged: true};
      }}, device, id, args, io);
    }
  };
  return {opts, counts, config, run: () => collect(opts, {transport,
    now: () => clock, wait: async ms => { clock += ms; }})};
}

test('默认预览无IO，固定单包，执行标志严格', async () => {
  const opts = {...options, state: 'unused'};
  assert.equal((await collect(opts)).deviceActions, 0);
  await assert.rejects(collect({...opts, packageName: 'other'}), /PACKAGE_NOT_ALLOWED/);
  await assert.rejects(collect({...opts, execute: 'true'}), /EXECUTE_FLAG_INVALID/);
});
test('严格路径、单路径、普通文件、摘要与8MiB上限', () => {
  assert.equal(MAX_APK_BYTES, 8388608);
  const bytes = Buffer.from('apk');
  for (const value of ['/data/app/../x.apk', '/data/app/x.apk\npackage:/data/app/y.apk',
    '/sdcard/x.apk', '/system/x;id.apk', '/system//x.apk']) assert.throws(() => validateArchivePath(value));
  for (const changes of [{mode: 'a1ff'}, {size: MAX_APK_BYTES + 1}, {size: 0}, {sha: 'bad'}, {inode: '-1'}])
    assert.throws(() => validateSnapshot(snapshot(bytes, changes), options));
  assert.throws(() => validateSnapshot(snapshot(bytes) + '\nextra', options));
  assert.equal(validateSnapshot(snapshot(bytes, {size: MAX_APK_BYTES}), options).archive.bytes, MAX_APK_BYTES);
  const command = snapshotCommand(options.packageName);
  assert.ok(command.includes('bb=/system/bin/busybox'));
  assert.equal(command.match(/\/system\/bin\/stat -c/g).length, 4);
  assert.ok(!command.includes('"$bb" stat'));
  assert.ok(command.includes('8388608'));
  assert.equal(command.match(/sha256sum/g).length, 2);
});

test('真实shell在每一项一致性断言失败时退出', () => {
  const assertions = snapshotCommand(options.packageName).split('\n').filter(line =>
    /^\[ "\$(before|middle|first)" = /.test(line));
  assert.equal(assertions.length, 3);
  const shell = process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : '/bin/sh';
  for (const [values, expected] of [
    ['before=a; middle=a; after=a; first=a; second=a', 0],
    ['before=b; middle=a; after=a; first=a; second=a', 26],
    ['before=a; middle=a; after=b; first=a; second=a', 26],
    ['before=a; middle=a; after=a; first=a; second=b', 26]
  ]) {
    const result = spawnSync(shell, ['-c', ['set -eu', values, ...assertions, 'echo VERIFIED'].join('\n')], {encoding: 'utf8'});
    assert.ifError(result.error);
    assert.equal(result.status, expected, result.stderr);
    assert.equal(result.stdout.trim(), expected === 0 ? 'VERIFIED' : '');
  }
});

test('D31原生stat设备号为十六进制，时间和inode仍严格检查', () => {
  const raw = snapshot(Buffer.from('apk')).replace('stat=1|', 'stat=45845d|');
  assert.equal(validateSnapshot(raw, options).archive.stat.device, '45845d');
  for (const changed of [raw.replace('45845d', 'g234'), raw.replace('|100|100', '|unknown|100'),
    raw.replace('stat=45845d|', 'stat=45845d|-')])
    assert.throws(() => validateSnapshot(changed, options));
});
test('专用父任务接受超过1MiB，拒绝超过8MiB与错误父绑定', () => {
  const request = {id: 'task', device_id: 'synthetic-device', type: 'get_file', params: {path: '/system/a.apk'}};
  const good = {...request, state: 'success', result: {action: 'uploaded', bytes: 1483580, sha256: digest}};
  assert.equal(validateArchiveParent(good, request).bytes, 1483580);
  for (const change of [{id: 'wrong'}, {device_id: 'wrong'}, {type: 'root_exec'}, {state: 'failed'},
    {params: {}}, {result: {...good.result, bytes: MAX_APK_BYTES + 1}}])
    assert.throws(() => validateArchiveParent({...good, ...change}, request));
  const req = {...request, type: 'root_exec'};
  for (const change of [{truncated: true}, {exit_code: 1}])
    assert.throws(() => validateArchiveParent({...req, state: 'success', result: {...root('ok'), ...change}}, req));
});
test('四任务与固定原件保全后调用原received，续作不重采', async t => {
  const f = fixture(t); const r = await f.run();
  assert.equal(path.basename(r.archiveFile), 'installed.apk');
  assert.equal(path.basename(r.receiptFile), 'archive-receipt.json');
  assert.equal(r.status, 'CAPTURE_VERIFIED'); assert.equal(r.deviceId, undefined);
  await f.run(); assert.deepEqual(f.counts, {enqueue: 4, download: 1, received: 1});
});
test('8MiB边界通过真实received参数检查', async t => {
  const f = fixture(t, {size: MAX_APK_BYTES}); assert.equal((await f.run()).bytes, MAX_APK_BYTES);
});
for (const [name, config, error] of [
  ['同路径inode替换', {after: {inode: '43'}}, /INSTALLED_ARCHIVE_CHANGED/],
  ['路径变化', {after: {path: '/system/other.apk'}}, /INSTALLED_ARCHIVE_CHANGED/],
  ['摘要变化', {after: {sha: 'b'.repeat(64)}}, /INSTALLED_ARCHIVE_CHANGED/],
  ['身份变化', {badIdentity: true}, /OBSERVATION_IDENTITY_CHANGED/],
  ['文件回执不匹配', {badReturn: true}, /RETURN_BINDING_CHANGED/],
  ['下载损坏', {corrupt: true}, /LOCAL_ARCHIVE/]
]) test(name + '不确认删除', async t => {
  const f = fixture(t, config); await assert.rejects(f.run(), error); assert.equal(f.counts.received, 0);
});
for (const [name, config, error, downloads, received] of [
  ['入队丢响应', {loseEnqueue: true}, /LOST_ENQUEUE/, 1, 1],
  ['下载中断', {interruptDownload: true}, /LOST_DOWNLOAD/, 2, 1],
  ['确认丢响应', {loseReceived: true}, /LOST_RECEIVED/, 1, 2]
]) test(name + '恢复原任务', async t => {
  const f = fixture(t, config); await assert.rejects(f.run(), error); await f.run();
  assert.deepEqual(f.counts, {enqueue: 4, download: downloads, received});
});
test('等待超时保留原号', async t => {
  const f = fixture(t, {pending: true}); await assert.rejects(f.run(), /PENDING_QUERY_ORIGINAL_TASK/);
  f.config.pending = false; await f.run(); assert.equal(f.counts.enqueue, 4);
});
test('待清理续作只重试确认', async t => {
  const f = fixture(t, {cleanupPending: true}); assert.equal((await f.run()).cleanupPending, true);
  f.config.cleanupPending = false; assert.equal((await f.run()).cleanupPending, false);
  assert.deepEqual(f.counts, {enqueue: 4, download: 1, received: 2});
});
test('本地原件与回执篡改及目标绑定改变均拒绝', async t => {
  const f = fixture(t); const r = await f.run();
  f.opts.version++; await assert.rejects(f.run(), /STATE_BINDING_CHANGED/); f.opts.version--;
  fs.writeFileSync(r.receiptFile, '{}'); await assert.rejects(f.run(), /LOCAL_RECEIPT_CHANGED/);
  fs.writeFileSync(r.archiveFile, 'bad'); await assert.rejects(f.run(), /LOCAL_ARCHIVE/);
  assert.equal(f.counts.received, 1);
});
