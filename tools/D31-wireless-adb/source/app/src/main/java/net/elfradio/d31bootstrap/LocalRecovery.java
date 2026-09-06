package net.elfradio.d31bootstrap;

import android.content.Context;
import android.content.Intent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

final class LocalRecovery {
    private static final String PATCH = "/data/local/d31-patches/apply-home-patch.sh";
    private static final String PATCH_BACKUP =
            "/data/local/d31-patches/apply-home-patch.sh.backup-20260905-000927";
    private static final String PATCH_BACKUP_SHA256 =
            "8C3CD3621CD5CBB2CDC1A95B8DE36C91D248FAB79969B37C009B30302DA10BB1";
    private static final String VERIFIED_PATCH_SHA256_V7 =
            "BA25EBF967F80DB0C8F7A6C3AC01DB34E042049A3EB7EA32BA4EDA845332DBD0";
    private static final String VERIFIED_PATCH_SHA256_V8 =
            "7B5B5C2A6A4EA30AD29FBBA6D4C0339C8A4CCAA41BA738D57D990AA8ACCF65ED";
    private static final String VERIFIED_PATCH_SHA256_V9 =
            "786AEAB0B906BFFA723FF8C87F2EDDDD9C184E4F60BDB57881D492FD08B22C4C";
    private static final String PATCH_TEMP = PATCH + ".local-recovery-new";

    private LocalRecovery() {
    }

    static AdbControl.ActionResult run(Context context) {
        StringBuilder log = new StringBuilder();
        log.append("D31本地急救 1.10.30\n")
                .append("不会删除账号、短信、SIP配置、SIM数据或用户文件。\n")
                .append("所有厂商root调用均直接使用原厂客户端并设有限时。\n");

        if (!runRequired(log, "确认原厂root客户端", "true", 5000)) {
            return failed(log, "原厂root客户端不存在或不可执行，已停止");
        }

        if (!runRootCheck(log, "清理并回读陈旧root锁",
                staleLockCleanupCondition())) {
            return failed(log, "root锁仍由活动进程持有或无法安全清理，已停止");
        }

        boolean startupPatchRestored = restoreStartupPatch(log);
        if (!startupPatchRestored) {
            log.append("蜂窝开机补丁未能安全回滚；继续恢复ADB，未覆盖开机脚本。\n");
        }

        String[][] commands = {
                {"关闭运行时4G强制属性", "setprop sys.4g.enable false"},
                {"关闭运行时VoLTE强制属性", "setprop sys.volte.enable false"},
                {"启用持久ADB服务", "setprop persist.service.adb.enable 1"},
                {"设置持久ADB端口", "setprop persist.adb.tcp.port 5555"},
                {"设置当前ADB端口", "setprop service.adb.tcp.port 5555"},
                {"启用Android调试开关", "settings put global adb_enabled 1"},
                {"设置持久MTK USB模式", "setprop persist.sys.usb.config mass_storage,adb"},
                {"暂时停用MTK USB组合", "setprop sys.usb.config none"}
        };
        for (String[] command : commands) {
            if (!runRequired(log, command[0], command[1], 5000)) {
                return failed(log, command[0] + "失败，已停止");
            }
        }

        sleep(2000);
        if (!runRequired(log, "恢复MTK USB组合",
                "setprop sys.usb.config mass_storage,adb", 5000)) {
            return failed(log, "恢复MTK USB组合失败，已停止");
        }
        if (!runRequired(log, "启动adbd", "start adbd", 5000)) {
            return failed(log, "启动adbd命令失败，已停止");
        }

        context.startService(new Intent(context, ProbeService.class)
                .setAction("local-recovery"));

        long deadline = System.currentTimeMillis() + 25000L;
        while (System.currentTimeMillis() < deadline && !isListening(5555)) {
            sleep(1000);
        }

        log.append("\n== 最终属性回读 ==\n").append(readStatus());
        boolean adbListening = isListening(5555);
        boolean probeListening = isListening(8765);
        log.append("127.0.0.1:5555 ")
                .append(adbListening ? "正在监听\n" : "未监听\n")
                .append("127.0.0.1:8765 ")
                .append(probeListening ? "正在监听\n" : "未监听\n");

        boolean adbSucceeded = adbListening
                && "5555".equals(readValue("getprop persist.adb.tcp.port"))
                && "5555".equals(readValue("getprop service.adb.tcp.port"))
                && "running".equals(readValue("getprop init.svc.adbd"));
        boolean succeeded = adbSucceeded && startupPatchRestored;
        log.append("\n== 急救结论 ==\n")
                .append(succeeded ? "急救完成。电脑现在可以连接D31的5555端口。\n"
                        : adbSucceeded
                                ? "ADB已经恢复，但开机补丁没有回滚。请保留本页。\n"
                                : "ADB仍未完整恢复。请保留本页，不要反复点击。\n");
        return new AdbControl.ActionResult(log.toString(), succeeded);
    }

    private static boolean restoreStartupPatch(StringBuilder log) {
        if (runRootCheck(log, "校验当前开机脚本是否为已验证版本",
                anyHashCheckCommand(PATCH,
                        VERIFIED_PATCH_SHA256_V7, VERIFIED_PATCH_SHA256_V8,
                        VERIFIED_PATCH_SHA256_V9))) {
            log.append("当前开机脚本已是验证版本，保留原文件，不执行回退。\n");
            return true;
        }

        if (!runRootCheck(log, "校验蜂窝补丁备份",
                hashCheckCommand(PATCH_BACKUP, PATCH_BACKUP_SHA256))) {
            log.append("备份不存在或SHA-256不匹配，拒绝覆盖开机脚本。\n");
            return false;
        }

        String[][] restore = {
                {"复制已验证备份到临时文件", "cp " + PATCH_BACKUP + " " + PATCH_TEMP},
                {"恢复临时文件属主", "chown 0:0 " + PATCH_TEMP},
                {"恢复临时文件权限", "chmod 0755 " + PATCH_TEMP},
                {"原子替换开机脚本", "mv -f " + PATCH_TEMP + " " + PATCH},
                {"同步开机脚本", "sync"}
        };
        for (String[] command : restore) {
            if (!runRequired(log, command[0], command[1], 5000)) return false;
        }

        return runRootCheck(log, "复核恢复后的开机脚本",
                hashCheckCommand(PATCH, PATCH_BACKUP_SHA256));
    }

    static String hashCheckCommand(String path, String expectedSha256) {
        return "/system/bin/busybox sha256sum " + path
                + " | /system/bin/busybox grep -qi '^"
                + expectedSha256 + "  '";
    }

    static String anyHashCheckCommand(String path, String... expectedDigests) {
        StringBuilder command = new StringBuilder();
        for (String digest : expectedDigests) {
            if (command.length() > 0) command.append(" || ");
            command.append(hashCheckCommand(path, digest));
        }
        return command.toString();
    }

    static String staleLockCleanupCondition() {
        String lock = "/data/local/snSudoSerialize/held";
        return "owner=$(cat " + lock + "/owner 2>/dev/null); "
                + "if [ -n \"$owner\" ] && [ -d \"/proc/$owner\" ]; "
                + "then false; else rm -rf " + lock + " && [ ! -e " + lock + " ]; fi";
    }

    static String markedCheckCommand(String condition, String marker) {
        return "(" + condition + ") && echo PASS > " + marker
                + " && chmod 0644 " + marker;
    }

    private static boolean runRootCheck(
            StringBuilder log, String label, String condition) {
        String marker = "/data/local/tmp/d31-local-recovery-"
                + Long.toHexString(System.currentTimeMillis()) + "-"
                + Integer.toHexString(label.hashCode());
        AdbControl.ActionResult submitted = AdbControl.executeOriginalRoot(
                label, markedCheckCommand(condition, marker), 5000);
        log.append(submitted.log);
        if (!submitted.succeeded) return false;

        long deadline = System.currentTimeMillis() + 5000L;
        String value = "";
        while (System.currentTimeMillis() < deadline) {
            value = readValue("cat " + marker + " 2>/dev/null");
            if ("PASS".equals(value)) break;
            sleep(100);
        }
        log.append("[设备回读 ").append(value.isEmpty() ? "未生成" : value).append("]\n");
        AdbControl.executeOriginalRoot("清理急救回读标记", "rm -f " + marker, 5000);
        return "PASS".equals(value);
    }

    private static boolean runRequired(StringBuilder log, String label,
            String command, int timeoutMillis) {
        AdbControl.ActionResult result = AdbControl.executeOriginalRoot(
                label, command, timeoutMillis);
        log.append(result.log);
        return result.succeeded;
    }

    private static AdbControl.ActionResult failed(StringBuilder log, String reason) {
        log.append("\n== 急救结论 ==\n").append(reason).append('\n');
        return new AdbControl.ActionResult(log.toString(), false);
    }

    private static String readStatus() {
        return "persist.service.adb.enable="
                + readValue("getprop persist.service.adb.enable") + '\n'
                + "persist.adb.tcp.port="
                + readValue("getprop persist.adb.tcp.port") + '\n'
                + "service.adb.tcp.port="
                + readValue("getprop service.adb.tcp.port") + '\n'
                + "persist.sys.usb.config="
                + readValue("getprop persist.sys.usb.config") + '\n'
                + "sys.usb.config=" + readValue("getprop sys.usb.config") + '\n'
                + "sys.usb.state=" + readValue("getprop sys.usb.state") + '\n'
                + "sys.usb.ffs.ready=" + readValue("getprop sys.usb.ffs.ready") + '\n'
                + "init.svc.adbd=" + readValue("getprop init.svc.adbd") + '\n'
                + "sys.4g.enable=" + readValue("getprop sys.4g.enable") + '\n'
                + "sys.volte.enable=" + readValue("getprop sys.volte.enable") + '\n';
    }

    private static String readValue(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true).start();
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline) {
                try {
                    int exit = process.exitValue();
                    return exit == 0 ? readAll(process.getInputStream()).trim() : "";
                } catch (IllegalThreadStateException running) {
                    sleep(50);
                }
            }
            process.destroy();
            return "";
        } catch (Throwable ignored) {
            if (process != null) process.destroy();
            return "";
        }
    }

    private static boolean isListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
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
