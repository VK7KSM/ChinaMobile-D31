package net.elfradio.d31bootstrap;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

final class ArpControl {
    private static final String ROOT = "/proc/sys/net/ipv4/conf/";
    private static final String[][] SETTINGS = {
            {"all/arp_ignore", "1"},
            {"all/arp_announce", "2"},
            {"default/arp_ignore", "1"},
            {"default/arp_announce", "2"},
            {"eth0/arp_ignore", "1"},
            {"eth0/arp_announce", "2"},
            {"wlan0/arp_ignore", "1"},
            {"wlan0/arp_announce", "2"}
    };

    private ArpControl() {
    }

    static AdbControl.ActionResult ensureApplied(Context context) {
        AdbControl.ActionResult write = AdbControl.executeRootSequence(
                "应用双网卡ARP防串扰参数", buildApplyCommands());
        StringBuilder log = new StringBuilder(write.log);
        boolean verified = appendStatus(log);
        log.append("ARP参数结论：")
                .append(write.succeeded && verified ? "写入和回读均通过\n" : "未完全通过\n");
        return new AdbControl.ActionResult(log.toString(), write.succeeded && verified);
    }

    static String status() {
        StringBuilder log = new StringBuilder("== 双网卡ARP防串扰参数 ==\n");
        boolean verified = appendStatus(log);
        log.append("状态=").append(verified ? "正确" : "异常").append('\n');
        return log.toString();
    }

    static List<String> buildApplyCommands() {
        List<String> commands = new ArrayList<>();
        for (String[] setting : SETTINGS) {
            commands.add("echo " + setting[1] + " > " + ROOT + setting[0]);
        }
        return commands;
    }

    static String[][] expectedSettings() {
        String[][] copy = new String[SETTINGS.length][2];
        for (int i = 0; i < SETTINGS.length; i++) {
            copy[i][0] = ROOT + SETTINGS[i][0];
            copy[i][1] = SETTINGS[i][1];
        }
        return copy;
    }

    private static boolean appendStatus(StringBuilder log) {
        boolean verified = true;
        for (String[] setting : SETTINGS) {
            String path = ROOT + setting[0];
            String actual = readValue(path);
            boolean matches = setting[1].equals(actual);
            log.append(path).append('=').append(actual.isEmpty() ? "<无法读取>" : actual)
                    .append(matches ? " [通过]\n" : " [期望 " + setting[1] + "]\n");
            verified &= matches;
        }
        return verified;
    }

    private static String readValue(String path) {
        try (InputStream input = new FileInputStream(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString("US-ASCII").trim();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
