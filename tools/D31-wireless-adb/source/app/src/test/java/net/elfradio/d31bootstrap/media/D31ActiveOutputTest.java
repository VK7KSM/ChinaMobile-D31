package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class D31ActiveOutputTest {
    static D31OutputEvidence.Result evaluate(String f,String p,JSONObject identity)throws Exception{
        return D31OutputEvidence.evaluate(D31OutputFixtures.dump(f),D31OutputFixtures.dump(p),D31OutputIdentity.from(identity),1040);
    }
    static void denied(String f,String p)throws Exception{assertNotEquals(D31OutputEvidence.State.SELF_ONLY,evaluate(f,p,D31OutputFixtures.identity()).state);}
    @Test public void syntheticBeforeDuringAfterMatchesSeparateClientAndMixerFormats()throws Exception{
        for(boolean active:new boolean[]{false,true,false})assertEquals(active?D31OutputEvidence.State.SELF_ONLY:D31OutputEvidence.State.EMPTY,
                evaluate(D31OutputFixtures.flinger(active),D31OutputFixtures.policy(active),D31OutputFixtures.identity()).state);
    }
    @Test public void noTrustedIdentityKeepsLegacyActiveRejection(){
        assertEquals("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED",D31OutputEvidence.evaluate(D31OutputFixtures.dump(D31OutputFixtures.flinger(true)),
                D31OutputFixtures.dump(D31OutputFixtures.policy(true)),D31OutputFixtures.PID,1040).reason);
    }
    @Test public void releasedTrackCanBeEmptyBeforePrimaryEntersStandby()throws Exception{
        String f=D31OutputFixtures.flinger(false).replaceFirst("Standby: yes","Standby: no")
                .replaceFirst("Output device: 0 \\(NONE\\)","Output device: 0x2 (SPEAKER)");
        assertEquals(D31OutputEvidence.State.EMPTY,evaluate(f,D31OutputFixtures.policy(false),D31OutputFixtures.identity()).state);
        assertEquals(D31OutputEvidence.State.EMPTY,D31OutputEvidence.evaluate(D31OutputFixtures.dump(f),
                D31OutputFixtures.dump(D31OutputFixtures.policy(false)),D31OutputFixtures.PID,1040).state);
        assertEquals(D31OutputEvidence.State.UNKNOWN,evaluate(f,D31OutputFixtures.policy(true),D31OutputFixtures.identity()).state);
        denied(f.replaceFirst("0 are active","1 are active"),D31OutputFixtures.policy(false));
    }
    @Test public void releasedTrackWithPendingConfigRemainsUnknown()throws Exception{
        String f=D31OutputFixtures.flinger(false).replaceFirst("Standby: yes","Standby: no")
                .replaceFirst("Output device: 0 \\(NONE\\)","Output device: 0x2 (SPEAKER)")
                .replaceFirst("Pending config events: none","Pending config events:\n    KeyValue: FM_DIRECT_CONTROL=1");
        assertEquals("MEDIA_OUTPUT_CONFIG_PENDING",evaluate(f,D31OutputFixtures.policy(false),D31OutputFixtures.identity()).reason);
    }
    @Test public void everyIdentityDimensionMustMatch()throws Exception{
        String[] keys={"pid","audio_session","stream_type","sample_rate","channels","audio_format"};
        for(String key:keys){JSONObject id=D31OutputFixtures.identity();id.put(key,id.getInt(key)+1);
            assertNotEquals(key,D31OutputEvidence.State.SELF_ONLY,evaluate(D31OutputFixtures.flinger(true),D31OutputFixtures.policy(true),id).state);}
        for(String key:keys){JSONObject id=D31OutputFixtures.identity();id.put(key,String.valueOf(id.getInt(key)));
            try{D31OutputIdentity.from(id);fail(key);}catch(IOException expected){}}
    }
    @Test public void activeRowCannotForgePidSessionStreamRateOrPcmFormat()throws Exception{
        String row=D31OutputFixtures.activeRow(),f=D31OutputFixtures.flinger(true),p=D31OutputFixtures.policy(true);
        String[] changed={row.replace("41001","41002"),row.replace("7101","7105"),row.replace("41001 3","41001 4"),
                row.replace("48000","16000"),row.replaceFirst("00000001","00000003"),row.replace("00000001 7101","00000003 7101")};
        for(String replacement:changed)denied(f.replace(row,replacement),p);
    }
    @Test public void otherActivityAndDuplicateOwnActivityNeverPass()throws Exception{
        String f=D31OutputFixtures.flinger(true).replace("4 Tracks of which 1","5 Tracks of which 2"),row=D31OutputFixtures.activeRow();
        denied(f.replace(row,row+row.replace("3 yes","4 yes").replace("41001","41002")),D31OutputFixtures.policy(true));
        denied(f.replace(row,row+row.replace("3 yes","4 yes")),D31OutputFixtures.policy(true));
        denied(D31OutputFixtures.flinger(true).replace("4 Tracks of which 1","4 Tracks of which 0"),D31OutputFixtures.policy(true));
    }
    @Test public void globalSessionReferenceMustBindSameClientExactlyOnce()throws Exception{
        String f=D31OutputFixtures.flinger(true),ref="  7101 41001 1\n",p=D31OutputFixtures.policy(true);
        for(String replacement:new String[]{"",ref.replace("41001","41002"),ref.replace(" 1\n"," 0\n"),ref+ref})denied(f.replace(ref,replacement),p);
    }
    @Test public void policyCountsMutesAndOtherHandlesStayStrict()throws Exception{
        String f=D31OutputFixtures.flinger(true),p=D31OutputFixtures.policy(true);
        for(String change:new String[]{p.replace("03 -20.000 01 00","03 -20.000 00 00"),p.replace("03 -20.000 01 00","03 -20.000 02 00"),
                p.replace("03 -20.000 01 00","03 -20.000 01 01"),p.replace("04 -20.000 00 00","04 -20.000 01 00"),
                p.replace("03 -20.000 00 00","03 -20.000 01 00"),p.replace("- Output 4 dump:","- Output 8 dump:")})denied(f,change);
    }
    @Test public void mixerAndPolicyMustMatchObservedPcm32StereoSpeakerRoute()throws Exception{
        String f=D31OutputFixtures.flinger(true),p=D31OutputFixtures.policy(true);
        for(String change:new String[]{p.replace("Sampling rate: 48000","Sampling rate: 16000"),p.replace("Format: 00000003","Format: 00000001"),
                p.replace("Channels: 00000003","Channels: 00000001"),p.replace("Devices 00000002","Devices 00000001"),p.replace("Flags 00000002","Flags 00000004")})denied(f,change);
        for(String change:new String[]{f.replace("pcm32","pcm16"),f.replace("Channel count: 2","Channel count: 1"),
                f.replace("Output device: 0x2 (SPEAKER)","Output device: 0x1 (EARPIECE)"),f.replace("FastMixer not initialized","FastMixer active"),
                f.replace("mAFSuspend : 0","mAFSuspend : 1"),f.replace("0x000 0","0x004 0")})denied(change,p);
    }
    @Test public void patchSourceSinkPortAndMultiplicityMustMatch()throws Exception{
        String f=D31OutputFixtures.flinger(true),p=D31OutputFixtures.policy(true);
        for(String change:new String[]{p.replace("I/O handle 2","I/O handle 4"),p.replace("Mix ID 2","Mix ID 3"),
                p.replace("Device ID 1","Device ID 2"),p.replace("- Device ID 1 AUDIO_DEVICE_OUT_SPEAKER","- Device ID 1 AUDIO_DEVICE_OUT_EARPIECE"),
                p.replace("- 1 sinks:","- 2 sinks:"),p.replace("Audio Patches:\n","Audio Patches:\n  Audio patch 2:\n"),
                p.replace("- Mix ID 2 I/O handle 2\n","")})denied(f,change);
    }
    @Test public void staleIncompleteMalformedAndWideWindowsNeverAuthorize()throws Exception{
        String f=D31OutputFixtures.flinger(true),p=D31OutputFixtures.policy(true);D31OutputIdentity id=D31OutputIdentity.from(D31OutputFixtures.identity());
        assertNotEquals(D31OutputEvidence.State.SELF_ONLY,D31OutputEvidence.evaluate(new AudioInputOwnership.Dump(f,false,1000,1020),D31OutputFixtures.dump(p),id,1040).state);
        assertNotEquals(D31OutputEvidence.State.SELF_ONLY,D31OutputEvidence.evaluate(D31OutputFixtures.dump(f),D31OutputFixtures.dump(p),id,6000).state);
        assertNotEquals(D31OutputEvidence.State.SELF_ONLY,D31OutputEvidence.evaluate(D31OutputFixtures.dump(f),new AudioInputOwnership.Dump(p,true,3000,3020),id,3040).state);
        denied(f.replace("4800 A 3","4800 A"),p);denied(f,p.replace(" refCount muteCount"," refCount unknown"));
    }
    static PttOutputGuard guard(D31OutputFixtures.Time time)throws Exception{
        time.now=1040;PttOutputGuard guard=new PttOutputGuard(D31OutputFixtures.PID,time);guard.actualOutput(D31OutputFixtures.identity());guard.focusOwned(true);return guard;
    }
    static AudioCaptureObservation.Sample sample(JSONObject external){return new AudioCaptureObservation.Sample(
            D31OutputFixtures.dump(D31OutputFixtures.flinger(true)),D31OutputFixtures.dump(D31OutputFixtures.policy(true)),external,1000,1020);}
    static JSONObject external()throws Exception{
        JSONObject value=D31OutputFixtures.idle(2);value.getJSONObject("audio").getJSONArray("streams").getJSONObject(3).put("active",true);return value;
    }
    @Test public void guardNormalizesOnlyProvenOwnStreamAndPreservesOriginal()throws Exception{
        PttOutputGuard guard=guard(new D31OutputFixtures.Time());AudioCaptureObservation.Sample sample=sample(external());String raw=sample.externalJson;
        guard.sample(sample,"");guard.requireOwnedOutput();assertTrue(guard.snapshot().getBoolean("self_output_verified"));assertEquals(raw,sample.externalJson);
        try{guard.requireIdle();fail();}catch(IOException expected){}
        guard.focusOwned(false);try{guard.requireOwnedOutput();fail();}catch(IOException expected){}
    }
    @Test public void ownershipDoesNotExemptPhoneInputOtherStreamOrUnknownExternalState()throws Exception{
        PttOutputGuard guard=guard(new D31OutputFixtures.Time());
        for(int kind=0;kind<7;kind++){
            JSONObject e=external(),a=e.getJSONObject("audio");
            if(kind==0)e.getJSONObject("cellular").put("call_state",1);
            if(kind==1)e.getJSONObject("nexui").put("resolved",false);
            if(kind==2)a.getJSONArray("sources").getJSONObject(1).put("active",true);
            if(kind==3)a.getJSONArray("streams").getJSONObject(4).put("active",true);
            if(kind==4)a.getJSONArray("streams").getJSONObject(3).put("remote_active",true);
            if(kind==5)a.put("focus_gain",0);
            if(kind==6)a.getJSONArray("streams").getJSONObject(3).put("active",false);
            guard.sample(sample(e),"");try{guard.requireOwnedOutput();fail("未拒绝外部状态 "+kind);}catch(IOException expected){}
        }
    }
    @Test public void newIdentityNeedsNewSampleAndCannotChangeSession()throws Exception{
        D31OutputFixtures.Time time=new D31OutputFixtures.Time();time.now=1040;PttOutputGuard guard=new PttOutputGuard(D31OutputFixtures.PID,time);
        guard.focusOwned(true);guard.sample(sample(external()),"");guard.actualOutput(D31OutputFixtures.identity());assertFalse(guard.observedSince(1000));
        guard.sample(sample(external()),"");guard.requireOwnedOutput();guard.actualOutput(D31OutputFixtures.identity());guard.requireOwnedOutput();
        for(String key:new String[]{"pid","audio_session","stream_type","sample_rate","channels","audio_format"}){
            JSONObject id=D31OutputFixtures.identity();id.put(key,id.getInt(key)+1);try{guard.actualOutput(id);fail(key);}catch(IOException expected){}
        }
    }
}
