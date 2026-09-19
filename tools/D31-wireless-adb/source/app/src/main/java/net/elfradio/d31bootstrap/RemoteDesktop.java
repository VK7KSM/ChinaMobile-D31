package net.elfradio.d31bootstrap;

import java.security.SecureRandom;
import org.json.JSONObject;

/**
 * 远程桌面的核心侧编排。
 *
 * D22 的分工是应用进程收到 hello 后反向 HTTP 调用核心拉起 scrcpy；D31 没有反向通路
 * （桥是单向的核心→应用，唯一的反向口是局域网暴露且无鉴权的救援端口，不能用），
 * 所以改成核心驱动：把邀约交给应用，然后按轮次取状态，见到「等服务端」才拉起 scrcpy 并把 scid 送进去。
 * 换 scid 重试也挪到这里——D22 那段是在应用内做的，本类保留同样的「最多再来一次」语义。
 *
 * 为什么不在收到邀约时就把 scrcpy 拉起来：中继要等浏览器和设备两边都接上才发 hello，
 * 提前拉起会让 scrcpy 空转、白占 CPU 和屏幕唤醒，而且浏览器可能压根没来。
 */
final class RemoteDesktop implements AutoCloseable {
    interface Bridge { JSONObject request(JSONObject command) throws Exception; }
    interface Clock { long elapsed(); }
    /** scrcpy 的拉起与结束；抽成接口是为了单测不去碰 su 和 /proc。 */
    interface Launcher { JSONObject start(JSONObject request) throws Exception; JSONObject stop() throws Exception; }
    /** 资产是否就绪；同样抽出来，单测不依赖真实文件。 */
    interface Availability { boolean ready(); }

    /** 一次桥调用就是一次跨进程往返；30秒的准备时限下，1秒一轮足够跟上。 */
    static final long POLL_MS = 1000L;
    /** 与 D22 一致：换一个 scid 再拉一次，仍失败才判失败。 */
    static final int MAX_LAUNCHES = 2;

    private final Bridge bridge;
    private final Launcher launcher;
    private final Availability availability;
    private final Clock clock;
    private final String apkHash;
    private final SecureRandom random = new SecureRandom();

    private String session = "", scid = "";
    private int launches;
    private long polledElapsed;
    private boolean closed;

    RemoteDesktop(Bridge bridge, Launcher launcher, Clock clock, String apkHash) {
        this(bridge, launcher, clock, apkHash, RemoteDesktop::available);
    }

    RemoteDesktop(Bridge bridge, Launcher launcher, Clock clock, String apkHash, Availability availability) {
        this.bridge = bridge; this.launcher = launcher; this.clock = clock;
        this.apkHash = apkHash; this.availability = availability;
    }

    /** scid 是 8 位十六进制，首字节抹掉高位，与 D22 生成方式一致。 */
    String newScid() {
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        return String.format("%02x%02x%02x%02x", bytes[0] & 0x7f, bytes[1], bytes[2], bytes[3]);
    }

    boolean active() { return !session.isEmpty(); }

    /** 能力位：只有完整版带 scrcpy 资产，装好且哈希对得上才敢上报。 */
    static boolean available() {
        try { return DesktopAsset.packaged() && DesktopAsset.installed(); }
        catch (Exception unavailable) { return false; }
    }

    synchronized void accept(JSONObject offer) throws Exception {
        if (closed || offer == null) return;
        DesktopOffer parsed = DesktopOffer.parse(offer, System.currentTimeMillis());
        // 同一场会话每轮上报都会带下来；已经在跑就不要重新开始。
        if (parsed.sessionId.equals(session)) return;
        if (!availability.ready()) throw new java.io.IOException("DESKTOP_ASSET_NOT_READY");
        if (active()) release("已开始新的远程桌面会话");
        session = parsed.sessionId; scid = ""; launches = 0; polledElapsed = 0;
        bridge.request(new JSONObject().put("operation", "desktop_start")
                .put("apk_sha256", apkHash).put("offer", parsed.forApp()));
    }

    /** 由核心工作循环驱动，和其他按轮次的活儿同一圈。 */
    synchronized void pump() {
        if (closed || !active()) return;
        if (polledElapsed != 0 && clock.elapsed() - polledElapsed < POLL_MS) return;
        polledElapsed = clock.elapsed();
        try { advance(bridge.request(new JSONObject().put("operation", "desktop_query"))); }
        catch (Exception unavailable) {
            // 取不到就等下一圈；应用进程自己有30秒准备时限和20分钟空闲时限兜底。
            System.err.println("DESKTOP_QUERY_UNAVAILABLE " + session);
        }
    }

    private void advance(JSONObject snapshot) throws Exception {
        if (snapshot == null) return;
        // 迟到的旧会话快照不得驱动当前这次。
        String reported = snapshot.optString("session_id");
        if (!session.equals(reported)) return;
        String state = snapshot.optString("state");
        if ("awaiting_server".equals(state)) { launch(snapshot.optString("quality", DesktopOffer.WIFI)); return; }
        if ("server_failed".equals(state)) {
            if (launches < MAX_LAUNCHES) { launch(snapshot.optString("quality", DesktopOffer.WIFI)); return; }
            release("屏幕服务未能启动");
            return;
        }
        if ("ended".equals(state) || "idle".equals(state)) release(snapshot.optString("detail", "远程桌面已结束"));
    }

    /** 拉起 scrcpy 并把 scid 送进应用进程；scid 每次都换，避免撞上残留进程占着的抽象套接字。 */
    private void launch(String quality) throws Exception {
        launches++;
        String next = newScid();
        launcher.start(DesktopOffer.scrcpyParams(next, quality));
        scid = next;
        bridge.request(new JSONObject().put("operation", "desktop_server").put("scid", next));
    }

    /** 收掉 scrcpy 并通知应用进程结束；两边都做，任一侧失败不影响另一侧。 */
    private void release(String reason) {
        String owned = session;
        session = ""; scid = ""; launches = 0; polledElapsed = 0;
        try { launcher.stop(); } catch (Exception unavailable) { System.err.println("DESKTOP_SERVER_STOP_FAILED " + owned); }
        try { bridge.request(new JSONObject().put("operation", "desktop_stop").put("reason", reason)); }
        catch (Exception unavailable) { System.err.println("DESKTOP_STOP_UNCONFIRMED " + owned); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (active()) release("维护核心正在退出");
    }
}
