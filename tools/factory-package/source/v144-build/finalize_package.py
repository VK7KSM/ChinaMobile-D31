"""由实算通过的载荷生成旁清单和新版本批准候选，不覆盖旧批准件。"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import zipfile

STAGE = Path(__file__).resolve().parent
ROOT = STAGE.parents[3]
TOOLS = ROOT / 'research/d31/factory_package'
OUT = ROOT / 'research/d31/dist/factory-flash-v1.4.4'


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest().upper()


def create(path, document):
    raw = (json.dumps(document, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    if path.exists():
        if path.read_bytes() != raw:
            raise ValueError('拒绝覆盖不同的冻结旁清单：' + str(path))
    else:
        with path.open('xb') as stream:
            stream.write(raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--installed-only', action='store_true')
    args = parser.parse_args()
    source = ROOT / 'research/d31/analysis/2026-09-13-factory-v1.4.4'
    sources = json.loads((TOOLS / 'sources-v1.4.4.json').read_text(encoding='utf-8'))
    native = (TOOLS / 'native/update_binary.c').read_text(encoding='utf-8')
    entries = re.findall(r'\{"(payload/[^" ]+)", "(/data/[^" ]+)", (0[0-7]+), ([0-9]+), ([0-9]+)\}', native)
    installed = []
    for entry, destination, mode, uid, gid in entries:
        if destination.endswith('/factory-init-required'):
            continue
        name = entry.replace('payload/apps/', 'apks/').replace('payload/runtime/', 'runtime/').replace('payload/system-patches/', 'system_payload/')
        assert sha(source / name) == sources[name][1]
        installed.append(dict(path=destination, sha256=sources[name][1], mode=mode, uid=int(uid), gid=int(gid)))
    overlay = json.loads((STAGE / 'system-overlay.json').read_text(encoding='utf-8'))
    installed.extend({key: entry[key] for key in ('path', 'sha256', 'mode', 'uid', 'gid', 'selinux')} for entry in overlay.values())
    installed.sort(key=lambda item: item['path'])
    assert len({item['path'] for item in installed}) == len(installed)
    installed_path = OUT / 'installed-files-v1.4.4.json'
    create(installed_path, installed)
    if args.installed_only:
        print(str(installed_path) + ' SHA256=' + sha(installed_path))
        return
    verification = json.loads((OUT / 'package_verification.json').read_text(encoding='utf-8'))
    package = OUT / 'D31_SVP3390_Factory_Flash_v1.4.4_testkey.zip'
    assert verification['result'] == '通过' and verification['sha256'] == sha(package)
    assert verification['bytes'] == package.stat().st_size
    with zipfile.ZipFile(package) as archive:
        manifest = json.loads(archive.read('payload/manifest.json'))
        assert manifest['版本'] == '1.4.4'
        create(OUT / 'manifest-v1.4.4.json', manifest)
    approved = dict(version='1.4.4', fileName=package.name, bytes=package.stat().st_size, sha256=sha(package),
                    bootSha256=sources['partitions/boot.img'][1], recoverySha256=sources['partitions/recovery.img'][1],
                    systemSha256=sources['partitions/system.img'][1], logoSha256=sources['partitions/logo.img'][1],
                    installedFilesSha256=sha(installed_path),
                    githubUrl='https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.4.4/' + package.name,
                    cloudflareUrl='https://cdn.elfradio.net/d31/' + package.name,
                    elfRemote=dict(package='net.elfradio.d31bootstrap', systemApk='/system/priv-app/D31ElfRemote/D31ElfRemote.apk',
                                   versionCode=170, versionName='1.34.6-candidate',
                                   sha256=sources['system_files/priv-app/D31ElfRemote/D31ElfRemote.apk'][1]))
    create(OUT / 'approved-package-v1.4.4.json', approved)
    print(json.dumps(approved, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
