package net.elfradio.d31bootstrap.telemetry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 只验证真实媒体字段和本地关联，不把模型结果当上传验收。 */
public class MediaAssociationTest {
    private static JSONObject report(String network) throws Exception {
        return new JSONObject().put("report_id", "synthetic-report-1").put("network", network);
    }
    private static JSONObject ack() throws Exception {
        return new JSONObject().put("ok", true).put("report_id", "synthetic-report-1");
    }
    @Test public void automaticPhotoRequiresExactAcknowledgementAndRealNetwork() throws Exception {
        assertEquals("ELIGIBLE", MediaAssociation.automaticPhotoEligibility(report("wifi"), ack()));
        assertEquals("REPORT_NOT_ACKNOWLEDGED", MediaAssociation.automaticPhotoEligibility(report("wifi"), null));
        assertEquals("REPORT_NOT_ACKNOWLEDGED", MediaAssociation.automaticPhotoEligibility(report("wifi"), ack().put("report_id", "other")));
        assertEquals("REPORT_NOT_ACKNOWLEDGED", MediaAssociation.automaticPhotoEligibility(report("wifi"), ack().put("ok", false)));
        assertEquals("SERVER_NETWORK_POLICY_REJECTED", MediaAssociation.automaticPhotoEligibility(report("ethernet"), ack()));
    }
    @Test public void cellularExceptionRequiresExactCriticalBatteryEvent() throws Exception {
        JSONObject event = new JSONObject().put("type", "low_battery").put("level", 1).put("thresholds", new JSONArray().put(2));
        JSONObject report = report("cellular").put("report_event", event);
        assertEquals("ELIGIBLE", MediaAssociation.automaticPhotoEligibility(report, ack()));
        event.put("level", 2);
        assertEquals("SERVER_NETWORK_POLICY_REJECTED", MediaAssociation.automaticPhotoEligibility(report, ack()));
        event.put("level", 1).put("thresholds", new JSONArray().put(5));
        assertEquals("SERVER_NETWORK_POLICY_REJECTED", MediaAssociation.automaticPhotoEligibility(report, ack()));
    }
    @Test public void photoFieldsContainNoSyntheticLocationOrTransport() throws Exception {
        String hash = new String(new char[64]).replace('\0', 'a');
        JSONObject photo = MediaAssociation.photo("synthetic-report-1", 1800000000000L, 262144, hash);
        assertEquals(4, photo.length()); assertEquals(1800000000000L, photo.getLong("captured_at"));
        assertFalse(photo.has("gps"));
        for (long size : new long[]{3, 262145}) {
            try { MediaAssociation.photo("synthetic-report-1", 1800000000000L, size, hash); fail("应拒绝长度"); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void recordingUsesActualTimesAndOnlyExplicitReportAssociation() throws Exception {
        JSONObject recording = MediaAssociation.recording("synthetic-media-1", "audio", null, "synthetic-task-1", 1000, 1250);
        assertEquals(250, recording.getLong("duration_ms")); assertTrue(recording.isNull("report_id"));
        assertEquals("UNLINKED", recording.getString("association")); assertEquals("NOT_INTEGRATED", recording.getString("transport_state"));
        assertEquals("device", recording.getString("time_source"));
        JSONObject linked = MediaAssociation.recording("synthetic-media-2", "video", "synthetic-report-1", null, 1000, 2000);
        assertEquals("synthetic-report-1", linked.getString("report_id")); assertFalse(linked.has("gps"));
        try { MediaAssociation.recording("synthetic-media-1", "audio", null, null, 1000, 999); fail("应拒绝负时间"); }
        catch (IllegalArgumentException expected) { }
    }
}
