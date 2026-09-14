"""衔接原交接、后置原件和原verify；仅本地文件与原Java合同。"""
import json
from pathlib import Path
import shlex
import sys
import time

sys.dont_write_bytecode = True
import check_start_sh_handoff as handoff
import start_sh_repair as repair


def preflight(options, java, now):
    value = handoff.check(options['handoff'], options['handoffSha256'], options['deviceId'], java, now, historical=True)
    eb, need = repair.eb, repair.need
    root = Path(options['handoff']).resolve().parent
    prepared = repair.obj(root / 'prepared/prepared-private.json')
    raw = eb.read_bytes(options['handoffResult'])
    need(repair.digest(raw) == options['handoffResultSha256'], 'QUERY_CAPTURE_DIGEST_MISMATCH')
    capture = eb.baseline.strict_json(raw)
    for key in ('handoffSha256', 'preparedSha256', 'planSha256', 'deviceId', 'taskId', 'fixture'):
        need(eb.same(capture[key], value[key]), 'QUERY_CAPTURE_BINDING')
    request, response = capture['queryRequest'], capture['queryResult']
    task = repair.parent(request, response, value['deviceId'], 'root_exec')
    result = task['result']
    need(result.get('action') == 'completed' and type(result.get('exit_code')) is int
         and result['exit_code'] == 0 and result.get('truncated') is False, 'QUERY_OUTPUT_INCOMPLETE')
    apk = '/data/local/d31-remote/releases/' + eb.hash_value(options['apkSha256']) + '/remote.apk'
    argv = shlex.split(request['params']['command'])
    need(len(argv) == 5 and argv[:4] == ['CLASSPATH=' + apk, '/system/bin/app_process', '/system/bin',
         'net.elfradio.d31bootstrap.RemoteRepairCommand'], 'QUERY_COMMAND_SHAPE')
    need(eb.same(eb.baseline.strict_json(argv[4].encode()), value['requests']['query']), 'QUERY_PLAN_BINDING')
    receipt = eb.embedded_json(result['text'])
    need(receipt.get('schema') == 1 and receipt.get('task_id') == value['taskId']
         and receipt.get('plan_sha256') == value['planSha256'], 'RECEIPT_PLAN_BINDING')
    need(receipt.get('verification_scope') == 'FILE_CONTENT_AND_METADATA' and receipt.get('runtime_effect') == 'NOT_CHECKED'
         and receipt.get('system_consistency') == 'NOT_ASSESSED', 'RECEIPT_SCOPE')
    count = eb.integer(receipt['next_event'])
    need(0 < count <= 256 and receipt['state']['phase'] in ('SUCCEEDED', 'REJECTED', 'ROLLED_BACK', 'NEEDS_ATTENTION'), 'TERMINAL_QUERY_REQUIRED')
    journal = '/data/local/d31-remote/repairs/' + value['taskId']
    need(receipt.get('journal') == journal, 'REPAIR_JOURNAL_MISMATCH')
    start, end = repair.stamp(task['started_at']), repair.stamp(task['completed_at'])
    need(end >= start > prepared['preimageProof']['completedAtMs'], 'QUERY_TIME_INVALID')
    need(type(options['version']) is int and options['version'] > 0, 'VERSION_REQUIRED')
    return {'value': value, 'prepared': prepared, 'queryRequest': request, 'queryResult': response,
            'receipt': receipt, 'queryEnd': end, 'journal': journal, 'apk': apk}


def finalize(options, collection, output, java, now):
    context = preflight(options, java, now)
    eb, need = repair.eb, repair.need
    device = context['value']['deviceId']
    root = Path(options['handoff']).resolve().parent
    proof = collection['proofs']
    # 先复核取回父任务与原字节，再组原证据包；不能以本地目标载荷充当后像。
    diagnostic = proof['diagnostic']
    parent_request, parent_result = repair.obj(diagnostic['request']), repair.obj(diagnostic['result'])
    task = repair.parent(parent_request, parent_result, device, 'root_exec')
    result = task['result']
    need(result.get('action') == 'completed' and type(result.get('exit_code')) is int
         and result['exit_code'] == 0 and result.get('truncated') is False, 'DIAGNOSTIC_OUTPUT_INCOMPLETE')
    diagnostic_id, request = eb.command_request(parent_request['params']['command'])
    need(diagnostic_id == collection['diagnosticId'] and eb.same(request, collection['diagnosticRequest']), 'DIAGNOSTIC_BINDING')
    receipt = eb.embedded_json(result['text'])
    report_path = '/data/local/d31-remote/diagnostics/' + diagnostic_id + '/report.json'
    observation, returned = repair.returned(proof['observation'], device, report_path, context['queryEnd'])
    need(returned['startedAtMs'] > repair.stamp(task['completed_at']), 'REPORT_NOT_AFTER_DIAGNOSTIC')
    eb.match_digest(receipt, observation)
    after, after_proof = repair.returned(proof['after_file'], device, repair.TARGET, context['queryEnd'])
    need(after_proof['startedAtMs'] > returned['completedAtMs'], 'AFTER_FILE_ORDER')
    before_record, before_raw, before_objects = repair.bundle(root / 'evidence', context['value']['evidenceSha256'])
    observed = eb.baseline.strict_json(observation)
    report = java.call('compare', {'observation': observed['manifest'], 'firmware': before_objects['firmware'], 'now': now})
    report['inputs'] = {role: {**eb.digest(raw), 'manifestPointer': pointer} for role, raw, pointer in
                        [('observation', observation, '/manifest'), ('firmware', before_raw['firmware'], '')]}
    output.mkdir(mode=0o700, exist_ok=False)
    inputs = output / 'inputs'; inputs.mkdir()
    raw = {'request': eb.encode(request), 'receipt': eb.encode(receipt), 'report': eb.encode(report),
           'observation': observation, 'firmware': before_raw['firmware'], 'firmware_generation': before_raw['firmware_generation'],
           'parent_request': eb.read_bytes(diagnostic['request']), 'parent_result': eb.read_bytes(diagnostic['result'])}
    paths = {}
    for role, data in raw.items():
        paths[role] = str(inputs / (role + '.json')); eb.write_new(Path(paths[role]), data)
    seal = eb.prepare(paths, output / 'after-bundle', fixture=before_record['fixture'], now_ms=now)
    for name in ('queryRequest', 'queryResult'):
        repair.save(output / (name + '.json'), context[name])
    config = {'prepared': str(root / 'prepared/prepared-private.json'), 'prepared_sha256': context['value']['preparedSha256'],
              'before_bundle': str(root / 'evidence'), 'target_payload': str(root / 'prepared/target-payload.sh'),
              'query_request': str(output / 'queryRequest.json'), 'query_result': str(output / 'queryResult.json'),
              'device_plan': proof['plan'], 'events': [proof[f'event-{i:06d}'] for i in range(context['receipt']['next_event'])],
              'after_bundle': str(output / 'after-bundle'), 'after_evidence_sha256': seal['sha256'],
              'after_file': proof['after_file'], 'boot': proof['boot']}
    repair.save(output / 'verify-config-private.json', config)
    verified = repair.verify(config, output / 'verified', java, now)
    return {'closedLoopVerified': verified['closed'], 'fixture': verified['fixture'], 'status': verified['status'],
            'runtimeVerification': verified['runtimeVerification'], 'systemConsistency': verified['systemConsistency'],
            'verifyConfig': str(output / 'verify-config-private.json')}


if __name__ == '__main__':
    try:
        data = json.load(sys.stdin)
        options = data['options']
        java = repair.Java(options['java'], options['classpath'])
        now = time.time_ns() // 1000000
        if data['action'] == 'preflight':
            result = preflight(options, java, now)
        else:
            repair.need(data['action'] == 'finalize', 'ACTION_INVALID')
            result = finalize(options, data['collection'], Path(data['output']).resolve(), java, now)
        print(json.dumps(result))
    except Exception as error:
        code = str(error)
        print(code if code and all(c in 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_' for c in code)
              else 'LOCAL_POST_EVIDENCE_REJECTED', file=sys.stderr)
        raise SystemExit(2) from None
