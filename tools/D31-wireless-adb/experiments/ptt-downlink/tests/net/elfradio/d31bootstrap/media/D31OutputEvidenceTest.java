package net.elfradio.d31bootstrap.media;
import org.junit.Test;
import org.json.JSONObject;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class D31OutputEvidenceTest {
    static String path(String name)throws Exception{
        return new String(Files.readAllBytes(Paths.get(System.getProperty("d31.workspace"),"research/d31/captures/2026-09-12/parallel-batch7-audio-idle114",name)),StandardCharsets.UTF_8);
    }
    static AudioInputOwnership.Dump dump(String value){return new AudioInputOwnership.Dump(value,true,1000,1020);}
    static D31OutputEvidence.Result evaluate(String f,String p){return D31OutputEvidence.evaluate(dump(f),dump(p),1234,1040);}
    @Test public void actualD31CompleteIdlePairIsAccepted()throws Exception{
        assertEquals(D31OutputEvidence.State.EMPTY,evaluate(path("audio-flinger-private.txt"),path("audio-policy-private.txt")).state);
    }
    @Test public void real138EndedSessionKeepsInactiveClientRowsWithoutExemption()throws Exception{
        String raw=new String(Files.readAllBytes(Paths.get(System.getProperty("d31.workspace"),
                "research/d31/captures/2026-09-12/b13-business138-after-mic/audio-private.txt")),StandardCharsets.UTF_8);
        int f=raw.indexOf("Library pre_processing"),p=raw.indexOf("AudioPolicyManager:",f);
        assertTrue(f>=0&&p>f);String flinger=raw.substring(f,p),policy=raw.substring(p);
        assertTrue(flinger.contains("3 Tracks of which 0 are active"));
        // 只重建离线相对采样时刻，不冒充该历史文件当前仍新鲜。
        assertEquals(D31OutputEvidence.State.EMPTY,evaluate(flinger,policy).state);
    }
    @Test public void activeCountMutationIsNeverOwnOutputProof()throws Exception{
        String f=path("audio-flinger-private.txt").replaceFirst("0 Tracks","1 Tracks of which 1 are active");
        assertEquals("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED",evaluate(f,path("audio-policy-private.txt")).reason);
    }
    @Test public void incompleteOrStalePairNeverPasses()throws Exception{
        String f=path("audio-flinger-private.txt"),p=path("audio-policy-private.txt");
        assertEquals(D31OutputEvidence.State.UNKNOWN,D31OutputEvidence.evaluate(new AudioInputOwnership.Dump(f,false,1000,1020),dump(p),1234,1040).state);
        assertEquals(D31OutputEvidence.State.UNKNOWN,D31OutputEvidence.evaluate(dump(f),dump(p),1234,6040).state);
    }
    @Test public void policyMismatchOrUnknownColumnFailsClosed()throws Exception{
        String f=path("audio-flinger-private.txt"),p=path("audio-policy-private.txt");
        assertEquals("MEDIA_OUTPUT_HANDLES_DISAGREE",evaluate(f,p.replace("- Output 2 dump:","- Output 12 dump:")).reason);
        assertEquals(D31OutputEvidence.State.UNKNOWN,evaluate(f,p.replace("Stream volume refCount muteCount","Stream volume owner muteCount")).state);
    }
    @Test public void filteredHistoricalActiveSummaryIsNotCompleteEvidence()throws Exception{
        String f=new String(Files.readAllBytes(Paths.get(System.getProperty("d31.workspace"),"research/d31/captures/2026-08-30/20260830_000100-zello-channel-real-sample/03_audio_flinger_samples.txt")),StandardCharsets.UTF_8);
        assertTrue(f.contains("3 Tracks of which 1 are active"));
        assertEquals(D31OutputEvidence.State.UNKNOWN,evaluate(f,path("audio-policy-private.txt")).state);
    }
    @Test public void fixedWebRtcLibraryExposesRealOutputIdentityFieldWithoutLoadingNative()throws Exception{
        Class<?> module=Class.forName("org.webrtc.audio.JavaAudioDeviceModule",false,getClass().getClassLoader());
        Class<?> output=module.getField("audioOutput").getType();
        assertEquals("android.media.AudioTrack",output.getDeclaredField("audioTrack").getType().getName());
    }
}
