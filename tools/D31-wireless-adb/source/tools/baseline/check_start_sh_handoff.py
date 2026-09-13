"""复用原prepare合同核验已审核交接包；只读，不生成新事务。"""
import argparse
import json
from pathlib import Path
import sys
sys.dont_write_bytecode = True
import start_sh_repair as repair


def check(file, expected, device, java, now, historical=False):
    repair.dependencies(Path(__file__).resolve().parent)
    eb, need = repair.eb, repair.need
    file = Path(file).resolve()
    raw = eb.read_bytes(file)
    need(repair.digest(raw) == expected, 'HANDOFF_DIGEST_MISMATCH')
    h = eb.baseline.strict_json(raw)
    need(h['format'] == 'd31-start-sh-review-handoff-1' and h['executed'] is False
         and h['status'] == 'REVIEW_ONLY_NOT_EXECUTION_AUTHORIZATION', 'HANDOFF_FORMAT')
    need(h['device_id'] == device and h['executor'] == 'net.elfradio.d31bootstrap.RemoteRepairCommand', 'HANDOFF_IDENTITY')
    def read(relative):
        p = (file.parent / relative).resolve()
        need(p.is_relative_to(file.parent) and p != file.parent, 'LOCAL_PATH_OUTSIDE_HANDOFF')
        return eb.read_bytes(p)
    prepared_raw = read('prepared/prepared-private.json')
    need(repair.digest(prepared_raw) == h['prepared_sha256'], 'PREPARED_DIGEST_MISMATCH')
    p = eb.baseline.strict_json(prepared_raw)
    need(type(p['fixture']) is bool and p['fixture'] == h['fixture'] and p['device_id'] == device, 'PREPARED_IDENTITY')
    plan = p['plan']
    canonical = java.call('plan', plan)
    need(canonical['plan_sha256'] == p['plan_sha256'] == h['plan_sha256'], 'CANONICAL_PLAN_MISMATCH')
    need(plan['task_id'] == h['task_id'] and plan['device_class'] == 'D31'
         and plan['plan_id'] == 'system-support-root' and plan['revision'] == 'v144-loop1'
         and len(plan['changes']) == 1 and plan['dependencies'] == [], 'FIXED_PLAN_REQUIRED')
    c = plan['changes'][0]
    need(c['path'] == repair.LOGICAL and c['id'] == 'start' and c['artifact'] == 'start-script'
         and c['after'] == [], 'FIXED_START_SH_ONLY')
    need(h['evidence_sha256'] == p['evidence_bundle_sha256'] == plan['evidence_sha256'], 'EVIDENCE_BINDING')
    record, evidence_raw, objects = repair.bundle(file.parent / 'evidence', h['evidence_sha256'])
    need(record['identity']['device_id'] == device and record['fixture'] == h['fixture'], 'BUNDLE_IDENTITY')
    for role in ('firmware', 'firmware_generation'):
        eb.match_digest(p[role], evidence_raw[role])
    repair.context_review(objects, h['evidence_sha256'], p['context_review'])
    original, target = read('prepared/preimage-private.sh'), read('prepared/target-payload.sh')
    eb.match_digest({'sha256': c['original_sha256'], 'bytes': c['original_bytes']}, original)
    eb.match_digest(p['preimageProof']['data'], original)
    eb.match_digest({'sha256': c['target_sha256'], 'bytes': c['target_bytes']}, target)
    need(p['mapped']['destination'] == repair.TARGET and p['mapped']['action'] == 'copy', 'FIXED_MAPPING_REQUIRED')
    need(repair.root_script(target)[0] == repair.ROOT and original != target, 'TARGET_ROOT_OR_NO_DIFFERENCE')
    firmware = repair.entry(objects['firmware'])
    need(repair.observed(firmware, 'sha256') == repair.digest(target), 'FIRMWARE_TARGET_BINDING')
    m = repair.check_observation(objects, original, target, p['mapped'], now, historical=historical)
    need(eb.same(p['observationIdentity'], {k: m[k] for k in eb.IDENTITY})
         and p['before']['capturedAtMs'] == m['capturedAtMs'], 'OBSERVATION_IDENTITY')
    repair.compare(java, objects, 'DIFFERENT', m['capturedAtMs'] if historical else now)
    need(h['payload'] == {'local': 'prepared/target-payload.sh',
         'remote': '/data/local/d31-remote/repair-input/' + h['task_id'] + '/artifacts/start-script',
         'sha256': c['target_sha256'], 'bytes': c['target_bytes']}, 'PAYLOAD_BINDING')
    requests = {}
    need(set(h['requests']) == {'submit', 'run', 'query'}, 'REQUEST_SET')
    for operation in ('submit', 'run', 'query'):
        item = h['requests'][operation]
        need(item['path'] == 'prepared/' + operation + '-request.json', 'REQUEST_PATH')
        data = read(item['path'])
        eb.match_digest(item, data)
        request = eb.baseline.strict_json(data)
        expected_request = {'operation': operation, 'task_id': h['task_id'], 'plan_sha256': h['plan_sha256']}
        if operation == 'submit': expected_request['plan'] = plan
        need(eb.same(request, expected_request), 'REQUEST_PLAN_BINDING')
        requests[operation] = request
    return {'handoffSha256': expected, 'deviceId': device, 'taskId': h['task_id'], 'planSha256': h['plan_sha256'],
            'preparedSha256': h['prepared_sha256'], 'evidenceSha256': h['evidence_sha256'], 'fixture': h['fixture'],
            'capturedAtMs': m['capturedAtMs'], 'validUntilMs': min(m['validUntilMs'], m['capturedAtMs'] + 300000),
            'bootId': p['boot']['id'], 'payload': h['payload'], 'requests': requests,
            'closedLoopVerified': False, 'runtimeVerification': 'NOT_PERFORMED'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('handoff', 'sha256', 'device-id', 'java', 'classpath', 'now-ms'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--historical', action='store_true')
    args = parser.parse_args()
    try:
        result = check(args.handoff, args.sha256, args.device_id, repair.Java(args.java, args.classpath),
                       int(args.now_ms), args.historical)
        print(json.dumps(result, ensure_ascii=False))
    except Exception as error:
        # 只输出受控错误码，不输出可能含私人路径/观测的第三方异常正文。
        code = str(error) if type(error) is ValueError and str(error).replace('_', '').isalnum() else type(error).__name__
        print('本地交接核验失败：' + code, file=sys.stderr)
        raise SystemExit(2) from None
