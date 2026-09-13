package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.media.AudioManager;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 单读取线程固定周期；独立截止计时器只撤销缓存，不制造替代Binder线程。 */
public final class CallDuplexObserver implements AutoCloseable {
    public static final long REFRESH_MS=1000, REFRESH_WAIT_MS=3250, FIRST_WAIT_MS=1750;
    interface RouteRead {boolean speaker()throws Exception;}
    interface EvidenceSink {
        void observed(AudioCaptureObservation.Sample sample,CachedCallDuplexGuard.Generation token,
                JSONObject decision,JSONObject partial);
        default boolean rawSaved(){return false;}
    }
    private AppMediaReadTrace trace;
    private EvidenceSink evidence;
    private final AudioCaptureObservation.Reader reader;
    private final RouteRead route;
    private final MediaCapture.Clock clock;
    private final CachedCallDuplexGuard guard;
    private final Runnable changed;
    private final Cancellation cancel=new Cancellation();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"d31-call-observer"));
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"d31-call-observer-deadline"));
    private final CountDownLatch first=new CountDownLatch(1);
    private volatile boolean closed;
    private boolean started;
    private volatile long samples,failed,idleWaitMs;
    private final java.util.concurrent.atomic.AtomicLong timedOut=new java.util.concurrent.atomic.AtomicLong();
    private volatile int idleAttempts;
    private volatile boolean restored;
    public CallDuplexObserver(Context context,CachedCallDuplexGuard guard,MediaCapture.Clock clock,Runnable changed) {
        this(new AndroidAudioCaptureObservation(context),()->((AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).isSpeakerphoneOn(),guard,clock,changed);
    }
    CallDuplexObserver(Context context,CachedCallDuplexGuard guard,MediaCapture.Clock clock,Runnable changed,
            AppMediaReadTrace trace,EvidenceSink evidence) {
        this(new AndroidAudioCaptureObservation(context,trace),()->((AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).isSpeakerphoneOn(),guard,clock,changed);
        this.trace=trace;this.evidence=evidence;
    }
    synchronized void evidence(EvidenceSink sink) {
        if(started)throw new IllegalStateException("MEDIA_CALL_OBSERVER_ALREADY_STARTED");
        evidence=sink;
    }
    CallDuplexObserver(AudioCaptureObservation.Reader reader,RouteRead route,CachedCallDuplexGuard guard,MediaCapture.Clock clock,Runnable changed) {
        this.reader=reader;this.route=route;this.guard=guard;this.clock=clock;this.changed=changed;
    }
    private static Thread daemon(Runnable action,String name){Thread t=new Thread(action,name);t.setDaemon(true);return t;}
    public synchronized void start()throws IOException {
        if(started||closed)throw new IOException("MEDIA_CALL_OBSERVER_NOT_REUSABLE");started=true;
        worker.execute(this::periodic);
    }
    private void periodic() {
        long began=System.nanoTime();capture();
        // 超过一秒立即开始下一轮，不累积历史fixedRate任务抢占显式新鲜读取。
        long delay=Math.max(0,REFRESH_MS-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began));
        synchronized(this){if(!closed)worker.schedule(this::periodic,delay,TimeUnit.MILLISECONDS);}
    }
    private void capture() {
        if(closed)return;
        CachedCallDuplexGuard.Generation token=guard.generation();
        long began=clock.elapsed();AtomicBoolean complete=new AtomicBoolean();
        AudioCaptureObservation.Sample sample=null;Boolean before=null,after=null;
        String readError="";
        if(trace!=null)trace.reset();
        ScheduledFuture<?> deadline;
        try {deadline=deadlines.schedule(()->{
                if(complete.compareAndSet(false,true)){timedOut.incrementAndGet();guard.invalidate(token);notifyChanged();}
            },CallDuplexGuard.MAX_SAMPLE_MS,TimeUnit.MILLISECONDS);
        }catch(RejectedExecutionException stopped){guard.invalidate(token);first.countDown();return;}
        try {
            cancel.check();before=route.speaker();
            sample=reader.read(cancel);
            after=route.speaker();long finished=clock.elapsed();cancel.check();
            if(!before.equals(after)){readError="MEDIA_CALL_ROUTE_CHANGED";throw new IOException(readError);}
            if(!closed&&complete.compareAndSet(false,true))guard.sample(sample,after,began,finished,token);
            else readError="MEDIA_CALL_SAMPLE_EXPIRED";
            samples++;
        }catch(Exception|LinkageError failure){failed++;guard.invalidate(token);
            if(readError.isEmpty())readError="MEDIA_CALL_OBSERVATION_READ_FAILED";
        }
        finally {
            complete.set(true);deadline.cancel(false);
            if(evidence!=null)try {
                boolean same=guard.generation()==token;
                JSONObject decision=guard.snapshot().put("round_started_elapsed_ms",began)
                        .put("round_finished_elapsed_ms",clock.elapsed()).put("focus_owned_at_start",token.focus)
                        .put("speaker_before",before==null?JSONObject.NULL:before)
                        .put("speaker_after",after==null?JSONObject.NULL:after).put("read_error",readError);
                decision.put("same_generation",same&&guard.generation()==token);
                evidence.observed(sample,token,decision,trace==null?null:trace.snapshot());
            }catch(Exception|LinkageError ignored){ }
            first.countDown();notifyChanged();
        }
    }
    private void notifyChanged(){if(changed!=null&&!closed)try{changed.run();}catch(Exception|LinkageError ignored){}}
    public void awaitFirst()throws Exception {
        if(!first.await(FIRST_WAIT_MS,TimeUnit.MILLISECONDS)){guard.invalidate();throw new IOException("MEDIA_CALL_FIRST_SAMPLE_TIMEOUT");}
    }
    public void refresh()throws Exception {refresh(REFRESH_WAIT_MS);}
    private void refresh(long timeout)throws Exception {
        if(closed)throw new IOException("MEDIA_CALL_OBSERVER_CLOSED");
        long since=clock.elapsed();Future<?> job=worker.submit(this::capture);
        try {job.get(timeout,TimeUnit.MILLISECONDS);
            if(!guard.observedSince(since))throw new IOException("MEDIA_CALL_FRESH_SAMPLE_UNCONFIRMED");
        }catch(Exception failure){job.cancel(true);guard.invalidate();
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new IOException("MEDIA_CALL_REFRESH_UNCONFIRMED");}
    }
    /** 调用者须先证明peer两端真实退出并释放焦点；预算包含每轮读取与等待。 */
    public void awaitIdle(long budget)throws Exception {
        if(budget<=0||budget>4000)throw new IOException("MEDIA_CALL_IDLE_BUDGET_INVALID");
        long began=System.nanoTime(),deadline=began+TimeUnit.MILLISECONDS.toNanos(budget);idleAttempts=0;
        try {
            for(;;){
                long left=remaining(deadline);if(left<=0)throw new IOException("MEDIA_CALL_IDLE_WAIT_TIMEOUT");
                idleAttempts++;
                try {refresh(Math.min(REFRESH_WAIT_MS,left));
                    if(remaining(deadline)<=0)throw new IOException("MEDIA_CALL_IDLE_WAIT_TIMEOUT");
                    guard.requireIdle();return;
                }catch(IOException pending){if(closed||remaining(deadline)<=0)throw new IOException("MEDIA_CALL_IDLE_WAIT_TIMEOUT");}
                Thread.sleep(Math.min(100,remaining(deadline)));
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("MEDIA_CALL_IDLE_INTERRUPTED");}
        finally{idleWaitMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);}
    }
    static long remaining(long deadline){return Math.max(0,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));}
    void routeRestored(){restored=true;}
    public JSONObject snapshot()throws Exception {return guard.snapshot().put("sample_count",samples).put("read_failure_count",failed).put("timeout_count",timedOut.get())
            .put("idle_wait_ms",idleWaitMs).put("idle_wait_attempts",idleAttempts).put("route_restored",restored)
            .put("raw_audio_saved",false).put("raw_observations_saved",evidence!=null&&evidence.rawSaved());}
    public void close()throws Exception {close(FIRST_WAIT_MS);}
    void close(long budget)throws Exception {
        synchronized(this){closed=true;cancel.cancel();guard.close();worker.shutdownNow();deadlines.shutdownNow();}
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(0,budget));
        if(!worker.awaitTermination(remaining(deadline),TimeUnit.MILLISECONDS)
                ||!deadlines.awaitTermination(remaining(deadline),TimeUnit.MILLISECONDS))throw new IOException("MEDIA_CALL_OBSERVER_RELEASE_UNCONFIRMED");
    }
}
