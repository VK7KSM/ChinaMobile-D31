package net.elfradio.d31bootstrap;

import java.net.URI;
import net.elfradio.d31bootstrap.media.AppMediaBridge;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteMediaSessionsTest {
    static final String HASH="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static class Time implements RemoteMediaSessions.Clock {
        long now=100000;public long wall(){return now;}public long elapsed(){return now;}
    }
    static class Wire implements RemoteMediaSessions.Bridge {
        int starts,queries,stops,closes;AppMediaBridge.Callback callback;String id;JSONObject startedOffer;
        public void start(String hash,JSONObject offer,AppMediaBridge.Callback cb){starts++;id=offer.optString("session_id");callback=cb;startedOffer=offer;}
        public void query(String id,AppMediaBridge.Callback cb){queries++;callback=cb;}
        public void stop(String id,AppMediaBridge.Callback cb){stops++;callback=cb;}
        public void close(){closes++;}
        void reply(String state)throws Exception{callback.completed(new JSONObject().put("session_id",id).put("state",state));}
    }
    static JSONObject offer()throws Exception{return new JSONObject().put("session_id","test-session").put("mode","microphone")
            .put("url","wss://v.elfradio.net/api/elfremote/media/device?session_id=test-session")
            .put("token","abcdefghijklmnop-secret").put("expires_at",140000);}
    RemoteMediaSessions make(Wire wire,Time time){return new RemoteMediaSessions(()->wire,HASH,URI.create("https://v.elfradio.net"),time);}
    @Test public void idleDoesNotConstructBridgeAndOnlyMicrophoneAdvertised()throws Exception {
        int[] created={0};Time time=new Time();RemoteMediaSessions sessions=new RemoteMediaSessions(()->{created[0]++;return new Wire();},HASH,URI.create("https://v.elfradio.net"),time);
        sessions.tick();assertFalse(sessions.available());sessions.accept(offer());assertEquals(0,created[0]);
        sessions.setAvailable(true);assertEquals("[\"microphone\"]",sessions.modes().toString());
        sessions.tick();sessions.snapshot();assertEquals(0,created[0]);sessions.close();
    }
    @Test public void duplicateAndNullOffersDoNotStopOrRestartConnectedSession()throws Exception {
        Wire wire=new Wire();Time time=new Time();RemoteMediaSessions s=make(wire,time);s.setAvailable(true);
        s.accept(offer());assertEquals(1,wire.starts);s.accept(offer());s.accept(null);assertEquals(1,wire.starts);
        wire.reply("connecting");s.accept(null);assertTrue(s.active());assertEquals(0,wire.stops);
        assertFalse(s.snapshot().toString().contains("abcdefghijklmnop-secret"));assertFalse(s.snapshot().toString().contains("wss:"));s.close();
    }
    @Test public void localPttContractDoesNotEnableCloudDispatch()throws Exception {
        Wire wire=new Wire();Time time=new Time();RemoteMediaSessions s=make(wire,time);s.setAvailable(true);
        s.accept(offer().put("mode","ptt"));assertEquals(0,wire.starts);assertFalse(s.active());
        assertEquals("MEDIA_MODE_NOT_AVAILABLE",s.snapshot().getString("reason"));
        assertEquals("[\"microphone\"]",s.modes().toString());s.close();
    }
    @Test public void pttDoesNotRequireMicrophoneCapabilityAndWithdrawsItsSession()throws Exception {
        Wire wire=new Wire();Time time=new Time();RemoteMediaSessions s=make(wire,time);s.setPttAvailable(true);
        assertEquals("[\"ptt\"]",s.modes().toString());s.accept(offer());assertEquals(0,wire.starts);
        s.accept(offer().put("mode","ptt"));assertEquals(1,wire.starts);wire.reply("streaming");
        s.setAvailable(false);assertEquals(0,wire.stops);
        s.setPttAvailable(false);assertEquals(1,wire.stops);wire.reply("closed");assertFalse(s.active());s.close();
    }
    @Test public void pttPlaybackAndRouteSummaryRetainNoPrivateOutputDump()throws Exception{
        Wire wire=new Wire();Time t=new Time();RemoteMediaSessions s=make(wire,t);s.setPttAvailable(true);
        s.accept(offer().put("mode","ptt"));
        wire.callback.completed(new JSONObject().put("session_id",wire.id).put("state","closed")
            .put("cleanup_complete",true).put("ptt_evidence",new JSONObject().put("complete_pairs",1)
                .put("raw","private output").put("playback",new JSONObject().put("subscribed",true)
                    .put("pcm",new JSONObject().put("samples",4800).put("peak",0.01).put("raw","private output")))
                .put("output_observer",new JSONObject().put("idle_wait_ms",3100).put("route_restored",true)
                    .put("late_route_restore",true).put("raw","private output"))));
        JSONObject value=s.snapshot().getJSONObject("session").getJSONObject("ptt_evidence");
        assertEquals(4800,value.getJSONObject("playback").getJSONObject("pcm").getInt("samples"));
        assertTrue(value.getJSONObject("output_observer").getBoolean("route_restored"));
        assertFalse(s.snapshot().toString().contains("private output"));s.close();
    }
    @Test public void videoNeedsCameraAndWithdrawalStopsOnlyItsActiveSession()throws Exception{
        Wire wire=new Wire();Time t=new Time();RemoteMediaSessions s=make(wire,t);s.setAvailable(true);
        s.accept(offer().put("mode","video"));assertEquals(0,wire.starts);
        s.setVideoAvailable(true);assertEquals("[\"microphone\",\"video\"]",s.modes().toString());
        s.accept(offer().put("mode","video"));wire.reply("streaming");
        assertEquals("video",s.snapshot().getString("mode"));s.setVideoAvailable(false);
        assertEquals(1,wire.stops);assertEquals("[\"microphone\"]",s.modes().toString());
        wire.reply("closed");assertFalse(s.active());s.close();
    }
    @Test public void queryRenewsOnlyOneRequestAndClosedOfferCannotReplay()throws Exception {
        Wire wire=new Wire();Time t=new Time();RemoteMediaSessions s=make(wire,t);s.setAvailable(true);s.accept(offer());wire.reply("connecting");
        t.now+=3000;s.tick();s.tick();assertEquals(1,wire.queries);wire.reply("streaming");assertTrue(s.active());
        t.now+=3000;s.tick();wire.reply("closed");assertFalse(s.active());s.accept(offer());assertEquals(1,wire.starts);s.close();
    }
    @Test public void stoppingDuringStartWaitsForStartThenStops()throws Exception {
        Wire wire=new Wire();Time t=new Time();RemoteMediaSessions s=make(wire,t);s.setAvailable(true);s.accept(offer());s.stop();assertEquals(0,wire.stops);
        wire.reply("connecting");assertEquals(1,wire.stops);wire.reply("closing");t.now+=3000;s.tick();wire.reply("closed");assertFalse(s.active());s.close();
    }
    @Test public void capabilityWithdrawalDuringQueryStillDispatchesStop()throws Exception {
        Wire wire=new Wire();Time t=new Time();RemoteMediaSessions s=make(wire,t);s.setAvailable(true);s.accept(offer());wire.reply("streaming");
        t.now+=3000;s.tick();s.setAvailable(false);assertEquals(0,wire.stops);wire.reply("streaming");assertEquals(1,wire.stops);assertFalse(s.available());s.close();
    }
    @Test public void connectDeadlineAndReleaseFailureKeepBusy()throws Exception {
        Wire wire=new Wire();Time t=new Time();RemoteMediaSessions s=make(wire,t);s.setAvailable(true);s.accept(offer());wire.reply("connecting");
        t.now+=40001;s.tick();assertEquals(1,wire.stops);wire.reply("release_unconfirmed");assertTrue(s.active());assertFalse(s.available());
        assertFalse(s.snapshot().getBoolean("cleanup_complete"));s.close();
    }
    @Test public void streamingHasThirtyMinuteBoundWithoutOfferRefresh()throws Exception {
        Wire wire=new Wire();Time t=new Time();RemoteMediaSessions s=make(wire,t);s.setAvailable(true);s.accept(offer());wire.reply("streaming");
        t.now+=1799000;s.tick();assertEquals(0,wire.stops);wire.reply("streaming");t.now+=1001;s.tick();assertEquals(1,wire.stops);s.close();
    }
    @Test public void preparedIdleKeepsTransportBeyondThirtyMinutesUntilExplicitStop()throws Exception{
        Wire wire=new Wire();Time time=new Time();RemoteMediaSessions s=make(wire,time);
        s.setAvailable(true);s.setPttAvailable(true);s.setVideoAvailable(true);s.setPrepareAvailable(true);
        s.accept(offer().put("mode","prepare"));
        wire.callback.completed(new JSONObject().put("session_id",wire.id).put("state","idle").put("transport_ready",true).put("operation",0));
        assertTrue(s.active());assertFalse(s.snapshot().getBoolean("cleanup_complete"));
        time.now+=1800001;s.tick();assertEquals(0,wire.stops);
        wire.callback.completed(new JSONObject().put("session_id",wire.id).put("state","active").put("transport_ready",true)
                .put("operation",1).put("active_mode","ptt"));
        assertEquals("ptt",s.snapshot().getJSONObject("session").getString("active_mode"));
        s.stop();assertEquals(1,wire.stops);wire.reply("closed");assertFalse(s.active());s.close();
    }
    @Test public void preparedWithoutTransportReadyStillHasConnectionDeadline()throws Exception{
        Wire wire=new Wire();Time time=new Time();RemoteMediaSessions s=make(wire,time);
        s.setAvailable(true);s.setPttAvailable(true);s.setVideoAvailable(true);s.setPrepareAvailable(true);
        s.accept(offer().put("mode","prepare"));wire.reply("idle");time.now+=40001;s.tick();assertEquals(1,wire.stops);s.close();
    }
    @Test public void uploadCredentialsComeOnlyFromLocalCoreAndNeverFromNetworkOffer()throws Exception{
        Wire wire=new Wire();Time time=new Time();RemoteMediaSessions s=make(wire,time);
        s.setAvailable(true);s.setPttAvailable(true);s.setVideoAvailable(true);s.setPrepareAvailable(true);
        JSONObject credentials=new JSONObject().put("device_id","test-device").put("token","local-only-token");
        s.accept(offer().put("mode","prepare").put("_credentials",new JSONObject().put("token","untrusted")),credentials);
        credentials.put("token","changed");
        assertEquals("local-only-token",wire.startedOffer.getJSONObject("_credentials").getString("token"));
        assertFalse(s.snapshot().toString().contains("local-only-token"));wire.reply("idle");s.setVideoAvailable(false);assertEquals(0,wire.stops);s.close();
        Wire second=new Wire();s=make(second,new Time());s.setAvailable(true);s.setPttAvailable(true);s.setVideoAvailable(true);s.setPrepareAvailable(true);
        s.accept(offer().put("mode","prepare").put("_credentials",new JSONObject().put("token","untrusted")));
        assertFalse(second.startedOffer.has("_credentials"));s.close();
    }
    @Test public void cameraUnavailableKeepsAudioPrepareWithoutEagerBridgeCreation()throws Exception{
        Wire wire=new Wire();Time time=new Time();int[] created={0};
        RemoteMediaSessions s=new RemoteMediaSessions(()->{created[0]++;return wire;},HASH,URI.create("https://v.elfradio.net"),time);
        s.setAvailable(true);s.setPttAvailable(true);s.setPrepareAvailable(true);s.setVideoAvailable(false);
        assertTrue(s.snapshot().getBoolean("managed_media_prepare_v1"));s.tick();assertEquals(0,created[0]);
        assertEquals("[\"microphone\",\"ptt\",\"call\"]",s.modes().toString());
        s.accept(offer().put("mode","video"));assertEquals(0,wire.starts);
        s.accept(offer().put("mode","prepare"));assertEquals(1,created[0]);assertEquals(1,wire.starts);
        wire.callback.completed(new JSONObject().put("session_id",wire.id).put("state","idle").put("transport_ready",true));
        for(String operation:new String[]{"","ptt","call","microphone","alarm"}){
            wire.callback.completed(new JSONObject().put("session_id",wire.id).put("state",operation.isEmpty()?"idle":"active")
                    .put("transport_ready",true).put("active_mode",operation));
            s.setVideoAvailable(false);assertEquals(0,wire.stops);
        }
        s.stop();assertEquals(1,wire.stops);wire.reply("closed");s.close();
    }
    @Test public void cameraWithdrawalStillStopsPreparedCameraOperationsIncludingLateReply()throws Exception{
        for(String operation:new String[]{"photo","video"})for(boolean late:new boolean[]{false,true}){
            Wire wire=new Wire();Time time=new Time();RemoteMediaSessions s=make(wire,time);
            s.setAvailable(true);s.setPttAvailable(true);s.setPrepareAvailable(true);s.setVideoAvailable(true);
            s.accept(offer().put("mode","prepare"));
            if(late){wire.reply("idle");time.now+=3000;s.tick();s.setVideoAvailable(false);assertEquals(0,wire.stops);}
            wire.callback.completed(new JSONObject().put("session_id",wire.id).put("state","active")
                    .put("transport_ready",true).put("active_mode",operation));
            if(!late)s.setVideoAvailable(false);
            assertEquals(1,wire.stops);wire.reply("closed");s.close();
        }
    }
    @Test public void cameraOptionalPrepareStillRequiresAudioAndExplicitReadiness()throws Exception{
        for(int flags=0;flags<8;flags++){
            Wire wire=new Wire();RemoteMediaSessions s=make(wire,new Time());
            s.setAvailable((flags&1)!=0);s.setPttAvailable((flags&2)!=0);s.setPrepareAvailable((flags&4)!=0);
            assertEquals(flags==7,s.snapshot().getBoolean("managed_media_prepare_v1"));
            s.accept(offer().put("mode","prepare"));assertEquals(flags==7?1:0,wire.starts);s.close();
        }
    }
    @Test public void unsupportedExpiredAndForeignOffersNeverStart()throws Exception {
        Wire wire=new Wire();Time t=new Time();RemoteMediaSessions s=make(wire,t);s.setAvailable(true);
        s.accept(offer().put("mode","call"));s.accept(offer().put("expires_at",99999));s.accept(offer().put("url","wss://elsewhere.test/api/elfremote/media/device?session_id=test-session"));
        assertEquals(0,wire.starts);assertFalse(s.active());s.close();
    }
    @Test public void unchangedQueriesDoNotWakeCloudReports()throws Exception {
        Wire wire=new Wire();Time t=new Time();RemoteMediaSessions s=make(wire,t);int[] events={0};s.listener(()->events[0]++);
        s.setAvailable(true);s.accept(offer());wire.reply("streaming");int count=events[0];
        for(int n=0;n<20;n++){t.now+=3000;s.tick();wire.reply("streaming");s.setAvailable(true);}
        assertEquals(count,events[0]);t.now+=3000;s.tick();wire.reply("closed");assertEquals(count+1,events[0]);s.close();
    }
    @Test public void terminalReasonAndGuardSummarySurviveWithoutPrivateRawDump()throws Exception {
        Wire wire=new Wire();Time t=new Time();RemoteMediaSessions s=make(wire,t);s.setAvailable(true);s.accept(offer());wire.reply("streaming");
        t.now+=3000;s.tick();wire.callback.completed(new JSONObject().put("session_id",wire.id).put("state","closed")
                .put("reason","MEDIA_RTC_INPUT_UNPARSEABLE").put("diagnostics",new JSONObject()
                    .put("flinger","private raw").put("guard",new JSONObject().put("failure_code","MEDIA_RTC_INPUT_UNPARSEABLE")
                        .put("raw","private raw").put("last_sample",new JSONObject().put("input_reason","INPUT_CONFIGURATION_UNVERIFIED")))
                    .put("record_parameters",new JSONObject().put("sample_rate",48000).put("session_id",42))));
        JSONObject snapshot=s.snapshot();assertEquals("MEDIA_RTC_INPUT_UNPARSEABLE",snapshot.getString("reason"));
        assertEquals(48000,snapshot.getJSONObject("session").getJSONObject("diagnostics").getJSONObject("record_parameters").getInt("sample_rate"));
        assertTrue(snapshot.toString().contains("INPUT_CONFIGURATION_UNVERIFIED"));assertFalse(snapshot.toString().contains("private raw"));s.close();
    }
    @Test public void advancingPcmCountersDoNotScheduleRepeatedReports()throws Exception{
        Wire wire=new Wire();Time t=new Time();RemoteMediaSessions s=make(wire,t);int[] wakes={0};s.listener(()->wakes[0]++);
        s.setAvailable(true);s.accept(offer());wire.reply("streaming");int before=wakes[0];
        for(int i=1;i<=5;i++){
            t.now+=3000;s.tick();wire.callback.completed(new JSONObject().put("session_id",wire.id).put("state","streaming")
                .put("diagnostics",new JSONObject().put("pcm",new JSONObject().put("samples",i*16000).put("rms",0.01).put("raw","never expose"))));
        }
        assertEquals(before,wakes[0]);
        assertEquals(80000,s.snapshot().getJSONObject("session").getJSONObject("diagnostics").getJSONObject("pcm").getInt("samples"));
        assertFalse(s.snapshot().toString().contains("never expose"));s.close();
    }
    @Test public void callRequiresBothRecordingAndOutputCapability()throws Exception{
        for(int flags=0;flags<4;flags++){
            Wire wire=new Wire();RemoteMediaSessions sessions=make(wire,new Time());
            sessions.setAvailable((flags&1)!=0);sessions.setPttAvailable((flags&2)!=0);
            assertEquals(flags==3,sessions.modes().toString().contains("\"call\""));sessions.accept(offer().put("mode","call"));
            assertEquals(flags==3?1:0,wire.starts);assertFalse(sessions.snapshot().getBoolean("managed_media_prepare_v1"));sessions.close();
        }
    }
    @Test public void eitherCallCapabilityWithdrawalStopsAfterInflightReply()throws Exception{
        for(boolean microphone:new boolean[]{false,true}){
            Wire wire=new Wire();Time time=new Time();RemoteMediaSessions sessions=make(wire,time);
            sessions.setAvailable(true);sessions.setPttAvailable(true);sessions.accept(offer().put("mode","call"));
            if(microphone)sessions.setAvailable(false);else sessions.setPttAvailable(false);
            assertEquals(0,wire.stops);wire.reply("connecting");assertEquals(1,wire.stops);
            wire.reply("closed");assertFalse(sessions.active());assertFalse(sessions.modes().toString().contains("\"call\""));sessions.close();
        }
    }
    @Test public void callReceiptCarriesBothDirectionsWithoutPrivateFieldsOrContentClaim()throws Exception{
        Wire wire=new Wire();RemoteMediaSessions sessions=make(wire,new Time());sessions.setAvailable(true);sessions.setPttAvailable(true);
        sessions.accept(offer().put("mode","call"));
        wire.callback.completed(new JSONObject().put("session_id",wire.id).put("state","streaming")
                .put("mode","call").put("ready",true).put("input_verified",true).put("output_verified",true)
                .put("capture_frames_seen",true).put("playback_frames_seen",true).put("unmuted",true).put("subscribed",true)
                .put("route","speaker").put("remote_audio_content","VERIFIED").put("local_recording",true)
                .put("token","private-value").put("input_identity",new JSONObject().put("pid",123)).put("managed_media_prepare_v1",true));
        JSONObject detail=sessions.snapshot().getJSONObject("session");
        assertTrue(detail.getBoolean("input_verified"));assertTrue(detail.getBoolean("output_verified"));assertTrue(detail.getBoolean("ready"));
        assertEquals("speaker",detail.getString("route"));assertEquals("NOT_VERIFIED",detail.getString("remote_audio_content"));
        assertFalse(detail.getBoolean("local_recording"));assertFalse(detail.toString().contains("private-value"));assertFalse(detail.has("input_identity"));
        assertFalse(detail.has("managed_media_prepare_v1"));sessions.close();
    }
    @Test public void callReleaseFailureKeepsSlotAndRejectsNewInvites()throws Exception{
        Wire wire=new Wire();RemoteMediaSessions sessions=make(wire,new Time());sessions.setAvailable(true);sessions.setPttAvailable(true);
        sessions.accept(offer().put("mode","call"));wire.reply("release_unconfirmed");
        assertTrue(sessions.active());assertFalse(sessions.available());assertEquals(0,sessions.modes().length());
        sessions.accept(offer().put("mode","call").put("session_id","different"));assertEquals(1,wire.starts);sessions.close();
    }
    @Test public void callReceiptDoesNotCoerceStringsIntoDirectionalProof()throws Exception{
        Wire wire=new Wire();RemoteMediaSessions sessions=make(wire,new Time());sessions.setAvailable(true);sessions.setPttAvailable(true);
        sessions.accept(offer().put("mode","call"));wire.callback.completed(new JSONObject().put("session_id",wire.id).put("state","muted_verification")
                .put("input_verified","true").put("output_verified",1).put("ready","true").put("route","private-route"));
        JSONObject detail=sessions.snapshot().getJSONObject("session");assertFalse(detail.has("input_verified"));assertFalse(detail.has("output_verified"));
        assertFalse(detail.has("ready"));assertFalse(detail.has("route"));sessions.close();
    }
}
