package net.elfradio.d31bootstrap.management;

import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.json.JSONObject;

/** 真实API23适配；公共维护租约由根包桥提供，生产任务能力仍关闭。 */
public final class NetworkAndroidPlatform implements NetworkChangeTransaction.Platform {
    public interface Maintenance {
        AutoCloseable acquire(String taskId, boolean recovery) throws Exception;
        void requireReady(String taskId) throws Exception;
        void release(String taskId) throws Exception;
    }
    final File apk;
    final String hash;
    private final Maintenance maintenance;
    private final NetworkChangeTransaction engine;
    private final NetworkChangeTransaction.Store store;
    private String task;
    private boolean recovering;

    /** 此显式操作只准备持久目录，不切网；须在根入口单独授权，不由query偷偷创建。 */
    public static void prepareStorage() throws Exception { NetworkAndroidFiles.initialize(); }

    public NetworkAndroidPlatform(String apkSha256, Maintenance maintenance) throws Exception {
        NetworkAndroidFiles.requireDevice();
        if (maintenance == null) throw new IOException("NETWORK_MAINTENANCE_REQUIRED");
        this.hash = apkSha256; this.apk = NetworkAndroidFiles.apk(hash); this.maintenance = maintenance;
        NetworkAndroidFiles files = new NetworkAndroidFiles();
        files.beforeOpen(new File(NetworkAndroidFiles.ROOT, "wifi-enabled.journal"));
        store = new NetworkChangeTransactionJournal(NetworkAndroidFiles.ROOT, files);
        engine = new NetworkChangeTransaction(store, this,
                new NetworkChangeTransaction.Clock() {
                    public String bootId() throws Exception { return NetworkAndroidFiles.boot(); }
                    public long elapsedMillis() { return SystemClock.elapsedRealtime(); }
                });
    }
    public JSONObject begin(String taskId, boolean target, long windowMs) throws Exception {
        if (windowMs < 10000 || windowMs > 120000) throw new IOException("NETWORK_PRODUCTION_WINDOW_10000_120000");
        return complete(taskId, engine.begin(taskId, target, windowMs));
    }
    public JSONObject query(String taskId) throws Exception { return engine.query(taskId); }
    public JSONObject confirm(String taskId, NetworkChangeTransaction.Confirmation confirmation) throws Exception {
        return complete(taskId, engine.confirm(taskId, confirmation));
    }
    public JSONObject cancel(String taskId) throws Exception {
        recovering = true;
        try { return complete(taskId, engine.cancel(taskId)); } finally { recovering = false; }
    }
    JSONObject recover(String taskId) throws Exception {
        recovering = true;
        try { return engine.recover(taskId); } finally { recovering = false; }
    }
    JSONObject complete(String taskId, JSONObject result) throws Exception {
        if (NetworkChangeTransaction.settled(result.optString("state"))) releaseChecked(taskId, false);
        return result;
    }
    /** begin失败且确实尚无事务时收回预留；检查与release之间始终持Journal锁。 */
    public void releaseUnstarted(String taskId) throws Exception { releaseChecked(taskId, true); }
    private void releaseChecked(String taskId, boolean absentOnly) throws Exception {
        releaseChecked(store, maintenance, taskId, absentOnly);
    }
    static void releaseChecked(NetworkChangeTransaction.Store store, Maintenance maintenance, String taskId, boolean absentOnly) throws Exception {
        NetworkChangeTransaction.token(taskId);
        try (NetworkChangeTransaction.Store.Session session = store.lock()) {
            JSONObject records = NetworkChangeTransaction.records(session);
            if (absentOnly ? records.has(taskId) : !records.has(taskId)
                    || !NetworkChangeTransaction.settled(records.getJSONObject(taskId).getString("state")))
                throw new IOException("NETWORK_RELEASE_STATE_NOT_SETTLED");
            maintenance.release(taskId);
        }
    }
    @Override public AutoCloseable acquire(String taskId) throws Exception {
        AutoCloseable lease = maintenance.acquire(taskId, recovering);
        if (lease == null) throw new NetworkChangeTransaction.Busy();
        try {
            NetworkAndroidFiles.task(taskId); task = taskId;
            if (!recovering) {
                android.system.StructStatVfs space = android.system.Os.statvfs(NetworkAndroidFiles.ROOT.getPath());
                if (space.f_bavail < 4194304L / Math.max(1, space.f_frsize) + 1)
                    throw new IOException("NETWORK_ROLLBACK_SPACE_REQUIRED");
            }
        }
        catch (Exception error) { try { lease.close(); } finally { task = null; } throw error; }
        return () -> { try { lease.close(); } finally { task = null; } };
    }
    @Override public void requireRecoveryOwner(String taskId, String bootId, long deadlineElapsed) throws Exception {
        maintenance.requireReady(taskId);
        NetworkRecoveryGuard.launchAndConfirm(this, taskId, bootId, deadlineElapsed);
    }
    private File folder() throws Exception {
        if (task == null) throw new IOException("NETWORK_LEASE_REQUIRED");
        File folder = new File(NetworkAndroidFiles.ROOT, task); NetworkAndroidFiles.directory(folder); return folder;
    }
    /** 没有确认返回的写调用可能迟到；即使当前开关像原值，也不允许掩盖这种不确定性。 */
    private JSONObject requireCallSettled() throws Exception {
        File intentFile = new File(folder(), "call-intent.json");
        if (!NetworkAndroidFiles.exists(intentFile)) return null;
        JSONObject intent = NetworkAndroidFiles.read(intentFile);
        String id = intent.getString("id");
        if (!id.matches("[a-f0-9]{32}") || !task.equals(intent.getString("task_id"))
                || !hash.equals(intent.getString("apk_sha256"))) throw new IOException("NETWORK_CALL_ID_INVALID");
        // 真正换boot后旧Binder调用不可能继续执行；仍须实际读当前开关，不能宣布已恢复。
        if (callEndedByReboot(intent.getString("boot_id"), NetworkAndroidFiles.boot())) return null;
        JSONObject result = NetworkAndroidFiles.read(new File(folder(), "call-" + id + ".json"));
        if (!callSettled(intent, result)) throw new IOException("NETWORK_PRIOR_BINDER_CALL_UNKNOWN");
        return result;
    }
    static boolean callEndedByReboot(String oldBoot, String currentBoot) throws IOException {
        String pattern = "[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}";
        if (oldBoot == null || currentBoot == null || !oldBoot.matches(pattern) || !currentBoot.matches(pattern))
            throw new IOException("NETWORK_CALL_BOOT_UNKNOWN");
        return !oldBoot.equals(currentBoot);
    }
    static boolean callSettled(JSONObject intent, JSONObject result) throws Exception {
        return intent.getString("id").equals(result.getString("id"))
                && intent.getString("task_id").equals(result.getString("task_id"))
                && intent.getString("boot_id").equals(result.getString("boot_id"))
                && intent.getString("apk_sha256").equals(result.getString("apk_sha256"))
                && intent.getBoolean("target") == result.getBoolean("target")
                && Boolean.TRUE.equals(result.opt("setter_returned"))
                && result.opt("request_accepted") instanceof Boolean
                && result.opt("target_observed") instanceof Boolean
                && result.opt("target") instanceof Boolean;
    }
    interface ReceiptWriter { void save(JSONObject receipt) throws Exception; }
    static boolean pendingTarget(JSONObject receipt) throws IOException {
        if (receipt == null) return false;
        if (!(receipt.opt("request_accepted") instanceof Boolean)
                || !(receipt.opt("target_observed") instanceof Boolean)
                || !(receipt.opt("target") instanceof Boolean))
            throw new IOException("NETWORK_CALL_RECEIPT_INVALID");
        return Boolean.TRUE.equals(receipt.opt("request_accepted"))
                && !Boolean.TRUE.equals(receipt.opt("target_observed"));
    }
    /** Binder返回不代表异步队列已落实；首次目标观察必须落盘后才能提供可结案读数。 */
    static Boolean observeCall(JSONObject receipt, Boolean actual, ReceiptWriter writer) throws Exception {
        if (!pendingTarget(receipt)) return actual;
        if (actual == null || !actual.equals(receipt.opt("target"))) return null;
        JSONObject observed = new JSONObject(receipt.toString()).put("target_observed", true);
        writer.save(observed);
        return actual;
    }
    @Override public Boolean readWifiEnabled() throws Exception {
        JSONObject receipt = requireCallSettled();
        String output = invoke("get", task, hash);
        Boolean actual;
        if (output.equals("ENABLED")) actual = true;
        else if (output.equals("DISABLED")) actual = false;
        else if (output.equals("UNKNOWN")) actual = null;
        else throw new IOException("NETWORK_WIFI_REPLY_INVALID");
        return observeCall(receipt, actual, observed -> NetworkAndroidFiles.write(
                new File(folder(), "call-" + observed.getString("id") + ".json"), observed));
    }
    @Override public void setWifiEnabled(boolean target) throws Exception {
        requireNotInterrupted();
        if (pendingTarget(requireCallSettled())) throw new IOException("NETWORK_PRIOR_TARGET_PENDING");
        String id = UUID.randomUUID().toString().replace("-", "");
        JSONObject intent = new JSONObject().put("id", id).put("task_id", task).put("target", target)
                .put("apk_sha256", hash).put("boot_id", NetworkAndroidFiles.boot());
        NetworkAndroidFiles.write(new File(folder(), "call-intent.json"), intent);
        boolean reportedObserved = false;
        try { reportedObserved = invoke("set", task, hash, id, Boolean.toString(target)).equals("SET_OBSERVED"); }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw cancelled; }
        catch (Exception uncertain) { /* 子进程失败不证明Binder未返回；只接受精确持久回执与新鲜目标读回。 */ }
        requireNotInterrupted();
        reconcileSetter(intent, requireCallSettled(), reportedObserved, this::readWifiEnabled);
    }
    private static void requireNotInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("NETWORK_CALL_INTERRUPTED");
    }
    static void reconcileSetter(JSONObject intent, JSONObject receipt, boolean reportedObserved,
                                Callable<Boolean> durableReadback) throws Exception {
        requireNotInterrupted();
        if (receipt == null || !callSettled(intent, receipt) || !Boolean.TRUE.equals(receipt.opt("request_accepted")))
            throw new IOException("NETWORK_SET_RECEIPT_UNKNOWN");
        if (reportedObserved && Boolean.TRUE.equals(receipt.opt("target_observed"))) return;
        // 生产回调observeCall先同步target_observed，保存失败或仍pending都不能正常返回。
        Boolean actual = durableReadback.call();
        requireNotInterrupted();
        if (!Boolean.valueOf(intent.getBoolean("target")).equals(actual))
            throw new IOException("NETWORK_SET_TARGET_UNCONFIRMED");
    }
    String invoke(String... args) throws Exception {
        ArrayList<String> command = new ArrayList<>(Arrays.asList("/system/bin/app_process", "/system/bin",
                "--nice-name=d31-network-call", NetworkWifiCommand.class.getName()));
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("CLASSPATH", apk.getPath());
        return NetworkProcess.collect(builder.start(), 4000);
    }
}
