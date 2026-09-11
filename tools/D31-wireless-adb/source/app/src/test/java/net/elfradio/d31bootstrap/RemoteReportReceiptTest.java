package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteReportReceiptTest {
    private JSONObject body() throws Exception {
        return new JSONObject().put("report_id", "report-test").put("app_version", "new-version")
                .put("_core_instance", "new-instance").put("_notice_version", 5);
    }
    private JSONObject reply() throws Exception { return new JSONObject().put("report_id", "report-test"); }

    @Test public void freshMatchingReportConfirmsCurrentInstance() throws Exception {
        assertTrue(RemoteReportReceipt.current(body(), reply(), "new-version", "new-instance"));
    }
    @Test public void previousVersionReceiptCannotConfirmUpdate() throws Exception {
        assertFalse(RemoteReportReceipt.current(body().put("app_version", "old-version"), reply(), "new-version", "new-instance"));
    }
    @Test public void previousProcessOfSameVersionCannotConfirmNewInstance() throws Exception {
        assertFalse(RemoteReportReceipt.current(body().put("_core_instance", "old-instance"), reply(), "new-version", "new-instance"));
    }
    @Test public void legacyPendingReportCanReplayWithoutConfirmingHealth() throws Exception {
        JSONObject pending = body(); pending.remove("_core_instance");
        assertFalse(RemoteReportReceipt.current(pending, reply(), "new-version", "new-instance"));
        assertEquals("report-test", RemoteReportReceipt.wire(pending).getString("report_id"));
    }
    @Test public void mismatchedOrEmptyReceiptDoesNotConfirmHealth() throws Exception {
        assertFalse(RemoteReportReceipt.current(body(), reply().put("report_id", "other"), "new-version", "new-instance"));
        assertFalse(RemoteReportReceipt.current(body().put("report_id", ""), reply().put("report_id", ""), "new-version", "new-instance"));
        assertFalse(RemoteReportReceipt.current(body().put("_core_instance", ""), reply(), "new-version", ""));
    }
    @Test public void internalMarkersStayLocalAndOriginalIsUnchanged() throws Exception {
        JSONObject pending = body().put("status_request_id", "request-test");
        String original = pending.toString();
        JSONObject wire = RemoteReportReceipt.wire(pending);
        assertFalse(wire.has("_core_instance")); assertFalse(wire.has("_notice_version"));
        assertEquals("request-test", wire.getString("status_request_id"));
        assertEquals("new-version", wire.getString("app_version"));
        assertEquals(original, pending.toString());
    }
}
