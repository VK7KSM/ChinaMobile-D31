package net.elfradio.d31bootstrap.media;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import java.io.*;
import static org.junit.Assert.*;

public class MediaCaptureTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    static final class Clock implements MediaCapture.Clock {
        long wall=100000,elapsed=1000;
        public long wall(){return wall;}public long elapsed(){return elapsed;}
        void advance(long ms){wall+=ms;elapsed+=ms;}
    }
    final Clock clock=new Clock();
    int calls,releases;boolean busy;
    final AudioGuard guard=new AudioGuard(){public void requireIdle()throws Exception{if(busy)throw new IOException("MEDIA_PHONE_BUSY");}};
    MediaCapture.Device device(final String behavior){return new MediaCapture.Device(){public MediaCapture.Captured capture(CaptureRequest request,File file,Cancellation cancel,long deadline,AudioGuard guard)throws Exception{
        calls++;try{
            try(FileOutputStream out=new FileOutputStream(file)){if(!"empty".equals(behavior))out.write(new byte[]{1,2,3,4});}
            if("failed".equals(behavior))throw new IOException("private platform detail must not escape");
            if("cancel".equals(behavior))cancel.cancel();
            if("timeout".equals(behavior))clock.advance(70000);
            if("busy".equals(behavior))busy=true;
            return new MediaCapture.Captured(100001,100101,"fixture","photo".equals(request.kind)?"image/jpeg":"audio/mp4");
        }finally{releases++;}
    }};}
    CaptureRequest photo(String id)throws Exception{return CaptureRequest.photo(id,"report-a","front",200000);}
    interface Operation{void run()throws Exception;}
    static void rejects(String expected,Operation op)throws Exception{try{op.run();fail("expected "+expected);}catch(IOException error){assertTrue(error.getMessage(),error.getMessage().contains(expected));}}
    @Test public void actualFileReceiptAndDuplicate()throws Exception{
        File root=temp.newFolder();MediaCapture capture=new MediaCapture(root,device("ok"),guard,clock);
        JSONObject first=capture.photo(photo("one"),new Cancellation());assertEquals("completed",first.getString("state"));
        assertEquals("report-a",first.getString("report_id"));assertEquals(100001,first.getLong("started_at_ms"));assertEquals(100101,first.getLong("ended_at_ms"));
        assertEquals(4,first.getInt("bytes"));assertEquals(MediaFiles.hash(new File(first.getString("path"))),first.getString("sha256"));assertFalse(first.getBoolean("uploaded"));
        assertEquals(first.toString(),capture.photo(photo("one"),new Cancellation()).toString());assertEquals(1,calls);assertEquals(1,releases);
    }
    @Test public void changedContentNeverReplayed()throws Exception{
        File root=temp.newFolder();final MediaCapture capture=new MediaCapture(root,device("ok"),guard,clock);
        JSONObject result=capture.photo(photo("one"),new Cancellation());new File(result.getString("path")).delete();
        rejects("MISSING_OR_CHANGED",new Operation(){public void run()throws Exception{capture.photo(photo("one"),new Cancellation());}});assertEquals(1,calls);
    }
    @Test public void sameIdDifferentReportRejected()throws Exception{
        final MediaCapture capture=new MediaCapture(temp.newFolder(),device("ok"),guard,clock);capture.photo(photo("one"),new Cancellation());
        rejects("ID_CONFLICT",new Operation(){public void run()throws Exception{capture.photo(CaptureRequest.photo("one","other","front",200000),new Cancellation());}});
    }
    @Test public void interruptedIntentNeverReplays()throws Exception{
        File root=temp.newFolder();File folder=new File(root,"captures/one");folder.mkdirs();MediaFiles.writeNew(new File(folder,"intent.json"),photo("one").identity());
        final MediaCapture capture=new MediaCapture(root,device("ok"),guard,clock);
        rejects("INTERRUPTED",new Operation(){public void run()throws Exception{capture.photo(photo("one"),new Cancellation());}});assertEquals(0,calls);
    }
    @Test public void cancelledBeforeOpening()throws Exception{
        final Cancellation cancellation=new Cancellation();cancellation.cancel();final MediaCapture capture=new MediaCapture(temp.newFolder(),device("ok"),guard,clock);
        rejects("CANCELLED",new Operation(){public void run()throws Exception{capture.photo(photo("one"),cancellation);}});assertEquals(0,calls);
    }
    @Test public void cancellationAndFailureReleaseAndKeepReceipt()throws Exception{
        for(String behavior:new String[]{"cancel","failed","timeout","busy","empty"}){
            busy=false;MediaCapture capture=new MediaCapture(temp.newFolder(),device(behavior),guard,clock);clock.wall=100000;clock.elapsed=1000;
            JSONObject result=capture.photo(photo("one"),new Cancellation());assertNotEquals("completed",result.getString("state"));assertFalse(result.has("path"));
            assertEquals(calls,releases);assertFalse(result.toString().contains("private platform"));
        }
    }
    @Test public void phoneBusyDoesNotOpenHardware()throws Exception{
        busy=true;final MediaCapture capture=new MediaCapture(temp.newFolder(),device("ok"),guard,clock);
        rejects("PHONE_BUSY",new Operation(){public void run()throws Exception{capture.photo(photo("one"),new Cancellation());}});assertEquals(0,calls);
    }
    @Test public void expiryRejected()throws Exception{
        final MediaCapture capture=new MediaCapture(temp.newFolder(),device("ok"),guard,clock);
        rejects("EXPIRED",new Operation(){public void run()throws Exception{capture.photo(CaptureRequest.photo("a","b","front",clock.wall),new Cancellation());}});assertEquals(0,calls);
    }
    @Test public void audioBoundedAndAssociated()throws Exception{
        MediaCapture capture=new MediaCapture(temp.newFolder(),device("ok"),guard,clock);
        JSONObject result=capture.audio(CaptureRequest.audio("root-task","report-a",1000,200000),new Cancellation());
        assertEquals("audio/mp4",result.getString("mime"));assertTrue(result.getString("path").endsWith(".m4a"));
        for(final int duration:new int[]{0,999,60001,Integer.MAX_VALUE})rejects("DURATION",new Operation(){public void run()throws Exception{CaptureRequest.audio("id","report",duration,200000);}});
    }
    @Test public void realPhotoOfferAndUnsupportedStream()throws Exception{
        CaptureRequest r=CaptureRequest.photoOffer(new JSONObject().put("session_id","session-1").put("mode","photo").put("camera","back").put("expires_at",200000));
        assertEquals("session-1",r.reportId);assertEquals("back",r.camera);
        rejects("STREAM_NOT_IMPLEMENTED",new Operation(){public void run()throws Exception{CaptureRequest.photoOffer(new JSONObject().put("mode","microphone"));}});
        rejects("INVALID_ID",new Operation(){public void run()throws Exception{CaptureRequest.photo("../escape","report","front",200000);}});
    }
    @Test public void mediaLeasePreventsConcurrentOpening()throws Exception{
        File root=temp.newFolder();final MediaCapture capture=new MediaCapture(root,device("ok"),guard,clock);
        try(MediaFiles.Lease held=MediaFiles.lease(root)){rejects("BUSY",new Operation(){public void run()throws Exception{capture.photo(photo("one"),new Cancellation());}});}
        assertEquals(0,calls);
    }
}
