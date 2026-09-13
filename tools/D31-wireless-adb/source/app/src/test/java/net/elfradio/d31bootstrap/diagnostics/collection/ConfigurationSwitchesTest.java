package net.elfradio.d31bootstrap.diagnostics.collection;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticManifest;
import net.elfradio.d31bootstrap.diagnostics.DiagnosticCoverageComparison;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static net.elfradio.d31bootstrap.diagnostics.collection.CollectionFixture.*;

public class ConfigurationSwitchesTest {
    private static final String[] PATHS = {ConfigurationSwitches.SUPPORT, ConfigurationSwitches.RECOVERY, ConfigurationSwitches.RESCUE};
    static byte[] script(String path) throws Exception {
        String resource = path.equals(PATHS[0]) ? "system-support-start.sh"
                : path.equals(PATHS[1]) ? "recovery-volume-persistent.sh" : "rescue-start.sh";
        try (InputStream input = ConfigurationSwitchesTest.class.getResourceAsStream("/diagnostics/" + resource)) {
            return read(input);
        }
    }
    private static byte[] read(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int n;
        while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
        return bytes.toByteArray();
    }
    static Access fixture(Clock clock, String path, byte[] bytes, String kind) throws Exception {
        Access access = new Access(clock).add("/data/local", "directory", "");
        String parent = path.substring(0, path.lastIndexOf('/'));
        access.add(parent, "directory", "").add(path, "file", ""); access.nodes.get(path).bytes = bytes;
        String marker = ConfigurationSwitches.marker(path, bytes);
        if (marker != null && !kind.equals("ABSENT")) access.add(marker, kind, "");
        return access;
    }
    static JSONObject collect(Access access, Clock clock, String scope) throws Exception {
        return new ManifestCollector(access, clock).collect(identity(), scope, limits(32, 65536, 3)).manifest().toJson();
    }
    static JSONObject semantic(JSONObject manifest, String path) throws Exception {
        return field(manifest, path, ConfigurationSwitches.field(path));
    }
    private static JSONObject collect(String path, String kind) throws Exception {
        Clock clock = new Clock(); return collect(fixture(clock, path, script(path), kind), clock, "/data/local");
    }
    @Test public void frozenConsumersAndMarkerTypesProduceRealBooleans() throws Exception {
        for (String path : PATHS) for (String kind : new String[]{"file", "directory", "block", "ABSENT"}) {
            JSONObject evidence = semantic(collect(path, kind), path);
            assertEquals("OBSERVED", evidence.getString("state"));
            assertEquals(!kind.equals("ABSENT") && (path.equals(PATHS[0]) || kind.equals("file")), evidence.getBoolean("value"));
        }
    }
    @Test public void linksFollowConsumerContractWithoutFollowingFilesystemLinks() throws Exception {
        for (String path : PATHS) {
            JSONObject evidence = semantic(collect(path, "symlink"), path);
            assertEquals(path.equals(PATHS[1]) ? "OBSERVED" : "NOT_CHECKED", evidence.getString("state"));
            if (path.equals(PATHS[1])) assertFalse(evidence.getBoolean("value")); else assertFalse(evidence.has("value"));
        }
    }
    @Test public void absentDiffersFromDeniedMarkerAndDeniedParent() throws Exception {
        for (String path : PATHS) for (boolean parent : new boolean[]{false, true}) {
            Clock clock = new Clock(); Access access = fixture(clock, path, script(path), "ABSENT");
            access.failingStat = parent ? path.substring(0, path.lastIndexOf('/')) : ConfigurationSwitches.marker(path, script(path));
            // 直接从消费者父目录采集时，父目录拒绝会阻止整个范围进入，不能补造关闭。
            JSONObject manifest = collect(access, clock, "/data/local");
            if (parent) assertEquals("READ_FAILED", entry(manifest, access.failingStat).getJSONObject("presence").getString("state"));
            else {
                JSONObject evidence = semantic(manifest, path);
                assertEquals("READ_FAILED", evidence.getString("state")); assertFalse(evidence.has("value"));
            }
        }
    }
    @Test public void singleScriptScopeDoesNotProbeOutsideScope() throws Exception {
        for (String path : PATHS) {
            Clock clock = new Clock(); Access access = fixture(clock, path, script(path), "file");
            JSONObject evidence = semantic(collect(access, clock, path), path);
            assertEquals("SWITCH_MARKER_OUTSIDE_SCOPE", evidence.getString("reason"));
        }
    }
    @Test public void changedTemplateDoesNotGuessFromFilename() throws Exception {
        for (String path : PATHS) {
            byte[] bytes = (new String(script(path), StandardCharsets.UTF_8) + "# changed\n").getBytes(StandardCharsets.UTF_8);
            Clock clock = new Clock(); Access access = fixture(clock, path, bytes, "ABSENT");
            assertEquals("SWITCH_CONSUMER_TEMPLATE_NOT_RECOGNIZED", semantic(collect(access, clock, "/data/local"), path).getString("reason"));
        }
    }
    @Test public void actualRootChoosesItsOwnDisabledMarker() throws Exception {
        String path = PATHS[0]; byte[] bytes = SystemSupportConfigurationTest.wrongRoot();
        Clock clock = new Clock(); Access access = fixture(clock, path, bytes, "file");
        access.add("/data/local/d31-system-support-old", "directory", "");
        assertTrue(semantic(collect(access, clock, "/data/local"), path).getBoolean("value"));
        assertEquals("SWITCH_MARKER_OUTSIDE_SCOPE", semantic(collect(access, clock, SystemSupportConfigurationTest.ROOT), path).getString("reason"));
    }
    @Test public void markerChangesBetweenReadsCannotBecomeObserved() throws Exception {
        final String path = PATHS[1], marker = ConfigurationSwitches.marker(path, script(path));
        Clock clock = new Clock(); final Access base = fixture(clock, path, script(path), "file");
        // 首次缺失后出现标记，必须保留不稳定证据。
        base.nodes.remove(marker);
        CollectionAccess changingAbsence = new CollectionAccess() {
            int reads;
            public Stat lstat(String p) throws IOException {
                if (p.equals(marker) && ++reads == 2) {
                    try { base.add(marker, "file", ""); } catch (Exception e) { throw new IOException(e); }
                }
                return base.lstat(p);
            }
            public String readLink(String p) throws IOException { return base.readLink(p); }
            public Handle openRegular(String p, Stat s) throws IOException { return base.openRegular(p,s); }
            public Listing list(String p, Stat s, int n, long b, long t) throws IOException { return base.list(p,s,n,b,t); }
        };
        JSONObject result = new ManifestCollector(changingAbsence, clock).collect(identity(), "/data/local", limits(32,65536,3)).manifest().toJson();
        assertEquals("UNSTABLE", semantic(result, path).getString("state"));
    }
    @Test public void failureReadingConsumerNeverPublishesSwitch() throws Exception {
        Clock clock = new Clock(); String path = PATHS[1]; Access access = fixture(clock,path,script(path),"ABSENT"); access.failAfter=0;
        assertEquals("READ_FAILED", semantic(collect(access,clock,"/data/local"),path).getString("state"));
    }
    @Test public void evidenceCountsLeaveFiveGapsAndDetectSwitchDifference() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/data/local", "directory", "");
        for (String path : PATHS) access.nodes.putAll(fixture(clock,path,script(path),"file").nodes);
        JSONObject observed = collect(access,clock,"/data/local");
        JSONObject firmware = new JSONObject(observed.toString()).put("role","FIRMWARE");
        semantic(firmware,PATHS[0]).put("value",false);
        JSONObject report = new DiagnosticCoverageComparison().compare(DiagnosticManifest.parse(observed),DiagnosticManifest.parse(firmware),11000);
        JSONObject coverage = report.getJSONObject("configurationCoverage");
        assertEquals(4,coverage.getInt("bothObservedItems")); assertEquals(5,coverage.getInt("gapItems"));
        assertEquals("DIFFERENT",coverage.getJSONArray("items").getJSONObject(1).getString("pair"));
        assertEquals("NOT_ASSESSED",report.getString("systemConsistency")); assertFalse(report.getBoolean("repairPlanGenerated"));
        semantic(firmware,PATHS[0]).put("value","false");
        report = new DiagnosticCoverageComparison().compare(DiagnosticManifest.parse(observed),DiagnosticManifest.parse(firmware),11000);
        assertEquals(6,report.getJSONObject("configurationCoverage").getInt("gapItems"));
    }
    public static void main(String[] args) throws Exception {
        Clock clock = new Clock(); byte[] bytes = read(System.in);
        System.out.println(collect(fixture(clock,args[0],bytes,args[1]),clock,"/data/local"));
    }
}
