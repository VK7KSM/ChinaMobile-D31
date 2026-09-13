package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 仅合成身份和双源原件；不连接设备，不保存声音。 */
public class PttPersistentOutputGuardTest {
    final CallDuplexFixtures.Time clock=new CallDuplexFixtures.Time();
    final PttOutputGuard guard=new PttOutputGuard(D31OutputFixtures.PID,clock);
    interface Checked { void run()throws Exception; }
    static void denied(Checked action)throws Exception {
        try{action.run();fail("必须拒绝");}catch(IOException expected){}
    }
    long muted()throws Exception {
        guard.actualOutput(D31OutputFixtures.identity());guard.focusOwned(true);
        long epoch=guard.outputMuted(true);guard.confirmMuted(epoch);return epoch;
    }
    void observe(boolean input,boolean output,int focus)throws Exception {
        guard.sample(CallDuplexFixtures.sample(input,output,focus,1000),"");
    }
    @Test public void mutedOwnOutputWithConnectionFocusIsOperationIdleButNeverReleased()throws Exception {
        muted();observe(false,true,2);guard.requireOperationIdle();
        denied(guard::requireIdle);guard.requireOwnedOutput();
        assertTrue(guard.snapshot().getBoolean("self_output_verified"));
    }
    @Test public void emptyOutputStillNeedsNoFocusAndCompleteExternalIdle()throws Exception {
        observe(false,false,0);denied(guard::requireOperationIdle);guard.requireIdle();
        observe(false,false,2);denied(guard::requireOperationIdle);
        guard.focusOwned(true);guard.requireIdle();denied(guard::requireOperationIdle);
    }
    @Test public void muteRequestAloneOrUnmuteNeverAuthorizeQuietOutput()throws Exception {
        guard.actualOutput(D31OutputFixtures.identity());guard.focusOwned(true);long epoch=guard.outputMuted(true);
        observe(false,true,2);denied(guard::requireOperationIdle);
        guard.confirmMuted(epoch);guard.requireOperationIdle();
        guard.outputMuted(false);guard.confirmMuted(epoch);observe(false,true,2);denied(guard::requireOperationIdle);
    }
    @Test public void repeatedSameClockMuteCannotReuseOldCallbackOrOldRead()throws Exception {
        long old=muted();observe(false,true,2);guard.requireOperationIdle();
        long fresh=guard.outputMuted(true);guard.confirmMuted(old);
        observe(false,true,2);denied(guard::requireOperationIdle);
        guard.confirmMuted(fresh);guard.requireOperationIdle();
        guard.sample(CallDuplexFixtures.sample(false,true,2,1000),"",guard.focusEpoch(),old);
        denied(guard::requireOperationIdle);
    }
    @Test public void initialIdentityCannotRetroactivelyConfirmAnEarlierMuteEpoch()throws Exception {
        long old=guard.outputMuted(true);long oldRead=guard.muteEpoch();
        guard.actualOutput(D31OutputFixtures.identity());guard.focusOwned(true);guard.confirmMuted(old);
        guard.sample(CallDuplexFixtures.sample(false,true,2,1000),"",guard.focusEpoch(),oldRead);
        denied(guard::requireOperationIdle);
        long now=guard.outputMuted(true);guard.confirmMuted(now);observe(false,true,2);guard.requireOperationIdle();
    }
    @Test public void duplicateIdentityDoesNotInvalidateCurrentConfirmation()throws Exception {
        muted();observe(false,true,2);guard.actualOutput(D31OutputFixtures.identity());guard.requireOperationIdle();
        denied(()->guard.actualOutput(D31OutputFixtures.identity().put("audio_session",7199)));
    }
    @Test public void focusReleaseNeedsAReadFromTheNewEpoch()throws Exception {
        muted();guard.focusOwned(true);observe(false,true,2);guard.requireOwnedOutput();
        guard.requireOperationIdle();long oldFocus=guard.focusEpoch();
        guard.focusOwned(false);
        guard.sample(CallDuplexFixtures.sample(false,true,2,1000),"",oldFocus,guard.muteEpoch());
        denied(guard::requireOperationIdle);
        observe(false,true,2);denied(guard::requireOperationIdle);
        observe(false,true,0);denied(guard::requireOperationIdle);
        guard.focusOwned(true);observe(false,true,2);guard.requireOperationIdle();
    }
    @Test public void staleRollbackFailedAndRevokedReadsStayRejected()throws Exception {
        muted();observe(false,true,2);
        clock.now=5001;denied(guard::requireOperationIdle);clock.now=999;denied(guard::requireOperationIdle);
        clock.now=1040;long oldFocus=guard.focusEpoch();guard.revokeSample("MEDIA_OUTPUT_REFRESH_TIMEOUT");
        denied(guard::requireOperationIdle);
        guard.sample(CallDuplexFixtures.sample(false,true,2,1000),"",oldFocus,guard.muteEpoch());
        denied(guard::requireOperationIdle);
        observe(false,true,2);guard.requireOperationIdle();
    }
    @Test public void phoneSipOtherStreamsSourcesAndRemoteOutputAreNotExempted()throws Exception {
        muted();for(int fault=0;fault<7;fault++){
            JSONObject e=CallDuplexFixtures.external(false,true,2),a=e.getJSONObject("audio");
            switch(fault){
                case 0:e.getJSONObject("cellular").put("call_state",1);break;
                case 1:e.getJSONObject("nexui").getJSONArray("statuses").put(0,"CONNECTED");break;
                case 2:a.getJSONArray("streams").getJSONObject(4).put("active",true);break;
                case 3:a.getJSONArray("sources").getJSONObject(7).put("active",true);break;
                case 4:a.getJSONArray("streams").getJSONObject(3).put("remote_active",true);break;
                case 5:a.put("focus_gain",1);break;
                case 6:a.put("mode",3);break;
                default:throw new AssertionError();
            }
            String raw=e.toString();guard.sample(CallDuplexFixtures.sample(CallDuplexFixtures.flinger(false,true),
                    CallDuplexFixtures.policy(false,true),e,1000),"");
            denied(guard::requireOperationIdle);assertEquals(raw,e.toString());
        }
    }
    @Test public void wrongOwnerSessionOrPolicyCannotBecomeMutedSelf()throws Exception {
        muted();String f=D31OutputFixtures.flinger(true),p=D31OutputFixtures.policy(true);
        for(String wrong:new String[]{f.replace("yes 41001","yes 41002"),f.replace("00000001 7101","00000001 7199"),
                f.replace("4 Tracks of which 1","4 Tracks of which 2")}){
            guard.sample(CallDuplexFixtures.sample(wrong,p,CallDuplexFixtures.external(false,true,2),1000),"");
            denied(guard::requireOperationIdle);
        }
        guard.sample(CallDuplexFixtures.sample(f,p.replace("03 -20.000 01 00","03 -20.000 00 00"),
                CallDuplexFixtures.external(false,true,2),1000),"");denied(guard::requireOperationIdle);
    }
    @Test public void startingAndMissingIdentityCannotBecomeOperationIdle()throws Exception {
        long epoch=guard.outputMuted(true);guard.confirmMuted(epoch);observe(false,true,2);denied(guard::requireOperationIdle);
        muted();String f=D31OutputFixtures.flinger(true).replaceFirst("Standby: no","Standby: yes").replace("4800 A 3","4104 R 1");
        guard.sample(CallDuplexFixtures.sample(f,D31OutputFixtures.policy(true),CallDuplexFixtures.external(false,true,2),1000),"");
        denied(guard::requireOperationIdle);
    }
    @Test public void activeCallIsNotPttIdleButExistingDuplexProofStillAcceptsIt()throws Exception {
        muted();guard.focusOwned(true);observe(true,true,2);denied(guard::requireOperationIdle);denied(guard::requireOwnedOutput);
        CachedCallDuplexGuard duplex=new CachedCallDuplexGuard(D31OutputFixtures.PID,clock);
        CallDuplexGuard.Identity in=CallDuplexFixtures.input(),out=CallDuplexFixtures.output();
        duplex.bind(in,out);duplex.focusOwned(true);
        duplex.sample(CallDuplexFixtures.sample(true,true,2,1000),true,1000,1040,duplex.generation());
        CallDuplexGuard.Evidence proof=duplex.current(in,out);
        assertNotNull(proof);assertTrue(proof.inputOwned);assertTrue(proof.outputOwned);
    }
    @Test public void everyModeCanReturnToQuietBoundaryWithoutClaimingHardwareReleased()throws Exception {
        muted();for(String mode:new String[]{"ptt","call","microphone","video","photo","alarm"}){
            observe(false,true,2);guard.requireOperationIdle();
            if("call".equals(mode)||"microphone".equals(mode)||"video".equals(mode)){
                observe(true,true,"call".equals(mode)?2:0);denied(guard::requireOperationIdle);
            }
            long next=guard.outputMuted(true);guard.confirmMuted(next);
            observe(false,true,2);guard.requireOperationIdle();denied(guard::requireIdle);
        }
        observe(false,false,0);guard.requireIdle();
    }
    @Test public void activeDuplexMayCheckCallsWithoutApplyingPttInputIdleGate()throws Exception {
        muted();observe(true,true,2);guard.requireNoCalls();denied(guard::requireOperationIdle);
        guard.outputMuted(false);observe(true,true,2);guard.requireNoCalls();
    }
    @Test public void callOnlyCheckRejectsBusyUnknownAndStaleRaw()throws Exception {
        muted();for(int fault=0;fault<4;fault++){
            JSONObject raw=CallDuplexFixtures.external(true,true,2);
            if(fault==0)raw.getJSONObject("cellular").put("call_state",1);
            if(fault==1)raw.getJSONObject("nexui").getJSONArray("statuses").put(0,"CONNECTED");
            if(fault==2)raw.remove("cellular");
            if(fault==3)raw.getJSONObject("nexui").put("resolved",false);
            guard.sample(CallDuplexFixtures.sample(CallDuplexFixtures.flinger(true,true),CallDuplexFixtures.policy(true,true),raw,1000),"");
            denied(guard::requireNoCalls);
        }
        observe(true,true,2);guard.requireNoCalls();clock.now=5001;denied(guard::requireNoCalls);
    }
    @Test public void callOnlyCheckCannotReuseReadBeforeFocusLossOrFailedRead()throws Exception {
        muted();observe(true,true,2);guard.requireNoCalls();long epoch=guard.focusEpoch();
        guard.focusOwned(false);guard.focusOwned(true);
        guard.sample(CallDuplexFixtures.sample(true,true,2,1000),"",epoch,guard.muteEpoch());denied(guard::requireNoCalls);
        observe(true,true,2);guard.requireNoCalls();guard.revokeSample("MEDIA_OUTPUT_REFRESH_TIMEOUT");denied(guard::requireNoCalls);
    }
}
