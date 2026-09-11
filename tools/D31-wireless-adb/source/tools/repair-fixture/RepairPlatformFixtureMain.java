package net.elfradio.d31bootstrap.repair;

import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** 独立测试jar入口；必须显式给出全新隔离根，不包含在生产APK中。 */
public final class RepairPlatformFixtureMain {
    private static final String PREFIX = "/data/local/d31-repair-fixtures";
    private static final class InterruptedProcess extends Error { }

    public static void main(String[] args) {
        AndroidRepairFileIo actual = null;
        File root = null;
        boolean created = false;
        int status = 1;
        String stage = "ARGUMENTS";
        JSONArray cases = new JSONArray();
        try {
            if (args.length != 1 || !args[0].matches(PREFIX + "/[A-Za-z0-9_-]{1,80}"))
                throw new IllegalArgumentException("必须指定全新/data/local/d31-repair-fixtures/<本次编号>");
            stage = "IO_INITIALIZATION";
            actual = new AndroidRepairFileIo();
            stage = "ENVIRONMENT"; actual.environment();
            Os.umask(0077);
            stage = "FIXTURE_PARENT"; actual.privateDirectory(new File(PREFIX));
            root = new File(args[0]);
            stage = "FIXTURE_ROOT";
            // 仅本次排他创建成功后才允许写失败回执，不碰已有失败目录。
            Os.mkdir(root.getPath(), 0700); created = true; actual.trustedDirectory(root);
            stage = "IO_PROBE";
            File probe = new File(root, "io-probe.bin");
            actual.writeNew(probe, bytes("xattr-probe"));
            RepairFileIo.Snapshot snapshot = actual.read(probe);
            cases.put(new JSONObject().put("case", "api23_io_probe").put("state", "PASS")
                    .put("metadata", snapshot.metadata.json()).put("sha256", snapshot.hash));
            for (String mode : new String[]{"success", "rollback", "interrupted", "rollback-metadata-failure"}) {
                stage = "CASE_" + mode;
                cases.put(run(actual, root, mode));
            }
            JSONObject result = new JSONObject().put("state", "PASS").put("scope", "ISOLATED_ANDROID_FILES")
                    .put("runtime_effect", "NOT_CHECKED").put("cases", cases);
            stage = "RESULT_PERSISTENCE"; actual.writeNew(new File(root, "result.json"), bytes(result.toString()));
            System.out.println(result.toString());
            status = 0;
        } catch (Throwable failure) {
            try {
                JSONObject result = new JSONObject().put("state", "FAIL").put("stage", stage)
                        .put("reason", failure.getClass().getSimpleName()).put("cases", cases);
                if (failure instanceof android.system.ErrnoException)
                    result.put("errno", ((android.system.ErrnoException) failure).errno);
                if (failure.getStackTrace().length > 0) result.put("at", failure.getStackTrace()[0].toString());
                if (actual != null && created) {
                    try { actual.writeNew(new File(root, "failure.json"), bytes(result.toString())); }
                    catch (Throwable persistence) { result.put("persistence", persistence.getClass().getSimpleName()); }
                } else result.put("persistence", "FIXTURE_ROOT_NOT_CREATED");
                System.out.println(result.toString());
            } catch (Throwable reporting) {
                System.out.println("{\"state\":\"FAIL\",\"stage\":\"FAILURE_REPORTING\"}");
            }
        }
        System.exit(status);
    }

    private static JSONObject run(AndroidRepairFileIo actual, File root, String mode) throws Exception {
        File directory = new File(root, mode); actual.privateDirectory(directory);
        File targets = new File(directory, "targets"), journal = new File(directory, "journal"), payloads = new File(directory, "payloads");
        actual.privateDirectory(targets); actual.privateDirectory(journal); actual.privateDirectory(payloads);
        File target = new File(targets, "start.sh"), untouched = new File(targets, "untouched.bin");
        actual.writeNew(target, bytes("original-fixture"));
        Os.chmod(target.getPath(), 0750);
        if (!target.setLastModified(1600000000000L)) throw new IOException("测试修改时间无法设置");
        actual.writeNew(untouched, bytes("must-be-preserved"));
        actual.writeNew(new File(payloads, "replacement"), bytes("replacement-fixture"));
        RepairFileIo.Snapshot original = actual.read(target), other = actual.read(untouched), replacement = actual.read(new File(payloads, "replacement"));
        RepairPlan plan = new RepairPlan("fixture-task", "static-file-fixture", "v1", "D31", actual.build(),
                RepairFiles.sha256(bytes("isolated-fixture-only")), Collections.singletonList(new RepairPlan.Change(
                    "start", "system-support/start.sh", original.hash, original.bytes, replacement.hash, replacement.bytes,
                    "replacement", Collections.emptyList())), Collections.emptyList());
        FaultIo io = new FaultIo(actual, mode);
        Map<String, File> paths = Collections.singletonMap("system-support/start.sh", target);
        AndroidRepairPlatform platform = new AndroidRepairPlatform(journal, payloads, plan, id -> () -> { }, plan.sha256(), paths, io);
        RepairTransactions engine = new RepairTransactions(journal, platform); engine.submit(plan, System.currentTimeMillis());
        if (mode.equals("interrupted")) {
            try { finish(engine, plan.taskId); throw new AssertionError("中断注入未触发"); }
            catch (InterruptedProcess expected) { }
            if (!phase(engine, plan.taskId).equals("SWITCH_INTENT")) throw new AssertionError("中断意图未落盘");
            platform = new AndroidRepairPlatform(journal, payloads, plan, id -> () -> { }, plan.sha256(), paths, io);
            engine = new RepairTransactions(journal, platform);
        }
        finish(engine, plan.taskId);
        String phase = phase(engine, plan.taskId);
        String wanted = mode.equals("rollback") ? "ROLLED_BACK" : mode.equals("rollback-metadata-failure") ? "NEEDS_ATTENTION" : "SUCCEEDED";
        if (!phase.equals(wanted)) throw new AssertionError("事务终态不符：" + phase);
        RepairFileIo.Snapshot result = actual.read(target);
        boolean restored = mode.startsWith("rollback");
        if (!result.content(restored ? original.hash : replacement.hash, restored ? original.bytes : replacement.bytes))
            throw new AssertionError("最终内容不符");
        boolean metadataMatches = result.metadata.same(original.metadata);
        if (metadataMatches == mode.equals("rollback-metadata-failure")) throw new AssertionError("元数据判定不符");
        if (!other.same(actual.read(untouched))) throw new AssertionError("非目标原件变化");
        if (io.replacements != (restored ? 2 : 1)) throw new AssertionError("发生重复替换");
        JSONObject report = new JSONObject().put("case", mode).put("state", "PASS").put("phase", phase)
                .put("writes", io.replacements).put("original", original.json()).put("actual", result.json())
                .put("metadata_matches", metadataMatches).put("non_target_preserved", true);
        actual.writeNew(new File(directory, "assertions.json"), bytes(report.toString()));
        return report;
    }
    private static void finish(RepairTransactions engine, String task) throws Exception {
        for (int i = 0; i < 80 && !RepairTransactions.terminal(phase(engine, task)); i++) engine.step(task, System.currentTimeMillis());
        if (!RepairTransactions.terminal(phase(engine, task))) throw new AssertionError("事务未在有界步骤结束");
    }
    private static String phase(RepairTransactions engine, String task) throws Exception {
        return engine.query(task).getJSONObject("state").getString("phase");
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    /** 只在真实原子切换完成后注入异常，不替代Android文件系统调用。 */
    private static final class FaultIo implements RepairFileIo {
        final RepairFileIo actual; final String mode; int replacements;
        FaultIo(RepairFileIo actual, String mode) { this.actual = actual; this.mode = mode; }
        public void environment() throws Exception { actual.environment(); }
        public String build() { return actual.build(); }
        public void privateDirectory(File file) throws Exception { actual.privateDirectory(file); }
        public void trustedDirectory(File file) throws Exception { actual.trustedDirectory(file); }
        public long available(File file) throws Exception { return actual.available(file); }
        public Snapshot read(File file) throws Exception { return actual.read(file); }
        public void copyNew(File source, File destination, Snapshot expected) throws Exception { actual.copyNew(source, destination, expected); }
        public void writeNew(File file, byte[] bytes) throws Exception { actual.writeNew(file, bytes); }
        public byte[] readRecord(File file) throws Exception { return actual.readRecord(file); }
        public void replace(File target, File content, Snapshot old, Snapshot expected, Metadata metadata) throws Exception {
            actual.replace(target, content, old, expected, metadata); replacements++;
            if (replacements == 1 && mode.equals("interrupted")) throw new InterruptedProcess();
            if (replacements == 1 && mode.startsWith("rollback")) throw new IOException("测试切换后异常");
            if (replacements == 2 && mode.equals("rollback-metadata-failure")) Os.chmod(target.getPath(), 0600);
        }
    }
    private RepairPlatformFixtureMain() { }
}
