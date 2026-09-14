package net.elfradio.d31bootstrap;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RemoteVisualMediaReadinessTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final ManualExecutor executor=new ManualExecutor();
    private final FakeClock clock=new FakeClock();
    private final AtomicInteger wakes=new AtomicInteger();
    private final List<String> operations=new ArrayList<>(),hashes=new ArrayList<>();
    private RemoteVisualMedia media;
    private File apk;
    private int cameras=1,prepareCalls,failures;
    private boolean permission=true,idleQuery;
    private Runnable duringPrepare,duringStart,duringQuery;
    @Before public void setup()throws Exception {
        apk=temp.newFile("fixture.apk");try(FileOutputStream out=new FileOutputStream(apk)){out.write(new byte[]{1,2,3});}
    }
    private JSONObject bridge(JSONObject command)throws Exception {
        String op=command.getString("operation");operations.add(op);
        if("prepare".equals(op)) {
            prepareCalls++;hashes.add(command.getString("apk_sha256"));
            if(duringPrepare!=null)duringPrepare.run();
            if(failures>0){failures--;throw new IOException("offline");}
            return new JSONObject().put("state","prepared").put("managed_media_modes",new JSONArray(permission&&cameras>0?"['photo','alarm']":"['alarm']"))
                    .put("media_cameras",cameras).put("camera_permission",permission).put("sampled_at",clock.elapsed);
        }
        if("start".equals(op)){assertTrue(command.getString("apk_sha256").matches("[a-f0-9]{64}"));if(duringStart!=null)duringStart.run();return new JSONObject().put("state","running").put("session_id","fixture-session");}
        if("query".equals(op)){if(duringQuery!=null)duringQuery.run();return new JSONObject().put("state",idleQuery?"idle":"running")
                .put("cleanup_complete",idleQuery).put("session_id","fixture-session");}
        if("stop".equals(op))return new JSONObject().put("state","idle").put("cleanup_complete",true);
        throw new AssertionError(op);
    }
    private void create(){media=new RemoteVisualMedia(this::bridge,apk,wakes::incrementAndGet,clock,executor);}
    private void advance(long delta){clock.elapsed+=delta;media.tick();executor.runAll();}
    private void accept() {
        try{media.accept(new JSONObject().put("mode","photo").put("session_id","fixture-session").put("camera","front")
                .put("token","fixture-session-token-123456")
                .put("url","wss://v.elfradio.net/api/elfremote/media/device?session_id=fixture-session")
                .put("expires_at",clock.wall()+40000),new JSONObject().put("device_id","fixture-device").put("token","fixture-token"));
        }catch(Exception failure){throw new AssertionError(failure);}
    }
    @After public void close(){if(media!=null)media.close();executor.runAll();}
    @Test public void startupRemainsAsynchronousAndZeroBecomesOneAfterBackoff()throws Exception {
        cameras=0;create();assertEquals(0,prepareCalls);assertEquals("preparing",media.snapshot().getString("state"));
        executor.runAll();assertEquals(0,media.snapshot().getInt("media_cameras"));
        cameras=1;advance(29999);assertEquals(1,prepareCalls);advance(1);
        assertEquals(2,prepareCalls);assertEquals(1,media.snapshot().getInt("media_cameras"));assertEquals(2,wakes.get());
    }
    @Test public void positiveCapabilityIsPolledAtMostEveryFiveMinutes()throws Exception {
        create();executor.runAll();cameras=0;advance(299999);assertEquals(1,prepareCalls);advance(1);
        assertEquals(0,media.snapshot().getInt("media_cameras"));assertEquals("[\"alarm\"]",media.snapshot().getJSONArray("managed_media_modes").toString());
    }
    @Test public void permissionRevocationRemovesPhotoEvenWithPositiveCameraCount()throws Exception {
        create();executor.runAll();permission=false;advance(300000);
        assertFalse(media.snapshot().getBoolean("camera_permission"));assertEquals(1,media.snapshot().getInt("media_cameras"));
        assertEquals("[\"alarm\"]",media.snapshot().getJSONArray("managed_media_modes").toString());
        permission=true;advance(30000);assertTrue(media.snapshot().getBoolean("camera_permission"));
    }
    @Test public void firstFailureAndZeroResponsesBackOffWithFiveMinuteCeiling()throws Exception {
        failures=1;cameras=0;create();executor.runAll();assertEquals("VISUAL_PREPARE_FAILED",media.snapshot().getString("error"));
        for(long delay:new long[]{30000,60000,120000,240000,300000,300000}){
            int count=prepareCalls;advance(delay-1);assertEquals(count,prepareCalls);advance(1);assertEquals(count+1,prepareCalls);
        }
        cameras=1;advance(300000);cameras=0;advance(300000);
        int before=prepareCalls;advance(30000);assertEquals(before+1,prepareCalls);
    }
    @Test public void successfulUnchangedCapabilityDoesNotWakeAndHashIsCached()throws Exception {
        create();executor.runAll();int before=wakes.get();assertTrue(apk.delete());
        advance(300000);advance(300000);
        assertEquals(3,prepareCalls);assertEquals(before,wakes.get());assertEquals(1,new HashSet<>(hashes).size());
    }
    @Test public void repeatedFailureDoesNotWakeRepeatedly()throws Exception {
        failures=3;create();executor.runAll();int before=wakes.get();advance(30000);advance(60000);
        assertEquals(before,wakes.get());assertEquals("unconfirmed",media.snapshot().getString("state"));
    }
    @Test public void activeSessionQueriesNeverScheduleReadinessOrOverwriteSession()throws Exception {
        create();executor.runAll();accept();media.tick();assertEquals(1,executor.size());executor.runAll();
        cameras=0;advance(300000);assertEquals(1,prepareCalls);assertEquals("running",media.snapshot().getString("state"));
        assertEquals("fixture-session",media.snapshot().getString("session_id"));
        idleQuery=true;advance(2000);assertFalse(media.active());media.tick();executor.runAll();
        assertEquals(2,prepareCalls);assertEquals(0,media.snapshot().getInt("media_cameras"));
    }
    @Test public void sessionAcceptedDuringPrepareDiscardsLateReadinessAndStartsWithHash()throws Exception {
        cameras=0;create();duringPrepare=this::accept;executor.runOne();
        assertTrue(media.active());assertEquals("preparing",media.snapshot().getString("state"));assertEquals(0,wakes.get());
        media.tick();assertEquals(1,executor.size());executor.runAll();assertEquals("running",media.snapshot().getString("state"));
        assertEquals(Arrays.asList("prepare","start"),operations);
    }
    @Test public void sessionAcceptedBeforeInitialWorkerSkipsPrepareButStillHashesStart()throws Exception {
        create();accept();executor.runAll();assertEquals(Arrays.asList("start"),operations);
        assertTrue(media.active());assertEquals("running",media.snapshot().getString("state"));
    }
    @Test public void closeDuringPrepareDropsLateReplyWithoutWakeOrReschedule()throws Exception {
        create();String before=media.snapshot().toString();duringPrepare=media::close;executor.runAll();
        assertEquals(before,media.snapshot().toString());assertEquals(0,wakes.get());advance(900000);
        assertEquals(1,prepareCalls);assertTrue(executor.isShutdown());assertEquals(0,executor.size());
    }
    @Test public void closeDuringSessionStartOrQueryCannotPublishLateReply()throws Exception {
        create();executor.runAll();accept();duringStart=media::close;String before=media.snapshot().toString();executor.runAll();
        assertEquals(before,media.snapshot().toString());assertEquals(Arrays.asList("prepare","start","stop"),operations);
    }
    @Test public void closeDuringQueryKeepsSnapshotAndStopsOwnedSession()throws Exception {
        create();executor.runAll();accept();executor.runAll();String before=media.snapshot().toString();int count=wakes.get();
        duringQuery=media::close;idleQuery=true;media.tick();executor.runAll();
        assertEquals(before,media.snapshot().toString());assertEquals(count,wakes.get());assertEquals("stop",operations.get(operations.size()-1));
    }
    @Test public void tickFloodAndReentrantTicksKeepOnePrepareInFlight()throws Exception {
        create();for(int i=0;i<100;i++)media.tick();assertEquals(1,executor.size());
        duringPrepare=()->{clock.elapsed+=300000;for(int i=0;i<100;i++)media.tick();assertEquals(0,executor.size());};
        executor.runAll();assertEquals(1,prepareCalls);assertEquals(0,executor.size());
    }
    @Test public void executorRejectionBacksOffWithoutStickyFlight()throws Exception {
        executor.rejectOnce=true;create();assertEquals("VISUAL_PREPARE_FAILED",media.snapshot().getString("error"));
        advance(29999);assertEquals(0,prepareCalls);advance(1);assertEquals(1,prepareCalls);
    }
    @Test public void rejectedSessionStillInvalidatesOlderPrepareGeneration()throws Exception {
        create();duringPrepare=()->{executor.rejectOnce=true;accept();};executor.runOne();
        assertFalse(media.active());assertEquals("VISUAL_START_UNCONFIRMED",media.snapshot().getString("error"));
        assertEquals(0,media.snapshot().getInt("media_cameras"));
        duringPrepare=null;media.tick();executor.runAll();assertEquals(1,media.snapshot().getInt("media_cameras"));
    }
    @Test public void closingBeforeWorkerRunsDoesNotCallPrepare()throws Exception {
        create();media.close();executor.runAll();assertTrue(operations.isEmpty());assertEquals(0,wakes.get());
        advance(300000);assertEquals(0,executor.size());
    }
    private static final class FakeClock implements RemoteVisualMedia.Clock {
        long elapsed;public long wall(){return 1000000;}public long elapsed(){return elapsed;}
    }
    private static final class ManualExecutor extends AbstractExecutorService {
        private final Deque<Runnable> jobs=new ArrayDeque<>();boolean closed,rejectOnce;
        public void execute(Runnable job){if(closed||rejectOnce){rejectOnce=false;throw new RejectedExecutionException();}jobs.add(job);}
        int size(){return jobs.size();}
        void runOne(){jobs.remove().run();}
        void runAll(){int count=0;while(!jobs.isEmpty()){if(++count>1000)throw new AssertionError("工作队列未有界停止");runOne();}}
        public void shutdown(){closed=true;}
        public List<Runnable> shutdownNow(){closed=true;List<Runnable> result=new ArrayList<>(jobs);jobs.clear();return result;}
        public boolean isShutdown(){return closed;}public boolean isTerminated(){return closed&&jobs.isEmpty();}
        public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
    }
}
