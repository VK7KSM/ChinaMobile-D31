package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AppCallEvidenceWiringTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void realSessionExposesSanitizedDiagnosticsWithoutAuthorizingAudio()throws Exception {
        AppCallSessionTest.Time time=new AppCallSessionTest.Time();AppCallSessionTest.Wire wire=new AppCallSessionTest.Wire();
        AppCallSessionTest.Ops ops=new AppCallSessionTest.Ops();
        AppCallSession session=new AppCallSession(temporary.newFolder(),AppCallSessionTest.offer(time,"call"),time,wire,(sender,signals)->{
            ops.sender=sender;ops.signals=signals;return ops;
        });
        try{session.start();assertTrue(wire.entered.await(2,TimeUnit.SECONDS));wire.emit(AppCallSessionTest.hello());
            assertTrue(ops.openEntered.await(2,TimeUnit.SECONDS));
            JSONObject supplied=AppCallEvidenceTest.media().put("private_identity","private-value");
            ops.signals.diagnostics(supplied);
            JSONObject view=session.snapshot().getJSONObject("media_diagnostics");
            assertEquals(0.5,view.getJSONObject("peer").getJSONObject("capture_pcm").getDouble("rms"),0.0001);
            assertEquals(0.25,view.getJSONObject("peer").getJSONObject("playback_pcm").getDouble("rms"),0.0001);
            supplied.getJSONObject("peer").getJSONObject("capture_pcm").put("rms",0);
            assertFalse(view.toString().contains("private"));assertEquals(0,ops.unmutes.get());assertFalse(session.snapshot().getBoolean("ready"));
            assertEquals(0.5,session.snapshot().getJSONObject("media_diagnostics").getJSONObject("peer").getJSONObject("capture_pcm").getDouble("rms"),0.0001);
        }finally{session.stop();assertTrue(session.awaitClosed(3000));}
    }
    @Test public void operationsFinalizesEvidenceAfterExistingReleaseAndPreservesReleaseFailure()throws Exception {
        for(boolean failPeer:new boolean[]{false,true}){
            AndroidCallOperationsTest.Rig rig=new AndroidCallOperationsTest.Rig();File files=temporary.newFolder();
            AppCallEvidence evidence=new AppCallEvidence(files,AppCallEvidenceTest.HASH,rig.time,rig.operations::snapshot,null,"ops-test");
            AppCallSessionTest.set(rig.operations,"evidence",evidence);rig.open();rig.failPeer=failPeer;
            if(failPeer)rig.expectCloseFailure();else rig.operations.close();
            JSONObject result=AppCallEvidenceTest.read(new File(files,"media/call-duplex-statistics/ops-test/final.json"));
            assertEquals(!failPeer,result.getBoolean("media_resources_cleanup_complete"));
            assertTrue(rig.events.contains("observer-close"));assertEquals(failPeer,rig.journal);
        }
    }
}
