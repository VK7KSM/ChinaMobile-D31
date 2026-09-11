package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import static org.junit.Assert.*;

public class RemoteCoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private JSONObject offer() throws Exception {
        return new JSONObject().put("id", "cmd_test-1").put("type", "root_exec")
                .put("expires_at", System.currentTimeMillis() + 60000)
                .put("params", new JSONObject().put("command", "id").put("cwd", "/").put("timeout", 5));
    }

    private static final class Fake implements RemoteTasks.Transport {
        int submissions;
        boolean loseExecReply, failReceipt;
        JSONObject outcome, progress;
        public JSONObject local(String path, JSONObject body) throws Exception {
            if (path.equals("/exec")) {
                submissions++;
                outcome = new JSONObject().put("state", "completed").put("exit_code", 0).put("output", "test");
                if (loseExecReply) throw new IOException("lost response");
            }
            return outcome;
        }
        public JSONObject progress(JSONObject body) throws Exception {
            if (failReceipt && body.optString("state").equals("success")) throw new IOException("offline");
            progress = body;
            return new JSONObject().put("ok", true).put("task", new JSONObject()
                    .put("id", body.getString("task_id")).put("state", body.getString("state")));
        }
    }

    @Test public void identityRemainsD31AndRejectsRandomMac() throws Exception {
        JSONObject identity = RemoteProtocol.ethernetIdentity("00:12:34:56:78:90", "0\n");
        assertEquals("d31", identity.getString("variant"));
        for (String mac : new String[]{"02:12:34:56:78:90", "01:12:34:56:78:90", "00:00:00:00:00:00"}) {
            try { RemoteProtocol.ethernetIdentity(mac, "0"); fail(); } catch (IOException expected) { }
        }
        try { RemoteProtocol.ethernetIdentity("00:12:34:56:78:90", "3"); fail(); } catch (IOException expected) { }
    }

    @Test public void cloudIdsMapToLegacyCompatibleScopedIds() throws Exception {
        String id = RemoteProtocol.localJobId("dev_a", "cmd_1");
        assertTrue(id.matches("[a-f0-9]{64}"));
        assertNotEquals(id, RemoteProtocol.localJobId("dev_b", "cmd_1"));
        assertEquals(id, RemoteProtocol.localJobId("dev_a", "cmd_1"));
    }

    @Test public void expiredAndCancelledCommandsNeverExecute() throws Exception {
        for (boolean cancel : new boolean[]{false, true}) {
            File dir = temporary.newFolder(); Fake fake = new Fake();
            JSONObject task = offer();
            if (cancel) task.put("cancel_requested", true); else task.put("expires_at", 1);
            new RemoteTasks(dir, "dev_a", fake).accept(task, System.currentTimeMillis());
            assertEquals(0, fake.submissions); assertEquals("rejected", fake.progress.getString("state"));
        }
    }

    @Test public void restartedClientDoesNotReplayCompletedCommand() throws Exception {
        File dir = temporary.newFolder(); Fake fake = new Fake(); JSONObject task = offer();
        new RemoteTasks(dir, "dev_a", fake).accept(task, System.currentTimeMillis());
        fake.outcome = null;
        new RemoteTasks(dir, "dev_a", fake).accept(task, System.currentTimeMillis());
        assertEquals(1, fake.submissions);
    }

    @Test public void lostExecReplyQueriesExistingJob() throws Exception {
        File dir = temporary.newFolder(); Fake fake = new Fake(); fake.loseExecReply = true;
        try { new RemoteTasks(dir, "dev_a", fake).accept(offer(), System.currentTimeMillis()); fail(); }
        catch (IOException expected) { }
        new RemoteTasks(dir, "dev_a", fake).resume();
        assertEquals(1, fake.submissions); assertEquals("success", fake.progress.getString("state"));
    }

    @Test public void missingAmbiguousJobFailsWithoutReplay() throws Exception {
        File dir = temporary.newFolder(); Fake fake = new Fake(); fake.loseExecReply = true;
        try { new RemoteTasks(dir, "dev_a", fake).accept(offer(), System.currentTimeMillis()); fail(); }
        catch (IOException expected) { }
        fake.outcome = null;
        new RemoteTasks(dir, "dev_a", fake).resume();
        assertEquals(1, fake.submissions); assertEquals("failed", fake.progress.getString("state"));
    }

    @Test public void offlineTerminalReceiptSurvivesRestart() throws Exception {
        File dir = temporary.newFolder(); Fake fake = new Fake(); fake.failReceipt = true;
        try { new RemoteTasks(dir, "dev_a", fake).accept(offer(), System.currentTimeMillis()); fail(); }
        catch (IOException expected) { }
        fake.failReceipt = false; fake.outcome = null;
        new RemoteTasks(dir, "dev_a", fake).resume();
        assertEquals(1, fake.submissions); assertEquals("success", fake.progress.getString("state"));
    }

    @Test public void changedCommandWithSameIdIsRejected() throws Exception {
        File dir = temporary.newFolder(); Fake fake = new Fake(); JSONObject task = offer();
        RemoteTasks tasks = new RemoteTasks(dir, "dev_a", fake); tasks.accept(task, System.currentTimeMillis());
        task.getJSONObject("params").put("command", "reboot");
        try { tasks.accept(task, System.currentTimeMillis()); fail(); } catch (IOException expected) { }
        assertEquals(1, fake.submissions);
    }

    @Test public void credentialsPersistAndCorruptionDoesNotCreateNewIdentity() throws Exception {
        File dir = temporary.newFolder(); RemoteState state = new RemoteState(dir);
        String token = state.snapshot().getString("token");
        assertEquals(token, new RemoteState(dir).snapshot().getString("token"));
        RescueFiles.write(new File(dir, "identity.json"), "{\"token\":\"broken\"}");
        try { new RemoteState(dir); fail(); } catch (IOException expected) { }
    }

    @Test public void changedTaskTypeCannotReuseCommandReceipt() throws Exception {
        File dir=temporary.newFolder(); Fake fake=new Fake(); JSONObject task=offer();
        RemoteTasks tasks=new RemoteTasks(dir,"dev_a",fake);
        tasks.accept(task,System.currentTimeMillis());
        task.put("type","file_manage");
        assertThrows(IOException.class,()->tasks.accept(task,System.currentTimeMillis()));
        assertEquals(1,fake.submissions);
    }

    @Test public void newerNoticeSurvivesAcknowledgementOfOlderReport() throws Exception {
        File dir = temporary.newFolder(); RemoteState state = new RemoteState(dir);
        long now = System.currentTimeMillis();
        state.notice(new JSONObject().put("type", "status_request").put("request_id", "request_2")
                .put("version", 2).put("expires_at_ms", now + 60000), now);
        state.acknowledge(new JSONObject().put("_notice_version", 1));
        assertEquals(2, new RemoteState(dir).snapshot().getJSONObject("notice").getLong("version"));
        state.acknowledge(new JSONObject().put("_notice_version", 2));
        assertFalse(state.snapshot().has("notice"));
    }

    @Test public void insecurePushConfigIsRejected() throws Exception {
        JSONObject config = new JSONObject().put("tls", false).put("host", "example.com").put("port", 1883);
        try { RemoteProtocol.validateConnection(config); fail(); } catch (IOException expected) { }
    }

    @Test public void bundledRootLoadsAndTls12IsEnabled() throws Exception {
        try (javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) RemoteTls.factory().createSocket()) {
            assertTrue(java.util.Arrays.asList(socket.getEnabledProtocols()).contains("TLSv1.2"));
        }
    }

    @Test public void nameUsesSystemThenBluetoothThenModelWithoutLocalConfiguration() {
        assertEquals("system", RemoteDeviceName.read((table, key) -> table.equals("global") ? " system " : "bluetooth", "model"));
        assertEquals("bluetooth", RemoteDeviceName.read((table, key) -> table.equals("global") ? "null" : "bluetooth", "model"));
        assertEquals("model", RemoteDeviceName.read((table, key) -> "", "model"));
    }
}
