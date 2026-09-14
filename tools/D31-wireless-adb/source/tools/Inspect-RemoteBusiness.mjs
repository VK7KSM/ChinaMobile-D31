import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {WebTransport} from './fault-transfer/WebTransport.mjs';
import {captureHttp} from './Verify-RemoteMediaModes.mjs';

const [sessionFile, directory, deviceName] = process.argv.slice(2);
assert.ok(sessionFile && directory && deviceName && !fs.existsSync(directory));
fs.mkdirSync(directory, {recursive: true});
const transport = new WebTransport({session: JSON.parse(fs.readFileSync(sessionFile, 'utf8').replace(/^\uFEFF/, ''))});
const response = await captureHttp({transport, route: '/api/devices', capture: directory, label: 'devices'});
assert.equal(response.status, 200);
const matches = response.value.devices.filter(d => d.name === deviceName && d.model_id === 'mdl_d31');
assert.equal(matches.length, 1);
const device = matches[0];
fs.writeFileSync(path.join(directory, 'device-private.json'), JSON.stringify(device, null, 2), {flag: 'wx', mode: 0o600});
const summary = {
  version: device.app_version, ready: device.ready, network: device.network,
  mediaModes: device.managed_media_modes, cameras: device.media_cameras,
  locationSource: device.loc?.source, locationAccuracyMetres: device.loc?.acc_m,
  locationReason: device.location_reason, networkLocationReason: device.network_location_reason,
  radioFieldExposed: Object.hasOwn(device, 'radio'), radioWifiCount: device.radio?.wifiAccessPoints?.length,
  radioCellCount: device.radio?.cellTowers?.length
};
fs.writeFileSync(path.join(directory, 'summary.json'), JSON.stringify(summary, null, 2), {flag: 'wx'});
console.log(JSON.stringify(summary));
