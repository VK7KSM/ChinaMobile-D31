package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.ArrayDeque;
import org.json.JSONArray;
import org.json.JSONObject;

/** 纯双向通话协议；不持有网络、Android对象或线程，不在锁内调用外部操作。 */
public final class CallProtocol {
    public static final long MAX_DURATION_MS = 30 * 60 * 1000L;
    public static final long CLEANUP_TIMEOUT_MS = 8000L;
    public enum Route { SPEAKER, HANDSET }

    public static final class Action {
        public final String kind;
        public final JSONObject body;
        private Action(String kind, JSONObject body) { this.kind = kind; this.body = body; }
    }

    /** completed只能用于适配器已完成协商且确实无需发送answer的分支。 */
    public static final class SubscriptionResult {
        private final JSONObject answer;
        private SubscriptionResult(JSONObject answer) {
            this.answer = answer == null ? null : copy(answer);
        }
        public static SubscriptionResult completed() { return new SubscriptionResult(null); }
        public static SubscriptionResult answer(JSONObject body) {
            if (body == null) throw new IllegalArgumentException("MEDIA_CALL_ANSWER_INVALID");
            return new SubscriptionResult(body);
        }
    }

    private final MediaCapture.Clock clock;
    private final Route route;
    private final ArrayDeque<Action> actions = new ArrayDeque<>();
    private String state = "waiting_hello", reason = "", cleanupReason = "";
    private String pending = "", remoteSession = "";
    private int sequence, pendingId;
    private long deadline, rpcDeadline, cleanupDeadline;
    private boolean hello, tracks, published, subscribing, requiresAnswer;
    private boolean subscribed, ice, prepared, inputOwned, outputOwned;
    private boolean captureFrames, playbackFrames, unmuteRequested, unmuted, ready, stopping;

    public CallProtocol(MediaCapture.Clock clock, long expiresAt) throws IOException {
        this(clock, expiresAt, Route.SPEAKER);
    }
    public CallProtocol(MediaCapture.Clock clock, long expiresAt, Route route) throws IOException {
        if (clock == null || route == null) throw new IOException("MEDIA_OFFER_INVALID");
        long wall = clock.wall();
        if (expiresAt <= wall || expiresAt - wall > 45000L || expiresAt - wall <= 0)
            throw new IOException("MEDIA_OFFER_INVALID");
        this.clock = clock;
        this.route = route;
        deadline = clock.elapsed() + expiresAt - wall;
    }

    public Route route() { return route; }
    public synchronized Action poll() { tick(); return actions.poll(); }
    public synchronized boolean hasActions() { return !actions.isEmpty(); }
    public synchronized boolean stopping() { return stopping; }

    public synchronized void receive(JSONObject message) {
        if (!active()) return;
        try {
            String type = message.getString("type");
            if ("closed".equals(type)) { stop("MEDIA_REMOTE_CLOSED"); return; }
            if ("waiting".equals(type) || "pong".equals(type) || "ready".equals(type)) return;
            if ("hello".equals(type)) {
                require(!hello && "call".equals(message.optString("mode")), "MEDIA_CALL_HELLO_INVALID");
                hello = true; state = "opening"; emit("OPEN", new JSONObject());
            } else if ("tracks".equals(type)) {
                // 此处是relay主动构造的通知，不是SFU的tracks回显；RPC结果在下面原样交给适配器。
                String session = message.getString("sessionId");
                JSONArray list = message.getJSONArray("tracks");
                require(session.matches("[A-Za-z0-9_-]{1,128}") && list.length() == 1, "MEDIA_CALL_TRACKS_INVALID");
                JSONObject track = list.getJSONObject(0);
                require("audio".equals(track.optString("trackName")) && "remote".equals(track.optString("location"))
                        && session.equals(track.optString("sessionId")), "MEDIA_CALL_TRACKS_INVALID");
                require(!tracks || remoteSession.equals(session), "MEDIA_CALL_TRACKS_CHANGED");
                tracks = true; remoteSession = session; maybeSubscribe();
            } else if ("rpc".equals(type)) {
                require(message.opt("id") instanceof Integer && message.getInt("id") == pendingId
                        && !pending.isEmpty(), "MEDIA_RPC_UNEXPECTED");
                require(!message.has("error"), "MEDIA_RPC_REJECTED");
                JSONObject result = message.getJSONObject("result");
                String action = pending; pending = ""; pendingId = 0;
                switch (action) {
                    case "new": state = "creating_publish"; emit("CREATE_PUBLISH", result); break;
                    case "publish":
                        description(result, "answer"); state = "applying_publish"; emit("APPLY_PUBLISH", result); break;
                    case "published":
                        published = true; state = "waiting_tracks"; maybeSubscribe(); break;
                    case "subscribe":
                        requiresAnswer = false;
                        if (result.has("sessionDescription")) {
                            JSONObject sdp = result.getJSONObject("sessionDescription");
                            String kind = sdp.getString("type");
                            require("offer".equals(kind) || "answer".equals(kind), "MEDIA_CALL_SDP_INVALID");
                            description(result, kind); requiresAnswer = "offer".equals(kind);
                        }
                        state = "applying_subscribe"; emit("APPLY_SUBSCRIBE", result); break;
                    case "answer": negotiationComplete(); break;
                    default: throw new IOException("MEDIA_RPC_UNEXPECTED");
                }
            } else throw new IOException("MEDIA_CALL_MESSAGE_UNEXPECTED");
        } catch (Exception failure) { stop(code(failure, "MEDIA_CALL_MESSAGE_INVALID")); }
    }

    public synchronized void opened() {
        if (!inState("opening", "MEDIA_CALL_OPEN_STATE")) return;
        state = "creating_session"; rpc("new", new JSONObject());
    }
    public synchronized void publishCreated(JSONObject offer) {
        if (!inState("creating_publish", "MEDIA_CALL_PUBLISH_STATE")) return;
        try {
            description(offer, "offer");
            JSONArray list = offer.getJSONArray("tracks");
            require(list.length() == 1, "MEDIA_CALL_PUBLISH_INVALID");
            JSONObject track = list.getJSONObject(0);
            require("audio".equals(track.getString("trackName"))
                    && track.getString("mid").matches("[A-Za-z0-9_-]{1,64}"), "MEDIA_CALL_PUBLISH_INVALID");
            state = "publishing"; rpc("publish", offer);
        } catch (Exception failure) { stop(code(failure, "MEDIA_CALL_PUBLISH_INVALID")); }
    }
    public synchronized void publishApplied() {
        if (!inState("applying_publish", "MEDIA_CALL_PUBLISH_STATE")) return;
        state = "announcing_publish"; rpc("published", new JSONObject());
    }
    private void maybeSubscribe() {
        if (published && tracks && !subscribing) {
            subscribing = true; state = "subscribing"; rpc("subscribe", new JSONObject());
        }
    }
    public synchronized void subscriptionApplied(SubscriptionResult result) {
        if (!inState("applying_subscribe", "MEDIA_CALL_SUBSCRIBE_STATE")) return;
        try {
            require(result != null && (result.answer != null) == requiresAnswer, "MEDIA_CALL_ANSWER_INVALID");
            if (requiresAnswer) {
                description(result.answer, "answer"); state = "answering"; rpc("answer", result.answer);
            } else negotiationComplete();
        } catch (Exception failure) { stop(code(failure, "MEDIA_CALL_ANSWER_INVALID")); }
    }
    private void negotiationComplete() {
        state = "finishing_negotiation"; emit("NEGOTIATED", new JSONObject());
    }
    public synchronized void negotiationApplied() {
        if (!inState("finishing_negotiation", "MEDIA_CALL_NEGOTIATION_STATE")) return;
        subscribed = true; state = "connected_wait"; maybePrepare();
    }
    public synchronized void ice(boolean connected) {
        if (!active()) return;
        if (ice && !connected) { stop("MEDIA_CALL_ICE_LOST"); return; }
        ice = connected; maybePrepare();
    }
    private void maybePrepare() {
        if (subscribed && ice && "connected_wait".equals(state)) {
            state = "preparing_muted"; emit("PREPARE_MUTED", new JSONObject());
        }
    }
    public synchronized void prepared() {
        if (!inState("preparing_muted", "MEDIA_CALL_PREPARE_STATE")) return;
        prepared = true; state = "muted_verification";
    }
    /** 只接收本机适配器严格归属与真实回调观察；Web消息不参与此门。静音采样有效。 */
    public synchronized void mediaObserved(boolean input, boolean output, boolean capture, boolean playback) {
        if (!active()) return;
        if (!prepared) { stop("MEDIA_CALL_PROOF_STATE"); return; }
        if ((inputOwned && !input) || (outputOwned && !output)) { stop("MEDIA_CALL_OWNERSHIP_LOST"); return; }
        inputOwned = input; outputOwned = output;
        captureFrames |= capture; playbackFrames |= playback;
        if (!unmuteRequested && inputOwned && outputOwned && captureFrames && playbackFrames) {
            unmuteRequested = true; state = "unmuting"; emit("UNMUTE", new JSONObject());
        }
    }
    public synchronized void unmuted() {
        if (!inState("unmuting", "MEDIA_CALL_UNMUTE_STATE")) return;
        unmuted = true; ready = true; state = "streaming";
        deadline = clock.elapsed() + MAX_DURATION_MS;
        emit("SEND", object("type", "ready"));
    }
    public synchronized void tick() {
        long now = clock.elapsed();
        if (stopping) {
            if ("closing".equals(state) && now >= cleanupDeadline) released("MEDIA_CALL_CLEANUP_TIMEOUT");
        } else if (now >= deadline) stop(ready ? "MEDIA_CALL_DURATION_EXPIRED" : "MEDIA_SESSION_TIMEOUT");
        else if (!pending.isEmpty() && now >= rpcDeadline) stop("MEDIA_RPC_TIMEOUT");
    }
    private boolean active() { tick(); return !stopping; }
    private boolean inState(String expected, String error) {
        if (!active()) return false;
        if (!expected.equals(state)) { stop(error); return false; }
        return true;
    }
    public synchronized void stop(String error) {
        if (stopping) return;
        stopping = true; state = "closing"; reason = safeCode(error, "MEDIA_STOPPED");
        cleanupDeadline = clock.elapsed() + CLEANUP_TIMEOUT_MS;
        pending = ""; pendingId = 0; actions.clear(); emit("CLOSE", new JSONObject());
    }
    /** 终态不可被重复或晚到的释放结果升级，超时仍保留待执行的CLOSE。 */
    public synchronized void released(String failure) {
        if (!stopping || !"closing".equals(state)) return;
        cleanupReason = failure == null || failure.isEmpty() ? "" : safeCode(failure, "MEDIA_CALL_RELEASE_UNCONFIRMED");
        if (cleanupReason.isEmpty() && clock.elapsed() >= cleanupDeadline) cleanupReason = "MEDIA_CALL_CLEANUP_TIMEOUT";
        state = cleanupReason.isEmpty() ? "closed" : "release_unconfirmed";
    }
    public synchronized JSONObject snapshot() {
        try { return new JSONObject().put("mode", "call").put("route", route == Route.SPEAKER ? "speaker" : "handset")
                .put("state", state).put("reason", reason).put("cleanup_reason", cleanupReason)
                .put("cleanup_complete", "closed".equals(state)).put("ready", ready && !stopping)
                .put("published", published).put("subscribed", subscribed).put("ice_connected", ice)
                .put("input_verified", inputOwned).put("output_verified", outputOwned)
                .put("capture_frames_seen", captureFrames).put("playback_frames_seen", playbackFrames)
                .put("unmuted", unmuted).put("pending_rpc", pending);
        } catch (Exception failure) { throw new IllegalStateException("MEDIA_CALL_JSON_FAILED", failure); }
    }
    private void rpc(String action, JSONObject body) {
        if (!pending.isEmpty()) { stop("MEDIA_RPC_OVERLAP"); return; }
        pending = action; pendingId = ++sequence; rpcDeadline = Math.min(deadline, clock.elapsed() + 20000L);
        try { emit("SEND", new JSONObject().put("type", "rpc").put("id", pendingId).put("action", action).put("body", body)); }
        catch (Exception failure) { stop("MEDIA_CALL_JSON_FAILED"); }
    }
    private void emit(String kind, JSONObject body) {
        if (actions.size() >= 8) { stop("MEDIA_CALL_ACTION_OVERFLOW"); return; }
        actions.add(new Action(kind, copy(body)));
    }
    private static void description(JSONObject body, String type) throws Exception {
        JSONObject sdp = body.getJSONObject("sessionDescription");
        require(type.equals(sdp.getString("type")) && !sdp.getString("sdp").trim().isEmpty(), "MEDIA_CALL_SDP_INVALID");
    }
    private static void require(boolean condition, String code) throws IOException {
        if (!condition) throw new IOException(code);
    }
    private static JSONObject copy(JSONObject body) {
        try { return new JSONObject(body.toString()); }
        catch (Exception failure) { throw new IllegalArgumentException("MEDIA_CALL_JSON_FAILED", failure); }
    }
    private static JSONObject object(String key, String value) {
        try { return new JSONObject().put(key, value); }
        catch (Exception failure) { throw new IllegalStateException("MEDIA_CALL_JSON_FAILED", failure); }
    }
    static String code(Throwable error, String fallback) { return safeCode(error.getMessage(), fallback); }
    private static String safeCode(String error, String fallback) {
        return error != null && error.matches("MEDIA_[A-Z0-9_]{1,80}") ? error : fallback;
    }
}
