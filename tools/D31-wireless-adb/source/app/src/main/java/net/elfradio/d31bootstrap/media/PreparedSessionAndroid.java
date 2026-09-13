package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.media.AudioManager;
import java.io.File;
import java.io.IOException;

/** 将现有路由租约、持久peer和单次照片警报适配器接入同一APP会话。 */
final class PreparedSessionAndroid implements PreparedSessionPort.Factory {
    private final Context app;
    private final File files,cache,apk;
    private final String hash;
    private final RtcOffer offer;
    private final MediaCapture.Clock clock;
    private final AndroidAudioOccupancy occupancy;
    private AndroidPersistentRtc rtc;
    private AndroidAudioOccupancy.LocalOutput localOutput;
    private AudioGuard connected;
    PreparedSessionAndroid(Context app,File files,File cache,File apk,String hash,RtcOffer offer,
                           MediaCapture.Clock clock,AudioGuard current) throws IOException {
        if(app==null||files==null||cache==null||apk==null||hash==null||offer==null||clock==null||current==null)
            throw new IOException("MEDIA_PREPARED_DEPENDENCY_INVALID");
        if(!(current instanceof AndroidAudioOccupancy))throw new IOException("MEDIA_PREPARED_OCCUPANCY_UNAVAILABLE");
        this.app=app;this.files=files;this.cache=cache;this.apk=apk;this.hash=hash;this.offer=offer;this.clock=clock;this.occupancy=(AndroidAudioOccupancy)current;
    }
    public PreparedSessionPort.Peer createPeer(final PreparedSessionPort.Events events) throws Exception {
        if(rtc!=null)throw new IOException("MEDIA_PREPARED_NOT_REUSABLE");
        rtc=new AndroidPersistentRtc(app,apk,hash,cache,clock);
        final AudioManager audio=(AudioManager)app.getSystemService(Context.AUDIO_SERVICE);
        localOutput=new AndroidAudioOccupancy.LocalOutput(){
            public long generation(){return rtc.outputGeneration();}
            public void requireMutedOutput()throws Exception {rtc.requireMutedOutput();}
        };
        connected=()->{occupancy.requireNoCalls();rtc.requireFocus();
            if(audio==null||audio.getMode()!=AudioManager.MODE_NORMAL||!audio.isSpeakerphoneOn())throw new IOException("MEDIA_ROUTE_OWNERSHIP_LOST");};
        return new AndroidPersistentMediaPeer(rtc,new AndroidPersistentMediaPeer.Route(){
            private DownlinkRouteLease lease;
            private long routeReadyAt;
            @Override
            public void beforeActivate(long operation,String mode) throws Exception {
                requireOperationIdle();
            }
            public void open(long operation) throws Exception {
                if(lease!=null)throw new IOException("MEDIA_ROUTE_RECOVERY_PENDING");
                awaitInitialIdle(occupancy);
                lease=AndroidDownlinkRoute.create(app,occupancy,rtc::focusOwned,()->{rtc.softStop();events.failed("MEDIA_PREPARED_FOCUS_LOST");});
                lease.recover();lease.openPtt();routeReadyAt=clock.elapsed();
            }
            public void prepared()throws Exception {rtc.awaitMutedOutput(1500);occupancy.refreshSince(routeReadyAt,1500);requireOperationIdle();}
            public void checkConnected(String mode)throws Exception {connected.requireIdle();}
            public void close(long operation) throws Exception {
                close(operation,4000);
            }
            public void close(long operation,long remainingMs) throws Exception {
                if(lease==null)return;
                long end=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(remainingMs);
                lease.releaseFocus();
                rtc.requireReleased();
                long releasedAt=clock.elapsed();
                long left=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime());
                if(left<=0)throw new IOException("MEDIA_ROUTE_RESTORE_UNCONFIRMED");
                occupancy.refreshSince(releasedAt,Math.min(1500,left));
                left=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime());
                if(left<=0)throw new IOException("MEDIA_ROUTE_RESTORE_UNCONFIRMED");
                awaitIdle(()->occupancy.requireIdleSince(releasedAt),Math.min(3500,left),"MEDIA_PREPARED_ROUTE_IDLE_UNCONFIRMED");
                lease.recover();lease=null;
            }
            @Override
            public void afterDeactivate(long operation,String mode) throws Exception {
                if("alarm".equals(mode)){
                    rtc.awaitMutedOutput(1500);
                    awaitAlarmIdle(occupancy,localOutput,clock,1500);
                    requireOperationIdle();return;
                }
                long stoppedAt=clock.elapsed();
                if(AndroidPersistentMediaPeer.capture(mode))occupancy.refreshSince(stoppedAt,1500);
                rtc.awaitMutedOutput(1500);requireOperationIdle();
            }
        },new AndroidPersistentMediaPeer.Events(){
            public void changed(){events.changed();}
            public void failed(String code){events.failed(code);}
        });
    }
    public PreparedSessionPort.Extra createExtra(PreparedSessionPort.Operation operation,PreparedSessionPort.OperationEvents events) throws Exception {
        if(rtc==null)throw new IOException("MEDIA_PREPARED_NOT_CONNECTED");
        return new AppPreparedExtras(app,files,offer,operation,events,this::requireOperationIdle,()->occupancy.projectMutedOutput(localOutput));
    }
    private void requireOperationIdle()throws Exception {connected.requireIdle();rtc.requireMutedOutput();}
    static void awaitAlarmIdle(AndroidAudioOccupancy occupancy,AndroidAudioOccupancy.LocalOutput local,
                               MediaCapture.Clock clock,long timeoutMs)throws Exception {
        final long end=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        awaitIdle(()->{
            long left=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime());
            if(left<=0)throw new IOException("MEDIA_PREPARED_IDLE_UNCONFIRMED");
            // stop返回后策略层仍可能忙；每轮在既有采样线程补读，不豁免已释放的警报流。
            occupancy.refreshSince(clock.elapsed(),Math.min(AndroidAudioOccupancy.MAX_SAMPLE_MS,left));
            org.json.JSONObject raw=occupancy.projectMutedOutput(local);
            AndroidAudioOccupancy.Observation value=AndroidAudioOccupancy.evaluate(raw,
                    raw.getLong("sample_started_elapsed_ms"),raw.getLong("sample_finished_elapsed_ms"));
            if(value.overall()!=AndroidAudioOccupancy.State.IDLE)
                throw new IOException("MEDIA_AUDIO_"+value.overall().name()+":"+value.reason);
            if(System.nanoTime()>=end)throw new IOException("MEDIA_PREPARED_IDLE_UNCONFIRMED");
        },timeoutMs,"MEDIA_PREPARED_IDLE_UNCONFIRMED");
    }
    static void awaitInitialIdle(AndroidAudioOccupancy occupancy)throws Exception {
        if(!occupancy.awaitFirstSample(1500))throw new IOException("MEDIA_AUDIO_FIRST_SAMPLE_TIMEOUT");
        occupancy.requireIdle();
    }
    static void requireActivationIdle(AudioGuard current,String mode) throws Exception {
        if(!"photo".equals(mode))current.requireIdle();
    }
    static void restore(DownlinkRouteLease lease,AudioGuard current,long timeoutMs) throws Exception {
        lease.releaseFocus();
        awaitIdle(current,timeoutMs,"MEDIA_PREPARED_ROUTE_IDLE_UNCONFIRMED");
        lease.recover();
    }
    static void awaitDeactivationIdle(AudioGuard current,String mode,long timeoutMs) throws Exception {
        if("microphone".equals(mode)||"video".equals(mode)||"alarm".equals(mode))
            awaitIdle(current,timeoutMs,"MEDIA_PREPARED_IDLE_UNCONFIRMED");
    }
    private static void awaitIdle(AudioGuard current,long timeoutMs,String code) throws Exception {
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for(;;){
            try{current.requireIdle();break;}
            catch(Exception busy){
                long left=end-System.nanoTime();
                if(left<=0)throw new IOException(code,busy);
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(left,java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100)));
            }
        }
    }
}
