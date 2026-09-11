package net.elfradio.d31bootstrap;

import java.io.File;
import org.json.JSONObject;

/** 手动安装只以本地健康完成交接；不创建云更新任务或等待联网。 */
final class RemoteManualUpdate {
    interface Platform {
        JSONObject installed() throws Exception;
        JSONObject active() throws Exception;
        JSONObject preserve(JSONObject apk) throws Exception;
        boolean cloudBusy() throws Exception;
        void stop() throws Exception;
        void select(JSONObject apk) throws Exception;
        boolean healthy(JSONObject apk) throws Exception;
        void restore(JSONObject apk) throws Exception;
        String session();
    }
    private final File directory;
    private final Platform platform;
    RemoteManualUpdate(File directory, Platform platform) throws Exception {
        this.directory = directory; this.platform = platform;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("本地更新记录不可用");
    }

    boolean tick(long now) throws Exception {
        JSONObject installed = platform.installed();
        String hash = installed.getString("sha256");
        if (!hash.matches("[a-f0-9]{64}")) throw new java.io.IOException("安装摘要无效");
        File file = new File(directory, hash + ".json");
        JSONObject s = file.isFile() ? read(file) : null;
        // 已开始的恢复不受包管理器当前版本变化影响。
        File pending = new File(directory, "pending.json");
        if (pending.isFile()) {
            String name=read(pending).getString("file");
            if(!name.matches("[a-f0-9]{64}\\.json"))throw new java.io.IOException("本地更新记录名无效");
            file = new File(directory,name); s = read(file);
        }
        if (s == null) {
            JSONObject active = platform.active();
            if (!installed.optBoolean("remote_full") || platform.cloudBusy() || installed.getLong("versionCode") <= active.getLong("versionCode")) return false;
            s = new JSONObject().put("phase", "prepared").put("target", platform.preserve(installed))
                    .put("backup", platform.preserve(active)).put("started_at", now);
            save(file, s);
            save(pending, new JSONObject().put("file", file.getName()));
        }
        String phase = s.getString("phase");
        if (terminal(phase)) { if (pending.isFile() && !pending.delete()) throw new java.io.IOException("本地更新标记未清除"); return false; }
        if (phase.equals("prepared")) {
            if (platform.cloudBusy()) return false;
            if (!matches(platform.installed(), s.getJSONObject("target"))) {
                s.put("phase", "superseded"); save(file,s); return true;
            }
            platform.stop();
            // 旧核心可能在退出前最后领取了一项云更新，此时交还监督器处理。
            if (platform.cloudBusy()) return false;
            s.put("phase", "selecting"); save(file, s);
            return true;
        }
        if (phase.equals("selecting")) {
            try {
                platform.select(s.getJSONObject("target"));
                s.put("phase", "health").put("health_at", now).put("session", platform.session());
            } catch (Exception failed) { s.put("phase", "rollback").put("reason", failed.getClass().getSimpleName()); }
            save(file, s); return true;
        }
        if (phase.equals("health")) {
            try{platform.select(s.getJSONObject("target"));}
            catch(Exception failed){s.put("phase","rollback").put("reason",failed.getClass().getSimpleName());save(file,s);return true;}
            if (!platform.session().equals(s.optString("session")) || now < s.optLong("health_at")) {
                s.put("session", platform.session()).put("health_at", now); save(file,s);
            }
            if (!matches(platform.installed(),s.getJSONObject("target"))) {
                // 不覆盖用户随后安装的第三个版本，保留本次记录后重新评估。
                s.put("phase", "superseded");
            } else if (platform.healthy(s.getJSONObject("target"))) {
                s.put("phase", "success").put("local_health",true).put("cloud_required",false).put("finished_at",now);
            } else if (now - s.getLong("health_at") >= 150000) s.put("phase", "rollback");
            save(file,s); return true;
        }
        if (phase.equals("rollback")) {
            JSONObject backup = s.getJSONObject("backup");
            if (!matches(platform.installed(), backup)) {
                if (!matches(platform.installed(),s.getJSONObject("target"))) {
                    s.put("phase","superseded"); save(file,s); return true;
                }
                if (s.optBoolean("restore_intent")) return true;
                platform.stop(); s.put("restore_intent",true); save(file,s);
                platform.restore(backup);
            }
            platform.select(backup);
            if(platform.healthy(backup)){s.put("phase","recovered").put("finished_at",now);save(file,s);}
            return true;
        }
        throw new java.io.IOException("未知本地更新阶段");
    }
    static boolean terminal(String phase) { return phase.equals("success") || phase.equals("recovered") || phase.equals("superseded"); }
    private static boolean matches(JSONObject a, JSONObject b) throws Exception { return a.getString("sha256").equals(b.getString("sha256")); }
    private static JSONObject read(File file) throws Exception { return new JSONObject(RescueFiles.read(file,64000)); }
    private static void save(File file,JSONObject value) throws Exception { RescueFiles.write(file,value.toString()); }
}
