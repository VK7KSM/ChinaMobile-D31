package net.elfradio.d31bootstrap.repair;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

final class RepairFiles {
    static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[32768]; int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        return hex(digest.digest());
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    static byte[] read(File file, int limit) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = input.read(bytes)) != -1) {
                if (output.size() + count > limit) throw new IOException("事务记录超过上限");
                output.write(bytes, 0, count);
            }
            return output.toByteArray();
        }
    }

    static String text(File file) throws IOException {
        return new String(read(file, 128 * 1024), StandardCharsets.UTF_8);
    }

    static void mkdir(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("事务目录不可用");
        if (!directory.getAbsoluteFile().equals(directory.getCanonicalFile()))
            throw new IOException("事务目录不能经过链接或相对路径");
    }

    static void writeNew(File target, byte[] bytes) throws IOException {
        if (target.exists()) throw new IOException("事务记录已存在，禁止覆盖");
        File temporary = new File(target.getParentFile(), target.getName() + ".pending-" + UUID.randomUUID());
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes); output.getFD().sync();
        }
        // 调用方始终持有独立事务锁；中断临时文件保留，不当作已提交记录。
        if (target.exists() || !temporary.renameTo(target)) throw new IOException("事务记录提交失败");
    }

    static boolean matches(File file, String hash, long size) throws Exception {
        return file.isFile() && file.getAbsoluteFile().equals(file.getCanonicalFile())
                && file.length() == size && sha256(file).equals(hash);
    }

    private RepairFiles() { }
}
