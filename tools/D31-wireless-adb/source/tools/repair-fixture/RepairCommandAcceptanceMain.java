package net.elfradio.d31bootstrap;

import android.os.Build;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.repair.AndroidRepairPlatform;
import net.elfradio.d31bootstrap.repair.RepairPlan;

/** 独立验收jar；仅生成真实方案摘要、读取原件和有界持有现有维护锁。 */
public final class RepairCommandAcceptanceMain {
    public static void main(String[] args) {
        try {
            if (args.length < 3 || !args[1].matches("[a-f0-9]{64}") || !args[2].matches("[0-9]{1,10}") || Os.getuid() != 0
                    || Build.VERSION.SDK_INT != 23 || !"hct6735_66_m0".equals(Build.DEVICE)
                    || !"hct6737t_66_m0".equals(Build.MODEL)) throw new IOException("验收参数或D31环境不符");
            Os.umask(0077);
            int expectedVersion = Integer.parseInt(args[2]);
            if (expectedVersion < 96) throw new IOException("验收版本必须至少96");
            File apk = new File("/data/local/d31-remote/releases/" + args[1] + "/remote.apk");
            RemoteUpdatePlatform platform = new RemoteUpdatePlatform();
            JSONObject archive = platform.inspect(apk), installed = platform.current(), active = platform.activeArchive();
            for (JSONObject item : new JSONObject[]{archive, installed, active}) {
                if (item.getInt("versionCode") != expectedVersion || !args[1].equals(item.getString("sha256")))
                    throw new IOException("冻结APK、已安装副本及活动载荷必须匹配明确版本与摘要");
            }
            if (!RemoteUpdatePlatform.fullClient(apk)) throw new IOException("验收APK必须为完整制品");
            switch (args[0]) {
                case "snapshot":
                    if (args.length != 3) throw new IOException("快照参数无效");
                    System.out.println(snapshot(archive)); break;
                case "plan":
                    if (args.length != 4 || !args[3].matches("reject-[a-f0-9]{32}")) throw new IOException("需要全新拒绝测试任务号");
                    if (new File(RemoteRepairCommand.JOURNAL, args[3]).exists()) throw new IOException("测试任务已经存在");
                    System.out.println(plan(archive, args[3])); break;
                case "hold":
                    if (args.length != 3) throw new IOException("持锁参数无效");
                    try (RemoteMaintenance.Lease lease = RemoteMaintenance.acquire()) {
                        if (lease == null) throw new IOException("未取得真实维护锁");
                        RemoteMaintenance.requireRepairReady();
                        System.out.println("D31_REPAIR_LOCK_HELD_V1"); System.out.flush();
                        long until = SystemClock.elapsedRealtime() + 20000;
                        while (SystemClock.elapsedRealtime() < until) Thread.sleep(50);
                    }
                    System.out.println("D31_REPAIR_LOCK_RELEASED_V1"); break;
                default: throw new IOException("不支持的验收操作");
            }
            System.exit(0);
        } catch (Throwable failure) {
            try {
                System.out.println(new JSONObject().put("state", "FAIL").put("reason", failure.getClass().getSimpleName()));
            } catch (Throwable reporting) { System.out.println("{\"state\":\"FAIL\"}"); }
            System.exit(1);
        }
    }

    private static JSONObject plan(JSONObject archive, String task) throws Exception {
        JSONObject baseline = snapshot(archive), target = baseline.getJSONObject("target");
        String actual = target.getString("sha256");
        String mismatch = (actual.charAt(0) == '0' ? "1" : "0") + actual.substring(1);
        String destination = hash(("no-payload-" + task).getBytes(StandardCharsets.UTF_8));
        if (destination.equals(mismatch)) throw new IOException("测试摘要意外相同");
        RepairPlan plan = new RepairPlan(task, "production-original-mismatch", "v1", "D31", Build.FINGERPRINT,
                hash(baseline.toString().getBytes(StandardCharsets.UTF_8)), Collections.singletonList(new RepairPlan.Change(
                    "start", "system-support/start.sh", mismatch, target.getLong("bytes"), destination, 1,
                    "never-created", Collections.emptyList())), Collections.emptyList());
        String digest = plan.sha256();
        JSONObject submit = request("submit", task, digest).put("plan", plan.toJson());
        return new JSONObject().put("apk", archive.getString("path")).put("baseline", baseline)
                .put("submit", submit).put("step", request("step", task, digest))
                .put("query", request("query", task, digest)).put("expected_phase", "REJECTED");
    }
    private static JSONObject request(String operation, String task, String digest) throws Exception {
        return new JSONObject().put("operation", operation).put("task_id", task).put("plan_sha256", digest);
    }
    private static JSONObject snapshot(JSONObject archive) throws Exception {
        File repair = new File(RemoteMaintenance.ROOT, "repair.json");
        boolean windows = RemoteWindowsMaintenance.reserved();
        JSONObject value = new JSONObject().put("apk", archive).put("build", Build.FINGERPRINT).put("target", target())
                .put("repair", repair.exists() ? new JSONObject(RescueFiles.read(repair, 4096)) : JSONObject.NULL)
                .put("windows_reserved", windows);
        if (windows) value.put("windows_id", new JSONObject(RescueFiles.read(new File(RemoteMaintenance.ROOT, "windows.json"), 4096)).getString("id"));
        return value;
    }
    private static JSONObject target() throws Exception {
        File target = AndroidRepairPlatform.productionPaths().get("system-support/start.sh");
        if (target == null || !target.getPath().equals("/data/local/d31-system-support/start.sh")
                || !target.equals(target.getCanonicalFile())) throw new IOException("生产固定目标不符");
        StructStat pathBefore = Os.lstat(target.getPath());
        if (!OsConstants.S_ISREG(pathBefore.st_mode) || pathBefore.st_nlink != 1 || pathBefore.st_size > RepairPlan.MAX_FILE_BYTES)
            throw new IOException("生产目标类型或长度不符");
        Method getContext = Class.forName("android.os.SELinux").getMethod("getFileContext", String.class);
        String context = (String) getContext.invoke(null, target.getPath());
        if (context == null) throw new IOException("原件标签未确认");
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); long bytes = 0;
        FileDescriptor fd = Os.open(target.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
        try (FileInputStream input = new FileInputStream(fd)) {
            if (!same(pathBefore, Os.fstat(fd))) throw new IOException("目标打开期间变化");
            byte[] buffer = new byte[32768]; int count;
            while ((count = input.read(buffer)) != -1) {
                bytes += count; if (bytes > RepairPlan.MAX_FILE_BYTES) throw new IOException("目标长度变化");
                digest.update(buffer, 0, count);
            }
            if (!same(pathBefore, Os.fstat(fd)) || !same(pathBefore, Os.lstat(target.getPath()))
                    || bytes != pathBefore.st_size || !context.equals(getContext.invoke(null, target.getPath())))
                throw new IOException("原件读取期间变化");
        }
        return new JSONObject().put("path", target.getPath()).put("sha256", hex(digest.digest())).put("bytes", bytes)
                .put("device", pathBefore.st_dev).put("inode", pathBefore.st_ino).put("uid", pathBefore.st_uid)
                .put("gid", pathBefore.st_gid).put("mode", pathBefore.st_mode).put("modified", pathBefore.st_mtime)
                .put("changed", pathBefore.st_ctime).put("selinux", context);
    }
    private static boolean same(StructStat a, StructStat b) {
        return a.st_dev == b.st_dev && a.st_ino == b.st_ino && a.st_size == b.st_size && a.st_mode == b.st_mode
                && a.st_uid == b.st_uid && a.st_gid == b.st_gid && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime && a.st_nlink == b.st_nlink;
    }
    private static String hash(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    private RepairCommandAcceptanceMain() { }
}
