"""从正式1.4.4及已批准194构建独立1.4.5；不连接设备或上传。"""
from pathlib import Path
import argparse
import copy
import datetime
import gzip
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[3]
FACTORY = Path(__file__).resolve().parent
BASE = ROOT / 'research/d31/dist/factory-flash-v1.4.4'
PREPARED = ROOT / 'research/d31/staging/b18-prepared180-20260913/prepared-source'
VARIANTS = ROOT / 'research/d31/staging/b25-variants193-194-20260914/remote-variants.json'
HANDOVER = ROOT / 'research/d31/staging/b15-factory-location-handover-20260913'
ZIP_HASH = '22427BB1171CA778BFE51F3C9B2E1AD6916EF630DA08FF22B1DA4D41D90C9F58'
APK_HASH = '55E70AA54E97B6C42539DB044BC13BCC6A72A4791BFC73FEA9BF45AAD16D93BF'
LIB_HASH = '976AD84FF585EB7121FF7D800A172E60995F634D59A64A7F913E4DAC8907B08E'
JAR_HASH = 'D4513C3108452DD1DBD0C8AFB05BB4F7567BF68E0EEDEF5AD4139FF877FB6E13'
APK_KEY = 'system_files/priv-app/D31ElfRemote/D31ElfRemote.apk'
LIB_KEY = 'system_files/priv-app/D31ElfRemote/lib/arm/libjingle_peerconnection_so.so'
JAR_KEY = 'system_payload/startup-handover.jar'
SEED_KEY = 'system_payload/remote-updates-enabled'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest().upper()


def descriptor(path):
    return [Path(path).stat().st_size, sha(path)]


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def write(path, value):
    with Path(path).open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def linux(path):
    value = Path(path).resolve().as_posix()
    require(value.startswith(ROOT.as_posix() + '/'), '路径必须在工作区内')
    require(not any(c in value for c in '\r\n"'), '路径含非法字符')
    return '/mnt/host/c' + value[2:]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def source_delta(old, new):
    return {name: {'before': old.get(name), 'after': new.get(name)}
            for name in sorted(set(old) | set(new)) if old.get(name) != new.get(name)}


def payload_key(name):
    folder, filename = name.split('/', 1)
    return 'payload/' + {'apks': 'apps', 'runtime': 'runtime', 'system_payload': 'system-patches'}[folder] + '/' + filename


class Build:
    def __init__(self, stage, output):
        self.stage, self.output = stage.resolve(), output.resolve()
        require(not self.stage.exists() and not self.output.exists(), '输出已存在，拒绝覆盖')
        require(self.stage.is_relative_to(ROOT / 'research/d31/staging'), 'stage越界')
        require(self.output == ROOT / 'research/d31/dist/factory-flash-v1.4.5', '正式制品目录不符')
        self.stage.mkdir()
        (self.stage / 'logs').mkdir()
        self.commands = []
        self.inputs = {}

    def run(self, label, command, timeout=180, expected=0):
        index = len(self.commands)
        stem = self.stage / 'logs' / ('%03d-%s' % (index, label))
        command = [str(v) for v in command]
        with Path(str(stem) + '.stdout').open('xb') as out, Path(str(stem) + '.stderr').open('xb') as err:
            result = subprocess.run(command, stdout=out, stderr=err, timeout=timeout, env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
        row = dict(command=command, exit=result.returncode, stdout=str(stem.relative_to(self.stage)) + '.stdout',
                   stderr=str(stem.relative_to(self.stage)) + '.stderr')
        self.commands.append(row)
        write(self.stage / 'logs' / ('%03d.json' % index), row)
        require(result.returncode == expected, '命令失败：' + label + '，见日志')
        return Path(str(stem) + '.stdout').read_bytes(), Path(str(stem) + '.stderr').read_bytes()

    def debug(self, image, command, change=False, absent=False):
        if change:
            require(image == self.stage / 'source/partitions/system.img', '拒绝修改派生源以外的镜像')
        out, err = self.run('debugfs', ['wsl.exe', '-d', 'docker-desktop', '--', 'debugfs'] +
                            (['-w'] if change else []) + ['-R', command, linux(image)])
        diagnostics = [line for line in err.splitlines() if line and not line.startswith(b'debugfs 1.')]
        if absent:
            require(len(diagnostics) == 1 and b'File not found by ext2_lookup' in diagnostics[0], '缺失判定不明确')
        else:
            require(not diagnostics, 'debugfs报告错误，不能只看退出码')
        return out

    def bind(self, path):
        self.inputs[str(path.resolve())] = descriptor(path)

    def prepare(self):
        builder = load('v145_builder', FACTORY / 'build_factory_package.py')
        old = read(FACTORY / 'sources-v1.4.4.json')
        prepared = read(PREPARED / 'sources-next-candidate.json')
        require(len(old) == 52 and len(prepared) == 53, '来源数量变化')
        require(set(source_delta(old, prepared)) == {APK_KEY, JAR_KEY, SEED_KEY}, '准备源存在未批准差异')
        builder.validate_source_members(PREPARED / 'source', prepared)
        for name, expected in prepared.items():
            path = PREPARED / 'source' / name
            require(descriptor(path) == expected, '准备源摘要不符：' + name)
            self.bind(path)
        package = BASE / 'D31_SVP3390_Factory_Flash_v1.4.4_testkey.zip'
        require(sha(package) == ZIP_HASH, '正式1.4.4 ZIP摘要不符')
        self.bind(package)
        self.bind(VARIANTS)
        self.bind(FACTORY / 'sources-v1.4.4.json')
        self.bind(PREPARED / 'sources-next-candidate.json')
        self.bind(PREPARED / 'installed-files-next-candidate.json')
        variants = read(VARIANTS)
        rows = [r for r in variants['artifacts'] if r['artifact'] == 'full' and r['versionCode'] == 194]
        require(len(rows) == 1, '完整194记录不唯一')
        remote = rows[0]
        apk = (VARIANTS.parent / remote['path']).resolve()
        require(apk.is_relative_to(VARIANTS.parent) and descriptor(apk) == [4937276, APK_HASH], '194制品不符')
        require(remote['sha256'].upper() == APK_HASH and remote['remote_full'] is True, '194索引不符')
        self.bind(apk)
        with zipfile.ZipFile(apk) as archive:
            require(len(archive.namelist()) == len(set(archive.namelist())), 'APK成员重复')
            libs = [n for n in archive.namelist() if n.startswith('lib/') and not n.endswith('/')]
            require(libs == ['lib/armeabi-v7a/libjingle_peerconnection_so.so'], 'APK原生库合同变化')
            library = archive.read(libs[0])
            require(len(library) == 6536680 and hashlib.sha256(library).hexdigest().upper() == LIB_HASH, '库摘要不符')
            require(library[:5] == b'\x7fELF\x01' and int.from_bytes(library[18:20], 'little') == 40, '库不是ARM32 ELF')
        require(descriptor(PREPARED / 'source' / JAR_KEY) == [15760, JAR_HASH], 'JAR来源不符')
        require((PREPARED / 'source' / SEED_KEY).read_bytes() == b'system-migration\n', 'enabled种子不符')
        frozen = self.stage / 'factory-snapshot'
        frozen.mkdir()
        for path in FACTORY.rglob('*'):
            if path.is_file() and '__pycache__' not in path.parts and path.suffix in ('.py', '.ps1', '.sh', '.c', '.S', '.json'):
                target = frozen / path.relative_to(FACTORY)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
                self.bind(path)
        source = self.stage / 'source'
        source.mkdir()
        for name in prepared:
            target = source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(PREPARED / 'source' / name, target)
        original_image = self.stage / 'base-system-v1.4.4.img'
        zip_facts = {}
        with zipfile.ZipFile(package) as archive:
            require(len(archive.namelist()) == len(set(archive.namelist())), '正式ZIP成员重复')
            for name, expected in old.items():
                if name.startswith('system_files/') or name == 'partitions/system.img':
                    continue
                entry = 'payload/' + Path(name).name if name.startswith('partitions/') else payload_key(name)
                with archive.open(entry) as stream:
                    digest = hashlib.file_digest(stream, 'sha256').hexdigest().upper()
                actual = [archive.getinfo(entry).file_size, digest]
                require(actual == expected, '正式ZIP与旧来源不一致：' + name)
                zip_facts[entry] = actual
            with archive.open('payload/system.img.gz') as member:
                zipped_hash = hashlib.file_digest(member, 'sha256').hexdigest().upper()
            zip_facts['payload/system.img.gz'] = [archive.getinfo('payload/system.img.gz').file_size, zipped_hash]
            with archive.open('payload/system.img.gz') as member, gzip.GzipFile(fileobj=member) as stream, original_image.open('xb') as target:
                count = 0
                while chunk := stream.read(4 * 1024**2):
                    count += len(chunk)
                    require(count <= builder.SYSTEM_SIZE, '镜像解压超限')
                    target.write(chunk)
        require(descriptor(original_image) == old['partitions/system.img'], '正式镜像与旧摘要不符')
        self.bind(original_image)
        self.original_image = original_image
        write(self.stage / 'base-zip-members.json', zip_facts)
        # 六个镜像内来源必须从正式镜像实际回读，不接受旁清单代替观察。
        observed = {}
        for name, expected in old.items():
            if not name.startswith('system_files/'):
                continue
            relative = name[len('system_files/'):]
            target = self.stage / 'baseline-readback' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            self.debug(original_image, 'dump /%s %s' % (relative, linux(target)))
            require(descriptor(target) == expected, '基线镜像内来源不符：' + name)
            observed[name] = descriptor(target)
        write(self.stage / 'baseline-system-observed.json', observed)
        # 工作镜像字节明确来自本轮ZIP解压，不把历史prepared镜像当新原件。
        shutil.copyfile(original_image, source / 'partitions/system.img')
        shutil.copyfile(apk, source / APK_KEY)
        require((source / LIB_KEY).read_bytes() == library, '旁置库与194 APK不同源')
        self.sources = copy.deepcopy(prepared)
        self.sources[APK_KEY] = descriptor(source / APK_KEY)
        self.installed = read(PREPARED / 'installed-files-next-candidate.json')
        for row in self.installed:
            if row['path'] == '/system/' + APK_KEY[len('system_files/'):]:
                row['sha256'] = APK_HASH
        self.old = old
        write(self.stage / 'source-plan.json', {'正式ZIP': str(package), 'sha256': ZIP_HASH,
              '准备目录': str(PREPARED), '194': remote, '初始化JAR': {'bytes': 15760, 'sha256': JAR_HASH},
              '设备验收依据': '用户本轮确认完整194已三机health验收；本任务不重新采集或扩大验收',
              '计划差异': source_delta(old, self.sources), '尚未完成': '工作镜像尚未写入194'})
        print('正式ZIP、全部准备源、六项镜像来源及194同源库已核对', flush=True)

    def handover_tests(self):
        out = self.stage / 'handover-rebuild'
        out.mkdir()
        names = ['FactoryInit.java', 'HandoverPolicy.java', 'HandoverRuntime.java', 'StartupHandover.java']
        for name in names:
            original = HANDOVER / 'next-handover' / name
            self.bind(original)
            shutil.copyfile(original, out / name)
        location = ROOT / 'research/d31/staging/b15-factory-location-init-20260913'
        (out / 'tests').mkdir()
        for name in ['FactoryLocationInitTest.java', 'HandoverRuntime.java']:
            shutil.copyfile(location / 'tests' / name, out / 'tests' / name)
        tests = load('v145_location_tests', location / 'verify.py')
        tests.STAGE = out
        tests.CANDIDATE = out / 'FactoryInit.java'
        tests.main()
        java = ROOT / '.tools/jdk17/jdk-17.0.20+8/bin'
        (out / 'classes').mkdir()
        (out / 'dex').mkdir()
        self.run('handover-javac', [java / 'javac.exe', '--release', '8', '-encoding', 'UTF-8', '-d', out / 'classes'] + [out / n for n in names])
        self.run('handover-classes', [java / 'jar.exe', 'cf', out / 'classes.jar', '-C', out / 'classes', '.'])
        sdk = Path('C:/Dev/android-sdk')
        self.run('handover-d8', [java / 'java.exe', '-cp', sdk / 'build-tools/34.0.0/lib/d8.jar', 'com.android.tools.r8.D8', '--release',
                               '--min-api', '23', '--lib', sdk / 'platforms/android-34/android.jar', '--output', out / 'dex', out / 'classes.jar'])
        with zipfile.ZipFile(self.stage / 'source' / JAR_KEY) as archive:
            require(archive.read('classes.dex') == (out / 'dex/classes.dex').read_bytes(), '初始化JAR与冻结源码复编DEX不一致')
        write(out / 'verification.json', {'结果': '14项定位回归、无请求拒绝及同源DEX复编通过',
              'jarSha256': JAR_HASH, 'dexSha256': sha(out / 'dex/classes.dex'),
              '边界': '本机PM/Settings桩，不是实机首次初始化验收'})

    def derive_image(self):
        image = self.stage / 'source/partitions/system.img'
        relative = APK_KEY[len('system_files/'):]
        self.debug(image, 'rm /' + relative, change=True)
        self.debug(image, 'write %s /%s' % (linux(self.stage / 'source' / APK_KEY), relative), change=True)
        for field, value in [('mode', '0100644'), ('uid', '0'), ('gid', '0')]:
            self.debug(image, 'set_inode_field /%s %s %s' % (relative, field, value), change=True)
        label = self.stage / 'system-file-label.bin'
        label.write_bytes(b'u:object_r:system_file:s0\0')
        self.debug(image, 'ea_set -f %s /%s security.selinux' % (linux(label), relative), change=True)
        verified = []
        for row in self.installed:
            if not row['path'].startswith('/system/'):
                continue
            relative = row['path'][len('/system/'):]
            key = 'system_files/' + relative
            target = self.stage / 'image-readback' / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            self.debug(image, 'dump /%s %s' % (relative, linux(target)))
            require(descriptor(target) == self.sources[key], '镜像回读不一致：' + key)
            info = self.debug(image, 'stat /' + relative).decode()
            require('Type: regular' in info and re.search(r'Mode:\s+' + row['mode'] + r'\b', info), '文件类型或权限不符')
            require(re.search(r'User:\s+0\s+Group:\s+0\s', info), '属主不符')
            xattr = target.with_name(target.name + '.selinux')
            self.debug(image, 'ea_get -f %s /%s security.selinux' % (linux(xattr), relative))
            require(xattr.read_bytes() == row['selinux'].encode() + b'\0', 'SELinux不符')
            verified.append({'path': row['path'], 'bytes': target.stat().st_size, 'sha256': sha(target), 'mode': row['mode'],
                             'uid': 0, 'gid': 0, 'selinux': row['selinux'], '原始回读': str(target.relative_to(self.stage))})
        self.debug(image, 'stat /priv-app/D31ElfRemote/lib/arm64', absent=True)
        self.run('e2fsck', ['wsl.exe', '-d', 'docker-desktop', '--', 'e2fsck', '-f', '-n', linux(image)])
        self.sources['partitions/system.img'] = descriptor(image)
        require(set(source_delta(self.old, self.sources)) == {APK_KEY, JAR_KEY, SEED_KEY, 'partitions/system.img'}, '最终来源差异超出批准集合')
        write(self.stage / 'image-verification.json', {'结果': '六个登记文件内容、权限、属主及SELinux回读通过', 'files': verified,
              'image': descriptor(image), '全system树': '未逐项校验', '实刷': '未执行'})
        write(self.stage / 'sources-v1.4.5.json', self.sources)
        write(self.stage / 'installed-files-v1.4.5.json', self.installed)
        write(self.stage / 'source-delta-v144-v145.json', source_delta(self.old, self.sources))

    def package(self):
        self.run('unit-tests', [sys.executable, '-B', '-m', 'unittest', 'discover', '-s', str(FACTORY), '-p', 'test_v145.py', '-v'])
        for name in ['test_prepare_next_sources.py', 'test_manifest_contract.py', 'test_source_members.py']:
            self.run(name[:-3], [sys.executable, '-B', FACTORY / name])
        shell = shutil.which('pwsh.exe')
        require(shell is not None, '缺少PowerShell 7')
        self.run('build-and-verify', [shell, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', FACTORY / 'build_factory_package.ps1',
                 '-SourceDirectory', self.stage / 'source', '-OutputDirectory', self.output,
                 '-Version', '1.4.5', '-SourcesManifest', self.stage / 'sources-v1.4.5.json'], timeout=1800)

    def finish(self):
        report = read(self.output / 'package_verification.json')
        require(report['result'] == '通过', '签名包验核未通过')
        require(report['项目APK']['系统elfRemote']['版本号'] == '194', '正式包未验到194')
        for name, expected in self.sources.items():
            require(descriptor(self.stage / 'source' / name) == expected, '构建期间来源变化：' + name)
        for path, expected in self.inputs.items():
            require(descriptor(Path(path)) == expected, '构建期间输入变化：' + path)
        package = self.output / 'D31_SVP3390_Factory_Flash_v1.4.5_testkey.zip'
        installed = self.output / 'installed-files-v1.4.5.json'
        shutil.copyfile(self.stage / 'installed-files-v1.4.5.json', installed)
        shutil.copyfile(self.stage / 'sources-v1.4.5.json', self.output / 'sources-v1.4.5.json')
        shutil.copyfile(self.output / 'staging/payload/manifest.json', self.output / 'manifest-v1.4.5.json')
        approval = {'version': '1.4.5', 'fileName': package.name, 'bytes': package.stat().st_size, 'sha256': sha(package),
                    'installedFilesSha256': sha(installed),
                    'elfRemote': {'package': 'net.elfradio.d31bootstrap', 'systemApk': '/system/priv-app/D31ElfRemote/D31ElfRemote.apk',
                                  'versionCode': 194, 'versionName': '1.34.18-candidate', 'sha256': APK_HASH}}
        for part in ['boot', 'recovery', 'system', 'logo']:
            approval[part + 'Sha256'] = self.sources['partitions/' + part + '.img'][1]
        write(self.output / 'approved-package-v1.4.5.json', approval)
        write(self.stage / 'inputs-unchanged.json', self.inputs)
        write(self.stage / 'commands.json', self.commands)
        write(self.stage / 'delivery.json', {'结果': '离线构建、签名、ZIP及登记载荷检查完成，待主线发布',
              '产物': approval, '来源差异': source_delta(self.old, self.sources),
              '全system树': '未逐项核对', '实刷及新包首次初始化': '未执行', '上传': '未执行，由主线负责'})
        print(json.dumps(approval, ensure_ascii=False, indent=2), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'research/d31/dist/factory-flash-v1.4.5')
    args = parser.parse_args()
    build = Build(args.stage, args.output)
    try:
        build.prepare()
        build.handover_tests()
        build.derive_image()
        build.package()
        build.finish()
    except BaseException as exc:
        write(build.stage / 'incomplete.json', {'状态': '未完成，不能发布', '错误': str(exc),
              '时间': datetime.datetime.now().isoformat(), '日志': 'logs/'})
        raise


if __name__ == '__main__':
    main()
