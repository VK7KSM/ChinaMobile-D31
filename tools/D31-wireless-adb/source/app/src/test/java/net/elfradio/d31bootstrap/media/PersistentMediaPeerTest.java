package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PersistentMediaPeerTest {
    static final class Backend implements AndroidPersistentMediaPeer.Backend {
        AndroidPersistentMediaPeer owner;int opens,closes,stops,activates;boolean idle=true,ready=true,stopFailure,closeFailure;long op;
        CountDownLatch stopEntered,stopUnblock,activateEntered,activateUnblock;String mode,failureCode;int laterHardwareSteps;
        public void open(AndroidPersistentMediaPeer owner,Cancellation cancel){this.owner=owner;opens++;}
        public JSONObject publish(JSONObject value)throws Exception{return new JSONObject().put("tracks","audio/video");}
        public void applyPublish(JSONObject value){}
        public JSONObject subscribe(JSONObject value)throws Exception{return value.optBoolean("offer")?new JSONObject().put("sessionDescription","answer"):null;}
        public void validate(){}
        public boolean idle(){return idle;}
        public void activate(long op,String mode,String facing)throws Exception{this.op=op;this.mode=mode;activates++;idle="photo".equals(mode)||"alarm".equals(mode);
            if(activateEntered!=null){activateEntered.countDown();activateUnblock.await();owner.checkOperation(op);laterHardwareSteps++;}}
        public boolean ready(long op,String mode){return ready&&op==this.op;}
        public void softStop(){}
        public void stop(long deadline)throws Exception{stops++;if(stopEntered!=null){stopEntered.countDown();stopUnblock.await();}if(stopFailure)throw new IOException(failureCode==null?"硬件未停":failureCode);idle=true;}
        public void switchCamera(long op,String facing){}
        public JSONObject snapshot(){return new JSONObject();}
        public void close()throws Exception{closes++;if(closeFailure)throw new IOException("未释放");idle=true;}
    }
    static final class Fixture implements AutoCloseable {
        final Backend backend=new Backend();final List<String> routes=Collections.synchronizedList(new ArrayList<>());
        final List<String> checks=new ArrayList<>();
        boolean routeFailure;final AndroidPersistentMediaPeer peer=new AndroidPersistentMediaPeer(backend,new AndroidPersistentMediaPeer.Route(){
            public void beforeActivate(long op,String mode){checks.add(mode);assertTrue(backend.idle);}
            public void open(long op)throws Exception{routes.add("open"+op);if(routeFailure)throw new IOException("焦点拒绝");}
            public void close(long op){assertTrue(backend.idle);routes.add("close"+op);}
        },new AndroidPersistentMediaPeer.Events(){public void changed(){}public void failed(String code){}});
        void prepare()throws Exception{peer.open();peer.createPublish(new JSONObject());peer.applyPublish(new JSONObject());peer.subscribe(new JSONObject());peer.negotiationComplete();backend.owner.ice(true);}
        public void close(){try{peer.close();}catch(Exception ignored){}}
    }
    @Test public void preparingOpensConnectionRouteOnce()throws Exception{try(Fixture f=new Fixture()){f.prepare();assertTrue(f.peer.transportReady());assertEquals(Collections.singletonList("open0"),f.routes);assertEquals(1,f.backend.opens);}}
    @Test public void cancelledBeforeActivateConsumesOnlyNextGeneration()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();f.peer.deactivate(1);assertEquals(0,f.backend.activates);assertEquals(0,f.backend.stops);assertEquals(Collections.singletonList("open0"),f.routes);
        assertEquals(1,f.peer.snapshot().getLong("operation"));f.peer.activate(2,"ptt","front");assertTrue(f.peer.operationReady(2));f.peer.deactivate(2);
        assertEquals(Collections.singletonList("open0"),f.routes);}}
    @Test public void skippedCancellationDoesNotConsumeGeneration()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();try{f.peer.deactivate(2);fail();}catch(IOException expected){}assertEquals(0,f.peer.snapshot().getLong("operation"));assertTrue(f.peer.transportReady());}}
    @Test public void missingAnswerAcknowledgementCannotBecomeTransportReady()throws Exception{try(Fixture f=new Fixture()){
        f.peer.open();f.peer.createPublish(new JSONObject());f.peer.applyPublish(new JSONObject());assertNotNull(f.peer.subscribe(new JSONObject().put("offer",true)));
        f.peer.ice(true);assertFalse(f.peer.transportReady());f.peer.negotiationComplete();assertTrue(f.peer.transportReady());}}
    @Test public void repeatedPttRetainsBackendAndTransport()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();for(long op=1;op<=2;op++){f.peer.activate(op,"ptt","front");assertTrue(f.peer.operationReady(op));f.peer.deactivate(op);assertTrue(f.peer.transportReady());assertFalse(f.peer.operationReady(op));}
        assertEquals(1,f.backend.opens);assertEquals(0,f.backend.closes);assertEquals(Collections.singletonList("open0"),f.routes);}}
    @Test public void sixModesShareConnectionRoute()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();long op=0;for(String mode:Arrays.asList("ptt","call","microphone","video","photo","alarm")){f.peer.activate(++op,mode,"front");assertTrue(f.peer.operationReady(op));f.peer.deactivate(op);}
        assertEquals(Collections.singletonList("open0"),f.routes);assertEquals(Arrays.asList("ptt","call","microphone","video","photo","alarm"),f.checks);assertEquals(0,f.backend.closes);}}
    @Test public void wrongOperationCannotStopOrSwitchCurrentHardware()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();f.peer.activate(1,"video","front");f.peer.deactivate(0);assertEquals(0,f.backend.stops);assertTrue(f.peer.operationReady(1));
        try{f.peer.switchCamera(0,"back");fail();}catch(IOException expected){}assertTrue(f.peer.operationReady(1));assertEquals(0,f.backend.closes);}}
    @Test public void duplicateOrSkippedActivationDoesNotTouchBackend()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();for(long op:new long[]{0,2,-1}){try{f.peer.activate(op,"call","front");fail();}catch(IOException expected){}}
        assertEquals(0,f.backend.activates);assertEquals(Collections.singletonList("open0"),f.routes);assertTrue(f.peer.transportReady());}}
    @Test public void invalidModeOrFacingCannotReopenRoute()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();try{f.peer.activate(1,"wrong","front");fail();}catch(IOException expected){}
        try{f.peer.activate(1,"ptt","wrong");fail();}catch(IOException expected){}assertEquals(Collections.singletonList("open0"),f.routes);}}
    @Test public void actualHardwareReadyIsRequiredForActiveReady()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();f.backend.ready=false;f.peer.activate(1,"call","front");assertFalse(f.peer.operationReady(1));f.backend.ready=true;assertTrue(f.peer.operationReady(1));}}
    @Test public void iceLossRevokesBothReadinessValues()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();f.peer.activate(1,"ptt","front");f.peer.ice(false);assertFalse(f.peer.operationReady(1));assertFalse(f.peer.transportReady());}}
    @Test public void deniedRouteNeverStartsHardware()throws Exception{try(Fixture f=new Fixture()){
        f.routeFailure=true;try{f.prepare();fail();}catch(IOException expected){}f.peer.close();
        assertEquals(0,f.backend.activates);assertEquals(Arrays.asList("open0","close0"),f.routes);}}
    @Test public void stopFailureCannotPublishIdleAndDisconnects()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();f.peer.activate(1,"call","front");f.backend.stopFailure=true;
        try{f.peer.deactivate(1);fail();}catch(IOException expected){}assertFalse(f.peer.transportReady());f.peer.close();assertEquals(1,f.backend.closes);}}
    @Test public void controlledBackendFailureIsPreservedBeforeOuterFailedEvent()throws Exception{
        for(String code:Arrays.asList("MEDIA_PERSISTENT_HARDWARE_STOP_TIMEOUT","VISUAL_CAMERA_BUSY"))try(Fixture f=new Fixture()){
            f.prepare();f.peer.activate(1,"call","front");f.backend.stopFailure=true;f.backend.failureCode=code;
            try{f.peer.deactivate(1);fail();}catch(IOException expected){assertEquals(code,expected.getMessage());}
            assertEquals(code,f.peer.snapshot().getString("error"));
        }
    }
    @Test public void uncontrolledBackendTextUsesFallbackFailureCode()throws Exception{
        for(String message:Arrays.asList("秘密异常原文","MEDIA_BAD\nPRIVATE","VISUAL_lowercase"))try(Fixture f=new Fixture()){
            f.prepare();f.peer.activate(1,"call","front");f.backend.stopFailure=true;f.backend.failureCode=message;
            try{f.peer.deactivate(1);fail();}catch(IOException expected){}
            assertEquals("MEDIA_PERSISTENT_OPERATION_FAILED",f.peer.snapshot().getString("error"));
        }
    }
    @Test public void hardwareStopIsBoundedIndependentlyOfWorkerCompletion()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();f.peer.activate(1,"ptt","front");f.backend.stopEntered=new CountDownLatch(1);f.backend.stopUnblock=new CountDownLatch(1);
        ExecutorService caller=Executors.newSingleThreadExecutor();try{
            Future<?> done=caller.submit(()->{try{f.peer.deactivate(1);throw new AssertionError();}catch(Exception expected){}});
            assertTrue(f.backend.stopEntered.await(1,TimeUnit.SECONDS));done.get(2500,TimeUnit.MILLISECONDS);assertFalse(f.peer.transportReady());
            assertFalse(f.routes.contains("close0"));f.backend.stopUnblock.countDown();f.peer.close();assertEquals(1,f.backend.closes);
        }finally{f.backend.stopUnblock.countDown();caller.shutdownNow();}}}
    @Test public void failedBackendCloseStillReleasesFocusAndRestoresOnlyWhenIdle()throws Exception {
        for(boolean idle:new boolean[]{false,true}){
            Backend backend=new Backend();boolean[] focused={false},restored={false},disposed={false};int[] checked={0};
            AndroidPersistentMediaPeer peer=new AndroidPersistentMediaPeer(backend,new AndroidPersistentMediaPeer.Route(){
                public void open(long op){focused[0]=true;}
                public void close(long op)throws Exception {
                    focused[0]=false;checked[0]++;
                    if(!backend.idle)throw new IOException("MEDIA_ROUTE_RESTORE_UNCONFIRMED");
                    restored[0]=true;
                }
                public void dispose(long remainingMs){disposed[0]=true;}
            },new AndroidPersistentMediaPeer.Events(){public void changed(){}public void failed(String code){}});
            prepare(peer);peer.activate(1,"call","front");backend.closeFailure=true;backend.idle=idle;
            try{peer.close();fail();}catch(IOException expected){}
            assertFalse(focused[0]);assertEquals(1,checked[0]);assertEquals(idle,restored[0]);assertTrue(disposed[0]);
            assertTrue(peer.snapshot().getBoolean("release_unconfirmed"));assertFalse(peer.snapshot().getBoolean("peer_cleanup_complete"));
            try{peer.close();fail();}catch(IOException expected){}
            assertEquals(1,backend.closes);assertEquals(1,checked[0]);
        }
    }
    @Test public void normalFullCloseDisposesExactlyOnce()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();f.peer.activate(1,"call","front");f.peer.close();f.peer.close();assertEquals(1,f.backend.closes);assertTrue(f.peer.snapshot().getBoolean("peer_cleanup_complete"));assertEquals(Arrays.asList("open0","close0"),f.routes);}}
    private static void prepare(AndroidPersistentMediaPeer peer)throws Exception{peer.open();peer.createPublish(new JSONObject());peer.applyPublish(new JSONObject());peer.subscribe(new JSONObject());peer.negotiationComplete();peer.ice(true);}
    @Test public void successfulCloseRemainsIdempotentAfterOriginalDeadline()throws Exception{
        Backend backend=new Backend();AndroidPersistentMediaPeer peer=new AndroidPersistentMediaPeer(backend,new AndroidPersistentMediaPeer.Route(){public void open(long op){}public void close(long op){}},new AndroidPersistentMediaPeer.Events(){public void changed(){}public void failed(String code){}},100);
        prepare(peer);peer.close();Thread.sleep(150);peer.close();assertEquals(1,backend.closes);assertTrue(peer.snapshot().getBoolean("peer_cleanup_complete"));
    }
    @Test public void normalFreshIdleRouteWaitCanExceedOldThreeSecondBudget()throws Exception{
        Backend backend=new Backend();AndroidPersistentMediaPeer peer=new AndroidPersistentMediaPeer(backend,new AndroidPersistentMediaPeer.Route(){
            public void open(long op){}public void close(long op)throws Exception{assertTrue(backend.idle);Thread.sleep(3200);}
        },new AndroidPersistentMediaPeer.Events(){public void changed(){}public void failed(String code){}});
        try{prepare(peer);peer.activate(1,"ptt","front");peer.close();assertTrue(peer.snapshot().getBoolean("peer_cleanup_complete"));assertFalse(peer.snapshot().getBoolean("release_unconfirmed"));}
        finally{peer.close();}
    }
    @Test public void routeCompletionAfterTimeoutCannotUpgradeCleanup()throws Exception{
        Backend backend=new Backend();CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1);
        AndroidPersistentMediaPeer peer=new AndroidPersistentMediaPeer(backend,new AndroidPersistentMediaPeer.Route(){
            public void open(long op){}public void close(long op)throws Exception{entered.countDown();finish.await();}
        },new AndroidPersistentMediaPeer.Events(){public void changed(){}public void failed(String code){}},100);
        try{prepare(peer);peer.activate(1,"ptt","front");try{peer.close();fail();}catch(IOException expected){}
            assertEquals(0,entered.getCount());assertTrue(peer.snapshot().getBoolean("release_unconfirmed"));finish.countDown();
            java.lang.reflect.Field field=AndroidPersistentMediaPeer.class.getDeclaredField("worker");field.setAccessible(true);
            assertTrue(((ExecutorService)field.get(peer)).awaitTermination(1,TimeUnit.SECONDS));
            try{peer.close();fail();}catch(IOException expected){}assertFalse(peer.snapshot().getBoolean("peer_cleanup_complete"));
        }finally{finish.countDown();try{peer.close();}catch(Exception ignored){}}
    }
    @Test public void cancelIsNonblockingWhileHardwareStopIsBlocked()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();f.peer.activate(1,"call","front");f.backend.stopEntered=new CountDownLatch(1);f.backend.stopUnblock=new CountDownLatch(1);ExecutorService caller=Executors.newFixedThreadPool(2);
        try{Future<?> stop=caller.submit(()->{try{f.peer.deactivate(1);}catch(Exception ignored){}});assertTrue(f.backend.stopEntered.await(1,TimeUnit.SECONDS));
            caller.submit(f.peer::cancel).get(200,TimeUnit.MILLISECONDS);assertFalse(f.peer.operationReady(1));f.backend.stopUnblock.countDown();stop.get(1,TimeUnit.SECONDS);
        }finally{f.backend.stopUnblock.countDown();caller.shutdownNow();}}}
    @Test public void invalidationDuringAdmissionPreventsHardware()throws Exception{cancelBlockedRoute(false);}
    @Test public void cancelledRouteExceptionDoesNotFailWholeSession()throws Exception{cancelBlockedRoute(true);}
    private void cancelBlockedRoute(boolean throwAfterCancel)throws Exception{
        Backend backend=new Backend();CountDownLatch entered=new CountDownLatch(1),unblock=new CountDownLatch(1);
        List<String> routes=new ArrayList<>(),errors=new ArrayList<>();
        AndroidPersistentMediaPeer peer=new AndroidPersistentMediaPeer(backend,new AndroidPersistentMediaPeer.Route(){
            private void block(long op)throws Exception{if(op==1){entered.countDown();unblock.await();if(throwAfterCancel)throw new IOException("MEDIA_ROUTE_CANCELLED");}}
            public void beforeActivate(long op,String mode)throws Exception{block(op);}
            public void open(long op)throws Exception{routes.add("open"+op);}
            public void close(long op){assertTrue(backend.idle);routes.add("close"+op);}
        },new AndroidPersistentMediaPeer.Events(){public void changed(){}public void failed(String code){errors.add(code);}});
        ExecutorService callers=Executors.newFixedThreadPool(2);
        try{prepare(peer);Future<?> active=callers.submit(()->{peer.activate(1,"call","front");return null;});
            assertTrue(entered.await(1,TimeUnit.SECONDS));callers.submit(()->peer.invalidateOperation(1)).get(200,TimeUnit.MILLISECONDS);
            assertFalse(peer.operationReady(1));unblock.countDown();active.get(1,TimeUnit.SECONDS);
            assertEquals(0,backend.activates);assertEquals(0,backend.closes);assertTrue(errors.isEmpty());assertEquals("",peer.snapshot().getString("error"));
            peer.deactivate(1);assertEquals("idle",peer.snapshot().getString("phase"));assertTrue(peer.transportReady());
            assertEquals(Collections.singletonList("open0"),routes);
            peer.activate(2,"call","front");assertTrue(peer.operationReady(2));peer.deactivate(2);assertEquals(1,backend.opens);assertEquals(0,backend.closes);
        }finally{unblock.countDown();callers.shutdownNow();peer.close();}
    }
    @Test public void earlyInvalidationCannotBeResetByLateActivation()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();f.peer.invalidateOperation(1);f.peer.activate(1,"ptt","front");assertEquals(0,f.backend.activates);assertEquals(Collections.singletonList("open0"),f.routes);
        f.peer.deactivate(1);assertTrue(f.peer.transportReady());f.peer.activate(2,"ptt","front");assertTrue(f.peer.operationReady(2));}}
    @Test public void staleAndFutureInvalidationsCannotMuteCurrentOrNextOperation()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();f.peer.invalidateOperation(99);f.peer.activate(1,"call","front");f.peer.invalidateOperation(2);assertTrue(f.peer.operationReady(1));
        f.peer.deactivate(1);f.peer.activate(2,"call","front");f.peer.invalidateOperation(1);assertTrue(f.peer.operationReady(2));}}
    @Test public void invalidationAfterFirstHardwareStepSkipsRemainingStepsAndStopsNormally()throws Exception{try(Fixture f=new Fixture()){
        f.prepare();f.backend.activateEntered=new CountDownLatch(1);f.backend.activateUnblock=new CountDownLatch(1);
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try{Future<?> active=caller.submit(()->{f.peer.activate(1,"call","front");return null;});assertTrue(f.backend.activateEntered.await(1,TimeUnit.SECONDS));
            f.peer.invalidateOperation(1);f.backend.activateUnblock.countDown();active.get(1,TimeUnit.SECONDS);
            assertEquals(1,f.backend.activates);assertEquals(0,f.backend.laterHardwareSteps);assertEquals(0,f.backend.closes);
            f.peer.deactivate(1);assertEquals(1,f.backend.stops);assertTrue(f.peer.transportReady());assertEquals("",f.peer.snapshot().getString("error"));
            f.peer.activate(2,"call","front");assertTrue(f.peer.operationReady(2));assertEquals(1,f.backend.laterHardwareSteps);
        }finally{f.backend.activateUnblock.countDown();caller.shutdownNow();}}}
    @Test public void allModesConfirmMutedIdleAfterHardwareStop()throws Exception{
        Backend backend=new Backend();List<String> waited=new ArrayList<>();
        AndroidPersistentMediaPeer peer=new AndroidPersistentMediaPeer(backend,new AndroidPersistentMediaPeer.Route(){
            public void open(long op){}public void close(long op){}
            public void afterDeactivate(long op,String mode){assertTrue(backend.idle);waited.add(mode);}
        },new AndroidPersistentMediaPeer.Events(){public void changed(){}public void failed(String code){}});
        try{prepare(peer);long op=0;for(String mode:Arrays.asList("ptt","call","microphone","video","photo","alarm")){peer.activate(++op,mode,"front");peer.deactivate(op);}
            assertEquals(Arrays.asList("ptt","call","microphone","video","photo","alarm"),waited);assertTrue(peer.transportReady());
        }finally{peer.close();}
    }
}
