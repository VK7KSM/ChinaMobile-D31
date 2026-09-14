import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {sessionCookie} from './remote-tool-session.mjs';

// 复用已完成的诊断请求验证生产任务通道，不安装载荷，不重新采集系统。
const [sessionPath,capture,deviceName,probeRequestPath]=process.argv.slice(2);
if(!sessionPath||!capture||!deviceName?.trim()||!probeRequestPath||fs.existsSync(capture))
  throw Error('必须显式指定会话、全新捕获目录、设备名称和已执行的诊断请求');
const prior=JSON.parse(fs.readFileSync(probeRequestPath,'utf8').replace(/^\uFEFF/,''));
if(typeof prior.command!=='string'||!prior.command.startsWith('CLASSPATH=')
    ||!prior.command.includes(' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteDiagnosticCommand '))
  throw Error('输入不是明确的诊断请求');
fs.mkdirSync(capture,{recursive:true});
const headers={Cookie:sessionCookie(JSON.parse(fs.readFileSync(sessionPath,'utf8').replace(/^\uFEFF/,''))),
  Origin:'https://v.elfradio.net','Content-Type':'application/json'};
const save=(name,data)=>fs.writeFileSync(path.join(capture,name),JSON.stringify(data,null,2),{flag:'wx'});
async function api(route,body){
  const response=await fetch('https://v.elfradio.net'+route,{headers,method:body?'POST':'GET',
    body:body?JSON.stringify(body):undefined,redirect:'error',signal:AbortSignal.timeout(20000)});
  if(!response.ok)throw Error('接口HTTP '+response.status);
  return response.json();
}
const matches=(await api('/api/devices')).devices.filter(d=>d.name===deviceName&&d.model_id==='mdl_d31');
if(matches.length!==1||!matches[0].ready)throw Error('目标D31不唯一或尚未就绪');
const device=matches[0];save('before-private.json',device);
async function task(label,command){
  const request={device_id:device.id,id:'d31-diagnostic-'+randomUUID(),type:'root_exec',
    params:{cwd:'/',timeout:120,command},expires_at:Date.now()+600000};
  save(label+'-request-private.json',request);
  save(label+'-enqueue.json',await api('/api/elfremote/task',request));
  const deadline=Date.now()+150000;
  while(Date.now()<deadline){
    const response=await api('/api/elfremote/tasks?device_id='+encodeURIComponent(device.id)+'&task_id='+encodeURIComponent(request.id));
    if(response.task&&['success','failed','rejected','expired'].includes(response.task.state)){
      save(label+'-result-private.json',response);
      if(response.task.state!=='success')throw Error('诊断Web任务未成功，保留原回执');
      return response.task.result.text;
    }
    await new Promise(resolve=>setTimeout(resolve,2000));
  }
  throw Error('任务结果超时，查询原任务，不自动重发');
}
const receipt=JSON.parse(await task('cached-report',prior.command));
if(receipt.state!=='completed'||!/^\/data\/local\/d31-remote\/diagnostics\/[a-f0-9]{64}\/report\.json$/.test(receipt.path)
  ||! /^[a-f0-9]{64}$/.test(receipt.sha256))throw Error('诊断回执格式不符');
const output=await task('report-readback',"/system/bin/busybox sha256sum '"+receipt.path+"'");
if(output.trim().split(/\s+/)[0]!==receipt.sha256)throw Error('云任务回读报告摘要不同');
save('result.json',{passed:true,transport:'existing-root_exec',cachedReport:true,reportSha256:receipt.sha256,
  newApkInstalled:false,newCloudCapability:false,systemConsistency:'NOT_ASSESSED'});
console.log('现有Web任务通道返回原诊断报告回执，云命令回读摘要一致；未安装候选或新增服务器能力。');
