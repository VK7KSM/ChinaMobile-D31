package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.IOException;
import org.json.JSONObject;

/**
 * 二维码窗口在应用侧的拥有者，跑在 :visual 进程里。
 *
 * 为什么结果要在这里落地、而不是让窗口直接回报给 root 核心：核心那个 Binder 是 uid 0 持有的，
 * 往它上面再开一个受理入口就等于给应用进程多一条打进 root 的通路。窗口和本类同属一个应用、同一个 uid，
 * 这条回报是应用内 IPC，不跨权限边界；核心则沿用既有的那条桥按轮次来取结果，受理面一格不放宽。
 *
 * 本类只记录「窗口现在是什么状态」，不判定任务成败，也不发回执，那些都在核心侧的 ShareLinkTasks 里。
 */
final class ShareLinkWindow {
    static final String ACTIVITY = "net.elfradio.d31bootstrap.ShareLinkActivity";
    static final String DESCRIPTOR = "net.elfradio.d31bootstrap.SHARE_LINK";
    static final String EXTRA = "share_link_lease", EXTRA_DISMISS = "share_link_dismiss";
    static final int READY = 1, ENDED = 2;
    private static final String ID = "[A-Za-z0-9_-]{1,96}";

    private final Context context;
    /** 窗口进程被系统回收时不会有任何回报，超过这个时限就按未显示处理，不能一直等。 */
    private static final long LAUNCH_GRACE_MS = 15000L;

    private String session = "", outcome = "", reason = "";
    private long launchedElapsed, shownAtMs, endedAtMs;
    private boolean showing, settled;

    ShareLinkWindow(Context context) { this.context = context; }

    /** 窗口进程与本进程同属一个应用；别的 uid 不得伪造结束回报。 */
    private final Binder owner = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel out, int flags) throws RemoteException {
            if (Binder.getCallingUid() != android.os.Process.myUid()) return false;
            if (code != READY && code != ENDED) return super.onTransact(code, data, out, flags);
            data.enforceInterface(DESCRIPTOR);
            accept(code, data.readString(), data.readString(), data.readString());
            return true;
        }
    };

    private synchronized void accept(int code, String reported, String value, String why) {
        // 迟到的旧会话回报不得覆盖当前这次的结果。
        if (session.isEmpty() || !session.equals(reported)) return;
        if (code == READY) {
            if (shownAtMs == 0) { shownAtMs = System.currentTimeMillis(); showing = true; }
            return;
        }
        if (settled) return;
        settled = true; showing = false;
        outcome = value == null ? "" : value;
        reason = why == null ? "" : why;
        endedAtMs = System.currentTimeMillis();
    }

    synchronized JSONObject show(JSONObject params) throws Exception {
        String requested = params.optString("session");
        if (!requested.matches(ID)) throw new IOException("SHARE_LINK_SESSION_INVALID");
        String url = params.optString("url"), qrText = params.optString("qr_text");
        long displayMs = params.optLong("display_ms");
        if (url.isEmpty() || qrText.isEmpty() || displayMs <= 0) throw new IOException("SHARE_LINK_LEASE_INVALID");

        Bundle lease = new Bundle();
        lease.putString("url", url);
        lease.putString("qr_text", qrText);
        lease.putLong("display_ms", displayMs);
        lease.putString("session", requested);
        lease.putBinder("owner", owner);

        session = requested; outcome = ""; reason = "";
        shownAtMs = 0; endedAtMs = 0; showing = false; settled = false;
        launchedElapsed = SystemClock.elapsedRealtime();
        context.startActivity(new Intent()
                .setClassName(context.getPackageName(), ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA, lease));
        return snapshot();
    }

    /** 撤下当前窗口；窗口已经自己结束过就保留原结果，不改写成 dismissed。 */
    synchronized JSONObject dismiss(String requested) throws Exception {
        if (!session.isEmpty() && (requested == null || requested.isEmpty() || session.equals(requested))) {
            context.startActivity(new Intent()
                    .setClassName(context.getPackageName(), ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_DISMISS, session));
            if (!settled) {
                settled = true; showing = false;
                outcome = "dismissed"; reason = "core_requested"; endedAtMs = System.currentTimeMillis();
            }
        }
        return snapshot();
    }

    synchronized JSONObject snapshot() throws Exception {
        // 拉起后一直没有任何回报，说明窗口进程没起来或被系统收走了，按未显示结清。
        if (!settled && shownAtMs == 0 && !session.isEmpty()
                && SystemClock.elapsedRealtime() - launchedElapsed > LAUNCH_GRACE_MS) {
            settled = true; showing = false;
            outcome = "failed"; reason = "window_never_reported"; endedAtMs = System.currentTimeMillis();
        }
        String state = session.isEmpty() ? "idle" : settled ? "ended" : showing ? "showing" : "starting";
        return new JSONObject().put("session", session).put("state", state)
                .put("outcome", outcome).put("reason", reason)
                .put("shown_at_ms", shownAtMs).put("ended_at_ms", endedAtMs);
    }
}
