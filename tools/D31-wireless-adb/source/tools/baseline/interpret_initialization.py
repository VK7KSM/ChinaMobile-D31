"""复用原清单验证和比较器，生成初始化完成条件的离线事实旁注。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import time

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
BASELINE = HERE
spec = importlib.util.spec_from_file_location('original_baseline', BASELINE / 'compare_baseline.py')
original = importlib.util.module_from_spec(spec)
spec.loader.exec_module(original)


def prepare(output, java_home, json_jar):
    """在指定私有输出内冻结实际使用的源码及编译产物。"""
    source = BASELINE.parent.parent / 'app/src/main/java/net/elfradio/d31bootstrap/diagnostics'
    paths = [source / (name + '.java') for name in
             ('DiagnosticContract', 'DiagnosticManifest', 'DiagnosticCoverageComparison')]
    paths.append(HERE / 'CompletionMain.java')
    frozen = output / 'sources'
    frozen.mkdir()
    refs = []
    for path in paths:
        data = path.read_bytes()
        with (frozen / path.name).open('xb') as stream:
            stream.write(data)
        refs.append({'name': path.name, 'sha256': hashlib.sha256(data).hexdigest()})
    classes = output / 'classes'
    classes.mkdir()
    suffix = '.exe' if os.name == 'nt' else ''
    original.run_logged([str(Path(java_home) / 'bin' / ('javac' + suffix)), '-encoding', 'UTF-8',
                         '-source', '8', '-target', '8', '-cp', str(json_jar), '-d', str(classes)]
                        + [str(frozen / p.name) for p in paths], output, 'compile')
    return [str(Path(java_home) / 'bin' / ('java' + suffix)), '-Xmx512m', '-cp',
            str(classes) + os.pathsep + str(json_jar),
            'net.elfradio.d31bootstrap.diagnostics.CompletionMain'], refs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--observation', action='append', required=True)
    parser.add_argument('--firmware', required=True)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--json-jar', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--now-ms', type=int)
    args = parser.parse_args()
    if not 1 <= len(args.observation) <= 5:
        parser.error('只接受一至五份原manifest')
    requested_output = Path(args.output)
    if requested_output.is_symlink():
        parser.error('输出路径已存在，禁止覆盖')
    output = requested_output.resolve()
    # 只创建明确指定的新目录；不覆盖目录或文件，也不自动创建父目录。
    try:
        output.mkdir(mode=0o700, exist_ok=False)
    except OSError:
        parser.error('输出目录必须全新且父目录已存在，禁止覆盖')
    try:
        observations, refs = [], []
        for path in args.observation:
            manifest, ref = original.read_input(path)
            observations.append(manifest)
            refs.append(ref)
        firmware, fw_ref = original.read_input(args.firmware)
        original.save_new(output / 'inputs-private.json', {'observations': observations, 'firmware': firmware})
        jar = Path(args.json_jar).resolve()
        command, sources = prepare(output, args.java_home, jar)
        original.save_new(output / 'provenance.json', {'observations': refs, 'firmware': fw_ref,
                          'sources': sources, 'runnerSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                          'baselineRunnerSha256': hashlib.sha256((BASELINE / 'compare_baseline.py').read_bytes()).hexdigest(),
                          'jsonJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest()})
        now = args.now_ms if args.now_ms is not None else time.time_ns() // 1000000
        original.run_logged(command + [str(output / 'inputs-private.json'), str(output / 'result-private.json'),
                                       str(now)], output, 'interpret')
        result = json.loads((output / 'result-private.json').read_text(encoding='utf-8'))
        for i, report in enumerate(result['coverageReports']):
            original.save_new(output / f'coverage-{i}-private.json', report)
            with (output / f'coverage-{i}-private.md').open('x', encoding='utf-8') as stream:
                stream.write(original.render(report))
        completion = result['completion']
        completion['inputs'] = {'observations': refs, 'firmware': fw_ref}
        original.save_new(output / 'completion.json', completion)
        original.save_new(output / 'result.json', {'generated': True, 'consumerBinding': completion['consumerBinding'],
                          'interpretation': completion['semantics']['status'], 'repairPlanGenerated': False})
        print('已生成离线事实旁注；未核验运行状态，未生成修复。')
    except Exception:
        original.save_new(output / 'rejection.json', {'generated': False, 'repairPlanGenerated': False,
                          'reason': '输入、编译或解释失败；原始证据保留，不视为通过。'})
        # 原始输入可能带私人值；控制台不输出异常正文。
        print('离线解释拒绝；详见指定私有目录内拒绝记录。', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
