package net.elfradio.d31bootstrap;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.Socket;
import java.net.InetSocketAddress;

final class RescueInstaller {
    static final String ROOT = "/data/local/d31-rescue";
    static final String HOOK = "/system/bin/install-recovery.sh";
    static final String MARKER = "# D31_RESCUE_BEGIN";

    static File directory(Context context) {
        File dir = new File(context.getFilesDir(), "rescue");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    static boolean healthy() {
        try (Socket connection = new Socket()) {
            connection.connect(new InetSocketAddress("127.0.0.1", 8765), 500);
            connection.setSoTimeout(700);
            connection.getOutputStream().write(("GET /health HTTP/1.1\r\nHost: 127.0.0.1:8765\r\n"
                    + "Connection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] b = new byte[512]; int n;
                while ((n = in.read(b)) != -1 && out.size() < 4096) out.write(b, 0, n);
            }
            String replyText = out.toString("UTF-8");
            if (!replyText.startsWith("HTTP/1.1 200 ")) return false;
            JSONObject reply = new JSONObject(replyText.substring(replyText.indexOf("\r\n\r\n") + 4));
            return "d31-root-rescue".equals(reply.optString("service"))
                    && BuildConfig.VERSION_CODE == reply.optInt("version_code")
                    && BuildConfig.VERSION_NAME.equals(reply.optString("version"));
        } catch (Exception ignored) { return false; }
    }

    static synchronized String ensure(Context context) {
        if (healthy()) return "独立命令服务已运行";
        try {
            File dir = directory(context);
            File guard = new File(dir, "enabled");
            String generation = Integer.toString(BuildConfig.VERSION_CODE);
            if (!guard.isFile() || !generation.equals(RescueFiles.read(guard, 128)))
                RescueFiles.write(guard, generation);
            File launcher = new File(dir, "start.sh");
            RescueFiles.write(launcher, "#!/system/bin/sh\n"
                    + "[ -f " + RescueFiles.quote(guard.getPath()) + " ] || exit 0\n"
                    + "export CLASSPATH=" + ROOT + "/daemon.apk\n"
                    + "exec /system/bin/busybox setsid /system/bin/app_process /system/bin "
                    + "--nice-name=d31-rescue net.elfradio.d31bootstrap.RescueDaemon "
                    + ROOT + " " + RescueFiles.quote(guard.getPath()) + "\n");
            File installer = new File(dir, "install.sh");
            String apk = context.getPackageCodePath();
            String script = "#!/system/bin/sh\nset -e\numask 077\n"
                    + "[ \"$(id -u)\" = 0 ]\n"
                    + "mkdir -p " + ROOT + "\nchmod 0700 " + ROOT + "\n"
                    + "cp " + RescueFiles.quote(apk) + " " + ROOT + "/daemon.apk.new\n"
                    + "chmod 0600 " + ROOT + "/daemon.apk.new\n"
                    + "mv " + ROOT + "/daemon.apk.new " + ROOT + "/daemon.apk\n"
                    + "cp " + RescueFiles.quote(launcher.getPath()) + " " + ROOT + "/start.sh.new\n"
                    + "chmod 0700 " + ROOT + "/start.sh.new\n"
                    + "mv " + ROOT + "/start.sh.new " + ROOT + "/start.sh\n";
            script += "sleep 3\n/system/bin/sh " + ROOT + "/start.sh > " + ROOT
                    + "/daemon.log 2>&1 < /dev/null &\n";
            RescueFiles.write(installer, script);
            String result = runInstaller(installer);
            for (int n = 0; n < 10; n++) {
                if (healthy()) return "独立命令服务启动并回读成功\n" + result;
                Thread.sleep(500);
            }
            return "独立命令服务尚未通过回读，保留日志待查\n" + result;
        } catch (Exception error) { return "独立命令服务部署失败：" + error; }
    }

    private static String runInstaller(File installer) throws Exception {
        String helper = new File("/system/bin/snSudoClient.real").canExecute()
                ? "/system/bin/snSudoClient.real" : "/system/bin/snSudoClient";
        Process child = new ProcessBuilder(helper,
                "/system/bin/sh " + RescueFiles.quote(installer.getPath())
                        + " > " + RescueFiles.quote(installer.getPath() + ".log") + " 2>&1")
                .redirectErrorStream(true).start();
        Thread drain = new Thread(() -> {
            try (InputStream input = child.getInputStream()) {
                byte[] buffer = new byte[1024];
                while (input.read(buffer) != -1) { }
            } catch (IOException ignored) { }
        }, "d31-install-output");
        drain.setDaemon(true);
        drain.start();
        long deadline = android.os.SystemClock.elapsedRealtime() + 12000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            try { return "部署客户端退出码：" + child.exitValue() + "；以实际健康回读为准"; }
            catch (IllegalThreadStateException running) { Thread.sleep(100); }
        }
        child.destroy();
        return "部署客户端超时；未据此判断服务端成功或失败";
    }

    static synchronized String enableBoot(Context context) {
        try {
            if (!healthy()) return "先启动并验证8765命令服务，再启用开机入口";
            File original = new File(directory(context), "boot-before.sh");
            File reader = new File(directory(context), "read-boot.sh");
            RescueFiles.write(reader, "#!/system/bin/sh\nset -e\ncp " + HOOK + " "
                    + RescueFiles.quote(original.getPath()) + "\nchmod 0644 "
                    + RescueFiles.quote(original.getPath()) + "\n");
            if (original.exists() && !original.delete()) return "旧启动快照无法清理，停止修改";
            runInstaller(reader);
            String before = RescueFiles.read(original, 100000);
            if (before.contains(MARKER)) return "开机救援入口已存在";
            String expected = RescueFiles.sha256(original);
            File hook = new File(directory(context), "install-recovery.sh.new");
            RescueFiles.write(hook, patchedHook(before));
            String wanted = RescueFiles.sha256(hook);
            File installer = new File(directory(context), "enable-boot.sh");
            String backup = ROOT + "/install-recovery.before-" + System.currentTimeMillis();
            RescueFiles.write(installer, "#!/system/bin/sh\nset -e\n"
                    + "busybox sha256sum " + HOOK + " | busybox grep -q '^" + expected + "  '\n"
                    + "cp -p " + HOOK + " " + backup + "\n"
                    + "mount -o remount,rw /system\n"
                    + "trap 'mount -o remount,ro /system' EXIT\n"
                    + "cp -p " + HOOK + " " + HOOK + ".rescue-new\n"
                    + "cat " + RescueFiles.quote(hook.getPath()) + " > " + HOOK + ".rescue-new\n"
                    + "chcon u:object_r:install_recovery_exec:s0 " + HOOK + ".rescue-new\n"
                    + "/system/bin/sh -n " + HOOK + ".rescue-new\n"
                    + "mv " + HOOK + ".rescue-new " + HOOK + "\nsync\n"
                    + "mount -o remount,ro /system\ntrap - EXIT\n");
            String log = runInstaller(installer);
            if (!original.delete()) return "启动回读快照无法更新，未验收";
            runInstaller(reader);
            return (wanted.equals(RescueFiles.sha256(original))
                    ? "开机救援入口已安装并校验，原文件已备份\n" : "开机入口校验失败，保留现场\n") + log;
        } catch (Exception error) { return "开机救援部署失败：" + error; }
    }

    static String patchedHook(String before) {
        if (before.contains(MARKER)) return before;
        if (!before.startsWith("#!/system/bin/sh\n")) throw new IllegalArgumentException("Unknown boot hook");
        String insert = MARKER + "\n"
                + "if [ -r " + ROOT + "/start.sh ]; then\n"
                + "  /system/bin/sh " + ROOT + "/start.sh > " + ROOT + "/daemon.log 2>&1 < /dev/null &\n"
                + "fi\n# D31_RESCUE_END\n";
        return "#!/system/bin/sh\n" + insert + before.substring("#!/system/bin/sh\n".length());
    }
}
