import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {sessionCookie} from './remote-tool-session.mjs';
const [sessionPath,capture,mode,deviceName]=process.argv.slice(2);
if(!deviceName?.trim()||/[\0\r\n]/.test(deviceName))throw Error('必须在原参数末尾显式传入目标设备名称deviceName');
if(!sessionPath||!capture||fs.existsSync(capture)||!['busy','restart','stop','verify'].includes(mode))throw Error('需要会话文件、全新目录及明确测试阶段');
const {default:WebSocket}=await import('ws').catch(()=>{throw Error('缺少ws工具依赖，请在tools目录运行npm install --package-lock=false');});
fs.mkdirSync(capture,{recursive:true});
const base='https://v.elfradio.net';
const headers={Cookie:sessionCookie(JSON.parse(fs.readFileSync(sessionPath,'utf8').replace(/^\uFEFF/,''))),Origin:base};
function save(name,value){fs.writeFileSync(path.join(capture,name),JSON.stringify(value,null,2),{flag:'wx'});}
async function api(route,body){const r=await fetch(base+route,{headers:{...headers,'Content-Type':'application/json'},method:body?'POST':'GET',body:body?JSON.stringify(body):undefined,redirect:'error',signal:AbortSignal.timeout(15000)});const value=await r.json();if(!r.ok)throw Error('HTTP '+r.status);return value;}
const matches=(await api('/api/devices')).devices.filter(d=>d.name===deviceName);
// 当前Web公共列表漏出维护能力字段；仅对已验证82版D31按真实任务接口验收。
if(matches.length!==1||matches[0].model_id!=='mdl_d31'||matches[0].app_version!=='1.16.2-adb-recovery')throw Error('D31维护版本未就绪');
const device=matches[0];save('device-private.json',device);
async function task(type,params,label){
  const request={device_id:device.id,type,id:'adb-maint-'+randomUUID(),params,expires_at:Date.now()+180000};
  save(label+'-request.json',request);save(label+'-enqueue.json',await api('/api/elfremote/task',request));
  const end=Date.now()+90000;
  while(Date.now()<end){
    const value=await api('/api/elfremote/tasks?device_id='+encodeURIComponent(device.id)+'&task_id='+encodeURIComponent(request.id));
    if(value.task&&['success','failed','rejected','expired'].includes(value.task.state)){
      save(label+'-result.json',value);if(value.task.state!=='success')throw Error('维护任务未成功');return value.task.result;
    }
    await new Promise(resolve=>setTimeout(resolve,3000));
  }
  throw Error('维护任务超时，保留原编号');
}
if(mode==='busy'){
  const session=await api('/api/elfremote/adb/session',{device_id:device.id});save('session-private.json',session);
  const socket=new WebSocket(base.replace('https:','wss:')+'/api/elfremote/adb/browser?session_id='+encodeURIComponent(session.session_id),{headers,handshakeTimeout:15000});
  try{
    const result=await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(Error('占用提示超时')),20000);
      socket.on('error',e=>{clearTimeout(timer);reject(e);});
      socket.on('message',raw=>{const value=JSON.parse(raw);fs.appendFileSync(path.join(capture,'wire.jsonl'),JSON.stringify(value)+'\n');
        if(value.type==='ready'){clearTimeout(timer);reject(Error('存在电脑连接时不应宣称可用'));}
        if(value.type==='closed'){clearTimeout(timer);resolve(value);}
      });
    });
    save('result.json',result);if(!result.message.includes('占用'))throw Error('没有明确返回占用原因');
  }finally{socket.close();setTimeout(()=>socket.terminate(),1000).unref();}
}else if(mode==='restart'){
  await task('root_exec',{command:'getprop service.adb.tcp.port; getprop persist.adb.tcp.port; getprop sys.usb.config; ps | grep adbd',timeout:10},'before');
  await task('restart_adbd',{},'restart');
  await task('root_exec',{command:'getprop service.adb.tcp.port; getprop persist.adb.tcp.port; getprop sys.usb.config; ps | grep adbd',timeout:10},'after');
}else if(mode==='stop'){
  await task('root_exec',{command:'stop adbd; sleep 1; [ "$(getprop init.svc.adbd)" = stopped ] && echo D31_ADBD_STOPPED',timeout:10},'stopped');
}else{
  const result=await task('root_exec',{command:'getprop service.adb.tcp.port; getprop persist.adb.tcp.port; getprop sys.usb.config; [ "$(getprop init.svc.adbd)" = running ] && echo D31_ADBD_RUNNING; ps | grep -E "d31-rescue|d31-system-support|com.starnet.nexui"',timeout:10},'restored');
  if(!result.text.includes('D31_ADBD_RUNNING'))throw Error('ADB未恢复');
}
console.log('ADB_MAINTENANCE_OK mode='+mode);
