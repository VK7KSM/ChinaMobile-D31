package net.elfradio.d31bootstrap.media;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.webrtc.*;
import static org.junit.Assert.*;

public class PersistentSoftwareMediaTest {
    static final class Time implements PersistentSoftwareMedia.Time {long at=1;int waits;public long now(){return at;}public void waitNanos(long ns){waits++;at+=ns;}}
    static ByteBuffer bytes(){ByteBuffer b=ByteBuffer.allocateDirect(320);for(int i=0;i<320;i++)b.put(i,(byte)77);return b;}
    static void zero(ByteBuffer b){for(int i=0;i<b.capacity();i++)assertEquals(0,b.get(i));}
    @Test public void softwareZeroTicksAtTenMsWithoutBurstCatchup()throws Exception{Time t=new Time();try(PersistentSoftwareMedia sw=new PersistentSoftwareMedia(()->fail(),t)){
        ByteBuffer b=bytes();sw.audio(b,2,1,16000,0,0,false);assertEquals(10000001,t.at);zero(b);
        sw.audio(b,2,1,16000,0,0,false);assertEquals(20000001,t.at);t.at+=1000000000;sw.audio(b,2,1,16000,0,0,false);
        long now=t.at;sw.audio(b,2,1,16000,0,0,false);assertEquals(now+10000000,t.at);assertEquals(4,sw.audioBuffers());}}
    @Test public void realCaptureDoesNotAddTenMsDelayAndPreservesPcm()throws Exception{Time t=new Time();try(PersistentSoftwareMedia sw=new PersistentSoftwareMedia(()->fail(),t)){
        ByteBuffer b=bytes();assertEquals(123,sw.audio(b,2,1,16000,320,123,true));assertEquals(0,t.waits);assertEquals(77,b.get(0));}}
    @Test public void stoppingWipesAlreadyReadHardwareBytes()throws Exception{try(PersistentSoftwareMedia sw=new PersistentSoftwareMedia(()->fail(),new Time())){ByteBuffer b=bytes();sw.audio(b,2,1,16000,320,123,false);zero(b);}}
    @Test public void invalidShapeWipesBytesAndFails()throws Exception{AtomicInteger failures=new AtomicInteger();try(PersistentSoftwareMedia sw=new PersistentSoftwareMedia(failures::incrementAndGet,new Time())){
        ByteBuffer b=bytes();sw.audio(b,2,1,48000,320,123,true);zero(b);assertEquals(1,failures.get());}}
    @Test public void closedSoftwareNeverPassesMicrophoneBytes()throws Exception{PersistentSoftwareMedia sw=new PersistentSoftwareMedia(()->fail(),new Time());sw.close();ByteBuffer b=bytes();sw.audio(b,2,1,16000,320,123,true);zero(b);}
    @Test public void interruptedSoftwareCallbackKeepsPacingAndInterrupt()throws Exception{Time t=new Time();try(PersistentSoftwareMedia sw=new PersistentSoftwareMedia(()->fail(),t)){
        Thread.currentThread().interrupt();sw.audio(bytes(),2,1,16000,0,0,false);assertTrue(Thread.interrupted());assertEquals(10000001,t.at);
    }finally{Thread.interrupted();}}
    @Test public void actualI420FrameIs160By120Black()throws Exception{VideoFrame frame=PersistentSoftwareMedia.black(123);try{
        assertEquals(160,frame.getBuffer().getWidth());assertEquals(120,frame.getBuffer().getHeight());VideoFrame.I420Buffer b=frame.getBuffer().toI420();
        try{for(int i=0;i<b.getDataY().capacity();i++)assertEquals(16,b.getDataY().get(i));assertEquals((byte)128,b.getDataU().get(0));assertEquals((byte)128,b.getDataV().get(0));}finally{b.release();}
    }finally{frame.release();}}
}
