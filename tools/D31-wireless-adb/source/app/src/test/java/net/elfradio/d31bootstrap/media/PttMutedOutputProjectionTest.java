package net.elfradio.d31bootstrap.media;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 其它活动流保留给原警报监控；投影不是全局空闲授权。 */
public class PttMutedOutputProjectionTest {
    final CallDuplexFixtures.Time clock=new CallDuplexFixtures.Time();
    final PttOutputGuard guard=new PttOutputGuard(D31OutputFixtures.PID,clock);
    static String flinger(int stream){
        String other=D31OutputFixtures.activeRow().replace("3 yes 41001 3","4 yes 42001 "+stream).replace("7101","7401");
        return D31OutputFixtures.flinger(true).replace("4 Tracks of which 1","5 Tracks of which 2")
                .replace(D31OutputFixtures.activeRow(),D31OutputFixtures.activeRow()+other)
                .replace("Hardware status:"," 7401 42001 1\nHardware status:");
    }
    static String policy(int stream){return D31OutputFixtures.policy(true).replaceFirst(
            String.format(java.util.Locale.ROOT,"%02d -20.000 00 00",stream),String.format(java.util.Locale.ROOT,"%02d -20.000 01 00",stream));}
    JSONObject external(int stream)throws Exception {
        JSONObject value=CallDuplexFixtures.external(false,true,2);
        value.getJSONObject("audio").getJSONArray("streams").getJSONObject(stream).put("active",true);return value;
    }
    void bind()throws Exception{guard.actualOutput(D31OutputFixtures.identity());guard.focusOwned(true);guard.confirmMuted(guard.outputMuted(true));}
    void sample(String f,String p,JSONObject e){guard.sample(CallDuplexFixtures.sample(f,p,e,1000),"");}
    @Test public void alarmRemainsVisibleAndOldStrictApiStillRejectsIt()throws Exception {
        bind();JSONObject raw=external(4);String original=raw.toString();sample(flinger(4),policy(4),raw);
        PttPersistentOutputGuardTest.denied(guard::requireOwnedOutput);PttPersistentOutputGuardTest.denied(guard::requireOperationIdle);
        JSONObject projected=guard.projectMutedOutput();JSONObject audio=projected.getJSONObject("audio");
        assertFalse(audio.getJSONArray("streams").getJSONObject(3).getBoolean("active"));
        assertTrue(audio.getJSONArray("streams").getJSONObject(4).getBoolean("active"));assertEquals(0,audio.getInt("focus_gain"));
        assertEquals(original,raw.toString());
        assertEquals(AndroidAudioOccupancy.State.BUSY,AndroidAudioOccupancy.evaluate(projected,1000,1040).overall());
        assertEquals(AndroidAudioOccupancy.State.BUSY,AndroidAudioOccupancy.evaluate(PhotoAlarmBackend.alarmObservation(projected,false),1000,1040).overall());
        assertEquals(AndroidAudioOccupancy.State.IDLE,AndroidAudioOccupancy.evaluate(PhotoAlarmBackend.alarmObservation(projected,true),1000,1040).overall());
        projected.getJSONObject("audio").put("mode",99);assertEquals(0,guard.projectMutedOutput().getJSONObject("audio").getInt("mode"));
    }
    @Test public void otherStreamPhoneSipAndRemoteFlagsAreNeverHidden()throws Exception {
        bind();for(int fault=0;fault<4;fault++){
            JSONObject e=external(5);
            if(fault==1)e.getJSONObject("cellular").put("call_state",1);
            if(fault==2)e.getJSONObject("nexui").getJSONArray("statuses").put(0,"CONNECTED");
            if(fault==3)e.getJSONObject("audio").getJSONArray("streams").getJSONObject(5).put("remote_active",true);
            sample(flinger(5),policy(5),e);JSONObject projected=guard.projectMutedOutput();
            assertTrue(projected.getJSONObject("audio").getJSONArray("streams").getJSONObject(5).getBoolean("active"));
            assertEquals(AndroidAudioOccupancy.State.BUSY,AndroidAudioOccupancy.evaluate(PhotoAlarmBackend.alarmObservation(projected,true),1000,1040).overall());
        }
    }
    @Test public void otherStreamCannotBeMissingFromExternalOrPolicy()throws Exception {
        bind();JSONObject e=external(4);e.getJSONObject("audio").getJSONArray("streams").getJSONObject(4).put("active",false);
        sample(flinger(4),policy(4),e);PttPersistentOutputGuardTest.denied(guard::projectMutedOutput);
        sample(flinger(4),D31OutputFixtures.policy(true),external(4));PttPersistentOutputGuardTest.denied(guard::projectMutedOutput);
    }
    @Test public void secondMusicOwnerAndUnknownRowsStayRejected()throws Exception {
        bind();sample(flinger(4).replace("yes 42001 4","yes 42001 3"),policy(4),external(4));
        PttPersistentOutputGuardTest.denied(guard::projectMutedOutput);
        sample(flinger(4).replace("4 yes 42001","4 maybe 42001"),policy(4),external(4));
        PttPersistentOutputGuardTest.denied(guard::projectMutedOutput);
    }
    @Test public void unmuteFocusLossStalenessOrRealInputInvalidateProjection()throws Exception {
        bind();sample(flinger(4),policy(4),external(4));guard.projectMutedOutput();
        guard.outputMuted(false);PttPersistentOutputGuardTest.denied(guard::projectMutedOutput);
        guard.confirmMuted(guard.outputMuted(true));sample(flinger(4),policy(4),external(4));
        guard.focusOwned(false);PttPersistentOutputGuardTest.denied(guard::projectMutedOutput);
        guard.focusOwned(true);sample(flinger(4),policy(4),external(4));clock.now=5001;
        PttPersistentOutputGuardTest.denied(guard::projectMutedOutput);clock.now=1040;
        guard.sample(CallDuplexFixtures.sample(true,true,2,1000),"");PttPersistentOutputGuardTest.denied(guard::projectMutedOutput);
    }
    @Test public void incompleteOrInvalidWindowCannotProduceProjection()throws Exception {
        bind();AudioCaptureObservation.Sample value=CallDuplexFixtures.sample(flinger(4),policy(4),external(4),1000);
        guard.sample(new AudioCaptureObservation.Sample(value.flinger,value.policy,external(4),1040,1030),"");
        PttPersistentOutputGuardTest.denied(guard::projectMutedOutput);
        guard.sample(new AudioCaptureObservation.Sample(new AudioInputOwnership.Dump(value.flinger.text,false,1000,1010),
                value.policy,external(4),1021,1030),"");PttPersistentOutputGuardTest.denied(guard::projectMutedOutput);
    }
}
