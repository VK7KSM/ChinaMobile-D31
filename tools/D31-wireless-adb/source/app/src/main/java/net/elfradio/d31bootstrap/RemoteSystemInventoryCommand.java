package net.elfradio.d31bootstrap;

import java.io.File;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.diagnostics.collection.*;

/** 独立按需入口，不在开机、心跳或界面线程执行系统哈希。 */
public final class RemoteSystemInventoryCommand {
    public static void main(String[] args) {
        try {
            if (args.length != 2 || android.system.Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                    || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                    || !"hct6737t_66_m0".equals(android.os.Build.MODEL)) throw new SecurityException("仅限D31维护入口");
            android.system.Os.umask(0077);
            String boot = RescueFiles.read(new File("/proc/sys/kernel/random/boot_id"), 128).trim();
            JSONObject active = new JSONObject(RescueFiles.read(new File("/data/local/d31-remote/runtime/active.json"), 16000));
            String digest = active.getString("sha256");
            if (!digest.matches("[a-f0-9]{64}") || !boot.matches("[a-f0-9-]{36}")) throw new SecurityException("活动身份无效");
            String apk = active.getString("path");
            if (!apk.equals("/data/local/d31-remote/releases/" + digest + "/remote.apk")
                    || !apk.equals(System.getenv("CLASSPATH"))) throw new SecurityException("采集必须使用当前活动核心");
            CollectionAccess.Clock clock = AndroidCollectionAccess.systemClock();
            JSONObject result = RemoteSystemInventoryJobs.execute(new File("/data/local/d31-remote/system-inventory"),
                    args[0], new JSONObject(args[1]), digest + "-" + boot, android.os.Build.FINGERPRINT,
                    new AndroidCollectionAccess("/system/bin/busybox", clock), clock);
            System.out.println(result.toString());
            System.exit(0);
        } catch (Exception error) {
            System.err.println("系统采集未完成：" + error.getClass().getSimpleName() + ": " + error.getMessage());
            System.exit(1);
        }
    }
    private RemoteSystemInventoryCommand() { }
}
