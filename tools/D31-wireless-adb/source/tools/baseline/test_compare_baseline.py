"""宿主严格输入和私有报告边界回归；Java业务测试单独执行。"""
import json
from pathlib import Path
import tempfile
import unittest
import compare_baseline as subject


class InputTests(unittest.TestCase):
    def test_duplicate_member(self):
        with self.assertRaises(ValueError): subject.strict_json(b'{"a":1,"a":2}')

    def test_duplicate_escaped_member(self):
        with self.assertRaises(ValueError): subject.strict_json(b'{"a":1,"\\u0061":2}')

    def test_nan_and_float(self):
        for value in (b'NaN', b'Infinity', b'-Infinity', b'1.5'):
            with self.assertRaises(ValueError): subject.strict_json(b'{"x":' + value + b'}')

    def test_depth(self):
        with self.assertRaises(ValueError): subject.strict_json(b'[' * 15 + b']' * 15)

    def test_escaped_quotes_do_not_confuse_depth(self):
        text = json.dumps({'x': '"' + '[' * 200 + '\\"'})
        self.assertEqual(json.loads(text), subject.strict_json(text.encode()))

    def test_size(self):
        with self.assertRaises(ValueError): subject.strict_json(b' ' * (subject.MAX_BYTES + 1))

    def test_trailing_data(self):
        with self.assertRaises(ValueError): subject.strict_json(b'{}{}')

    def test_bom(self):
        self.assertEqual({}, subject.strict_json(b'\xef\xbb\xbf{}'))

    def test_non_object(self):
        for data in (b'null', b'[]', b'1'):
            with self.assertRaises(ValueError): subject.strict_json(data)

    def test_wrapper_and_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'input.json'
            data = b'{"manifest":{"role":"TARGET"},"index":{"atomicSnapshot":false}}'
            path.write_bytes(data)
            manifest, ref = subject.read_input(path)
            self.assertEqual('TARGET', manifest['role'])
            self.assertEqual('/manifest', ref['manifestPointer'])
            self.assertEqual(len(data), ref['bytes'])
            self.assertEqual(data, path.read_bytes())

    def test_output_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'result.json'
            subject.save_new(path, {'a': 1})
            with self.assertRaises(FileExistsError): subject.save_new(path, {'a': 2})
            self.assertEqual({'a': 1}, json.loads(path.read_text()))


if __name__ == '__main__':
    unittest.main()
