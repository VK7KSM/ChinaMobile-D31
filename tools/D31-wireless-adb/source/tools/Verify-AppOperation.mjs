// 仅解析回执，不启动进程、不连接设备；128起必须验证共享维护预留真实释放。
export function validateAppOperation(status, {operation, id, version, requestId, sha, boot} = {}) {
  if (version < 128 && status === undefined) return false;
  const check = value => { if (!value) throw Error('APP_OPERATION_HOST_RELEASE_NOT_VERIFIED'); };
  const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value);
  const keys = new Set('operation_id operation apk_sha256 operation_request_id record_boot_id current_boot_id state reservation_released release_reason managed_media network_write'.split(' '));
  check(object(status) && Object.keys(status).every(key => keys.has(key))
    && Object.values(status).every(value => !object(value) && !Array.isArray(value)));
  check(status.operation === operation && /^[A-Za-z0-9_-]{1,96}$/.test(id || '') && status.operation_id === id
    && status.state === 'RELEASED' && status.reservation_released === true
    && status.release_reason === 'MATCHED_RELEASE_RECEIPT' && status.managed_media === false && status.network_write === false
    && uuid(requestId) && status.operation_request_id === requestId
    && /^[a-f0-9]{64}$/.test(status.apk_sha256 || '') && (sha === undefined || status.apk_sha256 === sha)
    && uuid(status.record_boot_id) && status.current_boot_id === status.record_boot_id
    && (boot === undefined || status.current_boot_id === boot));
  return true;
}
