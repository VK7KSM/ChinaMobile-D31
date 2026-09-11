package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import static org.junit.Assert.*;

public class RemoteBusinessCommandTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final String id = new String(new char[64]).replace('\0', 'a');
    private JSONObject params(boolean write) throws Exception {
        JSONObject p = new JSONObject().put("group", "sound").put("action", write ? "set" : "read");
        if (write) p.put("key", "brightness").put("value", 80);
        return p;
    }
    private JSONObject snapshot(boolean applied) throws Exception {
        return new JSONObject().put("ok", true).put("group", "sound")
                .put("sampled_at", 1000).put("applied", applied);
    }
    @Test public void repeatedCompletedTaskReturnsOriginalWithoutApplyingAgain() throws Exception {
        File root = temporary.newFolder(); int[] calls = {0};
        RemoteBusinessCommand.Operation operation = control -> {
            control.before(new JSONObject().put("brightness", 40));
            calls[0]++; return snapshot(true);
        };
        JSONObject first = RemoteBusinessCommand.execute(root, id, "system_config", params(true), operation);
        JSONObject again = RemoteBusinessCommand.execute(root, id, "system_config", params(true), operation);
        assertTrue(first.getBoolean("ok")); assertEquals(first.toString(), again.toString());
        assertEquals(1, calls[0]);
        assertEquals(40, new JSONObject(RescueFiles.read(new File(new File(root, id), "original.json"), 1000)).getInt("brightness"));
    }
    @Test public void interruptedTaskWithNoResultCannotReplay() throws Exception {
        File root = temporary.newFolder();
        try {
            RemoteBusinessCommand.execute(root, id, "system_config", params(true), control -> {
                control.before(new JSONObject().put("brightness", 40));
                throw new AssertionError("模拟进程在提交后中断");
            }); fail();
        } catch (AssertionError expected) { }
        try {
            RemoteBusinessCommand.execute(root, id, "system_config", params(true), control -> {
                throw new AssertionError("不得再次修改");
            }); fail();
        } catch (IOException expected) { }
        assertTrue(new File(new File(root, id), "original.json").isFile());
    }
    @Test public void changedParametersCannotReuseCompletedTask() throws Exception {
        File root = temporary.newFolder();
        RemoteBusinessCommand.execute(root, id, "system_config", params(true), control -> snapshot(true));
        try {
            RemoteBusinessCommand.execute(root, id, "system_config", params(true).put("value", 81), control -> {
                throw new AssertionError("同号不同参数不得执行");
            }); fail();
        } catch (IOException expected) { }
    }
    @Test public void missingAppliedOrExplicitFailureCannotBecomeSuccess() throws Exception {
        JSONObject result = RemoteBusinessCommand.execute(temporary.newFolder(), id, "system_config",
                params(true), control -> snapshot(false));
        assertFalse(result.getBoolean("ok"));
        result = RemoteBusinessCommand.execute(temporary.newFolder(), id, "system_config",
                params(false), control -> snapshot(false).put("ok", false));
        assertFalse(result.getBoolean("ok"));
    }
    @Test public void wrongGroupAndMissingSampleCannotBecomeSuccess() throws Exception {
        assertFalse(RemoteBusinessCommand.execute(temporary.newFolder(), id, "system_config",
                params(false), control -> snapshot(false).put("group", "apps")).getBoolean("ok"));
        assertFalse(RemoteBusinessCommand.execute(temporary.newFolder(), id, "system_config",
                params(false), control -> snapshot(false).put("sampled_at", 0)).getBoolean("ok"));
    }
    @Test public void failedOperationIsRecordedWithoutLeakingExceptionMessage() throws Exception {
        File root = temporary.newFolder();
        JSONObject result = RemoteBusinessCommand.execute(root, id, "system_config", params(false), control -> {
            throw new IOException("private-fixture-value");
        });
        assertFalse(result.getBoolean("ok"));
        assertFalse(result.toString().contains("private-fixture-value"));
        assertEquals(result.toString(), RemoteBusinessCommand.readResult(root, id, "system_config", params(false)).toString());
    }
    @Test public void cancellationBeforeStartNeverEntersOperationAndSurvivesReplay() throws Exception {
        File root = temporary.newFolder();
        RemoteBusinessCommand.cancel(root, id);
        JSONObject result = RemoteBusinessCommand.execute(root, id, "system_config", params(true), control -> {
            throw new AssertionError("已取消任务不得进入业务");
        });
        assertFalse(result.getBoolean("ok"));
        assertEquals("InterruptedException", result.getJSONObject("snapshot").getString("failure"));
        assertFalse(new File(new File(root, id), "original.json").exists());
        assertEquals(result.toString(), RemoteBusinessCommand.execute(root, id, "system_config", params(true), control -> {
            throw new AssertionError("取消结果不得重放");
        }).toString());
    }
    @Test public void cancellationAfterOriginalStopsBeforeWriteAndPreservesOriginal() throws Exception {
        File root = temporary.newFolder();
        JSONObject result = RemoteBusinessCommand.execute(root, id, "system_config", params(true), control -> {
            control.before(new JSONObject().put("brightness", 40));
            RemoteBusinessCommand.cancel(root, id);
            control.check();
            throw new AssertionError("取消后不得写入");
        });
        assertFalse(result.getBoolean("ok"));
        assertEquals(40, new JSONObject(RescueFiles.read(new File(new File(root, id), "original.json"), 1000)).getInt("brightness"));
        assertEquals("InterruptedException", result.getJSONObject("snapshot").getString("failure"));
    }
    @Test public void cancellationBeforeOriginalDoesNotCreateOriginal() throws Exception {
        File root = temporary.newFolder();
        JSONObject result = RemoteBusinessCommand.execute(root, id, "system_config", params(true), control -> {
            RemoteBusinessCommand.cancel(root, id);
            control.before(new JSONObject().put("brightness", 40));
            throw new AssertionError("取消后不得继续");
        });
        assertFalse(result.getBoolean("ok"));
        assertFalse(new File(new File(root, id), "original.json").exists());
    }
    @Test public void cancelIsIdempotentAndDoesNotCreateExecutionDirectory() throws Exception {
        File root = temporary.newFolder();
        RemoteBusinessCommand.cancel(root, id);
        File marker = new File(root, id + ".cancel");
        String original = RescueFiles.read(marker, 1000);
        long modified = marker.lastModified();
        RemoteBusinessCommand.cancel(root, id);
        assertEquals(original, RescueFiles.read(marker, 1000)); assertEquals(modified, marker.lastModified());
        assertFalse(new File(root, id).exists());
        try { RemoteBusinessCommand.cancel(root, "../escape"); fail(); } catch (IOException expected) { }
    }
    @Test public void cancellationDoesNotAffectAnotherTaskOrRewriteCompletedOutcome() throws Exception {
        File root = temporary.newFolder();
        RemoteBusinessCommand.cancel(root, new String(new char[64]).replace('\0', 'b'));
        JSONObject result = RemoteBusinessCommand.execute(root, id, "system_config", params(true), control -> snapshot(true));
        assertTrue(result.getBoolean("ok"));
        RemoteBusinessCommand.cancel(root, id);
        assertEquals(result.toString(), RemoteBusinessCommand.readResult(root, id, "system_config", params(true)).toString());
    }
    @Test public void readResultRejectsWrongValueKeyActionPackageAndOffset() throws Exception {
        File root = temporary.newFolder();
        RemoteBusinessCommand.execute(root, id, "system_config", params(true), control -> snapshot(true));
        for (JSONObject other : new JSONObject[]{params(true).put("value", 81), params(true).put("key", "media"),
                params(false), params(true).put("package", "example.other"), params(true).put("offset", 15)}) {
            try { RemoteBusinessCommand.readResult(root, id, "system_config", other); fail(other.toString()); }
            catch (IOException expected) { }
        }
        assertTrue(RemoteBusinessCommand.readResult(root, id, "system_config",
                params(true).put("package", "").put("offset", 0)).getBoolean("ok"));
    }
    @Test public void failedResultAlsoRequiresMatchingRequest() throws Exception {
        File root = temporary.newFolder();
        RemoteBusinessCommand.execute(root, id, "system_config", params(true), control -> { throw new IOException("拒绝"); });
        try { RemoteBusinessCommand.readResult(root, id, "system_config", params(true).put("value", 81)); fail(); }
        catch (IOException expected) { }
    }
    @Test public void readResultWithoutRequestCannotClaimSuccess() throws Exception {
        File root = temporary.newFolder(); File job = new File(root, id); assertTrue(job.mkdir());
        RescueFiles.write(new File(job, "result.json"), new JSONObject().put("id", id).put("type", "system_config")
                .put("ok", true).put("snapshot", snapshot(true)).toString());
        try { RemoteBusinessCommand.readResult(root, id, "system_config", params(true)); fail(); }
        catch (IOException expected) { }
    }
}
