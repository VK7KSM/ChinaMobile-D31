package net.elfradio.d31bootstrap;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

final class AdbControl {
    static final int PORT = 5555;
    private static final String NEXUI_PACKAGE = "com.starnet.nexui";
    private static final int NEXUI_UID_FALLBACK = 10067;

    static final class ActionResult {
        final String log;
        final boolean succeeded;

        ActionResult(String log, boolean succeeded) {
            this.log = log;
            this.succeeded = succeeded;
        }
    }

    private static final String ROOT_HELPER = "/system/bin/snSudoClient";
    private static final String ORIGINAL_ROOT_HELPER = "/system/bin/snSudoClient.real";
    private static final long ROOT_FAILURE_COOLDOWN_MILLIS = 120_000L;
    private static long rootBlockedUntil;
    private static String securityCommand(Context context) {
        int adminUid = context.getApplicationInfo().uid;
        int nexuiUid = packageUid(context, NEXUI_PACKAGE, NEXUI_UID_FALLBACK);
        return "while iptables -D INPUT -j D31_GUARD 2>/dev/null; do :; done; "
            + "iptables -F D31_GUARD 2>/dev/null || true; "
            + "iptables -X D31_GUARD 2>/dev/null || true; "
            + "while ip6tables -D INPUT -j D31_GUARD6 2>/dev/null; do :; done; "
            + "ip6tables -F D31_GUARD6 2>/dev/null || true; "
            + "ip6tables -X D31_GUARD6 2>/dev/null || true; "
            + "chown " + nexuiUid + ":" + adminUid + " /data/.snSudoSocket; "
            + "chmod 660 /data/.snSudoSocket";
    }

    private static String hardRestartCommand(Context context) {
        return "setprop persist.service.adb.enable 1; "
            + "setprop persist.adb.tcp.port 5555; "
            + "setprop service.adb.tcp.port 5555; "
            + "settings put global adb_enabled 1; "
            + "setprop persist.sys.usb.config mass_storage,adb; "
            + "setprop sys.usb.config none; sleep 2; "
            + "setprop sys.usb.config mass_storage,adb; "
            + securityCommand(context);
    }

    private AdbControl() {
    }

    static ActionResult ensureEnabled(Context context) {
        if (isHealthy(context)) {
            StringBuilder log = new StringBuilder();
            log.append("无线 ADB 状态正常，本次只读复核，不调用厂商root通道。\n");
            appendStatus(log, context);
            return new ActionResult(log.toString(), isHealthy(context));
        }
        return restartEnabled(context);
    }

    static ActionResult restartEnabled(Context context) {
        StringBuilder log = new StringBuilder();
        run(log, "当前身份", "id");
        int exit = runRoot(log, "通过厂商root通道切换MTK USB状态",
                hardRestartCommand(context));
        long deadline = System.currentTimeMillis() + 25000L;
        while (System.currentTimeMillis() < deadline && !isHealthy(context)) {
            sleep(1000);
        }
        appendStatus(log, context);
        boolean succeeded = exit == 0 && isHealthy(context);
        log.append("\n== 执行结论 ==\n")
                .append(succeeded
                        ? "控制命令成功，设备本地检查通过；仍需电脑完成ADB协议握手\n"
                        : "控制命令失败或设备本地状态异常\n");
        return new ActionResult(log.toString(), succeeded);
    }

    static String status(Context context) {
        StringBuilder log = new StringBuilder();
        appendStatus(log, context);
        return log.toString();
    }

    static ActionResult executeRoot(String label, String command) {
        StringBuilder log = new StringBuilder();
        int exit = runRoot(log, label, command);
        return new ActionResult(log.toString(), exit == 0);
    }

    static synchronized ActionResult executeRootSequence(String label, List<String> commands) {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < commands.size(); i++) {
            int exit = runRoot(log,
                    label + "（" + (i + 1) + "/" + commands.size() + "）",
                    commands.get(i));
            if (exit != 0) {
                log.append("序列在第").append(i + 1).append("步停止。\n");
                return new ActionResult(log.toString(), false);
            }
        }
        return new ActionResult(log.toString(), true);
    }

    static synchronized ActionResult executeOriginalRoot(
            String label, String command, int timeoutMillis) {
        StringBuilder log = new StringBuilder();
        int exit = runRootWithHelper(
                log, label, ORIGINAL_ROOT_HELPER, command, timeoutMillis);
        return new ActionResult(log.toString(), exit == 0);
    }

    private static void appendStatus(StringBuilder log, Context context) {
        log.append("\n== Android API状态 ==\nADB_ENABLED=")
                .append(adbEnabled(context)).append('\n');
        log.append("5555/8765未设置设备侧来源限制，由外部路由器控制公网入站\n");
        run(log, "ADB 属性",
                "getprop ro.secure; getprop ro.debuggable; getprop ro.adb.secure; "
                + "getprop persist.service.adb.enable; getprop persist.adb.tcp.port; "
                + "getprop service.adb.tcp.port; getprop persist.sys.usb.config; "
                + "getprop sys.usb.config; getprop sys.usb.state; "
                + "getprop sys.usb.ffs.ready; getprop init.svc.adbd");
        boolean listening = isListening();
        log.append("\n== 本机端口验证 ==\n127.0.0.1:")
                .append(PORT).append(listening ? " 正在监听\n" : " 未监听\n");
    }

    static boolean isListening() {
        return TcpListenerState.isListening(PORT);
    }

    static boolean isHealthy(Context context) {
        return "5555".equals(readValue("getprop persist.adb.tcp.port"))
                && "5555".equals(readValue("getprop service.adb.tcp.port"))
                && adbEnabled(context) == 1
                && readValue("getprop persist.sys.usb.config").contains("adb")
                && readValue("getprop sys.usb.config").contains("adb")
                && "1".equals(readValue("getprop sys.usb.ffs.ready"))
                && "running".equals(readValue("getprop init.svc.adbd"))
                && isListening();
    }

    private static int adbEnabled(Context context) {
        try {
            return Settings.Global.getInt(
                    context.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int packageUid(Context context, String packageName, int fallback) {
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(packageName, 0);
            return info.uid;
        } catch (PackageManager.NameNotFoundException ignored) {
            return fallback;
        }
    }

    private static String readValue(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true).start();
            if (process.waitFor() != 0) return "";
            return readAll(process.getInputStream()).trim();
        } catch (Throwable ignored) {
            if (process != null) process.destroy();
            return "";
        }
    }

    private static int run(StringBuilder log, String label, String command) {
        log.append("\n== ").append(label).append(" ==\n");
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true).start();
            long deadline = System.currentTimeMillis() + 8000L;
            Integer exit = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    exit = process.exitValue();
                    break;
                } catch (IllegalThreadStateException running) {
                    sleep(100);
                }
            }
            if (exit == null) {
                process.destroy();
                log.append(readAll(process.getInputStream())).append("[超时，已终止]\n");
                return -1;
            } else {
                log.append(readAll(process.getInputStream())).append("[退出码 ")
                        .append(exit).append("]\n");
                return exit;
            }
        } catch (Throwable error) {
            log.append(error).append('\n');
            if (process != null) process.destroy();
            return -2;
        }
    }

    private static synchronized int runRoot(StringBuilder log, String label, String command) {
        long now = SystemClock.elapsedRealtime();
        if (now < rootBlockedUntil) {
            long remainingSeconds = (rootBlockedUntil - now + 999L) / 1000L;
            log.append("\n== ").append(label).append(" ==\n")
                    .append("[厂商root通道处于失败冷却期，跳过本次操作；剩余约")
                    .append(remainingSeconds).append("秒]\n")
                    .append("[退出码 -3]\n");
            return -3;
        }

        int exit = runRootWithHelper(log, label, ROOT_HELPER, command, 8000);
        if (isRootTransportFailure(exit)) {
            rootBlockedUntil = SystemClock.elapsedRealtime() + ROOT_FAILURE_COOLDOWN_MILLIS;
        }
        return exit;
    }

    static boolean isRootTransportFailure(int exit) {
        return exit == -1 || exit == 75 || exit == 124;
    }

    private static int runRootWithHelper(StringBuilder log, String label,
            String helper, String command, int timeoutMillis) {
        log.append("\n== ").append(label).append(" ==\n");
        Process process = null;
        try {
            process = new ProcessBuilder(helper, command)
                    .redirectErrorStream(true).start();
            int exit = waitFor(process, log, timeoutMillis);
            log.append("[退出码 ").append(exit).append("]\n");
            return exit;
        } catch (Throwable error) {
            log.append(error).append('\n');
            if (process != null) process.destroy();
            return -2;
        }
    }

    private static int waitFor(Process process, StringBuilder log, int timeoutMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Integer exit = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                exit = process.exitValue();
                break;
            } catch (IllegalThreadStateException running) {
                sleep(100);
            }
        }
        if (exit == null) {
            process.destroy();
            log.append("[超时，已终止；不再读取已关闭的输出流]\n");
            return -1;
        }
        log.append(readAll(process.getInputStream()));
        return exit;
    }

    private static String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toString("UTF-8");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
