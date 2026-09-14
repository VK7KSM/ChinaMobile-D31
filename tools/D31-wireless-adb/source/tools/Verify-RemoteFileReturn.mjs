import fs from 'node:fs';
import path from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {sessionCookie} from './remote-tool-session.mjs';
const [sessionPath,incoming,capture,deviceName]=process.argv.slice(2);
if(!deviceName?.trim()||/[\0\r\n]/.test(deviceName))throw Error('必须在原参数末尾显式传入目标设备名称deviceName');
if(!sessionPath||!incoming||!capture||fs.existsSync(capture))throw Error('需要会话文件、已验收的接收证据和全新取回目录');
const scope=JSON.parse(fs.readFileSync(path.join(incoming,'scope.json'),'utf8'));
const source=fs.readFileSync(path.join(incoming,'source.bin'));
if(!scope.target.startsWith('/data/local/tmp/d31-receive-check-'))throw Error('只允许取回本批合成文件');
fs.mkdirSync(capture,{recursive:true});
const base='https://v.elfradio.net',headers={Cookie:sessionCookie(JSON.parse(fs.readFileSync(sessionPath,'utf8').replace(/^\uFEFF/,''))),Origin:base};
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
function save(name,value){fs.writeFileSync(path.join(capture,name),JSON.stringify(value,null,2),{flag:'wx'});}
async function api(route,body){const r=await fetch(base+route,{headers:{...headers,'Content-Type':'application/json'},method:body?'POST':'GET',body:body?JSON.stringify(body):undefined,redirect:'error',signal:AbortSignal.timeout(20000)});const value=await r.json();if(!r.ok)throw Error('HTTP '+r.status+' '+JSON.stringify(value));return value;}
const matches=(await api('/api/devices')).devices.filter(d=>d.name===deviceName);
if(matches.length!==1||matches[0].model_id!=='mdl_d31'||!matches[0].managed_file_return)throw Error('D31实际取回能力未就绪');
const d=matches[0];save('device-private.json',d);
async function check(file,bytes,label){
  const request={device_id:d.id,type:'get_file',id:'d31-return-'+randomUUID(),params:{path:file,allow_cellular:true},expires_at:Date.now()+600000};
  save(label+'-request-private.json',request);save(label+'-enqueue.json',await api('/api/elfremote/task',request));
  const route='/api/elfremote/file-return?device_id='+encodeURIComponent(d.id)+'&task_id='+encodeURIComponent(request.id);
  let finished=false;const until=Date.now()+240000;
  while(Date.now()<until){
    const value=await api('/api/elfremote/tasks?device_id='+encodeURIComponent(d.id)+'&task_id='+encodeURIComponent(request.id));
    if(value.task&&['success','failed','rejected','expired'].includes(value.task.state)){
      save(label+'-task-result.json',value);
      if(value.task.state!=='success'||value.task.result?.action!=='uploaded'||value.task.result?.sha256!==hash(bytes))throw Error('文件取回结果不符');
      finished=true;break;
    }
    await new Promise(resolve=>setTimeout(resolve,3000));
  }
  if(!finished)throw Error('取回任务尚未完成，保留原编号查询');
  save(label+'-server-file.json',await api(route));
  const r=await fetch(base+route+'&download=1',{headers,redirect:'error',signal:AbortSignal.timeout(120000)});
  if(r.status!==200)throw Error('下载HTTP '+r.status);
  const actual=Buffer.from(await r.arrayBuffer());fs.writeFileSync(path.join(capture,label+'-download.bin'),actual,{flag:'wx'});
  if(!actual.equals(bytes))throw Error('下载文件字节不一致');
  if(bytes.length>8388700){
    const start=8388600,end=start+199;
    const range=await fetch(base+route+'&download=1',{headers:{...headers,Range:'bytes='+start+'-'+end},redirect:'error',signal:AbortSignal.timeout(20000)});
    const partial=Buffer.from(await range.arrayBuffer());
    if(range.status!==206||range.headers.get('Content-Range')!==`bytes ${start}-${end}/${bytes.length}`||!partial.equals(bytes.subarray(start,end+1)))throw Error('跨块Range下载不符');
    fs.writeFileSync(path.join(capture,label+'-range.bin'),partial,{flag:'wx'});
  }
  const duplicate=await api('/api/elfremote/task',request);save(label+'-duplicate.json',duplicate);if(!duplicate.duplicate)throw Error('同号任务未去重');
  console.log(label+' 文件取回及下载校验通过 bytes='+bytes.length);
}
await check(scope.target,source,'large');await check(scope.root+'/empty.bin',Buffer.alloc(0),'empty');
save('result.json',{passed:true,bytes:source.length,sha256:hash(source),empty:true,range:true});
console.log('PRODUCTION_FILE_RETURN_OK');
