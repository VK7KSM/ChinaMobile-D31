package net.elfradio.d31bootstrap.media;

import android.Manifest;
import android.app.Service;
import android.app.AppOpsManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.*;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** 按需独立进程服务；凭据仅经UID0 Binder传入，不通过Intent或磁盘。 */
public final class PhotoAlarmService extends Service {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String,Endpoint> pending=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(4),PhotoAlarmSession.threads("d31-visual-control"));
    private static PhotoAlarmSession active;
    /** 二维码窗口的拥有者；跨请求保留，核心靠 share_link_query 按轮次取结果。 */
    private static ShareLinkWindow shareLinkWindow;
    private static IBinder owner;
    private IBinder.DeathRecipient death;
    private boolean destroyed;
    private int lastStart;
    private String verifiedHash="";
    private PhotoAlarmJournal journal;
    private AutomaticPhotoRunner automatic;
    private AutomaticPhotoCameraAvailability automaticCamera;
    private IBinder automaticOwner;
    private IBinder.DeathRecipient automaticDeath;
    private long automaticTouched;
    private final AtomicBoolean retiring=new AtomicBoolean();
    private final Runnable tick=new Runnable(){public void run(){
        if(destroyed)return;
        for(Endpoint endpoint:pending.values())if(SystemClock.elapsedRealtime()-endpoint.started>6000)endpoint.finish(PhotoAlarmContract.error("VISUAL_BRIDGE_TIMEOUT"));
        synchronized(PhotoAlarmService.class){
            if(active!=null&&active.finished()&&retiring.compareAndSet(false,true)){
                try{worker.execute(()->{try{retireFinished();}catch(Exception pending){}finally{retiring.set(false);}});}
                catch(RejectedExecutionException pending){retiring.set(false);}
            }
            if(automatic!=null&&SystemClock.elapsedRealtime()-automaticTouched>90000){
                automatic.cancel();
                if(!automatic.busy())clearAutomatic();
            }
            if(pending.isEmpty()&&active==null&&automatic==null){stopSelf(lastStart);return;}
        }
        main.postDelayed(this,250);
    }};
    private PhotoAlarmJournal journal()throws Exception {
        if(journal==null)journal=new PhotoAlarmJournal(new File(getFilesDir().getCanonicalFile(),"visual-sessions"));
        return journal;
    }
    private void retireFinished()throws Exception {
        PhotoAlarmSession candidate;
        synchronized(PhotoAlarmService.class){candidate=active;if(candidate==null||!candidate.finished())return;}
        journal().complete(candidate.snapshot());
        synchronized(PhotoAlarmService.class){if(active==candidate){active=null;unlink();}}
    }
    public int onStartCommand(Intent intent,int flags,int startId){
        lastStart=startId;
        try {
            MediaReadiness.requireApplicationIdentity(this,PhotoAlarmContract.PACKAGE);
            if(intent==null||!PhotoAlarmContract.ACTION.equals(intent.getAction())||pending.size()>=8)throw new IOException();
            String id=intent.getStringExtra("request_id"),boot=intent.getStringExtra("boot_id");long started=intent.getLongExtra("started_elapsed_ms",-1);
            AppMediaContract.request(id,boot,started,SystemClock.elapsedRealtime());if(!boot.equals(AppMediaContract.bootId()))throw new IOException();
            ResultReceiver receiver=intent.getParcelableExtra("reply");if(receiver==null)throw new IOException();
            Endpoint endpoint=new Endpoint(id,boot,started,receiver);if(pending.putIfAbsent(id,endpoint)==null)endpoint.hello();
        }catch(Exception invalid){}
        main.removeCallbacks(tick);main.postDelayed(tick,250);return START_NOT_STICKY;
    }
    private JSONObject prepare(String hash)throws Exception {
        MediaReadiness.requireApplicationIdentity(this,PhotoAlarmContract.PACKAGE);
        if(!hash.equals(verifiedHash)){
            if(!hash.equals(MediaFiles.hash(new File(getApplicationInfo().sourceDir).getCanonicalFile())))throw new IOException("VISUAL_APK_MISMATCH");
            verifiedHash=hash;
        }
        AppOpsManager ops=(AppOpsManager)getSystemService(APP_OPS_SERVICE);
        boolean runtimePermission=checkPermission(Manifest.permission.CAMERA,android.os.Process.myPid(),android.os.Process.myUid())==PackageManager.PERMISSION_GRANTED;
        boolean appOp=runtimePermission&&ops!=null&&ops.checkOpNoThrow(AppOpsManager.OPSTR_CAMERA,android.os.Process.myUid(),getPackageName())==AppOpsManager.MODE_ALLOWED;
        boolean camera=runtimePermission&&appOp;
        boolean alarm=getSystemService(AUDIO_SERVICE)!=null;
        int cameras=android.hardware.Camera.getNumberOfCameras();
        JSONArray modes=new JSONArray();if(camera&&cameras>0)modes.put("photo");if(alarm)modes.put("alarm");
        return new JSONObject().put("state","prepared").put("apk_hash_match",true).put("app_identity_match",true)
                .put("managed_media_modes",modes).put("media_cameras",cameras).put("camera_permission",camera)
                .put("camera_runtime_permission",runtimePermission).put("camera_app_op_allowed",appOp).put("captures_started",false);
    }
    private JSONObject execute(JSONObject x,IBinder caller)throws Exception {
        String op=x.getString("operation");
        if("prepare".equals(op))return prepare(x.getString("apk_sha256"));
        if(op.startsWith("auto_"))return automatic(x,caller);
        if(op.startsWith("share_link_"))return shareLink(x,op);
        retireFinished();
        if("start".equals(op)){
            JSONObject rawOffer=x.getJSONObject("offer");String requested=rawOffer.optString("session_id");
            synchronized(PhotoAlarmService.class){
                if(active==null||!requested.equals(active.snapshot().optString("session_id"))){
                    JSONObject prior=journal().read(requested);
                    if(prior!=null){if(!rawOffer.optString("mode").equals(prior.optString("mode")))throw new IOException("VISUAL_SESSION_MODE_CONFLICT");return prior;}
                }
            }
            JSONObject readiness=prepare(x.getString("apk_sha256"));
            PhotoAlarmOffer offer=PhotoAlarmOffer.parse(x.getJSONObject("offer"),new URI("https://v.elfradio.net"),System.currentTimeMillis());
            if(!readiness.getJSONArray("managed_media_modes").toString().contains("\""+offer.mode+"\""))throw new IOException("VISUAL_CAPABILITY_UNAVAILABLE");
            synchronized(PhotoAlarmService.class){
                if(active!=null){
                    if(owner!=null&&owner.equals(caller)&&offer.id.equals(active.snapshot().optString("session_id"))){active.renew();return active.snapshot();}
                    throw new IOException("VISUAL_SESSION_BUSY");
                }
                if(caller==null||!caller.isBinderAlive())throw new IOException("VISUAL_OWNER_DEAD");
                PhotoAlarmBackend backend=new PhotoAlarmBackend(getApplicationContext(),main,x.getJSONObject("credentials"));
                JSONObject prior=journal().begin(offer);if(prior!=null)return prior;
                PhotoAlarmSession session=new PhotoAlarmSession(offer,new PhotoAlarmSocket(),backend,AndroidMediaDevice.CLOCK);
                owner=caller;death=()->{synchronized(PhotoAlarmService.class){if(active!=null)active.close();}};
                try{owner.linkToDeath(death,0);active=session;session.start();}
                catch(Exception failure){session.close();owner=null;throw failure;}
                return session.snapshot();
            }
        }
        synchronized(PhotoAlarmService.class){
            String requested=x.optString("session_id");
            if(!requested.isEmpty()&&(active==null||!requested.equals(active.snapshot().optString("session_id")))){
                JSONObject prior=journal().read(requested);if(prior!=null)return prior;
            }
            if(active==null)return new JSONObject().put("session_id",requested).put("state","idle").put("cleanup_complete",true);
            if(!caller.equals(owner)||!x.optString("session_id").equals(active.snapshot().optString("session_id")))throw new IOException("VISUAL_OWNER_MISMATCH");
            if("stop".equals(op))active.close();else active.renew();return active.snapshot();
        }
    }
    /**
     * 二维码窗口不占相机也不占音频，所以不参与媒体会话的占用判定，只和自己串行。
     * show 只负责拉起并立即返回：显示时长以分钟计，远超本桥6秒的同步时限，等不起。
     */
    private JSONObject shareLink(JSONObject x,String op)throws Exception {
        synchronized(PhotoAlarmService.class){
            if(shareLinkWindow==null)shareLinkWindow=new ShareLinkWindow(getApplicationContext());
            if(PhotoAlarmContract.SHARE_LINK_SHOW.equals(op))return shareLinkWindow.show(x);
            if(PhotoAlarmContract.SHARE_LINK_DISMISS.equals(op))return shareLinkWindow.dismiss(x.optString("session"));
            return shareLinkWindow.snapshot();
        }
    }
    private JSONObject automatic(JSONObject x,IBinder caller)throws Exception {
        if(caller==null||!caller.isBinderAlive())throw new IOException("VISUAL_OWNER_DEAD");
        JSONObject readiness=prepare(x.getString("apk_sha256")),job=x.getJSONObject("job");
        synchronized(PhotoAlarmService.class){
            automaticTouched=SystemClock.elapsedRealtime();
            if(automaticOwner!=null&&!automaticOwner.equals(caller)) {
                automatic.cancel();if(automatic.busy())throw new IOException("MEDIA_BUSY");clearAutomatic();
            }
            if(automatic==null){
                automaticCamera=new AutomaticPhotoCameraAvailability(this,main);
                automatic=new AutomaticPhotoRunner(AutomaticPhotoRunner.applicationFiles(new File(getApplicationInfo().dataDir),getFilesDir()),new AndroidMediaDevice(this,AndroidMediaDevice.CLOCK),
                        AndroidMediaDevice.CLOCK,critical->new AutomaticPhotoNetwork(this,critical),AndroidMediaDevice::cameraReleased,automaticCamera::check);
                automaticOwner=caller;automaticDeath=()->{synchronized(PhotoAlarmService.class){if(automatic!=null)automatic.cancel();automaticTouched=0;}};
                caller.linkToDeath(automaticDeath,0);
            }
            String op=x.getString("operation");
            if("auto_cancel".equals(op)){
                automatic.cancel();boolean clean=!automatic.busy();automaticTouched=0;if(clean)clearAutomatic();
                return new JSONObject().put("cleanup_complete",clean);
            }
            if("auto_finish".equals(op)){
                JSONObject result=automatic.finish(job);
                if(result.optBoolean("cleanup_complete")&&!automatic.busy())clearAutomatic();
                return result;
            }
            if(active!=null&&!active.finished())return new JSONObject().put("state","waiting").put("error","MEDIA_BUSY");
            JSONObject failure=automaticReadinessFailure(readiness);
            if(failure!=null)return failure;
            return automatic.start(job,x.getJSONObject("credentials"));
        }
    }
    static JSONObject automaticReadinessFailure(JSONObject readiness)throws Exception {
        String error;
        if(!readiness.optBoolean("camera_runtime_permission"))error="AUTO_PHOTO_CAMERA_PERMISSION_DENIED";
        else if(!readiness.optBoolean("camera_app_op_allowed"))error="AUTO_PHOTO_CAMERA_APPOP_DENIED";
        else if(readiness.optInt("media_cameras")<1)error="AUTO_PHOTO_CAMERA_NOT_ENUMERATED";
        else return null;
        return new JSONObject().put("state","failed").put("permanent",true).put("error",error);
    }
    private void clearAutomatic(){
        if(automatic!=null)automatic.close();automatic=null;
        if(automaticCamera!=null){automaticCamera.close();automaticCamera=null;}
        if(automaticOwner!=null&&automaticDeath!=null)automaticOwner.unlinkToDeath(automaticDeath,0);
        automaticOwner=null;automaticDeath=null;
    }
    private void unlink(){if(owner!=null&&death!=null)owner.unlinkToDeath(death,0);owner=null;death=null;}
    private final class Endpoint extends Binder {
        final String id,boot;final long started;final ResultReceiver receiver;final AtomicBoolean used=new AtomicBoolean(),done=new AtomicBoolean();
        Endpoint(String id,String boot,long started,ResultReceiver receiver){this.id=id;this.boot=boot;this.started=started;this.receiver=receiver;}
        Bundle envelope(JSONObject value)throws Exception {
            JSONObject json=new JSONObject().put("request_id",id).put("boot_id",boot).put("started_elapsed_ms",started);
            if(value!=null)json.put("result",value);Bundle data=new Bundle();data.putString("envelope",json.toString());return data;
        }
        void hello()throws Exception {Bundle data=envelope(null);data.putBinder("control",this);receiver.send(PhotoAlarmContract.HELLO,data);}
        protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
            if(Binder.getCallingUid()!=0)throw new SecurityException("VISUAL_ROOT_REQUIRED");
            try{
                data.enforceInterface(PhotoAlarmContract.DESCRIPTOR);if(code!=PhotoAlarmContract.EXECUTE)return false;
                if(done.get()||!used.compareAndSet(false,true))return true;
                AppMediaContract.request(id,boot,started,SystemClock.elapsedRealtime());
                JSONObject command=PhotoAlarmContract.command(data.readString());IBinder caller=data.readStrongBinder();
                if(caller==null||!boot.equals(AppMediaContract.bootId()))throw new IOException();
                worker.execute(()->{try{
                    if(done.get()||destroyed)throw new IOException();
                    AppMediaContract.request(id,boot,started,SystemClock.elapsedRealtime());
                    finish(execute(command,caller));
                }catch(Exception failed){String errorCode=failed.getMessage();finish(PhotoAlarmContract.error(errorCode!=null&&errorCode.matches("[A-Z0-9_]{1,100}")?errorCode:"VISUAL_COMMAND_FAILED"));}});
            }catch(Exception failed){finish(PhotoAlarmContract.error("VISUAL_COMMAND_REJECTED"));}
            return true;
        }
        void finish(JSONObject result){if(!done.compareAndSet(false,true))return;try{receiver.send(PhotoAlarmContract.RESULT,envelope(result));}catch(Exception ignored){}finally{pending.remove(id,this);}}
    }
    public IBinder onBind(Intent intent){return null;}
    public void onDestroy(){destroyed=true;main.removeCallbacks(tick);for(Endpoint endpoint:pending.values())endpoint.finish(PhotoAlarmContract.error("VISUAL_SERVICE_STOPPED"));
        synchronized(PhotoAlarmService.class){if(active!=null)active.close();unlink();clearAutomatic();}worker.shutdownNow();super.onDestroy();}
}
