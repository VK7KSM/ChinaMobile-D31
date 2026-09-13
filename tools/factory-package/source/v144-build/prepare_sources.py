"""只从明确来源生成1.4.4干净来源集合，核对短信全部DEX缓存。"""
from pathlib import Path
import hashlib
import json
import re
import shutil
import sys

STAGE = Path(__file__).resolve().parent
ROOT = STAGE.parents[3]
TOOLS = ROOT / 'research/d31/factory_package'
sys.path.insert(0, str(TOOLS))
from verify_oat import verify
from build_factory_package import validate_source_members


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest().upper()


def main():
    out = ROOT / 'research/d31/analysis/2026-09-13-factory-v1.4.4'
    old = ROOT / 'research/d31/analysis/2026-09-09-factory-v1.4.3'
    sources = json.loads((TOOLS / 'sources-v1.4.3.json').read_text(encoding='utf-8'))
    image = json.loads((STAGE / 'system-image-candidate.json').read_text(encoding='utf-8'))
    handover = json.loads((STAGE / 'handover-candidate.json').read_text(encoding='utf-8'))
    messages = json.loads((STAGE / 'inputs/messages.json').read_text(encoding='utf-8'))
    assert messages['versionCode'] == 11 and messages['versionName'] == '0.5.2-dev-debug'
    assert messages['sha256'] == '49300FC626B5493F490013414CBCD0B9FB8DCA31729B2E36DA1328906D75F6EF'
    assert messages['oat']['sha256'] == 'FA193B2648016248453EE13D669DBE321DFDD0F8021409EDD6325CDBF6FE3C0D'
    for path, metadata in [(STAGE / 'inputs/messages.apk', messages), (STAGE / 'inputs/messages-arm64.odex', messages['oat'])]:
        assert path.stat().st_size == metadata['bytes'] and digest(path) == metadata['sha256']
    result = verify(STAGE / 'inputs/messages-arm64.odex', STAGE / 'inputs/messages.apk', '/data/app/net.elfradio.d31phone.debug-1/base.apk')
    (STAGE / 'messages-oat-verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    replacements = {
        'partitions/system.img': ROOT / image['image'],
        'system_payload/startup-handover.jar': Path(handover['path']),
        'system_payload/config-tab': STAGE / 'templates/config-tab',
        'system_payload/init-package-restrictions.xml': STAGE / 'templates/init-package-restrictions.xml',
        'system_payload/init-runtime-permissions.xml': STAGE / 'templates/init-runtime-permissions.xml',
        'system_payload/nexui-v8-ethernet-gate.apk': ROOT / 'research/d31/analysis/2026-09-11-cellular-empty-sip/build-20260911-140409/nexui-cellular-empty-sip.apk',
    }
    assert digest(replacements['partitions/system.img']) == image['sha256']
    assert digest(replacements['system_payload/startup-handover.jar']) == handover['sha256']
    assert digest(replacements['system_payload/nexui-v8-ethernet-gate.apk']) == '7CDF23A34CBFC008E7A00E4D1255640E93DD0233AC7A44148DD21E85F0C9CD4E'
    additions = {
        'apks/D31-Messages.apk': STAGE / 'inputs/messages.apk',
        'runtime/D31-Messages-arm64.odex': STAGE / 'inputs/messages-arm64.odex',
        'apks/D31-System-Support-1.1.0.apk': ROOT / 'research/d31/analysis/2026-09-09-notification-integration/final-system-support-1.1.0/D31-SystemSupport-1.1.0.apk',
        'partitions/recovery.img': ROOT / 'research/d31/captures/2026-08-30/20260830_205000-d31-factory-package-source/partitions/recovery.img',
    }
    assert digest(additions['apks/D31-System-Support-1.1.0.apk']) == '1A0DFB6F65A862875A6BE68B768CB38AD89978C83202AD7ECE3890CC79EDF79E'
    assert digest(additions['partitions/recovery.img']) == '173CB00459E4CDFC2B4BF04D7BED4A130947795F8ACB3B557BBEF2C218B2E7D5'
    removed = {'apks/D31-Wireless-ADB-1.11.6.apk', 'apks/D31-Messages-0.4.0.apk',
               'runtime/D31-Messages-0.4.0-arm64.odex', 'apks/D31-System-Support-1.0.4.apk'}
    assert removed <= sources.keys()
    overlay = json.loads((STAGE / 'system-overlay.json').read_text(encoding='utf-8'))
    additions.update({'system_files/' + name: STAGE / 'system-overlay' / name for name in overlay})
    out.mkdir(exist_ok=False)
    approved, origins = {}, {}
    for name in sorted((sources.keys() - removed) | additions.keys()):
        source = additions.get(name, replacements.get(name, old / name))
        if name == 'system_payload/factory-init-required':
            target = out / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b'1.4.4\n')
            origins[name] = '干净1.4.4待初始化标记'
        else:
            if name not in additions and name not in replacements:
                assert source.stat().st_size == sources[name][0] and digest(source) == sources[name][1], name
            target = out / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            assert digest(target) == digest(source), name
            origins[name] = str(source.relative_to(ROOT))
        approved[name] = [target.stat().st_size, digest(target)]
    validate_source_members(out, approved)
    manifest = TOOLS / 'sources-v1.4.4.json'
    with manifest.open('x', encoding='utf-8') as stream:
        json.dump(approved, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    (STAGE / 'source-origins.json').write_text(json.dumps(origins, ensure_ascii=False, indent=2), encoding='utf-8')
    # 安装器已有目的地合同也是全部预置OAT核对的唯一来源。
    native = (TOOLS / 'native/update_binary.c').read_text(encoding='utf-8')
    pairs = re.findall(r'\{"(payload/[^" ]+)", "(/data/[^" ]+)"', native)
    mapped = {destination: entry.replace('payload/apps/', 'apks/').replace('payload/runtime/', 'runtime/').replace('payload/system-patches/', 'system_payload/') for entry, destination in pairs}
    oats = []
    for destination, name in mapped.items():
        if name.startswith('runtime/'):
            apk_destination = destination.replace('/oat/arm64/base.odex', '/base.apk')
            oats.append(verify(out / name, out / mapped[apk_destination], apk_destination))
    (STAGE / 'all-oat-verification.json').write_text(json.dumps(oats, ensure_ascii=False, indent=2), encoding='utf-8')
    print('1.4.4来源已生成，批准文件=' + str(len(approved)) + '，全部OAT核验=' + str(len(oats)))


if __name__ == '__main__':
    main()
