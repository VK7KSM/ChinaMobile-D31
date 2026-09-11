package net.elfradio.d31bootstrap;

import android.content.Context;
import net.elfradio.d31bootstrap.management.SystemManagement;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;

/** 新管理任务沿用8765执行与回执；同号中断任务只查询，不重复修改。 */
public final class RemoteBusinessCommand {
    static final File ROOT = new File(RemoteUpdatePlatform.RUNTIME, "business");
    interface Operation {
        JSONObject run(SystemManagement.Control control) throws Exception;
    }

    static String command(String apk, String id, String type, JSONObject params) throws Exception {
        validateId(id);
        if (apk == null || (!apk.matches("/data/local/d31-remote/releases/[a-f0-9]{64}/remote\\.apk")
                && !apk.equals("/system/priv-app/D31ElfRemote/D31ElfRemote.apk")))
            throw new IOException("管理执行载荷路径无效");
        JSONObject request = request(type, params);
        ensure(ROOT);
        android.system.Os.chmod(ROOT.getPath(), 0700);
        File file = new File(ROOT, id + ".request.json");
        if (file.exists()) {
            checkPath(file);
            if (!RemoteProtocol.sameJson(request, new JSONObject(RescueFiles.read(file, 16000))))
                throw new IOException("管理任务编号对应的参数已经变化");
        } else {
            RescueFiles.write(file, request.toString());
            android.system.Os.chmod(file.getPath(), 0600);
        }
        return "CLASSPATH=" + RescueFiles.quote(apk)
                + " /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteBusinessCommand "
                + RescueFiles.quote(id);
    }

    private static JSONObject request(String type, JSONObject params) throws Exception {
        return new JSONObject().put("type", type).put("params", SystemManagement.validate(type, params));
    }

    /** 不抢执行进程持有的维护锁；标记独立于任务目录，覆盖已入队但尚未启动的取消。 */
    static void cancel(File root, String id) throws Exception {
        validateId(id);
        ensure(root);
        File marker = new File(root, id + ".cancel");
        checkPath(marker);
        if (!marker.exists()) RescueFiles.write(marker, new JSONObject().put("id", id)
                .put("cancel_requested", true).toString());
    }

    public static void main(String[] args) {
        try {
            if (android.system.Os.getuid() != 0 || args.length != 1
                    || android.os.Build.VERSION.SDK_INT != 23
                    || !"hct6735_66_m0".equals(android.os.Build.DEVICE))
                throw new IOException("管理任务执行身份或设备不符");
            validateId(args[0]);
            File input = new File(ROOT, args[0] + ".request.json");
            checkPath(input);
            JSONObject request = new JSONObject(RescueFiles.read(input, 16000));
            try (RemoteMaintenance.Lease lease = RemoteMaintenance.acquire()) {
                if (lease == null) throw new IOException("另一维护操作正在进行");
                RemoteMaintenance.requireUnreserved();
                final Context context = context();
                JSONObject result = execute(ROOT, args[0], request.getString("type"),
                        request.getJSONObject("params"), control -> SystemManagement.execute(context,
                                request.getString("type"), request.getJSONObject("params"), control));
                System.out.println(result.getJSONObject("snapshot").toString());
                System.exit(result.getBoolean("ok") ? 0 : 1);
            }
        } catch (Exception failure) {
            System.err.println("管理任务未完成：" + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    static JSONObject execute(File root, String id, String type, JSONObject params, Operation operation) throws Exception {
        validateId(id);
        JSONObject normalized = request(type, params);
        ensure(root);
        File job = new File(root, id);
        checkPath(job);
        if (!job.mkdir()) {
            if (!job.isDirectory()) throw new IOException("管理任务目录不可用");
            File previous = new File(job, "request.json");
            checkPath(previous);
            if (!RemoteProtocol.sameJson(normalized, new JSONObject(RescueFiles.read(previous, 16000))))
                throw new IOException("管理任务参数冲突");
            return readResult(root, id, type, params);
        }
        RescueFiles.write(new File(job, "request.json"), normalized.toString());
        SystemManagement.Control control = new SystemManagement.Control() {
            public void check() throws Exception {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("管理任务已中断");
                File marker = new File(root, id + ".cancel");
                checkPath(marker);
                if (marker.exists()) throw new InterruptedException("管理任务已取消");
            }
            public void before(JSONObject original) throws Exception {
                check();
                File file = new File(job, "original.json");
                if (file.exists()) throw new IOException("禁止覆盖管理原像");
                RescueFiles.write(file, original.toString());
            }
        };
        JSONObject snapshot;
        boolean ok;
        try {
            control.check();
            snapshot = operation.run(control);
            validateSnapshot(normalized.getJSONObject("params"), snapshot);
            ok = true;
        } catch (Exception failure) {
            snapshot = failure instanceof SystemManagement.Failure
                    ? new JSONObject(((SystemManagement.Failure) failure).result.toString()) : new JSONObject();
            snapshot.put("applied", false).put("failure", failure.getClass().getSimpleName());
            ok = false;
        }
        JSONObject result = new JSONObject().put("id", id).put("type", type).put("ok", ok)
                .put("snapshot", snapshot);
        RescueFiles.write(new File(job, "result.json"), result.toString());
        return result;
    }

    static JSONObject readResult(File root, String id, String type, JSONObject params) throws Exception {
        validateId(id);
        JSONObject normalized = request(type, params);
        File previous = new File(new File(root, id), "request.json");
        checkPath(previous);
        JSONObject recorded = new JSONObject(RescueFiles.read(previous, 16000));
        if (!RemoteProtocol.sameJson(normalized, request(recorded.getString("type"), recorded.getJSONObject("params"))))
            throw new IOException("管理回执对应的完整请求不符");
        File file = new File(new File(root, id), "result.json");
        checkPath(file);
        JSONObject result = new JSONObject(RescueFiles.read(file, 120000));
        if (!id.equals(result.getString("id")) || !type.equals(result.getString("type")))
            throw new IOException("管理回执与任务不符");
        if (result.getBoolean("ok")) validateSnapshot(normalized.getJSONObject("params"), result.getJSONObject("snapshot"));
        return result;
    }

    private static void validateSnapshot(JSONObject params, JSONObject snapshot) throws Exception {
        if (!snapshot.optBoolean("ok") || !params.getString("group").equals(snapshot.optString("group"))
                || !(snapshot.opt("sampled_at") instanceof Number)
                || snapshot.optLong("sampled_at") <= 0
                || ("set".equals(params.getString("action")) && !snapshot.optBoolean("applied")))
            throw new IOException("缺少管理任务实际回读证据");
    }

    private static void validateId(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IOException("管理任务编号无效");
    }
    private static void ensure(File root) throws Exception {
        checkPath(root);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("管理目录不可用");
    }
    private static void checkPath(File file) throws IOException {
        if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) throw new IOException("管理路径不能为链接");
    }
    private static Context context() throws Exception {
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
        Class<?> owner = Class.forName("android.app.ActivityThread");
        Object thread = owner.getMethod("systemMain").invoke(null);
        return (Context) owner.getMethod("getSystemContext").invoke(thread);
    }
    private RemoteBusinessCommand() { }
}
