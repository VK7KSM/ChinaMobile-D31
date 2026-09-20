package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/**
 * 分享链接任务的核心侧拥有者：校验载荷、拉起显示窗口、收集结果、发回执。
 *
 * 回执套既有任务状态机：state 只能是 claimed / running / success / failed / rejected，
 * 且显示之前必须先发 claimed——服务端不接受从 pending 直接跳到 running。
 * shown/dismissed/expired/superseded/failed 这些语义一律放在 result.share_link.outcome 里，
 * 塞进 state 会被服务端的迁移校验整条拒掉。
 *
 * 终态回执落盘后才算数：服务端没确认就按退避重试，直到 acknowledged，
 * 否则设备这边看着结束了、服务端那边任务永远挂在 running。
 *
 * 安卓相关的窗口与Binder在 Screen 实现里，本类只做判定与记账，便于单元测试。
 */
final class ShareLinkTasks implements AutoCloseable {
    /** 显示窗口；show 立即返回，结果经 Listener 回报。 */
    interface Screen {
        void show(ShareLinkPayload payload, String session, Listener listener) throws Exception;
        void dismiss();
    }
    interface Listener { void outcome(String session, String outcome, String reason); }
    /**
     * 发回执，返回服务端回读的任务状态。
     *
     * 不能用 ok 判定采纳：服务端对不在迁移表里的回执是静默丢弃，HTTP 仍是 200、ok 仍是 true，
     * 只是任务状态原地不动。所以只有回读状态和所发一致才算数，否则要补发缺的那一步。
     * 发不出去（网络不可达等）才抛出，由本类按退避重试。
     */
    interface Progress { String send(JSONObject receipt) throws Exception; }
    /** 警报是找设备用的，优先级高于二维码；占用时直接拒收不排队。 */
    interface Busy { boolean alarmActive(); }
    interface Clock { long wall(); long elapsed(); }

    /** 窗口没有按时回报结束时的宽限；超过就按到期结清，不能让任务永远挂着。 */
    static final long GRACE_MS = 10000L;
    static final long RETRY_BASE_MS = 30000L, RETRY_MAX_MS = 300000L;
    /** 已确认的记录保留多久。留一段是为了防重投重弹，不是为了存档。 */
    static final long RETENTION_MS = 7L * 24 * 3600 * 1000;
    private static final String ID = "[A-Za-z0-9_-]{1,96}";
    private static final int RECORD_MAX_CHARS = 16000;

    private final File records;
    private final String deviceId;
    private final Screen screen;
    private final Progress progress;
    private final Busy busy;
    private final Clock clock;
    /** 已确认、无需再补发的任务号。避免 retry() 每轮把整个目录读进内存解析一遍。 */
    private final java.util.Set<String> settledIds = new java.util.HashSet<>();
    private String current = "", session = "", qrMode = "";
    private long deadlineElapsed, shownAtMs;
    private boolean closed;

    ShareLinkTasks(File privateRoot, String deviceId, Screen screen, Progress progress, Busy busy, Clock clock) throws Exception {
        records = new File(privateRoot, "share-links");
        if (!records.isDirectory() && !records.mkdirs()) throw new IOException("SHARE_LINK_JOURNAL_UNAVAILABLE");
        this.deviceId = deviceId; this.screen = screen; this.progress = progress; this.busy = busy; this.clock = clock;
    }

    static JSONObject result(String outcome, long shownAtMs, long endedAtMs, String reason, String qrMode) throws Exception {
        return new JSONObject().put("share_link", new JSONObject().put("outcome", outcome)
                .put("shown_at_ms", shownAtMs).put("ended_at_ms", endedAtMs)
                .put("reason", reason == null ? "" : reason).put("qr_mode", qrMode == null ? "" : qrMode));
    }

    private JSONObject receipt(String taskId, String state, String detail, JSONObject result) throws Exception {
        return new JSONObject().put("device_id", deviceId).put("task_id", taskId)
                .put("state", state).put("detail", detail).put("result", result);
    }

    private File record(String taskId) { return new File(records, taskId + ".json"); }

    private JSONObject load(String taskId) throws Exception {
        File file = record(taskId);
        return file.isFile() ? new JSONObject(RescueFiles.read(file, RECORD_MAX_CHARS)) : null;
    }

    private void save(String taskId, JSONObject saved) throws Exception {
        RescueFiles.write(record(taskId), saved.toString());
    }

    synchronized void accept(JSONObject task) throws Exception {
        if (closed) return;
        String taskId = task.optString("id");
        // 任务号会被当成文件名用，非法形状必须在落盘之前就挡住。
        if (!taskId.matches(ID)) throw new IOException("SHARE_LINK_TASK_ID_INVALID");

        JSONObject saved = load(taskId);
        if (saved != null) {
            // 已结清的任务不重做：推送重投或补领时只补确认，绝不再弹一次窗口。
            if (saved.has("receipt")) { flush(taskId, saved); return; }
            if (taskId.equals(current)) {
                if (task.optBoolean("cancel_requested")) settle(ShareLinkOutcome.DISMISSED, "cancelled");
                return;
            }
        } else {
            saved = new JSONObject().put("task", task);
            save(taskId, saved);
        }
        drive(taskId, saved, task);
    }

    /** 从「已记账、未结清」推进到「已显示」或「已拒收」。中途抛出的，下一轮重新走一遍即可。 */
    private void drive(String taskId, JSONObject saved, JSONObject task) throws Exception {
        long now = clock.wall();
        if (task.optBoolean("cancel_requested")) {
            finish(taskId, saved, "rejected", "任务已取消，未显示", ShareLinkOutcome.DISMISSED, "cancelled", 0, "");
            return;
        }
        if (task.optLong("expires_at", 0) > 0 && task.optLong("expires_at") <= now) {
            // 领取期限已过：设备离线一段时间后再上线，不应该突然弹出一个二维码。
            finish(taskId, saved, "rejected", "任务领取期限已过", ShareLinkOutcome.FAILED, "envelope_expired", 0, "");
            return;
        }
        ShareLinkPayload payload;
        try { payload = ShareLinkPayload.parse(task.optJSONObject("params"), now); }
        catch (Exception invalid) {
            finish(taskId, saved, "failed", invalid.getMessage(), ShareLinkOutcome.FAILED, "", 0, "");
            return;
        }
        // 排在载荷校验之后：警报期间收到一个畸形载荷，该报载荷无效而不是 alarm_busy，
        // 否则服务端学不到载荷有问题，重试多少次都一样。
        if (busy != null && busy.alarmActive()) {
            finish(taskId, saved, "rejected", "警报占用中，未显示二维码", ShareLinkOutcome.FAILED, "alarm_busy", 0, "");
            return;
        }

        // 领取确认必须在显示之前落地；发不出去就整个退回，下一轮重来，绝不先斩后奏。
        if (!saved.optBoolean("claimed")) {
            deliver(taskId, receipt(taskId, "claimed", detail("claimed"), null));
            saved.put("claimed", true);
            save(taskId, saved);
        }

        // 上一个还在显示就先结清为被顶替，避免核心同时等两个窗口的结果。
        settle(ShareLinkOutcome.SUPERSEDED, "superseded");
        current = taskId;
        session = java.util.UUID.randomUUID().toString();
        qrMode = ShareLinkPayload.qrMode(payload.qrText);
        shownAtMs = 0;
        deadlineElapsed = clock.elapsed() + Math.max(1, payload.deadline(now) - now) + GRACE_MS;
        final String owned = session;
        try { screen.show(payload, session, (s, outcome, reason) -> report(owned, s, outcome, reason)); }
        catch (Exception failure) {
            current = ""; session = "";
            finish(taskId, saved, "failed", "窗口未能显示", ShareLinkOutcome.FAILED,
                    failure.getClass().getSimpleName(), 0, qrMode);
        }
    }

    /** 窗口回报；只接受当前会话，迟到的旧会话回报一律丢弃。 */
    private synchronized void report(String owned, String reported, String outcome, String reason) {
        if (closed || current.isEmpty() || !owned.equals(session) || !owned.equals(reported)) return;
        String taskId = current;
        try {
            JSONObject saved = load(taskId);
            if (saved == null || saved.has("receipt")) return;
            if (ShareLinkOutcome.SHOWN.equals(outcome)) {
                shownAtMs = clock.wall();
                saved.put("shown_at_ms", shownAtMs);
                save(taskId, saved);
                // running 是过程态：发不出去不影响最终结果，终态回执自己带重试补齐。
                try { deliver(taskId, receipt(taskId, "running", "二维码已显示",
                        result(outcome, shownAtMs, 0, reason, qrMode))); }
                catch (Exception unconfirmed) { /* 终态会重发，此处不自旋 */ }
                return;
            }
            current = ""; session = "";
            boolean ok = !ShareLinkOutcome.FAILED.equals(outcome);
            finish(taskId, saved, ok ? "success" : "failed", ok ? "二维码已结束显示" : "二维码未能显示",
                    outcome, reason, shownAtMs, qrMode);
        } catch (Exception unrecorded) { System.err.println("SHARE_LINK_REPORT_UNRECORDED " + taskId); }
    }

    /** 由核心工作循环驱动：兜底到期结清，并补发服务端还没确认的终态回执。 */
    synchronized void tick() {
        if (closed) return;
        if (!current.isEmpty() && clock.elapsed() >= deadlineElapsed) {
            // 窗口所在进程被回收时不会有回报，这里按时限兜底。
            String taskId = current; current = ""; session = "";
            screen.dismiss();
            try {
                JSONObject saved = load(taskId);
                if (saved != null && !saved.has("receipt"))
                    finish(taskId, saved, "success", "二维码已到期撤下", ShareLinkOutcome.EXPIRED,
                            "no_report_before_deadline", shownAtMs, qrMode);
            } catch (Exception unrecorded) { System.err.println("SHARE_LINK_SWEEP_UNRECORDED " + taskId); }
        }
        retry();
    }

    /**
     * 终态回执没被服务端确认就一直重试；不重试过程态，那个重发没有意义。
     *
     * 每轮都读整个目录会随使用时间线性变慢：记录只增不删，tick 又是5秒一次，
     * 用一年就是每5秒把几百个早已结清的 JSON 全解析一遍。所以先用内存里的已结清集合
     * 按文件名过滤，真有待补发的才读盘；同时顺带清理超过保留期的已确认记录。
     */
    private void retry() {
        File[] files = records.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;
        long now = clock.wall();
        for (File file : files) {
            String name = file.getName(), taskId = name.substring(0, name.length() - ".json".length());
            if (!taskId.matches(ID) || taskId.equals(current)) continue;
            if (settledIds.contains(taskId)) { expire(file, now); continue; }
            try {
                JSONObject saved = new JSONObject(RescueFiles.read(file, RECORD_MAX_CHARS));
                if (!saved.has("receipt")) continue;
                if (saved.optBoolean("acknowledged")) { settledIds.add(taskId); expire(file, now); continue; }
                if (saved.optLong("retry_at") > now) continue;
                flush(taskId, saved);
            } catch (Exception unreadable) { /* 单条记录坏了不拖垮整轮补发 */ }
        }
    }

    /** 已确认且过了保留期的记录可以删；删掉后重投会再弹一次，所以保留期不能太短。 */
    private void expire(File file, long now) {
        long modified = file.lastModified();
        if (modified <= 0 || now - modified < RETENTION_MS) return;
        String name = file.getName();
        if (file.delete()) settledIds.remove(name.substring(0, name.length() - ".json".length()));
    }

    private void settle(String outcome, String reason) throws Exception {
        if (current.isEmpty()) return;
        String taskId = current; current = ""; session = "";
        screen.dismiss();
        JSONObject saved = load(taskId);
        if (saved == null || saved.has("receipt")) return;
        finish(taskId, saved, "success", "二维码已撤下", outcome, reason, shownAtMs, qrMode);
    }

    private void finish(String taskId, JSONObject saved, String state, String detail,
                        String outcome, String reason, long shownAt, String mode) throws Exception {
        saved.put("receipt", receipt(taskId, state, detail, result(outcome, shownAt, clock.wall(), reason, mode)));
        save(taskId, saved);
        flush(taskId, saved);
    }

    /**
     * 服务端停在 from、我们要到 to 时中间还差的那一步；没有可补的返回 null。
     * 迁移表是 pending→claimed→running→终态，终态里只有 success 必须走满这条链，
     * rejected 和 failed 从 pending、claimed、running 都可以直接到。
     */
    static String bridge(String from, String to) {
        if (from == null || from.equals(to)) return null;
        if ("pending".equals(from)) return "claimed";
        if ("claimed".equals(from)) return "running";
        return null;
    }

    /** 迁移表上的先后次序；终态之间不分先后，一律算同一档。 */
    static int rank(String state) {
        if ("pending".equals(state)) return 0;
        if ("claimed".equals(state)) return 1;
        if ("running".equals(state)) return 2;
        return 3;
    }

    private static String detail(String state) {
        return "claimed".equals(state) ? "设备已接收二维码任务" : "二维码已显示";
    }

    /** 发到服务端真的迁移过去为止；每轮先重发目标，不成再补中间那一步。重复发同一状态是安全的。 */
    private void deliver(String taskId, JSONObject receipt) throws Exception {
        String target = receipt.getString("state");
        for (int attempt = 0; attempt < 6; attempt++) {
            String server = progress.send(new JSONObject(receipt.toString()));
            // 服务端已经走得更靠前（比如过程态补发时任务早就终结了），不是错，直接认。
            if (target.equals(server) || rank(server) > rank(target)) return;
            String missing = bridge(server, target);
            if (missing == null) throw new IOException("SHARE_LINK_RECEIPT_REFUSED " + server + "->" + target);
            progress.send(receipt(taskId, missing, detail(missing), null));
        }
        throw new IOException("SHARE_LINK_RECEIPT_NOT_SETTLED");
    }

    private void flush(String taskId, JSONObject saved) throws Exception {
        if (saved.optBoolean("acknowledged")) return;
        try { deliver(taskId, saved.getJSONObject("receipt")); }
        catch (Exception unconfirmed) {
            int failures = Math.min(16, saved.optInt("failures") + 1);
            long delay = Math.min(RETRY_MAX_MS, RETRY_BASE_MS << Math.min(4, failures - 1));
            saved.put("failures", failures).put("retry_at", clock.wall() + delay)
                    .put("last_error", unconfirmed.getClass().getSimpleName());
            save(taskId, saved);
            return;
        }
        saved.put("acknowledged", true).put("retry_at", 0);
        save(taskId, saved);
        settledIds.add(taskId);
    }

    /**
     * 退出前必须把在途任务结清。窗口最长显示300秒，核心因升级或重启退出时撞上的概率不低；
     * 不结清的话服务端那条任务就停在 running——下次启动 current 是空的，到期清扫进不来，
     * retry() 又只补发「已有 receipt」的记录，这条会永远补不上。
     * finish() 是先落盘再发，所以此刻发不出去也留在磁盘上，下次启动由 retry() 补发。
     */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (!current.isEmpty()) {
            String taskId = current;
            screen.dismiss();
            try {
                JSONObject saved = load(taskId);
                if (saved != null && !saved.has("receipt"))
                    finish(taskId, saved, "success", "核心退出，二维码已撤下",
                            ShareLinkOutcome.SUPERSEDED, "core_shutdown", shownAtMs, qrMode);
            } catch (Exception unrecorded) { System.err.println("SHARE_LINK_SHUTDOWN_UNRECORDED " + taskId); }
        }
        current = ""; session = "";
    }
}
