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
    names = {'PRESENCE': '存在证据', 'FILE_CONTENT': '文件内容', 'METADATA': '元数据',
             'CONFIGURATION_SEMANTICS': '配置语义', 'ACTIVATION': '实际生效来源', 'PERSONAL_DATA': '已脱敏个人数据'}
    for key, value in report['categories'].items():
        lines.append(f"| {names[key]} | {value['total']} | {value['SAME']} | {value['DIFFERENT']} | {value['UNKNOWN']} |")
    lines += ['', '## 缺口与边界', '',
              '- 原始值、采集来源字符串及身份未复制；路径和字段名仍可能敏感，报告仅作私有证据。',
              '- 脱敏、不适用、未检查、读取失败和不稳定均不算相同；原因计数可能重叠。',
              '- 未应用基准审批、最大快照年龄规则及允许差异规则，不能据此执行自动修复。',
              '- 完整文件树、分区聚合和运行验证未完成；未出现语义字段时不表示配置一致。',
              '- JSON的entries包含全部已知路径及逐字段缺口；引用按inputs的原件摘要和manifestPointer追溯。', '',
              '## 上下文与时效', '',
              f"采集时效：`{report['observation']['freshness']}`；固件时效：`{report['firmware']['freshness']}`。", '',
              '绑定差异字段：' + '、'.join(k for k, v in report['bindingEqual'].items() if not v), '',
              '条件差异字段：' + '、'.join(k for k, v in report['contextEqual'].items() if not v), '']
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
