package net.elfradio.d31bootstrap;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 远程桌面服务端命令行与进程识别。
 * D31 的 su 是 AOSP 风格，拉起写法与 D22 不同；结束时 pkill 可能匹配不到，必须能靠 cmdline 认出残留进程，
 * 否则它会占住抽象套接字让下一次会话连不上。
 */
public class DesktopLauncherTest {
    private static byte[] cmdline(String... parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) text.append(part).append('\0');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test public void serverCommandClampsEveryParameterAndOmitsUnsetSize() {
        String command = DesktopLauncher.serverCommand("0a1b2c3d", 30, 1500000, 0);
        assertTrue(command.startsWith("CLASSPATH=/data/local/d31-desktop/scrcpy-server exec app_process / com.genymobile.scrcpy.Server 3.3.3 "));
        assertTrue(command.contains("scid=0a1b2c3d"));
        assertTrue(command.contains("max_fps=30"));
        assertTrue(command.contains("video_bit_rate=1500000"));
        assertFalse("未指定尺寸时不得传max_size", command.contains("max_size"));
        assertTrue(command.contains("control=true"));
        assertFalse("不采集音频", command.contains("audio=true"));

        // 越界参数必须夹紧，不能原样传给服务端。
        String low = DesktopLauncher.serverCommand("00000000", -5, 1, -100);
        assertTrue(low.contains("max_fps=" + DesktopLauncher.FPS_MIN));
        assertTrue(low.contains("video_bit_rate=" + DesktopLauncher.BITRATE_MIN));
        assertFalse(low.contains("max_size"));
        String high = DesktopLauncher.serverCommand("ffffffff", 999, 99000000, 4096);
        assertTrue(high.contains("max_fps=" + DesktopLauncher.FPS_MAX));
        assertTrue(high.contains("video_bit_rate=" + DesktopLauncher.BITRATE_MAX));
        assertTrue(high.contains("max_size=" + DesktopLauncher.SIZE_MAX));
    }

    @Test public void cmdlineMatchesOnlyThisSessionServer() {
        byte[] server = cmdline("app_process", "/", "com.genymobile.scrcpy.Server", "3.3.3", "scid=0a1b2c3d", "control=true");
        assertTrue(DesktopLauncher.cmdlineMatches(server, "0a1b2c3d"));
        // 另一次会话的服务端不得被误杀。
        assertFalse(DesktopLauncher.cmdlineMatches(server, "0a1b2c3e"));
        // 只带会话号但不是服务端的进程不得被误杀。
        assertFalse(DesktopLauncher.cmdlineMatches(cmdline("/system/bin/pkill", "-f", "scid=0a1b2c3d"), "0a1b2c3d"));
        // 是服务端但没有会话号的，不属于本次会话。
        assertFalse(DesktopLauncher.cmdlineMatches(cmdline("app_process", "com.genymobile.scrcpy.Server"), "0a1b2c3d"));
        // 读不到内容时不得判定为匹配，否则会去杀一个未知进程。
        assertFalse(DesktopLauncher.cmdlineMatches(new byte[0], "0a1b2c3d"));
        assertFalse(DesktopLauncher.cmdlineMatches(null, "0a1b2c3d"));
        // 会话号形状非法时一律不匹配，避免用空串或通配去杀进程。
        assertFalse(DesktopLauncher.cmdlineMatches(server, ""));
        assertFalse(DesktopLauncher.cmdlineMatches(server, "0A1B2C3D"));
        assertFalse(DesktopLauncher.cmdlineMatches(server, "0a1b2c3"));
    }

    @Test public void assetLivesBesideTheCoreDirectoryNotInsideIt() {
        // scrcpy 必须以 uid 2000 运行，其所在目录要可穿越；核心目录里有设备令牌，一格都不能放宽。
        assertEquals("/data/local/d31-desktop", DesktopAsset.DIR_PATH);
        assertEquals("/data/local/d31-desktop/scrcpy-server", DesktopAsset.TARGET_PATH);
        assertFalse("资产不得放在核心目录之内", DesktopAsset.TARGET_PATH.startsWith("/data/local/d31-remote/"));
        assertEquals("3.3.3", DesktopAsset.VERSION);
        assertTrue(DesktopAsset.SHA256.matches("[a-f0-9]{64}"));
    }
}
