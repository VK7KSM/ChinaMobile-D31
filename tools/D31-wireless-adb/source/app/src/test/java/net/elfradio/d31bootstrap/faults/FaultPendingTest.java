package net.elfradio.d31bootstrap.faults;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 使用真实扫描索引文件验证轻量回执；仅合成元数据，不采集设备日志。 */
public class FaultPendingTest {
    private File root() throws Exception { return Files.createTempDirectory("fault-pending-field-").toFile().getCanonicalFile(); }
    private JSONObject read(File root) throws Exception { return FaultPending.read(root, 16, "").getJSONObject("capacity"); }
    private JSONObject scan(File root, JSONObject scan) throws Exception {
        FaultArchive.jsonNew(new File(root, "scan.json"), scan);
        return read(root);
    }
    @Test public void recordedTrueAndFalseArePassedAsBooleansWithoutChangingScan() throws Exception {
        for (boolean value : new boolean[]{true, false}) {
            File root = root(), file = new File(root, "scan.json");
            JSONObject input = new JSONObject().put("state", "PARTIAL").put("capturedAtMs", 1000)
                    .put("deferred", value ? 1 : 0).put("uncollectedSourcesMayExpire", value);
            FaultArchive.jsonNew(file, input); byte[] before = Files.readAllBytes(file.toPath());
            JSONObject capacity = read(root);
            assertEquals(Boolean.valueOf(value), capacity.get("uncollectedSourcesMayExpire"));
            assertEquals("LAST_SCAN_NOT_LIVE", capacity.getString("scope"));
            assertEquals(1000, capacity.getLong("capturedAtMs"));
            assertArrayEquals(before, Files.readAllBytes(file.toPath()));
            assertEquals(1, root.list().length);
        }
    }
    @Test public void missingFieldRemainsUnknownEvenWhenScanFinishedOrCapacityFull() throws Exception {
        for (String state : new String[]{"FINISHED", "PARTIAL", "CAPACITY_LIMIT"}) {
            JSONObject value = scan(root(), new JSONObject().put("state", state).put("deferred", 0));
            assertTrue(value.has("uncollectedSourcesMayExpire")); assertTrue(value.isNull("uncollectedSourcesMayExpire"));
        }
    }
    @Test public void nullStringsNumbersContainersCannotCoerceIntoKnownBooleans() throws Exception {
        for (Object bad : new Object[]{JSONObject.NULL, "true", "false", 1, 0, new JSONObject(), new JSONArray()}) {
            JSONObject value = scan(root(), new JSONObject().put("uncollectedSourcesMayExpire", bad));
            assertTrue(value.has("uncollectedSourcesMayExpire")); assertTrue(value.isNull("uncollectedSourcesMayExpire"));
        }
    }
    @Test public void sourceDiscoveryAndCapacityDoNotOverrideTheRecordedObservation() throws Exception {
        JSONObject value = scan(root(), new JSONObject().put("state", "CAPACITY_LIMIT").put("sourceDiscoveryComplete", false)
                .put("deferred", 9).put("uncollectedSourcesMayExpire", false));
        assertEquals(Boolean.FALSE, value.get("uncollectedSourcesMayExpire"));
        value = scan(root(), new JSONObject().put("state", "FINISHED").put("deferred", 0).put("uncollectedSourcesMayExpire", true));
        assertEquals(Boolean.TRUE, value.get("uncollectedSourcesMayExpire"));
    }
    @Test public void missingOrDamagedScanRemainsUnknownAndRawFileIsPreserved() throws Exception {
        File root = root(); assertTrue(read(root).isNull("uncollectedSourcesMayExpire"));
        File file = new File(root, "scan.json"); byte[] bytes = "{broken-synthetic-scan".getBytes(StandardCharsets.UTF_8);
        FaultArchive.writeNew(file, bytes);
        assertTrue(read(root).isNull("uncollectedSourcesMayExpire"));
        assertArrayEquals(bytes, Files.readAllBytes(file.toPath()));
    }
    @Test public void fieldOnlyAppearsInsideCapacityAndPendingStillContainsNoRawSources() throws Exception {
        File root = root();
        FaultArchive.jsonNew(new File(root, "scan.json"), new JSONObject().put("uncollectedSourcesMayExpire", true)
                .put("privateSyntheticField", "NOT_FOR_OUTPUT"));
        JSONObject result = FaultPending.read(root, 16, "");
        assertFalse(result.has("uncollectedSourcesMayExpire"));
        assertTrue(result.getJSONObject("capacity").getBoolean("uncollectedSourcesMayExpire"));
        assertFalse(result.getBoolean("rawContentInSummary")); assertEquals(0, result.getJSONArray("events").length());
        assertFalse(result.toString().contains("NOT_FOR_OUTPUT"));
        assertTrue(result.toString().getBytes(StandardCharsets.UTF_8).length < 8000);
    }
}
