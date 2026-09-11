package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import java.util.Iterator;
import org.json.JSONObject;

/** wifi_enabled单一开关的事务核心；不注册任务、不创建线程、不调用网络接口。 */
public final class NetworkChangeTransaction {
    public interface Clock {
        String bootId() throws Exception;
        long elapsedMillis();
    }
    public interface Store {
        Session lock() throws Exception;
        interface Session extends AutoCloseable {
            /** 返回独立可变副本：{schema_version:1, records:{任务号:任务记录}}。损坏必须抛异常。 */
            JSONObject read() throws Exception;
            /** 仅接收内部records字典，不接收read的外层封装；实现加封装并持久化深拷贝。
             * 正常返回前必须同步落盘；抛异常可能已落盘，调用者须重新读取，不能继续平台写入。 */
            void save(JSONObject records) throws Exception;
            void close() throws Exception;
        }
    }
    public interface Platform {
        /** 必须覆盖外部设置/其它维护；跨步骤预留由集成方持久维护。 */
        AutoCloseable acquire(String taskId) throws Exception;
        /** 独立恢复owner必须已绑定本任务、前像及期限；内存定时器不满足此门。 */
        void requireRecoveryOwner(String taskId, String bootId, long deadlineElapsed) throws Exception;
        /** 只接受当前确定的ENABLED/DISABLED；过渡态、权限失败返回null或抛异常。 */
        Boolean readWifiEnabled() throws Exception;
        /** 必须有界完成或报错；返回不等于已生效。 */
        void setWifiEnabled(boolean enabled) throws Exception;
    }
    public interface Confirmation {
        /** 集成方验证新鲜、认证且绑定本任务/目标的管理链路确认，不能使用普通HTTP200。 */
        boolean verify(String taskId, boolean target, long startedElapsed, long nowElapsed) throws Exception;
    }

    private final Store store;
    private final Platform platform;
    private final Clock clock;

    public NetworkChangeTransaction(Store store, Platform platform, Clock clock) {
        if (store == null || platform == null || clock == null) throw new IllegalArgumentException("事务依赖缺失");
        this.store = store; this.platform = platform; this.clock = clock;
    }

    /** 同号同值同期限定幂等；不会重写网络或延长期限。不是Web任务入口。 */
    public JSONObject begin(String taskId, boolean target, long confirmWithinMs) throws Exception {
        token(taskId);
        if (confirmWithinMs < 1000 || confirmWithinMs > 120000) throw new IOException("确认期限须为1000..120000毫秒");
        try (Store.Session s = store.lock()) {
            JSONObject all = records(s);
            if (all.has(taskId)) {
                JSONObject old = all.getJSONObject(taskId);
                if (old.getBoolean("target") != target || old.getLong("window_ms") != confirmWithinMs)
                    throw new IOException("任务编号参数冲突");
                return report(old);
            }
            if (all.length() >= 128) throw new IOException("事务历史需独立归档，禁止自动丢弃幂等记录");
            for (Iterator<String> i = all.keys(); i.hasNext();)
                if (!settled(all.getJSONObject(i.next()).getString("state"))) throw new IOException("已有未解决网络事务");
            try (AutoCloseable owner = owner(taskId)) {
                String boot = boot(); long now = now();
                if (now > Long.MAX_VALUE - confirmWithinMs) throw new IOException("单调时钟溢出");
                Boolean before = platform.readWifiEnabled();
                if (before == null) throw new IOException("真实前像不可读，未执行修改");
                JSONObject job = new JSONObject().put("task_id", taskId).put("key", "wifi_enabled")
                        .put("target", target).put("before", before).put("window_ms", confirmWithinMs)
                        .put("boot_id", boot).put("started_elapsed", now).put("deadline_elapsed", now + confirmWithinMs)
                        .put("last_elapsed", now).put("state", "PREPARED").put("reason", "PREIMAGE_RECORDED")
                        .put("apply_attempted", false).put("rollback_attempted", false).put("rollback_returned", false).put("rollback_attempts", 0)
                        .put("original_verified", false).put("target_verified", false);
                all.put(taskId, job); s.save(all);
                if (before == target) return finish(s, all, job, "UNCHANGED", "NO_CHANGE", true, false);
                try {
                    platform.requireRecoveryOwner(taskId, boot, job.getLong("deadline_elapsed"));
                    if (!validTime(job)) return finish(s, all, job, "ABORTED", "DEADLINE_BEFORE_APPLY", false, false);
                    if (!before.equals(platform.readWifiEnabled()))
                        return finish(s, all, job, "NEEDS_ATTENTION", "PREIMAGE_CHANGED", false, false);
                } catch (Exception error) {
                    return finish(s, all, job, "ABORTED", "RECOVERY_OWNER_NOT_READY", false, false);
                }
                job.put("apply_attempted", true);
                transition(s, all, job, "APPLYING", "APPLY_INTENT");
                if (!validTime(job)) return rollback(s, all, job, "DEADLINE_BEFORE_SETTER");
                try { platform.setWifiEnabled(target); }
                catch (Exception error) { return rollback(s, all, job, "APPLY_FAILED"); }
                Boolean after = read();
                if (!Boolean.valueOf(target).equals(after) || !validTime(job))
                    return rollback(s, all, job, "APPLY_UNCONFIRMED_OR_EXPIRED");
                job.put("target_verified", true);
                return transition(s, all, job, "AWAITING_CONFIRM", "TARGET_READ_BACK");
            }
        }
    }

    public JSONObject confirm(String taskId, Confirmation confirmation) throws Exception {
        token(taskId);
        try (Store.Session s = store.lock()) {
            JSONObject all = records(s), job = existing(all, taskId);
            if (settled(job.getString("state"))) return report(job);
            try (AutoCloseable owner = owner(taskId)) {
                if (!validTime(job)) return rollback(s, all, job, "CONFIRM_DEADLINE_EXPIRED");
                if (!"AWAITING_CONFIRM".equals(job.getString("state"))) return report(job);
                boolean accepted = false;
                try {
                    accepted = confirmation != null && confirmation.verify(taskId, job.getBoolean("target"),
                            job.getLong("started_elapsed"), now());
                } catch (Exception ignored) { /* 确认来源失败不转成功，也不延长期限。 */ }
                if (!validTime(job)) return rollback(s, all, job, "CONFIRM_DEADLINE_EXPIRED");
                if (!accepted) return report(job);
                if (!Boolean.valueOf(job.getBoolean("target")).equals(read()))
                    return rollback(s, all, job, "TARGET_NO_LONGER_OBSERVED");
                if (!validTime(job)) return rollback(s, all, job, "CONFIRM_DEADLINE_EXPIRED");
                return finish(s, all, job, "CONFIRMED", "MANAGEMENT_CONFIRMATION", false, true);
            }
        }
    }

    /** 由独立宿主显式调用；同启动未到期只查询，换boot/时钟倒退立即走保守恢复。 */
    public JSONObject recover(String taskId) throws Exception { return resume(taskId, false); }
    public JSONObject cancel(String taskId) throws Exception { return resume(taskId, true); }

    private JSONObject resume(String taskId, boolean cancel) throws Exception {
        token(taskId);
        try (Store.Session s = store.lock()) {
            JSONObject all = records(s), job = existing(all, taskId);
            if (settled(job.getString("state"))) return report(job);
            try (AutoCloseable owner = owner(taskId)) {
                if (!cancel && "AWAITING_CONFIRM".equals(job.getString("state")) && validTime(job)) {
                    job.put("last_elapsed", now());
                    s.save(all);
                    return report(job);
                }
                return rollback(s, all, job, cancel ? "CANCELLED" : "RECOVERY_REQUIRED");
            }
        }
    }

    /** 只读日志，不读网络也不隐式执行回滚；不可读和不存在分开报告。 */
    public JSONObject query(String taskId) throws Exception {
        token(taskId);
        try (Store.Session s = store.lock()) {
            JSONObject all = records(s);
            return all.has(taskId) ? report(all.getJSONObject(taskId)) : new JSONObject().put("state", "ABSENT");
        } catch (Exception error) {
            return new JSONObject().put("state", "UNKNOWN").put("reason", "STORE_UNAVAILABLE")
                    .put("recovery_required", true).put("restored", false);
        }
    }

    private JSONObject rollback(Store.Session s, JSONObject all, JSONObject job, String reason) throws Exception {
        Boolean current = read();
        boolean before = job.getBoolean("before");
        if (current == null) return finish(s, all, job, "NEEDS_ATTENTION", "READBACK_UNKNOWN", false, false);
        if (current == before) return finish(s, all, job,
                job.getBoolean("rollback_returned") ? "ROLLED_BACK" : job.getBoolean("apply_attempted")
                        ? "ORIGINAL_OBSERVED" : "UNCHANGED", reason, true, false);
        // PREPARED未调用setter，不接管随后由第三方改变的开关。
        if (!job.getBoolean("apply_attempted"))
            return finish(s, all, job, "NEEDS_ATTENTION", "UNOWNED_CHANGE", false, false);
        if (job.getInt("rollback_attempts") >= 2)
            return finish(s, all, job, "NEEDS_ATTENTION", "ROLLBACK_RETRY_LIMIT", false, false);
        job.put("rollback_attempted", true).put("rollback_returned", false)
                .put("rollback_attempts", job.getInt("rollback_attempts") + 1);
        transition(s, all, job, "ROLLING_BACK", reason);
        try { platform.setWifiEnabled(before); job.put("rollback_returned", true); }
        catch (Exception ignored) { /* setter可能已执行，真实回读决定配置是否恢复。 */ }
        boolean verified = Boolean.valueOf(before).equals(read());
        return finish(s, all, job, verified ? job.getBoolean("rollback_returned") ? "ROLLED_BACK" : "ORIGINAL_OBSERVED" : "NEEDS_ATTENTION",
                verified ? "ORIGINAL_READ_BACK" : "ROLLBACK_UNCONFIRMED", verified, false);
    }

    private JSONObject finish(Store.Session s, JSONObject all, JSONObject job, String state, String reason,
                              boolean originalVerified, boolean targetVerified) throws Exception {
        job.put("original_verified", originalVerified).put("target_verified", targetVerified);
        return transition(s, all, job, state, reason);
    }
    private JSONObject transition(Store.Session s, JSONObject all, JSONObject job, String state, String reason) throws Exception {
        job.put("state", state).put("reason", reason);
        s.save(all);
        return report(job);
    }
    private boolean validTime(JSONObject job) {
        try {
            long now = now();
            boolean valid = boot().equals(job.getString("boot_id")) && now >= job.getLong("last_elapsed")
                    && now < job.getLong("deadline_elapsed");
            if (valid) job.put("last_elapsed", now);
            return valid;
        } catch (Exception error) { return false; }
    }
    private Boolean read() { try { return platform.readWifiEnabled(); } catch (Exception error) { return null; } }
    private String boot() throws Exception {
        String value = clock.bootId();
        if (value == null || value.isEmpty() || value.length() > 128) throw new IOException("启动标识不可用");
        return value;
    }
    private long now() throws IOException {
        long value = clock.elapsedMillis();
        if (value < 0) throw new IOException("单调时钟不可用");
        return value;
    }
    private AutoCloseable owner(String taskId) throws Exception {
        AutoCloseable owner = platform.acquire(taskId);
        if (owner == null) throw new IOException("维护所有权不可用");
        return owner;
    }
    static void token(String taskId) throws IOException {
        if (taskId == null || !taskId.matches("[a-zA-Z0-9-]{1,64}")) throw new IOException("任务编号无效");
    }
    private static boolean settled(String state) {
        return "CONFIRMED".equals(state) || "ROLLED_BACK".equals(state)
                || "UNCHANGED".equals(state) || "ORIGINAL_OBSERVED".equals(state) || "ABORTED".equals(state);
    }
    private static JSONObject existing(JSONObject all, String taskId) throws Exception {
        if (!all.has(taskId)) throw new IOException("任务不存在");
        return all.getJSONObject(taskId);
    }
    private static JSONObject records(Store.Session s) throws Exception {
        JSONObject root = s.read();
        if (root.getInt("schema_version") != 1) throw new IOException("事务日志版本不支持");
        JSONObject all = root.getJSONObject("records");
        for (Iterator<String> i = all.keys(); i.hasNext();) {
            String key = i.next(); token(key);
            JSONObject job = all.getJSONObject(key);
            if (!key.equals(job.getString("task_id")) || !"wifi_enabled".equals(job.getString("key"))
                    || !(job.get("before") instanceof Boolean) || !(job.get("target") instanceof Boolean)
                    || !java.util.Arrays.asList("PREPARED", "APPLYING", "AWAITING_CONFIRM", "ROLLING_BACK",
                        "CONFIRMED", "ROLLED_BACK", "UNCHANGED", "ORIGINAL_OBSERVED", "ABORTED", "NEEDS_ATTENTION").contains(job.getString("state")))
                throw new IOException("事务日志结构无效");
            for (String flag : new String[]{"apply_attempted", "rollback_attempted", "rollback_returned", "original_verified", "target_verified"})
                if (!(job.get(flag) instanceof Boolean)) throw new IOException("事务标志类型无效");
            long window = job.getLong("window_ms"), start = job.getLong("started_elapsed"), last = job.getLong("last_elapsed");
            if (window < 1000 || window > 120000 || start < 0 || start > Long.MAX_VALUE - window || last < start
                    || job.getLong("deadline_elapsed") != start + window || job.getInt("rollback_attempts") < 0
                    || job.getInt("rollback_attempts") > 2 || job.getString("boot_id").isEmpty())
                throw new IOException("事务期限或恢复计数无效");
            job.getString("reason");
        }
        return all;
    }
    static JSONObject report(JSONObject job) throws Exception {
        JSONObject out = new JSONObject();
        for (String key : new String[]{"task_id", "key", "target", "before", "state", "reason", "window_ms",
                "deadline_elapsed", "apply_attempted", "rollback_attempted", "rollback_returned", "rollback_attempts", "original_verified", "target_verified"})
            out.put(key, job.get(key));
        String state = job.getString("state");
        return out.put("restored", "ROLLED_BACK".equals(state) && job.getBoolean("original_verified"))
                .put("recovery_required", !settled(state)).put("connectivity", "NOT_CHECKED");
    }
}
