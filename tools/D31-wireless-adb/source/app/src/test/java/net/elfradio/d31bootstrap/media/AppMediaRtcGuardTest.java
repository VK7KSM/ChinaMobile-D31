package net.elfradio.d31bootstrap.media;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
import static net.elfradio.d31bootstrap.media.AudioCaptureLifecycleTest.*;
import static net.elfradio.d31bootstrap.media.AudioCaptureObservationTest.*;

public class AppMediaRtcGuardTest {
    @Test public void captured138RateMismatchAndInputOnlyCorrection()throws Exception {
        String capture=System.getProperty("d31.mic138.diagnostics");
        org.junit.Assume.assumeNotNull(capture);
        java.util.List<java.nio.file.Path> files=new java.util.ArrayList<>();
        try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(java.nio.file.Paths.get(capture))){
            paths.filter(p->p.getFileName().toString().matches("sample-[12]\\.json")).forEach(files::add);
        }
        assertEquals(2,files.size());
        for(java.nio.file.Path file:files){
            JSONObject raw=new JSONObject(new String(java.nio.file.Files.readAllBytes(file),java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(raw.getBoolean("sample_returned"));assertEquals("",raw.getString("read_error"));
            JSONObject f=raw.getJSONObject("flinger"),p=raw.getJSONObject("policy");
            long now=raw.getLong("elapsed_ms");int pid=raw.getInt("pid"),session=raw.getInt("audio_session");
            AudioCaptureObservation.Sample original=capturedSample(raw,f.getString("text"),p.getString("text"));
            AppMediaRtcGuard.Observation rejected=AppMediaRtcGuard.evaluate(original,pid,session,now);
            assertEquals("INPUT_CONFIGURATION_UNVERIFIED",rejected.inputReason);assertFalse(rejected.ready);
            String flinger=replaceInputRate(f.getString("text"),"Input thread ","usb_hw version");
            String policy=replaceInputRate(p.getString("text"),"Inputs dump:","Streams dump:");
            AudioCaptureObservation.Sample corrected=capturedSample(raw,flinger,policy);
            assertTrue(AppMediaRtcGuard.evaluate(corrected,pid,session,now).ready);
            assertTrue(AppMediaRtcGuard.evaluate(corrected,pid+1,session,now).busy);
            assertTrue(AppMediaRtcGuard.evaluate(corrected,pid,session+1,now).busy);
            assertFalse(AppMediaRtcGuard.evaluate(capturedSample(raw,flinger,p.getString("text")),pid,session,now).ready);
            assertFalse(AppMediaRtcGuard.evaluate(corrected,pid,session,now+5000).ready);
            AudioCaptureObservation.Sample incomplete=new AudioCaptureObservation.Sample(
                    new AudioInputOwnership.Dump(flinger,false,corrected.flinger.startedElapsedMs,corrected.flinger.finishedElapsedMs),
                    corrected.policy,new JSONObject(raw.getString("external_raw")),corrected.began,corrected.finished);
            assertFalse(AppMediaRtcGuard.evaluate(incomplete,pid,session,now).ready);
        }
    }
    private static AudioCaptureObservation.Sample capturedSample(JSONObject raw,String flinger,String policy)throws Exception {
        JSONObject f=raw.getJSONObject("flinger"),p=raw.getJSONObject("policy");
        return new AudioCaptureObservation.Sample(
                new AudioInputOwnership.Dump(flinger,f.getBoolean("complete"),f.getLong("started_elapsed_ms"),f.getLong("finished_elapsed_ms")),
                new AudioInputOwnership.Dump(policy,p.getBoolean("complete"),p.getLong("started_elapsed_ms"),p.getLong("finished_elapsed_ms")),
                new JSONObject(raw.getString("external_raw")),raw.getLong("external_started_elapsed_ms"),raw.getLong("external_finished_elapsed_ms"));
    }
    private static String replaceInputRate(String text,String from,String to){
        int begin=text.indexOf(from),end=text.indexOf(to,begin);assertTrue(begin>=0&&end>begin);
        String input=text.substring(begin,end);assertTrue(input.contains("48000"));
        return text.substring(0,begin)+input.replace("48000","16000")+text.substring(end);
    }
    @Test public void actualOwnInputOnlyAndFreshEvidencePermitAudio()throws Exception {
        AudioCaptureObservation.Sample sample=sample(F,P,ownRaw(),1000);
        AppMediaRtcGuard.Observation value=AppMediaRtcGuard.evaluate(sample,555,42,1040);
        assertTrue(value.ready);assertFalse(value.busy);assertTrue(value.fresh(1040));assertFalse(value.fresh(5001));
        assertFalse(value.fresh(999));
    }
    @Test public void otherPidSessionAndCallsVetoOwnExemption()throws Exception {
        AudioCaptureObservation.Sample sample=sample(F,P,ownRaw(),1000);
        assertTrue(AppMediaRtcGuard.evaluate(sample,556,42,1040).busy);
        assertTrue(AppMediaRtcGuard.evaluate(sample,555,43,1040).busy);
        JSONObject raw=ownRaw();raw.getJSONObject("cellular").put("call_state",2);
        assertFalse(AppMediaRtcGuard.evaluate(sample(F,P,raw,1000),555,42,1040).ready);
        assertTrue(AppMediaRtcGuard.evaluate(sample(F,P,raw,1000),555,42,1040).busy);
    }
    @Test public void missingMismatchedOrOverwideEvidenceNeverUnmutes()throws Exception {
        assertFalse(AppMediaRtcGuard.evaluate(null,555,42,1040).ready);
        assertFalse(AppMediaRtcGuard.evaluate(sample(F,P.replace("Input 18","Input 19"),ownRaw(),1000),555,42,1040).ready);
        AudioCaptureObservation.Sample pair=sample(F,P,ownRaw(),1000);
        AudioCaptureObservation.Sample wide=new AudioCaptureObservation.Sample(pair.flinger,pair.policy,ownRaw(),2500,2540);
        assertFalse(AppMediaRtcGuard.evaluate(wide,555,42,2540).ready);
    }
    @Test public void emptyInputIsNotCaptureSuccess()throws Exception {
        assertFalse(AppMediaRtcGuard.evaluate(sample(AudioInputOwnershipTest.FLINGER,AudioInputOwnershipTest.POLICY,
                AndroidAudioOccupancyTest.idle(),1000),555,42,1040).ready);
    }
    @Test public void pinnedWebRtcDependencyHasActualRecorderField()throws Exception {
        Class<?> module=Class.forName("org.webrtc.audio.JavaAudioDeviceModule",false,getClass().getClassLoader());
        Class<?> input=module.getField("audioInput").getType();
        assertEquals("android.media.AudioRecord",input.getDeclaredField("audioRecord").getType().getName());
    }
}
