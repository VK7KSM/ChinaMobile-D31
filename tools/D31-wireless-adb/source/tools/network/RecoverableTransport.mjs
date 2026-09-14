import https from 'node:https';
import dns from 'node:dns';
import {Readable} from 'node:stream';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
import {WebTransport} from '../fault-transfer/WebTransport.mjs';
import {QueueError, requireThat as need} from '../fault-transfer/QueueStore.mjs';

// 地址候选切换发生在TCP连接阶段；不在HTTP写出后自动重放请求。
export function dualStackFetch({request = https.request, lookup = dns.lookup, timeoutMs = 20000} = {}) {
  return (url, options = {}) => new Promise((resolve, reject) => {
    const target = new URL(url);
    if (target.origin !== 'https://v.elfradio.net') return reject(new QueueError('ORIGIN_REJECTED'));
    const signal = AbortSignal.any([AbortSignal.timeout(timeoutMs), ...(options.signal ? [options.signal] : [])]);
    const req = request(target, {
      method: options.method || 'GET', headers: options.headers, signal, agent: false,
      autoSelectFamily: true, autoSelectFamilyAttemptTimeout: 250,
      lookup: (host, opts, callback) => lookup(host, {...opts, all: true, hints: 0}, callback),
    }, incoming => {
      const headers = new Headers();
      for (const [key, value] of Object.entries(incoming.headers)) if (value !== undefined)
        headers.set(key, Array.isArray(value) ? value.join(', ') : String(value));
      if ([204, 205, 304].includes(incoming.statusCode)) {
        incoming.resume(); resolve(new Response(null, {status: incoming.statusCode, headers}));
      } else resolve(new Response(Readable.toWeb(incoming), {status: incoming.statusCode, headers}));
    });
    req.once('error', reject);
    req.end(options.body);
  });
}

export class RecoverableTransport extends WebTransport {
  constructor(options) { super({...options, fetchImpl: options.fetchImpl || dualStackFetch()}); }

  async response(route, body, options) {
    const response = await super.response(route, body, options);
    if (response.status === 408) throw await this.retryError(response, 'HTTP_408');
    return response;
  }

  async downloadResume(deviceId, taskId, {filename, bytes, sha256, signal}) {
    need(Number.isSafeInteger(bytes) && bytes > 0 && bytes <= 8388608 && /^[a-f0-9]{64}$/.test(sha256), 'FILE_BINDING_INVALID');
    let have = 0;
    if (fs.existsSync(filename)) {
      const stat = fs.lstatSync(filename);
      need(stat.isFile() && !stat.isSymbolicLink() && stat.nlink === 1 && stat.size <= bytes, 'PART_INVALID');
      have = stat.size;
    }
    if (have < bytes) {
      let response;
      try {
        response = await this.fetch(this.base + this.fileRoute(deviceId, taskId) + '&download=1', {
          method: 'GET', redirect: 'error', signal,
          headers: {Cookie: this.cookie, Origin: this.base, 'Accept-Encoding': 'identity',
            ...(have ? {Range: `bytes=${have}-`} : {})},
        });
      } catch { throw new QueueError('DOWNLOAD_NETWORK_UNAVAILABLE', true); }
      let fd;
      try {
        if (response.status === 408 || response.status === 429 || response.status >= 500)
          throw await this.retryError(response, `HTTP_${response.status}`);
        need(response.status === (have ? 206 : 200), 'DOWNLOAD_RANGE_STATUS');
        const length = response.headers.get('content-length');
        // 动态流可没有长度头；实际字节上限、完整长度和SHA仍为验收门。
        need(length === null || length === String(bytes - have), 'DOWNLOAD_LENGTH_HEADER');
        const encoding = response.headers.get('content-encoding');
        need(encoding === null || encoding.toLowerCase() === 'identity', 'DOWNLOAD_ENCODING_UNSUPPORTED');
        if (have) need(response.headers.get('content-range') === `bytes ${have}-${bytes - 1}/${bytes}`, 'DOWNLOAD_RANGE_HEADER');
        fd = fs.openSync(filename, have ? 'r+' : (fs.existsSync(filename) ? 'r+' : 'wx'), 0o600);
        let at = have;
        for await (const chunk of response.body) {
          need(at + chunk.length <= bytes, 'DOWNLOAD_BYTE_LIMIT');
          let offset = 0;
          while (offset < chunk.length) offset += fs.writeSync(fd, chunk, offset, chunk.length - offset, at + offset);
          at += chunk.length;
        }
        if (at !== bytes) throw new QueueError('DOWNLOAD_INTERRUPTED', true);
      } catch (error) {
        if (error instanceof QueueError) throw error;
        throw new QueueError('DOWNLOAD_INTERRUPTED', true);
      } finally {
        if (fd !== undefined) { try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); } }
        try { await response.body?.cancel(); } catch {}
      }
    }
    const actual = createHash('sha256').update(fs.readFileSync(filename)).digest('hex');
    need(actual === sha256, 'DOWNLOAD_HASH_MISMATCH');
    return {bytes, sha256};
  }
}
