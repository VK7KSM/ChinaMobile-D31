package net.elfradio.d31bootstrap;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * 分享链接任务的核心侧判定与回执。
 *
 * 两条最容易错又最难在设备上看出来的：
 * 一是显示之前必须先发 claimed，服务端不接受 pending 直接跳 running；
 * 二是终态回执没被确认就必须一直重试，否则设备看着结束了、服务端那边永远挂着。
 * 另外 shown/dismissed/expired 这些语义只能待在 result.share_link.outcome 里。
 */
public class ShareLinkTasksTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    /** 服务端任务状态机允许的取值，回执不得越界。 */
    private static final List<String> STATES =
            java.util.Arrays.asList("claimed", "running", "success", "failed", "rejected");
    /** outcome 的全部合法取值。注意 failed 与状态机同名但不同字段，两者不可互相推断。 */
    private static final List<String> OUTCOMES = java.util.Arrays.asList(ShareLinkOutcome.SHOWN,
            ShareLinkOutcome.DISMISSED, ShareLinkOutcome.EXPIRED, ShareLinkOutcome.SUPERSEDED, ShareLinkOutcome.FAILED);

    private static final class Clock implements ShareLinkTasks.Clock {
        long wall = 1700000000000L, elapsed = 10000L;
        @Override public long wall() { return wall; }
        @Override public long elapsed() { return elapsed; }
    }

    private static final class Screen implements ShareLinkTasks.Screen {
        ShareLinkTasks.Listener listener;
        String session = "";
        int shows, dismisses;
        Exception failure;
        @Override public void show(ShareLinkPayload payload, String session, ShareLinkTasks.Listener listener) throws Exception {
            if (failure != null) throw failure;
            shows++; this.session = session; this.listener = listener;
        }
        @Override public void dismiss() { dismisses++; }
    }

    /**
     * 按服务端的迁移表模拟。要点是不在表里的回执被静默丢弃：HTTP 仍是 200、ok 仍是 true，
     * 只有回读的任务状态才说明到底采没采纳。用 ok 判定采纳的实现在这个假服务端上必然露馅。
     */
    private static final class Server {
        final java.util.Map<String, String> states = new java.util.HashMap<>();
        final List<JSONObject> received = new ArrayList<>(), accepted = new ArrayList<>();
        /** 为真时一概不迁移，但照样回 200/ok 和当前状态，模拟静默丢弃。 */
        boolean drop;

        static boolean allowed(String from, String to) {
            if ("pending".equals(from)) return java.util.Arrays.asList("claimed", "rejected", "expired", "failed").contains(to);
            if ("claimed".equals(from)) return java.util.Arrays.asList("running", "rejected", "expired", "failed").contains(to);
            if ("running".equals(from)) return java.util.Arrays.asList("success", "failed", "rejected").contains(to);
            return false;
        }

        String apply(JSONObject receipt) {
            String id = receipt.optString("task_id"), to = receipt.optString("state");
            String from = states.containsKey(id) ? states.get(id) : "pending";
            received.add(receipt);
            if (!drop && (from.equals(to) || allowed(from, to))) { states.put(id, to); accepted.add(receipt); return to; }
            return from;
        }

        String state(String id) { return states.containsKey(id) ? states.get(id) : "pending"; }
    }

    private final Server server = new Server();
    private final List<JSONObject> sent = server.received;
    private final Clock clock = new Clock();
    private final Screen screen = new Screen();
    private boolean alarm;
    /** 非空时所有回执都发不出去，模拟服务端不可达。 */
    private Exception offline;

    private ShareLinkTasks tasks() throws Exception {
        return new ShareLinkTasks(temp.getRoot(), "D31-TEST", screen, receipt -> {
            if (offline != null) throw offline;
            return server.apply(receipt);
        }, () -> alarm, clock);
    }

    private JSONObject task(String id) throws Exception {
        return new JSONObject().put("id", id).put("type", ShareLinkPayload.TYPE)
                .put("expires_at", clock.wall + 60000)
                .put("params", new JSONObject().put("url", "https://v.elfradio.net/m/ABC123")
                        .put("qr_text", "HTTPS://V.ELFRADIO.NET/M/ABC123")
                        .put("link_expires_at", clock.wall + 600000)
                        .put("display_ms", 30000));
    }

    private JSONObject only() { assertEquals("应当只有一条回执", 1, sent.size()); return sent.get(0); }

    private JSONObject last() { assertFalse("应当有回执", sent.isEmpty()); return sent.get(sent.size() - 1); }

    private static JSONObject link(JSONObject receipt) {
        JSONObject result = receipt.optJSONObject("result");
        assertNotNull("回执缺少 result", result);
        JSONObject link = result.optJSONObject("share_link");
        assertNotNull("语义必须落在 result.share_link 里", link);
        return link;
    }

    private void assertLegal(JSONObject receipt) {
        String state = receipt.optString("state");
        assertTrue("state 越出服务端状态机：" + state, STATES.contains(state));
        assertEquals("D31-TEST", receipt.optString("device_id"));
        if (receipt.opt("result") == null) {
            // 只有 claimed 和补阶梯用的 running 可以不带业务结果，终态一律要带。
            assertTrue("终态回执必须带 result：" + state,
                    "claimed".equals(state) || "running".equals(state));
            return;
        }
        String outcome = link(receipt).optString("outcome");
        assertTrue("outcome 取值不认识：" + outcome, OUTCOMES.contains(outcome));
        // shown/dismissed/expired/superseded 只属于 outcome，一旦出现在 state 里服务端会整条拒掉。
        assertFalse("语义值被塞进了 state：" + state,
                OUTCOMES.contains(state) && !ShareLinkOutcome.FAILED.equals(state));
    }

    private void assertAllLegal() { for (JSONObject receipt : sent) assertLegal(receipt); }

    private List<String> states() { return statesOf(sent); }

    private static List<String> statesOf(List<JSONObject> receipts) {
        List<String> values = new ArrayList<>();
        for (JSONObject receipt : receipts) values.add(receipt.optString("state"));
        return values;
    }

    @Test public void claimIsReportedBeforeTheWindowIsEverShown() throws Exception {
        try (ShareLinkTasks owner = tasks()) {
            owner.accept(task("task-0"));
            // 服务端的状态机是 pending → claimed → running，跳过 claimed 整条回执会被拒。
            assertEquals(java.util.Collections.singletonList("claimed"), states());
            assertEquals(1, screen.shows);
            assertNull("claimed 回执不带业务结果", only().opt("result"));

            screen.listener.outcome(screen.session, ShareLinkOutcome.SHOWN, "");
            assertEquals(java.util.Arrays.asList("claimed", "running"), states());
            assertAllLegal();
        }
    }

    @Test public void showThenDismissReportsRunningThenSuccessWithOutcomeOutsideState() throws Exception {
        try (ShareLinkTasks owner = tasks()) {
            owner.accept(task("task-1"));
            screen.listener.outcome(screen.session, ShareLinkOutcome.SHOWN, "");
            JSONObject running = last();
            assertEquals("running", running.optString("state"));
            assertEquals(ShareLinkOutcome.SHOWN, link(running).optString("outcome"));
            assertEquals("全大写链接应当走字母数字模式", "alphanumeric", link(running).optString("qr_mode"));
            assertEquals(clock.wall, link(running).optLong("shown_at_ms"));

            long shownAt = clock.wall;
            clock.wall += 5000;
            screen.listener.outcome(screen.session, ShareLinkOutcome.DISMISSED, "");
            JSONObject done = last();
            assertEquals("人工关闭属于正常完成，不是失败", "success", done.optString("state"));
            assertEquals(ShareLinkOutcome.DISMISSED, link(done).optString("outcome"));
            assertEquals("终态要保留开始显示的时刻", shownAt, link(done).optLong("shown_at_ms"));
            assertEquals(clock.wall, link(done).optLong("ended_at_ms"));
            assertEquals(java.util.Arrays.asList("claimed", "running", "success"), states());
            assertAllLegal();
        }
    }

    @Test public void settledTaskIsNeverShownAgainOnRedelivery() throws Exception {
        try (ShareLinkTasks owner = tasks()) {
            owner.accept(task("task-2"));
            screen.listener.outcome(screen.session, ShareLinkOutcome.DISMISSED, "");
            assertEquals(1, screen.shows);
            sent.clear();
            // 推送重投或补领时不能再弹一次窗口，也不该再发回执。
            owner.accept(task("task-2"));
            assertEquals(1, screen.shows);
            assertTrue(sent.isEmpty());
        }
        // 换一个拥有者（相当于核心重启）同样不得重弹、不得重发。
        try (ShareLinkTasks restarted = tasks()) {
            restarted.accept(task("task-2"));
            assertEquals(1, screen.shows);
            assertTrue(sent.isEmpty());
        }
    }

    @Test public void alarmHoldsPriorityAndExpiredEnvelopeIsRejectedWithoutShowing() throws Exception {
        alarm = true;
        try (ShareLinkTasks owner = tasks()) {
            owner.accept(task("task-3"));
            JSONObject rejected = only();
            assertLegal(rejected);
            assertEquals("rejected", rejected.optString("state"));
            assertEquals("alarm_busy", link(rejected).optString("reason"));
            assertEquals("警报占用时一格屏幕都不给二维码", 0, screen.shows);
        }
        alarm = false;
        sent.clear();
        try (ShareLinkTasks owner = tasks()) {
            // 设备离线很久后再上线，过期的信封不应该突然弹出二维码。
            owner.accept(task("task-4").put("expires_at", clock.wall - 1));
            JSONObject rejected = only();
            assertLegal(rejected);
            assertEquals("rejected", rejected.optString("state"));
            assertEquals("envelope_expired", link(rejected).optString("reason"));
            assertEquals(0, screen.shows);
        }
    }

    @Test public void badPayloadAndScreenFailureBothReportFailedWithoutLeavingTaskOpen() throws Exception {
        try (ShareLinkTasks owner = tasks()) {
            JSONObject bad = task("task-5");
            bad.getJSONObject("params").put("password", "1234");
            owner.accept(bad);
            JSONObject failed = only();
            assertLegal(failed);
            assertEquals("failed", failed.optString("state"));
            assertEquals("口令类载荷必须在显示之前就被挡住，连 claimed 都不发", 0, screen.shows);

            sent.clear();
            screen.failure = new IllegalStateException("no window");
            owner.accept(task("task-6"));
            assertEquals(java.util.Arrays.asList("claimed", "failed"), states());
            assertAllLegal();

            // 窗口没起来就必须放开占用，否则后面的任务全被判成被顶替。
            screen.failure = null;
            sent.clear();
            owner.accept(task("task-7"));
            assertEquals(1, screen.shows);
            assertEquals("接管新任务时不该有结清旧任务的回执", java.util.Collections.singletonList("claimed"), states());
        }
    }

    @Test public void deadlineSweepSettlesWhenTheWindowProcessNeverReportsBack() throws Exception {
        try (ShareLinkTasks owner = tasks()) {
            owner.accept(task("task-8"));
            screen.listener.outcome(screen.session, ShareLinkOutcome.SHOWN, "");
            sent.clear();

            // 显示时长30秒，加上宽限期之前不得提前撤下。
            clock.elapsed += 30000 + ShareLinkTasks.GRACE_MS - 1;
            owner.tick();
            assertTrue("未到时限不得撤下", sent.isEmpty());
            assertEquals(0, screen.dismisses);

            clock.elapsed += 1;
            owner.tick();
            JSONObject swept = only();
            assertLegal(swept);
            assertEquals("success", swept.optString("state"));
            assertEquals(ShareLinkOutcome.EXPIRED, link(swept).optString("outcome"));
            assertEquals(1, screen.dismisses);

            // 结清之后再扫一次不得重复发回执。
            sent.clear();
            clock.elapsed += 60000;
            owner.tick();
            assertTrue(sent.isEmpty());
        }
    }

    @Test public void lateReportFromASupersededSessionIsDiscarded() throws Exception {
        try (ShareLinkTasks owner = tasks()) {
            owner.accept(task("task-9"));
            ShareLinkTasks.Listener stale = screen.listener;
            String staleSession = screen.session;
            stale.outcome(staleSession, ShareLinkOutcome.SHOWN, "");
            sent.clear();

            owner.accept(task("task-10"));
            // 旧任务先被结清为被顶替，然后才是新任务的 claimed。
            assertEquals(java.util.Arrays.asList("claimed", "success"), states());
            JSONObject superseded = null;
            for (JSONObject receipt : sent) if ("success".equals(receipt.optString("state"))) superseded = receipt;
            assertNotNull(superseded);
            assertEquals("task-9", superseded.optString("task_id"));
            assertEquals(ShareLinkOutcome.SUPERSEDED, link(superseded).optString("outcome"));
            assertEquals(2, screen.shows);
            assertNotEquals(staleSession, screen.session);

            sent.clear();
            // 旧窗口退出时的迟到回报不得把新任务结清掉。
            stale.outcome(staleSession, ShareLinkOutcome.DISMISSED, "");
            assertTrue("迟到的旧会话回报必须丢弃", sent.isEmpty());

            screen.listener.outcome(screen.session, ShareLinkOutcome.DISMISSED, "");
            // task-10 没上报过 shown，服务端停在 claimed，所以终态要先补一条 running。
            for (JSONObject receipt : sent) assertEquals("task-10", receipt.optString("task_id"));
            assertEquals("success", server.state("task-10"));
            assertEquals("task-9 早已结清，不得被重开", "success", server.state("task-9"));
        }
    }

    @Test public void unconfirmedTerminalReceiptIsRetriedUntilTheServerTakesIt() throws Exception {
        try (ShareLinkTasks owner = tasks()) {
            owner.accept(task("task-11"));
            screen.listener.outcome(screen.session, ShareLinkOutcome.SHOWN, "");
            sent.clear();

            // 服务端不可达时结束显示：终态回执必须留着，不能当作已送达。
            offline = new java.io.IOException("unreachable");
            screen.listener.outcome(screen.session, ShareLinkOutcome.DISMISSED, "");
            assertTrue(sent.isEmpty());

            // 退避未到不重试，避免离线时空转。
            owner.tick();
            assertTrue(sent.isEmpty());

            clock.wall += ShareLinkTasks.RETRY_BASE_MS;
            offline = null;
            owner.tick();
            JSONObject resent = only();
            assertLegal(resent);
            assertEquals("success", resent.optString("state"));
            assertEquals("task-11", resent.optString("task_id"));
            assertEquals(ShareLinkOutcome.DISMISSED, link(resent).optString("outcome"));

            // 确认之后不得再发第三遍。
            sent.clear();
            clock.wall += ShareLinkTasks.RETRY_MAX_MS;
            owner.tick();
            assertTrue(sent.isEmpty());
        }
    }

    @Test public void claimThatTheServerNeverConfirmedDoesNotShowTheWindow() throws Exception {
        offline = new java.io.IOException("unreachable");
        try (ShareLinkTasks owner = tasks()) {
            try { owner.accept(task("task-12")); fail("领取未确认时应当整个退回"); }
            catch (Exception expected) { assertSame(offline, expected); }
            assertEquals("领取没确认就不能先把二维码亮出来", 0, screen.shows);

            // 下一轮服务端恢复，同一个任务重新走一遍，这次才显示。
            offline = null;
            owner.accept(task("task-12"));
            assertEquals(1, screen.shows);
            assertEquals(java.util.Collections.singletonList("claimed"), states());
        }
    }

    @Test public void terminalReceiptClimbsTheLadderWhenTheServerSilentlyDroppedEarlierSteps() throws Exception {
        try (ShareLinkTasks owner = tasks()) {
            owner.accept(task("task-13"));
            screen.listener.outcome(screen.session, ShareLinkOutcome.SHOWN, "");
            assertEquals("running", server.state("task-13"));

            // 模拟服务端那边把前面几步丢了（例如中途重建），任务退回 pending。
            server.states.put("task-13", "pending");
            sent.clear();
            server.accepted.clear();

            screen.listener.outcome(screen.session, ShareLinkOutcome.DISMISSED, "");

            // success 不能从 pending 发，否则被静默丢弃、面板上永远停在 pending。
            assertEquals("success", server.state("task-13"));
            assertEquals("补的中间步必须是 claimed 和 running", java.util.Arrays.asList("claimed", "running", "success"),
                    statesOf(server.accepted));
            assertAllLegal();
        }
    }

    @Test public void receiptIsNotTreatedAsAcknowledgedWhileTheServerKeepsDroppingIt() throws Exception {
        try (ShareLinkTasks owner = tasks()) {
            owner.accept(task("task-14"));
            screen.listener.outcome(screen.session, ShareLinkOutcome.SHOWN, "");

            // 服务端此刻一概不采纳，但仍旧回 200/ok 和当前状态。
            server.drop = true;
            screen.listener.outcome(screen.session, ShareLinkOutcome.DISMISSED, "");
            assertEquals("回执没被采纳，状态不得前进", "running", server.state("task-14"));

            // 只要回读状态没跟上，就不得记成已确认；服务端恢复后下一轮必须补上。
            server.drop = false;
            clock.wall += ShareLinkTasks.RETRY_MAX_MS;
            sent.clear();
            owner.tick();
            assertFalse("未被采纳的终态回执必须继续重发", sent.isEmpty());
            assertEquals("success", server.state("task-14"));
        }
    }

    @Test public void ladderStopsAtTerminalAndDoesNotInventStepsBackwards() {
        assertEquals("claimed", ShareLinkTasks.bridge("pending", "success"));
        assertEquals("running", ShareLinkTasks.bridge("claimed", "success"));
        assertNull("running 到 success 之间没有中间步", ShareLinkTasks.bridge("running", "success"));
        assertNull("到了终态就不再补步，冲突要报出来而不是自己编", ShareLinkTasks.bridge("failed", "success"));
        assertNull(ShareLinkTasks.bridge("success", "success"));
        // 服务端走得更靠前不算错，按次序认下来即可。
        assertTrue(ShareLinkTasks.rank("running") > ShareLinkTasks.rank("claimed"));
        assertTrue(ShareLinkTasks.rank("success") > ShareLinkTasks.rank("running"));
        assertEquals("终态之间不分先后", ShareLinkTasks.rank("failed"), ShareLinkTasks.rank("success"));
    }

    @Test public void acknowledgedRecordsAreCleanedUpAfterTheRetentionPeriod() throws Exception {
        File journal = new File(temp.getRoot(), "share-links");
        try (ShareLinkTasks owner = tasks()) {
            owner.accept(task("task-15"));
            screen.listener.outcome(screen.session, ShareLinkOutcome.DISMISSED, "");
            assertEquals("success", server.state("task-15"));
            assertEquals(1, journal.list().length);

            // 保留期内不删：重投时还要靠它挡住重弹。
            clock.wall += ShareLinkTasks.RETENTION_MS - 1000;
            owner.tick();
            assertEquals("保留期内不得删除", 1, journal.list().length);

            // 记录只增不删的话，tick 每5秒把整个目录读一遍，用久了会线性变慢。
            for (File record : journal.listFiles()) record.setLastModified(clock.wall - ShareLinkTasks.RETENTION_MS - 1);
            owner.tick();
            assertEquals("过了保留期的已确认记录应当清掉", 0, journal.list().length);
        }
    }

    @Test public void shutdownSettlesTheTaskStillOnScreenSoItIsNotLeftRunning() throws Exception {
        File journal = new File(temp.getRoot(), "share-links");
        ShareLinkTasks owner = tasks();
        owner.accept(task("task-16"));
        screen.listener.outcome(screen.session, ShareLinkOutcome.SHOWN, "");
        assertEquals("running", server.state("task-16"));
        sent.clear();

        // 核心因升级或重启退出时二维码还在显示：不结清的话服务端那条任务永远停在 running，
        // 下次启动 current 是空的、到期清扫进不来、retry 又只补发已有 receipt 的记录。
        owner.close();
        assertEquals("退出必须把在途任务结清", "success", server.state("task-16"));
        assertEquals(ShareLinkOutcome.SUPERSEDED, link(last()).optString("outcome"));
        assertEquals("core_shutdown", link(last()).optString("reason"));
        assertEquals(1, screen.dismisses);
        assertEquals(1, journal.list().length);
    }

    @Test public void shutdownReceiptSurvivesAnUnreachableServerAndIsResentNextStart() throws Exception {
        ShareLinkTasks owner = tasks();
        owner.accept(task("task-17"));
        screen.listener.outcome(screen.session, ShareLinkOutcome.SHOWN, "");
        offline = new java.io.IOException("unreachable");
        owner.close();
        assertEquals("发不出去时状态不得前进", "running", server.state("task-17"));

        // finish() 是先落盘再发，所以下次启动 retry() 能补上。
        offline = null;
        sent.clear();
        try (ShareLinkTasks restarted = tasks()) {
            clock.wall += ShareLinkTasks.RETRY_MAX_MS;
            restarted.tick();
            assertEquals("success", server.state("task-17"));
            assertEquals("task-17", last().optString("task_id"));
        }
    }

    @Test public void malformedTaskIdIsRefusedBeforeAnythingIsWrittenToDisk() throws Exception {
        try (ShareLinkTasks owner = tasks()) {
            // 任务号会被当成文件名用，带路径分隔符的必须在落盘前就挡住。
            for (String id : new String[]{"", "../escape", "a/b", "id with space"}) {
                try { owner.accept(task("x").put("id", id)); fail("应当拒绝任务号：" + id); }
                catch (Exception expected) { assertEquals("SHARE_LINK_TASK_ID_INVALID", expected.getMessage()); }
            }
            assertEquals(0, screen.shows);
            assertTrue(sent.isEmpty());
            assertEquals("不得因非法任务号产生任何记账文件", 0,
                    new File(temp.getRoot(), "share-links").list().length);
        }
    }
}
