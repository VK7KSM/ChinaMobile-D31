package net.elfradio.d31bootstrap;

import java.io.File;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RemoteMaintenanceTest {
    private static String map(String path) {
        return "70000000-70001000 r--p 00000000 103:00 123 " + path + "\n";
    }
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void leaseExcludesAnotherWriterAndReleases() throws Exception {
        File root = temporary.newFolder();
        try (RemoteMaintenance.Lease first = RemoteMaintenance.acquire(root)) {
            assertNotNull(first);
            assertNull(RemoteMaintenance.acquire(root));
        }
        try (RemoteMaintenance.Lease next = RemoteMaintenance.acquire(root)) { assertNotNull(next); }
    }
    @Test public void reservationSurvivesLeaseAndCannotBeStolen() throws Exception {
        File root = temporary.newFolder();
        try (RemoteMaintenance.Lease lease = RemoteMaintenance.acquire(root)) {
            RemoteMaintenance.reserve(root, "repair-a", "digest-a");
        }
        assertTrue(new File(root, "repair.json").isFile());
        RemoteMaintenance.reserve(root, "repair-a", "digest-a");
        try { RemoteMaintenance.reserve(root, "repair-b", "digest-a"); fail(); } catch (java.io.IOException expected) { }
        try { RemoteMaintenance.release(root, "repair-a", "digest-b"); fail(); } catch (java.io.IOException expected) { }
        assertTrue(new File(root, "repair.json").isFile());
        RemoteMaintenance.release(root, "repair-a", "digest-a");
        assertFalse(new File(root, "repair.json").exists());
    }
    @Test public void brokenReservationIsNeverReplaced() throws Exception {
        File root = temporary.newFolder();
        RescueFiles.write(new File(root, "repair.json"), "broken");
        try { RemoteMaintenance.reserve(root, "a", "b"); fail(); } catch (org.json.JSONException expected) { }
        assertEquals("broken", RescueFiles.read(new File(root, "repair.json"), 100));
    }
    @Test public void newSupervisorCannotAdmitOldOrUnmappedCore() throws Exception {
        String hash = RemoteProtocol.hash("apk");
        String path = "/data/local/d31-remote/releases/" + hash + "/remote.apk";
        org.json.JSONObject active = new org.json.JSONObject().put("sha256", hash).put("path", path).put("versionCode", 96);
        org.json.JSONObject core = new org.json.JSONObject().put("maintenance_protocol", 1).put("uid", 0).put("pid", 123)
                .put("local_ready", true).put("time_ms", 100).put("version_code", 96).put("apk_sha256", hash);
        RemoteMaintenance.requireCore(core, active, "123", map(path), 101);
        for (int version : new int[]{85, 94}) {
            try { RemoteMaintenance.requireCore(new org.json.JSONObject(core.toString()).put("version_code", version), active,
                    "123", map(path), 101); fail(); } catch (java.io.IOException expected) { }
        }
        try { RemoteMaintenance.requireCore(core, active, "123", map("/old.apk"), 101); fail(); } catch (java.io.IOException expected) { }
        try { RemoteMaintenance.requireCore(core, active, "124", map(path), 101); fail(); } catch (java.io.IOException expected) { }
        try { RemoteMaintenance.requireCore(core, active, "123", map(path), 20101); fail(); } catch (java.io.IOException expected) { }
    }
    @Test public void repairAdmitsExactCacheMappingButRejectsLookalikes() throws Exception {
        String hash=RemoteProtocol.hash("apk");
        for(String path:new String[]{"/system/priv-app/D31ElfRemote/D31ElfRemote.apk",
                "/data/local/d31-remote/releases/"+hash+"/remote.apk"}) {
            org.json.JSONObject active=new org.json.JSONObject().put("sha256",hash).put("path",path).put("versionCode",98);
            org.json.JSONObject core=new org.json.JSONObject().put("maintenance_protocol",1).put("uid",0).put("pid",123)
                    .put("local_ready",true).put("time_ms",100).put("version_code",98).put("apk_sha256",hash);
            String cache="/data/dalvik-cache/arm64/"+path.substring(1).replace('/','@')+"@classes.dex";
            RemoteMaintenance.requireCore(core,active,"123",map(cache),101);
            for(String fake:new String[]{cache+" (deleted)","/fake"+cache,cache+".old",cache.replace("/arm64/","/arm64/prefix")}) {
                try { RemoteMaintenance.requireCore(core,active,"123",map(fake),101);fail(fake); }
                catch(java.io.IOException expected) { }
            }
        }
    }
}
