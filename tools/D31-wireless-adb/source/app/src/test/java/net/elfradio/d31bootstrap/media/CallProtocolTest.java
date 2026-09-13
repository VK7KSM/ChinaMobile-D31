package net.elfradio.d31bootstrap.media;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CallProtocolTest {
    static final class Clock implements MediaCapture.Clock {
        volatile long wall = 1000000L, elapsed = 100L;
        public long wall() { return wall; }
        public long elapsed() { return elapsed; }
    }
    static JSONObject sdp(String type) {
        try { return new JSONObject().put("sessionDescription", new JSONObject().put("type", type).put("sdp", "v=0\r\ns=offline\r\n")); }
        catch (JSONException failure) { throw new AssertionError(failure); }
    }
    static JSONObject offer() {
        try { return sdp("offer").put("tracks", new JSONArray().put(new JSONObject().put("mid", "0").put("trackName", "audio"))); }
        catch (JSONException failure) { throw new AssertionError(failure); }
    }
    static JSONObject tracks(String session) {
        try { return new JSONObject().put("type", "tracks").put("sessionId", session).put("tracks", new JSONArray().put(
                new JSONObject().put("location", "remote").put("sessionId", session).put("trackName", "audio"))); }
        catch (JSONException failure) { throw new AssertionError(failure); }
    }
    static JSONObject hello() {
        try { return new JSONObject().put("type", "hello").put("mode", "call"); }
        catch (JSONException failure) { throw new AssertionError(failure); }
    }
    static JSONObject reply(JSONObject rpc, JSONObject result) {
        try { return new JSONObject().put("type", "rpc").put("id", rpc.getInt("id")).put("result", result); }
        catch (JSONException failure) { throw new AssertionError(failure); }
    }
    static boolean jsonBoolean(JSONObject value, String key) {
        try { return value.getBoolean(key); }
        catch (JSONException failure) { throw new AssertionError(failure); }
    }
    static CallProtocol.Action action(CallProtocol p, String kind) {
        CallProtocol.Action a = p.poll(); assertNotNull(kind, a); assertEquals(kind, a.kind); return a;
    }
    static JSONObject rpc(CallProtocol p, String name) throws Exception {
        JSONObject m = action(p, "SEND").body;
        assertEquals("rpc", m.getString("type")); assertEquals(name, m.getString("action"));
        assertNull(p.poll()); return m;
    }
    static CallProtocol create(Clock c) throws Exception { return new CallProtocol(c, c.wall + 45000L); }
    static void newSession(CallProtocol p) throws Exception {
        p.receive(hello()); action(p, "OPEN"); p.opened();
        p.receive(reply(rpc(p, "new"), new JSONObject().put("sessionId", "local")));
        action(p, "CREATE_PUBLISH");
    }
    static JSONObject publish(CallProtocol p) throws Exception {
        newSession(p); p.publishCreated(offer()); JSONObject request = rpc(p, "publish");
        p.receive(reply(request, sdp("answer"))); action(p, "APPLY_PUBLISH"); p.publishApplied();
        return rpc(p, "published");
    }
    static void subscribe(CallProtocol p, JSONObject result) throws Exception {
        p.receive(tracks("remote")); p.receive(reply(publish(p), new JSONObject().put("ok", true)));
        p.receive(reply(rpc(p, "subscribe"), result)); action(p, "APPLY_SUBSCRIBE");
    }
    static void prepared(CallProtocol p) throws Exception {
        subscribe(p, sdp("offer")); p.subscriptionApplied(CallProtocol.SubscriptionResult.answer(sdp("answer")));
        p.receive(reply(rpc(p, "answer"), new JSONObject())); action(p, "NEGOTIATED"); p.negotiationApplied();
        p.ice(true); action(p, "PREPARE_MUTED"); p.prepared();
    }
    static void streaming(CallProtocol p) throws Exception {
        prepared(p); p.mediaObserved(true, true, true, true); action(p, "UNMUTE"); p.unmuted();
        assertEquals("ready", action(p, "SEND").body.getString("type"));
        assertTrue(p.snapshot().getBoolean("ready"));
    }
    static void reason(CallProtocol p, String value) throws Exception { assertEquals(value, p.snapshot().getString("reason")); }

    @Test public void fullFiveRpcFlowPreservesOfferAndAnswer() throws Exception {
        CallProtocol p = create(new Clock());
        p.receive(tracks("remote")); newSession(p);
        JSONObject local = offer().put("extension", 7); p.publishCreated(local);
        JSONObject req = rpc(p, "publish"); assertEquals(7, req.getJSONObject("body").getInt("extension"));
        assertEquals(2, req.getInt("id"));
        JSONObject result = sdp("answer").put("tracks", new JSONArray().put("opaque"));
        p.receive(reply(req, result)); assertEquals(result.toString(), action(p, "APPLY_PUBLISH").body.toString());
        assertNull(p.poll()); p.publishApplied(); JSONObject pub = rpc(p, "published");
        assertEquals(3, pub.getInt("id")); p.receive(reply(pub, new JSONObject().put("ok", true)));
        JSONObject sub = rpc(p, "subscribe"); assertEquals(4, sub.getInt("id")); assertEquals(0, sub.getJSONObject("body").length());
        p.receive(reply(sub, sdp("offer"))); action(p, "APPLY_SUBSCRIBE");
        p.subscriptionApplied(CallProtocol.SubscriptionResult.answer(sdp("answer")));
        JSONObject ans = rpc(p, "answer"); assertEquals(5, ans.getInt("id"));
        assertEquals(sdp("answer").toString(), ans.getJSONObject("body").toString());
        p.ice(true); assertNull(p.poll()); p.receive(reply(ans, new JSONObject()));
        action(p, "NEGOTIATED"); assertFalse(p.snapshot().getBoolean("subscribed"));
        p.negotiationApplied(); action(p, "PREPARE_MUTED"); p.prepared();
        p.mediaObserved(true, true, true, true); action(p, "UNMUTE"); assertFalse(p.snapshot().getBoolean("ready"));
        p.unmuted(); action(p, "SEND"); assertTrue(p.snapshot().getBoolean("ready"));
        assertEquals("speaker", p.snapshot().getString("route"));
    }
    @Test public void earlyTracksAtEveryPublishBoundaryDoNotOverlapRpc() throws Exception {
        for (int phase = 0; phase < 7; phase++) {
            CallProtocol p = create(new Clock());
            if (phase == 0) p.receive(tracks("remote"));
            p.receive(hello()); action(p, "OPEN");
            if (phase == 1) p.receive(tracks("remote"));
            p.opened(); JSONObject n = rpc(p, "new");
            if (phase == 2) p.receive(tracks("remote"));
            p.receive(reply(n, new JSONObject())); action(p, "CREATE_PUBLISH");
            if (phase == 3) p.receive(tracks("remote"));
            p.publishCreated(offer()); JSONObject pub = rpc(p, "publish");
            if (phase == 4) p.receive(tracks("remote"));
            p.receive(reply(pub, sdp("answer"))); action(p, "APPLY_PUBLISH");
            if (phase == 5) p.receive(tracks("remote"));
            assertNull(p.poll()); p.publishApplied(); JSONObject ann = rpc(p, "published");
            if (phase == 6) p.receive(tracks("remote"));
            assertNull(p.poll()); p.receive(reply(ann, new JSONObject())); rpc(p, "subscribe");
            p.receive(tracks("remote")); assertNull(p.poll()); assertFalse(p.stopping());
        }
    }
    @Test public void lateTracksTriggerExactlyOneSubscribe() throws Exception {
        CallProtocol p = create(new Clock()); p.receive(reply(publish(p), new JSONObject()));
        assertEquals("waiting_tracks", p.snapshot().getString("state")); assertNull(p.poll());
        p.receive(tracks("remote")); rpc(p, "subscribe");
        for (int i = 0; i < 1000; i++) p.receive(tracks("remote"));
        assertNull(p.poll()); assertFalse(p.stopping());
    }
    @Test public void changedTracksFail() throws Exception {
        CallProtocol p = create(new Clock()); p.receive(tracks("one")); p.receive(tracks("two")); reason(p, "MEDIA_CALL_TRACKS_CHANGED");
    }
    @Test public void invalidTracksFailClosed() throws Exception {
        JSONObject[] invalid = {tracks("bad session"), tracks("remote").put("tracks", new JSONArray()),
                tracks("remote").put("tracks", new JSONArray().put(new JSONObject().put("trackName", "video"))),
                tracks("remote").put("tracks", tracks("other").getJSONArray("tracks")),
                tracks("remote").put("tracks", new JSONArray().put(tracks("remote").getJSONArray("tracks").get(0)).put(new JSONObject()))};
        for (JSONObject m : invalid) { CallProtocol p = create(new Clock()); p.receive(m); assertTrue(p.stopping()); }
    }
    @Test public void duplicateOrWrongHelloFail() throws Exception {
        CallProtocol p = create(new Clock()); p.receive(hello()); p.receive(hello()); reason(p, "MEDIA_CALL_HELLO_INVALID");
        p = create(new Clock()); p.receive(hello().put("mode", "ptt")); reason(p, "MEDIA_CALL_HELLO_INVALID");
    }
    @Test public void rejectedAndWrongIdRpcFail() throws Exception {
        for (Object id : new Object[]{2, "1", 1.5, JSONObject.NULL}) {
            CallProtocol p = create(new Clock()); p.receive(hello()); action(p, "OPEN"); p.opened(); JSONObject req = rpc(p, "new");
            p.receive(reply(req, new JSONObject()).put("id", id)); reason(p, "MEDIA_RPC_UNEXPECTED");
        }
        CallProtocol p = create(new Clock()); p.receive(hello()); action(p, "OPEN"); p.opened();
        p.receive(reply(rpc(p, "new"), new JSONObject()).put("error", "private details")); reason(p, "MEDIA_RPC_REJECTED");
        assertFalse(p.snapshot().toString().contains("private details"));
    }
    @Test public void duplicateRpcAckDoesNotAdvanceTwice() throws Exception {
        CallProtocol p = create(new Clock()); p.receive(hello()); action(p, "OPEN"); p.opened();
        JSONObject ack = reply(rpc(p, "new"), new JSONObject()); p.receive(ack); p.receive(ack); reason(p, "MEDIA_RPC_UNEXPECTED");
    }
    @Test public void malformedRpcAndUnknownMessageFail() throws Exception {
        CallProtocol p = create(new Clock()); p.receive(hello()); action(p, "OPEN"); p.opened();
        p.receive(reply(rpc(p, "new"), new JSONObject()).put("result", "not-object")); assertTrue(p.stopping());
        p = create(new Clock()); p.receive(new JSONObject().put("type", "unmute")); assertTrue(p.stopping());
    }
    @Test public void ignoredRemoteReadyCannotAuthorizePlayback() throws Exception {
        CallProtocol p = create(new Clock());
        for (String type : new String[]{"waiting", "pong", "ready"}) p.receive(new JSONObject().put("type", type).put("input_verified", true).put("output_verified", true));
        p.ice(true); assertFalse(p.snapshot().getBoolean("ready")); assertNull(p.poll());
    }
    @Test public void publishNeedsRealAudioOfferAndRemoteAnswer() throws Exception {
        for (JSONObject offer : new JSONObject[]{sdp("offer"), offer().put("sessionDescription", sdp("answer").get("sessionDescription")),
                offer().put("tracks", new JSONArray()), offer().put("tracks", new JSONArray().put(new JSONObject().put("mid", "").put("trackName", "audio")))}) {
            CallProtocol p = create(new Clock()); newSession(p); p.publishCreated(offer); assertTrue(p.stopping());
        }
        CallProtocol p = create(new Clock()); newSession(p); p.publishCreated(offer()); p.receive(reply(rpc(p, "publish"), sdp("offer")));
        reason(p, "MEDIA_CALL_SDP_INVALID");
    }
    @Test public void noAnswerPathRequiresExplicitAdapterCompletion() throws Exception {
        for (JSONObject result : new JSONObject[]{new JSONObject(), sdp("answer")}) {
            CallProtocol p = create(new Clock()); subscribe(p, result); assertFalse(p.snapshot().getBoolean("subscribed"));
            p.subscriptionApplied(CallProtocol.SubscriptionResult.completed()); action(p, "NEGOTIATED");
            assertNull(p.poll()); p.negotiationApplied(); assertTrue(p.snapshot().getBoolean("subscribed"));
        }
    }
    @Test public void missingOrUnsolicitedAnswerFails() throws Exception {
        CallProtocol p = create(new Clock()); subscribe(p, sdp("offer")); p.subscriptionApplied(CallProtocol.SubscriptionResult.completed());
        reason(p, "MEDIA_CALL_ANSWER_INVALID");
        p = create(new Clock()); subscribe(p, new JSONObject()); p.subscriptionApplied(CallProtocol.SubscriptionResult.answer(sdp("answer")));
        reason(p, "MEDIA_CALL_ANSWER_INVALID");
        p = create(new Clock()); subscribe(p, new JSONObject()); p.subscriptionApplied(null); reason(p, "MEDIA_CALL_ANSWER_INVALID");
    }
    @Test public void subscriptionAnswerMustBeAnswerSdp() throws Exception {
        CallProtocol p = create(new Clock()); subscribe(p, sdp("offer")); p.subscriptionApplied(CallProtocol.SubscriptionResult.answer(sdp("offer")));
        reason(p, "MEDIA_CALL_SDP_INVALID");
    }
    @Test public void allFourLocalGatesAreNecessary() throws Exception {
        for (int missing = 0; missing < 4; missing++) {
            CallProtocol p = create(new Clock()); prepared(p);
            p.mediaObserved(missing != 0, missing != 1, missing != 2, missing != 3);
            assertFalse(p.snapshot().getBoolean("ready")); assertNull(p.poll());
            p.mediaObserved(true, true, true, true); action(p, "UNMUTE"); p.unmuted(); action(p, "SEND");
            assertTrue(p.snapshot().getBoolean("ready"));
        }
    }
    @Test public void silenceCallbacksSufficeWithoutEnergyField() throws Exception {
        CallProtocol p = create(new Clock()); streaming(p); assertTrue(p.snapshot().getBoolean("capture_frames_seen"));
    }
    @Test public void observedOwnershipLossStopsActiveCall() throws Exception {
        for (boolean inputLost : new boolean[]{true, false}) {
            CallProtocol p = create(new Clock()); streaming(p); p.mediaObserved(!inputLost, inputLost, true, true);
            reason(p, "MEDIA_CALL_OWNERSHIP_LOST"); assertFalse(p.snapshot().getBoolean("ready"));
        }
    }
    @Test public void prematureProofOrUnmuteFails() throws Exception {
        CallProtocol p = create(new Clock()); p.mediaObserved(true, true, true, true); reason(p, "MEDIA_CALL_PROOF_STATE");
        p = create(new Clock()); p.unmuted(); reason(p, "MEDIA_CALL_UNMUTE_STATE");
    }
    @Test public void repeatedMediaAndIceDoNotSendReadyAgain() throws Exception {
        CallProtocol p = create(new Clock()); streaming(p);
        for (int i = 0; i < 1000; i++) { p.mediaObserved(true, true, true, true); p.ice(true); }
        assertNull(p.poll()); assertTrue(p.snapshot().getBoolean("ready"));
    }
    @Test public void iceLossAndRemoteCloseStop() throws Exception {
        CallProtocol p = create(new Clock()); streaming(p); p.ice(false); reason(p, "MEDIA_CALL_ICE_LOST");
        p = create(new Clock()); p.receive(new JSONObject().put("type", "closed")); reason(p, "MEDIA_REMOTE_CLOSED");
    }
    @Test public void rpcTimeoutIsTwentySeconds() throws Exception {
        Clock c = new Clock(); CallProtocol p = create(c); p.receive(hello()); action(p, "OPEN"); p.opened(); rpc(p, "new");
        c.elapsed += 19999; p.tick(); assertFalse(p.stopping()); c.elapsed++; p.tick(); reason(p, "MEDIA_RPC_TIMEOUT");
    }
    @Test public void invitationBoundAndWallClockJumps() throws Exception {
        for (long remaining : new long[]{-1, 0, 45001, Long.MAX_VALUE}) {
            Clock c = new Clock();
            try { new CallProtocol(c, c.wall + remaining); fail("invalid expiry accepted"); } catch (java.io.IOException expected) { }
        }
        Clock c = new Clock(); CallProtocol p = create(c); c.wall = Long.MAX_VALUE;
        c.elapsed += 44999; p.tick(); assertFalse(p.stopping()); c.elapsed++; p.tick(); reason(p, "MEDIA_SESSION_TIMEOUT");
    }
    @Test public void lateAckCannotBeatDeadlineWithoutTick() throws Exception {
        Clock c = new Clock(); CallProtocol p = create(c); p.receive(hello()); action(p, "OPEN"); p.opened(); JSONObject req = rpc(p, "new");
        c.elapsed += 20000; p.receive(reply(req, new JSONObject())); reason(p, "MEDIA_RPC_TIMEOUT");
    }
    @Test public void thirtyMinutesStartsAtReadyAndCannotRenew() throws Exception {
        Clock c = new Clock(); CallProtocol p = create(c); prepared(p); c.elapsed += 10000;
        p.mediaObserved(true, true, true, true); action(p, "UNMUTE"); p.unmuted(); action(p, "SEND");
        c.elapsed += CallProtocol.MAX_DURATION_MS - 1; p.mediaObserved(true, true, true, true); p.ice(true); p.tick();
        assertTrue(p.snapshot().getBoolean("ready")); c.elapsed++; p.tick(); reason(p, "MEDIA_CALL_DURATION_EXPIRED");
    }
    @Test public void lateUnmuteCompletionCannotExtendExpiredInvite() throws Exception {
        Clock c = new Clock(); CallProtocol p = create(c); prepared(p); p.mediaObserved(true, true, true, true); action(p, "UNMUTE");
        c.elapsed += 45000; p.unmuted(); reason(p, "MEDIA_SESSION_TIMEOUT"); assertFalse(p.snapshot().getBoolean("ready"));
    }
    @Test public void stopClearsQueueAndAllLateEventsAreInert() throws Exception {
        CallProtocol p = create(new Clock()); p.receive(hello()); p.stop("MEDIA_OWNER_DIED");
        p.opened(); p.publishCreated(offer()); p.publishApplied(); p.subscriptionApplied(null); p.negotiationApplied();
        p.prepared(); p.mediaObserved(true, true, true, true); p.unmuted(); p.ice(true); p.receive(hello()); p.stop("MEDIA_HOST_CLOSED");
        action(p, "CLOSE"); assertNull(p.poll()); reason(p, "MEDIA_OWNER_DIED"); assertFalse(p.snapshot().getBoolean("ready"));
    }
    @Test public void cleanupResultIsStickyAndSeparateFromBusinessReason() throws Exception {
        CallProtocol p = create(new Clock()); p.stop("MEDIA_OWNER_DIED"); action(p, "CLOSE");
        p.released("MEDIA_ROUTE_NOT_RELEASED"); p.released(""); p.stop("MEDIA_HOST_CLOSED");
        reason(p, "MEDIA_OWNER_DIED"); assertFalse(p.snapshot().getBoolean("cleanup_complete"));
        assertEquals("MEDIA_ROUTE_NOT_RELEASED", p.snapshot().getString("cleanup_reason"));
        p = create(new Clock()); p.stop("MEDIA_STOPPED"); p.released(""); p.released("MEDIA_FAILURE");
        assertTrue(p.snapshot().getBoolean("cleanup_complete"));
    }
    @Test public void cleanupTimeoutRetainsCloseAndCannotBeUpgraded() throws Exception {
        Clock c = new Clock(); CallProtocol p = create(c); p.stop("MEDIA_HOST_CLOSED");
        c.elapsed += CallProtocol.CLEANUP_TIMEOUT_MS; p.tick(); action(p, "CLOSE"); p.released("");
        assertEquals("MEDIA_CALL_CLEANUP_TIMEOUT", p.snapshot().getString("cleanup_reason"));
        assertFalse(p.snapshot().getBoolean("cleanup_complete")); assertNull(p.poll());
    }
    @Test public void lateReleaseWithoutInterveningTickStillHonorsCleanupBound() throws Exception {
        Clock c = new Clock(); CallProtocol p = create(c); p.stop("MEDIA_HOST_CLOSED"); action(p, "CLOSE");
        c.elapsed += CallProtocol.CLEANUP_TIMEOUT_MS; p.released("");
        assertFalse(p.snapshot().getBoolean("cleanup_complete"));
        assertEquals("MEDIA_CALL_CLEANUP_TIMEOUT", p.snapshot().getString("cleanup_reason"));
    }
    @Test public void everyRpcStageHasIndependentTwentySecondBound() throws Exception {
        for (String stage : new String[]{"publish", "published", "subscribe", "answer"}) {
            Clock c = new Clock(); CallProtocol p = create(c);
            if ("publish".equals(stage)) { newSession(p); p.publishCreated(offer()); rpc(p, "publish"); }
            else if ("published".equals(stage)) publish(p);
            else if ("subscribe".equals(stage)) { p.receive(tracks("remote")); p.receive(reply(publish(p), new JSONObject())); rpc(p, "subscribe"); }
            else { subscribe(p, sdp("offer")); p.subscriptionApplied(CallProtocol.SubscriptionResult.answer(sdp("answer"))); rpc(p, "answer"); }
            c.elapsed += 20000; p.tick(); reason(p, "MEDIA_RPC_TIMEOUT");
        }
    }
    @Test public void answerResultIsDetachedFromCallerMutation() throws Exception {
        CallProtocol p = create(new Clock()); subscribe(p, sdp("offer")); JSONObject body = sdp("answer");
        CallProtocol.SubscriptionResult result = CallProtocol.SubscriptionResult.answer(body);
        body.getJSONObject("sessionDescription").put("type", "offer"); p.subscriptionApplied(result); rpc(p, "answer");
        assertFalse(p.stopping());
    }
    @Test public void sfuDoesNotNeedToEchoLocationOrOmitEmptyErrorCode() throws Exception {
        for (Object error : new Object[]{JSONObject.NULL, ""}) {
            CallProtocol p = create(new Clock()); p.receive(tracks("remote")); newSession(p); p.publishCreated(offer());
            JSONObject published = sdp("answer").put("errorCode", error).put("tracks", new JSONArray().put(
                    new JSONObject().put("trackName", "audio").put("mid", "0").put("errorCode", error)));
            p.receive(reply(rpc(p, "publish"), published));
            JSONObject passed = action(p, "APPLY_PUBLISH").body;
            assertFalse(passed.getJSONArray("tracks").getJSONObject(0).has("location"));
            assertEquals(published.toString(), passed.toString()); p.publishApplied();
            p.receive(reply(rpc(p, "published"), new JSONObject()));
            JSONObject subscribed = sdp("offer").put("errorCode", error).put("tracks", new JSONArray().put(
                    new JSONObject().put("trackName", "audio").put("mid", "1").put("errorCode", error)));
            p.receive(reply(rpc(p, "subscribe"), subscribed));
            assertEquals(subscribed.toString(), action(p, "APPLY_SUBSCRIBE").body.toString());
            assertFalse(p.stopping()); assertFalse(p.snapshot().getBoolean("ready"));
            p.subscriptionApplied(CallProtocol.SubscriptionResult.answer(sdp("answer")));
            p.receive(reply(rpc(p, "answer"), new JSONObject())); action(p, "NEGOTIATED");
            assertFalse(p.snapshot().getBoolean("subscribed")); assertNull(p.poll());
        }
    }
    @Test public void actualSfuTrackErrorIsPreservedForAdapterRejection() throws Exception {
        CallProtocol p = create(new Clock());
        JSONObject result = sdp("offer").put("tracks", new JSONArray().put(new JSONObject().put("errorCode", "TRACK_NOT_FOUND")));
        p.receive(tracks("remote")); p.receive(reply(publish(p), new JSONObject())); p.receive(reply(rpc(p, "subscribe"), result));
        assertEquals("TRACK_NOT_FOUND", action(p, "APPLY_SUBSCRIBE").body.getJSONArray("tracks").getJSONObject(0).getString("errorCode"));
        assertFalse(p.snapshot().getBoolean("subscribed")); assertFalse(p.snapshot().getBoolean("ready"));
    }
    @Test public void snapshotsAndQueuedBodiesAreDetached() throws Exception {
        CallProtocol p = create(new Clock()); newSession(p); JSONObject body = offer(); p.publishCreated(body);
        body.getJSONObject("sessionDescription").put("sdp", "changed");
        assertNotEquals("changed", rpc(p, "publish").getJSONObject("body").getJSONObject("sessionDescription").getString("sdp"));
        p.snapshot().put("ready", true); assertFalse(p.snapshot().getBoolean("ready"));
    }
    @Test public void handsetIsExplicitExtensionNotDefault() throws Exception {
        Clock c = new Clock(); CallProtocol p = new CallProtocol(c, c.wall + 45000, CallProtocol.Route.HANDSET);
        assertEquals(CallProtocol.Route.HANDSET, p.route()); assertEquals("handset", p.snapshot().getString("route"));
    }
    @Test public void arbitraryErrorDetailsAreRedacted() throws Exception {
        CallProtocol p = create(new Clock()); p.stop("sensitive details"); p.released("sensitive path");
        reason(p, "MEDIA_STOPPED"); assertEquals("MEDIA_CALL_RELEASE_UNCONFIRMED", p.snapshot().getString("cleanup_reason"));
    }
}
