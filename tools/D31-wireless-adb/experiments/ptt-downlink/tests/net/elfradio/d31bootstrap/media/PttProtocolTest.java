package net.elfradio.d31bootstrap.media;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class PttProtocolTest {
    static class Time implements MediaCapture.Clock{long now=1000;public long elapsed(){return now;}public long wall(){return now;}}
    static JSONObject hello()throws Exception{return new JSONObject("{type:'hello',mode:'ptt'}");}
    static JSONObject tracks()throws Exception{return new JSONObject("{type:'tracks',sessionId:'remote-a',tracks:[{location:'remote',sessionId:'remote-a',trackName:'audio'}]}");}
    static void reply(PttProtocol p,PttProtocol.Action sent,JSONObject result)throws Exception{
        p.receive(new JSONObject().put("type","rpc").put("id",sent.body.getInt("id")).put("result",result));
    }
    static void subscribe(PttProtocol p)throws Exception{
        p.receive(hello());assertEquals("OPEN",p.poll().kind);p.opened();PttProtocol.Action create=p.poll();
        p.receive(tracks());p.receive(tracks());assertNull(p.poll());reply(p,create,new JSONObject());
        PttProtocol.Action subscribe=p.poll();assertEquals("subscribe",subscribe.body.getString("action"));
        reply(p,subscribe,new JSONObject());assertEquals("APPLY_SUBSCRIBE",p.poll().kind);
        p.answerCreated(new JSONObject());reply(p,p.poll(),new JSONObject());assertEquals("ANSWER_ACK",p.poll().kind);
    }
    @Test public void earlyTracksAndDuplicatesProduceSingleSubscribe()throws Exception{
        PttProtocol p=new PttProtocol(new Time(),46000);p.receive(tracks());subscribe(p);
        p.receive(tracks());assertNull(p.poll());assertEquals("connected_wait",p.snapshot().getString("state"));
    }
    @Test public void noReadyFromIceSubscriptionOrFramesWithoutOutputProof()throws Exception{
        PttProtocol p=new PttProtocol(new Time(),46000);subscribe(p);p.ice(true);
        assertEquals("PREPARE_MUTED",p.poll().kind);p.playbackFrames();assertNull(p.poll());assertFalse(p.snapshot().getBoolean("ready"));
        p.outputVerified();assertEquals("UNMUTE",p.poll().kind);assertNull(p.poll());
        p.unmuted();assertEquals("ready",p.poll().body.getString("type"));assertTrue(p.snapshot().getBoolean("ready"));
        p.outputVerified();p.playbackFrames();p.unmuted();assertNull(p.poll());
    }
    @Test public void stopDropsQueuedStartAndLateCallbacks()throws Exception{
        PttProtocol p=new PttProtocol(new Time(),46000);p.receive(hello());p.stop("MEDIA_HOST_CLOSED");
        assertEquals("CLOSE",p.poll().kind);p.opened();p.answerCreated(new JSONObject());p.ice(true);p.outputVerified();p.unmuted();p.receive(tracks());
        assertNull(p.poll());p.released("");assertEquals("closed",p.snapshot().getString("state"));
    }
    @Test public void realRpcReplyProcessedWithoutWaitingOnWorkerAndUnexpectedReplyStops()throws Exception{
        PttProtocol p=new PttProtocol(new Time(),46000);p.receive(hello());p.poll();p.opened();PttProtocol.Action rpc=p.poll();
        reply(p,rpc,new JSONObject());assertEquals("waiting_tracks",p.snapshot().getString("state"));
        reply(p,rpc,new JSONObject());assertEquals("MEDIA_RPC_UNEXPECTED",p.snapshot().getString("reason"));assertEquals("CLOSE",p.poll().kind);
    }
    @Test public void rpcAndInvitationTimeoutsUseMonotonicClock()throws Exception{
        Time time=new Time();PttProtocol p=new PttProtocol(time,46000);p.receive(hello());p.poll();p.opened();p.poll();
        time.now+=20000;p.tick();assertEquals("MEDIA_RPC_TIMEOUT",p.snapshot().getString("reason"));
        Time other=new Time();PttProtocol waiting=new PttProtocol(other,2000);other.now=2000;waiting.tick();
        assertEquals("MEDIA_SESSION_TIMEOUT",waiting.snapshot().getString("reason"));
    }
    @Test public void sixtySecondsStartsAtReadyAndCleanupFailureRetainsOriginalReason()throws Exception{
        Time time=new Time();PttProtocol p=new PttProtocol(time,46000);subscribe(p);p.ice(true);p.poll();p.outputVerified();p.poll();p.playbackFrames();
        time.now=20000;p.unmuted();p.poll();time.now=79999;p.tick();assertNull(p.poll());
        time.now=80000;p.tick();assertEquals("CLOSE",p.poll().kind);p.released("MEDIA_PTT_RELEASE_UNCONFIRMED");
        assertEquals("release_unconfirmed",p.snapshot().getString("state"));assertFalse(p.snapshot().getBoolean("cleanup_complete"));
        assertEquals("MEDIA_PTT_DURATION_EXPIRED",p.snapshot().getString("reason"));
    }
    @Test public void changedRemoteTrackSessionAndForeignModeRejected()throws Exception{
        PttProtocol p=new PttProtocol(new Time(),46000);p.receive(tracks());
        JSONObject other=tracks();other.put("sessionId","other");other.getJSONArray("tracks").getJSONObject(0).put("sessionId","other");p.receive(other);
        assertEquals("MEDIA_PTT_TRACKS_CHANGED",p.snapshot().getString("reason"));
        PttProtocol wrong=new PttProtocol(new Time(),46000);wrong.receive(new JSONObject("{type:'hello',mode:'call'}"));
        assertEquals("MEDIA_PTT_HELLO_INVALID",wrong.snapshot().getString("reason"));
    }
    @Test public void iceLostOrExternalOwnershipFailureClosesBeforeReady()throws Exception{
        PttProtocol p=new PttProtocol(new Time(),46000);subscribe(p);p.ice(true);p.poll();p.ice(false);
        assertEquals("MEDIA_PTT_ICE_LOST",p.snapshot().getString("reason"));assertEquals("CLOSE",p.poll().kind);
        PttProtocol guard=new PttProtocol(new Time(),46000);subscribe(guard);guard.ice(true);guard.poll();
        guard.stop("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED");assertFalse(guard.snapshot().getBoolean("ready"));
    }
}
