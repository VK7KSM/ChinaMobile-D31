package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AppCallEvidenceTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    static final String HASH=String.join("",java.util.Collections.nCopies(64,"a"));
    static JSONObject media()throws Exception {
        AppMediaPcmStats input=new AppMediaPcmStats(16000),output=new AppMediaPcmStats(48000);
        input.accept(new byte[]{0,64,0,-64},2,1,16000,true);
        output.accept(new byte[]{0,32,0,-32},2,1,48000,true);
        return new JSONObject().put("peer",new JSONObject().put("capture_pcm",input.snapshot())
                .put("playback_pcm",output.snapshot()).put("unmuted",true))
                .put("duplex",new JSONObject().put("fresh",true)).put("cleanup_complete",true);
    }
    static JSONObject decision(boolean valid)throws Exception {
        return new JSONObject().put("fresh",valid).put("same_generation",valid).put("input_owned",valid).put("output_owned",valid)
                .put("focus_owned_at_start",true).put("speaker_before",true).put("speaker_after",true)
                .put("round_started_elapsed_ms",1000).put("round_finished_elapsed_ms",1040).put("read_error","");
    }
    static CachedCallDuplexGuard.Generation generation()throws Exception {
        return new CachedCallDuplexGuard.Generation(CallDuplexFixtures.input(),CallDuplexFixtures.output(),true,false);
    }
    static JSONObject read(File file)throws Exception{return new JSONObject(new String(Files.readAllBytes(file.toPath()),"UTF-8"));}
    @Test public void bothEnergySidesAreNumbersAndPrivateFieldsAreNotForwarded()throws Exception {
        JSONObject input=media().put("token","private-token").put("pid",12345).put("raw_hex","private-bytes");
        input.getJSONObject("peer").put("actual_input_identity",new JSONObject().put("audio_session",12345))
                .put("error","MEDIA_PRIVATE_TOKEN").getJSONObject("capture_pcm").put("audio",new byte[]{1,2}).put("rms","private-rms");
        JSONObject clean=AppCallEvidence.publicView(input);
        String text=clean.toString();assertFalse(text.contains("private"));assertFalse(text.contains("12345"));
        assertFalse(clean.getJSONObject("peer").getJSONObject("capture_pcm").has("rms"));
        assertEquals(0.5,clean.getJSONObject("peer").getJSONObject("capture_pcm").getDouble("peak"),0.00001);
        assertEquals(0.25,clean.getJSONObject("peer").getJSONObject("playback_pcm").getDouble("rms"),0.00001);
        assertEquals("MEDIA_CALL_DIAGNOSTIC_ERROR_REDACTED",clean.getJSONObject("peer").getString("error"));
    }
    @Test public void preservesExactRoundAndPrivateIdentitiesWithThreeRawSlots()throws Exception {
        File files=temporary.newFolder();AtomicReference<JSONObject> last=new AtomicReference<>();
        AppCallEvidence evidence=new AppCallEvidence(files,HASH,new CallDuplexFixtures.Time(),AppCallEvidenceTest::media,last::set,"round-test");
        AudioCaptureObservation.Sample original=CallDuplexFixtures.sample(true,true,2,1000);
        for(int i=0;i<5;i++)evidence.observed(original,generation(),decision(i<3),null);
        evidence.finish(media(),2500);
        File directory=new File(files,"media/call-duplex-diagnostics/round-test");
        assertEquals(3,directory.listFiles().length);
        for(File file:directory.listFiles()) {
            JSONObject saved=read(file),detail=saved.getJSONObject("decision");
            assertEquals(original.flinger.text,saved.getJSONObject("flinger").getString("text"));
            assertEquals(original.policy.text,saved.getJSONObject("policy").getString("text"));
            assertEquals(original.externalJson,saved.getString("external_raw"));
            assertEquals(CallDuplexFixtures.IN,detail.getJSONObject("actual_input_identity").getInt("audio_session"));
            assertEquals(7101,detail.getJSONObject("actual_output_identity").getInt("audio_session"));
            assertEquals(1000,detail.getLong("round_started_elapsed_ms"));assertFalse(saved.getBoolean("contains_audio"));
            assertTrue(detail.getBoolean("focus_owned_at_start"));
        }
        assertEquals("FIRST_REJECTED_ROUND",read(new File(directory,"sample-3.json")).getJSONObject("decision").getString("phase"));
        assertFalse(last.get().toString().contains("actual_input_identity"));
        assertTrue(last.get().getBoolean("evidence_writer_exit_verified"));
        JSONObject terminal=read(new File(files,"media/call-duplex-statistics/round-test/final.json"));
        assertFalse(terminal.getBoolean("cleanup_complete"));assertTrue(terminal.getBoolean("media_resources_cleanup_complete"));
    }
    @Test public void missingSampleKeepsReadFailureAndPartialTraceOnlyInPrivateArtifact()throws Exception {
        File files=temporary.newFolder();AppCallEvidence evidence=new AppCallEvidence(files,HASH,new CallDuplexFixtures.Time(),AppCallEvidenceTest::media,null,"partial-test");
        JSONObject failure=decision(false).put("read_error","MEDIA_CALL_OBSERVATION_READ_FAILED");
        evidence.observed(null,generation(),failure,new JSONObject().put("stage","SYNTHETIC_FAILURE").put("raw_hex","010203"));
        evidence.finish(media(),2000);
        JSONObject raw=read(new File(files,"media/call-duplex-diagnostics/partial-test/sample-1.json"));
        assertFalse(raw.getBoolean("sample_returned"));assertEquals("010203",raw.getJSONObject("read_trace").getString("raw_hex"));
        assertEquals("MEDIA_CALL_OBSERVATION_READ_FAILED",raw.getString("read_error"));
        assertFalse(read(new File(files,"media/call-duplex-statistics/partial-test/final.json")).toString().contains("010203"));
    }
    @Test public void historicalDirectoriesAreNotOverwrittenAndStorageFailureDoesNotThrowFromObserve()throws Exception {
        File files=temporary.newFolder();File existing=new File(files,"media/call-duplex-statistics/retained");assertTrue(existing.mkdirs());
        File marker=new File(existing,"final.json");Files.write(marker.toPath(),"immutable".getBytes("UTF-8"));
        AppCallEvidence evidence=new AppCallEvidence(files,HASH,new CallDuplexFixtures.Time(),AppCallEvidenceTest::media,null,"retained");
        evidence.observed(null,generation(),decision(false),null);evidence.finish(media(),2000);
        assertEquals("immutable",new String(Files.readAllBytes(marker.toPath()),"UTF-8"));
        assertEquals("MEDIA_CALL_EVIDENCE_WRITE_UNCONFIRMED",evidence.storage().getString("evidence_error"));
    }
    @Test public void fullStorageIsBoundedAndNeverDeletesEarlierSessions()throws Exception {
        File files=temporary.newFolder();
        for(String kind:new String[]{"call-duplex-statistics","call-duplex-diagnostics"})for(int i=0;i<4;i++)
            assertTrue(new File(files,"media/"+kind+"/old-"+i).mkdirs());
        AppCallEvidence evidence=new AppCallEvidence(files,HASH,new CallDuplexFixtures.Time(),AppCallEvidenceTest::media,null,"new-test");
        evidence.observed(null,generation(),decision(false),null);evidence.finish(media(),2000);
        assertEquals(4,new File(files,"media/call-duplex-statistics").listFiles().length);
        assertEquals(4,new File(files,"media/call-duplex-diagnostics").listFiles().length);
        assertEquals("MEDIA_DIAGNOSTIC_STORAGE_LIMIT",evidence.storage().getString("storage_error"));
    }
    @Test public void blockedWriterDoesNotBlockObserverAndCloseUsesFiniteBudget()throws Exception {
        File files=temporary.newFolder();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AppCallEvidence evidence=new AppCallEvidence(files,HASH,new CallDuplexFixtures.Time(),AppCallEvidenceTest::media,value->{
            entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}
        },"blocked-test");
        evidence.observed(null,generation(),decision(false),null);assertTrue(entered.await(2,TimeUnit.SECONDS));
        long began=System.nanoTime();
        for(int i=0;i<1000;i++)evidence.observed(null,generation(),decision(true),null);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)<1500);
        try {evidence.finish(media(),50);fail();}catch(java.io.IOException expected){
            assertEquals("MEDIA_CALL_EVIDENCE_RELEASE_UNCONFIRMED",expected.getMessage());
        }finally{release.countDown();}
        assertTrue(evidence.storage().getInt("statistics_skipped")>0);
        // 本测试刻意阻塞发布回调；释放后确认本次创建的线程真实退出。
        AppCallSessionTest.until(()->Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&"d31-call-evidence".equals(t.getName())));
        try{evidence.finish(media(),2000);fail("超时终态不得因晚到线程退出升级");}
        catch(java.io.IOException expected){assertEquals("MEDIA_CALL_EVIDENCE_RELEASE_UNCONFIRMED",expected.getMessage());}
    }
    @Test public void constructingEvidenceCreatesNoFilesOrThreadsAndCloseBeforeSampleSavesOnlyFinalStats()throws Exception {
        File files=temporary.newFolder();AppCallEvidence evidence=new AppCallEvidence(files,HASH,new CallDuplexFixtures.Time(),AppCallEvidenceTest::media,null,"lazy-test");
        assertEquals(0,files.listFiles().length);assertFalse(evidence.rawSaved());
        evidence.finish(media(),2000);evidence.finish(media(),2000);
        assertFalse(new File(files,"media/call-duplex-diagnostics").exists());
        assertEquals(1,new File(files,"media/call-duplex-statistics/lazy-test").listFiles().length);
    }
    @Test public void statisticsLimitKeepsTerminalSlotAndPublicUpdatesAfterSaturation()throws Exception {
        File files=temporary.newFolder();AtomicReference<JSONObject> last=new AtomicReference<>();
        AppCallEvidence evidence=new AppCallEvidence(files,HASH,new CallDuplexFixtures.Time(),AppCallEvidenceTest::media,last::set,"limit-test");
        try {
            for(int i=0;i<AppCallEvidence.MAX_STATS;i++){
                final int target=i+1;
                evidence.observed(null,generation(),decision(true),null);
                AppCallSessionTest.until(()->evidence.storage().getInt("statistics_saved")==target);
                AtomicBoolean pending=(AtomicBoolean)AppCallSessionTest.field(evidence,"statsPending");
                AppCallSessionTest.until(()->!pending.get());
            }
            evidence.observed(null,generation(),decision(true).put("round_started_elapsed_ms",1234),null);
            assertEquals(1234,last.get().getJSONObject("round").getLong("round_started_elapsed_ms"));
        }finally{evidence.finish(media(),2000);}
        assertEquals(AppCallEvidence.MAX_STATS+1,new File(files,"media/call-duplex-statistics/limit-test").listFiles().length);
    }
    @Test public void realFullQueueRejectsTerminalSubmissionButStillShutsDownAndDrains()throws Exception {
        File files=temporary.newFolder();CountDownLatch publishing=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicBoolean first=new AtomicBoolean(true);AtomicReference<JSONObject> publicState=new AtomicReference<>();
        AppCallEvidence evidence=new AppCallEvidence(files,HASH,new CallDuplexFixtures.Time(),AppCallEvidenceTest::media,value->{
            publicState.set(value);
            if(first.compareAndSet(true,false)){publishing.countDown();
                while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}
            }
        },"full-queue-test");
        ThreadPoolExecutor writer=(ThreadPoolExecutor)AppCallSessionTest.field(evidence,"writer");
        ExecutorService closer=Executors.newSingleThreadExecutor();
        try {
            CachedCallDuplexGuard.Generation idle=new CachedCallDuplexGuard.Generation(null,null,false,false);
            evidence.observed(null,idle,decision(true).put("input_owned",false).put("output_owned",false),null);
            assertTrue(publishing.await(2,TimeUnit.SECONDS));
            assertFalse(((AtomicBoolean)AppCallSessionTest.field(evidence,"statsPending")).get());
            AudioCaptureObservation.Sample sample=CallDuplexFixtures.sample(true,true,2,1000);
            evidence.observed(sample,generation(),decision(true),null);
            evidence.observed(sample,generation(),decision(true),null);
            evidence.observed(sample,generation(),decision(false),null);
            assertEquals(4,writer.getQueue().size());assertEquals(0,writer.getQueue().remainingCapacity());
            Future<?> closing=closer.submit(()->{try{evidence.finish(media(),2000);}catch(Exception failure){throw new RuntimeException(failure);}});
            AppCallSessionTest.until(writer::isShutdown);
            assertFalse(closing.isDone());release.countDown();closing.get(2500,TimeUnit.MILLISECONDS);
            assertTrue(writer.isTerminated());assertFalse(evidence.storage().getBoolean("final_saved"));
            assertEquals("MEDIA_CALL_EVIDENCE_FINAL_WRITE_UNCONFIRMED",evidence.storage().getString("evidence_error"));
            assertEquals(3,evidence.storage().getInt("saved_samples"));
            assertEquals(0,evidence.storage().getInt("raw_unconfirmed"));
            assertFalse(new File(files,"media/call-duplex-statistics/full-queue-test/final.json").exists());
            assertTrue(publicState.get().getBoolean("evidence_writer_exit_verified"));
        }finally{release.countDown();writer.shutdownNow();assertTrue(writer.awaitTermination(2,TimeUnit.SECONDS));closer.shutdownNow();}
    }
    @Test public void failedRawSaveRemainsPublicAfterLaterRawSaveSucceeds()throws Exception {
        File files=temporary.newFolder();AtomicReference<JSONObject> last=new AtomicReference<>();
        AppCallEvidence evidence=new AppCallEvidence(files,HASH,new CallDuplexFixtures.Time(),AppCallEvidenceTest::media,last::set,"raw-failure-test");
        JSONObject hugePartial=new JSONObject().put("synthetic_trace",String.join("",java.util.Collections.nCopies(AppMediaRtcDiagnostics.MAX_BYTES,"x")));
        evidence.observed(null,generation(),decision(false),hugePartial);
        AppCallSessionTest.until(()->evidence.storage().getInt("raw_failures")==1);
        evidence.observed(CallDuplexFixtures.sample(true,true,2,1000),generation(),decision(true),null);
        evidence.finish(media(),2500);
        JSONObject storage=AppCallEvidence.publicView(last.get()).getJSONObject("storage");
        assertEquals(1,storage.getInt("saved_samples"));assertEquals(1,storage.getInt("raw_failures"));
        assertEquals(2,storage.getInt("raw_requested"));assertEquals(0,storage.getInt("raw_unconfirmed"));
        assertEquals("MEDIA_DIAGNOSTIC_SIZE_LIMIT",storage.getString("storage_error"));
        assertTrue(storage.getBoolean("final_saved"));
    }
}
