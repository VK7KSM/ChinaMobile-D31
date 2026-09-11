import fs from 'node:fs';
import path from 'node:path';
import {sessionCookie} from './remote-tool-session.mjs';
const [sessionPath,capture,deviceName]=process.argv.slice(2);
if(!deviceName?.trim()||/[\0\r\n]/.test(deviceName))throw Error('必须在原参数末尾显式传入目标设备名称deviceName');
if(!sessionPath||!capture||fs.existsSync(capture))throw Error('需要会话文件和全新核验目录');
fs.mkdirSync(capture,{recursive:true});
const session=JSON.parse(fs.readFileSync(sessionPath,'utf8').replace(/^\uFEFF/,''));
for(const route of ['/api/devices','/api/elfremote/releases?channel=d31']) {
  const r=await fetch('https://v.elfradio.net'+route,{headers:{Cookie:sessionCookie(session)},redirect:'error',signal:AbortSignal.timeout(15000)});
  let body=await r.text(),json;try{json=JSON.parse(body);}catch{}
  if(r.ok&&json?.devices)json={...json,devices:json.devices.filter(d=>d.name===deviceName)};
  const result={route,http:r.status,retry_after:r.headers.get('Retry-After'),body:json||body};
  fs.writeFileSync(path.join(capture,route.includes('releases')?'releases.json':'device-private.json'),JSON.stringify(result,null,2),{flag:'wx'});
  console.log(JSON.stringify({route,http:r.status,retry_after:result.retry_after,...(r.ok?{version:json?.devices?.[0]?.app_version,update:json?.devices?.[0]?.update?.state,count:json?.releases?.length}:{error:json||body.slice(0,500)})}));
}
