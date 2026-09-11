package net.elfradio.d31bootstrap;

import android.content.Context;
import java.io.*;
import java.nio.channels.FileLock;
import org.json.JSONObject;

/** 给旧固定监督基线补一次离线交接支持；不改boot、Recovery或桌面。 */
public final class RemoteManualBootstrap {
    static final int MIN_SUPERVISOR = 85;
    static void request(Context context) {
        request(context,()->{});
    }
    static void request(Context context,Runnable finished) {
        if(!RemoteDeployment.systemManaged()){finished.run();return;}
        final String apk=context.getApplicationInfo().sourceDir;
        new Thread(()->{
            try {
                String launch="export CLASSPATH="+RescueFiles.quote(apk)
                        +"; exec /system/bin/app_process /system/bin --nice-name=d31-manual-update "
                        +"net.elfradio.d31bootstrap.RemoteManualBootstrap";
                RootTransport.execute("/system/bin/busybox setsid /system/bin/sh -c "+RescueFiles.quote(launch)
                        +" </dev/null >/dev/null 2>&1 &",5000);
            }catch(Exception error){ProbeLog.append(context,"本地更新检查未提交："+error.getClass().getSimpleName());}
            finally{finished.run();}
        },"d31-manual-bootstrap").start();
    }

    public static void main(String[] args) throws Exception {
        if(args.length!=0 || android.system.Os.getuid()!=0 || android.os.Build.VERSION.SDK_INT!=23
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL) || !RemoteDeployment.systemManaged())
            throw new SecurityException("非已部署的D31系统组件");
        File root=new File(RemoteUpdates.ROOT,"manual-bootstrap");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("本地更新目录不可用");
        try(RandomAccessFile lockFile=new RandomAccessFile(new File(root,"lock"),"rw");
            FileLock lock=RemoteFileLocks.tryExclusive(lockFile.getChannel())) {
            if(lock==null)return;
            File result=new File(root,"result.json");
            RemoteUpdatePlatform platform=new RemoteUpdatePlatform();
            JSONObject target=platform.current();
            JSONObject baseline=platform.inspect(RemoteUpdatePlatform.BASELINE);
            if(baseline.getInt("versionCode")>=MIN_SUPERVISOR){
                if(!RemoteUpdates.ready()) {
                    File interrupted=new File(RemoteUpdates.ROOT,"stop-supervisor");
                    if(interrupted.isFile() && "manual-bootstrap".equals(RescueFiles.read(interrupted,128).trim())
                            && !interrupted.delete())throw new IOException("中断的本地交接标记无法清除");
                    startSupervisor();
                }
                RescueFiles.write(result,new JSONObject().put("state","ready").put("time_ms",System.currentTimeMillis()).toString());
                System.exit(0);return;
            }
            if(RemoteUpdatePlatform.cloudUpdateBusy()) {
                RescueFiles.write(result,new JSONObject().put("state","deferred").put("reason","云更新尚未结束").toString());
                System.exit(0);return;
            }
            if(target.getInt("versionCode")<MIN_SUPERVISOR || !RemoteUpdatePlatform.fullClient(new File(target.getString("path"))))
                throw new SecurityException("需要完整离线客户端");
            JSONObject backup=platform.preserve(RemoteUpdatePlatform.BASELINE);
            JSONObject preserved=platform.preserve(new File(target.getString("path")));
            File before=new File(root,"baseline-"+backup.getString("sha256")+".json");
            if(!before.exists())RescueFiles.write(before,backup.toString());
            RescueFiles.write(result,new JSONObject().put("state","preparing").put("backup",backup).put("target",preserved).toString());
            File stop=new File(RemoteUpdates.ROOT,"stop-supervisor");
            boolean stopping=false, replacing=false;
            String stage="stop_supervisor";
            try {
                // 原监督会正常结束它所拥有的核心，再释放同一把锁。
                RescueFiles.write(stop,"manual-bootstrap\n"); stopping=true;
                long until=android.os.SystemClock.elapsedRealtime()+60000;
                boolean stopped=false;
                while(android.os.SystemClock.elapsedRealtime()<until) {
                    try(RandomAccessFile f=new RandomAccessFile(new File(RemoteUpdates.ROOT,"supervisor.lock"),"rw");
                        FileLock supervisor=RemoteFileLocks.tryExclusive(f.getChannel())) {
                        if(supervisor!=null){stopped=true;break;}
                    }
                    Thread.sleep(200);
                }
                if(!stopped)throw new IOException("监督未退出，未替换系统基线");
                if(RemoteUpdatePlatform.cloudUpdateBusy())throw new IOException("存在未结束云任务，保留旧基线");
                if(!RemoteUpdatePolicy.matches(target,platform.current()))throw new IOException("安装版本已变化");
                if(!RemoteUpdatePlatform.ACTIVE.isFile() || RemoteUpdatePlatform.BASELINE.getPath().equals(
                        RemoteUpdateFiles.read(RemoteUpdatePlatform.ACTIVE).optString("path")))
                    RescueFiles.write(RemoteUpdatePlatform.ACTIVE,backup.toString());
                stage="replace_baseline"; replacing=true; replaceBaseline(preserved);
                if(!RemoteUpdatePolicy.matches(target,platform.inspect(RemoteUpdatePlatform.BASELINE)))throw new IOException("系统基线回读不符");
                RescueFiles.write(result,new JSONObject().put("state","supervisor_updated").put("version_code",target.getInt("versionCode"))
                        .put("time_ms",System.currentTimeMillis()).toString());
            }catch(Exception error){
                boolean restored=!replacing;
                if(replacing)try{replaceBaseline(backup);restored=RemoteUpdatePolicy.matches(backup,platform.inspect(RemoteUpdatePlatform.BASELINE));}catch(Exception ignored){}
                RescueFiles.write(result,new JSONObject().put("state","attention").put("baseline_restored",restored)
                        .put("stage",stage).put("error",error.getClass().getSimpleName()).put("reason",error.getMessage()).toString());
            }finally{
                if(stopping){if(stop.exists()&&!stop.delete())throw new IOException("监督停止标记未清除");startSupervisor();}
            }
        }
        System.exit(0);
    }
    private static void replaceBaseline(JSONObject archive)throws Exception{
        String staged=RemoteUpdatePlatform.BASELINE.getPath()+".manual-new";
        String hash=archive.getString("sha256");
        if(!hash.matches("[a-f0-9]{64}"))throw new IOException("基线摘要无效");
        run("set -e\nmount -o remount,rw /system\n"
                +"trap 'mount -o remount,ro /system' EXIT\n"
                +"cp "+RescueFiles.quote(archive.getString("path"))+" "+RescueFiles.quote(staged)+"\n"
                +"chown 0:0 "+RescueFiles.quote(staged)+"\nchmod 0644 "+RescueFiles.quote(staged)+"\n"
                +"chcon u:object_r:system_file:s0 "+RescueFiles.quote(staged)+"\n"
                +"actual=$(/system/bin/busybox sha256sum "+RescueFiles.quote(staged)+")\n"
                +"[ \"${actual%% *}\" = "+RescueFiles.quote(hash)+" ]\n"
                +"sync\nmv "+RescueFiles.quote(staged)+" "+RescueFiles.quote(RemoteUpdatePlatform.BASELINE.getPath())+"\nsync\n");
    }
    private static void startSupervisor() throws Exception {
        run("/system/bin/d31-elfremote-start </dev/null >/dev/null 2>&1 &");
    }
    private static void run(String command)throws Exception{
        Process p=new ProcessBuilder("/system/bin/sh","-c",command).redirectErrorStream(true).start();
        try(InputStream in=p.getInputStream()){byte[] b=new byte[1024];while(in.read(b)!=-1){}}
        if(p.waitFor()!=0)throw new IOException("本地监督部署命令失败");
    }
}
