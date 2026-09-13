package net.elfradio.d31bootstrap.diagnostics.collection;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 可注入的只读边界；实现必须拒绝祖先链接、叶链接跟随及无限目录列表。 */
public interface CollectionAccess {
    Stat lstat(String absolutePath) throws IOException;
    String readLink(String absolutePath) throws IOException;
    Handle openRegular(String absolutePath, Stat expected) throws IOException;
    Listing list(String absolutePath, Stat expected, int maximumNames, long maximumBytes, long timeoutMs) throws IOException;

    interface Clock {
        long wallTimeMillis();
        long elapsedRealtimeMillis();
    }

    interface Handle extends Closeable {
        Stat stat() throws IOException;
        int read(byte[] bytes, int offset, int length) throws IOException;
    }

    /** 不暴露操作系统错误原文，避免路径、账号或命令输出泄漏到错误消息。 */
    final class Failure extends IOException {
        public final String code;
        public Failure(String code) { super(code); this.code = code; }
    }

    final class Stat {
        public final String type;
        public final long device, inode, size, modified, changed, uid, gid;
        public final int mode;
        public Stat(String type, long device, long inode, long size, long modified, long changed,
                    int mode, long uid, long gid) {
            if (!type.matches("file|directory|symlink|block|unsupported") || size < 0
                    || mode < 0 || mode > 07777 || uid < 0 || gid < 0
                    || uid > 4294967295L || gid > 4294967295L) throw new IllegalArgumentException("INVALID_STAT");
            this.type = type; this.device = device; this.inode = inode; this.size = size;
            this.modified = modified; this.changed = changed; this.mode = mode; this.uid = uid; this.gid = gid;
        }
        public boolean same(Stat other) {
            return other != null && type.equals(other.type) && device == other.device && inode == other.inode
                    && size == other.size && modified == other.modified && changed == other.changed
                    && mode == other.mode && uid == other.uid && gid == other.gid;
        }
    }

    /** names仅含直接子名称；complete只能在真实枚举结束且无失败时为真。 */
    final class Listing {
        public final List<String> names;
        public final boolean complete;
        public final String reason;
        public final long bytesRead;
        public Listing(List<String> names, boolean complete, String reason, long bytesRead) {
            this.names = Collections.unmodifiableList(new ArrayList<String>(names));
            this.complete = complete;
            this.reason = reason;
            this.bytesRead = bytesRead;
        }
    }
}
