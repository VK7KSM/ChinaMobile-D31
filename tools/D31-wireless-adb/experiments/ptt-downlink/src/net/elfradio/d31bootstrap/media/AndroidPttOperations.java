package net.elfradio.d31bootstrap.media;

import java.io.IOException;
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
    private volatile boolean cancelled;
    private boolean observerStarted,routeOpened;
    private long mutedAt=-1;
    public AndroidPttOperations(AndroidRtcDownlink peer,PttOutputGuard guard,PttOutputObserver observer,
            DownlinkRouteLease route,MediaCapture.Clock clock,Sender sender){
        this.peer=peer;this.guard=guard;this.observer=observer;this.route=route;this.clock=clock;this.sender=sender;
    }
    public void open()throws Exception{
        check();observerStarted=true;observer.start();observer.awaitFirst();check();guard.requireIdle();
        // 设置可能先成功后抛错，关闭仍须检查恢复日志。
        routeOpened=true;route.openPtt();check();peer.open();check();
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
        observer.actualAudioSession(session);
        if(!guard.observedSince(mutedAt))return 0;
        try{guard.requireOwnedOutput();return 1;}
        catch(IOException failure){if("MEDIA_OUTPUT_NOT_STARTED".equals(failure.getMessage()))return 0;throw failure;}
    }
    public boolean playbackFrames(){try{return peer.snapshot().optBoolean("playback_frames_seen");}catch(Exception failure){return false;}}
    public void unmute()throws Exception{check();peer.enablePlayout(guard::requireOwnedOutput);}
    public void cancel(){cancelled=true;peer.cancel();sender.abort();}
    private void check()throws IOException{if(cancelled)throw new IOException("MEDIA_CANCELLED");}
    public void close()throws Exception{
        cancelled=true;Exception failure=null;boolean peerClosed=false;
        try{peer.close();peerClosed=true;}catch(Exception|LinkageError error){failure=new IOException("MEDIA_PTT_RELEASE_UNCONFIRMED",error);}
        // 输出释放未知时不恢复路由；保留日志和主线互斥槽。
        if(peerClosed&&routeOpened){
            try{route.releaseFocus();observer.refresh();route.close();}
            catch(Exception error){failure=new IOException("MEDIA_ROUTE_RESTORE_UNCONFIRMED",error);}
        }
        if(observerStarted)try{observer.close();}catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        try{sender.close();}catch(Exception error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(failure!=null)throw failure;
    }
}
