package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AppCallWiringTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    static JSONObject offer(MediaCapture.Clock clock)throws Exception{return RtcOfferTest.offer().put("mode","call").put("expires_at",clock.wall()+40000);}
    static void field(Object target,String name,Object value)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    @Test public void realBackendSelectsLazyCallShellAndFinalAndroidOperations()throws Exception{
        File files=temp.newFolder(),cache=temp.newFolder();AppMediaBackend backend=new AppMediaBackend(null);
        AppCallSession session=(AppCallSession)backend.createSession(new File(files,"unused.apk"),AppMediaContractTest.HASH,files,cache,
                ()->{throw new AssertionError("构造不可重新读取音频");},RtcOfferTest.parse(RtcOfferTest.offer().put("mode","call")));
        assertEquals("idle",session.snapshot().getString("state"));assertEquals("call",session.snapshot().getString("mode"));
        assertEquals(0,files.list().length);assertEquals(0,cache.list().length);
        Field factory=AppCallSession.class.getDeclaredField("factory");factory.setAccessible(true);
        CallSessionController.Operations operations=((AppCallSession.Factory)factory.get(session)).create(new AppCallSession.Sender(){
            public void send(JSONObject value){fail();}public void abort(){}public void close(){}
        },new AppCallSession.Signals(){public void changed(boolean ice){}public void failed(String code){}});
        assertTrue(operations instanceof AndroidCallOperations);operations.close();
        // 宿主仅替换此实例时钟用于无启动清理，生产固定使用Android elapsedRealtime。
        field(session,"clock",new AppCallSessionTest.Time());session.close();assertTrue(session.awaitClosed(2000));backend.close();
    }
    private final class Rig implements AutoCloseable {
        final AppCallSessionTest.Time clock=new AppCallSessionTest.Time();final AppCallSessionTest.Wire wire=new AppCallSessionTest.Wire();
        final AppCallSessionTest.Ops ops=new AppCallSessionTest.Ops();final Object owner=new Object();
        final File files=temp.newFolder();AppCallSession call;final AppMediaController controller;
        Rig()throws Exception {controller=new AppMediaController(new AppMediaController.Backend(){
            public URI origin(){return URI.create("https://control.example");}
            public JSONObject prepare(String hash,Cancellation cancel){throw new AssertionError("call不得借用prepare");}
            public AppMediaController.Session create(String hash,RtcOffer offer,Cancellation cancel)throws Exception{
                assertEquals("call",offer.mode);call=new AppCallSession(files,offer,clock,wire,(sender,signals)->{
                    ops.sender=sender;ops.signals=signals;return ops;});return call;
            }
        },clock);}
        void start()throws Exception {
            JSONObject command=new JSONObject().put("operation","start").put("apk_sha256",AppMediaContractTest.HASH).put("offer",offer(clock));
            controller.execute("call-start",AppMediaContract.command(command.toString()),owner,new Cancellation());
            assertTrue(wire.entered.await(2,TimeUnit.SECONDS));wire.emit(AppCallSessionTest.tracks());wire.emit(AppCallSessionTest.hello());
        }
        public void close()throws Exception{
            ops.releaseClose.countDown();controller.serviceDestroyed();if(call!=null)assertTrue(call.awaitClosed(3000));
        }
    }
    private interface Condition{boolean done()throws Exception;}
    private static void until(Condition condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.done()){if(System.nanoTime()>until)fail("状态等待超时");Thread.sleep(5);}}
    @Test public void unchangedControllerDrivesParsedCallAndPreservesDirectionalReceipt()throws Exception{
        try(Rig r=new Rig()){r.ops.input=1;r.ops.output=1;r.start();until(()->r.call.snapshot().optBoolean("ready"));
            JSONObject result=r.controller.query("session-1",r.owner);assertEquals("call",result.getString("mode"));
            assertTrue(result.getBoolean("input_verified"));assertTrue(result.getBoolean("output_verified"));assertFalse(result.getBoolean("managed_media"));
            assertFalse(result.has("managed_media_prepare_v1"));assertFalse(result.toString().contains("abcdefghijklmnop"));
            r.controller.stop("session-1");assertTrue(r.call.awaitClosed(3000));assertFalse(r.controller.hasActive());
        }
    }
    @Test public void callUsesExistingOwnerDeathLeaseAndMutualExclusion()throws Exception{
        try(Rig r=new Rig()){r.start();until(()->r.ops.opens.get()==1);assertTrue(r.controller.hasActive());
            MediaCaptureTest.rejects("BUSY",()->r.controller.execute("other",new JSONObject().put("operation","start")
                    .put("apk_sha256",AppMediaContractTest.HASH).put("offer",offer(r.clock).put("mode","ptt")),new Object(),new Cancellation()));
            r.controller.ownerDied(new Object());assertTrue(r.controller.hasActive());r.controller.ownerDied(r.owner);
            assertTrue(r.call.awaitClosed(3000));assertEquals(1,r.ops.closes.get());assertFalse(r.controller.hasActive());
        }
    }
    @Test public void releaseUnconfirmedFromRealCallShellKeepsControllerBusy()throws Exception{
        try(Rig r=new Rig()){r.ops.cleanupFails=true;r.start();until(()->r.ops.opens.get()==1);r.controller.stop("session-1");
            assertTrue(r.call.awaitClosed(3000));assertTrue(r.controller.hasActive());
            JSONObject result=r.controller.query("session-1",r.owner);assertEquals("release_unconfirmed",result.getString("state"));
            assertFalse(result.getBoolean("cleanup_complete"));
        }
    }
}
