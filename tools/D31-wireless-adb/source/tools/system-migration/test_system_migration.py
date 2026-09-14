#!/usr/bin/env python3
"""执行生产shell的隔离副本；仅替换绝对根路径并模拟Android命令，保留全部原始证据。"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import unittest

HERE = Path(__file__).resolve().parent
EVIDENCE = None
SHELL = None


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def posix(p):
    s = p.resolve().as_posix()
    return '/' + s[0].lower() + s[2:] if os.name == 'nt' else s


class TransactionTests(unittest.TestCase):
    def setUp(self):
        self.root = EVIDENCE / self._testMethodName
        self.root.mkdir()
        self.stage = self.root / 'data/local/d31-remote/deploy-test'
        for p in ('system/bin', 'system/etc', 'system/priv-app', 'proc', 'bin', 'data/app/original',
                  'data/local/d31-remote/deploy-test'):
            (self.root / p).mkdir(parents=True, exist_ok=True)
        self.old = self.root / 'data/app/original/base.apk'
        self.old.write_bytes(b'original-apk-model')
        (self.root / 'system/bin/install-recovery.sh').write_text('#!/system/bin/sh\necho original-hook\n')
        (self.stage / 'remote.apk').write_bytes(b'candidate-apk-model')
        (self.stage / 'migration-check.jar').write_bytes(b'checker-model')
        (self.stage / 'marker').write_text('1\n')
        (self.stage / 'start.sh').write_text('#!/system/bin/sh\n# net.elfradio.d31bootstrap.RemoteSupervisor\nexit 0\n')
        (self.stage / 'install-recovery.sh').write_text('#!/system/bin/sh\n# new-hook\nexit 0\n')
        self.state = {'installed': 'old', 'enabled': 0, 'components': {'BootReceiver': 2}, 'mount': 'ro'}
        self.save()
        for name in ('install-remote-system.sh', 'replace-remote-system.sh', 'remote-system-transaction.sh'):
            src = HERE / name if name == 'remote-system-transaction.sh' else HERE.parent / name
            content = src.read_text(encoding='utf-8')
            # 生产控制流保持原样，只将受测绝对路径指向隔离目录。
            for prefix in ('/system', '/data/local', '/proc/mounts'):
                content = re.sub(re.escape(prefix) + r'(?=[/"\s])', lambda match: posix(self.root) + match.group(), content)
            content = content.replace('set -eu\n', "set -eux\nPATH='" + posix(self.root / 'bin') + "':/usr/bin:/bin\nexport PATH\n")
            (self.stage / name).write_text(content, encoding='utf-8', newline='\n')
        for command in ('app_process', 'id', 'getprop', 'mount', 'cp', 'chcon', 'chown', 'sync'):
            script = '#!/bin/sh\nexec "$MIGRATION_MODEL_PYTHON" "$MIGRATION_MODEL_COMMANDS" ' + command + ' "$@"\n'
            (self.root / 'bin' / command).write_text(script, newline='\n')
        shutil.copy2(self.root / 'bin/app_process', self.root / 'system/bin/app_process')
        (self.root / 'system/bin/sh').write_text('#!/bin/sh\nexec "$MIGRATION_MODEL_SH" "$@"\n', newline='\n')
        busybox = '#!/bin/sh\ncmd=$1; shift\ncase "$cmd" in timeout) shift; exec "$@";; setsid) exec "$@";; sha256sum|awk) exec /usr/bin/"$cmd" "$@";; *) exit 90;; esac\n'
        (self.root / 'bin/busybox').write_text(busybox, newline='\n')
        shutil.copy2(self.root / 'bin/busybox', self.root / 'system/bin/busybox')
        for folder in (self.root / 'bin', self.root / 'system/bin'):
            for f in folder.iterdir():
                f.chmod(0o755)
        (self.root / 'proc/mounts').write_text('none ' + posix(self.root) + '/system ext4 ro 0 0\n')
        self.env = dict(os.environ, MIGRATION_MODEL_ROOT=str(self.root), MIGRATION_MODEL_POSIX_ROOT=posix(self.root),
                        MIGRATION_MODEL_SH=str(SHELL), MIGRATION_MODEL_PYTHON=sys.executable,
                        MIGRATION_MODEL_COMMANDS=str(HERE / 'model_commands.py'), MSYS2_ARG_CONV_EXCL='*')
        self.calls = 0

    def save(self):
        (self.root / 'model.json').write_text(json.dumps(self.state), encoding='utf-8')

    def load(self):
        self.state = json.loads((self.root / 'model.json').read_text())
        return self.state

    def run_script(self, action='install', expected=None, checker=None):
        self.calls += 1
        anchor = self.root / ('system/priv-app/D31ElfRemote/D31ElfRemote.apk' if action == 'replace' else 'system/bin/install-recovery.sh')
        # rollback/finalize沿用首次预登记摘要，避免拿已改动的钩子伪造原像。
        if not hasattr(self, 'anchor'):
            self.anchor = sha(anchor)
        entry = 'install-remote-system.sh' if action == 'install' else 'replace-remote-system.sh' if action == 'replace' else 'remote-system-transaction.sh'
        args = ([action] if action in ('finalize', 'rollback') else []) + [posix(self.stage), expected or sha(self.stage / 'remote.apk'), self.anchor, checker or sha(self.stage / 'migration-check.jar')]
        env = dict(self.env)
        for key in list(env):
            if key.lower() == 'path':
                del env[key]
        env['PATH'] = posix(self.root / 'bin') + ':/usr/bin:/bin'
        result = subprocess.run([str(SHELL), posix(self.stage / entry), *args], env=env, capture_output=True, timeout=45)
        (self.root / ('stdout-%d.txt' % self.calls)).write_bytes(result.stdout)
        (self.root / ('stderr-%d.txt' % self.calls)).write_bytes(result.stderr)
        (self.root / ('exit-%d.txt' % self.calls)).write_text(str(result.returncode))
        return result.returncode

    def status(self):
        p = self.stage / 'status'
        return p.read_text().strip() if p.exists() else None

    def assert_restored(self):
        self.assertEqual('old', self.load()['installed'])
        self.assertEqual('RESTORED_APK_AND_SETTINGS_RUNTIME_NOT_VERIFIED', self.status())
        self.assertEqual(b'original-apk-model', (self.stage / 'backup/original.apk').read_bytes())
        self.assertEqual('ro', self.state['mount'])
        self.assertEqual({'BootReceiver': 2}, self.state['components'])

    def test_install_staged_not_core_success(self):
        self.assertEqual(0, self.run_script())
        self.assertEqual('SYSTEM_COMPONENT_STAGED_FINALIZE_REQUIRED', self.status())
        self.assertEqual(0, self.load()['enabled'])
        self.assertFalse(self.state.get('core_verified', False))
        self.assertEqual(b'original-apk-model', (self.stage / 'backup/original.apk').read_bytes())

    def test_disabled_state_preserved_on_success(self):
        self.state['enabled'] = 3; self.save()
        self.assertEqual(0, self.run_script())
        self.assertEqual(3, self.load()['enabled'])

    def test_explicit_enabled_state_restored_on_failure(self):
        self.state.update(enabled=1, fail='verify-installed'); self.save()
        self.assertNotEqual(0, self.run_script())
        self.assert_restored(); self.assertEqual(1, self.state['enabled'])

    def test_disabled_state_restored_on_failure(self):
        self.state.update(enabled=2, fail='verify-installed'); self.save()
        self.assertNotEqual(0, self.run_script())
        self.assert_restored(); self.assertEqual(2, self.state['enabled'])

    def test_pm_failure_restores_apk_hook_and_state(self):
        self.state['fail'] = 'install'; self.save()
        self.assertNotEqual(0, self.run_script())
        self.assert_restored()
        self.assertIn('original-hook', (self.root / 'system/bin/install-recovery.sh').read_text())
        self.assertFalse((self.root / 'system/etc/d31-elfremote.system').exists())

    def test_partial_copy_never_publishes_incomplete_system_file(self):
        self.state['partial_copy'] = 'remote.apk'; self.save()
        self.assertNotEqual(0, self.run_script())
        self.assert_restored()
        self.assertFalse((self.root / 'system/priv-app/D31ElfRemote').exists())

    def test_partial_start_copy_restores_originals(self):
        self.state['partial_copy'] = 'start.sh'; self.save()
        self.assertNotEqual(0, self.run_script())
        self.assert_restored()
        self.assertFalse((self.root / 'system/bin/d31-elfremote-start.new').exists())

    def test_partial_marker_copy_restores_originals(self):
        self.state['partial_copy'] = 'marker'; self.save()
        self.assertNotEqual(0, self.run_script())
        self.assert_restored()
        self.assertFalse((self.root / 'system/etc/d31-elfremote.system.new').exists())

    def test_rollback_install_failure_is_attention(self):
        self.assertEqual(0, self.run_script())
        self.load(); self.state['fail'] = 'restore-install'; self.save()
        self.assertNotEqual(0, self.run_script('rollback'))
        self.assertEqual('ATTENTION_ROLLBACK_INCOMPLETE', self.status())
        self.assertEqual('candidate', self.load()['installed'])
        self.assertTrue((self.stage / 'backup/original.apk').is_file())

    def test_third_party_install_not_overwritten(self):
        self.state['third_party'] = True; self.save()
        self.assertNotEqual(0, self.run_script())
        self.assertEqual('third-party', self.load()['installed'])
        self.assertEqual('ATTENTION_ROLLBACK_INCOMPLETE', self.status())

    def test_external_system_change_not_overwritten(self):
        self.assertEqual(0, self.run_script())
        target = self.root / 'system/priv-app/D31ElfRemote/D31ElfRemote.apk'
        target.write_bytes(b'external-change')
        self.load(); self.state['external_system'] = True; self.save()
        self.assertNotEqual(0, self.run_script('rollback'))
        self.assertEqual(b'external-change', target.read_bytes())

    def test_finalize_needs_real_system_identity(self):
        self.assertEqual(0, self.run_script())
        self.assertNotEqual(0, self.run_script('finalize'))
        self.assertEqual('SYSTEM_IDENTITY_NOT_VERIFIED', self.status())
        self.assertFalse(self.load().get('core_verified', False))

    def test_health_without_mapping_cannot_complete(self):
        self.state['system_identity'] = True; self.save()
        self.assertEqual(0, self.run_script())
        self.assertNotEqual(0, self.run_script('finalize'))
        self.assertNotEqual('INSTALLED_SYSTEM_IDENTITY_AND_CORE_MAPPING_VERIFIED', self.status())

    def test_finalize_verified_layers(self):
        self.state.update(system_identity=True, mapping_ok=True); self.save()
        self.assertEqual(0, self.run_script())
        self.assertEqual(0, self.run_script('finalize'))
        self.assertEqual('INSTALLED_SYSTEM_IDENTITY_AND_CORE_MAPPING_VERIFIED', self.status())

    def test_original_stop_not_cleared_to_force_finalize(self):
        self.state.update(stopped=True, system_identity=True, mapping_ok=True); self.save()
        self.assertEqual(0, self.run_script())
        self.assertNotEqual(0, self.run_script('finalize'))

    def legacy(self, version):
        self.state.update(system_version=version, installed_kind='full'); self.save()
        folder = self.root / 'system/priv-app/D31ElfRemote'; folder.mkdir()
        (folder / 'D31ElfRemote.apk').write_bytes(b'original-system-model')
        (self.root / 'system/etc/d31-elfremote.system').write_text('1\n')
        (self.root / 'system/bin/d31-elfremote-start').write_text('original-supervisor')

    def test_system85_not_replaced_for_version_alignment(self):
        self.legacy(85)
        self.assertNotEqual(0, self.run_script('replace'))
        self.assertFalse((self.stage / 'backup').exists())
        self.assertEqual(b'original-system-model', (self.root / 'system/priv-app/D31ElfRemote/D31ElfRemote.apk').read_bytes())

    def test_legacy_replace_restores_system_and_pm(self):
        self.legacy(84)
        self.state['fail'] = 'verify-installed'; self.save()
        self.assertNotEqual(0, self.run_script('replace'))
        self.assert_restored()
        self.assertEqual(b'original-system-model', (self.root / 'system/priv-app/D31ElfRemote/D31ElfRemote.apk').read_bytes())

    def test_hash_mismatch_before_mutation(self):
        self.assertNotEqual(0, self.run_script(expected='0' * 64))
        self.assertFalse((self.stage / 'backup').exists())

    def test_checker_hash_mismatch_before_mutation(self):
        self.assertNotEqual(0, self.run_script(checker='0' * 64))
        self.assertFalse((self.stage / 'backup').exists())

    def test_busy_maintenance_does_not_install(self):
        self.state['busy'] = True; self.save()
        self.assertNotEqual(0, self.run_script())
        self.assertEqual('old', self.load()['installed'])
        self.assertFalse((self.stage / 'backup').exists())

    def test_non_increasing_version_does_not_install(self):
        self.state['candidate_version'] = 92; self.save()
        self.assertNotEqual(0, self.run_script())
        self.assertFalse((self.stage / 'backup').exists())

    def test_identity_rejection_does_not_install(self):
        self.state['identity_ok'] = False; self.save()
        self.assertNotEqual(0, self.run_script())
        self.assertFalse((self.stage / 'backup').exists())

    def test_missing_parameters_rejected(self):
        result = subprocess.run([str(SHELL), posix(self.stage / 'install-remote-system.sh')], capture_output=True, env=self.env)
        self.assertEqual(2, result.returncode)
        self.assertFalse((self.stage / 'backup').exists())

    def test_replay_preserves_original_backup(self):
        self.assertEqual(0, self.run_script())
        before = (self.stage / 'backup/state.json').read_bytes()
        self.assertNotEqual(0, self.run_script())
        self.assertEqual(before, (self.stage / 'backup/state.json').read_bytes())

    def test_hook_0750_kept_after_install_and_rollback(self):
        # Windows不能证明Android标签写入；这里验证shell确实经过属性恢复门两次。
        self.state.update(hook_mode='0750', hook_label='u:object_r:system_file:s0'); self.save()
        self.assertEqual(0, self.run_script())
        self.assertEqual('0750', self.load()['hook_mode'])
        self.assertEqual(0, self.run_script('rollback'))
        self.assert_restored()
        self.assertEqual('0750', self.state['hook_mode'])
        self.assertEqual('u:object_r:system_file:s0', self.state['hook_label'])
        self.assertEqual(2, self.state['metadata_restores'])

    def test_cp_success_label_failure_cannot_claim_restored(self):
        self.state['label_restore_denied'] = True; self.save()
        self.assertNotEqual(0, self.run_script())
        self.assertEqual('ATTENTION_ROLLBACK_INCOMPLETE', self.status())
        self.assertEqual('ro', self.load()['mount'])
        self.assertIn('original-hook', (self.root / 'system/bin/install-recovery.sh').read_text())
        commands = (self.root / 'commands.jsonl').read_text()
        self.assertEqual(2, commands.count('"restore-file-metadata"'))

    def test_existing_hook_temporary_file_not_overwritten(self):
        pending = self.root / 'system/bin/install-recovery.sh.elfremote-new'
        pending.write_bytes(b'other-owner')
        self.assertNotEqual(0, self.run_script())
        self.assertEqual(b'other-owner', pending.read_bytes())
        self.assertFalse((self.stage / 'backup').exists())

    def test_readonly_remount_failure_enters_rollback(self):
        self.state['fail'] = 'mount-ro'; self.save()
        self.assertNotEqual(0, self.run_script())
        self.assert_restored()

    def test_original_readwrite_mount_preserved_after_install_and_rollback(self):
        self.state['mount'] = 'rw'; self.save()
        (self.root / 'proc/mounts').write_text('none ' + posix(self.root) + '/system ext4 rw 0 0\n')
        self.assertEqual(0, self.run_script())
        self.assertEqual('rw', self.load()['mount'])
        self.assertEqual('rw', (self.stage / 'backup/system-mount-mode').read_text().strip())
        self.assertEqual(0, self.run_script('rollback'))
        self.assertEqual('rw', self.load()['mount'])
        self.assertEqual('RESTORED_APK_AND_SETTINGS_RUNTIME_NOT_VERIFIED', self.status())

    def test_unknown_mount_state_rejects_before_backup(self):
        (self.root / 'proc/mounts').write_text('')
        self.assertNotEqual(0, self.run_script())
        self.assertFalse((self.stage / 'backup').exists())

    def test_existing_start_temporary_file_not_overwritten(self):
        pending = self.root / 'system/bin/d31-elfremote-start.new'
        pending.write_bytes(b'other-owner')
        self.assertNotEqual(0, self.run_script())
        self.assertEqual(b'other-owner', pending.read_bytes())
        self.assertFalse((self.stage / 'backup').exists())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--shell', type=Path, required=True)
    parser.add_argument('--evidence-dir', type=Path, required=True)
    args = parser.parse_args()
    SHELL = args.shell.resolve()
    EVIDENCE = args.evidence_dir.resolve(); EVIDENCE.mkdir(parents=True, exist_ok=False)
    syntax = []
    for script in (HERE / 'remote-system-transaction.sh', HERE.parent / 'install-remote-system.sh', HERE.parent / 'replace-remote-system.sh'):
        result = subprocess.run([str(SHELL), '-n', str(script)], capture_output=True)
        syntax.append({'script': str(script), 'sha256': sha(script), 'exit': result.returncode})
        require_ok = result.returncode == 0
        if not require_ok:
            raise SystemExit(result.stderr.decode(errors='replace'))
    (EVIDENCE / 'syntax.json').write_text(json.dumps(syntax, indent=2))
    with (EVIDENCE / 'unittest-raw.txt').open('x', encoding='utf-8') as output:
        result = unittest.TextTestRunner(stream=output, verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(TransactionTests))
    summary = {'tests': result.testsRun, 'failures': len(result.failures), 'errors': len(result.errors),
               'skipped': len(result.skipped), 'modelOnly': True, 'deviceCommandsExecuted': False}
    (EVIDENCE / 'result.json').write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary))
    sys.exit(0 if result.wasSuccessful() else 1)
