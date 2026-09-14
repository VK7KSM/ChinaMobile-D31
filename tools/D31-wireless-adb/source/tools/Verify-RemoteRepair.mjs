import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {sessionCookie} from './remote-tool-session.mjs';

// 只经现有Web命令查询已完成本地事务，不把验通道变成重新修复。
const [sessionPath,capture,deviceName,requestPath]=process.argv.slice(2);
if(!sessionPath||!capture||!deviceName?.trim()||!requestPath||fs.existsSync(capture))
  throw Error('需要会话、全新捕获目录、明确D31名称与已验收查询请求');
const query=JSON.parse(fs.readFileSync(requestPath,'utf8').replace(/^\uFEFF/,''));
if(!/^\/data\/local\/d31-remote\/releases\/[a-f0-9]{64}\/remote\.apk$/.test(query.apk)
   ||query.request?.operation!=='query'||!query.expected_phase
   ||!/^[A-Za-z0-9][A-Za-z0-9_-]{0,95}$/.test(query.request.task_id)
   ||!/^[a-f0-9]{64}$/.test(query.request.plan_sha256))throw Error('仅接受明确的已验收事务查询');
const quote=value=>"'"+value.replaceAll("'","'\\''")+"'";
const command='CLASSPATH='+quote(query.apk)+' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteRepairCommand '+quote(JSON.stringify(query.request));
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
if(matches.length!==1||!matches[0].ready)throw Error('D31目标不唯一或未就绪');
const device=matches[0];save('before-private.json',device);
const request={device_id:device.id,id:'d31-repair-query-'+randomUUID(),type:'root_exec',
  params:{cwd:'/',timeout:30,command},expires_at:Date.now()+600000};
save('request-private.json',request);save('enqueue.json',await api('/api/elfremote/task',request));
let result;
const deadline=Date.now()+150000;
while(Date.now()<deadline){
  const response=await api('/api/elfremote/tasks?device_id='+encodeURIComponent(device.id)+'&task_id='+encodeURIComponent(request.id));
  if(response.task&&['success','failed','rejected','expired'].includes(response.task.state)){
    save('response-private.json',response);
    if(response.task.state!=='success')throw Error('Web查询失败，保留原任务，不重发');
    result=JSON.parse(response.task.result.text);break;
  }
  await new Promise(resolve=>setTimeout(resolve,2000));
}
if(!result)throw Error('查询未完成；保存了原任务号，不能据此判修复失败或重发');
if(result.task_id!==query.request.task_id||result.plan_sha256!==query.request.plan_sha256
   ||result.state?.phase!==query.expected_phase)throw Error('Web与本地事务回读不一致');
save('result.json',{passed:true,transport:'existing-root_exec',queryOnly:true,phase:result.state.phase,
  runtimeEffect:result.runtime_effect,systemConsistency:result.system_consistency});
console.log('Web通过原命令通道取得同一修复事务状态，未重写目标文件。');
