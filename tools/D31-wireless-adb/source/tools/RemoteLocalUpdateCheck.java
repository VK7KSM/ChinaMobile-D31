package net.elfradio.d31bootstrap;

import java.io.File;
import org.json.JSONObject;

/** ADB开发交付时调用实际安装与核心切换适配器，不进入APK、不伪造云端任务。 */
public final class RemoteLocalUpdateCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || android.system.Os.getuid() != 0
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL))
            throw new IllegalArgumentException("需要已核对的D31与制品参数");
        if (!new File(RemoteUpdatePlatform.RUNTIME, "updates/stop-supervisor").isFile()
                || !new File(RemoteUpdatePlatform.CORE, "stop").isFile()
                || new File(RemoteUpdatePlatform.CORE, "remote.pid").exists())
            throw new IllegalStateException("监督和核心尚未停止");
        File directory = new File(args[3]);
        if (!directory.getCanonicalPath().matches("/data/local/d31-remote/local-check-[a-z0-9-]+")
                || !directory.mkdir()) throw new IllegalArgumentException("需要新的独立证据目录");
        RemoteUpdatePlatform platform = new RemoteUpdatePlatform();
        JSONObject before = platform.current();
        JSONObject target = platform.inspect(new File(args[0]));
        if (!args[1].equals(before.getString("sha256")) || !args[2].equals(target.getString("sha256"))
                || target.getInt("versionCode") <= before.getInt("versionCode"))
            throw new IllegalStateException("实际新旧版本与预登记不一致");
        JSONObject backup = platform.backup(directory);
        RescueFiles.write(new File(directory, "before.json"), backup.toString());
        RescueFiles.write(new File(directory, "target.json"), target.toString());
        boolean success = false;
        try {
            RescueFiles.write(new File(directory, "install-intent.json"), target.toString());
            platform.install(target, false);
            platform.select(target);
            long deadline = android.os.SystemClock.elapsedRealtime() + 150000;
            while (!platform.localHealthy(target) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(500);
            if (!platform.localHealthy(target) || !RemoteUpdatePolicy.matches(target, platform.current()))
                throw new IllegalStateException("新版本本地健康未通过");
            RescueFiles.write(new File(directory, "health.json"), RescueFiles.read(
                    new File(RemoteUpdatePlatform.CORE, "health.json"), 64000));
            RescueFiles.write(new File(directory, "result.json"), new JSONObject().put("local_install", true)
                    .put("local_health", true).put("cloud_tested", false).put("version_code", target.getInt("versionCode")).toString());
            success = true;
            System.out.println("LOCAL_INSTALL_AND_CORE_HEALTH_OK version=" + target.getInt("versionCode"));
        } finally {
            platform.stopCore();
            if (!success) {
                platform.install(backup, true);
                platform.select(backup);
                platform.stopCore();
                System.out.println("PREVIOUS_VERSION_RESTORED");
            }
        }
        System.exit(0);
    }
}
