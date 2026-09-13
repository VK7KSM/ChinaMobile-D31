"""仅复制正式1.4.3原像后回填六项批准内容；逐字回读及元数据核验。"""
from pathlib import Path
import datetime
import hashlib
import json
import shutil
import subprocess

STAGE = Path(__file__).resolve().parent
ROOT = STAGE.parents[3]
SOURCE = ROOT / 'research/d31/analysis/v143-final-zip-audit-20260909-170903/system.img'
SOURCE_HASH = '0E4FF332D2697D539007FB6895D18EC619B1A59772C78AD33FD4893B636C5686'


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest().upper()


def linux(path):
    value = path.resolve().as_posix()
    if not value.startswith('C:/Dev/H13_D22/'):
        raise ValueError('路径不在本工作区')
    return '/mnt/host/c' + value[2:]


def main():
    assert SOURCE.stat().st_size == 1610612736 and sha(SOURCE) == SOURCE_HASH
    entries = json.loads((STAGE / 'system-overlay.json').read_text(encoding='utf-8'))
    for name, item in entries.items():
        path = STAGE / 'system-overlay' / name
        assert path.stat().st_size == item['bytes'] and sha(path) == item['sha256']
    out = STAGE / ('system-image-' + datetime.datetime.now().strftime('%H%M%S'))
    out.mkdir(exist_ok=False)
    image = out / 'system.img'
    shutil.copyfile(SOURCE, image)
    records = []
    def run(*args):
        result = subprocess.run(['wsl.exe', '-d', 'docker-desktop', '--', *args], capture_output=True, timeout=120)
        name = '%03d' % len(records)
        (out / (name + '.stdout.txt')).write_bytes(result.stdout)
        (out / (name + '.stderr.txt')).write_bytes(result.stderr)
        records.append(dict(command=args, exit=result.returncode, output=name))
        (out / 'commands.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
        if result.returncode != 0:
            raise RuntimeError('本地镜像命令失败：' + name)
        return (result.stdout + result.stderr).decode(errors='replace')
    def debug(command, write=False):
        return run('debugfs', *(['-w'] if write else []), '-R', command, linux(image))
    dirs = ['priv-app/D31ElfRemote', 'priv-app/D31ElfRemote/lib', 'priv-app/D31ElfRemote/lib/arm']
    labels = {}
    for item in entries.values():
        label = item['selinux']
        if label not in labels:
            target = out / ('label-' + str(len(labels)) + '.bin')
            target.write_bytes(label.encode('ascii') + b'\0')
            labels[label] = target
    for name in dirs:
        debug('mkdir /' + name, True)
    for name, item in entries.items():
        # 仅两个既有文件需要替换；全部原像保留在SOURCE。
        if name in ('bin/install-recovery.sh', 'vendor/starnet/launcher/config/config-tab'):
            debug('rm /' + name, True)
        debug('write ' + linux(STAGE / 'system-overlay' / name) + ' /' + name, True)
    metadata = {name: dict(mode='0755', uid=0, gid=0, selinux='u:object_r:system_file:s0') for name in dirs}
    metadata.update(entries)
    for name, item in metadata.items():
        mode = ('04' if name in dirs else '010') + item['mode']
        for field, value in [('mode', mode), ('uid', str(item['uid'])), ('gid', str(item['gid']))]:
            debug('set_inode_field /' + name + ' ' + field + ' ' + value, True)
        debug('ea_set -f ' + linux(labels[item['selinux']]) + ' /' + name + ' security.selinux', True)
        info = debug('stat /' + name)
        assert ('0' + item['mode'].lstrip('0')) in info and 'User:     0   Group:     0' in info, name
        label_copy = out / ('label-readback-' + name.replace('/', '_'))
        debug('ea_get -f ' + linux(label_copy) + ' /' + name + ' security.selinux')
        assert label_copy.read_bytes() == labels[item['selinux']].read_bytes(), name
        if name in entries:
            target = out / 'readback' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            debug('dump /' + name + ' ' + linux(target))
            assert target.stat().st_size == item['bytes'] and sha(target) == item['sha256'], name
    missing = debug('stat /priv-app/D31ElfRemote/lib/arm64')
    assert 'File not found' in missing, missing
    run('e2fsck', '-f', '-n', linux(image))
    assert sha(SOURCE) == SOURCE_HASH
    report = dict(status='离线六项回读、ARM32目录、权限及SELinux通过；未实刷', image=str(image.relative_to(ROOT)),
                  bytes=image.stat().st_size, sha256=sha(image), baselineSha256=SOURCE_HASH,
                  files=entries, directories=dirs)
    (out / 'image-verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    (STAGE / 'system-image-candidate.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(dict(image=report['image'], sha256=report['sha256']), ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
