package net.elfradio.d31bootstrap.diagnostics.collection;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticCoverageComparison;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticManifest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static net.elfradio.d31bootstrap.diagnostics.collection.CollectionFixture.*;

/** 用实际采集器和已有内存文件平台复现；全部身份与内容均为合成夹具。 */
public class EnumerationCoverageTest {
    private static JSONObject collected(String fault) throws Exception {
        Clock clock = new Clock();
        Access access = new Access(clock).add("/scope", "directory", "");
        if (fault.equals("READ_FAILED")) access.failingList = "/scope";
        if (fault.equals("NOT_CHECKED")) access.add("/scope/child", "file", "");
        if (fault.equals("UNSTABLE")) access.onList = () -> access.nodes.get("/scope").revision++;
        return new ManifestCollector(access, clock).collect(identity(), "/scope",
                limits(fault.equals("NOT_CHECKED") ? 1 : 3, 100, 3)).manifest().toJson();
    }

    private static JSONObject firmware() throws Exception {
        // 独立的合成固件预期；不改写任何历史采集角色或阶段。
        JSONObject manifest = identity().put("schemaVersion", 1).put("role", "FIRMWARE")
                .put("snapshotId", "fixture-firmware-manifest").put("collectorVersion", "fixture")
                .put("capturedAtMs", 10000).put("validUntilMs", 12000).put("uptimeMs", 0)
                .put("completeness", "COMPLETE")
                .put("scope", new JSONArray().put(new JSONObject().put("path", "/scope")
                        .put("state", "COMPLETE").put("source", "fixture")));
        return manifest.put("entries", new JSONArray().put(new JSONObject().put("path", "/scope")
                .put("presence", observed("PRESENT")).put("fields", new JSONObject()
                        .put("type", observed("directory")))));
    }

    private static JSONObject observed(Object value) throws Exception {
        return new JSONObject().put("state", "OBSERVED").put("value", value).put("source", "fixture");
    }

    private static JSONObject fields(JSONObject manifest) throws Exception {
        return manifest.getJSONArray("entries").getJSONObject(0).getJSONObject("fields");
    }

    private static JSONObject compare(JSONObject observation, JSONObject expected) throws Exception {
        return new DiagnosticCoverageComparison().compare(DiagnosticManifest.parse(observation),
                DiagnosticManifest.parse(expected), 10100);
    }

    private static JSONObject pair(JSONObject report, String path, String name) throws Exception {
        JSONArray rows = report.getJSONArray("entries");
        for (int i = 0; i < rows.length(); i++) {
            if (!path.equals(rows.getJSONObject(i).getString("path"))) continue;
            JSONArray pairs = rows.getJSONObject(i).getJSONArray("fields");
            for (int j = 0; j < pairs.length(); j++) {
                if (name.equals(pairs.getJSONObject(j).getString("field"))) return pairs.getJSONObject(j);
            }
        }
        throw new AssertionError("缺少预期字段");
    }

    private static void missingConfiguration(JSONObject report) throws Exception {
        assertEquals(0, report.getJSONObject("categories").getJSONObject("CONFIGURATION_SEMANTICS").getInt("total"));
        assertTrue(report.getJSONArray("gaps").toString().contains("CONFIGURATION_SEMANTICS_NOT_COLLECTED"));
        assertFalse(report.getBoolean("repairPlanGenerated"));
        assertEquals("NOT_ASSESSED", report.getString("systemConsistency"));
        assertEquals("NOT_PERFORMED", report.getString("runtimeVerification"));
    }

    private static void checkCollectionFailure(String fault) throws Exception {
        JSONObject input = collected(fault);
        assertEquals(fault, fields(input).getJSONObject("semantic.enumeration").getString("state"));
        String original = input.toString();
        JSONObject report = compare(input, firmware());
        missingConfiguration(report);
        JSONObject row = pair(report, "/scope", "semantic.enumeration");
        assertEquals("INVENTORY", row.getString("category"));
        assertEquals("UNKNOWN", row.getString("pair"));
        assertEquals(fault, row.getJSONObject("observation").getString("state"));
        assertEquals("/entries/0/fields/semantic.enumeration", row.getJSONObject("observation").getString("pointer"));
        assertTrue(row.getJSONObject("firmware").isNull("pointer"));
        assertEquals(1, report.getJSONObject("categories").getJSONObject("INVENTORY").getInt("total"));
        assertEquals(original, input.toString());
    }

    @Test public void failedListingDoesNotCountAsConfiguration() throws Exception { checkCollectionFailure("READ_FAILED"); }
    @Test public void exhaustedBudgetDoesNotCountAsConfiguration() throws Exception { checkCollectionFailure("NOT_CHECKED"); }
    @Test public void changedDirectoryDoesNotCountAsConfiguration() throws Exception { checkCollectionFailure("UNSTABLE"); }

    @Test public void completeListingStillHasNoConfiguration() throws Exception {
        JSONObject report = compare(collected("NONE"), firmware());
        missingConfiguration(report);
        assertEquals(0, report.getJSONObject("categories").getJSONObject("INVENTORY").getInt("total"));
    }

    @Test public void observedEnumerationIsStillInventory() throws Exception {
        JSONObject observation = collected("NONE"), expected = firmware();
        fields(observation).put("semantic.enumeration", observed("COMPLETE"));
        fields(expected).put("semantic.enumeration", observed("COMPLETE"));
        JSONObject report = compare(observation, expected);
        missingConfiguration(report);
        assertEquals("SAME", pair(report, "/scope", "semantic.enumeration").getString("pair"));
    }

    @Test public void configurationDifferenceSurvivesAlongsideInventoryFailure() throws Exception {
        JSONObject observation = collected("READ_FAILED"), expected = firmware();
        fields(observation).put("semantic.enabled", observed(true));
        fields(expected).put("semantic.enabled", observed(false));
        JSONObject report = compare(observation, expected);
        assertEquals(1, report.getJSONObject("categories").getJSONObject("CONFIGURATION_SEMANTICS").getInt("DIFFERENT"));
        assertEquals("CONFIGURATION_SEMANTICS", pair(report, "/scope", "semantic.enabled").getString("category"));
        assertFalse(report.getJSONArray("gaps").toString().contains("CONFIGURATION_SEMANTICS_NOT_COLLECTED"));
        assertFalse(report.getBoolean("repairPlanGenerated"));
    }

    @Test public void redactionStillTakesPriorityAndDoesNotLeakValues() throws Exception {
        JSONObject observation = collected("READ_FAILED");
        fields(observation).put("semantic.account", new JSONObject().put("state", "REDACTED")
                .put("source", "fixture-sensitive-source").put("reason", "合成脱敏项"));
        JSONObject report = compare(observation, firmware());
        missingConfiguration(report);
        assertEquals("PERSONAL_DATA", pair(report, "/scope", "semantic.account").getString("category"));
        assertFalse(report.toString().contains("fixture-sensitive-source"));
    }

    @Test public void failedEnumerationStillPreventsInferredAbsence() throws Exception {
        JSONObject observation = collected("READ_FAILED"), expected = firmware();
        observation.put("completeness", "COMPLETE");
        observation.getJSONArray("scope").getJSONObject(0).put("state", "COMPLETE");
        expected.getJSONArray("entries").put(new JSONObject().put("path", "/scope/child")
                .put("presence", observed("PRESENT")).put("fields", new JSONObject().put("type", observed("file"))));
        JSONObject row = pair(compare(observation, expected), "/scope/child", "presence");
        assertEquals("UNKNOWN", row.getString("pair"));
        assertFalse(row.getJSONObject("observation").getBoolean("inferredAbsence"));
    }

    @Test public void categoryTotalsRetainEveryField() throws Exception {
        JSONObject report = compare(collected("READ_FAILED"), firmware());
        JSONObject categories = report.getJSONObject("categories");
        int total = 0;
        for (java.util.Iterator<String> keys = categories.keys(); keys.hasNext();)
            total += categories.getJSONObject(keys.next()).getInt("total");
        assertEquals(report.getJSONObject("counts").getJSONObject("fields").getInt("total"), total);
    }

    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]);
        Files.createDirectory(output);
        Files.write(output.resolve("observation.json"), new JSONObject().put("manifest", collected("READ_FAILED"))
                .toString(2).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        Files.write(output.resolve("firmware.json"), firmware().toString(2).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }
}
