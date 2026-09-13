package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 按150已观察状态构造脱敏夹具，不读取原件或工作区属性。 */
public class Ptt150OutputTest {
    static String active(){return D31OutputFixtures.flinger(true).replace("4800 A 3","4104 A 3").replace("0x000 0","0x001 1026");}
    static String starting(){return D31OutputFixtures.flinger(true).replaceFirst("Standby: no","Standby: yes").replace("4800 A 3","4104 R 1");}
    static D31OutputEvidence.Result evaluate(String f,String p)throws Exception{
        return D31OutputEvidence.evaluate(D31OutputFixtures.dump(f),D31OutputFixtures.dump(p),D31OutputIdentity.from(D31OutputFixtures.identity()),1040);
    }
    static void rejected(String f,String p)throws Exception{
        D31OutputEvidence.State state=evaluate(f,p).state;
        assertNotEquals(D31OutputEvidence.State.SELF_ONLY,state);assertNotEquals(D31OutputEvidence.State.STARTING,state);
    }
    static AudioCaptureObservation.Sample sample(String f,JSONObject e){return new AudioCaptureObservation.Sample(
            D31OutputFixtures.dump(f),D31OutputFixtures.dump(D31OutputFixtures.policy(true)),e,1000,1020);}
    static JSONObject external()throws Exception{
        JSONObject e=D31OutputFixtures.idle(2);e.getJSONObject("audio").getJSONArray("streams").getJSONObject(3).put("active",true);return e;
    }
    static PttOutputGuard guard(D31OutputFixtures.Time clock)throws Exception{
        clock.now=1040;PttOutputGuard guard=new PttOutputGuard(D31OutputFixtures.PID,clock);
        guard.actualOutput(D31OutputFixtures.identity());guard.focusOwned(true);return guard;
    }
    @Test public void underrunNotificationDoesNotInvalidateActualOwnership()throws Exception{
        assertEquals(D31OutputEvidence.State.SELF_ONLY,evaluate(active(),D31OutputFixtures.policy(true)).state);
        assertEquals(D31OutputEvidence.State.SELF_ONLY,evaluate(active().replace("0x001 1026","0x000 1026"),D31OutputFixtures.policy(true)).state);
    }
    @Test public void everyUnverifiedFlagAndCombinationRemainsRejected()throws Exception{
        for(String flag:new String[]{"0x002","0x003","0x004","0x005","0x008","0x009","0x100","0x200","0x400","0xfff","1"})
            rejected(active().replace("0x001 1026",flag+" 1026"),D31OutputFixtures.policy(true));
    }
    @Test public void resumingFillingIsPendingNeverIdleOrAuthorization()throws Exception{
        assertEquals(D31OutputEvidence.State.STARTING,evaluate(starting(),D31OutputFixtures.policy(true)).state);
        PttOutputGuard guard=guard(new D31OutputFixtures.Time());guard.sample(sample(starting(),external()),"");
        assertEquals(0,guard.outputProof());assertFalse(guard.snapshot().getBoolean("self_output_verified"));
        try{guard.requireOwnedOutput();fail();}catch(IOException expected){assertEquals("MEDIA_OUTPUT_STARTING",expected.getMessage());}
        try{guard.requireIdle();fail();}catch(IOException expected){}
    }
    @Test public void newActiveSampleCanCompletePendingProof()throws Exception{
        PttOutputGuard guard=guard(new D31OutputFixtures.Time());guard.sample(sample(starting(),external()),"");assertEquals(0,guard.outputProof());
        guard.sample(sample(active(),external()),"");assertEquals(1,guard.outputProof());guard.requireOwnedOutput();
    }
    @Test public void startingAndUnderrunCannotExemptOtherIdentityOrInconsistentPolicy()throws Exception{
        for(String f:new String[]{starting(),active()}){String p=D31OutputFixtures.policy(true);
        for(String changed:new String[]{f.replace("3 yes 41001","3 yes 41002"),f.replace("00000001 7101","00000001 7199"),
                f.replace("41001 3","41001 4"),f.replace("48000","16000"),f.replace("3 yes 41001 3 00000001","3 yes 41001 3 00000003")})rejected(changed,p);
        for(String changed:new String[]{p.replace("03 -20.000 01 00","03 -20.000 00 00"),p.replace("03 -20.000 01 00","03 -20.000 02 00"),
                p.replace("03 -20.000 01 00","03 -20.000 01 01"),p.replace("04 -20.000 00 00","04 -20.000 01 00"),p.replace("I/O handle 2","I/O handle 4")})rejected(f,changed);
        }
    }
    @Test public void startingStillRequiresCompleteGlobalReferencesAndSingleActiveTrack()throws Exception{
        String f=starting(),p=D31OutputFixtures.policy(true);
        rejected(f.replace("7101 41001 1","7101 41002 1"),p);
        rejected(f.replace("4 Tracks of which 1","4 Tracks of which 2"),p);
        assertNotEquals(D31OutputEvidence.State.STARTING,D31OutputEvidence.evaluate(
                D31OutputFixtures.dump(f),D31OutputFixtures.dump(p),D31OutputFixtures.PID,1040).state);
    }
    @Test public void unknownStatesFillingAndPendingConfigNeverBecomeStarting()throws Exception{
        String f=starting(),p=D31OutputFixtures.policy(true);
        for(String pair:new String[]{"A 1","R 3","S 1","P 1","R 2"})rejected(f.replace("R 1",pair),p);
        rejected(f.replaceFirst("Standby: yes","Standby: no"),p);
        rejected(f.replace("0x000 0","0x001 0"),p);
        rejected(f.replaceFirst("Pending config events: none","Pending config events:\n    KeyValue: FM_DIRECT_CONTROL=1"),p);
    }
    @Test public void startingDoesNotHidePhoneInputOtherStreamOrFocusLoss()throws Exception{
        for(int kind=0;kind<5;kind++){
            PttOutputGuard guard=guard(new D31OutputFixtures.Time());JSONObject e=external();
            if(kind==0)e.getJSONObject("cellular").put("call_state",1);
            if(kind==1)e.getJSONObject("audio").getJSONArray("sources").getJSONObject(1).put("active",true);
            if(kind==2)e.getJSONObject("audio").getJSONArray("streams").getJSONObject(4).put("active",true);
            if(kind==3)e.getJSONObject("audio").put("focus_gain",0);
            guard.sample(sample(starting(),e),"");if(kind==4)guard.focusOwned(false);
            try{guard.outputProof();fail("未拒绝外部接管");}catch(IOException expected){}
        }
    }
    @Test public void staleStartingObservationCannotKeepWaiting()throws Exception{
        D31OutputFixtures.Time clock=new D31OutputFixtures.Time();PttOutputGuard guard=guard(clock);
        guard.sample(sample(starting(),external()),"");clock.now=6000;
        try{guard.outputProof();fail();}catch(IOException expected){assertEquals("MEDIA_OUTPUT_SAMPLE_STALE",expected.getMessage());}
    }
    @Test public void repeatedStartingProofDoesNotExtendOriginalConnectionDeadline()throws Exception{
        D31OutputFixtures.Time clock=new D31OutputFixtures.Time();PttOutputGuard guard=guard(clock);
        PttProtocol p=new PttProtocol(clock,2000);
        p.receive(new JSONObject("{type:'hello',mode:'ptt'}"));assertEquals("OPEN",p.poll().kind);p.opened();
        int create=p.poll().body.getInt("id");
        p.receive(new JSONObject("{type:'tracks',sessionId:'synthetic',tracks:[{location:'remote',sessionId:'synthetic',trackName:'audio'}]}"));
        p.receive(new JSONObject().put("type","rpc").put("id",create).put("result",new JSONObject()));
        int subscribe=p.poll().body.getInt("id");
        p.receive(new JSONObject().put("type","rpc").put("id",subscribe).put("result",new JSONObject()));
        assertEquals("APPLY_SUBSCRIBE",p.poll().kind);p.answerCreated(new JSONObject());int answer=p.poll().body.getInt("id");
        p.receive(new JSONObject().put("type","rpc").put("id",answer).put("result",new JSONObject()));
        assertEquals("ANSWER_ACK",p.poll().kind);p.ice(true);assertEquals("PREPARE_MUTED",p.poll().kind);
        for(long now:new long[]{1040,1400,1999}){
            clock.now=now;guard.sample(sample(starting(),external()),"");assertEquals(0,guard.outputProof());
            p.playbackFrames();p.tick();assertNull(p.poll());assertFalse(p.snapshot().getBoolean("ready"));
        }
        clock.now=2000;p.tick();assertEquals("CLOSE",p.poll().kind);
        assertEquals("MEDIA_SESSION_TIMEOUT",p.snapshot().getString("reason"));
    }
}
