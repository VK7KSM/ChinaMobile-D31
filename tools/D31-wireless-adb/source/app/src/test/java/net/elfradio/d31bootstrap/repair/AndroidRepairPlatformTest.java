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

public final class AndroidRepairPlatformTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static final class Crash extends Error { }

    final class Fixture {
        final File journal, payloads, target, sentinel;
        final HostRepairFileIo io = new HostRepairFileIo();
        final RepairPlan plan;
        final Map<String, File> mapping;
        final RepairFileIo.Metadata original = new RepairFileIo.Metadata(0, 0, 0750, 1600000000L, "u:object_r:system_data_file:s0");
        boolean locked, busy;
        String approval;
        AndroidRepairPlatform platform;
        RepairTransactions engine;
        Fixture() throws Exception {
            File root = temporary.newFolder().getCanonicalFile();
            journal = new File(root, "journal"); payloads = new File(root, "payloads");
            File targets = new File(root, "targets");
            io.privateDirectory(journal); io.privateDirectory(payloads); io.privateDirectory(targets);
            target = new File(targets, "start.sh"); sentinel = new File(targets, "not-a-target");
            Files.write(target.toPath(), bytes("original")); io.apply(target, original);
            Files.write(sentinel.toPath(), bytes("keep")); Files.write(new File(payloads, "new-start").toPath(), bytes("replacement"));
            mapping = Collections.singletonMap("system-support/start.sh", target);
            plan = new RepairPlan("task-one", "support-file", "v1", "D31", io.build(), RepairFiles.sha256(bytes("evidence")),
                    Collections.singletonList(new RepairPlan.Change("start", "system-support/start.sh", RepairFiles.sha256(bytes("original")), 8,
                            RepairFiles.sha256(bytes("replacement")), 11, "new-start", Collections.emptyList())), Collections.emptyList());
            approval = plan.sha256(); reopen();
        }
        void reopen() throws Exception {
            platform = new AndroidRepairPlatform(journal, payloads, plan, task -> {
                if (busy || locked) return null;
                locked = true; return () -> locked = false;
            }, approval, mapping, io);
            engine = new RepairTransactions(journal, platform);
        }
        void submit() throws Exception { engine.submit(plan, 1); }
        String phase() throws Exception { return engine.query(plan.taskId).getJSONObject("state").getString("phase"); }
        void until(String phase) throws Exception {
            for (int i = 0; i < 80; i++) {
                if (phase().equals(phase)) return;
                assertFalse(phase(), RepairTransactions.terminal(phase())); engine.step(plan.taskId, i + 2);
            }
            fail("未到达阶段");
        }
        void finish() throws Exception {
            for (int i = 0; i < 80 && !RepairTransactions.terminal(phase()); i++) engine.step(plan.taskId, i + 100);
            assertTrue(RepairTransactions.terminal(phase())); assertArrayEquals(bytes("keep"), Files.readAllBytes(sentinel.toPath()));
        }
        File record() { return new File(journal, "task-one/metadata/0.json"); }
        void original() throws Exception { assertArrayEquals(bytes("original"), Files.readAllBytes(target.toPath())); assertTrue(original.same(io.read(target).metadata)); }
    }

    @Test public void productionRegistryIsFixedAndDoesNotExposeRunningGuard() {
        Map<String, File> paths = AndroidRepairPlatform.productionPaths();
        assertEquals(1, paths.size());
        assertEquals("/data/local/d31-system-support/start.sh", paths.get("system-support/start.sh").getPath().replace('\\', '/'));
        assertThrows(UnsupportedOperationException.class, () -> paths.put("guard", new File("guard")));
    }
    @Test public void journalReloadedPlanRetainsMetadataAndHasNoExtraMaintenanceLock() throws Exception {
        Fixture f = new Fixture(); f.submit(); f.finish();
        assertEquals("SUCCEEDED", f.phase()); assertEquals(1, f.io.replacements);
        assertTrue(f.original.same(f.io.read(f.target).metadata)); assertTrue(f.record().isFile());
        f.reopen(); f.finish(); assertEquals(1, f.io.replacements);
        assertEquals(Collections.singleton("transaction.lock"), new HashSet<>(Arrays.asList(f.journal.list((dir, name) -> name.endsWith(".lock")))));
    }
    @Test public void leaseAndApprovalCannotBeBypassed() throws Exception {
        Fixture f = new Fixture();
        assertThrows(IllegalStateException.class, () -> f.platform.inspect("system-support/start.sh"));
        f.submit(); f.busy = true; assertTrue(f.engine.step(f.plan.taskId, 2).getBoolean("maintenance_busy"));
        f.busy = false; f.approval = RepairFiles.sha256(bytes("wrong")); f.reopen(); f.finish();
        assertEquals("REJECTED", f.phase()); assertEquals(0, f.io.replacements); f.original();
    }
    @Test public void metadataOnlyChangesBeforeSwitchArePreserved() throws Exception {
        Fixture f = new Fixture(); f.submit(); f.until("SWITCH_READY");
        f.io.apply(f.target, new RepairFileIo.Metadata(0, 0, 0640, f.original.modified, f.original.context)); f.finish();
        assertEquals("NEEDS_ATTENTION", f.phase()); assertEquals(0, f.io.replacements);
        assertEquals(0640, f.io.read(f.target).metadata.mode);
    }
    @Test public void exceptionAfterRenameRestoresOriginalMetadataAndContent() throws Exception {
        Fixture f = new Fixture(); f.submit();
        f.io.hook = (event, file) -> { if (event.equals("after-rename") && f.io.replacements == 1) throw new IOException("disk-failure"); };
        f.finish(); assertEquals("ROLLED_BACK", f.phase()); f.original(); assertEquals(2, f.io.replacements);
    }
    @Test public void crashAfterRenameConfirmsActualFileWithoutRepeatingWrite() throws Exception {
        Fixture f = new Fixture(); f.submit();
        f.io.hook = (event, file) -> { if (event.equals("after-rename")) throw new Crash(); };
        assertThrows(Crash.class, f::finish); assertEquals("SWITCH_INTENT", f.phase());
        f.io.hook = (event, file) -> { }; f.reopen(); f.finish();
        assertEquals("SUCCEEDED", f.phase()); assertEquals(1, f.io.replacements); assertTrue(f.original.same(f.io.read(f.target).metadata));
    }
    @Test public void crashBeforeRenameDoesNotReplayIntentOrLoseOriginal() throws Exception {
        Fixture f = new Fixture(); f.submit();
        f.io.hook = (event, file) -> { if (event.equals("before-rename")) throw new Crash(); };
        assertThrows(Crash.class, f::finish); f.io.hook = (event, file) -> { }; f.reopen(); f.finish();
        assertEquals("ROLLED_BACK", f.phase()); assertEquals(0, f.io.replacements); f.original();
    }
    @Test public void interruptedMetadataCaptureNeverAcceptsChangedOriginal() throws Exception {
        Fixture f = new Fixture(); f.submit();
        f.io.hook = (event, file) -> { if (event.equals("after-record")) throw new Crash(); };
        assertThrows(Crash.class, f::finish);
        f.io.hook = (event, file) -> { }; f.io.apply(f.target, new RepairFileIo.Metadata(0, 0, 0644, f.original.modified, f.original.context));
        f.reopen(); f.finish(); assertEquals("REJECTED", f.phase()); assertEquals(0, f.io.replacements);
    }
    @Test public void missingOrCorruptMetadataCannotDriveReplacement() throws Exception {
        for (boolean missing : new boolean[]{false, true}) {
            Fixture f = new Fixture(); f.submit(); f.until("SWITCH_READY");
            if (missing) Files.delete(f.record().toPath()); else Files.write(f.record().toPath(), bytes("{}"));
            f.finish(); assertEquals("NEEDS_ATTENTION", f.phase()); assertEquals(0, f.io.replacements); f.original();
        }
    }
    @Test public void rollbackDoesNotRequireOriginalPayloadToRemainAvailable() throws Exception {
        Fixture f = new Fixture(); f.submit();
        f.io.hook = (event, file) -> {
            if (event.equals("after-rename") && f.io.replacements == 1) {
                Files.delete(new File(f.payloads, "new-start").toPath()); Files.delete(f.payloads.toPath());
                throw new IOException("payload-no-longer-present");
            }
        };
        f.until("ROLLBACK_READY"); f.reopen(); f.finish(); assertEquals("ROLLED_BACK", f.phase()); f.original();
    }
    @Test public void restoredContentWithWrongMetadataNeverClaimsSuccessfulRollback() throws Exception {
        Fixture f = new Fixture(); f.submit();
        f.io.hook = (event, file) -> {
            if (!event.equals("after-rename")) return;
            if (f.io.replacements == 1) throw new IOException("fail-switch");
            f.io.apply(f.target, new RepairFileIo.Metadata(0, 0, 0600, f.original.modified, f.original.context));
        };
        f.finish(); assertEquals("NEEDS_ATTENTION", f.phase());
        f.reopen(); f.finish(); assertEquals(2, f.io.replacements); assertEquals(0600, f.io.read(f.target).metadata.mode);
    }
    @Test public void payloadHashFailureAndUnmappedTargetsNeverWriteFiles() throws Exception {
        Fixture f = new Fixture(); Files.write(new File(f.payloads, "new-start").toPath(), bytes("wrong"));
        f.submit(); f.finish(); assertEquals("REJECTED", f.phase()); f.original();
        JSONObject json = f.plan.toJson(); json.getJSONArray("changes").getJSONObject(0).put("path", "system-support/guard");
        RepairPlan outside = RepairPlan.fromJson(json);
        assertThrows(IOException.class, () -> new AndroidRepairPlatform(f.journal, f.payloads, outside,
                id -> () -> { }, outside.sha256(), f.mapping, f.io));
    }
}
