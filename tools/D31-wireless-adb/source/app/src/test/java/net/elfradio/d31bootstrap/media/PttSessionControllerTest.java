package net.elfradio.d31bootstrap.media;
import org.junit.Test;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PttSessionControllerTest {
    static class Ops implements PttSessionController.Operations{
        PttSessionController controller;final CountDownLatch openEntered=new CountDownLatch(1),releaseOpen=new CountDownLatch(1);
        volatile boolean block,unknown,cleanupFails,muted;int opens,subscribes,unmutes,closes;AtomicInteger cancels=new AtomicInteger();
        public void open()throws Exception{opens++;openEntered.countDown();if(block&&!releaseOpen.await(2,TimeUnit.SECONDS))throw new Exception("test-timeout");}
        public void send(JSONObject body)throws Exception{
            if("rpc".equals(body.optString("type")))controller.receive(new JSONObject().put("type","rpc").put("id",body.getInt("id")).put("result",new JSONObject()).toString());
        }
        public JSONObject subscribe(JSONObject body){subscribes++;return new JSONObject();}
        public void answerAcknowledged(){}public void prepareMuted(){muted=true;}
        public int outputProof()throws Exception{if(unknown)throw new java.io.IOException("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED");return 1;}
        public boolean playbackFrames(){return true;}
        public void unmute(){unmutes++;}public void cancel(){cancels.incrementAndGet();releaseOpen.countDown();}
        public void close()throws Exception{closes++;if(cleanupFails)throw new java.io.IOException("MEDIA_ROUTE_RESTORE_UNCONFIRMED");}
    }
    static void waitState(PttSessionController c,String state)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<end){if(state.equals(c.snapshot().getString("state")))return;Thread.sleep(5);}
        fail("状态未到达: "+state+" 实际: "+c.snapshot());
    }
    static PttSessionController create(Ops ops,AtomicInteger changed)throws Exception{
        PttSessionController c=new PttSessionController(new PttProtocolTest.Time(),46000,ops,changed::incrementAndGet);ops.controller=c;return c;
    }
    @Test public void immediateRpcCallbacksDoNotDeadlockAndStableTicksDoNotReport()throws Exception{
        Ops ops=new Ops();AtomicInteger changed=new AtomicInteger();PttSessionController c=create(ops,changed);
        try{
            c.receive(PttProtocolTest.tracks().toString());c.receive(PttProtocolTest.hello().toString());c.ice(true);
            waitState(c,"streaming");Thread.sleep(15);int count=changed.get();
            for(int i=0;i<100;i++)c.tick();Thread.sleep(30);
            assertEquals(count,changed.get());assertEquals(1,ops.opens);assertEquals(1,ops.subscribes);assertEquals(1,ops.unmutes);
        }finally{c.close();assertTrue(c.awaitClosed(2000));}assertEquals(1,ops.closes);
    }
    @Test public void blockedOpenDoesNotBlockTickAndRemoteStopCancelsIt()throws Exception{
        Ops ops=new Ops();ops.block=true;PttSessionController c=create(ops,new AtomicInteger());
        try{
            c.receive(PttProtocolTest.hello().toString());assertTrue(ops.openEntered.await(1,TimeUnit.SECONDS));
            c.tick();assertEquals(1,ops.releaseOpen.getCount());
            c.receive("{type:'closed'}");assertTrue(c.awaitClosed(2000));assertTrue(ops.cancels.get()>0);
            assertEquals(0,ops.subscribes);assertEquals(0,ops.unmutes);
        }finally{c.close();c.awaitClosed(2000);}
    }
    @Test public void missingRealOutputProofStopsWithoutUnmuting()throws Exception{
        Ops ops=new Ops();ops.unknown=true;PttSessionController c=create(ops,new AtomicInteger());
        try{
            c.receive(PttProtocolTest.hello().toString());c.receive(PttProtocolTest.tracks().toString());c.ice(true);
            assertTrue(c.awaitClosed(2000));assertTrue(ops.muted);assertEquals(0,ops.unmutes);
            assertEquals("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED",c.snapshot().getString("reason"));
        }finally{c.close();c.awaitClosed(2000);}
    }
    @Test public void cleanupErrorRetainsBothBusinessReasonAndFailure()throws Exception{
        Ops ops=new Ops();ops.cleanupFails=true;PttSessionController c=create(ops,new AtomicInteger());
        c.stop("MEDIA_HOST_CLOSED");assertTrue(c.awaitClosed(2000));
        assertEquals("release_unconfirmed",c.snapshot().getString("state"));assertEquals("MEDIA_HOST_CLOSED",c.snapshot().getString("reason"));
        assertEquals("MEDIA_ROUTE_RESTORE_UNCONFIRMED",c.snapshot().getString("cleanup_reason"));
    }
}
