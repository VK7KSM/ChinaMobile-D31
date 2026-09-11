import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const [apkPath, metadataPath, deviceId, outputPath] = process.argv.slice(2);
if (!apkPath || !metadataPath || !deviceId || !outputPath) throw Error('需要APK、实际解析元数据、目标设备和新输出路径');
if (!/^[A-Za-z0-9_-]{1,128}$/.test(deviceId)) throw Error('目标设备无效');
const metadata = JSON.parse(fs.readFileSync(metadataPath, 'utf8').replace(/^\uFEFF/, ''));
const cert = '9b31f89fa50b672ecfe02d73a534cc03f6cf893739aec268f9fe0b71e72da72e';
if (metadata.package !== 'net.elfradio.d31bootstrap' || metadata.certSha256 !== cert
    || !Number.isSafeInteger(metadata.versionCode) || metadata.versionCode <= 0 || !metadata.versionName
    || metadata.remote_full !== true)
  throw Error('实际APK不是原签名D31版本');
const apk = fs.readFileSync(apkPath);
const sha256 = crypto.createHash('sha256').update(apk).digest('hex');
if (!apk.length || apk.length > 64*1024*1024 || metadata.size !== apk.length || metadata.sha256 !== sha256)
  throw Error('元数据与实际APK不一致');
const keyPath = process.env.D31_UPDATE_SIGNING_KEY;
if (!keyPath) throw Error('缺少D31_UPDATE_SIGNING_KEY');
const dir = path.dirname(fileURLToPath(import.meta.url));
const publicKey = fs.readFileSync(path.join(dir, '../app/src/main/resources/update-public.pem'));
const job = 'upd-d31-' + crypto.randomUUID();
const manifest = {package:metadata.package, channel:'d31', model_id:'mdl_d31',
  versionCode:metadata.versionCode, versionName:metadata.versionName, certSha256:cert,
  size:apk.length, sha256, device_id:deviceId, job_id:job, expires_at:Date.now()+3600000,
  url:'https://v.elfradio.net/api/elfremote/apk/'+job};
const manifest_raw = JSON.stringify(manifest);
const signature = crypto.sign('sha256',Buffer.from(manifest_raw),fs.readFileSync(keyPath));
if (!crypto.verify('sha256',Buffer.from(manifest_raw),publicKey,signature)) throw Error('发布签名与内置公钥不符');
fs.writeFileSync(outputPath,JSON.stringify({manifest_raw,signature:signature.toString('hex'),apk_b64:apk.toString('base64')}),{flag:'wx',mode:0o600});
console.log(JSON.stringify({versionCode:manifest.versionCode,size:apk.length,sha256,output:path.resolve(outputPath)}));
