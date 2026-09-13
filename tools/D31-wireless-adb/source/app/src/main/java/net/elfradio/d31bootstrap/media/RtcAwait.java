package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** API21可用的单次回调桥；取消不能依赖被阻塞的媒体工作线程。 */
final class RtcAwait<T> {
    private final CountDownLatch done=new CountDownLatch(1);
    private T value;
    private Exception error;
    synchronized void succeed(T result){if(done.getCount()==0)return;value=result;done.countDown();}
    synchronized void fail(String code){if(done.getCount()==0)return;error=new IOException(code);done.countDown();}
    T get(Cancellation cancel, MediaCapture.Clock clock, long timeoutMs) throws Exception {
        long end=clock.elapsed()+timeoutMs;
        while(true){
            cancel.check();
            long remaining=end-clock.elapsed();
            if(remaining<=0)throw new IOException("MEDIA_RTC_TIMED_OUT");
            if(done.await(Math.min(remaining,100),TimeUnit.MILLISECONDS)){
                cancel.check();if(error!=null)throw error;return value;
            }
        }
    }
}
