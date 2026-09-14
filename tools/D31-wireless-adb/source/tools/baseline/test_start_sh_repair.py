"""公开合成父任务及真实临时事务回归；不含私有captures，不调用设备。"""
import copy
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import unittest
import zipfile

sys.dont_write_bytecode = True
BASELINE = Path(__file__).resolve().parent
OUT = Path(os.environ['REPAIR_TEST_OUTPUT'])
spec = importlib.util.spec_from_file_location('start_sh_repair', BASELINE / 'start_sh_repair.py')
s = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)
s.dependencies(OUT / 'dependencies')
eb = s.eb
JAVA = s.Java(os.environ['REPAIR_JAVA'], os.environ['REPAIR_CLASSPATH'])
NOW = 1000000
BOOT = '11111111-2222-3333-4444-555555555555'


def put(file, value):
    file.write_bytes(eb.encode(value))
    return str(file)


class LoopTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(os.environ['REPAIR_FIRMWARE_ZIP']) as z:
            cls.target = z.read('payload/system-patches/support-start.sh')
        cls.original = cls.target.replace(b'ROOT=/data/local/d31-system-support\n', b'ROOT=/data/local/d31-wrong-root\n', 1)
        assert cls.target != cls.original

    def setUp(self):
        self.root = OUT / self._testMethodName
        self.root.mkdir()
        self.counter = 0
        sources = {'system_payload/support-start.sh': [len(self.target), s.digest(self.target)]}
        self.sources = self.root / 'sources.json'; put(self.sources, sources)
        inventory = eb.encode({'文件': [{'path': 'payload/system-patches/support-start.sh', **eb.digest(self.target)}]})
        package = self.root / 'fixture.zip'
        with zipfile.ZipFile(package, 'x') as z:
            z.writestr('payload/system-patches/support-start.sh', self.target)
            z.writestr('payload/manifest.json', inventory)
        mapped = {'action': 'copy', 'source': 'payload/system-patches/support-start.sh', 'destination': s.TARGET, 'mode': '0700', 'uid': 0, 'gid': 0}
        bindings = {'installerSourceSha256': '1' * 64, 'sourceListSha256': s.digest(self.sources.read_bytes()), 'packageSha256': s.digest(package.read_bytes())}
        mapping = {'schemaVersion': 1, 'firmwareId': 'D31-factory-1.4.4', 'build': 'fixture-build', 'bindings': bindings,
                   'inventory': {'path': 'payload/manifest.json', 'sha256': s.digest(inventory)}, 'entries': [mapped]}
        self.mapping = self.root / 'mapping.json'; put(self.mapping, mapping)
        mapping_sha = s.digest(self.mapping.read_bytes())
        self.firmware = self.manifest(self.target, 'firmware', NOW - 10000, 'FIRMWARE')
        self.firmware['context']['stage'] = 'POST_INSTALL_BEFORE_FIRST_BOOT'
        self.firmware['scope'][0]['source'] = 'explicit-install-map:sha256:' + mapping_sha
        self.generation = {'schemaVersion': 1, 'snapshotId': 'firmware', 'mappingSha256': mapping_sha, 'generatorSha256': '2' * 64,
                           'bindings': bindings, 'inputKind': 'ZIP', 'packageEvidence': eb.digest(package.read_bytes())}
        before, seal = self.bundle(self.original, 'before', NOW - 2000, NOW - 1900)
        self.config = {'evidence_bundle': str(before), 'evidence_sha256': seal['sha256'], 'device_id': 'fixture-device',
             'task_id': 'repair-fixture', 'mapping': str(self.mapping), 'sources': str(self.sources), 'package': str(package),
             'context_review': {'purpose': s.CONTEXT_POLICY, 'evidence_sha256': seal['sha256'], 'acceptedDifferences': {
                 'context.stage': {'observation': 'RUNNING', 'firmware': 'POST_INSTALL_BEFORE_FIRST_BOOT'}}},
             'preimage': self.file_proof(self.original, s.TARGET, NOW - 1700), 'boot': self.boot_proof(NOW - 2100)}

    def manifest(self, data, name, captured, role='TARGET'):
        def observed(v): return {'state': 'OBSERVED', 'value': v, 'source': 'fixture'}
        fields = {k: {'state': 'NOT_CHECKED', 'reason': 'FIXTURE_UNCHECKED', 'source': 'fixture'} for k in
                  ('type', 'sha256', 'mode', 'uid', 'gid', 'link', 'selinux', 'xattrs', 'activeSource', 'mountSource', 'activation')}
        fields.update({k: observed(v) for k, v in {'type': 'file', 'sha256': s.digest(data), 'mode': '0700', 'uid': 0, 'gid': 0,
                       s.FIELD: s.root_script(data)[0]}.items()})
        return {'schemaVersion': 1, 'role': role, 'snapshotId': name, 'baselineId': 'fixture-baseline', 'baselineRevision': '1',
                'firmwareId': 'D31-factory-1.4.4', 'build': 'fixture-build', 'collectorVersion': 'fixture',
                'capturedAtMs': captured, 'validUntilMs': NOW + 10000, 'uptimeMs': captured,
                'context': {'model': 'D31', 'hardwareClass': 'SVP3390', 'firmwareFamily': 'D31-factory', 'stage': 'RUNNING',
                            'network': 'NOT_CHECKED', 'sim': 'NOT_CHECKED', 'storage': 'NOT_CHECKED'},
                'completeness': 'COMPLETE', 'scope': [{'path': s.TARGET, 'state': 'COMPLETE', 'source': 'fixture'}],
                'entries': [{'path': s.TARGET, 'presence': observed('PRESENT'), 'fields': fields}]}

    def bundle(self, data, name, captured, started, mutate=None):
        m = self.manifest(data, name, captured)
        if mutate: mutate(m)
        identity = {k: m[k] for k in eb.IDENTITY}
        observation = {'operation': 'manifest', 'manifest': m, 'index': {'snapshotId': name, 'scope': s.TARGET, 'state': 'COMPLETE', 'atomicSnapshot': False}}
        report = JAVA.call('compare', {'observation': m, 'firmware': self.firmware, 'now': NOW})
        raw = {'observation': eb.encode(observation), 'firmware': eb.encode(self.firmware)}
        report['inputs'] = {k: {**eb.digest(v), 'manifestPointer': '/manifest' if k == 'observation' else ''} for k, v in raw.items()}
        self.counter += 1
        diagnostic = s.digest((name + str(self.counter)).encode())
        request = {'operation': 'manifest', 'scope': s.TARGET, 'identity': identity}
        receipt = {'id': diagnostic, 'state': 'completed', 'path': '/data/local/d31-remote/diagnostics/' + diagnostic + '/report.json', **eb.digest(raw['observation'])}
        command = 'CLASSPATH=/data/local/d31-remote/releases/' + 'a' * 64 + '/remote.apk /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteDiagnosticCommand ' + diagnostic + ' ' + shlex.quote(json.dumps(request))
        parent, result = self.parent('root_exec', {'command': command}, {'exit_code': 0, 'truncated': False, 'text': json.dumps(receipt)}, started)
        objects = {'request': request, 'receipt': receipt, 'report': report, 'observation': observation, 'firmware': self.firmware,
                   'firmware_generation': self.generation, 'parent_request': parent, 'parent_result': result}
        inputs = self.root / (name + '-inputs'); inputs.mkdir()
        paths = {k: put(inputs / (k + '.json'), v) for k, v in objects.items()}
        out = self.root / (name + '-bundle')
        seal = eb.prepare(paths, out, fixture=True, now_ms=NOW)
        return out, seal

    def parent(self, kind, params, result, started):
        self.counter += 1
        request = {'device_id': 'fixture-device', 'id': 'fixture-parent-' + str(self.counter), 'type': kind, 'params': params}
        response = {'ok': True, 'task': {'device_id': 'fixture-device', 'id': request['id'], 'type': kind, 'params': params,
                    'state': 'success', 'started_at': started, 'completed_at': started + 10, 'result': result}}
        return request, response

    def proof(self, request, result, data=None):
        self.counter += 1
        base = self.root / ('proof-' + str(self.counter)); base.mkdir()
        proof = {'request': put(base / 'request.json', request), 'result': put(base / 'result.json', result)}
        if data is not None:
            (base / 'data.bin').write_bytes(data); proof['file'] = str(base / 'data.bin')
        return proof

    def file_proof(self, data, path, started):
        request, result = self.parent('get_file', {'path': path, 'allow_cellular': False}, {'action': 'uploaded', **eb.digest(data)}, started)
        return self.proof(request, result, data)

    def boot_proof(self, started, boot=BOOT):
        req, res = self.parent('root_exec', {'command': '/system/bin/cat /proc/sys/kernel/random/boot_id'},
                              {'exit_code': 0, 'truncated': False, 'text': boot + '\n'}, started)
        return self.proof(req, res)

    def prepare(self):
        out = self.root / 'prepared'
        result = s.prepare(self.config, out, JAVA, NOW)
        return out, result

    def verified_config(self, mode='success'):
        out, result = self.prepare()
        prepared = s.obj(out / 'prepared-private.json')
        plan = self.root / 'plan.json'; put(plan, prepared['plan'])
        transaction = self.root / 'transaction'
        run = subprocess.run([JAVA.java, '-cp', JAVA.classpath, 'net.elfradio.d31bootstrap.repair.RepairLoopFixture', str(transaction),
              str(plan), str(out / 'preimage-private.sh'), str(out / 'target-payload.sh'), mode, str(NOW - 500)], capture_output=True)
        self.assertEqual(0, run.returncode, run.stderr.decode())
        tx = s.obj(transaction / 'transaction.json')
        receipt = {k: tx[k] for k in ('schema', 'task_id', 'plan_sha256', 'state', 'next_event')}
        receipt.update(verification_scope='FILE_CONTENT_AND_METADATA', runtime_effect='NOT_CHECKED', system_consistency='NOT_ASSESSED')
        query = {'operation': 'query', 'task_id': receipt['task_id'], 'plan_sha256': receipt['plan_sha256']}
        command = 'CLASSPATH=/data/local/d31-remote/releases/' + 'a' * 64 + '/remote.apk /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteRepairCommand ' + shlex.quote(json.dumps(query))
        req, res = self.parent('root_exec', {'command': command}, {'exit_code': 0, 'truncated': False, 'text': json.dumps(receipt)}, NOW - 300)
        query_proof = self.proof(req, res)
        remote = '/data/local/d31-remote/repairs/' + tx['task_id']
        journal = transaction / 'journal' / tx['task_id']
        events = [self.file_proof((journal / f'{i:06d}.json').read_bytes(), remote + f'/{i:06d}.json', NOW - 280) for i in range(tx['next_event'])]
        data = (transaction / 'device/system-support/start.sh').read_bytes()
        after, seal = self.bundle(data, 'after', NOW - 250, NOW - 200)
        return {'prepared': str(out / 'prepared-private.json'), 'prepared_sha256': result['preparedSha256'],
            'before_bundle': self.config['evidence_bundle'], 'query_request': query_proof['request'], 'query_result': query_proof['result'],
            'device_plan': self.file_proof((journal / 'plan.json').read_bytes(), remote + '/plan.json', NOW - 280), 'events': events,
            'after_bundle': str(after), 'after_evidence_sha256': seal['sha256'], 'target_payload': str(out / 'target-payload.sh'),
            'after_file': self.file_proof(data, s.TARGET, NOW - 160), 'boot': self.boot_proof(NOW - 140)}

    def reject_prepare(self, code):
        with self.assertRaisesRegex(ValueError, code): self.prepare()

    def test_prepare_actual_java_digest_and_raw_bundle_hash(self):
        out, result = self.prepare(); p = s.obj(out / 'prepared-private.json')
        self.assertEqual(self.config['evidence_sha256'], p['plan']['evidence_sha256'])
        self.assertEqual(JAVA.call('plan', p['plan'])['plan_sha256'], result['planSha256'])
        self.assertNotEqual(s.digest(eb.encode(p['plan'])), result['planSha256'])
        self.assertEqual([s.LOGICAL], [c['path'] for c in p['plan']['changes']])
        self.assertTrue(p['fixture']); self.assertEqual('NOT_CHECKED', p['serverAuthorization'])

    def test_success_uses_real_transaction_chain_and_new_report(self):
        result = s.verify(self.verified_config(), self.root / 'verified', JAVA, NOW)
        self.assertTrue(result['closed']); self.assertEqual('NOT_ASSESSED', result['systemConsistency'])

    def test_rollback_remains_unrepaired(self):
        result = s.verify(self.verified_config('rollback'), self.root / 'verified', JAVA, NOW)
        self.assertFalse(result['closed']); self.assertEqual('ROLLED_BACK', result['phase'])

    def test_wrong_device(self):
        self.config['device_id'] = 'other'; self.reject_prepare('PREPARE_DEVICE_MISMATCH')

    def test_unknown_context(self):
        self.config['context_review']['acceptedDifferences'] = {}; self.reject_prepare('UNREVIEWED_CONTEXT')

    def test_preimage_changed(self):
        Path(self.config['preimage']['file']).write_bytes(b'changed'); self.reject_prepare('DIGEST_MISMATCH')

    def test_payload_wrong_package(self):
        with Path(self.config['package']).open('ab') as f: f.write(b'changed')
        self.reject_prepare('PACKAGE_BINDING')

    def test_wrong_return_path(self):
        req = s.obj(self.config['preimage']['request']); req['params']['path'] = '/private/account'; put(Path(self.config['preimage']['request']), req)
        self.reject_prepare('RETURN_PARAMS_MISMATCH|RETURN_REMOTE_PATH_MISMATCH')

    def test_query_wrong_plan(self):
        config = self.verified_config(); res = s.obj(config['query_result']); receipt = json.loads(res['task']['result']['text']); receipt['plan_sha256'] = '0' * 64
        res['task']['result']['text'] = json.dumps(receipt); put(Path(config['query_result']), res)
        with self.assertRaisesRegex(ValueError, 'RECEIPT_PLAN_BINDING'): s.verify(config, self.root / 'verified', JAVA, NOW)

    def test_event_missing(self):
        config = self.verified_config(); config['events'].pop()
        with self.assertRaisesRegex(ValueError, 'EVENT_PREFIX_INCOMPLETE'): s.verify(config, self.root / 'verified', JAVA, NOW)

    def test_delayed_offline_verify(self):
        config = self.verified_config()
        result = s.verify(config, self.root / 'verified', JAVA, NOW + 86400000)
        self.assertTrue(result['closed'])
        comparison = s.obj(self.root / 'verified/comparison-private.json')
        self.assertEqual('EXPIRED', comparison['observation']['freshness'])

    def test_same_boot_wall_clock_rollback(self):
        config = self.verified_config()
        head = s.digest(Path(config['device_plan']['file']).read_bytes())
        for index, proof in enumerate(config['events']):
            event = s.obj(proof['file'])
            event['time_ms'] = NOW - 500 - index * 100
            event['previous_sha256'] = head
            raw = eb.encode(event)
            config['events'][index] = self.file_proof(raw, '/data/local/d31-remote/repairs/repair-fixture/' + f'{index:06d}.json', NOW - 280)
            head = s.digest(raw)
        def rollback(m):
            m['capturedAtMs'] = NOW - 20000
            m['validUntilMs'] = NOW - 10000
        after, seal = self.bundle(self.target, 'after-clock-rollback', NOW - 250, NOW - 200, rollback)
        config['after_bundle'], config['after_evidence_sha256'] = str(after), seal['sha256']
        result = s.verify(config, self.root / 'verified', JAVA, NOW)
        self.assertTrue(result['closed']); self.assertTrue(result['sameBoot'])

    def test_event_chain_wrong_raw_plan_seed(self):
        config = self.verified_config(); event = s.obj(config['events'][0]['file']); event['previous_sha256'] = s.obj(config['prepared'])['plan_sha256']
        config['events'][0] = self.file_proof(eb.encode(event), '/data/local/d31-remote/repairs/repair-fixture/000000.json', NOW - 280)
        with self.assertRaisesRegex(ValueError, 'EVENT_CHAIN_MISMATCH'): s.verify(config, self.root / 'verified', JAVA, NOW)

    def test_old_diagnostic_rejected(self):
        config = self.verified_config(); config['after_bundle'] = config['before_bundle']; config['after_evidence_sha256'] = self.config['evidence_sha256']
        with self.assertRaisesRegex(ValueError, 'CACHED_DIAGNOSTIC_REUSED'): s.verify(config, self.root / 'verified', JAVA, NOW)

    def test_target_stage_is_not_live_readback(self):
        config = self.verified_config(); config['after_file'] = self.file_proof(self.target, '/data/local/d31-remote/repairs/repair-fixture/stage/0.bin', NOW - 160)
        with self.assertRaisesRegex(ValueError, 'RETURN_REMOTE_PATH_MISMATCH'): s.verify(config, self.root / 'verified', JAVA, NOW)

    def test_changed_boot(self):
        config = self.verified_config(); config['boot'] = self.boot_proof(NOW - 140, 'aaaaaaaa-2222-3333-4444-555555555555')
        with self.assertRaisesRegex(ValueError, 'BOOT_CHANGED_OR_REUSED'): s.verify(config, self.root / 'verified', JAVA, NOW)

    def test_readback_before_query(self):
        config = self.verified_config(); config['after_file'] = self.file_proof(self.target, s.TARGET, NOW - 1000)
        with self.assertRaisesRegex(ValueError, 'RETURN_NOT_AFTER_QUERY'): s.verify(config, self.root / 'verified', JAVA, NOW)

    def test_existing_output_preserved(self):
        out, _ = self.prepare(); old = (out / 'prepared-private.json').read_bytes()
        with self.assertRaises(FileExistsError): s.prepare(self.config, out, JAVA, NOW)
        self.assertEqual(old, (out / 'prepared-private.json').read_bytes())


if __name__ == '__main__':
    unittest.main(verbosity=2)
