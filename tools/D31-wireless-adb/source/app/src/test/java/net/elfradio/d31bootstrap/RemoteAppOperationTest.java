package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 使用真实桌面文件租约与持久记录验证跨调用恢复；不连接设备或启动APP。 */
public class RemoteAppOperationTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String HASH=repeat('a'), REQUEST="12345678-1234-1234-1234-123456789abc";
    private static final String BOOT="00000000-0000-0000-0000-000000000001", NEXT_BOOT="00000000-0000-0000-0000-000000000002";
    private static String repeat(char value){StringBuilder s=new StringBuilder();for(int i=0;i<64;i++)s.append(value);return s.toString();}
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    private static JSONObject proc(int pid,int uid,String start,String name)throws Exception{
        return new JSONObject().put("pid",pid).put("uid",uid).put("start_time",start).put("process_name",name);
    }
    private final class Disk implements RemoteAppOperation.Access {
        final File root;
        String boot=BOOT;
        boolean failWrite,failReserve,failRelease;
        JSONObject liveOwner=proc(123,0,"10","app_process"),liveApp=proc(456,10001,"20","net.elfradio.d31bootstrap");
        boolean ownerAbsent,appAbsent,observationFailed;
        Disk()throws Exception{root=temporary.newFolder();}
        public AutoCloseable acquire()throws Exception{return RemoteMaintenance.acquire(root);}
        public String boot(){return boot;}
        public void requireStart(String hash)throws Exception{if(reservation()!=null)throw new IOException("busy");}
        JSONObject readFile(String name)throws Exception{File file=new File(root,name);return file.exists()?new JSONObject(RescueFiles.read(file,32768)):null;}
        public JSONObject read(String id)throws Exception{return readFile(id+".json");}
        public void write(String id,JSONObject value)throws Exception{
            if(failWrite)throw new IOException("write failed");RescueFiles.write(new File(root,id+".json"),value.toString());
        }
        public JSONObject reservation()throws Exception{return readFile("repair.json");}
        public void reserve(JSONObject value)throws Exception{
            if(failReserve)throw new IOException("reserve failed");
            if(reservation()!=null)throw new IOException("busy");
            RescueFiles.write(new File(root,"repair.json"),RemoteAppOperation.reservationFor(value).toString());
        }
        public void release(JSONObject value)throws Exception{
            if(failRelease)throw new IOException("unlink failed");
            if(!RemoteProtocol.sameJson(reservation(),RemoteAppOperation.reservationFor(value)))throw new IOException("wrong reservation");
            if(!new File(root,"repair.json").delete())throw new IOException("delete failed");
        }
        public JSONObject owner()throws Exception{return copy(liveOwner);}
        public JSONObject application(String kind,String hash,int pid,int uid)throws Exception{
            if(appAbsent||pid!=liveApp.getInt("pid")||uid!=liveApp.getInt("uid")||observationFailed)throw new IOException("identity unknown");
            return copy(liveApp);
        }
        public RemoteAppOperation.Presence presence(JSONObject saved)throws Exception{
            if(observationFailed)return RemoteAppOperation.Presence.UNKNOWN;
            boolean owner=saved.getInt("pid")==123;
            if(owner?ownerAbsent:appAbsent)return RemoteAppOperation.Presence.DEAD;
            return RemoteAppOperationFiles.compareProcess(saved,owner?liveOwner:liveApp);
        }
    }
    private RemoteAppOperation begin(Disk d,String kind,String id)throws Exception{
        return RemoteAppOperation.open(d,kind,id,HASH,RemoteAppOperation.CONTACTS.equals(kind)?new JSONObject():new JSONObject().put("duration_ms",2500),true);
    }
    private RemoteAppOperation reopen(Disk d,String id)throws Exception{return RemoteAppOperation.open(d,null,id,HASH,null,false);}
    private RemoteAppOperation armed(Disk d,String kind)throws Exception{
        RemoteAppOperation operation=begin(d,kind,"task");operation.arm(REQUEST,456,10001);return operation;
    }
    private JSONObject audio()throws Exception{
        return new JSONObject().put("operation_request_id",REQUEST).put("app_pid",456).put("app_uid",10001)
                .put("operation",RemoteAppOperation.AUDIO).put("diagnostic_id","task").put("duration_ms",2500)
                .put("worker_finished",true).put("factory_release_pending",false).put("audio_record_created",true)
                .put("recording_started",true).put("release_verified",true).put("stop_completed",true).put("stop_error","").put("release_error","")
                .put("input_lifecycle",new JSONObject().put("state","closed").put("release_confirmed",true)
                        .put("native_start_attempted",true).put("native_stop_returned",true).put("native_release_returned",true).put("reader_finished",true));
    }
    private JSONObject contacts()throws Exception{
        return new JSONObject().put("operation_request_id",REQUEST).put("app_pid",456).put("app_uid",10001)
                .put("operation_apk_sha256",HASH).put("kind","NEXUI_APP_LOCAL_METADATA").put("ok",false)
                .put("remoteOutcomeKnown",true).put("bindingRequested",true).put("unbindConfirmed",true).put("replyChannelClosed",true);
    }
    private interface Attempt{void run()throws Exception;}
    private static void refused(Attempt attempt)throws Exception{try{attempt.run();fail("应拒绝");}catch(IOException expected){}}

    @Test public void rootCloseDoesNotReleaseAndSameIdNeverReplays()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation op=armed(d,RemoteAppOperation.AUDIO)){assertTrue(op.shouldExecute());assertNull(d.acquire());}
        assertNotNull(d.reservation());
        try(RemoteAppOperation op=begin(d,RemoteAppOperation.AUDIO,"task")){assertFalse(op.shouldExecute());assertFalse(op.recoverDeath());}
        refused(()->{try(RemoteAppOperation ignored=begin(d,RemoteAppOperation.CONTACTS,"other")) {}});
        assertNotNull(d.reservation());
    }
    @Test public void fullRequestAndBootAreBoundAcrossReopen()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation ignored=begin(d,RemoteAppOperation.AUDIO,"task")){}
        refused(()->{try(RemoteAppOperation ignored=RemoteAppOperation.open(d,RemoteAppOperation.AUDIO,"task",HASH,new JSONObject().put("duration_ms",5000),true)) {}});
        refused(()->{try(RemoteAppOperation ignored=begin(d,RemoteAppOperation.CONTACTS,"task")) {}});
        refused(()->{try(RemoteAppOperation ignored=RemoteAppOperation.open(d,null,"task",repeat('b'),null,false)) {}});
        d.boot=NEXT_BOOT;d.ownerAbsent=true;
        refused(()->{try(RemoteAppOperation ignored=begin(d,RemoteAppOperation.AUDIO,"task")) {}});
        try(RemoteAppOperation query=reopen(d,"task")){
            assertEquals(BOOT,query.status().getString("record_boot_id"));assertEquals(NEXT_BOOT,query.status().getString("current_boot_id"));
            assertFalse(query.status().getBoolean("reservation_released"));
        }
        assertNotNull(d.reservation());
    }
    @Test public void preparedRequiresConfirmedOriginalOwnerDeath()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation ignored=begin(d,RemoteAppOperation.CONTACTS,"task")){}
        try(RemoteAppOperation op=reopen(d,"task")){
            assertFalse(op.recoverDeath());d.observationFailed=true;assertFalse(op.recoverDeath());
            d.observationFailed=false;d.ownerAbsent=true;assertTrue(op.recoverDeath());assertTrue(op.status().getBoolean("reservation_released"));
        }
    }
    @Test public void preparedBeforeReservationFailureCanRecoverWithoutReexecution()throws Exception{
        Disk d=new Disk();d.failReserve=true;refused(()->begin(d,RemoteAppOperation.AUDIO,"task"));
        assertNotNull(d.read("task"));assertNull(d.reservation());
        try(RemoteAppOperation op=reopen(d,"task")){
            assertFalse(op.shouldExecute());assertFalse(op.recoverDeath());d.ownerAbsent=true;
            assertTrue(op.recoverDeath());assertTrue(op.status().getBoolean("reservation_released"));
        }
    }
    @Test public void armedRootDeathIsInsufficientButOriginalAppDeathReleases()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation ignored=armed(d,RemoteAppOperation.AUDIO)){}
        d.ownerAbsent=true;
        try(RemoteAppOperation op=reopen(d,"task")){
            assertFalse(op.recoverDeath());d.observationFailed=true;assertFalse(op.recoverDeath());
            d.observationFailed=false;d.appAbsent=true;assertTrue(op.recoverDeath());
        }
    }
    @Test public void samePidNewStartTimeProvesOriginalEndedNotPermissionFailure()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation ignored=armed(d,RemoteAppOperation.AUDIO)){}
        try(RemoteAppOperation op=reopen(d,"task")){
            d.liveApp.put("uid",10002);assertFalse(op.recoverDeath());
            d.liveApp.put("start_time","21");assertTrue(op.recoverDeath());
        }
    }
    @Test public void wrongReleaseReceiptsCannotClearReservation()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation op=armed(d,RemoteAppOperation.AUDIO)){
            String[] keys={"operation_request_id","app_pid","app_uid","diagnostic_id","duration_ms","worker_finished","factory_release_pending","stop_completed","release_verified","stop_error","release_error"};
            Object[] values={"another",999,10002,"other",2501,false,true,false,"true","IOException","IOException"};
            for(int i=0;i<keys.length;i++){assertFalse(op.finish(audio().put(keys[i],values[i])).getBoolean("reservation_released"));assertNotNull(d.reservation());}
            for(String key:new String[]{"release_confirmed","native_stop_returned","native_release_returned","reader_finished"}){
                JSONObject result=audio();result.getJSONObject("input_lifecycle").put(key,false);
                assertFalse(op.finish(result).getBoolean("reservation_released"));
            }
            assertTrue(op.finish(audio()).getBoolean("reservation_released"));
        }
        try(RemoteAppOperation op=begin(d,RemoteAppOperation.AUDIO,"task")){assertFalse(op.shouldExecute());assertTrue(op.previousResult().getBoolean("release_verified"));}
    }
    @Test public void failureBeforeRecorderCreationNeedsActualWorkerAndFactoryRelease()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation op=armed(d,RemoteAppOperation.AUDIO)){
            JSONObject result=audio().put("audio_record_created",false).put("recording_started",false);result.remove("input_lifecycle");
            assertFalse(op.finish(copy(result).put("worker_finished",false)).getBoolean("reservation_released"));
            assertFalse(op.finish(copy(result).put("factory_release_pending",true)).getBoolean("reservation_released"));
            assertFalse(op.finish(copy(result).put("recording_started",true)).getBoolean("reservation_released"));
            assertTrue(op.finish(result).getBoolean("reservation_released"));
        }
    }
    @Test public void contactsFailureWithRealCleanupCanReleaseButRemainsBusinessFailure()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation op=armed(d,RemoteAppOperation.CONTACTS)){
            for(String key:new String[]{"remoteOutcomeKnown","unbindConfirmed","replyChannelClosed"})
                assertFalse(op.finish(contacts().put(key,false)).getBoolean("reservation_released"));
            assertFalse(op.finish(contacts().put("operation_apk_sha256",repeat('b'))).getBoolean("reservation_released"));
            assertFalse(op.finish(contacts().put("unbindConfirmed","true")).getBoolean("reservation_released"));
            assertTrue(op.finish(contacts()).getBoolean("reservation_released"));assertFalse(op.previousResult().getBoolean("ok"));
        }
    }
    @Test public void failedArmPersistencePreventsExecution()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation op=begin(d,RemoteAppOperation.AUDIO,"task")){
            d.failWrite=true;boolean sent=false;
            try{op.arm(REQUEST,456,10001);sent=true;fail();}catch(IOException expected){}
            assertFalse(sent);assertEquals("PREPARED",d.read("task").getString("state"));assertNotNull(d.reservation());
        }
    }
    @Test public void closedOrInterruptedCallerCannotArmOrRelease()throws Exception{
        Disk d=new Disk();RemoteAppOperation closed=begin(d,RemoteAppOperation.AUDIO,"task");closed.close();
        refused(()->closed.arm(REQUEST,456,10001));assertNotNull(d.reservation());
        Disk other=new Disk();try(RemoteAppOperation op=armed(other,RemoteAppOperation.AUDIO)){
            Thread.currentThread().interrupt();try{refused(()->op.finish(audio()));assertTrue(Thread.currentThread().isInterrupted());}
            finally{Thread.interrupted();}assertNotNull(other.reservation());
        }
    }
    @Test public void releaseUnlinkFailureIsRetriedWithoutExecutingAgain()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation op=armed(d,RemoteAppOperation.AUDIO)){
            d.failRelease=true;refused(()->op.finish(audio()));assertFalse(op.status().getBoolean("reservation_released"));
        }
        d.failRelease=false;try(RemoteAppOperation op=reopen(d,"task")){assertTrue(op.recoverDeath());assertTrue(op.status().getBoolean("reservation_released"));}
    }
    @Test public void cannotOverwriteOrReleaseAnotherOperationsReservation()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation op=armed(d,RemoteAppOperation.AUDIO)){
            JSONObject other=new JSONObject().put("task_id","another").put("plan_sha256",HASH);
            RescueFiles.write(new File(d.root,"repair.json"),other.toString());
            refused(()->op.finish(audio()));d.appAbsent=true;refused(()->op.recoverDeath());assertTrue(RemoteProtocol.sameJson(other,d.reservation()));
        }
    }
    @Test public void genericRepairCannotClaimOrClearAppKind()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation ignored=begin(d,RemoteAppOperation.AUDIO,"task")){}
        refused(()->RemoteMaintenance.reserve(d.root,"app-task",HASH));refused(()->RemoteMaintenance.release(d.root,"app-task",HASH));assertNotNull(d.reservation());
    }
    @Test public void recoveryHandshakeRequiresSameFrozenProcess()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation op=armed(d,RemoteAppOperation.CONTACTS)){
            op.verifyRecoveryPeer("new-recovery-request",456,10001);
            d.liveApp.put("start_time","21");refused(()->op.verifyRecoveryPeer("new-recovery-request",456,10001));
            assertNotNull(d.reservation());
        }
    }
    @Test public void coherentReleaseReceiptStillNeedsFreshProcessObservation()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation op=armed(d,RemoteAppOperation.AUDIO)){
            d.observationFailed=true;assertFalse(op.finish(audio()).getBoolean("reservation_released"));
            d.observationFailed=false;d.boot=NEXT_BOOT;refused(()->op.finish(audio()));assertNotNull(d.reservation());
        }
    }
    @Test public void processComparisonRejectsPartialOrWrongIdentity()throws Exception{
        JSONObject saved=proc(456,10001,"20","app");
        assertEquals(RemoteAppOperation.Presence.ALIVE,RemoteAppOperationFiles.compareProcess(saved,copy(saved)));
        for(JSONObject changed:new JSONObject[]{copy(saved).put("pid",455),copy(saved).put("uid",0),copy(saved).put("process_name","other"),copy(saved).put("start_time",""),new JSONObject()})
            assertEquals(RemoteAppOperation.Presence.UNKNOWN,RemoteAppOperationFiles.compareProcess(saved,changed));
        assertEquals(RemoteAppOperation.Presence.DEAD,RemoteAppOperationFiles.compareProcess(saved,copy(saved).put("start_time","21")));
    }
    @Test public void statParserHandlesParenthesesAndRejectsTruncation()throws Exception{
        StringBuilder line=new StringBuilder("456 (name with ) brackets) S");for(int field=4;field<=21;field++)line.append(" 0");line.append(" 12345 0");
        assertEquals("12345",RemoteAppOperationFiles.startTime(line.toString()));
        refused(()->RemoteAppOperationFiles.startTime("456 (app) S 0"));
        refused(()->RemoteAppOperationFiles.startTime(line.toString().replace("12345","invalid")));
    }
    @Test public void parentAndRecordModesRejectLinksWritableParentsAndHardlinkedRecords(){
        assertTrue(RemoteAppOperationFiles.safeAttributes(0,0040755,2,true));assertTrue(RemoteAppOperationFiles.safeAttributes(0,0100600,1,false));
        assertFalse(RemoteAppOperationFiles.safeAttributes(0,0040775,2,true));assertFalse(RemoteAppOperationFiles.safeAttributes(1000,0040755,2,true));
        assertFalse(RemoteAppOperationFiles.safeAttributes(0,0120777,1,false));assertFalse(RemoteAppOperationFiles.safeAttributes(0,0100600,2,false));
        assertFalse(RemoteAppOperationFiles.safeAttributes(0,0010600,1,false));
    }
    @Test public void explicitCrossBootRecoveryReleasesPreparedAndArmedWithoutAppCall()throws Exception{
        for(boolean armed:new boolean[]{false,true}){
            Disk d=new Disk();try(RemoteAppOperation op=begin(d,RemoteAppOperation.AUDIO,"task")){if(armed)op.arm(REQUEST,456,10001);}
            d.boot=NEXT_BOOT;d.observationFailed=true;
            try(RemoteAppOperation op=reopen(d,"task")){
                assertFalse(op.shouldExecute());assertTrue(op.recoverDeath());assertNull(d.reservation());
                assertEquals("OLD_BOOT_RESOURCES_ENDED",op.status().getString("release_reason"));
            }
        }
    }
    @Test public void crossBootRecoveryCannotClearAnotherReservation()throws Exception{
        Disk d=new Disk();try(RemoteAppOperation ignored=armed(d,RemoteAppOperation.AUDIO)){}
        JSONObject other=new JSONObject().put("task_id","another").put("plan_sha256",HASH);
        RescueFiles.write(new File(d.root,"repair.json"),other.toString());d.boot=NEXT_BOOT;
        try(RemoteAppOperation op=reopen(d,"task")){refused(()->op.recoverDeath());assertTrue(RemoteProtocol.sameJson(other,d.reservation()));}
    }
    @Test public void crossBootPreparedWithoutReservationAlsoRecovers()throws Exception{
        Disk d=new Disk();d.failReserve=true;refused(()->begin(d,RemoteAppOperation.AUDIO,"task"));d.boot=NEXT_BOOT;
        try(RemoteAppOperation op=reopen(d,"task")){assertTrue(op.recoverDeath());assertTrue(op.status().getBoolean("reservation_released"));}
    }
}
