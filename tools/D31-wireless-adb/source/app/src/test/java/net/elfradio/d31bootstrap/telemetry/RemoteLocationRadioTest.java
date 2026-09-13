package net.elfradio.d31bootstrap.telemetry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteLocationRadioTest {
    private static final long NOW = 1800000000000L;
    private JSONObject radio(JSONArray rows) throws Exception {
        return new JSONObject().put("sampled_at_ms", NOW - 1234).put("wifiAccessPoints", rows).put("cellTowers", new JSONArray());
    }
    private JSONObject ap(String mac, Object strength) throws Exception {
        return new JSONObject().put("macAddress", mac).put("signalStrength", strength);
    }
    @Test public void filtersInvalidMacAndStrengthAndNeedsTwoDistinctAccessPoints() throws Exception {
        JSONArray rows = new JSONArray().put(ap("00:11:22:33:44:55", -45)).put(ap("00:11:22:33:44:55", -35))
                .put(ap("02:11:22:33:44:55", -20)).put(ap("01:11:22:33:44:55", -20))
                .put(ap("00:00:5e:33:44:55", -20)).put(ap("00:00:00:00:00:00", -20))
                .put(ap("00:11:22:33:44:66", "-40")).put(ap("00:11:22:33:44:77", -128));
        assertEquals(0, RemoteLocationRadio.validated(radio(rows), NOW).getJSONArray("wifiAccessPoints").length());
        rows.put(ap("00:11:22:33:44:88", -60));
        JSONObject result = RemoteLocationRadio.validated(radio(rows), NOW);
        assertEquals(2, result.getJSONArray("wifiAccessPoints").length());
        assertEquals(-45, result.getJSONArray("wifiAccessPoints").getJSONObject(0).getInt("signalStrength"));
        assertEquals(NOW - 1234, result.getLong("sampled_at_ms"));
        assertFalse(result.has("radio_location"));
    }
    @Test public void keepsStrongestSixAndDoesNotForwardExtraIdentity() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 8; i++) rows.put(ap("00:11:22:33:44:0" + i, -90 + i).put("SSID", "private"));
        JSONObject result = RemoteLocationRadio.validated(radio(rows).put("IMSI", "private"), NOW);
        assertEquals(6, result.getJSONArray("wifiAccessPoints").length());
        assertEquals(-83, result.getJSONArray("wifiAccessPoints").getJSONObject(0).getInt("signalStrength"));
        assertFalse(result.toString().contains("private"));
    }
    @Test public void singlePointDiagnosticDoesNotChangeWireThresholdOrTime() throws Exception {
        JSONObject value = radio(new JSONArray().put(ap("00:11:22:33:44:55", -45)))
                .put("wifi_raw_count", 8).put("wifi_valid_count", 1).put("wifi_initial_raw_count", 8)
                .put("wifi_initial_valid_count", 0).put("wifi_scan_result", "results_updated")
                .put("wifi_scan_wait_ms", 2000).put("wifi_newest_age_ms", 1700);
        JSONObject result = RemoteLocationRadio.validated(value, NOW);
        assertEquals(0, result.getJSONArray("wifiAccessPoints").length());
        assertEquals(1, result.getInt("wifi_valid_count")); assertEquals(8, result.getInt("wifi_raw_count"));
        assertEquals("results_updated", result.getString("wifi_scan_result"));
        assertEquals(2000, result.getLong("wifi_scan_wait_ms"));
        JSONObject report = RemoteLocationRadio.forReport(result, NOW + 180000);
        assertEquals(NOW - 1234, report.getLong("sampled_at_ms"));
        assertEquals(1, report.getInt("wifi_valid_count")); assertEquals(1700, report.getLong("wifi_newest_age_ms"));
    }
    @Test public void invalidDiagnosticTypesAndPrivateValuesAreFiltered() throws Exception {
        JSONObject value = radio(new JSONArray()).put("wifi_raw_count", "8").put("wifi_valid_count", -1)
                .put("wifi_initial_raw_count", 10001).put("wifi_initial_valid_count", 0.5)
                .put("wifi_scan_wait_ms", -1).put("wifi_newest_age_ms", "1000")
                .put("wifi_scan_result", "private:00:11:22:33:44:55");
        JSONObject result = RemoteLocationRadio.validated(value, NOW);
        for (String key : new String[]{"wifi_raw_count", "wifi_valid_count", "wifi_initial_raw_count", "wifi_initial_valid_count",
                "wifi_scan_wait_ms", "wifi_newest_age_ms", "wifi_scan_result"}) assertFalse(result.has(key));
    }
    @Test public void staleFutureAndCoercedTimesAreRejected() throws Exception {
        for (Object time : new Object[]{NOW + 1, NOW - 120001, 0, "1800000000000", NOW - 0.5}) {
            try { RemoteLocationRadio.validated(radio(new JSONArray()).put("sampled_at_ms", time), NOW); fail(); }
            catch (IllegalArgumentException expected) { }
        }
        assertFalse(RemoteLocationRadio.recent(0, 100));
        assertFalse(RemoteLocationRadio.recent(101, 100));
        assertTrue(RemoteLocationRadio.recent(1, 120000000001L));
    }
    @Test public void validatesTowerBoundsAndTypeWithoutInferringSimPresence() throws Exception {
        assertNull(RemoteLocationRadio.tower("gsm", 65536, 2, 505, 2, -70));
        assertNull(RemoteLocationRadio.tower("lte", Integer.MAX_VALUE, 2, 505, 2, -70));
        assertNull(RemoteLocationRadio.tower("lte", 123, 2, Integer.MAX_VALUE, 2, -70));
        assertNull(RemoteLocationRadio.tower("nr", 123, 2, 505, 2, -70));
        JSONObject row = RemoteLocationRadio.tower("lte", 123, 2, 505, 2, Integer.MAX_VALUE);
        assertFalse(row.has("signalStrength"));
        JSONObject value = radio(new JSONArray()).put("radioType", "lte").put("cellTowers", new JSONArray().put(row).put(row));
        assertEquals(1, RemoteLocationRadio.validated(value, NOW).getJSONArray("cellTowers").length());
        value.put("radioType", "nr");
        assertEquals(0, RemoteLocationRadio.validated(value, NOW).getJSONArray("cellTowers").length());
    }

    @Test public void frozenReportHasSeparateExpiryWithoutRelaxingObservationFreshness() throws Exception {
        JSONObject observation = radio(new JSONArray()).put("sampled_at_ms", NOW - 120000);
        assertEquals(NOW - 120000, RemoteLocationRadio.validated(observation, NOW).getLong("sampled_at_ms"));
        observation.put("sampled_at_ms", NOW - 120001);
        try { RemoteLocationRadio.validated(observation, NOW); fail("过期扫描仍须拒绝"); }
        catch (IllegalArgumentException expected) { }
        assertFalse(RemoteLocationRadio.recent(1, 120000000002L));
        for (long age : new long[]{120001, 180000, 299999, 300000, 900000}) {
            observation.put("sampled_at_ms", NOW - age);
            String before = observation.toString();
            assertEquals(NOW - age, RemoteLocationRadio.forReport(observation, NOW).getLong("sampled_at_ms"));
            assertEquals(before, observation.toString());
        }
        for (Object at : new Object[]{NOW - 900001, NOW + 1, 0, "1800000000000", NOW - 0.5}) {
            observation.put("sampled_at_ms", at);
            try { RemoteLocationRadio.forReport(observation, NOW); fail("报告时间边界须拒绝"); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
