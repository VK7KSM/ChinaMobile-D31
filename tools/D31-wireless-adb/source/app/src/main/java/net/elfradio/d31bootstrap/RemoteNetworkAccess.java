package net.elfradio.d31bootstrap;

import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import net.elfradio.d31bootstrap.management.NetworkAndroidPlatform;
import net.elfradio.d31bootstrap.management.NetworkChangeTransaction;
import net.elfradio.d31bootstrap.management.NetworkRecoveryGuard;
import org.json.JSONObject;

/** 网络事务复用旧监督也识别的维护预留；恢复不依赖云端或主核心健康。 */
public final class RemoteNetworkAccess implements NetworkAndroidPlatform.Maintenance {
    private static final File RESERVATION = new File(RemoteMaintenance.ROOT, "repair.json");
    private final String digest;

    private RemoteNetworkAccess(String digest) { this.digest = digest; }

    static void validate(String[] args) throws IOException {
        if (args == null || args.length < 2) throw new IOException("NETWORK_ARGUMENTS_INVALID");
        if (args.length == 2 && "prepare".equals(args[0])) { hash(args[1]); return; }
        if (args.length < 3 || !args[1].matches("[A-Za-z0-9-]{1,64}")) throw new IOException("NETWORK_ARGUMENTS_INVALID");
        hash(args[2]);
        if (args.length == 3 && ("query".equals(args[0]) || "cancel".equals(args[0]) || "resume".equals(args[0]))) return;
        if (args.length == 5 && "begin".equals(args[0])
                && ("true".equals(args[3]) || "false".equals(args[3]))
                && args[4].matches("[1-9][0-9]{4,5}")
                && Long.parseLong(args[4]) <= 120000) return;
        if (args.length == 6 && "guard".equals(args[0]) && args[3].matches("[a-f0-9]{32}")
                && args[4].matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                && args[5].matches("[0-9]{1,18}")) return;
        throw new IOException("NETWORK_ARGUMENTS_INVALID");
    }
    private static void hash(String digest) throws IOException {
        if (digest == null || !digest.matches("[a-f0-9]{64}")) throw new IOException("NETWORK_APK_HASH_INVALID");
    }
    static JSONObject reservation(String task, String digest) throws Exception {
        if (task == null || !task.matches("[A-Za-z0-9-]{1,64}")) throw new IOException("NETWORK_TASK_INVALID");
        hash(digest);
        return new JSONObject().put("task_id", "network-" + task).put("plan_sha256", digest)
                .put("kind", "network").put("network_task", task).put("schema_version", 1);
    }
    static void requireOwner(JSONObject record, String task, String digest) throws Exception {
        JSONObject expected = reservation(task, digest);
        if (!expected.getString("task_id").equals(record.optString("task_id"))
                || !digest.equals(record.optString("plan_sha256")) || !"network".equals(record.optString("kind"))
                || !task.equals(record.optString("network_task")) || record.optInt("schema_version") != 1)
            throw new IOException("NETWORK_RESERVATION_MISMATCH");
    }
    private static JSONObject readReservation() throws Exception {
        if (!RESERVATION.getAbsoluteFile().equals(RESERVATION.getCanonicalFile()))
            throw new IOException("NETWORK_RESERVATION_PATH_INVALID");
        return new JSONObject(RescueFiles.read(RESERVATION, 4096));
    }
    private static void syncReservationDirectory() throws Exception {
        FileDescriptor fd = Os.open(RemoteMaintenance.ROOT.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw new IOException("NETWORK_MAINTENANCE_DIRECTORY_INVALID");
            Os.fsync(fd);
        } finally { Os.close(fd); }
    }
    private void current() throws Exception {
        RemoteMaintenance.requireRepairReady();
        JSONObject active = new JSONObject(RescueFiles.read(RemoteUpdatePlatform.ACTIVE, 4096));
        if (!digest.equals(active.getString("sha256")) || active.getInt("versionCode") != BuildConfig.VERSION_CODE
                || !active.getString("path").equals(System.getenv("CLASSPATH")))
            throw new IOException("NETWORK_ACTIVE_APK_MISMATCH");
    }
    @Override public AutoCloseable acquire(String task, boolean recovery) throws Exception {
        reservation(task, digest);
        RemoteMaintenance.Lease lease = RemoteMaintenance.acquire();
        if (lease == null) return null;
        try {
            if (RESERVATION.exists()) {
                requireOwner(readReservation(), task, digest);
                if (!recovery) current();
            } else {
                if (recovery) throw new IOException("NETWORK_RECOVERY_RESERVATION_MISSING");
                RemoteMaintenance.requireUnreserved(); current();
                RescueFiles.write(RESERVATION, reservation(task, digest).toString());
                Os.chmod(RESERVATION.getPath(), 0600); syncReservationDirectory();
            }
            return lease;
        } catch (Exception error) {
            try { lease.close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    @Override public void requireReady(String task) throws Exception {
        // 发起方持维护锁等待守护握手；守护只读校验，不反向等待这把锁。
        requireOwner(readReservation(), task, digest);
    }
    @Override public void release(String task) throws Exception {
        try (RemoteMaintenance.Lease lease = RemoteMaintenance.acquire()) {
            if (lease == null) throw new NetworkChangeTransaction.Busy();
            if (!RESERVATION.exists()) return;
            requireOwner(readReservation(), task, digest);
            if (!RESERVATION.delete()) throw new IOException("NETWORK_RESERVATION_RELEASE_FAILED");
            syncReservationDirectory();
        }
    }
    public static void main(String[] args) {
        int exit = 1;
        try {
            validate(args);
            if (Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                    || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                    || !"hct6737t_66_m0".equals(android.os.Build.MODEL)) throw new IOException("NETWORK_DEVICE_INVALID");
            Os.umask(0077);
            String digest = "prepare".equals(args[0]) ? args[1] : args[2];
            RemoteNetworkAccess access = new RemoteNetworkAccess(digest);
            JSONObject result;
            if ("prepare".equals(args[0])) {
                try (RemoteMaintenance.Lease lease = RemoteMaintenance.acquire()) {
                    if (lease == null) throw new IOException("NETWORK_MAINTENANCE_BUSY");
                    RemoteMaintenance.requireUnreserved(); access.current(); NetworkAndroidPlatform.prepareStorage();
                    result = new JSONObject().put("state", "STORAGE_PREPARED").put("network_changed", false);
                }
            } else if ("guard".equals(args[0])) {
                access.requireReady(args[1]); result = NetworkRecoveryGuard.run(args, access);
            } else {
                NetworkAndroidPlatform platform = new NetworkAndroidPlatform(digest, access);
                if ("resume".equals(args[0])) {
                    try { result = platform.resumeRecovery(args[1]); }
                    catch (IOException attention) {
                        if (!"NETWORK_RECOVERY_ATTENTION_REQUIRED".equals(attention.getMessage())) throw attention;
                        result = new JSONObject().put("state", "NEEDS_ATTENTION")
                                .put("recovery_dispatch", "NEEDS_ATTENTION").put("restored", false);
                    }
                }
                else if ("query".equals(args[0])) result = platform.query(args[1]);
                else if ("cancel".equals(args[0])) {
                    if ("ABSENT".equals(platform.query(args[1]).optString("state"))) {
                        platform.releaseUnstarted(args[1]);
                        result = new JSONObject().put("state", "ABSENT_CANCELLED").put("restored", false)
                                .put("network_changed", false).put("reservation_released", true);
                    } else result = platform.cancel(args[1]);
                }
                else {
                    try { result = platform.begin(args[1], Boolean.parseBoolean(args[3]), Long.parseLong(args[4])); }
                    catch (Exception failed) {
                        // 确认从未落盘才释放；日志损坏或已有意图时保留预留供恢复。
                        try { platform.releaseUnstarted(args[1]); }
                        catch (Exception cleanup) { failed.addSuppressed(cleanup); }
                        throw failed;
                    }
                }
            }
            System.out.println(result.put("network_write", false).put("version_code", BuildConfig.VERSION_CODE));
            exit = 0;
        } catch (Exception error) {
            // 原异常可能带系统路径或私有参数；只返回固定状态，不泄漏任意异常正文。
            String reason = error.getMessage();
            if (reason == null || !reason.matches("NETWORK_[A-Z0-9_]{1,80}")) reason = "NETWORK_COMMAND_FAILED";
            System.out.println("{\"state\":\"COMMAND_FAILED\",\"network_write\":false,\"restored\":false,\"reason\":\"" + reason + "\"}");
        }
        System.exit(exit);
    }
}
