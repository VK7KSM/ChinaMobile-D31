import java.io.*;
import java.lang.reflect.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import java.util.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** 仅在安装器写入待初始化标记时应用无账户的初始权限和组件开关。 */
public final class FactoryInit {
    static File ROOT = new File("/data/local/d31-startup-handover");
    final Object pm;
    final Class<?> api;
    final Class<?> component;
    FactoryInit() throws Exception {
        if ((Integer)Class.forName("android.system.Os").getMethod("getuid").invoke(null) != 0)
            throw new SecurityException("Root required");
        pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
        if (pm == null) throw new IOException("PackageManager not ready");
        api = Class.forName("android.content.pm.IPackageManager");
        component = Class.forName("android.content.ComponentName");
        api.getMethod("grantRuntimePermission",String.class,String.class,int.class);
        api.getMethod("setComponentEnabledSetting",component,int.class,int.class,int.class);
        api.getMethod("setPackageStoppedState",String.class,boolean.class,int.class);
    }
    static Document document(String name) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new File(ROOT, name));
    }
    static String setting(String group, String name) throws Exception {
        return HandoverRuntime.command("/system/bin/settings", "get", group, name);
    }
    static void putSetting(String group, String name, String value) throws Exception {
        HandoverRuntime.command("/system/bin/settings", "put", group, name, value);
    }
    static String addService(String list, String service) {
        Set<String> items=new LinkedHashSet<>();
        if(!list.isEmpty() && !list.equals("null")) items.addAll(Arrays.asList(list.split(":")));
        items.add(service);
        StringBuilder result=new StringBuilder();
        for(String item:items) { if(result.length()>0) result.append(':'); result.append(item); }
        return result.toString();
    }
    static void userDefaults(boolean apply) throws Exception {
        String guard="net.elfradio.d31zelloguard/net.elfradio.d31zelloguard.GuardAccessibilityService";
        String notifications="net.elfradio.d31zelloguard/net.elfradio.d31zelloguard.GuardNotificationListener";
        String sms="net.elfradio.d31phone.debug";
        if(apply) {
            putSetting("secure","enabled_accessibility_services",addService(setting("secure","enabled_accessibility_services"),guard));
            putSetting("secure","accessibility_enabled","1");
            putSetting("secure","enabled_notification_listeners",addService(setting("secure","enabled_notification_listeners"),notifications));
            putSetting("secure","sms_default_application",sms);
            HandoverRuntime.command("/system/bin/appops","set",sms,"WRITE_SMS","allow");
            putSetting("secure","show_ime_with_hard_keyboard","1");
        }
        if(!Arrays.asList(setting("secure","enabled_accessibility_services").split(":")).contains(guard)
                || !setting("secure","accessibility_enabled").equals("1")) throw new IOException("Shortcut settings mismatch");
        if(!Arrays.asList(setting("secure","enabled_notification_listeners").split(":")).contains(notifications)) throw new IOException("Notification listener mismatch");
        if(!setting("secure","sms_default_application").equals(sms)
                || !HandoverRuntime.command("/system/bin/appops","get",sms,"WRITE_SMS").contains("allow")) throw new IOException("Default SMS mismatch");
        if(!setting("secure","show_ime_with_hard_keyboard").equals("1")) throw new IOException("Keyboard setting mismatch");
        for(String pkg:new String[]{"com.loudtalks","net.elfradio.d31zelloguard"}) {
            if(apply) HandoverRuntime.command("/system/bin/dumpsys","deviceidle","whitelist","+"+pkg);
            boolean present=false;
            for(String line:HandoverRuntime.command("/system/bin/dumpsys","deviceidle","whitelist").split("\n")) {
                String[] fields=line.trim().split(",");
                if(fields.length==3 && fields[0].equals("user") && fields[1].equals(pkg)) present=true;
            }
            if(!present) throw new IOException("Battery whitelist mismatch " + pkg);
        }
    }
    static void disableTcpAcceleration(Document doc) throws Exception {
        if(!doc.getDocumentElement().getTagName().equals("map")) throw new IOException("Invalid preference map");
        NodeList nodes=doc.getDocumentElement().getChildNodes();
        Element target=null;
        for(int i=0;i<nodes.getLength();i++) if(nodes.item(i) instanceof Element) {
            Element e=(Element)nodes.item(i);
            if(e.getAttribute("name").equals("TcpAcclerate")) {
                if(target!=null || !e.getTagName().equals("boolean")) throw new IOException("Invalid TCP setting");
                target=e;
            }
        }
        if(target==null) { target=doc.createElement("boolean"); target.setAttribute("name","TcpAcclerate"); doc.getDocumentElement().appendChild(target); }
        target.setAttribute("value","false");
    }
    // 在交接事务已经停止Nexui后初始化，避免修改正在使用的SharedPreferences。
    static void nexuiDefaultsIfRequired() throws Exception {
        if(!new File(ROOT,"factory-init-complete").isFile() || new File(ROOT,"factory-runtime-complete").isFile()) return;
        File directory=new File("/data/data/com.starnet.nexui/shared_prefs");
        String owner=HandoverRuntime.command("/system/bin/stat","-c","%u:%g","/data/data/com.starnet.nexui");
        if(!owner.matches("[0-9]+:[0-9]+")) throw new IOException("Invalid application owner");
        if(!directory.isDirectory() && !directory.mkdir()) throw new IOException("Cannot create preference directory");
        HandoverRuntime.command("/system/bin/chown",owner,directory.getPath());
        HandoverRuntime.command("/system/bin/chmod","0771",directory.getPath());
        File prefs=new File(directory,"starNetBaseConfigFile.xml");
        Document doc;
        if(prefs.isFile()) {
            HandoverRuntime.command("/system/bin/cp","-p",prefs.getPath(),new File(ROOT,"nexui-before-init.xml").getPath());
            doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(prefs);
        } else { doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument(); doc.appendChild(doc.createElement("map")); }
        disableTcpAcceleration(doc);
        File temp=new File(directory,"starNetBaseConfigFile.xml.d31-new");
        try(FileOutputStream out=new FileOutputStream(temp)) {
            TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc),new StreamResult(out));
            out.getFD().sync();
        }
        HandoverRuntime.command("/system/bin/chown",owner,temp.getPath());
        HandoverRuntime.command("/system/bin/chmod","0660",temp.getPath());
        if(!temp.renameTo(prefs)) throw new IOException("Cannot commit TCP setting");
        HandoverRuntime.command("/system/bin/restorecon",prefs.getPath());
        try(FileOutputStream out=new FileOutputStream(new File(ROOT,"factory-runtime-complete"))) { out.write("1.4.1\n".getBytes("UTF-8")); out.getFD().sync(); }
        System.out.println("FACTORY_TCP_ACCELERATION_DISABLED");
    }
    Object call(String name, Class<?>[] types, Object... args) throws Exception {
        return api.getMethod(name, types).invoke(pm, args);
    }
    void requirePackage(String name) throws Exception {
        Object info = call("getApplicationInfo", new Class<?>[]{String.class,int.class,int.class}, name, 0, 0);
        if (info == null) throw new IOException("Missing package " + name);
    }
    void run(boolean apply, boolean verify) throws Exception {
        NodeList packages = document("init-package-restrictions.xml").getElementsByTagName("pkg");
        NodeList permissions = document("init-runtime-permissions.xml").getElementsByTagName("pkg");
        for (NodeList nodes : new NodeList[]{packages,permissions})
            for (int i=0;i<nodes.getLength();i++) requirePackage(((Element)nodes.item(i)).getAttribute("name"));
        for (int i=0;i<packages.getLength();i++) {
            Element item=(Element)packages.item(i);
            String pkg=item.getAttribute("name");
            int desired=Integer.parseInt(item.getAttribute("enabled"));
            if (apply) call("setApplicationEnabledSetting", new Class<?>[]{String.class,int.class,int.class,int.class,String.class}, pkg,desired,1,0,"com.android.shell");
            int state=(Integer)call("getApplicationEnabledSetting",new Class<?>[]{String.class,int.class},pkg,0);
            if ((apply || verify) && state!=desired) throw new IOException("Package state mismatch " + pkg);
            if(apply && desired==0) call("setPackageStoppedState",new Class<?>[]{String.class,boolean.class,int.class},pkg,false,0);
            for (String tag : new String[]{"disabled-components","enabled-components"}) {
                NodeList groups=item.getElementsByTagName(tag);
                if (groups.getLength()==0) continue;
                NodeList entries=((Element)groups.item(0)).getElementsByTagName("item");
                for(int j=0;j<entries.getLength();j++) {
                    String name=((Element)entries.item(j)).getAttribute("name");
                    Object target=component.getConstructor(String.class,String.class).newInstance(pkg,name);
                    int wanted=tag.equals("disabled-components")?2:1;
                    if(apply) call("setComponentEnabledSetting",new Class<?>[]{component,int.class,int.class,int.class},target,wanted,1,0);
                    int value=(Integer)call("getComponentEnabledSetting",new Class<?>[]{component,int.class},target,0);
                    boolean scheduled=verify && name.equals("androidx.work.impl.background.systemalarm.RescheduleReceiver");
                    if((apply || verify) && !scheduled && value!=wanted) throw new IOException("Component state mismatch " + name);
                }
            }
        }
        for(int i=0;i<permissions.getLength();i++) {
            Element item=(Element)permissions.item(i);
            String pkg=item.getAttribute("name");
            NodeList entries=item.getElementsByTagName("item");
            for(int j=0;j<entries.getLength();j++) {
                Element permission=(Element)entries.item(j);
                if(!permission.getAttribute("granted").equals("true")) continue;
                String name=permission.getAttribute("name");
                if(apply) call("grantRuntimePermission",new Class<?>[]{String.class,String.class,int.class},pkg,name,0);
                int granted=(Integer)call("checkPermission",new Class<?>[]{String.class,String.class,int.class},name,pkg,0);
                if((apply || verify) && granted!=0) throw new IOException("Permission mismatch " + pkg + " " + name);
            }
        }
        if(apply || verify) {
            userDefaults(apply);
            if(apply) HandoverRuntime.command("/system/bin/appops","set","com.starnet.getnumber","SEND_SMS","ignore");
            String ops=HandoverRuntime.command("/system/bin/appops","get","com.starnet.getnumber","SEND_SMS");
            if(!ops.contains("ignore")) throw new IOException("SMS deny verification failed");
            if(apply) HandoverRuntime.command("/system/bin/settings","put","global","heads_up_notifications_enabled","0");
            if(!HandoverRuntime.command("/system/bin/settings","get","global","heads_up_notifications_enabled").equals("0"))
                throw new IOException("Heads up setting mismatch");
        }
        if(apply) {
            // 引导APK已完成职责，独立root探针由自己的启动入口运行。
            HandoverRuntime.command("/system/bin/am","force-stop","--user","0","net.elfradio.d31bootstrap");
            File done=new File(ROOT,"factory-init-complete");
            try(FileOutputStream out=new FileOutputStream(done)) { out.write("1.4.1\n".getBytes("UTF-8")); out.getFD().sync(); }
            if(!new File(ROOT,"factory-init-required").delete()) throw new IOException("Init marker removal failed");
        }
        System.out.println(apply?"FACTORY_INIT_VERIFIED":verify?"FACTORY_STATE_VERIFIED":"FACTORY_INIT_INSPECT_ONLY");
    }
    public static void main(String[] args) {
        try {
            if(args.length<1 || args.length>2 || !(args[0].equals("--apply") || args[0].equals("--inspect") || args[0].equals("--verify"))) throw new IllegalArgumentException();
            boolean apply=args[0].equals("--apply");
            if(args.length==2) {
                if(apply || !args[1].startsWith("/data/local/tmp/d31-")) throw new IllegalArgumentException();
                ROOT=new File(args[1]);
            }
            if(apply && !new File(ROOT,"factory-init-required").isFile()) throw new IOException("No factory init request");
            new FactoryInit().run(apply,args[0].equals("--verify"));
            System.exit(0);
        } catch(Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}
