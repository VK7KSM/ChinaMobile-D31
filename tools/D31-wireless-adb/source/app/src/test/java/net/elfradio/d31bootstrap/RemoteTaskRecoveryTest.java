package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import static org.junit.Assert.*;

public class RemoteTaskRecoveryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final class Transport implements RemoteTasks.Transport {
        int submissions, queries, receipts;
        boolean offline;
        public JSONObject local(String path, JSONObject body) throws Exception {
            if (path.equals("/exec")) { submissions++; throw new AssertionError("不能重新下发"); }
            queries++;
            return new JSONObject().put("state", "completed").put("exit_code", 0).put("output", "local-result");
        }
        public JSONObject progress(JSONObject body) throws Exception {
            receipts++;
            if (offline) throw new IOException("receipt offline");
            return new JSONObject().put("ok", true).put("task", new JSONObject()
                    .put("id", body.getString("task_id")).put("state", body.getString("state")));
        }
    }
    private JSONObject saved(String id, boolean complete) throws Exception {
        JSONObject task = new JSONObject().put("id", id);
        JSONObject record = new JSONObject().put("task", task).put("dispatch_intent", true)
                .put("request", new JSONObject().put("id", RemoteProtocol.localJobId("device-test", id)));
        if (complete) record.put("receipt", new JSONObject().put("device_id", "device-test")
                .put("task_id", id).put("state", "success"));
        return record;
    }
    private File write(File root, String id, boolean complete) throws Exception {
        File record = new File(new File(root, "receipts"), RemoteProtocol.localJobId("device-test", id) + ".json");
        RescueFiles.write(record, saved(id, complete).toString()); return record;
    }
    @Test public void failedOldReceiptDoesNotBlockSavingRunningCommandResult() throws Exception {
        File root = temporary.newFolder(); Transport transport = new Transport(); transport.offline = true;
        RemoteTasks tasks = new RemoteTasks(root, "device-test", transport);
        RescueFiles.write(new File(new File(root, "receipts"), "000-old.json"), saved("old-receipt", true).toString());
        File running = write(root, "running-command", false);
        assertTrue("000-old.json".compareTo(running.getName()) < 0);
        try { tasks.resume(); fail(); } catch (IOException expected) { }
        JSONObject saved = new JSONObject(RescueFiles.read(running, 128000));
        assertEquals("local-result", saved.getJSONObject("receipt").getJSONObject("result").getString("text"));
        assertFalse(saved.optBoolean("acknowledged"));
        assertEquals(0, transport.submissions); assertEquals(1, transport.queries); assertEquals(1, transport.receipts);
        transport.offline = false;
        new RemoteTasks(root, "device-test", transport).resume();
        assertTrue(new JSONObject(RescueFiles.read(running, 128000)).getBoolean("acknowledged"));
        assertEquals(1, transport.queries); assertEquals(0, transport.submissions);
    }
    @Test public void receiptBacklogIsDrainedInBoundedBatches() throws Exception {
        File root = temporary.newFolder(); Transport transport = new Transport();
        RemoteTasks tasks = new RemoteTasks(root, "device-test", transport);
        for (int i = 0; i < 9; i++) write(root, "completed-" + i, true);
        tasks.resume(); assertEquals(4, transport.receipts);
        tasks.resume(); assertEquals(8, transport.receipts);
        tasks.resume(); assertEquals(9, transport.receipts);
        tasks.resume(); assertEquals(9, transport.receipts);
        assertEquals(0, transport.queries); assertEquals(0, transport.submissions);
    }
    @Test public void corruptRecordDoesNotHideOtherLocalResults() throws Exception {
        File root = temporary.newFolder(); Transport transport = new Transport(); transport.offline = true;
        RemoteTasks tasks = new RemoteTasks(root, "device-test", transport);
        RescueFiles.write(new File(new File(root, "receipts"), "000-broken.json"), "broken");
        File running = write(root, "running-command", false);
        try { tasks.resume(); fail(); } catch (Exception expected) { }
        assertTrue(new JSONObject(RescueFiles.read(running, 128000)).has("receipt"));
        assertEquals("broken", RescueFiles.read(new File(new File(root, "receipts"), "000-broken.json"), 128000));
        assertEquals(0, transport.submissions);
    }
}
