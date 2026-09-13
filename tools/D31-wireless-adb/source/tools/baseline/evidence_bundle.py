"""准备或复核私有离线证据包；不连接设备、网络，不执行请求内容。"""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import stat
import sys
import time

sys.dont_write_bytecode = True
MODULE_DIR = Path(__file__).resolve().parent
BASELINE = MODULE_DIR / 'compare_baseline.py'
spec = importlib.util.spec_from_file_location('baseline_input_contract', BASELINE)
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)
ROLES = ('request', 'receipt', 'report', 'observation', 'firmware', 'firmware_generation')
LOCAL_ROLES = (*ROLES, 'parent_request', 'parent_result')
IDENTITY = ('snapshotId', 'baselineId', 'baselineRevision', 'firmwareId', 'build', 'context')
CONTEXT = ('model', 'hardwareClass', 'firmwareFamily', 'stage', 'network', 'sim', 'storage')
SHA = re.compile('[a-f0-9]{64}')
METHOD = 'HISTORICAL_TWO_INPUT_EVIDENCE_COVERAGE'


class BindingError(ValueError):
    pass


def require(value, code):
    if not value:
        raise BindingError(code)


def digest(data):
    return {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}


def same(left, right):
    # Python把True与1视为相等；合同中的布尔与整数必须分开。
    return json.dumps(left, sort_keys=True, ensure_ascii=True) == json.dumps(right, sort_keys=True, ensure_ascii=True)


def string(value):
    require(isinstance(value, str) and 0 < len(value) <= 4096
            and not any(ord(c) < 32 for c in value), 'INVALID_STRING')
    return value


def integer(value):
    require(type(value) is int and 0 <= value <= 2**63 - 1, 'INVALID_INTEGER')
    return value


def hash_value(value):
    require(isinstance(value, str) and SHA.fullmatch(value), 'INVALID_SHA256')
    return value


def source_hash(value):
    require(isinstance(value, str), 'INVALID_SHA256')
    return hash_value(value.lower())


def safe_path(value):
    raw = str(value)
    require(not raw.startswith(('\\\\', '//')) and '://' not in raw, 'LOCAL_PATH_REQUIRED')
    path = Path(os.path.abspath(raw))
    require(':' not in str(path)[len(path.drive):], 'LOCAL_PATH_REQUIRED')
    for part in (*reversed(path.parents), path):
        s = part.lstat()
        require(not stat.S_ISLNK(s.st_mode) and not getattr(s, 'st_file_attributes', 0) & 0x400,
                'LINK_OR_REPARSE_POINT')
    return path


def read_bytes(path):
    path = safe_path(path)
    require(path.is_file(), 'REGULAR_FILE_REQUIRED')
    with path.open('rb') as stream:
        before = os.fstat(stream.fileno())
        data = stream.read(baseline.MAX_BYTES + 1)
        after = os.fstat(stream.fileno())
    require((before.st_size, before.st_mtime_ns, before.st_ino) ==
            (after.st_size, after.st_mtime_ns, after.st_ino), 'INPUT_CHANGED')
    require(len(data) <= baseline.MAX_BYTES and len(data) == after.st_size, 'INPUT_SIZE_LIMIT')
    return data


def pointer_tokens(pointer):
    require(isinstance(pointer, str) and (pointer == '' or pointer.startswith('/')), 'INVALID_POINTER')
    if not pointer:
        return []
    tokens = pointer[1:].split('/')
    require(all(re.search('~(?![01])', p) is None for p in tokens), 'INVALID_POINTER_ESCAPE')
    return [p.replace('~1', '/').replace('~0', '~') for p in tokens]


def resolve_pointer(obj, pointer):
    for token in pointer_tokens(pointer):
        if isinstance(obj, list):
            require(re.fullmatch('0|[1-9][0-9]*', token) is not None, 'INVALID_ARRAY_INDEX')
            require(len(token) < 10 and int(token) < len(obj), 'POINTER_NOT_FOUND')
            obj = obj[int(token)]
        else:
            require(isinstance(obj, dict) and token in obj, 'POINTER_NOT_FOUND')
            obj = obj[token]
    return obj


def match_digest(reference, data):
    require(isinstance(reference, dict), 'DIGEST_REQUIRED')
    hash_value(reference.get('sha256'))
    integer(reference.get('bytes'))
    require(same({key: reference[key] for key in ('sha256', 'bytes')}, digest(data)), 'DIGEST_MISMATCH')


def command_request(command):
    # 只解析既有固定argv形状；绝不交给shell或subprocess。
    parts = shlex.split(string(command), posix=True)
    require(len(parts) == 6 and parts[0].startswith('CLASSPATH=/') and parts[1:4] ==
            ['/system/bin/app_process', '/system/bin', 'net.elfradio.d31bootstrap.RemoteDiagnosticCommand'],
            'DIAGNOSTIC_COMMAND_SHAPE')
    return hash_value(parts[4]), baseline.strict_json(parts[5].encode('utf-8'))


def embedded_json(value):
    require(isinstance(value, str), 'JSON_TEXT_REQUIRED')
    return baseline.strict_json(value.encode('utf-8'))


def receipt_object(obj):
    if 'output' in obj:
        require(obj.get('truncated') is False and type(obj.get('exit_code')) is int
                and obj['exit_code'] == 0, 'RECEIPT_OUTPUT_INCOMPLETE')
        return embedded_json(obj['output'])
    return obj


def validate_parent(objects, observation_bytes):
    request, receipt = objects['request'], receipt_object(objects['receipt'])
    parent, response = objects['parent_request'], objects['parent_result']
    task = response['task']
    require(response.get('ok') is True and isinstance(task, dict), 'PARENT_RESPONSE_INVALID')
    require(parent['type'] == task['type'] == 'root_exec' and task['state'] == 'success', 'PARENT_NOT_SUCCESS')
    string(parent['device_id'])
    require(string(parent['id']) == string(task['id']), 'PARENT_TASK_MISMATCH')
    for owner in (response, task):
        if 'device_id' in owner:
            require(owner['device_id'] == parent['device_id'], 'PARENT_DEVICE_MISMATCH')
    if 'params' in task:
        require(same(task['params'], parent['params']), 'PARENT_PARAMS_MISMATCH')
    diagnostic_id, embedded = command_request(parent['params']['command'])
    if 'command' in request:
        request_id, request = command_request(request['command'])
        require(request_id == diagnostic_id, 'REQUEST_DIAGNOSTIC_MISMATCH')
    require(same(request, embedded), 'PARENT_REQUEST_MISMATCH')
    require(set(request) == {'operation', 'scope', 'identity'} and request['operation'] == 'manifest',
            'MANIFEST_REQUEST_REQUIRED')
    require(set(request['identity']) == set(IDENTITY), 'IDENTITY_FIELDS_MISMATCH')
    output = task['result']
    require(isinstance(output, dict), 'PARENT_OUTPUT_REQUIRED')
    require(output.get('truncated') is False and type(output.get('exit_code')) is int
            and output['exit_code'] == 0, 'PARENT_OUTPUT_INCOMPLETE')
    returned = embedded_json(output['text'])
    require(same(returned, receipt), 'PARENT_RECEIPT_MISMATCH')
    require(receipt['id'] == diagnostic_id and receipt['state'] == 'completed'
            and receipt['path'] == '/data/local/d31-remote/diagnostics/' + diagnostic_id + '/report.json',
            'RECEIPT_DIAGNOSTIC_MISMATCH')
    match_digest(receipt, observation_bytes)
    observation = objects['observation']
    require(observation.get('operation') == 'manifest', 'OBSERVATION_WRAPPER_REQUIRED')
    manifest, index = observation['manifest'], observation['index']
    for key in IDENTITY:
        require(same(request['identity'][key], manifest[key]), 'REQUEST_IDENTITY_MISMATCH')
    require(index['snapshotId'] == manifest['snapshotId'] and index['scope'] == request['scope']
            and [s['path'] for s in manifest['scope']] == [request['scope']], 'REQUEST_SCOPE_MISMATCH')
    require(index.get('atomicSnapshot') is False, 'ATOMIC_SNAPSHOT_NOT_SUPPORTED')
    return {'device_id': parent['device_id'], 'task_id': parent['id'], 'diagnostic_id': diagnostic_id,
            'deviceContext': 'LOCAL_PARENT_REQUEST_ONLY_SERVER_CONFIRMATION_REQUIRED',
            'registrationInstance': 'SERVER_CONFIRMATION_REQUIRED',
            'parentResponseDevicePresent': 'device_id' in task or 'device_id' in response}


def validate_report(report, raw, objects):
    require(report['method'] == METHOD and type(report['schemaVersion']) is int
            and report['schemaVersion'] == 1, 'REPORT_SCHEMA_UNSUPPORTED')
    require(report['systemConsistency'] == 'NOT_ASSESSED' and report['repairPlanGenerated'] is False,
            'REPORT_BOUNDARY_MISMATCH')
    require(set(report['inputs']) == {'observation', 'firmware'}, 'REPORT_INPUT_ROLES')
    derived = integer(report['derivedAtMs'])
    manifests, bases = {}, {}
    for side in ('observation', 'firmware'):
        reference = report['inputs'][side]
        match_digest(reference, raw[side])
        base = '/manifest' if 'manifest' in objects[side] else ''
        require(reference['manifestPointer'] == base, 'MANIFEST_POINTER_MISMATCH')
        manifest = resolve_pointer(objects[side], base)
        require(isinstance(manifest, dict) and type(manifest['schemaVersion']) is int
                and manifest['schemaVersion'] == 1, 'MANIFEST_SCHEMA_UNSUPPORTED')
        require(manifest['role'] in (('TARGET', 'BOARD') if side == 'observation' else ('FIRMWARE',)), 'MANIFEST_ROLE_MISMATCH')
        require(set(manifest['context']) == set(CONTEXT), 'CONTEXT_FIELDS_MISMATCH')
        captured, until = integer(manifest['capturedAtMs']), integer(manifest['validUntilMs'])
        integer(manifest['uptimeMs'])
        require(captured <= until, 'INVALID_TIME_WINDOW')
        for key in ('role', 'capturedAtMs', 'validUntilMs', 'completeness'):
            require(same(report[side][key], manifest[key]), 'REPORT_SUMMARY_MISMATCH')
        freshness = 'FUTURE' if derived < captured else 'EXPIRED' if derived > until else 'WITHIN_DECLARED_WINDOW'
        require(report[side]['freshness'] == freshness, 'REPORT_FRESHNESS_MISMATCH')
        require(len({e['path'] for e in manifest['entries']}) == len(manifest['entries']), 'DUPLICATE_ENTRY_PATH')
        manifests[side], bases[side] = manifest, base
    for section, keys in (('bindingEqual', ('build', 'baselineId', 'baselineRevision', 'firmwareId')),
                          ('contextEqual', CONTEXT)):
        require(set(report[section]) == set(keys), 'REPORT_CONTEXT_KEYS')
        for key in keys:
            left, right = manifests['observation'], manifests['firmware']
            if section == 'contextEqual':
                left, right = left['context'], right['context']
            require(report[section][key] is same(left[key], right[key]), 'REPORT_CONTEXT_MISMATCH')
    references = []

    def reference(ref, report_pointer, side, path, field):
        require(isinstance(ref, dict), 'EVIDENCE_REFERENCE_REQUIRED')
        p = ref.get('pointer')
        if p is None or p == '':
            references.append({'report_pointer': report_pointer, 'role': side, 'pointer': None,
                               'status': 'NO_DIRECT_EVIDENCE'})
            return
        tokens = pointer_tokens(p)
        expected_tail = ['presence'] if field == 'presence' else ['fields', field]
        require(len(tokens) == len(expected_tail) + 2 and tokens[0] == 'entries'
                and tokens[2:] == expected_tail, 'EVIDENCE_POINTER_TARGET_MISMATCH')
        entry = resolve_pointer(manifests[side], '/entries/' + tokens[1])
        require(entry['path'] == path, 'EVIDENCE_PATH_MISMATCH')
        evidence = resolve_pointer(objects[side], bases[side] + p)
        require(isinstance(evidence, dict) and evidence['state'] == ref['state'], 'EVIDENCE_STATE_MISMATCH')
        references.append({'report_pointer': report_pointer, 'role': side, 'pointer': bases[side] + p,
                           'status': 'RESOLVED', **digest(raw[side])})

    for i, entry in enumerate(report['entries']):
        for j, field in enumerate(entry['fields']):
            for side in ('observation', 'firmware'):
                reference(field[side], f'/entries/{i}/fields/{j}/{side}', side, entry['path'], field['field'])
    catalog = report.get('configurationCoverage')
    require(catalog is None or isinstance(catalog, dict), 'CONFIGURATION_COVERAGE_OBJECT_REQUIRED')
    supported = catalog is not None and catalog.get('catalogId') == 'd31-finite-configuration' \
        and type(catalog.get('catalogVersion')) is int and catalog['catalogVersion'] in (1, 2, 3)
    if supported:
        for i, item in enumerate(catalog['items']):
            for side in ('observation', 'firmware'):
                p = f'/configurationCoverage/items/{i}/{side}'
                reference(item[side], p, side, item['path'], item['field'])
                if item[side].get('presence') is not None:
                    reference(item[side]['presence'], p + '/presence', side, item['path'], 'presence')
    return manifests, {'references': references, 'catalog': 'SUPPORTED' if supported else 'UNSUPPORTED_OR_ABSENT',
                       'reportSemanticsRecomputed': False, 'reportSchemaPreserved': True}


def validate_firmware(source, firmware):
    require(type(source['schemaVersion']) is int and source['schemaVersion'] == 1
            and source['snapshotId'] == firmware['snapshotId'], 'FIRMWARE_SNAPSHOT_MISMATCH')
    mapping = source_hash(source['mappingSha256'])
    source_hash(source['generatorSha256'])
    require(set(source['bindings']) == {'installerSourceSha256', 'sourceListSha256', 'packageSha256'}, 'FIRMWARE_BINDINGS')
    for value in source['bindings'].values():
        source_hash(value)
    require(all(s['source'] == 'explicit-install-map:sha256:' + mapping for s in firmware['scope']), 'FIRMWARE_MAPPING_MISMATCH')
    package = source['packageEvidence']
    if source['inputKind'] == 'ZIP':
        require(source_hash(package['sha256']) == source_hash(source['bindings']['packageSha256']), 'FIRMWARE_PACKAGE_MISMATCH')
        integer(package['bytes'])
        status = 'SOURCE_REPORT_DIGEST_CONSISTENT_PACKAGE_NOT_REHASHED'
    else:
        require(source['inputKind'] == 'DIRECTORY' and package['state'] == 'NOT_CHECKED', 'FIRMWARE_PACKAGE_STATE')
        status = 'NOT_CHECKED'
    return {'snapshotLink': 'MATCHED', 'mappingSha256': mapping, 'bindings': source['bindings'],
            'packageEvidence': package, 'packageVerification': status,
            'generationReproduced': False, 'baselineApproval': 'SERVER_CONFIRMATION_REQUIRED'}


def build_manifest(raw, fixture, prepared_at):
    require(set(raw) == set(LOCAL_ROLES) and type(fixture) is bool, 'ATTACHMENTS_REQUIRED')
    objects = {role: baseline.strict_json(data) for role, data in raw.items()}
    manifests, report_checks = validate_report(objects['report'], raw, objects)
    identity = validate_parent(objects, raw['observation'])
    firmware = validate_firmware(objects['firmware_generation'], manifests['firmware'])
    attachments = {role: {'path': f'attachments-private/{role}.json', **digest(raw[role])} for role in ROLES}
    local = {role: {'path': f'context-private/{role}.json', **digest(raw[role])} for role in LOCAL_ROLES if role not in ROLES}
    uploads = []
    for role in ROLES:
        uploads.append({'attachment': attachments[role]['path'], 'method': 'POST', 'route': '/api/elfremote/files',
                        'body': {'device_id': identity['device_id'], 'name': role + '.json', 'size': len(raw[role]),
                                 'purpose': 'evidence', 'evidence': {'task_id': identity['task_id'],
                                 'diagnostic_id': identity['diagnostic_id'], 'role': role, 'fixture': fixture}},
                        'complete': {'sha256': attachments[role]['sha256']}})
    return {'format': 'd31-offline-evidence-bundle-1', 'privacy': 'PRIVATE', 'fixture': fixture,
            'preparedAtMs': integer(prepared_at), 'identity': identity, 'attachments': attachments, 'localContext': local,
            'inputs': objects['report']['inputs'], 'reportChecks': report_checks, 'firmwareChecks': firmware,
            'times': {'derivedAtMs': objects['report']['derivedAtMs'],
                      'device': {side: {key: manifests[side][key] for key in ('capturedAtMs', 'validUntilMs', 'uptimeMs')}
                                 for side in manifests},
                      'parentDeclared': {key: objects['parent_result']['task'].get(key) for key in
                                         ('created_at', 'started_at', 'completed_at')},
                      'serverReceivedAtMs': None, 'clockTrust': 'NOT_VERIFIED'},
            'uploadInitializations': uploads,
            'serverConfirmationRequired': ['PARENT_TASK_AUTHORIZATION_AND_DEVICE', 'REGISTRATION_INSTANCE',
                                           'PARENT_REQUEST_AND_RESULT_AUTHENTICITY', 'DIAGNOSTIC_GROUP_AND_FIXTURE',
                                           'ATTACHMENT_PURPOSE_DEPLOYMENT', 'SEALED_RETENTION_AND_READ_AUTHORIZATION',
                                           'SOURCE_RETURN_TASK_IF_SUPPLIED'],
            'status': {'localBinding': 'CONSISTENT', 'serverAuthorization': 'NOT_CHECKED',
                       'uploaded': False, 'sealed': False, 'runtimeVerification': 'NOT_PERFORMED',
                       'systemConsistency': 'NOT_ASSESSED', 'repairPlanGenerated': False}}


def encode(obj):
    return (json.dumps(obj, ensure_ascii=True, indent=2) + '\n').encode('utf-8')


def write_new(path, data):
    with path.open('xb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def prepare(paths, output, fixture=False, now_ms=None):
    raw = {role: read_bytes(paths[role]) for role in LOCAL_ROLES}
    manifest = build_manifest(raw, fixture, time.time_ns() // 1000000 if now_ms is None else now_ms)
    output = Path(os.path.abspath(output))
    safe_path(output.parent)
    output.mkdir(mode=0o700, exist_ok=False)
    (output / 'attachments-private').mkdir(mode=0o700)
    (output / 'context-private').mkdir(mode=0o700)
    # 清单最后提交；中途失败保留不完整目录，禁止就地重试或覆盖。
    for role, reference in {**manifest['attachments'], **manifest['localContext']}.items():
        write_new(output / reference['path'], raw[role])
    manifest_bytes = encode(manifest)
    require(len(manifest_bytes) <= baseline.MAX_BYTES, 'MANIFEST_SIZE_LIMIT')
    write_new(output / 'manifest-private.json', manifest_bytes)
    seal = digest(manifest_bytes)
    write_new(output / 'local-commit.json', encode({'manifest': seal, 'privacy': 'PRIVATE', 'serverSealed': False}))
    verify(output, seal['sha256'])
    return seal


def verify(output, expected_sha256):
    output = safe_path(output)
    expected_sha256 = hash_value(expected_sha256)
    raw_manifest = read_bytes(output / 'manifest-private.json')
    require(digest(raw_manifest)['sha256'] == expected_sha256, 'MANIFEST_DIGEST_MISMATCH')
    commit = baseline.strict_json(read_bytes(output / 'local-commit.json'))
    match_digest(commit['manifest'], raw_manifest)
    require(commit['serverSealed'] is False and commit['privacy'] == 'PRIVATE', 'LOCAL_COMMIT_MISMATCH')
    manifest = baseline.strict_json(raw_manifest)
    require(set(manifest['attachments']) == set(ROLES)
            and set(manifest['localContext']) == {'parent_request', 'parent_result'}, 'ATTACHMENT_ROLES_MISMATCH')
    raw = {}
    expected_files = {'manifest-private.json', 'local-commit.json'}
    for role, ref in {**manifest['attachments'], **manifest['localContext']}.items():
        name = ('attachments-private/' if role in ROLES else 'context-private/') + role + '.json'
        require(ref['path'] == name, 'ATTACHMENT_PATH_MISMATCH')
        raw[role] = read_bytes(output / name)
        match_digest(ref, raw[role])
        expected_files.add(name)
    actual_files = set()
    for path in output.rglob('*'):
        safe_path(path)
        if path.is_file():
            actual_files.add(path.relative_to(output).as_posix())
        else:
            require(path.relative_to(output).as_posix() in ('attachments-private', 'context-private'), 'UNEXPECTED_DIRECTORY')
    require(actual_files == expected_files, 'UNEXPECTED_OR_MISSING_ATTACHMENT')
    rebuilt = build_manifest(raw, manifest['fixture'], manifest['preparedAtMs'])
    require(same(rebuilt, manifest), 'BUNDLE_CONTRACT_MISMATCH')
    return digest(raw_manifest)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    create = sub.add_parser('prepare', help='只准备全新私有包，不上传')
    for role in LOCAL_ROLES:
        create.add_argument('--' + role.replace('_', '-'), required=True)
    create.add_argument('--output', required=True)
    create.add_argument('--fixture', action='store_true', help='明确标记合成夹具，不代表真实任务')
    check = sub.add_parser('verify', help='从原字节重新核对绑定')
    check.add_argument('--output', required=True)
    check.add_argument('--manifest-sha256', required=True, help='使用准备时另行保留的清单摘要')
    args = parser.parse_args(argv)
    try:
        result = prepare({r: getattr(args, r) for r in LOCAL_ROLES}, args.output, args.fixture) \
            if args.action == 'prepare' else verify(args.output, args.manifest_sha256)
        print(json.dumps({'localBinding': 'CONSISTENT', 'manifest': result, 'serverAuthorization': 'NOT_CHECKED'}))
        return 0
    except (ValueError, OSError, KeyError, TypeError, IndexError, RecursionError) as error:
        # 不把解析异常中的私有字段、原件内容或宿主路径写到公共终端。
        code = str(error) if isinstance(error, BindingError) else 'INPUT_OR_IO_REJECTED'
        print('离线校验拒绝：' + code + '；原件未改写，不完整输出不得上传。', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
