package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 设备本机的代理客户端入口（核心侧）：应用进程经 root 通道把一份请求写进核心目录，
 * 核心每轮取走执行，结果写回同目录，应用轮询读取。
 *
 * 与服务端任务走同一个运行时，所以事务、看门狗、状态形状完全一致。一次只执行一条。
 * 「更新订阅」向服务端取一份与 `configure_proxy` 同形的参数（含可选核心清单）；
 * 「连接」在已有配置时直接启动，没有配置才去取——已经配好的东西不该再去要一遍。
 */
final class ProxyLocal {
    static final String REQUEST = "proxy-local-request.json", RESULT = "proxy-local-result.json";
    static final String OFFER_PATH = "/api/devices/proxy-config/offer";
    static final String OP_OVERVIEW = "overview", OP_CONNECT = "connect", OP_DISCONNECT = "disconnect",
            OP_UPDATE = "update", OP_TEST_NODES = "test_nodes", OP_SELECT = "select", OP_SET_APPS = "set_apps",
            OP_REMOVE = "remove";

    /** 运行时对本机界面开放的操作面：服务端任务那一套加上节点、分流、总览。 */
    interface Client extends ProxyTasks.Runtime {
        JSONObject overview() throws Exception;
        JSONArray nodes() throws Exception;
        JSONArray testNodes() throws Exception;
    }
    /** 向服务端要配置时的请求编号：同一编号重试拿回同一份参数，换编号有 60 秒限频；成功配置后才换新编号。 */
    private String offerRequestId = "";
    private long offerRequestedAt;
    static final long OFFER_ID_TTL_MS = 9 * 60 * 1000;

    private JSONObject fetchOffer() throws Exception {
        long now = System.currentTimeMillis();
        if (offerRequestId.isEmpty() || now - offerRequestedAt > OFFER_ID_TTL_MS) {
            offerRequestId = java.util.UUID.randomUUID().toString(); offerRequestedAt = now;
        }
        JSONObject params = offer.fetch(offerRequestId);
        if (params == null) throw new IOException("服务端未返回配置");
        return params;
    }
    /** 向服务端取一份配置参数（与 `configure_proxy` 的 params 同形）。 */
    interface Offer { JSONObject fetch(String requestId) throws Exception; }

    private final File request, result;
    private final Client client;
    private final Offer offer;
    private Thread worker;

    ProxyLocal(File root, Client client, Offer offer) {
        request = new File(root, REQUEST); result = new File(root, RESULT);
        this.client = client; this.offer = offer;
    }

    static boolean supports(String op) {
        return OP_OVERVIEW.equals(op) || OP_CONNECT.equals(op) || OP_DISCONNECT.equals(op) || OP_UPDATE.equals(op)
                || OP_TEST_NODES.equals(op) || OP_SELECT.equals(op) || OP_SET_APPS.equals(op) || OP_REMOVE.equals(op);
    }

    /** 由核心每轮调用；有请求且没有在执行的就取走执行，从不阻塞。 */
    synchronized void tick() {
        if (worker != null) { if (worker.isAlive()) return; worker = null; }
        if (!request.isFile()) return;
        JSONObject body;
        try { body = new JSONObject(RescueFiles.read(request, 8192)); }
        catch (Exception malformed) { request.delete(); return; }
        request.delete();
        final String op = body.optString("op"), id = body.optString("request_id");
        final JSONObject params = body.optJSONObject("params");
        if (!supports(op) || !id.matches("[A-Za-z0-9_.:-]{1,96}")) { write(id, op, false, "本机请求无效", null); return; }
        worker = new Thread(() -> execute(op, id, params == null ? new JSONObject() : params), "d31-proxy-local");
        worker.setDaemon(true);
        worker.start();
    }

    private void execute(String op, String id, JSONObject params) {
        try {
            String detail = "";
            if (OP_CONNECT.equals(op)) {
                boolean configured = client.status().optBoolean("configured");
                if (!configured) { client.configure(fetchOffer()); offerRequestId = ""; detail = "已取得配置，"; }
                JSONObject status = client.start();
                detail += status.optBoolean("proxy_reachable") ? "已连接，节点可达" : "已启动，但节点连通测试未通过";
            } else if (OP_DISCONNECT.equals(op)) { client.stop(); detail = "已断开"; }
            else if (OP_UPDATE.equals(op)) { client.configure(fetchOffer()); offerRequestId = ""; detail = "订阅已更新"; }
            else if (OP_TEST_NODES.equals(op)) { client.testNodes(); detail = "测速完成"; }
            else if (OP_SELECT.equals(op)) { detail = "已切换到 " + client.selectNode(params.optString("name")); }
            else if (OP_SET_APPS.equals(op)) {
                List<String> apps = new ArrayList<>();
                JSONArray given = params.optJSONArray("apps");
                for (int i = 0; given != null && i < given.length(); i++) apps.add(given.optString(i));
                client.setApps(apps); detail = "分流名单已更新";
            } else if (OP_REMOVE.equals(op)) { client.remove(); detail = "代理模块已移除"; }
            write(id, op, true, detail, client.overview());
        } catch (Exception failure) {
            JSONObject view = null;
            try { view = client.overview(); } catch (Exception unavailable) { /* 状态取不到就不带 */ }
            write(id, op, false, describe(failure), view);
        }
    }

    /** 给人看的失败原因：服务端拒绝时用它的话，本机错误码原样给。 */
    static String describe(Exception failure) {
        if (failure instanceof RemoteHttp.Rejected) {
            RemoteHttp.Rejected rejected = (RemoteHttp.Rejected) failure;
            // 401/404/405：本机取订阅的接口在服务端还没有；Worker 对未知路径回的是管理员登录提示，对座机前的人毫无意义。
            if (rejected.status == 401 || rejected.status == 404 || rejected.status == 405)
                return "管理服务器还没有提供本机取订阅的接口（HTTP " + rejected.status + "）。请从管理面板给这台设备下发配置，或稍后再试。";
            return rejected.reason == null || rejected.reason.isEmpty() ? "服务端拒绝（HTTP " + rejected.status + "）" : rejected.reason;
        }
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    private void write(String id, String op, boolean ok, String detail, JSONObject view) {
        try {
            RescueFiles.write(result, new JSONObject().put("request_id", id).put("op", op).put("ok", ok)
                    .put("detail", detail).put("view", view == null ? JSONObject.NULL : view)
                    .put("at_ms", System.currentTimeMillis()).toString());
        } catch (Exception unavailable) { System.err.println("PROXY_LOCAL_RESULT_WRITE_FAILED " + unavailable); }
    }

    /** 测试用。 */
    synchronized boolean busy() { return worker != null && worker.isAlive(); }
}
