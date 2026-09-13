package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.*;
import static org.junit.Assert.*;

public class AndroidRtcCallTest {
    static class Time implements MediaCapture.Clock {volatile long now=1040;public long elapsed(){return now;}public long wall(){return now;}}
    static class Guard implements CallDuplexGuard {
        volatile Evidence value;boolean busy;int preflights,reads,revokeAt=-1;
        public void requireIdleSpeakerRoute()throws Exception{preflights++;if(busy)throw new IOException("MEDIA_CALL_ROUTE_BUSY");}
        public Evidence current(Identity input,Identity output){if(++reads==revokeAt)value=null;return value;}
    }
    static class Engine implements AndroidRtcCall.Backend {
        AndroidRtcCall owner;int opens,closes;volatile boolean mute=true;boolean noIdentity,noFrames,failClose,throwMute;
        CountDownLatch openEntered,openExit,closeEntered,closeExit;final List<Boolean> mutes=Collections.synchronizedList(new ArrayList<>());
        public void open(AndroidRtcCall owner,Cancellation cancel)throws Exception{
            this.owner=owner;opens++;if(openEntered!=null)openEntered.countDown();if(openExit!=null)openExit.await();
            owner.attachMute(value->{mutes.add(value);if(throwMute)throw new IllegalStateException();mute=value;});
        }
        public JSONObject createPublish(JSONObject result)throws Exception{return new JSONObject().put("source",result);}
        public void applyPublish(JSONObject result){}
        public CallProtocol.SubscriptionResult subscribe(JSONObject result){return CallProtocol.SubscriptionResult.completed();}
        public void negotiationComplete(){}
        public void prepareMuted()throws Exception{
            if(!noIdentity){owner.identity(id(true,7101));owner.identity(id(false,7102));}
            if(!noFrames){owner.pcm(true,new byte[320],2,1,16000);owner.pcm(false,new byte[960],2,1,48000);}
        }
        public void close()throws Exception{closes++;if(closeEntered!=null)closeEntered.countDown();if(closeExit!=null)closeExit.await();if(failClose)throw new IOException("MEDIA_TEST_CLOSE");}
    }
    static CallDuplexGuard.Identity id(boolean input,int session)throws Exception{return new CallDuplexGuard.Identity(input,41001,session,input?16000:48000,1,2,input?1:3);}
    final List<AndroidRtcCall> peers=new ArrayList<>();
    static class Rig {Engine engine=new Engine();Guard guard=new Guard();Time time=new Time();AndroidRtcCall peer;AtomicInteger failures=new AtomicInteger();}
    Rig rig(){return rig(1000,500);}
    Rig rig(long operation,long release){Rig r=new Rig();r.peer=new AndroidRtcCall(r.engine,r.time,r.guard,new AndroidRtcCall.Events(){
        public void changed(boolean ice){}public void failed(String code){r.failures.incrementAndGet();}},operation,release);peers.add(r.peer);return r;}
    static void prepare(Rig r)throws Exception{
        r.peer.open(CallProtocol.Route.SPEAKER);r.peer.createPublish(new JSONObject());r.peer.applyPublish(new JSONObject());
        r.peer.subscribe(new JSONObject());r.peer.negotiationComplete();r.peer.ice(true);r.peer.prepareMuted();
    }
    static void proof(Rig r){r.guard.value=new CallDuplexGuard.Evidence(r.peer.inputIdentity(),r.peer.outputIdentity(),true,true,1000,1020);}
    interface Checked {void run()throws Exception;}
    static void rejects(String code,Checked action)throws Exception{try{action.run();fail("未拒绝："+code);}catch(IOException expected){assertEquals(code,expected.getMessage());}}
    @After public void cleanup(){for(AndroidRtcCall peer:peers)try{peer.close();}catch(Exception ignored){}}
    @Test public void constructionIsLazyAndCloseBeforeOpenIsBounded()throws Exception{
        Rig r=rig();assertEquals(0,r.engine.opens);assertNull(r.engine.owner);r.peer.close();assertEquals(1,r.engine.closes);assertTrue(r.peer.snapshot().getBoolean("peer_cleanup_complete"));
    }
    @Test public void handsetIsRejectedBeforeResourceCreation()throws Exception{
        Rig r=rig();rejects("MEDIA_CALL_ROUTE_UNSUPPORTED",()->r.peer.open(CallProtocol.Route.HANDSET));r.peer.close();assertEquals(0,r.engine.opens);
    }
    @Test public void busyRouteNeverStartsNativeResources()throws Exception{
        Rig r=rig();r.guard.busy=true;rejects("MEDIA_CALL_ROUTE_BUSY",()->r.peer.open(CallProtocol.Route.SPEAKER));r.peer.close();assertEquals(0,r.engine.opens);
    }
    @Test public void cancelledBeforeOpenCannotStartOrReopen()throws Exception{
        Rig r=rig();r.peer.cancel();rejects("MEDIA_CANCELLED",()->r.peer.open(CallProtocol.Route.SPEAKER));r.peer.close();assertEquals(0,r.engine.opens);
        rejects("MEDIA_CANCELLED",()->r.peer.open(CallProtocol.Route.SPEAKER));
    }
    @Test public void validDuplexUsesOneBackendAndSeparatePcmWithoutEnergyAuthorization()throws Exception{
        Rig r=rig();prepare(r);assertTrue(r.engine.mute);assertEquals(0,r.peer.inputProof());assertEquals(0,r.peer.outputProof());
        proof(r);assertEquals(1,r.peer.inputProof());assertEquals(1,r.peer.outputProof());r.peer.unmute();assertFalse(r.engine.mute);
        JSONObject state=r.peer.snapshot();assertEquals(160,state.getJSONObject("capture_pcm").getInt("samples"));assertEquals(480,state.getJSONObject("playback_pcm").getInt("samples"));
        assertEquals(0,state.getJSONObject("capture_pcm").getDouble("peak"),0);assertEquals(0,state.getJSONObject("playback_pcm").getDouble("peak"),0);
        assertEquals(1,r.engine.opens);assertEquals(2,r.guard.preflights);r.peer.close();assertTrue(r.engine.mute);assertEquals(1,r.engine.closes);
    }
    @Test public void pcmAndIdentityAloneNeverUnmute()throws Exception{
        Rig r=rig();prepare(r);assertTrue(r.peer.captureFrames());assertTrue(r.peer.playbackFrames());
        rejects("MEDIA_CALL_DUPLEX_UNVERIFIED",r.peer::unmute);assertTrue(r.engine.mute);
    }
    @Test public void missingIdentityCannotAuthorizeEvenWithForgedEvidence()throws Exception{
        Rig r=rig();r.engine.noIdentity=true;prepare(r);r.guard.value=new CallDuplexGuard.Evidence(id(true,7101),id(false,7102),true,true,1000,1020);
        assertEquals(0,r.peer.inputProof());assertEquals(0,r.peer.outputProof());rejects("MEDIA_CALL_DUPLEX_UNVERIFIED",r.peer::unmute);
    }
    @Test public void identityValueCopiesDoNotAuthorizeDifferentGeneration()throws Exception{
        Rig r=rig();prepare(r);r.guard.value=new CallDuplexGuard.Evidence(id(true,7101),id(false,7102),true,true,1000,1020);
        assertEquals(0,r.peer.inputProof());assertEquals(0,r.peer.outputProof());
    }
    @Test public void independentlyPassingObservationsCannotBeCombined()throws Exception{
        Rig r=rig();prepare(r);proof(r);assertEquals(1,r.peer.inputProof());
        r.guard.value=new CallDuplexGuard.Evidence(r.peer.inputIdentity(),r.peer.outputIdentity(),false,true,1000,1020);assertEquals(0,r.peer.outputProof());
        rejects("MEDIA_CALL_DUPLEX_UNVERIFIED",r.peer::unmute);assertTrue(r.engine.mute);
    }
    @Test public void newJointPairCanReplaceAnOlderPairBeforeUnmute()throws Exception{
        Rig r=rig();prepare(r);proof(r);assertEquals(1,r.peer.inputProof());proof(r);assertEquals(1,r.peer.outputProof());
        proof(r);r.peer.unmute();assertFalse(r.engine.mute);
    }
    @Test public void newPartialEvidenceAtUnmuteCannotReusePreviouslyApprovedPair()throws Exception{
        Rig r=rig();prepare(r);proof(r);r.peer.inputProof();r.peer.outputProof();
        r.guard.value=new CallDuplexGuard.Evidence(r.peer.inputIdentity(),r.peer.outputIdentity(),true,false,1000,1020);
        rejects("MEDIA_CALL_DUPLEX_UNVERIFIED",r.peer::unmute);assertTrue(r.engine.mute);
    }
    @Test public void revocationBeforeSoftwareSetterIsRechecked()throws Exception{
        Rig r=rig();prepare(r);proof(r);r.peer.inputProof();r.peer.outputProof();r.guard.revokeAt=r.guard.reads+2;
        rejects("MEDIA_CALL_DUPLEX_UNVERIFIED",r.peer::unmute);assertFalse(r.engine.mutes.contains(false));
    }
    @Test public void outputCannotPassWithoutCorrespondingInputConsumption()throws Exception{
        Rig r=rig();prepare(r);proof(r);assertEquals(0,r.peer.outputProof());
    }
    @Test public void missingFramesKeepSoftwareMuteAfterOwnership()throws Exception{
        Rig r=rig();r.engine.noFrames=true;prepare(r);proof(r);assertEquals(1,r.peer.inputProof());assertEquals(1,r.peer.outputProof());
        rejects("MEDIA_CALL_DUPLEX_UNVERIFIED",r.peer::unmute);assertTrue(r.engine.mute);
    }
    @Test public void ownershipRevocationInEitherPcmCallbackSealsBothDirections()throws Exception{
        for(boolean inputSide:new boolean[]{true,false}){
            Rig r=rig();prepare(r);proof(r);r.peer.inputProof();r.peer.outputProof();r.peer.unmute();r.guard.value=null;
            r.peer.pcm(inputSide,new byte[inputSide?320:960],2,1,inputSide?16000:48000);
            assertTrue(r.engine.mute);assertFalse(r.peer.snapshot().getBoolean("unmuted"));assertEquals(1,r.failures.get());
        }
    }
    @Test public void freshEvidenceCannotResurrectCancelledAudio()throws Exception{
        Rig r=rig();prepare(r);proof(r);r.peer.inputProof();r.peer.outputProof();r.peer.unmute();r.peer.cancel();proof(r);
        rejects("MEDIA_CANCELLED",r.peer::unmute);r.peer.pcm(true,new byte[320],2,1,16000);assertTrue(r.engine.mute);
    }
    @Test public void sessionChangeAndUnexpectedAudioStopFailClosed()throws Exception{
        Rig changed=rig();prepare(changed);changed.peer.identity(id(true,7199));assertEquals("MEDIA_CALL_IDENTITY_CHANGED",changed.peer.snapshot().getString("error"));
        Rig stopped=rig();prepare(stopped);stopped.peer.stopped(false);assertEquals("MEDIA_CALL_PLAYOUT_STOPPED",stopped.peer.snapshot().getString("error"));
    }
    @Test public void repeatIdentityRetainsReferenceAndPrivateFieldsNeverEnterSnapshot()throws Exception{
        Rig r=rig();prepare(r);CallDuplexGuard.Identity input=r.peer.inputIdentity();r.peer.identity(id(true,7101));assertSame(input,r.peer.inputIdentity());
        JSONObject privateValue=r.peer.privateInputIdentity();privateValue.put("audio_session",99);assertEquals(7101,r.peer.privateInputIdentity().getInt("audio_session"));
        String publicValue=r.peer.snapshot().toString();assertFalse(publicValue.contains("41001"));assertFalse(publicValue.contains("7101"));assertFalse(publicValue.contains("audio_session"));
    }
    @Test public void wrongCallbackFormatStopsRatherThanCountingReadiness()throws Exception{
        Rig r=rig();prepare(r);r.peer.pcm(false,new byte[960],2,1,16000);assertEquals("MEDIA_CALL_PCM_FORMAT_INVALID",r.peer.snapshot().getString("error"));
        assertEquals(1,r.peer.snapshot().getJSONObject("playback_pcm").getInt("invalid_callbacks"));
    }
    @Test public void futureWideStaleAndRollbackEvidenceCannotAuthorize()throws Exception{
        Rig r=rig();prepare(r);
        for(long[] times:new long[][]{{1000,1041},{-1,0},{0,1600},{1041,1041},{1020,1000}}){
            r.guard.value=new CallDuplexGuard.Evidence(r.peer.inputIdentity(),r.peer.outputIdentity(),true,true,times[0],times[1]);assertEquals(0,r.peer.inputProof());
        }
        proof(r);r.time.now=4500;assertEquals(1,r.peer.inputProof());r.time.now=4501;assertEquals(0,r.peer.inputProof());
    }
    @Test public void unsupportedActualSourcesRatesAndFormatsAreRejected()throws Exception{
        for(int[] shape:new int[][]{{1,41001,7101,16000,1,2,7},{1,41001,7101,48000,1,2,1},{0,41001,7102,48000,2,2,3},
                {0,41001,7102,48000,1,3,3},{0,41001,7102,48000,1,2,0},{1,0,7101,16000,1,2,1},{1,41001,0,16000,1,2,1}})
            rejects("MEDIA_CALL_IDENTITY_FORMAT_INVALID",()->new CallDuplexGuard.Identity(shape[0]==1,shape[1],shape[2],shape[3],shape[4],shape[5],shape[6]));
    }
    @Test public void blockedNativeOpenReturnsBoundedlyAndLateCompletionOnlyCloses()throws Exception{
        Rig r=rig(120,120);r.engine.openEntered=new CountDownLatch(1);r.engine.openExit=new CountDownLatch(1);
        long began=System.nanoTime();rejects("MEDIA_CALL_OPERATION_TIMEOUT",()->r.peer.open(CallProtocol.Route.SPEAKER));
        assertTrue((System.nanoTime()-began)/1000000<700);rejects("MEDIA_CALL_RELEASE_UNCONFIRMED",r.peer::close);
        r.engine.openExit.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(r.engine.closes==0&&System.nanoTime()<until)Thread.yield();
        rejects("MEDIA_CALL_RELEASE_UNCONFIRMED",r.peer::close);assertEquals(1,r.engine.opens);assertEquals(1,r.engine.closes);assertTrue(r.engine.mute);
    }
    @Test public void cancelDoesNotWaitForNativeOperation()throws Exception{
        Rig r=rig(3000,500);r.engine.openEntered=new CountDownLatch(1);r.engine.openExit=new CountDownLatch(1);
        ExecutorService caller=Executors.newSingleThreadExecutor();Future<?> opening=caller.submit(()->{try{r.peer.open(CallProtocol.Route.SPEAKER);fail();}catch(Exception expected){}});
        try{assertTrue(r.engine.openEntered.await(1,TimeUnit.SECONDS));long began=System.nanoTime();r.peer.cancel();assertTrue((System.nanoTime()-began)/1000000<100);
            opening.get(500,TimeUnit.MILLISECONDS);r.engine.openExit.countDown();r.peer.close();assertTrue(r.engine.mute);
        }finally{r.engine.openExit.countDown();caller.shutdownNow();assertTrue(caller.awaitTermination(1,TimeUnit.SECONDS));}
    }
    @Test public void closeFailureNeverReportsCompleteAndIsNotRetried()throws Exception{
        Rig r=rig();r.engine.failClose=true;rejects("MEDIA_CALL_RELEASE_UNCONFIRMED",r.peer::close);rejects("MEDIA_CALL_RELEASE_UNCONFIRMED",r.peer::close);
        assertEquals(1,r.engine.closes);assertFalse(r.peer.snapshot().getBoolean("peer_cleanup_complete"));
    }
    @Test public void throwingSoftwareMuteDoesNotPreventPhysicalClose()throws Exception{
        Rig r=rig();prepare(r);proof(r);r.peer.inputProof();r.peer.outputProof();r.peer.unmute();r.engine.throwMute=true;
        r.peer.cancel();r.peer.close();assertEquals(1,r.engine.closes);assertTrue(r.peer.snapshot().getBoolean("peer_cleanup_complete"));
    }
}
