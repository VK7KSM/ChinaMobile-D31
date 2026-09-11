package net.elfradio.d31bootstrap;

import android.content.Context;
import java.io.*;
import java.nio.channels.FileLock;
import org.json.JSONObject;

/** 给旧固定监督基线补一次离线交接支持；不改boot、Recovery或桌面。 */
public final class RemoteManualBootstrap {
    static final int MIN_SUPERVISOR = 85;
    static int requiredSupervisor(boolean maintenance, int clientVersion) {
        return maintenance ? Math.max(96, clientVersion) : MIN_SUPERVISOR;
    }
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

    public static void main(String[] args) {
        try {
            runMain(args);
            System.exit(0);
        } catch(Exception failure) {
            System.err.println("监督交接未完成："+failure.getClass().getSimpleName()+": "+failure.getMessage());
            failure.printStackTrace(System.err);
            System.err.flush();
            System.exit(1);
        }
    }
    private static void runMain(String[] args) throws Exception {
        boolean maintenanceUpgrade=args.length==1 && "maintenance".equals(args[0]);
        int requiredSupervisor=requiredSupervisor(maintenanceUpgrade, BuildConfig.VERSION_CODE);
        if((args.length!=0 && !maintenanceUpgrade) || android.system.Os.getuid()!=0 || android.os.Build.VERSION.SDK_INT!=23
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL) || !RemoteDeployment.systemManaged())
            throw new SecurityException("非已部署的D31系统组件");
        File root=new File(RemoteUpdates.ROOT,"manual-bootstrap");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("本地更新目录不可用");
        try(RandomAccessFile lockFile=new RandomAccessFile(new File(root,"lock"),"rw");
            FileLock lock=RemoteFileLocks.tryExclusive(lockFile.getChannel());
            RemoteMaintenance.Lease maintenance=RemoteMaintenance.acquire()) {
            if(lock==null || maintenance==null)return;
            File result=new File(root,"result.json");
            RemoteUpdatePlatform platform=new RemoteUpdatePlatform();
            JSONObject target=platform.current();
            JSONObject baseline=platform.inspect(RemoteUpdatePlatform.BASELINE);
            if(baseline.getInt("versionCode")>=requiredSupervisor){
                File interrupted=new File(RemoteUpdates.ROOT,"stop-supervisor");
                boolean recovering=interrupted.exists();
                if(recovering) {
                    if(!interrupted.isFile() || !"manual-bootstrap".equals(RescueFiles.read(interrupted,128).trim()))
                        throw new IOException("另一维护流程保留了停止标记");
                    awaitSupervisorExit();
                    if(!interrupted.delete())throw new IOException("中断的本地交接标记无法清除");
                }
                RescueFiles.write(result,new JSONObject().put("state","baseline_compatible")
                        .put("time_ms",System.currentTimeMillis()).toString());
                startSupervisor(root, result, baseline);
                System.exit(0);return;
            }
            RemoteMaintenance.requireUnreserved();
            if(RemoteUpdatePlatform.cloudUpdateBusy()) {
                RescueFiles.write(result,new JSONObject().put("state","deferred").put("reason","云更新尚未结束").toString());
                System.exit(0);return;
            }
            if(target.getInt("versionCode")<requiredSupervisor || !RemoteUpdatePlatform.fullClient(new File(target.getString("path"))))
                throw new SecurityException("需要完整离线客户端");
            File stop=new File(RemoteUpdates.ROOT,"stop-supervisor");
            requireOwnedStop(stop);
            JSONObject backup=platform.preserve(RemoteUpdatePlatform.BASELINE);
            JSONObject preserved=platform.preserve(new File(target.getString("path")));
            File before=new File(root,"baseline-"+backup.getString("sha256")+".json");
            if(!before.exists())RescueFiles.write(before,backup.toString());
            RescueFiles.write(result,new JSONObject().put("state","preparing").put("backup",backup).put("target",preserved).toString());
            boolean stopping=false, replacing=false;
            String stage="stop_supervisor";
            try {
                // 原监督会正常结束它所拥有的核心，再释放同一把锁。
                RescueFiles.write(stop,"manual-bootstrap\n"); stopping=true;
                awaitSupervisorExit();
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
                if(stopping){
                    requireOwnedStop(stop);
                    if(stop.exists()&&!stop.delete())throw new IOException("监督停止标记未清除");
                    // 使用实际恢复后的基线；旧85备份也必须能够重新启动。
                    startSupervisor(root, result, platform.inspect(RemoteUpdatePlatform.BASELINE));
                }
            }
        }
        System.exit(0);
    }
    static void requireOwnedStop(File stop) throws Exception {
        if (!stop.getAbsoluteFile().equals(stop.getCanonicalFile())) throw new IOException("停止标记不能为链接");
        if (stop.exists() && (!stop.isFile() || !"manual-bootstrap".equals(RescueFiles.read(stop,128).trim())))
            throw new IOException("另一维护流程保留了停止标记");
    }
    private static void awaitSupervisorExit() throws Exception {
        long until=android.os.SystemClock.elapsedRealtime()+60000;
        while(android.os.SystemClock.elapsedRealtime()<until) {
            try(RandomAccessFile f=new RandomAccessFile(new File(RemoteUpdates.ROOT,"supervisor.lock"),"rw");
                FileLock supervisor=RemoteFileLocks.tryExclusive(f.getChannel())) {
                if(supervisor!=null)return;
            }
            Thread.sleep(200);
        }
        throw new IOException("监督未退出，保留交接记录");
    }
    private static void replaceBaseline(JSONObject archive)throws Exception{
        run(baselineReplaceCommand(archive));
    }
    static String baselineReplaceCommand(JSONObject archive)throws Exception{
        String staged=RemoteUpdatePlatform.BASELINE.getPath()+".manual-new";
        String hash=archive.getString("sha256");
        if(!hash.matches("[a-f0-9]{64}"))throw new IOException("基线摘要无效");
        // 读取唯一挂载项；成功和失败都恢复真实前像，并以回读失败影响最终退出码。
        String mode="/system/bin/busybox awk '$2==\"/system\" { n++; if ($4 ~ /(^|,)ro(,|$)/) { modes++; mode=\"ro\" } "
                +"if ($4 ~ /(^|,)rw(,|$)/) { modes++; mode=\"rw\" } } END { if (n!=1 || modes!=1) exit 1; print mode }' /proc/mounts";
        return "set -eu\noriginal_mode=$("+mode+")\n"
                +"restore_mount() { status=$?; trap - EXIT HUP INT TERM; "
                +"mount -o remount,\"$original_mode\" /system || exit 1; "
                +"observed=$("+mode+") || exit 1; [ \"$observed\" = \"$original_mode\" ] || exit 1; exit \"$status\"; }\n"
                +"trap restore_mount EXIT\ntrap 'exit 129' HUP\ntrap 'exit 130' INT\ntrap 'exit 143' TERM\n"
                +"mount -o remount,rw /system\n[ \"$("+mode+")\" = rw ]\n"
                +"cp "+RescueFiles.quote(archive.getString("path"))+" "+RescueFiles.quote(staged)+"\n"
                +"chown 0:0 "+RescueFiles.quote(staged)+"\nchmod 0644 "+RescueFiles.quote(staged)+"\n"
                +"chcon u:object_r:system_file:s0 "+RescueFiles.quote(staged)+"\n"
                +"actual=$(/system/bin/busybox sha256sum "+RescueFiles.quote(staged)+")\n"
                +"[ \"${actual%% *}\" = "+RescueFiles.quote(hash)+" ]\n"
                +"sync\nmv "+RescueFiles.quote(staged)+" "+RescueFiles.quote(RemoteUpdatePlatform.BASELINE.getPath())+"\nsync\n";
    }
    interface LaunchAccess {
        long wallTime();
        long elapsedTime();
        String read(File file, int limit) throws Exception;
        String hash(File file) throws Exception;
        Process start(File log) throws Exception;
        void pause(long millis) throws InterruptedException;
    }
    static String launchCommand(File log) {
        // API23不使用ProcessBuilder.redirectOutput；无后台&，由原脚本exec setsid。
        return "exec /system/bin/sh /system/bin/d31-elfremote-start </dev/null >"
                + RescueFiles.quote(log.getAbsolutePath()) + " 2>&1";
    }
    private static LaunchAccess launchAccess() {
        return new LaunchAccess() {
            public long wallTime() { return System.currentTimeMillis(); }
            public long elapsedTime() { return android.os.SystemClock.elapsedRealtime(); }
            public String read(File file,int limit) throws Exception { return RescueFiles.read(file,limit); }
            public String hash(File file) throws Exception { return RescueFiles.sha256(file); }
            public Process start(File log) throws Exception {
                android.system.Os.chmod(log.getPath(),0600);
                return new ProcessBuilder("/system/bin/sh","-c",launchCommand(log)).start();
            }
            public void pause(long millis) throws InterruptedException { Thread.sleep(millis); }
        };
    }
    static final class LaunchFailure extends IOException {
        final JSONObject receipt;
        LaunchFailure(JSONObject receipt, Exception cause) {
            super("监督启动未确认，保留日志：" + receipt.optString("log"),cause);
            this.receipt=receipt;
        }
    }
    private static void startSupervisor(File root,File result,JSONObject baseline) throws Exception {
        JSONObject current=new JSONObject(RescueFiles.read(result,64000));
        try {
            JSONObject receipt=launchSupervisor(root,baseline,15000,launchAccess());
            RescueFiles.write(result,current.put("startup",receipt).toString());
        } catch(LaunchFailure failure) {
            // 不回退可能已经映射的新APK，也不重建停止标记；下次显式调用先核验现存监督。
            RescueFiles.write(result,current.put("previous_state",current.optString("state"))
                    .put("state","startup_attention").put("startup",failure.receipt).toString());
            throw failure;
        }
    }
    /** 握手仅证明监督身份；调用者持维护锁时不能等待核心或Web。超时不杀、不重复派生。 */
    static JSONObject launchSupervisor(File root,JSONObject baseline,long timeoutMs,LaunchAccess access) throws Exception {
        if(timeoutMs<1 || timeoutMs>60000 || baseline.getInt("versionCode")<MIN_SUPERVISOR
                || !baseline.getString("sha256").matches("[a-f0-9]{64}"))throw new IOException("启动参数无效");
        File log=File.createTempFile("supervisor-launch-",".log",root);
        File record=new File(log.getPath()+".json");
        JSONObject receipt=new JSONObject().put("state","launch_pending").put("log",log.getAbsolutePath())
                .put("version_code",baseline.getInt("versionCode")).put("apk_sha256",baseline.getString("sha256"))
                .put("runtime_effect","NOT_CHECKED");
        Process process=null;
        long start=access.elapsedTime(),wall=access.wallTime();
        try {
            RescueFiles.write(record,receipt.toString());
            JSONObject prior=supervisorHealth(access);
            int oldPid=prior.optInt("pid",-1);
            receipt.put("previous_pid",oldPid);
            JSONObject live=confirmedSupervisor(access,baseline,0,-1);
            if(live!=null && access.elapsedTime()-start<timeoutMs) {
                receipt.put("state","already_running").put("pid",live.getInt("pid"))
                        .put("heartbeat_time_ms",live.getLong("time_ms"));
                RescueFiles.write(record,receipt.toString());return receipt;
            }
            if(access.elapsedTime()-start>=timeoutMs)throw new IOException("启动预检超时");
            receipt.put("started_at_ms",wall);
            RescueFiles.write(record,receipt.toString());
            process=access.start(log);
            process.getOutputStream().close();
            while(true) {
                long elapsed=access.elapsedTime()-start;
                if(elapsed<0 || elapsed>=timeoutMs || access.wallTime()<wall)throw new IOException("监督握手超时或时钟回退");
                Integer exit=null;
                try { exit=process.exitValue(); } catch(IllegalThreadStateException running) { }
                if(exit!=null)receipt.put("launcher_exit_code",exit);
                if(exit!=null && exit!=0)throw new IOException("监督启动进程退出："+exit);
                live=confirmedSupervisor(access,baseline,wall,oldPid);
                if(live!=null && access.elapsedTime()-start<timeoutMs) {
                    receipt.put("state","supervisor_confirmed").put("pid",live.getInt("pid"))
                            .put("heartbeat_time_ms",live.getLong("time_ms"));
                    RescueFiles.write(record,receipt.toString());return receipt;
                }
                long remaining=timeoutMs-(access.elapsedTime()-start);
                if(remaining>0)access.pause(Math.min(100,remaining));
            }
        } catch(Exception failure) {
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            receipt.put("state","unconfirmed").put("error",failure.getClass().getSimpleName());
            try { RescueFiles.write(record,receipt.toString()); } catch(Exception save) { failure.addSuppressed(save); }
            throw new LaunchFailure(receipt,failure);
        } finally {
            if(process!=null) {
                closeLaunchStream(process.getOutputStream());
                closeLaunchStream(process.getInputStream());closeLaunchStream(process.getErrorStream());
            }
        }
    }
    private static JSONObject supervisorHealth(LaunchAccess access) {
        try { return new JSONObject(access.read(new File(RemoteUpdates.ROOT,"supervisor.json"),4096)); }
        catch(Exception unavailable) { return new JSONObject(); }
    }
    private static JSONObject confirmedSupervisor(LaunchAccess access,JSONObject baseline,long since,int excludedPid) {
        try {
            JSONObject health=supervisorHealth(access);
            int pid=health.optInt("pid",-1),version=baseline.getInt("versionCode");
            long time=health.getLong("time_ms"),age=access.wallTime()-time;
            if(pid<=0 || pid==excludedPid || health.optInt("uid",-1)!=0 || health.optInt("version_code")!=version
                    || (version>=96 && health.optInt("maintenance_protocol")!=RemoteMaintenance.PROTOCOL)
                    || time<since || age<0 || age>=20000)return null;
            String path=baseline.getString("path");
            String maps=access.read(new File("/proc/"+pid+"/maps"),RemoteRuntimeInventory.MAPS_LIMIT);
            if(!RemoteRuntimeInventory.mapsArchive(maps,path)
                    || !baseline.getString("sha256").equals(access.hash(new File(path))))return null;
            JSONObject after=supervisorHealth(access);
            if(after.optInt("pid",-1)!=pid || after.optLong("time_ms",-1)<time
                    || after.optInt("version_code")!=version)return null;
            return health;
        } catch(Exception unavailable) { return null; }
    }
    private static void closeLaunchStream(Closeable stream) {
        try { stream.close(); } catch(IOException ignored) { }
    }
    private static void run(String command)throws Exception{
        Process p=new ProcessBuilder("/system/bin/sh","-c",command).redirectErrorStream(true).start();
        try(InputStream in=p.getInputStream()){byte[] b=new byte[1024];while(in.read(b)!=-1){}}
        if(p.waitFor()!=0)throw new IOException("本地监督部署命令失败");
    }
}
