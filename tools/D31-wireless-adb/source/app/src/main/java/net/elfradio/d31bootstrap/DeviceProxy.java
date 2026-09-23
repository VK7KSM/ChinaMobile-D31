package net.elfradio.d31bootstrap;

import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;

/**
 * 设备本机代理客户端的传输层（应用侧）：把请求经 root 通道写进核心目录，轮询核心写回的结果。
 * 通道与主界面其它按钮相同（RootTransport）；应用进程自己不碰代理进程、不读令牌。
 * 调用是阻塞的，必须在后台线程上用。
 */
final class DeviceProxy {
    static final String STATE_DIR = "/data/local/d31-remote/runtime/state";
    private static final long POLL_MS = 1500;

    /** 结果：`ok`、`detail`、`view`（运行时总览，失败时也尽量带）。 */
    static JSONObject call(String op, JSONObject params, long timeoutMs) throws Exception {
        String id = UUID.randomUUID().toString();
        JSONObject body = new JSONObject().put("op", op).put("request_id", id).put("requested_at_ms", System.currentTimeMillis());
        if (params != null) body.put("params", params);
        String path = STATE_DIR + "/" + ProxyLocal.REQUEST;
        RootTransport.Result written = RootTransport.execute("printf %s " + RescueFiles.quote(body.toString()) + " > " + RescueFiles.quote(path + ".tmp")
                + " && mv " + RescueFiles.quote(path + ".tmp") + " " + RescueFiles.quote(path), 8000);
        if (written.exit != 0) throw new IOException("无法把请求交给核心");
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS);
            RootTransport.Result read = RootTransport.execute("cat " + RescueFiles.quote(STATE_DIR + "/" + ProxyLocal.RESULT), 8000);
            if (read.exit != 0) continue;
            try {
                JSONObject result = new JSONObject(read.output.trim());
                if (id.equals(result.optString("request_id"))) return result;
            } catch (Exception partial) { /* 核心可能正在写，下一轮再读 */ }
        }
        throw new IOException("核心没有在时限内回应");
    }

    static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", value / 1024.0);
        if (value < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", value / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", value / 1073741824.0);
    }

    static String uptime(long startedAtMs, long now) {
        if (startedAtMs <= 0 || now < startedAtMs) return "—";
        long seconds = (now - startedAtMs) / 1000;
        return String.format(java.util.Locale.US, "%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private DeviceProxy() {}
}
