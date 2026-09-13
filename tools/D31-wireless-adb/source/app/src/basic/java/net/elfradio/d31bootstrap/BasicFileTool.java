package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import android.util.Base64;
import org.json.JSONObject;

/** 通过既有8765任务调用，分块读写不引入另一套HTTP或云传输协议。 */
public final class BasicFileTool {
    static final int CHUNK_BYTES = 3072;
    interface Publisher { void publish(File stage, File target) throws Exception; }

    public static void main(String[] args) throws Exception {
        JSONObject result;
        if (args.length == 4 && "read".equals(args[0])) {
            File file = new File(args[1]);
            byte[] bytes = read(file, Long.parseLong(args[2]), Integer.parseInt(args[3]));
            result = new JSONObject().put("bytes", bytes.length).put("size", file.length())
                    .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
        } else if (args.length == 5 && "write".equals(args[0])) {
            byte[] bytes = Base64.decode(args[3], Base64.NO_WRAP);
            long size = write(new File(args[1]), Long.parseLong(args[2]), bytes, args[4]);
            result = new JSONObject().put("size", size).put("chunk_sha256", digest(bytes));
        } else if (args.length == 4 && "commit".equals(args[0])) {
            commit(new File(args[1]), new File(args[2]), args[3]);
            result = new JSONObject().put("committed", true).put("sha256", args[3]);
        } else if (args.length == 2 && "hash".equals(args[0])) {
            File file = new File(args[1]);
            result = new JSONObject().put("size", file.length()).put("sha256", RescueFiles.sha256(file));
        } else throw new IllegalArgumentException("文件操作参数无效");
        System.out.println(result.toString());
    }

    static byte[] read(File file, long offset, int count) throws Exception {
        if (offset < 0 || count < 1 || count > CHUNK_BYTES || !file.isFile())
            throw new IOException("读取范围或文件无效");
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (offset > input.length()) throw new IOException("读取偏移超过文件末尾");
            byte[] bytes = new byte[(int) Math.min(count, input.length() - offset)];
            input.seek(offset);
            input.readFully(bytes);
            return bytes;
        }
    }

    static long write(File stage, long offset, byte[] bytes, String expected) throws Exception {
        validateStage(stage);
        if (offset < 0 || offset > Long.MAX_VALUE - bytes.length || bytes.length > CHUNK_BYTES
                || bytes.length == 0 || !digest(bytes).equalsIgnoreCase(expected))
            throw new IOException("写入范围或分块哈希无效");
        try (RandomAccessFile owner = new RandomAccessFile(lockFile(stage), "rw");
             FileLock lock = owner.getChannel().tryLock()) {
            if (lock == null) throw new IOException("暂存文件被占用");
            validateStage(stage);
            try (RandomAccessFile output = new RandomAccessFile(stage, "rw")) {
                if (offset < output.length()) {
                    if (offset + bytes.length > output.length()) throw new IOException("重试块跨越现有末尾");
                    byte[] old = new byte[bytes.length];
                    output.seek(offset); output.readFully(old);
                    if (!java.util.Arrays.equals(old, bytes)) throw new IOException("重试内容冲突，未覆盖");
                } else {
                    if (offset != output.length()) throw new IOException("拒绝跳过缺失分块");
                    output.seek(offset); output.write(bytes); output.getFD().sync();
                }
                return output.length();
            }
        }
    }

    static void commit(File stage, File target, String expected) throws Exception {
        commit(stage, target, expected, (source, destination) ->
                android.system.Os.link(source.getPath(), destination.getPath()));
    }

    static void commit(File stage, File target, String expected, Publisher publisher) throws Exception {
        validateStage(stage);
        if (!stage.isFile() || target.exists() || !target.getAbsoluteFile().equals(target.getCanonicalFile())
                || !stage.getCanonicalFile().getParentFile().equals(target.getCanonicalFile().getParentFile()))
            throw new IOException("只能提交到同目录且尚不存在的真实路径");
        try (RandomAccessFile owner = new RandomAccessFile(lockFile(stage), "rw");
             FileLock lock = owner.getChannel().tryLock()) {
            if (lock == null) throw new IOException("暂存文件被占用");
            validateStage(stage);
            if (!RescueFiles.sha256(stage).equalsIgnoreCase(expected))
                throw new IOException("完整哈希不匹配，未提交");
            // link对已存在目标原子拒绝，避免检查后rename覆盖并发创建的文件。
            publisher.publish(stage, target);
            if (!stage.delete()) throw new IOException("目标已提交，但暂存链接未移除；先核对目标哈希");
        }
    }

    private static File lockFile(File stage) throws Exception {
        File file = new File(stage.getPath() + ".lock");
        if (!file.getAbsoluteFile().equals(file.getCanonicalFile()))
            throw new IOException("锁文件必须为真实路径");
        // 独立锁避免Windows禁止其他句柄读取数据；锁文件保留，不删除或替换锁节点。
        return file;
    }

    private static void validateStage(File file) throws Exception {
        if (!file.isAbsolute() || !file.getName().startsWith(".d31-basic-part-")
                || file.getName().endsWith(".lock")
                || !file.getAbsoluteFile().equals(file.getCanonicalFile()))
            throw new IOException("必须使用真实绝对路径及独立.d31-basic-part-暂存文件");
    }

    static String digest(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    private BasicFileTool() { }
}
