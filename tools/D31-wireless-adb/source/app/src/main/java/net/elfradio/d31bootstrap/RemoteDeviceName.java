package net.elfradio.d31bootstrap;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class RemoteDeviceName {
    interface Reader { String get(String table, String key) throws Exception; }
    static String read(Reader reader, String model) {
        for (String[] field : new String[][]{{"global", "device_name"}, {"secure", "bluetooth_name"}}) {
            try {
                String value = reader.get(field[0], field[1]);
                if (value != null && !value.trim().isEmpty() && !"null".equals(value.trim())) return value.trim();
            } catch (Exception unavailable) { }
        }
        return model;
    }

    // 独立root进程没有Android应用归属；使用原厂settings入口按真实调用者访问设置库。
    static String setting(String table, String key) throws Exception {
        Process process = new ProcessBuilder("/system/bin/settings", "get", table, key).start();
        long until = android.os.SystemClock.elapsedRealtime() + 5000;
        try {
            while (true) {
                try { if (process.exitValue() != 0) return ""; break; }
                catch (IllegalThreadStateException running) {
                    if (android.os.SystemClock.elapsedRealtime() >= until) return "";
                    Thread.sleep(25);
                }
            }
            try (InputStream in = process.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                int b; while ((b = in.read()) != -1) { if (out.size() >= 2048) return ""; out.write(b); }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { process.destroy(); }
    }
}
