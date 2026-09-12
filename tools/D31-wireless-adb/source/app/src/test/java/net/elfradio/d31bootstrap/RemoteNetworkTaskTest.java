package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class RemoteNetworkTaskTest {
    private static final String HASH = new String(new char[64]).replace('\0', 'a');
    private static final String BOOT = "00000000-0000-4000-8000-000000000001";
    private JSONObject task() throws Exception {
        return new JSONObject().put("id", "synthetic-network").put("type", "system_config").put("request_digest", HASH)
                .put("params", new JSONObject().put("group", "wifi").put("action", "set").put("key", "enabled")
                        .put("value", false).put("network_transaction", new JSONObject().put("version", 1)
                                .put("apk_sha256", HASH).put("confirm_within_ms", 60000)));
    }
    private JSONObject intent() throws Exception { return RemoteNetworkTask.validate("synthetic-device", task()); }
    private JSONObject report(String state) throws Exception {
        return new JSONObject().put("task_id", intent().get("local_task_id")).put("key", "wifi_enabled")
                .put("before", true).put("target", false).put("window_ms", 60000).put("started_elapsed", 1000)
                .put("deadline_elapsed", 61000).put("last_elapsed", 3000).put("boot_id", BOOT)
                .put("state", state).put("target_verified", true).put("original_verified", false)
                .put("apply_attempted", true).put("restored", false);
    }
    @Test public void normalizedWebDefaultsAreAcceptedButPreserved() throws Exception {
        JSONObject task = task(); task.getJSONObject("params").put("package", "").put("offset", 0);
        assertTrue(RemoteNetworkTask.validate("synthetic-device", task).getJSONObject("params").has("package"));
    }
    @Test public void rejectsUnexpectedOrCoercedParameters() throws Exception {
        for (Object value : new Object[]{"false", 0, JSONObject.NULL}) {
            JSONObject task = task(); task.getJSONObject("params").put("value", value);
            reject(task);
        }
        JSONObject task = task(); task.getJSONObject("params").put("ssid", "synthetic"); reject(task);
        task = task(); task.getJSONObject("params").put("package", "synthetic.package"); reject(task);
        task = task(); task.getJSONObject("params").put("offset", "0"); reject(task);
    }
    @Test public void rejectsMissingDigestOrWrongVersionAndWindow() throws Exception {
        JSONObject task = task(); task.remove("request_digest"); reject(task);
        for (Object window : new Object[]{9999, 120001, "60000", 60000.5, JSONObject.NULL}) {
            task = task(); task.getJSONObject("params").getJSONObject("network_transaction").put("confirm_within_ms", window); reject(task);
        }
        task = task(); task.getJSONObject("params").getJSONObject("network_transaction").put("version", 2); reject(task);
    }
    private void reject(JSONObject task) throws Exception {
        try { RemoteNetworkTask.validate("synthetic-device", task); fail("必须拒绝"); }
        catch (IOException expected) { }
    }
    @Test public void awaitingAndAttentionNeverBecomeSuccessEvenWithReleasedReservation() throws Exception {
        for (String state : new String[]{"AWAITING_CONFIRM", "NEEDS_ATTENTION", "APPLYING", "UNKNOWN", "ABSENT"}) {
            JSONObject result = RemoteNetworkTask.result(intent(), report(state), true);
            assertFalse(result.getBoolean("complete")); assertFalse(result.getBoolean("success"));
        }
    }
    @Test public void confirmedWithoutCleanupRemainsPending() throws Exception {
        JSONObject result = RemoteNetworkTask.result(intent(), report("CONFIRMED"), false);
        assertFalse(result.getBoolean("complete")); assertFalse(result.getBoolean("success"));
    }
    @Test public void confirmedUsesOriginalBindingAndObservation() throws Exception {
        JSONObject result = RemoteNetworkTask.result(intent(), report("CONFIRMED"), true);
        assertTrue(result.getBoolean("success"));
        JSONObject wire = result.getJSONObject("network_transaction");
        assertEquals(BOOT, wire.getJSONObject("binding").getString("boot_id"));
        assertEquals(3000, wire.getLong("observed_elapsed"));
        assertFalse(wire.getBoolean("current_enabled"));
    }
    @Test public void restorationIsFailureForRequestedChange() throws Exception {
        JSONObject report = report("ROLLED_BACK").put("original_verified", true).put("target_verified", false).put("restored", true);
        JSONObject result = RemoteNetworkTask.result(intent(), report, true);
        assertTrue(result.getBoolean("complete")); assertFalse(result.getBoolean("success"));
        assertTrue(result.getJSONObject("network_transaction").getBoolean("current_enabled"));
    }
    @Test public void unchangedAfterApplyCannotBeReportedAsUnchangedSuccess() throws Exception {
        JSONObject report = report("UNCHANGED").put("original_verified", true).put("target_verified", false);
        JSONObject result = RemoteNetworkTask.result(intent(), report, true);
        assertFalse(result.getBoolean("success"));
        assertEquals("ORIGINAL_OBSERVED", result.getJSONObject("network_transaction").getString("status"));
    }
    @Test public void originalObservedIsNotClaimedAsRollback() throws Exception {
        JSONObject report = report("ORIGINAL_OBSERVED").put("original_verified", true).put("target_verified", false);
        JSONObject result = RemoteNetworkTask.result(intent(), report, true);
        assertFalse(result.getBoolean("success")); assertFalse(result.getJSONObject("network_transaction").getBoolean("restored"));
    }
    @Test public void wrongTaskTargetAndWindowCannotCloseTask() throws Exception {
        for (String key : new String[]{"task_id", "target", "window_ms", "key"}) {
            JSONObject report = report("CONFIRMED");
            report.put(key, "target".equals(key) ? true : "window_ms".equals(key) ? 50000 : "wrong");
            try { RemoteNetworkTask.result(intent(), report, true); fail(); } catch (IOException expected) { }
        }
    }
    @Test public void bootMismatchDeadlineEqualityAndClockRollbackStopConfirmation() throws Exception {
        JSONObject report = report("AWAITING_CONFIRM");
        assertTrue(RemoteNetworkConfirmation.eligible(report, BOOT, 3000));
        assertFalse(RemoteNetworkConfirmation.eligible(report, BOOT, 2999));
        assertFalse(RemoteNetworkConfirmation.eligible(report, BOOT, 61000));
        assertFalse(RemoteNetworkConfirmation.eligible(report, "other", 3000));
        report.put("state", "ROLLED_BACK"); assertFalse(RemoteNetworkConfirmation.eligible(report, BOOT, 3000));
    }
}
