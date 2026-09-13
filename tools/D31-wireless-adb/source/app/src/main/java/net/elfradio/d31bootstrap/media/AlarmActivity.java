package net.elfradio.d31bootstrap.media;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/** 只拥有警报窗口；音轨及正常音量恢复由主线租约拥有者负责。 */
public final class AlarmActivity extends Activity {
    private static final String TAG = "D31AlarmUi";
    private static final String DESCRIPTOR = "net.elfradio.d31bootstrap.ALARM";
    private static final int GET_ACTIVE = 1, READY = 2, STOP = 3;
    private static final String VOLUME = "original_volume";
    private static final String SESSION = "session";
    private static final ScheduledThreadPoolExecutor WORKER = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "d31-alarm-ui-lease");
        thread.setDaemon(true); return thread;
    });
    // 仅由主线程访问；配置重建共享同一租约，不误发STOP或覆盖恢复记录。
    private static Lease current;
    private Lease lease;
    private AlarmView alarmView;
    private boolean closing;
    private boolean handoffPending;
    private int intentGeneration;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = 1f;
        getWindow().setAttributes(attributes);
        immersive();
        accept(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        accept(intent);
    }

    private void accept(Intent intent) {
        Bundle extra = intent == null ? null : intent.getBundleExtra("alarm_lease");
        IBinder owner = extra == null ? null : extra.getBinder("owner");
        Object volume = extra == null ? null : extra.get(VOLUME);
        String session;
        try {
            String value = extra == null ? null : extra.getString(SESSION);
            session = UUID.fromString(value).toString();
            if (!session.equalsIgnoreCase(value)) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            Log.w(TAG, "ALARM_UI_INVALID_SESSION");
            if (lease == null) closeWindow();
            return;
        }
        if (owner == null || !(volume instanceof Integer)) {
            Log.w(TAG, "ALARM_UI_INVALID_LEASE");
            if (lease == null) closeWindow();
            return;
        }
        final int generation = ++intentGeneration;
        if (current != null) {
            if (!current.owner.equals(owner) || !current.session.equals(session)) {
                Lease previous = current;
                handoffPending = true;
                // 与旧轮询、恢复和新会话登记串行；不等待下一次250ms轮询。
                WORKER.execute(() -> {
                    boolean available = false;
                    try { available = !previous.owner.isBinderAlive() || Lease.call(previous.owner, GET_ACTIVE) == 0; }
                    catch (Exception failure) { available = !previous.owner.isBinderAlive(); }
                    if (available && !previous.closed) previous.finishLease(true, true, false);
                    final boolean adopt = available;
                    previous.main.post(() -> {
                        if (generation != intentGeneration || closing) return;
                        handoffPending = false;
                        if (adopt && (current == previous || current == null)) {
                            current = null;
                            attach(intent, owner, (Integer) volume, session);
                        } else {
                            Log.w(TAG, "ALARM_UI_LEASE_BUSY");
                            if (lease == null) closeWindow();
                        }
                    });
                });
                return;
            }
        }
        handoffPending = false;
        attach(intent, owner, (Integer) volume, session);
    }

    private void attach(Intent intent, IBinder owner, int volume, String session) {
        if (current == null) current = new Lease(getApplicationContext(), owner, volume, session);
        lease = current;
        setIntent(intent);
        lease.activity = this;
        alarmView = new AlarmView(this);
        setContentView(alarmView);
        lease.start();
        // 复用已验证的原厂屏保退出入口；仍须有焦点的真实绘制才发READY。
        try {
            sendBroadcast(new Intent("com.starnet.phone.action.CALL_STATE")
                    .setPackage("com.starnet.screensaver"));
        } catch (RuntimeException failure) {
            Log.w(TAG, "ALARM_UI_SCREENSAVER_DISMISS_FAILED");
        }
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) { immersive(); if (alarmView != null) alarmView.invalidate(); }
    }

    @Override public void onBackPressed() { stopLocally(); }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        stopLocally();
    }

    private void stopLocally() {
        if (!closing && lease != null) lease.stop();
    }

    private void closeWindow() {
        if (closing) return;
        closing = true;
        if (isTaskRoot()) {
            // 独立任务退回之前的前台任务，不发HOME、不启动主界面或原厂桌面。
            moveTaskToBack(true);
            finishAndRemoveTask();
        } else finish();
    }

    @Override protected void onDestroy() {
        if (lease != null && lease.activity == this) {
            lease.activity = null;
            if (!isChangingConfigurations() && !closing) lease.stop();
        }
        super.onDestroy();
    }

    private final class AlarmView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path triangle = new Path();
        private boolean notified;

        AlarmView(Context context) {
            super(context);
            setKeepScreenOn(true);
            setContentDescription("停止通信警报");
            setOnClickListener(view -> stopLocally());
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            long now = SystemClock.uptimeMillis();
            canvas.drawColor((now / 500L) % 2 == 0 ? Color.BLACK : Color.WHITE);
            float size = Math.min(getWidth(), getHeight()) * 0.72f;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            triangle.reset();
            triangle.moveTo(cx, cy - size * 0.5f);
            triangle.lineTo(cx - size * 0.5f, cy + size * 0.4f);
            triangle.lineTo(cx + size * 0.5f, cy + size * 0.4f);
            triangle.close();
            paint.setColor(Color.RED);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.045f);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(triangle, paint);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(size * 0.065f);
            canvas.drawLine(cx, cy - size * 0.18f, cx, cy + size * 0.1f, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy + size * 0.25f, size * 0.035f, paint);
            if (!notified && hasWindowFocus() && getWidth() > 0 && getHeight() > 0) {
                notified = true;
                // 首帧完成后才通知；租约线程保证持久化先于READY。
                post(() -> { if (lease != null && !closing) lease.ready(); });
            }
            if (!closing) postInvalidateDelayed(500L - now % 500L);
        }
    }

    private static final class Lease {
        final IBinder owner;
        final String session;
        int originalVolume;
        final SharedPreferences recovery;
        final AudioManager audio;
        final Handler main = new Handler(Looper.getMainLooper());
        final IBinder.DeathRecipient death = () -> submit(() -> finishLease(true, true));
        AlarmActivity activity; // 主线程读写，工作线程只通过main访问。
        boolean started, linked, readySent, stopSent;
        volatile boolean closed;

        Lease(Context context, IBinder owner, int volume, String session) {
            this.owner = owner; originalVolume = volume; this.session = session;
            recovery = context.getSharedPreferences("alarm_ui_recovery", Context.MODE_PRIVATE);
            audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        }

        void submit(Runnable action) {
            if (!closed) {
                try { WORKER.execute(() -> { if (!closed) action.run(); }); }
                catch (java.util.concurrent.RejectedExecutionException ignored) { }
            }
        }

        void start() {
            submit(() -> {
                if (started) return;
                started = true;
                if (owner == null) { finishLease(true, true); return; }
                try {
                    owner.linkToDeath(death, 0); linked = true;
                    if (call(owner, GET_ACTIVE) == 0) { finishLease(true, true); return; }
                    if (audio == null || originalVolume < 0 || originalVolume > audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))
                        throw new IllegalStateException("ALARM_UI_INVALID_VOLUME");
                    prepareJournal();
                    poll();
                } catch (Exception failure) { failed(); }
            });
        }

        void poll() {
            if (closed) return;
            try {
                if (call(owner, GET_ACTIVE) == 0) { finishLease(true, true); return; }
                WORKER.schedule(this::poll, 250, TimeUnit.MILLISECONDS);
            } catch (Exception failure) { failed(); }
        }

        void ready() {
            submit(() -> {
                if (readySent) return;
                try {
                    if (call(owner, GET_ACTIVE) == 0) { finishLease(true, true); return; }
                    call(owner, READY, originalVolume); readySent = true;
                } catch (Exception failure) { failed(); }
            });
        }

        void stop() {
            submit(() -> {
                if (stopSent) return;
                try { call(owner, STOP); stopSent = true; }
                catch (Exception failure) { failed(); }
            });
        }

        private void prepareJournal() {
            if (recovery.contains(VOLUME)) {
                int saved = recovery.getInt(VOLUME, -1);
                if (session.equals(recovery.getString(SESSION, null))) {
                    // 相同会话的UI进程重建：此时音量可能已最大，必须继续使用旧原值。
                    originalVolume = saved;
                } else {
                    // 新会话尚未READY、主线尚未起播；先完成上一次残留恢复。
                    restoreVolume(saved);
                    originalVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);
                }
            } else originalVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            if (originalVolume < 0 || originalVolume > audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))
                throw new IllegalStateException("ALARM_UI_INVALID_SAVED_VOLUME");
            if (!recovery.edit().putString(SESSION, session).putInt(VOLUME, originalVolume).commit())
                throw new IllegalStateException("ALARM_UI_RECOVERY_WRITE_FAILED");
        }

        private void restoreVolume(int volume) {
            if (audio == null || volume < 0 || volume > audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))
                throw new IllegalStateException("ALARM_UI_RECOVERY_UNAVAILABLE");
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) != volume)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0);
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) != volume)
                throw new IllegalStateException("ALARM_UI_RECOVERY_NOT_APPLIED");
        }

        private void failed() {
            if (owner == null || !owner.isBinderAlive()) { finishLease(true, true); return; }
            // 活跃拥有者协议异常时不能擅自恢复其音量；请求停止并保留恢复记录。
            try { call(owner, STOP); } catch (Exception ignored) { }
            try {
                if (call(owner, GET_ACTIVE) == 0) { finishLease(true, true); return; }
            } catch (Exception ignored) { }
            if (!owner.isBinderAlive()) { finishLease(true, true); return; }
            Log.w(TAG, "ALARM_UI_LIVE_OWNER_FAILURE_RECOVERY_RETAINED");
            finishLease(false, false);
        }

        private void finishLease(boolean restore, boolean clear) {
            finishLease(restore, clear, true);
        }

        private void finishLease(boolean restore, boolean clear, boolean dismiss) {
            if (closed) return;
            closed = true;
            if (restore) {
                try {
                    int volume = recovery.getInt(VOLUME, originalVolume);
                    restoreVolume(volume);
                } catch (Exception failure) { clear = false; Log.w(TAG, "ALARM_UI_VOLUME_RECOVERY_FAILED"); }
            }
            if (clear) {
                try {
                    if (!recovery.edit().remove(VOLUME).remove(SESSION).commit()) Log.w(TAG, "ALARM_UI_RECOVERY_CLEAR_FAILED");
                } catch (RuntimeException failure) { Log.w(TAG, "ALARM_UI_RECOVERY_CLEAR_FAILED"); }
            }
            if (linked) { try { owner.unlinkToDeath(death, 0); } catch (RuntimeException ignored) { } }
            main.post(() -> {
                if (current == this) current = null;
                if (dismiss && activity != null && activity.lease == this && !activity.handoffPending) activity.closeWindow();
            });
        }

        private static int call(IBinder target, int code) throws RemoteException {
            return call(target, code, 0);
        }

        private static int call(IBinder target, int code, int originalVolume) throws RemoteException {
            if (target == null) throw new RemoteException("ALARM_UI_MISSING_OWNER");
            Parcel request = Parcel.obtain(), reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(DESCRIPTOR);
                if (code == READY) request.writeInt(originalVolume);
                if (!target.transact(code, request, reply, 0)) throw new RemoteException("ALARM_UI_UNKNOWN_TRANSACTION");
                reply.readException();
                if (code != GET_ACTIVE) return 0;
                if (reply.dataAvail() != 4) throw new RemoteException("ALARM_UI_INVALID_ACTIVE_REPLY");
                int active = reply.readInt();
                if (active != 0 && active != 1) throw new RemoteException("ALARM_UI_INVALID_ACTIVE");
                return active;
            } finally { request.recycle(); reply.recycle(); }
        }
    }
}
