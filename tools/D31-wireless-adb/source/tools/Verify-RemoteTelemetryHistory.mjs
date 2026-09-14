import fs from 'node:fs';
import path from 'node:path';
import {sessionCookie} from './remote-tool-session.mjs';

const [sessionPath,capture,deviceName,responsePath,expectedPath]=process.argv.slice(2);
if(!sessionPath||!capture||fs.existsSync(capture)||!deviceName||!responsePath||!expectedPath)
  throw Error('需要会话、全新目录、设备名称与本轮实际回执和采样');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const reportId=read(responsePath).report_id, expected=read(expectedPath).fields;
if(!/^[A-Za-z0-9_.:-]{1,96}$/.test(reportId||''))throw Error('实际报告编号无效');
const headers={Cookie:sessionCookie(read(sessionPath))};
fs.mkdirSync(capture,{recursive:true});
const save=(name,value)=>fs.writeFileSync(path.join(capture,name),JSON.stringify(value,null,2),{flag:'wx'});
async function api(route){
  const r=await fetch('https://v.elfradio.net'+route,{headers,redirect:'error',signal:AbortSignal.timeout(20000)});
  if(!r.ok)throw Error('历史查询HTTP '+r.status);return r.json();
}
const matches=(await api('/api/devices')).devices.filter(d=>d.name===deviceName&&d.model_id==='mdl_d31');
if(matches.length!==1)throw Error('D31目标不唯一');
const query=new URLSearchParams({device_id:matches[0].id,from:new Date(Date.now()-1800000).toISOString(),to:new Date().toISOString(),limit:'500'});
const history=await api('/api/devices/history?'+query);save('history-private.json',history);
const record=history.records?.find(r=>r.report_id===reportId);
if(!record)throw Error('没有找到本轮实际报告编号');
for(const field of ['battery','charging','battery_present','location_reason'])
  if(record[field]!==expected[field])throw Error('历史与设备实读字段不符：'+field);
if(expected.gps===null&&record.location_status==='sampled')throw Error('缺失GPS被误记为实测位置');
save('result.json',{passed:true,fields:['battery','charging','battery_present','location_reason'],
  locationStatus:record.location_status,historyReadOnly:true});
console.log('本轮D31报告已进入轨迹历史，供电字段与实读一致，缺失GPS未冒充精确定位。');
