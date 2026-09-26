"""从已发布1.4.5派生完整迁移包1.4.6，逐字核验所有未改业务载荷。"""
import argparse
import copy
import gzip
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

from verify_factory_package import verify_manifest_files, verify_whole_file_footer, verify_whole_file_signature

ROOT = Path(__file__).resolve().parents[3]
FACTORY = Path(__file__).resolve().parent
BASE = ROOT / 'research/d31/dist/factory-flash-v1.4.5'
BASE_HASH = 'E74EFFC90A36EA9C7149532A7FFA7D556A448462324410BE7AA689DAF583CFC1'
INSTALLER = 'META-INF/com/google/android/update-binary'
MANIFEST = 'payload/manifest.json'


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(stream):
    total, result = 0, hashlib.sha256()
    while chunk := stream.read(4 * 1024 * 1024):
        total += len(chunk)
        result.update(chunk)
    return {'bytes': total, 'sha256': result.hexdigest().upper()}


def file_digest(path):
    with path.open('rb') as source:
        return digest(source)


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def save(path, value):
    with path.open('x', encoding='utf-8', newline='\n') as target:
        json.dump(value, target, ensure_ascii=False, indent=2)
        target.write('\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    require(not output.exists(), '输出已存在，拒绝覆盖')
    output.mkdir(parents=True)
    logs = output / 'logs'
    logs.mkdir()

    def run(label, command):
        result = subprocess.run([str(v) for v in command], capture_output=True, timeout=1200)
        (logs / (label + '.txt')).write_bytes(result.stdout + result.stderr)
        require(result.returncode == 0, '命令失败，请查看日志：' + label)
        return result.stdout.decode('utf-8', errors='replace')

    base = BASE / 'D31_SVP3390_Factory_Flash_v1.4.5_testkey.zip'
    require(file_digest(base)['sha256'] == BASE_HASH, '源正式包摘要不符')
    old_report = read(BASE / 'package_verification.json')
    require(old_report['sha256'] == BASE_HASH and old_report['result'] == '通过', '源包验收记录不符')
    ndk = ROOT / '.tools/android-sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/windows-x86_64/bin'
    binary = output / 'update-binary'
    source = FACTORY / 'native/update_binary.c'
    frozen = output / 'source'
    frozen.mkdir()
    for name in ('update_binary.c', 'tls_align.S'):
        shutil.copyfile(FACTORY / 'native' / name, frozen / name)
    run('compile', [ndk / 'aarch64-linux-android23-clang.cmd', '-DD31_PACKAGE_146', '-static', '-Oz',
                   '-fno-ident', '-Wl,--build-id=none', '-Wall', '-Wextra', '-Werror', '-o', binary,
                   frozen / 'update_binary.c', frozen / 'tls_align.S', '-lz'])
    run('strip', [ndk / 'llvm-strip.exe', binary])
    elf = run('readelf', [ndk / 'llvm-readelf.exe', '--file-header', '--program-headers', '--dynamic', binary])
    require(re.search(r'^\s*TLS\s+.*\s0x40\s*$', elf, re.M), 'TLS段未按64字节对齐')
    require('(NEEDED)' not in elf and 'AArch64' in elf, '安装器架构或动态依赖不符')
    require(file_digest(source) == file_digest(frozen / source.name), '编译期间源码发生变化')
    installer = binary.read_bytes()
    unsigned = output / 'D31_SVP3390_Factory_Flash_v1.4.6_unsigned.zip'
    signed = output / 'D31_SVP3390_Factory_Flash_v1.4.6_testkey.zip'
    original = {}
    with zipfile.ZipFile(base) as before, zipfile.ZipFile(unsigned, 'x', compression=zipfile.ZIP_STORED, allowZip64=False) as after:
        require(len(before.namelist()) == len(set(before.namelist())), '源包路径重复')
        manifest = json.loads(before.read(MANIFEST))
        manifest['版本'] = '1.4.6'
        manifest['禁止写入分区'] = [p for p in manifest['禁止写入分区'] if p not in ('boot', 'recovery')]
        for part in ('preloader', 'lk', 'pgpt', 'sgpt'):
            if part not in manifest['禁止写入分区']:
                manifest['禁止写入分区'].append(part)
        manifest['写入分区'] = ['system', 'logo', 'userdata', 'boot', 'recovery']
        manifest['缓存清理'] = '清除cache中的旧系统缓存，保留当前Recovery安装记录；不格式化运行中的cache分区'
        manifest['启动分区策略'] = '写入包内配套boot与Recovery，不要求旧镜像摘要相同；身份和校准分区保留'
        for row in manifest['文件']:
            if row['path'] == INSTALLER:
                row.update(file_digest(binary))
        save(output / 'manifest-v1.4.6.json', manifest)
        for entry in before.infolist():
            name = entry.filename
            if name.startswith('META-INF/') and name not in (INSTALLER, 'META-INF/com/google/android/updater-script'):
                continue
            info = zipfile.ZipInfo(name, entry.date_time)
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = entry.external_attr
            if name == INSTALLER:
                after.writestr(info, installer)
            elif name == MANIFEST:
                after.writestr(info, (output / 'manifest-v1.4.6.json').read_bytes())
            else:
                with before.open(entry) as src, after.open(info, 'w') as dst:
                    checksum, length = hashlib.sha256(), 0
                    while chunk := src.read(4 * 1024 * 1024):
                        dst.write(chunk)
                        checksum.update(chunk)
                        length += len(chunk)
                    original[name] = {'bytes': length, 'sha256': checksum.hexdigest().upper()}
    certificate = ROOT / 'research/h13_root/aosp/testkey.x509.pem'
    conscrypt = FACTORY / 'tools/conscrypt-openjdk-uber-2.5.2.jar'
    require(file_digest(conscrypt)['sha256'] == 'EAF537D98E033D0F0451CD1B8CC74E02D7B55EC882DA63C88060D806BA89C348', '签名依赖摘要不符')
    run('sign', [args.java, '-Xmx2g', '-cp', str(conscrypt) + ';' + str(ROOT / 'research/h13_root/aosp/signapk.jar'),
                 'com.android.signapk.SignApk', '-w', certificate, ROOT / 'research/h13_root/aosp/testkey.pk8', unsigned, signed])
    footer = verify_whole_file_footer(signed)
    signature = verify_whole_file_signature(signed, certificate, Path('C:/Program Files/Git/usr/bin/openssl.exe'), footer)
    checked = {}
    with zipfile.ZipFile(signed) as archive:
        require(archive.testzip() is None, '签名包CRC失败')
        require(len(archive.namelist()) == len(set(archive.namelist())), '签名包路径重复')
        required = set(original) | {INSTALLER, MANIFEST}
        require({p for p in archive.namelist() if not p.startswith('META-INF/')} == {p for p in required if not p.startswith('META-INF/')}, '签名后载荷集合变化')
        for name in sorted(set(original) | {INSTALLER}):
            info = archive.getinfo(name)
            require(info.compress_type == zipfile.ZIP_STORED and not (info.flag_bits & 8), '载荷ZIP格式不符：' + name)
            with archive.open(name) as src:
                actual = digest(src)
            require(actual == (file_digest(binary) if name == INSTALLER else original[name]), '未批准的载荷变化：' + name)
            checked[name] = actual
        require(json.loads(archive.read(MANIFEST)) == manifest, '签名后清单变化')
        with archive.open('payload/system.img.gz') as compressed, gzip.GzipFile(fileobj=compressed) as system:
            checked['payload/system.img.gz -> system.img'] = digest(system)
        verify_manifest_files(manifest, checked, '1.4.6')
    approval = copy.deepcopy(read(BASE / 'approved-package-v1.4.5.json'))
    require(checked['payload/system.img.gz -> system.img']['sha256'] == approval['systemSha256'], '系统原像变化')
    for part in ('boot', 'recovery', 'logo'):
        require(checked['payload/' + part + '.img']['sha256'] == approval[part + 'Sha256'], '配套镜像变化')
    approval.update(version='1.4.6', fileName=signed.name, replaceBootRecovery=True, **file_digest(signed))
    approval['githubUrl'] = 'https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.4.6/' + signed.name
    approval['cloudflareUrl'] = 'https://cdn.elfradio.net/d31/' + signed.name
    for stem in ('sources', 'installed-files'):
        shutil.copyfile(BASE / (stem + '-v1.4.5.json'), output / (stem + '-v1.4.6.json'))
    require(file_digest(output / 'installed-files-v1.4.6.json')['sha256'] == approval['installedFilesSha256'], '系统安装清单变化')
    save(output / 'approved-package-v1.4.6.json', approval)
    save(output / 'package_verification.json', {'result': '通过', **file_digest(signed), 'whole_file_signature': signature,
         'checked_payloads': checked, '原包摘要': BASE_HASH, '实际改动': [INSTALLER, MANIFEST, '整包签名'],
         '业务载荷对照': '所有业务文件和系统原像均与已发布1.4.5相同', '实刷及首次启动': '尚未验收'})
    print(json.dumps(approval, ensure_ascii=False, indent=2), flush=True)


if __name__ == '__main__':
    main()
