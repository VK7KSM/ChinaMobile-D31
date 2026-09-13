package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreparedSessionAndroidTest {
    @Test public void nonRouteAudioStopWaitsForExistingCacheAndOtherModesDoNotWait()throws Exception{
        for(String mode:new String[]{"microphone","video","alarm"}){
            AtomicInteger reads=new AtomicInteger();
            PreparedSessionAndroid.awaitDeactivationIdle(()->{if(reads.incrementAndGet()<3)throw new IOException("MEDIA_AUDIO_BUSY");},mode,500);
            assertEquals(3,reads.get());
        }
        for(String mode:new String[]{"ptt","call","photo"})
            PreparedSessionAndroid.awaitDeactivationIdle(()->{fail("不得重复等待或检查无音频照片");},mode,500);
    }
    @Test public void nonRouteAudioStopRemainsBoundedAndDoesNotAcceptBusyCache()throws Exception{
        long started=System.nanoTime();
        try{PreparedSessionAndroid.awaitDeactivationIdle(()->{throw new IOException("MEDIA_AUDIO_BUSY");},"microphone",80);fail();}
        catch(IOException expected){assertEquals("MEDIA_PREPARED_IDLE_UNCONFIRMED",expected.getMessage());}
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<500);
    }
    @Test public void allFiveAudioModesRequireIdleAndPhotoDoesNot()throws Exception{
        AtomicInteger reads=new AtomicInteger();AudioGuard denied=()->{reads.incrementAndGet();throw new IOException("MEDIA_AUDIO_BUSY");};
        PreparedSessionAndroid.requireActivationIdle(denied,"photo");assertEquals(0,reads.get());
        for(String mode:new String[]{"call","ptt","microphone","video","alarm"}){
            try{PreparedSessionAndroid.requireActivationIdle(denied,mode);fail();}
            catch(IOException expected){assertEquals("MEDIA_AUDIO_BUSY",expected.getMessage());}
        }
        assertEquals(5,reads.get());
    }
    static final class Route {
        boolean focus,speaker=true,cleared;
        final AtomicInteger reads=new AtomicInteger();
        final DownlinkRouteLease lease;
        Route(AudioGuard guard)throws Exception{
            lease=new DownlinkRouteLease(new DownlinkRouteLease.Port(){
                public int mode(){return 0;}
                public boolean speaker(){return speaker;}
                public void speaker(boolean value){speaker=value;}
                public boolean focus(){focus=true;return true;}
                public void abandonFocus(){focus=false;}
            },new DownlinkRouteLease.Journal(){
                public JSONObject read()throws Exception{return cleared?null:new JSONObject().put("schema",1).put("old_mode",0).put("target_mode",0).put("old_speaker",false).put("target_speaker",true);}
                public void write(JSONObject value){}
                public void clear(){cleared=true;}
            },guard);
            java.lang.reflect.Field owned=DownlinkRouteLease.class.getDeclaredField("focused");owned.setAccessible(true);owned.setBoolean(lease,true);focus=true;
        }
    }
    @Test public void releasesFocusThenWaitsForFreshIdleBeforeRecover()throws Exception{
        Route[] route={null};AtomicInteger checks=new AtomicInteger();
        AudioGuard guard=()->{assertFalse(route[0].focus);if(checks.incrementAndGet()<3)throw new IOException("MEDIA_AUDIO_BUSY");};
        route[0]=new Route(guard);PreparedSessionAndroid.restore(route[0].lease,guard,500);
        assertTrue(checks.get()>=4);assertTrue(route[0].cleared);assertFalse(route[0].speaker);
    }
    @Test public void boundedIdleFailurePreservesJournalAndSpeaker()throws Exception{
        AudioGuard guard=()->{throw new IOException("MEDIA_AUDIO_BUSY");};Route route=new Route(guard);
        long started=System.nanoTime();try{PreparedSessionAndroid.restore(route.lease,guard,80);fail();}
        catch(IOException expected){assertEquals("MEDIA_PREPARED_ROUTE_IDLE_UNCONFIRMED",expected.getMessage());}
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<500);
        assertFalse(route.focus);assertFalse(route.cleared);assertTrue(route.speaker);
    }
}
