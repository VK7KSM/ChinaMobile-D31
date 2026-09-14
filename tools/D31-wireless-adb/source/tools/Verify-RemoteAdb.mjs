import fs from 'node:fs';
import path from 'node:path';
import {sessionCookie} from './remote-tool-session.mjs';
const [sessionPath,capture,deviceName]=process.argv.slice(2);
if(!deviceName?.trim()||/[\0\r\n]/.test(deviceName))throw Error('必须在原参数末尾显式传入目标设备名称deviceName');
if(!sessionPath||!capture||fs.existsSync(capture))throw Error('需要会话文件和全新ADB核验目录');
const {default:WebSocket}=await import('ws').catch(()=>{throw Error('缺少ws工具依赖，请在tools目录运行npm install --package-lock=false');});
fs.mkdirSync(capture,{recursive:true});
const base='https://v.elfradio.net';
const headers={Cookie:sessionCookie(JSON.parse(fs.readFileSync(sessionPath,'utf8').replace(/^\uFEFF/,''))),Origin:base};
function save(name,value){fs.writeFileSync(path.join(capture,name),JSON.stringify(value,null,2),{flag:'wx'});}
async function api(route,body){
  const r=await fetch(base+route,{headers:{...headers,'Content-Type':'application/json'},method:body?'POST':'GET',body:body?JSON.stringify(body):undefined,redirect:'error',signal:AbortSignal.timeout(20000)});
  const value=await r.json();if(!r.ok)throw Error('HTTP '+r.status);return value;
}
const matches=(await api('/api/devices')).devices.filter(d=>d.name===deviceName);
if(matches.length!==1||matches[0].model_id!=='mdl_d31'||!matches[0].managed_adb_session)throw Error('D31实际ADB会话能力未就绪');
const device=matches[0];save('device-private.json',device);
async function attempt(index){
  const session=await api('/api/elfremote/adb/session',{device_id:device.id});save(index+'-session-private.json',session);
  const socket=new WebSocket(base.replace('https:','wss:')+'/api/elfremote/adb/browser?session_id='+encodeURIComponent(session.session_id),{headers,handshakeTimeout:15000});
  let output='',ready=false,closed=null,failure=null;
  socket.on('error',e=>{failure=e;});
  socket.on('message',raw=>{
    try{const data=JSON.parse(raw.toString());fs.appendFileSync(path.join(capture,index+'-wire.jsonl'),JSON.stringify({at:Date.now(),data})+'\n');
      if(data.type==='ready')ready=true;
      if(data.type==='output')output+=Buffer.from(data.data,'base64').toString('utf8');
      if(data.type==='closed')closed=data;
    }catch(e){failure=e;}
  });
  async function until(predicate,label,timeout=15000){
    const end=Date.now()+timeout;
    while(!predicate()){
      if(failure)throw failure;
      if(closed)throw Error(label+'：'+closed.message);
      if(Date.now()>end)throw Error(label+'超时');
      await new Promise(resolve=>setTimeout(resolve,100));
    }
  }
  function input(text){socket.send(JSON.stringify({type:'input',data:Buffer.from(text).toString('base64')}));}
  try{
    await until(()=>ready,'等待设备终端',65000);
    socket.send(JSON.stringify({type:'resize',rows:28,columns:100}));
    input("id; pwd; printf '%s%s\\n' D31_ADB_ READY\n");
    await until(()=>output.includes('D31_ADB_READY')&&/uid=0\(root\)/.test(output),'真实root回显');
    if(index===1){
      input("printf '%s%s\\n' D31_SLEEP_ BEGIN; sleep 10\n");
      await until(()=>output.includes('D31_SLEEP_BEGIN'),'中断前标记');
      input('\x03');
      input("printf '%s%s\\n' D31_INTERRUPT_ OK\n");
      await until(()=>output.includes('D31_INTERRUPT_OK'),'Ctrl+C中断',7000);
    }
    input('exit\n');
    await until(()=>closed!==null,'正常退出');
    if(closed.exit!==null)throw Error('旧协议退出码应保持未知');
    save(index+'-result.json',{passed:true,root:true,interrupt:index===1,exit:closed.exit});
    console.log('ADB会话'+index+'真实交互与退出通过');
  }finally{
    fs.writeFileSync(path.join(capture,index+'-terminal.txt'),output,{flag:'wx'});
    if(socket.readyState===WebSocket.OPEN)socket.send(JSON.stringify({type:'close'}));
    socket.close();setTimeout(()=>socket.terminate(),1000).unref();
  }
}
await attempt(1);await attempt(2);
save('result.json',{passed:true,sessions:2,real_adb:true});console.log('PRODUCTION_ADB_SESSION_OK');
