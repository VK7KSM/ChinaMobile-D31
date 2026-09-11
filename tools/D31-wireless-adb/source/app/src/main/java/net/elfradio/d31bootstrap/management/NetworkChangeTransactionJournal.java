package net.elfradio.d31bootstrap.management;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** 私有目录内追加带摘要的完整快照；尾部破损拒绝继续，不把旧快照冒充最新状态。 */
public final class NetworkChangeTransactionJournal implements NetworkChangeTransaction.Store {
    private static final int MAX_FRAME = 256 * 1024;
    private static final long MAX_BYTES = 8 * 1024 * 1024;
    private final File file;

    /** 调用方提供已创建、权限受控的canonical私有目录；本类不创建系统目录或改权限。 */
    public NetworkChangeTransactionJournal(File directory) throws IOException {
        if (directory == null || !directory.isDirectory()
                || !directory.getAbsoluteFile().equals(directory.getCanonicalFile()))
            throw new IOException("需要已核验的私有事务目录");
        file = new File(directory, "wifi-enabled.journal");
    }

    @Override public Session lock() throws Exception { return new Locked(); }

    private final class Locked implements Session {
        private final RandomAccessFile stream;
        private final FileLock lock;

        Locked() throws Exception {
            if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) throw new IOException("事务日志不能为链接");
            stream = new RandomAccessFile(file, "rw");
            try {
                lock = stream.getChannel().tryLock();
                if (lock == null) throw new IOException("事务日志被占用");
            } catch (IOException | OverlappingFileLockException error) {
                stream.close(); throw new IOException("事务日志锁未取得", error);
            }
        }

        @Override public JSONObject read() throws Exception {
            long length = stream.length();
            if (length > MAX_BYTES) throw new IOException("事务日志超出容量");
            stream.seek(0);
            JSONObject latest = new JSONObject().put("schema_version", 1).put("records", new JSONObject());
            while (stream.getFilePointer() < length) {
                if (length - stream.getFilePointer() < 4) throw new IOException("事务日志尾部不完整");
                int size = stream.readInt();
                if (size <= 0 || size > MAX_FRAME || length - stream.getFilePointer() < size + 32L)
                    throw new IOException("事务日志记录不完整或超限");
                byte[] body = new byte[size], digest = new byte[32];
                stream.readFully(body); stream.readFully(digest);
                if (!MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(body)))
                    throw new IOException("事务日志摘要不符");
                latest = new JSONObject(new String(body, StandardCharsets.UTF_8));
            }
            return latest;
        }

        @Override public void save(JSONObject records) throws Exception {
            read(); // 即使前次append失败，也不得越过破损尾部继续写入。
            byte[] body = new JSONObject().put("schema_version", 1).put("records", records)
                    .toString().getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_FRAME || stream.length() + body.length + 36L > MAX_BYTES)
                throw new IOException("事务日志容量不足，原件保留");
            stream.seek(stream.length());
            stream.writeInt(body.length); stream.write(body);
            stream.write(MessageDigest.getInstance("SHA-256").digest(body));
            stream.getFD().sync();
        }

        @Override public void close() throws Exception {
            try { lock.release(); } finally { stream.close(); }
        }
    }
}
