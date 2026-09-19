package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * 核心（root）内拉起与结束 scrcpy 服务端。
 *
 * 必须以 uid 2000 运行：scrcpy 对系统服务自称 com.android.shell，剪贴板等服务会校验调用方 uid，
 * 以 root 运行会被拒。D31 的 su 是 AOSP 风格 `su UID COMMAND...`，不支持 D22 用的 `su 2000 -c`，
 * 复合命令必须写成 `su 2000 /system/bin/sh -c '...'`。
 *
 * 视频与控制两条字节流由应用进程直接连 localabstract:scrcpy_<scid>，核心不转发字节。
 */
final class DesktopLauncher implements RemoteDesktop.Launcher {
    private static final Pattern SCID = Pattern.compile("^[0-9a-f]{8}$");
    static final int FPS_MIN = 1, FPS_MAX = 60;
    static final int BITRATE_MIN = 100000, BITRATE_MAX = 8000000;
    static final int SIZE_MAX = 1920;
    private final File log;
    private String currentScid = "";

    DesktopLauncher(File root) { this.log = new File(root, "desktop.log"); }

    static int clampFps(int value) { return Math.max(FPS_MIN, Math.min(FPS_MAX, value)); }
    static int clampBitRate(int value) { return Math.max(BITRATE_MIN, Math.min(BITRATE_MAX, value)); }
    static int clampSize(int value) { return Math.max(0, Math.min(SIZE_MAX, value)); }

    /** 服务端命令行，纯函数便于测试；参数先夹紧再拼接，scid 已在调用前校验为八位十六进制。 */
    static String serverCommand(String scid, int maxFps, int bitRate, int maxSize) {
        int fps = clampFps(maxFps), rate = clampBitRate(bitRate), size = clampSize(maxSize);
        return "CLASSPATH=" + DesktopAsset.TARGET_PATH + " exec app_process / com.genymobile.scrcpy.Server "
                + DesktopAsset.VERSION + " scid=" + scid
                + " tunnel_forward=true audio=false control=true cleanup=true power_on=true"
                + " send_dummy_byte=false log_level=info max_fps=" + fps + " video_bit_rate=" + rate
                + (size > 0 ? " max_size=" + size : "");
    }

    /** /proc/<pid>/cmdline 以NUL分隔；判断该进程是否本次会话的服务端。 */
    static boolean cmdlineMatches(byte[] cmdline, String scid) {
        if (cmdline == null || cmdline.length == 0 || !SCID.matcher(scid).matches()) return false;
        String text = new String(cmdline, java.nio.charset.StandardCharsets.UTF_8).replace('\0', ' ');
        return text.contains("com.genymobile.scrcpy.Server") && text.contains("scid=" + scid);
    }

    @Override public synchronized JSONObject start(JSONObject request) throws Exception {
        String scid = request.optString("scid");
        if (!SCID.matcher(scid).matches()) throw new IllegalArgumentException("DESKTOP_SCID_INVALID");
        if (!DesktopAsset.installed()) throw new IllegalStateException("DESKTOP_ASSET_NOT_READY");
        stopLocked();
        // 每次拉起前重申权限：外部改动过目录或文件时，uid 2000 读不到会表现为类路径为空的崩溃，很难从报错看出来。
        DesktopAsset.applyPermissions();
        if (log.length() > 262144 && !log.delete()) throw new IOException("DESKTOP_LOG_ROTATE_FAILED");
        String server = serverCommand(scid, request.optInt("max_fps", 30), request.optInt("bit_rate", 1500000), request.optInt("max_size", 0));
        // setsid 让服务端独立于本次调用的进程组存活；su 2000 降权；输出追加到核心目录日志。
        new ProcessBuilder("/system/bin/setsid", "/system/bin/sh", "-c",
                "exec su 2000 /system/bin/sh -c " + RescueFiles.quote(server)
                        + " </dev/null >>" + RescueFiles.quote(log.getPath()) + " 2>&1")
                .start();
        currentScid = scid;
        return new JSONObject().put("ok", true).put("scid", scid)
                .put("socket", "scrcpy_" + scid).put("version", DesktopAsset.VERSION);
    }

    @Override public synchronized JSONObject stop() throws Exception {
        stopLocked();
        return new JSONObject().put("ok", true);
    }

    synchronized JSONObject status() {
        boolean running = !currentScid.isEmpty() && !scan(currentScid).isEmpty();
        return jsonQuiet(running, running ? currentScid : "");
    }

    private static JSONObject jsonQuiet(boolean running, String scid) {
        try { return new JSONObject().put("running", running).put("scid", scid).put("scrcpy_ready", DesktopAsset.installed()); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    /**
     * 先用 pkill 快路径，再扫 /proc 兜底并轮询确认消失。
     * 只用 pkill 不够：部分机型的 ps/pkill 读不到完整参数就匹配不到，残留进程会占住抽象套接字，
     * 导致下一次会话连不上；只扫 /proc 也不够，杀不掉时需要快路径先收走大多数情况。
     */
    private void stopLocked() {
        if (currentScid.isEmpty()) return;
        String scid = currentScid;
        currentScid = "";
        try { new ProcessBuilder("/system/bin/pkill", "-f", "scid=" + scid).start().waitFor(); } catch (Exception ignored) { }
        for (int attempt = 0; attempt < 20; attempt++) {
            java.util.List<Integer> pids = scan(scid);
            if (pids.isEmpty()) return;
            for (int pid : pids) {
                try { android.os.Process.killProcess(pid); } catch (Exception ignored) { }
            }
            try { Thread.sleep(100); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
        System.err.println("DESKTOP_SERVER_STOP_PENDING scid=" + scid);
    }

    /** 扫描仍在运行的本次会话服务端进程号；读不到的条目跳过，不把读失败当成已退出。 */
    private java.util.List<Integer> scan(String scid) {
        java.util.List<Integer> found = new java.util.ArrayList<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return found;
        for (File entry : entries) {
            if (!entry.isDirectory() || !entry.getName().matches("[0-9]+")) continue;
            try (java.io.InputStream in = new java.io.FileInputStream(new File(entry, "cmdline"))) {
                // API23没有Files.readAllBytes；cmdline很短，读满一个缓冲区即可判定。
                byte[] buffer = new byte[4096]; int filled = 0, n;
                while (filled < buffer.length && (n = in.read(buffer, filled, buffer.length - filled)) != -1) filled += n;
                if (cmdlineMatches(java.util.Arrays.copyOf(buffer, filled), scid)) found.add(Integer.parseInt(entry.getName()));
            } catch (Exception unreadable) { /* 进程已退出或不可读；不据此判定为已退出 */ }
        }
        return found;
    }
}
