import fs from 'node:fs';
import path from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {sessionCookie} from './remote-tool-session.mjs';
const [sessionPath,capture,deviceName]=process.argv.slice(2);
if(!deviceName?.trim()||/[\0\r\n]/.test(deviceName))throw Error('必须在原参数末尾显式传入目标设备名称deviceName');
if(!sessionPath||!capture||fs.existsSync(capture))throw Error('需要会话文件和全新证据目录');
fs.mkdirSync(capture,{recursive:true});
const base='https://v.elfradio.net';
const headers={Cookie:sessionCookie(JSON.parse(fs.readFileSync(sessionPath,'utf8').replace(/^\uFEFF/,''))),Origin:base};
const hash=data=>createHash('sha256').update(data).digest('hex');
const save=(name,data)=>fs.writeFileSync(path.join(capture,name),JSON.stringify(data,null,2),{flag:'wx'});
async function api(route,body,method='POST',binary=false){
  const r=await fetch(base+route,{headers:{...headers,...(body!==undefined?{'Content-Type':binary?'application/octet-stream':'application/json'}:{})},method:body===undefined?'GET':method,body:body===undefined?undefined:binary?body:JSON.stringify(body),redirect:'error',signal:AbortSignal.timeout(120000)});
  const value=await r.json();if(!r.ok)throw Error('HTTP '+r.status+' '+JSON.stringify(value));return value;
}
const matches=(await api('/api/devices')).devices.filter(d=>d.name===deviceName);
if(matches.length!==1||matches[0].model_id!=='mdl_d31'||!matches[0].managed_file_tasks)throw Error('目标或实际文件能力不符');
const d=matches[0];save('before-private.json',d);let seq=0;
async function task(type,params,expected='success'){
  const label=String(++seq).padStart(2,'0'),request={device_id:d.id,type,id:'d31-receive-'+randomUUID(),params,expires_at:Date.now()+600000};
  save(label+'-request-private.json',request);save(label+'-enqueue.json',await api('/api/elfremote/task',request));
  const end=Date.now()+240000;
  while(Date.now()<end){
    const value=await api('/api/elfremote/tasks?device_id='+encodeURIComponent(d.id)+'&task_id='+encodeURIComponent(request.id));
    if(value.task&&['success','failed','rejected','expired'].includes(value.task.state)){
      save(label+'-result.json',value);if(value.task.state!==expected)throw Error('任务结果不符：'+JSON.stringify(value.task));
      console.log('步骤'+label+' '+type+' '+value.task.state);return value.task;
    }
    await new Promise(resolve=>setTimeout(resolve,3000));
  }
  throw Error('任务仍未完成，保留原编号查询，不重放');
}
async function upload(name,bytes){
  const m=(await api('/api/elfremote/files',{device_id:d.id,name,size:bytes.length})).file;
  save('upload-'+m.id+'-init.json',m);
  for(let index=0;index*8388608<bytes.length;index++){
    const part=bytes.subarray(index*8388608,Math.min(bytes.length,(index+1)*8388608));
    await api('/api/elfremote/files/'+m.id+'/parts/'+index+'?sha256='+hash(part),part,'PUT',true);
  }
  const done=await api('/api/elfremote/files/'+m.id+'/complete',{sha256:hash(bytes)});save('upload-'+m.id+'-complete.json',done);
  return m.id;
}
const quote=value=>"'"+value.replaceAll("'","'\\''")+"'";
const root='/data/local/tmp/d31-receive-check-'+Date.now(),target=root+"/文件 ' payload.bin";
save('scope.json',{root,target});
await task('root_exec',{command:'mkdir '+quote(root),cwd:'/',timeout:15});
const payload=Buffer.alloc(16777533);for(let i=0;i<payload.length;i++)payload[i]=(i*31+(i>>>12))&255;
fs.writeFileSync(path.join(capture,'source.bin'),payload,{flag:'wx'});
const transfer=await upload('d31-receive-test.bin',payload);
const received=await task('send_file',{transfer_id:transfer,path:target,overwrite:false});
if(received.result?.sha256!==hash(payload)||received.result?.bytes!==payload.length||received.result?.action!=='committed')throw Error('设备提交回执不符');
const checked=await task('root_exec',{command:'busybox sha256sum '+quote(target),cwd:root,timeout:30});
if(!checked.result.text.startsWith(hash(payload)))throw Error('设备文件实际摘要不符');
const empty=await upload('empty.bin',Buffer.alloc(0));
const zero=await task('send_file',{transfer_id:empty,path:root+'/empty.bin',overwrite:false});
if(zero.result?.bytes!==0||zero.result?.sha256!==hash(Buffer.alloc(0)))throw Error('空文件回执不符');
const conflicting=await upload('must-not-overwrite.bin',Buffer.from('must not replace original'));
await task('send_file',{transfer_id:conflicting,path:target,overwrite:false},'failed');
const preserved=await task('root_exec',{command:'busybox sha256sum '+quote(target)+'\ntest -f '+quote(root+'/empty.bin'),cwd:root,timeout:30});
if(!preserved.result.text.startsWith(hash(payload)))throw Error('拒绝覆盖后原文件发生变化');
save('result.json',{passed:true,bytes:payload.length,sha256:hash(payload),empty:true,conflict_preserved:true,tasks:seq});
console.log('PRODUCTION_FILE_RECEIVE_OK');
