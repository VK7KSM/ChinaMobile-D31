package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * 代理任务的领取、执行与回执。假服务端照 REPAIR_ADVANCE 的规矩：不在迁移表里的回执静默丢弃、照样回 ok，
 * 所以设备侧必须回读状态、按阶梯补发——这是二维码任务那次踩过的坑。
 */
public class ProxyTasksTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String CONFIG_URL = "https://v.elfradio.net/api/elfremote/proxy-config/%s?device_id=dev_abc&token=" + "f".repeat(64);

    static final class Runtime implements ProxyTasks.Runtime {
        final List<String> ops = new ArrayList<>();
        boolean installed = true, configured = true, running, reachable = true;
        Exception failure;
        private JSONObject op(String name) throws Exception {
            ops.add(name);
            if (failure != null) throw failure;
            return status();
        }
        @Override public JSONObject configure(JSONObject params) throws Exception { JSONObject s = op("configure"); configured = true; return status(); }
        @Override public JSONObject start() throws Exception { op("start"); running = true; return status(); }
        @Override public JSONObject stop() throws Exception { op("stop"); running = false; return status(); }
        @Override public JSONObject test() throws Exception { return op("test"); }
        @Override public JSONObject remove() throws Exception { op("remove"); installed = false; configured = false; running = false; return status(); }
        @Override public JSONObject status() throws Exception {
            return ProxyStatus.build(installed ? "1.19.31" : "", installed, installed, configured, running, running, running,
                    running && reachable, "direct", 1000L, "v1", "c".repeat(64), "");
        }
    }

    /** 假服务端：只接受合法迁移，其余丢弃但仍回当前状态；`dropNext` 模拟一次静默丢弃。 */
    static final class Server implements ProxyTasks.Progress {
        final Map<String, String> states = new HashMap<>();
        final List<String> sent = new ArrayList<>();
        Exception failure; boolean dropNext;
        @Override public String send(JSONObject receipt) throws Exception {
            if (failure != null) throw failure;
            String id = receipt.getString("task_id"), to = receipt.getString("state");
            String from = states.containsKey(id) ? states.get(id) : "pending";
            sent.add(to);
            if (dropNext) { dropNext = false; return from; }
            if (legal(from, to)) states.put(id, to);
            return states.containsKey(id) ? states.get(id) : "pending";
        }
        static boolean legal(String from, String to) {
            if ("pending".equals(from)) return to.equals("claimed") || to.equals("rejected") || to.equals("failed");
            if ("claimed".equals(from)) return to.equals("running") || to.equals("rejected") || to.equals("failed");
            if ("running".equals(from)) return to.equals("success") || to.equals("failed") || to.equals("rejected");
            return false;
        }
    }

    private final Runtime runtime = new Runtime();
    private final Server server = new Server();
    private long wall = 1789800000000L;
    private ProxyTasks tasks;

    private ProxyTasks tasks() throws Exception {
        if (tasks == null) tasks = new ProxyTasks(temp.getRoot(), "dev_abc", runtime, server, () -> wall);
        return tasks;
    }

    private JSONObject task(String type, String id) throws Exception {
        JSONObject t = new JSONObject().put("id", id).put("type", type).put("expires_at", wall + 600000);
        if (ProxyTasks.CONFIGURE.equals(type)) t.put("params", new JSONObject().put("url", String.format(CONFIG_URL, id))
                .put("size", 100).put("sha256", "c".repeat(64)).put("version", "v1"));
        return t;
    }

    /** 执行在工作线程上；反复 tick 直到当前任务让位或超时。 */
    private void drive() throws Exception {
        for (int i = 0; i < 200; i++) {
            tasks().tick();
            if (!tasks().busy()) return;
            Thread.sleep(10);
        }
        fail("任务未在期限内结束");
    }

    private JSONObject journal(String id) throws Exception {
        return new JSONObject(RescueFiles.read(new File(new File(temp.getRoot(), "proxy-tasks"), id + ".json"), 64000));
    }

    @Test public void validateEnforcesTheContract() throws Exception {
        long now = wall;
        assertEquals("configure_proxy", ProxyTasks.validate(task(ProxyTasks.CONFIGURE, "t1"), now).getString("type"));
        JSONObject withCore = task(ProxyTasks.CONFIGURE, "t1");
        withCore.getJSONObject("params").put("core", new JSONObject().put("manifest_raw", "{}").put("signature", "0".repeat(512)));
        ProxyTasks.validate(withCore, now);
        for (String type : new String[]{ProxyTasks.START, ProxyTasks.STOP, ProxyTasks.TEST, ProxyTasks.REMOVE})
            assertEquals(type, ProxyTasks.validate(task(type, "t2"), now).getString("type"));
        JSONObject[] invalid = {
                task("unknown_proxy", "t3"),
                task(ProxyTasks.START, "t4").put("params", new JSONObject().put("extra", true)),
                task(ProxyTasks.CONFIGURE, "t5").put("params", task(ProxyTasks.CONFIGURE, "other").getJSONObject("params")),
                task(ProxyTasks.CONFIGURE, "t6").put("params", task(ProxyTasks.CONFIGURE, "t6").getJSONObject("params").put("sha256", "xyz")),
                task(ProxyTasks.CONFIGURE, "t7").put("params", task(ProxyTasks.CONFIGURE, "t7").getJSONObject("params").put("size", 0)),
                task(ProxyTasks.CONFIGURE, "t8").put("params", task(ProxyTasks.CONFIGURE, "t8").getJSONObject("params").put("bogus", 1)),
                task(ProxyTasks.CONFIGURE, "t9").put("params", JSONObject.NULL),
                task(ProxyTasks.STOP, "bad id"),
                task(ProxyTasks.STOP, "t10").put("expires_at", 0),
        };
        for (JSONObject t : invalid) {
            try { ProxyTasks.validate(t, now); fail("应当拒绝：" + t); }
            catch (IOException expected) { assertTrue(expected.getMessage().startsWith("PROXY_")); }
        }
    }

    @Test public void configureTaskWalksTheLadderAndReportsTheStatusEnvelope() throws Exception {
        tasks().accept(task(ProxyTasks.CONFIGURE, "t1"));
        drive();
        assertEquals(java.util.Collections.singletonList("configure"), runtime.ops);
        assertEquals("success", server.states.get("t1"));
        assertEquals(java.util.Arrays.asList("claimed", "running", "success"), server.sent);
        JSONObject saved = journal("t1");
        assertTrue(saved.getBoolean("acknowledged"));
        JSONObject result = saved.getJSONObject("receipt").getJSONObject("result");
        assertEquals("proxy", result.getString("stage"));
        assertEquals("configure_proxy", result.getString("action"));
        assertEquals(2, result.getJSONObject("proxy").getInt("schema_version"));
        assertTrue(result.getJSONObject("proxy").getBoolean("configured"));
    }

    @Test public void testTaskFailsWhenTheProxyPathIsNotReachable() throws Exception {
        runtime.running = true; runtime.reachable = false;
        tasks().accept(task(ProxyTasks.TEST, "t1"));
        drive();
        assertEquals("failed", server.states.get("t1"));
        assertEquals("检测失败也要带状态，面板才知道差在哪一项", "proxy",
                journal("t1").getJSONObject("receipt").getJSONObject("result").getString("stage"));
    }

    @Test public void runtimeFailureIsReportedAsFailedWithTheCurrentStatus() throws Exception {
        runtime.failure = new IOException("PROXY_CORE_NOT_INSTALLED");
        tasks().accept(task(ProxyTasks.START, "t1"));
        drive();
        assertEquals("failed", server.states.get("t1"));
        JSONObject receipt = journal("t1").getJSONObject("receipt");
        assertTrue(receipt.getString("detail").contains("PROXY_CORE_NOT_INSTALLED"));
        assertEquals("start_proxy", receipt.getJSONObject("result").getString("action"));
    }

    @Test public void cancelBeforeExecutionRejectsWithoutRunningAnything() throws Exception {
        tasks().accept(task(ProxyTasks.STOP, "t1"));
        tasks().accept(task(ProxyTasks.STOP, "t1").put("cancel_requested", true));
        drive();
        assertTrue(runtime.ops.isEmpty());
        assertEquals("rejected", server.states.get("t1"));
    }

    @Test public void expiredTaskIsRejectedWithoutRunningAnything() throws Exception {
        tasks().accept(task(ProxyTasks.STOP, "t1"));
        wall += 700000;
        drive();
        assertTrue(runtime.ops.isEmpty());
        assertEquals("rejected", server.states.get("t1"));
    }

    @Test public void repeatedOffersAndCoreRestartsDoNotReExecute() throws Exception {
        tasks().accept(task(ProxyTasks.START, "t1"));
        drive();
        tasks().accept(task(ProxyTasks.START, "t1"));
        drive();
        // 核心重启：新实例读同一份记账。
        tasks = null;
        tasks().accept(task(ProxyTasks.START, "t1"));
        drive();
        assertEquals("同一任务只执行一次", 1, runtime.ops.size());
        assertEquals(java.util.Arrays.asList("claimed", "running", "success"), server.sent);
    }

    @Test public void silentlyDroppedReceiptIsBridgedNotAssumed() throws Exception {
        server.dropNext = true;
        tasks().accept(task(ProxyTasks.START, "t1"));
        drive();
        assertEquals("success", server.states.get("t1"));
        assertEquals("第一次 claimed 被丢弃后要补发", "claimed", server.sent.get(0));
        assertEquals("claimed", server.sent.get(1));
        assertEquals(1, runtime.ops.size());
    }

    @Test public void progressOutageBacksOffAndResumesFromTheJournal() throws Exception {
        server.failure = new IOException("HTTP 503");
        tasks().accept(task(ProxyTasks.START, "t1"));
        tasks().tick();
        assertTrue(journal("t1").optLong("retry_at") > wall);
        assertTrue(runtime.ops.isEmpty());
        tasks().tick();
        assertTrue("退避期内不再打服务端", server.sent.isEmpty());
        server.failure = null;
        wall += ProxyTasks.RETRY_BASE_MS;
        drive();
        assertEquals("success", server.states.get("t1"));
        assertEquals(1, runtime.ops.size());
    }

    @Test public void malformedTaskGetsARejectedReceiptInsteadOfSilence() throws Exception {
        tasks().accept(task(ProxyTasks.START, "t1").put("params", new JSONObject().put("x", 1)));
        drive();
        assertEquals("rejected", server.states.get("t1"));
        assertTrue(runtime.ops.isEmpty());
    }

    @Test public void removeTaskReportsTheUninstalledShape() throws Exception {
        tasks().accept(task(ProxyTasks.REMOVE, "t1"));
        drive();
        JSONObject proxy = journal("t1").getJSONObject("receipt").getJSONObject("result").getJSONObject("proxy");
        assertEquals("success", server.states.get("t1"));
        assertFalse(proxy.getBoolean("asset_verified")); assertFalse(proxy.getBoolean("core_verified"));
        assertFalse(proxy.getBoolean("configured")); assertFalse(proxy.getBoolean("running"));
        assertTrue(proxy.isNull("version"));
    }
}
