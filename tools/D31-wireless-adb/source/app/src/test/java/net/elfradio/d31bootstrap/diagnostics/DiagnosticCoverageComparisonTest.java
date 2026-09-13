package net.elfradio.d31bootstrap.diagnostics;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticCoverageComparisonTest {
    private static JSONObject observed(Object value) throws Exception {
        return new JSONObject().put("state", "OBSERVED").put("value", value).put("source", "private-source");
    }
    private static JSONObject missing(String state) throws Exception {
        return new JSONObject().put("state", state).put("source", "private-source").put("reason", "测试缺口");
    }
    private static JSONObject entry(String path) throws Exception {
        return new JSONObject().put("path", path).put("presence", observed("PRESENT"))
                .put("fields", new JSONObject().put("type", observed("file"))
                        .put("sha256", observed(new String(new char[64]).replace('\0', 'a')))
                        .put("mode", observed("0600")).put("uid", observed(0)).put("gid", observed(0))
                        .put("link", missing("NOT_APPLICABLE")));
    }
    private static JSONObject manifest(String role) throws Exception {
        JSONObject context = new JSONObject();
        for (String name : new String[]{"model", "hardwareClass", "firmwareFamily", "stage", "network", "sim", "storage"})
            context.put(name, "fixture");
        return new JSONObject().put("schemaVersion", 1).put("role", role).put("snapshotId", "private-snapshot")
                .put("baselineId", "unfrozen").put("baselineRevision", "1").put("firmwareId", "fixture")
                .put("build", "fixture").put("collectorVersion", "fixture").put("capturedAtMs", 100)
                .put("validUntilMs", 200).put("uptimeMs", 0).put("context", context).put("completeness", "PARTIAL")
                .put("scope", new JSONArray().put(new JSONObject().put("path", "/system").put("state", "PARTIAL")
                        .put("source", "private-scope-source").put("reason", "测试缺口")))
                .put("entries", new JSONArray().put(entry("/system/file")));
    }
    private static JSONObject compare(JSONObject o, JSONObject f) throws Exception {
        return new DiagnosticCoverageComparison().compare(DiagnosticManifest.parse(o), DiagnosticManifest.parse(f), 300);
    }
    private static JSONObject field(JSONObject report, String name) throws Exception {
        JSONArray fields = report.getJSONArray("entries").getJSONObject(0).getJSONArray("fields");
        for (int i = 0; i < fields.length(); i++) if (name.equals(fields.getJSONObject(i).getString("field"))) return fields.getJSONObject(i);
        throw new AssertionError(name);
    }
    private static JSONObject fields(JSONObject manifest) throws Exception {
        return manifest.getJSONArray("entries").getJSONObject(0).getJSONObject("fields");
    }

    @Test public void ordinaryHashesDoNotClaimSystemConsistency() throws Exception {
        JSONObject r = compare(manifest("TARGET"), manifest("FIRMWARE"));
        assertEquals("SAME", field(r, "sha256").getString("pair"));
        assertEquals("UNKNOWN", field(r, "link").getString("pair"));
        assertEquals("UNKNOWN", field(r, "activation").getString("pair"));
        assertEquals("NOT_COMPARED", r.getString("offlineComparison"));
        assertEquals("NOT_ASSESSED", r.getString("systemConsistency"));
        assertEquals("EXPIRED", r.getJSONObject("observation").getString("freshness"));
        assertEquals(12, r.getJSONObject("counts").getJSONObject("fields").getInt("total"));
    }
    @Test public void noValuesSourcesOrSnapshotNamesCopied() throws Exception {
        JSONObject o = manifest("BOARD"), f = manifest("FIRMWARE");
        fields(o).put("semantic.secret", observed("private-value"));
        fields(f).put("semantic.secret", observed("private-value"));
        String text = compare(o, f).toString();
        assertFalse(text.contains("private-value")); assertFalse(text.contains("private-source"));
        assertFalse(text.contains("private-snapshot")); assertFalse(text.contains("private-scope-source"));
    }
    @Test public void redactedFieldsRemainPersonalUnknown() throws Exception {
        JSONObject o = manifest("BOARD"), f = manifest("FIRMWARE");
        fields(o).put("semantic.account", missing("REDACTED"));
        fields(f).put("semantic.account", missing("REDACTED"));
        JSONObject r = compare(o, f), p = field(r, "semantic.account");
        assertEquals("PERSONAL_DATA", p.getString("category"));
        assertEquals("UNKNOWN", p.getString("pair"));
        assertEquals(1, r.getJSONObject("categories").getJSONObject("PERSONAL_DATA").getInt("redacted"));
    }
    @Test public void rawConfigurationTypesStayDifferent() throws Exception {
        JSONObject o = manifest("BOARD"), f = manifest("FIRMWARE");
        fields(o).put("semantic.enabled", observed(1)); fields(f).put("semantic.enabled", observed("1"));
        assertEquals("DIFFERENT", field(compare(o, f), "semantic.enabled").getString("pair"));
    }
    @Test public void absentSemanticIsNotEmptyEquivalent() throws Exception {
        JSONObject o = manifest("BOARD"), f = manifest("FIRMWARE");
        fields(o).put("semantic.enabled", observed(true));
        JSONObject pair = field(compare(o, f), "semantic.enabled");
        assertEquals("UNKNOWN", pair.getString("pair"));
        assertTrue(pair.getJSONObject("firmware").isNull("pointer"));
    }
    @Test public void xattrsIgnoreMemberOrder() throws Exception {
        JSONObject o = manifest("BOARD"), f = manifest("FIRMWARE");
        fields(o).put("xattrs", observed(new JSONObject().put("one", "a").put("two", "b")));
        fields(f).put("xattrs", observed(new JSONObject().put("two", "b").put("one", "a")));
        assertEquals("SAME", field(compare(o, f), "xattrs").getString("pair"));
    }
    @Test public void partialOmissionsAreNotMissingFiles() throws Exception {
        JSONObject o = manifest("TARGET"), f = manifest("FIRMWARE"); o.put("entries", new JSONArray());
        JSONObject r = compare(o, f);
        assertEquals(1, r.getJSONObject("counts").getInt("firmwareOnlyPaths"));
        assertEquals("UNKNOWN", field(r, "presence").getString("pair"));
        assertFalse(field(r, "presence").getJSONObject("observation").getBoolean("inferredAbsence"));
    }
    @Test public void completeEnumerationMayProveAbsence() throws Exception {
        JSONObject o = manifest("TARGET"), f = manifest("FIRMWARE");
        o.put("completeness", "COMPLETE").put("entries", new JSONArray());
        o.getJSONArray("scope").getJSONObject(0).put("state", "COMPLETE");
        JSONObject p = field(compare(o, f), "presence");
        assertEquals("DIFFERENT", p.getString("pair"));
        assertTrue(p.getJSONObject("observation").getBoolean("inferredAbsence"));
    }
    @Test public void missingPresencePreservesSuccessfulSideButDoesNotCompareItsFields() throws Exception {
        JSONObject o = manifest("TARGET"), f = manifest("FIRMWARE");
        o.getJSONArray("entries").getJSONObject(0).put("presence", missing("READ_FAILED")).put("fields", new JSONObject());
        JSONObject r = compare(o, f);
        assertEquals("UNKNOWN", field(r, "sha256").getString("pair"));
        assertEquals(1, r.getJSONObject("counts").getJSONObject("fields").getInt("readFailed"));
    }
    @Test public void unavailableStatesAreReportedSeparately() throws Exception {
        JSONObject o = manifest("TARGET"), f = manifest("FIRMWARE");
        fields(o).put("sha256", missing("UNSTABLE")); fields(f).put("sha256", missing("READ_FAILED"));
        JSONObject c = compare(o, f).getJSONObject("counts").getJSONObject("fields");
        assertEquals(1, c.getInt("unstable")); assertEquals(1, c.getInt("readFailed"));
        assertEquals(c.getInt("total"), c.getInt("SAME") + c.getInt("DIFFERENT") + c.getInt("UNKNOWN"));
    }
    @Test public void bindingsAndContextMismatchStayVisible() throws Exception {
        JSONObject o = manifest("TARGET"), f = manifest("FIRMWARE");
        f.put("baselineRevision", "2").put("build", "other"); f.getJSONObject("context").put("stage", "preboot");
        JSONObject r = compare(o, f);
        assertFalse(r.getJSONObject("bindingEqual").getBoolean("build"));
        assertFalse(r.getJSONObject("bindingEqual").getBoolean("baselineRevision"));
        assertFalse(r.getJSONObject("contextEqual").getBoolean("stage"));
        assertEquals("NOT_COMPARED", r.getString("offlineComparison"));
    }
    @Test public void futureAndBoundaryFreshness() throws Exception {
        DiagnosticManifest o = DiagnosticManifest.parse(manifest("TARGET")), f = DiagnosticManifest.parse(manifest("FIRMWARE"));
        assertEquals("FUTURE", new DiagnosticCoverageComparison().compare(o, f, 99).getJSONObject("observation").getString("freshness"));
        assertEquals("WITHIN_DECLARED_WINDOW", new DiagnosticCoverageComparison().compare(o, f, 200).getJSONObject("observation").getString("freshness"));
    }
    @Test public void scopesAreNotSilentlyIntersected() throws Exception {
        JSONObject o = manifest("TARGET"), f = manifest("FIRMWARE");
        f.getJSONArray("scope").getJSONObject(0).put("path", "/data");
        f.put("entries", new JSONArray().put(entry("/data/file")));
        JSONObject r = compare(o, f);
        assertEquals(2, r.getJSONObject("counts").getInt("knownPaths"));
        assertEquals(0, r.getJSONObject("counts").getInt("intersectionPaths"));
    }
    @Test public void emptyInputDoesNotPass() throws Exception {
        JSONObject o = manifest("TARGET"), f = manifest("FIRMWARE");
        o.put("entries", new JSONArray()); f.put("entries", new JSONArray());
        JSONObject r = compare(o, f);
        assertEquals("NOT_ASSESSED", r.getString("systemConsistency"));
        assertEquals(0, r.getJSONObject("counts").getInt("knownPaths"));
    }
    @Test public void rolesNotCoerced() throws Exception {
        try { compare(manifest("FIRMWARE"), manifest("TARGET")); fail(); }
        catch (DiagnosticContract.Invalid e) { assertEquals("ROLE_MISMATCH", e.code); }
    }
    @Test public void invalidTreeStillUsesExistingValidation() throws Exception {
        JSONObject o = manifest("TARGET"); o.getJSONArray("entries").put(entry("/system/file/child"));
        try { compare(o, manifest("FIRMWARE")); fail(); }
        catch (DiagnosticContract.Invalid e) { assertEquals("CONTRADICTORY_ANCESTOR_TYPE", e.code); }
    }
    @Test public void sourcePointersUseOriginalOrderNotSortedOrder() throws Exception {
        JSONObject o = manifest("TARGET"); o.getJSONArray("entries").put(entry("/system/a"));
        JSONObject r = compare(o, manifest("FIRMWARE"));
        assertEquals("/entries/1/fields/sha256", field(r, "sha256").getJSONObject("observation").getString("pointer"));
    }
    @Test public void unionOver4096RejectedWithoutSampling() throws Exception {
        JSONObject o = manifest("TARGET"), f = manifest("FIRMWARE"); JSONArray entries = new JSONArray();
        for (int i = 0; i < 4096; i++) entries.put(entry("/system/n" + i));
        o.put("entries", entries);
        try { compare(o, f); fail(); }
        catch (DiagnosticContract.Invalid e) { assertEquals("COMPARISON_LIMIT", e.code); }
    }
    @Test public void unionAt4096RetainsLast() throws Exception {
        JSONObject o = manifest("TARGET"), f = manifest("FIRMWARE"); JSONArray entries = new JSONArray();
        for (int i = 0; i < 4096; i++) entries.put(entry(String.format("/system/n%04d", i)));
        o.put("entries", entries); f.put("entries", new JSONArray());
        JSONObject r = compare(o, f);
        assertEquals("/system/n4095", r.getJSONArray("entries").getJSONObject(4095).getString("path"));
    }
    @Test public void declaredCompleteWithUnknownEntryDoesNotInferAbsence() throws Exception {
        JSONObject o = manifest("TARGET"), f = manifest("FIRMWARE");
        o.put("completeness", "COMPLETE"); o.getJSONArray("scope").getJSONObject(0).put("state", "COMPLETE");
        o.put("entries", new JSONArray().put(new JSONObject().put("path", "/system/other")
                .put("presence", missing("READ_FAILED")).put("fields", new JSONObject())));
        assertEquals("UNKNOWN", field(compare(o, f), "presence").getString("pair"));
    }
    @Test public void unionFieldsAreBounded() throws Exception {
        JSONObject o = manifest("TARGET"), f = manifest("FIRMWARE");
        for (int i = 0; i < 30; i++) {
            fields(o).put("semantic.o" + i, observed(true)); fields(f).put("semantic.f" + i, observed(false));
        }
        try { compare(o, f); fail(); }
        catch (DiagnosticContract.Invalid e) { assertEquals("COMPARISON_LIMIT", e.code); }
    }
    @Test public void categoryCountsAddUp() throws Exception {
        JSONObject r = compare(manifest("TARGET"), manifest("FIRMWARE"));
        JSONObject groups = r.getJSONObject("categories"); int sum = 0;
        java.util.Iterator<String> names = groups.keys();
        while (names.hasNext()) sum += groups.getJSONObject(names.next()).getInt("total");
        assertEquals(r.getJSONObject("counts").getJSONObject("fields").getInt("total"), sum);
    }
}
