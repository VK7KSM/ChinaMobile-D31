"""固定start.sh的离线prepare/verify；无网络、ADB、安装器或修复执行入口。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import zipfile

sys.dont_write_bytecode = True
TARGET = '/data/local/d31-system-support/start.sh'
LOGICAL = 'system-support/start.sh'
ROOT = '/data/local/d31-system-support'
FIELD = 'semantic.system_support.root'
MAX_SCRIPT = 4096
CONTEXT_POLICY = 'ROOT_ONLY_CONTENT_NOT_BASELINE_APPROVAL'
eb = None


def dependencies(directory):
    global eb
    file = Path(directory).resolve() / 'evidence_bundle.py'
    if not file.is_file() or not (file.parent / 'compare_baseline.py').is_file():
        raise ValueError('EVIDENCE_BUNDLE_DEPENDENCY_NOT_IMPORTED')
    spec = importlib.util.spec_from_file_location('start_sh_evidence_dependency', file)
    eb = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(eb)


def need(value, code):
    if not value:
        raise ValueError(code)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def obj(file):
    return eb.baseline.strict_json(eb.read_bytes(file))


def save(file, value):
    eb.write_new(Path(file), eb.encode(value))


class Java:
    def __init__(self, java, classpath):
        self.java, self.classpath = str(java), classpath

    def call(self, action, value):
        p = subprocess.run([self.java, '-cp', self.classpath, 'StartShRepairMain', action],
            input=eb.encode(value), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        need(p.returncode == 0, 'AUTHORITATIVE_JAVA_CONTRACT_REJECTED')
        return eb.baseline.strict_json(p.stdout)


def bundle(directory, expected):
    eb.verify(directory, expected)
    record = obj(Path(directory) / 'manifest-private.json')
    raw = {role: eb.read_bytes(Path(directory) / item['path']) for role, item in
           {**record['attachments'], **record['localContext']}.items()}
    objects = {k: eb.baseline.strict_json(v) for k, v in raw.items()}
    # verify内部已复用validate_parent/validate_report/validate_firmware，不重复六roles框架。
    return record, raw, objects


def stamp(value):
    # Web任务时间按当前API的UTC ISO字符串或整毫秒读取，不信任浮点/布尔时间。
    if type(value) is int:
        need(value >= 0, 'TASK_TIME_INVALID')
        return value
    from datetime import datetime
    need(isinstance(value, str) and value.endswith('Z'), 'TASK_TIME_REQUIRED')
    return int(datetime.fromisoformat(value[:-1] + '+00:00').timestamp() * 1000)


def parent(request, response, device, kind):
    task = response.get('task', {})
    need(response.get('ok') is True and request.get('type') == task.get('type') == kind
         and request.get('device_id') == device and task.get('state') == 'success'
         and isinstance(request.get('id'), str) and request['id'] == task.get('id'), 'RETURN_PARENT_MISMATCH')
    for owner in (task, response):
        need('device_id' not in owner or owner['device_id'] == device, 'RETURN_DEVICE_MISMATCH')
    need('params' not in task or eb.same(task['params'], request['params']), 'RETURN_PARAMS_MISMATCH')
    return task


def returned(proof, device, remote, after=None):
    raw = eb.read_bytes(proof['file'])
    request, response = obj(proof['request']), obj(proof['result'])
    task = parent(request, response, device, 'get_file')
    need(request['params'].get('path') == remote, 'RETURN_REMOTE_PATH_MISMATCH')
    result = task['result']
    need(result.get('action') == 'uploaded', 'RETURN_NOT_UPLOADED')
    eb.match_digest(result, raw)
    started = stamp(task.get('started_at'))
    ended = stamp(task.get('completed_at'))
    need(ended >= started and (after is None or started > after), 'RETURN_NOT_AFTER_QUERY')
    return raw, {'request': eb.digest(eb.read_bytes(proof['request'])), 'result': eb.digest(eb.read_bytes(proof['result'])),
                 'data': eb.digest(raw), 'taskId': request['id'], 'startedAtMs': started, 'completedAtMs': ended}


def boot(proof, device):
    request, response = obj(proof['request']), obj(proof['result'])
    task = parent(request, response, device, 'root_exec')
    need(shlex.split(request['params']['command']) == ['/system/bin/cat', '/proc/sys/kernel/random/boot_id'], 'BOOT_COMMAND_SHAPE')
    result = task['result']
    need(type(result.get('exit_code')) is int and result['exit_code'] == 0 and result.get('truncated') is False, 'BOOT_OUTPUT_INCOMPLETE')
    value = result['text'].strip()
    need(re.fullmatch(r'[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}', value) is not None, 'BOOT_ID_INVALID')
    start, end = stamp(task.get('started_at')), stamp(task.get('completed_at'))
    need(end >= start, 'BOOT_TIME_INVALID')
    return {'id': value, 'taskId': request['id'], 'startedAtMs': start, 'completedAtMs': end,
            'request': eb.digest(eb.read_bytes(proof['request'])), 'result': eb.digest(eb.read_bytes(proof['result']))}


def entry(manifest):
    rows = [e for e in manifest['entries'] if e['path'] == TARGET]
    need(len(rows) == 1, 'FIXED_TARGET_NOT_UNIQUE')
    need(rows[0]['presence'].get('state') == 'OBSERVED' and rows[0]['presence'].get('value') == 'PRESENT', 'TARGET_PRESENCE_UNKNOWN')
    return rows[0]


def observed(item, field):
    evidence = item['fields'].get(field, {})
    need(evidence.get('state') == 'OBSERVED', 'UNKNOWN_' + field)
    return evidence['value']


def root_script(data):
    need(len(data) <= MAX_SCRIPT, 'SCRIPT_TOO_LARGE')
    m = re.match(rb'#!/system/bin/sh\nROOT=(/data/local/[A-Za-z0-9][A-Za-z0-9_-]{0,63})\n', data)
    need(m is not None, 'SCRIPT_SHAPE_UNKNOWN')
    return m[1].decode('ascii'), data[:m.start(1)] + b'<ROOT>' + data[m.end(1):]


def payload(config, raw, objects):
    mapping_raw, sources_raw = eb.read_bytes(config['mapping']), eb.read_bytes(config['sources'])
    generation = objects['firmware_generation']
    need(digest(mapping_raw) == generation['mappingSha256'].lower(), 'MAPPING_BINDING')
    need(digest(sources_raw) == generation['bindings']['sourceListSha256'].lower(), 'SOURCE_BINDING')
    mapping, sources = eb.baseline.strict_json(mapping_raw), eb.baseline.strict_json(sources_raw)
    need(mapping['schemaVersion'] == 1 and mapping['firmwareId'] == 'D31-factory-1.4.4', 'MAPPING_VERSION')
    need(eb.same(mapping['bindings'], generation['bindings']), 'GENERATION_BINDING')
    package = eb.safe_path(config['package'])
    with package.open('rb') as stream:
        before = os.fstat(stream.fileno())
        package_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
        need(package_hash == generation['bindings']['packageSha256'].lower(), 'PACKAGE_BINDING')
        stream.seek(0)
        with zipfile.ZipFile(stream) as archive:
            names = archive.namelist()
            need(len(names) == len(set(names)), 'DUPLICATE_ZIP_MEMBER')
            rows = [e for e in mapping['entries'] if e['destination'] == TARGET]
            need(len(rows) == 1 and rows[0]['action'] == 'copy' and rows[0]['source'] ==
                 'payload/system-patches/support-start.sh', 'FIXED_COPY_MAPPING_REQUIRED')
            mapped = rows[0]
            need(archive.getinfo(mapped['source']).file_size <= MAX_SCRIPT, 'SCRIPT_TOO_LARGE')
            target = archive.read(mapped['source'])
            need(archive.getinfo(mapping['inventory']['path']).file_size <= eb.baseline.MAX_BYTES, 'INVENTORY_TOO_LARGE')
            inventory_raw = archive.read(mapping['inventory']['path'])
        after = os.fstat(stream.fileno())
    need((before.st_size, before.st_mtime_ns, before.st_ino) == (after.st_size, after.st_mtime_ns, after.st_ino), 'PACKAGE_CHANGED')
    need(before.st_size == generation['packageEvidence']['bytes'] and generation['inputKind'] == 'ZIP', 'REAL_ZIP_REQUIRED')
    need(digest(inventory_raw) == mapping['inventory']['sha256'], 'INVENTORY_BINDING')
    inventory = eb.baseline.strict_json(inventory_raw)['文件']
    members = [x for x in inventory if x['path'] == mapped['source']]
    need(len(members) == 1, 'PAYLOAD_INVENTORY_NOT_UNIQUE')
    eb.match_digest({**members[0], 'sha256': members[0]['sha256'].lower()}, target)
    source = sources['system_payload/support-start.sh']
    need(eb.same(source[0], len(target)) and source[1].lower() == digest(target), 'PAYLOAD_SOURCE')
    firmware = objects['firmware']
    need(firmware['build'] == mapping['build'] and firmware['firmwareId'] == mapping['firmwareId'], 'FIRMWARE_BINDING')
    item = entry(firmware)
    need(observed(item, 'sha256') == digest(target) and observed(item, FIELD) == ROOT and root_script(target)[0] == ROOT, 'FIRMWARE_PAYLOAD')
    for field in ('mode', 'uid', 'gid'):
        need(eb.same(observed(item, field), mapped[field]), 'FIRMWARE_METADATA')
    return target, mapped


def context_review(objects, evidence_sha, review):
    observation, firmware = objects['observation']['manifest'], objects['firmware']
    need(review.get('purpose') == CONTEXT_POLICY and review.get('evidence_sha256') == evidence_sha, 'CONTEXT_REVIEW_BINDING')
    differences = {}
    for field in ('build', 'baselineId', 'baselineRevision', 'firmwareId'):
        if not eb.same(observation[field], firmware[field]):
            need(field != 'build', 'BUILD_MISMATCH')
            differences[field] = {'observation': observation[field], 'firmware': firmware[field]}
    for field in eb.CONTEXT:
        if not eb.same(observation['context'][field], firmware['context'][field]):
            need(field not in ('model', 'hardwareClass', 'firmwareFamily'), 'DEVICE_CLASS_MISMATCH')
            differences['context.' + field] = {'observation': observation['context'][field], 'firmware': firmware['context'][field]}
    need(observation['context']['model'] == 'D31' and observation['context']['stage'] == 'RUNNING', 'RUNNING_D31_REQUIRED')
    need(eb.same(review.get('acceptedDifferences'), differences), 'UNREVIEWED_CONTEXT_DIFFERENCE')


def check_observation(objects, raw, target, mapped, now, historical=False):
    report, m = objects['observation'], objects['observation']['manifest']
    need(m['role'] == 'TARGET' and report['index'].get('state') == 'COMPLETE' and m['completeness'] == 'COMPLETE', 'COLLECTION_INCOMPLETE')
    captured, until = eb.integer(m['capturedAtMs']), eb.integer(m['validUntilMs'])
    if not historical:
        need(0 <= now - captured <= 300000 and now <= until, 'STALE_OR_FUTURE_OBSERVATION')
    item = entry(m)
    need(observed(item, 'type') == 'file' and observed(item, 'sha256') == digest(raw), 'PREIMAGE_MISMATCH')
    for field in ('mode', 'uid', 'gid'):
        need(eb.same(observed(item, field), mapped[field]), 'METADATA_REPAIR_NOT_SUPPORTED_' + field)
    root, masked = root_script(raw)
    need(observed(item, FIELD) == root, 'ROOT_BYTES_MISMATCH')
    need(masked == root_script(target)[1], 'NOT_ROOT_ONLY_DIFFERENCE')
    return m


def compare(java, objects, expected, now):
    actual = java.call('compare', {'observation': objects['observation']['manifest'], 'firmware': objects['firmware'], 'now': now})
    rows = [i for i in actual['configurationCoverage']['items'] if i['id'] == 'system_support.root']
    reported = [i for i in objects['report']['configurationCoverage']['items'] if i['id'] == 'system_support.root']
    need(len(rows) == len(reported) == 1 and rows[0]['path'] == reported[0]['path'] == TARGET
         and rows[0]['field'] == reported[0]['field'] == FIELD and rows[0]['pair'] == reported[0]['pair'] == expected,
         'ROOT_DIFFERENCE_NOT_PROVEN')
    return actual


def prepare(config, output, java, now):
    record, raw, objects = bundle(config['evidence_bundle'], config['evidence_sha256'])
    device = record['identity']['device_id']
    need(config['device_id'] == device, 'PREPARE_DEVICE_MISMATCH')
    context_review(objects, config['evidence_sha256'], config['context_review'])
    target, mapped = payload(config, raw, objects)
    boot_before = boot(config['boot'], device)
    diagnostic_task = objects['parent_result']['task']
    need(boot_before['completedAtMs'] < stamp(diagnostic_task['started_at']), 'BOOT_NOT_BEFORE_DIAGNOSTIC')
    original, proof = returned(config['preimage'], device, TARGET)
    need(proof['startedAtMs'] > stamp(diagnostic_task['completed_at']), 'PREIMAGE_NOT_AFTER_DIAGNOSTIC')
    m = check_observation(objects, original, target, mapped, now)
    need(original != target, 'NO_CONTENT_DIFFERENCE')
    actual = compare(java, objects, 'DIFFERENT', now)
    plan = java.call('plan', {'schema': 1, 'task_id': config['task_id'], 'plan_id': 'system-support-root', 'revision': 'v144-loop1',
        'device_class': 'D31', 'build': m['build'], 'evidence_sha256': config['evidence_sha256'],
        'changes': [{'id': 'start', 'path': LOGICAL, 'original_sha256': digest(original), 'original_bytes': len(original),
                     'target_sha256': digest(target), 'target_bytes': len(target), 'artifact': 'start-script', 'after': []}], 'dependencies': []})
    output.mkdir(mode=0o700, exist_ok=False)
    eb.write_new(output / 'preimage-private.sh', original)
    eb.write_new(output / 'target-payload.sh', target)
    save(output / 'comparison-private.json', actual)
    prepared = {'format': 'd31-start-sh-review-1', **plan, 'device_id': device, 'fixture': record['fixture'],
        'evidence_bundle_sha256': config['evidence_sha256'], 'firmware': eb.digest(raw['firmware']),
        'firmware_generation': eb.digest(raw['firmware_generation']), 'context_review': config['context_review'],
        'preimageProof': proof, 'boot': boot_before, 'mapped': mapped, 'observationIdentity': {k: m[k] for k in eb.IDENTITY},
        'before': {'snapshotId': m['snapshotId'], 'diagnosticId': record['identity']['diagnostic_id'],
                   'parentId': record['identity']['task_id'], 'capturedAtMs': m['capturedAtMs'], 'uptimeMs': m['uptimeMs']},
        'status': 'REVIEW_ONLY_NOT_EXECUTION_AUTHORIZATION', 'serverAuthorization': 'NOT_CHECKED',
        'runtimeVerification': 'NOT_PERFORMED', 'systemConsistency': 'NOT_ASSESSED'}
    data = eb.encode(prepared)
    eb.write_new(output / 'prepared-private.json', data)
    save(output / 'local-commit.json', eb.digest(data))
    save(output / 'submit-request.json', {'operation': 'submit', 'task_id': plan['plan']['task_id'],
         'plan_sha256': plan['plan_sha256'], 'plan': plan['plan']})
    for operation in ('run', 'query'):
        save(output / (operation + '-request.json'), {'operation': operation, 'task_id': plan['plan']['task_id'], 'plan_sha256': plan['plan_sha256']})
    return {'preparedSha256': digest(data), 'planSha256': plan['plan_sha256'], 'readyForReview': True, 'executed': False}


def query(config, prepared):
    request, response = obj(config['query_request']), obj(config['query_result'])
    task = parent(request, response, prepared['device_id'], 'root_exec')
    argv = shlex.split(request['params']['command'])
    need(len(argv) == 5 and re.fullmatch(r'CLASSPATH=/data/local/d31-remote/releases/[a-f0-9]{64}/remote.apk', argv[0])
         and argv[1:4] == ['/system/bin/app_process', '/system/bin', 'net.elfradio.d31bootstrap.RemoteRepairCommand'], 'QUERY_COMMAND_SHAPE')
    embedded = eb.baseline.strict_json(argv[4].encode())
    need(eb.same(embedded, {'operation': 'query', 'task_id': prepared['plan']['task_id'], 'plan_sha256': prepared['plan_sha256']}), 'QUERY_PLAN_BINDING')
    result = task['result']
    need(type(result.get('exit_code')) is int and result['exit_code'] == 0 and result.get('truncated') is False, 'QUERY_OUTPUT_INCOMPLETE')
    receipt = eb.embedded_json(result['text'])
    need(receipt.get('schema') == 1 and receipt.get('task_id') == prepared['plan']['task_id'] and receipt.get('plan_sha256') == prepared['plan_sha256'], 'RECEIPT_PLAN_BINDING')
    need(receipt.get('verification_scope') == 'FILE_CONTENT_AND_METADATA' and receipt.get('runtime_effect') == 'NOT_CHECKED'
         and receipt.get('system_consistency') == 'NOT_ASSESSED', 'RECEIPT_SCOPE')
    return receipt, stamp(task['completed_at'])


def verify(config, output, java, now):
    prepared_raw = eb.read_bytes(config['prepared'])
    need(digest(prepared_raw) == config['prepared_sha256'], 'PREPARED_DIGEST_MISMATCH')
    prepared = eb.baseline.strict_json(prepared_raw)
    canonical = java.call('plan', prepared['plan'])
    need(canonical['plan_sha256'] == prepared['plan_sha256'] and prepared['plan']['evidence_sha256'] == prepared['evidence_bundle_sha256'], 'CANONICAL_PLAN_MISMATCH')
    _, before_raw, before_objects = bundle(config['before_bundle'], prepared['evidence_bundle_sha256'])
    eb.match_digest(prepared['firmware'], before_raw['firmware'])
    receipt, query_end = query(config, prepared)
    need(query_end > prepared['preimageProof']['completedAtMs'], 'QUERY_BEFORE_PREIMAGE')
    count = eb.integer(receipt['next_event'])
    need(0 < count <= 256 and len(config['events']) == count, 'EVENT_PREFIX_INCOMPLETE')
    root = '/data/local/d31-remote/repairs/' + prepared['plan']['task_id']
    plan_raw, _ = returned(config['device_plan'], prepared['device_id'], root + '/plan.json')
    need(java.call('plan', eb.baseline.strict_json(plan_raw))['plan_sha256'] == prepared['plan_sha256'], 'DEVICE_PLAN_MISMATCH')
    head, last = digest(plan_raw), None
    for index, proof in enumerate(config['events']):
        event_raw, _ = returned(proof, prepared['device_id'], root + '/' + f'{index:06d}.json')
        need(len(event_raw) <= 128 * 1024, 'EVENT_TOO_LARGE')
        event = eb.baseline.strict_json(event_raw)
        need(type(event['sequence']) is int and event['sequence'] == index and event['previous_sha256'] == head
             and event['plan_sha256'] == prepared['plan_sha256'], 'EVENT_CHAIN_MISMATCH')
        # 设备墙钟可回拨；顺序由原字节链和sequence证明，不与Web父任务时间混比。
        eb.integer(event['time_ms'])
        head, last = digest(event_raw), event['state']
    need(eb.same(last, receipt['state']), 'QUERY_EVENT_PREFIX_MISMATCH')
    phase = last['phase']
    need(phase in ('SUCCEEDED', 'REJECTED', 'ROLLED_BACK', 'NEEDS_ATTENTION'), 'TRANSACTION_NOT_TERMINAL')
    record, raw, objects = bundle(config['after_bundle'], config['after_evidence_sha256'])
    need(record['identity']['device_id'] == prepared['device_id'] and record['fixture'] is prepared['fixture'], 'AFTER_DEVICE_OR_FIXTURE_MISMATCH')
    for role in ('firmware', 'firmware_generation'):
        eb.match_digest(prepared[role], raw[role])
    m, before = objects['observation']['manifest'], prepared['before']
    need(m['snapshotId'] != before['snapshotId'] and record['identity']['diagnostic_id'] != before['diagnosticId']
         and record['identity']['task_id'] != before['parentId'], 'CACHED_DIAGNOSTIC_REUSED')
    need(m['uptimeMs'] > before['uptimeMs'], 'AFTER_SNAPSHOT_NOT_FRESH')
    need(stamp(objects['parent_result']['task']['started_at']) > query_end, 'DIAGNOSTIC_NOT_AFTER_QUERY')
    for key in eb.IDENTITY:
        if key != 'snapshotId':
            need(eb.same(m[key], prepared['observationIdentity'][key]), 'AFTER_CONTEXT_CHANGED')
    raw_file, proof = returned(config['after_file'], prepared['device_id'], TARGET, query_end)
    need(proof['taskId'] != prepared['preimageProof']['taskId'], 'PREIMAGE_RETURN_REUSED')
    boot_after = boot(config['boot'], prepared['device_id'])
    need(boot_after['id'] == prepared['boot']['id'] and boot_after['taskId'] != prepared['boot']['taskId'], 'BOOT_CHANGED_OR_REUSED')
    need(boot_after['startedAtMs'] > max(proof['completedAtMs'], stamp(objects['parent_result']['task']['completed_at'])), 'BOOT_NOT_AFTER_READBACK')
    target = eb.read_bytes(config['target_payload'])
    eb.match_digest({'sha256': prepared['plan']['changes'][0]['target_sha256'], 'bytes': prepared['plan']['changes'][0]['target_bytes']}, target)
    # 复核历史证据不要求此刻仍在采集有效期；Java报告保留当前EXPIRED/FUTURE标记。
    check_observation(objects, raw_file, target, prepared['mapped'], now, historical=True)
    matched = raw_file == target
    actual = compare(java, objects, 'SAME' if matched else 'DIFFERENT', now)
    result = {'closed': phase == 'SUCCEEDED' and matched, 'phase': phase, 'fileAndRootMatch': matched,
              'freshDiagnostic': True, 'sameBoot': True, 'eventPrefixCount': count, 'eventHeadSha256': head, 'fixture': prepared['fixture'],
              'status': 'FILE_ROOT_REPAIRED' if phase == 'SUCCEEDED' and matched else 'TERMINAL_WITHOUT_REPAIR_PROOF',
              'serverAuthorization': 'NOT_CHECKED', 'runtimeVerification': 'NOT_PERFORMED', 'systemConsistency': 'NOT_ASSESSED'}
    output.mkdir(mode=0o700, exist_ok=False)
    save(output / 'result.json', result)
    save(output / 'comparison-private.json', actual)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'verify'))
    parser.add_argument('--config', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--dependency-dir', default=str(Path(__file__).resolve().parent))
    parser.add_argument('--java', required=True)
    parser.add_argument('--classpath', required=True)
    args = parser.parse_args()
    try:
        dependencies(args.dependency_dir)
        result = globals()[args.action](obj(args.config), Path(args.output).absolute(), Java(args.java, args.classpath), time.time_ns() // 1000000)
        print(json.dumps(result, ensure_ascii=True))
    except Exception as error:
        print('离线证据未通过；保留原件。错误类型：' + type(error).__name__, file=sys.stderr)
        raise SystemExit(2) from None


if __name__ == '__main__':
    main()
