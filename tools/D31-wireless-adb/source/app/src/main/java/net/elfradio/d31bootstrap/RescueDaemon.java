package net.elfradio.d31bootstrap;

import android.os.SystemClock;
import android.system.Os;
import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;

public final class RescueDaemon {
    private static final int OUTPUT_LIMIT = 65536;
    private static volatile int activeGroup;

    public static void main(String[] args) throws Exception {
        if (Os.getuid() != 0 || args.length != 2) return;
        File root = new File(args[0]);
        File guard = new File(args[1]);
        if (!guard.isFile()) return;
        String generation = RescueFiles.read(guard, 128);
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(root, "daemon.lock"), "rw");
             FileLock lock = lockFile.getChannel().tryLock()) {
            if (lock == null) return;
            RescueJobs jobs = new RescueJobs(new File(root, "jobs"), RescueDaemon::execute);
            RescueHttpServer server = new RescueHttpServer(8765, jobs, () -> status(guard));
            server.start(3000, true);
            try {
                while (guard.isFile() && generation.equals(RescueFiles.read(guard, 128)))
                    Thread.sleep(2000);
            } finally {
                killGroup(activeGroup);
                server.stop();
            }
        }
        System.exit(0);
    }

    static JSONObject execute(File folder, String command, int timeout) throws Exception {
        File script = new File(folder, "command.sh");
        File pidFile = new File(folder, "group.pid");
        RescueFiles.write(script, command + "\n");
        Process process = new ProcessBuilder("/system/bin/busybox", "setsid", "/system/bin/sh", "-c",
                "echo $$ > \"$1\"; exec /system/bin/sh \"$2\"", "rescue",
                pidFile.getPath(), script.getPath()).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean[] truncated = {false};
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) != -1) {
                    synchronized (output) {
                        int keep = Math.min(n, OUTPUT_LIMIT - output.size());
                        output.write(buf, 0, keep);
                        if (keep < n) truncated[0] = true;
                    }
                }
            } catch (IOException ignored) { }
        }, "d31-rescue-output");
        reader.setDaemon(true); reader.start();
        long start = SystemClock.elapsedRealtime();
        Integer exit = null;
        boolean timedOut = false;
        try {
            while (exit == null) {
                if (activeGroup == 0 && pidFile.isFile()) {
                    try { activeGroup = Integer.parseInt(RescueFiles.read(pidFile, 32).trim()); }
                    catch (NumberFormatException ignored) { }
                }
                if (activeGroup > 1) {
                    try {
                        Integer status = waitChild(activeGroup);
                        if (status != null) {
                            exit = android.system.OsConstants.WIFEXITED(status)
                                    ? android.system.OsConstants.WEXITSTATUS(status)
                                    : 128 + android.system.OsConstants.WTERMSIG(status);
                        }
                    } catch (Exception reaped) {
                        try { exit = process.exitValue(); }
                        catch (IllegalThreadStateException running) { }
                    }
                }
                if (exit != null) break;
                if (SystemClock.elapsedRealtime() - start >= timeout * 1000L) { timedOut = true; break; }
                Thread.sleep(50);
            }
        } finally {
            if (activeGroup == 0 && pidFile.isFile()) {
                try { activeGroup = Integer.parseInt(RescueFiles.read(pidFile, 32).trim()); }
                catch (Exception ignored) { }
            }
            // 清理同进程组子进程；不会撤销已经完成的写入。
            killGroup(activeGroup); activeGroup = 0;
            if (exit == null) process.destroy();
            if (timedOut && pidFile.isFile()) {
                try {
                    int childPid = Integer.parseInt(RescueFiles.read(pidFile, 32).trim());
                    for (int n = 0; n < 20; n++) {
                        if (waitChild(childPid) != null) break;
                        Thread.sleep(25);
                    }
                } catch (Exception ignored) { }
            }
            reader.join(1500);
            if (reader.isAlive()) process.getInputStream().close();
        }
        String text;
        synchronized (output) { text = new String(output.toByteArray(), StandardCharsets.UTF_8); }
        RescueFiles.write(new File(folder, "output.txt"), text);
        return new JSONObject().put("state", timedOut ? "timed_out" : "completed")
                .put("exit_code", exit == null ? JSONObject.NULL : exit)
                .put("output", text).put("truncated", truncated[0])
                .put("elapsed_ms", SystemClock.elapsedRealtime() - start);
    }

    private static void killGroup(int pid) {
        if (pid > 1) try { Os.kill(-pid, 9); } catch (Exception ignored) { }
    }

    private static Integer waitChild(int pid) throws Exception {
        // Android 6将waitpid隐藏于SDK之外，独立app_process需显式回收子进程。
        Class<?> holder = Class.forName("android.util.MutableInt");
        Object status = holder.getConstructor(int.class).newInstance(0);
        int result = (Integer) Os.class.getMethod("waitpid", int.class, holder, int.class)
                .invoke(null, pid, status, android.system.OsConstants.WNOHANG);
        return result > 0 ? holder.getField("value").getInt(status) : null;
    }

    private static String status(File guard) {
        String report = "D31 独立root救援 " + BuildConfig.VERSION_NAME
                + "\n端口：8765；开发模式：局域网免鉴权\n运行毫秒：" + SystemClock.elapsedRealtime()
                + "\n命令入口：POST /exec；结果入口：GET /jobs/任务号\n";
        File cached = new File(guard.getParentFile(), "status.txt");
        try { return report + RescueFiles.read(cached, 300000); }
        catch (Exception ignored) { return report + "普通探针尚未更新缓存，命令服务可独立使用。\n"; }
    }
}
