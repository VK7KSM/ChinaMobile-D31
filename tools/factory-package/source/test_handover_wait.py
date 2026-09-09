"""在隔离宿主环境模拟十五分钟以上首次启动，不等待真实十五分钟。"""
from pathlib import Path
import subprocess,tempfile,hashlib,json
ROOT=Path(__file__).resolve().parents[3]
SCRIPT=ROOT/'research/d31/analysis/2026-09-08-startup-handover/start.sh'
OUT=ROOT/'research/d31/analysis/first-boot-audit-20260909-010711'
bash='C:/Program Files/Git/bin/bash.exe'
def unix(path):
    return '/'+str(path.drive)[0].lower()+path.as_posix()[2:]
results=[]
for disabled in (False,True):
    with tempfile.TemporaryDirectory(prefix='d31-wait-test-') as tmp:
        p=Path(tmp); (p/'state/runs').mkdir(parents=True)
        if disabled: (p/'state/disabled').touch()
        (p/'bin').mkdir()
        (p/'bin/getprop').write_text('#!/bin/sh\nf='+unix(p/'count')+'\nn=$(cat "$f" 2>/dev/null || echo 0)\nn=$((n+1)); echo "$n" > "$f"\n[ "$n" -gt 190 ] && echo 1 || echo 0\n',newline='\n')
        (p/'bin/sleep').write_text('#!/bin/sh\nexit 0\n',newline='\n')
        (p/'bin/busybox').write_text('#!/bin/sh\necho "$*" >> '+unix(p/'calls')+'\nexit 0\n',newline='\n')
        source=SCRIPT.read_text().replace('root=/data/local/d31-startup-handover','root='+unix(p/'state'))
        source=source.replace('boot=$(cat /proc/sys/kernel/random/boot_id)','boot=01234567-89ab-cdef-0123-456789abcdef')
        source=source.replace('/system/bin/busybox',unix(p/'bin/busybox'))
        source=source.replace('set -u','set -u\nexport PATH='+unix(p/'bin')+':$PATH')
        (p/'start.sh').write_text(source,newline='\n')
        result=subprocess.run([bash,unix(p/'start.sh')],capture_output=True,timeout=30)
        log=(p/'state/runs/01234567-89ab-cdef-0123-456789abcdef/result.txt').read_text()
        calls=(p/'calls').read_text() if (p/'calls').exists() else ''
        assert result.returncode==0, result.stderr
        if disabled: assert not calls and 'HANDOVER_DISABLED_NO_PATCH' in log
        else:
            assert 'WAITING_ANDROID_BOOT_SECONDS=900' in log
            assert calls.count('HandoverRuntime --apply')==1
            assert 'HANDOVER_EXIT=0' in log
        results.append({'停用':disabled,'日志':log,'调用':calls})
(OUT/'slow-first-boot-test.json').write_text(json.dumps({'原脚本SHA256':hashlib.sha256(SCRIPT.read_bytes()).hexdigest(),'测试':results},ensure_ascii=False,indent=2),encoding='utf-8')
print('慢首次启动与显式停用两项测试通过')
