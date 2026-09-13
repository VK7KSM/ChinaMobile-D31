"""由冻结输入生成1.4.4系统覆盖层，不连接设备、不修改原件。"""
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

STAGE = Path(__file__).resolve().parent
ROOT = STAGE.parents[3]
APK = ROOT / 'research/d31/staging/b15-variants169-170-20260913-1704/build/outputs/apk/full/release/app-full-release.apk'
APK_HASH = '3DA0A647B602163098ECB110DEA881DC519A6B3F15C25797806A5205BF861DF8'
LIB_ENTRY = 'lib/armeabi-v7a/libjingle_peerconnection_so.so'
LIB_HASH = '976AD84FF585EB7121FF7D800A172E60995F634D59A64A7F913E4DAC8907B08E'
LIB_PATH = 'priv-app/D31ElfRemote/lib/arm/libjingle_peerconnection_so.so'


def digest(data):
    return hashlib.sha256(data).hexdigest().upper()


def native_bytes(apk):
    if digest(apk.read_bytes()) != APK_HASH:
        raise ValueError('170 APK摘要不匹配')
    with zipfile.ZipFile(apk) as archive:
        members = [n for n in archive.namelist() if n.startswith('lib/') and not n.endswith('/')]
        if members != [LIB_ENTRY]:
            raise ValueError('170原生库集合不匹配')
        data = archive.read(LIB_ENTRY)
    if len(data) != 6536680 or digest(data) != LIB_HASH or data[:6] != b'\x7fELF\x01\x01' or data[18:20] != b'\x28\x00':
        raise ValueError('170原生库不是冻结ARM32原字节')
    return data


def main():
    data = native_bytes(APK)
    capture = ROOT / 'research/d31/captures/2026-09-13/firmware-backfill-1639'
    out = STAGE / 'system-overlay'
    out.mkdir(exist_ok=False)
    files = {}
    def add(name, value, mode, label='system_file', source=''):
        target = out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(value)
        files[name] = dict(path='/system/' + name, bytes=len(value), sha256=digest(value),
                           mode=mode, uid=0, gid=0, selinux='u:object_r:' + label + ':s0', source=source)
    add('priv-app/D31ElfRemote/D31ElfRemote.apk', APK.read_bytes(), '0644', source=str(APK.relative_to(ROOT)))
    add(LIB_PATH, data, '0644', source=str(APK.relative_to(ROOT)) + '!' + LIB_ENTRY)
    for name, pulled, mode, label in [
        ('bin/install-recovery.sh', 'static-00-install-recovery.sh', '0750', 'install_recovery_exec'),
        ('bin/d31-elfremote-start', 'static-01-d31-elfremote-start', '0755', 'system_file'),
        ('etc/d31-elfremote.system', 'static-02-d31-elfremote.system', '0644', 'system_file'),
    ]:
        source = capture / pulled
        add(name, source.read_bytes(), mode, label, str(source.relative_to(ROOT)))
    add('vendor/starnet/launcher/config/config-tab', (STAGE / 'templates/config-tab').read_bytes(), '0644', source='干净正式模板，仅修改elfRemote标签')
    (STAGE / 'system-overlay.json').write_text(json.dumps(files, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (STAGE / 'installed-system-files-candidate.json').write_text(json.dumps(list(files.values()), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('系统覆盖层已生成：6文件，含冻结ARM32原字节库；尚未写入镜像。')


if __name__ == '__main__':
    main()
