package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.elfradio.d31bootstrap.DesktopOffer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.JavaAudioDeviceModule;

/**
 * 远程桌面会话（应用进程）。移植自 D22 的同名实现，逐条对齐其时序与参数。
 *
 * 连接 scrcpy 服务端开的视频/控制两条本机套接字，经独立 RTCPeerConnection 的两条有序 DataChannel
 * 与浏览器直连，Worker 只转发信令。scrcpy 字节原样搬运，不加帧头也不解析内容，浏览器那端用 Tango 解。
 * 不创建音频/摄像头轨道，不请求音频焦点。
 *
 * 与 D22 的唯一分岔：**scrcpy 由核心拉起，不是本进程去要**。D22 的应用进程可以反向 HTTP 调用核心
 * （CoreClient./desktop/start），D31 没有这条通路——桥是单向的核心→应用，唯一的反向口是那个
 * 局域网暴露且无鉴权的救援端口，不能用。所以这里收到 hello 之后只是把状态置为「等核心给 scid」，
 * 由核心按轮次取状态、拉起 scrcpy、再经桥把 scid 送进来（server 方法）。套接字连不上时也一样：
 * 本进程只如实报 server_failed，换 scid 重试由核心做，D22 那段应用内重试因此不照搬。
 */
final class DesktopSession {
    /** 与 D22 一致：20分钟无操作自动关闭，30秒查一次。 */
    private static final long IDLE_LIMIT_MS = 20 * 60 * 1000L, IDLE_CHECK_MS = 30000L;
    private static final long PREPARE_TIMEOUT_MS = 30000L;
    private static final int CHUNK = 16384;
    private static final long BACKPRESSURE_BYTES = 2L * 1024 * 1024;
    private static final long VIDEO_SOCKET_MS = 8000L, CONTROL_SOCKET_MS = 3000L;
    private static final String TAG = "D31Desktop";
    private static final long SDP_TIMEOUT_SECONDS = 10L;

    /** 核心据此判断该做什么；取值只增不改，核心侧按字符串比对。 */
    static final String IDLE = "idle", CONNECTING = "connecting", AWAITING_SERVER = "awaiting_server",
            STARTING = "starting", ACTIVE = "active", SERVER_FAILED = "server_failed", ENDED = "ended";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final File apk;
    private final String hash;
    private final File nativeCache;

    private WebSocketClient socket;
    private PeerConnectionFactory factory;
    private PeerConnection pc;
    private DataChannel video, control;
    private LocalSocket videoSocket, controlSocket;
    private Thread videoPump, controlPump;
    private PowerManager.WakeLock screen;
    private JSONArray iceServers = new JSONArray();

    private volatile String id = "", state = IDLE, quality = DesktopOffer.WIFI, scid = "", detail = "";
    private volatile boolean closed = true, destroyed, readySent, videoFlowing;
    private volatile int generation;
    private volatile long lastInputAt, receivedAt;
    private volatile int width, height;

    private final Runnable idleCheck = new Runnable() { public void run() {
        if (closed) return;
        if (SystemClock.elapsedRealtime() - lastInputAt >= IDLE_LIMIT_MS) { stop("20分钟无操作，远程桌面已自动关闭"); return; }
        main.postDelayed(this, IDLE_CHECK_MS);
    } };
    private final Runnable prepareTimeout = () -> stop("远程桌面准备超时");

    DesktopSession(Context context, File apk, String hash, File nativeCache) {
        this.context = context.getApplicationContext(); this.apk = apk; this.hash = hash; this.nativeCache = nativeCache;
    }

    /** 只读 volatile 字段，刻意不加锁：会话侧一旦阻塞，取状态的线程不该跟着一起卡。 */
    JSONObject snapshot() throws Exception {
        return new JSONObject().put("session_id", id).put("state", state).put("scid", scid)
                .put("quality", quality).put("generation", generation)
                .put("width", width).put("height", height).put("detail", detail);
    }

    /**
     * 核心把邀约送进来；只负责连上中继并等 hello，拉起 scrcpy 是核心的事。
     *
     * 同样不能整体 synchronized：顶替旧会话要调 stop()，而 stop() 里的 close() 必须在锁外，
     * 握着锁进去就把那道修复抵消了。所以判定与赋值各自短暂持锁，中间的 stop() 在锁外。
     */
    JSONObject start(JSONObject offer) throws Exception {
        if (destroyed) throw new IOException("DESKTOP_SERVICE_CLOSED");
        DesktopOffer parsed = DesktopOffer.parse(offer, System.currentTimeMillis());
        boolean supersede;
        synchronized (this) {
            // 同一场会话重复下发（上报每轮都会带）：保持现状，不重连。
            if (!closed && parsed.sessionId.equals(id)) return snapshot();
            supersede = !closed;
        }
        if (supersede) stop("已开始新的远程桌面会话");
        final String owner;
        synchronized (this) {
            id = parsed.sessionId; quality = parsed.quality; generation = parsed.generation;
            iceServers = parsed.iceServers; scid = ""; detail = ""; width = 0; height = 0;
            closed = false; readySent = false; videoFlowing = false; state = CONNECTING;
            receivedAt = SystemClock.elapsedRealtime(); lastInputAt = receivedAt;
            owner = id;
        }
        executor.execute(() -> { try { connect(parsed, owner); } catch (Exception failure) { fail(failure); } });
        return snapshot();
    }

    private void connect(DesktopOffer offer, String owner) throws Exception {
        final String host = offer.url.getHost();
        WebSocketClient client = new WebSocketClient(offer.url,
                Collections.singletonMap("Authorization", "Bearer " + offer.token)) {
            @Override protected void onSetSSLParameters(javax.net.ssl.SSLParameters parameters) {
                // Android 6（API 23）没有 setEndpointIdentificationAlgorithm，Java-WebSocket 的默认实现
                // 会直接抛 NoSuchMethodError 把整个应用进程打死。这里照 AdbSessions 的做法自己完成握手
                // 与域名验证——必须赶在把会话令牌放进 Authorization 头发出去之前验完。
                try {
                    javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) getSocket();
                    socket.setSoTimeout(10000);
                    socket.startHandshake();
                    if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.getSession()))
                        throw new javax.net.ssl.SSLPeerUnverifiedException("远程桌面中继证书域名不匹配");
                    socket.setSoTimeout(0);
                } catch (IOException failure) { throw new IllegalStateException("远程桌面中继TLS验证失败", failure); }
            }
            public void onOpen(ServerHandshake handshake) { android.util.Log.i(TAG, "desktop_connected"); }
            public void onMessage(String raw) {
                if (socket != this || closed) return;
                executor.execute(() -> {
                    if (closed || !owner.equals(id)) return;
                    try {
                        if (raw.length() > 96000) throw new IOException("信令过大");
                        message(new JSONObject(raw));
                    } catch (Exception | LinkageError failure) { fail(new Exception(failure)); }
                });
            }
            public void onClose(int code, String reason, boolean remote) { if (socket == this) stop("远程桌面连接已断开"); }
            public void onError(Exception error) { if (socket == this) fail(error); }
        };
        socket = client;
        client.setTcpNoDelay(true);
        client.setConnectionLostTimeout(20);
        client.connect();
        main.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
    }

    private void message(JSONObject x) throws Exception {
        switch (x.optString("type")) {
            case "hello":
                if (x.has("ice_servers")) iceServers = x.getJSONArray("ice_servers");
                generation = x.optInt("generation", generation);
                // D22 在这里直接回调核心拉起 scrcpy；这里只能挂起等核心来给 scid。
                state = AWAITING_SERVER;
                sendStatus("starting", "");
                break;
            case "signal":
                if (x.optInt("generation") != generation || pc == null) return;
                if ("answer".equals(x.optString("kind")))
                    set(new SessionDescription(SessionDescription.Type.ANSWER, x.getString("payload")));
                else if ("candidate".equals(x.optString("kind"))) {
                    JSONObject c = new JSONObject(x.getString("payload"));
                    pc.addIceCandidate(new IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate")));
                }
                break;
            case "restart":
                // 新代次：重建对等连接与数据通道，scrcpy 服务端与本机套接字保持不动。
                generation = x.optInt("generation", generation + 1); readySent = false;
                closePeer(); openPeer(); break;
            case "closed": stop(x.optString("message", "远程桌面已结束")); break;
            default: break;
        }
    }

    /** 核心已按 scid 拉起 scrcpy；接上两条本机套接字并开始协商。 */
    synchronized JSONObject server(String started) throws Exception {
        if (closed || !AWAITING_SERVER.equals(state)) return snapshot();
        if (started == null || !started.matches("[0-9a-f]{8}")) throw new IOException("DESKTOP_SCID_INVALID");
        scid = started; state = STARTING; detail = "";
        final String owner = id, name = "scrcpy_" + started;
        executor.execute(() -> {
            try {
                if (closed || !owner.equals(id)) return;
                sendStatus("server_started", "");
                // 先视频后控制，与 D22 一致；两条都连同一个抽象套接字名。
                videoSocket = connectLocal(name, VIDEO_SOCKET_MS);
                controlSocket = connectLocal(name, CONTROL_SOCKET_MS);
                sendStatus("socket_ready", "");
                acquireScreen();
                openPeer();
            } catch (Exception failure) {
                // 换 scid 重试是核心的事；这里只如实置状态，别自己重试。
                synchronized (DesktopSession.this) {
                    if (!closed && owner.equals(id)) { state = SERVER_FAILED; detail = String.valueOf(failure.getMessage()); }
                }
                android.util.Log.w(TAG, "desktop_socket_failed " + failure);
                sendStatus("failed", String.valueOf(failure.getMessage()));
            }
        });
        return snapshot();
    }

    private static LocalSocket connectLocal(String name, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        Exception last = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            LocalSocket s = new LocalSocket();
            try { s.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT)); return s; }
            catch (IOException retry) { last = retry; try { s.close(); } catch (IOException ignored) { } Thread.sleep(150); }
        }
        throw new IOException("屏幕服务套接字未就绪", last);
    }

    private synchronized void openPeer() throws Exception {
        if (closed) return;
        if (factory == null) {
            ApkMediaLibrary library = new ApkMediaLibrary(apk, hash, nativeCache);
            if (!library.load("jingle_peerconnection_so")) throw new IOException("MEDIA_NATIVE_LOAD_FAILED");
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                    .setNativeLibraryLoader(library).createInitializationOptions());
            // 只用数据通道，不建音轨也不开麦克风；这个模块建而不启，和 D22 一致。
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
                    .createAudioDeviceModule()).createPeerConnectionFactory();
        }
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        for (int i = 0; i < iceServers.length(); i++) {
            JSONObject s = iceServers.getJSONObject(i);
            List<String> urls = new ArrayList<>();
            Object u = s.opt("urls");
            if (u instanceof JSONArray) for (int k = 0; k < ((JSONArray) u).length(); k++) urls.add(((JSONArray) u).getString(k));
            else if (u != null) urls.add(String.valueOf(u));
            PeerConnection.IceServer.Builder b = PeerConnection.IceServer.builder(urls);
            if (s.has("username")) b.setUsername(s.getString("username"));
            if (s.has("credential")) b.setPassword(s.getString("credential"));
            servers.add(b.createIceServer());
        }
        if (servers.isEmpty()) servers.add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer());
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(servers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.iceCandidatePoolSize = 1;
        final String owner = id;
        final int gen = generation;
        pc = factory.createPeerConnection(config, new PeerConnection.Observer() {
            public void onSignalingChange(PeerConnection.SignalingState s) { }
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                if (closed || !owner.equals(id) || gen != generation) return;
                android.util.Log.i(TAG, "desktop_ice state=" + s + " after_ms=" + (SystemClock.elapsedRealtime() - receivedAt));
                if (s == PeerConnection.IceConnectionState.FAILED) sendStatus("ice_failed", "");
            }
            public void onIceConnectionReceivingChange(boolean v) { }
            public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }
            public void onIceCandidate(IceCandidate c) {
                if (closed || !owner.equals(id) || gen != generation) return;
                try { signal("candidate", new JSONObject().put("candidate", c.sdp)
                        .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).toString()); }
                catch (Exception failure) { fail(failure); }
            }
            public void onIceCandidatesRemoved(IceCandidate[] c) { }
            public void onAddStream(MediaStream s) { }
            public void onRemoveStream(MediaStream s) { }
            public void onDataChannel(DataChannel c) { }
            public void onRenegotiationNeeded() { }
            public void onAddTrack(RtpReceiver r, MediaStream[] streams) { }
        });
        if (pc == null) throw new IOException("远程桌面连接初始化失败");
        DataChannel.Init init = new DataChannel.Init();
        init.ordered = true;
        video = pc.createDataChannel("video", init);
        control = pc.createDataChannel("control", init);
        video.registerObserver(new DataChannel.Observer() {
            public void onBufferedAmountChange(long p) { }
            public void onStateChange() { if (video != null && video.state() == DataChannel.State.OPEN) startVideoPump(gen); }
            public void onMessage(DataChannel.Buffer b) { }
        });
        control.registerObserver(new DataChannel.Observer() {
            public void onBufferedAmountChange(long p) { }
            public void onStateChange() {
                if (control != null && control.state() == DataChannel.State.OPEN) { startControlPump(gen); checkReady(); }
            }
            public void onMessage(DataChannel.Buffer b) {
                if (closed || gen != generation || controlSocket == null) return;
                byte[] bytes = new byte[b.data.remaining()];
                b.data.get(bytes);
                lastInputAt = SystemClock.elapsedRealtime();
                try { synchronized (controlSocket) { OutputStream out = controlSocket.getOutputStream(); out.write(bytes); out.flush(); } }
                catch (IOException failure) { fail(failure); }
            }
        });
        // SDP 的 offer 必须由设备发，中继把这个方向钉死了，反了会被拒。
        SessionDescription offer = create();
        set(offer);
        signal("offer", offer.description);
    }

    private void startVideoPump(int gen) {
        if (videoPump != null && videoPump.isAlive()) return;
        videoPump = new Thread(() -> pump(videoSocket, () -> video, gen, true), "d31-desktop-video");
        videoPump.setDaemon(true); videoPump.start();
    }

    private void startControlPump(int gen) {
        if (controlPump != null && controlPump.isAlive()) return;
        controlPump = new Thread(() -> pump(controlSocket, () -> control, gen, false), "d31-desktop-control");
        controlPump.setDaemon(true); controlPump.start();
    }

    private interface ChannelRef { DataChannel get(); }

    private void pump(LocalSocket from, ChannelRef to, int gen, boolean isVideo) {
        byte[] buffer = new byte[CHUNK];
        try (InputStream in = from.getInputStream()) {
            int n;
            while (!closed && (n = in.read(buffer)) != -1) {
                DataChannel dc = to.get();
                if (dc == null || gen != generation) { if (gen != generation) return; continue; }
                while (!closed && gen == generation && dc.bufferedAmount() > BACKPRESSURE_BYTES) Thread.sleep(5);
                if (closed || gen != generation) return;
                if (dc.state() != DataChannel.State.OPEN) return;
                dc.send(new DataChannel.Buffer(ByteBuffer.wrap(Arrays.copyOf(buffer, n)), true));
                if (isVideo && !videoFlowing) { videoFlowing = true; checkReady(); }
            }
            if (!closed && gen == generation) stop(isVideo ? "屏幕服务已结束" : "控制通道已结束");
        } catch (Exception failure) { if (!closed && gen == generation) fail(failure); }
    }

    /**
     * 视频真的流起来、且控制通道已开，才算就绪；只报一次。
     *
     * 出错不能在锁里调 fail()：fail 走 stop、stop 要关 WebSocket，而本方法此刻还持着锁，
     * 等于把 stop() 里"close 放到锁外"那道修复整个抵消。所以锁内只把异常记下来，出锁再处理。
     */
    private void checkReady() {
        Exception failed = null;
        synchronized (this) {
            if (closed || readySent || !videoFlowing || control == null || control.state() != DataChannel.State.OPEN) return;
            readySent = true; state = ACTIVE;
            try {
                android.view.Display display = ((android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
                android.graphics.Point size = new android.graphics.Point();
                display.getRealSize(size);
                width = size.x; height = size.y;
            } catch (Exception unavailable) { failed = unavailable; }
        }
        main.removeCallbacks(prepareTimeout);
        main.removeCallbacks(idleCheck);
        main.postDelayed(idleCheck, IDLE_CHECK_MS);
        if (failed != null) { fail(failed); return; }
        android.util.Log.i(TAG, "desktop_ready after_ms=" + (SystemClock.elapsedRealtime() - receivedAt));
        try { send(new JSONObject().put("type", "ready").put("width", width).put("height", height).put("encoder", "hardware")); }
        catch (Exception failure) { fail(failure); }
    }

    private void acquireScreen() {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            // 设备休眠时 SurfaceFlinger 不出帧，必须保持亮屏，否则画面是黑的但一切看着正常。
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "elfRemote:desktop-screen");
            lock.acquire(IDLE_LIMIT_MS + 60000L);
            screen = lock;
        } catch (Exception failure) { android.util.Log.w(TAG, "desktop_screen_lock_failed " + failure); }
    }

    /**
     * D22 这两处用的是 CompletableFuture，那是 API 24 才有的，D31 是 API 23，
     * 直接搬过来会在真机上抛 NoClassDefFoundError 打死整个应用进程。
     * 改用 CountDownLatch 配 AtomicReference，与本项目其他等回调的地方一致。
     */
    private SessionDescription create() throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<SessionDescription> value = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>();
        pc.createOffer(new Sdp() {
            public void onCreateSuccess(SessionDescription d) { value.set(d); done.countDown(); }
            public void onCreateFailure(String e) { failure.set(e); done.countDown(); }
        }, new MediaConstraints());
        if (!done.await(SDP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IOException("DESKTOP_OFFER_TIMEOUT");
        if (failure.get() != null) throw new IOException(failure.get());
        SessionDescription offer = value.get();
        if (offer == null) throw new IOException("DESKTOP_OFFER_EMPTY");
        return offer;
    }

    private void set(SessionDescription d) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> failure = new AtomicReference<>();
        Sdp o = new Sdp() {
            public void onSetSuccess() { done.countDown(); }
            public void onSetFailure(String e) { failure.set(e); done.countDown(); }
        };
        if (d.type == SessionDescription.Type.OFFER) pc.setLocalDescription(o, d); else pc.setRemoteDescription(o, d);
        if (!done.await(SDP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IOException("DESKTOP_SDP_TIMEOUT");
        if (failure.get() != null) throw new IOException(failure.get());
    }

    private void signal(String kind, String payload) throws Exception {
        send(new JSONObject().put("type", "signal").put("kind", kind).put("payload", payload).put("generation", generation));
    }

    /** stage 与网关对齐，网页端两边文案一致；中继限 stage 32、message 140 字符。 */
    private void sendStatus(String stage, String message) {
        try {
            JSONObject x = new JSONObject().put("type", "status")
                    .put("stage", stage.length() > 32 ? stage.substring(0, 32) : stage);
            if (message != null && !message.isEmpty())
                x.put("message", message.length() > 140 ? message.substring(0, 140) : message);
            send(x);
        } catch (Exception ignored) { }
    }

    private void send(JSONObject x) {
        WebSocketClient ws = socket;
        if (!closed && ws != null && ws.isOpen()) ws.send(x.toString());
    }

    private void fail(Exception failure) {
        android.util.Log.w(TAG, "desktop_failed " + failure);
        sendStatus("failed", String.valueOf(failure.getMessage()));
        stop("远程桌面失败");
    }

    private void closePeer() {
        if (video != null) { try { video.unregisterObserver(); video.close(); video.dispose(); } catch (Exception ignored) { } video = null; }
        if (control != null) { try { control.unregisterObserver(); control.close(); control.dispose(); } catch (Exception ignored) { } control = null; }
        if (pc != null) { try { pc.close(); pc.dispose(); } catch (Exception ignored) { } pc = null; }
    }

    /**
     * 关闭动作**必须在锁外**做。
     *
     * WebSocketClient.close() 要拿 WebSocket 自己的那把锁，而读线程是先拿到那把锁、
     * 再从 onClose/onError 回调进来拿本对象的锁。两边锁序相反，只要把 close() 放进
     * synchronized 里就会死锁：现象不是报错而是整条会话卡住，连带上报线程一起停，
     * 进程还活着、日志里 last_error 是空的，极难查。网关 2026-09-19 就是这么失联近八分钟的。
     */
    JSONObject stop(String reason) {
        WebSocketClient ws;
        synchronized (this) {
            if (closed) return quietSnapshot();
            closed = true; state = ENDED; detail = reason == null ? "" : reason;
            ws = socket; socket = null;
        }
        main.removeCallbacks(prepareTimeout);
        main.removeCallbacks(idleCheck);
        android.util.Log.i(TAG, "desktop_stopped reason=" + reason);
        if (ws != null) try { ws.close(); } catch (Exception ignored) { }
        executor.execute(() -> {
            closePeer();
            for (LocalSocket s : new LocalSocket[]{videoSocket, controlSocket})
                if (s != null) try { s.close(); } catch (IOException ignored) { }
            videoSocket = null; controlSocket = null;
            // scrcpy 由核心结束：核心取到 ended 之后会按 scid 收掉它。
            if (screen != null) { try { if (screen.isHeld()) screen.release(); } catch (Exception ignored) { } screen = null; }
            if (factory != null) { try { factory.dispose(); } catch (Exception ignored) { } factory = null; }
        });
        return quietSnapshot();
    }

    private JSONObject quietSnapshot() {
        try { return snapshot(); } catch (Exception unavailable) { return new JSONObject(); }
    }

    /**
     * 不能是 synchronized：它握着本对象的锁去调 stop()，等于把上面那道修复整个抵消掉。
     * 只在锁里翻转标志，真正的关闭交给 stop() 在锁外做。
     */
    void shutdown() {
        synchronized (this) {
            if (destroyed) return;
            destroyed = true;
        }
        stop("客户端服务停止");
        executor.shutdown();
    }

    private static class Sdp implements SdpObserver {
        public void onCreateSuccess(SessionDescription d) { }
        public void onSetSuccess() { }
        public void onCreateFailure(String e) { }
        public void onSetFailure(String e) { }
    }
}
