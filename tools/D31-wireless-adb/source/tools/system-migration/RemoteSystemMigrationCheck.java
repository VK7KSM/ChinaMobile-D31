package net.elfradio.d31bootstrap;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.*;
import android.system.*;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** 迁移专用独立DEX；复用候选中的维护锁、验包和安装适配器，不编入正式APK。 */
public final class RemoteSystemMigrationCheck {
    private static final String PKG = RemoteUpdatePolicy.PACKAGE;
    private static final File BASE = RemoteUpdatePlatform.BASELINE;
    private static final File RUNTIME = RemoteUpdatePlatform.RUNTIME;
    private static final String HOOK = "/system/bin/install-recovery.sh";
    private static final String START = "/system/bin/d31-elfremote-start";
    private static final String MARKER = "/system/etc/d31-elfremote.system";
    private static final String[] CONTROLS = {"state/stop", "updates/stop-supervisor", "updates/enabled"};
    private final RemoteUpdatePlatform platform = new RemoteUpdatePlatform();
    private final PackageManager pm;
    private RemoteSystemMigrationCheck() throws Exception {
        platform.current();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("currentActivityThread").invoke(null);
        pm = ((Context)at.getMethod("getSystemContext").invoke(thread)).getPackageManager();
    }
    private static void require(boolean ok, String reason) throws IOException { if (!ok) throw new IOException(reason); }
    private static File safe(File file) throws Exception {
        File absolute = file.getAbsoluteFile();
        for (File p=absolute; p!=null; p=p.getParentFile()) {
            try { require(!OsConstants.S_ISLNK(Os.lstat(p.getPath()).st_mode), "路径含链接"); }
            catch (ErrnoException absent) { if (absent.errno!=OsConstants.ENOENT) throw absent; }
        }
        require(absolute.equals(absolute.getCanonicalFile()), "路径不规范");
        return absolute;
    }
    private static JSONObject read(File file) throws Exception { return RemoteUpdateFiles.read(safe(file)); }
    private static String hash(File file) throws Exception { safe(file); return RescueFiles.sha256(file); }
    private static void save(File file, JSONObject value) throws Exception { safe(file); RescueFiles.write(file, value.toString()); }
    private static void copy(File from, File to) throws Exception {
        safe(from); safe(to); require(!to.exists(), "不能覆盖备份");
        RemoteUpdateFiles.copy(from,to);
        StructStat s=Os.stat(from.getPath()); Os.chmod(to.getPath(),s.st_mode & 07777); Os.chown(to.getPath(),s.st_uid,s.st_gid);
    }
    private static boolean isKind(File apk, String kind) throws Exception {
        String name="assets/remote-"+kind+".marker"; int count=0;
        try(ZipFile zip=new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> all=zip.entries();
            while(all.hasMoreElements()) {
                String n=all.nextElement().getName();
                if(n.equalsIgnoreCase("assets/remote-basic.marker") || n.equalsIgnoreCase("assets/remote-full.marker")) {
                    if(!n.equals(name))return false;
                    count++;
                }
            }
            if(count!=1)return false;
            try(InputStream in=zip.getInputStream(zip.getEntry(name))) {
                for(byte b:("d31-"+kind+"-v1\n").getBytes(StandardCharsets.UTF_8))if(in.read()!=(b&255))return false;
                return in.read()==-1;
            }
        }
    }
    private JSONObject settings() throws Exception {
        JSONObject result=new JSONObject().put("application",pm.getApplicationEnabledSetting(PKG));
        JSONObject components=new JSONObject();
        PackageInfo p=pm.getPackageInfo(PKG,PackageManager.GET_ACTIVITIES|PackageManager.GET_RECEIVERS
                |PackageManager.GET_SERVICES|PackageManager.GET_PROVIDERS|PackageManager.GET_DISABLED_COMPONENTS);
        for(ComponentInfo[] group:new ComponentInfo[][]{p.activities,p.receivers,p.services,p.providers}) {
            if(group!=null)for(ComponentInfo c:group) components.put(c.name,pm.getComponentEnabledSetting(new ComponentName(PKG,c.name)));
        }
        ApplicationInfo info=pm.getApplicationInfo(PKG,0);
        return result.put("components",components).put("system",(info.flags&ApplicationInfo.FLAG_SYSTEM)!=0)
                .put("updatedSystem",(info.flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0)
                .put("privileged",(info.getClass().getField("privateFlags").getInt(info)&8)!=0);
    }
    private static void attrs(String path,int mode,boolean directory) throws Exception {
        safe(new File(path)); StructStat s=Os.lstat(path);
        require((directory?OsConstants.S_ISDIR(s.st_mode):OsConstants.S_ISREG(s.st_mode))
                &&(s.st_mode&07777)==mode&&s.st_uid==0&&s.st_gid==0,"系统文件类型、权限或属主不符");
        String label=label(path);
        require(label.equals("u:object_r:system_file:s0"),"系统文件标签不符");
    }
    private static String label(String path) throws Exception {
        Object value=Class.forName("android.os.SELinux").getMethod("getFileContext",String.class).invoke(null,path);
        require(value instanceof String&&!((String)value).isEmpty(),"SELinux标签读取失败");return (String)value;
    }
    private static JSONObject fileAttributes(File file) throws Exception {
        safe(file);StructStat s=Os.lstat(file.getPath());require(OsConstants.S_ISREG(s.st_mode),"钩子必须为普通非链接文件");
        return new JSONObject().put("mode",s.st_mode&07777).put("uid",s.st_uid).put("gid",s.st_gid).put("label",label(file.getPath()));
    }
    private static void checkAttributes(File file,JSONObject expected) throws Exception {
        JSONObject actual=fileAttributes(file);
        for(String field:new String[]{"mode","uid","gid","label"})require(actual.get(field).equals(expected.get(field)),"钩子属性回读不符："+field);
    }
    private static void setHookAttributes(JSONObject s) throws Exception {
        File f=safe(new File(HOOK));JSONObject a=s.getJSONObject("hookAttributes");
        Os.chown(f.getPath(),a.getInt("uid"),a.getInt("gid"));Os.chmod(f.getPath(),a.getInt("mode"));
        Object ok=Class.forName("android.os.SELinux").getMethod("setFileContext",String.class,String.class).invoke(null,f.getPath(),a.getString("label"));
        require(Boolean.TRUE.equals(ok),"钩子标签设置失败");checkAttributes(f,a);
    }
    private void prepare(String route,File stage,String expected,String anchor) throws Exception {
        require(route.equals("install")||route.equals("replace"),"路线错误");
        RemoteMaintenance.requireUnreserved();
        require(!RemoteUpdatePlatform.cloudUpdateBusy()&&!new File(RUNTIME,"updates/manual/pending.json").exists(),"已有更新未结束");
        JSONObject candidate=platform.inspect(safe(new File(stage,"remote.apk"))), old=platform.current(), state=settings();
        require(candidate.getString("sha256").equals(expected)&&candidate.getInt("versionCode")>=96
                &&candidate.getInt("versionCode")>old.getInt("versionCode")&&isKind(new File(stage,"remote.apk"),"full"),"候选必须同包同证书、完整标记且递增至至少96");
        require(candidate.getLong("size")>0&&candidate.getLong("size")<=RemoteUpdatePolicy.MAX_BYTES,"候选大小超限");
        require(!pm.getApplicationInfo(PKG,0).sourceDir.startsWith("/mnt/"),"不支持外置安装副本");
        File backup=new File(stage,"backup"); safe(backup); require(!backup.exists(),"已有备份禁止重放安装");
        JSONObject oldFiles=new JSONObject(),newFiles=new JSONObject().put(BASE.getPath(),expected);
        JSONObject active=null;
        if(RemoteUpdatePlatform.ACTIVE.exists()) {
            active=platform.activeArchive();
            require(candidate.getInt("versionCode")>active.getInt("versionCode"),"候选不得低于或等于活动核心");
        }
        if(route.equals("install")) {
            require(!state.getBoolean("system")&&!state.getBoolean("updatedSystem")&&!state.getBoolean("privileged")
                    &&isKind(new File(old.getString("path")),"basic")&&active==null,"首次部署仅接受普通基础及无活动核心");
            for(String p:new String[]{BASE.getParent(),START,MARKER}) {safe(new File(p));require(!new File(p).exists(),"系统托管目标已经存在");}
            fileAttributes(new File(HOOK)); require(hash(new File(HOOK)).equals(anchor),"原钩子变化");
            oldFiles.put(HOOK,anchor);
            newFiles.put(HOOK,hash(new File(stage,"install-recovery.sh"))).put(START,hash(new File(stage,"start.sh")))
                    .put(MARKER,hash(new File(stage,"marker")));
            require(RescueFiles.read(new File(stage,"marker"),32).equals("1\n"),"托管标记不符");
            require(RescueFiles.read(new File(stage,"start.sh"),16384).contains("net.elfradio.d31bootstrap.RemoteSupervisor"),"暂存入口不是既有监督入口");
        } else {
            JSONObject baseline=platform.inspect(safe(BASE));
            require(baseline.getInt("versionCode")<RemoteManualBootstrap.MIN_SUPERVISOR,
                    "兼容基线不得看齐版本替换；普通升级用手动接替，必要功能升级用显式maintenance");
            require(baseline.getString("sha256").equals(anchor),"系统原件变化");
            attrs(BASE.getPath(),0644,false); oldFiles.put(BASE.getPath(),anchor);
            attrs(START,0755,false); attrs(MARKER,0644,false);
            // 固定入口必须已经是监督入口，拒绝把旧直接核心脚本误当监督部署。
            require(RescueFiles.read(new File(START),16384).contains("net.elfradio.d31bootstrap.RemoteSupervisor"),"需先审查旧入口迁移");
        }
        JSONObject controls=new JSONObject();
        for(String p:CONTROLS) {
            File f=safe(new File(RUNTIME,p));
            controls.put(p,f.exists()?RescueFiles.read(f,4096):JSONObject.NULL);
        }
        require(backup.mkdir(),"备份目录创建失败");
        copy(new File(old.getString("path")),new File(backup,"original.apk"));
        if(route.equals("install"))copy(new File(HOOK),new File(backup,"install-recovery.sh"));
        else copy(BASE,new File(backup,"system.apk"));
        if(active!=null) {
            copy(new File(active.getString("path")),new File(backup,"active.apk"));
            copy(RemoteUpdatePlatform.ACTIVE,new File(backup,"active.json"));
        }
        JSONObject s=new JSONObject().put("route",route).put("old",old).put("candidate",candidate).put("settings",state)
                .put("controls",controls).put("oldFiles",oldFiles).put("newFiles",newFiles).put("hadActive",active!=null)
                .put("hookAttributes",fileAttributes(new File(HOOK)));
        save(new File(backup,"state.json"),s); RescueFiles.write(new File(backup,"route"),route+"\n");
        RescueFiles.write(new File(backup,"ready"),"1\n");
        System.out.println("BACKUP_AND_APK_IDENTITY_VERIFIED");
    }
    private static boolean locked(File path) throws Exception {
        safe(path); if(!path.exists())return false;
        try(RandomAccessFile f=new RandomAccessFile(path,"rw");FileLock lock=RemoteFileLocks.tryExclusive(f.getChannel())) {return lock==null;}
    }
    private static boolean coreProcess() throws Exception {
        File[] entries=new File("/proc").listFiles(); require(entries!=null,"进程目录不可读");
        for(File p:entries)if(p.getName().matches("[0-9]+")) {
            try {String cmd=RescueFiles.read(new File(p,"cmdline"),4096);
                if(cmd.startsWith("d31-elfremote\u0000")||cmd.startsWith("d31-remote-supervisor\u0000"))return true;
            }catch(FileNotFoundException exited) { }
        }
        return false;
    }
    private static void quiesce() throws Exception {
        for(String p:new String[]{"state/stop","updates/stop-supervisor"}) {
            File f=safe(new File(RUNTIME,p)); if(!f.getParentFile().isDirectory())require(f.getParentFile().mkdirs(),"停止目录不可用");
            if(!f.exists())RescueFiles.write(f,"system-migration\n");
        }
        long deadline=android.os.SystemClock.elapsedRealtime()+60000;
        do {
            if(!locked(new File(RUNTIME,"updates/supervisor.lock"))&&!locked(new File(RUNTIME,"state/remote.lock"))&&!coreProcess())return;
            Thread.sleep(200);
        }while(android.os.SystemClock.elapsedRealtime()<deadline);
        throw new IOException("监督或核心未退出，保留原件和停止标记");
    }
    private void guard(JSONObject s) throws Exception {
        String now=platform.current().getString("sha256");
        require(now.equals(s.getJSONObject("old").getString("sha256"))||now.equals(s.getJSONObject("candidate").getString("sha256")),"发现第三方安装，停止恢复避免覆盖");
        if(RemoteUpdatePlatform.ACTIVE.exists()) {
            JSONObject active=read(RemoteUpdatePlatform.ACTIVE);
            String value=active.getString("sha256");
            String original=s.getBoolean("hadActive")?read(new File(new File(s.getJSONObject("candidate").getString("path")).getParentFile(),"backup/active.json")).getString("sha256"):"";
            require(value.equals(original)||value.equals(s.getJSONObject("candidate").getString("sha256")),"活动指针已由第三方改变");
        }
    }
    private static void guardFiles(JSONObject s) throws Exception {
        JSONObject old=s.getJSONObject("oldFiles"),next=s.getJSONObject("newFiles");
        File backup=new File(new File(s.getJSONObject("candidate").getString("path")).getParentFile(),"backup");
        if(s.getString("route").equals("install"))require(hash(new File(backup,"install-recovery.sh")).equals(old.getString(HOOK)),"钩子备份被改变");
        else require(hash(new File(backup,"system.apk")).equals(old.getString(BASE.getPath())),"系统APK备份被改变");
        Iterator<String> keys=next.keys();
        while(keys.hasNext()) {
            String p=keys.next();File f=safe(new File(p));
            if(!f.exists())continue;
            String actual=hash(f);
            require(actual.equals(next.getString(p))||actual.equals(old.optString(p)),"系统文件已被其他写入改变，保留原像待处理");
        }
        for(String p:new String[]{BASE.getPath()+".new",HOOK+".elfremote-new"})safe(new File(p));
    }
    private void restoreSettings(JSONObject s) throws Exception {
        JSONObject old=s.getJSONObject("settings"),components=old.getJSONObject("components");
        pm.setApplicationEnabledSetting(PKG,old.getInt("application"),PackageManager.DONT_KILL_APP);
        Iterator<String> keys=components.keys();
        while(keys.hasNext()){String name=keys.next();pm.setComponentEnabledSetting(new ComponentName(PKG,name),components.getInt(name),PackageManager.DONT_KILL_APP);}
        JSONObject now=settings();require(now.getInt("application")==old.getInt("application"),"包启用状态未恢复");
        keys=components.keys();while(keys.hasNext()){String k=keys.next();require(now.getJSONObject("components").getInt(k)==components.getInt(k),"组件启用状态未恢复");}
    }
    private static void restoreControls(JSONObject s) throws Exception {
        JSONObject old=s.getJSONObject("controls");
        for(String p:CONTROLS) {
            File f=safe(new File(RUNTIME,p));
            if(!old.isNull(p)){RescueFiles.write(f,old.getString(p));continue;}
            if(f.exists()) {
                require(RescueFiles.read(f,4096).equals("system-migration\n"),"停止标记已由其他操作改变");
                require(f.delete(),"停止标记清理失败");
            }
        }
    }
    private void installed(JSONObject s) throws Exception {
        require(RemoteUpdatePolicy.matches(platform.current(),s.getJSONObject("candidate")),"PM回执后实际安装原件不符");
        JSONObject files=s.getJSONObject("newFiles");Iterator<String> keys=files.keys();
        while(keys.hasNext()){String p=keys.next();require(hash(new File(p)).equals(files.getString(p)),"系统写入回读不符");}
    }
    private void system(JSONObject s) throws Exception {
        installed(s);JSONObject now=settings();
        require(now.getBoolean("system")&&now.getBoolean("privileged"),"系统扫描/特权身份尚未确认，禁止宣称接替完成");
        for(String permission:new String[]{"READ_LOGS","DUMP","WRITE_SECURE_SETTINGS","INSTALL_PACKAGES","DELETE_PACKAGES","REBOOT"})
            require(pm.checkPermission("android.permission."+permission,PKG)==PackageManager.PERMISSION_GRANTED,"所需系统权限未授予："+permission);
        attrs(BASE.getParent(),0755,true);attrs(BASE.getPath(),0644,false);attrs(START,0755,false);attrs(MARKER,0644,false);
        checkAttributes(new File(HOOK),s.getJSONObject("hookAttributes"));
        require(s.getJSONObject("controls").isNull("state/stop")&&s.getJSONObject("controls").isNull("updates/stop-supervisor"),"原状态要求停止，未授权自动恢复运行");
    }
    private JSONObject loaded(JSONObject s) throws Exception {
        JSONObject active=platform.activeArchive(),target=s.getJSONObject("candidate");
        require(RemoteUpdatePolicy.matches(active,target),"活动原件未接替");
        File apk=safe(new File(active.getString("path")));
        String pid=RescueFiles.read(new File(RUNTIME,"state/remote.pid"),64).trim();require(pid.matches("[1-9][0-9]*"),"核心PID无效");
        File proc=new File("/proc/"+pid);String before=RescueFiles.read(new File(proc,"stat"),4096);
        require(Os.stat(proc.getPath()).st_uid==0,"核心非root");
        require(RescueFiles.read(new File(proc,"cmdline"),4096).startsWith("d31-elfremote\u0000"),"PID非目标核心");
        String maps=RescueFiles.read(new File(proc,"maps"),2*1024*1024),encoded=apk.getPath().substring(1).replace('/','@')+"@classes.dex";
        JSONArray matched=new JSONArray();
        for(String line:maps.split("\n")) {
            String[] columns=line.trim().split("\\s+",6);if(columns.length!=6)continue;
            String file=columns[5];
            if(file.equals(apk.getPath())||(file.startsWith("/data/dalvik-cache/")&&file.endsWith("/"+encoded)))matched.put(line);
        }
        require(matched.length()>0,"缺少核心原件对应的独立进程映射");
        JSONObject h=read(new File(RUNTIME,"state/health.json"));long age=System.currentTimeMillis()-h.getLong("time_ms");
        require(age>=0&&age<20000&&h.optInt("uid",-1)==0&&h.optBoolean("local_ready")
                &&h.optInt("version_code",-1)==target.getInt("versionCode")&&target.getString("sha256").equals(h.optString("apk_sha256")),"本地健康证据不新鲜或不匹配");
        // stat的CPU计数可变化，只比较右括号之后的starttime字段。
        String after=RescueFiles.read(new File(proc,"stat"),4096);
        require(before.substring(before.lastIndexOf(')')+2).split(" ")[19].equals(after.substring(after.lastIndexOf(')')+2).split(" ")[19]),"核心PID被复用");
        require(hash(apk).equals(target.getString("sha256")),"映射核验期间原件改变");
        return new JSONObject().put("installed",platform.current()).put("systemArchive",platform.inspect(BASE)).put("active",active)
                .put("pid",pid).put("mappingEvidence",matched).put("localHealth",true).put("rebootVerified",false).put("cloudVerified",false);
    }
    // 独立只读入口：不依赖迁移state，不获取会创建锁文件的维护锁。
    private void smoke() throws Exception {
        JSONObject report=new JSONObject().put("readOnly",true).put("sdk",android.os.Build.VERSION.SDK_INT)
                .put("snapshotAtomic",false).put("migrationVerified",false);
        JSONArray errors=new JSONArray();JSONObject files=new JSONObject();
        for(String path:new String[]{HOOK,START,MARKER}) {
            try { files.put(path,fileAttributes(new File(path))); }
            catch(Exception error){errors.put(path+": "+error.toString());}
        }
        report.put("files",files);
        try {
            // 只解析setter签名；本命令绝不调用setter。
            Class.forName("android.os.SELinux").getMethod("setFileContext",String.class,String.class);
            report.put("labelSetterResolved",true).put("labelSetterExecuted",false);
        }catch(Exception error){errors.put("SELinux: "+error.toString());}
        try {report.put("installed",platform.current()).put("installedIdentity",settings());}
        catch(Exception error){errors.put("PackageManager: "+error.toString());}
        try {
            JSONObject permissions=new JSONObject();
            for(String p:new String[]{"READ_LOGS","DUMP","WRITE_SECURE_SETTINGS","INSTALL_PACKAGES","DELETE_PACKAGES","REBOOT"})
                permissions.put(p,pm.checkPermission("android.permission."+p,PKG)==PackageManager.PERMISSION_GRANTED);
            report.put("permissions",permissions);
        }catch(Exception error){errors.put("permissions: "+error.toString());}
        try {report.put("currentCore",loaded(new JSONObject().put("candidate",platform.activeArchive())));}
        catch(Exception error){errors.put("currentCore: "+error.toString());}
        report.put("errors",errors).put("passed",errors.length()==0);
        System.out.println(report);
        require(errors.length()==0,"只读冒烟检查存在缺证据，参见JSON；不代表迁移失败或成功");
    }
    private void command(String[] a) throws Exception {
        if(a[0].equals("prepare")){require(a.length==5,"参数错误");prepare(a[1],safe(new File(a[2])),a[3],a[4]);return;}
        require(a.length==2,"参数错误");File stage=safe(new File(a[1])),backup=new File(stage,"backup");JSONObject s=read(new File(backup,"state.json"));
        switch(a[0]) {
            case "guard-rollback":guard(s);break;
            case "guard-files":guardFiles(s);break;
            case "restore-file-metadata":setHookAttributes(s);break;
            case "quiesce":quiesce();break;
            case "install":guard(s);platform.install(platform.preserve(new File(stage,"remote.apk")),false);installed(s);break;
            case "verify-installed":installed(s);System.out.println(platform.current());break;
            case "restore-install":
                guard(s);require(hash(new File(backup,"original.apk")).equals(s.getJSONObject("old").getString("sha256")),"原APK备份已改变");
                if(!RemoteUpdatePolicy.matches(platform.current(),s.getJSONObject("old")))platform.install(platform.preserve(new File(backup,"original.apk")),true);
                if(s.getBoolean("hadActive")) {
                    JSONObject prior=read(new File(backup,"active.json"));
                    JSONObject preserved=platform.preserve(new File(backup,"active.apk"));require(RemoteUpdatePolicy.matches(prior,preserved),"原活动备份不符");
                    require(RemoteUpdatePolicy.matches(prior,platform.inspect(safe(new File(prior.getString("path"))))),"原活动路径已改变，保留备份待处理");
                    RescueFiles.write(RemoteUpdatePlatform.ACTIVE,RescueFiles.read(new File(backup,"active.json"),64000));
                }else if(RemoteUpdatePlatform.ACTIVE.exists()) {
                    require(RemoteUpdatePolicy.matches(read(RemoteUpdatePlatform.ACTIVE),s.getJSONObject("candidate")),"活动指针已由其他操作改变");
                    require(RemoteUpdatePlatform.ACTIVE.delete(),"活动指针清理失败");
                }
                break;
            case "restore-state":restoreSettings(s);break;
            case "restore-controls":restoreControls(s);break;
            case "enable-supervision":
                system(s);File enabled=safe(new File(RUNTIME,"updates/enabled"));
                if(!enabled.exists())RescueFiles.write(enabled,"system-migration\n");
                break;
            case "verify-restored":
                require(RemoteUpdatePolicy.matches(platform.current(),s.getJSONObject("old")),"原安装未恢复");
                for(String key:new String[]{"system","updatedSystem","privileged"})require(settings().getBoolean(key)==s.getJSONObject("settings").getBoolean(key),"PM身份尚未恢复，可能需要重新扫描");
                JSONObject originals=s.getJSONObject("oldFiles");Iterator<String> oldKeys=originals.keys();
                while(oldKeys.hasNext()){String p=oldKeys.next();require(hash(new File(p)).equals(originals.getString(p)),"原系统文件恢复回读不符");}
                checkAttributes(new File(HOOK),s.getJSONObject("hookAttributes"));
                if(s.getString("route").equals("install")) {
                    for(String p:new String[]{BASE.getParent(),START,MARKER}) {
                        safe(new File(p));require(!new File(p).exists(),"首次部署新增系统文件尚未清除");
                    }
                }else attrs(BASE.getPath(),0644,false);
                break;
            case "verify-system":system(s);System.out.println(settings());break;
            case "wait-core":
                long deadline=android.os.SystemClock.elapsedRealtime()+160000;Exception last=null;
                do {try(RemoteMaintenance.Lease lease=RemoteMaintenance.acquire()) {
                    require(lease!=null,"维护忙");RemoteMaintenance.requireUnreserved();system(s);System.out.println(loaded(s));return;
                }catch(Exception pending){last=pending;Thread.sleep(500);}}while(android.os.SystemClock.elapsedRealtime()<deadline);
                throw new IOException("接替未通过，保留现场及原APK等待显式恢复",last);
            default:throw new IllegalArgumentException("未知迁移命令");
        }
    }
    public static void main(String[] args) {
        int code=0;
        try {
            require(args.length>0&&Os.getuid()==0&&android.os.Build.VERSION.SDK_INT==23
                    &&"hct6735_66_m0".equals(android.os.Build.DEVICE)&&"hct6737t_66_m0".equals(android.os.Build.MODEL),"仅限已核对D31及显式命令");
            if(args[0].equals("smoke")) {
                require(args.length==1,"smoke不接受迁移参数");new RemoteSystemMigrationCheck().smoke();
            }else if(args[0].equals("locked-run")) {
                require(args.length==7,"锁入口参数错误");
                try(RemoteMaintenance.Lease lease=RemoteMaintenance.acquire()) {
                    require(lease!=null,"维护忙，未执行迁移");RemoteMaintenance.requireUnreserved();
                    ArrayList<String> command=new ArrayList<>(Arrays.asList("/system/bin/sh",safe(new File(args[1])).getPath(),"--locked"));
                    command.addAll(Arrays.asList(args).subList(2,args.length));
                    ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);
                    builder.environment().put("D31_MIGRATION_LOCK_HELD","1");
                    Process child=builder.start();
                    try(InputStream in=child.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)System.out.write(b,0,n);}
                    code=child.waitFor();
                }
            }else if("1".equals(System.getenv("D31_MIGRATION_LOCK_HELD"))||args[0].equals("wait-core")) {
                new RemoteSystemMigrationCheck().command(args);
            }else try(RemoteMaintenance.Lease lease=RemoteMaintenance.acquire()) {
                require(lease!=null,"维护忙");RemoteMaintenance.requireUnreserved();new RemoteSystemMigrationCheck().command(args);
            }
        }catch(Exception error){error.printStackTrace(System.err);code=1;}
        System.exit(code);
    }
}
