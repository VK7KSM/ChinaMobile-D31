package net.elfradio.d31bootstrap;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class RemoteRuntimeInventoryTest {
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
