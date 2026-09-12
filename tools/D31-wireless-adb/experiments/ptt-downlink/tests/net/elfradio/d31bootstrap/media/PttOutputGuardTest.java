package net.elfradio.d31bootstrap.media;
import org.junit.Test;
import org.json.JSONObject;
import org.json.JSONArray;
import static org.junit.Assert.*;

public class PttOutputGuardTest {
    @Test public void diagnosticFirstOutputDoesNotWaitForIdentityAndFailureRemainsBounded(){
        assertFalse(PttOutputObserver.shouldSave(0,false,true));
        assertTrue(PttOutputObserver.shouldSave(0,true,true));assertTrue(PttOutputObserver.shouldSave(1,true,true));
        assertFalse(PttOutputObserver.shouldSave(2,true,true));assertTrue(PttOutputObserver.shouldSave(2,true,false));
        assertFalse(PttOutputObserver.shouldSave(3,true,false));assertTrue(PttOutputObserver.shouldSave(0,false,false));
    }
    static JSONObject idle(int focus)throws Exception{
        JSONObject audio=new JSONObject().put("mode",0).put("focus_gain",focus);
        JSONArray streams=new JSONArray(),sources=new JSONArray();
        for(int i=0;i<10;i++)streams.put(new JSONObject().put("stream",i).put("active",false).put("remote_active",false));
        for(int i=0;i<9;i++)sources.put(new JSONObject().put("source",i).put("active",false));
        audio.put("streams",streams).put("sources",sources);
        return new JSONObject().put("audio",audio).put("cellular",new JSONObject().put("call_state",0).put("phone_count",1))
                .put("nexui",new JSONObject().put("resolved",true).put("statuses",new JSONArray().put("IDLE")));
    }
    static AudioCaptureObservation.Sample sample(JSONObject external)throws Exception{
        return new AudioCaptureObservation.Sample(D31OutputEvidenceTest.dump(D31OutputEvidenceTest.path("audio-flinger-private.txt")),
                D31OutputEvidenceTest.dump(D31OutputEvidenceTest.path("audio-policy-private.txt")),external,1000,1020);
    }
    @Test public void ownTransientFocusOnlyIsNormalizedWithoutMutatingRaw()throws Exception{
        PttProtocolTest.Time time=new PttProtocolTest.Time();time.now=1040;PttOutputGuard guard=new PttOutputGuard(1234,time);
        AudioCaptureObservation.Sample sample=sample(idle(2));String raw=sample.externalJson;guard.sample(sample,"");
        try{guard.requireIdle();fail();}catch(java.io.IOException expected){assertEquals("MEDIA_PTT_FOCUS_NOT_OWNED",expected.getMessage());}
        guard.focusOwned(true);guard.requireIdle();assertEquals(raw,sample.externalJson);
        guard.focusOwned(false);try{guard.requireIdle();fail();}catch(java.io.IOException expected){}
    }
    @Test public void focusDoesNotExemptCallsMicrophoneOrOtherOutput()throws Exception{
        PttProtocolTest.Time time=new PttProtocolTest.Time();time.now=1040;PttOutputGuard guard=new PttOutputGuard(1234,time);guard.focusOwned(true);
        for(int kind=0;kind<4;kind++){
            JSONObject raw=idle(2);
            if(kind==0)raw.getJSONObject("cellular").put("call_state",1);
            if(kind==1)raw.getJSONObject("audio").getJSONArray("sources").getJSONObject(1).put("active",true);
            if(kind==2)raw.getJSONObject("audio").getJSONArray("streams").getJSONObject(3).put("active",true);
            if(kind==3)raw.getJSONObject("nexui").put("statuses",new JSONArray().put("CONNECTED"));
            guard.sample(sample(raw),"");try{guard.requireIdle();fail();}catch(java.io.IOException expected){}
        }
    }
    @Test public void noActiveEvidenceCannotAuthorizeAndFailedReadRevokesCachedIdle()throws Exception{
        PttProtocolTest.Time time=new PttProtocolTest.Time();time.now=1040;PttOutputGuard guard=new PttOutputGuard(1234,time);
        guard.sample(sample(idle(0)),"");guard.requireIdle();
        try{guard.requireOwnedOutput();fail();}catch(java.io.IOException expected){assertEquals("MEDIA_OUTPUT_NOT_STARTED",expected.getMessage());}
        guard.sample(null,"MEDIA_INPUT_SAMPLE_TIMEOUT");
        try{guard.requireIdle();fail();}catch(java.io.IOException expected){assertEquals("MEDIA_INPUT_SAMPLE_TIMEOUT",expected.getMessage());}
        assertFalse(guard.snapshot().getBoolean("self_output_verified"));
    }
    @Test public void staleOrClockRollbackRevokesCachedResultWithoutReading()throws Exception{
        PttProtocolTest.Time time=new PttProtocolTest.Time();time.now=1040;PttOutputGuard guard=new PttOutputGuard(1234,time);guard.sample(sample(idle(0)),"");
        for(long now:new long[]{999,5001}){time.now=now;try{guard.requireIdle();fail();}catch(java.io.IOException expected){assertEquals("MEDIA_OUTPUT_SAMPLE_STALE",expected.getMessage());}}
    }
}
