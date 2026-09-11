import fs from 'node:fs';
import path from 'node:path';
import {sessionCookie} from './remote-tool-session.mjs';

const [sessionPath,capture,target,slot,deviceName]=process.argv.slice(2);
if(!deviceName?.trim()||/[\0\r\n]/.test(deviceName))throw Error('必须在原参数末尾显式传入目标设备名称deviceName；只读取状态时target和slot传空字符串');
if(!sessionPath||!capture||fs.existsSync(capture))throw Error('需要会话文件和全新捕获目录');
fs.mkdirSync(capture,{recursive:true});
const headers={Cookie:sessionCookie(JSON.parse(fs.readFileSync(sessionPath,'utf8').replace(/^\uFEFF/,'')))};
const until=Date.now()+(target?90000:0);
let sequence=0;
do{
  const response=await fetch('https://v.elfradio.net/api/devices',{headers,signal:AbortSignal.timeout(20000)});
  if(!response.ok)throw Error('HTTP '+response.status);
  const body=await response.json();
  const matches=body.devices.filter(d=>d.name===deviceName&&d.model_id==='mdl_d31');
  if(matches.length!==1)throw Error('目标D31不唯一');
  const device=matches[0];
  fs.writeFileSync(path.join(capture,'device-'+(++sequence)+'-private.json'),JSON.stringify(device,null,2),{flag:'wx'});
  const accounts=device.sip_accounts||[];
  const selected=accounts.find(a=>a.target===target&&a.account_id===slot);
  console.log(JSON.stringify({version:device.app_version,accounts:accounts.map(a=>({target:a.target,slot:a.account_id,configuration:a.configuration_result?.state,state:a.registration?.state,fresh:a.registration?.fresh}))}));
  if(!target)break;
  if(['failed','rejected','expired'].includes(selected?.configuration_result?.state))throw Error('设备配置任务失败');
  if(selected?.configuration_result?.state==='success'&&selected.registration?.state==='registered'&&selected.registration.fresh){
    fs.writeFileSync(path.join(capture,'result.json'),JSON.stringify({target,slot,configured:true,registered:true,task_id:selected.configuration_result.task_id}),{flag:'wx'});
    process.exit(0);
  }
  if(Date.now()>=until)throw Error('尚未取得配置成功及新鲜注册状态');
  await new Promise(resolve=>setTimeout(resolve,5000));
}while(true);
