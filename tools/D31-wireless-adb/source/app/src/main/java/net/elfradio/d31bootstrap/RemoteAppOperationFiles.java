package net.elfradio.d31bootstrap;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** API23原子记录及只读进程观察；记录目录不持第二把锁，调用方必须持全局维护租约。 */
final class RemoteAppOperationFiles implements RemoteAppOperation.Access {
    private static final String PACKAGE="net.elfradio.d31bootstrap";
    private static final File ROOT=new File(RemoteMaintenance.ROOT,"app-operations");
    private final Context context;
    private final String hash;
    RemoteAppOperationFiles(Context context,String hash)throws Exception{
        requireDevice();this.context=context;this.hash=hash;
        String apk=System.getenv("CLASSPATH");
        if(apk==null||!hash.equals(RescueFiles.sha256(new File(apk))))throw new IOException("APP_OPERATION_EXECUTABLE_MISMATCH");
    }
    static void requireDevice()throws Exception{
        if(Os.getuid()!=0||android.os.Build.VERSION.SDK_INT!=23||!"hct6735_66_m0".equals(android.os.Build.DEVICE)
                ||!"hct6737t_66_m0".equals(android.os.Build.MODEL))throw new IOException("APP_OPERATION_DEVICE_INVALID");
        Os.umask(0077);
    }
    public AutoCloseable acquire()throws Exception{
        parents(RemoteMaintenance.ROOT);
        if(stat(RemoteMaintenance.ROOT)==null){Os.mkdir(RemoteMaintenance.ROOT.getPath(),0700);sync(RemoteMaintenance.ROOT.getParentFile());}
        require(RemoteMaintenance.ROOT,true);
        File lock=new File(RemoteMaintenance.ROOT,"lock");if(stat(lock)!=null)require(lock,false);
        return RemoteMaintenance.acquire();
    }
    public String boot()throws Exception{
        String boot=RescueFiles.read(new File("/proc/sys/kernel/random/boot_id"),64).trim();
        if(!boot.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IOException("APP_OPERATION_BOOT_UNKNOWN");
        return boot;
    }
    public void requireStart(String expected)throws Exception{
        RemoteMaintenance.requireUnreserved();RemoteMaintenance.requireRepairReady();
        JSONObject active=new JSONObject(RescueFiles.read(RemoteUpdatePlatform.ACTIVE,4096));
        if(!expected.equals(active.getString("sha256"))||active.getInt("versionCode")!=BuildConfig.VERSION_CODE
                ||!active.getString("path").equals(System.getenv("CLASSPATH")))throw new IOException("APP_OPERATION_ACTIVE_MISMATCH");
    }
    private static StructStat stat(File file)throws Exception{
        try{return Os.lstat(file.getPath());}catch(ErrnoException failure){if(failure.errno==OsConstants.ENOENT)return null;throw failure;}
    }
    private static StructStat require(File file,boolean directory)throws Exception{
        StructStat value=stat(file);
        if(value==null||!safeAttributes(value.st_uid,value.st_mode,value.st_nlink,directory)
                ||!file.getAbsoluteFile().equals(file.getCanonicalFile()))
            throw new IOException("APP_OPERATION_PATH_REJECTED");
        return value;
    }
    static boolean safeAttributes(int uid,int mode,long links,boolean directory){
        return uid==0&&(mode&0022)==0&&(mode&0170000)==(directory?0040000:0100000)&&(directory||links==1);
    }
    private static void parents(File file)throws Exception{
        for(File p=file.getParentFile();p!=null&&!p.equals(new File("/data/local"));p=p.getParentFile())require(p,true);
    }
    private static void same(StructStat first,StructStat next)throws IOException{
        if(first.st_dev!=next.st_dev||first.st_ino!=next.st_ino||first.st_uid!=next.st_uid||first.st_mode!=next.st_mode||first.st_nlink!=next.st_nlink)
            throw new IOException("APP_OPERATION_FILE_CHANGED");
    }
    private static void sync(File folder)throws Exception{
        StructStat before=require(folder,true);
        FileDescriptor fd=Os.open(folder.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW,0);
        try{same(before,Os.fstat(fd));Os.fsync(fd);}finally{Os.close(fd);}
    }
    private static JSONObject readFile(File file)throws Exception{
        parents(file);StructStat before=stat(file);if(before==null)return null;require(file,false);
        FileDescriptor fd=Os.open(file.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0);
        try{
            same(before,Os.fstat(fd));ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
            while((count=Os.read(fd,buffer,0,buffer.length))>0){if(out.size()+count>32768)throw new IOException("APP_OPERATION_RECORD_LIMIT");out.write(buffer,0,count);}
            same(before,require(file,false));return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }finally{Os.close(fd);}
    }
    private static void writeFile(File file,JSONObject value)throws Exception{
        parents(file);if(stat(file)!=null)require(file,false);
        byte[] data=value.toString().getBytes(StandardCharsets.UTF_8);if(data.length>32768)throw new IOException("APP_OPERATION_RECORD_LIMIT");
        File temporary=new File(file.getParentFile(),file.getName()+".pending-"+UUID.randomUUID());
        FileDescriptor fd=Os.open(temporary.getPath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_NOFOLLOW,0600);
        try{int at=0;while(at<data.length){int count=Os.write(fd,data,at,data.length-at);if(count<=0)throw new IOException("APP_OPERATION_WRITE_FAILED");at+=count;}Os.fsync(fd);}
        finally{Os.close(fd);}
        if(stat(file)!=null)require(file,false);
        Os.rename(temporary.getPath(),file.getPath());sync(file.getParentFile());
    }
    public JSONObject read(String id)throws Exception{
        if(stat(ROOT)==null)return null;require(ROOT,true);return readFile(new File(ROOT,id+".json"));
    }
    public void write(String id,JSONObject record)throws Exception{
        parents(ROOT);if(stat(ROOT)==null){Os.mkdir(ROOT.getPath(),0700);sync(ROOT.getParentFile());}
        require(ROOT,true);writeFile(new File(ROOT,id+".json"),record);
    }
    public JSONObject reservation()throws Exception{return readFile(new File(RemoteMaintenance.ROOT,"repair.json"));}
    public void reserve(JSONObject record)throws Exception{
        if(reservation()!=null)throw new IOException("APP_OPERATION_RESERVATION_BUSY");
        writeFile(new File(RemoteMaintenance.ROOT,"repair.json"),RemoteAppOperation.reservationFor(record));
    }
    public void release(JSONObject record)throws Exception{
        if(!RemoteProtocol.sameJson(reservation(),RemoteAppOperation.reservationFor(record)))throw new IOException("APP_OPERATION_RESERVATION_MISMATCH");
        Os.remove(new File(RemoteMaintenance.ROOT,"repair.json").getPath());sync(RemoteMaintenance.ROOT);
    }
    static String startTime(String stat)throws IOException{
        int end=stat.lastIndexOf(')');if(end<0)throw new IOException("APP_OPERATION_PROC_UNKNOWN");
        String[] values=stat.substring(end+1).trim().split("\\s+");
        if(values.length<20||!values[19].matches("[0-9]+"))throw new IOException("APP_OPERATION_PROC_UNKNOWN");
        return values[19];
    }
    private static JSONObject process(int pid)throws Exception{
        if(pid<=0)throw new IOException("APP_OPERATION_PID_INVALID");
        File folder=new File("/proc/"+pid);StructStat entry=Os.lstat(folder.getPath());
        String first=RescueFiles.read(new File(folder,"stat"),4096);
        String cmdline=RescueFiles.read(new File(folder,"cmdline"),2048);
        int terminator=cmdline.indexOf(0);
        if(terminator<1)throw new IOException("APP_OPERATION_PROC_UNKNOWN");
        String name=cmdline.substring(0,terminator);
        String again=RescueFiles.read(new File(folder,"stat"),4096);
        if(!startTime(first).equals(startTime(again)))throw new IOException("APP_OPERATION_PROCESS_CHANGED");
        return new JSONObject().put("pid",pid).put("uid",entry.st_uid).put("start_time",startTime(first)).put("process_name",name);
    }
    public JSONObject owner()throws Exception{return process(android.os.Process.myPid());}
    public JSONObject application(String kind,String expected,int pid,int uid)throws Exception{
        ApplicationInfo app=context.getPackageManager().getApplicationInfo(PACKAGE,0);
        String name=RemoteAppOperation.isContacts(kind)?PACKAGE+":contacts":PACKAGE;
        JSONObject process=process(pid);
        if(uid<10000||uid>=20000||uid!=app.uid||uid!=process.getInt("uid")||!name.equals(process.getString("process_name"))
                ||!expected.equals(hash)||!hash.equals(RescueFiles.sha256(new File(app.sourceDir)))
                ||!RemoteRuntimeInventory.mapsArchive(RescueFiles.read(new File("/proc/"+pid+"/maps"),RemoteRuntimeInventory.MAPS_LIMIT),app.sourceDir)
                ||!RemoteProtocol.sameJson(process,process(pid)))throw new IOException("APP_OPERATION_APP_IDENTITY_UNKNOWN");
        return process;
    }
    public RemoteAppOperation.Presence presence(JSONObject saved){
        try{
            int pid=saved.getInt("pid");if(pid<=0)return RemoteAppOperation.Presence.UNKNOWN;
            // 只有明确ENOENT或同PID的新启动时间才证明原进程结束；权限/解析失败一律未知。
            try{Os.lstat("/proc/"+pid);}catch(ErrnoException error){return error.errno==OsConstants.ENOENT?RemoteAppOperation.Presence.DEAD:RemoteAppOperation.Presence.UNKNOWN;}
            JSONObject current=process(pid);
            return compareProcess(saved,current);
        }catch(Exception failure){return RemoteAppOperation.Presence.UNKNOWN;}
    }
    static RemoteAppOperation.Presence compareProcess(JSONObject saved,JSONObject current){
        try{
            for(JSONObject value:new JSONObject[]{saved,current}){
                if(!(value.opt("pid") instanceof Integer)||value.getInt("pid")<=0
                        ||!(value.opt("uid") instanceof Integer)||value.getInt("uid")<0
                        ||!value.getString("start_time").matches("[1-9][0-9]*")
                        ||value.getString("process_name").isEmpty())return RemoteAppOperation.Presence.UNKNOWN;
            }
            if(saved.getInt("pid")!=current.getInt("pid"))return RemoteAppOperation.Presence.UNKNOWN;
            if(!saved.getString("start_time").equals(current.getString("start_time")))return RemoteAppOperation.Presence.DEAD;
            return RemoteProtocol.sameJson(current,saved)?RemoteAppOperation.Presence.ALIVE:RemoteAppOperation.Presence.UNKNOWN;
        }catch(Exception failure){return RemoteAppOperation.Presence.UNKNOWN;}
    }
}
