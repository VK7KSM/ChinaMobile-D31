package net.elfradio.d31bootstrap;

import java.io.IOException;
import org.json.JSONObject;

/**
 * 核心侧的二维码窗口适配器：把 ShareLinkTasks 要的「显示 / 撤下 / 结果」落到既有的 :visual 桥上。
 *
 * 为什么是拉不是推：窗口一显示就是几分钟，而那条桥是6秒同步应答，等不起；
 * 让窗口反过来直接回报给 root 核心又要在 uid 0 那边多开一个受理入口，不值得。
 * 所以 show 只管拉起，结果由核心的工作循环调 pump() 按轮次去取，取到了再喂回 Listener，
 * ShareLinkTasks 那边仍然是推的语义，判定逻辑不受这里影响。
 *
 * 线程约定：show/dismiss/pump 必须都由核心工作循环那一个线程调用。
 * 本类回调 Listener 时仍持有自己的锁，而 ShareLinkTasks 调 show 时也持有它自己的锁，
 * 两个线程进来就是锁序反转。现在只有工作循环一个线程，所以成立；要加第二个调用方得先拆这层回调。
 */
final class AndroidShareLinkScreen implements ShareLinkTasks.Screen {
    /** 一次桥调用就是一次跨进程往返，别每圈都打；窗口结束晚认几秒不影响任何判定。 */
    static final long POLL_MS = 3000L;

    interface Bridge { JSONObject request(JSONObject command) throws Exception; }

    private final Bridge bridge;
    private final ShareLinkTasks.Clock clock;
    private ShareLinkTasks.Listener listener;
    private String session = "";
    private boolean shown, ended;
    private long polledElapsed;

    AndroidShareLinkScreen(Bridge bridge, ShareLinkTasks.Clock clock) {
        this.bridge = bridge; this.clock = clock;
    }

    @Override public synchronized void show(ShareLinkPayload payload, String session,
                                            ShareLinkTasks.Listener listener) throws Exception {
        JSONObject snapshot = bridge.request(new JSONObject()
                .put("operation", ShareLinkPayload.OP_SHOW)
                .put("session", session)
                .put("url", payload.url)
                .put("qr_text", payload.qrText)
                .put("display_ms", payload.displayMs));
        this.session = session; this.listener = listener;
        shown = false; ended = false; polledElapsed = clock.elapsed();
        consume(snapshot);
    }

    @Override public synchronized void dismiss() {
        if (session.isEmpty()) return;
        String owned = session;
        session = ""; listener = null; shown = false; ended = true;
        try {
            bridge.request(new JSONObject()
                    .put("operation", ShareLinkPayload.OP_DISMISS)
                    .put("session", owned));
        } catch (Exception unavailable) {
            // 窗口进程可能已经没了；核心那边已按到期结清，这里不再重试。
            System.err.println("SHARE_LINK_DISMISS_UNCONFIRMED " + owned);
        }
    }

    /** 由核心工作循环驱动，和 ShareLinkTasks.tick() 同一圈调用。 */
    synchronized void pump() {
        if (session.isEmpty() || ended) return;
        if (clock.elapsed() - polledElapsed < POLL_MS) return;
        polledElapsed = clock.elapsed();
        try {
            consume(bridge.request(new JSONObject()
                    .put("operation", ShareLinkPayload.OP_QUERY)
                    .put("session", session)));
        } catch (Exception unavailable) {
            // 取不到就等下一圈；真的一直取不到，核心的到期兜底会把任务结清。
            System.err.println("SHARE_LINK_QUERY_UNAVAILABLE " + session);
        }
    }

    /** 把窗口快照翻成 Listener 的两次回报：先 shown，后终态。每种最多报一次。 */
    private void consume(JSONObject snapshot) throws Exception {
        if (snapshot == null) throw new IOException("SHARE_LINK_WINDOW_SILENT");
        // 迟到的旧会话快照不得当成当前这次的结果。
        if (!session.equals(snapshot.optString("session"))) return;
        ShareLinkTasks.Listener target = listener;
        if (target == null) return;
        String state = snapshot.optString("state");
        if (!shown && snapshot.optLong("shown_at_ms") > 0) {
            shown = true;
            target.outcome(session, ShareLinkOutcome.SHOWN, "");
        }
        if (!"ended".equals(state) || ended) return;
        ended = true;
        String owned = session, outcome = snapshot.optString("outcome"), reason = snapshot.optString("reason");
        session = ""; listener = null;
        target.outcome(owned, outcome.isEmpty() ? ShareLinkOutcome.FAILED : outcome, reason);
    }
}
