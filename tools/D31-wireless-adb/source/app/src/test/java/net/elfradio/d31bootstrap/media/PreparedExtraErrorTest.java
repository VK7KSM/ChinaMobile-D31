package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreparedExtraErrorTest {
    @Test public void preservesControlledOccupancyReason(){
        assertEquals("MEDIA_AUDIO_BUSY_GLOBAL_AUDIO_ACTIVE_OWNER_NOT_EXEMPTED",
            AppPreparedExtras.code(new IOException("MEDIA_AUDIO_BUSY:GLOBAL_AUDIO_ACTIVE_OWNER_NOT_EXEMPTED")));
    }
    @Test public void normalizesAlarmCodesWithoutChangingExistingMediaOrVisualCodes(){
        for(String value:new String[]{"ALARM_SCREEN_UNAVAILABLE","ALARM_CANCELLED","ALARM_VOLUME_RESTORE_FAILED"})
            assertEquals("VISUAL_"+value,AppPreparedExtras.code(new IOException(value)));
        for(String value:new String[]{"MEDIA_AUDIO_BUSY","VISUAL_ALARM_SCREEN_UNAVAILABLE"})
            assertEquals(value,AppPreparedExtras.code(new IOException(value)));
    }
    @Test public void alarmReadyTimeoutReachesFailureEventWithControllerAcceptedCode()throws Exception {
        AppPreparedExtrasTest.Events events=new AppPreparedExtrasTest.Events();
        AppPreparedExtrasTest.Backend backend=new AppPreparedExtrasTest.Backend(){
            @Override public void alarm(String id)throws Exception {throw new IOException("ALARM_SCREEN_UNAVAILABLE");}
        };
        AppPreparedExtras extra=AppPreparedExtrasTest.extra(AppPreparedExtrasTest.op(1,"alarm"),events,backend,1000);
        try{
            extra.start();AppPreparedExtrasTest.await(events.failed);
            assertEquals("VISUAL_ALARM_SCREEN_UNAVAILABLE",events.error);
            assertTrue(events.error.matches("(?:MEDIA|VISUAL)_[A-Z0-9_]{1,96}"));
            assertFalse(extra.ready());assertTrue(events.messages.isEmpty());
        }finally{extra.close();}
        assertEquals(1,backend.closeCalls.get());
    }
    @Test public void neverIncludesPrivateExceptionText(){
        for(String value:new String[]{null,"http://private.invalid","user:password","KEY:TOKEN with space","KEY:A:B",
                "ALARM_SCREEN_UNAVAILABLE private-value","ALARM_private"})
            assertEquals("VISUAL_OPERATION_FAILED",AppPreparedExtras.code(new IOException(value)));
    }
}
