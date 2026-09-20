package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 本机按钮走的文件请求/结果通道：一次一条、失败带原因、「启用」= 取配置 + 配置事务 + 启动。 */
public class ProxyLocalTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final ProxyTasksTest.Runtime runtime = new ProxyTasksTest.Runtime();
    private JSONObject offered = new JSONObject();
    private Exception offerFailure;
    private String offeredFor = "";

    private ProxyLocal local() {
        return new ProxyLocal(temp.getRoot(), runtime, id -> { offeredFor = id; if (offerFailure != null) throw offerFailure; return offered; });
    }

    private void request(String op) throws Exception {
        RescueFiles.write(new File(temp.getRoot(), ProxyLocal.REQUEST),
                new JSONObject().put("op", op).put("request_id", "req-" + op).toString());
    }

    private JSONObject drive(ProxyLocal local) throws Exception {
        for (int i = 0; i < 200; i++) {
            local.tick();
            File result = new File(temp.getRoot(), ProxyLocal.RESULT);
            if (!local.busy() && result.isFile()) return new JSONObject(RescueFiles.read(result, 64000));
            Thread.sleep(10);
        }
        fail("核心未写回结果");
        return null;
    }

    @Test public void enableFetchesAnOfferThenConfiguresAndStarts() throws Exception {
        request(ProxyLocal.OP_ENABLE);
        JSONObject result = drive(local());
        assertTrue(result.getBoolean("ok"));
        assertEquals("req-enable", result.getString("request_id"));
        assertEquals("req-enable", offeredFor);
        assertEquals(java.util.Arrays.asList("configure", "start"), runtime.ops);
        assertTrue(result.getJSONObject("status").getBoolean("running"));
        assertFalse("请求文件必须被取走", new File(temp.getRoot(), ProxyLocal.REQUEST).exists());
    }

    @Test public void serverRefusalIsShownInTheServersOwnWords() throws Exception {
        offerFailure = new RemoteHttp.Rejected(409, "请先在面板上传代理配置");
        request(ProxyLocal.OP_ENABLE);
        JSONObject result = drive(local());
        assertFalse(result.getBoolean("ok"));
        assertEquals("请先在面板上传代理配置", result.getString("detail"));
        assertTrue(runtime.ops.isEmpty());
        assertFalse("失败也要带当前状态", result.isNull("status"));
    }

    @Test public void stopRemoveAndStatusDoNotTouchTheServer() throws Exception {
        for (String op : new String[]{ProxyLocal.OP_STOP, ProxyLocal.OP_REMOVE, ProxyLocal.OP_STATUS}) {
            request(op);
            JSONObject result = drive(local());
            assertTrue(op, result.getBoolean("ok"));
            assertEquals("req-" + op, result.getString("request_id"));
        }
        assertEquals("", offeredFor);
        assertEquals(java.util.Arrays.asList("stop", "remove"), runtime.ops);
    }

    @Test public void malformedRequestGetsAResultNotSilence() throws Exception {
        RescueFiles.write(new File(temp.getRoot(), ProxyLocal.REQUEST), new JSONObject().put("op", "format_disk").put("request_id", "x").toString());
        JSONObject result = drive(local());
        assertFalse(result.getBoolean("ok"));
        assertTrue(runtime.ops.isEmpty());
    }

    @Test public void runtimeFailureIsReported() throws Exception {
        runtime.failure = new IOException("PROXY_CORE_NOT_INSTALLED");
        request(ProxyLocal.OP_STOP);
        JSONObject result = drive(local());
        assertFalse(result.getBoolean("ok"));
        assertEquals("PROXY_CORE_NOT_INSTALLED", result.getString("detail"));
    }
}
