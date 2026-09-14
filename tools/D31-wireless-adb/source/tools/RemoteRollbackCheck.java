package net.elfradio.d31bootstrap;

import java.io.File;
import org.json.JSONObject;

/** 只在开发验收调用，验证真实安装器降版恢复后再还原当前版本。 */
public final class RemoteRollbackCheck {
    private static void healthy(RemoteUpdatePlatform platform, JSONObject archive, File output) throws Exception {
        platform.select(archive);
        long deadline = android.os.SystemClock.elapsedRealtime() + 150000;
        while (!platform.localHealthy(archive) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(500);
        if (!platform.localHealthy(archive) || !RemoteUpdatePolicy.matches(archive, platform.current()))
            throw new IllegalStateException("恢复后的真实版本或本地健康不一致");
        RescueFiles.write(output, RescueFiles.read(new File(RemoteUpdatePlatform.CORE, "health.json"), 64000));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || android.system.Os.getuid() != 0
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL))
            throw new IllegalArgumentException("需要已核对D31与新旧制品参数");
        if (!new File(RemoteUpdatePlatform.RUNTIME, "updates/stop-supervisor").isFile()
                || !new File(RemoteUpdatePlatform.CORE, "stop").isFile()
                || new File(RemoteUpdatePlatform.CORE, "remote.pid").exists())
            throw new IllegalStateException("监督和核心未停止");
        File directory = new File(args[3]);
        if (!directory.getCanonicalPath().matches("/data/local/d31-remote/rollback-check-[a-z0-9-]+")
                || !directory.mkdir()) throw new IllegalArgumentException("需要新的证据目录");
        RemoteUpdatePlatform platform = new RemoteUpdatePlatform();
        JSONObject current = platform.backup(directory);
        JSONObject previous = platform.inspect(new File(args[0]));
        if (!args[1].equals(current.getString("sha256")) || !args[2].equals(previous.getString("sha256"))
                || previous.getInt("versionCode") >= current.getInt("versionCode"))
            throw new IllegalStateException("原件版本或摘要不符");
        RescueFiles.write(new File(directory, "original.json"), current.toString());
        RescueFiles.write(new File(directory, "previous.json"), previous.toString());
        boolean rollbackPassed = false;
        try {
            platform.install(previous, true);
            healthy(platform, previous, new File(directory, "rollback-health.json"));
            rollbackPassed = true;
            System.out.println("ROLLBACK_INSTALL_AND_HEALTH_OK version=" + previous.getInt("versionCode"));
        } finally {
            platform.stopCore();
            platform.install(current, false);
            try { healthy(platform, current, new File(directory, "restored-health.json")); }
            finally { platform.stopCore(); }
            RescueFiles.write(new File(directory, "result.json"), new JSONObject()
                    .put("rollback_passed", rollbackPassed).put("original_restored", true)
                    .put("original_version", current.getInt("versionCode")).put("cloud_tested", false).toString());
            System.out.println("ORIGINAL_VERSION_AND_HEALTH_RESTORED version=" + current.getInt("versionCode"));
        }
        System.exit(0);
    }
}
