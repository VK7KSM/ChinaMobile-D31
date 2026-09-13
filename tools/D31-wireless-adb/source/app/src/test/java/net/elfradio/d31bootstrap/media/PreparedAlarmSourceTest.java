package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

/** 只验证注入来源的组合与否决；自身输出和焦点的归属证明由调用方提供。 */
public class PreparedAlarmSourceTest {
    static JSONObject sample()throws Exception {return AndroidAudioOccupancyTest.idle();}
    static JSONObject stream(JSONObject value,int id)throws Exception {
        return value.getJSONObject("audio").getJSONArray("streams").getJSONObject(id);
    }
    static AndroidAudioOccupancy.State state(JSONObject value) {
        return AndroidAudioOccupancy.evaluate(value,100,110).overall();
    }
    @Test public void eachReadUsesCurrentSourceAndDoesNotMutateItsSample()throws Exception {
        JSONObject value=sample();stream(value,4).put("active",true);String before=value.toString();
        AtomicInteger reads=new AtomicInteger();AndroidAudioOccupancy.Source source=()->{reads.incrementAndGet();return value;};
        JSONObject owned=PhotoAlarmBackend.alarmObservation(source,true);
        assertEquals(AndroidAudioOccupancy.State.IDLE,state(owned));assertEquals(before,value.toString());
        value.getJSONObject("cellular").put("call_state",2);
        assertEquals(AndroidAudioOccupancy.State.BUSY,state(PhotoAlarmBackend.alarmObservation(source,true)));
        assertEquals(2,reads.get());
    }
    @Test public void sourceProjectionDoesNotGrantUnownedAlarmExemption()throws Exception {
        JSONObject projected=sample();stream(projected,4).put("active",true);
        assertEquals(AndroidAudioOccupancy.State.BUSY,state(PhotoAlarmBackend.alarmObservation(()->projected,false)));
        assertEquals(AndroidAudioOccupancy.State.IDLE,state(PhotoAlarmBackend.alarmObservation(()->projected,true)));
        assertTrue(stream(projected,4).getBoolean("active"));
    }
    @Test public void unprojectedMusicAndFocusRemainBusyEvenWhenToneOwned()throws Exception {
        for(boolean music:new boolean[]{true,false}) {
            JSONObject value=sample();stream(value,4).put("active",true);
            if(music)stream(value,3).put("active",true);else value.getJSONObject("audio").put("focus_gain",1);
            assertEquals(AndroidAudioOccupancy.State.BUSY,state(PhotoAlarmBackend.alarmObservation(()->value,true)));
        }
    }
    @Test public void otherOwnersCallsRemoteAlarmAndUnknownFieldsStillVeto()throws Exception {
        for(int variant=0;variant<7;variant++) {
            JSONObject value=sample();stream(value,4).put("active",true);
            switch(variant) {
                case 0:stream(value,2).put("active",true);break;
                case 1:stream(value,4).put("remote_active",true);break;
                case 2:value.getJSONObject("audio").getJSONArray("sources").getJSONObject(1).put("active",true);break;
                case 3:value.getJSONObject("cellular").put("call_state",2);break;
                case 4:value.getJSONObject("nexui").put("statuses",new JSONArray().put("CONNECTED"));break;
                case 5:value.getJSONObject("audio").put("mode",3);break;
                default:value.getJSONObject("audio").remove("sources");
            }
            assertEquals(variant==6?AndroidAudioOccupancy.State.UNKNOWN:AndroidAudioOccupancy.State.BUSY,
                    state(PhotoAlarmBackend.alarmObservation(()->value,true)));
        }
    }
    @Test public void sourceFailureReplacesPreviouslyIdleMonitorAndDoesNotFallBack()throws Exception {
        AtomicInteger reads=new AtomicInteger();AtomicLong now=new AtomicLong(100);
        AndroidAudioOccupancy.Source source=()->{if(reads.incrementAndGet()>1)throw new IOException("SOURCE_PROOF_LOST");return sample();};
        try(AndroidAudioOccupancy monitor=new AndroidAudioOccupancy(()->PhotoAlarmBackend.alarmObservation(source,true),now::get)) {
            monitor.start();assertTrue(monitor.awaitFirstSample(1500));monitor.requireIdle();
            monitor.refresh();assertEquals("SOURCE_READ_FAILED",monitor.snapshot().getString("reason"));
            try{monitor.requireIdle();fail();}catch(IOException expected){assertTrue(expected.getMessage().startsWith("MEDIA_AUDIO_UNKNOWN"));}
            assertEquals(2,reads.get());
        }
    }
    @Test public void nullOrIncompleteSourceIsNeverManufacturedIntoIdle()throws Exception {
        try{PhotoAlarmBackend.alarmObservation(()->null,true);fail();}catch(NullPointerException expected){}
        assertEquals(AndroidAudioOccupancy.State.UNKNOWN,state(PhotoAlarmBackend.alarmObservation(()->new JSONObject(),true)));
    }
    @Test public void oldAndOptionalSourceConstructorsRemainAvailable()throws Exception {
        Class<?>[] old={android.content.Context.class,java.io.File.class,RtcOffer.class,PreparedSessionPort.Operation.class,
                PreparedSessionPort.OperationEvents.class,AudioGuard.class};
        assertNotNull(AppPreparedExtras.class.getConstructor(old));
        Class<?>[] added=java.util.Arrays.copyOf(old,old.length+1);added[old.length]=AndroidAudioOccupancy.Source.class;
        assertNotNull(AppPreparedExtras.class.getConstructor(added));
        assertNotNull(PhotoAlarmBackend.class.getDeclaredConstructor(android.content.Context.class,android.os.Handler.class,JSONObject.class));
        assertNotNull(PhotoAlarmBackend.class.getDeclaredConstructor(android.content.Context.class,android.os.Handler.class,JSONObject.class,boolean.class,AudioGuard.class));
    }
}
