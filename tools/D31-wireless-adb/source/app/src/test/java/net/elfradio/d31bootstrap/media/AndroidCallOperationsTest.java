package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AndroidCallOperationsTest {
    static class Rig {
        final List<String> events=Collections.synchronizedList(new ArrayList<>());
        final CallDuplexFixtures.Time time=new CallDuplexFixtures.Time();
        final CachedCallDuplexGuard guard=new CachedCallDuplexGuard(CallDuplexFixtures.PID,time);
        final AtomicInteger aborts=new AtomicInteger(),transportCloses=new AtomicInteger();
        final CallDuplexGuard.Identity input,output;
        final AndroidCallOperations operations;
        volatile boolean focus,failPeer,failIdle,failObserver,failTransport;
        volatile boolean journal;volatile int activePeerCloses,maxPeerCloses;volatile long idleBudget;
        volatile CountDownLatch peerWait;
        Rig()throws Exception {
            input=CallDuplexFixtures.input();output=CallDuplexFixtures.output();
            AndroidCallOperations.Peer peer=new AndroidCallOperations.Peer(){
                public void open(CallProtocol.Route route){events.add("peer-open");}
                public JSONObject createPublish(JSONObject body){return body;}public void applyPublish(JSONObject body){}
                public CallProtocol.SubscriptionResult subscribe(JSONObject body){return null;}public void negotiationComplete(){}
                public void prepareMuted(){events.add("prepare");}
                public CallDuplexGuard.Identity input(){return input;}public CallDuplexGuard.Identity output(){return output;}
                public int inputProof(){return guard.current(input,output)==null?0:1;}public int outputProof(){return inputProof();}
                public boolean captureFrames(){return true;}public boolean playbackFrames(){return true;}public void unmute(){events.add("unmute");}
                public void cancel(){events.add("cancel");}
                public void close()throws Exception{activePeerCloses++;maxPeerCloses=Math.max(maxPeerCloses,activePeerCloses);
                    events.add("peer-close");if(peerWait!=null)peerWait.await();activePeerCloses--;if(failPeer)throw new IOException("private-peer-error");}
            };
            AndroidCallOperations.Observation observer=new AndroidCallOperations.Observation(){
                public void start(){events.add("observer-start");}public void awaitFirst()throws Exception{refresh();}
                public void refresh()throws Exception{events.add("sample");guard.sample(CallDuplexFixtures.sample(false,false,focus?2:0,1000),true,1000,1040,guard.generation());}
                public void awaitIdle(long budget)throws Exception{events.add("fresh-idle");idleBudget=budget;if(failIdle)throw new IOException("private-idle-error");refresh();guard.requireIdle();}
                public void restored(){events.add("restored");}
                public void close(long budget)throws Exception{events.add("observer-close");guard.close();if(failObserver)throw new IOException("private-observer-error");}
            };
            AndroidCallOperations.Routing route=new AndroidCallOperations.Routing(){
                public void recover()throws Exception{events.add("recover");guard.requireIdle();journal=false;}
                public void open(){events.add("route-open");journal=true;focus=true;guard.focusOwned(true);}
                public void releaseFocus(){events.add("focus-release");focus=false;guard.focusOwned(false);}
            };
            AppCallSession.Sender sender=new AppCallSession.Sender(){
                public void send(JSONObject body){events.add("send");}public void abort(){aborts.incrementAndGet();events.add("abort");}
                public void close()throws Exception{transportCloses.incrementAndGet();events.add("transport-close");if(failTransport)throw new IOException("private-transport-error");}
            };
            operations=new AndroidCallOperations(peer,guard,observer,route,time,sender);
        }
        void open()throws Exception{operations.open(CallProtocol.Route.SPEAKER);}
        void expectCloseFailure()throws Exception{try{operations.close();fail();}catch(IOException error){assertEquals("MEDIA_CALL_RELEASE_UNCONFIRMED",error.getMessage());assertNull(error.getCause());}}
    }
    @Test public void openAndPrepareTakeFreshIdleBeforeHardwareAndBindActualObjects()throws Exception{
        Rig r=new Rig();try{assertTrue(r.events.isEmpty());r.open();r.operations.prepareMuted();assertEquals(0,r.operations.inputProof());
            assertSame(r.input,r.guard.generation().input);assertSame(r.output,r.guard.generation().output);
            assertTrue(r.events.indexOf("route-open")<r.events.indexOf("peer-open"));
            assertEquals("sample",r.events.get(r.events.indexOf("prepare")-1));
        }finally{r.operations.close();}
    }
    @Test public void releaseWaitsForBothAudioEndsThenFreshIdleThenJournalRecovery()throws Exception{
        Rig r=new Rig();r.open();r.events.clear();r.operations.close();
        assertTrue(r.events.indexOf("peer-close")<r.events.indexOf("focus-release"));
        assertTrue(r.events.indexOf("focus-release")<r.events.indexOf("fresh-idle"));
        assertTrue(r.events.indexOf("fresh-idle")<r.events.indexOf("recover"));
        assertTrue(r.events.indexOf("recover")<r.events.indexOf("observer-close"));
        assertTrue(r.idleBudget>0&&r.idleBudget<=4000);assertFalse(r.journal);
    }
    @Test public void peerReleaseFailurePreservesJournalAndSkipsRouteChanges()throws Exception{
        Rig r=new Rig();r.open();r.events.clear();r.failPeer=true;r.expectCloseFailure();assertTrue(r.journal);
        assertFalse(r.events.contains("focus-release"));assertFalse(r.events.contains("fresh-idle"));assertFalse(r.events.contains("recover"));
        assertTrue(r.events.contains("observer-close"));assertEquals(1,r.transportCloses.get());
    }
    @Test public void idleFailurePreservesJournalAndNeverRestoresRoute()throws Exception{
        Rig r=new Rig();r.open();r.events.clear();r.failIdle=true;r.expectCloseFailure();assertTrue(r.journal);assertFalse(r.events.contains("recover"));
    }
    @Test public void allClosePathsAreIdempotentIncludingConcurrentCallers()throws Exception{
        Rig r=new Rig();r.open();ExecutorService callers=Executors.newFixedThreadPool(2);
        try{Future<?> a=callers.submit(()->{try{r.operations.close();}catch(Exception e){throw new RuntimeException(e);}});
            Future<?> b=callers.submit(()->{try{r.operations.close();}catch(Exception e){throw new RuntimeException(e);}});
            a.get(2,TimeUnit.SECONDS);b.get(2,TimeUnit.SECONDS);r.operations.close();
            assertEquals(1,Collections.frequency(r.events,"peer-close"));assertEquals(1,r.aborts.get());assertEquals(1,r.transportCloses.get());
        }finally{callers.shutdownNow();}
    }
    @Test public void cancelledBeforeOpenCannotAcquireAudioOrRoute()throws Exception{
        Rig r=new Rig();r.operations.cancel();try{r.open();fail();}catch(IOException expected){assertEquals("MEDIA_CANCELLED",expected.getMessage());}
        r.operations.close();assertFalse(r.events.contains("route-open"));assertFalse(r.events.contains("peer-open"));
    }
    @Test public void cleanupErrorsAreStickyAndPrivateValuesNeverEscape()throws Exception{
        Rig r=new Rig();r.open();r.failTransport=true;r.expectCloseFailure();r.failTransport=false;r.expectCloseFailure();
    }
    @Test public void oneSharedDeadlinePreventsLateRestoreAfterPeerTimeout()throws Exception {
        Rig r=new Rig();r.operations.cleanupBudget(100);r.open();r.events.clear();r.peerWait=new CountDownLatch(1);
        long began=System.nanoTime();r.expectCloseFailure();assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)<700);
        assertTrue(r.journal);r.peerWait.countDown();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(!r.events.contains("observer-close")&&System.nanoTime()<until)Thread.sleep(5);
        assertFalse(r.events.contains("recover"));assertFalse(r.events.contains("focus-release"));assertTrue(r.journal);
        assertFalse(r.operations.snapshot().getBoolean("cleanup_complete"));r.expectCloseFailure();
    }
}
