package net.elfradio.d31bootstrap.media;

import java.io.*;
import java.net.URI;
import java.util.concurrent.*;
import org.json.JSONObject;

/** APP异步单次执行器；原件与手动会话分目录，控制请求不等待相机或HTTP。 */
final class AutomaticPhotoRunner implements AutoCloseable {
    interface NetworkFactory { PhotoAlarmUpload.ConnectionPolicy get(boolean critical)throws Exception; }
    interface Released { boolean get(); }
    interface CameraAdmission { void check()throws Exception; }
    private final File files;
    private final MediaCapture.Device camera;
    private final MediaCapture.Clock clock;
    private final NetworkFactory networks;
    private final Released cameraReleased;
    private final CameraAdmission admission;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(PhotoAlarmSession.threads("d31-auto-photo-app"));
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(PhotoAlarmSession.threads("d31-auto-photo-deadline"));
    private final ExecutorService aborter=Executors.newSingleThreadExecutor(PhotoAlarmSession.threads("d31-auto-photo-abort"));
    private final java.util.concurrent.atomic.AtomicBoolean abortScheduled=new java.util.concurrent.atomic.AtomicBoolean();
    private Cancellation cancel=new Cancellation();
    private volatile PhotoAlarmUpload upload;
    private volatile boolean running,closed;
    private MediaFiles.Lease retainedLease;
    private String key="";
    private JSONObject snapshot=new JSONObject(),owned;
    /** 只解析Context提供的可信APP根；不对任务路径做canonical化以绕过链接检查。 */
    static File applicationFiles(File dataDir,File filesDir)throws IOException {
        File data=dataDir.getCanonicalFile(),files=filesDir.getCanonicalFile();
        if(!"files".equals(files.getName())||!data.equals(files.getParentFile()))
            throw new IOException("AUTO_PHOTO_PRIVATE_DIRECTORY_INVALID");
        return MediaFiles.plain(files);
    }
    AutomaticPhotoRunner(File files,MediaCapture.Device camera,MediaCapture.Clock clock,NetworkFactory networks,Released cameraReleased) {
        this(files,camera,clock,networks,cameraReleased,()->{});
    }
    AutomaticPhotoRunner(File files,MediaCapture.Device camera,MediaCapture.Clock clock,NetworkFactory networks,Released cameraReleased,CameraAdmission admission) {
        this.files=files;this.camera=camera;this.clock=clock;this.networks=networks;this.cameraReleased=cameraReleased;this.admission=admission;
    }
    synchronized JSONObject start(JSONObject job,JSONObject identity)throws Exception {
        reap();if(closed)throw new IOException("AUTO_PHOTO_STOPPED");
        if(!job.getString("device_id").equals(identity.getString("device_id")))throw new IOException("AUTO_PHOTO_IDENTITY_CHANGED");
        String next=key(job);
        if(next.equals(key)&&!"waiting".equals(snapshot.optString("state")))return snapshot();
        if(running||retainedLease!=null)throw new IOException("MEDIA_BUSY");
        final JSONObject frozen=new JSONObject(job.toString()),credentials=new JSONObject(identity.toString());
        key=next;owned=frozen;cancel=new Cancellation();abortScheduled.set(false);final Cancellation cancellation=cancel;running=true;
        snapshot=new JSONObject().put("state","running");
        worker.execute(()->{
            ScheduledFuture<?> timeout=null;
            try{
                cancellation.check();timeout=timer.schedule(()->cancelGeneration(cancellation),60,TimeUnit.SECONDS);
                execute(frozen,credentials,cancellation);
            }catch(Exception stopped){try{publish(new JSONObject().put("state","failed").put("error","AUTO_PHOTO_STOPPED"));}catch(Exception ignored){}}
            finally{if(timeout!=null)timeout.cancel(false);credentials.remove("token");running=false;}
        });
        return snapshot();
    }
    private static String key(JSONObject job)throws Exception{return job.getString("device_id")+"/"+job.getString("report_id")+"/"+job.getInt("attempt");}
    synchronized JSONObject snapshot()throws Exception {return new JSONObject(snapshot.toString());}
    synchronized boolean busy(){try{reap();}catch(Exception ignored){}return running||retainedLease!=null;}
    private synchronized void publish(JSONObject value){snapshot=value;}
    private void execute(JSONObject job,JSONObject identity,Cancellation cancellation) {
        long captured=0;MediaFiles.Lease lease=null;
        try {
            cancellation.check();File root=root(job);File folder=new File(new File(root,"captures"),job.getString("report_id"));
            File receiptFile=new File(folder,"result.json");JSONObject receipt=receiptFile.isFile()?MediaFiles.read(receiptFile):null;
            if(receipt!=null&&"completed".equals(receipt.optString("state")))captured=receipt.getLong("captured_at");
            if(receipt!=null&&new File(folder,"upload-ack.json").isFile()) {
                validateAck(folder,job,receipt);publish(evidence(receipt,"completed"));return;
            }
            if(receipt==null&&(clock.wall()>job.getLong("expires_at")||job.getLong("sampled_at")>clock.wall()+60000
                    ||(!job.getBoolean("critical")&&clock.wall()<job.optLong("capture_not_before")))) {
                publish(new JSONObject().put("state","discarded").put("error","AUTO_PHOTO_STALE"));return;
            }
            PhotoAlarmUpload.ConnectionPolicy network=networks.get(job.getBoolean("critical"));network.check();
            if(receipt==null) {
                lease=MediaFiles.lease(new File(files,"media"));cancellation.check();network.check();
                admission.check();cancellation.check();network.check();
                MediaCapture capture=new MediaCapture(root,camera,cancellation::check,clock);
                receipt=capture.photo(CaptureRequest.photo(job.getString("report_id"),job.getString("report_id"),"front",job.getLong("expires_at")),cancellation);
                if("MEDIA_CAMERA_RELEASE_PENDING".equals(receipt.optString("error"))) {
                    synchronized(this){retainedLease=lease;lease=null;}
                }
                if(lease!=null){lease.close();lease=null;}
            }
            if(!"completed".equals(receipt.optString("state"))) {
                publish(new JSONObject().put("state","failed").put("permanent",true).put("error",receipt.optString("error","AUTO_PHOTO_CAPTURE_FAILED")));return;
            }
            captured=receipt.getLong("captured_at");
            publish(evidence(receipt,"running"));
            cancellation.check();network.check();
            File ackFile=new File(folder,"upload-ack.json");
            if(ackFile.isFile()) {
                validateAck(folder,job,receipt);
            } else {
                PhotoAlarmUpload transfer=new PhotoAlarmUpload(new URI("https://v.elfradio.net"),identity,network);upload=transfer;
                try{cancellation.check();transfer.upload(receipt,cancellation);}finally{transfer.close();upload=null;}
            }
            publish(evidence(receipt,"completed"));
        } catch(Exception failure) {
            String code=failure.getMessage();if(code==null||!code.matches("[A-Z0-9_]{1,100}"))code="AUTO_PHOTO_FAILED";
            boolean waiting="MEDIA_BUSY".equals(code)||"AUTO_PHOTO_NETWORK_PAUSED".equals(code)
                    ||"AUTO_PHOTO_CAMERA_BUSY".equals(code)||"AUTO_PHOTO_CAMERA_UNKNOWN".equals(code);
            boolean permanent=failure instanceof PhotoAlarmUpload.Rejected&&((PhotoAlarmUpload.Rejected)failure).status>=400
                    &&((PhotoAlarmUpload.Rejected)failure).status<500&&((PhotoAlarmUpload.Rejected)failure).status!=429;
            try{publish(new JSONObject().put("state",waiting?"waiting":"failed").put("error",code).put("permanent",permanent).put("captured_at",captured));}catch(Exception ignored){}
        } finally {if(lease!=null){
            if(!cameraReleased.get()){synchronized(this){retainedLease=lease;}}
            else try{lease.close();}catch(Exception ignored){}
        }}
    }
    private static JSONObject evidence(JSONObject receipt,String state)throws Exception {
        JSONObject out=new JSONObject().put("state",state);
        for(String key:new String[]{"captured_at","source","sha256","bytes"})out.put(key,receipt.get(key));return out;
    }
    private static void validateAck(File folder,JSONObject job,JSONObject receipt)throws Exception {
        PhotoReport.uploadParameters(receipt);JSONObject ack=MediaFiles.read(new File(folder,"upload-ack.json"));
        File photo=MediaFiles.plain(new File(folder,"capture.jpg"));
        if(!photo.getPath().equals(receipt.getString("path"))||photo.length()!=receipt.getLong("bytes")
                ||!MediaFiles.hash(photo).equals(receipt.getString("sha256"))||!job.getString("report_id").equals(ack.optString("report_id"))
                ||receipt.getLong("bytes")!=ack.optLong("bytes")||!receipt.getString("sha256").equals(ack.optString("sha256")))throw new IOException("AUTO_PHOTO_ACK_MISMATCH");
    }
    private File root(JSONObject job)throws Exception {
        return MediaFiles.directory(new File(new File(MediaFiles.directory(new File(files,"automatic-photo")),job.getString("device_id")),job.getString("report_id")));
    }
    synchronized JSONObject finish(JSONObject job)throws Exception {
        if(owned!=null&&owned.getString("report_id").equals(job.getString("report_id"))&&owned.getString("device_id").equals(job.getString("device_id"))) {
            cancel();reap();if(busy())return new JSONObject().put("cleanup_complete",false);
        }
        File directory=root(job);erase(directory);
        return new JSONObject().put("cleanup_complete",true);
    }
    private static void erase(File file)throws Exception {
        MediaFiles.plain(file);if(!file.exists())return;
        if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("AUTO_PHOTO_CLEANUP_FAILED");for(File child:children)erase(child);}
        if(!file.delete())throw new IOException("AUTO_PHOTO_CLEANUP_FAILED");
    }
    synchronized void cancel(){
        cancel.cancel();PhotoAlarmUpload transfer=upload;
        if(transfer!=null&&abortScheduled.compareAndSet(false,true))aborter.execute(transfer::close);
    }
    synchronized void cancelGeneration(Cancellation generation){if(cancel==generation)cancel();}
    private void reap()throws Exception {if(!running&&retainedLease!=null&&cameraReleased.get()){retainedLease.close();retainedLease=null;}}
    public void close(){closed=true;cancel();worker.shutdown();timer.shutdown();aborter.shutdown();}
}
