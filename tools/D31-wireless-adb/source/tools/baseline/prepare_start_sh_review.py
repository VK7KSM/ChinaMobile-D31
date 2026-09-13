"""从本地采集原件生成可审单文件方案及既有执行器交接清单；不提交或执行。"""
import argparse
import difflib
import json
from pathlib import Path
import shlex
import sys
import time
import uuid

sys.dont_write_bytecode = True
import start_sh_repair as repair


def prepare_review(config, output, java, now):
    eb = repair.eb
    output = Path(output).absolute()
    inputs = config['inputs']
    expected = set(eb.LOCAL_ROLES) - {'report'}
    repair.need(isinstance(inputs, dict) and set(inputs) == expected, 'RAW_INPUT_ROLES_REQUIRED')
    raw = {role: eb.read_bytes(inputs[role]) for role in expected}
    objects = {role: eb.baseline.strict_json(data) for role, data in raw.items()}
    review = config['context_review']
    repair.need(review['observation_sha256'] == repair.digest(raw['observation'])
                and review['firmware_sha256'] == repair.digest(raw['firmware']), 'REVIEW_INPUT_BINDING')
    repair.need(review['purpose'] == repair.CONTEXT_POLICY, 'CONTEXT_REVIEW_PURPOSE')
    report = java.call('compare', {'observation': objects['observation']['manifest'],
                                 'firmware': objects['firmware'], 'now': now})
    report['inputs'] = {role: {**eb.digest(raw[role]), 'manifestPointer': '/manifest' if role == 'observation' else ''}
                        for role in ('observation', 'firmware')}
    output.mkdir(mode=0o700, exist_ok=False)
    repair.save(output / 'report-private.json', report)
    paths = {**inputs, 'report': str(output / 'report-private.json')}
    seal = eb.prepare(paths, output / 'evidence', fixture=config['fixture'], now_ms=now)
    prepared_config = {key: config[key] for key in ('device_id', 'task_id', 'mapping', 'sources', 'package', 'preimage', 'boot')}
    prepared_config.update(evidence_bundle=str(output / 'evidence'), evidence_sha256=seal['sha256'],
                           context_review={'purpose': review['purpose'], 'evidence_sha256': seal['sha256'],
                                           'acceptedDifferences': review['acceptedDifferences']})
    try:
        result = repair.prepare(prepared_config, output / 'prepared', java, now)
    except ValueError as error:
        if str(error) != 'NO_CONTENT_DIFFERENCE':
            raise
        _, _, verified = repair.bundle(output / 'evidence', seal['sha256'])
        actual = repair.compare(java, verified, 'SAME', now)
        repair.save(output / 'comparison-private.json', actual)
        result = {'status': 'NO_CONTENT_DIFFERENCE', 'fileAndRootMatch': True,
                  'repairPlanGenerated': False, 'executed': False, 'fixture': config['fixture'],
                  'evidenceSha256': seal['sha256'], 'serverAuthorization': 'NOT_CHECKED',
                  'runtimeVerification': 'NOT_PERFORMED', 'systemConsistency': 'NOT_ASSESSED'}
        eb.write_new(output / 'review-private.md',
                     ('# 单文件只读比较\n\n固定start.sh真实取回原件与绑定固件载荷相同，ROOT比较为SAME。\n'
                      '原prepare返回NO_CONTENT_DIFFERENCE；没有生成修复方案或请求，没有执行修复。\n'
                      '本结论仅涉及本次原件，不代表运行效果或整机一致。\n').encode('utf-8'))
        repair.save(output / 'result.json', result)
        return result
    prepared = repair.obj(output / 'prepared/prepared-private.json')
    change = prepared['plan']['changes'][0]
    original = eb.read_bytes(output / 'prepared/preimage-private.sh')
    target = eb.read_bytes(output / 'prepared/target-payload.sh')
    # 字节模板及ROOT已经由原prepare验证；这里仅呈现可审差异和原执行器落点。
    diff = ''.join(difflib.unified_diff(original.decode('utf-8').splitlines(True),
                                      target.decode('utf-8').splitlines(True),
                                      fromfile='preimage', tofile='target'))
    remote_root = '/data/local/d31-remote/repair-input/' + prepared['plan']['task_id']
    handoff = {
        'format': 'd31-start-sh-review-handoff-1', 'fixture': prepared['fixture'],
        'executed': False, 'status': 'REVIEW_ONLY_NOT_EXECUTION_AUTHORIZATION',
        'device_id': prepared['device_id'], 'task_id': prepared['plan']['task_id'],
        'plan_sha256': prepared['plan_sha256'], 'prepared_sha256': result['preparedSha256'],
        'evidence_sha256': seal['sha256'], 'executor': 'net.elfradio.d31bootstrap.RemoteRepairCommand',
        'payload': {'local': 'prepared/target-payload.sh',
                    'remote': remote_root + '/artifacts/' + change['artifact'],
                    'sha256': change['target_sha256'], 'bytes': change['target_bytes']},
        'requests': {operation: {'path': 'prepared/' + operation + '-request.json',
                                **eb.digest(eb.read_bytes(output / 'prepared' / (operation + '-request.json')))}
                     for operation in ('submit', 'run', 'query')},
        'postEvidence': {'before_bundle': 'evidence', 'prepared': 'prepared/prepared-private.json',
                         'target_payload': 'prepared/target-payload.sh',
                         'query_request': None, 'query_result': None, 'device_plan': None, 'events': None,
                         'after_bundle': None, 'after_evidence_sha256': None, 'after_file': None, 'boot': None},
        'serverAuthorization': 'NOT_CHECKED', 'runtimeVerification': 'NOT_PERFORMED',
        'systemConsistency': 'NOT_ASSESSED'}
    text = ('# 单文件修复审核\n\n'
            + ('合成夹具，仅用于离线验证，不是真实设备证据。\n\n' if prepared['fixture'] else '') +
            '此包仅供审核，未部署载荷、未提交或运行事务。批准后由既有执行入口消费请求。\n\n'
            '## 已核验范围\n\n'
            '- 固定start.sh已知模板，仅ROOT内容不同；原像来自绑定的文件取回回执。\n'
            '- 目标载荷来自配置中明确绑定的1.4.4映射、来源清单和ZIP。\n'
            '- 上下文差异由调用方按两份原件摘要显式确认，不推定个人配置有误。\n\n'
            '## 内容差异\n\n```diff\n' + diff + '```\n\n'
            '## 主线消费顺序\n\n'
            '1. 审核prepared内方案、原像和目标载荷；另行确认设备及当前活动完整APK。\n'
            '2. 经既有授权通道将载荷放到handoff-private.json登记的artifacts落点并核对摘要；禁止直接覆盖目标。\n'
            '3. 依次使用原RemoteRepairCommand消费submit、run、query请求。submit有副作用，不是预演；未知回执先查询原号，不重建任务。\n'
            '4. 终态后保存原计划和完整事件前缀，使用新诊断ID与快照ID重新采集；保存真实目标后像及同boot回执。\n'
            '5. 将这些实际原件填入原verify配置。未取得的postEvidence保持空，不能填目标载荷或推测值冒充。\n\n'
            '需要维护互斥、原像前检、备份、切换和回滚时，均由既有执行器处理。\n'
            '本包不证明服务器授权、运行效果或整机一致，历史证据也不证明此刻现场未变化。\n')
    eb.write_new(output / 'review-private.md', text.encode('utf-8'))
    repair.save(output / 'handoff-private.json', handoff)
    return {**result, 'handoffSha256': repair.digest(eb.read_bytes(output / 'handoff-private.json'))}


def capture_plan(config, output, now):
    """只生成原通道请求文件；没有会话、网络或ADB调用。"""
    eb = repair.eb
    output = Path(output).absolute()
    sha = eb.hash_value(config['apk_sha256'])
    version = eb.integer(config['expected_version'])
    repair.need(version > 0, 'EXPECTED_VERSION_REQUIRED')
    device = eb.string(config['device_id'])
    identity = dict(config['identity'])
    repair.need(set(identity) == set(eb.IDENTITY) - {'snapshotId'}, 'CAPTURE_IDENTITY_REQUIRED')
    repair.need(identity['context']['model'] == 'D31' and identity['context']['stage'] == 'RUNNING', 'RUNNING_D31_REQUIRED')
    diagnostic_id = uuid.uuid4().hex + uuid.uuid4().hex
    identity['snapshotId'] = 'readonly-' + uuid.uuid4().hex
    diagnostic = {'operation': 'manifest', 'scope': repair.TARGET, 'identity': identity}
    apk = '/data/local/d31-remote/releases/' + sha + '/remote.apk'
    command = 'CLASSPATH=' + shlex.quote(apk) + ' /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteDiagnosticCommand '
    command += diagnostic_id + ' ' + shlex.quote(json.dumps(diagnostic, separators=(',', ':')))
    firmware = repair.obj(config['firmware'])
    differences = {}
    for key in ('build', 'baselineId', 'baselineRevision', 'firmwareId'):
        if not eb.same(identity[key], firmware[key]):
            differences[key] = {'observation': identity[key], 'firmware': firmware[key]}
    for key in eb.CONTEXT:
        if not eb.same(identity['context'][key], firmware['context'][key]):
            differences['context.' + key] = {'observation': identity['context'][key], 'firmware': firmware['context'][key]}
    output.mkdir(mode=0o700, exist_ok=False)
    repair.save(output / 'diagnostic-request-private.json', diagnostic)
    report_path = '/data/local/d31-remote/diagnostics/' + diagnostic_id + '/report.json'
    steps = []
    specs = [('00-active', 'root_exec', {'command': '/system/bin/cat /data/local/d31-remote/runtime/active.json'}),
             ('01-boot', 'root_exec', {'command': '/system/bin/cat /proc/sys/kernel/random/boot_id'}),
             ('02-diagnostic', 'root_exec', {'command': command}),
             ('03-observation', 'get_file', {'path': report_path, 'allow_cellular': False}),
             ('04-preimage', 'get_file', {'path': repair.TARGET, 'allow_cellular': False}),
             ('05-boot-after', 'root_exec', {'command': '/system/bin/cat /proc/sys/kernel/random/boot_id'}),
             ('06-active-after', 'root_exec', {'command': '/system/bin/cat /data/local/d31-remote/runtime/active.json'})]
    for label, kind, params in specs:
        if kind == 'root_exec':
            params.update(cwd='/', timeout=120 if label == '02-diagnostic' else 30)
        task = {'device_id': device, 'id': 'd31-readonly-' + uuid.uuid4().hex, 'type': kind,
                'params': params, 'expires_at': now + 600000}
        repair.save(output / (label + '-request-private.json'), task)
        steps.append({'request': label + '-request-private.json', 'result': label + '-result-private.json'})
    local = lambda name: str(output / name)
    config_out = {key: str(Path(config[key]).absolute()) for key in ('mapping', 'sources', 'package')}
    config_out.update(device_id=device, task_id='review-' + uuid.uuid4().hex, fixture=False,
                      inputs={'request': local('diagnostic-request-private.json'), 'receipt': local('receipt-private.json'),
                              'observation': local('observation-private.json'), 'firmware': str(Path(config['firmware']).absolute()),
                              'firmware_generation': str(Path(config['firmware_generation']).absolute()),
                              'parent_request': local('02-diagnostic-request-private.json'), 'parent_result': local('02-diagnostic-result-private.json')},
                      preimage={'file': local('preimage-private.sh'), 'request': local('04-preimage-request-private.json'),
                                'result': local('04-preimage-result-private.json')},
                      boot={'request': local('01-boot-request-private.json'), 'result': local('01-boot-result-private.json')},
                      context_review={'purpose': repair.CONTEXT_POLICY, 'observation_sha256': None,
                                      'firmware_sha256': repair.digest(eb.read_bytes(config['firmware'])), 'acceptedDifferences': None})
    repair.save(output / 'consumer-config-template-private.json', config_out)
    repair.save(output / 'context-review-proposal-private.json', {'approved': False, 'differences': differences})
    repair.save(output / 'capture-plan-private.json', {'executed': False, 'expectedVersion': version,
                'expectedApkSha256': sha, 'expectedApkPath': apk, 'diagnosticId': diagnostic_id,
                'snapshotId': identity['snapshotId'], 'steps': steps,
                'deviceChanges': '仅原诊断存档和既有get_file取回暂存；不改目标或配置',
                'requiredGates': ['核对00活动版本/摘要/路径及主线已验收APK', '串行等待各父任务成功，root_exec内层exit_code=0且未截断',
                                  '按get_file原回执下载原字节并核对SHA及bytes', '01与05的boot相同，采集期间活动版本不变',
                                  '明确审批上下文提案后另存消费配置，不自动审批'],
                'repairSubmitted': False, 'systemConsistency': 'NOT_ASSESSED'})
    return {'planned': True, 'executed': False, 'requests': len(steps)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('config', 'output'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--capture-plan', action='store_true', help='仅生成只读请求计划，不发送')
    parser.add_argument('--java')
    parser.add_argument('--classpath')
    args = parser.parse_args()
    try:
        repair.dependencies(Path(__file__).resolve().parent)
        now = time.time_ns() // 1000000
        if args.capture_plan:
            result = capture_plan(repair.obj(args.config), args.output, now)
        else:
            repair.need(args.java and args.classpath, 'JAVA_AND_CLASSPATH_REQUIRED')
            result = prepare_review(repair.obj(args.config), args.output, repair.Java(args.java, args.classpath), now)
        print(json.dumps(result))
    except Exception as error:
        print('离线消费未通过；原件保留。错误类型：' + type(error).__name__, file=sys.stderr)
        raise SystemExit(2) from None


if __name__ == '__main__':
    main()
