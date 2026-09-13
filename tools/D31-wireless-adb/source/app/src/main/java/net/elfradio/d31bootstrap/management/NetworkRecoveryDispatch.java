package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import org.json.JSONObject;

/** 公共启动入口的一次有界调度；不重放begin，不等待云，也不创建代替守护的线程。 */
public final class NetworkRecoveryDispatch {
    public interface Launcher {
        /** 在Journal锁内调用；独立进程必须先心跳后取Journal锁。 */
        void ensure(Binding original) throws Exception;
    }
    public static final class Binding {
        public final String taskId, apkSha256, bootId;
        public final boolean before, target;
        public final long startedElapsed, deadlineElapsed, lastElapsed;

        Binding(JSONObject job, String hash) throws Exception {
            if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IOException("NETWORK_APK_HASH_INVALID");
            taskId = job.getString("task_id"); apkSha256 = hash; bootId = job.getString("boot_id");
            before = job.getBoolean("before"); target = job.getBoolean("target");
            startedElapsed = job.getLong("started_elapsed"); deadlineElapsed = job.getLong("deadline_elapsed");
            lastElapsed = job.getLong("last_elapsed");
        }
        void requireMatch(JSONObject job) throws Exception {
            if (!taskId.equals(job.getString("task_id")) || !bootId.equals(job.getString("boot_id"))
                    || before != job.getBoolean("before") || target != job.getBoolean("target")
                    || startedElapsed != job.getLong("started_elapsed") || deadlineElapsed != job.getLong("deadline_elapsed"))
                throw new IOException("NETWORK_ORIGINAL_BINDING_MISMATCH");
        }
    }
    private final NetworkChangeTransaction.Store store;
    private final NetworkAndroidPlatform.Maintenance maintenance;
    private final String hash;

    public NetworkRecoveryDispatch(NetworkChangeTransaction.Store store,
                                   NetworkAndroidPlatform.Maintenance maintenance, String hash) {
        if (store == null || maintenance == null) throw new IllegalArgumentException("恢复依赖缺失");
        this.store = store; this.maintenance = maintenance; this.hash = hash;
    }

    /** task和hash必须来自受保护的network维护预留；缺失/损坏日志不能按新事务重建。 */
    public JSONObject resume(String task, Launcher launcher) throws Exception {
        NetworkChangeTransaction.token(task);
        try (NetworkChangeTransaction.Store.Session session = store.lock()) {
            JSONObject records = NetworkChangeTransaction.records(session);
            maintenance.requireReady(task);
            if (!records.has(task)) {
                maintenance.release(task);
                return new JSONObject().put("state", "ABSENT").put("recovery_dispatch", "UNSTARTED_RELEASED")
                        .put("restored", false).put("network_write", false);
            }
            JSONObject job = records.getJSONObject(task);
            Binding original = new Binding(job, hash);
            JSONObject result = NetworkChangeTransaction.report(job).put("network_write", false);
            if (NetworkChangeTransaction.settled(job.getString("state"))) {
                maintenance.release(task);
                return result.put("recovery_dispatch", "SETTLED_RELEASED");
            }
            launcher.ensure(original);
            return result.put("recovery_dispatch", "GUARD_READY");
        }
    }

    /** 快照供独立确认通道使用；获取后先释放Journal锁，再做有界的认证网络请求。 */
    public Binding awaiting(String task) throws Exception {
        NetworkChangeTransaction.token(task);
        try (NetworkChangeTransaction.Store.Session session = store.lock()) {
            JSONObject records = NetworkChangeTransaction.records(session);
            maintenance.requireReady(task);
            if (!records.has(task) || !"AWAITING_CONFIRM".equals(records.getJSONObject(task).getString("state"))) return null;
            return new Binding(records.getJSONObject(task), hash);
        }
    }
}
