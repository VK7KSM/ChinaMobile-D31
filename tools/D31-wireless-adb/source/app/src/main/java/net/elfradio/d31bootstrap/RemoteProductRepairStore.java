package net.elfradio.d31bootstrap;

import java.io.*;
import org.json.JSONObject;

/** 原子持久记录及公共维护预留；原号保留，不清理历史终态。 */
final class RemoteProductRepairStore implements RemoteProductRepair.Store {
    private final File root, maintenance;
    RemoteProductRepairStore(File root, File maintenance) { this.root = root; this.maintenance = maintenance; }
    private File path(String id) throws Exception {
        RemoteProductRepair.validId(id);
        File path = new File(root, id + ".json");
        safe(root); safe(path); safe(new File(path.getPath() + ".tmp")); return path;
    }
    private static void safe(File file) throws Exception {
        if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) throw new IOException("修复记录不能使用链接");
    }
    @Override public JSONObject load(String id) throws Exception {
        File path = path(id);
        if (!RemoteMaintenance.existsNoFollow(path)) return null;
        JSONObject value = new JSONObject(RescueFiles.read(path, 32768));
        if (!id.equals(value.getJSONObject("plan").getString("operation_id"))) throw new IOException("存档原号不符");
        return value;
    }
    @Override public void save(String id, JSONObject record) throws Exception {
        File path = path(id);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("修复目录不可用");
        String text = record.toString();
        if (text.getBytes("UTF-8").length > 32768) throw new IOException("修复记录超限");
        RescueFiles.write(path, text); sync(root); sync(root.getParentFile());
    }
    @Override public void reserve(String id, String digest) throws Exception {
        RemoteMaintenance.reserve(maintenance, "product-" + id, digest); sync(maintenance);
    }
    @Override public void release(String id, String digest) throws Exception {
        RemoteMaintenance.release(maintenance, "product-" + id, digest); sync(maintenance);
    }
    private static void sync(File directory) throws Exception {
        if (File.separatorChar == '\\') return;
        safe(directory);
        if (!directory.isDirectory()) throw new IOException("同步目标不是目录");
        FileDescriptor fd = android.system.Os.open(directory.getPath(), android.system.OsConstants.O_RDONLY, 0);
        try { android.system.Os.fsync(fd); } finally { android.system.Os.close(fd); }
    }
}
