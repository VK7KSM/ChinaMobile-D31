"""复用已有假PM接口，执行1.4.4真实初始化类及干净模板。"""
import ast
from pathlib import Path
import shutil
import subprocess
import tempfile

STAGE = Path(__file__).resolve().parent
ROOT = STAGE.parents[3]
JAVA = ROOT / '.tools/jdk17/jdk-17.0.20+8/bin'

tree = ast.parse((ROOT / 'research/d31/factory_package/test_factory_init.py').read_text(encoding='utf-8'))
fixtures = next(ast.literal_eval(node.value) for node in tree.body if isinstance(node, ast.Assign)
                and any(isinstance(n, ast.Name) and n.id == 'FIXTURES' for n in node.targets))
fixtures['HandoverRuntime.java'] = fixtures['HandoverRuntime.java'].replace(
    'if(a[0].endsWith("am") && a[1].equals("force-stop")){writes++;return "";}',
    'if(a[0].endsWith("am") && a[1].equals("force-stop"))throw new AssertionError("初始化禁止强停APP");')
fixtures['FactoryInitTest.java'] = '''import java.io.*;
import java.nio.file.*;
import android.content.pm.IPackageManager;
public class FactoryInitTest {
 static int count;
 static void check(boolean v){count++;if(!v)throw new AssertionError("检查"+count);}
 public static void main(String[] args)throws Exception {
  FactoryInit.ROOT=new File(args[0]);
  IPackageManager pm=IPackageManager.INSTANCE;
  File required=new File(FactoryInit.ROOT,"factory-init-required"),done=new File(FactoryInit.ROOT,"factory-init-complete");
  required.createNewFile();FactoryInit init=new FactoryInit();
  init.run(false,false);check(pm.writes==0 && HandoverRuntime.writes==0);
  pm.missing="net.elfradio.d31system";
  try{init.run(true,false);throw new AssertionError();}catch(IOException expected){}
  check(pm.writes==0 && !done.exists() && required.exists());pm.missing="";
  pm.failPermission="android.permission.RECORD_AUDIO";
  try{init.run(true,false);throw new AssertionError();}catch(java.lang.reflect.InvocationTargetException expected){}
  check(!done.exists() && required.exists());pm.failPermission="";
  init.run(true,false);check(done.isFile() && !required.exists());
  check(new String(Files.readAllBytes(done.toPath()),"UTF-8").equals("1.4.4\\n"));
  check(pm.states.get("net.elfradio.d31bootstrap")==0);
  for(String name:new String[]{"BootReceiver","VendorNetworkReceiver","UsbBrowseActivity","UsbInsertPromptActivity"})
   check(pm.components.get("net.elfradio.d31bootstrap."+name)==2);
  check(pm.components.get("net.elfradio.d31bootstrap.RemoteManualReceiver")==1);
  for(String name:new String[]{"CAMERA","RECORD_AUDIO","READ_PHONE_STATE","ACCESS_COARSE_LOCATION","ACCESS_FINE_LOCATION"})
   check(pm.granted.contains("net.elfradio.d31bootstrap:android.permission."+name));
  check(pm.components.get("com.starnet.getnumber.GetNumberService")==2);
  check(HandoverRuntime.values.get("com.starnet.getnumber:SEND_SMS").equals("ignore"));
  check(HandoverRuntime.values.get("secure:sms_default_application").equals("net.elfradio.d31phone.debug"));
  String key="secure:enabled_notification_listeners", listeners=HandoverRuntime.values.get(key);
  check(listeners.contains("net.elfradio.d31system/net.elfradio.d31system.MessageNotificationListener"));
  check(listeners.contains("net.elfradio.d31zelloguard/net.elfradio.d31zelloguard.GuardNotificationListener"));
  int writes=pm.writes+HandoverRuntime.writes;init.run(false,true);
  check(writes==pm.writes+HandoverRuntime.writes);check(HandoverRuntime.whitelist.size()==2);
  HandoverRuntime.values.put(key,"net.elfradio.d31zelloguard/net.elfradio.d31zelloguard.GuardNotificationListener");
  try{init.run(false,true);throw new AssertionError();}catch(IOException expected){}
  HandoverRuntime.values.put(key,listeners);
  HandoverRuntime.whitelist.remove("com.loudtalks");
  try{init.run(false,true);throw new AssertionError();}catch(IOException expected){}
  HandoverRuntime.whitelist.add("com.loudtalks");
  pm.components.put("androidx.work.impl.background.systemalarm.RescheduleReceiver",1);init.run(false,true);
  pm.components.put("com.starnet.getnumber.GetNumberService",1);
  try{init.run(false,true);throw new AssertionError();}catch(IOException expected){}
  required.createNewFile();init.run(true,false);init.run(false,true);
  check(HandoverRuntime.values.get(key).equals(listeners));
  System.out.println("1.4.4真实初始化回归通过，显式断言="+count+"，另含缺包、权限失败、监听和白名单拒绝及重试");
 }
}'''
with tempfile.TemporaryDirectory(prefix='d31-v144-init-') as temporary:
    target = Path(temporary)
    for name, content in fixtures.items():
        path = target / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
    shutil.copy2(STAGE / 'handover/FactoryInit.java', target / 'FactoryInit.java')
    for name in ('init-package-restrictions.xml', 'init-runtime-permissions.xml'):
        shutil.copy2(STAGE / 'templates' / name, target / name)
    subprocess.run([str(JAVA / 'javac.exe'), '--release', '8', '-encoding', 'UTF-8', '-d', str(target)]
                   + [str(path) for path in target.rglob('*.java')], check=True)
    subprocess.run([str(JAVA / 'java.exe'), '-cp', str(target), 'FactoryInitTest', str(target)], check=True)
