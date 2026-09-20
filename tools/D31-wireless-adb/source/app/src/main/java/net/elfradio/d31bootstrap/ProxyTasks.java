package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/**
 * 代理类受管任务：`configure_proxy` / `start_proxy` / `stop_proxy` / `test_proxy` / `remove_proxy`。
 *
 * 一次只执行一条；执行在独立线程上（配置要下载最多 2 MiB 配置和几十 MB 核心，不能占着核心的工作线程），
 * 回执从工作线程发。回执阶梯与二维码任务同一套：服务端对不在迁移表里的回执静默丢弃，必须回读状态。
 * 记账落盘：核心重启后未完成的任务重新执行（五种操作都幂等），已完成未确认的补发回执。
 */
final class ProxyTasks {
    static final String CONFIGURE = "configure_proxy", START = "start_proxy", STOP = "stop_proxy",
            TEST = "test_proxy", REMOVE = "remove_proxy";
    static final long RETRY_BASE_MS = 30000, RETRY_MAX_MS = 300000, RETENTION_MS = 7L * 24 * 3600 * 1000;
    static final int MAX_JOURNAL = 256;

    interface Runtime {
        JSONObject configure(JSONObject params) throws Exception;
        JSONObject start() throws Exception;
        JSONObject stop() throws Exception;
        JSONObject test() throws Exception;
        JSONObject remove() throws Exception;
        JSONObject status() throws Exception;
    }
    /** 发一条回执，返回服务端回读的任务状态。 */
    interface Progress { String send(JSONObject receipt) throws Exception; }
    interface Clock { long wall(); }

    private final File root;
    private final String deviceId;
    private final Runtime runtime;
    private final Progress progress;
    private final Clock clock;
    private File currentFile;
    private JSONObject current;
    private Thread worker;
    private final AtomicReference<JSONObject> outcome = new AtomicReference<JSONObject>();
    private long lastSweep;

    ProxyTasks(File directory, String deviceId, Runtime runtime, Progress progress, Clock clock) throws Exception {
        root = new File(directory, "proxy-tasks");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("PROXY_TASK_DIR");
        this.deviceId = deviceId; this.runtime = runtime; this.progress = progress; this.clock = clock;
    }

    static boolean supports(String type) {
        return CONFIGURE.equals(type) || START.equals(type) || STOP.equals(type) || TEST.equals(type) || REMOVE.equals(type);
    }

    /** 与 Pixel 网关同一份合同：编号形状、期限、类型、参数只在配置任务上出现。 */
    static JSONObject validate(JSONObject task, long now) throws IOException {
        if (task == null) throw new IOException("PROXY_TASK_MISSING");
        String type = task.optString("type"), id = task.optString("id");
        long expires = task.optLong("expires_at");
        if (!id.matches("[A-Za-z0-9-]{1,96}") || !supports(type)) throw new IOException("PROXY_TASK_INVALID");
        if (expires <= 0 || expires > now + 86400000L) throw new IOException("PROXY_TASK_EXPIRY_INVALID");
        JSONObject params = task.optJSONObject("params");
        if (CONFIGURE.equals(type)) {
            if (params == null) throw new IOException("PROXY_TASK_PARAMS_MISSING");
            for (java.util.Iterator<String> keys = params.keys(); keys.hasNext();) {
                String key = keys.next();
                if (!key.equals("url") && !key.equals("size") && !key.equals("sha256") && !key.equals("version") && !key.equals("core"))
                    throw new IOException("PROXY_TASK_PARAMS_INVALID");
            }
            String url = ProxyDownload.validate(params.optString("url"), ProxyDownload.CONFIG_PATH);
            if (!url.contains("/proxy-config/" + id + "?")) throw new IOException("PROXY_TASK_URL_ID_MISMATCH");
            if (params.optLong("size") < 2 || params.optLong("size") > ProxyRuntime.MAX_CONFIG_BYTES
                    || !params.optString("sha256").matches("[0-9a-f]{64}")) throw new IOException("PROXY_TASK_PARAMS_INVALID");
        } else if (params != null && params.length() != 0) throw new IOException("PROXY_TASK_PARAMS_UNEXPECTED");
        return task;
    }

    private File file(String id) { return new File(root, id + ".json"); }

    private JSONObject read(File file) throws Exception { return new JSONObject(RescueFiles.read(file, 64000)); }

    /** 上报每轮都会把同一条任务带下来；已记账的只处理取消标志或补发回执。 */
    synchronized void accept(JSONObject task) throws Exception {
        long now = clock.wall();
        JSONObject valid;
        try { valid = validate(task, now); }
        catch (IOException invalid) {
            String id = task == null ? "" : task.optString("id");
            if (!id.matches("[A-Za-z0-9-]{1,96}") || file(id).exists()) return;
            JSONObject saved = new JSONObject().put("task", task).put("phase", "done")
                    .put("receipt", receipt(id, "rejected", "代理任务参数不满足执行条件", null));
            RescueFiles.write(file(id), saved.toString());
            return;
        }
        String id = valid.getString("id");
        File file = file(id);
        if (file.exists()) {
            JSONObject saved = read(file);
            if (valid.optBoolean("cancel_requested") && !saved.getJSONObject("task").optBoolean("cancel_requested")) {
                saved.getJSONObject("task").put("cancel_requested", true);
                RescueFiles.write(file, saved.toString());
            }
            return;
        }
        File[] records = root.listFiles();
        if (records != null && records.length >= MAX_JOURNAL) throw new IOException("PROXY_TASK_JOURNAL_FULL");
        RescueFiles.write(file, new JSONObject().put("task", valid).put("phase", "queued").toString());
    }

    /** 由核心每轮调用；推进当前任务一小步，从不阻塞在网络或执行上。 */
    synchronized void tick() {
        long now = clock.wall();
        try {
            if (current == null) pick();
            if (current == null) { sweep(now); return; }
            if (now < current.optLong("retry_at")) return;
            String phase = current.optString("phase");
            JSONObject task = current.getJSONObject("task");
            String id = task.getString("id");
            if ("queued".equals(phase)) {
                if (task.optBoolean("cancel_requested")) { finish("rejected", "任务已取消，未执行", null); return; }
                if (task.optLong("expires_at") <= now) { finish("rejected", "任务已过期，未执行", null); return; }
                deliver("claimed", receipt(id, "claimed", "设备已接收代理任务", null));
                phase("claimed");
            }
            if ("claimed".equals(phase = current.optString("phase"))) {
                deliver("running", receipt(id, "running", "设备正在执行代理任务", null));
                phase("running");
            }
            if ("running".equals(current.optString("phase"))) {
                if (worker == null) launch(task);
                JSONObject result = outcome.get();
                if (result == null) return;
                worker = null; outcome.set(null);
                boolean ok = result.optBoolean("ok");
                finish(ok ? "success" : "failed", result.optString("detail"), result.optJSONObject("result"));
                return;
            }
            if ("done".equals(current.optString("phase"))) flush();
        } catch (Exception failure) { backoff(failure); }
    }

    private void pick() throws Exception {
        File[] files = root.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File file : files) {
            JSONObject saved = read(file);
            if (saved.optBoolean("acknowledged")) continue;
            currentFile = file; current = saved; return;
        }
    }

    private void launch(JSONObject task) throws Exception {
        final String type = task.getString("type");
        final JSONObject params = task.optJSONObject("params");
        worker = new Thread(() -> {
            JSONObject result = new JSONObject();
            try {
                JSONObject status = CONFIGURE.equals(type) ? runtime.configure(params)
                        : START.equals(type) ? runtime.start() : STOP.equals(type) ? runtime.stop()
                        : TEST.equals(type) ? runtime.test() : runtime.remove();
                boolean ok = TEST.equals(type) ? status.optBoolean("proxy_reachable") : true;
                result.put("ok", ok).put("detail", ok ? "代理任务已完成" : "代理路径检测未通过")
                        .put("result", envelope(type, status));
            } catch (Exception failure) {
                JSONObject status = null;
                try { status = runtime.status(); } catch (Exception unavailable) { /* 状态取不到就回空结果 */ }
                try { result.put("ok", false).put("detail", "代理任务执行失败：" + code(failure)).put("result", status == null ? null : envelope(type, status)); }
                catch (Exception ignored) { }
            }
            outcome.set(result);
        }, "d31-proxy-task");
        worker.setDaemon(true);
        worker.start();
    }

    static String code(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    static JSONObject envelope(String type, JSONObject status) throws Exception {
        return new JSONObject().put("stage", "proxy").put("action", type).put("proxy", status);
    }

    private JSONObject receipt(String id, String state, String detail, JSONObject result) throws Exception {
        return new JSONObject().put("device_id", deviceId).put("task_id", id).put("state", state)
                .put("detail", detail).put("result", result == null ? JSONObject.NULL : result);
    }

    private void phase(String value) throws Exception {
        current.put("phase", value).put("retry_at", 0).put("failures", 0);
        RescueFiles.write(currentFile, current.toString());
    }

    private void finish(String state, String detail, JSONObject result) throws Exception {
        current.put("phase", "done").put("receipt", receipt(current.getJSONObject("task").getString("id"), state, detail, result))
                .put("retry_at", 0).put("failures", 0);
        RescueFiles.write(currentFile, current.toString());
        flush();
    }

    /** 终态回执：发出并被服务端采纳才算完，之后这条任务从当前位让开。 */
    private void flush() throws Exception {
        JSONObject receipt = current.getJSONObject("receipt");
        deliver(receipt.getString("state"), new JSONObject(receipt.toString()));
        current.put("acknowledged", true).put("acknowledged_at_ms", clock.wall());
        RescueFiles.write(currentFile, current.toString());
        current = null; currentFile = null;
    }

    /** 与 ShareLinkTasks 同一套阶梯：目标态没被采纳就补中间态再发一次，最多走完 pending→claimed→running→终态。 */
    private void deliver(String target, JSONObject receipt) throws Exception {
        for (int attempt = 0; attempt < 6; attempt++) {
            String server = progress.send(new JSONObject(receipt.toString()));
            if (target.equals(server) || ShareLinkTasks.rank(server) > ShareLinkTasks.rank(target)) return;
            String step = ShareLinkTasks.bridge(server, target);
            if (step == null) throw new IOException("PROXY_TASK_RECEIPT_DROPPED:" + server);
            progress.send(receipt(receipt.getString("task_id"), step, "设备推进代理任务", null));
        }
        throw new IOException("PROXY_TASK_RECEIPT_LADDER");
    }

    private void backoff(Exception failure) {
        try {
            if (current == null) return;
            int failures = Math.min(16, current.optInt("failures") + 1);
            long delay = Math.min(RETRY_MAX_MS, RETRY_BASE_MS << Math.min(4, failures - 1));
            current.put("failures", failures).put("retry_at", clock.wall() + delay).put("last_failure", failure.getClass().getSimpleName());
            RescueFiles.write(currentFile, current.toString());
        } catch (Exception ignored) { }
    }

    /** 已确认且过了保留期的记账删除；保留期内留着做去重依据。 */
    private void sweep(long now) {
        if (now - lastSweep < 3600000L) return;
        lastSweep = now;
        File[] files = root.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;
        for (File file : files) {
            try {
                JSONObject saved = read(file);
                if (saved.optBoolean("acknowledged") && now - saved.optLong("acknowledged_at_ms") > RETENTION_MS) file.delete();
            } catch (Exception unreadable) { file.delete(); }
        }
    }

    /** 测试用：当前是否有任务在推进。 */
    synchronized boolean busy() { return current != null; }
}
