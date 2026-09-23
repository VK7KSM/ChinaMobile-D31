package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 本机客户端走的文件请求/结果通道：一次一条、失败带原因、「连接」已配置就不再向服务端要配置。 */
public class ProxyLocalTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    static final class Client implements ProxyLocal.Client {
        final List<String> ops = new ArrayList<>();
        boolean installed = true, configured = true, running, reachable = true;
        String selected = ProxyConfig.AUTO;
        Exception failure;
        private JSONObject op(String name) throws Exception { ops.add(name); if (failure != null) throw failure; return status(); }
        @Override public JSONObject configure(JSONObject params) throws Exception { op("configure"); configured = true; return status(); }
        @Override public JSONObject start() throws Exception { op("start"); running = true; return status(); }
        @Override public JSONObject stop() throws Exception { op("stop"); running = false; return status(); }
        @Override public JSONObject test() throws Exception { return op("test"); }
        @Override public JSONObject remove() throws Exception { op("remove"); installed = false; configured = false; running = false; return status(); }
        @Override public JSONObject status() throws Exception {
            return ProxyStatus.build(installed ? "1.19.31" : "", installed, installed, configured, running, running, running,
                    running && reachable, "direct", 1000L, "v1", "c".repeat(64), "");
        }
        @Override public JSONObject overview() throws Exception { return new JSONObject().put("status", status()).put("selected", selected).put("nodes", nodes()); }
        @Override public JSONArray nodes() throws Exception { return new JSONArray().put(new JSONObject().put("name", "a").put("selected", "a".equals(selected))); }
        @Override public JSONArray testNodes() throws Exception { op("test_nodes"); return nodes(); }
        @Override public String selectNode(String name) throws Exception { op("select:" + name); if (failure != null) throw failure; selected = name; return name; }
        @Override public JSONArray setApps(List<String> packages) throws Exception { op("apps:" + packages); return new JSONArray(packages); }
    }

    private final Client client = new Client();
    private JSONObject offered = new JSONObject();
    private Exception offerFailure;
    private String offeredFor = "";

    private ProxyLocal local() {
        return new ProxyLocal(temp.getRoot(), client, id -> { offeredFor = id; if (offerFailure != null) throw offerFailure; return offered; });
    }

    private void request(String op, JSONObject params) throws Exception {
        JSONObject body = new JSONObject().put("op", op).put("request_id", "req-" + op);
        if (params != null) body.put("params", params);
        RescueFiles.write(new File(temp.getRoot(), ProxyLocal.REQUEST), body.toString());
    }

    private JSONObject drive(ProxyLocal local) throws Exception {
        File result = new File(temp.getRoot(), ProxyLocal.RESULT);
        result.delete();
        for (int i = 0; i < 200; i++) {
            local.tick();
            if (!local.busy() && result.isFile()) return new JSONObject(RescueFiles.read(result, 64000));
            Thread.sleep(10);
        }
        fail("核心未写回结果");
        return null;
    }

    @Test public void connectStartsDirectlyWhenAlreadyConfigured() throws Exception {
        request(ProxyLocal.OP_CONNECT, null);
        JSONObject result = drive(local());
        assertTrue(result.getBoolean("ok"));
        assertEquals("已经配好的东西不该再去要一遍", "", offeredFor);
        assertEquals(java.util.Collections.singletonList("start"), client.ops);
        assertTrue(result.getJSONObject("view").getJSONObject("status").getBoolean("running"));
        assertFalse(new File(temp.getRoot(), ProxyLocal.REQUEST).exists());
    }

    @Test public void connectFetchesConfigOnlyWhenNothingIsConfigured() throws Exception {
        client.configured = false;
        request(ProxyLocal.OP_CONNECT, null);
        JSONObject result = drive(local());
        assertTrue(result.getBoolean("ok"));
        assertTrue("向服务端要配置用独立编号", offeredFor.matches("[A-Za-z0-9-]{1,96}"));
        assertEquals(java.util.Arrays.asList("configure", "start"), client.ops);
    }

    @Test public void offerRequestIdIsReusedAcrossRetriesUntilAConfigureSucceeds() throws Exception {
        // 服务端：同一编号重试返回同一份参数；换编号 60 秒限频。失败重试必须沿用编号。
        ProxyLocal local = local();
        offerFailure = new RemoteHttp.Rejected(409, "请先在面板上传代理配置");
        request(ProxyLocal.OP_UPDATE, null); drive(local);
        String first = offeredFor;
        request(ProxyLocal.OP_UPDATE, null); drive(local);
        assertEquals("失败后重试沿用同一编号", first, offeredFor);
        offerFailure = null;
        request(ProxyLocal.OP_UPDATE, null); assertTrue(drive(local).getBoolean("ok"));
        assertEquals(first, offeredFor);
        request(ProxyLocal.OP_UPDATE, null); assertTrue(drive(local).getBoolean("ok"));
        assertNotEquals("成功之后才换新编号", first, offeredFor);
    }

    @Test public void serverRefusalIsShownInTheServersOwnWords() throws Exception {
        offerFailure = new RemoteHttp.Rejected(409, "请先在面板上传代理配置");
        request(ProxyLocal.OP_UPDATE, null);
        JSONObject result = drive(local());
        assertFalse(result.getBoolean("ok"));
        assertEquals("请先在面板上传代理配置", result.getString("detail"));
        assertTrue(client.ops.isEmpty());
        assertFalse("失败也要带总览", result.isNull("view"));
    }

    @Test public void selectPassesTheNameAndReportsTheConfirmedChoice() throws Exception {
        request(ProxyLocal.OP_SELECT, new JSONObject().put("name", "a"));
        JSONObject result = drive(local());
        assertTrue(result.getBoolean("ok"));
        assertEquals(java.util.Collections.singletonList("select:a"), client.ops);
        assertEquals("a", result.getJSONObject("view").getString("selected"));
    }

    @Test public void disconnectTestAndRemoveDoNotTouchTheServer() throws Exception {
        for (String op : new String[]{ProxyLocal.OP_DISCONNECT, ProxyLocal.OP_TEST_NODES, ProxyLocal.OP_REMOVE, ProxyLocal.OP_OVERVIEW}) {
            request(op, null);
            assertTrue(op, drive(local()).getBoolean("ok"));
        }
        assertEquals("", offeredFor);
        assertEquals(java.util.Arrays.asList("stop", "test_nodes", "remove"), client.ops);
    }

    @Test public void malformedRequestGetsAResultNotSilence() throws Exception {
        RescueFiles.write(new File(temp.getRoot(), ProxyLocal.REQUEST), new JSONObject().put("op", "format_disk").put("request_id", "x").toString());
        JSONObject result = drive(local());
        assertFalse(result.getBoolean("ok"));
        assertTrue(client.ops.isEmpty());
    }

    @Test public void runtimeFailureIsReported() throws Exception {
        client.failure = new IOException("PROXY_CORE_NOT_INSTALLED");
        request(ProxyLocal.OP_DISCONNECT, null);
        JSONObject result = drive(local());
        assertFalse(result.getBoolean("ok"));
        assertEquals("PROXY_CORE_NOT_INSTALLED", result.getString("detail"));
    }
}
