package net.elfradio.d31bootstrap;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class RescueFiles {
    private RescueFiles() { }

    static synchronized String read(File file, int limit) throws IOException {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096];
            int n;
            while (out.size() < limit && (n = in.read(bytes, 0,
                    Math.min(bytes.length, limit - out.size()))) != -1) out.write(bytes, 0, n);
            if (out.size() == limit && in.read() != -1) throw new IOException("File size limit exceeded");
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @android.annotation.SuppressLint("NewApi")
    static synchronized void write(File file, String value) throws IOException {
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (File.separatorChar == '\\') {
            // Windows离线测试使用其原子替换；Android仍用同文件系统rename。
            java.nio.file.Files.move(temp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else if (!temp.renameTo(file)) throw new IOException("Atomic rename failed: " + file);
    }

    static String sha256(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) hash.update(buffer, 0, n);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : hash.digest()) result.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return result.toString();
    }

    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
