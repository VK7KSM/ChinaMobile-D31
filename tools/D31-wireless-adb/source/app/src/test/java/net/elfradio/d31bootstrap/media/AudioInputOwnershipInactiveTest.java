package net.elfradio.d31bootstrap.media;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 154完整原件脱敏回归及单字段故障注入；不接触设备或网络。 */
public final class AudioInputOwnershipInactiveTest {
    private static JSONObject fixture() throws Exception {
        try (InputStream in = AudioInputOwnershipInactiveTest.class.getResourceAsStream("/media/call154-inactive-record.json")) {
            assertNotNull(in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    private static AudioInputOwnership.Dump dump(String value, long begin, long end) {
        return new AudioInputOwnership.Dump(value, true, begin, end);
    }
    private static AudioInputOwnership.Result evaluate(String f, String p) {
        return AudioInputOwnership.evaluate(dump(f, 1000, 1010), dump(p, 1011, 1020), 555, 42, 1040);
    }
    private static String change(String value, String before, String after) {
        assertTrue("故障注入必须命中原件", value.contains(before));
        return value.replace(before, after);
    }
    private static String inputChange(String f, String before, String after) {
        int start = f.indexOf("Input thread "), end = f.indexOf("usb_hw", start);
        return f.substring(0, start) + change(f.substring(start, end), before, after) + f.substring(end);
    }
    private static void rejected(String f, String p) {
        AudioInputOwnership.Result r = evaluate(f, p);
        assertEquals(r.reason, AudioInputOwnership.State.UNKNOWN, r.state);
        assertFalse(r.selfExemptionAllowed);
    }
    private static CachedCallDuplexGuard guard(String f, String p, JSONObject external, boolean focus) throws Exception {
        CachedCallDuplexGuard g = new CachedCallDuplexGuard(555, new MediaCapture.Clock() {
            public long wall() { return 1040; }
            public long elapsed() { return 1040; }
        });
        g.focusOwned(focus);
        g.sample(new AudioCaptureObservation.Sample(dump(f,1000,1010), dump(p,1011,1020), external,1021,1030),
                true,1000,1040,g.generation());
        return g;
    }
    @Test public void completeSanitizedOriginalProvesNoActiveInputButNeverReleaseOrSelf() throws Exception {
        JSONObject v=fixture();String f=v.getString("flinger"),p=v.getString("policy");
        AudioInputOwnership.Result r=evaluate(f,p);
        assertEquals(r.reason,AudioInputOwnership.State.NO_ACTIVE_INPUT,r.state);
        assertEquals("INACTIVE_INPUT_CONFIRMED_NOT_RELEASED",r.reason);
        assertEquals(0,r.activeInputs);assertFalse(r.selfExemptionAllowed);assertFalse(r.atomicReservation);
        assertEquals(D31OutputEvidence.State.EMPTY,D31OutputEvidence.evaluate(dump(f,1000,1010),dump(p,1011,1020),555,1040).state);
        CachedCallDuplexGuard g=guard(f,p,v.getJSONObject("external"),true);
        g.requireIdle();g.requireIdleSpeakerRoute();assertNull(g.current(null,null));
        assertFalse(g.snapshot().getBoolean("input_owned"));assertFalse(g.snapshot().getBoolean("output_owned"));
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("call154.raw",args[0]);
        new AudioInputOwnershipInactiveTest().actualPrivateOriginalReplaysWithoutTextChangesWhenProvided();
        System.out.println("154私有完整原件回放通过：无活动输入，代次保留，联合空闲门通过，未授予自身归属。");
    }
    private void actualPrivateOriginalReplaysWithoutTextChangesWhenProvided() throws Exception {
        String path=System.getProperty("call154.raw");
        if(path==null)throw new IllegalArgumentException("缺少明确的原件路径");
        byte[] bytes=Files.readAllBytes(Paths.get(path));
        JSONObject raw=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        JSONObject f=raw.getJSONObject("flinger"),p=raw.getJSONObject("policy"),d=raw.getJSONObject("decision");
        final long now=d.getLong("round_finished_elapsed_ms");
        AudioInputOwnership.Dump fd=dump(f.getString("text"),f.getLong("started_elapsed_ms"),f.getLong("finished_elapsed_ms"));
        AudioInputOwnership.Dump pd=dump(p.getString("text"),p.getLong("started_elapsed_ms"),p.getLong("finished_elapsed_ms"));
        assertEquals(AudioInputOwnership.State.NO_ACTIVE_INPUT,AudioInputOwnership.evaluate(fd,pd,555,42,now).state);
        CachedCallDuplexGuard g=new CachedCallDuplexGuard(555,new MediaCapture.Clock(){public long wall(){return 0;}public long elapsed(){return now;}});
        g.focusOwned(true);CachedCallDuplexGuard.Generation token=g.generation();
        g.sample(new AudioCaptureObservation.Sample(fd,pd,new JSONObject(raw.getString("external_raw")),
                raw.getLong("external_started_elapsed_ms"),raw.getLong("external_finished_elapsed_ms")),
                true,d.getLong("round_started_elapsed_ms"),now,token);
        assertSame(token,g.generation());g.requireIdleSpeakerRoute();assertNull(g.current(null,null));
        assertArrayEquals(bytes,Files.readAllBytes(Paths.get(path)));
    }
    @Test public void standbySourceActiveCountAndRowMustAgree() throws Exception {
        JSONObject v=fixture();String f=v.getString("flinger"),p=v.getString("policy");
        for(String[] fault:new String[][]{{"Standby: yes","Standby: no"},{"0 (default)","1 (mic)"},
                {"0 (default)","7 (voice_communication)"},{"1 Tracks of which 0","1 Tracks of which 1"},
                {"1 Tracks of which 0","2 Tracks of which 0"},{"No active record clients","No inactive record clients"},
                {"no  555","yes 555"},{"42 0 00000000","42 6 00000000"},{"00000000   2048","00000001   2048"},
                {"00000000   2048","00000000   1024"}}) rejected(inputChange(f,fault[0],fault[1]),p);
    }
    @Test public void eachInactivePreambleFieldIsRequired() throws Exception {
        JSONObject v=fixture();String f=v.getString("flinger"),p=v.getString("policy");
        int begin=f.indexOf("Input thread "),end=f.indexOf("1 Tracks of which",begin);
        for(String line:f.substring(begin,end).split("\n"))if(!line.trim().isEmpty()) {
            rejected(inputChange(f,line+"\n",""),p);
        }
    }
    @Test public void formatBuffersRouteAndPendingConfigurationMustBeExact() throws Exception {
        JSONObject v=fixture();String f=v.getString("flinger"),p=v.getString("policy");
        for(String[] fault:new String[][]{{"Sample rate: 16000","Sample rate: 48000"},{"mInput name: primary","mInput name: remote"},
                {"AudioIn_14","AudioIn_15"},{"HAL frame count: 320","HAL frame count: 640"},
                {"HAL buffer size: 640 bytes","HAL buffer size: 1280 bytes"},{"HAL format: 0x1","HAL format: 0x3"},
                {"Channel count: 1","Channel count: 2"},{"Frame size: 2 bytes","Frame size: 4 bytes"},
                {"Pending config events: none","Pending config events: active"},{"0x2 (SPEAKER)","0 (NONE)"},
                {"BUILTIN_MIC","BLUETOOTH_SCO"},{"Fast capture thread: no","Fast capture thread: yes"},
                {"Fast track available: no","Fast track available: yes"}}) rejected(inputChange(f,fault[0],fault[1]),p);
    }
    @Test public void policyReferenceOpenReferenceAndHandleMustMatch() throws Exception {
        JSONObject v=fixture();String f=v.getString("flinger"),p=v.getString("policy");
        for(String[] fault:new String[][]{{"\n Ref Count 0\n","\n Ref Count 1\n"},{"Open Ref Count 1","Open Ref Count 0"},
                {"Open Ref Count 1","Open Ref Count 2"},{"Ref Count 0","Ref Count -1"},
                {"Input 20 dump","Input 21 dump"},{"Sampling rate: 16000","Sampling rate: 8000"},
                {"Devices 80000004","Devices 80000010"},{"Channels: 00000010","Channels: 00000003"}})
            rejected(f,change(p,fault[0],fault[1]));
    }
    @Test public void singleSidedMissingOrDuplicateInputsReject() throws Exception {
        JSONObject v=fixture();String f=v.getString("flinger"),p=v.getString("policy");
        int begin=f.indexOf("Input thread "),end=f.indexOf("usb_hw",begin);
        String block=f.substring(begin,end);
        rejected(f.substring(0,begin)+f.substring(end),p);
        rejected(f.substring(0,begin)+block+f.substring(begin),p);
        int pb=p.indexOf("- Input 20 dump:"),pe=p.indexOf("Streams dump:",pb);
        rejected(f,p.substring(0,pb)+p.substring(pe));
        rejected(f,p.substring(0,pb)+p.substring(pb,pe)+p.substring(pb));
    }
    @Test public void referencesAndInputPatchCannotBeHiddenByInactiveRow() throws Exception {
        JSONObject v=fixture();String f=v.getString("flinger"),p=v.getString("policy");
        rejected(change(f,"Hardware status: 0"," 42 555 1\nHardware status: 0"),p);
        rejected(f,change(p,"Voe volume dump:","  - Device ID 9 AUDIO_DEVICE_IN_BUILTIN_MIC\nVoe volume dump:"));
    }
    @Test public void everyExternalSourceStreamAndRemoteActivityStillRejects() throws Exception {
        JSONObject v=fixture();String f=v.getString("flinger"),p=v.getString("policy");
        for(int i=0;i<9;i++) {
            JSONObject e=new JSONObject(v.getJSONObject("external").toString());
            e.getJSONObject("audio").getJSONArray("sources").getJSONObject(i).put("active",true);
            assertFalse(guard(f,p,e,true).snapshot().getBoolean("fresh"));
        }
        for(int i=0;i<10;i++)for(String flag:new String[]{"active","remote_active"}) {
            JSONObject e=new JSONObject(v.getJSONObject("external").toString());
            e.getJSONObject("audio").getJSONArray("streams").getJSONObject(i).put(flag,true);
            assertFalse(guard(f,p,e,true).snapshot().getBoolean("fresh"));
        }
    }
    @Test public void externalPhoneNexuiModeUnknownAndForeignFocusStillReject() throws Exception {
        JSONObject v=fixture();String f=v.getString("flinger"),p=v.getString("policy");
        for(int fault=0;fault<5;fault++) {
            JSONObject e=new JSONObject(v.getJSONObject("external").toString());
            switch(fault){case 0:e.getJSONObject("cellular").put("call_state",1);break;
                case 1:e.getJSONObject("nexui").getJSONArray("statuses").put(0,"CONNECTED");break;
                case 2:e.getJSONObject("audio").put("mode",3);break;
                case 3:e.getJSONObject("audio").put("service_error_type","unknown");break;
                case 4:e.getJSONObject("audio").getJSONArray("sources").remove(8);break;}
            assertFalse(guard(f,p,e,true).snapshot().getBoolean("fresh"));
        }
        assertFalse(guard(f,p,v.getJSONObject("external"),false).snapshot().getBoolean("fresh"));
    }
    @Test public void missingTruncatedAndStaleDumpsRemainUnknown() throws Exception {
        JSONObject v=fixture();String f=v.getString("flinger"),p=v.getString("policy");
        rejected(f.substring(0,f.indexOf(" route[9]")),p);
        rejected(f,p.substring(0,p.indexOf("Voe volume dump:")));
        assertEquals(AudioInputOwnership.State.UNKNOWN,AudioInputOwnership.evaluate(
                new AudioInputOwnership.Dump(f,false,1000,1010),dump(p,1011,1020),555,42,1040).state);
        assertEquals(AudioInputOwnership.State.UNKNOWN,AudioInputOwnership.evaluate(dump(f,1000,1010),dump(p,1011,1020),555,42,5001).state);
    }
    @Test public void extraColumnsRowsAndControlCharactersNeverBecomeInactive() throws Exception {
        JSONObject v=fixture();String f=v.getString("flinger"),p=v.getString("policy");
        for(String[] fault:new String[][]{{"Active Client Fmt","Active UID Fmt"},{"2048 16000","2048 16000 extra"},
                {"No active record clients","No active record clients\n  Vendor: inactive"},{"no  555","no  \u000b555"}})
            rejected(inputChange(f,fault[0],fault[1]),p);
    }
    @Test public void pttStillRequiresIndependentExternalIdleAndNeverGrantsOutputOwnership() throws Exception {
        JSONObject v=fixture();String f=v.getString("flinger"),p=v.getString("policy");
        MediaCapture.Clock time=new MediaCapture.Clock(){public long wall(){return 1040;}public long elapsed(){return 1040;}};
        PttOutputGuard g=new PttOutputGuard(555,time);g.focusOwned(true);
        JSONObject e=v.getJSONObject("external");
        AudioCaptureObservation.Sample s=new AudioCaptureObservation.Sample(dump(f,1000,1010),dump(p,1011,1020),e,1021,1030);
        g.sample(s,"");g.requireIdle();assertFalse(g.snapshot().getBoolean("self_output_verified"));
        e.getJSONObject("audio").getJSONArray("sources").getJSONObject(1).put("active",true);
        g.sample(new AudioCaptureObservation.Sample(s.flinger,s.policy,e,1021,1030),"");
        try{g.requireIdle();fail();}catch(java.io.IOException expected){}
    }
}
