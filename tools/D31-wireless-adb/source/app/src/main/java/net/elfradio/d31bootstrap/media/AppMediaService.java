package net.elfradio.d31bootstrap.media;

import android.app.Service;
import android.content.*;
import android.os.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 非导出、按需APP32服务。真实命令必须经UID0 Binder，不信任Intent中的身份声明。 */
public final class AppMediaService extends Service {
    public interface GuardFactory { AudioGuard create(Context context)throws Exception; }
    static final class Configuration {
        final GuardFactory factory;final URI origin;
        Configuration(GuardFactory factory,URI origin){this.factory=factory;this.origin=origin;}
    }
    private static volatile Configuration config=new Configuration(null,null);
    private static AppMediaController processController;
    public static void configure(GuardFactory factory,URI trustedControlOrigin){
        if(trustedControlOrigin==null||!"https".equals(trustedControlOrigin.getScheme())||trustedControlOrigin.getHost()==null
                ||trustedControlOrigin.getUserInfo()!=null||trustedControlOrigin.getQuery()!=null||trustedControlOrigin.getFragment()!=null
                ||!(trustedControlOrigin.getPath().isEmpty()||"/".equals(trustedControlOrigin.getPath())))
            throw new IllegalArgumentException("MEDIA_CONTROL_ORIGIN_INVALID");
        config=new Configuration(factory,trustedControlOrigin);
    }
    static Configuration configuration(){return config;}
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String,Endpoint> pending=new ConcurrentHashMap<String,Endpoint>();
    private final Map<IBinder,IBinder.DeathRecipient> owners=new HashMap<IBinder,IBinder.DeathRecipient>();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(1),
            new ThreadFactory(){public Thread newThread(Runnable job){Thread t=new Thread(job,"d31-app-media-control");t.setDaemon(true);return t;}});
    private AppMediaController controller;
    private AppMediaBackend backend;
    private boolean destroyed;
    private int lastStart;
    private final Runnable tick=new Runnable(){public void run(){
        if(destroyed)return;controller.tick();
        for(Endpoint endpoint:pending.values())if(SystemClock.elapsedRealtime()-endpoint.started>endpoint.executionWindow)endpoint.cancel();
        synchronized(owners){Iterator<Map.Entry<IBinder,IBinder.DeathRecipient>> it=owners.entrySet().iterator();
            while(it.hasNext()){Map.Entry<IBinder,IBinder.DeathRecipient> entry=it.next();
                if(!controller.owns(entry.getKey())&&!hasPendingOwner(entry.getKey())){entry.getKey().unlinkToDeath(entry.getValue(),0);it.remove();}}}
        if(pending.isEmpty()&&!controller.hasActive()){stopSelf(lastStart);return;}main.postDelayed(this,250);
    }};
    public void onCreate(){
        super.onCreate();backend=new AppMediaBackend(getApplicationContext());
        synchronized(AppMediaService.class){
            if(processController==null)processController=new AppMediaController(backend,AndroidMediaDevice.CLOCK);
            else processController.attachBackend(backend);controller=processController;
        }
    }
    public int onStartCommand(Intent intent,int flags,int startId){
        lastStart=startId;
        try{
            if(intent==null||!AppMediaContract.ACTION.equals(intent.getAction())
                    ||!new ComponentName(AppMediaContract.PACKAGE,AppMediaContract.SERVICE).equals(intent.getComponent()))throw new IOException();
            MediaReadiness.requireApplicationIdentity(this,AppMediaContract.PACKAGE);
            String id=intent.getStringExtra("request_id"),boot=intent.getStringExtra("boot_id");long started=intent.getLongExtra("started_elapsed_ms",-1);
            AppMediaContract.request(id,boot,started,SystemClock.elapsedRealtime());
            if(!boot.equals(AppMediaContract.bootId())||pending.size()>=8)throw new IOException();
            ResultReceiver receiver=intent.getParcelableExtra("reply");if(receiver==null)throw new IOException();
            Endpoint endpoint=new Endpoint(id,boot,started,receiver);
            if(pending.putIfAbsent(id,endpoint)==null)endpoint.hello();
        }catch(Exception invalid){/* 未认证的握手不执行操作，也不回显Intent内容。 */}
        main.removeCallbacks(tick);main.postDelayed(tick,250);return START_NOT_STICKY;
    }
    private boolean hasPendingOwner(IBinder owner){for(Endpoint endpoint:pending.values())if(owner.equals(endpoint.rootOwner))return true;return false;}
    private void watchOwner(final IBinder owner)throws Exception {
        synchronized(owners){
            if(owners.containsKey(owner))return;
            IBinder.DeathRecipient death=new IBinder.DeathRecipient(){public void binderDied(){
                for(Endpoint endpoint:pending.values())if(owner.equals(endpoint.rootOwner))endpoint.cancel();
                controller.ownerDied(owner);
            }};
            owner.linkToDeath(death,0);owners.put(owner,death);
            if(!owner.isBinderAlive())throw new IOException("MEDIA_ROOT_OWNER_DEAD");
        }
    }
    private final class Endpoint extends Binder {
        final String id,boot;final long started;final ResultReceiver receiver;
        final AtomicBoolean used=new AtomicBoolean(),finished=new AtomicBoolean();final Cancellation cancellation=new Cancellation();
        volatile IBinder rootOwner;volatile Future<?> job;
        volatile long executionWindow=AppMediaContract.WAIT_MS;
        Endpoint(String id,String boot,long started,ResultReceiver receiver){this.id=id;this.boot=boot;this.started=started;this.receiver=receiver;}
        void hello()throws Exception {Bundle data=envelope(null);data.putBinder("control",this);data.putInt("app_pid",android.os.Process.myPid());receiver.send(AppMediaContract.HELLO,data);}
        private Bundle envelope(JSONObject result)throws Exception {
            JSONObject value=new JSONObject().put("request_id",id).put("boot_id",boot).put("started_elapsed_ms",started);
            if(result!=null)value.put("result",result);
            String encoded=value.toString();if(encoded.getBytes("UTF-8").length>AppMediaContract.MAX_BYTES)throw new IOException("MEDIA_BRIDGE_REPLY_SIZE");
            Bundle data=new Bundle();data.putString("envelope",encoded);return data;
        }
        protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
            int caller=Binder.getCallingUid();
            try{
                AppMediaContract.caller(caller);data.enforceInterface(AppMediaContract.DESCRIPTOR);
                if(code==AppMediaContract.CANCEL){cancel();return true;}
                if(code!=AppMediaContract.EXECUTE)return false;
                if(!used.compareAndSet(false,true))return true;
                AppMediaContract.request(id,boot,started,SystemClock.elapsedRealtime());
                final JSONObject command=AppMediaContract.command(data.readString());rootOwner=data.readStrongBinder();
                if(rootOwner==null||!boot.equals(AppMediaContract.bootId()))throw new IOException("MEDIA_BRIDGE_OWNER_INVALID");
                executionWindow=AppMediaContract.executionWindow(command);
                String op=command.getString("operation");
                if("start".equals(op)||"local_audio_capture".equals(op))watchOwner(rootOwner);
                if("stop".equals(op)||"query".equals(op)){run(command);return true;}
                try{job=worker.submit(new Runnable(){public void run(){Endpoint.this.run(command);}});}
                catch(RejectedExecutionException busy){finish(AppMediaBridge.error("MEDIA_APP_BUSY"));}
                return true;
            }catch(Exception failure){finish(AppMediaBridge.error("MEDIA_BRIDGE_REQUEST_REJECTED"));return true;}
        }
        void run(JSONObject command){
            try{
                AppMediaContract.request(id,boot,started,SystemClock.elapsedRealtime(),executionWindow);cancellation.check();
                JSONObject result=controller.execute(id,command,rootOwner,cancellation,started+executionWindow);
                AppMediaContract.request(id,boot,started,SystemClock.elapsedRealtime(),executionWindow);cancellation.check();finish(result);
            }catch(Exception failure){
                cancellation.cancel();controller.cancelRequest(id);
                String code=failure.getMessage();finish(AppMediaBridge.error(code!=null&&code.matches("MEDIA_[A-Z0-9_]{1,80}")?code:"MEDIA_APP_COMMAND_FAILED"));
            }
        }
        void finish(JSONObject value){
            if(!finished.compareAndSet(false,true))return;
            try{receiver.send(AppMediaContract.RESULT,envelope(value));}catch(Exception ignored){}
            finally{pending.remove(id,this);}
        }
        void cancel(){cancellation.cancel();controller.cancelRequest(id);Future<?> current=job;if(current!=null)current.cancel(true);
            finish(AppMediaBridge.error("MEDIA_BRIDGE_CANCELLED"));}
    }
    public IBinder onBind(Intent intent){return null;}
    public void onDestroy(){
        destroyed=true;main.removeCallbacks(tick);for(Endpoint endpoint:pending.values())endpoint.cancel();controller.serviceDestroyed();worker.shutdownNow();
        synchronized(owners){for(Map.Entry<IBinder,IBinder.DeathRecipient> entry:owners.entrySet())entry.getKey().unlinkToDeath(entry.getValue(),0);owners.clear();}
        try{backend.close();}catch(Exception ignored){}super.onDestroy();
    }
}
