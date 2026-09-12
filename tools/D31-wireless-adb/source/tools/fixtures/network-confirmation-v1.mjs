import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';

// 2026-09-12冻结合同的合成响应替身，仅供宿主测试；不实现生产队列或授权状态机。
// 摘要是固定向量，不调用宿主requestDigest；存在Web源码时另与真实服务端交叉验证。
const APK = 'a'.repeat(64);
const DIGESTS = Object.freeze({
  false: '730b22c9101d64e81eb0b1424ea4acc4be629667dadc922257471b5fb664ec86',
  true: '87e4b20b26b3f19ea505aa2cbf97eff2f43a9851aa4f4b9f92ee157d5074dd11',
});
const BINDING = ['task_id', 'apk_sha256', 'key', 'before', 'target', 'boot_id', 'started_elapsed', 'deadline_elapsed', 'window_ms'];

export const frozenWeb = Object.freeze({
  networkAcceptanceAllowed(device, params, raw, now) {
    const gate = JSON.parse(raw);
    // 只匹配本测试固定的六十秒合成白名单；真实私有门由可选Web测试执行。
    return gate.device_id === device.id && gate.apk_sha256 === APK
      && params.network_transaction.apk_sha256 === APK && gate.expires_at === now + 60000;
  },
  async enqueueRepairTask(device, request, now, _storage, options) {
    assert.equal(options.allowNetworkAcceptance, true);
    assert.equal(typeof request.params.value, 'boolean');
    assert.deepEqual(request.params, {group: 'wifi', action: 'set', package: '', offset: 0, key: 'enabled',
      value: request.params.value, network_transaction: {version: 1, apk_sha256: APK, confirm_within_ms: 60000}});
    assert.equal(device.task, null);
    device.task = {...structuredClone(request), state: 'pending', request_digest: DIGESTS[request.params.value],
      created_at: new Date(now).toISOString(), result: null,
      network: {version: 1, local_task_id: createHash('sha256').update(`${device.id}:${request.id}`).digest('hex'),
        binding: null, result: null}};
    return {ok: true, task: device.task};
  },
  applyRepairProgress(device, taskId, state, _detail, result) {
    assert.equal(device.task.id, taskId);
    device.task.state = state;
    if (result) {
      device.task.result = structuredClone(result);
      device.task.network.result = structuredClone(result.network_transaction);
      device.task.network.binding = structuredClone(result.network_transaction.binding);
    }
  },
  grantNetworkConfirmation(device, task, data, now) {
    assert.equal(device.task.id, data.task_id);
    const v = data.network_confirmation;
    task.network.binding = Object.fromEntries(BINDING.map(key => [key, v[key]]));
    task.network.allowed_at = now;
    task.network.confirm_deadline_at = Math.min(task.expires_at, now + v.deadline_elapsed - v.issued_elapsed);
  },
  publicRepair(task) {
    const n = task.network;
    return {id: task.id, type: task.type, state: task.state, expires_at: task.expires_at,
      network: {version: 1, request_digest: task.request_digest, confirmation_issued: n.allowed_at != null,
        allowed_at: n.allowed_at ?? null, confirm_deadline_at: n.confirm_deadline_at ?? null,
        binding: n.binding ?? null, result: n.result ?? null}, result: task.result};
  },
});
