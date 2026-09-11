package net.elfradio.d31bootstrap.repair;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class RepairTransactionsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    final class Fixture {
        final TemporaryRepairPlatform platform;
        final File journal;
        final RepairPlan plan;
        RepairTransactions engine;
        Fixture(int count) throws Exception {
            File root = temporary.newFolder().getCanonicalFile();
            platform = new TemporaryRepairPlatform(new File(root, "device"));
            journal = new File(root, "journal");
            List<RepairPlan.Change> changes = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String id = String.valueOf((char) ('a' + i));
                platform.write("rules/" + id + ".bin", bytes("old-" + id));
                Files.write(new File(platform.payloads, id).toPath(), bytes("new-" + id));
                changes.add(new RepairPlan.Change(id, "rules/" + id + ".bin", RepairFiles.sha256(bytes("old-" + id)), 5,
                        RepairFiles.sha256(bytes("new-" + id)), 5, id,
                        i == 0 ? Collections.emptyList() : Collections.singletonList(String.valueOf((char) ('a' + i - 1)))));
            }
            platform.write("deps/layout.bin", bytes("layout"));
            platform.write("untouched/private.bin", bytes("preserved"));
            plan = new RepairPlan("task-one", "rules-repair", "v1", "D31", "fixture-build",
                    RepairFiles.sha256(bytes("evidence-only")), changes,
                    Collections.singletonList(new RepairPlan.Dependency("deps/layout.bin", RepairFiles.sha256(bytes("layout")), 6)));
            platform.approvedDigest = plan.sha256(); engine = new RepairTransactions(journal, platform);
        }
        void submit() throws Exception { engine.submit(plan, 1); }
        void reopen() throws Exception { engine = new RepairTransactions(journal, platform); }
        String phase() throws Exception { return engine.query(plan.taskId).getJSONObject("state").getString("phase"); }
        void until(String wanted) throws Exception {
            for (int n = 0; n < 100; n++) {
                if (wanted.equals(phase())) return;
                assertFalse("在预期阶段前结束：" + phase(), RepairTransactions.terminal(phase()));
                engine.step(plan.taskId, 10 + n);
            }
            fail("未到达阶段");
        }
        void finish() throws Exception {
            for (int n = 0; n < 100 && !RepairTransactions.terminal(phase()); n++) engine.step(plan.taskId, 100 + n);
            assertTrue(RepairTransactions.terminal(phase()));
            assertArrayEquals(bytes("preserved"), Files.readAllBytes(platform.target("untouched/private.bin").toPath()));
        }
        void contents(boolean repaired) throws Exception {
            for (RepairPlan.Change c : plan.changes) assertTrue(platform.inspect(c.path).matches(
                    repaired ? c.targetSha256 : c.originalSha256, repaired ? c.targetBytes : c.originalBytes));
        }
        File artifact(String kind, int index) { return new File(journal, plan.taskId + "/" + kind + "/" + index + ".bin"); }
    }

    @Test public void succeedsInDependencyOrderAndKeepsQueryableImmutableHistory() throws Exception {
        Fixture f = new Fixture(2); f.submit(); f.finish();
        assertEquals("SUCCEEDED", f.phase()); f.contents(true);
        assertEquals(Arrays.asList("switch-a", "switch-b"), f.platform.switches);
        String result = f.engine.query(f.plan.taskId).toString();
        f.reopen(); assertEquals(result, f.engine.query(f.plan.taskId).toString());
        assertEquals(result, f.engine.submit(f.plan, 200).toString());
        f.engine.step(f.plan.taskId, 300); assertEquals(1, f.platform.countOf("switch-a"));
        assertTrue(RepairFiles.matches(f.artifact("backup", 0), f.plan.changes.get(0).originalSha256, 5));
        assertTrue(result.contains("SWITCH_INTENT")); assertTrue(result.contains("CONTENT_AND_EFFECT_VERIFIED"));
    }

    @Test public void rejectsConflictingTaskWithoutOverwritingPlan() throws Exception {
        Fixture f = new Fixture(1); f.submit();
        JSONObject other = f.plan.toJson().put("revision", "v2");
        assertThrows(IllegalArgumentException.class, () -> f.engine.submit(RepairPlan.fromJson(other), 2));
        assertEquals(f.plan.sha256(), f.engine.query(f.plan.taskId).getString("plan_sha256"));
        Fixture second = new Fixture(1);
        RepairPlan plan = RepairPlan.fromJson(second.plan.toJson().put("task_id", "another"));
        assertThrows(IOException.class, () -> f.engine.submit(plan, 3));
        assertEquals("PENDING", f.phase()); f.contents(false);
    }

    @Test public void evidenceAloneDoesNotAuthorizeAndBusyLockDoesNotMutate() throws Exception {
        Fixture f = new Fixture(1); f.submit(); f.platform.busy = true;
        assertTrue(f.engine.step(f.plan.taskId, 2).getBoolean("maintenance_busy"));
        assertEquals("PENDING", f.phase());
        f.platform.busy = false; f.platform.approvedDigest = "unapproved"; f.finish();
        assertEquals("REJECTED", f.phase()); f.contents(false);
        assertEquals(0, f.platform.countOf("backup-a"));
    }

    @Test public void rejectsBuildAllowlistDependencyTypeAndCapacityMismatch() throws Exception {
        for (String fault : Arrays.asList("build", "model", "path", "dependency", "space", "missing", "directory")) {
            Fixture f = new Fixture(1); f.submit();
            switch (fault) {
                case "build": f.platform.build = "different-build"; break;
                case "model": f.platform.model = "Other"; break;
                case "path": f.platform.allowed.remove("rules/a.bin"); break;
                case "dependency": f.platform.write("deps/layout.bin", bytes("changed")); break;
                case "space": f.platform.space = 0; break;
                case "missing": Files.delete(f.platform.target("rules/a.bin").toPath()); break;
                case "directory": File file = f.platform.target("rules/a.bin"); Files.delete(file.toPath()); assertTrue(file.mkdir()); break;
            }
            f.finish(); assertEquals(fault, "REJECTED", f.phase()); assertEquals(0, f.platform.countOf("backup-a"));
        }
    }

    @Test public void originalChangesAfterBackupArePreserved() throws Exception {
        Fixture f = new Fixture(1); f.submit(); f.until("STAGE");
        f.platform.write("rules/a.bin", bytes("external")); f.finish();
        assertEquals("REJECTED", f.phase());
        assertArrayEquals(bytes("external"), Files.readAllBytes(f.platform.target("rules/a.bin").toPath()));
        assertTrue(RepairFiles.matches(f.artifact("backup", 0), f.plan.changes.get(0).originalSha256, 5));
    }

    @Test public void rejectsBadPayloadAndBackupWithoutAnyTargetWrite() throws Exception {
        for (String bad : Arrays.asList("payload", "backup", "stage", "missing-backup")) {
            Fixture f = new Fixture(1); f.submit();
            if (bad.equals("payload")) Files.write(new File(f.platform.payloads, "a").toPath(), bytes("wrong"));
            else {
                f.until("SWITCH_READY");
                if (bad.equals("missing-backup")) Files.delete(f.artifact("backup", 0).toPath());
                else Files.write(f.artifact(bad, 0).toPath(), bytes("wrong"));
            }
            f.finish(); assertEquals(bad, "REJECTED", f.phase()); f.contents(false);
        }
    }

    @Test public void diskFailureDuringPreparationLeavesTargetsAndRecordsAvailableOffline() throws Exception {
        for (String event : Arrays.asList("before-backup-a", "before-stage-a")) {
            Fixture f = new Fixture(1); f.submit();
            f.platform.hook = actual -> { if (actual.equals(event)) throw new IOException("sensitive-adapter-message"); };
            f.finish(); assertEquals("REJECTED", f.phase()); f.contents(false);
            f.platform.busy = true;
            assertFalse(f.engine.query(f.plan.taskId).toString().contains("sensitive-adapter-message"));
            f.reopen(); assertEquals("REJECTED", f.phase());
        }
    }

    @Test public void failedEffectRollsBackInReverseOrderAndVerifiesOriginals() throws Exception {
        Fixture f = new Fixture(2); f.submit(); f.platform.healthy = false; f.finish();
        assertEquals("ROLLED_BACK", f.phase()); f.contents(false);
        assertEquals(Arrays.asList("switch-a", "switch-b", "restore-b", "restore-a"), f.platform.switches);
    }

    @Test public void missingBackupRestoresOtherFilesButCannotClaimRecovery() throws Exception {
        Fixture f = new Fixture(2); f.submit(); f.until("VERIFY");
        Files.delete(f.artifact("backup", 0).toPath()); f.platform.healthy = false; f.finish();
        assertEquals("NEEDS_ATTENTION", f.phase());
        assertTrue(f.platform.inspect("rules/a.bin").matches(f.plan.changes.get(0).targetSha256, 5));
        assertTrue(f.platform.inspect("rules/b.bin").matches(f.plan.changes.get(1).originalSha256, 5));
        assertEquals(0, f.platform.countOf("call-restore-a"));
        RepairPlan other = RepairPlan.fromJson(f.plan.toJson().put("task_id", "new-job"));
        assertThrows(IOException.class, () -> f.engine.submit(other, 500));
    }

    @Test public void foreignContentDuringSwitchIsNeverOverwrittenByRollback() throws Exception {
        Fixture f = new Fixture(2); f.submit();
        f.platform.hook = event -> {
            if (event.equals("after-switch-b")) { f.platform.write("rules/b.bin", bytes("external")); throw new IOException("changed"); }
        };
        f.finish(); assertEquals("NEEDS_ATTENTION", f.phase());
        assertTrue(f.platform.inspect("rules/a.bin").matches(f.plan.changes.get(0).originalSha256, 5));
        assertArrayEquals(bytes("external"), Files.readAllBytes(f.platform.target("rules/b.bin").toPath()));
        assertEquals(0, f.platform.countOf("call-restore-b"));
    }

    @Test public void rollbackFailureIsBoundedAndNeverRetried() throws Exception {
        Fixture f = new Fixture(1); f.submit(); f.platform.healthy = false;
        f.platform.hook = event -> { if (event.equals("before-restore-a")) throw new IOException("disk failure"); };
        f.finish(); assertEquals("NEEDS_ATTENTION", f.phase());
        f.reopen(); f.finish(); assertEquals(1, f.platform.countOf("call-restore-a"));
        assertTrue(f.artifact("backup", 0).isFile());
    }

    @Test public void crashesBeforeAndAfterEachFileOperationUseActualHashes() throws Exception {
        for (String operation : Arrays.asList("backup-a", "backup-b", "stage-a", "stage-b", "switch-a", "switch-b", "restore-a", "restore-b")) {
            for (String when : Arrays.asList("before-", "after-")) {
                Fixture f = new Fixture(2); f.submit(); f.platform.healthy = !operation.startsWith("restore");
                String crash = when + operation;
                f.platform.hook = event -> { if (event.equals(crash)) throw new TemporaryRepairPlatform.InterruptedProcess(); };
                assertThrows(crash, TemporaryRepairPlatform.InterruptedProcess.class, f::finish);
                f.platform.hook = event -> { }; f.reopen(); f.finish();
                if (operation.startsWith("restore") && when.equals("before-")) assertEquals(crash, "NEEDS_ATTENTION", f.phase());
                else if (operation.startsWith("restore") || (operation.startsWith("switch") && when.equals("before-"))) {
                    assertEquals(crash, "ROLLED_BACK", f.phase()); f.contents(false);
                } else { assertEquals(crash, "SUCCEEDED", f.phase()); f.contents(true); }
                for (String id : Arrays.asList("a", "b")) {
                    assertTrue(crash, f.platform.countOf("switch-" + id) <= 1);
                    assertTrue(crash, f.platform.countOf("call-restore-" + id) <= 1);
                    assertTrue(crash, f.platform.countOf("backup-" + id) <= 1);
                    assertTrue(crash, f.platform.countOf("stage-" + id) <= 1);
                }
            }
        }
    }

    @Test public void everyDurableBoundarySurvivesBeforeAndAfterCommitCrashes() throws Exception {
        Fixture reference = new Fixture(2); reference.submit(); reference.finish();
        int events = reference.engine.query(reference.plan.taskId).getInt("next_event");
        for (int cut = 0; cut < events; cut++) for (boolean after : new boolean[]{false, true}) {
            Fixture f = new Fixture(2); final int sequence = cut;
            f.engine = new RepairTransactions(f.journal, f.platform, (file, raw) -> {
                boolean selected = file.getName().equals(String.format(Locale.ROOT, "%06d.json", sequence));
                if (selected && !after) throw new TemporaryRepairPlatform.InterruptedProcess();
                RepairFiles.writeNew(file, raw);
                if (selected && after) throw new TemporaryRepairPlatform.InterruptedProcess();
            });
            assertThrows(TemporaryRepairPlatform.InterruptedProcess.class, () -> { f.submit(); f.finish(); });
            f.reopen(); f.finish();
            assertTrue(f.phase(), f.phase().equals("SUCCEEDED") || f.phase().equals("ROLLED_BACK"));
            f.contents(f.phase().equals("SUCCEEDED"));
            assertTrue(f.platform.countOf("switch-a") <= 1); assertTrue(f.platform.countOf("switch-b") <= 1);
        }
    }

    @Test public void failedIntentPersistenceCannotStartTargetMutation() throws Exception {
        Fixture f = new Fixture(1); f.submit(); f.until("SWITCH_READY");
        f.engine = new RepairTransactions(f.journal, f.platform, (file, raw) -> { throw new IOException("journal disk full"); });
        assertThrows(IOException.class, () -> f.engine.step(f.plan.taskId, 50));
        assertEquals(0, f.platform.countOf("call-switch-a")); f.contents(false);
        f.reopen(); f.finish(); assertEquals("SUCCEEDED", f.phase());
    }

    @Test public void queryingInsideHeldExecutionLeaseReturnsDurableIntentWithoutChangingIt() throws Exception {
        Fixture f = new Fixture(1); f.submit();
        f.platform.hook = event -> {
            if (event.equals("before-switch-a")) {
                JSONObject snapshot = f.engine.query(f.plan.taskId);
                assertEquals("SWITCH_INTENT", snapshot.getJSONObject("state").getString("phase"));
                snapshot.getJSONObject("state").put("phase", "SUCCEEDED");
                assertEquals("SWITCH_INTENT", f.engine.query(f.plan.taskId).getJSONObject("state").getString("phase"));
            }
        };
        f.finish(); assertEquals("SUCCEEDED", f.phase());
        JSONObject result = f.engine.query(f.plan.taskId);
        for (int i = 0; i < result.getInt("next_event"); i++) {
            File file = new File(f.journal, f.plan.taskId + "/" + String.format(Locale.ROOT, "%06d.json", i));
            assertEquals(new JSONObject(RepairFiles.text(file)).toString(), result.getJSONArray("events").getJSONObject(i).toString());
        }
    }

    @Test public void partialBackupOrStageAfterCrashIsPreservedAndNeverAccepted() throws Exception {
        for (String kind : Arrays.asList("backup", "stage")) {
            Fixture f = new Fixture(1); f.submit();
            f.platform.hook = event -> {
                if (event.equals("before-" + kind + "-a")) {
                    Files.write(f.artifact(kind, 0).toPath(), bytes("x"));
                    throw new TemporaryRepairPlatform.InterruptedProcess();
                }
            };
            assertThrows(TemporaryRepairPlatform.InterruptedProcess.class, f::finish);
            f.platform.hook = event -> { }; f.reopen(); f.finish();
            assertEquals("REJECTED", f.phase()); f.contents(false);
            assertArrayEquals(bytes("x"), Files.readAllBytes(f.artifact(kind, 0).toPath()));
            assertEquals(0, f.platform.countOf(kind + "-a"));
        }
    }

    @Test public void unreadableChangedTargetRemainsUnconfirmedAndNeverAutoRestartsRollback() throws Exception {
        Fixture f = new Fixture(1); f.submit();
        f.platform.hook = event -> { if (event.equals("after-switch-a")) f.platform.unreadable.add("rules/a.bin"); };
        f.finish(); assertEquals("NEEDS_ATTENTION", f.phase());
        assertTrue(f.engine.query(f.plan.taskId).toString().contains("READ_FAILED"));
        assertEquals(0, f.platform.countOf("call-restore-a"));
        f.platform.unreadable.clear(); f.reopen(); f.finish();
        assertEquals("NEEDS_ATTENTION", f.phase()); assertEquals(0, f.platform.countOf("call-restore-a"));
    }

    @Test public void changedBuildOrAllowedPathBlocksRollbackRatherThanTouchingNewEnvironment() throws Exception {
        for (boolean build : new boolean[]{false, true}) {
            Fixture f = new Fixture(1); f.submit(); f.until("VERIFY");
            if (build) f.platform.build = "new-environment";
            else f.platform.allowed.remove("rules/a.bin");
            f.finish(); assertEquals("NEEDS_ATTENTION", f.phase());
            assertEquals(0, f.platform.countOf("call-restore-a"));
        }
    }

    @Test public void interruptedRollbackCannotClaimSuccessInAnotherBuild() throws Exception {
        Fixture f = new Fixture(1); f.submit(); f.platform.healthy = false;
        f.platform.hook = event -> {
            if (event.equals("after-restore-a")) throw new TemporaryRepairPlatform.InterruptedProcess();
        };
        assertThrows(TemporaryRepairPlatform.InterruptedProcess.class, f::finish);
        assertEquals("ROLLBACK_INTENT", f.phase()); f.contents(false);
        f.platform.hook = event -> { }; f.platform.build = "another-build";
        f.reopen(); f.finish();
        assertEquals("NEEDS_ATTENTION", f.phase());
        assertEquals(1, f.platform.countOf("call-restore-a"));
    }

    @Test public void everyRollbackJournalBoundaryRecoversWithoutReplayingRestores() throws Exception {
        Fixture reference = new Fixture(2); reference.submit(); reference.platform.healthy = false; reference.finish();
        JSONObject result = reference.engine.query(reference.plan.taskId);
        for (int cut = 0; cut < result.getInt("next_event"); cut++) {
            String phase = result.getJSONArray("events").getJSONObject(cut).getJSONObject("state").getString("phase");
            if (!phase.startsWith("ROLLBACK") && !phase.equals("ROLLED_BACK")) continue;
            for (boolean after : new boolean[]{false, true}) {
                Fixture f = new Fixture(2); f.submit(); f.platform.healthy = false;
                final int sequence = cut;
                f.engine = new RepairTransactions(f.journal, f.platform, (file, raw) -> {
                    boolean selected = file.getName().equals(String.format(Locale.ROOT, "%06d.json", sequence));
                    if (selected && !after) throw new TemporaryRepairPlatform.InterruptedProcess();
                    RepairFiles.writeNew(file, raw);
                    if (selected && after) throw new TemporaryRepairPlatform.InterruptedProcess();
                });
                assertThrows(TemporaryRepairPlatform.InterruptedProcess.class, f::finish);
                f.reopen(); f.finish();
                if (after && phase.equals("ROLLBACK_INTENT")) assertEquals("NEEDS_ATTENTION", f.phase());
                else { assertEquals("ROLLED_BACK", f.phase()); f.contents(false); }
                for (String id : Arrays.asList("a", "b")) assertTrue(f.platform.countOf("call-restore-" + id) <= 1);
            }
        }
    }

    @Test public void tamperedOrMissingJournalCannotDriveActions() throws Exception {
        for (boolean remove : new boolean[]{false, true}) {
            Fixture f = new Fixture(1); f.submit(); f.until("STAGE");
            File record = new File(f.journal, f.plan.taskId + "/000000.json");
            if (remove) Files.delete(record.toPath()); else Files.write(record.toPath(), bytes("{}"));
            assertThrows(Exception.class, () -> f.engine.step(f.plan.taskId, 50));
            assertEquals(0, f.platform.countOf("call-switch-a")); f.contents(false);
        }
    }

    @Test public void schemeRejectsAbsoluteTraversalShellUnknownFieldsAndBadDependencies() throws Exception {
        Fixture f = new Fixture(1);
        for (String path : Arrays.asList("/system/file", "../file", "a/../file", "a;id", "C:/boot", "a\\b", "a//b")) {
            JSONObject plan = f.plan.toJson(); plan.getJSONArray("changes").getJSONObject(0).put("path", path);
            assertThrows(IllegalArgumentException.class, () -> RepairPlan.fromJson(plan));
        }
        assertThrows(IllegalArgumentException.class, () -> RepairPlan.fromJson(f.plan.toJson().put("shell", "id")));
        JSONObject cycle = f.plan.toJson(); cycle.getJSONArray("changes").getJSONObject(0).getJSONArray("after").put("a");
        assertThrows(IllegalArgumentException.class, () -> RepairPlan.fromJson(cycle));
        JSONObject number = f.plan.toJson(); number.getJSONArray("changes").getJSONObject(0).put("original_bytes", 2.5);
        assertThrows(IllegalArgumentException.class, () -> RepairPlan.fromJson(number));
        assertEquals(f.plan.sha256(), RepairPlan.fromJson(f.plan.toJson()).sha256());
    }
}
