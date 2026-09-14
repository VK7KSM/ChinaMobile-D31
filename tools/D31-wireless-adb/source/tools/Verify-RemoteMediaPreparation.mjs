import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {sessionCookie} from './remote-tool-session.mjs';

// 仅启动按需准备/查询服务，不创建音频采集、不下发媒体会话。
const [sessionPath, capture, deviceName, version, apk] = process.argv.slice(2);
const match = /^\/data\/local\/d31-remote\/releases\/([a-f0-9]{64})\/remote\.apk$/.exec(apk || '');
if (!sessionPath || !capture || fs.existsSync(capture) || !deviceName || !version || !match)
  throw Error('需要会话、全新证据目录、设备名称、版本及活动APK路径');
const headers = {Cookie: sessionCookie(JSON.parse(fs.readFileSync(sessionPath, 'utf8').replace(/^\uFEFF/, ''))),
  Origin: 'https://v.elfradio.net', 'Content-Type': 'application/json'};
fs.mkdirSync(capture, {recursive: true});
const save = (name, value) => fs.writeFileSync(path.join(capture, name), JSON.stringify(value, null, 2), {flag: 'wx'});
async function api(route, body) {
  const response = await fetch('https://v.elfradio.net' + route, {headers, method: body ? 'POST' : 'GET',
    body: body ? JSON.stringify(body) : undefined, redirect: 'error', signal: AbortSignal.timeout(20000)});
  if (!response.ok) throw Error('接口HTTP ' + response.status);
  return response.json();
}
const matches = (await api('/api/devices')).devices.filter(d => d.name === deviceName && d.model_id === 'mdl_d31');
if (matches.length !== 1 || !matches[0].ready || String(matches[0].app_version) !== version)
  throw Error('目标、版本或就绪状态不符');
const device = matches[0]; save('before-private.json', device);
if (device.managed_media === true) throw Error('本批不允许整套媒体能力已开启');
const results = [];
for (const [index, operation] of ['query', 'prepare', 'query'].entries()) {
  const label = String(index + 1).padStart(2, '0');
  const request = {device_id: device.id, id: 'd31-media-prepare-' + randomUUID(), type: 'root_exec',
    params: {cwd: '/', timeout: 40, command: `CLASSPATH='${apk}' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteMediaCommand ${operation} ${match[1]}`},
    expires_at: Date.now() + 180000};
  save(label + '-request-private.json', request);
  save(label + '-enqueue-private.json', await api('/api/elfremote/task', request));
  const until = Date.now() + 120000;
  let completed = false;
  while (Date.now() < until) {
    const response = await api('/api/elfremote/tasks?device_id=' + encodeURIComponent(device.id) + '&task_id=' + encodeURIComponent(request.id));
    if (response.task && ['success', 'failed', 'rejected', 'expired', 'cancelled'].includes(response.task.state)) {
      save(label + '-response-private.json', response);
      const task = response.task;
      if (task.state !== 'success' || task.result?.exit_code !== 0 || task.result?.truncated)
        throw Error('原任务未成功，保留回执，不重新编号重发');
      const value = JSON.parse(task.result.text);
      if (!value.query_completed || value.managed_media !== false || value.capture_started !== false || value.operation !== operation)
        throw Error('准备入口回执不符');
      if (operation === 'prepare' && (value.result?.app_identity !== 'MATCH' || value.result?.process_64bit !== false
          || value.result?.apk_hash_match !== true || value.result?.jni_loaded !== true
          || value.result?.audio_record_created !== false || value.result?.network_started !== false))
        throw Error('真实应用身份或无采集准备证据不符');
      if (operation === 'prepare' && (!['IDLE', 'BUSY', 'UNKNOWN'].includes(value.result?.audio_occupancy_snapshot?.state)
          || typeof value.result.audio_occupancy_snapshot.reason !== 'string')) throw Error('缺少APP音频占用实测快照');
      if (operation === 'query' && value.result?.state !== 'idle') throw Error('媒体服务存在未预期会话');
      results.push(value); completed = true; break;
    }
    await new Promise(resolve => setTimeout(resolve, 1500));
  }
  if (!completed) throw Error('原任务未取得终态，先查询该任务再继续');
  console.log('媒体 ' + operation + ' 回执已核验');
}
save('result.json', {passed: true, queryPrepareQuery: true, actualApp32: true, jniLoaded: true,
  audioCaptureStarted: false, mediaNetworkStarted: false, managedMedia: false,
  preconditionsSatisfied: results[1].result.preconditions_satisfied, preparation: results[1].result});
console.log('真实APP32准备与查询通过；录音和网页媒体会话未启用。');
