package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreparedAlarmTest {
    final MediaCaptureTest.Clock clock=new MediaCaptureTest.Clock();
    final List<Runnable> callbacks=new ArrayList<>();
    int starts,stops;
    boolean busy,timerFails,releaseFails,cancelFails,locallyStopped;
    final AlarmTasks.Player player=duration->{assertEquals(0,duration);starts++;return new AlarmTasks.Tone(){
        public boolean isActive(){return !locallyStopped;}
        public void close()throws Exception{stops++;if(releaseFails)throw new IOException("private release");}
    };};
    final AlarmTasks.Scheduler scheduler=(r,d)->{if(timerFails)throw new IOException("ALARM_TIMER_UNAVAILABLE");callbacks.add(r);return ()->{callbacks.remove(r);if(cancelFails)throw new IllegalStateException("private timer");};};
    PhotoAlarmBackend.PreparedAlarm alarm(){return new PhotoAlarmBackend.PreparedAlarm(player,scheduler,()->{if(busy)throw new IOException("MEDIA_BUSY");},clock);}
    void tick(long ms){clock.advance(ms);List<Runnable> pending=new ArrayList<>(callbacks);callbacks.clear();for(Runnable r:pending)r.run();}
    @Test public void remainsActivePastTenSecondsUntilExplicitlyStopped()throws Exception {
        try(PhotoAlarmBackend.PreparedAlarm alarm=alarm()){alarm.start();tick(10000);tick(60000);assertEquals(0,stops);assertEquals("playing",alarm.state());}
        assertEquals(1,starts);assertEquals(1,stops);
    }
    @Test public void localStopCompletesPreparedOperation()throws Exception {
        try(PhotoAlarmBackend.PreparedAlarm alarm=alarm()){alarm.start();locallyStopped=true;tick(100);assertEquals("completed",alarm.state());assertEquals(1,stops);}
    }
    @Test public void cancelledTimerCannotAffectNextOperationTone()throws Exception {
        PhotoAlarmBackend.PreparedAlarm old=alarm();old.start();Runnable late=callbacks.get(0);old.close();
        try(PhotoAlarmBackend.PreparedAlarm next=alarm()){next.start();late.run();assertEquals("playing",next.state());assertEquals(1,stops);}
        assertEquals(2,stops);
    }
    @Test public void busyBeforeStartNeverPlays()throws Exception {
        busy=true;try(PhotoAlarmBackend.PreparedAlarm alarm=alarm()){try{alarm.start();fail();}catch(IOException expected){assertEquals("MEDIA_BUSY",expected.getMessage());}}
        assertEquals(0,starts);
    }
    @Test public void busyDuringToneStopsIt()throws Exception {
        try(PhotoAlarmBackend.PreparedAlarm alarm=alarm()){alarm.start();busy=true;tick(100);assertEquals(1,stops);
            try{alarm.state();fail();}catch(IOException expected){assertEquals("VISUAL_ALARM_FAILED",expected.getMessage());}}
    }
    @Test public void schedulerFailureAfterStartReleasesTone()throws Exception {
        timerFails=true;try(PhotoAlarmBackend.PreparedAlarm alarm=alarm()){try{alarm.start();fail();}catch(IOException expected){assertEquals("ALARM_TIMER_UNAVAILABLE",expected.getMessage());}}
        assertEquals(1,starts);assertEquals(1,stops);
    }
    @Test public void releaseFailureFromTimerCannotBeErasedByClose()throws Exception {
        PhotoAlarmBackend.PreparedAlarm alarm=alarm();alarm.start();releaseFails=true;busy=true;tick(100);
        for(int i=0;i<2;i++)try{alarm.close();fail();}catch(IOException expected){assertEquals("VISUAL_CLEANUP_PENDING",expected.getMessage());}
        assertEquals(1,stops);
    }
    @Test public void ticketCancellationFailureStillReleasesToneAndBlocksCleanup()throws Exception {
        PhotoAlarmBackend.PreparedAlarm alarm=alarm();alarm.start();cancelFails=true;
        try{alarm.close();fail();}catch(IOException expected){assertEquals("VISUAL_CLEANUP_PENDING",expected.getMessage());}assertEquals(1,stops);
    }
    @Test public void sameOperationCannotReplayAfterCompletionOrClose()throws Exception {
        PhotoAlarmBackend.PreparedAlarm alarm=alarm();alarm.start();tick(10000);
        try{alarm.start();fail();}catch(IOException expected){assertEquals("VISUAL_ALARM_NOT_STARTED_NO_REPLAY",expected.getMessage());}
        alarm.close();assertEquals(1,starts);
    }
    @Test public void closeDuringPlatformStartWaitsForOwnedToneAndReleasesIt()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),gate=new CountDownLatch(1),closed=new CountDownLatch(1);
        PhotoAlarmBackend.PreparedAlarm alarm=new PhotoAlarmBackend.PreparedAlarm(duration->{entered.countDown();gate.await();return ()->stops++;},scheduler,()->{},clock);
        Thread starting=new Thread(()->{try{alarm.start();}catch(Exception e){throw new AssertionError(e);}});starting.start();
        assertTrue(entered.await(1,TimeUnit.SECONDS));Thread closing=new Thread(()->{try{alarm.close();closed.countDown();}catch(Exception e){throw new AssertionError(e);}});closing.start();
        assertFalse(closed.await(20,TimeUnit.MILLISECONDS));gate.countDown();assertTrue(closed.await(1,TimeUnit.SECONDS));assertEquals(1,stops);starting.join();closing.join();
    }
}
