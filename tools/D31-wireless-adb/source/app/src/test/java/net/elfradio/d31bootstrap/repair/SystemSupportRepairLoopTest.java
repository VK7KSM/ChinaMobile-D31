package net.elfradio.d31bootstrap.repair;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import net.elfradio.d31bootstrap.diagnostics.collection.SystemSupportConfigurationTest;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static net.elfradio.d31bootstrap.diagnostics.collection.SystemSupportConfigurationTest.*;

/** 既有事务在宿主真实临时文件上替换、回滚，再以真实采集器回放读回字节。 */
public class SystemSupportRepairLoopTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String LOGICAL = "system-support/start.sh";

    private static void save(File root, String name, JSONObject json) throws Exception {
        Files.write(new File(root, name).toPath(), json.toString(2).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }

    private static JSONObject run(File root, boolean rollback, boolean mismatch) throws Exception {
        byte[] original = wrongRoot(), target = script();
        TemporaryRepairPlatform platform = new TemporaryRepairPlatform(new File(root, "device"));
        platform.write(LOGICAL, original);
        Files.write(new File(platform.payloads, "script").toPath(), target);
        JSONObject before = collect(Files.readAllBytes(platform.target(LOGICAL).toPath()));
        JSONObject initialReport = report(before);
        assertEquals("DIFFERENT", relation(initialReport));
        RepairPlan plan = new RepairPlan("fixture-root-repair", "system-support-root", "v1", "D31", "fixture-build",
                RepairFiles.sha256(before.toString().getBytes(StandardCharsets.UTF_8)),
                Collections.singletonList(new RepairPlan.Change("script", LOGICAL, RepairFiles.sha256(original), original.length,
                        RepairFiles.sha256(target), target.length, "script", Collections.emptyList())), Collections.emptyList());
        platform.approvedDigest = plan.sha256();
        RepairTransactions transactions = new RepairTransactions(new File(root, "journal"), platform);
        transactions.submit(plan, 1);
        if (mismatch) platform.write(LOGICAL, "changed-after-plan".getBytes(StandardCharsets.UTF_8));
        platform.healthy = !rollback;
        JSONObject transaction = transactions.query(plan.taskId);
        for (int i = 0; i < 96 && !RepairTransactions.terminal(transaction.getJSONObject("state").getString("phase")); i++)
            transaction = transactions.step(plan.taskId, i + 2);
        String phase = transaction.getJSONObject("state").getString("phase");
        assertEquals(mismatch ? "REJECTED" : rollback ? "ROLLED_BACK" : "SUCCEEDED", phase);
        JSONObject after = collect(Files.readAllBytes(platform.target(LOGICAL).toPath()));
        JSONObject finalReport = report(after);
        assertEquals(mismatch ? "UNKNOWN" : rollback ? "DIFFERENT" : "SAME", relation(finalReport));
        if (!mismatch) assertArrayEquals(rollback ? original : target, Files.readAllBytes(platform.target(LOGICAL).toPath()));
        else assertEquals(0, platform.switches.size());
        assertEquals(Collections.singleton(LOGICAL), platform.allowedPaths());
        assertFalse(finalReport.getBoolean("repairPlanGenerated"));
        assertEquals("NOT_PERFORMED", finalReport.getString("runtimeVerification"));
        save(root, "before-manifest.json", before); save(root, "after-manifest.json", after);
        save(root, "firmware-expectation.json", expected());
        save(root, "before-coverage.json", initialReport); save(root, "after-coverage.json", finalReport);
        save(root, "plan.json", plan.toJson()); save(root, "transaction.json", transaction);
        JSONObject result = new JSONObject().put("phase", phase).put("beforePair", relation(initialReport))
                .put("afterPair", relation(finalReport)).put("runtimeVerification", "NOT_PERFORMED")
                .put("systemConsistency", "NOT_ASSESSED").put("inputKind", "HOST_FIXTURE_WITH_FROZEN_SCRIPT_BYTES");
        save(root, "result.json", result);
        return result;
    }

    @Test public void existingFileRepairRemovesCollectedRootDifference() throws Exception { run(temporary.newFolder(), false, false); }
    @Test public void verificationFailureRollsBackAndRestoresOriginalSemanticValue() throws Exception { run(temporary.newFolder(), true, false); }
    @Test public void changedPreimageRejectsWithoutClaimingSemanticRepair() throws Exception { run(temporary.newFolder(), false, true); }

    public static void main(String[] args) throws Exception {
        File output = new File(args[0]);
        if (!output.mkdir()) throw new IllegalArgumentException("输出必须是全新目录");
        for (String name : new String[]{"success", "rollback", "preimage-mismatch"}) {
            File child = new File(output, name);
            if (!child.mkdir()) throw new IllegalStateException("无法创建证据目录");
            System.out.println(name + ": " + run(child, name.equals("rollback"), name.equals("preimage-mismatch")));
        }
    }
}
