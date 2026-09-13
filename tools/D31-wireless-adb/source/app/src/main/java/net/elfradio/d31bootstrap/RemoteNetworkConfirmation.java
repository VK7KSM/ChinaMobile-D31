package net.elfradio.d31bootstrap;

import android.os.SystemClock;
import android.system.Os;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.io.IOException;
import net.elfradio.d31bootstrap.management.NetworkAndroidPlatform;
import net.elfradio.d31bootstrap.management.NetworkConfirmationDispatch;
import org.json.JSONObject;

/** 原号独立确认执行者；退出、断网和云超时都不停止既有回退守护。 */
public final class RemoteNetworkConfirmation {
    static boolean eligible(JSONObject report, String boot, long now) {
        return "AWAITING_CONFIRM".equals(report.optString("state")) && boot.equals(report.optString("boot_id"))
                && now >= report.optLong("last_elapsed", Long.MAX_VALUE)
                && now < report.optLong("deadline_elapsed", -1);
    }

    static void ensure(String id, String hash) throws Exception {
        if (!RemoteMaintenance.existsNoFollow(RemoteNetworkTask.ROOT)) return;
        File request = RemoteNetworkTask.file(id, ".request.json");
        if (!RemoteMaintenance.existsNoFollow(request)) return;
        JSONObject intent = RemoteNetworkTask.read(id);
        if (!hash.equals(RemoteNetworkTask.hash(intent))) throw new IOException("NETWORK_CONFIRM_APK_MISMATCH");
        JSONObject report = RemoteNetworkAccess.platform(hash).query(id);
        if (!eligible(report, boot(), SystemClock.elapsedRealtime())) return;
        if (backoff(id, report) > SystemClock.elapsedRealtime()) return;
        File lockFile = RemoteNetworkTask.file(id, ".confirm.lock");
        if (RemoteMaintenance.existsNoFollow(lockFile)) {
            RemoteNetworkTask.requirePrivate(lockFile, false);
            try (RandomAccessFile stream = new RandomAccessFile(lockFile, "rw");
                 FileLock lock = RemoteFileLocks.tryExclusive(stream.getChannel())) {
                if (lock == null) return;
            }
        }
        String command = "exec /system/bin/busybox setsid /system/bin/app_process /system/bin --nice-name=d31-network-confirm "
                + RemoteNetworkConfirmation.class.getName() + " " + id + " " + hash + " </dev/null >/dev/null 2>&1";
        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", command);
        builder.environment().put("CLASSPATH", "/data/local/d31-remote/releases/" + hash + "/remote.apk");
        builder.start();
    }

    private static String boot() throws Exception {
        return RescueFiles.read(new File("/proc/sys/kernel/random/boot_id"), 80).trim();
    }

    private static long backoff(String id, JSONObject report) throws Exception {
        File file = RemoteNetworkTask.file(id, ".confirm-backoff.json");
        if (!RemoteMaintenance.existsNoFollow(file)) return 0;
        RemoteNetworkTask.requirePrivate(file, false);
        JSONObject value = new JSONObject(RescueFiles.read(file, 1000));
        if (!report.getString("boot_id").equals(value.getString("boot_id"))
                || report.getLong("deadline_elapsed") != value.getLong("deadline_elapsed")
                || !(value.opt("next_elapsed") instanceof Number) || value.getLong("next_elapsed") < 0)
            throw new IOException("NETWORK_CONFIRM_BACKOFF_INVALID");
        return value.getLong("next_elapsed");
    }

    public static void main(String[] args) {
        try {
            if (Os.getuid() != 0 || args.length != 2) throw new IOException("NETWORK_CONFIRM_ARGUMENTS_INVALID");
            Os.umask(0077);
            String id = args[0], hash = args[1];
            JSONObject intent = RemoteNetworkTask.read(id);
            if (!hash.equals(RemoteNetworkTask.hash(intent))) throw new IOException("NETWORK_CONFIRM_APK_MISMATCH");
            File lockFile = RemoteNetworkTask.file(id, ".confirm.lock");
            if (RemoteMaintenance.existsNoFollow(lockFile)) RemoteNetworkTask.requirePrivate(lockFile, false);
            try (RandomAccessFile stream = new RandomAccessFile(lockFile, "rw");
                 FileLock lock = RemoteFileLocks.tryExclusive(stream.getChannel())) {
                if (lock == null) return;
                RemoteNetworkTask.requirePrivate(lockFile, false);
                NetworkAndroidPlatform platform = RemoteNetworkAccess.platform(hash);
                File identityFile = new File(RemoteUpdatePlatform.CORE, "identity.json");
                JSONObject identity = new JSONObject(RescueFiles.read(identityFile, 32000));
                if (!intent.getString("device_id").equals(identity.optString("device_id")))
                    throw new IOException("NETWORK_CONFIRM_IDENTITY_MISMATCH");
                try (RemoteNetworkConfirmationSource source = new RemoteNetworkConfirmationSource(identity,
                        intent.getString("task_id"), intent.getString("request_digest"))) {
                while (!Thread.currentThread().isInterrupted()) {
                    JSONObject report = platform.query(id);
                    if (!eligible(report, boot(), SystemClock.elapsedRealtime())) break;
                    if (backoff(id, report) > SystemClock.elapsedRealtime()) break;
                    if (RemoteNetworkTask.cancelled(id)) { platform.cancel(id); break; }
                    platform.pollConfirmation(id, (request, budget) -> {
                        NetworkConfirmationDispatch.Receipt receipt = source.fetchVerified(request, budget);
                        if (receipt != null) {
                            JSONObject proof = new JSONObject().put("binding", RemoteNetworkTask.binding(intent, report))
                                    .put("nonce", receipt.nonce).put("issued_elapsed", receipt.issuedElapsed)
                                    .put("request_digest", intent.getString("request_digest"));
                            RemoteNetworkTask.write(RemoteNetworkTask.file(id, ".allow.json"), proof);
                        }
                        return receipt;
                    });
                    JSONObject current = platform.query(id);
                    if (!eligible(current, boot(), SystemClock.elapsedRealtime())) break;
                    long wait = Math.max(2000, source.retryAfterMs());
                    long left = current.getLong("deadline_elapsed") - SystemClock.elapsedRealtime();
                    RemoteNetworkTask.write(RemoteNetworkTask.file(id, ".confirm-backoff.json"), new JSONObject()
                            .put("boot_id", current.getString("boot_id")).put("deadline_elapsed", current.getLong("deadline_elapsed"))
                            .put("next_elapsed", SystemClock.elapsedRealtime() + Math.min(wait, 86400000)));
                    if (wait >= left) break;
                    Thread.sleep(wait);
                }
                }
            }
        } catch (Exception failure) {
            // 不输出身份或服务器正文，故障不改变回退守护的原预算。
            System.err.println("网络确认结束，原事务由独立守护核查");
        } finally { System.exit(0); }
    }
}
