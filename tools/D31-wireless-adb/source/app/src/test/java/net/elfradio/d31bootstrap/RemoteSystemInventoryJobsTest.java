package net.elfradio.d31bootstrap;

import java.io.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import net.elfradio.d31bootstrap.diagnostics.collection.*;

public class RemoteSystemInventoryJobsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String ID = new String(new char[64]).replace('\0', 'a');
    private static final CollectionAccess.Clock CLOCK = new CollectionAccess.Clock() {
        public long wallTimeMillis() { return 1000; }
        public long elapsedRealtimeMillis() { return 100; }
    };
    private static class Access implements CollectionAccess {
        int opens;
        final Stat stat = new Stat("file", 1, 2, 3, 4, 5, 0644, 0, 0);
        public Stat lstat(String p) { return stat; }
        public String readLink(String p) { throw new AssertionError(); }
        public Listing list(String p, Stat s, int n, long b, long t) { throw new AssertionError(); }
        public Handle openRegular(String p, Stat s) {
            opens++;
            return new Handle() {
                final ByteArrayInputStream in = new ByteArrayInputStream(new byte[]{1, 2, 3});
                public Stat stat() { return stat; }
                public int read(byte[] b, int o, int n) { return in.read(b, o, n); }
                public void close() { }
            };
        }
    }
    private JSONObject init() throws Exception {
        JSONObject context = new JSONObject();
        for (String key : new String[]{"model", "hardwareClass", "firmwareFamily", "stage", "network", "sim", "storage"}) context.put(key, "D31");
        return new JSONObject().put("operation", "init").put("roots", new JSONArray().put("/system/example"))
                .put("identity", new JSONObject().put("snapshotId", "initial").put("baselineId", "baseline")
                        .put("baselineRevision", "1").put("firmwareId", "firmware").put("build", "build").put("context", context));
    }
    private JSONObject run(File root, JSONObject r, Access access) throws Exception {
        return RemoteSystemInventoryJobs.execute(root, ID, r, "boot-binding", "build", access, CLOCK);
    }
    @Test public void repeatedOriginalStepReturnsPreservedBytesWithoutRescan() throws Exception {
        File root = temporary.newFolder(); Access a = new Access();
        run(root, init(), a);
        JSONObject request = new JSONObject().put("operation", "step").put("sequence", 0);
        JSONObject result = run(root, request, a);
        assertEquals(2, a.opens);
        assertEquals("CLOSED_WITHIN_PLAN", result.getJSONObject("summary").getString("inventory"));
        assertEquals(result.getString("sha256"), run(root, request, a).getString("sha256"));
        assertEquals(2, a.opens);
        assertEquals(1, run(root, new JSONObject().put("operation", "query"), a).getInt("nextSequence"));
    }
    @Test public void differentSessionAndScopeAndSequenceAreRejectedBeforeReading() throws Exception {
        File root = temporary.newFolder(); Access a = new Access(); run(root, init(), a);
        try { RemoteSystemInventoryJobs.execute(root, ID, new JSONObject().put("operation", "query"), "another-boot", "build", a, CLOCK); fail(); }
        catch (Exception expected) { }
        try { run(root, init().put("roots", new JSONArray().put("/system/other")), a); fail(); }
        catch (Exception expected) { }
        try { run(root, new JSONObject().put("operation", "step").put("sequence", 1), a); fail(); }
        catch (Exception expected) { }
        assertEquals(0, a.opens);
    }
    @Test public void changedArchivedEvidenceCannotBeAcceptedAsCompleted() throws Exception {
        File root = temporary.newFolder(); Access a = new Access(); run(root, init(), a);
        JSONObject result = run(root, new JSONObject().put("operation", "step").put("sequence", 0), a);
        File archive = new File(result.getString("path"));
        JSONObject value = new JSONObject(RescueFiles.read(archive, 4 * 1024 * 1024));
        value.getJSONObject("event").getJSONObject("index").put("readBytes", 7);
        RescueFiles.write(archive, value.toString());
        try { run(root, new JSONObject().put("operation", "query"), a); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("摘要")); }
        assertEquals(2, a.opens);
    }
    @Test public void incompleteTemporaryWriteIsPreserved() throws Exception {
        File root = temporary.newFolder(); Access a = new Access(); run(root, init(), a);
        File partial = new File(new File(root, ID), "event-00000.json.tmp");
        RescueFiles.write(partial, "incomplete");
        try { run(root, new JSONObject().put("operation", "step").put("sequence", 0), a); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("中断")); }
        assertEquals("incomplete", RescueFiles.read(partial, 100));
    }
}
