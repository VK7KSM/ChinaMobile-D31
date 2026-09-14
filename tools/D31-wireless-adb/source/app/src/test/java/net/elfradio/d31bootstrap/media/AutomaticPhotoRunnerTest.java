package net.elfradio.d31bootstrap.media;

import java.io.*;
import java.net.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AutomaticPhotoRunnerTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final long NOW=10000000;
    private final byte[] jpeg={(byte)255,(byte)216,12,34,(byte)255,(byte)217};
    private final AtomicInteger captures=new AtomicInteger(),opens=new AtomicInteger();
    private final AtomicBoolean wifi=new AtomicBoolean(true),released=new AtomicBoolean(true);
    private int http=200;
    private File files;
    private final List<byte[]> uploads=new ArrayList<>();
    private final List<AutomaticPhotoRunner> runners=new ArrayList<>();
    private final MediaCapture.Clock clock=new MediaCapture.Clock(){public long wall(){return NOW;}public long elapsed(){return 1000;}};
    @Before public void setup()throws Exception{files=temp.newFolder();}
    @After public void stop(){for(AutomaticPhotoRunner r:runners)r.close();}
    private JSONObject job(String id)throws Exception{return new JSONObject().put("device_id","fixture-device").put("report_id",id)
            .put("critical",false).put("sampled_at",NOW).put("expires_at",NOW+900000).put("attempt",0);}
    private JSONObject identity()throws Exception{return new JSONObject().put("device_id","fixture-device").put("token","offline-fixture");}
    private MediaCapture.Captured capture(CaptureRequest request,File output,Cancellation cancel,long deadline,AudioGuard guard)throws Exception {
        captures.incrementAndGet();assertEquals("front",request.camera);
        try(FileOutputStream stream=new FileOutputStream(output)){stream.write(jpeg);}
        return new MediaCapture.Captured(NOW,NOW,"camera:0:back","image/jpeg");
    }
    private AutomaticPhotoRunner runner(MediaCapture.Device camera){
        return runner(camera,()->{});
    }
    private AutomaticPhotoRunner runner(MediaCapture.Device camera,AutomaticPhotoRunner.CameraAdmission admission){
        AutomaticPhotoRunner r=new AutomaticPhotoRunner(files,camera,clock,critical->{
            if(!wifi.get())throw new IOException("AUTO_PHOTO_NETWORK_PAUSED");
            return new PhotoAlarmUpload.ConnectionPolicy(){
                public void check()throws Exception{if(!wifi.get())throw new IOException("AUTO_PHOTO_NETWORK_PAUSED");}
                public HttpsURLConnection open(URL url)throws Exception{
                    check();opens.incrementAndGet();
                    // 网络阶段不应继续占着prepare使用的全局锁。
                    try(MediaFiles.Lease lease=MediaFiles.lease(new File(files,"media"))){}
                    return new FakeConnection(url);
                }
            };
        },released::get,admission);runners.add(r);return r;
    }
    private JSONObject waitResult(AutomaticPhotoRunner r)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until){JSONObject s=r.snapshot();if(!"running".equals(s.optString("state")))return s;Thread.sleep(5);}
        fail("自动操作未在测试预算内结束");return null;
    }
    private void idle(AutomaticPhotoRunner r)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(r.busy()&&System.nanoTime()<until)Thread.sleep(5);assertFalse(r.busy());}
    @Test public void preparedLeasePreventsCaptureThenReleaseAllowsOnePhoto()throws Exception {
        AutomaticPhotoRunner r=runner(this::capture);JSONObject j=job("lock-test");
        try(MediaFiles.Lease lease=MediaFiles.lease(new File(files,"media"))){
            r.start(j,identity());assertEquals("waiting",waitResult(r).getString("state"));assertEquals(0,captures.get());
        }
        idle(r);r.start(j,identity());JSONObject result=waitResult(r);assertEquals("completed",result.getString("state"));
        assertEquals("camera:0:back",result.getString("source"));assertEquals(1,captures.get());
    }
    @Test public void wifiLossAfterJpegRetainsOriginalAndRestoresWithoutRecapture()throws Exception {
        AutomaticPhotoRunner r=runner((a,b,c,d,e)->{MediaCapture.Captured result=capture(a,b,c,d,e);wifi.set(false);return result;});
        JSONObject j=job("network-test");r.start(j,identity());JSONObject first=waitResult(r);
        assertEquals("waiting",first.getString("state"));assertEquals(NOW,first.getLong("captured_at"));assertEquals(0,opens.get());
        idle(r);wifi.set(true);r.start(j,identity());assertEquals("completed",waitResult(r).getString("state"));
        assertEquals(1,captures.get());assertArrayEquals(jpeg,uploads.get(0));
    }
    @Test public void networkChangeWhileWaitingForCameraPausesBeforeOpeningCamera()throws Exception {
        AutomaticPhotoRunner r=runner(this::capture,()->wifi.set(false));
        r.start(job("camera-admission-network-change"),identity());
        JSONObject result=waitResult(r);
        assertEquals("waiting",result.getString("state"));
        assertEquals("AUTO_PHOTO_NETWORK_PAUSED",result.getString("error"));
        assertEquals(0,captures.get());assertEquals(0,opens.get());
        idle(r);
        try(MediaFiles.Lease lease=MediaFiles.lease(new File(files,"media"))){}
    }
    @Test public void serverRetryUsesSameJpegAndAckReplayDoesNotUseNetwork()throws Exception {
        AutomaticPhotoRunner r=runner(this::capture);JSONObject j=job("retry-test");http=503;r.start(j,identity());
        assertEquals("failed",waitResult(r).getString("state"));idle(r);http=200;j.put("attempt",1);r.start(j,identity());
        assertEquals("completed",waitResult(r).getString("state"));idle(r);
        assertEquals(1,captures.get());assertArrayEquals(uploads.get(0),uploads.get(1));
        r.close();wifi.set(false);AutomaticPhotoRunner restarted=runner(this::capture);restarted.start(j,identity());
        assertEquals("completed",waitResult(restarted).getString("state"));assertEquals(2,opens.get());assertEquals(1,captures.get());
    }
    @Test public void pendingHardwareAndReceiptWriteFailureRetainGlobalLeaseUntilRealRelease()throws Exception {
        AutomaticPhotoRunner r=runner((a,b,c,d,e)->{
            released.set(false);assertTrue(new File(b.getParentFile(),"result.json").mkdir());throw new IOException("MEDIA_CAMERA_RELEASE_PENDING");
        });JSONObject j=job("pending-test");r.start(j,identity());assertEquals("failed",waitResult(r).getString("state"));
        assertTrue(r.busy());assertFalse(r.finish(j).getBoolean("cleanup_complete"));
        try{MediaFiles.lease(new File(files,"media"));fail();}catch(IOException expected){assertEquals("MEDIA_BUSY",expected.getMessage());}
        released.set(true);idle(r);assertTrue(r.finish(j).getBoolean("cleanup_complete"));
        try(MediaFiles.Lease lease=MediaFiles.lease(new File(files,"media"))){}
    }
    @Test public void cancellationReturnsImmediatelyAndCannotUploadLatePhoto()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AutomaticPhotoRunner r=runner((a,b,c,d,e)->{entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return capture(a,b,c,d,e);});
        JSONObject j=job("cancel-test");r.start(j,identity());assertTrue(entered.await(2,TimeUnit.SECONDS));
        long before=System.nanoTime();r.cancel();assertTrue(System.nanoTime()-before<TimeUnit.MILLISECONDS.toNanos(100));release.countDown();
        assertEquals("failed",waitResult(r).getString("state"));idle(r);assertEquals(0,opens.get());assertTrue(r.finish(j).getBoolean("cleanup_complete"));
    }
    @Test public void completedCleanupDoesNotAccumulate128CaptureFoldersOrTouchManual()throws Exception {
        File manual=new File(files,"visual-photo/captures/manual");assertTrue(manual.mkdirs());
        AutomaticPhotoRunner r=runner(this::capture);
        for(int i=0;i<130;i++){
            JSONObject j=job("cleanup-"+i);r.start(j,identity());assertEquals("completed",waitResult(r).getString("state"));idle(r);
            assertTrue(r.finish(j).getBoolean("cleanup_complete"));
        }
        assertEquals(130,captures.get());assertTrue(manual.isDirectory());
        assertEquals(0,new File(files,"automatic-photo/fixture-device").list().length);
    }
    @Test public void identityMismatchAndForgedOfferRejectedBeforeCapture()throws Exception {
        AutomaticPhotoRunner r=runner(this::capture);JSONObject j=job("identity-test");
        try{r.start(j,identity().put("device_id","another"));fail();}catch(IOException expected){assertEquals("AUTO_PHOTO_IDENTITY_CHANGED",expected.getMessage());}
        JSONObject command=new JSONObject().put("operation","auto_photo").put("apk_sha256",String.join("",Collections.nCopies(64,"a"))).put("job",j);
        PhotoAlarmContract.command(command.toString());command.put("offer",new JSONObject());
        try{PhotoAlarmContract.command(command.toString());fail();}catch(IOException expected){assertEquals("AUTO_PHOTO_OFFER_FORBIDDEN",expected.getMessage());}
        assertEquals(0,captures.get());
    }
    @Test public void expiredUncapturedPhotoIsDiscardedWithoutNetwork()throws Exception {
        AutomaticPhotoRunner r=runner(this::capture);r.start(job("stale-test").put("expires_at",NOW-1),identity());
        assertEquals("discarded",waitResult(r).getString("state"));assertEquals(0,captures.get());assertEquals(0,opens.get());
    }
    @Test public void unavailableOrUnknownCameraDoesNotCreateCaptureIntentAndOwnOpenDoesNotCancel()throws Exception {
        AutomaticPhotoCameraGate gate=new AutomaticPhotoCameraGate();
        AutomaticPhotoRunner r=runner((a,b,c,d,e)->{gate.update("0",false);return capture(a,b,c,d,e);},()->gate.requireAvailable("0",10));
        JSONObject j=job("availability-test");r.start(j,identity());assertEquals("AUTO_PHOTO_CAMERA_UNKNOWN",waitResult(r).getString("error"));idle(r);
        gate.update("0",false);r.start(j,identity());assertEquals("AUTO_PHOTO_CAMERA_BUSY",waitResult(r).getString("error"));idle(r);
        assertEquals(0,captures.get());assertFalse(new File(files,"automatic-photo/fixture-device/availability-test/captures/availability-test/intent.json").exists());
        gate.update("0",true);r.start(j,identity());assertEquals("completed",waitResult(r).getString("state"));assertEquals(1,captures.get());
        gate.close();try{gate.requireAvailable("0",0);fail();}catch(IOException expected){assertEquals("AUTO_PHOTO_CAMERA_UNKNOWN",expected.getMessage());}
    }
    @Test public void firstCallbackCanSatisfyBoundedWait()throws Exception {
        AutomaticPhotoCameraGate gate=new AutomaticPhotoCameraGate();CountDownLatch waiting=new CountDownLatch(1);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{Future<?> future=worker.submit(()->{waiting.countDown();try{gate.requireAvailable("0",1000);}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(waiting.await(1,TimeUnit.SECONDS));gate.update("0",true);future.get(2,TimeUnit.SECONDS);
        }finally{gate.close();worker.shutdownNow();}
    }
    @Test public void expiredGenerationTimerCannotCancelNextOperation()throws Exception {
        AutomaticPhotoRunner r=runner(this::capture);JSONObject a=job("generation-a");r.start(a,identity());waitResult(r);idle(r);
        java.lang.reflect.Field field=AutomaticPhotoRunner.class.getDeclaredField("cancel");field.setAccessible(true);Cancellation old=(Cancellation)field.get(r);
        r.start(job("generation-b"),identity());r.cancelGeneration(old);
        assertEquals("completed",waitResult(r).getString("state"));assertEquals(2,captures.get());
    }
    @Test public void immediateCloseAlwaysRetiresQueuedWork()throws Exception {
        for(int i=0;i<20;i++){
            AutomaticPhotoRunner r=runner(this::capture);r.start(job("close-"+i),identity());r.close();idle(r);
            assertNotEquals("running",r.snapshot().optString("state"));
        }
    }
    private final class FakeConnection extends HttpsURLConnection {
        final ByteArrayOutputStream body=new ByteArrayOutputStream();
        FakeConnection(URL url){super(url);}
        public void disconnect(){} public boolean usingProxy(){return false;} public void connect(){}
        public String getCipherSuite(){return "TLS_FIXTURE";} public Certificate[] getLocalCertificates(){return new Certificate[0];}
        public Certificate[] getServerCertificates(){return new Certificate[0];}
        public OutputStream getOutputStream(){return body;}
        public int getResponseCode(){uploads.add(body.toByteArray());return http;}
        public InputStream getInputStream()throws IOException{
            try{
                java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");StringBuilder hash=new StringBuilder();
                for(byte b:digest.digest(body.toByteArray()))hash.append(String.format(Locale.US,"%02x",b&255));
                return new ByteArrayInputStream(new JSONObject().put("ok",true).put("bytes",body.size()).put("sha256",hash.toString()).toString().getBytes("UTF-8"));
            }catch(Exception failure){throw new IOException(failure);}
        }
    }
}
