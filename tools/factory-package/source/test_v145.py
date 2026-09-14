"""1.4.5发布合同的离线回归，不执行刷机。"""
import json
from pathlib import Path
import re
import unittest
import zipfile
import build_v145 as build
from verify_factory_package import verify_app_page


class V145Tests(unittest.TestCase):
    def test_prepared_delta_only_approved(self):
        old = build.read(build.FACTORY / 'sources-v1.4.4.json')
        new = build.read(build.PREPARED / 'sources-next-candidate.json')
        self.assertEqual(set(build.source_delta(old, new)), {build.APK_KEY, build.JAR_KEY, build.SEED_KEY})

    def test_installer_all_payloads_and_metadata(self):
        native = (build.FACTORY / 'native/update_binary.c').read_text(encoding='utf-8')
        rows = re.findall(r'\{"(payload/[^" ]+)", "(/data/[^" ]+)", (0[0-7]+), ([0-9]+), ([0-9]+)\}', native)
        sources = build.read(build.PREPARED / 'sources-next-candidate.json')
        expected = {build.payload_key(n) for n in sources if n.split('/')[0] in ('apks', 'runtime', 'system_payload')}
        self.assertEqual({r[0] for r in rows}, expected)
        self.assertEqual(len(rows), len({r[1] for r in rows}))
        self.assertIn(('payload/system-patches/remote-updates-enabled', '/data/local/d31-remote/runtime/updates/enabled', '0600', '0', '0'), rows)
        mappings = {r[1]: r for r in rows}
        for item in build.read(build.PREPARED / 'installed-files-next-candidate.json'):
            if item['path'].startswith('/system/'):
                continue
            entry, _, mode, uid, gid = mappings[item['path']]
            self.assertEqual((item['mode'], item['uid'], item['gid']), (mode, int(uid), int(gid)))
            key = next(n for n in sources if n.split('/')[0] in ('apks', 'runtime', 'system_payload') and build.payload_key(n) == entry)
            self.assertEqual(item['sha256'], sources[key][1])

    def test_partition_strategy_unchanged(self):
        native = (build.FACTORY / 'native/update_binary.c').read_text(encoding='utf-8')
        body = native.split('int main(', 1)[1]
        first_write = body.index('flash_gzip_system(')
        for entry in ('payload/boot.img', 'payload/recovery.img'):
            self.assertLess(body.index('verify_stored_block(zip_fd, "' + entry), first_write)
        compare = native.split('static int verify_stored_block(', 1)[1].split('static int flash_gzip_system', 1)[0]
        self.assertIn('open(block, O_RDONLY | O_CLOEXEC)', compare)
        self.assertNotIn('write_all(', compare)
        self.assertNotIn('O_WRONLY', compare)
        self.assertIn('flash_logo(zip_fd)', body)
        self.assertNotIn('flash_stored_entry', native)

    def test_app_page_retains_elfremote(self):
        raw = (build.PREPARED / 'source/system_payload/config-tab').read_bytes()
        self.assertEqual(verify_app_page(raw, '1.4.4'), verify_app_page(raw, '1.4.5'))

    def test_194_library_source(self):
        row = next(r for r in build.read(build.VARIANTS)['artifacts'] if r['artifact'] == 'full')
        apk = build.VARIANTS.parent / row['path']
        self.assertEqual(build.descriptor(apk), [4937276, build.APK_HASH])
        with zipfile.ZipFile(apk) as archive:
            libs = [n for n in archive.namelist() if n.startswith('lib/') and not n.endswith('/')]
            self.assertEqual(libs, ['lib/armeabi-v7a/libjingle_peerconnection_so.so'])
            self.assertEqual(archive.read(libs[0]), (build.PREPARED / 'source' / build.LIB_KEY).read_bytes())

    def test_defaults_not_silently_retargeted(self):
        ps = (build.FACTORY / 'build_factory_package.ps1').read_text(encoding='utf-8')
        self.assertIn("[string]$Version = '1.4.4'", ps)
        self.assertIn("if ($Version -eq '1.4.5')", ps)
        self.assertIn('-DD31_PACKAGE_145', ps)


if __name__ == '__main__':
    unittest.main(verbosity=2)
