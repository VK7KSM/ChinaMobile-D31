package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RemoteDiagnosticJobsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final String id = String.join("", java.util.Collections.nCopies(64, "a"));
    @Test public void equivalentRequestReusesOriginalWithoutCollectingAgain() throws Exception {
        File root = temp.newFolder(); int[] calls = {0};
        RemoteDiagnosticJobs.Work work = (dir, request) -> { calls[0]++; return new JSONObject().put("fact", 1); };
        JSONObject a = RemoteDiagnosticJobs.execute(root, id, new JSONObject("{\"a\":1,\"b\":2}"), work);
        JSONObject b = RemoteDiagnosticJobs.execute(root, id, new JSONObject("{\"b\":2,\"a\":1}"), work);
        assertEquals(1, calls[0]); assertEquals(a.toString(), b.toString());
    }
    @Test public void conflictingRequestIsRejected() throws Exception {
        File root = temp.newFolder();
        RemoteDiagnosticJobs.execute(root, id, new JSONObject("{\"a\":1}"), (d, r) -> new JSONObject());
        try { RemoteDiagnosticJobs.execute(root, id, new JSONObject("{\"a\":2}"), (d, r) -> new JSONObject()); fail(); }
        catch (IOException expected) { }
    }
    @Test public void interruptedRecordDoesNotRepeatWork() throws Exception {
        File root = temp.newFolder(), job = new File(root, id); assertTrue(job.mkdir());
        RescueFiles.write(new File(job, "request.json"), "{\"operation\":\"runtime\"}");
        JSONObject result = RemoteDiagnosticJobs.execute(root, id, new JSONObject("{\"operation\":\"runtime\"}"),
                (d, r) -> { throw new AssertionError("不得重扫"); });
        assertEquals("interrupted", result.getString("state"));
    }
    @Test public void corruptedReportIsNotSilentlyRegenerated() throws Exception {
        File root = temp.newFolder();
        JSONObject request = new JSONObject("{\"operation\":\"runtime\"}");
        RemoteDiagnosticJobs.execute(root, id, request, (d, r) -> new JSONObject());
        RescueFiles.write(new File(new File(root, id), "report.json"), "broken");
        try { RemoteDiagnosticJobs.execute(root, id, request, (d, r) -> { throw new AssertionError(); }); fail(); }
        catch (IOException expected) { }
    }
    @Test public void failureReceiptPreservesEvidenceWithoutLeakingMessage() throws Exception {
        File root = temp.newFolder();
        JSONObject result = RemoteDiagnosticJobs.execute(root, id, new JSONObject(), (d, r) -> {
            RescueFiles.write(new File(d, "partial.txt"), "evidence"); throw new IOException("private detail"); });
        assertEquals("failed", result.getString("state")); assertFalse(result.toString().contains("private detail"));
        assertTrue(new File(new File(root, id), "partial.txt").isFile());
    }
    @Test public void receiptWithDifferentIdentityIsRejected() throws Exception {
        File root = temp.newFolder(); JSONObject request = new JSONObject();
        JSONObject result = RemoteDiagnosticJobs.execute(root, id, request, (d, r) -> new JSONObject());
        result.put("id", "different");
        RescueFiles.write(new File(new File(root, id), "result.json"), result.toString());
        try { RemoteDiagnosticJobs.execute(root, id, request, (d, r) -> { throw new AssertionError(); }); fail(); }
        catch (IOException expected) { }
    }
    @Test public void commandRejectsUnexpectedOperationsAndPrivateRoots() throws Exception {
        for (String json : new String[]{"{\"operation\":\"delete\"}",
                "{\"operation\":\"runtime\",\"extra\":true}",
                "{\"operation\":\"manifest\",\"scope\":\"/data/data\",\"identity\":{}}",
                "{\"operation\":\"manifest\",\"scope\":\"/system/../data\",\"identity\":{}}"}) {
            try { RemoteDiagnosticCommand.validate(new JSONObject(json)); fail(json); } catch (IOException expected) { }
        }
    }
}
