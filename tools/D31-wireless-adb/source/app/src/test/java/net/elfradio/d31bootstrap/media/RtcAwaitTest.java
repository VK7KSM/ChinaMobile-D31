package net.elfradio.d31bootstrap.media;

import org.junit.Test;
import static org.junit.Assert.*;

public class RtcAwaitTest {
    @Test public void firstCallbackWins()throws Exception{
        RtcAwait<String> value=new RtcAwait<String>();value.succeed("first");value.fail("ERROR");value.succeed("second");
        assertEquals("first",value.get(new Cancellation(),MediaCapture.SYSTEM_CLOCK,1000));
    }
    @Test public void cancellationWinsOverLateSuccess()throws Exception{
        final RtcAwait<String> value=new RtcAwait<String>();value.succeed("late");final Cancellation cancel=new Cancellation();cancel.cancel();
        MediaCaptureTest.rejects("CANCELLED",new MediaCaptureTest.Operation(){public void run()throws Exception{value.get(cancel,MediaCapture.SYSTEM_CLOCK,1000);}});
    }
    @Test public void failureAndDeadlineAreBounded()throws Exception{
        final RtcAwait<String> failed=new RtcAwait<String>();failed.fail("RPC_FAILED");
        MediaCaptureTest.rejects("RPC_FAILED",new MediaCaptureTest.Operation(){public void run()throws Exception{failed.get(new Cancellation(),MediaCapture.SYSTEM_CLOCK,1000);}});
        final MediaCapture.Clock clock=new MediaCapture.Clock(){long elapsed;public long wall(){return 0;}public long elapsed(){return ++elapsed*1000;}};
        MediaCaptureTest.rejects("TIMED_OUT",new MediaCaptureTest.Operation(){public void run()throws Exception{new RtcAwait<String>().get(new Cancellation(),clock,500);}});
    }
}
