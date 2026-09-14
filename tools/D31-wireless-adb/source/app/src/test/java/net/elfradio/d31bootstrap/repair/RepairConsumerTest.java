package net.elfradio.d31bootstrap.repair;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.jar.*;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 隔离配置消费者实际读取目标JAR；不加载类、不执行启动脚本或系统业务。 */
public final class RepairConsumerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String PATH = "startup-handover/handover.jar";
    private static byte[] archive(String mode) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("fixture.properties"));
            jar.write(("mode=" + mode + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }
    static class ConfigurationConsumer implements RepairConsumer.Verifier {
        int calls;
        String observed;
        public String id() { return "isolated-jar-configuration-v1"; }
        public boolean verify(RepairPlan plan, RepairConsumer.Files files) throws Exception {
            calls++;
            try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(files.read(PATH, 65536)))) {
                JarEntry entry = jar.getNextJarEntry();
                if (entry == null || !"fixture.properties".equals(entry.getName())) return false;
                Properties configuration = new Properties(); configuration.load(jar);
                observed = configuration.getProperty("mode");
                return configuration.size() == 1 && "standby".equals(observed) && jar.getNextJarEntry() == null;
            }
        }
    }
    final class Fixture {
        final File journal, payloads, target;
        final HostRepairFileIo io = new HostRepairFileIo();
        final RepairPlan plan;
        final byte[] original = archive("offline");
        final byte[] targetBytes;
        final Map<String, File> mapping;
        RepairTransactions engine;
        Fixture(String mode, RepairConsumer.Verifier consumer) throws Exception {
            File root = temporary.newFolder().getCanonicalFile();
            journal = new File(root, "journal"); payloads = new File(root, "payloads");
            File targets = new File(root, "targets");
            io.privateDirectory(journal); io.privateDirectory(payloads); io.privateDirectory(targets);
            target = new File(targets, "handover.jar"); targetBytes = archive(mode);
            Files.write(target.toPath(), original); Files.write(new File(payloads, "jar").toPath(), targetBytes);
            mapping = Collections.singletonMap(PATH, target);
            plan = new RepairPlan("consumer-fixture", "product-files", "v1", "D31", io.build(),
                    RepairFiles.sha256(new byte[]{1}), Collections.singletonList(new RepairPlan.Change(
                    "handover", PATH, RepairFiles.sha256(original), original.length,
                    RepairFiles.sha256(targetBytes), targetBytes.length, "jar", Collections.emptyList())), Collections.emptyList());
            reopen(consumer); engine.submit(plan, 1);
        }
        void reopen(RepairConsumer.Verifier consumer) throws Exception {
            engine = new RepairTransactions(journal, new AndroidRepairPlatform(journal, payloads, plan,
                    id -> () -> { }, plan.sha256(), mapping, io, consumer));
        }
        JSONObject state() throws Exception { return engine.query(plan.taskId).getJSONObject("state"); }
        void until(String phase) throws Exception {
            for (int i = 0; i < 50; i++) {
                if (phase.equals(state().getString("phase"))) return;
                assertFalse(RepairTransactions.terminal(state().getString("phase")));
                engine.step(plan.taskId, i + 2);
            }
            fail("阶段未到达");
        }
        void finish() throws Exception {
            for (int i = 0; i < 50 && !RepairTransactions.terminal(state().getString("phase")); i++)
                engine.step(plan.taskId, i + 100);
            assertTrue(RepairTransactions.terminal(state().getString("phase")));
        }
    }
    @Test public void consumesActualSwitchedArchiveAndPersistsReportWithoutPayload() throws Exception {
        ConfigurationConsumer consumer = new ConfigurationConsumer();
        Fixture f = new Fixture("standby", consumer); f.until("VERIFY");
        Files.delete(new File(f.payloads, "jar").toPath());
        f.reopen(consumer); f.finish();
        assertEquals("SUCCEEDED", f.state().getString("phase")); assertEquals("standby", consumer.observed);
        JSONObject report = f.state().getJSONObject("consumer_verification");
        assertEquals("PASSED", report.getString("status"));
        assertEquals("NOT_CHECKED", report.getString("running_service_effect"));
        assertEquals(RepairFiles.sha256(f.targetBytes), report.getJSONArray("observed_files").getJSONObject(0).getString("sha256"));
        f.reopen(consumer); f.finish(); assertEquals(1, consumer.calls); assertEquals(1, f.io.replacements);
    }
    @Test public void semanticallyRejectedConfigurationRollsBackEvenWhenHashMatches() throws Exception {
        ConfigurationConsumer consumer = new ConfigurationConsumer();
        Fixture f = new Fixture("active", consumer); f.finish();
        assertEquals("active", consumer.observed); assertEquals("ROLLED_BACK", f.state().getString("phase"));
        assertEquals("FAILED", f.state().getJSONObject("consumer_verification").getString("status"));
        assertArrayEquals(f.original, Files.readAllBytes(f.target.toPath())); assertEquals(2, f.io.replacements);
    }
    @Test public void consumerExceptionIsSanitizedAndRollsBack() throws Exception {
        Fixture f = new Fixture("standby", new ConfigurationConsumer() {
            @Override public boolean verify(RepairPlan p, RepairConsumer.Files files) throws Exception {
                files.read(PATH, 65536); throw new IOException("PRIVATE_FIXTURE_DETAIL");
            }
        });
        f.finish(); assertEquals("ROLLED_BACK", f.state().getString("phase"));
        assertEquals("READ_FAILED", f.state().getJSONObject("consumer_verification").getString("status"));
        assertFalse(f.engine.query(f.plan.taskId).toString().contains("PRIVATE_FIXTURE_DETAIL"));
    }
    @Test public void returningTrueWithoutReadingActualFileCannotPass() throws Exception {
        Fixture f = new Fixture("standby", new ConfigurationConsumer() {
            @Override public boolean verify(RepairPlan p, RepairConsumer.Files files) { return true; }
        });
        f.finish(); assertEquals("ROLLED_BACK", f.state().getString("phase"));
    }
    @Test public void readingOutsidePlanCannotPass() throws Exception {
        Fixture f = new Fixture("standby", new ConfigurationConsumer() {
            @Override public boolean verify(RepairPlan p, RepairConsumer.Files files) throws Exception {
                files.read("system-support/start.sh", 65536); return true;
            }
        });
        f.finish(); assertEquals("ROLLED_BACK", f.state().getString("phase"));
    }
    @Test public void expandedFileRequiresConsumerBeforeWriting() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new Fixture("standby", null));
    }
    @Test public void changedConsumerContractOnResumeRollsBackWithoutCallingIt() throws Exception {
        Fixture f = new Fixture("standby", new ConfigurationConsumer()); f.until("VERIFY");
        ConfigurationConsumer changed = new ConfigurationConsumer() {
            @Override public String id() { return "different-v2"; }
        };
        f.reopen(changed); f.finish(); assertEquals("ROLLED_BACK", f.state().getString("phase"));
        assertEquals(0, changed.calls); assertArrayEquals(f.original, Files.readAllBytes(f.target.toPath()));
    }
    @Test public void interruptionRetainsVerifyAndCanResumeWithoutReswitch() throws Exception {
        ConfigurationConsumer interrupt = new ConfigurationConsumer() {
            @Override public boolean verify(RepairPlan p, RepairConsumer.Files files) throws Exception {
                throw new InterruptedException("fixture");
            }
        };
        Fixture f = new Fixture("standby", interrupt); f.until("VERIFY");
        try { assertThrows(InterruptedException.class, () -> f.engine.step(f.plan.taskId, 100)); }
        finally { Thread.interrupted(); }
        assertEquals("VERIFY", f.state().getString("phase"));
        f.reopen(new ConfigurationConsumer()); f.finish();
        assertEquals("SUCCEEDED", f.state().getString("phase")); assertEquals(1, f.io.replacements);
    }
}
