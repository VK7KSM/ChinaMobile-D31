package net.elfradio.d31bootstrap;

import java.io.*;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.repair.*;

/** 原root_exec/8765入口执行同一事务；不启动后台扫描，不依赖Web回执推进。 */
public final class RemoteRepairCommand {
    static final File JOURNAL = new File("/data/local/d31-remote/repairs");
    static final File INPUT = new File("/data/local/d31-remote/repair-input");

    static JSONObject execute(RemoteRepairRequest request) throws Exception {
        // 查询只读原存档，不需要维护锁、联网或当前待修文件存在。
        if (request.operation.equals("query")) {
            if (!JOURNAL.isDirectory()) throw new FileNotFoundException("尚无修复事务存档");
            RepairTransactions transactions = new RepairTransactions(JOURNAL, queryPlatform());
            JSONObject snapshot = transactions.query(request.task); request.matches(snapshot);
            return receipt(snapshot);
        }
        try (RemoteMaintenance.Lease maintenance = RemoteMaintenance.acquire()) {
            if (maintenance == null) throw new IOException("维护正在进行，可继续查询原事务");
            RemoteMaintenance.requireRepairReady();
            RepairPlan plan = request.plan;
            RepairTransactions lookup = new RepairTransactions(JOURNAL, queryPlatform());
            if (plan == null) {
                JSONObject snapshot = lookup.query(request.task); request.matches(snapshot);
                plan = RepairPlan.fromJson(snapshot.getJSONObject("plan"));
            }
            File payload = new File(INPUT, request.task + "/artifacts");
            AndroidRepairPlatform platform = new AndroidRepairPlatform(JOURNAL, payload, plan,
                    task -> () -> { }, request.digest);
            RepairTransactions transactions = new RepairTransactions(JOURNAL, platform);
            JSONObject snapshot;
            if (request.operation.equals("submit")) snapshot = transactions.submit(plan, System.currentTimeMillis());
            else snapshot = transactions.query(request.task);
            request.matches(snapshot);
            String phase = snapshot.getJSONObject("state").getString("phase");
            if (!settled(phase)) RemoteMaintenance.reserve(RemoteMaintenance.ROOT, request.task, request.digest);
            if (!request.operation.equals("submit")) {
                long until = android.os.SystemClock.elapsedRealtime() + 90000;
                int limit = request.operation.equals("run") ? 96 : 1;
                for (int i = 0; i < limit && !RepairTransactions.terminal(phase); i++) {
                    snapshot = transactions.step(request.task, System.currentTimeMillis());
                    phase = snapshot.getJSONObject("state").getString("phase");
                    if (snapshot.optBoolean("maintenance_busy") || android.os.SystemClock.elapsedRealtime() >= until) break;
                }
            }
            if (settled(phase)) RemoteMaintenance.release(RemoteMaintenance.ROOT, request.task, request.digest);
            return receipt(snapshot);
        }
    }
    private static boolean settled(String phase) {
        return phase.equals("SUCCEEDED") || phase.equals("REJECTED") || phase.equals("ROLLED_BACK");
    }
    private static JSONObject receipt(JSONObject snapshot) throws Exception {
        return new JSONObject().put("schema", 1).put("task_id", snapshot.getString("task_id"))
                .put("plan_sha256", snapshot.getString("plan_sha256"))
                .put("state", snapshot.getJSONObject("state")).put("next_event", snapshot.getInt("next_event"))
                .put("journal", new File(JOURNAL, snapshot.getString("task_id")).getPath())
                .put("verification_scope", "FILE_CONTENT_AND_METADATA")
                .put("runtime_effect", "NOT_CHECKED").put("system_consistency", "NOT_ASSESSED");
    }
    private static RepairPlatform queryPlatform() {
        return new RepairPlatform() {
            public Lease acquire(String task) { throw new UnsupportedOperationException(); }
            public boolean approved(String digest) { return false; }
            public String deviceClass() { throw new UnsupportedOperationException(); }
            public String buildFingerprint() { throw new UnsupportedOperationException(); }
            public java.util.Set<String> allowedPaths() { return java.util.Collections.emptySet(); }
            public long availableBytes() { throw new UnsupportedOperationException(); }
            public FileState inspect(String path) { throw new UnsupportedOperationException(); }
            public void backup(RepairPlan.Change change, File destination) { throw new UnsupportedOperationException(); }
            public void stage(RepairPlan.Change change, File destination) { throw new UnsupportedOperationException(); }
            public void replace(String path, File content, FileState expected) { throw new UnsupportedOperationException(); }
            public boolean verify(RepairPlan plan) { throw new UnsupportedOperationException(); }
        };
    }
    public static void main(String[] args) {
        try {
            if (args.length != 1 || android.system.Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                    || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                    || !"hct6737t_66_m0".equals(android.os.Build.MODEL)) throw new SecurityException("仅限已授权D31维护入口");
            android.system.Os.umask(0077);
            System.out.println(execute(new RemoteRepairRequest(new JSONObject(args[0]))));
            System.exit(0);
        } catch (Exception failure) {
            System.err.println("修复请求未完成：" + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            System.exit(1);
        }
    }
    private RemoteRepairCommand() { }
}
