package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static org.junit.Assert.*;

public class RemoteUpdateTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static KeyPair pair;
    private static final long NOW = 1000000;
    @BeforeClass public static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); pair = generator.generateKeyPair();
    }
    private static JSONObject archive(int code) throws Exception {
        return new JSONObject().put("package", RemoteUpdatePolicy.PACKAGE).put("certSha256", RemoteUpdatePolicy.CERT)
                .put("versionCode", code).put("versionName", "v" + code).put("size", 2)
                .put("sha256", RemoteProtocol.hash("v" + code)).put("path", "/test/" + code + ".apk");
    }
    private static JSONObject manifest() throws Exception {
        return archive(68).put("channel", "d31").put("model_id", "mdl_d31").put("job_id", "upd-test")
                .put("url", "https://v.elfradio.net/api/elfremote/apk/upd-test").put("expires_at", NOW + 60000);
    }
    private static JSONObject sign(JSONObject m) throws Exception {
        String raw = m.toString(); Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(pair.getPrivate());
        signer.update(raw.getBytes(StandardCharsets.UTF_8)); StringBuilder hex = new StringBuilder();
        for (byte b : signer.sign()) hex.append(String.format(Locale.US, "%02x", b & 255));
        return new JSONObject().put("manifest_raw", raw).put("signature", hex.toString()).put("task_id", "update-test")
                .put("task_device_id", "test-device").put("task_expires_at", NOW + 60000);
    }
    private File job(JSONObject offer) throws Exception {
        File dir = temporary.newFolder(); RescueFiles.write(new File(dir, "offer.json"), offer.toString()); return dir;
    }
    static final class Fake implements RemoteUpdateEngine.Platform {
        JSONObject installed; int installs, rollbacks, stops, downloads, progressAttempts;
        boolean downloadError, offline, healthy, cloud, installError, unreadable;
        long serverDelay;
        String session = "boot-a";
        public String session() { return session; }
        List<String> progress = new ArrayList<>();
        Fake() throws Exception { installed = archive(67); }
        public JSONObject current() throws Exception { if (unreadable) throw new IOException("应用状态读取失败"); return installed; }
        public JSONObject prepare(JSONObject m, File dir) throws Exception {
            downloads++; if (downloadError) throw new IOException("断网"); return archive(68);
        }
        public JSONObject backup(File dir) throws Exception { return new JSONObject(installed.toString()); }
        public void stopCore() { stops++; }
        public void install(JSONObject apk, boolean rollback) throws Exception {
            if (rollback) rollbacks++; else installs++;
            if (installError && !rollback) throw new IOException("安装失败");
            installed = new JSONObject(apk.toString());
            if (rollback) unreadable=false;
        }
        public void select(JSONObject apk) { }
        public boolean localHealthy(JSONObject apk) { return healthy; }
        public boolean cloudHealthy(JSONObject apk) { return cloud; }
        public void progress(String job, String state, String detail) throws Exception {
            progressAttempts++;
            if (offline) {
                if (serverDelay > 0) throw new RemoteHttp.Rejected(503,"云离线",serverDelay);
                throw new IOException("云离线");
            }
            progress.add(state);
        }
    }
    private RemoteUpdateEngine engine(File dir, Fake fake) throws Exception {
        return new RemoteUpdateEngine(dir, fake, pair.getPublic(), "test-device");
    }
    private void advance(RemoteUpdateEngine e, int count) throws Exception { for (int i=0; i<count; i++) e.step(NOW + i); }

    @Test public void wrongPackageChannelModelCertificateAndUrlAreRejectedBeforeInstall() throws Exception {
        for (String[] change : new String[][]{{"package","net.elfradio.elfremote"},{"channel","d22"},{"model_id","mdl_d22"},
                {"certSha256",RemoteProtocol.hash("other")},{"url","http://v.elfradio.net/api/elfremote/apk/upd-test"},
                {"url","https://v.elfradio.net@evil.test/api/elfremote/apk/upd-test"}}) {
            Fake f = new Fake(); RemoteUpdateEngine e = engine(job(sign(manifest().put(change[0], change[1]))),f);
            e.step(NOW); assertEquals("rejected", e.state().getString("phase")); assertEquals(0,f.installs); assertEquals(0,f.stops);
        }
    }
    @Test public void tamperedSignedTextIsRejected() throws Exception {
        JSONObject offer = sign(manifest()); offer.put("manifest_raw", offer.getString("manifest_raw") + " ");
        Fake f = new Fake(); RemoteUpdateEngine e = engine(job(offer), f); e.step(NOW);
        assertEquals("rejected",e.state().getString("phase")); assertEquals(0,f.downloads);
    }
    @Test public void wrongDeviceAndExpiredTasksAreRejected() throws Exception {
        for (JSONObject offer : new JSONObject[]{sign(manifest()).put("task_device_id","other"),
                sign(manifest()).put("task_expires_at",NOW),sign(manifest().put("expires_at",NOW))}) {
            Fake f = new Fake(); RemoteUpdateEngine e = engine(job(offer),f); e.step(NOW);
            assertEquals("rejected",e.state().getString("phase")); assertEquals(0,f.installs);
        }
    }
    @Test public void normalUpdateRequiresInstalledVersionAndFreshHealthThenReportsOnce() throws Exception {
        Fake f = new Fake(); File dir = job(sign(manifest())); RemoteUpdateEngine e = engine(dir,f); advance(e,5);
        assertEquals("wait_health",e.state().getString("phase")); assertEquals(1,f.installs);
        e.step(NOW+100); assertEquals("wait_health",e.state().getString("phase"));
        f.healthy=true; f.cloud=true; e.step(NOW+200); assertEquals("success",e.state().getString("phase"));
        engine(dir,f).step(NOW+300); assertEquals(1,f.installs);
        assertEquals(Arrays.asList("claimed","downloading","verifying","installing","wait_health","success"),f.progress);
    }
    @Test public void crashAfterInstallIntentNeverBlindlyReinstalls() throws Exception {
        Fake f = new Fake(); File dir=job(sign(manifest())); RemoteUpdateEngine e=engine(dir,f); advance(e,4);
        JSONObject s=e.state(); s.put("install_intent",true); RescueFiles.write(new File(dir,"state.json"),s.toString());
        f.installed=archive(68); e=engine(dir,f); e.step(NOW+70000);
        assertEquals("wait_health",e.state().getString("phase")); assertEquals(0,f.installs);
    }
    @Test public void crashBeforeInstallerReallyRanRestoresOldCoreWithoutReinstall() throws Exception {
        Fake f=new Fake(); File dir=job(sign(manifest())); RemoteUpdateEngine e=engine(dir,f); advance(e,4);
        JSONObject s=e.state(); s.put("install_intent",true); RescueFiles.write(new File(dir,"state.json"),s.toString());
        e=engine(dir,f); e.step(NOW+10); assertEquals("rollback",e.state().getString("phase"));
        f.healthy=true; e.step(NOW+20); assertEquals("recovered",e.state().getString("phase")); assertEquals(0,f.installs);
    }
    @Test public void healthFailureRollsBackVerifiedOldVersionEvenAfterExpiry() throws Exception {
        Fake f=new Fake(); RemoteUpdateEngine e=engine(job(sign(manifest())),f); advance(e,5);
        e.step(NOW+160000); assertEquals("rollback",e.state().getString("phase"));
        f.healthy=true; e.step(NOW+160001); assertEquals("recovered",e.state().getString("phase"));
        assertEquals(67,f.installed.getInt("versionCode")); assertEquals(1,f.rollbacks);
    }
    @Test public void offlineCloudDoesNotRollbackLocallyHealthyVersionAndReceiptsReplayInOrder() throws Exception {
        Fake f=new Fake(); f.offline=true; File dir=job(sign(manifest())); RemoteUpdateEngine e=engine(dir,f); advance(e,5);
        f.healthy=true; e.step(NOW+300000); assertEquals("wait_health",e.state().getString("phase")); assertEquals(0,f.rollbacks);
        f.offline=false; f.cloud=true; e=engine(dir,f); e.step(NOW+300001);
        assertEquals("success",e.state().getString("phase"));
        assertTrue(f.progress.isEmpty());
        e.step(e.state().getLong("progress_retry_at"));
        assertEquals(Arrays.asList("claimed","downloading","verifying","installing","wait_health","success"),f.progress);
    }
    @Test public void progressFailureWaitPersistsAcrossEngineRecreationWithoutBlockingInstall() throws Exception {
        Fake f = new Fake(); f.offline=true; File dir=job(sign(manifest()));
        RemoteUpdateEngine e=engine(dir,f); advance(e,5);
        assertEquals(1,f.progressAttempts);
        assertEquals("wait_health",e.state().getString("phase"));
        assertEquals(1,f.installs);
        engine(dir,f).step(NOW+29999); assertEquals(1,f.progressAttempts);
        engine(dir,f).step(NOW+30000); assertEquals(2,f.progressAttempts);
        assertEquals(NOW+90000,engine(dir,f).state().getLong("progress_retry_at"));
        engine(dir,f).step(NOW+30001); assertEquals(2,f.progressAttempts);
    }

    @Test public void updateReceiptHonorsServerWaitAcrossEngineRecreation() throws Exception {
        Fake f=new Fake(); f.offline=true; f.serverDelay=900000;
        File dir=job(sign(manifest())); RemoteUpdateEngine e=engine(dir,f); advance(e,5);
        f.healthy=true;
        engine(dir,f).step(NOW+899999); assertEquals(1,f.progressAttempts);
        assertEquals("wait_health",e.state().getString("phase"));
        assertEquals(NOW+900000,e.state().getLong("progress_retry_at"));
        f.offline=false; f.cloud=true; engine(dir,f).step(NOW+900000);
        assertEquals("success",e.state().getString("phase"));
        assertEquals(6,e.state().getInt("acked")); assertEquals(0,f.rollbacks);
    }
    @Test public void localRecoveryContinuesDuringProgressCooldown() throws Exception {
        Fake f=new Fake(); f.offline=true; f.installError=true;
        File dir=job(sign(manifest())); RemoteUpdateEngine e=engine(dir,f); advance(e,5);
        assertEquals("rollback",e.state().getString("phase"));
        f.healthy=true; engine(dir,f).step(NOW+10);
        assertEquals("recovered",e.state().getString("phase"));
        assertEquals(1,f.progressAttempts);
        f.offline=false; engine(dir,f).step(NOW+30000);
        assertEquals(Arrays.asList("claimed","downloading","verifying","installing","rollback","recovered"),f.progress);
        int attempts=f.progressAttempts; engine(dir,f).step(NOW+40000);
        assertEquals(attempts,f.progressAttempts);
    }
    @Test public void terminalRetryDelayCapsAndSurvivesClockMovingBackwards() throws Exception {
        Fake f=new Fake(); f.offline=true;
        File dir=job(sign(manifest().put("package","wrong"))); RemoteUpdateEngine e=engine(dir,f);
        e.step(NOW); long now=NOW;
        for(int i=0;i<10;i++) {
            long retry=e.state().getLong("progress_retry_at");
            assertTrue(retry-now<=300000); e=engine(dir,f); e.step(retry); now=retry;
        }
        int attempts=f.progressAttempts; e.step(NOW-1000);
        assertEquals(attempts,f.progressAttempts);
        assertEquals(NOW-1000+300000,e.state().getLong("progress_retry_at"));
    }
    @Test public void downloadRetriesAreBoundedAndNeverStopCurrentCore() throws Exception {
        Fake f=new Fake(); f.downloadError=true; RemoteUpdateEngine e=engine(job(sign(manifest())),f); advance(e,3);
        e.step(NOW+3); assertEquals(1,f.downloads);
        e.step(NOW+30003); e.step(NOW+60004);
        assertEquals("rejected",e.state().getString("phase")); assertEquals(0,f.stops);
    }
    @Test public void sameVersionNeverInstalls() throws Exception {
        Fake f=new Fake(); f.installed=archive(68); RemoteUpdateEngine e=engine(job(sign(manifest())),f); advance(e,3);
        assertEquals(0,f.installs); assertEquals(0,f.stops);
        assertEquals("rejected",e.state().getString("phase"));
    }
    @Test public void shortAndOversizedDownloadCannotBecomeCommittedApk() throws Exception {
        for (byte[] value:new byte[][]{new byte[]{1},new byte[]{1,2,3}}) {
            File out=new File(temporary.newFolder(),"part");
            try {RemoteUpdateFiles.copyBounded(new ByteArrayInputStream(value),out,2); fail();} catch(Exception expected) { }
        }
    }
    @Test public void capacityCheckDoesNotOverflow() {
        assertFalse(RemoteUpdatePolicy.hasSpace(Long.MAX_VALUE,Long.MAX_VALUE,2));
        assertFalse(RemoteUpdatePolicy.hasSpace(1,2,2));
        assertTrue(RemoteUpdatePolicy.hasSpace(8*1024*1024,1000,1000));
    }
    @Test public void rebootDuringHealthWaitGetsFreshLocalDeadline() throws Exception {
        Fake f=new Fake(); RemoteUpdateEngine e=engine(job(sign(manifest())),f); advance(e,5);
        f.session="boot-b"; e.step(NOW+999999);
        assertEquals("wait_health",e.state().getString("phase")); assertEquals(0,f.rollbacks);
        f.healthy=true; f.cloud=true; e.step(NOW+1000000); assertEquals("success",e.state().getString("phase"));
    }
    @Test public void installerFailureDoesNotOverwriteBackupAndRecoversOldVersion() throws Exception {
        Fake f=new Fake(); f.installError=true; RemoteUpdateEngine e=engine(job(sign(manifest())),f); advance(e,5);
        assertEquals("rollback",e.state().getString("phase")); assertEquals(67,e.state().getJSONObject("backup").getInt("versionCode"));
        f.healthy=true; e.step(NOW+10); assertEquals("recovered",e.state().getString("phase")); assertEquals(1,f.installs);
    }
    @Test public void unreadableInstalledApkStillAllowsRealBackupRecovery() throws Exception {
        Fake f=new Fake(); RemoteUpdateEngine e=engine(job(sign(manifest())),f); advance(e,5);
        f.unreadable=true; f.healthy=true; f.cloud=true; e.step(NOW+10);
        assertEquals("rollback",e.state().getString("phase")); e.step(NOW+11);
        assertEquals("recovered",e.state().getString("phase")); assertEquals(1,f.rollbacks);
    }
    @Test public void concurrentPackageChangeCannotPassHealth() throws Exception {
        Fake f=new Fake(); RemoteUpdateEngine e=engine(job(sign(manifest())),f); advance(e,5);
        f.installed=archive(67); f.healthy=true; f.cloud=true; e.step(NOW+10);
        assertEquals("rollback",e.state().getString("phase"));
    }
}
