package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/**
 * 设备本机的代理操作入口（核心侧）：站在机器跟前的人在 elfRemote 界面按按钮，
 * 应用进程经 root 通道把一份请求写进核心目录，核心每轮取走执行，结果写回同目录，应用轮询读取。
 *
 * 与服务端任务走同一个 {@link ProxyTasks.Runtime}，所以事务、看门狗、状态形状完全一致。
 * 「下载并启用」需要配置：设备自己向服务端取一份与 `configure_proxy` 同形的参数（含可选核心清单），
 * 没有已上传配置时服务端拒绝，界面上直接把原因显示出来。一次只执行一条。
 */
final class ProxyLocal {
    static final String REQUEST = "proxy-local-request.json", RESULT = "proxy-local-result.json";
    static final String OFFER_PATH = "/api/devices/proxy-config/offer";
    static final String OP_ENABLE = "enable", OP_STOP = "stop", OP_REMOVE = "remove", OP_STATUS = "status";

    /** 向服务端取一份配置参数（与 `configure_proxy` 的 params 同形）。 */
    interface Offer { JSONObject fetch(String requestId) throws Exception; }

    private final File request, result;
    private final ProxyTasks.Runtime runtime;
    private final Offer offer;
    private Thread worker;

    ProxyLocal(File root, ProxyTasks.Runtime runtime, Offer offer) {
        request = new File(root, REQUEST); result = new File(root, RESULT);
        this.runtime = runtime; this.offer = offer;
    }

    static boolean supports(String op) {
        return OP_ENABLE.equals(op) || OP_STOP.equals(op) || OP_REMOVE.equals(op) || OP_STATUS.equals(op);
    }

    /** 由核心每轮调用；有请求且没有在执行的就取走执行，从不阻塞。 */
    synchronized void tick() {
        if (worker != null) { if (worker.isAlive()) return; worker = null; }
        if (!request.isFile()) return;
        JSONObject body;
        try { body = new JSONObject(RescueFiles.read(request, 4096)); }
        catch (Exception malformed) { request.delete(); return; }
        request.delete();
        final String op = body.optString("op"), id = body.optString("request_id");
        if (!supports(op) || !id.matches("[A-Za-z0-9_.:-]{1,96}")) { write(id, op, false, "本机请求无效", null); return; }
        worker = new Thread(() -> execute(op, id), "d31-proxy-local");
        worker.setDaemon(true);
        worker.start();
    }

    private void execute(String op, String id) {
        try {
            JSONObject status;
            if (OP_ENABLE.equals(op)) {
                JSONObject params = offer.fetch(id);
                if (params == null) throw new IOException("服务端未返回配置");
                runtime.configure(params);
                status = runtime.start();
                write(id, op, status.optBoolean("proxy_reachable"), status.optBoolean("proxy_reachable") ? "代理已启用并测试通过" : "代理已启动但连通测试未通过", status);
            } else if (OP_STOP.equals(op)) write(id, op, true, "代理已停用", runtime.stop());
            else if (OP_REMOVE.equals(op)) write(id, op, true, "代理模块已移除", runtime.remove());
            else write(id, op, true, "", runtime.status());
        } catch (Exception failure) {
            JSONObject status = null;
            try { status = runtime.status(); } catch (Exception unavailable) { /* 状态取不到就不带 */ }
            write(id, op, false, describe(failure), status);
        }
    }

    /** 给人看的失败原因：服务端拒绝时用它的话，本机错误码原样给。 */
    static String describe(Exception failure) {
        if (failure instanceof RemoteHttp.Rejected) {
            RemoteHttp.Rejected rejected = (RemoteHttp.Rejected) failure;
            return rejected.reason == null || rejected.reason.isEmpty() ? "服务端拒绝（HTTP " + rejected.status + "）" : rejected.reason;
        }
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    private void write(String id, String op, boolean ok, String detail, JSONObject status) {
        try {
            RescueFiles.write(result, new JSONObject().put("request_id", id).put("op", op).put("ok", ok)
                    .put("detail", detail).put("status", status == null ? JSONObject.NULL : status)
                    .put("at_ms", System.currentTimeMillis()).toString());
        } catch (Exception unavailable) { System.err.println("PROXY_LOCAL_RESULT_WRITE_FAILED " + unavailable); }
    }

    /** 测试用。 */
    synchronized boolean busy() { return worker != null && worker.isAlive(); }
}
