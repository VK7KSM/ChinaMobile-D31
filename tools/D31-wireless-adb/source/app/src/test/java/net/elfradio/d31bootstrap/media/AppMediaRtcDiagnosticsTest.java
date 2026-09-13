package net.elfradio.d31bootstrap.media;

import java.io.*;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static net.elfradio.d31bootstrap.media.AudioCaptureLifecycleTest.*;
import static net.elfradio.d31bootstrap.media.AudioCaptureObservationTest.*;

public class AppMediaRtcDiagnosticsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    static final String HASH="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    @Test public void preservesReturnedRawSampleAndBoundedPrivateMetadata()throws Exception {
        File root=temp.newFolder();AppMediaRtcDiagnostics d=new AppMediaRtcDiagnostics(root,"session-1",HASH);
        AudioCaptureObservation.Sample source=sample(F,P,ownRaw(),1000);
        d.save(source,"",555,42,1040,AppMediaRtcGuard.evaluate(source,555,42,1040).summary());
        JSONObject raw=new JSONObject(new String(Files.readAllBytes(new File(root,"session-1/sample-1.json").toPath()),"UTF-8"));
        assertEquals(F,raw.getJSONObject("flinger").getString("text"));assertEquals(P,raw.getJSONObject("policy").getString("text"));
        assertFalse(raw.getBoolean("contains_audio"));assertTrue(raw.getBoolean("sample_returned"));assertEquals(42,raw.getInt("audio_session"));
        assertEquals(1,d.snapshot().getInt("saved_samples"));
    }
    @Test public void absentReadIsExplicitNotInventedEmptyDump()throws Exception {
        File root=temp.newFolder();AppMediaRtcDiagnostics d=new AppMediaRtcDiagnostics(root,"session-2",HASH);
        d.save(null,"MEDIA_INPUT_DUMP_TIMEOUT",555,42,1040,new JSONObject());
        JSONObject raw=new JSONObject(new String(Files.readAllBytes(new File(root,"session-2/sample-1.json").toPath()),"UTF-8"));
        assertFalse(raw.getBoolean("sample_returned"));assertFalse(raw.has("flinger"));assertEquals("MEDIA_INPUT_DUMP_TIMEOUT",raw.getString("read_error"));
    }
    @Test public void sampleAndDirectoryLimitsNeverOverwriteEvidence()throws Exception {
        File root=temp.newFolder();
        for(int i=0;i<5;i++){
            AppMediaRtcDiagnostics d=new AppMediaRtcDiagnostics(root,"session-"+i,HASH);
            for(int n=0;n<10;n++)d.save(null,"",555,42,n,new JSONObject());
            assertEquals(i<4?3:0,d.snapshot().getInt("saved_samples"));
        }
        assertEquals(4,root.list().length);
        File original=new File(root,"session-0/sample-1.json");byte[] before=Files.readAllBytes(original.toPath());
        AppMediaRtcDiagnostics retry=new AppMediaRtcDiagnostics(root,"session-0",HASH);retry.save(null,"",555,42,9999,new JSONObject());
        assertArrayEquals(before,Files.readAllBytes(original.toPath()));
    }
}
