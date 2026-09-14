package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android持久状态诊断，所有安装入口禁止调用，回执传输与时间均为本地模拟。 */
public final class RemoteUpdateReceiptCheck {
    private static final class Platform implements RemoteUpdateEngine.Platform {
        boolean offline = true;
        int attempts;
        final List<String> receipts = new ArrayList<>();
        public JSONObject current() { throw new AssertionError("禁止读取安装业务"); }
        public JSONObject prepare(JSONObject manifest, File directory) { throw new AssertionError("禁止下载"); }
        public JSONObject backup(File directory) { throw new AssertionError("禁止备份业务"); }
        public void stopCore() { throw new AssertionError("禁止停止核心"); }
        public void install(JSONObject archive, boolean rollback) { throw new AssertionError("禁止安装"); }
        public void select(JSONObject archive) { throw new AssertionError("禁止切换核心"); }
        public boolean localHealthy(JSONObject archive) { throw new AssertionError("禁止伪造核心健康"); }
        public boolean cloudHealthy(JSONObject archive) { throw new AssertionError("禁止伪造云健康"); }
        public String session() { return "isolated-receipt-check"; }
        public void progress(String job, String state, String detail) throws Exception {
            attempts++;
            if (offline) throw new IOException("模拟离线");
            receipts.add(state);
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || android.system.Os.getuid() != 0
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL))
            throw new IllegalArgumentException("需要D31诊断目录");
        File root = new File(args[0]);
        if (!root.getCanonicalPath().matches("/data/local/d31-remote/receipt-check-[a-z0-9-]+")
                || !root.mkdir()) throw new IllegalArgumentException("需要新的独立目录");
        JSONArray events = new JSONArray();
        for (String phase : new String[]{"claimed", "rejected"})
            events.put(new JSONObject().put("state", phase).put("detail", "隔离诊断"));
        RescueFiles.write(new File(root, "state.json"), new JSONObject().put("phase", "rejected")
                .put("events", events).put("acked", 0).put("job_id", "update-isolated-check").toString());
        Platform platform = new Platform();
        long now = System.currentTimeMillis();
        RemoteUpdateEngine engine = new RemoteUpdateEngine(root, platform, null, "isolated-check");
        engine.step(now);
        RescueFiles.write(new File(root, "first-failure.json"), engine.state().toString());
        for (int i = 0; i < 10; i++) {
            long retry = engine.state().getLong("progress_retry_at");
            if (retry - now > 300000 || retry <= now) throw new AssertionError("重试范围错误");
            int attempts = platform.attempts;
            engine = new RemoteUpdateEngine(root, platform, null, "isolated-check");
            engine.step(retry - 1);
            if (attempts != platform.attempts) throw new AssertionError("重建绕过等待");
            engine.step(retry); now = retry;
            if (attempts + 1 != platform.attempts) throw new AssertionError("重试次数错误");
        }
        RescueFiles.write(new File(root, "capped-failure.json"), engine.state().toString());
        platform.offline = false;
        engine.step(engine.state().getLong("progress_retry_at"));
        if (!platform.receipts.equals(Arrays.asList("claimed", "rejected")))
            throw new AssertionError("回执次序错误");
        int attempts = platform.attempts;
        new RemoteUpdateEngine(root, platform, null, "isolated-check").step(now + 900000);
        if (platform.attempts != attempts || engine.state().getInt("acked") != 2)
            throw new AssertionError("终态回执重放");
        RescueFiles.write(new File(root, "result.json"), new JSONObject().put("version", BuildConfig.VERSION_CODE)
                .put("attempts", attempts).put("receipts", new JSONArray(platform.receipts))
                .put("device_install", false).put("cloud_contact", false).toString());
        System.out.println("PERSISTED_BACKOFF_OK ORDERED_TERMINAL_RECEIPTS_OK NO_DEVICE_INSTALL");
        System.exit(0);
    }
}
