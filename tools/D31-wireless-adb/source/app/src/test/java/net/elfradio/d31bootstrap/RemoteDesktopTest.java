package net.elfradio.d31bootstrap;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 核心侧的远程桌面编排。
 *
 * 这一层是 D31 与 D22 唯一分岔的地方：D22 由应用进程反向调用核心拉起 scrcpy，
 * D31 没有反向通路，改由核心按轮次取状态来驱动。所以最该钉住的是驱动时机——
 * 不能在浏览器还没接上时就把 scrcpy 拉起来空转，也不能在应用等着 scid 时干等着不动。
 */
public class RemoteDesktopTest {
    private static final String SESSION = "0a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d";
    private static final String TOKEN = "0a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d1b2c3d4e-5f6a-4b7c-8d9e-1f2a3b4c5d6e";
    private static final String HASH = "a".repeat(64);

    private static final class Clock implements RemoteDesktop.Clock {
        long elapsed = 1000L;
        @Override public long elapsed() { return elapsed; }
    }

    /** 记录核心对应用进程说过的每一句，以及应用当前回的状态。 */
    private static final class App implements RemoteDesktop.Bridge {
        final List<JSONObject> commands = new ArrayList<>();
        String state = "connecting", quality = "wifi", detail = "";
        String session = SESSION;
        Exception failure;
        @Override public JSONObject request(JSONObject command) throws Exception {
            commands.add(command);
            if (failure != null) throw failure;
            return new JSONObject().put("session_id", session).put("state", state)
                    .put("quality", quality).put("detail", detail);
        }
        List<String> operations() {
            List<String> values = new ArrayList<>();
            for (JSONObject c : commands) values.add(c.optString("operation"));
            return values;
        }
        JSONObject last(String operation) {
            for (int i = commands.size() - 1; i >= 0; i--)
                if (operation.equals(commands.get(i).optString("operation"))) return commands.get(i);
            return null;
        }
    }

    /** 只记账不真的起进程；单测里不碰 su，也不碰 /proc。 */
    private static final class Launcher implements RemoteDesktop.Launcher {
        final List<JSONObject> started = new ArrayList<>();
        int stops;
        Exception failure;
        @Override public JSONObject start(JSONObject request) throws Exception {
            if (failure != null) throw failure;
            started.add(request);
            return new JSONObject().put("ok", true).put("scid", request.getString("scid"));
        }
        @Override public JSONObject stop() throws Exception { stops++; return new JSONObject().put("ok", true); }
    }

    private final Clock clock = new Clock();
    private final App app = new App();
    private final Launcher launcher = new Launcher();

    /** 资产就绪与否在别处测；这里固定为就绪，专心测驱动时机。 */
    private RemoteDesktop desktop() { return new RemoteDesktop(app, launcher, clock, HASH, () -> true); }

    private JSONObject offer() throws Exception {
        return new JSONObject().put("session_id", SESSION).put("token", TOKEN)
                .put("quality", "wifi").put("generation", 1)
                .put("expires_at", System.currentTimeMillis() + 30000)
                .put("url", "wss://v.elfradio.net" + DesktopOffer.PATH + "?session_id=" + SESSION)
                .put("ice_servers", new JSONArray());
    }

    private void poll(RemoteDesktop desktop) { clock.elapsed += RemoteDesktop.POLL_MS; desktop.pump(); }

    @Test public void scrcpyIsNotStartedUntilTheAppSaysTheBrowserIsThere() throws Exception {
        try (RemoteDesktop desktop = desktop()) {
            desktop.accept(offer());
            assertEquals(java.util.Collections.singletonList("desktop_start"), app.operations());
            // 中继要等两边都接上才发 hello；此时提前拉起只会让 scrcpy 空转白占屏幕。
            assertTrue("浏览器还没接上就不该拉起屏幕服务", launcher.started.isEmpty());

            poll(desktop); poll(desktop);
            assertTrue("应用还在连中继，照样不该拉起", launcher.started.isEmpty());

            app.state = "awaiting_server";
            poll(desktop);
            assertEquals(1, launcher.started.size());
            assertEquals("拉起之后要立刻把 scid 送进应用进程", "desktop_server",
                    app.operations().get(app.operations().size() - 1));
            assertEquals(launcher.started.get(0).getString("scid"), app.last("desktop_server").getString("scid"));
        }
    }

    @Test public void qualityTierIsCarriedFromTheAppSnapshotIntoScrcpy() throws Exception {
        app.quality = "cellular";
        try (RemoteDesktop desktop = desktop()) {
            desktop.accept(offer().put("quality", "cellular"));
            app.state = "awaiting_server";
            poll(desktop);
            JSONObject started = launcher.started.get(0);
            assertEquals(15, started.getInt("max_fps"));
            assertEquals(500000, started.getInt("bit_rate"));
            assertEquals(960, started.getInt("max_size"));
        }
    }

    @Test public void socketFailureRetriesOnceWithAFreshScidThenGivesUp() throws Exception {
        try (RemoteDesktop desktop = desktop()) {
            desktop.accept(offer());
            app.state = "awaiting_server";
            poll(desktop);
            assertEquals(1, launcher.started.size());

            // 套接字连不上：换一个 scid 再来一次，不能沿用旧的，旧的可能被残留进程占着。
            app.state = "server_failed";
            poll(desktop);
            assertEquals(2, launcher.started.size());
            assertNotEquals("重试必须换 scid", launcher.started.get(0).getString("scid"),
                    launcher.started.get(1).getString("scid"));

            // 再失败就收手，不无限重试。
            poll(desktop);
            assertEquals(RemoteDesktop.MAX_LAUNCHES, launcher.started.size());
            assertTrue("放弃时要收掉屏幕服务", launcher.stops > 0);
            assertEquals("desktop_stop", app.operations().get(app.operations().size() - 1));
            assertFalse(desktop.active());
        }
    }

    @Test public void endedSessionStopsScrcpyAndStopsPolling() throws Exception {
        try (RemoteDesktop desktop = desktop()) {
            desktop.accept(offer());
            app.state = "awaiting_server";
            poll(desktop);
            int before = app.commands.size();

            app.state = "ended"; app.detail = "网页已关闭桌面";
            poll(desktop);
            assertTrue("会话结束必须收掉 scrcpy，否则它占着抽象套接字", launcher.stops > 0);
            assertEquals("desktop_stop", app.operations().get(app.operations().size() - 1));
            assertFalse(desktop.active());

            // 结束之后不再打桥，省掉无谓的跨进程往返。
            int after = app.commands.size();
            poll(desktop); poll(desktop);
            assertEquals(after, app.commands.size());
            assertTrue(after > before);
        }
    }

    @Test public void staleSnapshotFromAnotherSessionIsIgnored() throws Exception {
        try (RemoteDesktop desktop = desktop()) {
            desktop.accept(offer());
            app.session = "ffffffff-4e5f-4a6b-8c9d-0e1f2a3b4c5d";
            app.state = "awaiting_server";
            poll(desktop); poll(desktop);
            assertTrue("别的会话的状态不得驱动本次", launcher.started.isEmpty());
            assertTrue(desktop.active());
        }
    }

    @Test public void repeatedOfferForTheSameSessionDoesNotRestartAnything() throws Exception {
        try (RemoteDesktop desktop = desktop()) {
            desktop.accept(offer());
            app.state = "awaiting_server";
            poll(desktop);
            int commands = app.commands.size();
            int started = launcher.started.size();

            // 上报每一轮都会把同一个邀约带下来。
            desktop.accept(offer());
            desktop.accept(offer());
            assertEquals(commands, app.commands.size());
            assertEquals(started, launcher.started.size());
        }
    }

    @Test public void scidLooksLikeTheOneD22Generates() {
        RemoteDesktop desktop = desktop();
        for (int i = 0; i < 200; i++) {
            String scid = desktop.newScid();
            assertTrue("scid 必须是8位小写十六进制：" + scid, scid.matches("[0-9a-f]{8}"));
            // 首字节抹掉高位，与 D22 一致。
            assertTrue(Integer.parseInt(scid.substring(0, 2), 16) <= 0x7f);
        }
        desktop.close();
    }

    @Test public void closingTheCoreTakesTheScreenServerDownWithIt() throws Exception {
        RemoteDesktop desktop = desktop();
        desktop.accept(offer());
        app.state = "awaiting_server";
        poll(desktop);
        assertTrue(desktop.active());

        desktop.close();
        assertTrue("核心退出不能把 scrcpy 留在后台", launcher.stops > 0);
        assertFalse(desktop.active());
    }

    @Test public void bridgeFailureDuringPollLeavesTheSessionForTheAppTimeoutsToEnd() throws Exception {
        try (RemoteDesktop desktop = desktop()) {
            desktop.accept(offer());
            app.failure = new java.io.IOException("VISUAL_BRIDGE_TIMEOUT");
            poll(desktop); poll(desktop);
            // 取不到状态不能自己判死：应用进程有30秒准备时限和20分钟空闲时限兜底。
            assertTrue(desktop.active());
            assertEquals(0, launcher.stops);

            app.failure = null;
            app.state = "awaiting_server";
            poll(desktop);
            assertEquals(1, launcher.started.size());
        }
    }
}
