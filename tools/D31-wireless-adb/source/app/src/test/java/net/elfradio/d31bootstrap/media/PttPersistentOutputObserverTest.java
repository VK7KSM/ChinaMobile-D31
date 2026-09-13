package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class PttPersistentOutputObserverTest {
    final CallDuplexFixtures.Time clock=new CallDuplexFixtures.Time();
    final PttOutputGuard guard=new PttOutputGuard(D31OutputFixtures.PID,clock);
    void mute()throws Exception{guard.actualOutput(D31OutputFixtures.identity());guard.focusOwned(true);guard.confirmMuted(guard.outputMuted(true));}
    @Test public void operationWaitUsesOwnedMuteButFullReleaseStillRequiresEmpty()throws Exception {
        mute();AtomicInteger reads=new AtomicInteger();
        try(PttOutputObserver observer=new PttOutputObserver(cancel->{reads.incrementAndGet();return CallDuplexFixtures.sample(false,true,2,1000);},guard)){
            assertEquals(0,reads.get());observer.awaitOperationIdle(600);guard.requireOperationIdle();
            int count=reads.get();for(int i=0;i<1000;i++)guard.requireOperationIdle();assertEquals(count,reads.get());
            assertEquals("MEDIA_OUTPUT_OPERATION_IDLE_OBSERVED",observer.storage().getString("idle_wait_reason"));
            PttPersistentOutputGuardTest.denied(()->observer.awaitIdle(120));
        }
        PttPersistentOutputGuardTest.denied(guard::requireOperationIdle);
    }
    @Test public void busyCacheIsReReadOnSameWorkerUntilNoExternalOwner()throws Exception {
        mute();AtomicInteger reads=new AtomicInteger();java.util.Set<Long> threads=ConcurrentHashMap.newKeySet();
        try(PttOutputObserver observer=new PttOutputObserver(cancel->{threads.add(Thread.currentThread().getId());
            return CallDuplexFixtures.sample(false,true,reads.incrementAndGet()<3?1:2,1000);},guard)){
            observer.awaitOperationIdle(1200);assertEquals(3,reads.get());assertEquals(1,threads.size());
            assertEquals(3,observer.storage().getInt("idle_wait_attempts"));
        }
    }
    @Test public void timedOutUninterruptibleReadCannotRestoreAuthorization()throws Exception {
        mute();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);
        PttOutputObserver observer=new PttOutputObserver(cancel->{entered.countDown();
            for(;;)try{release.await();break;}catch(InterruptedException ignored){}
            returned.countDown();return CallDuplexFixtures.sample(false,true,2,1000);},guard);
        try{
            long started=System.nanoTime();PttPersistentOutputGuardTest.denied(()->observer.awaitOperationIdle(120));
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1000);
            release.countDown();assertTrue(returned.await(1,TimeUnit.SECONDS));
            java.lang.reflect.Field field=PttOutputObserver.class.getDeclaredField("worker");field.setAccessible(true);
            ((ScheduledExecutorService)field.get(observer)).submit(()->{}).get(1,TimeUnit.SECONDS);
            PttPersistentOutputGuardTest.denied(guard::requireOperationIdle);
        }finally{release.countDown();observer.close();}
    }
    @Test public void closeDoesNotHideAReaderThatHasNotExited()throws Exception {
        mute();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        PttOutputObserver observer=new PttOutputObserver(cancel->{entered.countDown();
            for(;;)try{release.await();break;}catch(InterruptedException ignored){}
            return CallDuplexFixtures.sample(false,true,2,1000);},guard);
        observer.start();assertTrue(entered.await(1,TimeUnit.SECONDS));
        try{PttPersistentOutputGuardTest.denied(()->observer.close(60));PttPersistentOutputGuardTest.denied(guard::requireOperationIdle);}
        finally{release.countDown();observer.close();}
    }
    @Test public void waitBoundsAreValidatedWithoutStartingReader()throws Exception {
        try(PttOutputObserver observer=new PttOutputObserver(cancel->{throw new AssertionError("不得读取");},guard)){
            for(long timeout:new long[]{0,-1,4001})try{observer.awaitOperationIdle(timeout);fail();}catch(IllegalArgumentException expected){}
        }
    }
}
