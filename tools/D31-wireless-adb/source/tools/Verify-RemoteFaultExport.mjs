import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import {randomUUID,createHash} from 'node:crypto';
import {sessionCookie} from './remote-tool-session.mjs';

const [sessionPath,capture,deviceName,version,apk]=process.argv.slice(2);
if(!sessionPath||!capture||fs.existsSync(capture)||!deviceName||!version||!/^\/data\/local\/d31-remote\/releases\/[a-f0-9]{64}\/remote\.apk$/.test(apk||''))
  throw Error('需要会话、全新目录、目标版本和已验证活动APK');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const base='https://v.elfradio.net';
const headers={Cookie:sessionCookie(read(sessionPath)),Origin:base,'Content-Type':'application/json'};
fs.mkdirSync(capture,{recursive:true});
const save=(n,v)=>fs.writeFileSync(path.join(capture,n),JSON.stringify(v,null,2),{flag:'wx'});
async function api(route,body){
  const r=await fetch(base+route,{headers,method:body?'POST':'GET',body:body?JSON.stringify(body):undefined,redirect:'error',signal:AbortSignal.timeout(20000)});
  if(!r.ok)throw Error('Web接口HTTP '+r.status);return r.json();
}
const matches=(await api('/api/devices')).devices.filter(d=>d.name===deviceName&&d.model_id==='mdl_d31');
if(matches.length!==1||!matches[0].ready||matches[0].app_version!==version||!matches[0].managed_file_return)throw Error('D31目标或能力不符');
const device=matches[0];save('device-private.json',device);let sequence=0;
async function task(type,params){
  const label=String(++sequence).padStart(2,'0');
  const request={device_id:device.id,id:'d31-export-'+randomUUID(),type,params,expires_at:Date.now()+600000};
  save(label+'-request-private.json',request);save(label+'-enqueue-private.json',await api('/api/elfremote/task',request));
  for(const until=Date.now()+180000;Date.now()<until;){
    const result=await api('/api/elfremote/tasks?device_id='+encodeURIComponent(device.id)+'&task_id='+request.id);
    if(result.task&&['success','failed','rejected','expired','cancelled'].includes(result.task.state)){
      save(label+'-result-private.json',result);
      if(result.task.state!=='success'||result.task.result?.truncated)throw Error('任务未成功，原编号已保留：'+label);
      console.log('故障验收步骤'+label+' '+type+'通过');return {request,result:result.task.result};
    }
    await new Promise(resolve=>setTimeout(resolve,2500));
  }
  throw Error('任务未取得终态，停止后续步骤，不重放');
}
async function command(args){
  if(args.some(a=>!/^[a-z0-9-]+$/.test(String(a))))throw Error('命令参数不符');
  const {result}=await task('root_exec',{cwd:'/',timeout:60,command:"CLASSPATH='"+apk+"' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.faults.FaultCommand "+args.join(' ')});
  if(result.exit_code!==0)throw Error('故障命令实际失败');return JSON.parse(result.text);
}
let event,cursor='';
for(let page=0;page<128;page++){
  const index=await command(cursor?['index','1',cursor]:['index','1']);
  event=index.events?.find(e=>['ANR','TOMBSTONE'].includes(e.category)&&e.state?.capture==='COMPLETE'&&['COMPLETE','PARTIAL'].includes(e.state?.phase));
  if(event||!index.hasMore)break;
  if(!/^[a-f0-9]{64}$/.test(index.nextAfter)||index.nextAfter<=cursor)throw Error('故障索引分页未推进');cursor=index.nextAfter;
}
if(!event)throw Error('现有事件中没有完整冻结的ANR或TOMBSTONE，不能用合成日志冒充');
const receipt=await command(['export',event.eventId]);save('export-receipt-private.json',receipt);
if(receipt.state!=='EXPORTED'||receipt.eventId!==event.eventId||receipt.bytes<1||receipt.bytes>8388608
  ||!/^[a-f0-9]{64}$/.test(receipt.sha256)||receipt.path!==`/data/local/d31-remote/faults/${event.eventId}/exports/export-${receipt.exportNumber}/bundle.zip`)
  throw Error('导出回执身份或路径不符');
const {request,result}=await task('get_file',{path:receipt.path,allow_cellular:false});
if(result.action!=='uploaded'||result.sha256!==receipt.sha256)throw Error('原件包上传摘要不符');
const route='/api/elfremote/file-return?device_id='+encodeURIComponent(device.id)+'&task_id='+request.id;
save('server-file-private.json',await api(route));
const response=await fetch(base+route+'&download=1',{headers,redirect:'error',signal:AbortSignal.timeout(60000)});
if(!response.ok)throw Error('原件包下载失败');
const bytes=Buffer.from(await response.arrayBuffer()),bundle=path.join(capture,'bundle-private.zip');
fs.writeFileSync(bundle,bytes,{flag:'wx'});
if(bytes.length!==receipt.bytes||createHash('sha256').update(bytes).digest('hex')!==receipt.sha256)throw Error('电脑下载包摘要不符');
const verifiedPath=path.join(capture,'bundle-verification-private.json');
execFileSync('python',[fileURLToPath(new URL('./faults/Verify-FaultExport.py',import.meta.url)),
  '--zip',bundle,'--receipt',path.join(capture,'export-receipt-private.json'),'--event-id',event.eventId,'--output',verifiedPath],{stdio:'pipe'});
const verified=read(verifiedPath);
if(verified.state!=='HOST_PACKAGE_VERIFIED'||verified.eventId!==event.eventId||!verified.sourceLogComplete||verified.rawFiles<1)
  throw Error('原件包仍有源日志缺口，不宣称完整源取回');
const archived=await command(['archive',event.eventId,receipt.sha256,String(receipt.bytes),receipt.manifestSha256]);
save('archive-receipt-private.json',archived);
if(archived.state!=='ARCHIVED'||archived.originalsDeleted!==false||archived.releasedBytes!==0||!archived.activeSlotReleased)throw Error('归档回执不符');
const checked=await command(['query',event.eventId]);save('after-query-private.json',checked);
if(checked.eventId!==event.eventId||checked.export?.archived!==true||checked.export?.state!=='EXPORTED'
  ||checked.export?.receipt?.sha256!==receipt.sha256)throw Error('归档后独立查询未确认同一有效导出包');
save('result.json',{passed:true,category:event.category,files:verified.files,rawFiles:verified.rawFiles,
  bundleBytes:bytes.length,sha256:receipt.sha256,archived:true,originalsDeleted:false,
  sourceLogComplete:verified.sourceLogComplete,sourceLogCompletenessScope:verified.sourceLogCompletenessScope,
  gapCount:verified.gapCount,fullIncidentWindow:false,rootCauseEstablished:false});
console.log('冻结故障原件经生产Web取回、逐项验证和保留原件归档通过；事件窗口缺口另行记录');
