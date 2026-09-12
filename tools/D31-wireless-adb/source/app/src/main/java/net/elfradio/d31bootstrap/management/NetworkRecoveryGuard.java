package net.elfradio.d31bootstrap.management;

import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.RemoteRuntimeInventory;

/** 每事务setsid独立进程；启动前像已落盘，心跳发布不取得事务日志锁。 */
public final class NetworkRecoveryGuard {
    static final String ENTRY = "net.elfradio.d31bootstrap.RemoteNetworkAccess";

    /** 网络读取有独立超时；等待期间仍持续证明守护存活，不依赖调用线程推进。 */
    static final class Heartbeat implements AutoCloseable {
        interface Writer { void write() throws Exception; }
        private final ScheduledExecutorService timer;
        private final AtomicReference<Exception> failure = new AtomicReference<>();
        Heartbeat(Writer writer) throws Exception {
            writer.write();
            timer = Executors.newSingleThreadScheduledExecutor();
            timer.scheduleWithFixedDelay(() -> {
                try { writer.write(); } catch (Exception error) { failure.compareAndSet(null, error); }
            }, 250, 250, TimeUnit.MILLISECONDS);
        }
        void check() throws Exception { if (failure.get() != null) throw failure.get(); }
        public void close() throws InterruptedException {
            timer.shutdownNow();
            if (!timer.awaitTermination(2000, TimeUnit.MILLISECONDS))
                System.err.println("网络守护心跳结束待进程退出确认");
        }
    }

    static String startTime(String stat) throws IOException {
        int end = stat.lastIndexOf(')');
        if (end < 0) throw new IOException("NETWORK_PROCESS_STAT_INVALID");
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        if (fields.length <= 19 || !fields[19].matches("[0-9]+")) throw new IOException("NETWORK_PROCESS_START_UNKNOWN");
        return fields[19];
    }
    static boolean fresh(JSONObject beat, String task, String nonce, String boot, String hash, long deadline, long now) throws Exception {
        return task.equals(beat.getString("task_id")) && nonce.equals(beat.getString("instance_id"))
                && boot.equals(beat.getString("boot_id")) && hash.equals(beat.getString("apk_sha256"))
                && deadline == beat.getLong("deadline_elapsed") && beat.getInt("uid") == 0 && beat.getInt("pid") > 0
                && beat.getLong("elapsed") <= now && now - beat.getLong("elapsed") <= 1000
                && beat.getLong("elapsed") >= 0 && beat.getString("start_time").matches("[0-9]+");
    }
    static void launchAndConfirm(NetworkAndroidPlatform platform, String task, String boot, long deadline) throws Exception {
        launchAndConfirm(platform, task, boot, deadline, false);
    }
    static void ensure(NetworkAndroidPlatform platform, NetworkRecoveryDispatch.Binding original) throws Exception {
        if (!platform.hash.equals(original.apkSha256)) throw new IOException("NETWORK_APK_HASH_MISMATCH");
        launchAndConfirm(platform, original.taskId, original.bootId, original.deadlineElapsed, true);
    }
    static void requireOriginal(JSONObject job, String task, String armedBoot, long deadline) throws Exception {
        if (!task.equals(job.getString("task_id")) || !armedBoot.equals(job.getString("boot_id"))
                || deadline != job.getLong("deadline_elapsed")) throw new IOException("NETWORK_GUARD_BINDING_MISMATCH");
    }
    static boolean priorAttention(JSONObject result, String task, String hash, String armedBoot, String currentBoot, long deadline) {
        return task.equals(result.optString("guard_task")) && hash.equals(result.optString("guard_apk_sha256"))
                && armedBoot.equals(result.optString("guard_armed_boot")) && currentBoot.equals(result.optString("guard_boot"))
                && deadline == result.optLong("guard_deadline", -1)
                && ("NEEDS_ATTENTION".equals(result.optString("state")) || "UNKNOWN".equals(result.optString("state")));
    }
    private static boolean verifiedOwner(JSONObject beat, String task, String instance, String currentBoot,
                                         String armedBoot, NetworkAndroidPlatform platform, long deadline) throws Exception {
        if (!armedBoot.equals(beat.optString("armed_boot_id"))
                || !fresh(beat, task, instance, currentBoot, platform.hash, deadline, SystemClock.elapsedRealtime())) return false;
        String pid = Integer.toString(beat.getInt("pid"));
        String stat = NetworkAndroidFiles.proc("/proc/" + pid + "/stat", 4096);
        String cmd = NetworkAndroidFiles.proc("/proc/" + pid + "/cmdline", 2048);
        String maps = NetworkAndroidFiles.proc("/proc/" + pid + "/maps", RemoteRuntimeInventory.MAPS_LIMIT);
        return beat.getString("start_time").equals(startTime(stat)) && cmd.contains("d31-network-guard")
                && android.system.Os.stat("/proc/" + pid).st_uid == 0
                && RemoteRuntimeInventory.mapsArchive(maps, platform.apk.getPath())
                && startTime(stat).equals(startTime(NetworkAndroidFiles.proc("/proc/" + pid + "/stat", 4096)));
    }
    private static void launchAndConfirm(NetworkAndroidPlatform platform, String task, String boot, long deadline,
                                         boolean reuse) throws Exception {
        if (boot == null || !boot.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}") || deadline < 0)
            throw new IOException("NETWORK_GUARD_BINDING_MISMATCH");
        String instance = UUID.randomUUID().toString().replace("-", "");
        File folder = NetworkAndroidFiles.task(task);
        String currentBoot = NetworkAndroidFiles.boot();
        if (reuse) {
            File priorResult = new File(folder, "guard-result.json");
            if (NetworkAndroidFiles.exists(priorResult) && priorAttention(NetworkAndroidFiles.read(priorResult),
                    task, platform.hash, boot, currentBoot, deadline))
                throw new IOException("NETWORK_RECOVERY_ATTENTION_REQUIRED");
            try {
                JSONObject beat = NetworkAndroidFiles.read(new File(folder, "heartbeat.json"));
                String priorInstance = beat.getString("instance_id");
                if (priorInstance.matches("[a-f0-9]{32}")
                        && verifiedOwner(beat, task, priorInstance, currentBoot, boot, platform, deadline)) return;
            } catch (Exception unavailable) { /* 无有效活守护证据，尝试新进程；guard.lock仍阻挡双执行者。 */ }
        }
        String command = "exec /system/bin/busybox setsid /system/bin/app_process /system/bin --nice-name=d31-network-guard "
                + ENTRY + " guard " + task + " " + platform.hash + " " + instance + " " + boot + " " + deadline
                + " </dev/null >/dev/null 2>&1";
        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true);
        builder.environment().put("CLASSPATH", platform.apk.getPath());
        Process child = builder.start();
        try {
            long until = SystemClock.elapsedRealtime() + 3000;
            while (SystemClock.elapsedRealtime() < until) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("NETWORK_GUARD_CANCELLED");
                try {
                    JSONObject beat = NetworkAndroidFiles.read(new File(folder, "heartbeat.json"));
                    if (verifiedOwner(beat, task, instance, currentBoot, boot, platform, deadline)) return;
                } catch (Exception unavailable) { /* 初始化期间无完整心跳，保持有界等待。 */ }
                try { child.exitValue(); throw new IOException("NETWORK_GUARD_EXITED"); }
                catch (IllegalThreadStateException running) { }
                Thread.sleep(50);
            }
            throw new IOException("NETWORK_GUARD_HANDSHAKE_TIMEOUT");
        } finally {
            // 不销毁已独立的守护；其按持久终态或自身预算退出。
            child.getInputStream().close(); child.getErrorStream().close(); child.getOutputStream().close();
        }
    }

    /** 根RemoteNetworkAccess.main委托此方法；本方法不是Android服务，不需要Manifest组件。 */
    public static JSONObject run(String[] args, NetworkAndroidPlatform.Maintenance maintenance) throws Exception {
        if (args.length != 6 || !args[0].equals("guard")) throw new IOException("NETWORK_GUARD_ARGS");
        final String task = args[1], hash = args[2], instance = args[3], armedBoot = args[4];
        NetworkChangeTransaction.token(task);
        if (!instance.matches("[a-f0-9]{32}") || !armedBoot.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                || !args[5].matches("[0-9]{1,18}")) throw new IOException("NETWORK_GUARD_IDENTITY_INVALID");
        final long deadline = Long.parseLong(args[5]);
        final NetworkAndroidPlatform platform = new NetworkAndroidPlatform(hash, maintenance);
        final File folder = NetworkAndroidFiles.task(task);
        File ownerFile = new File(folder, "guard.lock");
        if (!ownerFile.exists()) NetworkAndroidFiles.create(ownerFile, new byte[0]);
        NetworkAndroidFiles.regular(ownerFile);
        try (RandomAccessFile owner = new RandomAccessFile(ownerFile, "rw")) {
            NetworkAndroidFiles.same(NetworkAndroidFiles.regular(ownerFile), android.system.Os.fstat(owner.getFD()));
            try (FileLock lock = owner.getChannel().tryLock()) {
                if (lock == null) throw new IOException("NETWORK_GUARD_ALREADY_OWNED");
                final int pid = android.os.Process.myPid();
                final String processStart = startTime(NetworkAndroidFiles.proc("/proc/self/stat", 4096));
                try (Heartbeat heartbeat = new Heartbeat(() ->
                        NetworkAndroidFiles.write(new File(folder, "heartbeat.json"), new JSONObject().put("task_id", task)
                                .put("instance_id", instance).put("boot_id", NetworkAndroidFiles.boot()).put("apk_sha256", hash)
                                .put("armed_boot_id", armedBoot)
                                .put("deadline_elapsed", deadline).put("elapsed", SystemClock.elapsedRealtime()).put("uid", 0)
                                .put("pid", pid).put("start_time", processStart)))) {
                JSONObject result = NetworkRecoveryLoop.run(new NetworkRecoveryLoop.Host() {
                    public long elapsed() { return SystemClock.elapsedRealtime(); }
                    public String boot() throws Exception { return NetworkAndroidFiles.boot(); }
                    public void heartbeat() throws Exception { heartbeat.check(); }
                    public JSONObject query() throws Exception { return platform.guardQuery(task, armedBoot, deadline); }
                    public boolean recoveryReady(long remainingMs) throws Exception { return platform.recoveryServicesReady(task, remainingMs); }
                    public JSONObject recover() throws Exception {
                        try { return platform.recover(task); }
                        catch (NetworkChangeTransaction.Busy busy) { return new JSONObject().put("state", "UNKNOWN").put("reason", "STORE_BUSY"); }
                    }
                    public void settled(JSONObject state) throws Exception { platform.complete(task, state); }
                    public void pause() throws Exception { Thread.sleep(250); }
                    public void pause(long maximumMs) throws Exception { Thread.sleep(Math.min(250, maximumMs)); }
                }, armedBoot, deadline);
                result.put("guard_task", task).put("guard_apk_sha256", hash).put("guard_armed_boot", armedBoot)
                        .put("guard_boot", NetworkAndroidFiles.boot()).put("guard_deadline", deadline);
                NetworkAndroidFiles.write(new File(folder, "guard-result.json"), result);
                return result;
                }
            }
        }
    }
    private NetworkRecoveryGuard() { }
}
