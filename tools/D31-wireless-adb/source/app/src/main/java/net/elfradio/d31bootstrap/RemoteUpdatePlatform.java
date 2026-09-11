package net.elfradio.d31bootstrap;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.system.Os;
import java.io.*;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONObject;

final class RemoteUpdatePlatform implements RemoteUpdateEngine.Platform {
    static final File ROOT = new File("/data/local/d31-remote");
    static final File RUNTIME = new File(ROOT, "runtime");
    static final File CORE = new File(RUNTIME, "state");
    static final File ACTIVE = new File(RUNTIME, "active.json");
    static final File BASELINE = new File("/system/priv-app/D31ElfRemote/D31ElfRemote.apk");
    private Context context;
    private Process core;
    private JSONObject selected;
    private String instance = "";
    private long retryStartAt;
    private int quickFailures;
    private final String session = UUID.randomUUID().toString();
    public String session() { return session; }

    boolean packagesReady() {
        try {
            java.lang.reflect.Method check = Class.forName("android.os.ServiceManager").getMethod("checkService", String.class);
            return check.invoke(null, "package") != null && check.invoke(null, "activity") != null;
        } catch (Exception unavailable) { return false; }
    }

    private Context context() throws Exception {
        if (context == null) {
            if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
            Class<?> type = Class.forName("android.app.ActivityThread");
            Object thread = type.getMethod("systemMain").invoke(null);
            context = (Context) type.getMethod("getSystemContext").invoke(thread);
        }
        return context;
    }

    @SuppressWarnings("deprecation")
    JSONObject inspect(File apk) throws Exception {
        PackageInfo info = context().getPackageManager().getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (info == null || info.signatures == null || info.signatures.length != 1) throw new SecurityException("APK证书不可解析");
        StringBuilder cert = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(info.signatures[0].toByteArray()))
            cert.append(String.format(java.util.Locale.US, "%02x", b & 255));
        if (!RemoteUpdatePolicy.PACKAGE.equals(info.packageName) || !RemoteUpdatePolicy.CERT.equals(cert.toString()))
            throw new SecurityException("APK包名或签名不符");
        return new JSONObject().put("package", info.packageName).put("certSha256", cert.toString())
                .put("versionCode", info.versionCode).put("versionName", info.versionName)
                .put("sha256", RescueFiles.sha256(apk)).put("size", apk.length()).put("path", apk.getCanonicalPath());
    }

    public JSONObject current() throws Exception {
        String path = context().getPackageManager().getApplicationInfo(RemoteUpdatePolicy.PACKAGE, 0).sourceDir;
        return inspect(new File(path));
    }

    public JSONObject prepare(JSONObject manifest, File directory) throws Exception {
        if (!RemoteUpdatePolicy.hasSpace(ROOT.getUsableSpace(), manifest.getLong("size"), current().getLong("size")))
            throw new SecurityException("空间不足，保留旧版本");
        File apk = new File(directory, "download.apk");
        RemoteUpdateFiles.download(manifest, apk);
        JSONObject actual = inspect(apk);
        if (!RemoteUpdatePolicy.matches(manifest, actual)) throw new SecurityException("APK元数据不符");
        if (!fullClient(apk)) throw new SecurityException("云更新只接受完整elfRemote制品");
        return preserve(apk);
    }

    public JSONObject backup(File directory) throws Exception { return preserve(new File(current().getString("path"))); }

    JSONObject preserve(File source) throws Exception {
        JSONObject actual = inspect(source);
        RemoteReleaseFiles files = new RemoteReleaseFiles();
        files.requireRoot(ROOT);
        File releases = new File(ROOT, "releases"); files.directory(releases);
        File folder = new File(releases, actual.getString("sha256")); files.directory(folder);
        File apk = new File(folder, "remote.apk");
        if (!files.exists(apk)) { File part = new File(folder, "remote.apk.part");
            files.requireNewPart(part);
            RemoteUpdateFiles.copy(source, part); files.file(part);
            if (!part.renameTo(apk)) throw new IOException("版本提交失败"); }
        files.file(apk);
        JSONObject preserved = inspect(apk);
        if (!RemoteUpdatePolicy.matches(actual, preserved)) throw new SecurityException("版本目录存在冲突制品");
        return preserved;
    }

    private boolean alive() {
        if (core == null) return false;
        try { core.exitValue(); return false; } catch (IllegalThreadStateException running) { return true; }
    }

    JSONObject activeArchive() throws Exception {
        if(selected!=null)return new JSONObject(selected.toString());
        JSONObject saved = ACTIVE.isFile() ? RemoteUpdateFiles.read(ACTIVE) : inspect(BASELINE);
        JSONObject actual = inspect(verifiedPath(saved));
        if (!RemoteUpdatePolicy.matches(saved,actual)) throw new SecurityException("活动原件不符");
        return actual;
    }

    private String manualInstalledKey;
    private JSONObject manualInstalled;
    private JSONObject installedForManual() throws Exception {
        PackageInfo info=context().getPackageManager().getPackageInfo(RemoteUpdatePolicy.PACKAGE,0);
        File apk=new File(info.applicationInfo.sourceDir);
        String key=apk.getPath()+":"+info.versionCode+":"+info.lastUpdateTime+":"+apk.length()+":"+apk.lastModified();
        if(!key.equals(manualInstalledKey)) {
            manualInstalled=inspect(apk).put("remote_full",fullClient(apk)); manualInstalledKey=key;
        }
        return new JSONObject(manualInstalled.toString());
    }

    static boolean fullClient(File apk)throws Exception{
        try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(apk)){
            java.util.zip.ZipEntry marker=zip.getEntry("assets/remote-full.marker");
            if(marker==null || marker.getSize()!=12)return false;
            try(InputStream in=zip.getInputStream(marker)){
                byte[] expected="d31-full-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for(byte b:expected)if(in.read()!=(b&255))return false;
                return in.read()==-1;
            }
        }
    }

    static boolean cloudUpdateBusy() throws Exception {
        File jobs = new File(RemoteUpdates.ROOT,"jobs");
        File[] entries = jobs.listFiles();
        if (entries == null) { if (jobs.exists()) throw new IOException("更新记录不可读"); return false; }
        for(File entry:entries) {
            if(!new File(entry,"offer.json").isFile()) continue;
            File state=new File(entry,"state.json");
            if(!state.isFile() || !RemoteUpdateEngine.terminal(RemoteUpdateFiles.read(state).optString("phase"))) return true;
        }
        return false;
    }

    RemoteManualUpdate manualUpdater() throws Exception {
        return new RemoteManualUpdate(new File(RemoteUpdates.ROOT,"manual"),new RemoteManualUpdate.Platform(){
            public JSONObject installed()throws Exception{return installedForManual();}
            public JSONObject active()throws Exception{return activeArchive();}
            public JSONObject preserve(JSONObject a)throws Exception{return RemoteUpdatePlatform.this.preserve(new File(a.getString("path")));}
            public boolean cloudBusy()throws Exception{return cloudUpdateBusy();}
            public void stop()throws Exception{stopCore();}
            public void select(JSONObject a)throws Exception{RemoteUpdatePlatform.this.select(a);}
            public boolean healthy(JSONObject a)throws Exception{return localHealthy(a);}
            public void restore(JSONObject a)throws Exception{install(a,true);}
            public String session(){return RemoteUpdatePlatform.this.session();}
        });
    }

    public void stopCore() throws Exception {
        if (core == null || !alive()) { core = null; return; }
        RescueFiles.write(new File(CORE, "stop"), "update\n");
        long until = android.os.SystemClock.elapsedRealtime() + 45000;
        while (alive() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(100);
        if (alive()) throw new IOException("核心未正常退出，未开始安装");
        core = null;
    }

    public void install(JSONObject archive, boolean rollback) throws Exception {
        File source = verifiedPath(archive);
        JSONObject checked = inspect(source);
        if (!RemoteUpdatePolicy.matches(archive, checked)) throw new SecurityException("待安装原件校验失败");
        // Android包管理器不能读取root私有目录；只公开这一个待安装APK，不公开凭据。
        File publicDir = new File("/data/local/tmp/d31-elfremote-install-" + UUID.randomUUID());
        if (!publicDir.mkdir()) throw new IOException("安装暂存目录创建失败");
        Os.chmod(publicDir.getPath(), 0755);
        File readable = new File(publicDir, "remote.apk");
        RemoteUpdateFiles.copy(source, readable); Os.chmod(readable.getPath(), 0644);
        File command = new File(RUNTIME, "pm-" + UUID.randomUUID());
        if (!command.mkdir()) throw new IOException("安装日志目录创建失败");
        JSONObject result = RescueDaemon.execute(command, "pm install -r " + (rollback ? "-d " : "")
                + RescueFiles.quote(readable.getPath()), 120);
        RescueFiles.write(new File(command, "result.json"), result.toString());
        if (result.optInt("exit_code", -1) != 0 || !result.optString("output").contains("Success"))
            throw new IOException("包管理器安装未确认");
        if (!RemoteUpdatePolicy.matches(archive, current())) throw new IOException("安装后实际版本不符");
        if (!readable.delete() || !publicDir.delete()) System.err.println("安装已验证，暂存文件等待清理");
    }

    private File verifiedPath(JSONObject archive) throws Exception {
        File file = new File(archive.getString("path")); String path = file.getCanonicalPath();
        if (!path.equals(BASELINE.getPath()) && !path.matches("/data/local/d31-remote/releases/[a-f0-9]{64}/remote\\.apk"))
            throw new SecurityException("运行制品路径无效");
        return file;
    }

    public void select(JSONObject archive) throws Exception {
        if (selected != null && selected.getString("sha256").equals(archive.getString("sha256")) && alive()) return;
        File apk = verifiedPath(archive);
        if (!RemoteUpdatePolicy.matches(archive, inspect(apk))) throw new SecurityException("运行制品校验失败");
        stopCore();
        RescueFiles.write(ACTIVE, archive.toString()); selected = archive;
        File stop = new File(CORE, "stop"); if (stop.exists() && !stop.delete()) throw new IOException("旧停止标记不可清理");
        instance = UUID.randomUUID().toString();
        String command = "export CLASSPATH=" + RescueFiles.quote(apk.getPath())
                + "; exec /system/bin/app_process /system/bin --nice-name=d31-elfremote net.elfradio.d31bootstrap.RemoteDaemon "
                + RescueFiles.quote(CORE.getPath()) + " run " + instance + " </dev/null >/dev/null 2>&1";
        core = new ProcessBuilder("/system/bin/sh", "-c", command).start();
    }

    void ensureCore(long now) throws Exception {
        if (alive()) return;
        if (now < retryStartAt) return;
        quickFailures++; retryStartAt = now + Math.min(60000, quickFailures * 5000L);
        JSONObject archive = ACTIVE.exists() ? RemoteUpdateFiles.read(ACTIVE) : inspect(BASELINE);
        // 系统安装器升级固定基线后，重新读取其真实元数据；/data活动版本仍按原摘要验证。
        if (BASELINE.getPath().equals(archive.optString("path"))) archive = inspect(BASELINE);
        select(archive);
    }

    public boolean localHealthy(JSONObject archive) throws Exception {
        File health = new File(CORE, "health.json");
        if (!alive() || !health.isFile()) return false;
        JSONObject h = RemoteUpdateFiles.read(health);
        boolean healthy = instance.equals(h.optString("instance")) && h.optInt("version_code") == archive.getInt("versionCode")
                && archive.getString("sha256").equals(h.optString("apk_sha256")) && h.optBoolean("local_ready")
                && h.optInt("uid", -1) == 0 && System.currentTimeMillis() - h.optLong("time_ms") < 20000;
        if (healthy) quickFailures = 0;
        return healthy;
    }

    public boolean cloudHealthy(JSONObject archive) throws Exception {
        return localHealthy(archive) && RemoteUpdateFiles.read(new File(CORE, "health.json")).optBoolean("report_acknowledged");
    }

    public void progress(String job, String state, String detail) throws Exception {
        JSONObject identity = RemoteUpdateFiles.read(new File(CORE, "identity.json"));
        JSONObject response = RemoteHttp.cloud("/api/elfremote/update-progress", new JSONObject()
                .put("device_id", identity.getString("device_id")).put("token", identity.getString("token"))
                .put("job_id", job).put("state", state).put("detail", detail));
        JSONObject update = response.getJSONObject("update");
        if (!job.equals(update.optString("job_id")) || !state.equals(update.optString("state")))
            throw new IOException("云端未确认该更新进度");
    }
}
