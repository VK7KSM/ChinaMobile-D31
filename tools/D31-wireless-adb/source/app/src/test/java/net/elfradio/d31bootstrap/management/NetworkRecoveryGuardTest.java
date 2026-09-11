package net.elfradio.d31bootstrap.management;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class NetworkRecoveryGuardTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test(timeout=8000) public void realChildHandshakesWhileBeginStillOwnsJournalThenRecovers() throws Exception { fork("test-boot"); }
    @Test(timeout=8000) public void realChildAfterRebootRecoversPersistedTransaction() throws Exception { fork("new-boot"); }
    private void fork(String boot) throws Exception {
        File dir = temporary.newFolder();
        Files.write(new File(dir, "device").toPath(), "false".getBytes(StandardCharsets.UTF_8));
        NetworkChangeTransactionTest.Fixture fixture = new NetworkChangeTransactionTest.Fixture(); fixture.begin();
        Process child = null;
        try {
            try (NetworkChangeTransaction.Store.Session locked = new NetworkChangeTransactionJournal(dir).lock()) {
                locked.save(fixture.store.saved);
                child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(), "-cp",
                        System.getProperty("java.class.path"), NetworkRecoveryFork.class.getName(), dir.getPath(), boot)
                        .redirectErrorStream(true).start();
                long until = System.nanoTime() + 3000000000L;
                while (!new File(dir, "heartbeat").exists() && System.nanoTime() < until) Thread.sleep(10);
                assertTrue("子进程必须在父持日志锁时已发心跳", new File(dir, "heartbeat").exists());
                assertFalse(new File(dir, "settled").exists());
                assertEquals("false", new String(Files.readAllBytes(new File(dir, "device").toPath()), StandardCharsets.UTF_8));
            }
            assertEquals("ROLLED_BACK", NetworkProcess.collect(child, 3000));
            assertEquals("true", new String(Files.readAllBytes(new File(dir, "device").toPath()), StandardCharsets.UTF_8));
            JSONObject state = new JSONObject(new String(Files.readAllBytes(new File(dir, "settled").toPath()), StandardCharsets.UTF_8));
            assertTrue(state.getBoolean("restored"));
        } finally { if (child != null) child.destroy(); }
    }
    static final class Host implements NetworkRecoveryLoop.Host {
        long now; String boot = "a"; int hearts, queries, recovered, releases;
        JSONObject state = new JSONObject(); String result = "ROLLED_BACK";
        public long elapsed() { return now; }
        public String boot() { return boot; }
        public void heartbeat() { hearts++; }
        public JSONObject query() { assertTrue(hearts > queries++); return state; }
        public JSONObject recover() throws Exception { recovered++; return new JSONObject().put("state",result).put("restored",result.equals("ROLLED_BACK")); }
        public void settled(JSONObject s) { releases++; }
        public void pause() { now += 1000; }
    }
    @Test public void waitingUsesFixedDeadlineAndReleasesOnlySettledState() throws Exception {
        Host h = new Host(); h.state.put("state","AWAITING_CONFIRM");
        assertEquals("ROLLED_BACK",NetworkRecoveryLoop.run(h,"a",2000).getString("state"));
        assertEquals(3,h.hearts); assertEquals(1,h.recovered); assertEquals(1,h.releases);
    }
    @Test public void corruptJournalStopsWithoutSetterOrRelease() throws Exception {
        Host h = new Host(); h.state.put("state","UNKNOWN").put("reason","STORE_UNAVAILABLE");
        assertEquals("UNKNOWN",NetworkRecoveryLoop.run(h,"a",2000).getString("state"));
        assertEquals(0,h.recovered); assertEquals(0,h.releases);
    }
    @Test public void foreverBusyIsBoundedAndNotCalledRestored() throws Exception {
        Host h = new Host(); h.state.put("state","UNKNOWN").put("reason","STORE_BUSY");
        JSONObject out=NetworkRecoveryLoop.run(h,"a",2000);
        assertEquals("NEEDS_ATTENTION",out.getString("state")); assertFalse(out.getBoolean("restored"));
        assertEquals(0,h.recovered); assertEquals(0,h.releases);
    }
    @Test public void unknownBinderOutcomeDoesNotLoopAppendOrReleaseReservation() throws Exception {
        Host h = new Host(); h.state.put("state","APPLYING"); h.result="NEEDS_ATTENTION";
        assertEquals("NEEDS_ATTENTION",NetworkRecoveryLoop.run(h,"a",2000).getString("state"));
        assertEquals(1,h.recovered); assertEquals(0,h.releases);
    }
    @Test public void observedOriginalNotUpgradedToRestored() throws Exception {
        Host h = new Host(); h.state.put("state","APPLYING"); h.result="ORIGINAL_OBSERVED";
        JSONObject out=NetworkRecoveryLoop.run(h,"a",2000); assertFalse(out.getBoolean("restored")); assertEquals(1,h.releases);
    }
    @Test public void metadataRejectsLinksPermissionsAndNonRoot() throws Exception {
        NetworkAndroidFiles.requirePrivateMetadata(0100600,0,1,false);
        NetworkAndroidFiles.requirePrivateMetadata(0040700,0,2,true);
        for(int[] row:new int[][]{{0120600,0,1},{0100666,0,1},{0104600,0,1},{0100600,1000,1},{0100600,0,2},{0040700,0,1}}) {
            try { NetworkAndroidFiles.requirePrivateMetadata(row[0],row[1],row[2],false); fail(); } catch(IOException expected) { }
        }
    }
    @Test public void wifiStateNeverDefaultsTransitionOrUnknown() {
        assertEquals(Boolean.FALSE,NetworkWifiCommand.state(1)); assertEquals(Boolean.TRUE,NetworkWifiCommand.state(3));
        for(int value:new int[]{-1,0,2,4,5}) assertNull(NetworkWifiCommand.state(value));
    }
    @Test public void staleHeartbeatWrongInstanceWrongBootAndPidReuseRejected() throws Exception {
        JSONObject beat=new JSONObject().put("task_id","task").put("instance_id","nonce").put("boot_id","boot")
                .put("apk_sha256","hash").put("deadline_elapsed",5000).put("uid",0).put("pid",10).put("elapsed",1000).put("start_time","123");
        assertTrue(NetworkRecoveryGuard.fresh(beat,"task","nonce","boot","hash",5000,1500));
        assertFalse(NetworkRecoveryGuard.fresh(beat,"task","other","boot","hash",5000,1500));
        assertFalse(NetworkRecoveryGuard.fresh(beat,"task","nonce","other","hash",5000,1500));
        assertFalse(NetworkRecoveryGuard.fresh(beat,"task","nonce","boot","hash",5000,2001));
        assertFalse(NetworkRecoveryGuard.fresh(beat,"task","nonce","boot","hash",5000,999));
        String[] fields = new String[20]; java.util.Arrays.fill(fields,"0"); fields[19]="456";
        assertNotEquals(beat.getString("start_time"),NetworkRecoveryGuard.startTime("10 (name with ) char) " + String.join(" ",fields)));
    }
    @Test public void missingOrWrongSetterReceiptCannotAuthorizeLaterRecovery() throws Exception {
        JSONObject intent=new JSONObject().put("id","a").put("task_id","t").put("boot_id","b").put("apk_sha256","s").put("target",false);
        JSONObject result=new JSONObject(intent.toString());
        assertFalse(NetworkAndroidPlatform.callSettled(intent,result));
        result.put("setter_returned",true).put("request_accepted",true).put("target_observed",true);
        assertTrue(NetworkAndroidPlatform.callSettled(intent,result));
        result.put("id","old"); assertFalse(NetworkAndroidPlatform.callSettled(intent,result));
    }
    @Test public void slowTransitionRetainsBinderReturnWithoutClaimingTargetObserved() throws Exception {
        JSONObject intent=new JSONObject().put("id","a").put("task_id","t").put("boot_id","b").put("apk_sha256","s").put("target",false);
        JSONObject returned=NetworkWifiCommand.returnedReceipt(intent,true);
        assertFalse(returned.getBoolean("target_observed")); assertTrue(returned.getBoolean("request_accepted"));
        assertTrue(NetworkAndroidPlatform.callSettled(intent,returned));
        returned.put("task_id","other"); assertFalse(NetworkAndroidPlatform.callSettled(intent,returned));
    }
    @Test public void synchronousRejectionIsReturnedButNotAcceptedOrObserved() throws Exception {
        JSONObject intent=new JSONObject().put("id","a").put("task_id","t").put("boot_id","b").put("apk_sha256","s").put("target",false);
        JSONObject returned=NetworkWifiCommand.returnedReceipt(intent,false);
        assertTrue(NetworkAndroidPlatform.callSettled(intent,returned));
        assertFalse(returned.getBoolean("request_accepted")); assertFalse(returned.getBoolean("target_observed"));
        returned.put("setter_returned",false).put("target_observed",true);
        assertFalse(NetworkAndroidPlatform.callSettled(intent,returned));
    }
    static final class QueuedWifi implements NetworkChangeTransaction.Platform {
        Boolean actual = true; JSONObject receipt; int writes, observedSaves; boolean accepted = true, reconcileRollback;
        String boot = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        public AutoCloseable acquire(String task) { return () -> {}; }
        public void requireRecoveryOwner(String task,String boot,long deadline) { }
        public Boolean readWifiEnabled() throws Exception {
            JSONObject current = receipt;
            if (current != null && NetworkAndroidPlatform.callEndedByReboot(current.getString("boot_id"),boot)) current = null;
            return NetworkAndroidPlatform.observeCall(current,actual, saved -> { receipt=saved; observedSaves++; });
        }
        public void setWifiEnabled(boolean target) throws Exception {
            if (NetworkAndroidPlatform.pendingTarget(receipt)) throw new IOException("旧目标尚未落实");
            writes++;
            JSONObject intent=new JSONObject().put("id","call"+writes).put("task_id","task-1")
                    .put("boot_id",boot).put("apk_sha256","test").put("target",target);
            receipt=NetworkWifiCommand.returnedReceipt(intent,accepted);
            if (writes==1) throw new IOException("模拟返回后观察超时或明确拒绝");
            if (reconcileRollback) {
                // 模拟旧子进程观察窗口结束后目标才落实，传输失败但精确回执已持久保存。
                actual=target;
                NetworkAndroidPlatform.reconcileSetter(intent,receipt,false,this::readWifiEnabled);
                return;
            }
            actual=target; receipt.put("target_observed",true);
        }
    }
    @Test public void rollbackChildFailureCanReturnOnlyWithExactReceiptAndDurableActualTarget() throws Exception {
        QueuedWifi wifi=new QueuedWifi(); wifi.reconcileRollback=true;
        NetworkChangeTransaction engine=new NetworkChangeTransaction(new NetworkChangeTransactionTest.Memory(),wifi,new NetworkChangeTransactionTest.Time());
        assertEquals("NEEDS_ATTENTION",engine.begin("task-1",false,1000).getString("state"));
        wifi.actual=false;
        JSONObject result=engine.recover("task-1");
        assertEquals("ROLLED_BACK",result.getString("state")); assertTrue(result.getBoolean("rollback_returned"));
        assertTrue(result.getBoolean("original_verified")); assertTrue(result.getBoolean("restored"));
        assertEquals(2,wifi.observedSaves); assertEquals(2,wifi.writes);
    }
    @Test public void lostReplyMissingMismatchedRejectedAndPendingReceiptsNeverReturnSuccess() throws Exception {
        JSONObject intent=new JSONObject().put("id","a").put("task_id","t").put("boot_id","b").put("apk_sha256","s").put("target",false);
        JSONObject receipt=NetworkWifiCommand.returnedReceipt(intent,true);
        NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.reconcileSetter(intent,null,false,() -> { fail(); return false; }));
        for(String key:new String[]{"id","task_id","boot_id","apk_sha256"}) {
            JSONObject wrong=new JSONObject(receipt.toString()).put(key,"other");
            NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.reconcileSetter(intent,wrong,false,() -> { fail(); return false; }));
        }
        for(JSONObject bad:new JSONObject[]{new JSONObject(receipt.toString()).put("target",true),
                new JSONObject(receipt.toString()).put("request_accepted",false),
                new JSONObject(receipt.toString()).put("setter_returned",false)})
            NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.reconcileSetter(intent,bad,true,() -> { fail(); return false; }));
        NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.reconcileSetter(intent,receipt,false,
                () -> NetworkAndroidPlatform.observeCall(receipt,true,saved -> fail())));
        NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.reconcileSetter(intent,receipt,false,
                () -> NetworkAndroidPlatform.observeCall(receipt,false,saved -> {throw new IOException("落盘失败");})));
    }
    @Test public void lostReplyRequiresFreshReadEvenWithEarlierObservedFlag() throws Exception {
        JSONObject intent=new JSONObject().put("id","a").put("task_id","t").put("boot_id","b").put("apk_sha256","s").put("target",false);
        JSONObject receipt=NetworkWifiCommand.returnedReceipt(intent,true).put("target_observed",true);
        NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.reconcileSetter(intent,receipt,false,() -> true));
        NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.reconcileSetter(intent,receipt,false,() -> null));
        NetworkAndroidPlatform.reconcileSetter(intent,receipt,false,() -> false);
    }
    @Test public void cancellationNeverReconcilesOrReturnsSuccessAndKeepsInterruptFlag() throws Exception {
        JSONObject intent=new JSONObject().put("id","a").put("task_id","t").put("boot_id","b").put("apk_sha256","s").put("target",false);
        JSONObject receipt=NetworkWifiCommand.returnedReceipt(intent,true).put("target_observed",true);
        try {
            Thread.currentThread().interrupt();
            try { NetworkAndroidPlatform.reconcileSetter(intent,receipt,true,() -> { fail("取消后不能新增读取"); return false; }); fail(); }
            catch(InterruptedException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        } finally { Thread.interrupted(); }
        try {
            try { NetworkAndroidPlatform.reconcileSetter(intent,receipt,false,() -> { Thread.currentThread().interrupt(); return false; }); fail(); }
            catch(InterruptedException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        } finally { Thread.interrupted(); }
    }
    @Test public void wifiFailureReportsOnlyFixedStagesAndWhitelistedCodes() {
        String secret="任意私有路径或令牌不得输出";
        assertEquals("NETWORK_WIFI_CALL_FAILED stage=APK_CHECK code=NETWORK_PARENT_REJECTED",
                NetworkWifiCommand.failure(NetworkWifiCommand.Stage.APK_CHECK,new IOException("NETWORK_PARENT_REJECTED")));
        assertEquals("NETWORK_WIFI_CALL_FAILED stage=WIFI_READ code=METHOD_MISSING",
                NetworkWifiCommand.failure(NetworkWifiCommand.Stage.WIFI_READ,new NoSuchMethodException(secret)));
        assertEquals("NETWORK_WIFI_CALL_FAILED stage=WIFI_SET code=PERMISSION_DENIED",
                NetworkWifiCommand.failure(NetworkWifiCommand.Stage.WIFI_SET,new java.lang.reflect.InvocationTargetException(new SecurityException(secret))));
        assertEquals("NETWORK_WIFI_CALL_FAILED stage=SERVICE_LOOKUP code=INITIALIZATION_FAILED",
                NetworkWifiCommand.failure(NetworkWifiCommand.Stage.SERVICE_LOOKUP,new ExceptionInInitializerError(secret)));
        assertEquals("NETWORK_WIFI_CALL_FAILED stage=APK_CHECK code=IO_FAILED",
                NetworkWifiCommand.failure(NetworkWifiCommand.Stage.APK_CHECK,new IOException("NETWORK_PARENT_REJECTED "+secret)));
        assertEquals("NETWORK_WIFI_CALL_FAILED stage=UNKNOWN code=CALL_FAILED",NetworkWifiCommand.failure(null,new RuntimeException(secret)));
    }
    @Test public void returnedAcceptedOldValueCannotSettleBeforeLateTargetThenActualRollback() throws Exception {
        NetworkChangeTransactionTest.Memory store=new NetworkChangeTransactionTest.Memory();
        QueuedWifi wifi=new QueuedWifi();
        NetworkChangeTransaction engine=new NetworkChangeTransaction(store,wifi,new NetworkChangeTransactionTest.Time());
        JSONObject result=engine.begin("task-1",false,1000);
        assertEquals("NEEDS_ATTENTION",result.getString("state")); assertFalse(result.getBoolean("restored"));
        assertTrue(wifi.receipt.getBoolean("setter_returned")); assertTrue(wifi.actual);
        assertNull(wifi.readWifiEnabled()); assertTrue(NetworkAndroidPlatform.pendingTarget(wifi.receipt));
        final int[] releases={0};
        NetworkAndroidPlatform.Maintenance maintenance=new NetworkAndroidPlatform.Maintenance() {
            public AutoCloseable acquire(String t,boolean r) { return () -> {}; }
            public void requireReady(String t) { }
            public void release(String t) { releases[0]++; }
        };
        NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.releaseChecked(store,maintenance,"task-1",false));
        assertEquals("NEEDS_ATTENTION",engine.cancel("task-1").getString("state"));
        assertEquals(1,wifi.writes); assertEquals(0,wifi.observedSaves); assertEquals(0,releases[0]);
        // 模拟已排队的原请求此刻才落实；同号恢复先同步观察证据，再实际写回前像。
        wifi.actual=false;
        result=engine.recover("task-1");
        assertEquals("ROLLED_BACK",result.getString("state")); assertTrue(result.getBoolean("restored"));
        assertEquals(1,wifi.observedSaves); assertEquals(2,wifi.writes); assertTrue(wifi.actual);
        NetworkAndroidPlatform.releaseChecked(store,maintenance,"task-1",false); assertEquals(1,releases[0]);
    }
    @Test public void rejectedRequestAllowsOriginalObservationWithoutRollbackWrite() throws Exception {
        QueuedWifi wifi=new QueuedWifi(); wifi.accepted=false;
        NetworkChangeTransaction engine=new NetworkChangeTransaction(new NetworkChangeTransactionTest.Memory(),wifi,new NetworkChangeTransactionTest.Time());
        JSONObject result=engine.begin("task-1",false,1000);
        assertEquals("ORIGINAL_OBSERVED",result.getString("state")); assertFalse(result.getBoolean("restored"));
        assertEquals(1,wifi.writes); assertEquals(0,wifi.observedSaves); assertFalse(NetworkAndroidPlatform.pendingTarget(wifi.receipt));
    }
    @Test public void acceptedPendingOriginalMayBeObservedOnlyAfterVerifiedReboot() throws Exception {
        QueuedWifi wifi=new QueuedWifi();
        NetworkChangeTransaction engine=new NetworkChangeTransaction(new NetworkChangeTransactionTest.Memory(),wifi,new NetworkChangeTransactionTest.Time());
        engine.begin("task-1",false,1000);
        wifi.boot="";
        assertEquals("NEEDS_ATTENTION",engine.cancel("task-1").getString("state"));
        wifi.boot="bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee";
        JSONObject result=engine.cancel("task-1");
        assertEquals("ORIGINAL_OBSERVED",result.getString("state")); assertFalse(result.getBoolean("restored"));
        assertEquals(1,wifi.writes); assertEquals(0,wifi.observedSaves);
    }
    @Test public void targetObservationMustBeDurableAndUnknownDoesNotSave() throws Exception {
        JSONObject pending=NetworkWifiCommand.returnedReceipt(new JSONObject().put("target",false),true);
        assertNull(NetworkAndroidPlatform.observeCall(pending,null,saved -> fail("未知不能落目标观察")));
        NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.observeCall(pending,false,saved -> { throw new IOException("同步失败"); }));
        assertFalse(pending.getBoolean("target_observed"));
        assertTrue(NetworkAndroidPlatform.pendingTarget(pending));
        assertNull(NetworkAndroidPlatform.observeCall(pending,true,saved -> fail("前像不能落目标观察")));
    }
    @Test public void malformedAcceptanceCannotMasqueradeAsRejection() throws Exception {
        for (String key:new String[]{"request_accepted","target_observed","target"}) {
            JSONObject receipt=NetworkWifiCommand.returnedReceipt(new JSONObject().put("target",false),true);
            receipt.remove(key);
            NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.observeCall(receipt,true,saved -> fail()));
            receipt.put(key,"false");
            NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.observeCall(receipt,true,saved -> fail()));
        }
    }
    @Test public void preparedWithoutGuardCanCancelAndReleaseOnlyAfterKnownOriginal() throws Exception {
        NetworkChangeTransactionTest.Fixture f=new NetworkChangeTransactionTest.Fixture();
        f.store.crashAt=1; NetworkChangeTransactionTest.crashed(() -> f.begin()); f.device.ready=false;
        JSONObject result=f.engine().cancel("task-1");
        assertEquals("UNCHANGED",result.getString("state")); assertFalse(result.getBoolean("restored"));
        assertEquals(0,f.device.writes);
    }
    @Test public void absentReleaseRetainsJournalLockAndPreparedIsNotAbsent() throws Exception {
        NetworkChangeTransactionTest.Fixture f=new NetworkChangeTransactionTest.Fixture(); final int[] calls={0};
        NetworkAndroidPlatform.Maintenance maintenance=new NetworkAndroidPlatform.Maintenance() {
            public AutoCloseable acquire(String t,boolean r) { return () -> {}; }
            public void requireReady(String t) { }
            public void release(String t) { assertTrue(f.store.busy); calls[0]++; }
        };
        NetworkAndroidPlatform.releaseChecked(f.store,maintenance,"task-1",true); assertEquals(1,calls[0]);
        f.store.crashAt=1; NetworkChangeTransactionTest.crashed(() -> f.begin());
        NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.releaseChecked(f.store,maintenance,"task-1",true));
        assertEquals(1,calls[0]);
        f.engine().cancel("task-1"); NetworkAndroidPlatform.releaseChecked(f.store,maintenance,"task-1",false);
        assertEquals(2,calls[0]);
    }
    @Test public void onlyProvenNewBootEndsPendingOldBinderRisk() throws Exception {
        String a="aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", b="bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee";
        assertFalse(NetworkAndroidPlatform.callEndedByReboot(a,a)); assertTrue(NetworkAndroidPlatform.callEndedByReboot(a,b));
        NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.callEndedByReboot(a,null));
        NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.callEndedByReboot("",b));
    }
    @Test public void staleAbsentQueryCannotReleaseSameTaskThatBecameActive() throws Exception {
        NetworkChangeTransactionTest.Fixture f=new NetworkChangeTransactionTest.Fixture(); final int[] releases={0};
        assertEquals("ABSENT",f.engine().query("task-1").getString("state"));
        f.begin();
        NetworkAndroidPlatform.Maintenance maintenance=new NetworkAndroidPlatform.Maintenance() {
            public AutoCloseable acquire(String t,boolean r) { return () -> {}; }
            public void requireReady(String t) { }
            public void release(String t) { releases[0]++; }
        };
        NetworkChangeTransactionTest.rejected(() -> NetworkAndroidPlatform.releaseChecked(f.store,maintenance,"task-1",true));
        assertEquals(0,releases[0]); assertEquals("AWAITING_CONFIRM",f.engine().query("task-1").getString("state"));
    }
    @Test(timeout=6000) public void realJournalPreventsSameTaskBeginBetweenAbsentCheckAndRelease() throws Exception {
        File dir=temporary.newFolder(); NetworkChangeTransactionJournal store=new NetworkChangeTransactionJournal(dir);
        NetworkChangeTransactionTest.Device device=new NetworkChangeTransactionTest.Device();
        NetworkChangeTransaction engine=new NetworkChangeTransaction(store,device,new NetworkChangeTransactionTest.Time());
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1), proceed=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        NetworkAndroidPlatform.Maintenance maintenance=new NetworkAndroidPlatform.Maintenance() {
            public AutoCloseable acquire(String t,boolean r) { return () -> {}; }
            public void requireReady(String t) { }
            public void release(String t) throws Exception { entered.countDown(); if(!proceed.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("测试释放门超时"); }
        };
        try {
            java.util.concurrent.Future<?> release=executor.submit(() -> {
                try { NetworkAndroidPlatform.releaseChecked(store,maintenance,"task-1",true); }
                catch(Exception e) { throw new RuntimeException(e); }
            });
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            try { engine.begin("task-1",false,1000); fail(); } catch(NetworkChangeTransaction.Busy expected) { }
            assertEquals(0,device.writes); proceed.countDown(); release.get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("AWAITING_CONFIRM",engine.begin("task-1",false,1000).getString("state"));
            assertEquals(1,device.writes);
        } finally { proceed.countDown(); executor.shutdownNow(); }
    }
}
