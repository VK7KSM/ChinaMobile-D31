"""1.4.4来源、系统ABI、权限与安装目标的真实制品交叉回归。"""
from pathlib import Path
import json
import re
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET
from prepare_system_overlay import native_bytes, LIB_PATH

STAGE = Path(__file__).resolve().parent
ROOT = STAGE.parents[3]
TOOLS = ROOT / 'research/d31/factory_package'
SOURCE = ROOT / 'research/d31/analysis/2026-09-13-factory-v1.4.4'
sys.path.insert(0, str(TOOLS))
from verify_factory_package import expected_archive_hashes, verify_app_page
from build_factory_package import validate_source_members
INSTALLER = (TOOLS / 'native/update_binary.c').read_text(encoding='utf-8')
APPROVED = json.loads((TOOLS / 'sources-v1.4.4.json').read_text(encoding='utf-8'))


class ReleaseV144Test(unittest.TestCase):
    def test_installer_exact_payload_closure(self):
        actual = set(re.findall(r'\{"(payload/[^" ]+)",', INSTALLER))
        expected = {name for name in expected_archive_hashes(APPROVED) if name.startswith(('payload/apps/', 'payload/system-patches/', 'payload/runtime/'))}
        self.assertEqual(actual, expected)
        validate_source_members(SOURCE, APPROVED)

    def test_original_arm32_library_and_no_data_override(self):
        apk = SOURCE / 'system_files/priv-app/D31ElfRemote/D31ElfRemote.apk'
        self.assertEqual(native_bytes(apk), (SOURCE / 'system_files' / LIB_PATH).read_bytes())
        self.assertNotIn('/data/app/net.elfradio.d31bootstrap-', INSTALLER)
        self.assertFalse(any('Wireless-ADB' in name for name in APPROVED))
        self.assertIn('/system/' + LIB_PATH, INSTALLER)
        self.assertIn('lgetxattr(path, "security.selinux"', INSTALLER)

    def test_boot_and_recovery_read_only_before_system_write(self):
        main = INSTALLER.split('int main(', 1)[1]
        for part in ('boot', 'recovery'):
            self.assertLess(main.index('verify_stored_block(zip_fd, "payload/' + part + '.img"'), main.index('flash_gzip_system('))
        verifier = INSTALLER.split('static int verify_stored_block(', 1)[1].split('static int flash_gzip_system', 1)[0]
        self.assertIn('open(block, O_RDONLY | O_CLOEXEC)', verifier)
        self.assertNotIn('write_all(', verifier)
        self.assertIn('memcmp(expected, actual, count)', verifier)

    def test_requested_runtime_permissions_and_manual_receiver_exist(self):
        apks = {}
        for apk in list((SOURCE / 'apks').glob('*.apk')) + [SOURCE / 'system_files/priv-app/D31ElfRemote/D31ElfRemote.apk']:
            output = subprocess.check_output(['C:/Dev/android-sdk/build-tools/34.0.0/aapt.exe', 'dump', 'badging', str(apk)]).decode('utf-8')
            apks[re.search(r"package: name='([^']+)'", output).group(1)] = output
        for package in ET.parse(SOURCE / 'system_payload/init-runtime-permissions.xml').getroot():
            for permission in package:
                self.assertIn("name='" + permission.get('name') + "'", apks[package.get('name')])
        apk = SOURCE / 'system_files/priv-app/D31ElfRemote/D31ElfRemote.apk'
        manifest = subprocess.check_output(['C:/Dev/android-sdk/build-tools/34.0.0/aapt.exe', 'dump', 'xmltree', str(apk), 'AndroidManifest.xml']).decode('utf-8')
        self.assertIn('net.elfradio.d31bootstrap.RemoteManualReceiver', manifest)

    def test_clean_templates_and_application_page(self):
        verify_app_page((SOURCE / 'system_payload/config-tab').read_bytes(), '1.4.4')
        state = ET.parse(SOURCE / 'system_payload/init-package-restrictions.xml').getroot()
        remote = state.find("pkg[@name='net.elfradio.d31bootstrap']")
        self.assertEqual(remote.get('enabled'), '0')
        disabled = {entry.get('name') for entry in remote.find('disabled-components')}
        self.assertEqual(disabled, {'net.elfradio.d31bootstrap.' + name for name in ('BootReceiver', 'VendorNetworkReceiver', 'UsbBrowseActivity', 'UsbInsertPromptActivity')})
        for name in APPROVED:
            self.assertFalse(set(Path(name).parts) & {'shared_prefs', 'databases', 'accounts', 'identity', 'updates'})
        self.assertEqual((SOURCE / 'system_payload/factory-init-required').read_bytes(), b'1.4.4\n')


if __name__ == '__main__':
    unittest.main(verbosity=2)
