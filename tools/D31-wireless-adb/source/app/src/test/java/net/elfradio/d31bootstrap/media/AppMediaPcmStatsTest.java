package net.elfradio.d31bootstrap.media;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class AppMediaPcmStatsTest {
    @Test public void signedPcmAndAggregateEnergyWithoutKeepingOrChangingBytes()throws Exception{
        AppMediaPcmStats stats=new AppMediaPcmStats();
        byte[] data={0,0,0,64,0,(byte)128,(byte)255,127};byte[] original=data.clone();
        stats.accept(data,2,1,16000,true);assertArrayEquals(original,data);
        double expected=Math.sqrt((16384.0*16384+32768.0*32768+32767.0*32767)/4)/32768;
        java.util.Arrays.fill(data,(byte)0);
        JSONObject value=stats.snapshot();assertEquals(1,value.getLong("callbacks"));
        assertEquals(4,value.getLong("pcm_frames"));assertEquals(1,value.getDouble("peak"),0);
        assertEquals(expected,value.getDouble("rms"),1e-12);
        stats.accept(data,2,1,16000,false);
        assertEquals(expected/Math.sqrt(2),stats.snapshot().getDouble("rms"),1e-12);
        assertEquals(1,stats.snapshot().getLong("guard_ready_callbacks"));
    }
    @Test public void zeroSamplesAreValidAndNeverAuthorizeOrReject()throws Exception{
        AppMediaPcmStats stats=new AppMediaPcmStats();stats.accept(new byte[320],2,1,16000,true);
        JSONObject value=stats.snapshot();assertEquals(160,value.getLong("pcm_frames"));
        assertEquals(0,value.getDouble("peak"),0);assertEquals(0,value.getDouble("rms"),0);
        assertEquals(0,value.getLong("invalid_callbacks"));assertFalse(value.getBoolean("contains_audio"));
        assertFalse(value.getBoolean("used_for_authorization"));
    }
    @Test public void malformedUnexpectedOrOversizedFramesOnlyAffectDiagnosticCounter()throws Exception{
        AppMediaPcmStats stats=new AppMediaPcmStats();
        stats.accept(null,2,1,16000,true);stats.accept(new byte[1],2,1,16000,true);
        stats.accept(new byte[2],2,1,48000,true);stats.accept(new byte[2],3,1,16000,true);
        stats.accept(new byte[4],2,2,16000,true);stats.accept(new byte[8194],2,1,16000,true);
        JSONObject value=stats.snapshot();assertEquals(6,value.getLong("invalid_callbacks"));
        assertEquals(0,value.getLong("pcm_frames"));assertEquals(0,value.getDouble("rms"),0);
    }
}
