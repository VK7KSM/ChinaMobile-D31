package net.elfradio.d31bootstrap;

import android.content.Context;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.elfradio.d31bootstrap.media.AppMediaBridge;
import org.json.JSONObject;

/** 现有root_exec的按需准备与有界本地采音诊断；不提供媒体网络会话启动。 */
public final class RemoteMediaCommand {
    static void validate(String[] args) throws IOException {
        if (args == null || args.length < 2 || !args[1].matches("[a-f0-9]{64}"))
            throw new IOException("MEDIA_REQUEST_INVALID");
        if (args.length == 2 && ("prepare".equals(args[0]) || "query".equals(args[0]))) return;
        if (args.length != 4 || !"local_audio_capture".equals(args[0])
                || !args[2].matches("[A-Za-z0-9_-]{1,96}") || !args[3].matches("[1-9][0-9]{0,3}")
                || Integer.parseInt(args[3]) > 5000) throw new IOException("MEDIA_REQUEST_INVALID");
    }

    static JSONObject execute(String[] args) throws Exception {
        validate(args);
        try (RemoteMaintenance.Lease lease = RemoteMaintenance.acquire()) {
            if (lease == null) throw new IOException("MEDIA_MAINTENANCE_BUSY");
            RemoteMaintenance.requireUnreserved();
            RemoteMaintenance.requireRepairReady();
            JSONObject active = new JSONObject(RescueFiles.read(RemoteUpdatePlatform.ACTIVE, 4096));
            if (!args[1].equals(active.getString("sha256"))
                    || active.getInt("versionCode") != BuildConfig.VERSION_CODE
                    || !active.getString("path").equals(System.getenv("CLASSPATH")))
                throw new IOException("MEDIA_ACTIVE_APK_MISMATCH");
            if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
            Class<?> type = Class.forName("android.app.ActivityThread");
            Object thread = type.getMethod("systemMain").invoke(null);
            Context context = (Context) type.getMethod("getSystemContext").invoke(thread);
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<JSONObject> result = new AtomicReference<>();
            AtomicReference<String> error = new AtomicReference<>();
            AppMediaBridge.Callback callback = new AppMediaBridge.Callback() {
                public void completed(JSONObject value) { result.set(value); completed.countDown(); }
                public void failed(String code) { error.set(code); completed.countDown(); }
            };
            try (AppMediaBridge bridge = new AppMediaBridge(context)) {
                if (args[0].equals("prepare")) bridge.prepare(args[1], callback);
                else if (args[0].equals("local_audio_capture"))
                    bridge.captureLocalAudio(args[1], args[2], Integer.parseInt(args[3]), callback);
                else bridge.query("", callback);
                if (!completed.await(20, TimeUnit.SECONDS)) throw new IOException("MEDIA_COMMAND_TIMEOUT");
                if (error.get() != null) throw new IOException(error.get());
                if (result.get() == null) throw new IOException("MEDIA_REPLY_MISSING");
                return new JSONObject().put("operation", args[0]).put("query_completed", true)
                        .put("managed_media", false).put("capture_started", result.get().optBoolean("recording_started", false))
                        .put("version_code", BuildConfig.VERSION_CODE).put("result", result.get());
            }
        }
    }

    public static void main(String[] args) {
        try {
            if (android.system.Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                    || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                    || !"hct6737t_66_m0".equals(android.os.Build.MODEL))
                throw new IOException("MEDIA_REQUIRES_D31_ROOT");
            android.system.Os.umask(0077);
            System.out.println(execute(args));
            System.exit(0);
        } catch (Exception failure) {
            String code = failure.getMessage();
            if (code == null || !code.matches("MEDIA_[A-Z0-9_]{1,80}")) code = "MEDIA_COMMAND_FAILED";
            System.out.println("{\"query_completed\":false,\"managed_media\":false,\"error\":\"" + code + "\"}");
            System.exit(1);
        }
    }
    private RemoteMediaCommand() { }
}
