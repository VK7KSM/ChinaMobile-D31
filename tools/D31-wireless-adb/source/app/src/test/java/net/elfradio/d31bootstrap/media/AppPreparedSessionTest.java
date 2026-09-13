package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AppPreparedSessionTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    static final class Time implements MediaCapture.Clock {
        volatile long now=1000;
        public long wall(){return 100000+now;}
        public long elapsed(){return now;}
    }
    static JSONObject offer(Time clock)throws Exception{return new JSONObject().put("session_id","prepared-test")
        .put("mode","prepare").put("camera","front").put("expires_at",clock.wall()+45000)
        .put("url","wss://v.elfradio.net/api/elfremote/media/device?session_id=prepared-test")
        .put("token","abcdefghijklmnop-secret");}
    interface Check{boolean ok()throws Exception;}
    static void until(Check check)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!check.ok()&&System.nanoTime()<end)Thread.sleep(2);assertTrue("等待条件未满足",check.ok());}
    static void await(CountDownLatch value)throws Exception{if(!value.await(3,TimeUnit.SECONDS))throw new IOException("TEST_GATE_TIMEOUT");}
    static final class Wire implements MicrophoneSession.Transport {
        volatile MicrophoneSession.Events events;
        final List<JSONObject> messages=new CopyOnWriteArrayList<>();
        volatile String holdRpc="";
        volatile boolean rejectRpc,failClose,connectBlocked;
        final CountDownLatch connected=new CountDownLatch(1),connectGate=new CountDownLatch(1);
        int connects,aborts,closes;
        public void connect(RtcOffer offer,MicrophoneSession.Events events)throws Exception{
            connects++;this.events=events;connected.countDown();if(connectBlocked)await(connectGate);
        }
        public void send(JSONObject value)throws Exception{
            JSONObject saved=new JSONObject(value.toString());messages.add(saved);
            if("rpc".equals(saved.optString("type"))&&!holdRpc.equals(saved.optString("action"))){
                JSONObject response=new JSONObject().put("type","rpc").put("id",saved.getInt("id"));
                if(rejectRpc)response.put("error","PRIVATE_TOKEN_PATH");else response.put("result",new JSONObject());
                events.message(response.toString());
            }
        }
        public void abort(){aborts++;connectGate.countDown();}
        public void close()throws Exception{closes++;if(failClose)throw new IOException("PRIVATE_TOKEN_PATH");}
        int count(String type){int n=0;for(JSONObject value:messages)if(type.equals(value.optString("type")))n++;return n;}
        List<String> actions(){List<String> result=new ArrayList<>();for(JSONObject value:messages)if("rpc".equals(value.optString("type")))result.add(value.optString("action"));return result;}
    }
    static final class Peer implements PreparedSessionPort.Peer {
        PreparedSessionPort.Events events;
        volatile boolean negotiated,connected=true,hardwareReady=true,active,failDeactivate,failClose,answer,activeBlocked;
        volatile long operation;
        volatile long invalidated;
        final AtomicInteger invalidations=new AtomicInteger(),hardwareStarts=new AtomicInteger(),cancels=new AtomicInteger();
        volatile Thread invalidationThread;
        Exception publishFailure;
        final AtomicInteger opens=new AtomicInteger(),activations=new AtomicInteger(),deactivations=new AtomicInteger(),closes=new AtomicInteger(),switches=new AtomicInteger();
        final AtomicInteger snapshots=new AtomicInteger();
        final CountDownLatch activationEntered=new CountDownLatch(1),activationGate=new CountDownLatch(1);
        String mode="",camera="";
        String actualCamera;
        Object cameras=2;
        public void open(){opens.incrementAndGet();}
        public JSONObject createPublish(JSONObject result)throws Exception{if(publishFailure!=null)throw publishFailure;return new JSONObject().put("tracks",new JSONArray());}
        public void applyPublish(JSONObject result){}
        public JSONObject subscribe(JSONObject result)throws Exception{return answer?new JSONObject().put("sessionDescription",new JSONObject().put("type","answer").put("sdp","private-sdp")):null;}
        public void negotiationComplete(){negotiated=true;events.changed();}
        public boolean transportReady(){return negotiated&&connected;}
        public void activate(long op,String mode,String camera)throws Exception{
            if(op!=operation+1)throw new IOException("TEST_OPERATION");operation=op;this.mode=mode;this.camera=camera;
            activations.incrementAndGet();activationEntered.countDown();if(activeBlocked)await(activationGate);
            if(op<=invalidated)return;
            hardwareStarts.incrementAndGet();active=true;events.changed();
        }
        public void invalidateOperation(long op){invalidated=op;invalidationThread=Thread.currentThread();invalidations.incrementAndGet();}
        public boolean operationReady(long op){return active&&op==operation&&hardwareReady;}
        public void deactivate(long op)throws Exception{
            if(!active&&op==operation+1)operation=op;
            deactivations.incrementAndGet();if(failDeactivate)throw new IOException("PRIVATE_TOKEN_PATH");active=false;events.changed();
        }
        public void switchCamera(long op,String camera){switches.incrementAndGet();this.camera=camera;}
        public JSONObject snapshot()throws Exception{snapshots.incrementAndGet();return new JSONObject().put("PRIVATE_IDENTITY",123)
            .put("media",new JSONObject().put("operation",operation).put("capture_started",active).put("playback_started",active)
                .put("camera",actualCamera==null?camera:actualCamera).put("cameras",cameras)
                .put("video_started",active&&"video".equals(mode)).put("input_identity",new JSONObject().put("pid",123))
                .put("capture_pcm",new JSONObject().put("samples",320).put("rms",0.125).put("raw","PRIVATE_IDENTITY")));}
        public void cancel(){cancels.incrementAndGet();activationGate.countDown();}
        public void close()throws Exception{closes.incrementAndGet();active=false;if(failClose)throw new IOException("PRIVATE_TOKEN_PATH");}
    }
    static final class Extra implements PreparedSessionPort.Extra {
        final PreparedSessionPort.Operation operation;final PreparedSessionPort.OperationEvents events;
        volatile boolean ready=true,closeBlocked,failClose;
        final CountDownLatch closeEntered=new CountDownLatch(1),closeGate=new CountDownLatch(1);
        int starts,cancels,closes,switches;
        Extra(PreparedSessionPort.Operation operation,PreparedSessionPort.OperationEvents events){this.operation=operation;this.events=events;}
        public void start(){starts++;events.changed();}
        public boolean ready(){return ready;}
        public void switchCamera(String camera){switches++;}
        public void cancel(){cancels++;}
        public void close()throws Exception{closes++;closeEntered.countDown();if(closeBlocked)await(closeGate);if(failClose)throw new IOException("PRIVATE_TOKEN_PATH");}
        void result()throws Exception{events.message(new JSONObject().put("type","result").put("operation",operation.number)
            .put("report_id",operation.reportId).put("captured_at",123456).put("path","PRIVATE_TOKEN_PATH"));}
    }
    final class Flow implements AutoCloseable {
        final Time clock=new Time();final Wire wire=new Wire();final Peer peer=new Peer();
        final List<Extra> extras=new CopyOnWriteArrayList<>();final File files=temporary.newFolder();
        final AppPreparedSession session;
        volatile boolean extraReady=true,peerFactoryBlocked,extraFactoryBlocked;
        final CountDownLatch factoryEntered=new CountDownLatch(1),factoryGate=new CountDownLatch(1);
        Flow()throws Exception{
            RtcOffer parsed=RtcOffer.parse(offer(clock),URI.create("https://v.elfradio.net"),clock.wall());
            session=new AppPreparedSession(files,parsed,clock,wire,new PreparedSessionPort.Factory(){
                public PreparedSessionPort.Peer createPeer(PreparedSessionPort.Events events)throws Exception{
                    peer.events=events;if(peerFactoryBlocked){factoryEntered.countDown();await(factoryGate);}return peer;
                }
                public PreparedSessionPort.Extra createExtra(PreparedSessionPort.Operation op,PreparedSessionPort.OperationEvents events)throws Exception{
                    Extra extra=new Extra(op,events);extra.ready=extraReady;extras.add(extra);
                    if(extraFactoryBlocked){factoryEntered.countDown();await(factoryGate);}return extra;
                }
            });
        }
        void begin()throws Exception{session.start();await(wire.connected);}
        void ready()throws Exception{begin();hello();tracks();until(()->"idle".equals(state()));}
        void hello()throws Exception{session.receive(new JSONObject().put("type","hello").put("mode","prepare").put("camera","front").toString());}
        void tracks()throws Exception{session.receive(new JSONObject().put("type","tracks").put("sessionId","remote-test")
            .put("tracks",new JSONArray().put(new JSONObject().put("location","remote").put("sessionId","remote-test").put("trackName","audio"))).toString());}
        String state()throws Exception{return session.snapshot().getString("state");}
        void activate(long op,String mode)throws Exception{session.receive(new JSONObject().put("type","activate").put("operation",op)
            .put("mode",mode).put("camera","front").put("report_id","prepared-test-"+op).toString());}
        void deactivate(long op)throws Exception{session.receive(new JSONObject().put("type","deactivate").put("operation",op).toString());}
        void awaitReady()throws Exception{until(()->session.snapshot().getBoolean("ready"));}
        public void close()throws Exception{
            factoryGate.countDown();peer.activationGate.countDown();for(Extra extra:extras)extra.closeGate.countDown();
            session.stop();assertTrue(session.awaitClosed(3000));
            // 失败会话故意保留租约；仅释放本测试夹具，避免污染下一测试和临时目录。
            Field field=AppPreparedSession.class.getDeclaredField("lease");field.setAccessible(true);
            MediaFiles.Lease lease=(MediaFiles.Lease)field.get(session);if(lease!=null)lease.close();
        }
    }
    @Test public void negotiatesOriginalRpcAndReusesSingleTransportAndLeaseAcrossAllSixModes()throws Exception{
        try(Flow flow=new Flow()){
            flow.peer.answer=true;flow.ready();assertEquals(Arrays.asList("new","publish","published","subscribe","answer"),flow.wire.actions());
            assertEquals(1,flow.wire.count("transport_ready"));assertEquals(0,flow.peer.activations.get());
            long number=0;for(String mode:new String[]{"ptt","call","microphone","video","photo","alarm"}){
                flow.activate(++number,mode);flow.awaitReady();assertEquals(mode,flow.session.snapshot().getString("active_mode"));
                assertEquals(number,flow.session.snapshot().getLong("operation"));flow.deactivate(number);until(()->"idle".equals(flow.state()));
            }
            assertEquals(1,flow.wire.connects);assertEquals(1,flow.peer.opens.get());assertEquals(6,flow.wire.count("idle"));
            assertEquals(6,flow.wire.count("ready"));assertEquals(2,flow.extras.size());assertEquals(0,flow.wire.closes);
            try{MediaFiles.lease(new File(flow.files,"media"));fail();}catch(IOException expected){assertEquals("MEDIA_BUSY",expected.getMessage());}
        }
    }
    @Test public void noAnswerBranchStillCompletesNegotiation()throws Exception{try(Flow flow=new Flow()){
        flow.ready();assertTrue(flow.peer.negotiated);assertFalse(flow.wire.actions().contains("answer"));}}
    @Test public void tracksCanArriveWhileNewRpcIsPending()throws Exception{try(Flow flow=new Flow()){
        flow.wire.holdRpc="new";flow.begin();flow.hello();until(()->flow.wire.count("rpc")==1);flow.tracks();
        flow.session.receive(new JSONObject().put("type","rpc").put("id",1).put("result",new JSONObject()).toString());
        until(()->"idle".equals(flow.state()));assertEquals(1,flow.wire.count("transport_ready"));}}
    @Test public void answerMustBeAcknowledgedBeforeTransportReady()throws Exception{try(Flow flow=new Flow()){
        flow.peer.answer=true;flow.wire.holdRpc="answer";flow.begin();flow.hello();flow.tracks();until(()->flow.wire.actions().contains("answer"));
        assertFalse(flow.peer.negotiated);assertEquals(0,flow.wire.count("transport_ready"));
        flow.session.receive(new JSONObject().put("type","rpc").put("id",5).put("result",new JSONObject()).toString());until(()->"idle".equals(flow.state()));}}
    @Test public void inactiveObjectsDoNotPreventTransportReadyAndNoThirtyMinuteIdleDeadline()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.clock.now+=7200000;flow.session.tick();assertEquals("idle",flow.state());assertTrue(flow.session.snapshot().getBoolean("transport_ready"));}}
    @Test public void peerReadinessCannotClaimPhotoOrAlarmHardwareStarted()throws Exception{
        for(String mode:new String[]{"photo","alarm"})try(Flow flow=new Flow()){
            flow.extraReady=false;flow.ready();flow.activate(1,mode);until(()->flow.extras.size()==1&&flow.extras.get(0).starts==1);
            assertEquals(0,flow.wire.count("ready"));Extra extra=flow.extras.get(0);extra.ready=true;extra.events.changed();flow.awaitReady();
        }
    }
    @Test public void iceAloneCannotClaimAudioReady()throws Exception{try(Flow flow=new Flow()){
        flow.peer.hardwareReady=false;flow.ready();flow.activate(1,"call");until(()->flow.peer.activations.get()==1);
        assertEquals(0,flow.wire.count("ready"));flow.peer.hardwareReady=true;flow.peer.events.changed();flow.awaitReady();}}
    @Test public void repeatedDeactivateIsIdempotentAndOldStopCannotAffectNextOperation()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.activate(1,"ptt");flow.awaitReady();flow.deactivate(1);until(()->"idle".equals(flow.state()));
        flow.deactivate(1);until(()->flow.wire.count("idle")==2);assertEquals(1,flow.peer.deactivations.get());
        flow.activate(2,"call");flow.awaitReady();flow.deactivate(1);assertTrue(flow.session.snapshot().getBoolean("ready"));assertEquals(1,flow.peer.deactivations.get());}}
    @Test public void cancelledQueuedActivationDoesNotOpenHardwareOrBreakNextGeneration()throws Exception{try(Flow flow=new Flow()){
        flow.ready();CountDownLatch entered=new CountDownLatch(1),gate=new CountDownLatch(1);
        Field field=AppPreparedSession.class.getDeclaredField("worker");field.setAccessible(true);
        ((ExecutorService)field.get(flow.session)).execute(()->{entered.countDown();try{await(gate);}catch(Exception ignored){}});await(entered);
        flow.activate(1,"call");flow.deactivate(1);gate.countDown();until(()->"idle".equals(flow.state()));
        assertEquals(0,flow.peer.activations.get());assertEquals(1,flow.peer.operation);
        flow.activate(2,"ptt");flow.awaitReady();assertEquals(1,flow.peer.activations.get());}}
    @Test public void stopDuringActivationSuppressesLateReadyBeforeIdle()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.peer.activeBlocked=true;flow.activate(1,"call");await(flow.peer.activationEntered);flow.deactivate(1);
        flow.peer.activationGate.countDown();until(()->"idle".equals(flow.state()));assertEquals(0,flow.wire.count("ready"));assertFalse(flow.peer.active);}}
    @Test public void deactivateInvalidatesBlockedActivationImmediatelyWithoutCancellingTransport()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.peer.activeBlocked=true;flow.activate(1,"call");await(flow.peer.activationEntered);
        flow.deactivate(1);assertEquals(1,flow.peer.invalidated);assertSame(Thread.currentThread(),flow.peer.invalidationThread);
        assertEquals(0,flow.peer.deactivations.get());assertEquals(0,flow.peer.cancels.get());
        flow.deactivate(1);assertEquals(1,flow.peer.invalidations.get());
        flow.peer.activationGate.countDown();until(()->"idle".equals(flow.state()));
        assertEquals(0,flow.peer.hardwareStarts.get());assertEquals(0,flow.wire.count("ready"));
        assertTrue(flow.session.snapshot().getBoolean("transport_ready"));assertEquals("",flow.session.snapshot().getString("reason"));
        flow.activate(2,"microphone");flow.awaitReady();assertEquals(1,flow.peer.hardwareStarts.get());
        flow.deactivate(1);assertEquals(1,flow.peer.invalidations.get());assertTrue(flow.session.snapshot().getBoolean("ready"));
    }}
    @Test public void lateOldPhotoCallbacksCannotBeReboundToCurrentOperation()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.activate(1,"photo");flow.awaitReady();Extra old=flow.extras.get(0);flow.deactivate(1);until(()->"idle".equals(flow.state()));
        flow.activate(2,"photo");flow.awaitReady();old.result();old.events.failed("PRIVATE_TOKEN_PATH");old.events.changed();
        assertEquals(0,flow.wire.count("result"));assertEquals("active",flow.state());
        flow.extras.get(1).result();until(()->flow.wire.count("result")==1);
        for(JSONObject value:flow.wire.messages)if("result".equals(value.optString("type"))){assertEquals(2,value.getLong("operation"));assertFalse(value.toString().contains("PRIVATE"));}
    }}
    @Test public void photoResultDoesNotInventIdleWithoutDeactivate()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.activate(1,"photo");flow.awaitReady();flow.extras.get(0).result();until(()->flow.wire.count("result")==1);
        assertEquals("active",flow.state());assertEquals(0,flow.wire.count("idle"));}}
    @Test public void idleWaitsForExtraReleaseAndPreservesPeer()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.activate(1,"alarm");flow.awaitReady();Extra extra=flow.extras.get(0);extra.closeBlocked=true;
        flow.deactivate(1);await(extra.closeEntered);assertEquals(0,flow.wire.count("idle"));assertEquals("stopping",flow.session.snapshot().getString("operation_state"));
        extra.closeGate.countDown();until(()->"idle".equals(flow.state()));assertEquals(0,flow.peer.closes.get());}}
    @Test public void extraReleaseFailureStillStopsPeerAndClosesWholeConnection()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.activate(1,"alarm");flow.awaitReady();flow.extras.get(0).failClose=true;flow.deactivate(1);
        assertTrue(flow.session.awaitClosed(3000));assertEquals(1,flow.peer.deactivations.get());assertEquals(1,flow.peer.closes.get());
        assertEquals(0,flow.wire.count("idle"));assertEquals("release_unconfirmed",flow.state());}}
    @Test public void peerDeactivateFailureDoesNotReturnIdle()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.activate(1,"call");flow.awaitReady();flow.peer.failDeactivate=true;flow.deactivate(1);
        assertTrue(flow.session.awaitClosed(3000));assertEquals(0,flow.wire.count("idle"));assertEquals(1,flow.wire.closes);}}
    @Test public void disconnectCancelsPendingRpcWithoutWaitingTwentySeconds()throws Exception{try(Flow flow=new Flow()){
        flow.wire.holdRpc="new";flow.begin();flow.hello();until(()->flow.wire.count("rpc")==1);
        long before=System.nanoTime();flow.wire.events.disconnected();assertTrue(flow.session.awaitClosed(2000));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<2000);assertEquals(1,flow.peer.closes.get());}}
    @Test public void rpcErrorAndWrongFutureIdCloseWithoutLeakingServerText()throws Exception{
        for(boolean reject:new boolean[]{false,true})try(Flow flow=new Flow()){
            flow.wire.rejectRpc=reject;flow.wire.holdRpc=reject?"":"new";flow.begin();flow.hello();until(()->flow.wire.count("rpc")>=1);
            if(!reject)flow.session.receive(new JSONObject().put("type","rpc").put("id",100).put("result",new JSONObject()).toString());
            assertTrue(flow.session.awaitClosed(3000));assertFalse(flow.session.snapshot().toString().contains("PRIVATE"));
            for(JSONObject value:flow.wire.messages)assertFalse(value.toString().contains("PRIVATE"));
        }
    }
    @Test public void oldRpcResponsesAreIgnoredAfterNegotiation()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.session.receive(new JSONObject().put("type","rpc").put("id",1).put("error","PRIVATE").toString());assertEquals("idle",flow.state());}}
    @Test public void invalidOperationModeBindingAndNumericTypeCannotActivate()throws Exception{
        for(int variant=0;variant<4;variant++)try(Flow flow=new Flow()){
            flow.ready();JSONObject message=new JSONObject().put("type","activate").put("operation",1).put("mode","call").put("camera","front").put("report_id","prepared-test-1");
            if(variant==0)message.put("operation",2);if(variant==1)message.put("mode","unknown");
            if(variant==2)message.put("report_id","foreign");if(variant==3)message.put("operation","1");
            flow.session.receive(message.toString());assertTrue(flow.session.awaitClosed(3000));assertEquals(0,flow.peer.activations.get());
        }
    }
    @Test public void activateBeforeTransportReadyFailsClosed()throws Exception{try(Flow flow=new Flow()){
        flow.begin();flow.activate(1,"call");assertTrue(flow.session.awaitClosed(3000));assertEquals(0,flow.peer.activations.get());}}
    @Test public void activationAndInitialConnectionDeadlinesAreIndependent()throws Exception{
        try(Flow flow=new Flow()){flow.begin();flow.clock.now+=45000;flow.session.tick();assertTrue(flow.session.awaitClosed(3000));assertEquals("MEDIA_PREPARED_CONNECT_TIMEOUT",flow.session.snapshot().getString("reason"));}
        try(Flow flow=new Flow()){flow.ready();flow.peer.hardwareReady=false;flow.activate(1,"call");flow.clock.now+=15000;flow.session.tick();
            assertTrue(flow.session.awaitClosed(3000));assertEquals("MEDIA_PREPARED_ACTIVATION_TIMEOUT",flow.session.snapshot().getString("reason"));}
    }
    @Test public void operationDurationDoesNotBecomePersistentConnectionLifetime()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.activate(1,"ptt");flow.awaitReady();flow.clock.now+=59000;flow.session.tick();assertEquals("active",flow.state());
        flow.deactivate(1);until(()->"idle".equals(flow.state()));flow.clock.now+=3600000;flow.session.tick();assertEquals("idle",flow.state());}}
    @Test public void iceLossAfterReadinessClosesInsteadOfHoldingIdleForever()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.peer.connected=false;flow.peer.events.changed();assertTrue(flow.session.awaitClosed(3000));assertEquals(1,flow.wire.closes);}}
    @Test public void cameraSwitchUsesOriginalGenerationAndExplicitFacing()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.activate(1,"video");flow.awaitReady();flow.session.receive(new JSONObject().put("type","switch").put("operation",1).put("camera","back").toString());
        until(()->flow.wire.count("status")==2);assertEquals("back",flow.peer.camera);
        List<JSONObject> status=new ArrayList<>();for(JSONObject message:flow.wire.messages)if("status".equals(message.optString("type")))status.add(message);
        assertEquals("front",status.get(0).getString("camera"));assertEquals("back",status.get(1).getString("camera"));
        assertEquals(1,status.get(1).getLong("operation"));assertEquals(2,status.get(1).getInt("cameras"));
        flow.session.receive(new JSONObject().put("type","switch").put("operation",2).put("camera","front").toString());assertEquals(1,flow.peer.switches.get());}}
    @Test public void singleBackCameraStatusUsesObservedFacingAtReadyAndAfterSwitch()throws Exception{try(Flow flow=new Flow()){
        flow.peer.actualCamera="back";flow.peer.cameras=1;flow.ready();flow.activate(1,"video");flow.awaitReady();
        until(()->flow.wire.count("status")==1);
        flow.session.receive(new JSONObject().put("type","switch").put("operation",1).put("camera","front").toString());
        until(()->flow.wire.count("status")==2);
        for(JSONObject status:flow.wire.messages)if("status".equals(status.optString("type"))){
            assertEquals("back",status.getString("camera"));assertEquals(1,status.getInt("cameras"));assertEquals(1,status.getLong("operation"));assertEquals(4,status.length());
        }
        JSONObject media=flow.session.snapshot().getJSONObject("media");assertEquals("back",media.getString("camera"));assertEquals(1,media.getInt("cameras"));
    }}
    @Test public void invalidCameraObservationIsNeitherForwardedNorReplacedByRequestedFacing()throws Exception{try(Flow flow=new Flow()){
        flow.peer.actualCamera="PRIVATE_CAMERA_ID";flow.peer.cameras="PRIVATE_PATH";
        flow.ready();flow.activate(1,"video");flow.awaitReady();assertEquals(0,flow.wire.count("status"));
        JSONObject media=flow.session.snapshot().getJSONObject("media");assertFalse(media.has("camera"));assertFalse(media.has("cameras"));
    }}
    @Test public void stopDuringLatePeerFactoryClosesReturnedObjectWithoutOpeningIt()throws Exception{try(Flow flow=new Flow()){
        flow.peerFactoryBlocked=true;flow.begin();flow.hello();await(flow.factoryEntered);flow.session.stop();flow.factoryGate.countDown();
        assertTrue(flow.session.awaitClosed(3000));assertEquals(0,flow.peer.opens.get());assertEquals(1,flow.peer.closes.get());}}
    @Test public void stopDuringLateExtraFactoryClosesReturnedObjectWithoutStartingIt()throws Exception{try(Flow flow=new Flow()){
        flow.extraFactoryBlocked=true;flow.ready();flow.activate(1,"photo");await(flow.factoryEntered);flow.session.stop();flow.factoryGate.countDown();
        assertTrue(flow.session.awaitClosed(3000));assertEquals(0,flow.extras.get(0).starts);assertEquals(1,flow.extras.get(0).closes);}}
    @Test public void wireFailureKeepsCleanupUnconfirmedAndLeaseHeld()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.wire.failClose=true;flow.session.stop();assertTrue(flow.session.awaitClosed(3000));assertEquals("release_unconfirmed",flow.state());
        assertFalse(flow.session.snapshot().getBoolean("cleanup_complete"));
        try{MediaFiles.lease(new File(flow.files,"media"));fail();}catch(IOException expected){assertEquals("MEDIA_BUSY",expected.getMessage());}
        flow.wire.failClose=false;flow.session.stop();assertEquals("release_unconfirmed",flow.state());}}
    @Test public void stopDuringConnectAbortsAndReleasesLease()throws Exception{try(Flow flow=new Flow()){
        flow.wire.connectBlocked=true;flow.begin();flow.session.stop();assertTrue(flow.session.awaitClosed(3000));assertEquals("closed",flow.state());
        try(MediaFiles.Lease acquired=MediaFiles.lease(new File(flow.files,"media"))){assertNotNull(acquired);}}}
    @Test public void previewIsBoundAndLimitedAndNeverStoredInSnapshot()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.activate(1,"photo");flow.awaitReady();Extra extra=flow.extras.get(0);
        extra.events.message(new JSONObject().put("type","photo_preview").put("jpeg","/9j/2Q==").put("captured_at",1000));
        until(()->flow.wire.count("photo_preview")==1);assertFalse(flow.session.snapshot().toString().contains("jpeg"));
        extra.events.message(new JSONObject().put("type","photo_preview").put("jpeg",new String(new char[88001]).replace('\0','A')).put("captured_at",1000));
        assertTrue(flow.session.awaitClosed(3000));assertEquals(1,flow.wire.count("photo_preview"));}}
    @Test public void snapshotDoesNotInvokeNativeOrExposeOfferIdentity()throws Exception{try(Flow flow=new Flow()){
        flow.ready();JSONObject value=flow.session.snapshot();assertEquals("prepare",value.getString("mode"));
        assertFalse(value.toString().contains("secret"));assertFalse(value.toString().contains("PRIVATE"));assertFalse(value.toString().contains("wss:"));}}
    @Test public void controlledCauseSurvivesGenericNegotiationStage()throws Exception{try(Flow flow=new Flow()){
        flow.peer.publishFailure=new ExecutionException(new IOException("MEDIA_PERSISTENT_RECORD_INIT_FAILED"));flow.begin();flow.hello();
        assertTrue(flow.session.awaitClosed(3000));JSONObject state=flow.session.snapshot();
        assertEquals("MEDIA_PERSISTENT_RECORD_INIT_FAILED",state.getString("reason"));
        assertEquals("MEDIA_PREPARED_NEGOTIATION_FAILED",state.getJSONObject("diagnostics").getString("failed_stage"));}}
    @Test public void visualFailureCodeIsPreservedWithoutExceptionProse()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.activate(1,"photo");flow.awaitReady();flow.extras.get(0).events.failed("VISUAL_UPLOAD_NOT_ACKNOWLEDGED");
        assertTrue(flow.session.awaitClosed(3000));assertEquals("VISUAL_UPLOAD_NOT_ACKNOWLEDGED",flow.session.snapshot().getString("reason"));}}
    @Test public void rpcDiagnosticKeepsOnlyFiniteStatusAndTwoBoundedCodes()throws Exception{try(Flow flow=new Flow()){
        flow.wire.holdRpc="new";flow.begin();flow.hello();until(()->flow.wire.count("rpc")==1);
        flow.session.receive(new JSONObject().put("type","rpc").put("id",1).put("error","PRIVATE_TOKEN_PATH")
            .put("diagnostic",new JSONObject().put("status",503).put("code","remote_error")
                .put("tracks",new JSONArray().put("track_not_found").put("timeout").put("PRIVATE_TOKEN_PATH"))
                .put("sdp","PRIVATE_TOKEN_PATH").put("sessionId","PRIVATE_TOKEN_PATH")).toString());
        assertTrue(flow.session.awaitClosed(3000));JSONObject state=flow.session.snapshot();
        assertEquals("MEDIA_PREPARED_RPC_REJECTED",state.getString("reason"));
        JSONObject rpc=state.getJSONObject("diagnostics").getJSONObject("rpc");assertEquals(503,rpc.getInt("status"));
        assertEquals("new",rpc.getString("action"));assertEquals("remote_error",rpc.getString("code"));assertEquals(2,rpc.getJSONArray("tracks").length());
        assertFalse(state.toString().contains("PRIVATE"));}}
    @Test public void rpcDiagnosticRejectsCoercionAndRawText()throws Exception{try(Flow flow=new Flow()){
        flow.wire.holdRpc="new";flow.begin();flow.hello();until(()->flow.wire.count("rpc")==1);
        flow.session.receive(new JSONObject().put("type","rpc").put("id",1).put("error","PRIVATE_TOKEN_PATH")
            .put("diagnostic",new JSONObject().put("status","503").put("code","raw private text")
                .put("tracks",new JSONArray().put(new JSONObject().put("error","PRIVATE_TOKEN_PATH")))).toString());
        assertTrue(flow.session.awaitClosed(3000));JSONObject rpc=flow.session.snapshot().getJSONObject("diagnostics").getJSONObject("rpc");
        assertFalse(rpc.has("status"));assertFalse(rpc.has("code"));assertEquals(0,rpc.getJSONArray("tracks").length());}}
    @Test public void deactivateTimeoutCannotEmitIdleAfterLateCleanup()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.activate(1,"photo");flow.awaitReady();Extra extra=flow.extras.get(0);extra.closeBlocked=true;
        flow.deactivate(1);await(extra.closeEntered);flow.clock.now+=10000;flow.session.tick();extra.closeGate.countDown();
        assertTrue(flow.session.awaitClosed(3000));assertEquals(0,flow.wire.count("idle"));
        assertEquals("MEDIA_PREPARED_IDLE_TIMEOUT",flow.session.snapshot().getString("reason"));}}
    @Test public void expiredTotalCloseBudgetCannotBeUpgradedByLateWorker()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.activate(1,"photo");flow.awaitReady();Extra extra=flow.extras.get(0);extra.closeBlocked=true;
        flow.session.stop();await(extra.closeEntered);Field deadline=AppPreparedSession.class.getDeclaredField("closeAtNanos");deadline.setAccessible(true);
        deadline.setLong(flow.session,System.nanoTime()-1);extra.closeGate.countDown();assertTrue(flow.session.awaitClosed(3000));
        assertEquals("release_unconfirmed",flow.state());flow.session.stop();assertFalse(flow.session.snapshot().getBoolean("cleanup_complete"));}}
    @Test public void snapshotProjectsWorkerCachedHardwareAndPcmWithoutNativeCalls()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.activate(1,"video");flow.awaitReady();int sampled=flow.peer.snapshots.get();
        JSONObject state=flow.session.snapshot();assertTrue(state.getBoolean("capture_started"));assertTrue(state.getBoolean("playback_started"));
        assertTrue(state.getBoolean("video_started"));assertEquals(320,state.getJSONObject("media").getJSONObject("capture_pcm").getInt("samples"));
        assertEquals(0.125,state.getJSONObject("media").getJSONObject("capture_pcm").getDouble("rms"),0);
        assertFalse(state.toString().contains("PRIVATE"));assertFalse(state.getJSONObject("media").has("input_identity"));
        for(int i=0;i<20;i++)flow.session.snapshot();assertEquals(sampled,flow.peer.snapshots.get());
    }}
    @Test public void deactivateClearsStartedFlagsBeforeHardwareStopReturns()throws Exception{try(Flow flow=new Flow()){
        flow.ready();flow.activate(1,"photo");flow.awaitReady();Extra extra=flow.extras.get(0);extra.closeBlocked=true;
        flow.deactivate(1);await(extra.closeEntered);JSONObject state=flow.session.snapshot();
        assertFalse(state.getBoolean("capture_started"));assertFalse(state.getBoolean("playback_started"));assertFalse(state.getBoolean("video_started"));
        assertFalse(state.getJSONObject("media").getBoolean("capture_started"));assertFalse(state.getBoolean("cleanup_complete"));
        extra.closeGate.countDown();until(()->"idle".equals(flow.state()));
    }}
}
