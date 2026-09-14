#!/usr/bin/env python3
"""保留合成输入和逐项原始结果；不调用设备、网络或安装器。"""

import argparse
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import stat
import subprocess
import sys
import time
import unittest
import warnings
import zipfile

import generate_manifest as gm

EVIDENCE = None


def h(data):
    return hashlib.sha256(data).hexdigest()


def write_json(p, value):
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


class FirmwareTests(unittest.TestCase):
    def setUp(self):
        self.root = EVIDENCE / self._testMethodName
        self.root.mkdir()
        self.package = self.root / 'package'
        self.package.mkdir()
        (self.package / 'payload').mkdir()
        self.payload = {'payload/app.apk': b'package-content\x00\xff',
                        'payload/system.img.gz': gzip.compress(b'system-image' * 1024, mtime=0),
                        'payload/boot.img': b'boot-check-only'}
        self.catalog = {'original/app.apk': [len(self.payload['payload/app.apk']), h(self.payload['payload/app.apk'])],
                        'original/system.img': [len(b'system-image' * 1024), h(b'system-image' * 1024)],
                        'original/boot.img': [len(self.payload['payload/boot.img']), h(self.payload['payload/boot.img'])]}
        self.source_list = self.root / 'sources.json'
        write_json(self.source_list, self.catalog)
        self.installer = self.root / 'installer.c'
        self.installer.write_bytes(b'/* synthetic explicit mapping fixture; never executed */')
        self.inventory = {'文件': []}
        for name, data in self.payload.items():
            (self.package / name).write_bytes(data)
            item = {'path': name, 'bytes': len(data), 'sha256': h(data)}
            if name.endswith('.gz'):
                item.update({'uncompressed_bytes': self.catalog['original/system.img'][0],
                             'source_sha256': self.catalog['original/system.img'][1]})
            self.inventory['文件'].append(item)
        self.inventory_path = self.package / 'payload/manifest.json'
        write_json(self.inventory_path, self.inventory)
        self.mapping = {
            'schemaVersion': 1, 'firmwareId': 'synthetic-firmware', 'build': 'synthetic-build',
            'context': {k: 'synthetic-not-a-board' for k in gm.CONTEXT},
            'scope': ['/data', '/dev/block'],
            'bindings': {'installerSourceSha256': h(self.installer.read_bytes()),
                         'sourceListSha256': h(self.source_list.read_bytes()), 'packageSha256': '0' * 64},
            'inventory': {'path': 'payload/manifest.json', 'sha256': h(self.inventory_path.read_bytes())},
            'originRoots': {'payload': 'original'}, 'ignoredSources': {}, 'optionalMembers': {},
            'entries': [
                {'action': 'copy', 'source': 'payload/app.apk', 'destination': '/data/app/example/base.apk',
                 'mode': '0644', 'uid': 1000, 'gid': 1000},
                {'action': 'gzip-block', 'source': 'payload/system.img.gz', 'destination': '/dev/block/system'},
                {'action': 'verify-only', 'source': 'payload/boot.img', 'destination': '/dev/block/boot'},
                {'action': 'generated', 'destination': '/data/system/packages.xml', 'reason': '运行生成未检查'},
                {'action': 'migration', 'destination': '/data/system/users/0/runtime-permissions.xml', 'reason': '迁移未检查'},
                {'action': 'not-checked', 'destination': '/dev/block/recovery', 'reason': '未读取分区'}]}
        self.mapping_path = self.root / 'mapping.json'

    def generate(self, source=None):
        write_json(self.mapping_path, self.mapping)
        return gm.generate(source or self.package, self.mapping_path, self.installer, self.source_list,
                           'synthetic-unfrozen', 'test-1', int(time.time() * 1000) + 3600000)

    def rejected(self, reason, source=None):
        with self.assertRaisesRegex(gm.Rejected, reason) as caught:
            self.generate(source)
        (self.root / 'rejection.txt').write_text(str(caught.exception), encoding='utf-8')

    def make_zip(self, extra=()):
        result = self.root / 'input.zip'
        with zipfile.ZipFile(result, 'w', compression=zipfile.ZIP_DEFLATED) as z:
            for p in self.package.rglob('*'):
                if p.is_file():
                    z.write(p, p.relative_to(self.package).as_posix())
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                for name, data in extra:
                    z.writestr(name, data)
        self.mapping['bindings']['packageSha256'] = h(result.read_bytes())
        return result

    def test_directory_contract_and_unknowns(self):
        manifest, report = self.generate()
        self.assertEqual('FIRMWARE', manifest['role'])
        self.assertEqual('PARTIAL', manifest['completeness'])
        self.assertTrue(all(s['state'] == 'PARTIAL' for s in manifest['scope']))
        entries = {e['path']: e for e in manifest['entries']}
        app = entries['/data/app/example/base.apk']['fields']
        self.assertEqual('0644', app['mode']['value'])
        self.assertEqual(1000, app['uid']['value'])
        self.assertEqual('NOT_CHECKED', app['activation']['state'])
        self.assertEqual(h(b'system-image' * 1024), entries['/dev/block/system']['fields']['sha256']['value'])
        self.assertEqual('NOT_CHECKED', entries['/dev/block/system']['fields']['mode']['state'])
        for p in ('/dev/block/boot', '/dev/block/recovery', '/data/system/packages.xml', '/data/system/users/0/runtime-permissions.xml'):
            self.assertEqual('NOT_CHECKED', entries[p]['presence']['state'])
            self.assertEqual({}, entries[p]['fields'])
        self.assertEqual('NOT_ESTABLISHED', report['wholeSystemCoverage'])
        self.assertEqual('NOT_PERFORMED', report['runtimeVerification'])
        self.assertEqual('NOT_CHECKED', report['packageEvidence']['state'])
        write_json(self.root / 'firmware-manifest.json', manifest)
        write_json(self.root / 'source-report.json', report)

    def test_zip_and_directory_same_expected_entries(self):
        z = self.make_zip()
        directory, _ = self.generate()
        zipped, report = self.generate(z)
        self.assertEqual(directory['entries'], zipped['entries'])
        self.assertEqual(h(z.read_bytes()), report['packageEvidence']['sha256'])
        write_json(self.root / 'firmware-manifest.json', zipped)

    def test_wrong_payload_hash(self):
        (self.package / 'payload/app.apk').write_bytes(b'x' * len(self.payload['payload/app.apk']))
        self.rejected('哈希不匹配')

    def test_wrong_source_catalog(self):
        self.catalog['original/app.apk'][1] = '1' * 64
        write_json(self.source_list, self.catalog)
        self.mapping['bindings']['sourceListSha256'] = h(self.source_list.read_bytes())
        self.rejected('哈希不匹配')

    def test_missing_payload(self):
        (self.package / 'payload/app.apk').unlink()
        self.rejected('缺少')

    def test_missing_mapping(self):
        self.mapping['entries'].pop(0)
        self.rejected('不完整')

    def test_cannot_hide_payload_as_ignored(self):
        self.mapping['entries'].pop(0)
        self.mapping['ignoredSources']['payload/app.apk'] = '错误地忽略安装载荷'
        self.rejected('原始来源清单存在未映射项')

    def test_duplicate_destination(self):
        self.mapping['entries'][1]['destination'] = self.mapping['entries'][0]['destination']
        self.rejected('重复目的')

    def test_destination_parent_file_conflict(self):
        self.mapping['entries'][1]['destination'] = '/data/app/example/base.apk/child'
        self.rejected('子路径冲突')

    def test_unsafe_destinations(self):
        for bad in ('/data/../etc/x', '/data//x', '/data/x/', 'C:/data/x', '/data\\x', '/data-other/x'):
            with self.subTest(path=bad):
                self.mapping['entries'][0]['destination'] = bad
                with self.assertRaises(gm.Rejected):
                    self.generate()

    def test_unsafe_sources(self):
        for bad in ('../outside', '/absolute', 'payload/../outside', 'payload\\outside', 'C:/outside'):
            with self.subTest(path=bad):
                self.mapping['entries'][0]['source'] = bad
                with self.assertRaises(gm.Rejected):
                    self.generate()

    def test_unknown_action_or_forged_runtime_metadata(self):
        self.mapping['entries'][0]['action'] = 'execute-c'
        self.rejected('未知安装动作')
        self.mapping['entries'][0]['action'] = 'copy'
        self.mapping['entries'][3]['mode'] = '0644'
        self.rejected('未知字段')

    def test_overlapping_scopes(self):
        self.mapping['scope'].append('/data/app')
        self.rejected('范围重叠')

    def test_4097_is_rejected_without_truncation(self):
        self.mapping['entries'] = [{'action': 'generated', 'destination': '/data/item' + str(i),
                                    'reason': '未检查'} for i in range(4097)]
        self.rejected('4096')

    def test_4096_retains_last_entry(self):
        self.mapping['entries'] = self.mapping['entries'][:3] + [
            {'action': 'generated', 'destination': '/data/limit/' + str(i).zfill(4), 'reason': '未检查'}
            for i in range(4093)]
        manifest, report = self.generate()
        self.assertEqual(4096, len(manifest['entries']))
        self.assertEqual(4096, report['counts']['entries'])
        self.assertIn('/data/limit/4092', {e['path'] for e in manifest['entries']})
        self.assertEqual('PARTIAL', manifest['completeness'])
        write_json(self.root / 'firmware-manifest.json', manifest)

    def test_zip_symlink_even_broken(self):
        info = zipfile.ZipInfo('payload/broken-link')
        info.create_system = 3
        info.external_attr = (stat.S_IFLNK | 0o777) << 16
        self.rejected('链接或特殊节点', self.make_zip([(info, b'missing-target')]))

    def test_zip_special_node(self):
        info = zipfile.ZipInfo('payload/fifo')
        info.create_system = 3
        info.external_attr = (stat.S_IFIFO | 0o600) << 16
        self.rejected('链接或特殊节点', self.make_zip([(info, b'')]))

    def test_zip_traversal(self):
        self.rejected('越界段', self.make_zip([('../outside', b'x')]))
        self.assertFalse((self.root / 'outside').exists())

    def test_zip_duplicate_member(self):
        self.rejected('重复路径', self.make_zip([('payload/app.apk', b'bad')]))

    def test_zip_file_directory_conflict(self):
        self.rejected('前缀冲突', self.make_zip([('payload/app.apk/child', b'bad')]))

    def test_unmapped_extra_file(self):
        (self.package / 'payload/extra').write_bytes(b'extra')
        self.rejected('未声明文件')

    def test_inventory_duplicate_row(self):
        self.inventory['文件'].append(copy.deepcopy(self.inventory['文件'][0]))
        write_json(self.inventory_path, self.inventory)
        self.mapping['inventory']['sha256'] = h(self.inventory_path.read_bytes())
        self.rejected('来源清单重复路径')

    def test_gzip_expansion_must_match_original_not_container(self):
        changed = gzip.compress(b'z' * len(b'system-image' * 1024), mtime=0)
        (self.package / 'payload/system.img.gz').write_bytes(changed)
        self.inventory['文件'][1].update({'bytes': len(changed), 'sha256': h(changed)})
        write_json(self.inventory_path, self.inventory)
        self.mapping['inventory']['sha256'] = h(self.inventory_path.read_bytes())
        self.rejected('哈希不匹配')

    def test_gzip_expansion_bound(self):
        self.catalog['original/system.img'][0] = 4
        write_json(self.source_list, self.catalog)
        self.mapping['bindings']['sourceListSha256'] = h(self.source_list.read_bytes())
        self.rejected('大小超限')

    def test_installer_binding_tamper(self):
        self.installer.write_bytes(b'changed-source')
        self.rejected('审查绑定哈希')

    def test_zip_package_binding_tamper(self):
        z = self.make_zip()
        self.mapping['bindings']['packageSha256'] = '1' * 64
        self.rejected('整包SHA-256', z)

    def test_duplicate_json_keys(self):
        with self.assertRaisesRegex(gm.Rejected, 'JSON重复键'):
            gm.load_json(b'{"schemaVersion":1,"schemaVersion":2}')

    def test_directory_broken_symlink(self):
        try:
            (self.package / 'payload/broken').symlink_to('missing')
        except OSError as e:
            self.skipTest('Windows未授权创建符号链接：' + str(e))
        self.rejected('链接或重解析点')

    def test_directory_parent_symlink_escape(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'file').write_bytes(b'outside')
        try:
            (self.package / 'payload/escape').symlink_to(outside, target_is_directory=True)
        except OSError as e:
            self.skipTest('Windows未授权创建符号链接：' + str(e))
        self.rejected('链接或重解析点')

    def test_cli_missing_parameters(self):
        result = subprocess.run([sys.executable, '-B', str(Path(gm.__file__).resolve())],
                                capture_output=True, encoding='utf-8', errors='replace')
        (self.root / 'stdout.txt').write_text(result.stdout, encoding='utf-8')
        (self.root / 'stderr.txt').write_text(result.stderr, encoding='utf-8')
        self.assertEqual(2, result.returncode)
        self.assertIn('--input', result.stderr)
        self.assertEqual('', result.stdout)

    def test_cli_refusal_preserves_evidence_and_no_manifest(self):
        self.mapping['entries'].pop(0)
        write_json(self.mapping_path, self.mapping)
        output = self.root / 'rejected-output'
        args = ['--input', str(self.package), '--mapping', str(self.mapping_path),
                '--installer-source', str(self.installer), '--source-list', str(self.source_list),
                '--baseline-id', 'synthetic', '--baseline-revision', '1', '--output', str(output),
                '--valid-until-ms', str(int(time.time() * 1000) + 3600000)]
        with context_capture() as result:
            self.assertEqual(2, gm.main(args))
            first = (output / 'rejection.json').read_bytes()
            self.assertEqual(2, gm.main(args))
            self.assertEqual(first, (output / 'rejection.json').read_bytes())
        (self.root / 'stderr.txt').write_text(result.getvalue(), encoding='utf-8')
        self.assertFalse((output / 'firmware-manifest.json').exists())


def context_capture():
    import contextlib
    return contextlib.redirect_stderr(io.StringIO())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-dir', type=Path, required=True)
    args = parser.parse_args()
    EVIDENCE = args.evidence_dir.resolve()
    EVIDENCE.mkdir(exist_ok=False)
    with (EVIDENCE / 'unittest-raw.txt').open('x', encoding='utf-8') as log:
        result = unittest.TextTestRunner(stream=log, verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(FirmwareTests))
    summary = {'tests': result.testsRun, 'failures': len(result.failures), 'errors': len(result.errors),
               'skipped': len(result.skipped), 'successful': result.wasSuccessful()}
    write_json(EVIDENCE / 'result.json', summary)
    print(json.dumps(summary))
    sys.exit(0 if result.wasSuccessful() else 1)
