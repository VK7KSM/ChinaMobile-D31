package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CachedCallDuplexGuardTest {
    final CallDuplexFixtures.Time time=new CallDuplexFixtures.Time();
    final CachedCallDuplexGuard guard=new CachedCallDuplexGuard(CallDuplexFixtures.PID,time);
    CallDuplexGuard.Identity in,out;
    private void bind()throws Exception{in=CallDuplexFixtures.input();out=CallDuplexFixtures.output();guard.bind(in,out);guard.focusOwned(true);}
    private void sample(AudioCaptureObservation.Sample s){guard.sample(s,true,1000,1040,guard.generation());}
    private void ready()throws Exception{bind();sample(CallDuplexFixtures.sample(true,true,2,1000));assertNotNull(guard.snapshot().toString(),guard.current(in,out));}
    @Test public void sameSampleOwnsBothWithOriginalIdentityObjects()throws Exception{
        ready();CallDuplexGuard.Evidence e=guard.current(in,out);assertTrue(e.inputOwned);assertTrue(e.outputOwned);
        for(int i=0;i<10000;i++)assertSame(e,guard.current(in,out));assertSame(in,e.input);assertSame(out,e.output);
        assertNull(guard.current(CallDuplexFixtures.input(),out));
    }
    @Test public void inputProjectionNeverChangesPttInputRejection()throws Exception{
        ready();AudioCaptureObservation.Sample s=CallDuplexFixtures.sample(true,true,2,1000);
        assertEquals(D31OutputEvidence.State.BUSY,D31OutputEvidence.evaluate(s.flinger,s.policy,D31OutputIdentity.from(out.privateJson()),1040).state);
    }
    @Test public void age3500AndWholeWindow1500AreDifferentGates()throws Exception{
        ready();time.now=4500;assertNotNull(guard.current(in,out));time.now=4501;assertNull(guard.current(in,out));
        time.now=2501;guard.sample(CallDuplexFixtures.sample(true,true,2,1000),true,1000,2501,guard.generation());assertNull(guard.current(in,out));
    }
    @Test public void worstCaseFixedRateSamplesDoNotPeriodicallyExpire()throws Exception{
        bind();for(long began=1000;began<20000;began+=1500){time.now=began+1500;
            guard.sample(CallDuplexFixtures.sample(true,true,2,began),true,began,began+1500,guard.generation());
            assertNotNull(guard.current(in,out));time.now=began+2999;assertNotNull(guard.current(in,out));}
    }
    @Test public void focusLossAndRegainCannotResurrectInflightObservation()throws Exception{
        ready();CachedCallDuplexGuard.Generation token=guard.generation();guard.focusOwned(false);guard.focusOwned(true);
        sampleWith(token);assertNull(guard.current(in,out));sample(CallDuplexFixtures.sample(true,true,2,1000));assertNotNull(guard.current(in,out));
    }
    private void sampleWith(CachedCallDuplexGuard.Generation token)throws Exception{guard.sample(CallDuplexFixtures.sample(true,true,2,1000),true,1000,1040,token);}
    @Test public void failedReadRevokesAndLateCompletionCannotRestore()throws Exception{
        ready();CachedCallDuplexGuard.Generation token=guard.generation();guard.invalidate(token);sampleWith(token);assertNull(guard.current(in,out));
        sample(null);assertNull(guard.current(in,out));
    }
    @Test public void identityBindingMustPrecedeEntireObservation()throws Exception{
        CachedCallDuplexGuard.Generation token=guard.generation();bind();sampleWith(token);assertNull(guard.current(in,out));
        sample(CallDuplexFixtures.sample(true,true,2,1000));assertNotNull(guard.current(in,out));
        try{guard.bind(CallDuplexFixtures.input(),out);fail();}catch(IOException expected){assertEquals("MEDIA_CALL_IDENTITY_CHANGED",expected.getMessage());}
        assertNull(guard.current(in,out));
    }
    @Test public void startingIsPendingNeverAuthorizationOrIdle()throws Exception{
        bind();String f=CallDuplexFixtures.flinger(true,true).replaceFirst("Standby: no","Standby: yes").replace("4800 A 3","4104 R 1");
        sample(CallDuplexFixtures.sample(f,CallDuplexFixtures.policy(true,true),CallDuplexFixtures.external(true,true,2),1000));
        CallDuplexGuard.Evidence e=guard.current(in,out);assertNotNull(guard.snapshot().toString(),e);assertTrue(e.inputOwned);assertFalse(e.outputOwned);
        try{guard.requireIdle();fail();}catch(IOException expected){}
    }
    @Test public void underrunIsAcceptedButInvalidDisabledFlagsAreRejected()throws Exception{
        bind();String f=CallDuplexFixtures.flinger(true,true);
        sample(CallDuplexFixtures.sample(f.replace("0x000 0","0x001 1026"),CallDuplexFixtures.policy(true,true),CallDuplexFixtures.external(true,true,2),1000));
        assertTrue(guard.current(in,out).outputOwned);
        for(String flags:new String[]{"0x002","0x003","0x004","0x008","0x601"}){
            sample(CallDuplexFixtures.sample(f.replace("0x000 0",flags+" 0"),CallDuplexFixtures.policy(true,true),CallDuplexFixtures.external(true,true,2),1000));
            assertNull(flags,guard.current(in,out));}
    }
    @Test public void externalPhoneSipOtherRecordingPlaybackAndRemoteAllRevoke()throws Exception{
        bind();for(int fault=0;fault<8;fault++){JSONObject e=CallDuplexFixtures.external(true,true,2),a=e.getJSONObject("audio");
            switch(fault){case 0:e.getJSONObject("cellular").put("call_state",1);break;
                case 1:e.getJSONObject("nexui").getJSONArray("statuses").put(0,"CONNECTED");break;
                case 2:a.getJSONArray("sources").getJSONObject(7).put("active",true);break;
                case 3:a.getJSONArray("streams").getJSONObject(4).put("active",true);break;
                case 4:a.getJSONArray("streams").getJSONObject(3).put("remote_active",true);break;
                case 5:a.put("focus_gain",0);break;case 6:a.put("mode",3);break;case 7:a.put("service_error_type","private-value");break;}
            sample(CallDuplexFixtures.sample(CallDuplexFixtures.flinger(true,true),CallDuplexFixtures.policy(true,true),e,1000));assertNull("fault="+fault,guard.current(in,out));
            assertFalse(guard.snapshot().toString().contains("private-value"));}
    }
    @Test public void wrongInputPidSessionConfigurationOrOutputOwnerCannotBeProjected()throws Exception{
        bind();String f=CallDuplexFixtures.flinger(true,true);
        for(String changed:new String[]{f.replace("yes 41001 1","yes 41002 1"),f.replace("00000010 42 6","00000010 43 6"),
                f.replace("yes 41001 3","yes 41002 3"),f.replace("Sample rate: 16000","Sample rate: 48000")}){
            sample(CallDuplexFixtures.sample(changed,CallDuplexFixtures.policy(true,true),CallDuplexFixtures.external(true,true,2),1000));assertNull(guard.current(in,out));}
    }
    @Test public void patchProjectionRejectsWrongDirectionIdHandleDuplicatesOrThirdPatch()throws Exception{
        bind();String p=CallDuplexFixtures.policy(true,true);
        for(String changed:new String[]{p.replace("Mix ID 8","Mix ID 7"),p.replace("I/O handle 18","I/O handle 19"),
                p.replace("AUDIO_DEVICE_IN_BUILTIN_MIC","AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET"),p.replace("- handle: 91","- handle: 81"),
                p.replace("Voe volume dump:",CallDuplexFixtures.PATCH+"Voe volume dump:"),p.replace(CallDuplexFixtures.PATCH,"")}) {
            sample(CallDuplexFixtures.sample(CallDuplexFixtures.flinger(true,true),changed,CallDuplexFixtures.external(true,true,2),1000));assertNull(guard.current(in,out));}
    }
    @Test public void truncatedOrCrossWindowSourcesRevoke()throws Exception{
        ready();AudioCaptureObservation.Sample s=CallDuplexFixtures.sample(true,true,2,1000);
        sample(new AudioCaptureObservation.Sample(new AudioInputOwnership.Dump(s.flinger.text,false,1000,1010),s.policy,new JSONObject(s.externalJson),1021,1030));
        assertNull(guard.current(in,out));
        sample(new AudioCaptureObservation.Sample(new AudioInputOwnership.Dump(s.flinger.text,true,999,1010),s.policy,new JSONObject(s.externalJson),1021,1030));
        assertNull(guard.current(in,out));
    }
    @Test public void idleRequiresBothActuallyGoneAndRouteRequiresObservedFocus()throws Exception{
        bind();sample(CallDuplexFixtures.sample(false,false,0,1000));guard.requireIdle();
        try{guard.requireIdleSpeakerRoute();fail();}catch(IOException expected){}
        sample(CallDuplexFixtures.sample(false,false,2,1000));guard.requireIdleSpeakerRoute();
        sample(CallDuplexFixtures.sample(true,false,2,1000));
        try{guard.requireIdle();fail();}catch(IOException expected){}
        CallDuplexGuard.Evidence pending=guard.current(in,out);assertNotNull(guard.snapshot().toString(),pending);assertTrue(pending.inputOwned);assertFalse(pending.outputOwned);
    }
    @Test public void closeCannotBeUndoneByInflightSample()throws Exception{
        ready();CachedCallDuplexGuard.Generation token=guard.generation();guard.close();sampleWith(token);assertNull(guard.current(in,out));
    }
    @Test public void truncatedGlobalStreamsCannotHideOtherPlaybackCoverage()throws Exception{
        bind();JSONObject external=CallDuplexFixtures.external(true,true,2);
        org.json.JSONArray streams=external.getJSONObject("audio").getJSONArray("streams");
        while(streams.length()>4)streams.remove(streams.length()-1);
        sample(CallDuplexFixtures.sample(CallDuplexFixtures.flinger(true,true),CallDuplexFixtures.policy(true,true),external,1000));
        assertNull(guard.current(in,out));
    }
}
