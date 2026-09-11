package net.elfradio.d31bootstrap.media;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class AlarmTasksTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    final MediaCaptureTest.Clock clock=new MediaCaptureTest.Clock();
    int plays,stops;boolean busy,timerFails,playerFails;
    final List<Runnable> callbacks=new ArrayList<Runnable>();
    final AudioGuard guard=new AudioGuard(){public void requireIdle()throws Exception{if(busy)throw new IOException("MEDIA_PHONE_BUSY");}};
    final AlarmTasks.Player player=new AlarmTasks.Player(){public AlarmTasks.Tone start(int ms)throws Exception{
        assertEquals(10000,ms);plays++;if(playerFails)throw new IOException("ALARM_START_FAILED");
        return new AlarmTasks.Tone(){public void close(){stops++;}};
    }};
    final AlarmTasks.Scheduler scheduler=new AlarmTasks.Scheduler(){public AlarmTasks.Ticket after(final Runnable run,long delay)throws Exception{
        if(timerFails)throw new IOException("ALARM_TIMER_UNAVAILABLE");callbacks.add(run);
        return new AlarmTasks.Ticket(){public void cancel(){callbacks.remove(run);}};
    }};
    JSONObject task(String id,String type)throws Exception{return new JSONObject().put("id",id).put("type",type).put("params",new JSONObject()).put("expires_at",200000);}
    AlarmTasks owner(File root,AudioGuard gate)throws Exception{return new AlarmTasks(root,player,scheduler,gate,clock,null);}
    void tick(long ms){clock.advance(ms);List<Runnable> due=new ArrayList<Runnable>(callbacks);callbacks.clear();for(Runnable run:due)run.run();}
    @Test public void playReturnsAndTimerOwnsTenSeconds()throws Exception{
        try(AlarmTasks alarm=owner(temp.newFolder(),guard)){
            assertEquals("playing",alarm.accept(task("a","play_alarm")).getString("state"));assertEquals(0,stops);
            tick(9999);assertEquals(0,stops);tick(1);assertEquals(1,stops);assertEquals("completed",alarm.snapshot().getString("state"));
        }
    }
    @Test public void separateStopTaskStopsWithoutWaiting()throws Exception{
        try(AlarmTasks alarm=owner(temp.newFolder(),guard)){
            alarm.accept(task("a","play_alarm"));Runnable late=callbacks.get(0);
            assertEquals("stopped",alarm.accept(task("b","stop_alarm")).getString("state"));assertEquals(1,stops);
            alarm.accept(task("c","play_alarm"));late.run();assertEquals("playing",alarm.snapshot().getString("state"));
            alarm.accept(task("b","stop_alarm"));assertEquals("playing",alarm.snapshot().getString("state"));
        }
    }
    @Test public void duplicatePlayAndRestartDoNotReplay()throws Exception{
        File root=temp.newFolder();try(AlarmTasks alarm=owner(root,guard)){alarm.accept(task("a","play_alarm"));alarm.accept(task("a","play_alarm"));assertEquals(1,plays);}
        try(AlarmTasks alarm=owner(root,guard)){assertEquals("interrupted",alarm.accept(task("a","play_alarm")).getString("state"));assertEquals(1,plays);}
    }
    @Test public void pendingIntentRecoveredWithoutSound()throws Exception{
        File root=temp.newFolder(),record=new File(root,"alarms/a");record.mkdirs();
        MediaFiles.writeNew(new File(record,"intent.json"),new JSONObject().put("id","a").put("type","play_alarm").put("params",new JSONObject()));
        try(AlarmTasks alarm=owner(root,guard)){assertEquals("interrupted",alarm.accept(task("a","play_alarm")).getString("state"));assertEquals(0,plays);}
    }
    @Test public void unknownAudioDefaultRejects()throws Exception{
        try(final AlarmTasks alarm=owner(temp.newFolder(),null)){MediaCaptureTest.rejects("UNKNOWN",new MediaCaptureTest.Operation(){public void run()throws Exception{alarm.accept(task("a","play_alarm"));}});assertEquals(0,plays);}
    }
    @Test public void busyDuringPlaybackReleases()throws Exception{
        try(AlarmTasks alarm=owner(temp.newFolder(),guard)){alarm.accept(task("a","play_alarm"));busy=true;tick(100);assertEquals(1,stops);assertEquals("failed",alarm.snapshot().getString("state"));}
    }
    @Test public void timerFailureReleasesAndDoesNotReplay()throws Exception{
        timerFails=true;try(final AlarmTasks alarm=owner(temp.newFolder(),guard)){
            MediaCaptureTest.rejects("TIMER",new MediaCaptureTest.Operation(){public void run()throws Exception{alarm.accept(task("a","play_alarm"));}});
            assertEquals(1,stops);timerFails=false;alarm.accept(task("a","play_alarm"));assertEquals(1,plays);
        }
    }
    @Test public void cancellationStopsCurrentOwner()throws Exception{
        try(AlarmTasks alarm=owner(temp.newFolder(),guard)){alarm.accept(task("a","play_alarm"));assertEquals("cancelled",alarm.accept(task("a","play_alarm").put("cancel_requested",true)).getString("state"));assertEquals(1,stops);}
    }
    @Test public void twoOwnersCannotClaimSameDirectory()throws Exception{
        final File root=temp.newFolder();try(AlarmTasks alarm=owner(root,guard)){
            MediaCaptureTest.rejects("BUSY",new MediaCaptureTest.Operation(){public void run()throws Exception{owner(root,guard);}});
        }
        try(AlarmTasks alarm=owner(root,guard)){assertEquals("idle",alarm.snapshot().getString("state"));}
    }
    @Test public void captureBlockedWhileAlarmOwnsResources()throws Exception{
        File root=temp.newFolder();try(AlarmTasks alarm=owner(root,guard)){alarm.accept(task("a","play_alarm"));
            final MediaCapture capture=new MediaCapture(root,new MediaCapture.Device(){public MediaCapture.Captured capture(CaptureRequest r,File f,Cancellation c,long d,AudioGuard g){fail("hardware must not open");return null;}},guard,clock);
            MediaCaptureTest.rejects("BUSY",new MediaCaptureTest.Operation(){public void run()throws Exception{capture.photo(CaptureRequest.photo("p","r","front",200000),new Cancellation());}});
        }
    }
    @Test public void reportMatchesWebAlarmContractIncludingCancellation()throws Exception{
        try(AlarmTasks alarm=owner(temp.newFolder(),guard)){
            assertEquals("idle",alarm.reportSnapshot().getString("state"));
            alarm.accept(task("a","play_alarm"));
            JSONObject report=alarm.reportSnapshot();assertEquals(3,report.length());
            assertEquals("playing",report.getString("state"));assertTrue(report.getLong("started_at_ms")>=0);
            assertEquals(10000,report.getInt("duration_ms"));
            alarm.accept(task("a","play_alarm").put("cancel_requested",true));
            assertEquals("interrupted",alarm.reportSnapshot().getString("state"));
        }
    }
    @Test public void playerFailureIsDurableAndReleasesCaptureLease()throws Exception{
        File root=temp.newFolder();playerFails=true;
        try(final AlarmTasks alarm=owner(root,guard)){
            MediaCaptureTest.rejects("START_FAILED",new MediaCaptureTest.Operation(){public void run()throws Exception{alarm.accept(task("a","play_alarm"));}});
            assertEquals("failed",alarm.reportSnapshot().getString("state"));
            try(MediaFiles.Lease unused=MediaFiles.lease(root)){assertNotNull(unused);}
            playerFails=false;assertEquals("failed",alarm.accept(task("a","play_alarm")).getString("state"));assertEquals(1,plays);
        }
    }
    @Test public void failedTimerCancellationStillClosesTone()throws Exception{
        AlarmTasks.Scheduler bad=new AlarmTasks.Scheduler(){public AlarmTasks.Ticket after(Runnable run,long delay){
            return new AlarmTasks.Ticket(){public void cancel(){throw new IllegalStateException("cancel failed");}};
        }};
        File root=temp.newFolder();try(final AlarmTasks alarm=new AlarmTasks(root,player,bad,guard,clock,null)){
            alarm.accept(task("a","play_alarm"));
            try{alarm.accept(task("b","stop_alarm"));fail("expected scheduler cancellation failure");}
            catch(IllegalStateException expected){assertEquals("cancel failed",expected.getMessage());}
            assertEquals(1,stops);assertEquals("failed",alarm.reportSnapshot().getString("state"));
            try(MediaFiles.Lease unused=MediaFiles.lease(root)){assertNotNull(unused);}
        }
    }
}
