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


class ConfigurationRenderTests(unittest.TestCase):
    def report(self, with_catalog=True):
        report = {'counts': {'knownPaths': 1, 'intersectionPaths': 1, 'observationOnlyPaths': 0, 'firmwareOnlyPaths': 0},
                  'categories': {}, 'gaps': [], 'observation': {'freshness': 'EXPIRED'},
                  'firmware': {'freshness': 'EXPIRED'}, 'bindingEqual': {}, 'contextEqual': {}}
        if with_catalog:
            ids = ['system_support.root', 'system_support.disabled', 'recovery.enabled', 'startup.cellular_enabled',
                   'rescue.enabled', 'desktop.config_tab', 'initialization.components', 'initialization.permissions',
                   'initialization.completion']
            items = []
            for i, name in enumerate(ids):
                state = 'OBSERVED' if i == 0 else 'NOT_CHECKED'
                reasons = ['RAW_VALUES_ONLY_NO_ALLOWED_DIFFERENCE_RULES'] if i == 0 else ['CONFIGURATION_FIELD_CONTRACT_NOT_DEFINED']
                items.append({'id': name, 'pair': 'SAME' if i == 0 else 'UNKNOWN', 'reasons': reasons,
                              'observation': {'state': state, 'reasons': []}, 'firmware': {'state': state, 'reasons': []}})
            report['configurationCoverage'] = {'catalogId': 'd31-finite-configuration', 'catalogVersion': 1,
                'requiredItems': 9, 'bothObservedItems': 1, 'gapItems': 8, 'items': items}
        return report

    def test_root_does_not_hide_other_eight_rows(self):
        rendered = subject.render(self.report())
        self.assertIn('必检9项，双方有证据1项，缺口8项', rendered)
        self.assertEqual(8, rendered.count('字段合同尚未定义'))
        for label in ('系统支持ROOT', 'Recovery启用条件', '初始化完成条件'):
            self.assertIn(label, rendered)

    def test_old_report_explicitly_lacks_catalog(self):
        rendered = subject.render(self.report(False))
        self.assertIn('旧报告未提供版本化必检目录', rendered)
        self.assertNotIn('双方有证据1项', rendered)

    def test_unknown_catalog_is_not_interpreted_as_version_one(self):
        for key, value in (('catalogVersion', 4), ('catalogId', 'other-catalog')):
            report = self.report()
            report['configurationCoverage'][key] = value
            rendered = subject.render(report)
            self.assertIn('不支持此必检目录版本', rendered)
            self.assertNotIn('双方有证据1项', rendered)

    def test_version_two_keeps_nine_items_and_five_gaps(self):
        report = self.report()
        report['configurationCoverage'].update({'catalogVersion': 2, 'bothObservedItems': 4, 'gapItems': 5})
        rendered = subject.render(report)
        self.assertIn('目录版本2；必检9项，双方有证据4项，缺口5项', rendered)
        self.assertIn('初始化完成条件', rendered)

    def test_version_three_keeps_four_remaining_gaps(self):
        report = self.report()
        report['configurationCoverage'].update({'catalogVersion': 3, 'bothObservedItems': 5, 'gapItems': 4})
        rendered = subject.render(report)
        self.assertIn('目录版本3；必检9项，双方有证据5项，缺口4项', rendered)
        self.assertIn('蜂窝启动配置', rendered)
        self.assertIn('初始化完成条件', rendered)

    def test_unavailable_states_and_missing_field_are_visible(self):
        report = self.report()
        root = report['configurationCoverage']['items'][0]
        root.update({'pair': 'UNKNOWN', 'reasons': ['OBSERVATION_EVIDENCE_INSUFFICIENT', 'FIRMWARE_EVIDENCE_INSUFFICIENT']})
        root['observation'] = {'state': 'NOT_APPLICABLE', 'reasons': ['APPLICABILITY_RULE_NOT_VERIFIED']}
        root['firmware'] = {'state': 'READ_FAILED', 'reasons': ['FIELD_NOT_COLLECTED']}
        rendered = subject.render(report)
        for text in ('不适用声明未核验', '读取失败', '未采集字段', '适用性规则未核验'):
            self.assertIn(text, rendered)

    def test_renderer_does_not_echo_private_values_or_unknown_labels(self):
        report = self.report()
        row = report['configurationCoverage']['items'][0]
        row.update({'id': 'private-id', 'value': 'private-value', 'reasons': ['private-reason']})
        row['observation'].update({'source': 'private-source', 'value': 'private-value'})
        rendered = subject.render(report)
        for private in ('private-id', 'private-value', 'private-reason', 'private-source'):
            self.assertNotIn(private, rendered)


if __name__ == '__main__':
    unittest.main()
