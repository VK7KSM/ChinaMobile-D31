package net.elfradio.d31bootstrap.management;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 复用系统settings的外部Provider引用；不经shell拼接，不用API26 waitFor超时重载。 */
final class SettingsCommand {
    static String invoke(String... args) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList("/system/bin/settings", "--user", "0"));
        Collections.addAll(command, args);
        return collect(new ProcessBuilder(command).redirectErrorStream(true).start(), 5000);
    }

    static String collect(final Process process, long timeoutMs) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final IOException[] failure = new IOException[1];
        Thread reader = new Thread(new Runnable() {
            public void run() {
                try (InputStream input = process.getInputStream()) {
                    byte[] buffer = new byte[512];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > 4096) throw new IOException("系统设置输出超限");
                        output.write(buffer, 0, count);
                    }
                } catch (IOException error) { failure[0] = error; process.destroy(); }
            }
        }, "d31-settings-output");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.nanoTime() + timeoutMs * 1000000L;
        try {
            int exit;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("设置命令已取消");
                try { exit = process.exitValue(); break; }
                catch (IllegalThreadStateException running) {
                    if (System.nanoTime() - deadline >= 0) throw new IOException("系统设置命令超时");
                    Thread.sleep(20);
                }
            }
            long remaining = (deadline - System.nanoTime()) / 1000000L;
            if (remaining > 0) reader.join(remaining);
            if (reader.isAlive()) throw new IOException("系统设置输出未结束");
            if (failure[0] != null) throw failure[0];
            if (exit != 0) throw new IOException("系统设置命令失败，退出码=" + exit);
            return output.toString("UTF-8").trim();
        } finally {
            process.destroy();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
        }
    }
    private SettingsCommand() { }
}
