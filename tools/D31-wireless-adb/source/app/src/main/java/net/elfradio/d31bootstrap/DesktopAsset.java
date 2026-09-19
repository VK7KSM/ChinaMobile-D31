package net.elfradio.d31bootstrap;

import android.system.Os;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 官方 scrcpy 服务端原样随完整版 APK 分发（Apache-2.0，版本永久固定），由 root 核心释放到独立目录。
 *
 * 目录选择：放在核心目录 /data/local/d31-remote 的同级而不是子级。scrcpy 必须以 uid 2000 运行，
 * 而 uid 2000 要读到资产就必须能穿越其所在目录；放子级就得放宽核心目录权限，核心目录里有设备令牌，
 * 因此一格都不放宽。/data/local 本身是 0751，uid 2000 已可穿越，所以只需把本目录设为 0711、文件 0644。
 *
 * 校验：D31 没有 sha256sum，不降级成 md5，直接在核心进程内用 RescueFiles.sha256 校验。
 */
final class DesktopAsset {
    static final String VERSION = "3.3.3";
    static final String SHA256 = "7e70323ba7f259649dd4acce97ac4fefbae8102b2c6d91e2e7be613fd5354be0";
    static final String RESOURCE = "/scrcpy-server";
    /** 路径用字符串常量固定为POSIX形式：它会被拼进shell命令，不能依赖File.getPath()的平台分隔符。 */
    static final String DIR_PATH = "/data/local/d31-desktop";
    static final String TARGET_PATH = DIR_PATH + "/scrcpy-server";
    static final File DIR = new File(DIR_PATH);
    static final File TARGET = new File(TARGET_PATH);
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    /** 已安装且哈希正确才算就绪；供能力位与拉起前自检使用。 */
    static boolean installed() {
        try { return TARGET.isFile() && SHA256.equals(RescueFiles.sha256(TARGET)); }
        catch (Exception unavailable) { return false; }
    }

    /** 完整版才带该资源；基础版返回false，能力位据此保持false。 */
    static boolean packaged() {
        try (InputStream in = DesktopAsset.class.getResourceAsStream(RESOURCE)) { return in != null; }
        catch (Exception unavailable) { return false; }
    }

    /**
     * 缺失或哈希不符才释放；写临时文件、校验、原子改名，全程不经过shell。
     * 安装失败由核心的周期自检反复重试，不在本方法内自旋。
     */
    static synchronized void install() throws Exception {
        if (installed()) { applyPermissions(); return; }
        if (!DIR.isDirectory() && !DIR.mkdirs()) throw new IOException("DESKTOP_ASSET_DIR");
        Os.chmod(DIR_PATH, 0711);
        File temporary = new File(DIR, "scrcpy-server.new");
        try (InputStream in = DesktopAsset.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("DESKTOP_ASSET_MISSING");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32768]; int n; long length = 0;
                while ((n = in.read(buffer)) != -1) {
                    length += n;
                    if (length > MAX_BYTES) throw new IOException("DESKTOP_ASSET_TOO_LARGE");
                    out.write(buffer, 0, n);
                }
                out.getFD().sync();
            }
        }
        if (!SHA256.equals(RescueFiles.sha256(temporary))) { temporary.delete(); throw new IOException("DESKTOP_ASSET_HASH"); }
        Os.chmod(DIR_PATH + "/scrcpy-server.new", 0644);
        if (!temporary.renameTo(TARGET)) { temporary.delete(); throw new IOException("DESKTOP_ASSET_RENAME"); }
        applyPermissions();
        if (!installed()) throw new IOException("DESKTOP_ASSET_VERIFY");
    }

    /** 每次拉起前重申，避免外部改动后 uid 2000 读不到导致类路径为空。 */
    static void applyPermissions() throws Exception {
        Os.chmod(DIR_PATH, 0711);
        if (TARGET.isFile()) Os.chmod(TARGET_PATH, 0644);
    }

    private DesktopAsset() {}
}
