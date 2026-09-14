"""仅使用明确合成夹具；原始私有材料由另一个只读检查入口验证。"""

import copy
import io
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import sys
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import evidence_bundle as subject
TEST_ROOT = Path(__file__).resolve().parent


def fixtures():
    identity = {'snapshotId': 'fixture-observation', 'baselineId': 'fixture-unapproved',
                'baselineRevision': '0', 'firmwareId': 'fixture-firmware', 'build': 'fixture-build',
                'context': {k: 'NOT_CHECKED' for k in subject.CONTEXT}}
    evidence = {'state': 'OBSERVED', 'value': 'fixture-value', 'source': 'fixture-source'}
    manifest = {**identity, 'schemaVersion': 1, 'role': 'TARGET', 'collectorVersion': 'fixture',
                'capturedAtMs': 100, 'validUntilMs': 200, 'uptimeMs': 10, 'completeness': 'PARTIAL',
                'scope': [{'path': '/fixture', 'state': 'PARTIAL', 'source': 'fixture-source'}],
                'entries': [{'path': '/fixture/start.sh', 'presence': {'state': 'OBSERVED', 'value': 'PRESENT'},
                             'fields': {'semantic.system_support.root': evidence}}]}
    firmware = copy.deepcopy(manifest)
    firmware.update(role='FIRMWARE', snapshotId='fixture-firmware-snapshot', uptimeMs=0)
    firmware['scope'][0]['source'] = 'explicit-install-map:sha256:' + 'b' * 64
    observation = {'operation': 'manifest', 'manifest': manifest,
                   'index': {'snapshotId': identity['snapshotId'], 'scope': '/fixture', 'atomicSnapshot': False}}
    side = {'state': 'OBSERVED', 'pointer': '/entries/0/fields/semantic.system_support.root', 'observed': True,
            'presence': {'state': 'OBSERVED', 'pointer': '/entries/0/presence'}}
    report = {'schemaVersion': 1, 'engineVersion': 'coverage-1.1.0', 'method': subject.METHOD,
              'derivedAtMs': 150, 'systemConsistency': 'NOT_ASSESSED', 'repairPlanGenerated': False,
              'bindingEqual': {k: True for k in ('build', 'baselineId', 'baselineRevision', 'firmwareId')},
              'contextEqual': {k: True for k in subject.CONTEXT},
              'entries': [{'path': '/fixture/start.sh', 'fields': [{'field': 'semantic.system_support.root',
                           'pair': 'SAME', 'observation': copy.deepcopy(side), 'firmware': copy.deepcopy(side)}]}],
              'configurationCoverage': {'catalogId': 'd31-finite-configuration', 'catalogVersion': 3,
                  'items': [{'id': 'system_support.root', 'path': '/fixture/start.sh', 'field': 'semantic.system_support.root',
                             'observation': copy.deepcopy(side), 'firmware': copy.deepcopy(side)}]}}
    for role, value in (('observation', manifest), ('firmware', firmware)):
        report[role] = {k: value[k] for k in ('role', 'capturedAtMs', 'validUntilMs', 'completeness')}
        report[role]['freshness'] = 'WITHIN_DECLARED_WINDOW'
    request = {'operation': 'manifest', 'scope': '/fixture', 'identity': identity}
    receipt = {'id': 'a' * 64, 'state': 'completed',
               'path': '/data/local/d31-remote/diagnostics/' + 'a' * 64 + '/report.json',
               **subject.digest(subject.encode(observation)), 'systemConsistency': 'NOT_ASSESSED'}
    command = 'CLASSPATH=/fixture.apk /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteDiagnosticCommand '
    command += receipt['id'] + ' ' + shlex.quote(json.dumps(request))
    parent = {'device_id': 'fixture-device', 'id': 'fixture-web-task', 'type': 'root_exec', 'params': {'command': command}}
    result = {'ok': True, 'task': {'id': parent['id'], 'type': 'root_exec', 'state': 'success',
              'created_at': 110, 'started_at': 120, 'completed_at': 130,
              'result': {'exit_code': 0, 'truncated': False, 'text': json.dumps(receipt)}}}
    source = {'schemaVersion': 1, 'snapshotId': firmware['snapshotId'], 'mappingSha256': 'b' * 64,
              'generatorSha256': 'c' * 64, 'bindings': {'installerSourceSha256': 'd' * 64,
              'sourceListSha256': 'e' * 64, 'packageSha256': 'f' * 64}, 'inputKind': 'DIRECTORY',
              'packageEvidence': {'state': 'NOT_CHECKED', 'reason': '合成展开目录，不代表固件包已核验'}}
    raw = {k: subject.encode(v) for k, v in {'request': request, 'receipt': receipt, 'observation': observation,
           'firmware': firmware, 'firmware_generation': source, 'parent_request': parent, 'parent_result': result}.items()}
    report['inputs'] = {k: {**subject.digest(raw[k]), 'manifestPointer': '/manifest' if k == 'observation' else ''}
                        for k in ('observation', 'firmware')}
    raw['report'] = subject.encode(report)
    return raw


def change(raw, role, callback):
    obj = subject.baseline.strict_json(raw[role])
    callback(obj)
    raw[role] = subject.encode(obj)


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.raw = fixtures()

    def build(self):
        return subject.build_manifest(self.raw, True, 160)

    def reject(self, role, callback, error):
        change(self.raw, role, callback)
        with self.assertRaisesRegex(ValueError, error):
            self.build()

    def test_six_web_roles_and_explicit_server_boundary(self):
        bundle = self.build()
        self.assertEqual(set(subject.ROLES), set(bundle['attachments']))
        self.assertEqual(6, len(bundle['uploadInitializations']))
        for row in bundle['uploadInitializations']:
            self.assertEqual('evidence', row['body']['purpose'])
            self.assertIs(True, row['body']['evidence']['fixture'])
            self.assertEqual('fixture-web-task', row['body']['evidence']['task_id'])
        self.assertFalse(bundle['identity']['parentResponseDevicePresent'])
        self.assertEqual('NOT_CHECKED', bundle['status']['serverAuthorization'])
        self.assertFalse(bundle['status']['uploaded'])
        self.assertFalse(bundle['status']['repairPlanGenerated'])
        self.assertEqual('NOT_CHECKED', bundle['firmwareChecks']['packageVerification'])

    def test_byte_whitespace_change_rejected(self):
        self.raw['firmware'] += b' '
        with self.assertRaisesRegex(ValueError, 'DIGEST_MISMATCH'):
            self.build()

    def test_digest_length_bool_rejected(self):
        self.reject('report', lambda r: r['inputs']['observation'].update(bytes=True), 'INVALID_INTEGER')

    def test_reordered_report_members_preserve_raw_attachment(self):
        obj = subject.baseline.strict_json(self.raw['report'])
        self.raw['report'] = json.dumps(dict(reversed(list(obj.items())))).encode()
        self.assertEqual(subject.digest(self.raw['report'])['sha256'], self.build()['attachments']['report']['sha256'])

    def test_duplicate_members_and_numbers(self):
        for bad in (b'{"a":1,"\\u0061":2}', b'{"x":NaN}', b'{"x":1.2}', b'{}{}'):
            with self.subTest(bad=bad):
                self.raw['request'] = bad
                with self.assertRaises(ValueError):
                    self.build()

    def test_wrong_manifest_wrapper(self):
        self.reject('report', lambda r: r['inputs']['observation'].update(manifestPointer=''), 'MANIFEST_POINTER_MISMATCH')

    def test_wrong_device(self):
        self.reject('parent_result', lambda r: r['task'].update(device_id='fixture-other'), 'PARENT_DEVICE_MISMATCH')

    def test_wrong_task(self):
        self.reject('parent_result', lambda r: r['task'].update(id='fixture-other'), 'PARENT_TASK_MISMATCH')

    def test_wrong_diagnostic(self):
        self.reject('parent_request', lambda r: r['params'].update(command=r['params']['command'].replace('a' * 64, 'b' * 64)),
                    'RECEIPT_DIAGNOSTIC_MISMATCH')

    def test_request_identity_mismatch(self):
        self.reject('request', lambda r: r['identity']['context'].update(model='fixture-other'), 'PARENT_REQUEST_MISMATCH')

    def test_request_and_parent_cannot_override_observed_identity(self):
        req = subject.baseline.strict_json(self.raw['request'])
        req['identity']['context']['model'] = 'fixture-other'
        self.raw['request'] = subject.encode(req)
        change(self.raw, 'parent_request', lambda p: p['params'].update(command=
               p['params']['command'].split(' ' + 'a' * 64)[0] + ' ' + 'a' * 64 + ' ' + shlex.quote(json.dumps(req))))
        with self.assertRaisesRegex(ValueError, 'REQUEST_IDENTITY_MISMATCH'):
            self.build()

    def test_receipt_not_a_report_or_authorization(self):
        self.reject('receipt', lambda r: r.update(sha256='0' * 64), 'PARENT_RECEIPT_MISMATCH')

    def test_truncated_parent_rejected(self):
        self.reject('parent_result', lambda r: r['task']['result'].update(truncated=True), 'PARENT_OUTPUT_INCOMPLETE')

    def test_parent_command_is_data_never_executed(self):
        self.reject('parent_request', lambda r: r['params'].update(command=r['params']['command'] + '; arbitrary-command'),
                    'DIAGNOSTIC_COMMAND_SHAPE')

    def test_known_wrong_pointer(self):
        self.reject('report', lambda r: r['entries'][0]['fields'][0]['observation'].update(pointer='/entries/0/presence'),
                    'EVIDENCE_POINTER_TARGET_MISMATCH')

    def test_wrong_row_path(self):
        self.reject('report', lambda r: r['entries'][0].update(path='/fixture/other'), 'EVIDENCE_PATH_MISMATCH')

    def test_wrong_state(self):
        self.reject('report', lambda r: r['entries'][0]['fields'][0]['observation'].update(state='NOT_CHECKED'),
                    'EVIDENCE_STATE_MISMATCH')

    def test_missing_pointer_is_gap(self):
        change(self.raw, 'report', lambda r: r['entries'][0]['fields'][0]['observation'].update(pointer=None))
        ref = self.build()['reportChecks']['references'][0]
        self.assertEqual('NO_DIRECT_EVIDENCE', ref['status'])
        self.assertIsNone(ref['pointer'])

    def test_unknown_catalog_not_upgraded(self):
        change(self.raw, 'report', lambda r: r['configurationCoverage'].update(catalogVersion=999))
        self.assertEqual('UNSUPPORTED_OR_ABSENT', self.build()['reportChecks']['catalog'])

    def test_context_boolean_not_trusted(self):
        self.reject('report', lambda r: r['bindingEqual'].update(build=False), 'REPORT_CONTEXT_MISMATCH')

    def test_expired_future_kept_historical(self):
        for now, state in ((50, 'FUTURE'), (250, 'EXPIRED')):
            self.raw = fixtures()
            def mutate(r):
                r['derivedAtMs'] = now
                for side in ('observation', 'firmware'):
                    r[side]['freshness'] = state
            change(self.raw, 'report', mutate)
            self.assertEqual('NOT_ASSESSED', self.build()['status']['systemConsistency'])

    def test_firmware_snapshot_mismatch(self):
        self.reject('firmware_generation', lambda r: r.update(snapshotId='fixture-other'), 'FIRMWARE_SNAPSHOT_MISMATCH')

    def test_firmware_mapping_mismatch(self):
        self.reject('firmware_generation', lambda r: r.update(mappingSha256='0' * 64), 'FIRMWARE_MAPPING_MISMATCH')

    def test_firmware_zip_evidence_is_not_rehash(self):
        change(self.raw, 'firmware_generation', lambda r: r.update(inputKind='ZIP', packageEvidence={'sha256': 'f' * 64, 'bytes': 123}))
        self.assertEqual('SOURCE_REPORT_DIGEST_CONSISTENT_PACKAGE_NOT_REHASHED', self.build()['firmwareChecks']['packageVerification'])

    def test_firmware_zip_mismatch(self):
        self.reject('firmware_generation', lambda r: r.update(inputKind='ZIP', packageEvidence={'sha256': '0' * 64, 'bytes': 123}),
                    'FIRMWARE_PACKAGE_MISMATCH')

    def test_firmware_original_uppercase_hashes_preserved(self):
        change(self.raw, 'firmware_generation', lambda r: r['bindings'].update(packageSha256='F' * 64))
        self.assertEqual('F' * 64, self.build()['firmwareChecks']['bindings']['packageSha256'])

    def test_boolean_schema_is_not_version_one(self):
        self.reject('report', lambda r: r.update(schemaVersion=True), 'REPORT_SCHEMA_UNSUPPORTED')

    def test_legacy_request_receipt_envelopes(self):
        parent = subject.baseline.strict_json(self.raw['parent_request'])
        self.raw['request'] = subject.encode({'id': 'fixture-host-id', 'command': parent['params']['command'], 'timeout': 120})
        self.raw['receipt'] = subject.encode({'output': self.raw['receipt'].decode(), 'truncated': False, 'exit_code': 0})
        self.assertEqual('CONSISTENT', self.build()['status']['localBinding'])

    def test_pointer_escaping_in_full_report(self):
        field = 'semantic.a/b~c'
        for role in ('observation', 'firmware'):
            def rename(obj):
                manifest = obj['manifest'] if role == 'observation' else obj
                fields = manifest['entries'][0]['fields']
                fields[field] = fields.pop('semantic.system_support.root')
            change(self.raw, role, rename)
        def update_report(r):
            r['configurationCoverage']['catalogVersion'] = 999
            r['entries'][0]['fields'][0]['field'] = field
            for role in ('observation', 'firmware'):
                r['inputs'][role].update(subject.digest(self.raw[role]))
                r['entries'][0]['fields'][0][role]['pointer'] = '/entries/0/fields/semantic.a~1b~0c'
        change(self.raw, 'report', update_report)
        receipt = subject.baseline.strict_json(self.raw['receipt'])
        receipt.update(subject.digest(self.raw['observation']))
        self.raw['receipt'] = subject.encode(receipt)
        change(self.raw, 'parent_result', lambda r: r['task']['result'].update(text=json.dumps(receipt)))
        self.assertEqual('/manifest/entries/0/fields/semantic.a~1b~0c', self.build()['reportChecks']['references'][0]['pointer'])

    def test_network_paths_rejected_before_stat(self):
        for path in ('//example/share/file', '\\\\example\\share\\file', 'https://example/file'):
            with self.subTest(path=path), patch.object(Path, 'lstat', side_effect=AssertionError('不应访问路径')):
                with self.assertRaisesRegex(ValueError, 'LOCAL_PATH_REQUIRED'):
                    subject.read_bytes(path)

    def test_byte_limit(self):
        with self.assertRaises(ValueError):
            subject.baseline.strict_json(b' ' * (subject.baseline.MAX_BYTES + 1))

    def test_pointer_standard(self):
        obj = {'a/b': {'~key': [{'semantic.foo.bar': 7}]}}
        self.assertEqual(7, subject.resolve_pointer(obj, '/a~1b/~0key/0/semantic.foo.bar'))
        self.assertIs(obj, subject.resolve_pointer(obj, ''))
        for p in ('/a~2b', '/a~1b/~0key/01', '/a~1b/~0key/-1', '/a~1b/~0key/-', '/missing'):
            with self.subTest(pointer=p), self.assertRaises(ValueError):
                subject.resolve_pointer(obj, p)

    def test_prepare_verify_no_overwrite_and_tampering(self):
        with tempfile.TemporaryDirectory(dir=TEST_ROOT, prefix='fixture-test-') as directory:
            root = Path(directory)
            paths = {}
            for role, raw in self.raw.items():
                paths[role] = root / (role + '.json')
                subject.write_new(paths[role], raw)
            output = root / 'bundle-private'
            result = subject.prepare(paths, output, True, 160)
            self.assertEqual(result, subject.verify(output, result['sha256']))
            with self.assertRaises(FileExistsError):
                subject.prepare(paths, output, True, 160)
            for role in subject.ROLES:
                self.assertEqual(self.raw[role], (output / 'attachments-private' / (role + '.json')).read_bytes())
            with self.assertRaisesRegex(ValueError, 'MANIFEST_DIGEST_MISMATCH'):
                subject.verify(output, '0' * 64)
            (output / 'attachments-private/report.json').write_bytes(b'{}')
            with self.assertRaisesRegex(ValueError, 'DIGEST_MISMATCH'):
                subject.verify(output, result['sha256'])

    def test_partial_write_never_commits(self):
        with tempfile.TemporaryDirectory(dir=TEST_ROOT, prefix='fixture-test-') as directory:
            root = Path(directory)
            paths = {role: root / (role + '.json') for role in subject.LOCAL_ROLES}
            for role, path in paths.items():
                subject.write_new(path, self.raw[role])
            with patch.object(subject, 'write_new', side_effect=OSError('模拟磁盘写失败')):
                with self.assertRaises(OSError):
                    subject.prepare(paths, root / 'bundle-private', True, 160)
            self.assertFalse((root / 'bundle-private/local-commit.json').exists())

    def test_cli_error_does_not_echo_private_data(self):
        with patch.object(subject, 'prepare', side_effect=ValueError('fixture-private-secret')), patch('sys.stderr', new_callable=io.StringIO) as err:
            args = ['prepare', '--output', 'unused']
            for role in subject.LOCAL_ROLES:
                args.extend(['--' + role.replace('_', '-'), 'unused'])
            self.assertEqual(2, subject.main(args))
            self.assertNotIn('fixture-private-secret', err.getvalue())


    def assert_cli_rejects_object_type(self, role, mutate, code):
        change(self.raw, role, mutate)
        with tempfile.TemporaryDirectory(dir=TEST_ROOT, prefix='fixture-private-') as directory:
            root = Path(directory)
            output = root / 'bundle-private'
            args = [sys.executable, '-B', str(Path(subject.__file__).resolve()), 'prepare',
                    '--fixture', '--output', str(output)]
            paths = {}
            for attachment, raw in self.raw.items():
                path = root / (attachment + '.json')
                subject.write_new(path, raw)
                paths[attachment] = path
                args.extend(['--' + attachment.replace('_', '-'), str(path)])
            completed = subprocess.run(args, capture_output=True, timeout=20)
            self.assertEqual(2, completed.returncode)
            self.assertEqual(b'', completed.stdout)
            self.assertIn(code.encode('ascii'), completed.stderr)
            for forbidden in (b'Traceback', b'AttributeError', b'fixture-private-',
                              b'fixture-device', b'fixture-value'):
                self.assertNotIn(forbidden, completed.stderr)
            self.assertFalse(output.exists())
            for attachment, path in paths.items():
                self.assertEqual(self.raw[attachment], path.read_bytes())

    def test_cli_null_parent_result_rejected_without_traceback(self):
        self.assert_cli_rejects_object_type(
            'parent_result', lambda obj: obj['task'].update(result=None),
            'PARENT_OUTPUT_REQUIRED')

    def test_cli_list_configuration_coverage_rejected_without_traceback(self):
        self.assert_cli_rejects_object_type(
            'report', lambda obj: obj.update(configurationCoverage=[]),
            'CONFIGURATION_COVERAGE_OBJECT_REQUIRED')

    def test_cli_list_evidence_reference_rejected_without_traceback(self):
        self.assert_cli_rejects_object_type(
            'report', lambda obj: obj['entries'][0]['fields'][0].update(observation=[]),
            'EVIDENCE_REFERENCE_REQUIRED')


if __name__ == '__main__':
    unittest.main(verbosity=2)
