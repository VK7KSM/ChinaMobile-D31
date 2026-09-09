package net.elfradio.d31system;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

final class SystemActions {
    static final class ActionResult {
        final String log;
        final boolean succeeded;
        ActionResult(String log, boolean succeeded) { this.log = log; this.succeeded = succeeded; }
    }
    private static long blockedUntil;
    private SystemActions() { }

    static synchronized ActionResult executeRoot(String label, String command) {
        return LocalRootChannel.execute(label, command);
    }

    private static ActionResult legacyRoot(String label, String command) {
        if (SystemClock.elapsedRealtime() < blockedUntil)
            return new ActionResult(label + "：系统命令通道暂处于冷却期", false);
        Process process = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = null;
        try {
            process = new ProcessBuilder("/system/bin/snSudoClient", command).redirectErrorStream(true).start();
            final InputStream input = process.getInputStream();
            drain = new Thread(() -> {
                try {
                    byte[] bytes = new byte[2048]; int count;
                    while ((count = input.read(bytes)) != -1) {
                        synchronized (output) {
                            int remaining = 65536 - output.size();
                            if (remaining > 0) output.write(bytes, 0, Math.min(count, remaining));
                        }
                    }
                } catch (Exception ignored) { }
            }, "d31-system-output");
            drain.setDaemon(true); drain.start();
            long deadline = SystemClock.elapsedRealtime() + 8000;
            while (true) {
                try {
                    int exit = process.exitValue();
                    drain.join(500);
                    if (exit == 75 || exit == 124) blockedUntil = SystemClock.elapsedRealtime() + 120000;
                    synchronized (output) {
                        return new ActionResult(label + "\n" + output.toString("UTF-8") + "\n退出码=" + exit, exit == 0);
                    }
                } catch (IllegalThreadStateException running) {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        blockedUntil = SystemClock.elapsedRealtime() + 120000;
                        return new ActionResult(label + "：系统命令超时", false);
                    }
                    Thread.sleep(50);
                }
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ActionResult(label + "：" + error, false);
        } finally {
            if (process != null) {
                process.destroy();
                try { process.getInputStream().close(); } catch (Exception ignored) { }
            }
        }
    }

    static synchronized ActionResult executeRootSequence(String label, List<String> commands) {
        StringBuilder log = new StringBuilder();
        for (String command : commands) {
            ActionResult result = executeRoot(label, command);
            log.append(result.log).append('\n');
            if (!result.succeeded) return new ActionResult(log.toString(), false);
        }
        return new ActionResult(log.toString(), true);
    }
}
