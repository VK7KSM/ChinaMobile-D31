package net.elfradio.d31bootstrap.media;

import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** root异步控制桥；AMS只建立握手，真正命令经校验UID的APP Binder。 */
public final class AppMediaBridge implements AutoCloseable {
    public interface Callback { void completed(JSONObject result); void failed(String code); }
    public interface BeforeExecute { void verified(String requestId,int pid,int uid)throws Exception; }
    private final Context context;
    private final IBinder owner=new Binder();
    private final Set<Call> calls=Collections.newSetFromMap(new ConcurrentHashMap<Call,Boolean>());
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,5,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(4),
            new ThreadFactory(){public Thread newThread(Runnable job){Thread t=new Thread(job,"d31-app-media-bridge");t.setDaemon(true);return t;}});
    private volatile boolean closed;
    private volatile String ownedSession="";
    public AppMediaBridge(Context rootSystemContext){context=rootSystemContext;workers.allowCoreThreadTimeOut(true);}
    public void prepare(String verifiedApkSha256,Callback callback){
        try{submit(new JSONObject().put("operation","prepare").put("apk_sha256",verifiedApkSha256),callback);}
        catch(Exception failure){fail(callback,"MEDIA_BRIDGE_REQUEST_INVALID");}
    }
    public void start(String verifiedApkSha256,JSONObject offer,Callback callback){
        try{submit(new JSONObject().put("operation","start").put("apk_sha256",verifiedApkSha256).put("offer",offer),callback);}
        catch(Exception failure){fail(callback,"MEDIA_BRIDGE_REQUEST_INVALID");}
    }
    public void captureLocalAudio(String verifiedApkSha256,String diagnosticId,int durationMs,Callback callback){
        captureLocalAudio(verifiedApkSha256,diagnosticId,durationMs,null,callback);
    }
    public void captureLocalAudio(String verifiedApkSha256,String diagnosticId,int durationMs,BeforeExecute before,Callback callback){
        try{submit(new JSONObject().put("operation","local_audio_capture").put("apk_sha256",verifiedApkSha256)
                .put("diagnostic_id",diagnosticId).put("duration_ms",durationMs),callback,before);}
        catch(Exception failure){fail(callback,"MEDIA_BRIDGE_REQUEST_INVALID");}
    }
    public void recoverLocalAudio(String diagnosticId,BeforeExecute before,Callback callback){
        try{submit(new JSONObject().put("operation","stop").put("session_id",diagnosticId),callback,before);}
        catch(Exception failure){fail(callback,"MEDIA_BRIDGE_REQUEST_INVALID");}
    }
    public void stop(String sessionId,Callback callback){
        try{submit(new JSONObject().put("operation","stop").put("session_id",sessionId),callback);}
        catch(Exception failure){fail(callback,"MEDIA_BRIDGE_REQUEST_INVALID");}
    }
    /** 空编号只读当前状态；具体编号只为本bridge拥有的会话续租。 */
    public void query(String sessionId,Callback callback){
        try{submit(new JSONObject().put("operation","query").put("session_id",sessionId),callback);}
        catch(Exception failure){fail(callback,"MEDIA_BRIDGE_REQUEST_INVALID");}
    }
    private synchronized void submit(JSONObject command,Callback callback)throws Exception {
        submit(command,callback,null);
    }
    private synchronized void submit(JSONObject command,Callback callback,BeforeExecute before)throws Exception {
        if(closed||callback==null)throw new IOException("MEDIA_BRIDGE_CLOSED");
        Call call=new Call(AppMediaContract.command(command.toString()).toString(),callback,before);calls.add(call);
        try{workers.execute(call);}catch(RejectedExecutionException busy){calls.remove(call);fail(callback,"MEDIA_BRIDGE_BUSY");}
    }
    private static void fail(Callback callback,String code){if(callback!=null)try{callback.failed(code);}catch(Exception ignored){}}
    private final class Call implements Runnable {
        private String command;
        private final Callback callback;
        private final BeforeExecute before;
        private volatile int appPid=-1,appUid=-1;
        private final AtomicBoolean done=new AtomicBoolean(),cancelled=new AtomicBoolean();
        private volatile IBinder endpoint;
        private final CountDownLatch hello=new CountDownLatch(1),completed=new CountDownLatch(1);
        private final AtomicReference<JSONObject> result=new AtomicReference<JSONObject>();
        private final long executionWindow;
        private final boolean stopCommand;
        Call(String command,Callback callback,BeforeExecute before)throws Exception{this.command=command;this.callback=callback;this.before=before;executionWindow=AppMediaContract.executionWindow(new JSONObject(command));stopCommand="stop".equals(new JSONObject(command).optString("operation"));}
        public void run(){
            try{
                if(cancelled.get())throw new IOException("MEDIA_BRIDGE_CANCELLED");
                if(context==null||android.os.Process.myUid()!=0||Build.VERSION.SDK_INT!=23)throw new IOException("MEDIA_BRIDGE_REQUIRES_API23_ROOT");
                final ComponentName target=new ComponentName(AppMediaContract.PACKAGE,AppMediaContract.SERVICE);
                ServiceInfo service=context.getPackageManager().getServiceInfo(target,0);
                final int expectedUid=service.applicationInfo.uid;
                if(service.exported||!service.enabled||expectedUid<10000)throw new IOException("MEDIA_BRIDGE_SERVICE_IDENTITY");
                final String id=UUID.randomUUID().toString(),boot=AppMediaContract.bootId();
                final long started=SystemClock.elapsedRealtime();
                ResultReceiver receiver=new ResultReceiver(null){
                    protected void onReceiveResult(int code,Bundle data){
                        int sender=Binder.getCallingUid();
                        if(done.get()||cancelled.get()||sender!=expectedUid)return;
                        try{
                            if(data==null||!boot.equals(AppMediaContract.bootId()))throw new IOException();
                            String encoded=data.getString("envelope");
                            AppMediaContract.reply(sender,expectedUid,id,boot,started,SystemClock.elapsedRealtime(),encoded,
                                    code==AppMediaContract.RESULT?executionWindow:AppMediaContract.WAIT_MS);
                            if(code==AppMediaContract.HELLO){
                                IBinder control=data.getBinder("control");if(control==null)throw new IOException();
                                if(endpoint==null){appPid=data.getInt("app_pid",-1);appUid=sender;endpoint=control;hello.countDown();}
                            }else if(code==AppMediaContract.RESULT){
                                JSONObject body=new JSONObject(encoded).getJSONObject("result");
                                if(result.compareAndSet(null,body))completed.countDown();
                            }
                        }catch(Exception invalid){result.compareAndSet(null,error("MEDIA_BRIDGE_INVALID_REPLY"));hello.countDown();completed.countDown();}
                    }
                };
                Intent intent=new Intent(AppMediaContract.ACTION).setComponent(target).putExtra("request_id",id)
                        .putExtra("boot_id",boot).putExtra("started_elapsed_ms",started).putExtra("reply",receiver);
                Object manager=Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
                Object actual=AppMediaContract.start(Class.forName("android.app.IActivityManager"),manager,
                        Class.forName("android.app.IApplicationThread"),Intent.class,intent);
                if(!target.equals(actual))throw new IOException("MEDIA_BRIDGE_SERVICE_START_FAILED");
                await(hello,started,AppMediaContract.WAIT_MS);if(endpoint==null||cancelled.get())throw new IOException("MEDIA_BRIDGE_HANDSHAKE_FAILED");
                if(before!=null)before.verified(id,appPid,appUid);
                if(cancelled.get()||(closed&&!stopCommand))throw new IOException("MEDIA_BRIDGE_CANCELLED");
                Parcel data=Parcel.obtain();try{
                    data.writeInterfaceToken(AppMediaContract.DESCRIPTOR);data.writeString(command);data.writeStrongBinder(owner);
                    if(!endpoint.transact(AppMediaContract.EXECUTE,data,null,IBinder.FLAG_ONEWAY))throw new IOException("MEDIA_BRIDGE_TRANSACT_FAILED");
                }finally{data.recycle();}
                await(completed,started,executionWindow);JSONObject body=result.get();
                if(body==null||cancelled.get())throw new IOException("MEDIA_BRIDGE_REPLY_UNKNOWN");
                if(body.has("error")){fail(callback,body.optString("error","MEDIA_BRIDGE_FAILED"));return;}
                JSONObject request=new JSONObject(command);
                synchronized(AppMediaBridge.this){
                    if((closed&&!stopCommand)||cancelled.get())throw new IOException("MEDIA_BRIDGE_CANCELLED");
                    if("start".equals(request.getString("operation")))ownedSession=request.getJSONObject("offer").getString("session_id");
                    if("stop".equals(request.getString("operation"))&&ownedSession.equals(request.optString("session_id")))ownedSession="";
                    done.set(true);
                }
                try{callback.completed(body);}catch(Exception ignored){}
            }catch(Exception failure){
                cancel();String code=failure.getMessage();
                fail(callback,code!=null&&code.matches("MEDIA_[A-Z0-9_]{1,80}")?code:"MEDIA_BRIDGE_FAILED");
                if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            }finally{done.set(true);command=null;calls.remove(this);}
        }
        private void await(CountDownLatch latch,long started,long window)throws Exception {
            long remaining=window-(SystemClock.elapsedRealtime()-started);
            if(remaining<=0||!latch.await(remaining,TimeUnit.MILLISECONDS)||cancelled.get())throw new IOException("MEDIA_BRIDGE_TIMEOUT_OR_CANCELLED");
        }
        void cancel(){
            if(done.get())return;cancelled.set(true);hello.countDown();completed.countDown();IBinder control=endpoint;
            if(control!=null){Parcel data=Parcel.obtain();try{data.writeInterfaceToken(AppMediaContract.DESCRIPTOR);
                control.transact(AppMediaContract.CANCEL,data,null,IBinder.FLAG_ONEWAY);}catch(Exception ignored){}finally{data.recycle();}}
        }
    }
    static JSONObject error(String code){try{return new JSONObject().put("error",code).put("managed_media",false);}catch(Exception ignored){return new JSONObject();}}
    /** 非阻塞；取消未回执请求，停止本bridge的会话，APP的15秒租约另作兜底。 */
    public synchronized void close(){
        if(closed)return;for(Call call:calls)call.cancel();
        if(!ownedSession.isEmpty())stop(ownedSession,new Callback(){public void completed(JSONObject ignored){}public void failed(String ignored){}});
        closed=true;workers.shutdown();
    }
}
