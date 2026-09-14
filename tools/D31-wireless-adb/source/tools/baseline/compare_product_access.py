"""比较两份已保全的产品访问报告；固定范围、只读、不输出实际值。"""
import argparse
import hashlib
import os
from pathlib import Path
import sys

sys.dont_write_bytecode = True
from compare_baseline import MAX_BYTES, strict_json, save_new

CATALOG = 'd31-product-access-1'
SMS = 'net.elfradio.d31phone.debug'
REMOTE = 'net.elfradio.d31bootstrap'
GUARD = 'net.elfradio.d31zelloguard/net.elfradio.d31zelloguard.GuardAccessibilityService'
APP_OPS = ((SMS, 'READ_SMS'), (SMS, 'SEND_SMS'), (SMS, 'WRITE_SMS'), ('com.starnet.getnumber', 'SEND_SMS'),
           (REMOTE, 'CAMERA'), (REMOTE, 'RECORD_AUDIO'), (REMOTE, 'FINE_LOCATION'), (REMOTE, 'COARSE_LOCATION'))
APP_OP_IDS = tuple('appop:' + package + ':' + op for package, op in APP_OPS)
BOOLEAN_IDS = ('setting:accessibility_enabled', 'accessibility_service:' + GUARD, 'accessibility_bound:' + GUARD)
IDS = APP_OP_IDS + BOOLEAN_IDS
MODES = {0: 'allowed', 1: 'ignored', 2: 'errored', 3: 'default'}
FACTORY_RELATIVE = 'research/d31/staging/b15-configuration-v144-20260913/inputs/handover/FactoryInit.java'
FACTORY_SHA256 = '73cb8fc4d374b1227ace2d5c92c493096038a0d867ef077023c40ee13a2404b4'


def read_json(path):
    with Path(path).open('rb') as stream:
        before = os.fstat(stream.fileno())
        data = stream.read(MAX_BYTES + 1)
        after = os.fstat(stream.fileno())
    if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
        raise ValueError('INPUT_CHANGED_DURING_READ')
    return strict_json(data), {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}


def validate_report(report):
    if not isinstance(report, dict): raise ValueError('REPORT_OBJECT_REQUIRED')
    payload = report.get('report', report)
    product = payload.get('productAccess') if isinstance(payload, dict) else None
    if not isinstance(product, dict): raise ValueError('PRODUCT_ACCESS_REQUIRED')
    if set(product) != {'schemaVersion', 'catalog', 'userId', 'capturedAtMs', 'facts', 'observed', 'total',
                        'status', 'atomicSnapshot', 'executionState', 'systemConsistency', 'repairPlanGenerated'}:
        raise ValueError('PRODUCT_FIELDS_INVALID')
    if (type(product.get('schemaVersion')) is not int or product['schemaVersion'] != 1
            or product.get('catalog') != CATALOG or type(product.get('userId')) is not int or product['userId'] != 0):
        raise ValueError('CATALOG_OR_SCHEMA_INVALID')
    if type(product.get('capturedAtMs')) is not int or product['capturedAtMs'] < 0:
        raise ValueError('CAPTURE_TIME_INVALID')
    if (product['atomicSnapshot'] is not False or product['repairPlanGenerated'] is not False
            or product['executionState'] != 'NOT_CHECKED' or product['systemConsistency'] != 'NOT_ASSESSED'):
        raise ValueError('PRODUCT_BOUNDARY_INVALID')
    facts = product.get('facts')
    if not isinstance(facts, list) or len(facts) != len(IDS): raise ValueError('FACT_COUNT_INVALID')
    result = {}
    observed = 0
    for fact in facts:
        if not isinstance(fact, dict): raise ValueError('FACT_OBJECT_REQUIRED')
        identity = fact.get('id')
        if not isinstance(identity, str) or identity not in IDS or identity in result:
            raise ValueError('FACT_ID_INVALID')
        source = 'app_ops' if identity in APP_OP_IDS else dict(zip(BOOLEAN_IDS,
                ('secure.accessibility_enabled', 'secure.enabled_accessibility_services', 'accessibility_manager')))[identity]
        if fact.get('source') != source: raise ValueError('FACT_SOURCE_INVALID')
        if fact.get('state') == 'OBSERVED':
            if set(fact) != {'id', 'source', 'state', 'value'}: raise ValueError('OBSERVED_FIELDS_INVALID')
            observed += 1
            value = fact.get('value')
            if identity in APP_OP_IDS:
                if (not isinstance(value, dict) or set(value) != {'mode', 'modeName'}
                        or type(value.get('mode')) is not int or value['mode'] not in MODES
                        or value.get('modeName') != MODES[value['mode']]):
                    raise ValueError('APPOP_VALUE_INVALID')
            elif type(value) is not bool:
                raise ValueError('BOOLEAN_VALUE_INVALID')
        elif fact.get('state') == 'READ_FAILED':
            if set(fact) != {'id', 'source', 'state', 'reason'} or not isinstance(fact.get('reason'), str) or not fact['reason']:
                raise ValueError('FAILED_FACT_INVALID')
        else:
            raise ValueError('FACT_STATE_INVALID')
        result[identity] = fact
    if (type(product.get('total')) is not int or product['total'] != len(IDS)
            or type(product.get('observed')) is not int or product['observed'] != observed
            or product.get('status') != ('COMPLETE' if observed == len(IDS) else 'PARTIAL')):
        raise ValueError('SUMMARY_INVALID')
    return product, result


def initial_reference(factory_source):
    path = Path(factory_source)
    data = path.read_bytes() if path.is_file() else None
    digest = hashlib.sha256(data).hexdigest() if data is not None else None
    binding = {'sha256': digest, 'requiredSha256': FACTORY_SHA256,
               'state': 'MATCHED' if digest == FACTORY_SHA256 else 'MISSING' if data is None else 'MISMATCH'}
    values, refs = {}, {}
    if digest == FACTORY_SHA256:
        text = data.decode('utf-8-sig').splitlines()
        def line(needle):
            return next(n for n, item in enumerate(text, 1) if needle in item)
        values[APP_OP_IDS[2]] = {'mode': 0, 'modeName': 'allowed'}
        refs[APP_OP_IDS[2]] = [line('"set",sms,"WRITE_SMS","allow"')]
        values[APP_OP_IDS[3]] = {'mode': 1, 'modeName': 'ignored'}
        refs[APP_OP_IDS[3]] = [line('"set","com.starnet.getnumber","SEND_SMS","ignore"')]
        if len(BOOLEAN_IDS) == 3:
            values[BOOLEAN_IDS[0]] = True
            refs[BOOLEAN_IDS[0]] = [line('putSetting("secure","accessibility_enabled","1")')]
            values[BOOLEAN_IDS[1]] = True
            refs[BOOLEAN_IDS[1]] = [line('String guard='), line('putSetting("secure","enabled_accessibility_services"')]
    return binding, values, refs


def compare(left, right, factory_source):
    left_product, left_rows = validate_report(left)
    right_product, right_rows = validate_report(right)
    binding, expected, refs = initial_reference(factory_source)
    counts = dict.fromkeys(('MATCH', 'DIFFERENT', 'UNKNOWN'), 0)
    rows = []
    for identity in IDS:
        lrow, rrow = left_rows[identity], right_rows[identity]
        known = lrow['state'] == rrow['state'] == 'OBSERVED'
        relation = ('MATCH' if lrow['value'] == rrow['value'] else 'DIFFERENT') if known else 'UNKNOWN'
        counts[relation] += 1
        def reference(row):
            if identity not in expected: return {'result': 'UNKNOWN', 'reason': 'EXPECTATION_UNKNOWN'}
            if row['state'] != 'OBSERVED': return {'result': 'UNKNOWN', 'reason': 'OBSERVATION_UNKNOWN'}
            return {'result': 'MATCH' if row['value'] == expected[identity] else 'DIFFERENT',
                    'reason': 'INITIAL_REFERENCE_ONLY'}
        classification = ('PRODUCT_SMS_SUPPRESSION' if identity == APP_OP_IDS[3] else
                          'USER_REVOCABLE_AUTHORIZATION' if identity in (APP_OP_IDS[2], *BOOLEAN_IDS[:2]) else 'RUNTIME_OBSERVATION_ONLY')
        rows.append({'id': identity, 'classification': classification, 'result': relation,
                     'leftState': lrow['state'], 'rightState': rrow['state'],
                     'initialReference': {'state': 'SOURCE_BOUND' if identity in expected else 'EXPECTATION_UNKNOWN',
                                          'sourceLines': refs.get(identity, []),
                                          'left': reference(lrow), 'right': reference(rrow)}})
    return {'schemaVersion': 1, 'catalog': CATALOG, 'extent': 'FINITE_11_FACT_TWO_REPORT_COMPARISON',
            'total': len(IDS), 'counts': counts, 'facts': rows,
            'result': 'DIFFERENT' if counts['DIFFERENT'] else 'UNKNOWN' if counts['UNKNOWN'] else 'MATCH',
            'captureOrder': 'NONDECREASING' if left_product['capturedAtMs'] <= right_product['capturedAtMs'] else 'REVERSED',
            'factorySource': binding, 'identityAndBootBinding': 'NOT_VERIFIED_BY_THIS_TOOL',
            'runtimeApplicability': 'NOT_VERIFIED', 'executionState': 'NOT_CHECKED',
            'atomicSnapshot': False, 'systemConsistency': 'NOT_ASSESSED', 'repairPlanGenerated': False,
            'note': '两报告由原宿主完成身份与原件保全；此处只比固定事实，不回显原值、设备标识或任意note。初装参考不证明当前消费者匹配或实际绑定。'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--left', required=True)
    parser.add_argument('--right', required=True)
    parser.add_argument('--factory-source', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    output = Path(args.output)
    if output.is_symlink(): parser.error('输出路径已存在')
    try: output.mkdir(mode=0o700, exist_ok=False)
    except OSError: parser.error('输出须为父目录已存在的全新私有目录')
    try:
        left, lref = read_json(args.left)
        right, rref = read_json(args.right)
        result = compare(left, right, args.factory_source)
        result['inputs'] = {'left': lref, 'right': rref}
        save_new(output / 'comparison.json', result)
        print('已生成11项有限差异与初装参考；未生成修复。')
        return 0
    except Exception:
        save_new(output / 'rejection.json', {'generated': False, 'reason': '报告或固定合同校验失败', 'repairPlanGenerated': False})
        print('报告或固定合同校验失败；拒绝记录已保存。', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
