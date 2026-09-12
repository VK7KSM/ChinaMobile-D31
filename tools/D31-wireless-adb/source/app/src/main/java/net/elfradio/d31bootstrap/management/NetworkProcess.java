package net.elfradio.d31bootstrap.management;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** API23有界子进程收集；退出未知仍报错，不能据destroy推断Binder服务撤销请求。 */
public final class NetworkProcess {
    /** 调用方须合并stderr；最多2048字节，非零退出、超时、中断或输出不完整均抛异常。 */
    public static String collect(Process process, long timeoutMs) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[256]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (output.size() + n > 2048) throw new IOException("子进程输出超限");
                    output.write(buffer, 0, n);
                }
            } catch (Exception error) { failure.set(error); process.destroy(); }
        }, "d31-network-output");
        reader.setDaemon(true); reader.start();
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean interrupted = false;
        try {
            int code;
            for (;;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("网络调用已取消");
                try { code = process.exitValue(); break; } catch (IllegalThreadStateException running) { }
                if (System.nanoTime() >= until) throw new IOException("网络调用超时，效果未知");
                Thread.sleep(20);
            }
            long remaining = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime());
            if (remaining > 0) reader.join(remaining);
            if (reader.isAlive() || failure.get() != null || code != 0) throw new IOException("网络调用回执不完整");
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (InterruptedException error) {
            interrupted = true; throw error;
        } finally {
            process.destroy();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            try { reader.join(100); } catch (InterruptedException error) { interrupted = true; }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private NetworkProcess() { }
}
