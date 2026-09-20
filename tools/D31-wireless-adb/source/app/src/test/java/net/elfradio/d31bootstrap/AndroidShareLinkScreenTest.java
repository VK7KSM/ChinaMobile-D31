package net.elfradio.d31bootstrap;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 核心侧窗口适配器：把 :visual 回的窗口快照翻成 ShareLinkTasks 要的两次回报。
 *
 * 这层最容易出的错是把快照重复翻成回报——查询是按轮次打的，同一个「已显示」会被看到很多次，
 * 每次都报一遍的话核心就会重复发 running，甚至把已结清的任务再结清一次。
 */
public class AndroidShareLinkScreenTest {
    private static final class Clock implements ShareLinkTasks.Clock {
        long wall = 1700000000000L, elapsed = 10000L;
        @Override public long wall() { return wall; }
        @Override public long elapsed() { return elapsed; }
    }

    private static final class Window implements AndroidShareLinkScreen.Bridge {
        final List<JSONObject> commands = new ArrayList<>();
        String session = "", state = "starting", outcome = "", reason = "";
        long shownAtMs, endedAtMs;
        Exception failure;

        @Override public JSONObject request(JSONObject command) throws Exception {
            commands.add(command);
            if (failure != null) throw failure;
            if (ShareLinkPayload.OP_SHOW.equals(command.optString("operation"))) {
                session = command.optString("session");
                state = "starting"; outcome = ""; reason = ""; shownAtMs = 0; endedAtMs = 0;
            }
            return snapshot();
        }

        JSONObject snapshot() throws Exception {
            return new JSONObject().put("session", session).put("state", state)
                    .put("outcome", outcome).put("reason", reason)
                    .put("shown_at_ms", shownAtMs).put("ended_at_ms", endedAtMs);
        }

        void shown(long at) { state = "showing"; shownAtMs = at; }
        void ended(String value, String why, long at) { state = "ended"; outcome = value; reason = why; endedAtMs = at; }
    }

    private static final class Reports implements ShareLinkTasks.Listener {
        final List<String> outcomes = new ArrayList<>(), sessions = new ArrayList<>();
        @Override public void outcome(String session, String outcome, String reason) {
            sessions.add(session); outcomes.add(outcome);
        }
    }

    private final Clock clock = new Clock();
    private final Window window = new Window();
    private final Reports reports = new Reports();
    private final AndroidShareLinkScreen screen = new AndroidShareLinkScreen(window, clock);

    private ShareLinkPayload payload() throws Exception {
        return ShareLinkPayload.parse(new JSONObject().put("url", "https://v.elfradio.net/m/ABC123")
                .put("qr_text", "HTTPS://V.ELFRADIO.NET/M/ABC123")
                .put("link_expires_at", clock.wall + 600000)
                .put("display_ms", 30000), clock.wall);
    }

    private void poll() { clock.elapsed += AndroidShareLinkScreen.POLL_MS; screen.pump(); }

    @Test public void showSendsTheLeaseAndCarriesNoCredentials() throws Exception {
        screen.show(payload(), "session-1", reports);
        JSONObject command = window.commands.get(0);
        assertEquals(ShareLinkPayload.OP_SHOW, command.optString("operation"));
        assertEquals("session-1", command.optString("session"));
        assertEquals("https://v.elfradio.net/m/ABC123", command.optString("url"));
        assertEquals("HTTPS://V.ELFRADIO.NET/M/ABC123", command.optString("qr_text"));
        assertEquals(30000, command.optLong("display_ms"));
        // 桥上只传链接本身，设备令牌和任何口令都不经过这里。
        for (String forbidden : new String[]{"password", "passcode", "pin", "secret", "token", "credential"})
            assertFalse("命令里不得出现 " + forbidden, command.has(forbidden));
        assertTrue("窗口还没回报之前不得有任何结果", reports.outcomes.isEmpty());
    }

    @Test public void eachOutcomeIsReportedExactlyOnceNoMatterHowOftenItIsPolled() throws Exception {
        screen.show(payload(), "session-2", reports);

        window.shown(clock.wall);
        poll(); poll(); poll();
        assertEquals("反复查询不得重复报已显示",
                java.util.Collections.singletonList(ShareLinkOutcome.SHOWN), reports.outcomes);

        window.ended(ShareLinkOutcome.DISMISSED, "", clock.wall + 5000);
        poll(); poll();
        assertEquals(java.util.Arrays.asList(ShareLinkOutcome.SHOWN, ShareLinkOutcome.DISMISSED), reports.outcomes);
        for (String session : reports.sessions) assertEquals("session-2", session);

        // 结清之后不再查询，省掉无谓的跨进程往返。
        int before = window.commands.size();
        poll(); poll();
        assertEquals(before, window.commands.size());
    }

    @Test public void shownIsStillReportedWhenThePollOnlyEverSeesTheEndedSnapshot() throws Exception {
        screen.show(payload(), "session-3", reports);
        // 两次查询之间窗口可能已经显示完又关掉了，中间态根本没被看到过。
        window.shown(clock.wall);
        window.ended(ShareLinkOutcome.DISMISSED, "", clock.wall + 1000);
        poll();
        assertEquals("核心要靠 shown 记下开始时刻，不能跳过",
                java.util.Arrays.asList(ShareLinkOutcome.SHOWN, ShareLinkOutcome.DISMISSED), reports.outcomes);
    }

    @Test public void staleSessionSnapshotIsIgnored() throws Exception {
        screen.show(payload(), "session-4", reports);
        // 窗口那边已经换成了别的会话，这份快照不属于当前这次。
        window.session = "session-old";
        window.ended(ShareLinkOutcome.DISMISSED, "", clock.wall);
        poll();
        assertTrue("旧会话的快照不得当成当前结果", reports.outcomes.isEmpty());
    }

    @Test public void unreachableWindowLeavesTheTaskOpenForTheCoreDeadlineToSettle() throws Exception {
        screen.show(payload(), "session-5", reports);
        window.failure = new java.io.IOException("VISUAL_BRIDGE_TIMEOUT");
        poll(); poll();
        // 取不到就等下一圈，不能自己编一个终态；真取不到由核心的到期兜底结清。
        assertTrue(reports.outcomes.isEmpty());

        window.failure = null;
        window.shown(clock.wall);
        window.ended(ShareLinkOutcome.EXPIRED, "", clock.wall + 1000);
        poll();
        assertEquals(java.util.Arrays.asList(ShareLinkOutcome.SHOWN, ShareLinkOutcome.EXPIRED), reports.outcomes);
    }

    @Test public void emptyOutcomeFromTheWindowIsTreatedAsFailureNotAsSuccess() throws Exception {
        screen.show(payload(), "session-6", reports);
        window.shown(clock.wall);
        window.state = "ended";
        window.outcome = "";
        poll();
        // 窗口说结束了却没说结果，只能当没显示成功，不能猜成正常结束。
        assertEquals(java.util.Arrays.asList(ShareLinkOutcome.SHOWN, ShareLinkOutcome.FAILED), reports.outcomes);
    }

    @Test public void dismissTellsTheWindowAndStopsPolling() throws Exception {
        screen.show(payload(), "session-7", reports);
        window.shown(clock.wall);
        poll();
        int before = window.commands.size();

        screen.dismiss();
        JSONObject command = window.commands.get(window.commands.size() - 1);
        assertEquals(ShareLinkPayload.OP_DISMISS, command.optString("operation"));
        assertEquals("session-7", command.optString("session"));

        // 撤下之后核心已自行结清，窗口再回什么都不该再变成回报。
        window.ended(ShareLinkOutcome.DISMISSED, "", clock.wall);
        poll(); poll();
        assertEquals(before + 1, window.commands.size());
        assertEquals(java.util.Collections.singletonList(ShareLinkOutcome.SHOWN), reports.outcomes);
    }

    @Test public void dismissWithoutAnActiveWindowDoesNothing() {
        screen.dismiss();
        assertTrue("没有在显示的窗口就不该往桥上打空命令", window.commands.isEmpty());
    }
}
