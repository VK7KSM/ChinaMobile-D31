"""离线清单回归：合成载荷先实算摘要，不操作设备或重建固件。"""
import copy
import hashlib
import unittest
from verify_factory_package import verify_manifest_files


class ManifestContractTest(unittest.TestCase):
    def setUp(self):
        contents = {
            'META-INF/com/google/android/update-binary': b'installer',
            'payload/apps/test.apk': b'apk',
            'payload/system.img.gz': b'compressed',
            'payload/system.img.gz -> system.img': b'original',
        }
        self.checked = {name: {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                        for name, data in contents.items()}
        self.manifest = {'版本': '1.4.3', '文件': [dict(value, path=name) for name, value in self.checked.items()
                                              if ' -> ' not in name]}
        self.manifest['文件'][-1].update(uncompressed_bytes=8, source_sha256=self.checked['payload/system.img.gz -> system.img']['sha256'])

    def test_complete_manifest(self):
        verify_manifest_files(self.manifest, self.checked)

    def test_empty_manifest_is_not_complete(self):
        self.manifest['文件'] = []
        with self.assertRaises(SystemExit): verify_manifest_files(self.manifest, self.checked)

    def test_duplicate_cannot_replace_missing_entry(self):
        self.manifest['文件'][1] = copy.deepcopy(self.manifest['文件'][0])
        with self.assertRaises(SystemExit): verify_manifest_files(self.manifest, self.checked)

    def test_incorrect_size_or_hash(self):
        for field, value in [('bytes', 1), ('bytes', True), ('sha256', '0' * 64), ('sha256', None)]:
            with self.subTest(field=field, value=value):
                manifest = copy.deepcopy(self.manifest); manifest['文件'][0][field] = value
                with self.assertRaises(SystemExit): verify_manifest_files(manifest, self.checked)

    def test_noncanonical_or_unknown_paths(self):
        for name in ['../test', '/payload/apps/test.apk', 'payload/apps/../test.apk', 'payload/unknown', None]:
            with self.subTest(name=name):
                manifest = copy.deepcopy(self.manifest); manifest['文件'][0]['path'] = name
                with self.assertRaises(SystemExit): verify_manifest_files(manifest, self.checked)

    def test_decompressed_identity_must_match(self):
        for field, value in [('uncompressed_bytes', 9), ('source_sha256', 'f' * 64)]:
            with self.subTest(field=field):
                manifest = copy.deepcopy(self.manifest); manifest['文件'][-1][field] = value
                with self.assertRaises(SystemExit): verify_manifest_files(manifest, self.checked)

    def test_invalid_shape(self):
        for value in [None, [], {'版本': '1.4.3', '文件': [None] * 3}]:
            with self.assertRaises(SystemExit): verify_manifest_files(value, self.checked)


if __name__ == '__main__':
    unittest.main(verbosity=2)
