"""只测试新增离线消费接线；复用原夹具构造器，不运行原18项测试。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest

sys.dont_write_bytecode = True
BASELINE = Path(__file__).resolve().parent
import prepare_start_sh_review as consumer


class ConsumerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixture.LoopTests.setUpClass()

    def setUp(self):
        self.f = fixture.LoopTests()
        self.f._testMethodName = self._testMethodName
        self.f.setUp()
        self.output = self.f.root / 'consumer'
        inputs = {role: str(self.f.root / 'before-inputs' / (role + '.json'))
                  for role in consumer.repair.eb.LOCAL_ROLES if role != 'report'}
        self.config = {key: self.f.config[key] for key in
                       ('device_id', 'task_id', 'mapping', 'sources', 'package', 'preimage', 'boot')}
        self.config.update(inputs=inputs, fixture=True, context_review={
            'purpose': consumer.repair.CONTEXT_POLICY,
            'observation_sha256': consumer.repair.digest(Path(inputs['observation']).read_bytes()),
            'firmware_sha256': consumer.repair.digest(Path(inputs['firmware']).read_bytes()),
            'acceptedDifferences': self.f.config['context_review']['acceptedDifferences']})
        fixture.put(self.f.root / 'consumer-config-private.json', self.config)

    def consume(self):
        return consumer.prepare_review(self.config, self.output, fixture.JAVA, fixture.NOW)

    def test_generated_requests_consumed_by_real_parser(self):
        result = self.consume()
        self.assertFalse(result['executed'])
        eb = consumer.repair.eb
        handoff = consumer.repair.obj(self.output / 'handoff-private.json')
        self.assertEqual('/data/local/d31-remote/repair-input/repair-fixture/artifacts/start-script', handoff['payload']['remote'])
        self.assertEqual(self.f.target, (self.output / handoff['payload']['local']).read_bytes())
        for item in handoff['requests'].values():
            eb.match_digest(item, (self.output / item['path']).read_bytes())
        result = subprocess.run([fixture.JAVA.java, '-cp', fixture.JAVA.classpath,
                                 'net.elfradio.d31bootstrap.RepairReviewRequestMain', str(self.output / 'prepared')],
                                capture_output=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr.decode())
        (self.f.root / 'request-parser.stdout.txt').write_bytes(result.stdout)
        self.assertIsNone(handoff['postEvidence']['after_file'])
        self.assertEqual('NOT_CHECKED', handoff['serverAuthorization'])
        review = (self.output / 'review-private.md').read_text(encoding='utf-8')
        self.assertIn('-ROOT=/data/local/d31-wrong-root', review)
        self.assertIn('+ROOT=/data/local/d31-system-support', review)

    def test_context_approval_bound_to_raw_observation(self):
        self.config['context_review']['observation_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'REVIEW_INPUT_BINDING'): self.consume()
        self.assertFalse(self.output.exists())

    def test_cli_consumes_local_config(self):
        result = subprocess.run([sys.executable, '-B', str(BASELINE / 'prepare_start_sh_review.py'),
                                 '--config', str(self.f.root / 'consumer-config-private.json'),
                                 '--output', str(self.output), '--java', fixture.JAVA.java,
                                 '--classpath', fixture.JAVA.classpath], capture_output=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertFalse(json.loads(result.stdout)['executed'])
        self.assertTrue((self.output / 'handoff-private.json').is_file())
        (self.f.root / 'cli.stdout.txt').write_bytes(result.stdout)

    def test_context_approval_bound_to_raw_firmware(self):
        self.config['context_review']['firmware_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'REVIEW_INPUT_BINDING'): self.consume()
        self.assertFalse(self.output.exists())

    def test_unapproved_context_has_no_handoff(self):
        self.config['context_review']['acceptedDifferences'] = {}
        with self.assertRaisesRegex(ValueError, 'UNREVIEWED_CONTEXT_DIFFERENCE'): self.consume()
        self.assertFalse((self.output / 'handoff-private.json').exists())

    def test_supplied_report_cannot_replace_computed_comparison(self):
        self.config['inputs']['report'] = str(self.f.root / 'before-inputs/report.json')
        with self.assertRaisesRegex(ValueError, 'RAW_INPUT_ROLES_REQUIRED'): self.consume()

    def test_existing_review_preserved(self):
        self.consume()
        old = (self.output / 'handoff-private.json').read_bytes()
        with self.assertRaises(FileExistsError): self.consume()
        self.assertEqual(old, (self.output / 'handoff-private.json').read_bytes())

    def same_content_config(self, mutate=None):
        self.f.bundle(self.f.target, 'same', fixture.NOW - 2000, fixture.NOW - 1900, mutate)
        for role in self.config['inputs']:
            self.config['inputs'][role] = str(self.f.root / 'same-inputs' / (role + '.json'))
        self.config['preimage'] = self.f.file_proof(self.f.target, consumer.repair.TARGET, fixture.NOW - 1700)
        self.config['context_review']['observation_sha256'] = consumer.repair.digest(
            Path(self.config['inputs']['observation']).read_bytes())

    def test_same_content_is_explicit_no_plan(self):
        self.same_content_config()
        result = self.consume()
        self.assertEqual('NO_CONTENT_DIFFERENCE', result['status'])
        self.assertFalse(result['repairPlanGenerated'])
        self.assertFalse(result['executed'])
        self.assertFalse((self.output / 'prepared').exists())
        self.assertFalse((self.output / 'handoff-private.json').exists())
        self.assertTrue((self.output / 'result.json').exists())
        self.assertEqual('NOT_ASSESSED', result['systemConsistency'])

    def test_same_content_with_unknown_semantic_still_rejected(self):
        def unknown(m):
            m['entries'][0]['fields'][consumer.repair.FIELD] = {'state': 'NOT_CHECKED', 'source': 'fixture', 'reason': 'UNKNOWN'}
        self.same_content_config(unknown)
        with self.assertRaisesRegex(ValueError, 'UNKNOWN_semantic'): self.consume()
        self.assertFalse((self.output / 'result.json').exists())

    def test_same_content_stale_capture_still_rejected(self):
        self.same_content_config()
        with self.assertRaisesRegex(ValueError, 'STALE_OR_FUTURE_OBSERVATION'):
            consumer.prepare_review(self.config, self.output, fixture.JAVA, fixture.NOW + 400000)
        self.assertFalse((self.output / 'result.json').exists())

    def test_capture_plan_cli_only_writes_existing_read_requests(self):
        identity = self.f.manifest(self.f.target, 'unused', fixture.NOW)['context']
        config = {'device_id': 'fixture-device', 'expected_version': 176, 'apk_sha256': 'a' * 64,
                  'identity': {'baselineId': 'unapproved-device', 'baselineRevision': '176',
                               'firmwareId': 'D31-development-176', 'build': 'fixture-build', 'context': identity},
                  **{key: self.config[key] for key in ('mapping', 'sources', 'package')},
                  'firmware': self.config['inputs']['firmware'], 'firmware_generation': self.config['inputs']['firmware_generation']}
        filename = self.f.root / 'capture-config-private.json'
        fixture.put(filename, config)
        result = subprocess.run([sys.executable, '-B', str(BASELINE / 'prepare_start_sh_review.py'),
                                 '--capture-plan', '--config', str(filename), '--output', str(self.output)],
                                capture_output=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertFalse(json.loads(result.stdout)['executed'])
        plan = consumer.repair.obj(self.output / 'capture-plan-private.json')
        self.assertEqual(7, len(plan['steps']))
        requests = [consumer.repair.obj(self.output / step['request']) for step in plan['steps']]
        self.assertEqual(7, len({r['id'] for r in requests}))
        self.assertEqual(['root_exec', 'root_exec', 'root_exec', 'get_file', 'get_file', 'root_exec', 'root_exec'], [r['type'] for r in requests])
        for request in requests:
            if request['type'] == 'get_file': self.assertIs(request['params']['allow_cellular'], False)
        diagnostic_id, diagnostic = consumer.repair.eb.command_request(requests[2]['params']['command'])
        self.assertEqual(plan['diagnosticId'], diagnostic_id)
        self.assertEqual(consumer.repair.TARGET, diagnostic['scope'])
        self.assertEqual('/data/local/d31-remote/diagnostics/' + diagnostic_id + '/report.json', requests[3]['params']['path'])
        self.assertEqual(consumer.repair.TARGET, requests[4]['params']['path'])
        template = consumer.repair.obj(self.output / 'consumer-config-template-private.json')
        self.assertIsNone(template['context_review']['acceptedDifferences'])
        self.assertIsNone(template['context_review']['observation_sha256'])
        another = self.f.root / 'another-plan'
        consumer.capture_plan(config, another, fixture.NOW)
        self.assertNotEqual(plan['diagnosticId'], consumer.repair.obj(another / 'capture-plan-private.json')['diagnosticId'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java-home', 'json-jar', 'firmware-zip', 'output'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    output = Path(args.output).absolute()
    output.mkdir(exist_ok=False)
    for directory in ('frozen', 'classes', 'dependencies'):
        (output / directory).mkdir()
    project = BASELINE.parents[1]
    source = project / 'app/src/main/java/net/elfradio/d31bootstrap'
    java_sources = [source / 'diagnostics' / (name + '.java') for name in
                    ('DiagnosticContract', 'DiagnosticManifest', 'DiagnosticCoverageComparison')]
    java_sources += [source / 'repair/RepairPlan.java', source / 'repair/RepairFiles.java', source / 'RemoteRepairRequest.java',
                     BASELINE / 'StartShRepairMain.java', BASELINE / 'regression/RepairReviewRequestMain.java']
    records = []
    def freeze(path, destination):
        data = path.read_bytes()
        with destination.open('xb') as f: f.write(data)
        records.append({'source': str(path), 'frozen': str(destination.relative_to(output)),
                        'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)})
    for path in java_sources:
        freeze(path, output / 'frozen' / path.name)
    for name in ('start_sh_repair.py', 'prepare_start_sh_review.py', 'test_start_sh_repair.py', 'test_prepare_start_sh_review.py'):
        freeze(BASELINE / name, output / 'frozen' / name)
    for name in ('evidence_bundle.py', 'compare_baseline.py'):
        freeze(BASELINE / name, output / 'dependencies' / name)
    freeze(Path(args.json_jar).resolve(), output / 'frozen/json.jar')
    suffix = '.exe' if os.name == 'nt' else ''
    java = Path(args.java_home).resolve() / 'bin' / ('java' + suffix)
    classpath = str(output / 'classes') + os.pathsep + str(output / 'frozen/json.jar')
    command = [str(java.with_name('javac' + suffix)), '--release', '8', '-encoding', 'UTF-8',
               '-cp', str(output / 'frozen/json.jar'), '-d', str(output / 'classes')]
    command += [str(output / 'frozen' / path.name) for path in java_sources]
    compile_result = subprocess.run(command, capture_output=True, timeout=60)
    (output / 'compile.stdout.txt').write_bytes(compile_result.stdout)
    (output / 'compile.stderr.txt').write_bytes(compile_result.stderr)
    os.environ.update(REPAIR_TEST_OUTPUT=str(output), REPAIR_JAVA=str(java), REPAIR_CLASSPATH=classpath,
                      REPAIR_FIRMWARE_ZIP=str(Path(args.firmware_zip).resolve()))
    passed, count = False, 0
    start = time.monotonic()
    if compile_result.returncode == 0:
        global fixture
        spec = importlib.util.spec_from_file_location('repair_fixture_helpers', BASELINE / 'test_start_sh_repair.py')
        fixture = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fixture)
        fixture.NOW = time.time_ns() // 1000000
        consumer.repair.dependencies(BASELINE)
        with (output / 'tests.txt').open('x', encoding='utf-8') as log:
            result = unittest.TextTestRunner(stream=log, verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ConsumerTests))
        passed, count = result.wasSuccessful(), result.testsRun
    unchanged = all(hashlib.sha256(Path(r['source']).read_bytes()).hexdigest() == r['sha256'] for r in records)
    result = {'通过': passed and unchanged, '测试数': count, '秒数': time.monotonic() - start,
              '编译退出码': compile_result.returncode, '源码未变': unchanged,
              '设备网络及修复': '未访问或执行', '原18项测试': '未重复运行', '输入': records, '编译命令': command}
    with (output / 'result.json').open('x', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(json.dumps({'passed': result['通过'], 'tests': count, 'output': str(output)}))
    raise SystemExit(0 if result['通过'] else 1)


if __name__ == '__main__':
    main()
