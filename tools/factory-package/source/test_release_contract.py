"""校验发布载荷闭包与不写boot的源代码约束，不代替实刷验证。"""
import json
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET
from build_factory_package import EXPECTED_SOURCES

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT.parent / 'analysis/2026-09-09-factory-v1.4.3'
INSTALLER = (ROOT / 'native/update_binary.c').read_text(encoding='utf-8')


class ReleaseContractTest(unittest.TestCase):
    def test_all_files_have_installer_destination(self):
        entries = set(re.findall(r'\{"(payload/[^\"]+)",', INSTALLER))
        expected = set()
        for name in EXPECTED_SOURCES:
            folder, base = name.split('/', 1)
            if folder == 'partitions':
                continue
            expected.add('payload/' + {'apks': 'apps', 'runtime': 'runtime', 'system_payload': 'system-patches'}[folder] + '/' + base)
        self.assertEqual(expected, entries)

    def test_boot_is_compared_before_first_flash(self):
        body = INSTALLER.split('int main(', 1)[1]
        self.assertLess(body.index('verify_stored_block('), body.index('flash_gzip_system('))
        self.assertNotIn('flash_stored_entry', INSTALLER)
        compare = INSTALLER.split('static int verify_stored_block(', 1)[1].split('static int flash_gzip_system', 1)[0]
        self.assertIn('open(block, O_RDONLY | O_CLOEXEC)', compare)
        self.assertNotIn('write_all(', compare)
        self.assertNotIn('O_WRONLY', compare)
        self.assertIn('memcmp(expected, actual, count)', compare)
        self.assertIn('crc == entry.crc32_value', compare)

    def test_new_private_directories_are_created(self):
        for name in ['d31-startup-handover', 'd31-recovery-entry']:
            self.assertIn('mkdir_recursive("/data/local/' + name + '/runs", 0700)', INSTALLER)
            self.assertIn('chmod("/data/local/' + name + '", 0700)', INSTALLER)

    def test_old_bootstrap_is_not_in_payload(self):
        self.assertIn('apks/D31-Wireless-ADB-1.11.6.apk', EXPECTED_SOURCES)
        self.assertNotIn('1.11.1.apk', INSTALLER)
        self.assertNotIn('1.10.26.apk', INSTALLER)

    def test_no_state_journal_or_user_content_is_shipped(self):
        for path in EXPECTED_SOURCES:
            self.assertFalse(set(Path(path).parts) & {'shared_prefs', 'databases', 'user', 'accounts'})
            self.assertNotIn('pending.properties', path)
            self.assertNotIn('disabled', path)
            self.assertNotIn('latch', path)

    def test_recovery_and_startup_dependencies(self):
        source = SOURCE
        payload = source / 'system_payload'
        persistent = (payload / 'recovery-volume-persistent.sh').read_text()
        self.assertIn(EXPECTED_SOURCES['system_payload/recovery-volume-watch'][1].lower(), persistent)
        self.assertIn(EXPECTED_SOURCES['system_payload/recovery-volume-gate.sh'][1].lower(), persistent)
        self.assertIn('PREVIOUS_REQUEST_SUPPRESS_THIS_BOOT', persistent)
        hook = (source / 'system-live/bin/install-recovery.sh').read_text()
        for entry in ['d31-recovery-entry/persistent.sh', 'd31-system-support/start.sh', 'd31-rescue/start.sh', 'd31-startup-curtain/curtain.jar']:
            self.assertIn(entry, hook)
        rescue = (payload / 'rescue-start.sh').read_text()
        self.assertNotIn('/data/user/', rescue)
        self.assertIn('/data/local/d31-rescue/enabled', rescue)

    def test_first_boot_cannot_start_number_service(self):
        source = SOURCE / 'system_payload'
        state = ET.parse(source / 'init-package-restrictions.xml').getroot()
        number = state.find("pkg[@name='com.starnet.getnumber']")
        self.assertEqual(number.get('enabled'), '0')
        disabled = {item.get('name') for item in number.find('disabled-components')}
        self.assertEqual(disabled, {'com.starnet.getnumber.' + name for name in ['GetNumberService', 'SmsListener', 'BootReceiver', 'MainActivity']})
        self.assertEqual(state.find("pkg[@name='net.elfradio.d31bootstrap']").get('enabled'), '2')
        self.assertNotIn('/data/system/users/0/package-restrictions.xml', INSTALLER)
        self.assertNotIn('/data/system/users/0/runtime-permissions.xml', INSTALLER)
        self.assertIn('/data/local/d31-startup-handover/init-package-restrictions.xml', INSTALLER)
        self.assertIn('/data/local/d31-startup-handover/init-runtime-permissions.xml', INSTALLER)
        self.assertIn('/data/local/d31-startup-handover/factory-init-required', INSTALLER)

    def test_app_page_is_file_manager_and_has_no_accounts(self):
        from verify_factory_package import verify_app_page
        verify_app_page((SOURCE/'system_payload/config-tab').read_bytes())
        self.assertEqual((SOURCE/'system_payload/config-tab').read_bytes(), (ROOT/'templates/config-tab').read_bytes())

    def test_compiler_caches_match_installed_apks(self):
        destinations = dict(re.findall(r'\{"(payload/[^\"]+)", "([^\"]+)"', INSTALLER))
        apk_paths = {p for n,p in destinations.items() if n.startswith('payload/apps/')}
        for entry,path in destinations.items():
            if entry.startswith('payload/runtime/'):
                self.assertIn(path.replace('/oat/arm64/base.odex','/base.apk'), apk_paths)
        self.assertEqual(sum(n.startswith('payload/runtime/') for n in destinations), 8)

    def test_initial_permissions_are_requested_by_shipped_apks(self):
        import subprocess
        apks = {}
        for apk in (SOURCE/'apks').glob('*.apk'):
            data = subprocess.check_output(['C:/Dev/android-sdk/build-tools/34.0.0/aapt.exe','dump','badging',str(apk)]).decode('utf-8')
            package = re.search(r"package: name='([^']+)'",data).group(1)
            apks[package] = data
        permissions = ET.parse(SOURCE/'system_payload/init-runtime-permissions.xml')
        for pkg in permissions.getroot():
            for permission in pkg:
                self.assertIn("name='" + permission.get('name') + "'", apks[pkg.get('name')])


if __name__ == '__main__':
    unittest.main(verbosity=2)
