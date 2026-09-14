import fs from 'node:fs';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {QueueError} from './QueueStore.mjs';

// 复用唯一的逐项ZIP合同；不把服务器接收回执当作电脑保全证明。
export class PackageVerifier {
  constructor({python = 'python'} = {}) { this.python = python; }
  async verify({bundle, receiptFile, eventId, output, timeoutMs}) {
    const script = fileURLToPath(new URL('../faults/Verify-FaultExport.py', import.meta.url));
    await new Promise((resolve, reject) => {
      // Windows的Python需要扩展长度路径，否则真实落盘文件可能被误判为不存在。
      const localPath = value => process.platform === 'win32' ? path.toNamespacedPath(value) : value;
      execFile(this.python, [localPath(script), '--zip', localPath(bundle), '--receipt', localPath(receiptFile),
        '--event-id', eventId, '--output', localPath(output)],
        {timeout: timeoutMs, maxBuffer: 65536, windowsHide: true}, error => {
          if (!error) resolve();
          else reject(new QueueError(error.killed ? 'VERIFY_TIMEOUT' : 'PACKAGE_VERIFY_FAILED', !!error.killed));
        });
    });
    // 验证器结果也是保全证据，完成同步后才允许进入归档阶段。
    const fd = fs.openSync(output, 'r+');
    try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
  }
}
