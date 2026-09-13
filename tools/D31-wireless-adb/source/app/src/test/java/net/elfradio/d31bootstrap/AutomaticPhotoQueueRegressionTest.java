package net.elfradio.d31bootstrap;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 仅验证本地队列与ACK交接；相机原件复用和实际网络暂停由执行器测试验证。 */
public class AutomaticPhotoQueueRegressionTest {
    private static final long NOW = 1800000000000L;
    private static final long INTERVAL = 900000L;
    private static final long TTL = 86400000L;
    private static final String DEVICE = "synthetic-device-a";
    private static final String OTHER_DEVICE = "synthetic-device-b";
    private static final String TOKEN = "synthetic-secret-never-persist";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static JSONObject report(String id, long time) throws Exception {
        return new JSONObject().put("report_id", id).put("device_id", DEVICE)
                .put("network", "wifi").put("reported_at", Instant.ofEpochMilli(time).toString());
    }

    private static JSONObject ack(JSONObject report) throws Exception {
        return new JSONObject().put("ok", true).put("report_id", report.getString("report_id"));
    }

    private static JSONObject critical(String id, long time) throws Exception {
        return report(id, time).put("network", "cellular").put("report_event",
                new JSONObject().put("type", "low_battery").put("level", 1)
                        .put("thresholds", new JSONArray().put(2)));
    }

    private static JSONObject result(String state) throws Exception {
        return new JSONObject().put("state", state);
    }

    private static void enqueue(AutomaticPhotoQueue queue, JSONObject report, long now) throws Exception {
        assertTrue(queue.acknowledged(report, ack(report), now));
    }

    private static JSONObject persisted(File root) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File(root, "automatic-photos.json").toPath()),
                StandardCharsets.UTF_8));
    }

    @Test public void onlyExactBooleanAckEnqueuesOnceAcrossRestart() throws Exception {
        File root = temporary.newFolder();
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(root);
        JSONObject report = report("synthetic-ack", NOW);
        assertFalse(queue.acknowledged(report, null, NOW));
        assertFalse(queue.acknowledged(report, ack(report).put("ok", false), NOW));
        assertFalse(queue.acknowledged(report, ack(report).put("ok", "true"), NOW));
        assertFalse(queue.acknowledged(report, ack(report).put("report_id", "synthetic-other"), NOW));
        assertFalse(queue.pending());
        enqueue(queue, report, NOW);
        assertFalse(queue.acknowledged(report, ack(report), NOW));
        queue = new AutomaticPhotoQueue(root);
        assertFalse(queue.acknowledged(report, ack(report), NOW + 1));
        assertEquals(1, persisted(root).getJSONArray("jobs").length());
    }

    @Test public void ordinaryReportsAllowWifiAndEthernetOnly() throws Exception {
        for (String network : new String[]{"cellular", "unknown", "", "WIFI"}) {
            AutomaticPhotoQueue queue = new AutomaticPhotoQueue(temporary.newFolder());
            JSONObject report = report("synthetic-network", NOW).put("network", network);
            assertFalse(network, queue.acknowledged(report, ack(report), NOW));
            assertFalse(queue.pending());
        }
        for (String network : new String[]{"wifi", "ethernet"}) {
            AutomaticPhotoQueue queue = new AutomaticPhotoQueue(temporary.newFolder());
            enqueue(queue, report("synthetic-network-allowed", NOW).put("network",network), NOW);
            assertFalse(queue.next(NOW, DEVICE).getBoolean("critical"));
        }
    }

    @Test public void lowBatteryCannotBypassCellularRestrictionOrCadence() throws Exception {
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(temporary.newFolder());
        JSONObject invalid = critical("synthetic-critical-invalid", NOW);
        invalid.getJSONObject("report_event").put("level", 2);
        assertFalse(queue.acknowledged(invalid, ack(invalid), NOW));
        invalid.getJSONObject("report_event").put("level", "1");
        assertFalse(queue.acknowledged(invalid, ack(invalid), NOW));
        invalid.getJSONObject("report_event").put("level", 1).put("thresholds", new JSONArray().put("2"));
        assertFalse(queue.acknowledged(invalid, ack(invalid), NOW));
        enqueue(queue, report("synthetic-ordinary", NOW), NOW);
        JSONObject cellular = critical("synthetic-critical", NOW);
        assertFalse(queue.acknowledged(cellular, ack(cellular), NOW));
        JSONObject ethernet = critical("synthetic-wired-critical", NOW).put("network","ethernet");
        assertFalse(queue.acknowledged(ethernet, ack(ethernet), NOW));
        JSONObject ordinary = queue.next(NOW, DEVICE);
        queue.result(ordinary, result("completed"), NOW);
        queue.removed(queue.next(NOW, DEVICE), NOW);
        assertNull(queue.next(NOW, DEVICE));
        enqueue(queue, critical("synthetic-wired-later", NOW+INTERVAL).put("network","ethernet"), NOW+INTERVAL);
        JSONObject next = queue.next(NOW+INTERVAL, DEVICE);
        assertFalse(next.getBoolean("critical"));
        assertEquals(NOW+2*INTERVAL, next.getLong("expires_at"));
    }

    @Test public void queuedCadenceSurvivesRestartAndAllowsExactBoundary() throws Exception {
        File root = temporary.newFolder();
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(root);
        assertEquals(0, queue.reportAt());
        enqueue(queue, report("synthetic-first", NOW), NOW);
        queue = new AutomaticPhotoQueue(root);
        assertEquals(NOW + INTERVAL, queue.reportAt());
        JSONObject early = report("synthetic-early", NOW + INTERVAL - 1).put("network","ethernet");
        assertFalse(queue.acknowledged(early, ack(early), NOW + INTERVAL - 1));
        enqueue(queue, report("synthetic-boundary", NOW + INTERVAL).put("network","ethernet"), NOW + INTERVAL);
    }

    @Test public void actualCaptureTimeDelaysCadenceWithoutChangingSampleTime() throws Exception {
        File root = temporary.newFolder();
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(root);
        enqueue(queue, report("synthetic-captured", NOW - 1000), NOW);
        JSONObject job = queue.next(NOW, DEVICE);
        long captured = NOW + 20000;
        queue.result(job, result("waiting").put("captured_at", captured), captured);
        queue = new AutomaticPhotoQueue(root);
        assertEquals(captured + INTERVAL, queue.reportAt());
        JSONObject resumed = queue.next(captured + 30000, DEVICE);
        assertEquals(NOW - 1000, resumed.getLong("sampled_at"));
        assertEquals(NOW - 1000 + INTERVAL, resumed.getLong("expires_at"));
        assertEquals(captured + INTERVAL, resumed.getLong("capture_not_before"));
        queue.result(resumed, result("waiting").put("captured_at", captured - 1), captured + 30000);
        assertEquals(captured + INTERVAL, queue.reportAt());
        JSONObject early = report("synthetic-capture-early", captured + INTERVAL - 1);
        assertFalse(queue.acknowledged(early, ack(early), captured + INTERVAL - 1));
        enqueue(queue, report("synthetic-capture-due", captured + INTERVAL), captured + INTERVAL);
    }

    @Test public void sampleAgeAndFutureSkewAreBounded() throws Exception {
        for (long age : new long[]{INTERVAL, -60000}) {
            AutomaticPhotoQueue queue = new AutomaticPhotoQueue(temporary.newFolder());
            enqueue(queue, report("synthetic-age-allowed", NOW - age), NOW);
        }
        for (long time : new long[]{NOW - INTERVAL - 1, NOW + 60001, 0}) {
            AutomaticPhotoQueue queue = new AutomaticPhotoQueue(temporary.newFolder());
            JSONObject report = report("synthetic-age-rejected", time);
            assertFalse(queue.acknowledged(report, ack(report), NOW));
            assertFalse(queue.pending());
        }
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(temporary.newFolder());
        JSONObject old = critical("synthetic-critical-age", NOW - TTL).put("network","ethernet");
        assertFalse(queue.acknowledged(old, ack(old), NOW));
        JSONObject expired = critical("synthetic-critical-expired", NOW - TTL - 1);
        assertFalse(queue.acknowledged(expired, ack(expired), NOW));
    }

    @Test public void malformedSampleTimeCannotCreateJob() throws Exception {
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(temporary.newFolder());
        JSONObject report = report("synthetic-time", NOW).put("reported_at", "2026-02-30T00:00:00Z");
        try {
            queue.acknowledged(report, ack(report), NOW);
            fail("无效采样时间不得入队");
        } catch (java.io.IOException expected) {
            assertEquals("AUTO_PHOTO_REPORT_TIME_INVALID", expected.getMessage());
        }
        assertFalse(queue.pending());
    }

    @Test public void failedAttemptsPersistAndThirdFailureIsTerminal() throws Exception {
        File root = temporary.newFolder();
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(root);
        enqueue(queue, report("synthetic-retry", NOW), NOW);
        queue.result(queue.next(NOW, DEVICE), result("failed").put("error", "synthetic-upload-error"), NOW);
        queue = new AutomaticPhotoQueue(root);
        assertNull(queue.next(NOW + 29999, DEVICE));
        JSONObject second = queue.next(NOW + 30000, DEVICE);
        assertEquals(1, second.getInt("attempt"));
        assertFalse(second.optBoolean("terminal"));
        queue.result(second, result("failed"), NOW + 30000);
        queue = new AutomaticPhotoQueue(root);
        assertNull(queue.next(NOW + 149999, DEVICE));
        JSONObject third = queue.next(NOW + 150000, DEVICE);
        assertEquals(2, third.getInt("attempt"));
        queue.result(third, result("failed").put("error", "synthetic-third-error"), NOW + 150000);
        JSONObject terminal = new AutomaticPhotoQueue(root).next(NOW + 150000, DEVICE);
        assertTrue(terminal.getBoolean("terminal"));
        assertEquals(3, terminal.getInt("attempt"));
        assertEquals("synthetic-third-error", terminal.getString("error"));
    }

    @Test public void pauseAndRunningDoNotSpendFailureBudget() throws Exception {
        File root = temporary.newFolder();
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(root);
        enqueue(queue, report("synthetic-pause", NOW), NOW);
        long now = NOW;
        for (String error : new String[]{"AUTO_PHOTO_NETWORK_PAUSED", "MEDIA_BUSY", "AUTO_PHOTO_NETWORK_PAUSED", "MEDIA_BUSY"}) {
            queue.result(queue.next(now, DEVICE), result("waiting").put("error", error), now);
            queue = new AutomaticPhotoQueue(root);
            assertNull(queue.next(now + 29999, DEVICE));
            now += 30000;
            assertEquals(0, queue.next(now, DEVICE).getInt("attempt"));
            assertFalse(queue.next(now, DEVICE).optBoolean("terminal"));
        }
        queue.result(queue.next(now, DEVICE), result("running"), now);
        assertNull(queue.next(now + 1999, DEVICE));
        assertEquals(0, queue.next(now + 2000, DEVICE).getInt("attempt"));
        queue.result(queue.next(now + 2000, DEVICE), result("failed"), now + 2000);
        assertEquals(1, queue.next(now + 32000, DEVICE).getInt("attempt"));
    }

    @Test public void permanentFailureRequiresCleanupAfterOnlyOneAttempt() throws Exception {
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(temporary.newFolder());
        enqueue(queue, report("synthetic-permanent", NOW), NOW);
        queue.result(queue.next(NOW, DEVICE), result("failed").put("permanent", true), NOW);
        JSONObject terminal = queue.next(NOW, DEVICE);
        assertTrue(terminal.getBoolean("terminal"));
        assertEquals(1, terminal.getInt("attempt"));
        assertTrue(queue.pending());
    }

    @Test public void identityChangeTerminatesEvenWhileRetryIsDelayed() throws Exception {
        File root = temporary.newFolder();
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(root);
        JSONObject report = report("synthetic-identity", NOW);
        enqueue(queue, report, NOW);
        queue.result(queue.next(NOW, DEVICE), result("waiting"), NOW);
        queue = new AutomaticPhotoQueue(root);
        JSONObject terminal = queue.next(NOW + 1, OTHER_DEVICE);
        assertTrue(terminal.getBoolean("terminal"));
        assertEquals(DEVICE, terminal.getString("device_id"));
        assertEquals("AUTO_PHOTO_EXPIRED_OR_IDENTITY_CHANGED", terminal.getString("error"));
        queue.removed(terminal, NOW + 1);
        queue = new AutomaticPhotoQueue(root);
        assertFalse(queue.acknowledged(report, ack(report), NOW + 2));
        assertNull(queue.next(NOW + 2, DEVICE));
    }

    @Test public void deduplicationIncludesDeviceOwnership() throws Exception {
        File root = temporary.newFolder();
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(root);
        JSONObject first = report("synthetic-shared-id", NOW);
        enqueue(queue, first, NOW);
        queue.removed(queue.next(NOW, OTHER_DEVICE), NOW);
        JSONObject other = report("synthetic-shared-id", NOW).put("device_id", OTHER_DEVICE);
        enqueue(queue, other, NOW);
        JSONObject next = new AutomaticPhotoQueue(root).next(NOW, OTHER_DEVICE);
        assertEquals(OTHER_DEVICE, next.getString("device_id"));
        assertFalse(next.optBoolean("terminal"));
    }

    @Test public void expiryIsTerminalButCapturedRetryCanOutliveOrdinaryCaptureWindow() throws Exception {
        File root = temporary.newFolder();
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(root);
        enqueue(queue, report("synthetic-expiry", NOW), NOW);
        queue.result(queue.next(NOW, DEVICE), result("waiting").put("captured_at", NOW + 1000), NOW + 1000);
        queue = new AutomaticPhotoQueue(root);
        assertFalse(queue.next(NOW + INTERVAL + 1, DEVICE).optBoolean("terminal"));
        assertFalse(queue.next(NOW + TTL, DEVICE).optBoolean("terminal"));
        JSONObject expired = queue.next(NOW + TTL + 1, DEVICE);
        assertTrue(expired.getBoolean("terminal"));
        assertEquals("AUTO_PHOTO_EXPIRED_OR_IDENTITY_CHANGED", expired.getString("error"));
    }

    @Test public void terminalJobsRestartForCleanupAndNeverRegainQueueCaptureEligibility() throws Exception {
        for (String phase : new String[]{"completed", "discarded"}) {
            File root = temporary.newFolder();
            AutomaticPhotoQueue queue = new AutomaticPhotoQueue(root);
            JSONObject report = report("synthetic-finished", NOW);
            enqueue(queue, report, NOW);
            queue.result(queue.next(NOW, DEVICE), result(phase).put("captured_at", NOW + 1000), NOW + 1000);
            queue = new AutomaticPhotoQueue(root);
            JSONObject cleanup = queue.next(NOW + 1001, DEVICE);
            assertTrue(cleanup.getBoolean("terminal"));
            assertEquals(phase, cleanup.getString("last_state"));
            assertTrue(queue.pending());
            assertFalse(queue.acknowledged(report, ack(report), NOW + 1001));
            // 模拟清理尚未确认时重启，任务必须仍只能走终结清理。
            queue = new AutomaticPhotoQueue(root);
            assertTrue(queue.next(NOW + 1002, DEVICE).getBoolean("terminal"));
            queue.removed(cleanup, NOW + 1002);
            queue = new AutomaticPhotoQueue(root);
            assertFalse(queue.pending());
            assertNull(queue.next(NOW + 1003, DEVICE));
            assertFalse(queue.acknowledged(report, ack(report), NOW + 1003));
            assertEquals(1, persisted(root).getJSONArray("done").length());
            assertEquals(phase, persisted(root).getJSONArray("done").getJSONObject(0).getString("state"));
        }
    }

    @Test public void returnedJobIsDetachedAndLateResultsCannotResurrectRemovedJob() throws Exception {
        File root = temporary.newFolder();
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(root);
        enqueue(queue, report("synthetic-detached", NOW), NOW);
        JSONObject copy = queue.next(NOW, DEVICE);
        copy.put("attempt", 999).put("device_id", OTHER_DEVICE).put("terminal", true);
        JSONObject job = queue.next(NOW, DEVICE);
        assertEquals(0, job.getInt("attempt"));
        assertEquals(DEVICE, job.getString("device_id"));
        assertFalse(job.optBoolean("terminal"));
        queue.result(job, result("completed"), NOW);
        queue.removed(queue.next(NOW, DEVICE), NOW);
        queue.result(job, result("failed"), NOW + 1);
        assertFalse(new AutomaticPhotoQueue(root).pending());
        assertEquals(1, persisted(root).getJSONArray("done").length());
    }

    @Test public void queueAndHandoffNeverPersistReportOrReplyCredentials() throws Exception {
        File root = temporary.newFolder();
        JSONObject report = report("synthetic-private", NOW).put("token", TOKEN)
                .put("credentials", new JSONObject().put("token", TOKEN)).put("private_extra", TOKEN);
        JSONObject reply = ack(report).put("token", TOKEN).put("private_extra", TOKEN);
        AutomaticPhotoHandoff handoff = new AutomaticPhotoHandoff(root);
        assertTrue(handoff.offer(report, reply));
        File handoffFile = new File(root, "automatic-photo-acks/synthetic-private.json");
        String frozen = new String(Files.readAllBytes(handoffFile.toPath()), StandardCharsets.UTF_8);
        assertFalse(frozen.contains(TOKEN));
        assertFalse(frozen.contains("credentials"));
        assertFalse(frozen.contains("token"));
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(root);
        handoff.drain(queue, NOW);
        assertFalse(handoffFile.exists());
        assertFalse(persisted(root).toString().contains(TOKEN));
        assertFalse(persisted(root).toString().contains("token"));
        assertEquals(TOKEN, report.getString("token"));
        assertEquals(TOKEN, reply.getString("token"));
        File directRoot = temporary.newFolder();
        AutomaticPhotoQueue direct = new AutomaticPhotoQueue(directRoot);
        assertTrue(direct.acknowledged(report, reply, NOW));
        assertFalse(persisted(directRoot).toString().contains(TOKEN));
        direct.result(direct.next(NOW, DEVICE), result("completed").put("token", TOKEN), NOW);
        direct.removed(direct.next(NOW, DEVICE), NOW);
        assertFalse(persisted(directRoot).toString().contains(TOKEN));
    }

    @Test public void handoffRejectsInvalidAckAndDeduplicatesReplayAcrossRestart() throws Exception {
        File root = temporary.newFolder();
        JSONObject report = report("synthetic-handoff", NOW);
        AutomaticPhotoHandoff handoff = new AutomaticPhotoHandoff(root);
        assertFalse(handoff.offer(report, ack(report).put("ok", "true")));
        assertFalse(new File(root, "automatic-photo-acks").exists());
        assertTrue(handoff.offer(report, ack(report)));
        assertTrue(handoff.offer(report, ack(report)));
        assertEquals(1, new File(root, "automatic-photo-acks").list().length);
        AutomaticPhotoQueue queue = new AutomaticPhotoQueue(root);
        new AutomaticPhotoHandoff(root).drain(queue, NOW);
        assertEquals(1, persisted(root).getJSONArray("jobs").length());
        // 模拟队列落盘后交接文件删除前中断：重复交接不得新增任务。
        assertTrue(handoff.offer(report, ack(report)));
        new AutomaticPhotoHandoff(root).drain(new AutomaticPhotoQueue(root), NOW);
        assertEquals(1, persisted(root).getJSONArray("jobs").length());
        assertEquals(0, new File(root, "automatic-photo-acks").list().length);
    }
}
