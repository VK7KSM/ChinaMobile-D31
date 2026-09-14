import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {sessionCookie} from './remote-tool-session.mjs';

// 仅查询本轮D31管理能力；不写设置、不切网、不播放媒体。
const [sessionPath,capture,deviceName,expectedVersion,apk,groupList='sound,time,network,wifi,apps']=process.argv.slice(2);
const groups=groupList==='-'?[]:groupList.split(',');
if(groups.some(group=>!['sound','time','network','wifi','apps'].includes(group)))throw Error('只读组无效');
if(!sessionPath||!capture||fs.existsSync(capture)||!deviceName||!expectedVersion
  ||!/^\/data\/local\/d31-remote\/releases\/[a-f0-9]{64}\/remote\.apk$/.test(apk||''))
  throw Error('需要会话、全新目录、D31名称、版本和已验证活动载荷路径');
const headers={Cookie:sessionCookie(JSON.parse(fs.readFileSync(sessionPath,'utf8').replace(/^\uFEFF/,''))),
  Origin:'https://v.elfradio.net','Content-Type':'application/json'};
fs.mkdirSync(capture,{recursive:true});
const save=(name,value)=>fs.writeFileSync(path.join(capture,name),JSON.stringify(value,null,2),{flag:'wx'});
async function api(route,body){
  const response=await fetch('https://v.elfradio.net'+route,{headers,method:body?'POST':'GET',
    body:body?JSON.stringify(body):undefined,redirect:'error',signal:AbortSignal.timeout(20000)});
  if(!response.ok)throw Error('接口HTTP '+response.status);
  return response.json();
}
const matches=(await api('/api/devices')).devices.filter(d=>d.name===deviceName&&d.model_id==='mdl_d31');
if(matches.length!==1||!matches[0].ready||matches[0].app_version!==expectedVersion)
  throw Error('D31名称、版本或就绪状态不符');
const device=matches[0];save('before-private.json',device);
if(groups.length&&!device.managed_system_settings)throw Error('实际系统管理读取能力尚未上报');
let sequence=0;
async function task(type,params){
  const label=String(++sequence).padStart(2,'0');
  const request={device_id:device.id,id:'d31-management-'+randomUUID(),type,params,expires_at:Date.now()+600000};
  save(label+'-request-private.json',request);
  save(label+'-enqueue-private.json',await api('/api/elfremote/task',request));
  const until=Date.now()+150000;
  while(Date.now()<until){
    const result=await api('/api/elfremote/tasks?device_id='+encodeURIComponent(device.id)+'&task_id='+encodeURIComponent(request.id));
    if(result.task&&['success','failed','rejected','expired'].includes(result.task.state)){
      save(label+'-result-private.json',result);
      if(result.task.state!=='success'||result.task.result?.exit_code!==0||result.task.result?.truncated)
        throw Error('步骤'+label+'未成功，已保存原任务，禁止重发掩盖结果');
      console.log('步骤'+label+' '+type+'/'+(params.group||'故障索引')+'通过');
      return {request,result:JSON.parse(result.task.result.text)};
    }
    await new Promise(resolve=>setTimeout(resolve,2000));
  }
  throw Error('原任务未取得终态，保留任务号并停止后续动作');
}
let first;
for(const group of groups){
  const params={group,action:'read'};
  if(group==='apps')params.package='net.elfradio.d31bootstrap';
  const result=await task('system_config',params);
  if(!result.result.ok||result.result.group!==group||!Number.isFinite(result.result.sampled_at))
    throw Error('实际系统回读字段不符');
  first??=result;
}
if(first){
  const duplicate=await api('/api/elfremote/task',first.request);save('duplicate-private.json',duplicate);
  if(!duplicate.duplicate)throw Error('同号查询未去重');
}
const fault=await task('root_exec',{cwd:'/',timeout:30,
  command:"CLASSPATH='"+apk+"' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.faults.FaultQueryCommand index 1"});
if(fault.result.schemaVersion!==1||!Array.isArray(fault.result.events)||fault.result.rawContentInSummary!==false)
  throw Error('故障索引未按既有命令通道返回');
save('after-private.json',(await api('/api/devices')).devices.filter(d=>d.id===device.id));
save('result.json',{passed:true,readGroups:groups.length,duplicate:!!first,faultIndex:true,deviceChanges:'仅保存任务与故障原件，未写入业务设置'});
console.log('D31 '+groups.length+'组系统读取及故障索引Web验收通过。');
