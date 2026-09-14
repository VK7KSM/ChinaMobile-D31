"""38项宿主闭包测试；只使用冻结源码和合成报告，不访问设备。"""
import copy
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
import compare_product_configuration as subject

WORKSPACE = Path(__file__).resolve().parents[4]


class ProductTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.expected = subject.build_expected(WORKSPACE)

    def report(self):
        rows = []
        for row in self.expected['facts']:
            values = {field: e.get('value', 0 if field in ('enabledSetting', 'versionCode') else True)
                      for field, e in row['fields'].items()}
            rows.append({'id': row['id'], 'state': 'OBSERVED', 'value': values.get('value', values)})
        return {'productConfiguration': {'schemaVersion': 1, 'catalog': subject.CATALOG, 'userId': 0,
                                         'facts': rows, 'total': 38}}

    def row(self, result, prefix):
        return next(r for r in result['facts'] if r['id'].startswith(prefix))

    def test_exact_38_catalog_and_current_java_declarations(self):
        source = (WORKSPACE / 'research/d31_adb_bootstrap/app/src/main/java/net/elfradio/d31bootstrap/RemoteProductConfiguration.java').read_text(encoding='utf-8')
        constants = {name: value for name, value in re.findall(r'static final String (REMOTE|SUPPORT|SMS|GUARD) = "([^"]+)";', source)}
        def array(name):
            body = re.search(r'String\[\] ' + name + r' = \{(.*?)\};', source, re.S).group(1)
            result = []
            for expression in body.split(','):
                tokens = re.findall(r'"[^"]*"|\b(?:REMOTE|SUPPORT|SMS|GUARD)\b', expression)
                result.append(''.join(t[1:-1] if t.startswith('"') else constants[t] for t in tokens))
            return result
        self.assertEqual(list(subject.PACKAGES), array('PACKAGES'))
        self.assertEqual(list(subject.COMPONENTS), array('COMPONENTS'))
        loops = re.findall(r'for \(final String permission : new String\[\]\{(.*?)\}\)', source, re.S)
        actual = [(subject.SMS, n) for n in re.findall(r'"([A-Z_]+)"', loops[0])]
        actual += [(subject.SUPPORT, 'READ_PHONE_STATE'), ('com.loudtalks', 'RECORD_AUDIO')]
        actual += [(subject.REMOTE, n) for n in re.findall(r'"([A-Z_]+)"', loops[1])]
        self.assertEqual(subject.PERMISSIONS, actual)
        self.assertEqual(38, len(subject.IDS))
        self.assertEqual(38, len(set(subject.IDS)))

    def test_formal_sources_matched_and_xml_component_values(self):
        self.assertTrue(all(v['state'] == 'MATCHED' for v in self.expected['sources'].values()))
        row = self.row(self.expected, 'component:' + subject.REMOTE + '/' + subject.REMOTE + '.RemoteManualReceiver')
        self.assertEqual(1, row['fields']['enabledSetting']['value'])
        self.assertEqual('EXPECTATION_UNKNOWN', row['fields']['manifestEnabled']['state'])
        for row in self.expected['facts']:
            if row['id'].startswith('permission:') and not any(p in row['id'] for p in ('READ_LOGS', ':android.permission.DUMP', 'WRITE_SECURE_SETTINGS')):
                self.assertIs(True, row['fields']['value']['value'])

    def test_match_different_unknown_and_fixed_summary(self):
        report = self.report()
        result = subject.compare(report, self.expected)
        self.assertEqual('MATCH', self.row(result, 'setting:')['result'])
        self.assertEqual('UNKNOWN', self.row(result, 'battery_exempt:')['result'])
        self.assertEqual('UNKNOWN', self.row(result, 'package:')['result'])
        self.assertFalse(result['repairPlanGenerated'])
        self.assertEqual('NOT_ASSESSED', result['systemConsistency'])
        self.assertEqual(38, sum(result['counts'].values()))
        self.row(report['productConfiguration'], 'setting:')['value'] = False
        self.assertEqual('DIFFERENT', self.row(subject.compare(report, self.expected), 'setting:')['result'])

    def test_missing_failed_or_wrong_type_never_false(self):
        for value in (None, 'private-sentinel', 1, {}):
            report = self.report()
            self.row(report['productConfiguration'], 'setting:')['value'] = value
            result = subject.compare(report, self.expected)
            self.assertEqual('UNKNOWN', self.row(result, 'setting:')['result'])
            self.assertNotIn('private-sentinel', json.dumps(result))
        report = self.report()
        self.row(report['productConfiguration'], 'setting:')['state'] = 'READ_FAILED'
        self.assertEqual('UNKNOWN', self.row(subject.compare(report, self.expected), 'setting:')['result'])
        self.assertEqual(38, subject.compare({}, self.expected)['counts']['UNKNOWN'])

    def test_missing_sources_do_not_invent_expectations(self):
        with tempfile.TemporaryDirectory() as directory:
            expected = subject.build_expected(directory)
        self.assertTrue(all(v['state'] == 'EXPECTATION_UNKNOWN' for r in expected['facts'] for v in r['fields'].values()))
        self.assertEqual(38, subject.compare(self.report(), expected)['counts']['UNKNOWN'])

    def test_installed_false_does_not_imply_default_component_state(self):
        report = self.report()
        self.row(report['productConfiguration'], 'package:')['value'] = {'installed': False}
        row = self.row(subject.compare(report, self.expected), 'package:')
        self.assertEqual('DIFFERENT', row['result'])
        self.assertEqual('UNKNOWN', next(f for f in row['fields'] if f['field'] == 'enabledSetting')['result'])

    def test_battery_and_component_unknowns_not_overclaimed(self):
        for prefix in ('battery_exempt:', 'component:' + subject.SUPPORT):
            row = self.row(self.expected, prefix)
            self.assertTrue(all(e['state'] == 'EXPECTATION_UNKNOWN' for e in row['fields'].values()))
        row = self.row(self.expected, 'listener:')
        self.assertIn('规范化', row['note'])
        self.assertIs(True, row['fields']['value']['value'])

    def test_duplicate_extra_catalog_and_unbound_expectation_rejected(self):
        for case in ('duplicate', 'extra', 'catalog'):
            report = self.report()
            product = report['productConfiguration']
            if case == 'duplicate': product['facts'][1] = product['facts'][0]
            elif case == 'extra': product['facts'][0]['id'] = 'private-sentinel'
            else: product['catalog'] = 'wrong'
            with self.assertRaises(ValueError): subject.compare(report, self.expected)
        expected = copy.deepcopy(self.expected)
        expected['sources']['factory']['state'] = 'MISMATCH'
        with self.assertRaises(ValueError): subject.compare(self.report(), expected)

    def test_report_wrapper_and_private_metadata_not_echoed(self):
        report = self.report()
        report['deviceId'] = 'private-sentinel'
        for row in report['productConfiguration']['facts']:
            row['source'] = row['reason'] = row['classification'] = 'private-sentinel'
        expected = copy.deepcopy(self.expected)
        for row in expected['facts']:
            row['note'] = 'private-sentinel'
        result = subject.compare({'report': report}, expected)
        self.assertNotIn('private-sentinel', json.dumps(result))

    def test_expected_classifications_preserved_for_all_rows(self):
        result = subject.compare(self.report(), self.expected)
        expected = {row['id']: row['classification'] for row in self.expected['facts']}
        self.assertEqual(expected, {row['id']: row['classification'] for row in result['facts']})
        self.assertEqual('USER_SELECTABLE_DEFAULT', self.row(result, 'setting:')['classification'])
        self.assertEqual('PRODUCT_INITIAL_REFERENCE', self.row(result, 'component:')['classification'])
        for row in result['facts']:
            self.assertEqual({'id', 'classification', 'result', 'fields'}, set(row))

    def test_invalid_or_missing_classification_rejected_without_echo(self):
        for invalid in (None, True, 1, [], {}, '', 'private-sentinel'):
            with self.subTest(valueType=type(invalid).__name__):
                expected = copy.deepcopy(self.expected)
                expected['facts'][0]['classification'] = invalid
                with self.assertRaisesRegex(ValueError, '^EXPECTED_CLASSIFICATION_INVALID$'):
                    subject.compare(self.report(), expected)
        expected = copy.deepcopy(self.expected)
        del expected['facts'][0]['classification']
        with self.assertRaisesRegex(ValueError, '^EXPECTED_CLASSIFICATION_INVALID$'):
            subject.compare(self.report(), expected)

    def test_cli_explicit_expected_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            expected, report = root / 'expected.json', root / 'report.json'
            subject.save_new(expected, self.expected)
            subject.save_new(report, {'report': self.report()})
            output = root / 'new-private'
            command = [sys.executable, '-B', str(Path(subject.__file__)), 'compare', '--report', str(report),
                       '--expected', str(expected), '--output', str(output)]
            run = subprocess.run(command, capture_output=True, timeout=30)
            self.assertEqual(0, run.returncode)
            data = (output / 'comparison.json').read_bytes()
            self.assertIn('expectedSha256', json.loads(data)['inputs'])
            self.assertNotEqual(0, subprocess.run(command, capture_output=True, timeout=30).returncode)
            self.assertEqual(data, (output / 'comparison.json').read_bytes())


if __name__ == '__main__':
    unittest.main()
