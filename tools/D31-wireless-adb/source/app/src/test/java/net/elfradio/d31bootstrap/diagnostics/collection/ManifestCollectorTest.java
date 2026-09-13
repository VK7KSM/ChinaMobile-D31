package net.elfradio.d31bootstrap.diagnostics.collection;

import net.elfradio.d31bootstrap.diagnostics.DiagnosticComparator;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticManifest;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticRules;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static net.elfradio.d31bootstrap.diagnostics.collection.CollectionFixture.*;
import static org.junit.Assert.*;

public class ManifestCollectorTest {
    @Test public void regularBytesAreReallyHashedTwiceAndMissingMetadataStaysUnknown() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/scope/core", "file", "abc");
        ManifestCollector.Result result = new ManifestCollector(access, clock).collect(identity(), "/scope/core", limits(1, 6, 0));
        JSONObject manifest = result.manifest().toJson();
        assertEquals("COMPLETE", manifest.getString("completeness"));
        assertEquals(hash("abc"), field(manifest, "/scope/core", "sha256").getString("value"));
        assertEquals("0644", field(manifest, "/scope/core", "mode").getString("value"));
        assertEquals(6, result.index().getLong("readBytes")); assertEquals(2, access.opens); assertEquals(access.opens, access.closes);
        for (String name : Arrays.asList("selinux", "xattrs", "activeSource", "mountSource", "activation")) {
            assertEquals("NOT_CHECKED", field(manifest, "/scope/core", name).getString("state"));
        }
        assertEquals("NOT_ASSESSED", result.index().getString("systemConsistency"));
        DiagnosticManifest.parse(manifest);
    }

    @Test public void collectedMetadataGapsCannotPassExistingComparator() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/scope/core", "file", "abc");
        JSONObject target = new ManifestCollector(access, clock).collect(identity(), "/scope/core", limits(1, 6, 0)).manifest().toJson();
        JSONObject board = new JSONObject(target.toString()).put("role", "BOARD").put("snapshotId", "fixture-board");
        JSONObject firmware = new JSONObject(target.toString()).put("role", "FIRMWARE").put("snapshotId", "fixture-firmware-manifest");
        JSONObject rules = new JSONObject().put("schemaVersion", 1).put("rulesVersion", "fixture-rules")
                .put("baselineId", "fixture-baseline").put("baselineRevision", "1").put("boardSnapshotId", "fixture-board")
                .put("firmwareId", "fixture-firmware").put("firmwareSnapshotId", "fixture-firmware-manifest")
                .put("context", identity().getJSONObject("context")).put("scope", new JSONArray().put("/scope/core"))
                .put("maxSnapshotAgeMs", 1000).put("allowances", new JSONArray()).put("notApplicable", new JSONArray());
        JSONObject report = new DiagnosticComparator().compare(DiagnosticManifest.parse(board), DiagnosticManifest.parse(firmware),
                DiagnosticManifest.parse(target), DiagnosticRules.parse(rules), clock.wallTimeMillis());
        assertEquals("INSUFFICIENT_EVIDENCE", report.getString("offlineComparison"));
        assertEquals(0, report.getJSONObject("counts").getInt("same"));
    }

    @Test public void symlinkTargetIsRecordedAndNeverReadOrTraversed() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/scope", "directory", "")
                .add("/scope/link", "symlink", "").add("/outside", "file", "不可读取的哨兵");
        JSONObject result = new ManifestCollector(access, clock).collect(identity(), "/scope", limits(5, 100, 3)).manifest().toJson();
        assertEquals(2, result.getJSONArray("entries").length()); assertEquals(0, access.opens);
        assertEquals("/outside", field(result, "/scope/link", "link").getString("value"));
        assertEquals("COMPLETE", result.getString("completeness"));
    }

    @Test public void byteLimitNeverPublishesPartialHash() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/scope/file", "file", "abc");
        ManifestCollector.Result result = new ManifestCollector(access, clock).collect(identity(), "/scope/file", limits(1, 5, 0));
        JSONObject manifest = result.manifest().toJson();
        assertEquals("PARTIAL", manifest.getString("completeness")); assertEquals(0, access.opens);
        assertEquals("BYTE_LIMIT", field(manifest, "/scope/file", "sha256").getString("reason"));
        assertFalse(field(manifest, "/scope/file", "sha256").has("value"));
    }

    @Test public void changedSameLengthBytesWithUnchangedStatStillFailTwoPassHash() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/scope/file", "file", "abc");
        access.secondOpenBytes = "def".getBytes("UTF-8");
        JSONObject manifest = new ManifestCollector(access, clock).collect(identity(), "/scope/file", limits(1, 6, 0)).manifest().toJson();
        assertEquals("UNSTABLE", field(manifest, "/scope/file", "sha256").getString("state"));
        assertEquals("PARTIAL", manifest.getString("completeness")); assertEquals(2, access.closes);
    }

    @Test public void fileIdentityChangeDuringReadFailsAndClosesHandle() throws Exception {
        Clock clock = new Clock(); final Access access = new Access(clock).add("/scope/file", "file", "abc");
        access.onRead = new Runnable() { public void run() { access.nodes.get("/scope/file").inode++; } };
        JSONObject manifest = new ManifestCollector(access, clock).collect(identity(), "/scope/file", limits(1, 6, 0)).manifest().toJson();
        assertEquals("UNSTABLE", field(manifest, "/scope/file", "sha256").getString("state"));
        assertEquals(1, access.closes);
    }

    @Test public void readFailureAndDeletedChildAreNeverAbsence() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/scope", "directory", "").add("/scope/file", "file", "abc");
        access.failingStat = "/scope/file";
        JSONObject manifest = new ManifestCollector(access, clock).collect(identity(), "/scope", limits(5, 100, 4)).manifest().toJson();
        assertEquals("READ_FAILED", entry(manifest, "/scope/file").getJSONObject("presence").getString("state"));
        assertEquals("PARTIAL", manifest.getString("completeness"));
        access = new Access(clock);
        manifest = new ManifestCollector(access, clock).collect(identity(), "/missing", limits(1, 0, 0)).manifest().toJson();
        assertEquals("READ_FAILED", entry(manifest, "/missing").getJSONObject("presence").getString("state"));
        assertFalse(entry(manifest, "/missing").getJSONObject("presence").has("value"));
    }

    @Test public void failedDirectoryEnumerationDoesNotLookEmptyAndComplete() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/scope", "directory", ""); access.failingList = "/scope";
        JSONObject manifest = new ManifestCollector(access, clock).collect(identity(), "/scope", limits(3, 10, 3)).manifest().toJson();
        assertEquals("PARTIAL", manifest.getString("completeness"));
        assertEquals("READ_FAILED", field(manifest, "/scope", "semantic.enumeration").getString("state"));
    }

    @Test public void timeBudgetBeforeFirstStatAndAfterReadCannotSucceed() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/scope/file", "file", "abc"); access.statCost = 1000;
        JSONObject manifest = new ManifestCollector(access, clock).collect(identity(), "/scope/file", limits(1, 10, 0)).manifest().toJson();
        assertEquals("NOT_CHECKED", entry(manifest, "/scope/file").getJSONObject("presence").getString("state")); assertEquals(0, access.opens);
        access = new Access(clock).add("/scope/file", "file", "abc"); access.readCost = 1000;
        manifest = new ManifestCollector(access, clock).collect(identity(), "/scope/file", limits(1, 6, 0)).manifest().toJson();
        assertEquals("TIME_LIMIT", field(manifest, "/scope/file", "sha256").getString("reason")); assertEquals(1, access.closes);
    }

    @Test public void interruptDoesNotStartAccessAndRetainsInterruptFlag() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock);
        Thread.currentThread().interrupt();
        try {
            ManifestCollector.Result result = new ManifestCollector(access, clock).collect(identity(), "/scope", limits(1, 0, 0));
            assertEquals("PARTIAL", result.manifest().toJson().getString("completeness"));
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(0, access.stats);
        } finally { Thread.interrupted(); }
    }

    @Test public void depthBudgetAndExactEntryBoundaryAreExplicit() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/scope", "directory", "").add("/scope/child", "file", "");
        ManifestCollector collector = new ManifestCollector(access, clock);
        assertEquals("PARTIAL", collector.collect(identity(), "/scope", limits(2, 0, 0)).manifest().toJson().getString("completeness"));
        assertEquals("COMPLETE", collector.collect(identity(), "/scope", limits(2, 0, 1)).manifest().toJson().getString("completeness"));
        ManifestCollector.Result limited = collector.collect(identity(), "/scope", limits(1, 0, 1));
        assertEquals(1, limited.manifest().toJson().getJSONArray("entries").length());
        assertTrue(limited.index().getJSONArray("reasons").toString().contains("ENTRY_LIMIT"));
    }

    @Test public void moreThan4096RetainsBoundedPrefixAndNeverClaimsComplete() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/scope", "directory", "");
        for (int i = 0; i < 4096; i++) access.add("/scope/file-" + i, "file", "");
        ManifestCollector.Result result = new ManifestCollector(access, clock).collect(identity(), "/scope", limits(4096, 0, 1));
        assertEquals(4096, result.manifest().toJson().getJSONArray("entries").length());
        assertEquals("PARTIAL", result.manifest().toJson().getString("completeness"));
        assertTrue(result.index().getJSONArray("reasons").toString().contains("ENTRY_LIMIT"));
    }

    @Test public void metadataOutputBudgetStopsBeforeOversizedManifest() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/scope", "directory", "");
        for (int i = 0; i < 100; i++) access.add("/scope/file-" + i, "file", "");
        CollectionLimits limits = new CollectionLimits(101, 0, 0, 1000, 1, 16384, 1000);
        ManifestCollector.Result result = new ManifestCollector(access, clock).collect(identity(), "/scope", limits);
        assertTrue(result.index().getJSONArray("reasons").toString().contains("MANIFEST_LIMIT"));
        assertTrue(result.manifest().toJson().toString().length() < 16384);
    }

    @Test public void duplicateTraversalAndInjectionNamesRejectEnumeration() throws Exception {
        for (java.util.List<String> names : Arrays.asList(Arrays.asList("file", "file"), Arrays.asList("../outside"), Arrays.asList("line\nbreak"))) {
            Clock clock = new Clock(); Access access = new Access(clock).add("/scope", "directory", ""); access.injectedNames = names;
            JSONObject manifest = new ManifestCollector(access, clock).collect(identity(), "/scope", limits(5, 0, 2)).manifest().toJson();
            assertEquals("PARTIAL", manifest.getString("completeness")); assertEquals(1, manifest.getJSONArray("entries").length());
        }
    }

    @Test public void directoryMutationBetweenEnumerationsIsVisible() throws Exception {
        Clock clock = new Clock(); final Access access = new Access(clock).add("/scope", "directory", "");
        access.onList = new Runnable() { public void run() { access.nodes.put("/scope/new", new Node("file", 9, new byte[0])); } };
        JSONObject manifest = new ManifestCollector(access, clock).collect(identity(), "/scope", limits(5, 0, 2)).manifest().toJson();
        assertEquals("PARTIAL", manifest.getString("completeness"));
        assertEquals("UNSTABLE", field(manifest, "/scope", "semantic.enumeration").getString("state"));
    }

    @Test public void invalidIdentityAndOverlappingBatchesAreRejectedBeforeIo() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock);
        try { new ManifestCollector(access, clock).collect(identity().put("unexpected", true), "/scope", limits(1, 0, 0)); fail(); }
        catch (IllegalArgumentException expected) { assertEquals(0, access.stats); }
        ManifestCollector.validateIndependentScopes(Arrays.asList("/system", "/data/managed", "/system-other"));
        for (java.util.List<String> scopes : Arrays.asList(Arrays.asList("/", "/data"), Arrays.asList("/data", "/data/a"), Arrays.asList("/data", "/data"))) {
            try { ManifestCollector.validateIndependentScopes(scopes); fail(); }
            catch (IllegalArgumentException expected) { assertEquals("OVERLAPPING_SCOPE", expected.getMessage()); }
        }
    }
}
