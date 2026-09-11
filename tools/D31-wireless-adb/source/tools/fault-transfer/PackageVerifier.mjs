import fs from 'node:fs';
import {execFile} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {QueueError} from './QueueStore.mjs';

// 复用唯一的逐项ZIP合同；不把服务器接收回执当作电脑保全证明。
export class PackageVerifier {
  constructor({python = 'python'} = {}) { this.python = python; }
  async verify({bundle, receiptFile, eventId, output, timeoutMs}) {
    const script = fileURLToPath(new URL('../faults/Verify-FaultExport.py', import.meta.url));
    await new Promise((resolve, reject) => {
      execFile(this.python, [script, '--zip', bundle, '--receipt', receiptFile, '--event-id', eventId, '--output', output],
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
