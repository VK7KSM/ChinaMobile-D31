package net.elfradio.d31bootstrap.media;

import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** 仅在调用方工作线程使用；AMS握手不带凭据，实际操作由UID0 Binder发送。 */
public final class PhotoAlarmBridge {
    private final Context context;
    private final IBinder owner=new Binder();
    public PhotoAlarmBridge(Context context){this.context=context;}
    public JSONObject request(JSONObject command)throws Exception {
        PhotoAlarmContract.command(command.toString());
        if(android.os.Process.myUid()!=0||Build.VERSION.SDK_INT!=23)throw new IOException("VISUAL_BRIDGE_REQUIRES_API23_ROOT");
        ComponentName target=new ComponentName(PhotoAlarmContract.PACKAGE,PhotoAlarmContract.SERVICE);
        ServiceInfo info=context.getPackageManager().getServiceInfo(target,0);final int uid=info.applicationInfo.uid;
        if(info.exported||!info.enabled||uid<10000)throw new IOException("VISUAL_SERVICE_IDENTITY");
        final String id=UUID.randomUUID().toString(),boot=AppMediaContract.bootId();final long started=SystemClock.elapsedRealtime();
        CountDownLatch hello=new CountDownLatch(1),finished=new CountDownLatch(1);
        AtomicReference<IBinder> endpoint=new AtomicReference<>();AtomicReference<JSONObject> result=new AtomicReference<>();
        ResultReceiver receiver=new ResultReceiver(null){protected void onReceiveResult(int code,Bundle data){
            if(Binder.getCallingUid()!=uid)return;
            try{
                String raw=data.getString("envelope");
                AppMediaContract.reply(uid,uid,id,boot,started,SystemClock.elapsedRealtime(),raw);
                if(!boot.equals(AppMediaContract.bootId()))throw new IOException();
                if(code==PhotoAlarmContract.HELLO){if(endpoint.compareAndSet(null,data.getBinder("control")))hello.countDown();}
                else if(code==PhotoAlarmContract.RESULT){if(result.compareAndSet(null,new JSONObject(raw).getJSONObject("result")))finished.countDown();}
            }catch(Exception failed){result.compareAndSet(null,PhotoAlarmContract.error("VISUAL_REPLY_INVALID"));hello.countDown();finished.countDown();}
        }};
        Intent intent=new Intent(PhotoAlarmContract.ACTION).setComponent(target).putExtra("request_id",id).putExtra("boot_id",boot)
                .putExtra("started_elapsed_ms",started).putExtra("reply",receiver);
        Object manager=Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
        Object actual=AppMediaContract.start(Class.forName("android.app.IActivityManager"),manager,Class.forName("android.app.IApplicationThread"),Intent.class,intent);
        if(!target.equals(actual))throw new IOException("VISUAL_SERVICE_START_FAILED");
        await(hello,started);IBinder control=endpoint.get();if(control==null)throw new IOException("VISUAL_HANDSHAKE_FAILED");
        Parcel data=Parcel.obtain();try{
            data.writeInterfaceToken(PhotoAlarmContract.DESCRIPTOR);data.writeString(command.toString());data.writeStrongBinder(owner);
            if(!control.transact(PhotoAlarmContract.EXECUTE,data,null,IBinder.FLAG_ONEWAY))throw new IOException("VISUAL_TRANSACT_FAILED");
        }finally{data.recycle();}
        await(finished,started);return PhotoAlarmContract.reply(result.get());
    }
    private static void await(CountDownLatch latch,long started)throws Exception {
        long left=PhotoAlarmContract.WAIT_MS-(SystemClock.elapsedRealtime()-started);
        if(left<=0||!latch.await(left,TimeUnit.MILLISECONDS))throw new IOException("VISUAL_BRIDGE_TIMEOUT");
    }
}
