import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {sessionCookie} from './remote-tool-session.mjs';

const [sessionPath,capturePath,deviceName]=process.argv.slice(2);
if(!deviceName?.trim()||/[\0\r\n]/.test(deviceName))throw Error('必须在原参数末尾显式传入目标设备名称deviceName');
if(!sessionPath || !capturePath || fs.existsSync(capturePath)) throw Error('需要会话和全新证据目录');
fs.mkdirSync(capturePath,{recursive:true});
const headers={Cookie:sessionCookie(JSON.parse(fs.readFileSync(sessionPath,'utf8').replace(/^\uFEFF/,''))),Origin:'https://v.elfradio.net','Content-Type':'application/json'};
function save(name,value){fs.writeFileSync(path.join(capturePath,name),JSON.stringify(value,null,2),{flag:'wx'});}
async function api(route,body){
  const response=await fetch('https://v.elfradio.net'+route,{headers,method:body?'POST':'GET',body:body?JSON.stringify(body):undefined,redirect:'error',signal:AbortSignal.timeout(20000)});
  const value=await response.json();
  if(!response.ok)throw Error('接口HTTP '+response.status+' '+JSON.stringify(value));
  return value;
}
const devices=(await api('/api/devices')).devices.filter(d=>d.name===deviceName);
if(devices.length!==1 || devices[0].model_id!=='mdl_d31')throw Error('D31目标不唯一或机型不符');
const device=devices[0];save('before-private.json',device);
if(device.app_version!=='1.15.0-file-operations' || !device.managed_file_operations || !device.ready)throw Error('77版及文件能力尚未就绪');
let sequence=0;
async function task(type,params,expected='success'){
  const label=String(++sequence).padStart(2,'0');
  const request={device_id:device.id,id:'d31-files-'+randomUUID(),type,params,expires_at:Date.now()+600000};
  save(label+'-request-private.json',request);
  save(label+'-enqueue.json',await api('/api/elfremote/task',request));
  const until=Date.now()+150000;
  while(Date.now()<until){
    const response=await api('/api/elfremote/tasks?device_id='+encodeURIComponent(device.id)+'&task_id='+encodeURIComponent(request.id));
    const result=response.task;
    if(result && ['success','failed','rejected','expired'].includes(result.state)){
      save(label+'-result.json',response);
      if(result.state!==expected)throw Error('步骤'+label+'结果不符：'+JSON.stringify(result.result||result));
      console.log('步骤'+label+' '+type+'/'+(params.action||'诊断')+' '+result.state);
      return {request,result};
    }
    await new Promise(resolve=>setTimeout(resolve,2000));
  }
  throw Error('步骤'+label+'尚未取得终态，请查询原任务，不重放');
}
function quote(value){return "'"+value.replaceAll("'","'\\''")+"'";}
const root='/data/local/tmp/d31-file-check-'+Date.now();
save('test-scope.json',{root});
const source=root+'/source',name="引号' 空格$(false).txt";
await task('root_exec',{cwd:'/',timeout:15,command:'set -e\nmkdir '+quote(root)+'\nmkdir '+quote(source)+'\nprintf '+quote('D31 file operations\n原始测试字节\n')+' > '+quote(source+'/'+name)+'\n: > '+quote(source+'/empty')+'\nfor n in 01 02 03 04 05 06 07 08 09 10 11 12 13 14 15 16; do printf x > '+quote(source)+'/file$n; done\nprintf SETUP_OK'});
await task('file_manage',{action:'mkdir',path:root+'/destination'});
const copied=await task('file_manage',{action:'copy',path:source,target:root+'/destination/copy'});
const first=JSON.parse((await task('file_manage',{action:'list',path:source})).result.result.text);
if(first.total!==18 || first.entries.length!==12 || first.next!==12)throw Error('第一页列表不符');
const second=JSON.parse((await task('file_manage',{action:'list',path:source,offset:12})).result.result.text);
if(second.entries.length!==6 || second.next!==-1 || !second.entries.some(e=>e.name===name))throw Error('第二页字面文件名不符');
await task('file_manage',{action:'move',path:root+'/destination/copy',target:root+'/destination/moved'});
const trashed=JSON.parse((await task('file_manage',{action:'trash',path:root+'/destination/moved'})).result.result.text);
if(trashed.restore_to!==root+'/destination/moved' || !trashed.path.startsWith(root+'/destination/.elfremote-trash-'))throw Error('回收路径不符');
await task('file_manage',{action:'move',path:trashed.path,target:trashed.restore_to});
const verification=await task('root_exec',{cwd:root,timeout:15,command:'set -e\nbusybox sha256sum '+quote(source+'/'+name)+' '+quote(root+'/destination/moved/'+name)+'\ntest ! -e '+quote(root+'/destination/copy')+'\nprintf VERIFY_OK'});
const hashes=verification.result.result.text.match(/^[a-f0-9]{64}/gm);
if(hashes?.length!==2 || hashes[0]!==hashes[1])throw Error('文件摘要不一致');
const missing=await task('file_manage',{action:'list',path:root+'/missing'},'failed');
if(!missing.result.result.text.includes('目录不存在或不可读取'))throw Error('不可读目录未明确失败');
const duplicate=await api('/api/elfremote/task',copied.request);save('duplicate.json',duplicate);
if(!duplicate.duplicate)throw Error('服务端同号请求未去重');
const last=JSON.parse((await task('file_manage',{action:'list',path:root+'/destination'})).result.result.text);
if(last.total!==1 || last.entries[0].name!=='moved')throw Error('同号重试重复复制了文件');
save('result.json',{passed:true,tasks:sequence,duplicate:true,sha256:hashes[0],scope:root,cleanup:'测试原件保留，未删除用户文件'});
console.log('REMOTE_FILE_OPERATIONS_OK');
