package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.content.ContextWrapper;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import static org.junit.Assert.*;

/** 真实WebRTC Java类，零JNI/设备；不定义生产同名替身。 */
public class CallCancellationBoundaryTest {
    private static Object empty(Class<?> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type);
    }
    private static void set(Object instance,Class<?> type,String name,Object value)throws Exception{
        Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(instance,value);
    }
    private static Object get(Object instance,Class<?> type,String name)throws Exception{
        Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(instance);
    }
    private static void await(CountDownLatch latch){
        try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("回调测试等待超时");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
    }
    static final class Fixture implements AutoCloseable {
        final AndroidCallPeer backend;
        final AndroidRtcCall peer;
        final JavaAudioDeviceModule module;
        final Object input,output;
        final PeerConnection.Observer callbacks;
        final AtomicInteger failures=new AtomicInteger();
        CountDownLatch failedEntered,failedRelease;
        Fixture()throws Exception{
            MediaCapture.Clock clock=new MediaCapture.Clock(){public long wall(){return 1040;}public long elapsed(){return 1040;}};
            backend=new AndroidCallPeer((Context)empty(ContextWrapper.class),new File("unused.apk"),
                    new String(new char[64]).replace('\0','a'),new File("unused-cache"),clock);
            peer=new AndroidRtcCall(backend,clock,new CallDuplexGuard(){
                public void requireIdleSpeakerRoute(){}
                public Evidence current(Identity in,Identity out){return new Evidence(in,out,true,true,1000,1020);}
            },new AndroidRtcCall.Events(){public void changed(boolean ice){}public void failed(String code){
                failures.incrementAndGet();if(failedEntered!=null){failedEntered.countDown();await(failedRelease);}
            }},1000,3000);
            set(backend,AndroidCallPeer.class,"owner",peer);
            set(backend,AndroidCallPeer.class,"cancel",get(peer,AndroidRtcCall.class,"cancellation"));
            module=(JavaAudioDeviceModule)empty(JavaAudioDeviceModule.class);
            input=empty(Class.forName("org.webrtc.audio.WebRtcAudioRecord"));
            output=empty(Class.forName("org.webrtc.audio.WebRtcAudioTrack"));
            set(module,JavaAudioDeviceModule.class,"audioInput",input);set(module,JavaAudioDeviceModule.class,"audioOutput",output);
            PeerConnection.Observer found=null;
            for(Class<?> type:anonymousClasses())if(PeerConnection.Observer.class.isAssignableFrom(type)){
                Constructor<?> constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
                Class<?>[] types=constructor.getParameterTypes();Object[] args=new Object[types.length];
                for(int i=0;i<types.length;i++){
                    if(types[i]==AndroidCallPeer.class)args[i]=backend;
                    else if(types[i]==AndroidRtcCall.class)args[i]=peer;
                    else if(types[i]==Cancellation.class)args[i]=get(peer,AndroidRtcCall.class,"cancellation");
                    else throw new AssertionError("未知生产回调构造参数："+types[i]);
                }
                found=(PeerConnection.Observer)constructor.newInstance(args);break;
            }
            if(found==null)throw new AssertionError("未找到实际生产轨道回调");callbacks=found;
        }
        private static java.util.List<Class<?>> anonymousClasses()throws Exception{
            java.util.List<Class<?>> found=new java.util.ArrayList<>();
            for(int i=1;i<=12;i++)try{found.add(Class.forName(AndroidCallPeer.class.getName()+"$"+i));}catch(ClassNotFoundException ignored){}
            return found;
        }
        void attach()throws Exception{peer.attachMute(AndroidCallPeer.softwareMute(module));}
        void unmute()throws Exception{
            attach();set(peer,AndroidRtcCall.class,"prepared",true);peer.ice(true);
            peer.identity(new CallDuplexGuard.Identity(true,41001,7101,16000,1,2,1));
            peer.identity(new CallDuplexGuard.Identity(false,41001,7102,48000,1,2,3));
            peer.pcm(true,new byte[320],2,1,16000);peer.pcm(false,new byte[960],2,1,48000);peer.unmute();
        }
        void assertMuted(boolean expected)throws Exception{
            assertEquals(expected,get(input,input.getClass(),"microphoneMute"));assertEquals(expected,get(output,output.getClass(),"speakerMute"));
        }
        public void close(){try{peer.close();}catch(Exception ignored){}}
    }
    public static final class BlockingTrack extends AudioTrack {
        CountDownLatch entered,release;
        private BlockingTrack(){super(1);}
        public boolean setEnabled(boolean enabled){entered.countDown();await(release);return true;}
    }
    private static BlockingTrack blocking()throws Exception{
        BlockingTrack track=(BlockingTrack)empty(BlockingTrack.class);track.entered=new CountDownLatch(1);track.release=new CountDownLatch(1);return track;
    }
    private static RtpReceiver receiver(AudioTrack track)throws Exception{
        RtpReceiver value=(RtpReceiver)empty(RtpReceiver.class);set(value,RtpReceiver.class,"cachedTrack",track);return value;
    }
    private static RtpTransceiver transceiver(RtpReceiver receiver)throws Exception{
        RtpTransceiver value=(RtpTransceiver)empty(RtpTransceiver.class);set(value,RtpTransceiver.class,"cachedReceiver",receiver);return value;
    }
    @Test public void cancelMutesBothActualFieldsBeforeClose()throws Exception{
        try(Fixture f=new Fixture()){f.unmute();f.assertMuted(false);f.peer.cancel();f.assertMuted(true);assertEquals(0,f.failures.get());}
    }
    @Test public void attachAndCancelNeverInvokeLibraryLogCallbacks()throws Exception{
        AtomicInteger logs=new AtomicInteger();Method install=Logging.class.getDeclaredMethod("injectLoggable",Loggable.class,Logging.Severity.class);
        Method remove=Logging.class.getDeclaredMethod("deleteInjectedLoggable");install.setAccessible(true);remove.setAccessible(true);
        try(Fixture f=new Fixture()){
            try{install.invoke(null,(Loggable)(message,severity,tag)->logs.incrementAndGet(),Logging.Severity.LS_VERBOSE);
                f.unmute();f.peer.cancel();f.assertMuted(true);assertEquals(0,logs.get());
            }finally{remove.invoke(null);}
        }
    }
    @Test public void incompleteModuleCannotAttachOrPartiallyUnmute()throws Exception{
        try(Fixture f=new Fixture()){
            f.attach();set(f.module,JavaAudioDeviceModule.class,"audioOutput",null);
            try{AndroidCallPeer.softwareMute(f.module);fail("缺少真实输出仍生成静音控制");}
            catch(IOException expected){assertEquals("MEDIA_CALL_SOFTWARE_MUTE_CONTRACT_INVALID",expected.getMessage());}
            f.assertMuted(true);
        }
    }
    static final class NotVolatile {boolean microphoneMute;}
    static final class WrongType {volatile int microphoneMute;}
    static final class StaticField {static volatile boolean microphoneMute;}
    @Test public void wrongFieldContractsAreRejected()throws Exception{
        Method resolve=AndroidCallPeer.class.getDeclaredMethod("muteField",Object.class,String.class);resolve.setAccessible(true);
        for(Object device:new Object[]{new NotVolatile(),new WrongType(),new StaticField()}){
            try{resolve.invoke(null,device,"microphoneMute");fail("不符合固定库字段合同仍获准");}
            catch(InvocationTargetException expected){assertEquals("MEDIA_CALL_SOFTWARE_MUTE_CONTRACT_INVALID",expected.getCause().getMessage());}
        }
    }
    @Test public void lateTrackCallbacksDoNotTouchReleasedHandles()throws Exception{
        try(Fixture f=new Fixture()){
            f.peer.cancel();RtpReceiver receiver=receiver((AudioTrack)empty(AudioTrack.class));
            f.callbacks.onTrack(transceiver(receiver));f.callbacks.onAddTrack(receiver,new MediaStream[0]);assertEquals(0,f.failures.get());
        }
    }
    @Test public void lateIdentityCallbackDoesNotReadReleasedModule()throws Exception{
        try(Fixture f=new Fixture()){
            f.peer.close();Method identity=AndroidCallPeer.class.getDeclaredMethod("identity",boolean.class);identity.setAccessible(true);
            identity.invoke(f.backend,true);identity.invoke(f.backend,false);assertEquals(0,f.failures.get());
        }
    }
    @Test public void cancelDoesNotWaitForEnteredTrackCallback()throws Exception{
        try(Fixture f=new Fixture()){
            f.unmute();BlockingTrack track=blocking();RtpReceiver receiver=receiver(track);ExecutorService workers=Executors.newFixedThreadPool(2);
            try{
                Future<?> callback=workers.submit(()->f.callbacks.onAddTrack(receiver,new MediaStream[0]));assertTrue(track.entered.await(1,TimeUnit.SECONDS));
                workers.submit(f.peer::cancel).get(500,TimeUnit.MILLISECONDS);f.assertMuted(true);assertEquals(1,track.release.getCount());
                track.release.countDown();callback.get(1,TimeUnit.SECONDS);
            }finally{track.release.countDown();workers.shutdownNow();}
        }
    }
    @Test public void closeDrainsOnAddTrackBeforeSuccess()throws Exception{drainsTrack(false);}
    @Test public void closeDrainsOnTrackBeforeSuccess()throws Exception{drainsTrack(true);}
    private void drainsTrack(boolean onTrack)throws Exception{
        try(Fixture f=new Fixture()){
            BlockingTrack track=blocking();RtpReceiver receiver=receiver(track);RtpTransceiver tx=transceiver(receiver);
            ExecutorService workers=Executors.newFixedThreadPool(2);
            try{
                Future<?> callback=workers.submit(()->{if(onTrack)f.callbacks.onTrack(tx);else f.callbacks.onAddTrack(receiver,new MediaStream[0]);});
                assertTrue(track.entered.await(1,TimeUnit.SECONDS));CountDownLatch closing=new CountDownLatch(1),returned=new CountDownLatch(1);
                Future<?> close=workers.submit(()->{closing.countDown();try{f.peer.close();}catch(Exception e){throw new RuntimeException(e);}finally{returned.countDown();}});
                assertTrue(closing.await(1,TimeUnit.SECONDS));assertFalse("句柄回调未退出时提前释放成功",returned.await(100,TimeUnit.MILLISECONDS));
                track.release.countDown();callback.get(1,TimeUnit.SECONDS);close.get(1,TimeUnit.SECONDS);
                assertTrue(f.peer.snapshot().getBoolean("peer_cleanup_complete"));
            }finally{track.release.countDown();workers.shutdownNow();}
        }
    }
    @Test public void identityFailureCallbackIsAlsoDrained()throws Exception{
        try(Fixture f=new Fixture()){
            f.failedEntered=new CountDownLatch(1);f.failedRelease=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
            Method identity=AndroidCallPeer.class.getDeclaredMethod("identity",boolean.class);identity.setAccessible(true);
            try{
                Future<?> callback=workers.submit(()->{try{identity.invoke(f.backend,true);}catch(Exception e){throw new RuntimeException(e);}});
                assertTrue(f.failedEntered.await(1,TimeUnit.SECONDS));CountDownLatch closing=new CountDownLatch(1),returned=new CountDownLatch(1);
                Future<?> close=workers.submit(()->{closing.countDown();try{f.peer.close();}catch(Exception e){throw new RuntimeException(e);}finally{returned.countDown();}});
                assertTrue(closing.await(1,TimeUnit.SECONDS));assertFalse(returned.await(100,TimeUnit.MILLISECONDS));
                f.failedRelease.countDown();callback.get(1,TimeUnit.SECONDS);close.get(1,TimeUnit.SECONDS);
            }finally{f.failedRelease.countDown();workers.shutdownNow();}
        }
    }
    @Test public void hungCallbackFailsWithinExistingReleaseBudgetAndRetainsResources()throws Exception{
        try(Fixture f=new Fixture()){
            BlockingTrack track=blocking();RtpReceiver receiver=receiver(track);ExecutorService worker=Executors.newSingleThreadExecutor();
            java.util.Map<String,String> events=(java.util.Map<String,String>)get(f.backend,AndroidCallPeer.class,"remoteEvents");events.put("synthetic","audio");
            try{
                Future<?> callback=worker.submit(()->f.callbacks.onAddTrack(receiver,new MediaStream[0]));assertTrue(track.entered.await(1,TimeUnit.SECONDS));long began=System.nanoTime();
                try{f.peer.close();fail("未排空却声称释放完成");}
                catch(IOException expected){assertEquals("MEDIA_CALL_RELEASE_UNCONFIRMED",expected.getMessage());
                    assertEquals("MEDIA_CALL_CALLBACK_RELEASE_UNCONFIRMED",expected.getCause().getCause().getMessage());}
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)<3000);assertEquals("audio",events.get("synthetic"));
                track.release.countDown();callback.get(1,TimeUnit.SECONDS);
                assertFalse(f.peer.snapshot().getBoolean("peer_cleanup_complete"));assertTrue(f.peer.snapshot().getBoolean("release_unconfirmed"));
            }finally{track.release.countDown();worker.shutdownNow();}
        }
    }
}
