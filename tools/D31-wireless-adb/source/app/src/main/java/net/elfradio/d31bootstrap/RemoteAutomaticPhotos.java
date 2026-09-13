package net.elfradio.d31bootstrap;

import android.content.Context;
import java.io.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.media.PhotoAlarmBridge;

/** 独立有界执行线程；报告线程仅持久化资格，不等待APP或照片上传。 */
final class RemoteAutomaticPhotos implements AutoCloseable {
    interface Identity { JSONObject read()throws Exception; }
    interface Busy { boolean get(); }
    private volatile AutomaticPhotoQueue queue;
    private final AutomaticPhotoHandoff handoff;
    private final File root;
    private final PhotoAlarmBridge bridge;
    private final Identity identity;
    private final File apk;
    private final Busy busy;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"d31-auto-photo-root");t.setDaemon(true);return t;});
    private volatile boolean closed;
    private String hash="";
    private JSONObject owned;
    private boolean scheduled;
    private boolean wakeRequested;
    private long lastReportRequest;
    synchronized boolean reportDue(){
        if(queue==null)return false;
        long now=System.currentTimeMillis(),at=queue.reportAt();
        if(at<=0||now<at||(lastReportRequest>0&&now-lastReportRequest<AutomaticPhotoQueue.INTERVAL))return false;
        lastReportRequest=now;return true;
    }
    RemoteAutomaticPhotos(Context context,File root,String apkPath,Identity identity,Busy busy)throws Exception {
        this.root=root;handoff=new AutomaticPhotoHandoff(root);bridge=new PhotoAlarmBridge(context);apk=new File(apkPath);this.identity=identity;this.busy=busy;
        schedule(2000);
    }
    void acknowledged(JSONObject report,JSONObject reply) {
        try{if(handoff.offer(report,reply))schedule(2000);}
        catch(Exception failed){System.err.println("AUTO_PHOTO_HANDOFF_FAILED");}
    }
    private synchronized void schedule(long delay){
        if(closed)return;if(scheduled){wakeRequested=true;return;}scheduled=true;
        worker.schedule(()->{long next=tick();synchronized(this){
            scheduled=false;if(wakeRequested){wakeRequested=false;next=2000;}if(next>0)schedule(next);
        }},delay,TimeUnit.MILLISECONDS);
    }
    private long tick() {
        if(closed)return 0;
        JSONObject credentials=null;
        try {
            if(queue==null)queue=new AutomaticPhotoQueue(root);
            handoff.drain(queue,System.currentTimeMillis());
            if(!queue.pending())return 0;
            credentials=identity.read();JSONObject job=queue.next(System.currentTimeMillis(),credentials.getString("device_id"));
            if(job==null)return 2000;owned=job;
            if(hash.isEmpty())hash=RescueFiles.sha256(apk);
            JSONObject command=new JSONObject().put("operation",job.optBoolean("terminal")?"auto_finish":"auto_photo")
                    .put("apk_sha256",hash).put("job",job);
            if(job.optBoolean("terminal")) {
                JSONObject result=bridge.request(command);
                if(result.optBoolean("cleanup_complete")){queue.removed(job,System.currentTimeMillis());owned=null;}
            } else {
                if(busy.get())return 2000;
                command.put("credentials",credentials);
                queue.result(job,bridge.request(command),System.currentTimeMillis());
            }
            return queue.pending()?2000:0;
        }catch(Exception failed){System.err.println("AUTO_PHOTO_RETRY_PENDING");return 60000;}
        finally{if(credentials!=null)credentials.remove("token");}
    }
    public void close(){
        closed=true;
        worker.execute(()->{try{if(owned!=null&&!hash.isEmpty())bridge.request(new JSONObject().put("operation","auto_cancel")
                .put("apk_sha256",hash).put("job",owned));}catch(Exception ignored){}finally{worker.shutdown();}});
    }
}
