package net.elfradio.d31bootstrap.diagnostics.collection;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticCoverageComparison;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticManifest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static net.elfradio.d31bootstrap.diagnostics.collection.CollectionFixture.*;

public class SystemSupportConfigurationTest {
    public static final String TARGET = "/data/local/d31-system-support/start.sh";
    public static final String FIELD = "semantic.system_support.root";
    public static final String ROOT = "/data/local/d31-system-support";

    public static byte[] script() throws Exception {
        return resource("system-support-start.sh", 520, "12b040a1b32f48197a72b5d595fe59457735be2738ae1f12b1c206fdb51f527b");
    }

    private static byte[] resource(String name, int length, String sha256) throws Exception {
        try (InputStream input = SystemSupportConfigurationTest.class.getResourceAsStream("/diagnostics/" + name)) {
            if (input == null) throw new AssertionError("缺少冻结脚本资源");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            byte[] result = bytes.toByteArray();
            assertEquals(length, result.length);
            assertEquals(sha256, hash(new String(result, StandardCharsets.UTF_8)));
            return result;
        }
    }

    public static byte[] wrongRoot() throws Exception {
        return new String(script(), StandardCharsets.UTF_8).replace("ROOT=" + ROOT, "ROOT=" + ROOT + "-old")
                .getBytes(StandardCharsets.UTF_8);
    }

    public static JSONObject collect(byte[] bytes) throws Exception {
        Clock clock = new Clock(); Access access = access(clock, TARGET, bytes);
        return new ManifestCollector(access, clock).collect(identity(), TARGET, limits(1, bytes.length * 2L, 0)).manifest().toJson();
    }

    private static Access access(Clock clock, String path, byte[] bytes) throws Exception {
        Access access = new Access(clock).add(path, "file", "");
        access.nodes.get(path).bytes = bytes.clone();
        return access;
    }

    public static JSONObject expected() throws Exception {
        JSONObject evidence = new JSONObject().put("state", "OBSERVED").put("source", "fixture");
        return identity().put("schemaVersion", 1).put("role", "FIRMWARE").put("snapshotId", "fixture-firmware-manifest")
                .put("collectorVersion", "fixture").put("capturedAtMs", 10000).put("validUntilMs", 12000)
                .put("uptimeMs", 0).put("completeness", "COMPLETE")
                .put("scope", new JSONArray().put(new JSONObject().put("path", TARGET).put("state", "COMPLETE").put("source", "fixture")))
                .put("entries", new JSONArray().put(new JSONObject().put("path", TARGET)
                        .put("presence", new JSONObject(evidence.toString()).put("value", "PRESENT"))
                        .put("fields", new JSONObject().put(FIELD, new JSONObject(evidence.toString()).put("value", ROOT)))));
    }

    public static JSONObject report(JSONObject observed) throws Exception {
        return new DiagnosticCoverageComparison().compare(DiagnosticManifest.parse(observed), DiagnosticManifest.parse(expected()), 10100);
    }

    public static JSONObject semantic(JSONObject manifest) throws Exception {
        return manifest.getJSONArray("entries").getJSONObject(0).getJSONObject("fields").getJSONObject(FIELD);
    }

    public static String relation(JSONObject report) throws Exception {
        JSONArray fields = report.getJSONArray("entries").getJSONObject(0).getJSONArray("fields");
        for (int i = 0; i < fields.length(); i++) if (fields.getJSONObject(i).getString("field").equals(FIELD))
            return fields.getJSONObject(i).getString("pair");
        throw new AssertionError("报告缺少配置字段");
    }

    @Test public void frozenScriptProducesRootWithoutExtraIo() throws Exception {
        byte[] bytes = script(); Clock clock = new Clock(); Access access = access(clock, TARGET, bytes);
        ManifestCollector.Result result = new ManifestCollector(access, clock).collect(identity(), TARGET, limits(1, 1040, 0));
        assertEquals(ROOT, semantic(result.manifest().toJson()).getString("value"));
        assertEquals(2, access.opens); assertEquals(2, access.closes); assertEquals(1040, result.index().getLong("readBytes"));
        assertEquals("SAME", relation(report(result.manifest().toJson())));
        assertEquals("NOT_ASSESSED", result.index().getString("systemConsistency"));
    }

    @Test public void wrongRootIsActualConfigurationDifference() throws Exception {
        JSONObject manifest = collect(wrongRoot());
        assertEquals(ROOT + "-old", semantic(manifest).getString("value"));
        assertEquals("DIFFERENT", relation(report(manifest)));
        assertEquals("NOT_PERFORMED", report(manifest).getString("runtimeVerification"));
    }

    @Test public void currentSourceTemplateAlsoProvidesTheActualRoot() throws Exception {
        byte[] bytes = resource("system-support-source-start.sh", 139,
                "6f8eb77cf06b0fb5f0b8f5b751db1abac66cbbf9bd277b32749a4e1032f112b0");
        assertEquals(ROOT, semantic(collect(bytes)).getString("value"));
        byte[] changed = new String(bytes, StandardCharsets.UTF_8).replace("ROOT=" + ROOT, "ROOT=" + ROOT + "-old")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(ROOT + "-old", semantic(collect(changed)).getString("value"));
        assertEquals("DIFFERENT", relation(report(collect(changed))));
    }

    @Test public void unknownScriptDoesNotGuessRootFromAssignment() throws Exception {
        String original = new String(script(), StandardCharsets.UTF_8);
        for (String changed : new String[]{ original.replace("exec \"$ROOT/guard\"", "exit 0"),
                original + "ROOT=/data/local/other\n", original.replace("\n", "\r\n"),
                "#!/system/bin/sh\nROOT=" + ROOT + "\nexec \"$ROOT/guard\"\n" }) {
            JSONObject state = semantic(collect(changed.getBytes(StandardCharsets.UTF_8)));
            assertEquals("NOT_CHECKED", state.getString("state")); assertFalse(state.has("value"));
        }
    }

    @Test public void unsupportedRootSyntaxCannotBecomeObserved() throws Exception {
        String original = new String(script(), StandardCharsets.UTF_8);
        for (String root : new String[]{"$(touch /fixture)", "\"" + ROOT + "\"", "/data/local/../other", "/data//local/other", "/system/other"}) {
            JSONObject state = semantic(collect(original.replace("ROOT=" + ROOT, "ROOT=" + root).getBytes(StandardCharsets.UTF_8)));
            assertEquals("NOT_CHECKED", state.getString("state")); assertFalse(state.has("value"));
        }
    }

    @Test public void changedSecondReadDoesNotPublishFirstPassConfiguration() throws Exception {
        Clock clock = new Clock(); Access access = access(clock, TARGET, script());
        access.secondOpenBytes = new String(script(), StandardCharsets.UTF_8).replace("support\n", "supporx\n").getBytes(StandardCharsets.UTF_8);
        JSONObject state = semantic(new ManifestCollector(access, clock).collect(identity(), TARGET, limits(1, 1040, 0)).manifest().toJson());
        assertEquals("UNSTABLE", state.getString("state")); assertFalse(state.has("value"));
    }

    @Test public void readFailureAndBudgetRemainMissing() throws Exception {
        Clock clock = new Clock(); Access access = access(clock, TARGET, script()); access.failAfter = 0;
        JSONObject state = semantic(new ManifestCollector(access, clock).collect(identity(), TARGET, limits(1, 1040, 0)).manifest().toJson());
        assertEquals("READ_FAILED", state.getString("state")); assertFalse(state.has("value"));
        access = access(clock, TARGET, script());
        state = semantic(new ManifestCollector(access, clock).collect(identity(), TARGET, limits(1, 1039, 0)).manifest().toJson());
        assertEquals("NOT_CHECKED", state.getString("state")); assertEquals(0, access.opens); assertFalse(state.has("value"));
    }

    @Test public void oversizedContentRetainsHashButNotConfiguration() throws Exception {
        byte[] bytes = new byte[4097]; java.util.Arrays.fill(bytes, (byte) 'x');
        JSONObject manifest = collect(bytes);
        assertEquals("NOT_CHECKED", semantic(manifest).getString("state"));
        assertEquals("OBSERVED", field(manifest, TARGET, "sha256").getString("state"));
        assertEquals("UNKNOWN", relation(report(manifest)));
    }

    @Test public void otherPathsNeverGainThisConfiguration() throws Exception {
        Clock clock = new Clock(); Access access = access(clock, TARGET + ".old", script());
        JSONObject manifest = new ManifestCollector(access, clock).collect(identity(), TARGET + ".old", limits(1, 1040, 0)).manifest().toJson();
        assertFalse(manifest.getJSONArray("entries").getJSONObject(0).getJSONObject("fields").has(FIELD));
    }

    @Test public void symlinkDoesNotBecomeConfigurationOrGetRead() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add(TARGET, "symlink", "");
        JSONObject manifest = new ManifestCollector(access, clock).collect(identity(), TARGET, limits(1, 1040, 0)).manifest().toJson();
        assertEquals("NOT_CHECKED", semantic(manifest).getString("state")); assertEquals(0, access.opens);
    }

    @Test public void failedStatKeepsUnknownPresenceWithoutInventedFields() throws Exception {
        Clock clock = new Clock(); Access access = access(clock, TARGET, script()); access.failingStat = TARGET;
        JSONObject manifest = new ManifestCollector(access, clock).collect(identity(), TARGET, limits(1, 1040, 0)).manifest().toJson();
        JSONObject entry = manifest.getJSONArray("entries").getJSONObject(0);
        assertEquals("READ_FAILED", entry.getJSONObject("presence").getString("state")); assertEquals(0, entry.getJSONObject("fields").length());
        assertEquals("UNKNOWN", relation(report(manifest)));
    }

    @Test public void knownScriptIsCollectedDuringExistingDirectoryWalk() throws Exception {
        Clock clock = new Clock(); Access access = access(clock, TARGET, script()).add(ROOT, "directory", "");
        JSONObject manifest = new ManifestCollector(access, clock).collect(identity(), ROOT, limits(2, 1040, 1)).manifest().toJson();
        assertEquals(ROOT, field(manifest, TARGET, FIELD).getString("value")); assertEquals(2, access.opens);
    }
}
