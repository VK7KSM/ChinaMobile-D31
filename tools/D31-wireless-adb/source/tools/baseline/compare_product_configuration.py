"""对照38项产品事实与显式预期；只生成有限旁注，不生成修复。"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
from compare_baseline import MAX_BYTES, strict_json, save_new

CATALOG = 'd31-product-runtime-1'
CLASSIFICATIONS = frozenset(('PRODUCT_INITIAL_REFERENCE', 'USER_SELECTABLE_DEFAULT',
                            'USER_REVOCABLE_AUTHORIZATION', 'USER_REVOCABLE_POWER_EXCEPTION'))
REMOTE, SUPPORT, SMS, GUARD = ('net.elfradio.d31bootstrap', 'net.elfradio.d31system',
                              'net.elfradio.d31phone.debug', 'net.elfradio.d31zelloguard')
PACKAGES = (REMOTE, SUPPORT, SMS, GUARD, 'com.starnet.nexui', 'com.starnet.getnumber', 'com.loudtalks')
COMPONENTS = (SUPPORT + '/' + SUPPORT + '.SystemReceiver', SUPPORT + '/' + SUPPORT + '.MessageNotificationListener',
              GUARD + '/' + GUARD + '.GuardAccessibilityService', GUARD + '/' + GUARD + '.GuardNotificationListener',
              *('com.starnet.getnumber/com.starnet.getnumber.' + n for n in ('BootReceiver', 'GetNumberService', 'SmsListener', 'MainActivity')),
              *(REMOTE + '/' + REMOTE + '.' + n for n in ('BootReceiver', 'VendorNetworkReceiver', 'RemoteManualReceiver')))
PERMISSIONS = [(SMS, p) for p in ('READ_SMS', 'SEND_SMS', 'RECEIVE_SMS', 'READ_CONTACTS', 'READ_EXTERNAL_STORAGE')]
PERMISSIONS += [(SUPPORT, 'READ_PHONE_STATE')]
PERMISSIONS += [('com.loudtalks', 'RECORD_AUDIO')]
PERMISSIONS += [(REMOTE, p) for p in ('READ_LOGS', 'DUMP', 'WRITE_SECURE_SETTINGS', 'CAMERA', 'RECORD_AUDIO',
                                    'READ_PHONE_STATE', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION')]
PACKAGE_FIELDS = ('installed', 'versionCode', 'system', 'updatedSystem', 'privileged', 'enabled', 'enabledSetting')
COMPONENT_FIELDS = ('installed', 'enabled', 'enabledSetting', 'manifestEnabled')
IDS = (['package:' + p for p in PACKAGES] + ['component:' + p for p in COMPONENTS]
       + ['permission:' + p + ':android.permission.' + n for p, n in PERMISSIONS]
       + ['setting:default_sms_is_quik'] + ['listener:' + c for c in (COMPONENTS[1], COMPONENTS[3])]
       + ['battery_exempt:' + p for p in ('com.loudtalks', GUARD)])
SOURCES = {
    'factory': ('research/d31/staging/b15-configuration-v144-20260913/inputs/handover/FactoryInit.java',
                '73cb8fc4d374b1227ace2d5c92c493096038a0d867ef077023c40ee13a2404b4'),
    'packages': ('research/d31/analysis/2026-09-13-factory-v1.4.4/system_payload/init-package-restrictions.xml',
                 'd573d17073f1085545dc7db9a499523140b98a38766adaddf3aed9f50fa1e865'),
    'permissions': ('research/d31/analysis/2026-09-13-factory-v1.4.4/system_payload/init-runtime-permissions.xml',
                    '7f74c2cdb8f95d93396fcb1fa153caf225b3236c35c1d2e6a51d460f08181249'),
    'installer': ('research/d31/staging/b15-configuration-v144-20260913/inputs/update_binary.c',
                  '7301f3b2766d0e8f4bdc97008bb00fb7f5c7a8395ced2dc7d54e36a8c7eeb428')}


def fields_for(identity):
    return PACKAGE_FIELDS if identity.startswith('package:') else COMPONENT_FIELDS if identity.startswith('component:') else ('value',)


def unknown(reason='NO_BOUND_SOURCE_FOR_FIELD'):
    return {'state': 'EXPECTATION_UNKNOWN', 'reason': reason}


def build_expected(workspace):
    """只从已绑定的冻结源派生，不从当前设备或新版清单补猜。"""
    bindings, texts = {}, {}
    for name, (relative, pinned) in SOURCES.items():
        path = Path(workspace) / relative
        data = path.read_bytes() if path.is_file() else None
        sha = hashlib.sha256(data).hexdigest() if data is not None else None
        bindings[name] = {'path': relative, 'sha256': sha, 'requiredSha256': pinned,
                          'state': 'MATCHED' if sha == pinned else 'MISSING' if data is None else 'MISMATCH'}
        if sha == pinned: texts[name] = data.decode('utf-8-sig')

    def ref(name, needle):
        return {'source': name, 'line': next(i for i, line in enumerate(texts[name].splitlines(), 1) if needle in line)}

    def known(value, refs):
        return {'state': 'KNOWN', 'value': value, 'sources': refs}

    packages = ET.fromstring(texts['packages']) if 'packages' in texts else None
    permissions = ET.fromstring(texts['permissions']) if 'permissions' in texts else None
    rows = []
    for identity in IDS:
        values = {name: unknown() for name in fields_for(identity)}
        row = {'id': identity, 'fields': values, 'classification': 'PRODUCT_INITIAL_REFERENCE'}
        if identity.startswith('package:'):
            package = identity.split(':', 1)[1]
            node = next((p for p in (packages if packages is not None else []) if p.get('name') == package), None)
            if node is not None and 'factory' in texts:
                origin = [ref('packages', 'name="' + package + '"')]
                values['installed'] = known(True, origin + [ref('factory', 'if (info == null)')])
                values['enabledSetting'] = known(int(node.get('enabled')), origin + [ref('factory', 'int desired=')])
            # 安装器只说明文件部署，不提供运行PM的版本、updatedSystem或组件有效状态。
            if package == 'com.starnet.nexui':
                values['installed'] = unknown('INSTALLER_APK_PATH_IS_NOT_BOUND_PACKAGE_METADATA')
        elif identity.startswith('component:'):
            package, component = identity.split(':', 1)[1].split('/')
            node = next((p for p in (packages if packages is not None else []) if p.get('name') == package), None)
            if node is not None and 'factory' in texts:
                for group in node:
                    for item in group:
                        if item.get('name') == component and group.tag in ('disabled-components', 'enabled-components'):
                            wanted = 2 if group.tag == 'disabled-components' else 1
                            origin = [ref('packages', 'name="' + component + '"'), ref('factory', 'int wanted=')]
                            values['enabledSetting'] = known(wanted, origin)
                            if wanted == 2: values['enabled'] = known(False, origin)
        elif identity.startswith('permission:'):
            _, package, permission = identity.split(':', 2)
            node = next((p for p in (permissions if permissions is not None else []) if p.get('name') == package), None)
            item = next((p for p in node if p.get('name') == permission), None) if node is not None else None
            row['classification'] = 'USER_REVOCABLE_AUTHORIZATION'
            if item is not None and item.get('granted') == 'true' and 'factory' in texts:
                # 权限名在不同包可重复，精确记录该包内对应行。
                lines = texts['permissions'].splitlines()
                start = next(i for i, line in enumerate(lines) if 'name="' + package + '"' in line)
                line = next(i + 1 for i in range(start + 1, len(lines)) if 'name="' + permission + '"' in lines[i])
                values['value'] = known(True, [{'source': 'permissions', 'line': line}, ref('factory', 'int granted=')])
            else:
                values['value'] = unknown('NO_BOUND_RUNTIME_GRANT_SOURCE')
        elif identity == 'setting:default_sms_is_quik':
            row['classification'] = 'USER_SELECTABLE_DEFAULT'
            if 'factory' in texts:
                values['value'] = known(True, [ref('factory', 'String sms='), ref('factory', 'putSetting("secure","sms_default_application"')])
        elif identity.startswith('listener:'):
            row['classification'] = 'USER_REVOCABLE_AUTHORIZATION'
            if 'factory' in texts:
                values['value'] = known(True, [ref('factory', identity.split(':', 1)[1]), ref('factory', 'putSetting("secure","enabled_notification_listeners"')])
                row['note'] = '预期目标在系统规范化组件名单中；不把运行接口的短名规范化等同FactoryInit精确字串验证。'
        elif identity.startswith('battery_exempt:'):
            row['classification'] = 'USER_REVOCABLE_POWER_EXCEPTION'
            values['value'] = unknown('MERGED_POWER_EXEMPTION_IS_NOT_FACTORY_USER_WHITELIST_CONTRACT')
            row['note'] = '本批接口报告当前合并功能豁免，不是首次初始化user名单验证；未建立独立合并功能预期，保持未知。'
        rows.append(row)
    definition = Path(workspace) / 'research/d31_adb_bootstrap/app/src/main/java/net/elfradio/d31bootstrap/RemoteProductConfiguration.java'
    return {'schemaVersion': 1, 'catalog': CATALOG, 'kind': 'EXPLICIT_PRODUCT_EXPECTATIONS', 'userId': 0,
            'total': len(IDS), 'catalogSourceSha256': hashlib.sha256(definition.read_bytes()).hexdigest() if definition.is_file() else None,
            'reference': 'D31-factory-1.4.4', 'sources': bindings, 'facts': rows,
            'requiredConsumerSha256': '1dc959ec6e6513d48b8894042c347b749057690dd2674eea69ff639b84794978',
            'runtimeApplicability': 'NOT_VERIFIED', 'repairPlanGenerated': False,
            'notes': ['仅正式初装参考；运行报告没有消费者绑定时，不判初始化执行或固件适用性。',
                      '包enabledSetting=0是DEFAULT，不是禁用；版本和身份未由安装文件路径推断。',
                      '四个组件缺已绑定APK声明/覆盖值来源，保留未知；三个系统权限不凭系统路径或uses-permission猜授予。',
                      '事实名单固定38项；旧30项候选不再作为本批名单。']}


def index_rows(rows):
    if not isinstance(rows, list) or len(rows) > len(IDS): raise ValueError('FACT_COUNT_INVALID')
    result = {}
    for row in rows:
        if not isinstance(row, dict) or row.get('id') not in IDS or row['id'] in result:
            raise ValueError('FACT_ID_INVALID')
        result[row['id']] = row
    return result


def valid_value(field, value):
    if field in ('versionCode', 'enabledSetting'):
        return type(value) is int and 0 <= value <= (4 if field == 'enabledSetting' else 2147483647)
    return type(value) is bool


def compare(report, expected):
    if not isinstance(report, dict) or not isinstance(expected, dict): raise ValueError('OBJECT_REQUIRED')
    if (expected.get('catalog') != CATALOG or type(expected.get('schemaVersion')) is not int or expected.get('schemaVersion') != 1
            or type(expected.get('userId')) is not int
            or expected.get('kind') != 'EXPLICIT_PRODUCT_EXPECTATIONS' or expected.get('userId') != 0):
        raise ValueError('EXPECTED_CATALOG_INVALID')
    expectations = index_rows(expected.get('facts'))
    if set(expectations) != set(IDS): raise ValueError('EXPECTED_CATALOG_INCOMPLETE')
    # 接收原runtime报告或明确的report包装；不搜索任意嵌套数据。
    payload = report.get('report', report)
    product = payload.get('productConfiguration') if isinstance(payload, dict) else None
    observations = {}
    if product is not None:
        if (not isinstance(product, dict) or type(product.get('schemaVersion')) is not int or product.get('schemaVersion') != 1
                or type(product.get('userId')) is not int
                or product.get('catalog') != CATALOG or product.get('userId') != 0):
            raise ValueError('OBSERVATION_CATALOG_INVALID')
        observations = index_rows(product.get('facts'))
    rows, counts = [], dict.fromkeys(('MATCH', 'DIFFERENT', 'UNKNOWN'), 0)
    for identity in IDS:
        exp = expectations[identity]
        classification = exp.get('classification')
        if not isinstance(classification, str) or classification not in CLASSIFICATIONS:
            raise ValueError('EXPECTED_CLASSIFICATION_INVALID')
        values = exp.get('fields')
        if not isinstance(values, dict) or set(values) != set(fields_for(identity)):
            raise ValueError('EXPECTED_FIELDS_INVALID')
        observed = observations.get(identity, {})
        raw = observed.get('value')
        fields = []
        for name in fields_for(identity):
            expectation = values[name]
            if not isinstance(expectation, dict) or expectation.get('state') not in ('KNOWN', 'EXPECTATION_UNKNOWN'):
                raise ValueError('EXPECTED_STATE_INVALID')
            outcome, reason = 'UNKNOWN', 'EXPECTATION_UNKNOWN'
            if expectation['state'] == 'KNOWN':
                wanted = expectation.get('value')
                refs = expectation.get('sources')
                if not valid_value(name, wanted) or not isinstance(refs, list) or not refs:
                    raise ValueError('EXPECTED_VALUE_OR_SOURCE_INVALID')
                for ref in refs:
                    binding = expected.get('sources', {}).get(ref.get('source'), {}) if isinstance(ref, dict) else {}
                    if (binding.get('state') != 'MATCHED' or binding.get('sha256') != binding.get('requiredSha256')
                            or not isinstance(binding.get('sha256'), str) or len(binding['sha256']) != 64):
                        raise ValueError('EXPECTED_SOURCE_UNBOUND')
                value = raw.get(name) if isinstance(raw, dict) and name != 'value' else raw if name == 'value' else None
                supported = valid_value(name, value)
                if name != 'value' and (not isinstance(raw, dict) or type(raw.get('installed')) is not bool
                                        or name != 'installed' and raw.get('installed') is not True): supported = False
                if observed.get('state') != 'OBSERVED': reason = 'OBSERVATION_UNKNOWN'
                elif not supported: reason = 'OBSERVATION_VALUE_UNAVAILABLE'
                else:
                    outcome = 'MATCH' if type(value) is type(wanted) and value == wanted else 'DIFFERENT'
                    reason = 'RAW_REFERENCE_COMPARISON_ONLY'
            fields.append({'field': name, 'result': outcome, 'reason': reason})
        states = {f['result'] for f in fields}
        outcome = 'DIFFERENT' if 'DIFFERENT' in states else 'UNKNOWN' if 'UNKNOWN' in states else 'MATCH'
        counts[outcome] += 1
        rows.append({'id': identity, 'classification': classification, 'result': outcome, 'fields': fields})
    return {'schemaVersion': 1, 'catalog': CATALOG, 'extent': 'FINITE_38_FACT_CATALOG', 'total': len(IDS),
            'counts': counts, 'result': 'DIFFERENT' if counts['DIFFERENT'] else 'UNKNOWN' if counts['UNKNOWN'] else 'MATCH',
            'facts': rows, 'atomicSnapshot': False, 'runtimeApplicability': 'NOT_VERIFIED',
            'executionState': 'NOT_CHECKED', 'systemConsistency': 'NOT_ASSESSED', 'repairPlanGenerated': False,
            'note': '仅对显式来源预期作有限原始对照，不回显原值/设备标识，不改九项目录或产生修复。'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    build = sub.add_parser('build-expected', help='从冻结正式源生成显式预期')
    build.add_argument('--workspace', required=True)
    build.add_argument('--output', required=True)
    run = sub.add_parser('compare', help='比较原runtime报告与显式预期')
    run.add_argument('--report', required=True)
    run.add_argument('--expected', required=True)
    run.add_argument('--output', required=True)
    args = parser.parse_args()
    output = Path(args.output)
    if output.is_symlink(): parser.error('输出路径已存在')
    try: output.mkdir(mode=0o700, exist_ok=False)
    except OSError: parser.error('输出须为父目录已存在的全新私有目录')
    try:
        if args.command == 'build-expected':
            save_new(output / 'expected.json', build_expected(args.workspace))
        else:
            with Path(args.report).open('rb') as stream: report_bytes = stream.read(MAX_BYTES + 1)
            with Path(args.expected).open('rb') as stream: expected_bytes = stream.read(MAX_BYTES + 1)
            result = compare(strict_json(report_bytes), strict_json(expected_bytes))
            result['inputs'] = {'reportSha256': hashlib.sha256(report_bytes).hexdigest(),
                               'expectedSha256': hashlib.sha256(expected_bytes).hexdigest()}
            save_new(output / 'comparison.json', result)
        print('已生成38项有限离线结果；未核验整体系统，未生成修复。')
        return 0
    except Exception:
        save_new(output / 'rejection.json', {'generated': False, 'reason': '输入或来源合同不符', 'repairPlanGenerated': False})
        print('输入或来源合同不符；仅保存拒绝记录。', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
