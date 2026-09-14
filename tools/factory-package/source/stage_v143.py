"""从已发布1.4.2派生新目录，仅替换已验收短信APK及对应缓存。"""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
from verify_oat import verify

ROOT = Path(__file__).resolve().parents[3]
TOOLS = Path(__file__).parent
OLD = ROOT / 'research/d31/analysis/2026-09-09-factory-v1.4.2'
OUT = ROOT / 'research/d31/analysis/2026-09-09-factory-v1.4.3'
EVIDENCE = ROOT / 'research/d31/analysis/publish-v143-20260909'
APK = ROOT / 'research/d31_phone_sms/captures/2026-09-09-unified-inbox/D31-Messages-v0.4.0-code7.apk'
ADB = ['C:/Dev/android-sdk/platform-tools/adb.exe', '-P', '5042', '-s', '192.168.2.62:5555']
OAT_PATH = '/data/app/net.elfradio.d31phone.debug-1/oat/arm64/base.odex'

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest().upper()

def device(label, command):
    result = subprocess.run(ADB + command, capture_output=True, timeout=240)
    (EVIDENCE / (label + '-private.stdout')).write_bytes(result.stdout)
    (EVIDENCE / (label + '-private.stderr')).write_bytes(result.stderr)
    result.check_returncode()
    return result.stdout.decode(errors='replace')

EVIDENCE.mkdir(exist_ok=False)
OUT.mkdir(exist_ok=False)
assert sha(APK) == '9B1CED633F782964B1F696329CFD5C8CF6F24CCDA1A61925B7C2ACFB4D9D559F'
assert device('当前APK', ['shell', 'busybox sha256sum /data/app/net.elfradio.d31phone.debug-1/base.apk']).split()[0].upper() == sha(APK)
device('当前版本', ['shell', 'dumpsys package net.elfradio.d31phone.debug'])
cache = EVIDENCE / 'D31-Messages-0.4.0-arm64.odex'
device('拉取缓存', ['pull', OAT_PATH, str(cache)])
assert device('缓存哈希', ['shell', 'busybox sha256sum ' + OAT_PATH]).split()[0].upper() == sha(cache)
oat_check = verify(cache, APK, '/data/app/net.elfradio.d31phone.debug-1/base.apk')
(EVIDENCE / '短信缓存核对.json').write_text(json.dumps(oat_check, ensure_ascii=False, indent=2), encoding='utf-8')
old = json.loads((TOOLS / 'sources-v1.4.2.json').read_text(encoding='utf-8'))
rename = {'apks/D31-Messages-0.3.1.apk': 'apks/D31-Messages-0.4.0.apk',
          'runtime/D31-Messages-0.3.1-arm64.odex': 'runtime/D31-Messages-0.4.0-arm64.odex'}
updated = {}
for name, (size, digest) in old.items():
    original = OLD / name
    assert original.stat().st_size == size and sha(original) == digest, '旧版源文件变化：' + name
    new_name = rename.get(name, name)
    source = APK if name.startswith('apks/D31-Messages-') else cache if name.startswith('runtime/D31-Messages-') else original
    target = OUT / new_name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    updated[new_name] = [target.stat().st_size, sha(target)]
    if new_name == name:
        assert updated[new_name] == old[name]
(TOOLS / 'sources-v1.4.3.json').write_text(json.dumps(updated, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
oats = json.loads((OLD / 'oat-sources.json').read_text())
for item in oats:
    if item['artifact'].startswith('D31-Messages-'):
        item.update(artifact=cache.name, path=OAT_PATH, bytes=cache.stat().st_size, sha256=sha(cache).lower())
(OUT / 'oat-sources.json').write_text(json.dumps(oats, indent=2) + '\n', encoding='utf-8')
(OUT / 'system-live/bin').mkdir(parents=True)
shutil.copy2(OLD / 'system-live/bin/install-recovery.sh', OUT / 'system-live/bin/install-recovery.sh')
(EVIDENCE / '载荷变化.json').write_text(json.dumps({'替换': rename, '其余源文件一致': True, '登记项数': len(updated)}, ensure_ascii=False, indent=2), encoding='utf-8')
print('新载荷准备通过；仅短信APK与缓存变化，登记项数：', len(updated), flush=True)
