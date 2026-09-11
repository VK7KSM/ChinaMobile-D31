package net.elfradio.d31bootstrap.telemetry;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** API23一次性访问；调用者在现有工作线程调用，不更改定位开关、权限、MQTT或报告节拍。 */
public final class AndroidTelemetryAccess implements TelemetryCollector.Access {
    private final Context context;
    private final LocationManager manager;
    private boolean cleanupFailed;
    public AndroidTelemetryAccess(Context context) {
        if (context == null) throw new IllegalArgumentException("MISSING_CONTEXT");
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }
    public static TelemetryCollector.Clock clock() {
        return new TelemetryCollector.Clock() {
            public long wallTimeMillis() { return System.currentTimeMillis(); }
            public long elapsedRealtimeNanos() { return SystemClock.elapsedRealtimeNanos(); }
        };
    }
    @Override public synchronized TelemetryCollector.LocationReading location(final TelemetryCollector.Limits limits,
            final TelemetryCollector.Clock clock) throws Exception {
        if (cleanupFailed) return new TelemetryCollector.LocationReading(null, "cleanup_failed", false);
        if (manager == null) return new TelemetryCollector.LocationReading(null, "provider_unavailable", true);
        final List<String> enabled = new ArrayList<String>(2);
        TelemetryCollector.Fix cached = null;
        boolean denied = false;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (manager.getProvider(provider) == null || !manager.isProviderEnabled(provider)) continue;
                enabled.add(provider);
                Location location = manager.getLastKnownLocation(provider);
                TelemetryCollector.Fix candidate = location == null ? null : fix(location);
                if (TelemetryCollector.invalidFix(candidate, limits, clock) == null) cached = better(cached, candidate);
            } catch (SecurityException failure) { denied = true; }
            catch (IllegalArgumentException unavailable) { }
        }
        if (cached != null) return new TelemetryCollector.LocationReading(cached, "recent_cache", true);
        if (enabled.isEmpty()) return new TelemetryCollector.LocationReading(null, denied ? "permission_denied" : "location_disabled", true);
        if (limits.locationWindowMs == 0) return new TelemetryCollector.LocationReading(null, denied ? "permission_denied" : "no_cached_location", true);
        final AtomicReference<TelemetryCollector.Fix> observed = new AtomicReference<TelemetryCollector.Fix>();
        final CountDownLatch ready = new CountDownLatch(1);
        final long since = clock.elapsedRealtimeNanos();
        HandlerThread callbacks = new HandlerThread("d31-telemetry-once");
        LocationListener listener = new LocationListener() {
            public void onLocationChanged(Location location) {
                TelemetryCollector.Fix candidate = fix(location);
                if (candidate.elapsedNanos < since || TelemetryCollector.invalidFix(candidate, limits, clock) != null) return;
                observed.set(better(observed.get(), candidate));
                ready.countDown();
            }
            public void onProviderDisabled(String provider) { }
            public void onProviderEnabled(String provider) { }
            public void onStatusChanged(String provider, int status, Bundle extras) { }
        };
        String reason = "timeout";
        callbacks.start();
        try {
            int registered = 0;
            for (String provider : enabled) {
                try { manager.requestLocationUpdates(provider, 1000, 0, listener, callbacks.getLooper()); registered++; }
                catch (SecurityException failure) { denied = true; }
                catch (IllegalArgumentException unavailable) { }
            }
            if (registered == 0) reason = denied ? "permission_denied" : "provider_unavailable";
            else {
                long remaining = limits.locationWindowMs - Math.max(0, (clock.elapsedRealtimeNanos() - since) / 1000000);
                if (remaining > 0) ready.await(remaining, TimeUnit.MILLISECONDS);
            }
        } finally {
            try { manager.removeUpdates(listener); }
            catch (Exception failure) { cleanupFailed = true; }
            callbacks.quitSafely();
        }
        return new TelemetryCollector.LocationReading(observed.get(), cleanupFailed ? "cleanup_failed" : reason, !cleanupFailed);
    }
    private static TelemetryCollector.Fix better(TelemetryCollector.Fix old, TelemetryCollector.Fix next) {
        if (old == null) return next;
        if (!old.provider.equals(next.provider)) return "gps".equals(next.provider) ? next : old;
        return next.elapsedNanos > old.elapsedNanos ? next : old;
    }
    private static TelemetryCollector.Fix fix(Location location) {
        return new TelemetryCollector.Fix(location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : null, location.getProvider(), location.getTime(),
                location.getElapsedRealtimeNanos(), location.isFromMockProvider());
    }
    @Override public TelemetryCollector.BatteryReading battery() throws Exception {
        return StickyBatteryReader.read(new StickyBatteryReader.Access() {
            public TelemetryCollector.BatteryReading contextSticky() {
                return batteryReading(context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
            }
            public TelemetryCollector.BatteryReading rootSticky() throws Exception {
                Object manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
                Object sticky = StickyBatteryReader.query(Class.forName("android.app.IActivityManager"), manager,
                        Class.forName("android.app.IApplicationThread"), Class.forName("android.content.IIntentReceiver"),
                        IntentFilter.class, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (sticky != null && !(sticky instanceof Intent)) throw new IllegalStateException("INVALID_BATTERY_STICKY");
                return batteryReading((Intent) sticky);
            }
        }, android.os.Build.VERSION.SDK_INT, android.os.Process.myUid());
    }
    private static TelemetryCollector.BatteryReading batteryReading(Intent battery) {
        if (battery == null) return null;
        if (!Intent.ACTION_BATTERY_CHANGED.equals(battery.getAction())) throw new IllegalStateException("INVALID_BATTERY_ACTION");
        return new TelemetryCollector.BatteryReading(integer(battery, BatteryManager.EXTRA_LEVEL),
                integer(battery, BatteryManager.EXTRA_SCALE), integer(battery, BatteryManager.EXTRA_PLUGGED),
                integer(battery, BatteryManager.EXTRA_STATUS), bool(battery, BatteryManager.EXTRA_PRESENT));
    }
    private static Integer integer(Intent intent, String key) {
        Object value = extra(intent, key); return value instanceof Integer ? (Integer) value : null;
    }
    private static Boolean bool(Intent intent, String key) {
        Object value = extra(intent, key); return value instanceof Boolean ? (Boolean) value : null;
    }
    private static Object extra(Intent intent, String key) {
        Bundle extras = intent.getExtras(); return extras == null ? null : extras.get(key);
    }
}
