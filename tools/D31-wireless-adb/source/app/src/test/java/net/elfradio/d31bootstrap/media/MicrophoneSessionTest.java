package net.elfradio.d31bootstrap.media;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class MicrophoneSessionTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    volatile boolean busy;
    final AudioGuard guard=new AudioGuard(){public void requireIdle()throws Exception{if(busy)throw new IOException("BUSY");}};
    final MediaCapture.Clock clock=new MediaCapture.Clock(){public long wall(){return 100000;}public long elapsed(){return System.nanoTime()/1000000;}};
    class Wire implements MicrophoneSession.Transport {
        final List<JSONObject> sent=new CopyOnWriteArrayList<JSONObject>();
        final CountDownLatch attached=new CountDownLatch(1),newRequest=new CountDownLatch(1);
        volatile MicrophoneSession.Events events;boolean dropNew,rejectPublish;int closes,aborts;
        public void connect(RtcOffer offer,MicrophoneSession.Events events){this.events=events;attached.countDown();}
        public void send(JSONObject value)throws Exception {
            sent.add(value);
            if(!"rpc".equals(value.optString("type")))return;
            String action=value.getString("action");
            if("new".equals(action)){newRequest.countDown();if(dropNew)return;}
            JSONObject response=new JSONObject().put("type","rpc").put("id",value.getInt("id"));
            if("publish".equals(action)&&rejectPublish)response.put("error","private remote detail");
            else response.put("result","publish".equals(action)?new JSONObject().put("sessionDescription",new JSONObject().put("type","answer").put("sdp","fixture")):new JSONObject().put("ok",true));
            events.message(response.toString());
        }
        void hello()throws Exception{hello("microphone");}
        void hello(String mode)throws Exception{assertTrue(attached.await(2,TimeUnit.SECONDS));events.message(new JSONObject().put("type","hello").put("mode",mode).toString());}
        public void close(){closes++;}
        public void abort(){aborts++;}
        boolean ready(){for(JSONObject value:sent)if("ready".equals(value.optString("type")))return true;return false;}
    }
    class Device implements MicrophoneSession.Peer {
        volatile MicrophoneSession.PeerEvents events;volatile int opens,closes;boolean signalConnected=true;volatile boolean captureReady=true;volatile String failure="";
        public void requireHealthy()throws Exception{if(!failure.isEmpty())throw new IOException(failure);}
        public boolean captureReady(){return captureReady;}
        public void open(MicrophoneSession.PeerEvents events,Cancellation cancel){this.events=events;opens++;events.recording(true);if(signalConnected)events.connected();}
        public JSONObject publishOffer(Cancellation cancel)throws Exception{return new JSONObject().put("sessionDescription",new JSONObject().put("type","offer").put("sdp","fixture"))
                .put("tracks",new JSONArray().put(new JSONObject().put("mid","0").put("trackName","audio")));}
        public void answer(JSONObject value,Cancellation cancel)throws Exception{assertEquals("answer",value.getString("type"));}
        public void close(){closes++;if(events!=null)events.recording(false);}
    }
    MicrophoneSession session(File root,Wire wire,Device device,AudioGuard gate)throws Exception{
        return new MicrophoneSession(root,RtcOfferTest.parse(RtcOfferTest.offer()),wire,device,gate,clock,null);
    }
    void waitPublished(MicrophoneSession session)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!session.snapshot().getBoolean("published")&&System.nanoTime()<end)Thread.sleep(5);
        assertTrue(session.snapshot().toString(),session.snapshot().getBoolean("published"));
    }
    @Test public void realContractSequenceAndNoCaptureBeforeHello()throws Exception{
        File root=temp.newFolder();Wire wire=new Wire();Device device=new Device();MicrophoneSession s=session(root,wire,device,guard);
        try{s.start();assertTrue(wire.attached.await(2,TimeUnit.SECONDS));assertEquals(0,device.opens);wire.hello();waitPublished(s);
            List<String> actions=new ArrayList<String>();for(JSONObject message:wire.sent)if(message.has("action"))actions.add(message.getString("action"));
            assertEquals(Arrays.asList("new","publish","published"),actions);assertTrue(wire.ready());
            assertEquals("streaming",s.snapshot().getString("state"));assertFalse(s.snapshot().getBoolean("local_recording"));
            assertFalse(s.snapshot().has("token"));assertFalse(s.snapshot().has("url"));assertFalse(s.snapshot().has("report_id"));
        }finally{s.close();assertTrue(s.awaitClosed(2000));}
        assertEquals(1,device.closes);assertEquals(1,wire.closes);
        assertEquals(100000,s.snapshot().getLong("audio_record_started_at_ms"));
        assertEquals(100000,s.snapshot().getLong("audio_record_ended_at_ms"));
        try(MediaFiles.Lease lease=MediaFiles.lease(root)){assertNotNull(lease);}
    }
    @Test public void noReadyUntilIceConnected()throws Exception{
        Wire wire=new Wire();Device device=new Device();device.signalConnected=false;MicrophoneSession s=session(temp.newFolder(),wire,device,guard);
        try{s.start();wire.hello();waitPublished(s);assertFalse(wire.ready());device.events.connected();assertTrue(wire.ready());}
        finally{s.close();assertTrue(s.awaitClosed(2000));}
    }
    @Test public void videoUsesSameLifecycleButRequiresMatchingGreetingAndCapture()throws Exception{
        Wire wire=new Wire();Device device=new Device();device.captureReady=false;
        RtcOffer offer=RtcOfferTest.parse(RtcOfferTest.offer().put("mode","video"));
        MicrophoneSession s=new MicrophoneSession(temp.newFolder(),offer,wire,device,guard,clock,null);
        try{s.start();wire.hello("video");waitPublished(s);assertFalse(wire.ready());
            assertEquals("video",s.snapshot().getString("mode"));device.captureReady=true;
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!wire.ready()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(wire.ready());
        }finally{s.close();assertTrue(s.awaitClosed(2000));}
        Wire bad=new Wire();Device unopened=new Device();
        MicrophoneSession mismatch=new MicrophoneSession(temp.newFolder(),offer,bad,unopened,guard,clock,null);
        mismatch.start();bad.hello();assertTrue(mismatch.awaitClosed(2000));assertEquals(0,unopened.opens);
    }
    @Test public void closeCancelsPendingRpcWithoutTwentySecondWait()throws Exception{
        Wire wire=new Wire();wire.dropNew=true;Device device=new Device();MicrophoneSession s=session(temp.newFolder(),wire,device,guard);
        s.start();wire.hello();assertTrue(wire.newRequest.await(2,TimeUnit.SECONDS));s.close();assertTrue(s.awaitClosed(2000));
        assertEquals(1,wire.aborts);
        device.events.connected();assertFalse(wire.ready());assertEquals("closed",s.snapshot().getString("state"));
    }
    @Test public void unknownAndBusyNeverOpenTransportOrPeer()throws Exception{
        for(AudioGuard gate:new AudioGuard[]{null,guard}){
            busy=true;Wire wire=new Wire();Device device=new Device();final MicrophoneSession s=session(temp.newFolder(),wire,device,gate);
            try{MediaCaptureTest.rejects(gate==null?"UNKNOWN":"BUSY",new MediaCaptureTest.Operation(){public void run()throws Exception{s.start();}});
                assertNull(wire.events);assertEquals(0,device.opens);
            }finally{s.close();assertTrue(s.awaitClosed(2000));}
        }
    }
    @Test public void busyDuringStreamingClosesAndReleases()throws Exception{
        File root=temp.newFolder();Wire wire=new Wire();Device device=new Device();MicrophoneSession s=session(root,wire,device,guard);
        s.start();wire.hello();waitPublished(s);busy=true;assertTrue(s.awaitClosed(2000));assertEquals(1,device.closes);
        try(MediaFiles.Lease lease=MediaFiles.lease(root)){assertNotNull(lease);}
    }
    @Test public void asynchronousVideoFailureStopsWithoutCallingSessionUnderCameraLock()throws Exception{
        Wire wire=new Wire();Device device=new Device();MicrophoneSession s=session(temp.newFolder(),wire,device,guard);
        s.start();wire.hello();waitPublished(s);device.failure="MEDIA_VIDEO_FRAMES_STOPPED";
        assertTrue(s.awaitClosed(2000));assertEquals("MEDIA_VIDEO_FRAMES_STOPPED",s.snapshot().getString("reason"));
        assertEquals(1,device.closes);
    }
    @Test public void startupAndCleanupKeepSeparateSpecificCausesAndUnreleasedLock()throws Exception{
        File root=temp.newFolder();Wire wire=new Wire();Device device=new Device(){
            public void open(MicrophoneSession.PeerEvents events,Cancellation cancel){throw new IllegalStateException("MEDIA_VIDEO_FIRST_FRAME_TIMEOUT");}
            public void close(){throw new IllegalStateException("MEDIA_VIDEO_RELEASE_UNCONFIRMED");}
        };
        MicrophoneSession s=session(root,wire,device,guard);s.start();wire.hello();assertTrue(s.awaitClosed(2000));
        try{
            JSONObject result=s.snapshot();assertEquals("MEDIA_VIDEO_FIRST_FRAME_TIMEOUT",result.getString("reason"));
            assertEquals("MEDIA_VIDEO_RELEASE_UNCONFIRMED",result.getString("cleanup_reason"));
            assertEquals("release_unconfirmed",result.getString("state"));assertFalse(result.getBoolean("cleanup_complete"));
            MediaCaptureTest.rejects("BUSY",()->MediaFiles.lease(root));
        }finally{
            java.lang.reflect.Field lease=MicrophoneSession.class.getDeclaredField("lease");lease.setAccessible(true);
            ((MediaFiles.Lease)lease.get(s)).close();
        }
    }
    @Test public void rpcRejectionAndDuplicateHelloFailClosed()throws Exception{
        Wire wire=new Wire();wire.rejectPublish=true;Device device=new Device();MicrophoneSession s=session(temp.newFolder(),wire,device,guard);
        s.start();wire.hello();assertTrue(s.awaitClosed(2000));assertFalse(wire.ready());
        assertFalse(s.snapshot().toString().contains("private remote detail"));
        Wire second=new Wire();second.dropNew=true;MicrophoneSession duplicate=session(temp.newFolder(),second,new Device(),guard);
        duplicate.start();second.hello();second.hello();assertTrue(duplicate.awaitClosed(2000));assertFalse(second.ready());
    }
    @Test public void activeSessionExcludesAlarmAndAnotherSession()throws Exception{
        final File root=temp.newFolder();MicrophoneSession s=session(root,new Wire(),new Device(),guard);s.start();
        try{MediaCaptureTest.rejects("BUSY",new MediaCaptureTest.Operation(){public void run()throws Exception{MediaFiles.lease(root);}});}
        finally{s.close();assertTrue(s.awaitClosed(2000));}
    }
    @Test public void publishedIceDoesNotClaimReadyBeforeVerifiedInput()throws Exception{
        Wire wire=new Wire();Device device=new Device();device.captureReady=false;
        MicrophoneSession s=session(temp.newFolder(),wire,device,guard);
        try{s.start();wire.hello();waitPublished(s);assertFalse(wire.ready());
            device.captureReady=true;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!wire.ready()&&System.nanoTime()<end)Thread.sleep(5);
            assertTrue(wire.ready());assertTrue(s.snapshot().getBoolean("capture_verified"));
        }finally{s.close();assertTrue(s.awaitClosed(2000));}
        assertTrue(s.snapshot().getBoolean("cleanup_complete"));
    }
}
