package net.elfradio.d31bootstrap;

import java.io.*;
import java.nio.channels.FileLock;
import java.security.PublicKey;
import java.util.concurrent.*;
import org.json.JSONObject;

/** 固定系统基线里的独立监督进程，不随应用覆盖安装退出。 */
public final class RemoteSupervisor {
    public static void main(String[] args) throws Exception {
        if (android.system.Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL)) throw new SecurityException("目标非已验证D31");
        if (args.length == 2 && args[0].equals("inspect")) {
            RemoteUpdatePolicy.trustedKey();
            System.out.println(new RemoteUpdatePlatform().inspect(new File(args[1]))); System.exit(0); return;
        }
        if (args.length != 0) throw new IllegalArgumentException("运行参数无效");
        File root = RemoteUpdates.ROOT;
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("更新器目录不可用");
        android.system.Os.chmod(root.getPath(), 0700);
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(root, "supervisor.lock"), "rw");
             FileLock lock = RemoteFileLocks.tryExclusive(lockFile.getChannel())) {
            if (lock == null) return;
            RemoteUpdatePlatform platform = new RemoteUpdatePlatform();
            RemoteManualUpdate manual = platform.manualUpdater();
            PublicKey key = RemoteUpdatePolicy.trustedKey();
            ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
            heartbeat.scheduleWithFixedDelay(() -> {
                try { RescueFiles.write(new File(root, "supervisor.json"), new JSONObject().put("uid", 0)
                        .put("pid", android.os.Process.myPid()).put("time_ms", System.currentTimeMillis())
                        .put("version_code", BuildConfig.VERSION_CODE).toString()); }
                catch (Exception error) { System.err.println("监督心跳写入失败"); }
            }, 0, 5, TimeUnit.SECONDS);
            try {
                while (!new File(root, "stop-supervisor").exists()) {
                    try {
                        if (!platform.packagesReady()) { Thread.sleep(1000); continue; }
                        if(manual.tick(System.currentTimeMillis())) { Thread.sleep(1000); continue; }
                        platform.ensureCore(android.os.SystemClock.elapsedRealtime());
                        File jobs = new File(root, "jobs"); File[] entries = jobs.listFiles();
                        if (entries != null) for (File dir : entries) {
                            if (!dir.getName().matches("[a-f0-9]{64}") || !new File(dir, "offer.json").isFile()) continue;
                            JSONObject identity = RemoteUpdateFiles.read(new File(RemoteUpdatePlatform.CORE, "identity.json"));
                            RemoteUpdateEngine engine = new RemoteUpdateEngine(dir, platform, key, identity.getString("device_id"));
                            engine.step(System.currentTimeMillis());
                            if (!RemoteUpdateEngine.terminal(engine.state().getString("phase"))) break;
                        }
                    } catch (Exception failure) {
                        StringBuilder stack = new StringBuilder();
                        for (StackTraceElement frame : failure.getStackTrace()) {
                            if (stack.length() >= 4000) break;
                            stack.append(frame.toString()).append('\n');
                        }
                        RescueFiles.write(new File(root, "last-error.json"), new JSONObject().put("time_ms", System.currentTimeMillis())
                                .put("error", failure.getClass().getSimpleName()).put("stack", stack.toString()).toString());
                        Thread.sleep(5000);
                    }
                    Thread.sleep(2000);
                }
            } finally { heartbeat.shutdownNow(); platform.stopCore(); }
        }
        System.exit(0);
    }
}
