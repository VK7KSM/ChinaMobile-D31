"""使用假的安卓包管理器执行真实初始化类，验证新数据区及失败重试。"""
from pathlib import Path
import subprocess, tempfile, shutil

ROOT=Path(__file__).resolve().parents[3]
JAVA=Path('C:/Users/x/.jdks/jdk-17.0.20.1+1/bin')
HANDOVER=ROOT/'research/d31/analysis/2026-09-08-startup-handover'
SOURCE=ROOT/'research/d31/analysis/2026-09-09-factory-v1.4.1/system_payload'
FIXTURES={
 'android/system/Os.java': 'package android.system; public class Os { public static int getuid(){return 0;} }',
 'android/content/ComponentName.java': '''package android.content;
public class ComponentName { public final String name; public ComponentName(String pkg,String name){this.name=name;} }''',
 'android/app/AppGlobals.java': '''package android.app;
public class AppGlobals { public static Object getPackageManager(){return android.content.pm.IPackageManager.INSTANCE;} }''',
 'android/content/pm/IPackageManager.java': '''package android.content.pm;
import java.util.*;
import android.content.ComponentName;
public class IPackageManager {
 public static final IPackageManager INSTANCE=new IPackageManager();
 public final Map<String,Integer> states=new HashMap<>(),components=new HashMap<>();
 public final Set<String> granted=new HashSet<>();
 public String missing="",failPermission="";
 public int writes;
 public Object getApplicationInfo(String n,int f,int u){return n.equals(missing)?null:new Object();}
 public void setApplicationEnabledSetting(String n,int v,int f,int u,String c){writes++;states.put(n,v);}
 public int getApplicationEnabledSetting(String n,int u){return states.getOrDefault(n,0);}
 public void setPackageStoppedState(String n,boolean v,int u){writes++;}
 public void setComponentEnabledSetting(ComponentName n,int v,int f,int u){writes++;components.put(n.name,v);}
 public int getComponentEnabledSetting(ComponentName n,int u){return components.getOrDefault(n.name,0);}
 public void grantRuntimePermission(String p,String n,int u){if(n.equals(failPermission))throw new RuntimeException("injected");writes++;granted.add(p+":"+n);}
 public int checkPermission(String n,String p,int u){return granted.contains(p+":"+n)?0:-1;}
}''',
 'HandoverRuntime.java': '''import java.util.*;
public class HandoverRuntime {
 static final Map<String,String> values=new HashMap<>(); static int writes;
 static final Set<String> whitelist=new LinkedHashSet<>();
 static String command(String... a) {
  if(a[0].endsWith("dumpsys") && a[1].equals("deviceidle")) {
   if(a.length==4){writes++;whitelist.add(a[3].substring(1));return "";}
   StringBuilder b=new StringBuilder();for(String p:whitelist)b.append("user,").append(p).append(",10001\\n");return b.toString();
  }
  if(a[0].endsWith("settings")) {String k=a[2]+":"+a[3]; if(a[1].equals("put")){writes++;values.put(k,a[4]);return "";} return values.getOrDefault(k,"null");}
  if(a[0].endsWith("appops")) {String k=a[2]+":"+a[3];if(a[1].equals("set")){writes++;values.put(k,a[4]);return "";} return a[3]+": "+values.getOrDefault(k,"default");}
  if(a[0].endsWith("am") && a[1].equals("force-stop")){writes++;return "";}
  throw new AssertionError(Arrays.toString(a));
 }
}''',
 'FactoryInitTest.java': '''import java.io.*;
import java.nio.file.*;
import android.content.pm.IPackageManager;
public class FactoryInitTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  FactoryInit.ROOT=new File(args[0]);
  IPackageManager pm=IPackageManager.INSTANCE;
  File required=new File(FactoryInit.ROOT,"factory-init-required"),done=new File(FactoryInit.ROOT,"factory-init-complete");
  required.createNewFile();
  FactoryInit init=new FactoryInit();
  init.run(false,false);check(pm.writes==0 && HandoverRuntime.writes==0);
  pm.missing="net.elfradio.d31system";
  try{init.run(true,false);throw new AssertionError();}catch(IOException expected){}
  check(pm.writes==0 && !done.exists() && required.exists());pm.missing="";
  pm.failPermission="android.permission.READ_SMS";
  try{init.run(true,false);throw new AssertionError();}catch(java.lang.reflect.InvocationTargetException expected){}
  check(!done.exists() && required.exists());pm.failPermission="";
  init.run(true,false);check(done.isFile() && !required.exists());
  check(pm.states.get("net.elfradio.d31bootstrap")==2);
  check(pm.components.get("com.starnet.getnumber.GetNumberService")==2);
  int writes=pm.writes+HandoverRuntime.writes;
  init.run(false,true);check(writes==pm.writes+HandoverRuntime.writes);
  check(HandoverRuntime.whitelist.size()==2);
  HandoverRuntime.whitelist.remove("com.loudtalks");
  try{init.run(false,true);throw new AssertionError();}catch(IOException expected){}
  HandoverRuntime.whitelist.add("com.loudtalks");
  pm.components.put("androidx.work.impl.background.systemalarm.RescheduleReceiver",1);
  init.run(false,true);
  pm.components.put("com.starnet.getnumber.GetNumberService",1);
  try{init.run(false,true);throw new AssertionError();}catch(IOException expected){}
  required.createNewFile();init.run(true,false);init.run(false,true);
  System.out.println("真实初始化类测试通过：只读、缺包拒绝、部分失败保留标记、重试、状态回读、安全回归拒绝、定时接收器窗口");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='d31-init-test-') as temp:
    target=Path(temp)
    for name,body in FIXTURES.items():
        path=target/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body,encoding='utf-8')
    shutil.copy2(HANDOVER/'FactoryInit.java',target/'FactoryInit.java')
    for name in ('init-package-restrictions.xml','init-runtime-permissions.xml'):shutil.copy2(SOURCE/name,target/name)
    subprocess.run([str(JAVA/'javac.exe'),'--release','8','-encoding','UTF-8','-d',str(target)]+[str(p) for p in target.rglob('*.java')],check=True)
    subprocess.run([str(JAVA/'java.exe'),'-cp',str(target),'FactoryInitTest',str(target)],check=True)
