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
        int starts,queries,stops,closes;AppMediaBridge.Callback callback;String id;
        public void start(String hash,JSONObject offer,AppMediaBridge.Callback cb){starts++;id=offer.optString("session_id");callback=cb;}
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
}
