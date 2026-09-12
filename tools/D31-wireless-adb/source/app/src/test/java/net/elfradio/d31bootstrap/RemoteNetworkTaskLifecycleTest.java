package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RemoteNetworkTaskLifecycleTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final List<JSONObject> sent = new ArrayList<>();
    private boolean drop;
    private boolean allowMissingRead;
    private JSONObject queried;
    private File receipt;
    private File directory;
    private final String hash = new String(new char[64]).replace('\0', 'a');
    private RemoteTasks tasks() throws Exception {
        return new RemoteTasks(directory, "synthetic-device", new RemoteTasks.Transport() {
            public JSONObject local(String path, JSONObject body) {
                if (allowMissingRead && body == null && path.startsWith("/jobs/")) return null;
                throw new AssertionError("不得重放原命令");
            }
            public JSONObject progress(JSONObject body) throws Exception {
                sent.add(new JSONObject(body.toString()));
                if (drop) throw new IOException("合成ACK丢失");
                return new JSONObject().put("ok", true).put("task", new JSONObject().put("id", body.get("task_id")).put("state", body.get("state")));
            }
        }, (id, intent) -> new JSONObject(queried.toString()));
    }
    private void setup() throws Exception {
        directory = temporary.newFolder();
        tasks();
        String id = RemoteProtocol.localJobId("synthetic-device", "synthetic-cloud");
        JSONObject task = new JSONObject().put("id", "synthetic-cloud").put("type", "system_config").put("request_digest", hash)
                .put("params", new JSONObject().put("group", "wifi").put("action", "set").put("key", "enabled").put("value", false)
                        .put("network_transaction", new JSONObject().put("version", 1).put("apk_sha256", hash).put("confirm_within_ms", 60000)));
        receipt = new File(new File(directory, "receipts"), id + ".json");
        RescueFiles.write(receipt, new JSONObject().put("task", task).put("request", new JSONObject().put("id", id))
                .put("dispatch_intent", true).toString());
    }
    private void outcome(boolean complete, boolean success, String status) throws Exception {
        queried = new JSONObject().put("complete", complete).put("success", success)
                .put("network_transaction", new JSONObject().put("version", 1).put("status", status));
    }
    @Test public void completedBeginDoesNotReleaseCloudSlotBeforeConfirmation() throws Exception {
        setup(); outcome(false, false, "AWAITING_CONFIRM");
        tasks().resume(); tasks().resume();
        assertEquals(1, sent.size()); assertEquals("running", sent.get(0).getString("state"));
        assertFalse(new JSONObject(RescueFiles.read(receipt, 10000)).has("receipt"));
    }
    @Test public void completedConfirmationIsPersistedBeforeLostAckAndReplayedExactly() throws Exception {
        setup(); outcome(true, true, "CONFIRMED"); drop = true;
        try { tasks().resume(); fail(); } catch (IOException expected) { }
        assertTrue(new JSONObject(RescueFiles.read(receipt, 10000)).has("receipt"));
        outcome(false, false, "UNKNOWN"); drop = false;
        tasks().resume();
        assertEquals(2, sent.size()); assertTrue(RemoteProtocol.sameJson(sent.get(0), sent.get(1)));
        assertEquals("success", sent.get(1).getString("state"));
        assertTrue(new JSONObject(RescueFiles.read(receipt, 10000)).getBoolean("acknowledged"));
    }
    @Test public void rollbackEndsOriginalTaskAsFailed() throws Exception {
        setup(); outcome(true, false, "ROLLED_BACK"); tasks().resume();
        assertEquals("failed", sent.get(0).getString("state"));
        assertEquals("ROLLED_BACK", sent.get(0).getJSONObject("result").getJSONObject("network_transaction").getString("status"));
    }
    @Test public void unknownQueryDoesNotFabricateBindingOrReceipt() throws Exception {
        setup(); queried = new JSONObject().put("complete", false).put("success", false).put("local_state", "UNKNOWN");
        tasks().resume();
        assertTrue(sent.get(0).isNull("result"));
        assertFalse(new JSONObject(RescueFiles.read(receipt, 10000)).has("receipt"));
    }
    @Test public void cleanupPendingCannotBecomeSuccess() throws Exception {
        setup(); outcome(false, false, "CONFIRMED"); tasks().resume();
        assertEquals("running", sent.get(0).getString("state"));
        outcome(true, true, "CONFIRMED"); tasks().resume();
        assertEquals("success", sent.get(1).getString("state"));
    }
    @Test public void savedButUndispatchedTaskExpiresWithStructuredNotStarted() throws Exception {
        setup();
        JSONObject saved = new JSONObject(RescueFiles.read(receipt, 10000)); saved.remove("dispatch_intent");
        saved.getJSONObject("task").put("expires_at", 1);
        RescueFiles.write(receipt, saved.toString()); allowMissingRead = true;
        tasks().resume();
        assertEquals("rejected", sent.get(0).getString("state"));
        assertEquals("NOT_STARTED", sent.get(0).getJSONObject("result").getJSONObject("network_transaction").getString("status"));
    }
    @Test public void cancelledUndispatchedTaskDoesNotInvokePlatform() throws Exception {
        setup();
        JSONObject saved = new JSONObject(RescueFiles.read(receipt, 10000)); saved.remove("dispatch_intent");
        RescueFiles.write(receipt, saved.toString());
        JSONObject task = new JSONObject(saved.getJSONObject("task").toString()).put("cancel_requested", true);
        tasks().accept(task, System.currentTimeMillis());
        assertEquals("rejected", sent.get(0).getString("state"));
        assertEquals("NOT_STARTED", sent.get(0).getJSONObject("result").getJSONObject("network_transaction").getString("status"));
    }
}
