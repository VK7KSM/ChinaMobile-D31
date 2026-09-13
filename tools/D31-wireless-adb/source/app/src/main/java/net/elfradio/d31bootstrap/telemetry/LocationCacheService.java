package net.elfradio.d31bootstrap.telemetry;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

/** 不导出的按需定位Service；主动窗口结束或取消后释放定位监听，不常驻。 */
public final class LocationCacheService extends Service {
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Thread worker;
    private int lastStart;
    private final Runnable timeout = () -> stopSelf();

    @Override public synchronized int onStartCommand(final Intent intent, int flags, int startId) {
        lastStart = startId;
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        if (!ACTIVE.compareAndSet(false, true)) {
            AppLocationCache.respond(this, intent, "app_cache_busy");
            if (worker == null) stopSelf(startId);
            return START_NOT_STICKY;
        }
        worker = new Thread(() -> {
            try { AppLocationCache.respond(this, intent); }
            finally {
                synchronized (LocationCacheService.this) {
                    ACTIVE.set(false); worker = null; handler.removeCallbacks(timeout); stopSelf(lastStart);
                }
            }
        }, "d31-app-location-cache");
        worker.setDaemon(true);
        long window = Math.max(0, Math.min(45000, intent.getLongExtra("location_window_ms", 0)));
        handler.postDelayed(timeout, window + AppLocationCacheContract.WAIT_MS + 1000);
        worker.start();
        return START_NOT_STICKY;
    }
    @Override public synchronized void onDestroy() {
        handler.removeCallbacks(timeout);
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
