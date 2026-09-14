package net.elfradio.d31bootstrap.repair;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** 仅离线测试：复用原事务与原临时文件平台生成真实事件链。 */
public final class RepairLoopFixture {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        if (!root.mkdir()) throw new IllegalArgumentException("需要新测试目录");
        RepairPlan plan = RepairPlan.fromJson(new JSONObject(new String(Files.readAllBytes(new File(args[1]).toPath()), StandardCharsets.UTF_8)));
        TemporaryRepairPlatform platform = new TemporaryRepairPlatform(new File(root, "device"));
        platform.build = plan.buildFingerprint;
        platform.write(plan.changes.get(0).path, Files.readAllBytes(new File(args[2]).toPath()));
        Files.write(new File(platform.payloads, plan.changes.get(0).artifact).toPath(), Files.readAllBytes(new File(args[3]).toPath()));
        platform.approvedDigest = plan.sha256();
        platform.healthy = !args[4].equals("rollback");
        RepairTransactions tx = new RepairTransactions(new File(root, "journal"), platform);
        long now = Long.parseLong(args[5]);
        JSONObject result = tx.submit(plan, now++);
        if (args[4].equals("mismatch")) platform.write(plan.changes.get(0).path, "changed".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 96 && !RepairTransactions.terminal(result.getJSONObject("state").getString("phase")); i++)
            result = tx.step(plan.taskId, now++);
        Files.write(new File(root, "transaction.json").toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
