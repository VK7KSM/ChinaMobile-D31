package net.elfradio.d31bootstrap;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ProbeLog {
    private static final String FILE_NAME = "probe.log";
    private static final long MAX_BYTES = 256 * 1024L;

    private ProbeLog() {
    }

    static synchronized void append(Context context, String message) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (file.length() > MAX_BYTES) file.delete();
        String timestamp = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String entry = "\n[" + timestamp + "]\n" + message + "\n";
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(entry.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    static synchronized String read(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) return "尚无持久日志\n";
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        } catch (Throwable error) {
            return "读取持久日志失败：" + error + "\n";
        }
    }
}
