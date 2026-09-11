#!/usr/bin/env python3
"""仅供离线测试：用隔离文件模拟Android命令及校验入口，不连接任何设备。"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def host(value):
    if os.name == 'nt' and len(value) > 3 and value[0] == '/' and value[2] == '/':
        return value[1] + ':' + value[2:]
    return value


def shell_path(value):
    value = value.replace('\\', '/')
    return '/' + value[0].lower() + value[2:] if len(value) > 2 and value[1] == ':' else value


ROOT = Path(os.environ['MIGRATION_MODEL_ROOT'])
STATE = ROOT / 'model.json'
state = json.loads(STATE.read_text(encoding='utf-8'))
cmd, args = sys.argv[1], sys.argv[2:]


def save():
    STATE.write_text(json.dumps(state, ensure_ascii=False), encoding='utf-8')


def fail(point):
    if state.get('fail') == point and not state.get('failed'):
        state['failed'] = True
        save()
        raise RuntimeError('模型注入失败：' + point)


def file(relative):
    return ROOT / relative.lstrip('/')


def check(a):
    verb = a[0]
    if verb == 'locked-run':
        if state.get('busy'):
            raise RuntimeError('模型维护锁忙')
        result = subprocess.run([os.environ['MIGRATION_MODEL_SH'], shell_path(a[1]), '--locked', *[shell_path(v) for v in a[2:]]])
        return result.returncode
    stage = Path(host(a[2] if verb == 'prepare' else a[1]))
    backup = stage / 'backup'
    fail(verb)
    if verb == 'prepare':
        route = a[1]
        if state.get('candidate_version', 96) <= state.get('installed_version', 93):
            raise RuntimeError('模型版本未递增')
        if not state.get('identity_ok', True):
            raise RuntimeError('模型身份检查拒绝')
        if route == 'replace' and state['system_version'] >= 85:
            raise RuntimeError('模型兼容系统基线保留，使用既有手动接替')
        if route == 'install' and state.get('installed_kind', 'basic') != 'basic':
            raise RuntimeError('模型首次安装不是普通基础')
        backup.mkdir()
        shutil.copy2(file('data/app/original/base.apk'), backup / 'original.apk')
        shutil.copy2(file('system/bin/install-recovery.sh'), backup / 'install-recovery.sh')
        if route == 'replace':
            shutil.copy2(file('system/priv-app/D31ElfRemote/D31ElfRemote.apk'), backup / 'system.apk')
        (backup / 'route').write_text(route + '\n')
        (backup / 'state.json').write_text(json.dumps(state))
        (backup / 'ready').write_text('1\n')
    elif verb == 'quiesce':
        for p in ('state/stop', 'updates/stop-supervisor'):
            dest = file('data/local/d31-remote/runtime/' + p)
            dest.parent.mkdir(parents=True, exist_ok=True)
            if not dest.exists():
                dest.write_text('system-migration\n')
    elif verb == 'install':
        state['installed'] = 'candidate'
        state['enabled'] = 1
        state['components'] = {'BootReceiver': 1}
        if state.get('third_party'):
            state['installed'] = 'third-party'
        save()
    elif verb == 'verify-installed':
        if state['installed'] != 'candidate':
            raise RuntimeError('模型PM安装原件不符')
    elif verb == 'guard-rollback':
        if state['installed'] not in ('old', 'candidate'):
            raise RuntimeError('模型第三方安装禁止覆盖')
    elif verb == 'guard-files':
        if state.get('external_system'):
            raise RuntimeError('模型系统文件已被外部修改')
    elif verb == 'restore-file-metadata':
        if state.get('label_restore_denied'):
            raise RuntimeError('模型标签恢复被拒绝')
        before = json.loads((backup / 'state.json').read_text())
        state['hook_mode'] = before.get('hook_mode', '0750')
        state['hook_label'] = before.get('hook_label', 'u:object_r:system_file:s0')
        state['metadata_restores'] = state.get('metadata_restores', 0) + 1
        save()
    elif verb == 'restore-install':
        state['installed'] = 'old'
        save()
    elif verb == 'restore-state':
        before = json.loads((backup / 'state.json').read_text())
        state['enabled'] = before['enabled']
        state['components'] = before['components']
        save()
    elif verb == 'restore-controls':
        for p in ('state/stop', 'updates/stop-supervisor', 'updates/enabled'):
            f = file('data/local/d31-remote/runtime/' + p)
            before = json.loads((backup / 'state.json').read_text())
            if before.get('stopped'):
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text('original-stop\n')
            elif f.exists():
                f.unlink()
    elif verb == 'verify-restored':
        if state['installed'] != 'old' or state.get('scan_restore_needed'):
            raise RuntimeError('模型PM恢复未确认')
    elif verb in ('verify-system', 'enable-supervision'):
        if not state.get('system_identity', False):
            raise RuntimeError('模型PM特权身份尚未确认')
        if state.get('stopped'):
            raise RuntimeError('模型原停止状态不得自动清除')
    elif verb == 'wait-core':
        if not state.get('mapping_ok', False):
            raise RuntimeError('模型实际加载缺证据')
        state['core_verified'] = True
        save()
    else:
        raise RuntimeError('未知模型校验调用：' + verb)
    return 0


try:
    with (ROOT / 'commands.jsonl').open('a', encoding='utf-8') as log:
        log.write(json.dumps({'command': cmd, 'arguments': args}, ensure_ascii=False) + '\n')
    if cmd == 'app_process':
        code = check(args[2:])
    elif cmd == 'id':
        print('0'); code = 0
    elif cmd == 'getprop':
        print({'ro.build.version.sdk': '23', 'ro.product.device': 'hct6735_66_m0',
               'ro.product.model': 'hct6737t_66_m0'}[args[0]]); code = 0
    elif cmd == 'mount':
        fail('mount-rw' if args[1] == 'remount,rw' else 'mount-ro')
        mode = args[1].split(',')[1]
        root = shell_path(os.environ['MIGRATION_MODEL_POSIX_ROOT'])
        file('proc/mounts').write_text('none ' + root + '/system ext4 ' + mode + ' 0 0\n')
        state['mount'] = mode; save(); code = 0
    elif cmd == 'cp':
        real = [a for a in args if not a.startswith('-')]
        fail('copy')
        shutil.copy2(host(real[0]), host(real[1])); code = 0
    elif cmd in ('chcon', 'chown', 'sync'):
        fail(cmd); code = 0
    else:
        raise RuntimeError('未知模型命令：' + cmd)
except Exception as error:
    print(str(error), file=sys.stderr)
    code = 1
sys.exit(code)
