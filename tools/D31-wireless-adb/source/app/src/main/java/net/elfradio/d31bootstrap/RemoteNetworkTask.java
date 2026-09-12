package net.elfradio.d31bootstrap;

import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.IOException;
import net.elfradio.d31bootstrap.management.NetworkAndroidPlatform;
import org.json.JSONObject;

/** 一个云任务对应一个持久网络事务；普通命令完成后仍从原事务取终态。 */
public final class RemoteNetworkTask {
    static final File ROOT = new File(RemoteUpdatePlatform.RUNTIME, "network-tasks");

    static boolean matches(JSONObject task) {
        JSONObject p = task.optJSONObject("params");
        return "system_config".equals(task.optString("type")) && p != null
                && (p.has("network_transaction") || ("wifi".equals(p.optString("group"))
                && "set".equals(p.optString("action")) && "enabled".equals(p.optString("key"))));
    }

    static JSONObject validate(String device, JSONObject task) throws Exception {
        if (device == null || !device.matches("[A-Za-z0-9_-]{1,128}") || !matches(task))
            throw new IOException("NETWORK_TASK_INVALID");
        String id = task.getString("id"), local = RemoteProtocol.localJobId(device, id);
        JSONObject p = task.getJSONObject("params"), n = p.getJSONObject("network_transaction");
        int optional = (p.has("package") ? 1 : 0) + (p.has("offset") ? 1 : 0);
        if (p.length() != 5 + optional || (p.has("package") && !"".equals(p.opt("package")))
                || (p.has("offset") && !integer(p.opt("offset"), 0, 0))
                || !"wifi".equals(p.opt("group")) || !"set".equals(p.opt("action"))
                || !"enabled".equals(p.opt("key")) || !(p.opt("value") instanceof Boolean)
                || n.length() != 3 || !integer(n.opt("version"), 1, 1)
                || !integer(n.opt("confirm_within_ms"), 10000, 120000)
                || !(n.opt("apk_sha256") instanceof String)
                || !n.getString("apk_sha256").matches("[a-f0-9]{64}")
                || !(task.opt("request_digest") instanceof String)
                || !task.getString("request_digest").matches("[a-f0-9]{64}"))
            throw new IOException("NETWORK_TASK_PARAMS_INVALID");
        return new JSONObject().put("version", 1).put("device_id", device).put("task_id", id)
                .put("local_task_id", local).put("request_digest", task.getString("request_digest"))
                .put("params", new JSONObject(p.toString()));
    }

    private static boolean integer(Object value, long min, long max) {
        if (!(value instanceof Integer || value instanceof Long)) return false;
        long number = ((Number) value).longValue();
        return number >= min && number <= max;
    }

    static String command(String apk, String device, JSONObject task) throws Exception {
        JSONObject intent = validate(device, task);
        String hash = hash(intent), id = intent.getString("local_task_id");
        if (!("/data/local/d31-remote/releases/" + hash + "/remote.apk").equals(apk))
            throw new IOException("NETWORK_TASK_APK_MISMATCH");
        ensureRoot();
        File file = file(id, ".request.json");
        if (file.exists()) {
            if (!RemoteProtocol.sameJson(read(id), intent)) throw new IOException("NETWORK_TASK_CONFLICT");
        } else write(file, intent);
        return "CLASSPATH=" + RescueFiles.quote(apk)
                + " /system/bin/app_process /system/bin " + RemoteNetworkTask.class.getName() + " " + id;
    }

    static JSONObject read(String id) throws Exception {
        File file = file(id, ".request.json");
        requirePrivate(file, false);
        JSONObject intent = new JSONObject(RescueFiles.read(file, 16000));
        JSONObject task = new JSONObject().put("type", "system_config").put("id", intent.get("task_id"))
                .put("params", intent.get("params")).put("request_digest", intent.get("request_digest"));
        JSONObject checked = validate(intent.getString("device_id"), task);
        if (!id.equals(checked.getString("local_task_id")) || !RemoteProtocol.sameJson(checked, intent))
            throw new IOException("NETWORK_TASK_BINDING_INVALID");
        return intent;
    }

    static String hash(JSONObject intent) throws Exception {
        return intent.getJSONObject("params").getJSONObject("network_transaction").getString("apk_sha256");
    }

    static JSONObject query(String id, JSONObject expected) throws Exception {
        JSONObject intent = read(id);
        if (!RemoteProtocol.sameJson(intent, expected)) throw new IOException("NETWORK_TASK_BINDING_INVALID");
        String hash = hash(intent);
        File stopped = file(id, ".not-started.json");
        if (RemoteMaintenance.existsNoFollow(stopped)) {
            requirePrivate(stopped, false);
            JSONObject original = new JSONObject(RescueFiles.read(stopped, 16000));
            if (!RemoteProtocol.sameJson(intent, original.getJSONObject("intent"))
                    || !RemoteNetworkAccess.released(id, hash)) throw new IOException("NETWORK_NOT_STARTED_UNVERIFIED");
            return new JSONObject().put("complete", true).put("success", false)
                    .put("network_transaction", original.getJSONObject("result").getJSONObject("network_transaction"));
        }
        String originalApk = "/data/local/d31-remote/releases/" + hash + "/remote.apk";
        if (!originalApk.equals(System.getenv("CLASSPATH"))) {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/system/bin",
                    RemoteNetworkTask.class.getName(), "query", id).redirectErrorStream(true);
            builder.environment().put("CLASSPATH", originalApk);
            return new JSONObject(net.elfradio.d31bootstrap.management.NetworkProcess.collect(builder.start(), 6000));
        }
        NetworkAndroidPlatform platform = RemoteNetworkAccess.platform(hash);
        JSONObject report = platform.query(id);
        if ("ABSENT".equals(report.optString("state"))) {
            return new JSONObject().put("complete", false).put("success", false).put("local_state", "ABSENT");
        }
        boolean released = false;
        if (settled(report.optString("state"))) {
            released = RemoteNetworkAccess.released(id, hash);
            if (!released) {
                platform.resumeRecovery(id);
                released = RemoteNetworkAccess.released(id, hash);
            }
        }
        JSONObject result = result(intent, report, released);
        if ("CONFIRMED".equals(report.optString("state"))) {
            File proofFile = file(id, ".allow.json");
            requirePrivate(proofFile, false);
            JSONObject proof = new JSONObject(RescueFiles.read(proofFile, 8000));
            JSONObject transaction = result.getJSONObject("network_transaction");
            if (!RemoteProtocol.sameJson(proof.getJSONObject("binding"), transaction.getJSONObject("binding"))
                    || !intent.getString("request_digest").equals(proof.optString("request_digest"))
                    || proof.getLong("issued_elapsed") > report.getLong("last_elapsed")
                    || !proof.getString("nonce").matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
                throw new IOException("NETWORK_CONFIRM_PROOF_MISMATCH");
            transaction.put("confirmation_nonce", proof.getString("nonce"));
        }
        return result;
    }

    static JSONObject result(JSONObject intent, JSONObject report, boolean released) throws Exception {
        String state = report.optString("state");
        if (!"ABSENT".equals(state) && !"UNKNOWN".equals(state)) {
            JSONObject n = intent.getJSONObject("params").getJSONObject("network_transaction");
            if (!intent.getString("local_task_id").equals(report.opt("task_id"))
                    || !intent.getJSONObject("params").get("value").equals(report.opt("target"))
                    || !"wifi_enabled".equals(report.opt("key"))
                    || !RemoteProtocol.sameJson(n.get("confirm_within_ms"), report.opt("window_ms")))
                throw new IOException("NETWORK_TASK_RESULT_MISMATCH");
        }
        boolean complete = settled(state) && released;
        boolean success = complete && (("CONFIRMED".equals(state) && Boolean.TRUE.equals(report.opt("target_verified")))
                || ("UNCHANGED".equals(state) && Boolean.FALSE.equals(report.opt("apply_attempted"))
                && Boolean.TRUE.equals(report.opt("original_verified")) && report.get("before").equals(report.get("target"))));
        if ("ABSENT".equals(state) || "UNKNOWN".equals(state))
            return new JSONObject().put("complete", false).put("success", false).put("local_state", state);
        Object current = Boolean.TRUE.equals(report.opt("target_verified")) ? report.get("target")
                : Boolean.TRUE.equals(report.opt("original_verified")) ? report.get("before") : JSONObject.NULL;
        String wireState = "UNCHANGED".equals(state) && !report.get("before").equals(report.get("target"))
                && Boolean.TRUE.equals(report.opt("original_verified")) ? "ORIGINAL_OBSERVED" : state;
        return new JSONObject().put("network_transaction", new JSONObject().put("version", 1).put("status", wireState)
                .put("binding", binding(intent, report)).put("observed_elapsed", report.getLong("last_elapsed"))
                .put("current_enabled", current).put("cleanup_complete", released).put("restored", report.getBoolean("restored")))
                .put("complete", complete).put("success", success);
    }

    static JSONObject binding(JSONObject intent, JSONObject report) throws Exception {
        JSONObject binding = new JSONObject().put("apk_sha256", hash(intent));
        for (String key : new String[]{"task_id", "key", "before", "target", "boot_id", "started_elapsed", "deadline_elapsed", "window_ms"})
            binding.put(key, report.get(key));
        return binding;
    }

    static JSONObject notStarted(long elapsed) throws Exception {
        return new JSONObject().put("network_transaction", new JSONObject().put("version", 1).put("status", "NOT_STARTED")
                .put("binding", JSONObject.NULL).put("mutation_started", false).put("observed_elapsed", elapsed)
                .put("current_enabled", JSONObject.NULL).put("cleanup_complete", true).put("restored", false));
    }

    static boolean settled(String state) {
        return "CONFIRMED".equals(state) || "UNCHANGED".equals(state) || "ABORTED".equals(state)
                || "ROLLED_BACK".equals(state) || "ORIGINAL_OBSERVED".equals(state);
    }

    static void cancel(String id) throws Exception {
        JSONObject intent = read(id);
        try (AutoCloseable command = commandLease(id)) {
        write(file(id, ".cancel.json"), new JSONObject().put("cancel_requested", true));
        NetworkAndroidPlatform platform = RemoteNetworkAccess.platform(hash(intent));
        JSONObject report = platform.query(id);
        if (!"ABSENT".equals(report.optString("state"))) platform.cancel(id);
        }
    }

    private static AutoCloseable commandLease(String id) throws Exception {
        File path = file(id, ".command.lock");
        if (RemoteMaintenance.existsNoFollow(path)) requirePrivate(path, false);
        java.io.RandomAccessFile stream = new java.io.RandomAccessFile(path, "rw");
        try {
            Os.chmod(path.getPath(), 0600);
            java.nio.channels.FileLock lock = RemoteFileLocks.tryExclusive(stream.getChannel());
            if (lock == null) throw new IOException("NETWORK_COMMAND_BUSY");
            return () -> { try { lock.close(); } finally { stream.close(); } };
        } catch (Exception failure) { stream.close(); throw failure; }
    }

    static boolean cancelled(String id) throws Exception {
        File marker = file(id, ".cancel.json");
        if (!RemoteMaintenance.existsNoFollow(marker)) return false;
        requirePrivate(marker, false);
        if (!Boolean.TRUE.equals(new JSONObject(RescueFiles.read(marker, 1000)).opt("cancel_requested")))
            throw new IOException("NETWORK_CANCEL_INVALID");
        return true;
    }

    static File file(String id, String suffix) throws Exception {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IOException("NETWORK_TASK_INVALID");
        requirePrivate(ROOT, true);
        return new File(ROOT, id + suffix);
    }

    private static void ensureRoot() throws Exception {
        requirePrivate(RemoteUpdatePlatform.RUNTIME, true);
        if (!RemoteMaintenance.existsNoFollow(ROOT)) Os.mkdir(ROOT.getPath(), 0700);
        requirePrivate(ROOT, true);
    }

    static void requirePrivate(File file, boolean directory) throws Exception {
        android.system.StructStat st = Os.lstat(file.getPath());
        if (st.st_uid != 0 || (st.st_mode & 0777) != (directory ? 0700 : 0600)
                || (directory ? !OsConstants.S_ISDIR(st.st_mode) : !OsConstants.S_ISREG(st.st_mode) || st.st_nlink != 1)
                || !file.getAbsoluteFile().equals(file.getCanonicalFile())) throw new IOException("NETWORK_TASK_FILE_INVALID");
    }

    static void write(File file, JSONObject value) throws Exception {
        requirePrivate(file.getParentFile(), true);
        if (RemoteMaintenance.existsNoFollow(file)) requirePrivate(file, false);
        RescueFiles.write(file, value.toString());
        Os.chmod(file.getPath(), 0600);
        java.io.FileDescriptor fd = Os.open(file.getParent(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
        try { Os.fsync(fd); } finally { Os.close(fd); }
    }

    public static void main(String[] args) {
        int exit = 1;
        JSONObject original = null;
        NetworkAndroidPlatform current = null;
        boolean firstInvocation = false;
        boolean beginMayHaveRun = false;
        AutoCloseable command = null;
        try {
            if (Os.getuid() != 0 || (args.length != 1 && !(args.length == 2 && "query".equals(args[0]))))
                throw new IOException("NETWORK_TASK_ARGUMENTS_INVALID");
            Os.umask(0077);
            if (args.length == 2) {
                JSONObject expected = read(args[1]);
                if (!("/data/local/d31-remote/releases/" + hash(expected) + "/remote.apk").equals(System.getenv("CLASSPATH")))
                    throw new IOException("NETWORK_TASK_APK_MISMATCH");
                System.out.println(query(args[1], expected));
                System.exit(0); return;
            }
            JSONObject intent = read(args[0]);
            original = intent;
            command = commandLease(args[0]);
            if (RemoteMaintenance.existsNoFollow(file(args[0], ".not-started.json"))) {
                System.out.println(query(args[0], intent));
                System.exit(0); return;
            }
            File invoked = file(args[0], ".invoked.json");
            if (RemoteMaintenance.existsNoFollow(invoked)) {
                requirePrivate(invoked, false);
                if (!RemoteProtocol.sameJson(new JSONObject(RescueFiles.read(invoked, 16000)), intent))
                    throw new IOException("NETWORK_TASK_CONFLICT");
                System.out.println(query(args[0], intent));
                System.exit(0); return;
            }
            firstInvocation = true;
            write(invoked, intent);
            if (!new File(RemoteUpdatePlatform.RUNTIME, "network").exists()) RemoteNetworkAccess.prepare(hash(intent));
            NetworkAndroidPlatform platform = RemoteNetworkAccess.platform(hash(intent));
            current = platform;
            if (cancelled(args[0])) throw new IOException("NETWORK_TASK_CANCELLED_BEFORE_BEGIN");
            JSONObject p = intent.getJSONObject("params");
            JSONObject report;
            try {
                beginMayHaveRun = true;
                report = platform.begin(args[0], p.getBoolean("value"), p.getJSONObject("network_transaction").getLong("confirm_within_ms"));
            }
            catch (Exception failure) {
                try { platform.releaseUnstarted(args[0]); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            if ("AWAITING_CONFIRM".equals(report.optString("state"))) RemoteNetworkConfirmation.ensure(args[0], hash(intent));
            System.out.println(result(intent, report, settled(report.optString("state"))
                    && RemoteNetworkAccess.released(args[0], hash(intent))));
            exit = 0;
        } catch (Exception failure) {
            if (original != null && firstInvocation) try {
                String id = original.getString("local_task_id");
                if ((!beginMayHaveRun && current == null) || (current != null && "ABSENT".equals(current.query(id).optString("state")))) {
                    if (current != null) current.releaseUnstarted(id);
                    if (RemoteNetworkAccess.released(id, hash(original)))
                        write(file(id, ".not-started.json"), new JSONObject().put("intent", original)
                                .put("result", notStarted(android.os.SystemClock.elapsedRealtime())));
                }
            } catch (Exception unverified) { /* 无法证明未开始时保留原号，不伪造失败终态。 */ }
            System.out.println("{\"state\":\"NETWORK_TASK_UNRESOLVED\"}");
        } finally {
            if (command != null) try { command.close(); } catch (Exception cleanup) { exit = 1; }
        }
        System.exit(exit);
    }
}
