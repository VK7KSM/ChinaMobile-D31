package net.elfradio.d31bootstrap.media;

import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 真实peer状态机与真实双向解析组合；仅底层JNI后端使用离线替身。 */
public class CallDuplexIntegrationTest {
    static class Rig implements AutoCloseable {
        final CallDuplexFixtures.Time time=new CallDuplexFixtures.Time();
        final CachedCallDuplexGuard guard=new CachedCallDuplexGuard(CallDuplexFixtures.PID,time);
        final AtomicInteger failures=new AtomicInteger();
        volatile boolean muted=true;
        final AndroidRtcCall peer=new AndroidRtcCall(new AndroidRtcCall.Backend(){
            public void open(AndroidRtcCall target,Cancellation cancellation){target.attachMute(value->muted=value);}
            public JSONObject createPublish(JSONObject value){return value;}
            public void applyPublish(JSONObject value){}
            public CallProtocol.SubscriptionResult subscribe(JSONObject value){return CallProtocol.SubscriptionResult.completed();}
            public void negotiationComplete(){}
            public void prepareMuted()throws Exception{peer.identity(CallDuplexFixtures.input());peer.identity(CallDuplexFixtures.output());pcm();}
            public void close(){}
        },time,guard,new AndroidRtcCall.Events(){public void changed(boolean ice){}public void failed(String code){failures.incrementAndGet();}},1000,500);
        Rig()throws Exception {
            guard.focusOwned(true);sample(false,false);peer.open(CallProtocol.Route.SPEAKER);
            peer.createPublish(new JSONObject());peer.applyPublish(new JSONObject());peer.subscribe(new JSONObject());peer.negotiationComplete();
            peer.ice(true);peer.prepareMuted();guard.bind(peer.inputIdentity(),peer.outputIdentity());
        }
        void sample(boolean input,boolean output)throws Exception{guard.sample(CallDuplexFixtures.sample(input,output,2,1000),true,1000,1040,guard.generation());}
        void pcm(){peer.pcm(true,new byte[320],2,1,16000);peer.pcm(false,new byte[960],2,1,48000);}
        public void close()throws Exception{peer.close();guard.close();}
    }
    @Test public void realJointParserAuthorizesPeerAcrossFreshEvidenceReplacement()throws Exception{
        try(Rig r=new Rig()){
            assertTrue(r.muted);assertEquals(0,r.peer.inputProof());r.sample(true,true);assertEquals(1,r.peer.inputProof());
            r.sample(true,true);assertEquals(1,r.peer.outputProof());r.sample(true,true);r.peer.unmute();assertFalse(r.muted);
            r.pcm();assertEquals(1,r.peer.snapshot().getJSONObject("capture_pcm").getInt("guard_ready_callbacks"));
            assertEquals(1,r.peer.snapshot().getJSONObject("playback_pcm").getInt("guard_ready_callbacks"));assertEquals(0,r.failures.get());
        }
    }
    @Test public void realFocusEpochRevocationSealsBothPcmDirectionsAndCannotResurrect()throws Exception{
        try(Rig r=new Rig()){
            r.sample(true,true);r.peer.inputProof();r.peer.outputProof();r.peer.unmute();r.guard.focusOwned(false);r.pcm();
            assertTrue(r.muted);assertEquals(1,r.failures.get());
            r.guard.focusOwned(true);r.sample(true,true);r.pcm();assertTrue(r.muted);assertFalse(r.peer.snapshot().getBoolean("unmuted"));
        }
    }
}
