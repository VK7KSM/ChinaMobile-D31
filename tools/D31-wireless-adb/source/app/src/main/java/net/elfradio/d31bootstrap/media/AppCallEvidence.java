package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 原轮取证旁路；仅保存PCM累计数值，原始身份和系统转储仅进入APP私有目录。 */
final class AppCallEvidence implements CallDuplexObserver.EvidenceSink {
    static final int MAX_STATS=256,MAX_STATS_BYTES=8192;
    interface Source {JSONObject read()throws Exception;}
    interface Changed {void changed(JSONObject value);}
    private final AppMediaRtcDiagnostics raw;
    private final File statsRoot;
    private final String id;
    private final Source source;
    private final Changed changed;
    private final MediaCapture.Clock clock;
    private final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(4),r->{Thread t=new Thread(r,"d31-call-evidence");t.setDaemon(true);return t;});
    private final AtomicBoolean statsPending=new AtomicBoolean();
    private File statsDirectory;
    private boolean identitySaved,duplexSaved,rejectionSaved,closing;
    private volatile int statisticsSaved,dropped;
    private volatile int rawFailures,rawRequested;
    private volatile String rawError="";
    private volatile boolean finalSaved;
    private boolean releaseUnconfirmed;
    private volatile String error="";
    private volatile String latest="{}";
    private Future<?> completion;
    private JSONObject finalView;

    AppCallEvidence(File files,String hash,MediaCapture.Clock clock,Source source,Changed changed) {
        this(files,hash,clock,source,changed,UUID.randomUUID().toString());
    }
    AppCallEvidence(File files,String hash,MediaCapture.Clock clock,Source source,Changed changed,String id) {
        this.id=id;this.clock=clock;this.source=source;this.changed=changed;
        raw=new AppMediaRtcDiagnostics(new File(files,"media/call-duplex-diagnostics"),id,hash);
        statsRoot=new File(files,"media/call-duplex-statistics");
    }
    public synchronized void observed(AudioCaptureObservation.Sample sample,CachedCallDuplexGuard.Generation token,
            JSONObject decision,JSONObject partial) {
        if(closing)return;
        try {
            JSONObject stats=publicView(source.read()).put("statistics_elapsed_ms",clock.elapsed());
            JSONObject round=roundView(decision);
            stats.put("round",round);
            latest=stats.toString();
            boolean rejected=!decision.optBoolean("fresh")||!decision.optBoolean("same_generation")
                    ||!decision.optString("read_error").isEmpty();
            String phase="";
            // 首次拒绝优先；初始idle不占用身份与双向成功的原件配额。
            if(rejected&&!rejectionSaved){rejectionSaved=true;phase="FIRST_REJECTED_ROUND";}
            else if(token.input!=null&&token.output!=null&&!identitySaved){identitySaved=true;phase="FIRST_IDENTITY_ROUND";}
            else if(decision.optBoolean("input_owned")&&decision.optBoolean("output_owned")&&!duplexSaved){
                duplexSaved=true;phase="FIRST_DUPLEX_ROUND";
            }
            if(!phase.isEmpty()) {
                JSONObject privateDecision=new JSONObject(decision.toString()).put("scope","CALL_DUPLEX_OBSERVATION")
                        .put("phase",phase).put("statistics",stats)
                        .put("actual_input_identity",token.input==null?new JSONObject():token.input.privateJson())
                        .put("actual_output_identity",token.output==null?new JSONObject():token.output.privateJson());
                JSONObject frozenPartial=partial==null?null:new JSONObject(partial.toString());
                rawRequested++;
                try{writer.execute(()->{
                    try {
                        int before=raw.snapshot().getInt("saved_samples");
                        raw.save(sample,privateDecision.optString("read_error"),token.input!=null?token.input.pid:token.output!=null?token.output.pid:-1,
                                token.input==null?0:token.input.session,privateDecision.optLong("round_finished_elapsed_ms"),privateDecision,frozenPartial);
                        JSONObject saved=raw.snapshot();
                        if(saved.getInt("saved_samples")!=before+1||!saved.optString("storage_error").isEmpty())
                            rawFailed(saved.optString("storage_error"));
                    }catch(Exception|LinkageError failure){rawFailed("MEDIA_CALL_EVIDENCE_WRITE_UNCONFIRMED");}
                    finally{publish(stats);}
                });}catch(RejectedExecutionException failure){rawFailed("MEDIA_CALL_EVIDENCE_RAW_QUEUE_REJECTED");}
            }
            if(statisticsSaved<MAX_STATS&&statsPending.compareAndSet(false,true))writer.execute(()->{
                try{if(statisticsSaved<MAX_STATS){saveStats(stats,"stats-"+(statisticsSaved+1)+".json");statisticsSaved++;}}
                catch(Exception|LinkageError failure){error="MEDIA_CALL_EVIDENCE_WRITE_UNCONFIRMED";}
                finally{statsPending.set(false);publish(stats);}
            });
            else {dropped++;if(statisticsSaved>=MAX_STATS)publish(stats);}
        }catch(Exception|LinkageError failure){error="MEDIA_CALL_EVIDENCE_CAPTURE_INCOMPLETE";}
    }
    private void saveStats(JSONObject value,String name)throws Exception {
        if(statsDirectory==null) {
            if(!id.matches("[A-Za-z0-9_-]{1,96}"))throw new IOException("MEDIA_CALL_EVIDENCE_IDENTITY_INVALID");
            MediaFiles.directory(statsRoot);File[] prior=statsRoot.listFiles();
            if(prior==null||prior.length>=AppMediaRtcDiagnostics.MAX_SESSIONS)throw new IOException("MEDIA_CALL_EVIDENCE_STORAGE_LIMIT");
            File chosen=MediaFiles.plain(new File(statsRoot,id));
            if(!chosen.mkdir())throw new IOException("MEDIA_CALL_EVIDENCE_NO_OVERWRITE");
            statsDirectory=chosen;
        }
        JSONObject frozen=new JSONObject(value.toString()).put("contains_audio",false);
        if(frozen.toString().getBytes("UTF-8").length>MAX_STATS_BYTES)throw new IOException("MEDIA_CALL_EVIDENCE_SIZE_LIMIT");
        MediaFiles.writeNew(new File(statsDirectory,name),frozen);
    }
    private void publish(JSONObject stats) {
        try{JSONObject value=new JSONObject(stats.toString()).put("storage",storage());latest=value.toString();
            if(changed!=null)changed.changed(value);
        }catch(Exception|LinkageError failure){error="MEDIA_CALL_EVIDENCE_PUBLISH_INCOMPLETE";}
    }
    JSONObject storage()throws Exception {
        JSONObject value=raw.snapshot();
        return value.put("storage_error",rawError.isEmpty()?code(value.optString("storage_error")):rawError)
                .put("raw_requested",rawRequested).put("raw_failures",rawFailures).put("final_saved",finalSaved)
                .put("raw_unconfirmed",Math.max(0,rawRequested-value.optInt("saved_samples")-rawFailures))
                .put("statistics_saved",statisticsSaved).put("statistics_skipped",dropped)
                .put("evidence_error",error).put("statistics_limit",MAX_STATS).put("contains_audio",false);
    }
    private synchronized void rawFailed(String code){rawFailures++;
        if(rawError.isEmpty())rawError=code.isEmpty()?"MEDIA_CALL_EVIDENCE_WRITE_UNCONFIRMED":code(code);}
    public boolean rawSaved(){try{return raw.snapshot().optInt("saved_samples")>0;}catch(Exception ignored){return false;}}
    /** 与Operations共享剩余清理预算；存储错误不改变媒体裁定，线程未退出仍必须上报。 */
    void finish(JSONObject media,long budget)throws Exception {
        Future<?> task;
        synchronized(this) {
            if(releaseUnconfirmed)throw new IOException("MEDIA_CALL_EVIDENCE_RELEASE_UNCONFIRMED");
            if(!closing) {
                closing=true;
                try {
                JSONObject terminal=publicView(media).put("statistics_elapsed_ms",clock.elapsed()).put("terminal",true)
                        .put("media_resources_cleanup_complete",media.optBoolean("cleanup_complete")).put("cleanup_complete",false);
                finalView=terminal;
                JSONObject round=new JSONObject(latest).optJSONObject("round");
                if(round!=null)terminal.put("last_round",round);
                completion=writer.submit(()->{
                    try{saveStats(terminal.put("storage",storage()),"final.json");finalSaved=true;}
                    catch(Exception|LinkageError failure){error="MEDIA_CALL_EVIDENCE_WRITE_UNCONFIRMED";}
                    finally{publish(terminal);}
                });
                }catch(Exception|LinkageError failure){error="MEDIA_CALL_EVIDENCE_FINAL_WRITE_UNCONFIRMED";}
                finally{writer.shutdown();}
            }
            task=completion;
        }
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(0,budget));
        try {
            if(task!=null)task.get(CallDuplexObserver.remaining(deadline),TimeUnit.MILLISECONDS);
            if(!writer.awaitTermination(CallDuplexObserver.remaining(deadline),TimeUnit.MILLISECONDS))throw new IOException();
            synchronized(this){
                if(releaseUnconfirmed)throw new IOException("MEDIA_CALL_EVIDENCE_RELEASE_UNCONFIRMED");
                JSONObject value=finalView==null?publicView(media):new JSONObject(finalView.toString());
                publish(value.put("cleanup_complete",media.optBoolean("cleanup_complete"))
                    .put("evidence_writer_exit_verified",true));}
        }catch(Exception failure){
            synchronized(this){releaseUnconfirmed=true;error="MEDIA_CALL_EVIDENCE_RELEASE_UNCONFIRMED";}
            writer.shutdownNow();
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new IOException(error);
        }
    }
    static JSONObject publicView(JSONObject input)throws Exception {
        JSONObject value=new JSONObject().put("contains_audio",false).put("audio_content","NOT_VERIFIED");
        if(input==null)return value;
        JSONObject peer=input.optJSONObject("peer");
        if(peer!=null) {
            JSONObject clean=new JSONObject();
            booleans(peer,clean,"ice_connected negotiated prepared unmuted input_identity_observed output_identity_observed capture_started playback_started capture_frames_seen playback_frames_seen peer_cleanup_complete release_unconfirmed");
            clean.put("error",code(peer.optString("error")));
            for(String side:new String[]{"capture_pcm","playback_pcm"}) {
                JSONObject pcm=peer.optJSONObject(side);
                if(pcm!=null){JSONObject stats=new JSONObject().put("contains_audio",false).put("used_for_authorization",false)
                        .put("scope","PCM16_CALLBACK_AFTER_WEBRTC_SOFTWARE_MUTE");
                    numbers(pcm,stats,"callbacks pcm_frames samples guard_ready_callbacks invalid_callbacks",Long.MAX_VALUE);
                    int expected="capture_pcm".equals(side)?16000:48000;
                    if(pcm.opt("sample_rate") instanceof Number&&pcm.optDouble("sample_rate")==expected)stats.put("sample_rate",expected);
                    numbers(pcm,stats,"peak rms",1);
                    clean.put(side,stats);
                }
            }
            value.put("peer",clean);
        }
        JSONObject duplex=input.optJSONObject("duplex");
        if(duplex!=null) {
            JSONObject clean=new JSONObject();
            booleans(duplex,clean,"fresh idle input_owned output_owned route_restored raw_audio_saved raw_observations_saved");
            numbers(duplex,clean,"max_age_ms max_sample_ms sample_count read_failure_count timeout_count idle_wait_ms idle_wait_attempts",Long.MAX_VALUE);
            clean.put("reason",code(duplex.optString("reason")));value.put("duplex",clean);
        }
        booleans(input,value,"cleanup_complete terminal media_resources_cleanup_complete evidence_writer_exit_verified");
        value.put("cleanup_reason",code(input.optString("cleanup_reason")));
        numbers(input,value,"statistics_elapsed_ms",Long.MAX_VALUE);
        for(String key:new String[]{"round","last_round"})if(input.optJSONObject(key)!=null)value.put(key,roundView(input.getJSONObject(key)));
        JSONObject storage=input.optJSONObject("storage");
        if(storage!=null){JSONObject clean=new JSONObject().put("contains_audio",false);
            numbers(storage,clean,"saved_samples raw_requested raw_failures raw_unconfirmed statistics_saved statistics_skipped statistics_limit",Long.MAX_VALUE);
            booleans(storage,clean,"final_saved");
            clean.put("storage_error",code(storage.optString("storage_error"))).put("evidence_error",code(storage.optString("evidence_error")));
            value.put("storage",clean);
        }
        return value;
    }
    private static JSONObject roundView(JSONObject input)throws Exception {
        JSONObject out=new JSONObject();
        booleans(input,out,"fresh idle input_owned output_owned focus_owned_at_start speaker_before speaker_after same_generation");
        numbers(input,out,"round_started_elapsed_ms round_finished_elapsed_ms",Long.MAX_VALUE);
        return out.put("reason",code(input.optString("reason"))).put("read_error",code(input.optString("read_error")));
    }
    private static void booleans(JSONObject from,JSONObject to,String keys)throws Exception {
        for(String key:keys.split(" "))if(from.opt(key) instanceof Boolean)to.put(key,from.get(key));
    }
    private static void numbers(JSONObject from,JSONObject to,String keys,double maximum)throws Exception {
        for(String key:keys.split(" ")){Object value=from.opt(key);
            if(value instanceof Number){double n=((Number)value).doubleValue();
                if(!Double.isNaN(n)&&!Double.isInfinite(n)&&n>=0&&n<=maximum)to.put(key,value);
            }
        }
    }
    private static final java.util.Set<String> CODES=new java.util.HashSet<>(java.util.Arrays.asList((
            "MEDIA_CALL_ANSWER_FAILED MEDIA_CALL_AUDIO_THREAD_RELEASE_UNCONFIRMED MEDIA_CALL_CALLBACK_RELEASE_UNCONFIRMED "
            +"MEDIA_CALL_CAPTURE_FAILED MEDIA_CALL_CAPTURE_INIT_FAILED MEDIA_CALL_CAPTURE_START_FAILED MEDIA_CALL_CAPTURE_STOPPED "
            +"MEDIA_CALL_DATA_CHANNEL_FORBIDDEN MEDIA_CALL_DEPENDENCY_MISSING MEDIA_CALL_DIRECTION_INVALID MEDIA_CALL_DUPLEX_REVOKED "
            +"MEDIA_CALL_DUPLEX_UNVERIFIED MEDIA_CALL_ICE_LOST MEDIA_CALL_IDENTITY_CHANGED MEDIA_CALL_INPUT_IDENTITY_INVALID "
            +"MEDIA_CALL_NATIVE_FAILED MEDIA_CALL_NEGOTIATION_STATE MEDIA_CALL_NOT_CONNECTED MEDIA_CALL_OFFER_FAILED "
            +"MEDIA_CALL_OPERATION_TIMEOUT MEDIA_CALL_OUTPUT_IDENTITY_INVALID MEDIA_CALL_PCM_FORMAT_INVALID MEDIA_CALL_PLAYOUT_FAILED "
            +"MEDIA_CALL_PLAYOUT_INIT_FAILED MEDIA_CALL_PLAYOUT_START_FAILED MEDIA_CALL_PLAYOUT_STOPPED MEDIA_CALL_PUBLISH_STATE "
            +"MEDIA_CALL_RELEASE_UNCONFIRMED MEDIA_CALL_REMOTE_ERROR MEDIA_CALL_RESPONSE_MISSING MEDIA_CALL_ROUTE_UNSUPPORTED "
            +"MEDIA_CALL_ROUTE_UNVERIFIED MEDIA_CALL_SDP_FAILED MEDIA_CALL_SDP_INVALID MEDIA_CALL_SENDER_INVALID "
            +"MEDIA_CALL_SIGNALING_INVALID MEDIA_CALL_SOFTWARE_MUTE_CONTRACT_INVALID MEDIA_CALL_SOFTWARE_MUTE_FAILED "
            +"MEDIA_CALL_SUBSCRIBE_INVALID MEDIA_CALL_SUBSCRIBE_STATE MEDIA_CALL_TOPOLOGY_INVALID MEDIA_CALL_UNEXPECTED_ANSWER "
            +"MEDIA_CALL_UNEXPECTED_TRACK MEDIA_NATIVE_LOAD_FAILED MEDIA_PEER_INIT_FAILED MEDIA_PEER_NOT_REUSABLE "
            +"MEDIA_RECORD_PERMISSION_MISSING MEDIA_TRANSCEIVER_FAILED MEDIA_CALL_DUPLEX_OBSERVED MEDIA_CALL_DUPLEX_PENDING "
            +"MEDIA_CALL_IDLE_OBSERVED MEDIA_CALL_SAMPLE_UNAVAILABLE MEDIA_CALL_OBSERVATION_REVOKED "
            +"MEDIA_CALL_ROUTE_CHANGED MEDIA_CALL_SAMPLE_EXPIRED MEDIA_CALL_OBSERVATION_READ_FAILED "
            +"MEDIA_CALL_EVIDENCE_WRITE_UNCONFIRMED MEDIA_CALL_EVIDENCE_CAPTURE_INCOMPLETE MEDIA_CALL_EVIDENCE_PUBLISH_INCOMPLETE "
            +"MEDIA_CALL_EVIDENCE_RELEASE_UNCONFIRMED MEDIA_DIAGNOSTIC_IDENTITY MEDIA_DIAGNOSTIC_NO_OVERWRITE "
            +"MEDIA_CALL_EVIDENCE_FINAL_WRITE_UNCONFIRMED MEDIA_CALL_EVIDENCE_RAW_QUEUE_REJECTED "
            +"MEDIA_DIAGNOSTIC_SIZE_LIMIT MEDIA_DIAGNOSTIC_STORAGE_LIMIT MEDIA_DIAGNOSTIC_WRITE_FAILED "
            +"MEDIA_LINK_PATH_REJECTED MEDIA_DIRECTORY_UNAVAILABLE MEDIA_CALL_DIAGNOSTIC_ERROR_REDACTED").split(" ")));
    private static String code(String value){return value==null||value.isEmpty()?"":CODES.contains(value)?value:"MEDIA_CALL_DIAGNOSTIC_ERROR_REDACTED";}
}
