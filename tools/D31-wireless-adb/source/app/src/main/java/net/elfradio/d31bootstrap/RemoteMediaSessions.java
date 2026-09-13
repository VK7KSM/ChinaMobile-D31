package net.elfradio.d31bootstrap;

import android.content.Context;
import java.io.File;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import net.elfradio.d31bootstrap.media.AppMediaBridge;
import net.elfradio.d31bootstrap.media.RtcOffer;
import org.json.*;

/** 核心仅分派异步APP桥；空上报不是关闭信号，WebSocket负责远端停止。 */
public final class RemoteMediaSessions implements AutoCloseable {
    interface Bridge extends AutoCloseable {
        void start(String hash,JSONObject offer,AppMediaBridge.Callback callback);
        void query(String id,AppMediaBridge.Callback callback);
        void stop(String id,AppMediaBridge.Callback callback);
        void close();
    }
    interface Factory { Bridge create(); }
    interface Clock { long wall();long elapsed(); }
    private final Factory factory;
    private final Clock clock;
    private final URI origin;
    private final String hash;
    private final Map<String,Long> seen=new HashMap<>();
    private Bridge bridge;
    private boolean enabled,videoEnabled,pttEnabled,prepareEnabled,closed,inflight,stopping,stopDispatched,releaseUnknown;
    private Runnable changed;
    private Thread readinessThread;
    private String lastNotified="";
    private String id="",mode="",state="idle",reason="";
    private long nextQuery,deadline;
    private JSONObject detail=new JSONObject();
    public RemoteMediaSessions(Context context,File privateRoot,String apk,Runnable changed){
        this(context,apk,URI.create("https://v.elfradio.net"));
        if(privateRoot==null||!privateRoot.isAbsolute()||apk==null||!new File(apk).isAbsolute())
            throw new IllegalArgumentException("MEDIA_PRIVATE_PATH_INVALID");
        this.changed=changed;
        readinessThread=new Thread(()->{
            boolean ready=available(context);
            boolean camera=videoAvailable(context);
            boolean output=pttAvailable(context);
            synchronized(RemoteMediaSessions.this){
                if(!closed){enabled=ready;videoEnabled=camera;pttEnabled=output;prepareEnabled=ready&&output;if(!ready&&!output)reason="MEDIA_STATIC_CAPABILITY_NOT_READY";changed();}
            }
        },"d31-media-readiness");
        readinessThread.setDaemon(true);readinessThread.start();
    }
    public RemoteMediaSessions(Context context,String apkSha256,URI controlOrigin){
        this(()->new Bridge(){
            final AppMediaBridge value=new AppMediaBridge(context);
            final ExecutorService hashes=new ThreadPoolExecutor(0,1,1,TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>(),
                    r->{Thread t=new Thread(r,"d31-media-apk-check");t.setDaemon(true);return t;});
            public void start(String hash,JSONObject offer,AppMediaBridge.Callback cb){
                hashes.execute(()->{try{value.start(hash.matches("[a-f0-9]{64}")?hash:RescueFiles.sha256(new File(hash)),offer,cb);}
                    catch(Exception failure){cb.failed("MEDIA_APK_CHECK_FAILED");}});
            }
            public void query(String id,AppMediaBridge.Callback cb){value.query(id,cb);}
            public void stop(String id,AppMediaBridge.Callback cb){value.stop(id,cb);}
            public void close(){hashes.shutdownNow();value.close();}
        },apkSha256,controlOrigin,new Clock(){
            public long wall(){return System.currentTimeMillis();}
            public long elapsed(){return android.os.SystemClock.elapsedRealtime();}
        });
    }
    RemoteMediaSessions(Factory factory,String hash,URI origin,Clock clock){
        if(factory==null||clock==null||hash==null||!(hash.matches("[a-f0-9]{64}")||new File(hash).isAbsolute()))throw new IllegalArgumentException("MEDIA_DEPENDENCY_INVALID");
        this.factory=factory;this.hash=hash;this.origin=origin;this.clock=clock;
    }
    /** 在主线已有能力采样工作线程调用；仅查包/权限，不开服务、JNI或麦克风。 */
    public static boolean available(Context context){
        try{
            if(context==null||android.os.Build.VERSION.SDK_INT!=23||!"hct6735_66_m0".equals(android.os.Build.DEVICE))return false;
            android.content.pm.PackageManager pm=context.getPackageManager();String pkg="net.elfradio.d31bootstrap";
            android.content.pm.ServiceInfo service=pm.getServiceInfo(new android.content.ComponentName(pkg,pkg+".media.AppMediaService"),0);
            android.app.AppOpsManager ops=(android.app.AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);
            return service.enabled&&!service.exported&&service.applicationInfo.enabled
                    &&service.applicationInfo.uid>=10000&&ops!=null
                    &&ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_RECORD_AUDIO,service.applicationInfo.uid,pkg)==android.app.AppOpsManager.MODE_ALLOWED
                    &&pm.checkPermission(android.Manifest.permission.RECORD_AUDIO,pkg)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        }catch(Exception|LinkageError unavailable){return false;}
    }
    public synchronized void setAvailable(boolean value){enabled=value;if(!value&&!"ptt".equals(mode))stop();changed();}
    private static boolean pttAvailable(Context context){
        try{
            if(context==null||android.os.Build.VERSION.SDK_INT!=23||!"hct6735_66_m0".equals(android.os.Build.DEVICE))return false;
            android.content.pm.PackageManager pm=context.getPackageManager();String pkg="net.elfradio.d31bootstrap";
            android.content.pm.ServiceInfo service=pm.getServiceInfo(new android.content.ComponentName(pkg,pkg+".media.AppMediaService"),0);
            return service.enabled&&!service.exported&&service.applicationInfo.enabled&&service.applicationInfo.uid>=10000
                    &&pm.checkPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS,pkg)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        }catch(Exception|LinkageError unavailable){return false;}
    }
    synchronized void setPttAvailable(boolean value){pttEnabled=value;if(!value&&("ptt".equals(mode)||"call".equals(mode)||"prepare".equals(mode)))stop();changed();}
    private static boolean videoAvailable(Context context){
        try{
            android.content.pm.PackageManager pm=context.getPackageManager();String pkg="net.elfradio.d31bootstrap";
            android.content.pm.ApplicationInfo app=pm.getApplicationInfo(pkg,0);
            android.app.AppOpsManager ops=(android.app.AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);
            return pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
                    &&pm.checkPermission(android.Manifest.permission.CAMERA,pkg)==android.content.pm.PackageManager.PERMISSION_GRANTED
                    &&ops!=null&&ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_CAMERA,app.uid,pkg)==android.app.AppOpsManager.MODE_ALLOWED;
        }catch(Exception|LinkageError unavailable){return false;}
    }
    synchronized void setVideoAvailable(boolean value){videoEnabled=value;if(cameraOperationUnavailable())stop();changed();}
    synchronized void setPrepareAvailable(boolean value){prepareEnabled=value;if(!value&&"prepare".equals(mode))stop();changed();}
    private boolean prepareAvailable(){return prepareEnabled&&enabled&&pttEnabled&&available();}
    private boolean cameraOperationUnavailable(){
        String operation="prepare".equals(mode)?detail.optString("active_mode"):mode;
        return !videoEnabled&&("video".equals(operation)||"photo".equals(operation));
    }
    public synchronized boolean available(){return (enabled||pttEnabled)&&!closed&&!releaseUnknown;}
    public synchronized boolean active(){return !id.isEmpty()||inflight||releaseUnknown;}
    private void changed(){
        JSONObject notice=snapshot();notice.remove("session");
        String current=notice.toString();if(current.equals(lastNotified))return;lastNotified=current;
        if(changed!=null)try{changed.run();}catch(Exception ignored){}
    }
    void listener(Runnable listener){this.changed=listener;}
    public synchronized JSONArray modes(){
        JSONArray result=new JSONArray();if(available()){if(enabled){result.put("microphone");if(videoEnabled)result.put("video");}
            if(pttEnabled)result.put("ptt");if(enabled&&pttEnabled)result.put("call");}return result;
    }
    private boolean modeAvailable(String value){return "prepare".equals(value)?prepareAvailable():"ptt".equals(value)?pttEnabled:enabled&&(!"call".equals(value)||pttEnabled);}
    public synchronized JSONObject snapshot(){
        try{return new JSONObject().put("managed_media",available()).put("managed_media_modes",modes())
                .put("managed_media_prepare_v1",prepareAvailable())
                .put("session_id",id).put("mode",id.isEmpty()?"":mode).put("state",state).put("reason",reason)
                .put("cleanup_complete",id.isEmpty()&&!inflight&&!releaseUnknown).put("session",new JSONObject(detail.toString()));
        }catch(JSONException impossible){throw new IllegalStateException(impossible);}
    }
    public synchronized void accept(JSONObject value){
        accept(value,null);
    }
    public synchronized void accept(JSONObject value,JSONObject credentials){
        if(value==null||!available())return;
        final RtcOffer offer;
        try{offer=RtcOffer.parse(value,origin,clock.wall());}catch(Exception invalid){reason="MEDIA_OFFER_REJECTED";changed();return;}
        if(!modeAvailable(offer.mode)){reason="MEDIA_MODE_NOT_AVAILABLE";changed();return;}
        if("video".equals(offer.mode)&&!videoEnabled){reason="MEDIA_VIDEO_CAPABILITY_NOT_READY";changed();return;}
        if(!id.isEmpty()||inflight)return;
        Iterator<Map.Entry<String,Long>> previous=seen.entrySet().iterator();
        while(previous.hasNext())if(previous.next().getValue()<clock.wall())previous.remove();
        if(seen.containsKey(offer.id))return;
        if(seen.size()>=128){reason="MEDIA_OFFER_RATE_LIMIT";return;}
        seen.put(offer.id,offer.expiresAt);id=offer.id;mode=offer.mode;state="starting";reason="";stopping=false;stopDispatched=false;inflight=true;
        deadline=clock.elapsed()+Math.min(45000,offer.expiresAt-clock.wall());detail=new JSONObject();
        try{
            if(bridge==null)bridge=factory.create();
            JSONObject frozen=new JSONObject(value.toString());frozen.remove("_credentials");
            if("prepare".equals(mode)&&credentials!=null)frozen.put("_credentials",new JSONObject(credentials.toString()));
            bridge.start(hash,frozen,callback(id,true));
        }catch(Exception failure){inflight=false;reason="MEDIA_BRIDGE_START_FAILED";stop();}
        changed();
    }
    private AppMediaBridge.Callback callback(final String expected,final boolean initial){
        return new AppMediaBridge.Callback(){
            public void completed(JSONObject value){synchronized(RemoteMediaSessions.this){
                if(!expected.equals(id))return;
                try{
                inflight=false;nextQuery=clock.elapsed()+3000;
                if(value==null||!expected.equals(value.optString("session_id"))){reason="MEDIA_BRIDGE_SESSION_MISMATCH";stop();return;}
                String actual=value.optString("state");
                String sessionReason=value.optString("reason");
                if(sessionReason.matches("MEDIA_[A-Z0-9_]{1,80}"))reason=sessionReason;
                // APP返回的快照不含offer/令牌；只保留协议状态，不转发任意回复。
                try{detail=new JSONObject().put("state",actual).put("published",value.optBoolean("published"))
                        .put("ice_connected",value.optBoolean("ice_connected"))
                        .put("capture_verified",value.optBoolean("capture_verified"))
                        .put("reason",reason).put("diagnostics",diagnostics(value.optJSONObject("diagnostics")))
                        .put("cleanup_complete",value.optBoolean("cleanup_complete"))
                        .put("ptt_evidence",pttEvidence(value.optJSONObject("ptt_evidence")));
                    if("call".equals(mode))appendCallState(detail,value);
                    if("prepare".equals(mode))appendPreparedState(detail,value);
                }catch(JSONException ignored){}
                String cleanup=value.optString("cleanup_reason");
                try{if(cleanup.matches("MEDIA_[A-Z0-9_]{1,80}"))detail.put("cleanup_reason",cleanup);}catch(JSONException ignored){}
                if("closed".equals(actual)||"NOT_FOUND".equals(actual)){
                    state="closed";id="";stopping=false;if(closed&&bridge!=null){bridge.close();bridge=null;}return;
                }
                if("release_unconfirmed".equals(actual)){releaseUnknown=true;state=actual;return;}
                if("prepare".equals(mode)){
                    if(Boolean.TRUE.equals(value.opt("transport_ready"))&&!stopping)deadline=Long.MAX_VALUE;
                }else if("streaming".equals(actual)&&!"streaming".equals(state)&&!stopping)deadline=clock.elapsed()+1800000;
                state=actual;
                if(closed||!modeAvailable(mode)||cameraOperationUnavailable()||stopping)stop();
                }finally{changed();}
            }}
            public void failed(String code){synchronized(RemoteMediaSessions.this){
                if(!expected.equals(id))return;
                inflight=false;reason=code!=null&&code.matches("MEDIA_[A-Z0-9_]{1,80}")?code:"MEDIA_BRIDGE_FAILED";
                nextQuery=clock.elapsed()+3000;
                if(!stopping)stop();else state="closing";
                changed();
            }}
        };
    }
    /** 待命连接只上报当前操作及活动状态；上传凭据不进入快照。 */
    private static void appendPreparedState(JSONObject result,JSONObject value)throws JSONException {
        for(String key:new String[]{"transport_ready","ready","capture_started","playback_started","video_started"})
            if(value.opt(key) instanceof Boolean)result.put(key,value.opt(key));
        Object operation=value.opt("operation");
        if((operation instanceof Integer||operation instanceof Long)&&((Number)operation).longValue()>=0)result.put("operation",operation);
        String active=value.optString("active_mode");
        if(active.isEmpty()||Arrays.asList("ptt","call","microphone","video","photo","alarm").contains(active))result.put("active_mode",active);
    }
    /** 旧call状态仅透传严格布尔值；不将ready推断为语音内容已验收。 */
    private static void appendCallState(JSONObject result,JSONObject value)throws JSONException {
        for(String key:new String[]{"ready","subscribed","input_verified","output_verified","capture_frames_seen",
                "playback_frames_seen","unmuted","input_ownership_required","output_ownership_required"})
            if(value.opt(key) instanceof Boolean)result.put(key,value.opt(key));
        if("speaker".equals(value.optString("route")))result.put("route","speaker");
        result.put("local_recording",false).put("remote_audio_content","NOT_VERIFIED");
    }
    private static JSONObject pttEvidence(JSONObject source)throws JSONException {
        JSONObject result=new JSONObject();if(source==null)return result;
        for(String key:new String[]{"complete_pairs","saved_samples"}){
            Object value=source.opt(key);if(value instanceof Integer&&(Integer)value>=0)result.put(key,value);
        }
        String error=source.optString("evidence_error");
        if(error.matches("[A-Z0-9_]{0,100}"))result.put("evidence_error",error);
        JSONObject playback=source.optJSONObject("playback");
        if(playback!=null){
            JSONObject selected=diagnostics(playback);
            for(String key:new String[]{"subscribed","ice_connected","actual_remote_track","playback_started","playback_frames_seen","ready"})
                if(playback.opt(key) instanceof Boolean)selected.put(key,playback.opt(key));
            JSONObject shape=playback.optJSONObject("subscription_shape");
            if(shape!=null){
                JSONObject compact=new JSONObject();
                for(String key:new String[]{"error_code_type","tracks_type","track_error_type","location_type"}){
                    String value=shape.optString(key);if(value.matches("[A-Z_]{1,32}"))compact.put(key,value);
                }
                for(String key:new String[]{"location_remote","track_name_audio","mid_valid"})
                    if(shape.opt(key) instanceof Boolean)compact.put(key,shape.opt(key));
                if(shape.opt("track_count") instanceof Integer)compact.put("track_count",shape.opt("track_count"));
                selected.put("subscription_shape",compact);
            }
            result.put("playback",selected);
        }
        JSONObject observer=source.optJSONObject("output_observer");
        if(observer!=null){
            JSONObject selected=new JSONObject();
            for(String key:new String[]{"idle_wait_ms","idle_wait_attempts"}){
                Object value=observer.opt(key);if(value instanceof Number&&((Number)value).longValue()>=0)selected.put(key,value);
            }
            for(String key:new String[]{"idle_wait_reason","idle_wait_first_reason","observer_error"}){
                String value=observer.optString(key);if(value.matches("[A-Z0-9_]{0,100}"))selected.put(key,value);
            }
            for(String key:new String[]{"route_restored","late_route_restore"})
                if(observer.opt(key) instanceof Boolean)selected.put(key,observer.opt(key));
            result.put("output_observer",selected);
        }
        return result;
    }
    private static JSONObject diagnostics(JSONObject source)throws JSONException {
        JSONObject result=new JSONObject();if(source==null)return result;
        String stage=source.optString("failed_stage");
        if(java.util.Arrays.asList("OPEN","PUBLISH","APPLY_PUBLISH","SUBSCRIBE","NEGOTIATION","ACTIVATE","BEFORE_ACTIVATE",
                "ROUTE_OPEN","BACKEND_ACTIVATE","HARDWARE_STOP","ROUTE_RECOVER","AFTER_DEACTIVATE","SWITCH_CAMERA").contains(stage)
                ||stage.matches("MEDIA_PREPARED_[A-Z0-9_]{1,80}"))result.put("failed_stage",stage);
        JSONObject pcm=source.optJSONObject("pcm"),video=source.optJSONObject("video");
        if(pcm!=null){
            JSONObject selected=new JSONObject();
            for(String key:new String[]{"callbacks","pcm_frames","samples","guard_ready_callbacks","invalid_callbacks","peak","rms"}){
                Object value=pcm.opt(key);if(value instanceof Number&&!Double.isNaN(((Number)value).doubleValue())&&!Double.isInfinite(((Number)value).doubleValue())&&((Number)value).doubleValue()>=0)selected.put(key,value);
            }
            selected.put("contains_audio",false).put("used_for_authorization",false);result.put("pcm",selected);
        }
        if(video!=null){
            JSONObject selected=new JSONObject();
            for(String key:new String[]{"state","error","camera"}){String value=video.optString(key);if(value.matches("[A-Za-z0-9_]{0,100}"))selected.put(key,value);}
            for(String key:new String[]{"cameras","width","height","first_frame_elapsed_ms"}){Object value=video.opt(key);if(value instanceof Number)selected.put(key,value);}
            for(String key:new String[]{"track_attached","capturer_started","first_frame_received","cleanup_complete"})if(video.opt(key) instanceof Boolean)selected.put(key,video.opt(key));
            result.put("video",selected);
        }
        JSONObject guard=source.optJSONObject("guard"),parameters=source.optJSONObject("record_parameters");
        if(parameters!=null){JSONObject selected=new JSONObject();for(String key:new String[]{"sample_rate","channels","audio_format","audio_source"}){
            Object value=parameters.opt(key);if(value instanceof Integer)selected.put(key,value);
        }result.put("record_parameters",selected);}
        if(guard!=null){
            JSONObject selected=new JSONObject();String code=guard.optString("failure_code");
            if(code.isEmpty()||code.matches("MEDIA_[A-Z0-9_]{1,80}"))selected.put("failure_code",code);
            JSONObject sample=guard.optJSONObject("last_sample");
            if(sample!=null){JSONObject compact=new JSONObject();for(String key:new String[]{"input_state","input_reason","external_state","read_error","read_stage"}){
                String value=sample.optString(key);if(value.matches("[A-Z0-9_]{0,100}"))compact.put(key,value);
            }for(String key:new String[]{"window_valid","input_verified"})if(sample.opt(key) instanceof Boolean)compact.put(key,sample.opt(key));
                selected.put("last_sample",compact);
            }
            JSONObject storage=guard.optJSONObject("storage");
            if(storage!=null){JSONObject compact=new JSONObject();int count=storage.optInt("saved_samples",-1);
                if(count>=0&&count<=3)compact.put("saved_samples",count);
                String error=storage.optString("storage_error");if(error.matches("[A-Z0-9_]{0,100}"))compact.put("storage_error",error);
                selected.put("storage",compact);
            }
            result.put("guard",selected);
        }
        return result;
    }
    /** 核心约每秒调用；实际query已有线程池且只保留一个在途请求。 */
    public synchronized void tick(){
        if(id.isEmpty()||bridge==null)return;
        if(clock.elapsed()>=deadline&&!stopping)stop();
        if(stopping&&!stopDispatched)stop();
        if(!inflight&&clock.elapsed()>=nextQuery){inflight=true;bridge.query(id,callback(id,false));}
    }
    public synchronized void stop(){
        if(id.isEmpty()||bridge==null)return;
        stopping=true;state="closing";
        // start回执到达后再stop，避免未知在途start晚于stop才真正启动。
        if(inflight||stopDispatched)return;
        stopDispatched=true;inflight=true;bridge.stop(id,callback(id,false));
        changed();
    }
    public synchronized void close(){
        if(closed)return;closed=true;enabled=false;stop();
        if(readinessThread!=null)readinessThread.interrupt();
        if(bridge!=null){bridge.close();bridge=null;}
        changed();
        // 不伪造closed；若Binder取消结果未知，APP持有者死亡/15秒租约独立释放。
    }
}
