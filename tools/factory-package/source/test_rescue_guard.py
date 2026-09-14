"""用真实守护入口验证独立标记的生命周期；隔离安卓接口，不监听网络。"""
from pathlib import Path
import datetime, hashlib, json, shutil, subprocess, time

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'research/d31/analysis'/('rescue-guard-test-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
OUT.mkdir()
JAVA=Path('C:/Users/x/.jdks/jdk-17.0.20.1+1/bin')
BASE='net/elfradio/d31bootstrap/'
fixtures={
 'android/system/Os.java':'package android.system; public class Os {public static int getuid(){return 0;} public static void kill(int p,int s){throw new AssertionError();}}',
 'android/system/OsConstants.java':'package android.system; public class OsConstants {public static int WNOHANG=1;public static boolean WIFEXITED(int s){throw new AssertionError();}public static int WEXITSTATUS(int s){throw new AssertionError();}public static int WTERMSIG(int s){throw new AssertionError();}}',
 'android/annotation/SuppressLint.java':'package android.annotation; public @interface SuppressLint {String value();}',
 'android/os/SystemClock.java':'package android.os; public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}',
 'org/json/JSONObject.java':'package org.json; public class JSONObject {public static Object NULL=null; public JSONObject put(String k,Object v){return this;}}',
 BASE+'BuildConfig.java':'package net.elfradio.d31bootstrap; public class BuildConfig {public static String VERSION_NAME="guard-lifecycle-test";}',
 BASE+'RescueJobs.java':'''package net.elfradio.d31bootstrap;
import java.io.*; import org.json.*;
class RescueJobs {interface Runner {JSONObject run(File f,String c,int t)throws Exception;}
RescueJobs(File root,Runner runner)throws Exception {if(!root.mkdirs())throw new IOException();}}''',
 BASE+'RescueHttpServer.java':'''package net.elfradio.d31bootstrap;
import java.util.function.Supplier;
class RescueHttpServer {
RescueHttpServer(int p,RescueJobs j,Supplier<String> s){if(p!=8765)throw new AssertionError();System.out.println(s.get());}
void start(int t,boolean d){System.out.println("SERVER_STARTED");System.out.flush();}
void stop(){System.out.println("SERVER_STOPPED");System.out.flush();}}
'''
}
for name,body in fixtures.items():
    path=OUT/'src'/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body,encoding='utf-8')
for name in ('RescueDaemon.java','RescueFiles.java'):
    shutil.copy2(ROOT/'research/d31_adb_bootstrap/app/src/main/java'/BASE/name,OUT/'src'/BASE/name)
classes=OUT/'classes';classes.mkdir()
compiled=subprocess.run([str(JAVA/'javac.exe'),'--release','8','-encoding','UTF-8','-d',str(classes)]+[str(p) for p in (OUT/'src').rglob('*.java')],capture_output=True)
(OUT/'compile.stdout').write_bytes(compiled.stdout);(OUT/'compile.stderr').write_bytes(compiled.stderr)
assert compiled.returncode==0,compiled.stderr.decode(errors='replace')
command=[str(JAVA/'java.exe'),'-cp',str(classes),'net.elfradio.d31bootstrap.RescueDaemon']
results=[]
for mode in ('missing','removed','generation','lock'):
    root=OUT/mode;root.mkdir();guard=root/'enabled'
    if mode!='missing':guard.write_text('enabled\n')
    output=(OUT/(mode+'.log')).open('wb')
    process=subprocess.Popen(command+[str(root),str(guard)],stdout=output,stderr=subprocess.STDOUT)
    try:
        if mode=='missing':
            assert process.wait(timeout=5)==0
        else:
            end=time.monotonic()+5
            while time.monotonic()<end and 'SERVER_STARTED' not in (OUT/(mode+'.log')).read_text(encoding='utf-8',errors='replace'):time.sleep(.05)
            assert 'SERVER_STARTED' in (OUT/(mode+'.log')).read_text(encoding='utf-8',errors='replace')
            assert process.poll() is None
            if mode=='lock':
                second=subprocess.run(command+[str(root),str(guard)],capture_output=True,timeout=5)
                (OUT/'lock-second.log').write_bytes(second.stdout+second.stderr)
                assert second.returncode==0 and b'SERVER_STARTED' not in second.stdout
            if mode=='generation':guard.write_text('changed\n')
            else:guard.unlink()
            assert process.wait(timeout=5)==0
            assert 'SERVER_STOPPED' in (OUT/(mode+'.log')).read_text(encoding='utf-8',errors='replace')
        results.append({'测试':mode,'结果':'通过'})
    finally:
        if process.poll() is None:process.kill();process.wait(timeout=5)
        output.close()
result={'源码SHA256':hashlib.sha256((OUT/'src'/BASE/'RescueDaemon.java').read_bytes()).hexdigest(),'测试':results,'边界':'执行真实main和文件锁循环；网络与安卓接口隔离，不代替设备服务验证'}
(OUT/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print('独立标记生命周期4项通过',OUT)
