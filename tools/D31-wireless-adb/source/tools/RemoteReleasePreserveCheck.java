package net.elfradio.d31bootstrap;

import android.os.Build;
import android.system.Os;
import android.system.StructStat;
import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/** 独立候选helper：参数为预期版本号和APK摘要；调用真实归档，不安装或切换核心。 */
public final class RemoteReleasePreserveCheck {
    private static void require(boolean value) throws IOException {
        if (!value) throw new IOException("CHECK_REJECTED");
    }

    private static StructStat stat(File file, boolean directory, int permissions) throws Exception {
        require(file.getAbsoluteFile().equals(file.getCanonicalFile()));
        StructStat value = Os.lstat(file.getPath());
        require((value.st_mode & 0170000) == (directory ? 0040000 : 0100000));
        if (!directory) require(value.st_nlink == 1);
        if (permissions >= 0) require(value.st_uid == 0 && value.st_gid == 0
                && (value.st_mode & 07777) == permissions);
        return value;
    }

    private static boolean unchanged(StructStat before, StructStat after) {
        return before.st_dev == after.st_dev && before.st_ino == after.st_ino
                && before.st_size == after.st_size && before.st_mtime == after.st_mtime
                && before.st_mode == after.st_mode && before.st_uid == after.st_uid
                && before.st_gid == after.st_gid && before.st_nlink == after.st_nlink;
    }

    public static void main(String[] args) {
        JSONObject result = new JSONObject();
        String stage = "IDENTITY";
        boolean passed = false, acquired = false, released = false, preserveAttempted = false;
        try {
            require(args != null && args.length == 2 && args[0].matches("[1-9][0-9]{0,8}")
                    && args[1].matches("[a-f0-9]{64}"));
            int version = Integer.parseInt(args[0]);
            require(Os.getuid() == 0 && Build.VERSION.SDK_INT == 23
                    && "hct6735_66_m0".equals(Build.DEVICE) && "hct6737t_66_m0".equals(Build.MODEL));
            stage = "MAINTENANCE_ACQUIRE";
            try (RemoteMaintenance.Lease lease = RemoteMaintenance.acquire()) {
                require(lease != null); acquired = true;
                stage = "MAINTENANCE_RESERVATION";
                RemoteMaintenance.requireUnreserved();
                stage = "INSTALLED_BEFORE";
                RemoteUpdatePlatform platform = new RemoteUpdatePlatform();
                JSONObject before = platform.current();
                require(before.getInt("versionCode") == version && args[1].equals(before.getString("sha256")));
                File source = new File(before.getString("path"));
                StructStat original = stat(source, false, -1);
                require(RemoteUpdatePlatform.fullClient(source));
                File releases = new File(RemoteUpdatePlatform.ROOT, "releases");
                File folder = new File(releases, before.getString("sha256"));
                File apk = new File(folder, "remote.apk");
                stage = "PRESERVE"; preserveAttempted = true;
                JSONObject saved = platform.preserve(source);
                stage = "ARCHIVE_VERIFY";
                require(apk.getAbsolutePath().equals(saved.getString("path"))
                        && RemoteUpdatePolicy.matches(before, saved));
                stat(releases, true, 0700); stat(folder, true, 0700); stat(apk, false, 0600);
                require(RemoteUpdatePolicy.matches(before, platform.inspect(apk)));
                stage = "INSTALLED_AFTER";
                JSONObject after = platform.current();
                require(before.getString("path").equals(after.getString("path"))
                        && RemoteUpdatePolicy.matches(before, after)
                        && RemoteUpdatePolicy.matches(before, platform.inspect(source))
                        && unchanged(original, stat(source, false, -1)));
                result.put("versionCode", version).put("apkSha256", before.getString("sha256"))
                        .put("apkBytes", before.getLong("size")).put("installedUnchanged", true)
                        .put("archiveMetadataMatched", true).put("rootOwnedNonLinks", true)
                        .put("releasesMode", "0700").put("hashDirectoryMode", "0700").put("apkMode", "0600");
                stage = "MAINTENANCE_RELEASE";
            }
            released = true; passed = true; stage = "COMPLETE";
        } catch (Exception failure) {
            // 不输出异常文本、堆栈、设备标识或安装路径；失败阶段不冒充未执行的后续检查。
        }
        try {
            result.put("schemaVersion", 1).put("kind", "REMOTE_RELEASE_PRESERVE_CHECK")
                    .put("ok", passed).put("stage", stage).put("preserveAttempted", preserveAttempted)
                    .put("maintenanceAcquired", acquired)
                    .put("maintenanceReleased", acquired ? (released ? Boolean.TRUE : JSONObject.NULL) : Boolean.FALSE);
        } catch (Exception impossible) { passed = false; }
        System.out.println(result.toString());
        System.exit(passed ? 0 : 1);
    }

    private RemoteReleasePreserveCheck() { }
}
