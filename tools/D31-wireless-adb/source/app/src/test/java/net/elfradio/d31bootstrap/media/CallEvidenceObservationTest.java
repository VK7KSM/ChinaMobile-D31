package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CallEvidenceObservationTest {
    @Test public void observerPassesExactReadObjectWithoutSecondRead()throws Exception {
        CallDuplexObserverTest.Time time=new CallDuplexObserverTest.Time();
        CachedCallDuplexGuard guard=new CachedCallDuplexGuard(CallDuplexFixtures.PID,time);
        AtomicInteger reads=new AtomicInteger();AtomicReference<AudioCaptureObservation.Sample> original=new AtomicReference<>(),captured=new AtomicReference<>();
        AtomicReference<JSONObject> result=new AtomicReference<>();
        CallDuplexObserver observer=new CallDuplexObserver(c->{reads.incrementAndGet();AudioCaptureObservation.Sample sample=CallDuplexObserverTest.idle(time);original.set(sample);return sample;},()->true,guard,time,null);
        observer.evidence((sample,token,decision,partial)->{captured.set(sample);result.set(decision);});
        try{observer.start();observer.awaitFirst();assertSame(original.get(),captured.get());assertEquals(1,reads.get());
            assertTrue(result.get().getBoolean("same_generation"));assertTrue(result.get().getBoolean("fresh"));
            for(int i=0;i<100000;i++)guard.current(null,null);assertEquals(1,reads.get());
        }finally{observer.close();}
    }
    @Test public void changedFocusDuringReadIsExplicitlyMarkedAsAnotherGeneration()throws Exception {
        CallDuplexObserverTest.Time time=new CallDuplexObserverTest.Time();
        CachedCallDuplexGuard guard=new CachedCallDuplexGuard(CallDuplexFixtures.PID,time);AtomicReference<JSONObject> result=new AtomicReference<>();
        CallDuplexObserver observer=new CallDuplexObserver(c->{guard.focusOwned(true);return CallDuplexObserverTest.idle(time);},()->true,guard,time,null);
        observer.evidence((sample,token,decision,partial)->result.set(decision));
        try{observer.start();observer.awaitFirst();assertFalse(result.get().getBoolean("same_generation"));
            assertFalse(result.get().getBoolean("focus_owned_at_start"));assertFalse(result.get().getBoolean("fresh"));
        }finally{observer.close();}
    }
    @Test public void readFailureHasFixedErrorAndSinkFailureCannotAlterGuardDecision()throws Exception {
        CallDuplexObserverTest.Time time=new CallDuplexObserverTest.Time();
        CachedCallDuplexGuard guard=new CachedCallDuplexGuard(CallDuplexFixtures.PID,time);AtomicReference<JSONObject> result=new AtomicReference<>();
        CallDuplexObserver observer=new CallDuplexObserver(c->{throw new IOException("private-file-and-token");},()->true,guard,time,null);
        observer.evidence((sample,token,decision,partial)->result.set(decision));
        try{observer.start();observer.awaitFirst();assertEquals("MEDIA_CALL_OBSERVATION_READ_FAILED",result.get().getString("read_error"));
            assertFalse(result.get().toString().contains("private"));
        }finally{observer.close();}
        CachedCallDuplexGuard idle=new CachedCallDuplexGuard(CallDuplexFixtures.PID,time);
        CallDuplexObserver successful=new CallDuplexObserver(c->CallDuplexObserverTest.idle(time),()->true,idle,time,null);
        successful.evidence((sample,token,decision,partial)->{throw new IllegalStateException("private-evidence-failure");});
        try{successful.start();successful.awaitFirst();idle.requireIdle();}finally{successful.close();}
    }
}
