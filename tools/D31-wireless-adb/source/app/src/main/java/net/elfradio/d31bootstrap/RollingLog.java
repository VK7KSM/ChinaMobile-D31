package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class RollingLog {
    private final File directory;
    private final long maxBytes;
    private final int files;
    private boolean failed;

    RollingLog(File directory, long maxBytes, int files) {
        this.directory = directory;
        this.maxBytes = maxBytes;
        this.files = files;
    }

    synchronized boolean write(String text) {
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("log directory unavailable");
            String line = String.valueOf(text).replace('\n', ' ').replace('\r', ' ');
            if (line.length() > 1024) line = line.substring(0, 1024);
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            File active = file(0);
            if (active.length() + bytes.length > maxBytes) {
                File oldest = file(files - 1);
                if (oldest.exists() && !oldest.delete()) throw new IOException("log rotation delete failed");
                for (int i = files - 2; i >= 0; i--) {
                    File from = file(i);
                    if (from.exists() && !from.renameTo(file(i + 1))) throw new IOException("log rotation failed");
                }
            }
            try (FileOutputStream out = new FileOutputStream(active, true)) {
                out.write(bytes);
                out.flush();
            }
            failed = false;
            return true;
        } catch (IOException | SecurityException e) {
            failed = true;
            return false;
        }
    }

    synchronized boolean failed() { return failed; }
    private File file(int index) { return new File(directory, "runtime-" + index + ".log"); }
}
