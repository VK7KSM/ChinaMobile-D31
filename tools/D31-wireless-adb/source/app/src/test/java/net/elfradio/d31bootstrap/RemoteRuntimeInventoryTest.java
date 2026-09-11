package net.elfradio.d31bootstrap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class RemoteRuntimeInventoryTest {
    private static String map(String path) {
        return "70000000-70001000 r--p 00000000 103:00 123 " + path + "\n";
    }
    @Test public void mapsRecognizeExactApkAndEncodedSystemAndReleaseCache() {
        for (String apk : new String[]{"/system/priv-app/D31ElfRemote/D31ElfRemote.apk",
                "/data/local/d31-remote/releases/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/remote.apk"}) {
            assertTrue(RemoteRuntimeInventory.mapsArchive(map(apk), apk));
            String encoded = apk.substring(1).replace('/', '@') + "@classes.dex";
            for (String abi : new String[]{"arm", "arm64"}) {
                assertTrue(RemoteRuntimeInventory.mapsArchive(map("/data/dalvik-cache/" + abi + "/" + encoded), apk));
            }
        }
    }
    @Test public void mapsRejectFalsePrefixesSuffixesAndDifferentEncoding() {
        String apk = "/system/priv-app/D31ElfRemote/D31ElfRemote.apk";
        String encoded = "system@priv-app@D31ElfRemote@D31ElfRemote.apk@classes.dex";
        for (String path : new String[]{"/fake" + apk, apk + ".old", apk + " (deleted)",
                "/fake/data/dalvik-cache/arm64/" + encoded,
                "/data/dalvik-cache-extra/arm64/" + encoded,
                "/data/dalvik-cache/arm64/prefix" + encoded,
                "/data/dalvik-cache/arm64/" + encoded + ".old",
                "/data/dalvik-cache/arm64/" + encoded + " (deleted)",
                "/data/dalvik-cache/arm64/" + encoded.replace("priv-app", "app"),
                "/data/dalvik-cache/arm64/subdir/" + encoded}) {
            assertFalse(path, RemoteRuntimeInventory.mapsArchive(map(path), apk));
        }
    }
    @Test public void mapsRequirePathColumnAndUnambiguousAbsoluteArchive() {
        String apk = "/system/priv-app/D31ElfRemote/D31ElfRemote.apk";
        assertFalse(RemoteRuntimeInventory.mapsArchive("0000 " + apk + "\n", apk));
        assertFalse(RemoteRuntimeInventory.mapsArchive(map("[anon:" + apk + "]"), apk));
        assertFalse(RemoteRuntimeInventory.mapsArchive(map(apk), "/system/../" + apk));
        assertFalse(RemoteRuntimeInventory.mapsArchive(map(apk), null));
        assertFalse(RemoteRuntimeInventory.mapsArchive(null, apk));
        assertFalse(RemoteRuntimeInventory.mapsArchive(map("/a@b.apk"), "/a@b.apk"));
    }

    private static JSONObject archive(int version, String hash) throws Exception {
        return new JSONObject().put("package", "net.elfradio.d31bootstrap").put("certSha256", "certificate")
                .put("versionCode", version).put("versionName", "fixture").put("sha256", hash)
                .put("size", 100).put("path", "/fixture.apk");
    }
    private static final class Fake implements RemoteRuntimeInventory.Access {
        int baselineVersion = 80; boolean failActive; long healthTime = 9000;
        public JSONObject installed() throws Exception { return archive(90, "new").put("private_token", "excluded"); }
        public JSONObject systemArchive() throws Exception { return archive(baselineVersion, "old"); }
        public JSONObject active() throws Exception { if (failActive) throw new IOException("private path"); return archive(90, "new"); }
        public JSONObject health() throws Exception { return new JSONObject().put("version_code", 90).put("apk_sha256", "new")
                .put("uid", 0).put("time_ms", healthTime).put("local_ready", true).put("report_acknowledged", true)
                .put("instance", "private-instance"); }
        public JSONObject installation() throws Exception { return new JSONObject().put("system", true).put("updatedSystem", true)
                .put("privileged", true).put("uid", 10020).put("grantedPermissions", new JSONArray()); }
    }
    @Test public void distinguishesActualCopiesAndDoesNotClaimLoadedCode() throws Exception {
        JSONObject r = RemoteRuntimeInventory.collect(new Fake(), 10000);
        assertEquals(80, r.getJSONObject("systemArchive").getJSONObject("metadata").getInt("versionCode"));
        JSONObject a = r.getJSONObject("assessment");
        assertEquals("DIFFERENT", a.getString("installedVsSystem"));
        assertEquals("MATCH", a.getString("installedVsActive"));
        assertEquals("FRESH_SELF_REPORTED_MATCH", a.getString("coreHealth"));
        assertEquals("NOT_CHECKED", a.getString("loadedCode"));
        assertEquals("NOT_ASSESSED", r.getString("systemConsistency"));
    }
    @Test public void failedReadIsUnknownAndReasonDoesNotLeakDetails() throws Exception {
        Fake f = new Fake(); f.failActive = true;
        JSONObject r = RemoteRuntimeInventory.collect(f, 10000);
        assertEquals("READ_FAILED", r.getJSONObject("active").getString("state"));
        assertEquals("UNKNOWN", r.getJSONObject("assessment").getString("installedVsActive"));
        assertFalse(r.toString().contains("private path"));
    }
    @Test public void staleAndFutureHealthAreNotAccepted() throws Exception {
        Fake f = new Fake();
        for (long time : new long[]{-1, 10001, 0}) {
            f.healthTime = time;
            assertEquals("NOT_CONFIRMED", RemoteRuntimeInventory.collect(f, time == 0 ? 20000 : 10000)
                    .getJSONObject("assessment").getString("coreHealth"));
        }
    }
    @Test public void stripsUnrequestedState() throws Exception {
        String r = RemoteRuntimeInventory.collect(new Fake(), 10000).toString();
        assertFalse(r.contains("private_token")); assertFalse(r.contains("private-instance"));
    }
}
