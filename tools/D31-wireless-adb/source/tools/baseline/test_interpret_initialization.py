"""纯合成清单回归及原比较器接线核验；不访问设备。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import unittest

sys.dont_write_bytecode = True
import interpret_initialization as subject

ROOT = '/data/local/d31-startup-handover/'
LEAVES = ('factory-init-required', 'factory-init-complete', 'factory-runtime-complete', 'start.sh', 'handover.jar')
SCRIPT = '35a9963961dc7e30c9d9be2b0e5ebf37783582c8db78d5ca2f799f577da6e615'
JAR = '1dc959ec6e6513d48b8894042c347b749057690dd2674eea69ff639b84794978'
OLD = '3e12fe7cdf595483d66b72a9a3ab371a17ee58b2c801155c0b24f4f5481d85ec'


def observed(value):
    return {'state': 'OBSERVED', 'value': value, 'source': '测试来源'}


def unknown(state='READ_FAILED', reason='NOT_FOUND'):
    return {'state': state, 'reason': reason, 'source': '测试来源'}


def fixture(bits='011', firmware=False):
    result = {'schemaVersion': 1, 'role': 'FIRMWARE' if firmware else 'TARGET',
              'snapshotId': '合成快照', 'baselineId': '合成基线', 'baselineRevision': '1',
              'firmwareId': '合成固件', 'build': '合成构建', 'collectorVersion': '合成采集',
              'capturedAtMs': 100, 'validUntilMs': 200, 'uptimeMs': 50,
              'context': {name: '合成上下文' for name in ('model', 'hardwareClass', 'firmwareFamily', 'network', 'sim', 'storage')},
              'completeness': 'PARTIAL',
              'scope': [{'path': ROOT.rstrip('/'), 'state': 'PARTIAL', 'source': '合成范围', 'reason': '有界采集'}],
              'entries': []}
    result['context']['stage'] = 'POST_INSTALL_BEFORE_FIRST_BOOT' if firmware else 'RUNNING'
    for i, leaf in enumerate(LEAVES):
        present = i >= 3 or bits[i] == '1'
        fields = {'type': observed('file'), 'mode': observed('0600'), 'uid': observed(0), 'gid': observed(0)} if present else {}
        if i >= 3:
            fields['sha256'] = observed(SCRIPT if i == 3 else JAR)
        result['entries'].append({'path': ROOT + leaf, 'presence': observed('PRESENT' if present else 'ABSENT'), 'fields': fields})
    return result


class CompletionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.command, refs = subject.prepare(OUTPUT, JAVA_HOME, JSON_JAR)
        subject.original.save_new(OUTPUT / 'sources.json', {'sources': refs})
        cls.sequence = 0

    def run_case(self, observation=None, firmware=None, now=150, reject=False):
        type(self).sequence += 1
        directory = OUTPUT / ('case-%03d' % self.sequence)
        directory.mkdir()
        observations = observation if isinstance(observation, list) else [observation or fixture()]
        subject.original.save_new(directory / 'input.json', {'observations': observations, 'firmware': firmware or fixture('100', True)})
        command = self.command + [str(directory / 'input.json'), str(directory / 'result.json'), str(now)]
        if reject:
            with self.assertRaises(RuntimeError):
                subject.original.run_logged(command, directory, 'java')
            self.assertFalse((directory / 'result.json').exists())
            return
        subject.original.run_logged(command, directory, 'java')
        result = json.loads((directory / 'result.json').read_text(encoding='utf-8'))
        sidecar = result['completion']
        self.assertFalse(sidecar['repairPlanGenerated'])
        self.assertFalse(sidecar['atomicSnapshotEstablished'])
        self.assertEqual('NOT_PERFORMED', sidecar['runtimeVerification'])
        self.assertEqual('NOT_ASSESSED', sidecar['systemConsistency'])
        for coverage in result['coverageReports']:
            self.assertFalse(coverage['repairPlanGenerated'])
            row = next(r for r in coverage['configurationCoverage']['items'] if r['id'] == 'initialization.completion')
            self.assertEqual('GAP', row['coverage'])
            self.assertEqual(['CONFIGURATION_FIELD_CONTRACT_NOT_DEFINED'], row['reasons'])
        return result

    def test_eight_marker_combinations(self):
        runtime_gates = ['NOT_SATISFIED', 'NOT_SATISFIED', 'SATISFIED', 'NOT_SATISFIED',
                         'NOT_SATISFIED', 'NOT_SATISFIED', 'SATISFIED', 'NOT_SATISFIED']
        for value in range(8):
            bits = format(value, '03b')
            with self.subTest(bits=bits):
                r = self.run_case(fixture(bits))['completion']['semantics']
                self.assertEqual('CONDITIONAL_SOURCE_PREDICATES_ONLY', r['status'])
                self.assertEqual(bits, r['combination'])
                self.assertEqual(runtime_gates[value], r['runtimeMarkerGate'])
                self.assertEqual('SATISFIED' if value >= 4 else 'NOT_SATISFIED', r['javaApplyRequestGate'])
                self.assertEqual(r['javaApplyRequestGate'], r['shellRequestGate'])
                self.assertNotIn('success', r)

    def test_unknown_presence_never_becomes_absence(self):
        for state in ('READ_FAILED', 'NOT_CHECKED', 'UNSTABLE', 'REDACTED'):
            with self.subTest(state=state):
                o = fixture()
                o['entries'][0].update(presence=unknown(state), fields={})
                r = self.run_case(o)['completion']
                self.assertEqual('UNKNOWN', r['observationFacts'][0]['presence'])
                self.assertEqual(state, r['observationFacts'][0]['presenceState'])
                self.assertFalse(r['observationFacts'][0]['inferredAbsence'])
                self.assertEqual('UNKNOWN', r['semantics']['status'])

    def test_partial_omission_is_unknown(self):
        o = fixture()
        del o['entries'][0]
        f = self.run_case(o)['completion']['observationFacts'][0]
        self.assertEqual('UNKNOWN', f['presence'])
        self.assertIsNone(f['presencePointer'])

    def test_original_complete_enumeration_proves_only_absence(self):
        o = fixture()
        o['completeness'] = o['scope'][0]['state'] = 'COMPLETE'
        del o['entries'][0]
        r = self.run_case(o)['completion']
        self.assertEqual('ABSENT', r['observationFacts'][0]['presence'])
        self.assertTrue(r['observationFacts'][0]['inferredAbsence'])
        self.assertEqual('/scope/0', r['observationFacts'][0]['scopePointer'])
        self.assertEqual('011', r['semantics']['combination'])

    def test_failed_enumeration_blocks_inferred_absence(self):
        o = fixture()
        o['completeness'] = o['scope'][0]['state'] = 'COMPLETE'
        del o['entries'][0]
        o['entries'][0]['fields']['semantic.enumeration'] = unknown()
        self.assertEqual('UNKNOWN', self.run_case(o)['completion']['observationFacts'][0]['presence'])

    def test_types_are_not_java_isfile_guesses(self):
        for shape in ('symlink', 'directory', 'block', 'semantic', None):
            with self.subTest(shape=shape):
                o = fixture('111')
                o['entries'][0]['fields']['type'] = observed(shape) if shape else unknown('NOT_CHECKED')
                r = self.run_case(o)['completion']
                self.assertEqual('PRESENT', r['observationFacts'][0]['presence'])
                self.assertEqual('UNKNOWN', r['semantics']['status'])

    def test_known_old_jar_identified_but_never_interpreted(self):
        o = fixture()
        o['entries'][4]['fields']['sha256'] = observed(OLD)
        r = self.run_case(o)['completion']
        self.assertEqual('MATCH', r['observationFacts'][3]['consumerBinding'])
        self.assertEqual('HISTORICAL_20260911_CELLULAR_EMPTY_SIP', r['observationFacts'][4]['knownSource'])
        self.assertEqual('MISMATCH', r['consumerBinding'])
        self.assertEqual('UNKNOWN', r['semantics']['status'])
        self.assertIsNone(r['semantics']['combination'])

    def test_either_consumer_mismatch_blocks_semantics(self):
        for i in (3, 4):
            o = fixture()
            o['entries'][i]['fields']['sha256'] = observed('a' * 64)
            r = self.run_case(o)['completion']
            self.assertEqual('MISMATCH', r['consumerBinding'])
            self.assertEqual('NOT_IDENTIFIED', r['observationFacts'][i]['knownSource'])
            self.assertEqual('UNKNOWN', r['semantics']['status'])

    def test_unknown_absent_or_nonregular_consumer_blocks_binding(self):
        for change in ('hash', 'absent', 'symlink', 'type'):
            o = fixture()
            entry = o['entries'][4]
            if change == 'hash': entry['fields']['sha256'] = unknown('UNSTABLE')
            elif change == 'absent': entry.update(presence=observed('ABSENT'), fields={})
            elif change == 'symlink': entry['fields']['type'] = observed('symlink')
            else: del entry['fields']['type']
            r = self.run_case(o)['completion']
            self.assertEqual('UNKNOWN', r['consumerBinding'])
            self.assertEqual('UNKNOWN', r['semantics']['status'])

    def test_firmware_partial_omissions_remain_unknown(self):
        fw = fixture('100', True)
        fw['entries'] = [fw['entries'][0], *fw['entries'][3:]]
        r = self.run_case(firmware=fw)['completion']
        self.assertEqual(['UNKNOWN', 'UNKNOWN'], [f['presence'] for f in r['firmwareFacts'][1:3]])
        self.assertEqual(['RUNNING'], r['observationStages'])
        self.assertEqual('POST_INSTALL_BEFORE_FIRST_BOOT', r['firmwareStage'])
        self.assertFalse(r['repairPlanGenerated'])

    def test_injected_success_semantic_cannot_fill_original_gap(self):
        o = fixture()
        o['entries'][1]['fields']['semantic.initialization.completion'] = observed(True)
        self.run_case(o)

    def test_five_leaf_manifests_not_merged_into_simultaneous_state(self):
        batch = []
        for i in range(5):
            o = fixture()
            o['snapshotId'] += str(i)
            o['scope'][0]['path'] = ROOT + LEAVES[i]
            o['entries'] = [o['entries'][i]]
            batch.append(o)
        r = self.run_case(batch)['completion']
        self.assertEqual('MATCH', r['consumerBinding'])
        self.assertEqual(list(range(5)), [f['inputIndex'] for f in r['observationFacts']])
        self.assertEqual('UNKNOWN', r['semantics']['status'])
        self.assertIn('CROSS_BATCH_COMBINATION_NOT_ESTABLISHED', r['semantics']['reasons'])

    def test_duplicate_scopes_rejected_not_selected(self):
        self.run_case([fixture(), fixture()], reject=True)

    def test_invalid_original_contract_rejected(self):
        for change in ('role', 'unknown-key', 'sha256'):
            o = fixture()
            if change == 'role': o['role'] = 'FIRMWARE'
            elif change == 'unknown-key': o['extra'] = True
            else: o['entries'][4]['fields']['sha256'] = observed('wrong')
            self.run_case(o, reject=True)

    def test_time_limits_stay_visible_not_current_claim(self):
        for now, state in ((50, 'FUTURE'), (300, 'EXPIRED')):
            r = self.run_case(now=now)
            self.assertEqual(state, r['coverageReports'][0]['observation']['freshness'])
            self.assertEqual([state], r['completion']['observationFreshness'])
            self.assertEqual('NOT_PERFORMED', r['completion']['runtimeVerification'])

    def test_sidecar_does_not_echo_private_identity_reason_or_values(self):
        o = fixture()
        o['snapshotId'] = 'PRIVATE_SENTINEL_ID'
        o['context']['network'] = o['context']['stage'] = 'PRIVATE_SENTINEL_CONTEXT'
        o['entries'][0]['presence'] = unknown(reason='PRIVATE_SENTINEL_REASON')
        o['entries'][1]['fields']['semantic.private'] = observed('PRIVATE_SENTINEL_VALUE')
        r = self.run_case(o)['completion']
        self.assertNotIn('PRIVATE_SENTINEL', json.dumps(r))

    def test_cli_wrapper_provenance_and_original_comparator_exact_output(self):
        directory = OUTPUT / 'cli-inputs'
        directory.mkdir()
        obs, fw = directory / 'observation.json', directory / 'firmware.json'
        subject.original.save_new(obs, {'manifest': fixture(), 'index': {'atomicSnapshot': False}})
        subject.original.save_new(fw, fixture('100', True))
        output = OUTPUT / 'cli-output'
        common = ['--observation', str(obs), '--firmware', str(fw), '--java-home', str(JAVA_HOME),
                  '--json-jar', str(JSON_JAR), '--now-ms', '150']
        command = [sys.executable, '-B', str(subject.HERE / 'interpret_initialization.py'), *common, '--output', str(output)]
        subject.original.run_logged(command, directory, 'candidate')
        completion = json.loads((output / 'completion.json').read_text(encoding='utf-8'))
        self.assertEqual('/manifest', completion['inputs']['observations'][0]['manifestPointer'])
        self.assertEqual(hashlib.sha256(obs.read_bytes()).hexdigest(), completion['inputs']['observations'][0]['sha256'])
        original_output = OUTPUT / 'original-output'
        subject.original.run_logged([sys.executable, '-B', str(subject.BASELINE / 'compare_baseline.py'),
                                    *common, '--output', str(original_output)], directory, 'original')
        actual = json.loads((output / 'coverage-0-private.json').read_text(encoding='utf-8'))
        expected = json.loads((original_output / 'coverage-private.json').read_text(encoding='utf-8'))
        self.assertEqual(expected, actual)
        before = (output / 'completion.json').read_bytes()
        with self.assertRaises(RuntimeError): subject.original.run_logged(command, directory, 'overwrite')
        self.assertEqual(before, (output / 'completion.json').read_bytes())

    def test_strict_reader_rejects_shell_shape_and_duplicate_json(self):
        with self.assertRaises(ValueError): subject.original.strict_json(b'{"manifest":{},"manifest":{}}')
        self.run_case({'operation': 'root_exec', 'stdout': '合成shell输出'}, reject=True)

    def test_relocated_layout_and_explicit_output_outside_tools(self):
        self.assertEqual(subject.HERE, subject.BASELINE)
        self.assertNotIn(subject.HERE, OUTPUT.parents)
        source = subject.BASELINE.parent.parent / 'app/src/main/java/net/elfradio/d31bootstrap/diagnostics'
        self.assertTrue((source / 'DiagnosticManifest.java').is_file())
        directory = OUTPUT / 'relocated-inputs'
        directory.mkdir()
        obs, fw = directory / 'observation.json', directory / 'firmware.json'
        subject.original.save_new(obs, fixture())
        subject.original.save_new(fw, fixture('100', True))
        command = [sys.executable, '-B', str(subject.HERE / 'interpret_initialization.py'),
                   '--observation', str(obs), '--firmware', str(fw), '--java-home', str(JAVA_HOME),
                   '--json-jar', str(JSON_JAR), '--now-ms', '150', '--output', 'explicit-private']
        before = (obs.read_bytes(), fw.read_bytes())
        run = subprocess.run(command, cwd=directory, capture_output=True, timeout=120)
        self.assertEqual(0, run.returncode, run.stderr.decode('utf-8', errors='replace'))
        self.assertTrue((directory / 'explicit-private/completion.json').is_file())
        self.assertEqual(before, (obs.read_bytes(), fw.read_bytes()))

    def test_existing_outputs_and_missing_parent_rejected_without_writes(self):
        directory = OUTPUT / 'output-rejections'
        directory.mkdir()
        empty = directory / 'empty'
        empty.mkdir()
        occupied = directory / 'occupied'
        subject.original.save_new(occupied, {'保留': True})
        before = occupied.read_bytes()
        for output in (empty, occupied, directory / 'missing-parent/child'):
            with self.subTest(output=output.name):
                command = [sys.executable, '-B', str(subject.HERE / 'interpret_initialization.py'),
                           '--observation', '未读取', '--firmware', '未读取', '--java-home', str(JAVA_HOME),
                           '--json-jar', str(JSON_JAR), '--output', str(output)]
                run = subprocess.run(command, cwd=directory, capture_output=True, timeout=120)
                self.assertNotEqual(0, run.returncode)
        self.assertEqual([], list(empty.iterdir()))
        self.assertEqual(before, occupied.read_bytes())
        self.assertFalse((directory / 'missing-parent').exists())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--json-jar', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    JAVA_HOME, JSON_JAR, OUTPUT = Path(args.java_home).resolve(), Path(args.json_jar).resolve(), Path(args.output).resolve()
    OUTPUT.mkdir(exist_ok=False)
    with (OUTPUT / '测试结果.txt').open('x', encoding='utf-8') as stream:
        result = unittest.TextTestRunner(stream=stream, verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(CompletionTests))
    subject.original.save_new(OUTPUT / '测试摘要.json', {'测试方法数': result.testsRun, '失败数': len(result.failures),
                              '错误数': len(result.errors), '通过': result.wasSuccessful(), '真机测试': False})
    print('测试方法数=%d，失败=%d，错误=%d' % (result.testsRun, len(result.failures), len(result.errors)))
    sys.exit(0 if result.wasSuccessful() else 1)
