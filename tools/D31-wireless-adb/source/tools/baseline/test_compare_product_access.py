"""固定11项产品访问对照；合成输入、正式源及CLI定向测试，无设备操作。"""
import copy
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
import compare_product_access as subject

WORKSPACE = Path(__file__).resolve().parents[4]
FACTORY = WORKSPACE / subject.FACTORY_RELATIVE


def report():
    facts = []
    for identity in subject.APP_OP_IDS:
        mode = 1 if identity == subject.APP_OP_IDS[3] else 0
        facts.append({'id': identity, 'source': 'app_ops', 'state': 'OBSERVED',
                      'value': {'mode': mode, 'modeName': subject.MODES[mode]}})
    for identity, source in zip(subject.BOOLEAN_IDS, ('secure.accessibility_enabled', 'secure.enabled_accessibility_services', 'accessibility_manager')):
        facts.append({'id': identity, 'source': source, 'state': 'OBSERVED', 'value': True})
    return {'productAccess': {'schemaVersion': 1, 'catalog': subject.CATALOG, 'userId': 0, 'capturedAtMs': 100,
                             'facts': facts, 'observed': 11, 'total': 11, 'status': 'COMPLETE', 'atomicSnapshot': False,
                             'executionState': 'NOT_CHECKED', 'systemConsistency': 'NOT_ASSESSED', 'repairPlanGenerated': False}}


class AccessTests(unittest.TestCase):
    def compare(self, left=None, right=None):
        return subject.compare(left or report(), right or report(), FACTORY)

    def test_catalog_and_mode_names_match_current_pure_java(self):
        source = (WORKSPACE / 'research/d31_adb_bootstrap/app/src/main/java/net/elfradio/d31bootstrap/RemoteProductAccess.java').read_text(encoding='utf-8')
        body = re.search(r'String\[\]\[\] APP_OPS = \{(.*?)\};', source, re.S).group(1)
        pairs = re.findall(r'\{(QUIK|REMOTE|"[^"]+"),\s*"([A-Z_]+)"\}', body)
        self.assertEqual(list(subject.APP_OPS), [({'QUIK': subject.SMS, 'REMOTE': subject.REMOTE}.get(pkg, pkg.strip('"')), op) for pkg, op in pairs])
        body = re.search(r'String\[\] MODE_NAMES = \{(.*?)\};', source, re.S).group(1)
        self.assertEqual(list(subject.MODES.values()), re.findall(r'"([a-z]+)"', body))
        for prefix in ('setting:accessibility_enabled', 'accessibility_service:', 'accessibility_bound:'):
            self.assertIn('"' + prefix + '"', source)
        self.assertIn('put("modeName", MODE_NAMES[mode])', source)
        self.assertEqual(11, len(set(subject.IDS)))

    def test_matching_reports_and_only_four_initial_references(self):
        result = self.compare()
        self.assertEqual({'MATCH': 11, 'DIFFERENT': 0, 'UNKNOWN': 0}, result['counts'])
        known = [r for r in result['facts'] if r['initialReference']['state'] == 'SOURCE_BOUND']
        self.assertEqual({subject.APP_OP_IDS[2], subject.APP_OP_IDS[3], *subject.BOOLEAN_IDS[:2]}, {r['id'] for r in known})
        self.assertTrue(all(r['initialReference']['left']['result'] == 'MATCH' for r in known))
        self.assertEqual('MATCHED', result['factorySource']['state'])
        self.assertFalse(result['repairPlanGenerated'])
        self.assertEqual('NOT_ASSESSED', result['systemConsistency'])

    def test_differences_do_not_echo_modes_or_booleans(self):
        right = report()
        right['productAccess']['facts'][2]['value'] = {'mode': 3, 'modeName': 'default'}
        right['productAccess']['facts'][8]['value'] = False
        result = self.compare(right=right)
        self.assertEqual(2, result['counts']['DIFFERENT'])
        self.assertEqual('DIFFERENT', result['facts'][2]['initialReference']['right']['result'])
        for row in result['facts']:
            self.assertNotIn('value', json.dumps(row))
            self.assertNotIn('modeName', json.dumps(row))

    def test_null_global_switch_must_be_failed_not_false(self):
        right = report()
        fact = right['productAccess']['facts'][8]
        fact.pop('value')
        fact.update(state='READ_FAILED', reason='private-sentinel')
        right['productAccess'].update(observed=10, status='PARTIAL')
        result = self.compare(right=right)
        self.assertEqual('UNKNOWN', result['facts'][8]['result'])
        self.assertEqual('OBSERVATION_UNKNOWN', result['facts'][8]['initialReference']['right']['reason'])
        self.assertNotIn('private-sentinel', json.dumps(result))
        fact.update(state='OBSERVED', value=None)
        fact.pop('reason')
        right['productAccess'].update(observed=11, status='COMPLETE')
        with self.assertRaises(ValueError): self.compare(right=right)

    def test_bound_observation_has_no_fault_expectation(self):
        left, right = report(), report()
        left['productAccess']['facts'][-1]['value'] = False
        right['productAccess']['facts'][-1]['value'] = False
        row = self.compare(left, right)['facts'][-1]
        self.assertEqual('MATCH', row['result'])
        self.assertEqual('EXPECTATION_UNKNOWN', row['initialReference']['state'])
        self.assertEqual('RUNTIME_OBSERVATION_ONLY', row['classification'])

    def test_all_four_modes_and_malformed_modes(self):
        for mode, name in subject.MODES.items():
            right = report()
            right['productAccess']['facts'][0]['value'] = {'mode': mode, 'modeName': name}
            self.compare(right=right)
        for value in ({'mode': True, 'modeName': 'ignored'}, {'mode': 4, 'modeName': 'default'},
                      {'mode': 0, 'modeName': 'ALLOWED'}, {'mode': 0, 'name': 'allowed'},
                      {'mode': 0, 'modeName': 'ignored'}, {'mode': 0.0, 'modeName': 'allowed'},
                      {'mode': 0, 'modeName': 'allowed', 'private': 'private-sentinel'}):
            with self.subTest(value=value):
                right = report()
                right['productAccess']['facts'][0]['value'] = value
                with self.assertRaises(ValueError): self.compare(right=right)

    def test_catalog_id_count_duplicate_schema_and_summary_rejected(self):
        for change in ('schema', 'user', 'catalog', 'duplicate', 'unknown', 'missing', 'total', 'observed', 'status', 'repair', 'extra'):
            with self.subTest(change=change):
                right = report()
                value = right['productAccess']
                if change == 'schema': value['schemaVersion'] = True
                elif change == 'user': value['userId'] = False
                elif change == 'catalog': value['catalog'] = 'other'
                elif change == 'duplicate': value['facts'][1] = value['facts'][0]
                elif change == 'unknown': value['facts'][0]['id'] = 'unknown'
                elif change == 'missing': value['facts'].pop()
                elif change == 'total': value['total'] = 10
                elif change == 'observed': value['observed'] = 10
                elif change == 'status': value['status'] = 'PARTIAL'
                elif change == 'repair': value['repairPlanGenerated'] = True
                else: value['extra'] = 'private-sentinel'
                with self.assertRaises(ValueError): self.compare(right=right)

    def test_failed_value_source_and_boolean_types_rejected(self):
        for change in ('failed-value', 'source', 'integer', 'note'):
            right = report()
            row = right['productAccess']['facts'][8]
            if change == 'failed-value': row.update(state='READ_FAILED', reason='IOException')
            elif change == 'source': row['source'] = 'other'
            elif change == 'integer': row['value'] = 1
            else: row['note'] = 'private-sentinel'
            with self.assertRaises(ValueError): self.compare(right=right)

    def test_missing_or_modified_factory_only_removes_references(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'source.java'
            for exists in (False, True):
                if exists: subject.save_new(path, {'合成': True})
                result = subject.compare(report(), report(), path)
                self.assertEqual(11, result['counts']['MATCH'])
                self.assertTrue(all(r['initialReference']['state'] == 'EXPECTATION_UNKNOWN' for r in result['facts']))
                self.assertEqual('MISMATCH' if exists else 'MISSING', result['factorySource']['state'])

    def test_report_envelope_privacy_and_capture_order(self):
        left = report()
        left['deviceId'] = left['note'] = 'private-sentinel'
        left['productAccess']['capturedAtMs'] = 200
        result = self.compare({'report': left}, report())
        self.assertNotIn('private-sentinel', json.dumps(result))
        self.assertEqual('REVERSED', result['captureOrder'])
        self.assertEqual('NOT_VERIFIED_BY_THIS_TOOL', result['identityAndBootBinding'])

    def test_cli_hashes_no_overwrite_and_strict_json(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            left, right = root / 'left.json', root / 'right.json'
            subject.save_new(left, {'report': report()})
            subject.save_new(right, report())
            output = root / 'private-output'
            command = [sys.executable, '-B', str(Path(subject.__file__)), '--left', str(left), '--right', str(right),
                       '--factory-source', str(FACTORY), '--output', str(output)]
            self.assertEqual(0, subprocess.run(command, capture_output=True, timeout=30).returncode)
            before = (output / 'comparison.json').read_bytes()
            self.assertIn('sha256', json.loads(before)['inputs']['left'])
            self.assertNotEqual(0, subprocess.run(command, capture_output=True, timeout=30).returncode)
            self.assertEqual(before, (output / 'comparison.json').read_bytes())
        for data in (b'{"report":{},"report":{}}', b'{"value":NaN}', b'{"value":1.2}'):
            with self.assertRaises(ValueError): subject.strict_json(data)


if __name__ == '__main__':
    unittest.main()
