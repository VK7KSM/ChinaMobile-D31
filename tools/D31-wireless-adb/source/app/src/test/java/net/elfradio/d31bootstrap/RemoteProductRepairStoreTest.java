package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 真实本地文件重建与公共锁验证，目录全部为测试临时目录。 */
public class RemoteProductRepairStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void diskJournalAndReservationSurviveNewInstances() throws Exception {
        File root = temporary.newFolder(), maintenance = temporary.newFolder();
        RemoteProductRepairTest.Platform platform = new RemoteProductRepairTest.Platform(); platform.crashWrite = true;
        try (RemoteMaintenance.Lease lease = RemoteMaintenance.acquire(maintenance)) {
            assertNotNull(lease);
            try {
                new RemoteProductRepair(platform, new RemoteProductRepairStore(root, maintenance))
                        .apply(RemoteProductRepairTest.plan());
                fail();
            } catch (RemoteProductRepairTest.Crash expected) { }
        }
        assertTrue(new File(maintenance, "repair.json").isFile());
        RemoteProductRepair recreated = new RemoteProductRepair(platform, new RemoteProductRepairStore(root, maintenance));
        RemoteProductRepairTest.phase("APPLYING", recreated.query("test-1"));
        try (RemoteMaintenance.Lease lease = RemoteMaintenance.acquire(maintenance)) {
            assertNotNull(lease); RemoteProductRepairTest.phase("ROLLED_BACK", recreated.recover("test-1"));
        }
        assertFalse(new File(maintenance, "repair.json").exists());
        assertTrue(new File(root, "test-1.json").isFile());
        assertEquals(false, platform.value);
    }
    @Test public void ordinaryRepairReservationCannotBeStolen() throws Exception {
        File root = temporary.newFolder(), maintenance = temporary.newFolder();
        RemoteMaintenance.reserve(maintenance, "ordinary-repair", "original-digest");
        RemoteProductRepairTest.Platform platform = new RemoteProductRepairTest.Platform();
        try {
            new RemoteProductRepair(platform, new RemoteProductRepairStore(root, maintenance))
                    .apply(RemoteProductRepairTest.plan()); fail();
        } catch (IOException expected) { }
        assertEquals(0, platform.writes);
        JSONObject original = new JSONObject(RescueFiles.read(new File(maintenance, "repair.json"), 4096));
        assertEquals("ordinary-repair", original.getString("task_id"));
    }
    @Test public void corruptedJournalFailsClosed() throws Exception {
        File root = temporary.newFolder(), maintenance = temporary.newFolder();
        RescueFiles.write(new File(root, "test-1.json"), "broken");
        RemoteProductRepairTest.Platform platform = new RemoteProductRepairTest.Platform();
        try {
            new RemoteProductRepair(platform, new RemoteProductRepairStore(root, maintenance))
                    .apply(RemoteProductRepairTest.plan()); fail();
        } catch (org.json.JSONException expected) { }
        assertEquals(0, platform.writes);
        assertEquals("broken", RescueFiles.read(new File(root, "test-1.json"), 4096));
    }
    @Test public void pathTraversalRejected() throws Exception {
        RemoteProductRepairStore store = new RemoteProductRepairStore(temporary.newFolder(), temporary.newFolder());
        try { store.load("../other"); fail(); } catch (IOException expected) { }
    }
}
