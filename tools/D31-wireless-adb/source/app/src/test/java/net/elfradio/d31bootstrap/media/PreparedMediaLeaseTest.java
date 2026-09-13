package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PreparedMediaLeaseTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    final MediaCaptureTest.Clock clock=new MediaCaptureTest.Clock();
    JSONObject task(String id)throws Exception{return new JSONObject().put("id",id).put("type","play_alarm").put("params",new JSONObject()).put("expires_at",200000);}
    @Test public void preparedToneDoesNotTakeOrReleaseParentMediaLock()throws Exception {
        File root=temp.newFolder();AtomicInteger starts=new AtomicInteger(),stops=new AtomicInteger();
        AlarmTasks.Player player=duration->{starts.incrementAndGet();return ()->stops.incrementAndGet();};
        try(MediaFiles.Lease parent=MediaFiles.lease(root)) {
            for(int i=1;i<=2;i++) {
                try(PhotoAlarmBackend.PreparedAlarm alarm=new PhotoAlarmBackend.PreparedAlarm(player,(r,d)->()->{},()->{},clock)) {
                    alarm.start();assertEquals("playing",alarm.state());
                    MediaCaptureTest.rejects("BUSY",()->MediaFiles.lease(root));
                }
                MediaCaptureTest.rejects("BUSY",()->MediaFiles.lease(root));
            }
            assertFalse(new File(root,"alarm-owner/media.lock").exists());assertEquals(2,starts.get());assertEquals(2,stops.get());
        }
        try(MediaFiles.Lease next=MediaFiles.lease(root)){assertNotNull(next);}
    }
    @Test public void photoChildLockCoexistsWithParentAndKeepsRealReceiptAndNoReplay()throws Exception {
        File files=temp.newFolder(),root=new File(files,"visual-photo"),media=new File(files,"media");AtomicInteger calls=new AtomicInteger();
        MediaCapture.Device device=(request,file,cancel,deadline,guard)->{
            calls.incrementAndGet();try(FileOutputStream out=new FileOutputStream(file)){out.write(new byte[]{(byte)255,(byte)216,(byte)255,(byte)217});}
            return new MediaCapture.Captured(100001,100002,"camera:0:front","image/jpeg");
        };
        try(MediaFiles.Lease parent=MediaFiles.lease(media)) {
            MediaCapture capture=new MediaCapture(root,device,()->{},clock);
            CaptureRequest request=CaptureRequest.photo("session-1","session-1","front",200000);
            JSONObject result=capture.photo(request,new Cancellation());assertEquals("completed",result.getString("state"));
            assertEquals("session-1",result.getString("report_id"));assertEquals(result.toString(),capture.photo(request,new Cancellation()).toString());
            assertEquals(1,calls.get());MediaCaptureTest.rejects("BUSY",()->MediaFiles.lease(media));
            try(MediaFiles.Lease child=MediaFiles.lease(root)){assertNotNull(child);}
        }
    }
    @Test public void photoFailureReleasesOnlyItsChildLock()throws Exception {
        File files=temp.newFolder(),photo=new File(files,"visual-photo");
        try(MediaFiles.Lease parent=MediaFiles.lease(new File(files,"media"))) {
            MediaCapture capture=new MediaCapture(photo,(r,f,c,d,g)->{throw new java.io.IOException("TEST_CAPTURE_FAILURE");},()->{},clock);
            assertEquals("failed",capture.photo(CaptureRequest.photo("session-1","session-1","front",200000),new Cancellation()).getString("state"));
            assertTrue(new File(photo,"media.lock").exists());
            try(MediaFiles.Lease child=MediaFiles.lease(photo)){assertNotNull(child);}
            MediaCaptureTest.rejects("BUSY",()->MediaFiles.lease(new File(files,"media")));
        }
    }
    @Test public void previewSmallJpegIsOriginalAndBoundsRejectInvalidInput() {
        byte[] original={(byte)255,(byte)216,(byte)255,(byte)217};
        assertSame(original,PreparedPhotoPreview.shrink(original));assertNull(PreparedPhotoPreview.shrink(null));
        assertNull(PreparedPhotoPreview.shrink(new byte[4]));assertNull(PreparedPhotoPreview.shrink(new byte[262145]));
    }
}
