// 只读载入显式指定的现有Web纯协议模块，验证Java实际输出；不访问网络。
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

if (process.argv.length !== 4) throw new Error('必须指定现有system-settings.js绝对路径及Java输出夹具路径');
const { systemSettingsParams, applySystemSettingsResult } = await import(pathToFileURL(process.argv[2]).href);
const fixture = JSON.parse(await readFile(process.argv[3], 'utf8'));
const params = systemSettingsParams(fixture.params);
assert.deepEqual(params, fixture.params);
const device = { task: { state: 'running', params }, token_sha256: 'synthetic-test-only' };
applySystemSettingsResult(device, fixture.result, 123456);
assert.equal(device.system_settings.sound.brightness, 80);
assert.equal(device.system_settings.sound.applied, true);
assert.equal(device.system_targets['sound||brightness|'].params.value, 80);
for (const result of [
  { ...fixture.result, exit_code: 1 },
  { ...fixture.result, truncated: true },
  { ...fixture.result, text: JSON.stringify({ group: 'sound', sampled_at: 1, applied: false }) },
]) {
  assert.throws(() => applySystemSettingsResult({ task: { state: 'running', params } }, result, 123456));
}
console.log('通过：Java真实输出被现有Web协议接受；失败、截断及未应用回执被拒绝。未运行服务器或设备测试。');
