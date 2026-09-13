package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CallDuplexObserverTest {
    static class Time implements MediaCapture.Clock {
        final long origin=System.nanoTime();
        public long elapsed(){return 1000+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-origin);}
        public long wall(){return elapsed();}
    }
    static AudioCaptureObservation.Sample idle(Time time)throws Exception {
        long now=time.elapsed();
        AudioInputOwnership.Dump f=new AudioInputOwnership.Dump(CallDuplexFixtures.flinger(false,false),true,now,now);
        AudioInputOwnership.Dump p=new AudioInputOwnership.Dump(CallDuplexFixtures.policy(false,false),true,now,now);
        return new AudioCaptureObservation.Sample(f,p,CallDuplexFixtures.external(false,false,0),now,now);
    }
    @Test public void readerIsLazyAndPcmQueriesNeverReadSource()throws Exception {
        Time time=new Time();CachedCallDuplexGuard guard=new CachedCallDuplexGuard(CallDuplexFixtures.PID,time);AtomicInteger reads=new AtomicInteger();
        CallDuplexObserver observer=new CallDuplexObserver(cancel->{reads.incrementAndGet();return idle(time);},()->true,guard,time,null);
        assertEquals(0,reads.get());try{observer.start();observer.awaitFirst();guard.requireIdle();int count=reads.get();
            for(int i=0;i<100000;i++)assertNull(guard.current(null,null));assertEquals(count,reads.get());
        }finally{observer.close();}
    }
    @Test public void deadlineRevokesHungReadAndNoReplacementReaderIsCreated()throws Exception {
        Time time=new Time();CachedCallDuplexGuard guard=new CachedCallDuplexGuard(CallDuplexFixtures.PID,time);
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger reads=new AtomicInteger();
        CallDuplexObserver observer=new CallDuplexObserver(cancel->{
            if(reads.incrementAndGet()>1){blocked.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}}
            return idle(time);
        },()->true,guard,time,null);
        try{observer.start();observer.awaitFirst();guard.requireIdle();assertTrue(blocked.await(2,TimeUnit.SECONDS));
            CachedCallDuplexGuard.Generation token=guard.generation();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(guard.generation()==token&&System.nanoTime()<until)Thread.sleep(5);
            assertNotSame(token,guard.generation());assertEquals(2,reads.get());
            try{guard.requireIdle();fail();}catch(IOException expected){}
            try{observer.close(10);fail();}catch(IOException expected){assertEquals("MEDIA_CALL_OBSERVER_RELEASE_UNCONFIRMED",expected.getMessage());}
        }finally{release.countDown();observer.close();}
    }
    @Test public void routeChangeWithinSampleAndReadFailureNeverReuseIdle()throws Exception {
        Time time=new Time();CachedCallDuplexGuard guard=new CachedCallDuplexGuard(CallDuplexFixtures.PID,time);
        AtomicInteger routes=new AtomicInteger();AtomicBoolean failRead=new AtomicBoolean();
        CallDuplexObserver observer=new CallDuplexObserver(cancel->{if(failRead.get())throw new IOException("private-data");return idle(time);},
                ()->routes.incrementAndGet()%2==0,guard,time,null);
        try{observer.start();observer.awaitFirst();try{guard.requireIdle();fail();}catch(IOException expected){}
            failRead.set(true);try{observer.refresh();fail();}catch(IOException expected){assertFalse(expected.toString().contains("private-data"));}
        }finally{observer.close();}
    }
    @Test public void cleanupObtainsNewIdleAfterBusySampleWithinOneBudget()throws Exception {
        Time time=new Time();CachedCallDuplexGuard guard=new CachedCallDuplexGuard(CallDuplexFixtures.PID,time);AtomicInteger reads=new AtomicInteger();
        CallDuplexObserver observer=new CallDuplexObserver(cancel->{AudioCaptureObservation.Sample s=idle(time);
            if(reads.incrementAndGet()==1)return new AudioCaptureObservation.Sample(s.flinger,s.policy,CallDuplexFixtures.external(false,true,0),s.began,s.finished);
            return s;
        },()->true,guard,time,null);
        try{observer.start();observer.awaitFirst();observer.awaitIdle(4000);guard.requireIdle();assertTrue(reads.get()>=2);}
        finally{observer.close();}
    }
    @Test public void cleanupDeadlineDoesNotUseOldIdleOrRunLongerThanBudget()throws Exception {
        Time time=new Time();CachedCallDuplexGuard guard=new CachedCallDuplexGuard(CallDuplexFixtures.PID,time);AtomicInteger reads=new AtomicInteger();
        CallDuplexObserver observer=new CallDuplexObserver(cancel->{if(reads.incrementAndGet()>1)Thread.sleep(1000);return idle(time);},()->true,guard,time,null);
        try{observer.start();observer.awaitFirst();long before=System.nanoTime();
            try{observer.awaitIdle(120);fail();}catch(IOException expected){}
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<600);
            try{guard.requireIdle();fail();}catch(IOException expected){}
        }finally{observer.close();}
    }
    @Test public void slowPeriodicReadsDoNotAccumulateAheadOfExplicitRefresh()throws Exception {
        Time time=new Time();CachedCallDuplexGuard guard=new CachedCallDuplexGuard(CallDuplexFixtures.PID,time);
        AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();CountDownLatch third=new CountDownLatch(1);AtomicInteger reads=new AtomicInteger();
        CallDuplexObserver observer=new CallDuplexObserver(cancel->{int n=active.incrementAndGet();maximum.accumulateAndGet(n,Math::max);
            try{if(reads.incrementAndGet()==3)third.countDown();Thread.sleep(1100);return idle(time);}finally{active.decrementAndGet();}
        },()->true,guard,time,null);
        try{observer.start();observer.awaitFirst();assertTrue(third.await(4,TimeUnit.SECONDS));
            long began=System.nanoTime();observer.refresh();assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)<3000);
            guard.requireIdle();assertEquals(1,maximum.get());
        }finally{observer.close();}
    }
}
