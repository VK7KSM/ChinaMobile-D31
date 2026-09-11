package net.elfradio.d31bootstrap;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import org.json.JSONObject;

/** 更新、账号配置和修复共用；跨命令的修复预留不会因进程退出而丢失。 */
final class RemoteMaintenance {
    static final File ROOT = new File(RemoteUpdatePlatform.RUNTIME, "maintenance");
    static final int PROTOCOL = 1;
    static final class Lease implements AutoCloseable {
        final RandomAccessFile file; final FileLock lock;
        Lease(RandomAccessFile file, FileLock lock) { this.file = file; this.lock = lock; }
        public void close() throws IOException { try { lock.release(); } finally { file.close(); } }
    }
    static Lease acquire() throws Exception { return acquire(ROOT); }
    static Lease acquire(File root) throws Exception {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("维护目录不可用");
        File path = new File(root, "lock");
        if (!path.getAbsoluteFile().equals(path.getCanonicalFile())) throw new IOException("维护锁不能为链接");
        RandomAccessFile file = new RandomAccessFile(path, "rw");
        try {
            FileLock lock = RemoteFileLocks.tryExclusive(file.getChannel());
            if (lock != null) return new Lease(file, lock);
        } catch (OverlappingFileLockException busy) {
            // 同进程持锁与另一进程持锁均返回忙，不抢锁。
        } catch (Exception error) { file.close(); throw error; }
        file.close(); return null;
    }
    static boolean reserved() throws Exception {
        return new File(ROOT, "repair.json").exists() || RemoteWindowsMaintenance.reserved();
    }
    static void requireUnreserved() throws Exception {
        if (reserved()) throw new IOException("系统修复尚未完成，保留现有业务并暂缓其它维护");
    }
    static void reserve(File root, String task, String digest) throws Exception {
        File file = new File(root, "repair.json");
        if (file.exists()) {
            JSONObject old = read(file);
            if (!task.equals(old.getString("task_id")) || !digest.equals(old.getString("plan_sha256")))
                throw new IOException("另一系统修复尚未完成");
        } else RescueFiles.write(file, new JSONObject().put("task_id", task).put("plan_sha256", digest).toString());
    }
    static void release(File root, String task, String digest) throws Exception {
        File file = new File(root, "repair.json");
        if (!file.exists()) return;
        JSONObject old = read(file);
        if (!task.equals(old.getString("task_id")) || !digest.equals(old.getString("plan_sha256")))
            throw new IOException("不能释放另一修复的维护预留");
        if (!file.delete()) throw new IOException("维护预留无法释放");
    }
    static void requireRepairReady() throws Exception {
        if (RemoteWindowsMaintenance.reserved()) throw new IOException("Windows刷机交接尚未结束");
        JSONObject health = read(new File(RemoteUpdates.ROOT, "supervisor.json"));
        long age = System.currentTimeMillis() - health.getLong("time_ms");
        if (health.optInt("maintenance_protocol") != PROTOCOL || age < 0 || age >= 20000)
            throw new IOException("需先接替为支持维护互斥的系统监督");
        JSONObject core = read(new File(RemoteUpdatePlatform.CORE, "health.json"));
        JSONObject active = read(RemoteUpdatePlatform.ACTIVE);
        String pid = RescueFiles.read(new File(RemoteUpdatePlatform.CORE, "remote.pid"), 32).trim();
        if (!pid.matches("[1-9][0-9]{0,8}")) throw new IOException("实际核心进程未确认");
        String maps = RescueFiles.read(new File("/proc/" + pid + "/maps"), RemoteRuntimeInventory.MAPS_LIMIT);
        requireCore(core, active, pid, maps, System.currentTimeMillis());
        if (!active.getString("sha256").equals(RescueFiles.sha256(new File(active.getString("path")))))
            throw new IOException("活动核心原件与摘要不符");
        if (RemoteUpdatePlatform.cloudUpdateBusy()) throw new IOException("云更新未结束");
        File pending = new File(RemoteUpdates.ROOT, "manual/pending.json");
        if (pending.exists()) throw new IOException("手动APK交接未结束");
        if (new File(RemoteUpdates.ROOT, "stop-supervisor").exists()) throw new IOException("监督正在交接");
    }
    static void requireCore(JSONObject core, JSONObject active, String pid, String maps, long now) throws Exception {
        long age = now - core.optLong("time_ms");
        String path = active.optString("path"), hash = active.optString("sha256");
        if (!hash.matches("[a-f0-9]{64}")
                || !(path.equals("/data/local/d31-remote/releases/" + hash + "/remote.apk")
                || path.equals(RemoteUpdatePlatform.BASELINE.getPath().replace(File.separatorChar, '/'))))
            throw new IOException("维护核心必须为已冻结完整载荷");
        if (core.optInt("maintenance_protocol") != PROTOCOL || core.optInt("uid", -1) != 0
                || !pid.equals(String.valueOf(core.optInt("pid"))) || !core.optBoolean("local_ready")
                || age < 0 || age >= 20000 || active.optInt("versionCode") < 96
                || core.optInt("version_code") != active.optInt("versionCode")
                || !hash.equals(core.optString("apk_sha256")) || !RemoteRuntimeInventory.mapsArchive(maps, path))
            throw new IOException("实际活动核心尚未支持维护互斥或加载证据不足");
    }
    private static JSONObject read(File file) throws Exception {
        if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) throw new IOException("维护记录不能为链接");
        return new JSONObject(RescueFiles.read(file, 4096));
    }
    private RemoteMaintenance() { }
}
