package net.elfradio.d31bootstrap.telemetry;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** root只负责真实AMS启动及核验回包；缓存、主动定位和无线观测均在实际应用UID内执行。 */
public final class AppLocationCache {
    public static final String PACKAGE = "net.elfradio.d31bootstrap";
    public static final String SERVICE = PACKAGE + ".telemetry.LocationCacheService";
    static final String ACTION = PACKAGE + ".telemetry.READ_LOCATION_CACHE";
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final AtomicReference<String> DIAGNOSTIC = new AtomicReference<String>();

    /** 最近一次桥接故障的有界私有证据；不合并进正式报告，也不含坐标。 */
    public static JSONObject lastDiagnostic() throws Exception {
        String value = DIAGNOSTIC.get(); return value == null ? null : new JSONObject(value);
    }

    static void recordFailure(String stage, Throwable failure) {
        try {
            JSONObject detail = new JSONObject().put("stage", stage).put("at_ms", System.currentTimeMillis())
                    .put("failure", LocationCacheDiagnostics.failure(failure));
            String value = detail.toString();
            if (value.length() > 6000) {
                String message = failure.getMessage();
                detail = new JSONObject().put("stage", stage).put("type", failure.getClass().getName())
                        .put("message", message == null ? JSONObject.NULL : message.substring(0, Math.min(1024, message.length())))
                        .put("diagnostic_truncated", true);
                value = detail.toString();
            }
            DIAGNOSTIC.set(value); System.err.println("D31_LOCATION_CACHE_FAILURE " + value);
        } catch (Exception unavailable) { System.err.println("D31_LOCATION_CACHE_FAILURE_UNAVAILABLE"); }
    }

    static TelemetryCollector.LocationReading read(Context context, final TelemetryCollector.Limits limits,
                                                   final TelemetryCollector.Clock clock) throws Exception {
        return read(context, limits, clock, false);
    }

    static TelemetryCollector.LocationReading read(Context context, final TelemetryCollector.Limits limits,
                                                   final TelemetryCollector.Clock clock, boolean radio) throws Exception {
        return read(context, limits, clock, radio, true);
    }

    static TelemetryCollector.LocationReading read(Context context, final TelemetryCollector.Limits limits,
                                                   final TelemetryCollector.Clock clock, boolean radio, boolean radioScan) throws Exception {
        if (!IN_FLIGHT.compareAndSet(false, true)) return AppLocationCacheContract.missing("app_cache_busy");
        final AtomicBoolean closed = new AtomicBoolean();
        String stage = "validate_root";
        DIAGNOSTIC.set(null);
        try {
            if (Process.myUid() != 0 || android.os.Build.VERSION.SDK_INT != 23)
                return AppLocationCacheContract.missing("app_cache_unavailable");
            final ComponentName target = new ComponentName(PACKAGE, SERVICE);
            stage = "resolve_service";
            final int expectedUid = context.getPackageManager().getServiceInfo(target, 0).applicationInfo.uid;
            if (expectedUid <= 0) return AppLocationCacheContract.missing("app_cache_identity_mismatch");
            stage = "read_boot_id";
            final String id = UUID.randomUUID().toString(), boot = bootId();
            final long started = clock.elapsedRealtimeNanos();
            final CountDownLatch ready = new CountDownLatch(1);
            final AtomicReference<TelemetryCollector.LocationReading> result = new AtomicReference<TelemetryCollector.LocationReading>();
            // null Handler保持回调在Binder线程执行，才能读取真实发送UID。
            ResultReceiver receiver = new ResultReceiver(null) {
                @Override protected void onReceiveResult(int code, Bundle data) {
                    int senderUid = Binder.getCallingUid();
                    if (closed.get() || senderUid != expectedUid) return;
                    TelemetryCollector.LocationReading reading;
                    try {
                        if (data != null) {
                            String diagnostic = data.getString("diagnostic");
                            if (diagnostic != null && diagnostic.length() <= 6000) {
                                String value = new JSONObject(diagnostic).toString();
                                DIAGNOSTIC.set(value); System.err.println("D31_LOCATION_CACHE_APP_FAILURE " + value);
                            }
                        }
                        if (code != 1 || data == null || !boot.equals(bootId())) throw new IllegalArgumentException("CACHE_REPLY_INVALID");
                        reading = AppLocationCacheContract.decode(data.getString("payload"), id, boot, expectedUid,
                                senderUid, started, limits, clock);
                    } catch (Exception invalid) {
                        if (DIAGNOSTIC.get() == null) recordFailure("validate_reply", invalid);
                        reading = AppLocationCacheContract.missing("app_cache_invalid_reply");
                    }
                    if (result.compareAndSet(null, reading)) ready.countDown();
                }
            };
            Intent intent = new Intent(ACTION).setComponent(target).putExtra("request_id", id).putExtra("boot_id", boot)
                    .putExtra("started_elapsed_nanos", started).putExtra("max_location_age_ms", limits.maxLocationAgeMs)
                    .putExtra("location_window_ms", limits.locationWindowMs).putExtra("radio", radio)
                    .putExtra("radio_scan", radioScan)
                    .putExtra("reply", receiver);
            Object manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
            stage = "start_service";
            Object component = AppLocationCacheContract.start(Class.forName("android.app.IActivityManager"), manager,
                    Class.forName("android.app.IApplicationThread"), Intent.class, intent);
            if (!target.equals(component)) {
                recordFailure(stage, new IllegalStateException("CACHE_SERVICE_START_RESULT " + component));
                return AppLocationCacheContract.missing("app_cache_unavailable");
            }
            stage = "await_reply";
            long remaining = AppLocationCacheContract.WAIT_MS + limits.locationWindowMs
                    - Math.max(0, (clock.elapsedRealtimeNanos() - started) / 1000000);
            if (remaining > 0) ready.await(remaining, TimeUnit.MILLISECONDS);
            if (result.get() == null) {
                recordFailure(stage, new java.util.concurrent.TimeoutException("CACHE_REPLY_DEADLINE"));
                return AppLocationCacheContract.missing("app_cache_timeout");
            }
            return result.get();
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt(); throw cancelled;
        } catch (Exception unavailable) {
            recordFailure(stage, unavailable);
            return AppLocationCacheContract.missing("app_cache_unavailable");
        } finally { closed.set(true); IN_FLIGHT.set(false); }
    }

    /** 仅由不导出的应用Service在一次性工作线程调用，传入原始显式Intent。 */
    public static void respond(Context context, Intent intent) {
        respond(context, intent, null);
    }

    static void respond(Context context, Intent intent, String forcedReason) {
        ResultReceiver receiver = null;
        String stage = "app_validate_request";
        if (forcedReason == null) DIAGNOSTIC.set(null);
        try {
            if (intent == null || !ACTION.equals(intent.getAction()) || !new ComponentName(PACKAGE, SERVICE).equals(intent.getComponent())) return;
            receiver = intent.getParcelableExtra("reply");
            if (receiver == null) return;
            String id = intent.getStringExtra("request_id"), boot = intent.getStringExtra("boot_id");
            long started = intent.getLongExtra("started_elapsed_nanos", -1);
            TelemetryCollector.Clock clock = AndroidTelemetryAccess.clock();
            AppLocationCacheContract.request(id, boot, started, clock.elapsedRealtimeNanos());
            if (!bootId().equals(boot) || Process.myUid() <= 0 || !PACKAGE.equals(context.getPackageName())
                    || context.getPackageManager().getApplicationInfo(PACKAGE, 0).uid != Process.myUid())
                throw new SecurityException("CACHE_APP_IDENTITY_MISMATCH");
            TelemetryCollector.Limits limits = new TelemetryCollector.Limits(intent.getLongExtra("location_window_ms", 0),
                    intent.getLongExtra("max_location_age_ms", -1));
            TelemetryCollector.LocationReading reading;
            if (forcedReason != null) reading = AppLocationCacheContract.missing(forcedReason);
            else {
                stage = "app_location";
                try { reading = new AndroidTelemetryAccess(context).location(limits, clock); }
                catch (SecurityException denied) { recordFailure(stage, denied); reading = AppLocationCacheContract.missing("permission_denied"); }
                catch (Exception unavailable) { recordFailure(stage, unavailable); reading = AppLocationCacheContract.missing("provider_unavailable"); }
            }
            if (forcedReason == null && intent.getBooleanExtra("radio", false)) {
                JSONObject observation = RemoteLocationRadio.capture(context, intent.getBooleanExtra("radio_scan", true));
                reading = new TelemetryCollector.LocationReading(reading.fix, reading.reason, reading.listenerReleased,
                        observation.toString());
            }
            Bundle data = new Bundle();
            if (DIAGNOSTIC.get() != null) data.putString("diagnostic", DIAGNOSTIC.get());
            data.putString("payload", AppLocationCacheContract.encode(id, boot, Process.myUid(), started, reading, clock));
            receiver.send(1, data);
        } catch (Exception invalid) {
            recordFailure(stage, invalid);
            if (receiver != null) {
                Bundle data = new Bundle(); data.putString("diagnostic", DIAGNOSTIC.get()); receiver.send(0, data);
            }
        }
    }

    private static String bootId() throws Exception {
        try (FileInputStream input = new FileInputStream("/proc/sys/kernel/random/boot_id");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65]; int n = input.read(buffer);
            if (n < 0 || n > 64 || input.read() != -1) throw new IllegalStateException("INVALID_BOOT_ID");
            output.write(buffer, 0, n);
            String value = output.toString("US-ASCII").trim();
            if (!value.matches("[a-f0-9-]{36}")) throw new IllegalStateException("INVALID_BOOT_ID");
            return value;
        }
    }
    private AppLocationCache() { }
}
