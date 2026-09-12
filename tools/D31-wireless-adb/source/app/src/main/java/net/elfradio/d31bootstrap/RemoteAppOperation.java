package net.elfradio.d31bootstrap;

import android.content.Context;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.elfradio.d31bootstrap.management.ContactsAppBridge;
import net.elfradio.d31bootstrap.management.SystemManagement;
import net.elfradio.d31bootstrap.media.AppMediaBridge;
import org.json.JSONObject;

/** 两种实际APP资源操作的持久预留；全程复用RemoteMaintenance，不创建第二把锁。 */
public final class RemoteAppOperation implements AutoCloseable {
    public static final String AUDIO="local_audio_capture", CONTACTS="read-local-metadata";
    public static final String CONTACT_PAGES="read-local-pages";
    static boolean isContacts(String kind) { return CONTACTS.equals(kind) || CONTACT_PAGES.equals(kind); }
    enum Presence { ALIVE, DEAD, UNKNOWN }
    interface Access {
        AutoCloseable acquire() throws Exception;
        String boot() throws Exception;
        void requireStart(String hash) throws Exception;
        JSONObject read(String id) throws Exception;
        void write(String id,JSONObject record) throws Exception;
        JSONObject reservation() throws Exception;
        void reserve(JSONObject record) throws Exception;
        void release(JSONObject record) throws Exception;
        JSONObject owner() throws Exception;
        JSONObject application(String kind,String hash,int pid,int uid) throws Exception;
        Presence presence(JSONObject process) throws Exception;
    }
    private final Access access;
    private final AutoCloseable lease;
    private final JSONObject record;
    private final boolean fresh;
    private boolean closed;
    private RemoteAppOperation(Access access,AutoCloseable lease,JSONObject record,boolean fresh){
        this.access=access;this.lease=lease;this.record=record;this.fresh=fresh;
    }
    public static RemoteAppOperation begin(Context context,String kind,String id,String hash,JSONObject params)throws Exception{
        return open(new RemoteAppOperationFiles(context,hash),kind,id,hash,params,true);
    }
    static RemoteAppOperation open(Access access,String kind,String id,String hash,JSONObject params,boolean start)throws Exception{
        validate(id,hash);
        if(start&&(!AUDIO.equals(kind)&&!isContacts(kind)))throw new IOException("APP_OPERATION_KIND_INVALID");
        AutoCloseable lease=access.acquire();if(lease==null)throw new IOException("APP_OPERATION_MAINTENANCE_BUSY");
        try{
            JSONObject old=access.read(id);
            if(old!=null){
                requireRecord(old,id,hash);
                if(start&&!old.getString("boot_id").equals(access.boot()))throw new IOException("APP_OPERATION_BOOT_MISMATCH");
                if(start&&(!kind.equals(old.getString("operation"))||!RemoteProtocol.sameJson(params,old.getJSONObject("params"))))
                    throw new IOException("APP_OPERATION_REQUEST_CONFLICT");
                return new RemoteAppOperation(access,lease,old,false);
            }
            if(!start)throw new IOException("APP_OPERATION_NOT_FOUND");
            access.requireStart(hash);
            JSONObject record=new JSONObject().put("schema_version",1).put("operation_id",id).put("operation",kind)
                    .put("apk_sha256",hash).put("boot_id",access.boot()).put("params",new JSONObject(params.toString()))
                    .put("state","PREPARED").put("owner",access.owner());
            access.write(id,record);access.reserve(record);
            return new RemoteAppOperation(access,lease,record,true);
        }catch(Exception|Error failure){try{lease.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    static void validate(String id,String hash)throws IOException{
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,96}")||hash==null||!hash.matches("[a-f0-9]{64}"))
            throw new IOException("APP_OPERATION_ARGUMENTS_INVALID");
    }
    static void requireRecord(JSONObject record,String id,String hash)throws Exception{
        if(!Integer.valueOf(1).equals(record.opt("schema_version"))||!id.equals(record.getString("operation_id"))
                ||!hash.equals(record.getString("apk_sha256"))
                ||!record.getString("boot_id").matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                ||!(AUDIO.equals(record.getString("operation"))||isContacts(record.getString("operation")))
                ||!java.util.Arrays.asList("PREPARED","ARMED","RELEASED").contains(record.getString("state")))
            throw new IOException("APP_OPERATION_RECORD_MISMATCH");
        JSONObject params=record.getJSONObject("params"),owner=record.getJSONObject("owner");
        if(RemoteAppOperationFiles.compareProcess(owner,owner)!=Presence.ALIVE
                ||(isContacts(record.getString("operation"))?params.length()!=0
                :params.length()!=1||!(params.opt("duration_ms") instanceof Integer)
                    ||params.getInt("duration_ms")<1||params.getInt("duration_ms")>5000))
            throw new IOException("APP_OPERATION_RECORD_MISMATCH");
        if("ARMED".equals(record.getString("state"))){
            JSONObject app=record.getJSONObject("app");
            if(RemoteAppOperationFiles.compareProcess(app,app)!=Presence.ALIVE
                    ||!record.getString("request_id").matches("[a-f0-9-]{36}"))throw new IOException("APP_OPERATION_RECORD_MISMATCH");
        }
    }
    static JSONObject reservationFor(JSONObject record)throws Exception{
        return new JSONObject().put("task_id","app-"+record.getString("operation_id"))
                .put("plan_sha256",record.getString("apk_sha256")).put("kind","app_operation");
    }
    private void ownReservation()throws Exception{
        if(!RemoteProtocol.sameJson(reservationFor(record),access.reservation()))throw new IOException("APP_OPERATION_RESERVATION_MISMATCH");
    }
    private void check()throws Exception{
        if(closed||Thread.currentThread().isInterrupted())throw new IOException("APP_OPERATION_CLOSED_OR_CANCELLED");
        if(!record.getString("boot_id").equals(access.boot()))throw new IOException("APP_OPERATION_BOOT_MISMATCH");
    }
    public boolean shouldExecute(){return fresh;}
    public synchronized void arm(String requestId,int pid,int uid)throws Exception{
        check();ownReservation();
        if(!fresh||!"PREPARED".equals(record.getString("state"))||requestId==null||!requestId.matches("[a-f0-9-]{36}"))
            throw new IOException("APP_OPERATION_ALREADY_ARMED");
        JSONObject app=access.application(record.getString("operation"),record.getString("apk_sha256"),pid,uid);
        record.put("app",app).put("request_id",requestId).put("state","ARMED");
        access.write(record.getString("operation_id"),record);
    }
    public synchronized void verifyRecoveryPeer(String ignoredRequest,int pid,int uid)throws Exception{
        check();ownReservation();
        JSONObject current=access.application(record.getString("operation"),record.getString("apk_sha256"),pid,uid);
        if(!RemoteProtocol.sameJson(current,record.getJSONObject("app")))throw new IOException("APP_OPERATION_PROCESS_CHANGED");
    }
    static boolean receiptReleased(JSONObject record,JSONObject result)throws Exception{
        if(!"ARMED".equals(record.getString("state"))||result==null)return false;
        JSONObject app=record.getJSONObject("app");
        if(!record.getString("request_id").equals(result.optString("operation_request_id"))
                ||!Integer.valueOf(app.getInt("pid")).equals(result.opt("app_pid"))
                ||!Integer.valueOf(app.getInt("uid")).equals(result.opt("app_uid")))return false;
        if(isContacts(record.getString("operation")))return
                record.getString("apk_sha256").equals(result.optString("operation_apk_sha256"))
                &&(CONTACT_PAGES.equals(record.getString("operation"))
                    ? "read_local_pages".equals(result.optString("operation"))
                    : !"read_local_pages".equals(result.optString("operation")))
                &&"NEXUI_APP_LOCAL_METADATA".equals(result.optString("kind"))&&ContactsAppBridge.cleanupConfirmed(result);
        if(!AUDIO.equals(result.optString("operation"))||!record.getString("operation_id").equals(result.optString("diagnostic_id"))
                ||!Integer.valueOf(record.getJSONObject("params").getInt("duration_ms")).equals(result.opt("duration_ms"))
                ||!Boolean.TRUE.equals(result.opt("worker_finished"))||!Boolean.FALSE.equals(result.opt("factory_release_pending")))return false;
        if(Boolean.FALSE.equals(result.opt("audio_record_created")))return Boolean.FALSE.equals(result.opt("recording_started"));
        JSONObject lifecycle=result.optJSONObject("input_lifecycle");
        return Boolean.TRUE.equals(result.opt("release_verified"))&&Boolean.TRUE.equals(result.opt("stop_completed"))
                &&"".equals(result.opt("stop_error"))&&"".equals(result.opt("release_error"))
                &&lifecycle!=null&&"closed".equals(lifecycle.optString("state"))
                &&Boolean.TRUE.equals(lifecycle.opt("release_confirmed"))
                &&lifecycle.opt("native_start_attempted") instanceof Boolean
                &&(!Boolean.TRUE.equals(lifecycle.opt("native_start_attempted"))||Boolean.TRUE.equals(lifecycle.opt("native_stop_returned")))
                &&Boolean.TRUE.equals(lifecycle.opt("native_release_returned"))&&Boolean.TRUE.equals(lifecycle.opt("reader_finished"));
    }
    public synchronized JSONObject finish(JSONObject result)throws Exception{
        check();ownReservation();
        record.put("last_result",new JSONObject(result.toString()));
        access.write(record.getString("operation_id"),record);
        if(receiptReleased(record,result)&&access.presence(record.getJSONObject("app"))==Presence.ALIVE)release("MATCHED_RELEASE_RECEIPT");
        return status();
    }
    private void release(String reason)throws Exception{
        ownReservation();record.put("state","RELEASED").put("release_reason",reason);
        access.write(record.getString("operation_id"),record);
        access.release(record);
    }
    synchronized boolean recoverDeath()throws Exception{
        if(closed||Thread.currentThread().isInterrupted())throw new IOException("APP_OPERATION_CLOSED_OR_CANCELLED");
        String currentBoot=access.boot();
        if(!currentBoot.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IOException("APP_OPERATION_BOOT_UNKNOWN");
        if("RELEASED".equals(record.getString("state"))){
            if(RemoteProtocol.sameJson(reservationFor(record),access.reservation()))access.release(record);
            return true;
        }
        if(!record.getString("boot_id").equals(currentBoot)){
            if("PREPARED".equals(record.getString("state"))&&access.reservation()==null){
                record.put("state","RELEASED").put("release_reason","OLD_BOOT_UNARMED_BEFORE_RESERVE");
                access.write(record.getString("operation_id"),record);
            }else release("OLD_BOOT_RESOURCES_ENDED");
            return true;
        }
        JSONObject process="ARMED".equals(record.getString("state"))?record.getJSONObject("app"):record.getJSONObject("owner");
        // 写PREPARED后、写共享预留前中断：尚未允许APP执行；也须确认原宿主已死，不能按时间猜测。
        if("PREPARED".equals(record.getString("state"))&&access.reservation()==null){
            if(access.presence(process)!=Presence.DEAD)return false;
            record.put("state","RELEASED").put("release_reason","UNARMED_OWNER_ENDED_BEFORE_RESERVE");
            access.write(record.getString("operation_id"),record);return true;
        }
        ownReservation();
        if(access.presence(process)==Presence.DEAD){release("ARMED".equals(record.getString("state"))?"ORIGINAL_APP_PROCESS_ENDED":"UNARMED_OWNER_ENDED");return true;}
        return false;
    }
    public synchronized JSONObject status()throws Exception{
        return new JSONObject().put("operation_id",record.getString("operation_id")).put("operation",record.getString("operation"))
                .put("apk_sha256",record.getString("apk_sha256")).put("operation_request_id",record.optString("request_id"))
                .put("record_boot_id",record.getString("boot_id")).put("current_boot_id",access.boot())
                .put("state",record.getString("state")).put("reservation_released","RELEASED".equals(record.getString("state"))
                        &&!RemoteProtocol.sameJson(reservationFor(record),access.reservation()))
                .put("release_reason",record.optString("release_reason")).put("managed_media",false).put("network_write",false);
    }
    public synchronized JSONObject previousResult()throws Exception{
        JSONObject result=record.optJSONObject("last_result");return result==null?status():new JSONObject(result.toString());
    }
    @Override public synchronized void close()throws Exception{if(!closed){closed=true;lease.close();}}
    public static Context context()throws Exception{
        if(android.os.Looper.getMainLooper()==null)android.os.Looper.prepareMainLooper();
        Class<?> type=Class.forName("android.app.ActivityThread");Object thread=type.getMethod("systemMain").invoke(null);
        return (Context)type.getMethod("getSystemContext").invoke(thread);
    }
    public static SystemManagement.Control control(){return new SystemManagement.Control(){
        public void check()throws Exception{if(Thread.currentThread().isInterrupted())throw new InterruptedException();}
        public void before(JSONObject ignored)throws Exception{throw new IOException("APP_OPERATION_SETTING_WRITE_FORBIDDEN");}
    };}
    static JSONObject recover(Context context,RemoteAppOperation operation)throws Exception{
        if(operation.recoverDeath())return operation.status();
        if(!"ARMED".equals(operation.record.getString("state")))return operation.status();
        JSONObject result;
        if(isContacts(operation.record.getString("operation"))){
            try(ContactsAppBridge bridge=new ContactsAppBridge(context)){
                result=bridge.recoverLocalMetadata(operation.record.getString("apk_sha256"),operation.record.getString("request_id"),control(),operation::verifyRecoveryPeer);
            }
        }else{
            CountDownLatch done=new CountDownLatch(1);AtomicReference<JSONObject> reply=new AtomicReference<>();
            try(AppMediaBridge bridge=new AppMediaBridge(context)){
                bridge.recoverLocalAudio(operation.record.getString("operation_id"),operation::verifyRecoveryPeer,new AppMediaBridge.Callback(){
                    public void completed(JSONObject value){reply.set(value);done.countDown();}
                    public void failed(String code){done.countDown();}
                });
                if(!done.await(8,TimeUnit.SECONDS))throw new IOException("APP_OPERATION_RECOVERY_REPLY_UNKNOWN");
                result=reply.get();
            }
        }
        if(result!=null)operation.finish(result);
        return operation.status();
    }
    public static void main(String[] args){
        try{
            if(args==null||args.length!=3||!("query".equals(args[0])||"recover".equals(args[0])))throw new IOException("APP_OPERATION_ARGUMENTS_INVALID");
            validate(args[2],args[1]);RemoteAppOperationFiles.requireDevice();
            Context context=context();
            try(RemoteAppOperation operation=open(new RemoteAppOperationFiles(context,args[1]),null,args[2],args[1],null,false)){
                System.out.println("recover".equals(args[0])?recover(context,operation):operation.status());
            }
            System.exit(0);
        }catch(Exception failure){
            String code=failure.getMessage();if(code==null||!code.matches("APP_OPERATION_[A-Z0-9_]{1,80}"))code="APP_OPERATION_FAILED";
            System.out.println("{\"reservation_released\":false,\"error\":\""+code+"\"}");System.exit(1);
        }
    }
}
