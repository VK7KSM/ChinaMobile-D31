package net.elfradio.d31bootstrap.repair;

import java.io.File;
import java.util.*;
import org.json.JSONObject;

/** 生产与离线测试共用文件事务编排，系统调用封装不暴露给命令请求。 */
interface RepairFileIo {
    final class Metadata {
        final int uid, gid, mode;
        final long modified;
        final String context;
        Metadata(int uid, int gid, int mode, long modified, String context) {
            if (uid < 0 || gid < 0 || mode < 0 || mode > 0777 || modified < 0
                    || context == null || !context.matches("[A-Za-z0-9_:.,-]{1,255}"))
                throw new IllegalArgumentException("普通文件元数据无效或含特殊权限");
            this.uid = uid; this.gid = gid; this.mode = mode; this.modified = modified; this.context = context;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("uid", uid).put("gid", gid).put("mode", mode)
                    .put("modified", modified).put("selinux", context);
        }
        static Metadata parse(JSONObject json) throws Exception {
            return new Metadata(json.getInt("uid"), json.getInt("gid"), json.getInt("mode"),
                    json.getLong("modified"), json.getString("selinux"));
        }
        boolean same(Metadata other) {
            return other != null && uid == other.uid && gid == other.gid && mode == other.mode
                    && modified == other.modified && context.equals(other.context);
        }
    }

    final class Snapshot {
        final String hash, identity;
        final long bytes;
        final Metadata metadata;
        Snapshot(String hash, long bytes, String identity, Metadata metadata) {
            this.hash = RepairPlan.hash(hash); this.bytes = RepairPlan.size(bytes);
            this.identity = identity; this.metadata = metadata;
        }
        boolean same(Snapshot other) {
            return other != null && identity.equals(other.identity) && content(other.hash, other.bytes) && metadata.same(other.metadata);
        }
        boolean content(String sha, long length) { return hash.equals(sha) && bytes == length; }
        JSONObject json() throws Exception {
            return new JSONObject().put("sha256", hash).put("bytes", bytes).put("identity", identity).put("metadata", metadata.json());
        }
        static Snapshot parse(JSONObject json) throws Exception {
            return new Snapshot(json.getString("sha256"), json.getLong("bytes"), json.getString("identity"),
                    Metadata.parse(json.getJSONObject("metadata")));
        }
    }

    void environment() throws Exception;
    String build();
    void privateDirectory(File directory) throws Exception;
    void trustedDirectory(File directory) throws Exception;
    long available(File directory) throws Exception;
    Snapshot read(File file) throws Exception;
    void copyNew(File source, File destination, Snapshot expected) throws Exception;
    void writeNew(File destination, byte[] bytes) throws Exception;
    byte[] readRecord(File file) throws Exception;
    void replace(File destination, File content, Snapshot expectedTarget, Snapshot expectedContent, Metadata metadata) throws Exception;
}
