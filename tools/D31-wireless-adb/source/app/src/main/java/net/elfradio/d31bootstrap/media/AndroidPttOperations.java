package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** PTT专用执行适配，仅由PttSessionController工作线程调用；不自行连接服务器或开放能力。 */
public final class AndroidPttOperations implements PttSessionController.Operations {
    public interface Sender {void send(JSONObject value)throws Exception;void close()throws Exception;void abort();}
    private final AndroidRtcDownlink peer;
    private final PttOutputGuard guard;
    private final PttOutputObserver observer;
    private final DownlinkRouteLease route;
    private final MediaCapture.Clock clock;
    private final Sender sender;
    private final FutureTask<Void> abort;
    private final AtomicBoolean abortStarted=new AtomicBoolean();
    private volatile boolean cancelled;
    private boolean observerStarted,routeOpened;
    private long mutedAt=-1;
    public AndroidPttOperations(AndroidRtcDownlink peer,PttOutputGuard guard,PttOutputObserver observer,
            DownlinkRouteLease route,MediaCapture.Clock clock,Sender sender){
        this.peer=peer;this.guard=guard;this.observer=observer;this.route=route;this.clock=clock;this.sender=sender;
        abort=new FutureTask<>(()->{sender.abort();return null;});
    }
    public void open()throws Exception{
        check();observerStarted=true;observer.start();observer.awaitFirst();check();guard.requireIdle();
        // 设置可能先成功后抛错，关闭仍须检查恢复日志。
        routeOpened=true;route.recover();check();route.openPtt();check();peer.open();check();
    }
    public void send(JSONObject body)throws Exception{check();sender.send(body);}
    public JSONObject subscribe(JSONObject body)throws Exception{check();return peer.subscribe(body);}
    public void answerAcknowledged()throws Exception{check();peer.answerAcknowledged();}
    public void prepareMuted()throws Exception{
        check();observer.refresh();check();guard.requireIdle();mutedAt=clock.elapsed();observer.expectOutput();peer.prepareMutedPlayout(guard);
    }
    public int outputProof()throws Exception{
        check();JSONObject actual=peer.privatePlaybackIdentity();int session=actual.optInt("audio_session",0);
        if(session<=0)return 0;
        if(actual.optInt("pid",-1)!=android.os.Process.myPid())throw new IOException("MEDIA_OUTPUT_IDENTITY_MISMATCH");
        guard.actualOutput(actual);
        observer.actualAudioSession(session);
        if(!guard.observedSince(mutedAt))return 0;
        return guard.outputProof();
    }
    public boolean playbackFrames(){try{return peer.snapshot().optBoolean("playback_frames_seen");}catch(Exception failure){return false;}}
    public void unmute()throws Exception{check();peer.enablePlayout(guard::requireOwnedOutput);}
    public void cancel(){
        cancelled=true;peer.cancel();
        // 套接字关闭可能等待传输锁；独立打断线程不占核心、回调或媒体释放线程。
        if(abortStarted.compareAndSet(false,true)){
            Thread thread=new Thread(abort,"d31-ptt-abort");thread.setDaemon(true);thread.start();
        }
    }
    private void check()throws IOException{if(cancelled)throw new IOException("MEDIA_CANCELLED");}
    public void close()throws Exception{
        cancel();Exception failure=null;boolean peerClosed=false;
        try{peer.close();peerClosed=true;}catch(Exception|LinkageError error){failure=new IOException("MEDIA_PTT_RELEASE_UNCONFIRMED",error);}
        // 输出释放未知时不恢复路由；保留日志和主线互斥槽。
        if(peerClosed&&routeOpened){
            try{route.releaseFocus();observer.awaitIdle(4000);route.recover();observer.routeRestored();}
            catch(Exception error){failure=new IOException("MEDIA_ROUTE_RESTORE_UNCONFIRMED",error);}
        }
        if(observerStarted)try{observer.close();}catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        try{
            // 未退出的abort不能与close并发；超时明确保留互斥，不能宣称清理完成。
            try{abort.get(1750,TimeUnit.MILLISECONDS);}catch(ExecutionException ignored){/* 仍由close确认最终释放。 */}
            sender.close();
        }catch(Exception error){
            if(error instanceof InterruptedException)Thread.currentThread().interrupt();
            IOException transport=new IOException("MEDIA_TRANSPORT_RELEASE_UNCONFIRMED",error);
            if(failure==null)failure=transport;else failure.addSuppressed(transport);
        }
        if(failure!=null)throw failure;
    }
}
