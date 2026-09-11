package net.elfradio.d31bootstrap;

import java.io.*;
import org.json.JSONObject;

final class RemoteUpdates {
    static final File ROOT = new File(RemoteUpdatePlatform.RUNTIME, "updates");
    static boolean ready() {
        try {
            if (!new File(ROOT, "enabled").isFile() || new File(ROOT, "stop-supervisor").exists()) return false;
            JSONObject health = RemoteUpdateFiles.read(new File(ROOT, "supervisor.json"));
            long age = System.currentTimeMillis() - health.optLong("time_ms");
            int pid = health.optInt("pid", -1);
            return health.optInt("uid", -1) == 0 && age >= 0 && age < 20000 && pid > 0
                    && new File("/proc/" + pid).isDirectory();
        } catch (Exception unavailable) { return false; }
    }

    static void enqueue(JSONObject offer) throws Exception {
        try (RemoteMaintenance.Lease lease = RemoteMaintenance.acquire()) {
            if (lease == null) throw new IOException("维护正在进行，稍后重试更新");
            RemoteMaintenance.requireUnreserved();
            enqueueLocked(offer);
        }
    }

    private static void enqueueLocked(JSONObject offer) throws Exception {
        if (!ready()) throw new IOException("独立更新器未启用");
        String id = offer.optString("task_id");
        if (!id.matches("update-[a-zA-Z0-9-]{1,80}")) throw new IOException("需要v2独立更新任务");
        File jobs = new File(ROOT, "jobs");
        if (!jobs.isDirectory() && !jobs.mkdirs()) throw new IOException("更新任务目录不可用");
        File dir = new File(jobs, RemoteProtocol.hash(id)); File file = new File(dir, "offer.json");
        if (file.isFile()) {
            JSONObject previous = RemoteUpdateFiles.read(file);
            if (!previous.getString("manifest_raw").equals(offer.getString("manifest_raw"))
                    || !previous.getString("signature").equals(offer.getString("signature")))
                throw new IOException("同号更新内容冲突");
            return;
        }
        File[] existing = jobs.listFiles();
        if (existing == null || existing.length >= 128) throw new IOException("更新记录需归档");
        for (File old : existing) {
            File state = new File(old, "state.json");
            if (!state.isFile() || !RemoteUpdateEngine.terminal(RemoteUpdateFiles.read(state).optString("phase")))
                throw new IOException("已有更新任务未结束");
        }
        if (!dir.isDirectory() && !dir.mkdir()) throw new IOException("更新任务目录创建失败");
        RescueFiles.write(file, offer.toString());
    }
}
