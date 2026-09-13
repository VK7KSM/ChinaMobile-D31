package net.elfradio.d31bootstrap.diagnostics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 有限目录、旧输入和报告兼容性；不读取设备或真实配置。 */
public class DiagnosticConfigurationCoverageTest {
    private static final String PATH = "/data/local/d31-system-support/start.sh";
    private static final String FIELD = "semantic.system_support.root";
    private static final String[] IDS = {"system_support.root", "system_support.disabled", "recovery.enabled",
            "startup.cellular_enabled", "rescue.enabled", "desktop.config_tab", "initialization.components",
            "initialization.permissions", "initialization.completion"};

    private static JSONObject observed(Object value) throws Exception {
        return new JSONObject().put("state", "OBSERVED").put("value", value).put("source", "private-source");
    }
    private static JSONObject missing(String state) throws Exception {
        return new JSONObject().put("state", state).put("source", "private-source").put("reason", "private-reason");
    }
    private static JSONObject manifest(String role) throws Exception {
        JSONObject context = new JSONObject();
        for (String field : DiagnosticContract.CONTEXT_FIELDS) context.put(field, "fixture");
        return new JSONObject().put("schemaVersion", 1).put("role", role).put("snapshotId", "private-snapshot")
                .put("baselineId", "fixture").put("baselineRevision", "1").put("firmwareId", "fixture")
                .put("build", "fixture").put("collectorVersion", "fixture").put("capturedAtMs", 100)
                .put("validUntilMs", 200).put("uptimeMs", 0).put("context", context).put("completeness", "PARTIAL")
                .put("scope", new JSONArray().put(new JSONObject().put("path", "/data").put("state", "PARTIAL")
                        .put("source", "private-scope").put("reason", "private-reason")))
                .put("entries", new JSONArray());
    }
    private static JSONObject add(JSONObject manifest, String path, JSONObject fields) throws Exception {
        manifest.getJSONArray("entries").put(new JSONObject().put("path", path)
                .put("presence", observed("PRESENT")).put("fields", fields));
        return manifest;
    }
    private static JSONObject root(JSONObject manifest, JSONObject evidence) throws Exception {
        return add(manifest, PATH, new JSONObject().put(FIELD, evidence));
    }
    private static JSONObject report(JSONObject o, JSONObject f) throws Exception {
        return new DiagnosticCoverageComparison().compare(DiagnosticManifest.parse(o), DiagnosticManifest.parse(f), 150);
    }
    private static JSONObject coverage(JSONObject report) throws Exception { return report.getJSONObject("configurationCoverage"); }
    private static JSONObject first(JSONObject report) throws Exception { return coverage(report).getJSONArray("items").getJSONObject(0); }
    private static void gaps(JSONObject report, int both) throws Exception {
        JSONObject c = coverage(report);
        assertEquals("d31-finite-configuration", c.getString("catalogId"));
        assertEquals(3, c.getInt("catalogVersion")); assertEquals(9, c.getInt("requiredItems"));
        assertEquals(5, c.getInt("mappedItems")); assertEquals(both, c.getInt("bothObservedItems"));
        assertEquals(9 - both, c.getInt("gapItems")); assertEquals(0, c.getInt("verifiedNotApplicableItems"));
        assertFalse(c.getBoolean("applicabilityRulesApplied")); assertEquals("INCOMPLETE", c.getString("status"));
        assertEquals("NOT_ESTABLISHED", c.getString("wholeConfigurationCoverage"));
        assertTrue(report.getJSONArray("gaps").toString().contains("CONFIGURATION_REQUIRED_ITEMS_INCOMPLETE"));
        JSONArray items = c.getJSONArray("items"); assertEquals(9, items.length());
        for (int i = 0; i < IDS.length; i++) assertEquals(IDS[i], items.getJSONObject(i).getString("id"));
        assertEquals(1, report.getInt("schemaVersion")); assertFalse(report.getBoolean("repairPlanGenerated"));
        assertEquals("NOT_ASSESSED", report.getString("systemConsistency"));
    }

    @Test public void emptyLegacyInputKeepsNineGapsAndNoInventedEntries() throws Exception {
        JSONObject r = report(manifest("TARGET"), manifest("FIRMWARE")); gaps(r, 0);
        assertEquals(0, r.getJSONArray("entries").length());
        assertEquals(0, r.getJSONObject("counts").getInt("knownPaths"));
        assertEquals(0, r.getJSONObject("counts").getJSONObject("fields").getInt("total"));
        assertTrue(first(r).getJSONObject("observation").isNull("pointer"));
    }
    @Test public void rootBothObservedLeavesOtherEightGaps() throws Exception {
        JSONObject r = report(root(manifest("TARGET"), observed("fixture-root")), root(manifest("FIRMWARE"), observed("fixture-root")));
        gaps(r, 1); assertEquals("SAME", first(r).getString("pair"));
        assertEquals("BOTH_OBSERVED", first(r).getString("coverage"));
        for (int i = 1; i < 9; i++) {
            JSONObject item = coverage(r).getJSONArray("items").getJSONObject(i);
            boolean mapped = i >= 1 && i <= 4;
            assertEquals(mapped, item.getBoolean("mapped"));
            assertEquals(!mapped, item.isNull("field")); assertEquals(!mapped, item.isNull("path"));
            assertEquals("GAP", item.getString("coverage")); assertEquals("UNKNOWN", item.getString("pair"));
            assertTrue(item.getJSONArray("reasons").toString().contains(mapped
                    ? "OBSERVATION_EVIDENCE_INSUFFICIENT" : "CONFIGURATION_FIELD_CONTRACT_NOT_DEFINED"));
        }
    }
    @Test public void differentRootStillCountsEvidenceNotRepairSuccess() throws Exception {
        JSONObject r = report(root(manifest("BOARD"), observed("first-root")), root(manifest("FIRMWARE"), observed("second-root")));
        gaps(r, 1); assertEquals("DIFFERENT", first(r).getString("pair"));
        assertEquals("NOT_COMPARED", r.getString("offlineComparison"));
        assertEquals("NOT_PERFORMED", r.getString("runtimeVerification"));
    }
    @Test public void oneSidedRootPreservesGapAndEvidencePointer() throws Exception {
        JSONObject r = report(root(manifest("TARGET"), observed("fixture-root")), manifest("FIRMWARE"));
        gaps(r, 0); assertEquals(1, coverage(r).getInt("observationObservedItems"));
        assertEquals(0, coverage(r).getInt("firmwareObservedItems"));
        assertEquals("/entries/0/fields/" + FIELD, first(r).getJSONObject("observation").getString("pointer"));
        assertTrue(first(r).getJSONObject("firmware").isNull("pointer"));
        assertTrue(first(r).getJSONObject("firmware").getJSONArray("reasons").toString().contains("FIELD_NOT_COLLECTED"));
    }
    @Test public void everyUnavailableStateRemainsGapOnEitherSide() throws Exception {
        for (String state : new String[]{"NOT_CHECKED", "READ_FAILED", "UNSTABLE", "REDACTED", "NOT_APPLICABLE"}) {
            for (boolean observationMissing : new boolean[]{true, false}) {
                JSONObject r = report(root(manifest("TARGET"), observationMissing ? missing(state) : observed("fixture-root")),
                        root(manifest("FIRMWARE"), observationMissing ? observed("fixture-root") : missing(state)));
                gaps(r, 0); assertEquals("UNKNOWN", first(r).getString("pair"));
                JSONObject side = first(r).getJSONObject(observationMissing ? "observation" : "firmware");
                assertEquals(state, side.getString("state")); assertFalse(side.getBoolean("observed"));
                assertTrue(side.getJSONArray("reasons").toString().contains("EVIDENCE_" + state));
                if (state.equals("NOT_APPLICABLE")) assertTrue(side.getJSONArray("reasons").toString().contains("APPLICABILITY_RULE_NOT_VERIFIED"));
            }
        }
    }
    @Test public void observedWrongTypeCannotSatisfyRootContract() throws Exception {
        for (Object value : new Object[]{true, 1}) {
            JSONObject r = report(root(manifest("TARGET"), observed(value)), root(manifest("FIRMWARE"), observed(value)));
            gaps(r, 0); assertEquals("UNKNOWN", first(r).getString("pair"));
            assertTrue(first(r).getJSONObject("observation").getJSONArray("reasons").toString().contains("CONFIGURATION_VALUE_TYPE_UNSUPPORTED"));
            assertEquals(1, r.getJSONObject("categories").getJSONObject("CONFIGURATION_SEMANTICS").getInt("SAME"));
        }
    }
    @Test public void genericAndUnregisteredSemanticNamesCannotCoverCatalog() throws Exception {
        JSONObject o = manifest("TARGET"), f = manifest("FIRMWARE"), fields = new JSONObject();
        for (int i = 1; i < IDS.length; i++) fields.put("semantic." + IDS[i], observed(true));
        fields.put("semantic.enabled", observed(true));
        add(o, PATH + ".old", fields); add(f, PATH + ".old", fields);
        JSONObject r = report(o, f); gaps(r, 0);
        assertEquals(9, r.getJSONObject("categories").getJSONObject("CONFIGURATION_SEMANTICS").getInt("SAME"));
    }
    @Test public void exactFieldAtWrongPathDoesNotCoverRoot() throws Exception {
        JSONObject r = report(add(manifest("TARGET"), PATH + ".old", new JSONObject().put(FIELD, observed("fixture-root"))),
                root(manifest("FIRMWARE"), observed("fixture-root")));
        gaps(r, 0); assertEquals(2, r.getJSONObject("counts").getInt("knownPaths"));
    }
    @Test public void metadataAndFilePresenceAreNotConfiguration() throws Exception {
        JSONObject fields = new JSONObject().put("type", observed("file")).put("mode", observed("0600"));
        JSONObject r = report(add(manifest("TARGET"), PATH, fields), add(manifest("FIRMWARE"), PATH, fields));
        gaps(r, 0); assertEquals("NOT_CHECKED", first(r).getJSONObject("observation").getString("state"));
        assertTrue(first(r).getJSONObject("observation").getJSONObject("presence").getString("state").equals("OBSERVED"));
    }
    @Test public void completeAbsenceDoesNotBecomeNotApplicableOrPass() throws Exception {
        JSONObject o = manifest("TARGET"); o.put("completeness", "COMPLETE");
        o.getJSONArray("scope").getJSONObject(0).put("state", "COMPLETE");
        JSONObject r = report(o, root(manifest("FIRMWARE"), observed("fixture-root"))); gaps(r, 0);
        assertTrue(first(r).getJSONObject("observation").getJSONObject("presence").getBoolean("inferredAbsence"));
        assertTrue(first(r).getJSONObject("observation").getJSONArray("reasons").toString().contains("PRESENCE_NOT_CONFIRMED"));
    }
    @Test public void outOfScopeAndReadFailureRetainDistinctPresenceEvidence() throws Exception {
        JSONObject o = manifest("TARGET"); o.getJSONArray("scope").getJSONObject(0).put("path", "/system");
        JSONObject r = report(o, manifest("FIRMWARE")); gaps(r, 0);
        assertTrue(first(r).getJSONObject("observation").getJSONArray("reasons").toString().contains("OUTSIDE_SCOPE"));
        o = manifest("TARGET"); o.getJSONArray("scope").getJSONObject(0).put("state", "READ_FAILED");
        r = report(o, manifest("FIRMWARE"));
        assertEquals("READ_FAILED", first(r).getJSONObject("observation").getJSONObject("presence").getString("state"));
    }
    @Test public void rootPointersUseOriginalEntryOrder() throws Exception {
        JSONObject o = add(manifest("TARGET"), "/data/zz", new JSONObject());
        root(o, observed("fixture-root"));
        JSONObject r = report(o, root(manifest("FIRMWARE"), observed("fixture-root"))); gaps(r, 1);
        assertEquals("/entries/1/fields/" + FIELD, first(r).getJSONObject("observation").getString("pointer"));
    }
    @Test public void enumerationFailuresStayInventoryAndCannotHideGaps() throws Exception {
        JSONObject o = root(manifest("TARGET"), observed("fixture-root"));
        add(o, "/data/directory", new JSONObject().put("semantic.enumeration", missing("READ_FAILED")));
        JSONObject r = report(o, root(manifest("FIRMWARE"), observed("fixture-root"))); gaps(r, 1);
        assertEquals(1, r.getJSONObject("categories").getJSONObject("INVENTORY").getInt("readFailed"));
        assertEquals(1, r.getJSONObject("categories").getJSONObject("CONFIGURATION_SEMANTICS").getInt("SAME"));
        assertEquals(2, r.getJSONArray("entries").length());
    }
    @Test public void privateValuesAndReasonsNeverEnterCoverage() throws Exception {
        JSONObject r = report(root(manifest("TARGET"), observed("private-value")), root(manifest("FIRMWARE"), missing("REDACTED")));
        String text = r.toString();
        for (String secret : new String[]{"private-value", "private-source", "private-reason", "private-snapshot", "private-scope"})
            assertFalse(text.contains(secret));
    }
    @Test public void returnedCatalogCannotMutateSubsequentReports() throws Exception {
        JSONObject o = manifest("TARGET"), f = manifest("FIRMWARE");
        first(report(o, f)).put("id", "changed"); gaps(report(o, f), 0);
    }

    private static void save(Path folder, String name, JSONObject value) throws Exception {
        Files.write(folder.resolve(name), value.toString(2).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]); Files.createDirectory(output);
        for (String name : new String[]{"empty", "root", "different", "one-sided", "enumeration", "redacted", "arbitrary", "complete"}) {
            JSONObject o = manifest("TARGET"), f = manifest("FIRMWARE");
            if (!name.equals("empty") && !name.equals("complete")) root(o, observed("fixture-root"));
            if (!name.equals("empty") && !name.equals("one-sided")) root(f, name.equals("redacted") ? missing("REDACTED")
                    : observed(name.equals("different") ? "fixture-other" : "fixture-root"));
            if (name.equals("complete")) {
                o.put("completeness", "COMPLETE"); o.getJSONArray("scope").getJSONObject(0).put("state", "COMPLETE");
            }
            if (name.equals("enumeration")) add(o, "/data/directory", new JSONObject().put("semantic.enumeration", missing("READ_FAILED")));
            if (name.equals("arbitrary")) add(o, "/data/other", new JSONObject().put("semantic.recovery.enabled", observed(true)));
            save(output, name + "-observation.json", o); save(output, name + "-firmware.json", f);
            save(output, name + "-report.json", report(o, f));
        }
    }
}
