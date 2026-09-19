package net.elfradio.d31bootstrap;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import net.elfradio.d31bootstrap.qr.QrCode;

/**
 * 只负责把服务端推来的管理链接画成二维码。不持有设备令牌，也不向服务端发起任何请求。
 *
 * 结束原因（人工关闭 / 到期 / 被新任务顶替 / 核心撤下）经 Binder 租约回报给 :visual 进程里的
 * ShareLinkWindow——那是同一个应用、同一个 uid 的进程，不是 root 核心。核心按轮次去 :visual 取结果，
 * 这样就不必在 uid 0 那边再开一个受理入口。回执由核心统一发，本窗口不碰网络。
 */
public final class ShareLinkActivity extends Activity {
    static final String DESCRIPTOR = "net.elfradio.d31bootstrap.SHARE_LINK";
    static final String EXTRA = "share_link_lease", EXTRA_DISMISS = "share_link_dismiss";
    static final int READY = 1, ENDED = 2;
    // 取值与核心共用 ShareLinkOutcome，避免两边各写一份字符串。
    static final String OUTCOME_SHOWN = ShareLinkOutcome.SHOWN, OUTCOME_DISMISSED = ShareLinkOutcome.DISMISSED,
            OUTCOME_EXPIRED = ShareLinkOutcome.EXPIRED, OUTCOME_SUPERSEDED = ShareLinkOutcome.SUPERSEDED,
            OUTCOME_FAILED = ShareLinkOutcome.FAILED;

    private final Handler main = new Handler(Looper.getMainLooper());
    private IBinder owner;
    private String session = "";
    private long deadlineElapsed;
    private TextView remaining;
    private boolean ended;
    private final Runnable tick = this::refresh;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        accept(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 核心主动撤下：只结清当前这次，不接管新租约。会话号不符说明要撤的是更早那次，忽略。
        String dismiss = intent == null ? null : intent.getStringExtra(EXTRA_DISMISS);
        if (dismiss != null) {
            if (dismiss.equals(session)) { finish(OUTCOME_DISMISSED); super.finish(); }
            return;
        }
        // 新任务顶替旧的：先把旧租约结清为superseded，再接管新的，避免核心一直等不到回报。
        finish(OUTCOME_SUPERSEDED);
        accept(intent);
    }

    private void accept(Intent intent) {
        Bundle lease = intent == null ? null : intent.getBundleExtra(EXTRA);
        if (lease == null) { super.finish(); return; }
        String url = lease.getString("url", ""), qrText = lease.getString("qr_text", "");
        long displayMs = lease.getLong("display_ms", 0);
        if (url.isEmpty() || qrText.isEmpty() || displayMs <= 0) { super.finish(); return; }
        owner = lease.getBinder("owner");
        session = lease.getString("session", "");
        ended = false;
        deadlineElapsed = SystemClock.elapsedRealtime() + displayMs;
        try { setContentView(build(url, qrText)); }
        catch (Exception failure) { report(OUTCOME_FAILED, failure.getClass().getSimpleName()); super.finish(); return; }
        report(OUTCOME_SHOWN, "");
        main.removeCallbacks(tick);
        main.post(tick);
    }

    /** 屏幕是1280x800横屏：二维码占左侧，说明文字在右侧，扫码距离内足够大。 */
    private View build(String url, String qrText) {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        int shorter = Math.min(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        int qrPx = Math.max(160, Math.min(shorter - 2 * pad, (int) (360 * density)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(Color.WHITE);
        row.setPadding(pad, pad, pad, pad);
        row.setGravity(Gravity.CENTER);

        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap(qrText, qrPx));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(image, new LinearLayout.LayoutParams(qrPx, qrPx));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, 0, 0, 0);
        column.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("扫码管理这台设备");
        title.setTextColor(Color.BLACK);
        title.setTextSize(22);
        column.addView(title);

        TextView address = new TextView(this);
        address.setText(url);
        address.setTextColor(Color.DKGRAY);
        address.setTextSize(13);
        address.setPadding(0, pad / 2, 0, 0);
        address.setTextIsSelectable(true);
        column.addView(address);

        remaining = new TextView(this);
        remaining.setTextColor(Color.DKGRAY);
        remaining.setTextSize(13);
        remaining.setPadding(0, pad / 2, 0, 0);
        column.addView(remaining);

        Button close = new Button(this);
        close.setText("关闭");
        close.setOnClickListener(view -> { finish(OUTCOME_DISMISSED); super.finish(); });
        column.addView(close);

        row.addView(column, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** 全大写走字母数字模式，纠错M；小写会退回字节模式，核心已在回执里注明。 */
    static Bitmap bitmap(String text, int sizePx) {
        QrCode code = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int quiet = 2, modules = code.size + quiet * 2;
        int cell = Math.max(1, sizePx / modules), side = modules * cell;
        Bitmap output = Bitmap.createBitmap(side, side, Bitmap.Config.RGB_565);
        int[] row = new int[side];
        for (int y = 0; y < side; y++) {
            int my = y / cell - quiet;
            for (int x = 0; x < side; x++) row[x] = code.getModule(x / cell - quiet, my) ? Color.BLACK : Color.WHITE;
            output.setPixels(row, 0, side, 0, y, side, 1);
        }
        return output;
    }

    private void refresh() {
        if (ended) return;
        long left = deadlineElapsed - SystemClock.elapsedRealtime();
        if (left <= 0) { finish(OUTCOME_EXPIRED); super.finish(); return; }
        remaining.setText("剩余 " + Math.max(1, (left + 999) / 1000) + " 秒");
        main.postDelayed(tick, 1000);
    }

    private void finish(String outcome) {
        if (ended) return;
        ended = true;
        main.removeCallbacks(tick);
        report(outcome, "");
    }

    @Override protected void onDestroy() {
        // 进程被回收或窗口被系统收走也要结清，否则核心会一直等不到结果。
        finish(OUTCOME_DISMISSED);
        super.onDestroy();
    }

    private void report(String outcome, String reason) {
        IBinder target = owner;
        if (target == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(session);
            data.writeString(outcome);
            data.writeString(reason);
            target.transact(OUTCOME_SHOWN.equals(outcome) ? READY : ENDED, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException unavailable) { /* 核心已退出；本窗口继续按自己的时限收尾 */ }
        finally { data.recycle(); }
    }
}
