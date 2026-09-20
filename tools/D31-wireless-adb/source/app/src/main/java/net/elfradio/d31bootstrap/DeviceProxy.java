package net.elfradio.d31bootstrap;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;

/**
 * 设备本机的代理操作入口（应用侧）：把请求经 root 通道写进核心目录，轮询核心写回的结果。
 * 通道与本界面其它按钮相同（RootTransport）；应用进程自己不碰代理进程、不读令牌。
 */
final class DeviceProxy {
    static final String STATE_DIR = "/data/local/d31-remote/runtime/state";
    private static final long POLL_MS = 2000, TIMEOUT_MS = 180000;

    static String summary(JSONObject status) {
        if (status == null) return "状态不可用";
        StringBuilder text = new StringBuilder();
        boolean installed = status.optBoolean("asset_verified");
        text.append("核心：").append(installed ? "已安装 " + status.optString("version") : "未安装（按需下载）").append('\n');
        text.append("配置：").append(status.optBoolean("configured") ? "已配置 " + status.optString("config_version") : "未配置").append('\n');
        text.append("运行：").append(status.optBoolean("running") ? "运行中" : "已停止")
            .append("，本机监听 ").append(status.optBoolean("http_ready") && status.optBoolean("socks_ready") ? "就绪" : "未就绪")
            .append("，节点 ").append(status.optBoolean("proxy_reachable") ? "可达" : "未验证或不可达").append('\n');
        text.append("管理连接：").append("proxy".equals(status.optString("management_via")) ? "经代理" : "直连").append('\n');
        String error = status.optString("error_category");
        if (!error.isEmpty() && !"none".equals(error)) text.append("错误类别：").append(error);
        return text.toString().trim();
    }

    static void request(Activity activity, String op, String label) {
        Toast.makeText(activity, label + "…", Toast.LENGTH_SHORT).show();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String id = UUID.randomUUID().toString();
                String body = new JSONObject().put("op", op).put("request_id", id).put("requested_at_ms", System.currentTimeMillis()).toString();
                String path = STATE_DIR + "/" + ProxyLocal.REQUEST;
                RootTransport.Result written = RootTransport.execute("printf %s " + RescueFiles.quote(body) + " > " + RescueFiles.quote(path + ".tmp")
                        + " && mv " + RescueFiles.quote(path + ".tmp") + " " + RescueFiles.quote(path), 8000);
                if (written.exit != 0) throw new IOException("无法把请求交给核心");
                JSONObject result = poll(id);
                main.post(() -> show(activity, label, result));
            } catch (Exception failure) {
                String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                main.post(() -> Toast.makeText(activity, label + "失败：" + detail, Toast.LENGTH_LONG).show());
            }
        }, "d31-proxy-button").start();
    }

    private static JSONObject poll(String id) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS);
            RootTransport.Result read = RootTransport.execute("cat " + RescueFiles.quote(STATE_DIR + "/" + ProxyLocal.RESULT), 8000);
            if (read.exit != 0) continue;
            try {
                JSONObject result = new JSONObject(read.output.trim());
                if (id.equals(result.optString("request_id"))) return result;
            } catch (Exception partial) { /* 核心可能正在写，下一轮再读 */ }
        }
        throw new IOException("核心 3 分钟内没有回应（下载核心与配置可能仍在进行，稍后按「代理：状态」查看）");
    }

    private static void show(Activity activity, String label, JSONObject result) {
        if (activity.isFinishing()) return;
        String detail = result.optString("detail");
        String text = (detail.isEmpty() ? "" : detail + "\n\n") + summary(result.optJSONObject("status"));
        new AlertDialog.Builder(activity).setTitle(label + (result.optBoolean("ok") ? "" : "：失败"))
                .setMessage(text).setPositiveButton("关闭", null).show();
    }

    private DeviceProxy() {}
}
