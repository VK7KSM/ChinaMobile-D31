package net.elfradio.d31bootstrap;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.IOException;
import net.elfradio.d31bootstrap.management.NetworkProcess;
import org.json.JSONObject;

/** 固定监督调度原APK恢复未结束事务；维护锁只用于读取预留，不跨进程等待。 */
final class RemoteNetworkStartup {
    interface Access {
        long elapsed();
        JSONObject reservation() throws Exception;
        boolean resume(String task, String hash) throws Exception;
    }
    private final Access access;
    private long lastAttempt = -1;
    private String attentionKey;
    RemoteNetworkStartup(Access access) { this.access = access; }

    void tick() throws Exception {
        long now = access.elapsed();
        if (now < 0) throw new IOException("NETWORK_STARTUP_CLOCK_INVALID");
        if (lastAttempt >= 0 && now >= lastAttempt && now - lastAttempt < 10000) return;
        lastAttempt = now;
        JSONObject reservation = access.reservation();
        if (reservation == null || !"network".equals(reservation.optString("kind"))) { attentionKey = null; return; }
        String task = reservation.optString("network_task"), hash = reservation.optString("plan_sha256");
        RemoteNetworkAccess.requireOwner(reservation, task, hash);
        String key = task + ":" + hash;
        if (key.equals(attentionKey)) return;
        if (!access.resume(task, hash)) attentionKey = key;
    }

    static RemoteNetworkStartup android(RemoteUpdatePlatform platform) {
        return new RemoteNetworkStartup(new Access() {
            public long elapsed() { return android.os.SystemClock.elapsedRealtime(); }
            public JSONObject reservation() throws Exception {
                try (RemoteMaintenance.Lease lease = RemoteMaintenance.acquire()) {
                    if (lease == null) throw new IOException("NETWORK_STARTUP_MAINTENANCE_BUSY");
                    File file = new File(RemoteMaintenance.ROOT, "repair.json");
                    if (!RemoteMaintenance.existsNoFollow(file)) return null;
                    requireFile(file);
                    return new JSONObject(RescueFiles.read(file, 4096));
                }
            }
            public boolean resume(String task, String hash) throws Exception {
                File apk = new File("/data/local/d31-remote/releases/" + hash + "/remote.apk");
                if (!RemoteMaintenance.existsNoFollow(apk)) {
                    save(new JSONObject().put("state", "NEEDS_ATTENTION")
                            .put("recovery_dispatch", "ORIGINAL_APK_UNAVAILABLE").put("network_write", false));
                    return false;
                }
                requireFile(apk);
                if (!hash.equals(RescueFiles.sha256(apk))) throw new IOException("NETWORK_STARTUP_APK_MISMATCH");
                if (platform.inspect(apk).getInt("versionCode") < 130) {
                    save(new JSONObject().put("state", "NEEDS_ATTENTION")
                            .put("recovery_dispatch", "ORIGINAL_APK_UNSUPPORTED").put("network_write", false));
                    return false;
                }
                ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/system/bin",
                        "--nice-name=d31-network-resume", RemoteNetworkAccess.class.getName(), "resume", task, hash)
                        .redirectErrorStream(true);
                builder.environment().put("CLASSPATH", apk.getPath());
                JSONObject reply = new JSONObject(NetworkProcess.collect(builder.start(), 6000));
                if (!Boolean.FALSE.equals(reply.opt("network_write")) || !reply.has("recovery_dispatch"))
                    throw new IOException("NETWORK_STARTUP_REPLY_INVALID");
                save(reply);
                return !"NEEDS_ATTENTION".equals(reply.optString("recovery_dispatch"));
            }
            private void save(JSONObject reply) throws Exception {
                RescueFiles.write(new File(RemoteUpdates.ROOT, "network-recovery.json"),
                        reply.put("time_ms", System.currentTimeMillis()).toString());
            }
        });
    }

    private static void requireFile(File file) throws Exception {
        StructStat stat = Os.lstat(file.getPath());
        if (stat.st_uid != 0 || (stat.st_mode & 0022) != 0 || stat.st_nlink != 1
                || !OsConstants.S_ISREG(stat.st_mode) || !file.getAbsoluteFile().equals(file.getCanonicalFile()))
            throw new IOException("NETWORK_STARTUP_FILE_INVALID");
    }
}
