"""用现有Java清单合同生成本地覆盖对照；不访问设备、网络或执行安装器。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

MAX_BYTES = 8 * 1024 * 1024


def strict_json(data):
    if len(data) > MAX_BYTES:
        raise ValueError('输入超过8MiB')
    text = data.decode('utf-8-sig')
    depth, quoted, escaped = 0, False, False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in '[{':
            depth += 1
            if depth > 14:  # 既有manifest外层报告最多增加两层。
                raise ValueError('输入嵌套超过限制')
        elif char in ']}':
            depth -= 1

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('JSON重复成员')
            result[key] = value
        return result

    def invalid_number(_):
        raise ValueError('JSON不允许非有限值或小数')

    result = json.loads(text, object_pairs_hook=unique, parse_constant=invalid_number, parse_float=invalid_number)
    if not isinstance(result, dict):
        raise ValueError('输入必须是JSON对象')
    return result


def read_input(path):
    with Path(path).open('rb') as stream:
        before = os.fstat(stream.fileno())
        data = stream.read(MAX_BYTES + 1)
        after = os.fstat(stream.fileno())
    if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
        raise ValueError('输入读取期间变化')
    obj = strict_json(data)
    pointer = '/manifest' if 'manifest' in obj else ''
    manifest = obj['manifest'] if pointer else obj
    if not isinstance(manifest, dict):
        raise ValueError('manifest必须是对象')
    return manifest, {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data), 'manifestPointer': pointer}


def save_new(path, obj):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(obj, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def run_logged(command, output, name):
    started = time.time_ns() // 1000000
    with (output / (name + '.stdout.txt')).open('xb') as stdout, (output / (name + '.stderr.txt')).open('xb') as stderr:
        try:
            result = subprocess.run(command, stdout=stdout, stderr=stderr, timeout=120, check=False)
        except subprocess.TimeoutExpired:
            save_new(output / (name + '.invocation-private.json'), {'command': command, 'startedAtMs': started,
                     'timedOut': True, 'returnCode': None})
            raise
    save_new(output / (name + '.invocation-private.json'), {'command': command, 'startedAtMs': started,
             'timedOut': False, 'returnCode': result.returncode})
    if result.returncode:
        raise RuntimeError('本地编译或清单校验拒绝；参见私有原始输出')


def render(report):
    counts = report['counts']
    lines = ['# D31本地系统基准覆盖对照', '',
             '这是两份既有清单的历史证据对照，不是三方诊断、开发板冻结或刷机验收。', '',
             f"已知路径{counts['knownPaths']}；交集{counts['intersectionPaths']}；采集单边{counts['observationOnlyPaths']}；固件单边{counts['firmwareOnlyPaths']}。单边不自动等于缺失。", '',
             '| 类别 | 字段对 | 原始值相同 | 原始值不同 | 证据不足 |', '| --- | --- | --- | --- | --- |']
    names = {'PRESENCE': '存在证据', 'FILE_CONTENT': '文件内容', 'METADATA': '元数据', 'INVENTORY': '目录枚举',
             'CONFIGURATION_SEMANTICS': '配置语义', 'ACTIVATION': '实际生效来源', 'PERSONAL_DATA': '已脱敏个人数据'}
    for key, value in report['categories'].items():
        lines.append(f"| {names[key]} | {value['total']} | {value['SAME']} | {value['DIFFERENT']} | {value['UNKNOWN']} |")
    lines += ['', '## 缺口与边界', '',
              '- 配置语义：' + ('未取得可计入配置语义类别的字段证据。'
                              if 'CONFIGURATION_SEMANTICS_NOT_COLLECTED' in report['gaps']
                              else '仅统计已提供字段，未证明配置完整或修复完成。'),
              '- 原始值、采集来源字符串及身份未复制；路径和字段名仍可能敏感，报告仅作私有证据。',
              '- 脱敏、不适用、未检查、读取失败和不稳定均不算相同；原因计数可能重叠。',
              '- 未应用基准审批、最大快照年龄规则及允许差异规则，不能据此执行自动修复。',
              '- 完整文件树、分区聚合和运行验证未完成；未出现语义字段时不表示配置一致。',
              '- semantic.enumeration仅表示目录枚举证据，不计入配置语义覆盖；配置语义字段对数量不代表完整配置已采集。',
              '- JSON的entries包含全部已知路径及逐字段缺口；引用按inputs的原件摘要和manifestPointer追溯。', '',
              '## 上下文与时效', '',
              f"采集时效：`{report['observation']['freshness']}`；固件时效：`{report['firmware']['freshness']}`。", '',
              '绑定差异字段：' + '、'.join(k for k, v in report['bindingEqual'].items() if not v), '',
              '条件差异字段：' + '、'.join(k for k, v in report['contextEqual'].items() if not v), '']
    configuration = report.get('configurationCoverage')
    if configuration is not None:
        lines += ['', '## 有限配置必检目录', '']
        if configuration.get('catalogId') != 'd31-finite-configuration' or configuration.get('catalogVersion') not in (1, 2, 3):
            lines += ['当前工具不支持此必检目录版本；不能据此认定配置覆盖完整。']
        else:
            labels = {'system_support.root': '系统支持ROOT', 'system_support.disabled': '系统支持禁用条件',
                      'recovery.enabled': 'Recovery启用条件', 'startup.cellular_enabled': '蜂窝启动配置',
                      'rescue.enabled': '救援启用条件', 'desktop.config_tab': '桌面布局配置',
                      'initialization.components': '初始化组件状态', 'initialization.permissions': '初始化权限结果',
                      'initialization.completion': '初始化完成条件'}
            states = {'OBSERVED': '已有原始证据', 'NOT_CHECKED': '未检查', 'READ_FAILED': '读取失败',
                      'UNSTABLE': '不稳定', 'REDACTED': '已脱敏', 'NOT_APPLICABLE': '不适用声明未核验'}
            reasons = {'CONFIGURATION_FIELD_CONTRACT_NOT_DEFINED': '字段合同尚未定义',
                       'OBSERVATION_EVIDENCE_INSUFFICIENT': '采集侧证据不足',
                       'FIRMWARE_EVIDENCE_INSUFFICIENT': '固件侧证据不足',
                       'RAW_VALUES_ONLY_NO_ALLOWED_DIFFERENCE_RULES': '仅比较原始值，未应用允许差异规则',
                       'OUTSIDE_SCOPE': '不在采集范围', 'PATH_NOT_LISTED': '路径未列入清单',
                       'PRESENCE_NOT_CONFIRMED': '存在状态未确认', 'FIELD_NOT_COLLECTED': '未采集字段',
                       'CONFIGURATION_VALUE_TYPE_UNSUPPORTED': '配置值类型不符合已定义合同',
                       'APPLICABILITY_RULE_NOT_VERIFIED': '适用性规则未核验'}
            lines += [f"目录版本{configuration['catalogVersion']}；必检{configuration['requiredItems']}项，双方有证据{configuration['bothObservedItems']}项，"
                      f"缺口{configuration['gapItems']}项。仅为九项有限目录，未核验适用性，不代表配置全部覆盖。", '',
                      '| 项目 | 采集侧 | 固件侧 | 原始关系 | 不足或边界 |', '| --- | --- | --- | --- | --- |']
            for item in configuration['items']:
                detail = [reasons.get(code, '未知不足理由') for code in item['reasons']]
                for side, title in (('observation', '采集侧'), ('firmware', '固件侧')):
                    for code in item[side]['reasons']:
                        if code in reasons and code != 'CONFIGURATION_FIELD_CONTRACT_NOT_DEFINED':
                            detail.append(title + '：' + reasons[code])
                relation = {'SAME': '原始值相同', 'DIFFERENT': '原始值不同', 'UNKNOWN': '未知'}.get(item['pair'], '未知')
                left = states.get(item['observation']['state'], '未知状态')
                right = states.get(item['firmware']['state'], '未知状态')
                lines.append(f"| {labels.get(item['id'], '未知项目')} | {left} | {right} | {relation} | {'；'.join(detail)} |")
    else:
        lines += ['', '## 有限配置必检目录', '', '旧报告未提供版本化必检目录，不能据此认定九项配置已覆盖。']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--observation', required=True)
    parser.add_argument('--firmware', required=True)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--json-jar', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--now-ms', type=int)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    # 全新目录同时隔离编译输出和规范化私有输入，不触碰原始捕获。
    output.mkdir(parents=False, exist_ok=False)
    try:
        observation, obs_ref = read_input(args.observation)
        firmware, fw_ref = read_input(args.firmware)
        save_new(output / 'observation-private.json', observation)
        save_new(output / 'firmware-private.json', firmware)
        here = Path(__file__).resolve().parent
        source = here.parent.parent / 'app/src/main/java/net/elfradio/d31bootstrap/diagnostics'
        sources = [source / (name + '.java') for name in
                   ('DiagnosticContract', 'DiagnosticManifest', 'DiagnosticCoverageComparison')]
        sources.append(here / 'CoverageMain.java')
        frozen = output / 'sources'
        frozen.mkdir()
        frozen_sources = []
        for source_file in sources:
            target = frozen / source_file.name
            with target.open('xb') as stream:
                stream.write(source_file.read_bytes())
            frozen_sources.append(target)
        classes = output / 'classes'
        classes.mkdir()
        suffix = '.exe' if os.name == 'nt' else ''
        java_bin = Path(args.java_home) / 'bin'
        jar = str(Path(args.json_jar).resolve())
        save_new(output / 'inputs.json', {'observation': obs_ref, 'firmware': fw_ref,
                 'sources': [{'name': p.name, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in frozen_sources],
                 'runnerSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                 'jsonJarSha256': hashlib.sha256(Path(jar).read_bytes()).hexdigest()})
        run_logged([str(java_bin / ('javac' + suffix)), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                    '-cp', jar, '-d', str(classes)] + [str(p) for p in frozen_sources], output, 'compile')
        now = args.now_ms if args.now_ms is not None else time.time_ns() // 1000000
        run_logged([str(java_bin / ('java' + suffix)), '-Xmx512m', '-cp', str(classes) + os.pathsep + jar,
                    'CoverageMain', str(output / 'observation-private.json'), str(output / 'firmware-private.json'),
                    str(output / 'coverage-private.json'), str(now)], output, 'compare')
        report = json.loads((output / 'coverage-private.json').read_text(encoding='utf-8'))
        report['inputs'] = {'observation': obs_ref, 'firmware': fw_ref}
        save_new(output / 'report-private.json', report)
        with (output / 'report-private.md').open('x', encoding='utf-8') as stream:
            stream.write(render(report))
        save_new(output / 'result.json', {'generated': True, 'systemConsistency': 'NOT_ASSESSED', 'counts': report['counts']})
        print(json.dumps(report['counts']))
    except Exception:
        save_new(output / 'rejection.json', {'generated': False, 'offlineComparison': 'NOT_COMPARED',
                 'systemConsistency': 'NOT_ASSESSED', 'reason': '输入、编译或比较失败，原始证据保留；不得视为比较通过'})
        raise


if __name__ == '__main__':
    main()
