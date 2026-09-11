import fs from 'node:fs';
import path from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {sessionCookie} from './remote-tool-session.mjs';

const [sessionPath,capture,deviceName,indexPath]=process.argv.slice(2);
if(!sessionPath||!capture||fs.existsSync(capture)||!deviceName||!indexPath)throw Error('需要会话、全新目录、D31名称和真实故障查询回执');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const original=read(indexPath), index=JSON.parse(original.task.result.text), event=index.events?.[0];
if(!indexPath.endsWith('-result-private.json'))throw Error('需要本工具保存的原始查询回执');
const query=read(indexPath.replace(/-result-private\.json$/,'-request-private.json'));
if(original.task.state!=='success'||!event||query.id!==original.task.id)throw Error('没有已确认且绑定原请求的故障索引');
const headers={Cookie:sessionCookie(read(sessionPath)),Origin:'https://v.elfradio.net','Content-Type':'application/json'};
fs.mkdirSync(capture,{recursive:true});
const save=(name,value)=>fs.writeFileSync(path.join(capture,name),JSON.stringify(value,null,2),{flag:'wx'});
async function api(route,body){
  const r=await fetch('https://v.elfradio.net'+route,{headers,method:body?'POST':'GET',body:body?JSON.stringify(body):undefined,redirect:'error',signal:AbortSignal.timeout(20000)});
  if(!r.ok)throw Error('文件接口HTTP '+r.status);return r.json();
}
const matches=(await api('/api/devices')).devices.filter(d=>d.name===deviceName&&d.model_id==='mdl_d31');
if(matches.length!==1||!matches[0].managed_file_return||matches[0].id!==query.device_id)
  throw Error('D31目标或取回能力不符');
const device=matches[0], verified=[];
for(const [label,file] of [['event',event.eventIndex],['report',event.attempts?.[0]?.report]]){
  if(!file||!/^\/data\/local\/d31-remote\/faults\/[a-f0-9]{64}\/(?:attempt-[0-9]+\/)?(?:event|report)\.json$/.test(file.path)
    ||!/^[a-f0-9]{64}$/.test(file.sha256)||!Number.isInteger(file.bytes)||file.bytes<1||file.bytes>65536)
    throw Error('故障原件路径或摘要不符');
  const request={device_id:device.id,id:'d31-fault-return-'+randomUUID(),type:'get_file',params:{path:file.path,allow_cellular:false},expires_at:Date.now()+600000};
  save(label+'-request-private.json',request);save(label+'-enqueue-private.json',await api('/api/elfremote/task',request));
  let completed=false;
  for(const deadline=Date.now()+150000;Date.now()<deadline;){
    const result=await api('/api/elfremote/tasks?device_id='+encodeURIComponent(device.id)+'&task_id='+encodeURIComponent(request.id));
    if(result.task&&['success','failed','rejected','expired'].includes(result.task.state)){
      save(label+'-result-private.json',result);
      if(result.task.state!=='success'||result.task.result?.sha256!==file.sha256)throw Error('原件取回失败，保留任务不重发');
      completed=true;break;
    }
    await new Promise(resolve=>setTimeout(resolve,2000));
  }
  if(!completed)throw Error('原件任务未完成，请查询原编号');
  const route='/api/elfremote/file-return?'+new URLSearchParams({device_id:device.id,task_id:request.id,download:'1'});
  const response=await fetch('https://v.elfradio.net'+route,{headers,redirect:'error',signal:AbortSignal.timeout(20000)});
  if(!response.ok)throw Error('原件下载失败');
  const bytes=Buffer.from(await response.arrayBuffer());
  fs.writeFileSync(path.join(capture,label+'-download-private.json'),bytes,{flag:'wx'});
  if(bytes.length!==file.bytes||createHash('sha256').update(bytes).digest('hex')!==file.sha256)throw Error('下载原件长度或摘要不符');
  verified.push({label,bytes:bytes.length,sha256:file.sha256});
  console.log(label+'故障原件已通过现有文件通道取回并核对摘要');
}
save('result.json',{passed:true,verified,automaticUpload:false,rawFaultLogDownload:false});
