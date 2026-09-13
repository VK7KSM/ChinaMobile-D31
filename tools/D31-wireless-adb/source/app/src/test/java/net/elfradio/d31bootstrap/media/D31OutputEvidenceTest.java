package net.elfradio.d31bootstrap.media;

import org.junit.Test;
import static org.junit.Assert.*;

/** 使用合成D31转储；真实捕获回放仅由独立宿主工具执行。 */
public class D31OutputEvidenceTest {
    static AudioInputOwnership.Dump dump(String value){return D31OutputFixtures.dump(value);}
    static D31OutputEvidence.Result evaluate(String f,String p){
        return D31OutputEvidence.evaluate(dump(f),dump(p),D31OutputFixtures.PID,1040);
    }
    @Test public void syntheticCompleteIdlePairWithNoClientTracksIsAccepted(){
        String f=D31OutputFixtures.flinger(false).replaceAll(
                "(?m)^  [0-9]+ Tracks of which 0 are active\\n    Name[^\\n]*\\n(?:    [0-9]+ no[^\\n]*\\n)+","  0 Tracks\n");
        assertTrue(f.contains("0 Tracks\n"));assertFalse(f.contains("Tracks of which"));
        assertEquals(D31OutputEvidence.State.EMPTY,evaluate(f,D31OutputFixtures.policy(false)).state);
    }
    @Test public void syntheticEndedSessionKeepsInactiveClientRowsWithoutExemption(){
        String f=D31OutputFixtures.flinger(false);
        assertTrue(f.contains("3 Tracks of which 0 are active"));
        assertTrue(f.contains("no 42001 3 00000001 00000001"));
        assertEquals(D31OutputEvidence.State.EMPTY,evaluate(f,D31OutputFixtures.policy(false)).state);
    }
    @Test public void activeCountMutationIsNeverOwnOutputProof(){
        String f=D31OutputFixtures.flinger(false).replaceFirst("3 Tracks of which 0 are active","3 Tracks of which 1 are active");
        assertTrue(f.contains("3 Tracks of which 1 are active"));
        assertEquals("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED",evaluate(f,D31OutputFixtures.policy(false)).reason);
    }
    @Test public void incompleteOrStalePairNeverPasses(){
        String f=D31OutputFixtures.flinger(false),p=D31OutputFixtures.policy(false);
        assertEquals(D31OutputEvidence.State.UNKNOWN,D31OutputEvidence.evaluate(
                new AudioInputOwnership.Dump(f,false,1000,1020),dump(p),D31OutputFixtures.PID,1040).state);
        assertEquals(D31OutputEvidence.State.UNKNOWN,D31OutputEvidence.evaluate(dump(f),dump(p),D31OutputFixtures.PID,6040).state);
    }
    @Test public void policyMismatchOrUnknownColumnFailsClosed(){
        String f=D31OutputFixtures.flinger(false),p=D31OutputFixtures.policy(false);
        assertEquals("MEDIA_OUTPUT_HANDLES_DISAGREE",evaluate(f,p.replace("- Output 2 dump:","- Output 12 dump:")).reason);
        assertEquals(D31OutputEvidence.State.UNKNOWN,evaluate(f,p.replace("Stream volume refCount muteCount","Stream volume owner muteCount")).state);
    }
    @Test public void syntheticFilteredActiveSummaryIsNotCompleteEvidence(){
        String summary="Output thread 0x10002000 type 0 (MIXER):\n  I/O handle: 2\n  3 Tracks of which 1 are active\n";
        assertEquals(D31OutputEvidence.State.UNKNOWN,evaluate(summary,D31OutputFixtures.policy(false)).state);
    }
    @Test public void fixedWebRtcLibraryExposesRealOutputIdentityFieldWithoutLoadingNative()throws Exception{
        Class<?> module=Class.forName("org.webrtc.audio.JavaAudioDeviceModule",false,getClass().getClassLoader());
        Class<?> output=module.getField("audioOutput").getType();
        assertEquals("android.media.AudioTrack",output.getDeclaredField("audioTrack").getType().getName());
    }
}
