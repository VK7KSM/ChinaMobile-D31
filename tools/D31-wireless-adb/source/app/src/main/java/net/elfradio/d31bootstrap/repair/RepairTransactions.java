package net.elfradio.d31bootstrap.repair;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.repair.RepairPlatform.FileState;

/** 调用方显式推进；没有后台线程、网络回执依赖或自动恢复入口。 */
public final class RepairTransactions {
    private final RepairJournal journal;
    private final RepairPlatform platform;

    public RepairTransactions(File journalRoot, RepairPlatform platform) throws Exception {
        this(journalRoot, platform, RepairFiles::writeNew);
    }
    RepairTransactions(File journalRoot, RepairPlatform platform, RepairJournal.Writer writer) throws Exception {
        if (platform == null) throw new IllegalArgumentException("必须注入明确的平台");
        this.platform = platform; journal = new RepairJournal(journalRoot, writer);
    }

    public JSONObject submit(RepairPlan plan, long now) throws Exception {
        checkInterrupted();
        try (RepairJournal.Guard ignored = journal.lock()) {
            File directory = new File(journal.root, plan.taskId);
            if (directory.exists()) {
                RepairJournal.Loaded old = journal.load(plan.taskId);
                if (!old.digest.equals(plan.sha256())) throw new IllegalArgumentException("同号修复方案参数冲突");
                return journal.snapshot(old);
            }
            File[] jobs = journal.root.listFiles(File::isDirectory);
            if (jobs == null || jobs.length >= 128) throw new IOException("事务记录需要独立归档");
            for (File existing : jobs) {
                String phase = journal.load(existing.getName()).state.getString("phase");
                if (!settled(phase)) throw new IOException("已有事务未完整结束或仍需人工处理");
            }
            RepairFiles.mkdir(directory);
            journal.writer.write(new File(directory, "plan.json"), plan.toJson().toString().getBytes(StandardCharsets.UTF_8));
            RepairJournal.Loaded job = journal.load(plan.taskId);
            return journal.append(job, job.state, now);
        }
    }

    public JSONObject query(String taskId) throws Exception {
        // 已提交记录只追加且不改写；执行持锁期间也能读取枚举时最新的完整快照。
        return journal.snapshot(journal.load(taskId));
    }

    /** 每次调用最多执行一次目标切换或回滚；返回后即可失联，任务仍可query。 */
    public JSONObject step(String taskId, long now) throws Exception {
        checkInterrupted();
        try (RepairJournal.Guard ignored = journal.lock()) {
            RepairJournal.Loaded job = journal.load(taskId);
            String phase = job.state.getString("phase");
            if (terminal(phase)) return journal.snapshot(job);
            job.state.remove("observations");
            try (RepairPlatform.Lease lease = platform.acquire(taskId)) {
                checkInterrupted();
                if (lease == null) return journal.snapshot(job).put("maintenance_busy", true);
                switch (phase) {
                    case "PENDING": return pending(job, now);
                    case "BACKUP": return backup(job, now);
                    case "STAGE": return stage(job, now);
                    case "SWITCH_READY": return switchReady(job, now);
                    case "SWITCH_INTENT": return recoverSwitch(job, now);
                    case "VERIFY": return verify(job, now);
                    case "ROLLBACK_READY": return rollback(job, now);
                    case "ROLLBACK_INTENT": return recoverRollback(job, now);
                    default: throw new IOException("无法识别事务阶段，禁止执行");
                }
            }
        }
    }

    public static boolean terminal(String phase) { return settled(phase) || "NEEDS_ATTENTION".equals(phase); }
    private static boolean settled(String phase) {
        return "SUCCEEDED".equals(phase) || "REJECTED".equals(phase) || "ROLLED_BACK".equals(phase);
    }

    private JSONObject pending(RepairJournal.Loaded job, long now) throws Exception {
        try { admission(job); checkTargets(job, -1); checkSpace(job.plan); }
        catch (Exception error) { return fail(job, "PRECHECK_FAILED", error, now); }
        return transition(job, "BACKUP", 0, "PRECHECK_PASSED", now);
    }

    private JSONObject backup(RepairJournal.Loaded job, long now) throws Exception {
        int index = job.state.getInt("index"); RepairPlan.Change change = job.plan.changes.get(index);
        File backup = artifact(job, "backup", index);
        try {
            admission(job); checkTargets(job, -1);
            if (!backup.exists()) platform.backup(change, backup);
            if (!RepairFiles.matches(backup, change.originalSha256, change.originalBytes)) throw new IOException("备份原像校验失败");
            if (!observe(job, change.path).matches(change.originalSha256, change.originalBytes)) throw new IOException("备份期间原像变化");
        } catch (Exception error) { return fail(job, "BACKUP_FAILED", error, now); }
        return index + 1 == job.plan.changes.size()
                ? transition(job, "STAGE", 0, "BACKUPS_VERIFIED", now)
                : transition(job, "BACKUP", index + 1, "BACKUP_VERIFIED", now);
    }

    private JSONObject stage(RepairJournal.Loaded job, long now) throws Exception {
        int index = job.state.getInt("index"); RepairPlan.Change change = job.plan.changes.get(index);
        File stage = artifact(job, "stage", index);
        try {
            admission(job); checkTargets(job, -1);
            if (!stage.exists()) platform.stage(change, stage);
            if (!RepairFiles.matches(stage, change.targetSha256, change.targetBytes)) throw new IOException("暂存目标校验失败");
        } catch (Exception error) { return fail(job, "STAGE_FAILED", error, now); }
        return index + 1 == job.plan.changes.size()
                ? transition(job, "SWITCH_READY", 0, "STAGING_VERIFIED", now)
                : transition(job, "STAGE", index + 1, "STAGE_VERIFIED", now);
    }

    private JSONObject switchReady(RepairJournal.Loaded job, long now) throws Exception {
        int index = job.state.getInt("index"); RepairPlan.Change change = job.plan.changes.get(index);
        File target = artifact(job, "stage", index);
        try {
            admission(job); checkTargets(job, index - 1);
            for (int i = 0; i < job.plan.changes.size(); i++) {
                RepairPlan.Change c = job.plan.changes.get(i);
                if (!RepairFiles.matches(artifact(job, "backup", i), c.originalSha256, c.originalBytes)) throw new IOException("切换前备份缺失或变化");
            }
            if (!RepairFiles.matches(target, change.targetSha256, change.targetBytes)) throw new IOException("切换前暂存内容变化");
        } catch (Exception error) { return fail(job, "SWITCH_PRECHECK_FAILED", error, now); }
        job.state.put("attempted", index);
        transition(job, "SWITCH_INTENT", index, "SWITCH_INTENT_SAVED", now);
        try {
            checkInterrupted();
            platform.replace(change.path, target, FileState.regular(change.originalSha256, change.originalBytes));
            if (!observe(job, change.path).matches(change.targetSha256, change.targetBytes)) throw new IOException("切换回读不符");
        } catch (Exception error) { return fail(job, "SWITCH_FAILED", error, now); }
        return advanceSwitch(job, index, "SWITCH_READBACK_VERIFIED", now);
    }

    private JSONObject recoverSwitch(RepairJournal.Loaded job, long now) throws Exception {
        int index = job.state.getInt("index"); RepairPlan.Change change = job.plan.changes.get(index);
        boolean targetPresent;
        try {
            admission(job);
            FileState actual = observe(job, change.path);
            targetPresent = actual.matches(change.targetSha256, change.targetBytes);
            if (targetPresent) checkTargets(job, index);
        } catch (Exception error) { return fail(job, "INTERRUPTED_SWITCH_UNCERTAIN", error, now); }
        if (targetPresent) return advanceSwitch(job, index, "INTERRUPTED_SWITCH_OBSERVED_TARGET", now);
        // 原像仍在表示切换未发生；不重发已持久化的切换意图。
        return fail(job, "INTERRUPTED_SWITCH_NOT_TARGET", null, now);
    }

    private JSONObject advanceSwitch(RepairJournal.Loaded job, int index, String reason, long now) throws Exception {
        return index + 1 == job.plan.changes.size() ? transition(job, "VERIFY", index, reason, now)
                : transition(job, "SWITCH_READY", index + 1, reason, now);
    }

    private JSONObject verify(RepairJournal.Loaded job, long now) throws Exception {
        try {
            admission(job); checkTargets(job, job.plan.changes.size() - 1);
            if (!platform.verify(job.plan)) throw new IOException("方案生效核验失败");
            checkTargets(job, job.plan.changes.size() - 1);
        } catch (Exception error) { return fail(job, "VERIFY_FAILED", error, now); }
        return transition(job, "SUCCEEDED", job.state.getInt("index"), "CONTENT_AND_EFFECT_VERIFIED", now);
    }

    private JSONObject fail(RepairJournal.Loaded job, String reason, Exception error, long now) throws Exception {
        propagateInterruption(error);
        job.state.put("failure", reason).put("exception_class", error == null ? "" : error.getClass().getSimpleName());
        int attempted = job.state.getInt("attempted");
        return transition(job, attempted < 0 ? "REJECTED" : "ROLLBACK_READY", Math.max(0, attempted), reason, now);
    }

    private JSONObject rollback(RepairJournal.Loaded job, long now) throws Exception {
        int index = job.state.getInt("index"); RepairPlan.Change change = job.plan.changes.get(index);
        FileState current;
        try {
            checkRollbackEnvironment(job.plan, change.path);
            current = observe(job, change.path);
        }
        catch (Exception error) { propagateInterruption(error); return advanceRollback(job, index, true, "ROLLBACK_READ_FAILED", now); }
        if (current.matches(change.originalSha256, change.originalBytes)) {
            boolean restored;
            try { restored = platform.verifyRestored(change); } catch (Exception failure) { propagateInterruption(failure); restored = false; }
            return advanceRollback(job, index, !restored, restored ? "ORIGINAL_ALREADY_PRESENT" : "ORIGINAL_METADATA_UNCONFIRMED", now);
        }
        if (!current.matches(change.targetSha256, change.targetBytes))
            return advanceRollback(job, index, true, "FOREIGN_CONTENT_PRESERVED", now);
        File backup = artifact(job, "backup", index);
        try {
            if (!RepairFiles.matches(backup, change.originalSha256, change.originalBytes)) throw new IOException("回滚原像缺失或损坏");
        } catch (Exception error) { propagateInterruption(error); return advanceRollback(job, index, true, "ROLLBACK_BACKUP_INVALID", now); }
        transition(job, "ROLLBACK_INTENT", index, "ROLLBACK_INTENT_SAVED", now);
        try { checkInterrupted(); platform.replace(change.path, backup, current); }
        catch (Exception uncertain) { propagateInterruption(uncertain); /* 普通异常先回读，不直接重复写入。 */ }
        return recoverRollback(job, now);
    }

    private JSONObject recoverRollback(RepairJournal.Loaded job, long now) throws Exception {
        int index = job.state.getInt("index"); RepairPlan.Change change = job.plan.changes.get(index);
        boolean restored;
        try {
            checkRollbackEnvironment(job.plan, change.path);
            restored = observe(job, change.path).matches(change.originalSha256, change.originalBytes)
                    && platform.verifyRestored(change);
        }
        catch (Exception unavailable) { propagateInterruption(unavailable); restored = false; }
        return advanceRollback(job, index, !restored, restored ? "ROLLBACK_READBACK_VERIFIED" : "ROLLBACK_UNCONFIRMED_NO_REPLAY", now);
    }

    private JSONObject advanceRollback(RepairJournal.Loaded job, int index, boolean attention, String reason, long now) throws Exception {
        job.state.put("attention", attention || job.state.getBoolean("attention"));
        if (index > 0) return transition(job, "ROLLBACK_READY", index - 1, reason, now);
        // 全部目标重新只读核验；未尝试文件绝不由恢复路径改写。
        for (int i = 0; i < job.plan.changes.size(); i++) {
            RepairPlan.Change c = job.plan.changes.get(i);
            try {
                if (!observe(job, c.path).matches(c.originalSha256, c.originalBytes)
                        || (i <= job.state.getInt("attempted") && !platform.verifyRestored(c))) job.state.put("attention", true);
            }
            catch (Exception unavailable) { propagateInterruption(unavailable); job.state.put("attention", true); }
        }
        return transition(job, job.state.getBoolean("attention") ? "NEEDS_ATTENTION" : "ROLLED_BACK", 0, reason, now);
    }

    private void admission(RepairJournal.Loaded job) throws Exception {
        checkInterrupted();
        RepairPlan plan = job.plan;
        if (!platform.approved(job.digest)) throw new SecurityException("方案摘要未获明确批准");
        if (!plan.deviceClass.equals(platform.deviceClass()) || !plan.buildFingerprint.equals(platform.buildFingerprint()))
            throw new SecurityException("设备类别或构建不匹配");
        Set<String> allowed = platform.allowedPaths();
        for (RepairPlan.Change c : plan.changes) if (!allowed.contains(c.path)) throw new SecurityException("目标不在受控文件允许清单");
        for (RepairPlan.Dependency d : plan.dependencies) {
            if (!allowed.contains(d.path) || !observe(job, d.path).matches(d.sha256, d.bytes))
                throw new SecurityException("依赖条件不符");
        }
    }

    private void checkRollbackEnvironment(RepairPlan plan, String path) throws Exception {
        checkInterrupted();
        if (!plan.deviceClass.equals(platform.deviceClass()) || !plan.buildFingerprint.equals(platform.buildFingerprint())
                || !platform.allowedPaths().contains(path)) throw new SecurityException("恢复目标环境或允许路径变化");
    }

    private void checkTargets(RepairJournal.Loaded job, int switchedThrough) throws Exception {
        for (int i = 0; i < job.plan.changes.size(); i++) {
            RepairPlan.Change c = job.plan.changes.get(i); FileState actual = observe(job, c.path);
            if (!actual.matches(i <= switchedThrough ? c.targetSha256 : c.originalSha256,
                    i <= switchedThrough ? c.targetBytes : c.originalBytes)) throw new IOException("现场内容变化，停止后续切换");
        }
    }

    private void checkSpace(RepairPlan plan) throws Exception {
        long required = 8L * 1024 * 1024;
        for (RepairPlan.Change c : plan.changes) required += c.originalBytes * 3 + c.targetBytes * 2;
        if (platform.availableBytes() < required) throw new IOException("事务备份、暂存及回滚空间不足");
    }

    private FileState observe(RepairJournal.Loaded job, String path) throws Exception {
        JSONArray observations = job.state.optJSONArray("observations");
        if (observations == null) { observations = new JSONArray(); job.state.put("observations", observations); }
        FileState actual;
        try { actual = platform.inspect(path); }
        catch (Exception failure) {
            observations.put(new JSONObject().put("path", path).put("kind", "READ_FAILED")
                    .put("exception_class", failure.getClass().getSimpleName()));
            throw failure;
        }
        observations.put(new JSONObject().put("path", path).put("kind", actual.kind.name())
                .put("sha256", actual.sha256).put("bytes", actual.bytes));
        return actual;
    }

    private File artifact(RepairJournal.Loaded job, String kind, int index) throws IOException {
        File directory = new File(job.directory, kind); RepairFiles.mkdir(directory);
        return new File(directory, index + ".bin");
    }

    private JSONObject transition(RepairJournal.Loaded job, String phase, int index, String reason, long now) throws Exception {
        checkInterrupted();
        job.state.put("phase", phase).put("index", index).put("reason", reason);
        JSONObject result = journal.append(job, job.state, now);
        job.state.remove("observations");
        return result;
    }

    /** 取消不构成写入失败证据；保留最后持久意图，后续显式step只读恢复判定。 */
    static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("REPAIR_INTERRUPTED");
    }
    private static void propagateInterruption(Exception error) throws InterruptedException {
        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        checkInterrupted();
    }
}
