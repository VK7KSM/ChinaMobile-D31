package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.util.concurrent.*;
import org.json.JSONObject;

/** 仅读取线程执行Binder与有界原件保存；查询不执行读取。start仅用于已授权会话。 */
public final class PttOutputObserver implements AutoCloseable {
    private final AndroidAudioCaptureObservation reader;
    private final AppMediaReadTrace trace=new AppMediaReadTrace();
    private final AppMediaRtcDiagnostics diagnostics;
    private final PttOutputGuard guard;
    private final Cancellation cancel=new Cancellation();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private volatile int audioSession;
    private volatile boolean closed;
    private volatile boolean outputExpected;
    private boolean started;
    private int captured;
    private volatile String diagnosticError="";
    private final CountDownLatch first=new CountDownLatch(1);
    public PttOutputObserver(Context context,PttOutputGuard guard,File diagnosticRoot,String session,String hash){
        this.guard=guard;reader=new AndroidAudioCaptureObservation(context,trace);
        diagnostics=new AppMediaRtcDiagnostics(diagnosticRoot,session,hash);
    }
    public void actualAudioSession(int value){audioSession=value;}
    public void expectOutput(){outputExpected=true;}
    public synchronized void start(){
        if(started||closed)throw new IllegalStateException("MEDIA_OUTPUT_OBSERVER_NOT_REUSABLE");started=true;
        worker.scheduleWithFixedDelay(this::capture,0,1000,TimeUnit.MILLISECONDS);
    }
    private void capture(){
        try{
            int identityAtRead=audioSession;
            trace.reset();AudioCaptureObservation.Sample value=null;String error="";
            try{value=reader.read(cancel);}catch(Exception|LinkageError failure){
                String reason=failure.getMessage();error=reason!=null&&reason.matches("MEDIA_[A-Z0-9_]{1,80}")?reason:"MEDIA_OUTPUT_READ_FAILED";
            }
            if(!closed)guard.sample(value,error);
            // 首两份实际播放样本与随后第一份失败最多三件，不逐轮写盘。
            if(shouldSave(captured,outputExpected,value!=null)){
                captured++;
                try{diagnostics.save(value,error,android.os.Process.myPid(),identityAtRead,SystemClock.elapsedRealtime(),
                        new JSONObject().put("scope","PTT_OUTPUT_MUTED_OBSERVATION").put("identity_observed",identityAtRead>0),trace.snapshot());}
                catch(Exception failure){diagnosticError="MEDIA_OUTPUT_DIAGNOSTIC_FAILED";}
            }
        }finally{first.countDown();}
    }
    static boolean shouldSave(int captured,boolean expected,boolean returned){return captured<3&&(expected&&captured<2||!returned);}
    public void awaitFirst()throws Exception{
        if(!first.await(1750,TimeUnit.MILLISECONDS))throw new java.io.IOException("MEDIA_OUTPUT_FIRST_SAMPLE_TIMEOUT");
    }
    /** 排在同一读取线程，禁止与周期读取并发；超时不使用旧缓存冒充新鲜回读。 */
    public void refresh()throws Exception{
        if(closed)throw new java.io.IOException("MEDIA_OUTPUT_OBSERVER_CLOSED");
        Future<?> sample=worker.submit(this::capture);
        try{sample.get(1750,TimeUnit.MILLISECONDS);}
        catch(TimeoutException timeout){sample.cancel(true);guard.sample(null,"MEDIA_OUTPUT_REFRESH_TIMEOUT");throw new java.io.IOException("MEDIA_OUTPUT_REFRESH_TIMEOUT");}
    }
    public JSONObject storage()throws Exception{return diagnostics.snapshot().put("observer_error",diagnosticError);}
    public void close()throws Exception{
        closed=true;cancel.cancel();worker.shutdownNow();guard.sample(null,"MEDIA_OUTPUT_OBSERVER_CLOSED");
        if(!worker.awaitTermination(1750,TimeUnit.MILLISECONDS))throw new java.io.IOException("MEDIA_OUTPUT_OBSERVER_RELEASE_UNCONFIRMED");
    }
}
