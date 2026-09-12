package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AppMediaReadTraceTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void completedDumpsSurviveExternalTimeoutWithoutAuthorizingCapture()throws Exception{
        AppMediaReadTrace trace=new AppMediaReadTrace();trace.reset();trace.stage("FLINGER",1000);
        trace.dump("media.audio_flinger",new byte[]{0,13,10,(byte)255},true,1000,1100);
        trace.stage("POLICY",1100);trace.dump("media.audio_policy",new byte[]{65,66},true,1100,1200);
        trace.stage("EXTERNAL",1200);trace.external(new JSONObject().put("observation","fixture"),1200,2600);trace.stage("DEADLINE",2600);
        assertFalse(AppMediaRtcGuard.evaluate(null,555,42,2600).ready);
        File root=temp.newFolder();AppMediaRtcDiagnostics d=new AppMediaRtcDiagnostics(root,"partial-1",AppMediaRtcDiagnosticsTest.HASH);
        d.save(null,"MEDIA_INPUT_SAMPLE_TIMEOUT",555,42,2600,new JSONObject(),trace.snapshot());
        JSONObject raw=new JSONObject(new String(Files.readAllBytes(new File(root,"partial-1/sample-1.json").toPath()),"UTF-8"));
        assertFalse(raw.getBoolean("sample_returned"));JSONObject saved=raw.getJSONObject("read_trace");
        assertEquals("DEADLINE",saved.getString("stage"));assertEquals("000d0aff",saved.getJSONObject("flinger").getString("raw_hex"));
        assertTrue(saved.getJSONObject("flinger").getBoolean("complete"));assertEquals("4142",saved.getJSONObject("policy").getString("raw_hex"));
        assertTrue(saved.getString("external_raw").contains("fixture"));
    }
    @Test public void incompletePipePrefixIsPreservedAndResetDoesNotReuseOldData()throws Exception{
        AppMediaReadTrace trace=new AppMediaReadTrace();trace.stage("POLICY",1100);
        trace.dump("media.audio_policy",new byte[]{1,2,3},false,1100,1700);
        assertFalse(trace.snapshot().getJSONObject("policy").getBoolean("complete"));
        assertEquals("010203",trace.snapshot().getJSONObject("policy").getString("raw_hex"));
        trace.reset();trace.stage("PREFLIGHT",2000);assertFalse(trace.snapshot().has("policy"));assertFalse(trace.snapshot().has("flinger"));
    }
}
