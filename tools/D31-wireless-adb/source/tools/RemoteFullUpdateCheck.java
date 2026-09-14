package net.elfradio.d31bootstrap;

import java.io.File;
import org.json.JSONObject;

/** 独立开发验收：真实签名、缓存制品、安装与恢复；云回执保持模拟离线。 */
public final class RemoteFullUpdateCheck {
    private static final class Platform implements RemoteUpdateEngine.Platform {
        final RemoteUpdatePlatform actual = new RemoteUpdatePlatform();
        final boolean forceHealthFailure;
        int installs, restores, reports;
        Platform(boolean forceHealthFailure) { this.forceHealthFailure = forceHealthFailure; }
        public JSONObject current() throws Exception { return actual.current(); }
        public JSONObject prepare(JSONObject manifest, File folder) throws Exception { return actual.prepare(manifest, folder); }
        public JSONObject backup(File folder) throws Exception { return actual.backup(folder); }
        public void stopCore() throws Exception { actual.stopCore(); }
        public void install(JSONObject archive, boolean rollback) throws Exception {
            if (rollback) restores++; else installs++;
            actual.install(archive, rollback);
        }
        public void select(JSONObject archive) throws Exception { actual.select(archive); }
        public boolean localHealthy(JSONObject archive) throws Exception {
            boolean healthy = actual.localHealthy(archive);
            return healthy && !(forceHealthFailure && archive.getInt("versionCode") == 76);
        }
        public boolean cloudHealthy(JSONObject archive) { return false; }
        public String session() { return actual.session(); }
        public void progress(String job, String state, String detail) throws Exception {
            reports++;
            throw new RemoteHttp.Rejected(503, "独立诊断模拟回执离线", 900000);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5 || android.system.Os.getuid() != 0
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL)
                || !("recover".equals(args[4]) || "offline".equals(args[4])))
            throw new IllegalArgumentException("需要D31、制品、清单、摘要与模式");
        if (!new File(RemoteUpdates.ROOT,"stop-supervisor").isFile()
                || !new File(RemoteUpdatePlatform.CORE,"stop").isFile()
                || new File(RemoteUpdatePlatform.CORE,"remote.pid").exists())
            throw new IllegalStateException("生产监督和核心尚未停止");
        File root = new File(args[0]);
        if (!root.getCanonicalPath().matches("/data/local/d31-remote/engine-check-[a-z0-9-]+")
                || !root.mkdir()) throw new IllegalArgumentException("需要新的独立证据目录");
        Platform platform = new Platform("recover".equals(args[4]));
        JSONObject original = platform.backup(root);
        JSONObject target = platform.actual.inspect(new File(args[1]));
        if (original.getInt("versionCode") != 75 || target.getInt("versionCode") != 76
                || !args[3].equals(original.getString("sha256")))
            throw new IllegalStateException("实际基线不符");
        RescueFiles.write(new File(root,"original.json"), original.toString());
        JSONObject offer = RemoteUpdateFiles.read(new File(args[2]));
        JSONObject manifest = RemoteUpdatePolicy.validate(offer, RemoteUpdatePolicy.trustedKey(),
                "local-validation-only", System.currentTimeMillis(), false);
        if (!RemoteUpdatePolicy.matches(manifest,target)) throw new IllegalStateException("签名清单与真实APK不符");
        File job = new File(root,"job"); if (!job.mkdir()) throw new IllegalStateException("任务目录创建失败");
        RescueFiles.write(new File(job,"offer.json"), offer.toString());
        RemoteUpdateFiles.copy(new File(args[1]),new File(job,"download.apk"));
        boolean passed = false;
        long base = System.currentTimeMillis(); int step = 0;
        try {
            RemoteUpdateEngine engine = new RemoteUpdateEngine(job,platform,RemoteUpdatePolicy.trustedKey(),"local-validation-only");
            while (!"wait_health".equals(engine.state().getString("phase")) && step < 8) {
                engine.step(base + step);
                RescueFiles.write(new File(root,"step-" + step++ + ".json"), engine.state().toString());
                if (RemoteUpdateEngine.terminal(engine.state().getString("phase")))
                    throw new IllegalStateException("安装前意外进入终态");
            }
            if (!"wait_health".equals(engine.state().getString("phase")) || platform.installs != 1)
                throw new IllegalStateException("实际安装流程未完成");
            waitHealth(platform.actual,target);
            RescueFiles.write(new File(root,"new-core-health.json"),RescueFiles.read(
                    new File(RemoteUpdatePlatform.CORE,"health.json"),64000));
            engine = new RemoteUpdateEngine(job,platform,RemoteUpdatePolicy.trustedKey(),"local-validation-only");
            engine.step(base + 160000);
            RescueFiles.write(new File(root,"after-deadline.json"),engine.state().toString());
            if (platform.forceHealthFailure) {
                if (!"rollback".equals(engine.state().getString("phase"))) throw new IllegalStateException("未进入失败恢复");
                engine.step(base + 160001);
                waitHealth(platform.actual,original);
                engine = new RemoteUpdateEngine(job,platform,RemoteUpdatePolicy.trustedKey(),"local-validation-only");
                engine.step(base + 160002);
                if (!"recovered".equals(engine.state().getString("phase")) || platform.restores != 1
                        || !RemoteUpdatePolicy.matches(original,platform.current()))
                    throw new IllegalStateException("真实恢复未通过");
                RescueFiles.write(new File(root,"restored-health.json"),RescueFiles.read(
                        new File(RemoteUpdatePlatform.CORE,"health.json"),64000));
            } else {
                if (!"wait_health".equals(engine.state().getString("phase")) || platform.restores != 0
                        || !RemoteUpdatePolicy.matches(target,platform.current()))
                    throw new IllegalStateException("离线错误触发回滚或版本不符");
            }
            int installs=platform.installs, restores=platform.restores;
            for (int i=0;i<5;i++) new RemoteUpdateEngine(job,platform,RemoteUpdatePolicy.trustedKey(),"local-validation-only")
                    .step(base+170000+i);
            if (installs!=platform.installs || restores!=platform.restores || platform.reports!=1)
                throw new IllegalStateException("重建引擎重放安装或绕过回执等待");
            RescueFiles.write(new File(root,"result.json"),new JSONObject().put("mode",args[4])
                    .put("phase",engine.state().getString("phase")).put("installs",platform.installs)
                    .put("restores",platform.restores).put("simulated_reports",platform.reports)
                    .put("cloud_tested",false).put("http_download_tested",false)
                    .put("actual_version",platform.current().getInt("versionCode")).toString());
            passed=true;
            System.out.println(platform.forceHealthFailure ? "FULL_ENGINE_REAL_INSTALL_AND_RECOVERY_OK"
                    : "FULL_ENGINE_CACHED_INSTALL_OK OFFLINE_HEALTH_PRESERVED NO_REPLAY");
        } finally {
            platform.stopCore();
            if (!passed) {
                platform.actual.install(original,true);
                platform.actual.select(original);
                waitHealth(platform.actual,original);
                platform.actual.stopCore();
                System.out.println("FAILED_CHECK_ORIGINAL_RESTORED");
            }
        }
        System.exit(0);
    }
    private static void waitHealth(RemoteUpdatePlatform platform, JSONObject archive) throws Exception {
        long until=android.os.SystemClock.elapsedRealtime()+150000;
        while (!platform.localHealthy(archive) && android.os.SystemClock.elapsedRealtime()<until) Thread.sleep(300);
        if (!platform.localHealthy(archive)) throw new IllegalStateException("真实核心健康未通过");
    }
}
