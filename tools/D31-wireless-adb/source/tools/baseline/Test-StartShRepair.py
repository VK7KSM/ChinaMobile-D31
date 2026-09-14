"""从当前权威只读冻结小范围Java和证据依赖，全部测试输出进入新时间戳目录。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

sys.dont_write_bytecode = True
BASELINE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--project', required=True)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--json-jar', required=True)
    parser.add_argument('--firmware-zip', required=True)
    parser.add_argument('--output', required=True)
    a = parser.parse_args()
    project, output = Path(a.project).resolve(), Path(a.output).resolve()
    output.mkdir(exist_ok=False)
    frozen, classes, deps = output / 'frozen', output / 'classes', output / 'dependencies'
    for directory in (frozen, classes, deps):
        directory.mkdir()
    source = project / 'app/src/main/java/net/elfradio/d31bootstrap'
    files = [source / 'diagnostics' / (n + '.java') for n in ('DiagnosticContract', 'DiagnosticManifest', 'DiagnosticCoverageComparison')]
    files += [source / 'repair' / (n + '.java') for n in ('RepairPlan', 'RepairFiles', 'RepairPlatform', 'RepairJournal', 'RepairTransactions')]
    files += [project / 'app/src/test/java/net/elfradio/d31bootstrap/repair/TemporaryRepairPlatform.java',
              BASELINE / 'StartShRepairMain.java', BASELINE / 'RepairLoopFixture.java']
    records = []
    def copy(file, dest):
        raw = file.read_bytes()
        with dest.open('xb') as f:
            f.write(raw)
        records.append({'source': str(file), 'destination': str(dest.relative_to(output)), 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()})
    for file in files:
        copy(file, frozen / file.name)
    for name in ('evidence_bundle.py', 'compare_baseline.py'):
        copy(project / 'tools/baseline' / name, deps / name)
    for name in ('start_sh_repair.py', 'test_start_sh_repair.py', 'Test-StartShRepair.py'):
        copy(BASELINE / name, frozen / name)
    copy(Path(a.json_jar).resolve(), frozen / 'json.jar')
    calls = []
    def run(name, args, env=None):
        with (output / (name + '.stdout.txt')).open('xb') as out, (output / (name + '.stderr.txt')).open('xb') as err:
            start = time.time_ns() // 1000000
            result = subprocess.run(list(map(str, args)), stdout=out, stderr=err, env=env, timeout=180)
        calls.append({'name': name, 'command': list(map(str, args)), 'startMs': start, 'exit': result.returncode})
        return result.returncode
    suffix = '.exe' if os.name == 'nt' else ''
    java = Path(a.java_home).resolve() / 'bin' / ('java' + suffix)
    javac = java.with_name('javac' + suffix)
    compiled = run('compile', [javac, '--release', '8', '-encoding', 'UTF-8', '-cp', frozen / 'json.jar', '-d', classes, *[frozen / f.name for f in files]])
    test_exit = None
    if compiled == 0:
        env = dict(os.environ, REPAIR_TEST_OUTPUT=str(output), REPAIR_JAVA=str(java),
                   REPAIR_CLASSPATH=str(classes) + os.pathsep + str(frozen / 'json.jar'),
                   REPAIR_FIRMWARE_ZIP=str(Path(a.firmware_zip).resolve()))
        test_exit = run('tests', [sys.executable, '-B', BASELINE / 'test_start_sh_repair.py'], env)
    unchanged = all(hashlib.sha256(Path(r['source']).read_bytes()).hexdigest() == r['sha256'] for r in records)
    result = {'通过': compiled == 0 and test_exit == 0 and unchanged, '源码未变': unchanged,
              '设备与网络': '未访问', '依赖': records, '调用': calls}
    with (output / 'result.json').open('x', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(json.dumps({'passed': result['通过'], 'output': str(output)}))
    raise SystemExit(0 if result['通过'] else 1)


if __name__ == '__main__':
    main()
