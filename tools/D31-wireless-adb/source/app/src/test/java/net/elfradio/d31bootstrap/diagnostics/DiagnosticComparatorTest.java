package net.elfradio.d31bootstrap.diagnostics;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class DiagnosticComparatorTest {
    private static final long NOW = 10000;
    private static final String PATH = "/system/bin/core";
    private static final String HASH_A = repeat('a', 64);
    private static final String HASH_B = repeat('b', 64);
    private static final String HASH_C = repeat('c', 64);

    private static String repeat(char c, int count) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < count; i++) s.append(c);
        return s.toString();
    }

    private static JSONObject observed(Object value) throws Exception {
        return new JSONObject().put("state", "OBSERVED").put("value", value).put("source", "fixture-evidence");
    }

    private static JSONObject unavailable(String state) throws Exception {
        return new JSONObject().put("state", state).put("source", "fixture-evidence").put("reason", "测试证据状态");
    }

    private static JSONObject context() throws Exception {
        return new JSONObject().put("model", "D31").put("hardwareClass", "fixture-class")
                .put("firmwareFamily", "fixture-family").put("stage", "initialized")
                .put("network", "offline").put("sim", "absent").put("storage", "internal");
    }

    private static JSONObject scope(String path, String state) throws Exception {
        JSONObject s = new JSONObject().put("path", path).put("state", state).put("source", "fixture-enumeration");
        if (!state.equals("COMPLETE")) s.put("reason", "测试枚举缺口");
        return s;
    }

    private static JSONObject entry(String path) throws Exception {
        JSONObject fields = new JSONObject().put("type", observed("file")).put("sha256", observed(HASH_A))
                .put("mode", observed("0755")).put("uid", observed(0)).put("gid", observed(0))
                .put("link", unavailable("NOT_APPLICABLE")).put("selinux", observed("u:object_r:system_file:s0"))
                .put("xattrs", observed(new JSONObject())).put("activeSource", observed(path))
                .put("mountSource", observed("system-image")).put("activation", observed("selected"));
        return new JSONObject().put("path", path).put("presence", observed("PRESENT")).put("fields", fields);
    }

    private static JSONObject manifest(String role) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("role", role).put("snapshotId", "fixture-" + role)
                .put("baselineId", "fixture-baseline").put("baselineRevision", "1").put("firmwareId", "fixture-firmware")
                .put("build", "fixture-build").put("collectorVersion", "fixture-collector")
                .put("capturedAtMs", NOW - 100).put("validUntilMs", NOW + 100).put("uptimeMs", 200)
                .put("context", context()).put("completeness", "COMPLETE")
                .put("scope", new JSONArray().put(scope("/system", "COMPLETE")).put(scope("/data", "COMPLETE")))
                .put("entries", new JSONArray().put(entry(PATH)));
    }

    private static JSONObject rules() throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("rulesVersion", "fixture-rules-1")
                .put("baselineId", "fixture-baseline").put("baselineRevision", "1").put("boardSnapshotId", "fixture-BOARD")
                .put("firmwareId", "fixture-firmware").put("firmwareSnapshotId", "fixture-FIRMWARE")
                .put("context", context()).put("scope", new JSONArray().put("/system").put("/data"))
                .put("maxSnapshotAgeMs", 200).put("allowances", new JSONArray()).put("notApplicable", new JSONArray());
    }

    private static final class Fixture {
        final JSONObject board = manifest("BOARD"), firmware = manifest("FIRMWARE"), target = manifest("TARGET"), rules = rules();
        Fixture() throws Exception { }
        JSONObject compare() throws Exception { return compareAt(NOW); }
        JSONObject compareAt(long now) throws Exception {
            return new DiagnosticComparator().compare(DiagnosticManifest.parse(board), DiagnosticManifest.parse(firmware),
                    DiagnosticManifest.parse(target), DiagnosticRules.parse(rules), now);
        }
        JSONObject[] all() { return new JSONObject[]{board, firmware, target}; }
    }

    private static JSONObject fields(JSONObject manifest) throws Exception {
        return manifest.getJSONArray("entries").getJSONObject(0).getJSONObject("fields");
    }

    private static JSONObject item(JSONObject report, String path) throws Exception {
        JSONArray items = report.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) if (path.equals(items.getJSONObject(i).getString("path"))) return items.getJSONObject(i);
        throw new AssertionError("Missing result path");
    }

    private static JSONObject field(JSONObject report, String path, String name) throws Exception {
        JSONArray fields = item(report, path).getJSONArray("fields");
        for (int i = 0; i < fields.length(); i++) if (name.equals(fields.getJSONObject(i).getString("field"))) return fields.getJSONObject(i);
        throw new AssertionError("Missing result field");
    }

    private static void count(JSONObject report, String key, int expected) throws Exception {
        assertEquals(key, expected, report.getJSONObject("counts").getInt(key));
        JSONObject counts = report.getJSONObject("counts");
        assertEquals(counts.getInt("total"), counts.getInt("verified") + counts.getInt("unchecked") + counts.getInt("failed"));
    }

    private static JSONObject allowance(String path, String field, Object board, Object firmware, Object target) throws Exception {
        return new JSONObject().put("id", "fixture-allowance").put("path", path).put("field", field)
                .put("reason", "该设置保留用户选择，仅比较非个人语义状态").put("basis", "fixture-review-1")
                .put("board", board).put("firmware", firmware).put("target", target);
    }

    @Test public void completeEvidenceMatchesOnlyOfflineScope() throws Exception {
        JSONObject report = new Fixture().compare();
        assertEquals("MATCH_WITHIN_SCOPE", report.getString("offlineComparison"));
        assertEquals("NOT_ASSESSED", report.getString("systemConsistency"));
        assertEquals("NOT_PERFORMED", report.getString("runtimeVerification"));
        assertEquals("NOT_ESTABLISHED", report.getString("rootCause"));
        assertTrue(report.getBoolean("evidenceComplete"));
        count(report, "verified", 1); count(report, "same", 1);
    }

    @Test public void everyMissingRequiredFieldOnAllSidesIsUnknownNotEqual() throws Exception {
        for (String name : DiagnosticContract.REQUIRED_FIELDS) {
            Fixture f = new Fixture();
            for (JSONObject m : f.all()) fields(m).remove(name);
            JSONObject report = f.compare();
            assertEquals(name, "INSUFFICIENT_EVIDENCE", report.getString("offlineComparison"));
            count(report, "same", 0); count(report, "unchecked", 1);
            assertEquals("UNKNOWN", field(report, PATH, name).getJSONObject("pairs").getString("boardTarget"));
        }
    }

    @Test public void everyReadFailureOrUnstableOrRedactedFieldBlocksEquality() throws Exception {
        for (String state : new String[]{"READ_FAILED", "NOT_CHECKED", "UNSTABLE", "REDACTED"}) {
            for (String name : DiagnosticContract.REQUIRED_FIELDS) {
                Fixture f = new Fixture();
                fields(f.target).put(name, unavailable(state));
                JSONObject report = f.compare();
                assertEquals("INSUFFICIENT_EVIDENCE", report.getString("offlineComparison"));
                count(report, state.equals("READ_FAILED") ? "failed" : "unchecked", 1);
                count(report, "same", 0);
            }
        }
    }

    @Test public void partialEvidenceStillRetainsProvenDifference() throws Exception {
        Fixture f = new Fixture();
        fields(f.board).put("sha256", observed(HASH_B));
        fields(f.target).put("sha256", unavailable("READ_FAILED"));
        JSONObject report = f.compare();
        count(report, "failed", 1); count(report, "abnormal", 1);
        JSONObject difference = field(report, PATH, "sha256");
        assertEquals("DIFFERENT", difference.getJSONObject("pairs").getString("boardFirmware"));
        assertEquals("UNKNOWN", difference.getJSONObject("pairs").getString("boardTarget"));
        assertEquals("PARTIAL_EVIDENCE", difference.getString("relation"));
    }

    @Test public void sameVersionButDifferentContentIsAnomalousWithOriginalValues() throws Exception {
        Fixture f = new Fixture();
        for (JSONObject m : f.all()) fields(m).put("semantic.versionCode", observed(90));
        fields(f.target).put("sha256", observed(HASH_B));
        JSONObject report = f.compare();
        count(report, "abnormal", 1); count(report, "verified", 1);
        JSONObject diff = field(report, PATH, "sha256");
        assertEquals("TARGET_ONLY", diff.getString("relation"));
        assertEquals(HASH_A, diff.getJSONObject("evidence").getJSONObject("board").getString("value"));
        assertEquals(HASH_A, diff.getJSONObject("evidence").getJSONObject("firmware").getString("value"));
        assertEquals(HASH_B, diff.getJSONObject("evidence").getJSONObject("target").getString("value"));
        assertEquals("fixture-evidence", diff.getJSONObject("evidence").getJSONObject("target").getString("source"));
    }

    @Test public void threeWayRelationsDoNotInventCauses() throws Exception {
        String[][] vectors = {{HASH_A, HASH_B, HASH_A, "FIRMWARE_ONLY"},
                {HASH_B, HASH_A, HASH_A, "BOARD_ONLY"}, {HASH_A, HASH_B, HASH_C, "ALL_DIFFERENT"}};
        for (String[] vector : vectors) {
            Fixture f = new Fixture(); JSONObject[] all = f.all();
            for (int i = 0; i < 3; i++) fields(all[i]).put("sha256", observed(vector[i]));
            JSONObject report = f.compare();
            assertEquals(vector[3], field(report, PATH, "sha256").getString("relation"));
            assertEquals("NOT_ESTABLISHED", item(report, PATH).getString("rootCause"));
        }
    }

    @Test public void metadataAndActiveOriginsAndMigrationAreComparedIndependently() throws Exception {
        String[] names = {"type", "mode", "uid", "gid", "link", "selinux", "xattrs", "activeSource", "mountSource", "activation", "semantic.migration"};
        Object[] values = {"block", "0644", 1000, 1000, "/system/bin/other", "u:object_r:other:s0",
                new JSONObject().put("security.test", "present"), "/data/core/previous", "runtime-overlay", "fallback", "pending"};
        for (int i = 0; i < names.length; i++) {
            Fixture f = new Fixture();
            if (names[i].startsWith("semantic.")) for (JSONObject m : f.all()) fields(m).put(names[i], observed("complete"));
            fields(f.target).put(names[i], observed(values[i]));
            JSONObject report = f.compare();
            count(report, "abnormal", 1);
            assertEquals("ANOMALOUS_DIFFERENCE", field(report, PATH, names[i]).getString("classification"));
        }
    }

    @Test public void completeEnumerationDetectsMissingAndExtraFilesIncludingData() throws Exception {
        Fixture f = new Fixture();
        f.target.put("entries", new JSONArray().put(entry("/data/managed/old-core")));
        JSONObject report = f.compare();
        count(report, "total", 2); count(report, "verified", 2); count(report, "abnormal", 2);
        assertEquals("DIFFERENCES", report.getString("offlineComparison"));
        JSONObject absent = field(report, PATH, "presence").getJSONObject("evidence").getJSONObject("target");
        assertEquals("ABSENT", absent.getString("value"));
        assertEquals("ABSENT_FROM_COMPLETE_ENUMERATION", absent.getString("reason"));
    }

    @Test public void incompleteEnumerationNeverInventsAbsence() throws Exception {
        for (String state : new String[]{"PARTIAL", "READ_FAILED", "NOT_CHECKED"}) {
            Fixture f = new Fixture();
            f.target.put("entries", new JSONArray()).put("completeness", "PARTIAL");
            f.target.getJSONArray("scope").put(0, scope("/system", state));
            JSONObject report = f.compare();
            assertFalse(report.getBoolean("inventoryTotalKnown"));
            count(report, "abnormal", 0); count(report, "verified", 0);
            JSONObject value = field(report, PATH, "presence").getJSONObject("evidence").getJSONObject("target");
            assertFalse(value.has("value"));
            assertEquals(state.equals("READ_FAILED") ? "READ_FAILED" : "NOT_CHECKED", value.getString("state"));
        }
    }

    @Test public void unreadPartitionIsReportedEvenWithoutAnyEntries() throws Exception {
        Fixture f = new Fixture();
        for (JSONObject m : f.all()) {
            m.getJSONArray("scope").put(scope("/partitions/boot", "NOT_CHECKED"));
            m.put("completeness", "PARTIAL");
        }
        f.rules.getJSONArray("scope").put("/partitions/boot");
        JSONObject report = f.compare();
        assertEquals("INSUFFICIENT_EVIDENCE", report.getString("offlineComparison"));
        assertEquals(1, report.getJSONObject("scopeCounts").getInt("unchecked"));
        count(report, "verified", 1);
    }

    @Test public void partialManifestWithCompleteRootsStillCannotClaimComplete() throws Exception {
        Fixture f = new Fixture(); f.target.put("completeness", "PARTIAL");
        assertEquals("INSUFFICIENT_EVIDENCE", f.compare().getString("offlineComparison"));
    }

    @Test public void allEmptyInventoriesCannotProduceVacuousSuccess() throws Exception {
        Fixture f = new Fixture(); for (JSONObject m : f.all()) m.put("entries", new JSONArray());
        JSONObject report = f.compare(); count(report, "total", 0);
        assertEquals("INSUFFICIENT_EVIDENCE", report.getString("offlineComparison"));
    }

    @Test public void exactSemanticAllowancePreservesRawDifferenceAndReason() throws Exception {
        Fixture f = semanticFixture();
        JSONObject report = f.compare();
        count(report, "allowed", 1); count(report, "abnormal", 0); count(report, "same", 0);
        assertEquals("ALLOWED_DIFFERENCES", report.getString("offlineComparison"));
        JSONObject diff = field(report, "/data/managed/settings", "semantic.defaultMode");
        assertEquals("manual", diff.getJSONObject("evidence").getJSONObject("target").getString("value"));
        assertTrue(diff.getJSONObject("allowance").getString("reason").length() > 0);
        assertEquals(0, report.getJSONArray("unusedRuleIds").length());
    }

    private static Fixture semanticFixture() throws Exception {
        Fixture f = new Fixture();
        for (JSONObject m : f.all()) {
            m.put("entries", new JSONArray().put(entry("/data/managed/settings")));
            fields(m).put("semantic.defaultMode", observed("auto"));
        }
        fields(f.target).put("semantic.defaultMode", observed("manual"));
        f.rules.getJSONArray("allowances").put(allowance("/data/managed/settings", "semantic.defaultMode", "auto", "auto", "manual"));
        return f;
    }

    @Test public void allowanceCannotMaskOtherFieldOrUnapprovedValueOrMissingEvidence() throws Exception {
        Fixture f = semanticFixture(); fields(f.target).put("mode", observed("0777"));
        JSONObject report = f.compare(); count(report, "abnormal", 1); count(report, "allowed", 1);
        assertEquals("DIFFERENCES", report.getString("offlineComparison"));
        f = semanticFixture(); fields(f.target).put("semantic.defaultMode", observed("other"));
        count(f.compare(), "abnormal", 1); count(f.compare(), "allowed", 0);
        f = semanticFixture(); fields(f.target).put("semantic.defaultMode", unavailable("NOT_CHECKED"));
        report = f.compare(); count(report, "allowed", 0); count(report, "unchecked", 1);
        assertEquals(1, report.getJSONArray("unusedRuleIds").length());
    }

    @Test public void ruleDoesNotApplyToNeighborPath() throws Exception {
        Fixture f = semanticFixture();
        for (JSONObject m : f.all()) m.getJSONArray("entries").getJSONObject(0).put("path", "/data/managed/settings-other");
        count(f.compare(), "abnormal", 1); count(f.compare(), "allowed", 0);
    }

    @Test public void notApplicableRequiresTypeEvidenceAndExplicitRuleForActiveSource() throws Exception {
        Fixture f = new Fixture();
        for (JSONObject m : f.all()) fields(m).put("activeSource", unavailable("NOT_APPLICABLE"));
        count(f.compare(), "unchecked", 1);
        f.rules.getJSONArray("notApplicable").put(new JSONObject().put("id", "fixture-na").put("path", PATH)
                .put("field", "activeSource").put("reason", "仅作非活动资源比较").put("basis", "fixture-review"));
        count(f.compare(), "same", 1);
        fields(f.target).remove("type"); count(f.compare(), "unchecked", 1);
    }

    @Test public void notApplicableCannotHideFileHashOrSymlinkTarget() throws Exception {
        for (String name : new String[]{"sha256", "link"}) {
            Fixture f = new Fixture();
            for (JSONObject m : f.all()) {
                fields(m).put(name, unavailable("NOT_APPLICABLE"));
                if (name.equals("link")) fields(m).put("type", observed("symlink"));
            }
            f.rules.getJSONArray("notApplicable").put(new JSONObject().put("id", "fixture-na").put("path", PATH)
                    .put("field", name).put("reason", "测试无效适用性").put("basis", "fixture-review"));
            count(f.compare(), "unchecked", 1);
        }
    }

    @Test public void presentSideFailureIsNotHiddenByMissingOtherSide() throws Exception {
        Fixture f = new Fixture(); f.firmware.put("entries", new JSONArray());
        fields(f.target).put("sha256", unavailable("READ_FAILED"));
        JSONObject report = f.compare(); count(report, "failed", 1); count(report, "abnormal", 1);
        f = new Fixture(); f.firmware.put("entries", new JSONArray());
        fields(f.target).put("sha256", unavailable("NOT_APPLICABLE"));
        count(f.compare(), "unchecked", 1);
    }

    @Test public void metadataDifferenceBetweenPresentSidesSurvivesThirdSideAbsence() throws Exception {
        Fixture f = new Fixture(); f.firmware.put("entries", new JSONArray());
        fields(f.target).put("mode", observed("0777"));
        JSONObject diff = field(f.compare(), PATH, "mode");
        assertEquals("DIFFERENT", diff.getJSONObject("pairs").getString("boardTarget"));
        assertEquals("ANOMALOUS_DIFFERENCE", diff.getString("classification"));
    }

    @Test public void semanticTypesAreNotCoercedAndObjectKeyOrderIsIrrelevant() throws Exception {
        Fixture f = new Fixture();
        for (JSONObject m : f.all()) fields(m).put("semantic.schemaVersion", observed(1));
        fields(f.target).put("semantic.schemaVersion", observed("1"));
        count(f.compare(), "abnormal", 1);
        f = new Fixture();
        fields(f.board).put("xattrs", observed(new JSONObject().put("a", "1").put("b", "2")));
        fields(f.firmware).put("xattrs", observed(new JSONObject().put("b", "2").put("a", "1")));
        fields(f.target).put("xattrs", observed(new JSONObject().put("b", "2").put("a", "1")));
        count(f.compare(), "same", 1);
    }

    @Test public void mismatchedBindingsNeverProduceSamePairs() throws Exception {
        for (String key : new String[]{"baselineId", "baselineRevision", "firmwareId", "role"}) {
            Fixture f = new Fixture(); f.target.put(key, key.equals("role") ? "BOARD" : "different");
            JSONObject report = f.compare();
            assertEquals("NOT_COMPARABLE", report.getString("offlineComparison"));
            count(report, "same", 0); count(report, "unchecked", 1);
            assertEquals("NOT_COMPARABLE", field(report, PATH, "sha256").getJSONObject("pairs").getString("boardTarget"));
            assertEquals(HASH_A, field(report, PATH, "sha256").getJSONObject("evidence").getJSONObject("target").getString("value"));
        }
    }

    @Test public void everyContextDimensionAndPinnedSnapshotAndScopeMustMatch() throws Exception {
        for (String key : DiagnosticContract.CONTEXT_FIELDS) {
            Fixture f = new Fixture(); f.target.getJSONObject("context").put(key, "different");
            assertEquals(key, "NOT_COMPARABLE", f.compare().getString("offlineComparison"));
        }
        Fixture f = new Fixture(); f.board.put("snapshotId", "different");
        assertEquals("NOT_COMPARABLE", f.compare().getString("offlineComparison"));
        f = new Fixture(); f.firmware.put("snapshotId", "different");
        assertEquals("NOT_COMPARABLE", f.compare().getString("offlineComparison"));
        f = new Fixture(); f.target.put("snapshotId", "fixture-BOARD");
        assertEquals("NOT_COMPARABLE", f.compare().getString("offlineComparison"));
        f = new Fixture(); f.target.put("build", "different-build");
        assertEquals("NOT_COMPARABLE", f.compare().getString("offlineComparison"));
        f = new Fixture(); f.target.put("scope", new JSONArray().put(scope("/system", "COMPLETE")));
        JSONObject report = f.compare(); assertEquals("NOT_COMPARABLE", report.getString("offlineComparison"));
        assertEquals(2, report.getJSONArray("scope").length());
    }

    @Test public void freshnessBoundariesAreInclusiveAndFutureOrExpiredBlocked() throws Exception {
        Fixture f = new Fixture();
        assertEquals("MATCH_WITHIN_SCOPE", f.compareAt(NOW + 100).getString("offlineComparison"));
        assertEquals("NOT_COMPARABLE", f.compareAt(NOW + 101).getString("offlineComparison"));
        assertEquals("NOT_COMPARABLE", f.compareAt(NOW - 101).getString("offlineComparison"));
        f.rules.put("maxSnapshotAgeMs", 99);
        assertEquals("NOT_COMPARABLE", f.compare().getString("offlineComparison"));
    }

    @Test public void parsedInputsAndReportsAreDetached() throws Exception {
        Fixture f = new Fixture();
        DiagnosticManifest board = DiagnosticManifest.parse(f.board), firmware = DiagnosticManifest.parse(f.firmware), target = DiagnosticManifest.parse(f.target);
        DiagnosticRules rules = DiagnosticRules.parse(f.rules);
        fields(f.target).put("sha256", observed(HASH_B));
        fields(target.toJson()).remove("sha256");
        rules.toJson().put("baselineId", "changed");
        DiagnosticComparator comparator = new DiagnosticComparator();
        JSONObject report = comparator.compare(board, firmware, target, rules, NOW);
        field(report, PATH, "sha256").getJSONObject("evidence").getJSONObject("target").put("value", HASH_C);
        assertEquals("MATCH_WITHIN_SCOPE", comparator.compare(board, firmware, target, rules, NOW).getString("offlineComparison"));
    }

    @Test public void outputPathsAndFieldsHaveDeterministicOrder() throws Exception {
        Fixture f = new Fixture();
        for (JSONObject m : f.all()) m.getJSONArray("entries").put(entry("/data/managed/core"));
        JSONObject report = f.compare();
        assertEquals("/data/managed/core", report.getJSONArray("items").getJSONObject(0).getString("path"));
        JSONArray comparisons = item(report, PATH).getJSONArray("fields");
        assertEquals("presence", comparisons.getJSONObject(0).getString("field"));
        assertEquals("activation", comparisons.getJSONObject(1).getString("field"));
    }

    private interface Action { void run() throws Exception; }
    private static void rejects(String code, Action action) throws Exception {
        try { action.run(); fail("Expected contract rejection: " + code); }
        catch (DiagnosticContract.Invalid invalid) { assertEquals(code, invalid.code); }
    }

    @Test public void duplicateAndNonCanonicalPathsAreRejected() throws Exception {
        final Fixture duplicate = new Fixture(); duplicate.target.getJSONArray("entries").put(entry(PATH));
        rejects("DUPLICATE_PATH", new Action() { public void run() throws Exception { duplicate.compare(); } });
        for (String path : new String[]{"relative", "/system/../data/core", "/system/./core", "/system//core", "/system/core/", "/system\\core"}) {
            final JSONObject m = manifest("TARGET"); m.getJSONArray("entries").getJSONObject(0).put("path", path);
            rejects("INVALID_PATH", new Action() { public void run() throws Exception { DiagnosticManifest.parse(m); } });
        }
        final JSONObject outside = manifest("TARGET"); outside.getJSONArray("entries").getJSONObject(0).put("path", "/system-other/core");
        rejects("PATH_OUTSIDE_SCOPE", new Action() { public void run() throws Exception { DiagnosticManifest.parse(outside); } });
    }

    @Test public void overlappingEmptyOrContradictoryScopeIsRejected() throws Exception {
        final JSONObject overlap = manifest("TARGET"); overlap.getJSONArray("scope").put(scope("/system/bin", "COMPLETE"));
        rejects("OVERLAPPING_SCOPE", new Action() { public void run() throws Exception { DiagnosticManifest.parse(overlap); } });
        final JSONObject empty = manifest("TARGET"); empty.put("scope", new JSONArray());
        rejects("EMPTY_SCOPE", new Action() { public void run() throws Exception { DiagnosticManifest.parse(empty); } });
        final JSONObject incomplete = manifest("TARGET"); incomplete.getJSONArray("scope").put(0, scope("/system", "READ_FAILED"));
        rejects("CONTRADICTORY_COMPLETENESS", new Action() { public void run() throws Exception { DiagnosticManifest.parse(incomplete); } });
    }

    @Test public void absentParentAndFieldsWithoutPresentEntryAreRejected() throws Exception {
        final JSONObject parent = manifest("TARGET");
        parent.getJSONArray("entries").put(new JSONObject().put("path", "/system/bin").put("presence", observed("ABSENT")).put("fields", new JSONObject()));
        rejects("CONTRADICTORY_PRESENCE", new Action() { public void run() throws Exception { DiagnosticManifest.parse(parent); } });
        final JSONObject contradictory = manifest("TARGET"); contradictory.getJSONArray("entries").getJSONObject(0).put("presence", observed("ABSENT"));
        rejects("FIELDS_WITHOUT_PRESENT_ENTRY", new Action() { public void run() throws Exception { DiagnosticManifest.parse(contradictory); } });
    }

    @Test public void malformedEvidenceIsRejectedRatherThanCoerced() throws Exception {
        final JSONObject missingSource = manifest("TARGET"); fields(missingSource).getJSONObject("sha256").remove("source");
        rejects("STRING_REQUIRED", new Action() { public void run() throws Exception { DiagnosticManifest.parse(missingSource); } });
        final JSONObject nullValue = manifest("TARGET"); fields(nullValue).getJSONObject("sha256").put("value", JSONObject.NULL);
        rejects("VALUE_REQUIRED", new Action() { public void run() throws Exception { DiagnosticManifest.parse(nullValue); } });
        final JSONObject failedValue = manifest("TARGET"); fields(failedValue).put("sha256", unavailable("READ_FAILED").put("value", HASH_A));
        rejects("VALUE_WITHOUT_OBSERVATION", new Action() { public void run() throws Exception { DiagnosticManifest.parse(failedValue); } });
        final JSONObject owner = manifest("TARGET"); fields(owner).put("uid", observed("0"));
        rejects("INTEGER_REQUIRED", new Action() { public void run() throws Exception { DiagnosticManifest.parse(owner); } });
        final JSONObject mode = manifest("TARGET"); fields(mode).put("mode", observed("755"));
        rejects("INVALID_MODE", new Action() { public void run() throws Exception { DiagnosticManifest.parse(mode); } });
        final JSONObject hash = manifest("TARGET"); fields(hash).put("sha256", observed("short"));
        rejects("INVALID_SHA256", new Action() { public void run() throws Exception { DiagnosticManifest.parse(hash); } });
    }

    @Test public void unknownSchemaAndUnknownFieldsFailClosed() throws Exception {
        final JSONObject version = manifest("TARGET"); version.put("schemaVersion", 2);
        rejects("UNSUPPORTED_SCHEMA", new Action() { public void run() throws Exception { DiagnosticManifest.parse(version); } });
        final JSONObject unknown = manifest("TARGET"); unknown.put("excludeData", true);
        rejects("UNKNOWN_FIELD", new Action() { public void run() throws Exception { DiagnosticManifest.parse(unknown); } });
        final JSONObject fields = manifest("TARGET"); fields(fields).put("ignoredTypo", observed("present"));
        rejects("UNKNOWN_COMPARISON_FIELD", new Action() { public void run() throws Exception { DiagnosticManifest.parse(fields); } });
    }

    @Test public void invalidRulesCannotExcludeDirectoriesOrOmitJustification() throws Exception {
        final JSONObject wildcard = rules(); wildcard.getJSONArray("allowances").put(allowance("/data/*", "mode", "0755", "0755", "0777"));
        rejects("WILDCARD_RULE", new Action() { public void run() throws Exception { DiagnosticRules.parse(wildcard); } });
        final JSONObject reason = rules(); JSONObject rule = allowance(PATH, "mode", "0755", "0755", "0777"); rule.remove("reason"); reason.getJSONArray("allowances").put(rule);
        rejects("STRING_REQUIRED", new Action() { public void run() throws Exception { DiagnosticRules.parse(reason); } });
        final JSONObject outside = rules(); outside.getJSONArray("allowances").put(allowance("/vendor/core", "mode", "0755", "0755", "0777"));
        rejects("RULE_OUTSIDE_SCOPE", new Action() { public void run() throws Exception { DiagnosticRules.parse(outside); } });
        final JSONObject duplicate = rules(); JSONObject first = allowance(PATH, "mode", "0755", "0755", "0777");
        duplicate.getJSONArray("allowances").put(first).put(new JSONObject(first.toString()).put("id", "different-id"));
        rejects("DUPLICATE_RULE_FIELD", new Action() { public void run() throws Exception { DiagnosticRules.parse(duplicate); } });
    }

    @Test public void scaleDepthCyclesAndControlCharactersAreBoundedBeforeCopying() throws Exception {
        final JSONObject huge = manifest("TARGET"); huge.put("collectorVersion", repeat('x', DiagnosticContract.MAX_STRING + 1));
        rejects("INPUT_LIMIT", new Action() { public void run() throws Exception { DiagnosticManifest.parse(huge); } });
        final JSONObject cycle = manifest("TARGET"); cycle.put("cycle", cycle);
        rejects("INPUT_LIMIT", new Action() { public void run() throws Exception { DiagnosticManifest.parse(cycle); } });
        final JSONObject nul = manifest("TARGET"); nul.put("collectorVersion", "value\u0000suffix");
        rejects("CONTROL_CHARACTER", new Action() { public void run() throws Exception { DiagnosticManifest.parse(nul); } });
        final JSONObject excessive = manifest("TARGET"); JSONArray entries = new JSONArray();
        for (int i = 0; i <= DiagnosticContract.MAX_ENTRIES; i++) entries.put(new JSONObject());
        excessive.put("entries", entries);
        rejects("INPUT_LIMIT", new Action() { public void run() throws Exception { DiagnosticManifest.parse(excessive); } });
    }

    @Test public void maximumEntryBoundaryAndAggregateUnionAreBounded() throws Exception {
        final Fixture f = new Fixture();
        for (JSONObject m : f.all()) {
            JSONArray entries = new JSONArray();
            for (int i = 0; i < DiagnosticContract.MAX_ENTRIES; i++) entries.put(new JSONObject().put("path", "/data/managed/item-" + i)
                    .put("presence", observed("ABSENT")).put("fields", new JSONObject()));
            m.put("entries", entries);
        }
        count(f.compare(), "total", DiagnosticContract.MAX_ENTRIES);
        f.target.getJSONArray("entries").getJSONObject(0).put("path", "/data/managed/extra");
        rejects("COMPARISON_LIMIT", new Action() { public void run() throws Exception { f.compare(); } });
    }

    @Test public void limitRejectionExplicitlyReportsNotComparedAndUnknownTotal() throws Exception {
        JSONObject report = DiagnosticContract.invalid("COMPARISON_LIMIT").toJson();
        assertEquals("NOT_COMPARED", report.getString("offlineComparison"));
        assertEquals("NOT_ASSESSED", report.getString("systemConsistency"));
        assertEquals("NOT_IMPLEMENTED", report.getString("aggregationStatus"));
        assertTrue(report.getBoolean("requiresPartitionAggregation"));
        assertTrue(report.getJSONObject("counts").isNull("total"));
        assertTrue(report.getJSONObject("counts").isNull("unchecked"));
        assertEquals(0, report.getJSONObject("counts").getInt("verified"));
        JSONObject complete = new Fixture().compare();
        assertEquals("SINGLE_BOUNDED_BATCH", complete.getString("comparisonExtent"));
        assertEquals("NOT_ESTABLISHED", complete.getString("wholeSystemCoverage"));
    }
}
