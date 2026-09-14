import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
const [host,rawPort,capture]=process.argv.slice(2),port=Number(rawPort);
if(!host||host.trim()!==host||/[\s\0]/.test(host)||!/^\d+$/.test(rawPort||'')||!Number.isInteger(port)||port<1||port>65535)
  throw Error('必须显式传入目标主机和1至65535的端口');
if(!capture||fs.existsSync(capture))throw Error('需要全新捕获目录');
fs.mkdirSync(capture,{recursive:true});
const payload=Buffer.from('host::features=shell_v2;\0'),packet=Buffer.alloc(24+payload.length);
packet.writeUInt32LE(0x4e584e43,0);packet.writeUInt32LE(0x01000000,4);packet.writeUInt32LE(4096,8);packet.writeUInt32LE(payload.length,12);
packet.writeUInt32LE(payload.reduce((a,b)=>a+b,0),16);packet.writeUInt32LE((~0x4e584e43)>>>0,20);payload.copy(packet,24);
fs.writeFileSync(path.join(capture,'request.bin'),packet,{flag:'wx'});
const parts=[];
await new Promise((resolve,reject)=>{
  const socket=net.connect({host,port},()=>socket.write(packet));
  socket.setTimeout(5000,()=>socket.destroy());
  socket.on('data',b=>{parts.push(b);if(Buffer.concat(parts).length>=24)socket.end();});
  socket.on('error',reject);socket.on('close',resolve);
});
const response=Buffer.concat(parts);fs.writeFileSync(path.join(capture,'response.bin'),response,{flag:'wx'});
console.log(JSON.stringify({bytes:response.length,command:response.length>=24?response.readUInt32LE(0).toString(16):null}));
