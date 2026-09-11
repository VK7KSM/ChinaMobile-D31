import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {captureHttp} from './Verify-RemoteMediaModes.mjs';

// 全部使用内存响应，不访问ADB、Web或会话凭据；临时证据保留供复核。
function fixture(response) {
  const capture = fs.mkdtempSync(path.join(os.tmpdir(), 'd31-media-http-'));
  let calls = 0;
  return {capture, calls: () => calls,
    run: body => captureHttp({capture, label: 'http-1', route: '/api/elfremote/media/session', body,
      transport: {response: async () => { calls++; return response(); }}}),
    meta: () => JSON.parse(fs.readFileSync(path.join(capture, 'http-1-metadata.json'), 'utf8')),
    raw: () => fs.readFileSync(path.join(capture, 'http-1-body-private.bin'))};
}

test('非JSON的500创建响应逐字节保留且不重试', async () => {
  const raw = Buffer.from('<html>upstream error</html>\xff', 'latin1');
  const f = fixture(() => new Response(raw, {status: 500}));
  await assert.rejects(f.run({mode: 'ptt'}), /HTTP_JSON_INVALID/);
  assert.deepEqual(f.raw(), raw);
  assert.equal(f.meta().status, 500);
  assert.equal(f.meta().method, 'POST');
  assert.equal(f.meta().bodyComplete, true);
  assert.equal(f.meta().outcomeUnknown, true);
  assert.equal(f.meta().bodySha256, createHash('sha256').update(raw).digest('hex'));
  assert.equal(f.calls(), 1);
});

test('成功GET记录方法并保存原始JSON', async () => {
  const raw = Buffer.from('{"ok":true,"devices":[]}');
  const f = fixture(() => new Response(raw, {status: 200}));
  assert.equal((await f.run()).value.ok, true);
  assert.deepEqual(f.raw(), raw);
  assert.equal(f.meta().method, 'GET');
  assert.equal(f.meta().jsonParsed, true);
});

test('断流保存已接收字节及HTTP状态', async () => {
  const f = fixture(() => ({status: 502, body: (async function* () {
    yield Buffer.from('partial'); throw Error('private transport detail');
  })()}));
  await assert.rejects(f.run({mode: 'ptt'}), /HTTP_RESPONSE_UNKNOWN/);
  assert.equal(f.raw().toString(), 'partial');
  assert.equal(f.meta().bodyComplete, false);
  assert.equal(f.meta().status, 502);
  assert.equal(f.calls(), 1);
});

test('超限只保留1MiB并明确截断和未知POST结果', async () => {
  const f = fixture(() => new Response(Buffer.alloc(1048577, 65), {status: 500}));
  await assert.rejects(f.run({mode: 'ptt'}), /HTTP_BODY_LIMIT/);
  assert.equal(f.raw().length, 1048576);
  assert.equal(f.meta().truncated, true);
  assert.equal(f.meta().bodyComplete, false);
  assert.equal(f.meta().outcomeUnknown, true);
  assert.equal(f.calls(), 1);
});

test('无响应也留下请求与空原件及未知结果', async () => {
  const f = fixture(() => { throw Error('NETWORK_UNAVAILABLE'); });
  await assert.rejects(f.run({mode: 'ptt'}), /NETWORK_UNAVAILABLE/);
  assert.equal(f.raw().length, 0);
  assert.equal(f.meta().status, null);
  assert.equal(f.meta().requestAttempted, true);
  assert.equal(f.meta().outcomeUnknown, true);
  assert.equal(f.calls(), 1);
});

test('已有证据拒绝覆盖且不发送下一请求', async () => {
  const f = fixture(() => new Response('{"ok":false}', {status: 400}));
  await f.run({mode: 'ptt'});
  const before = f.raw();
  await assert.rejects(f.run({mode: 'ptt'}), /EEXIST/);
  assert.deepEqual(f.raw(), before);
  assert.equal(f.calls(), 1);
});
