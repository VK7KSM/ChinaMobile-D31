package net.elfradio.d31bootstrap;

import java.io.*;
import java.security.PublicKey;
import org.json.*;

/** 每一步先持久化意图；重启后核对真实版本，不把安装返回值当作健康。 */
final class RemoteUpdateEngine {
    interface Platform {
        JSONObject current() throws Exception;
        JSONObject prepare(JSONObject manifest, File directory) throws Exception;
        JSONObject backup(File directory) throws Exception;
        void stopCore() throws Exception;
        void install(JSONObject archive, boolean rollback) throws Exception;
        void select(JSONObject archive) throws Exception;
        boolean localHealthy(JSONObject archive) throws Exception;
        boolean cloudHealthy(JSONObject archive) throws Exception;
        String session();
        void progress(String job, String state, String detail) throws Exception;
    }
    private final File directory, stateFile;
    private final Platform platform;
    private final PublicKey key;
    private final String device;

    RemoteUpdateEngine(File directory, Platform platform, PublicKey key, String device) throws Exception {
        this.directory = directory; this.platform = platform; this.key = key; this.device = device;
        stateFile = new File(directory, "state.json");
    }

    JSONObject state() throws Exception {
        return stateFile.exists() ? new JSONObject(RescueFiles.read(stateFile, 64000))
                : new JSONObject().put("phase", "pending").put("events", new JSONArray()).put("acked", 0);
    }

    void step(long now) throws Exception {
        JSONObject s = state();
        flush(s, now); // 终态也继续补传；云断线不能中断已经开始的本地恢复。
        String phase = s.getString("phase");
        if (terminal(phase)) return;
        JSONObject offer = new JSONObject(RescueFiles.read(new File(directory, "offer.json"), 24000));
        JSONObject m;
        try { m = RemoteUpdatePolicy.validate(offer, key, device, now,
                phase.equals("installing") || phase.equals("wait_health") || phase.equals("rollback")); }
        catch (Exception invalid) {
            if (phase.equals("installing") || phase.equals("wait_health") || phase.equals("rollback")) throw invalid;
            if (!s.has("job_id")) s.put("job_id", offer.optString("task_id", ""));
            if (phase.equals("pending")) transition(s, "claimed", "设备已接收更新", now);
            transition(s, "rejected", "清单校验失败：" + invalid.getMessage(), now); return;
        }
        s.put("job_id", m.getString("task_id"));
        if (phase.equals("pending")) { transition(s, "claimed", "设备已接收更新", now); return; }
        if (phase.equals("claimed")) { transition(s, "downloading", "下载制品", now); return; }
        if (phase.equals("downloading")) {
            if (now < s.optLong("retry_after")) return;
            try {
                JSONObject target = platform.prepare(m, directory);
                if (!RemoteUpdatePolicy.matches(m, target)) throw new SecurityException("实际APK与清单不符");
                JSONObject old = platform.backup(directory);
                if (target.getInt("versionCode") <= old.getInt("versionCode")) throw new SecurityException("不接受降级或同版本覆盖");
                s.put("target", target).put("backup", old);
                transition(s, "verifying", "制品和原版本备份已校验", now);
            } catch (Exception error) {
                int retries = s.optInt("download_attempts") + 1; s.put("download_attempts", retries);
                if (retries >= 3 || error instanceof SecurityException) transition(s, "rejected", "制品准备失败", now);
                else { s.put("retry_after", now + 30000); save(s); }
            }
            return;
        }
        if (phase.equals("verifying")) {
            // 先把安装意图和真实备份落盘，再停止远程核心和调用安装器。
            transition(s, "installing", "准备安装", now); return;
        }
        if (phase.equals("installing")) {
            JSONObject target = s.getJSONObject("target");
            if (!s.optBoolean("install_intent")) {
                platform.stopCore(); s.put("install_intent", true); save(s);
                try { platform.install(target, false); }
                catch (Exception failure) { transition(s, "rollback", "安装未确认，恢复旧版本", now); return; }
            }
            if (!installedMatches(target)) {
                transition(s, "rollback", "实际安装版本不符", now); return;
            }
            platform.select(target);
            transition(s, "wait_health", "等待新核心与云连接核验", now); return;
        }
        if (phase.equals("wait_health")) {
            if (!platform.session().equals(s.optString("health_session"))) {
                s.put("health_session", platform.session()).put("phase_at", now); save(s);
            }
            JSONObject target = s.getJSONObject("target");
            if (platform.localHealthy(target)) {
                if (platform.cloudHealthy(target)) {
                    if (installedMatches(target)) transition(s, "success", "health-ok", now);
                    else transition(s, "rollback", "最终安装版本发生变化", now);
                }
                // 本地健康且离线时继续等回报，不把云端暂时不可达当作安装损坏。
            } else if (now - s.getLong("phase_at") >= 150000) {
                transition(s, "rollback", "新核心健康超时", now);
            }
            return;
        }
        if (phase.equals("rollback")) {
            JSONObject backup = s.getJSONObject("backup");
            if (!installedMatches(backup)) {
                if (s.optBoolean("rollback_intent")) { s.put("attention", "恢复安装未确认，保留原件等待处理"); save(s); return; }
                platform.stopCore(); s.put("rollback_intent", true); save(s);
                platform.install(backup, true);
                if (!RemoteUpdatePolicy.matches(backup, platform.current())) throw new IOException("恢复版本尚未确认");
            }
            platform.select(backup);
            if (platform.localHealthy(backup)) transition(s, "recovered", "last-good", now);
        }
    }

    private void transition(JSONObject s, String phase, String detail, long now) throws Exception {
        s.put("phase", phase).put("phase_at", now).put("detail", detail);
        if (phase.equals("wait_health")) s.put("health_session", platform.session());
        s.getJSONArray("events").put(new JSONObject().put("state", phase).put("detail", detail));
        save(s); flush(s, now);
    }
    private void flush(JSONObject s, long now) throws Exception {
        if (now < s.optLong("progress_failed_at")) {
            // 校时倒退时重新建立有限等待，不能无限等待旧的墙钟时间。
            s.put("progress_failed_at", now).put("progress_retry_at", now + progressDelay(s));
            save(s);
        }
        if (now < s.optLong("progress_retry_at")) return;
        JSONArray events = s.getJSONArray("events");
        while (s.optInt("acked") < events.length()) {
            int i = s.optInt("acked"); JSONObject event = events.getJSONObject(i);
            try { platform.progress(s.optString("job_id"), event.getString("state"), event.getString("detail")); }
            catch (Exception unavailable) {
                s.put("progress_failures", Math.min(16, s.optInt("progress_failures") + 1))
                        .put("progress_failed_at", now)
                        .put("progress_http", unavailable instanceof RemoteHttp.Rejected
                                ? ((RemoteHttp.Rejected) unavailable).status : 0)
                        .put("progress_server_delay", unavailable instanceof RemoteHttp.Rejected
                                ? ((RemoteHttp.Rejected) unavailable).retryAfterMillis : 0)
                        .put("progress_error", unavailable.getClass().getSimpleName());
                s.put("progress_retry_at", now + progressDelay(s));
                save(s); return;
            }
            s.put("acked", i + 1).put("progress_failures", 0).put("progress_failed_at", 0)
                    .put("progress_retry_at", 0).put("progress_http", 0).put("progress_server_delay", 0)
                    .put("progress_error", "");
            save(s);
        }
    }
    private static long progressDelay(JSONObject state) {
        int http = state.optInt("progress_http");
        long delay = http >= 400 && http < 500 && http != 408 && http != 429 ? 300000
                : Math.min(300000L, 30000L << Math.max(0, Math.min(4, state.optInt("progress_failures") - 1)));
        return Math.max(delay, Math.min(86400000L, state.optLong("progress_server_delay")));
    }
    private void save(JSONObject state) throws Exception { RescueFiles.write(stateFile, state.toString()); }
    private boolean installedMatches(JSONObject archive) {
        try { return RemoteUpdatePolicy.matches(archive, platform.current()); }
        catch (Exception unavailable) { return false; }
    }
    static boolean terminal(String phase) { return phase.equals("success") || phase.equals("recovered") || phase.equals("rejected"); }
}
