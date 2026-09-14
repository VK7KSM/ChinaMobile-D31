package net.elfradio.d31bootstrap;

import android.system.Os;
import android.system.StructStat;
import java.io.File;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** 只在新建测试目录验证真实API23文件行为，测试原件和结果保留。 */
public final class RemoteFileProtocolCheck {
    private final File root;
    private int count;

    private RemoteFileProtocolCheck(File root) { this.root = root; }

    private JSONObject run(String action, File source, File target, boolean overwrite) throws Exception {
        File job = new File(root, "job-" + (++count));
        require(job.mkdir(), "无法建立独立任务目录");
        JSONObject request = new JSONObject().put("action", action).put("path", source.getPath());
        if (target != null) request.put("target", target.getPath()).put("overwrite", overwrite);
        RescueFiles.write(new File(job, "request.json"), request.toString());
        JSONObject result = FileOperations.run(job, request);
        RescueFiles.write(new File(job, "result.json"), result.toString());
        return new JSONObject(result.getString("output"));
    }

    private static void require(boolean ok, String message) throws IOException {
        if (!ok) throw new IOException(message);
    }

    private void verify() throws Exception {
        File source = new File(root, "source"), target = new File(root, "target");
        require(source.mkdir() && target.mkdir(), "无法创建合成源和目标");
        File original = new File(source, "literal ' $(false).txt");
        RescueFiles.write(original, "测试原始内容\n");
        Os.chmod(original.getPath(), 0640);
        StructStat stat = Os.lstat(original.getPath());
        JSONObject listing = run("list", source, null, false);
        JSONArray entries = listing.getJSONArray("entries");
        require(entries.length() == 1, "列表数量不符");
        JSONObject entry = entries.getJSONObject(0);
        require(entry.getString("name").equals(original.getName()), "文件名被错误解释");
        require((entry.getInt("mode") & 07777) == (stat.st_mode & 07777)
                && entry.getInt("uid") == stat.st_uid && entry.getInt("gid") == stat.st_gid,
                "权限或属主不是实际lstat值");
        require(entry.getLong("modified_ms") == original.lastModified(), "修改时间合同不符");

        File overwritten = new File(target, "existing.txt");
        RescueFiles.write(overwritten, "原目标内容\n");
        String oldHash = RescueFiles.sha256(overwritten);
        run("copy", original, overwritten, true);
        require(RescueFiles.sha256(original).equals(RescueFiles.sha256(overwritten)), "覆盖后内容错误");
        File[] preserved = target.listFiles();
        boolean backedUp = false;
        if (preserved != null) for (File file : preserved) {
            if (!file.equals(overwritten) && file.isFile() && oldHash.equals(RescueFiles.sha256(file))) backedUp = true;
        }
        require(backedUp, "覆盖前原目标未备份");

        File snapshotJob = new File(root, "snapshot-job"), snapshot = new File(root, "snapshot.bin");
        require(snapshotJob.mkdir(), "无法建立快照任务目录");
        JSONObject captured = FileSnapshot.run(snapshotJob, new JSONObject().put("source", original.getPath())
                .put("target", snapshot.getPath()).put("uid", Os.getuid()));
        require(captured.getString("sha256").equals(RescueFiles.sha256(original))
                && FileSnapshot.recover(snapshotJob) != null, "文件快照或恢复核验失败");

        File outside = new File(root, "outside"), doomed = new File(root, "doomed");
        require(outside.mkdir() && doomed.mkdir(), "无法创建链接验证目录");
        File sentinel = new File(outside, "sentinel.txt");
        RescueFiles.write(sentinel, "此文件不可被递归删除\n");
        String sentinelHash = RescueFiles.sha256(sentinel);
        Os.symlink(outside.getPath(), new File(doomed, "directory-link").getPath());
        Os.symlink(new File(outside, "missing").getPath(), new File(doomed, "broken-link").getPath());
        require(new File(doomed, "child").mkdir(), "无法创建子目录");
        RescueFiles.write(new File(doomed, "child/file.txt"), "待删除合成内容\n");
        run("delete", doomed, null, false);
        require(!doomed.exists() && sentinelHash.equals(RescueFiles.sha256(sentinel)), "递归删除越过符号链接");

        File broken = new File(root, "broken-link");
        Os.symlink(new File(outside, "missing").getPath(), broken.getPath());
        run("delete", broken, null, false);
        try {
            Os.lstat(broken.getPath());
            throw new IOException("坏链接未删除");
        } catch (android.system.ErrnoException expected) {
            require(expected.errno == android.system.OsConstants.ENOENT, "删除链接后出现未知错误");
        }
        require(sentinelHash.equals(RescueFiles.sha256(sentinel)) && original.isFile(), "非目标原件发生变化");
        RescueFiles.write(new File(root, "result.json"), new JSONObject().put("passed", true)
                .put("version_code", BuildConfig.VERSION_CODE).put("tasks", count)
                .put("real_metadata", true).put("overwrite_backup", true)
                .put("file_snapshot_recovery", true)
                .put("recursive_delete_no_follow", true).put("broken_link_delete", true)
                .put("scope", "仅本次新建合成目录").toString());
        System.out.println("FILE_PROTOCOL_CHECK_OK");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || !"hct6737t_66_m0".equals(android.os.Build.MODEL))
            throw new IOException("仅用于D31本地文件验收");
        File root = new File(args[0]);
        if (!root.getCanonicalPath().equals(root.getAbsolutePath())
                || !root.getPath().matches("/data/local/tmp/d31-file-protocol-[a-z0-9-]+") || !root.mkdir())
            throw new IOException("需要新的独立验收目录");
        try { new RemoteFileProtocolCheck(root).verify(); }
        catch (Exception error) {
            RescueFiles.write(new File(root, "failure.json"), new JSONObject().put("passed", false)
                    .put("error", error.toString()).toString());
            throw error;
        }
    }
}
