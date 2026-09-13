package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.webrtc.*;
import static org.junit.Assert.*;

/** 脱敏三轨拓扑；执行真实RTC开关路径，异名子类仅替代JNI调用。 */
public class PersistentRemoteSelectionTest {
    static final class Row {
        String mid,sender,receiver,kind;RtpTransceiver.RtpTransceiverDirection direction;
        int writes,reads;boolean enabled,accept=true;
        Row(String mid,String sender,String receiver,String kind,RtpTransceiver.RtpTransceiverDirection direction){
            this.mid=mid;this.sender=sender;this.receiver=receiver;this.kind=kind;this.direction=direction;
        }
    }
    public static final class Track extends MediaStreamTrack {
        Pc pc;Row row;int generation;
        private Track(){super(1);}
        void live(){if(generation!=pc.generation)throw new IllegalStateException("过期轨包装");}
        public String kind(){live();return row.kind;}
        public boolean setEnabled(boolean enabled){live();row.writes++;boolean changed=enabled!=row.enabled;
            if(row.accept)row.enabled=enabled;return row.accept&&changed;}
        public boolean enabled(){live();row.reads++;return row.enabled;}
    }
    public static final class Receiver extends RtpReceiver {
        Pc pc;Row row;int generation;Track track;
        private Receiver(){super(1);}
        public String id(){assertEquals(generation,pc.generation);return row.receiver;}
        public MediaStreamTrack track(){assertEquals(generation,pc.generation);return track;}
    }
    public static final class Sender extends RtpSender {
        String id;private Sender(){super(1);}
        public String id(){return id;}
    }
    public static final class Transceiver extends RtpTransceiver {
        Pc pc;Row row;int generation;Receiver receiver;Sender sender;
        private Transceiver(){super(1);}
        public String getMid(){assertEquals(generation,pc.generation);return row.mid;}
        public RtpTransceiverDirection getCurrentDirection(){assertEquals(generation,pc.generation);return row.direction;}
        public RtpReceiver getReceiver(){assertEquals(generation,pc.generation);return receiver;}
        public RtpSender getSender(){assertEquals(generation,pc.generation);return sender;}
    }
    public static final class Pc extends PeerConnection {
        List<Row> rows;int generation,receiverEnumerations,transceiverEnumerations,playoutCalls;Runnable enumerated;
        private Pc(){super((NativePeerConnectionFactory)null);}
        Receiver receiver(Row row){try{
            Receiver receiver=(Receiver)CallInitOnlyReleaseTest.empty(Receiver.class);receiver.pc=this;receiver.row=row;receiver.generation=generation;
            if(row.kind!=null){Track track=(Track)CallInitOnlyReleaseTest.empty(Track.class);track.pc=this;track.row=row;track.generation=generation;receiver.track=track;}
            return receiver;
        }catch(Exception e){throw new AssertionError(e);}}
        public List<RtpReceiver> getReceivers(){receiverEnumerations++;generation++;List<RtpReceiver> result=new ArrayList<>();for(Row row:rows)result.add(receiver(row));return result;}
        public List<RtpTransceiver> getTransceivers(){transceiverEnumerations++;generation++;List<RtpTransceiver> result=new ArrayList<>();try{
            for(Row row:rows){Transceiver tx=(Transceiver)CallInitOnlyReleaseTest.empty(Transceiver.class);tx.pc=this;tx.row=row;tx.generation=generation;tx.receiver=receiver(row);
                tx.sender=(Sender)CallInitOnlyReleaseTest.empty(Sender.class);tx.sender.id=row.sender;result.add(tx);}
            if(enumerated!=null)enumerated.run();return result;
        }catch(Exception e){throw new AssertionError(e);}}
        public void setAudioPlayout(boolean enabled){playoutCalls++;}
        public void setAudioRecording(boolean enabled){}
        public void close(){}
        public void dispose(){}
    }
    static final class Fixture implements AutoCloseable {
        final CallInitOnlyReleaseTest logs=new CallInitOnlyReleaseTest();
        final PersistentMediaPeerTest.Fixture facade=new PersistentMediaPeerTest.Fixture();
        final PersistentHardwareBoundaryTest.Fixture hardware;
        final Pc pc;final Row localAudio,localVideo,remote;
        Fixture()throws Exception{
            logs.quietLibraryLogs();hardware=new PersistentHardwareBoundaryTest.Fixture();facade.prepare();
            pc=(Pc)CallInitOnlyReleaseTest.empty(Pc.class);pc.rows=new ArrayList<>();
            localAudio=new Row("a","local-a","unused-a","audio",RtpTransceiver.RtpTransceiverDirection.SEND_ONLY);
            localVideo=new Row("v","local-v","unused-v","video",RtpTransceiver.RtpTransceiverDirection.SEND_ONLY);
            remote=new Row("r","remote-s","remote-r","audio",RtpTransceiver.RtpTransceiverDirection.RECV_ONLY);
            pc.rows.add(localAudio);pc.rows.add(localVideo);pc.rows.add(remote);
            CallInitOnlyReleaseTest.set(hardware.rtc,"pc",pc);CallInitOnlyReleaseTest.set(hardware.rtc,"owner",facade.peer);CallInitOnlyReleaseTest.set(hardware.rtc,"cancel",new Cancellation());
            ((DownlinkTrackBinding)CallInitOnlyReleaseTest.get(hardware.rtc,"binding")).subscribeResult(new JSONObject().put("tracks",new JSONArray().put(new JSONObject().put("mid","r").put("trackName","audio"))));
            senders().put("local-a","audio");senders().put("local-v","video");events().put("remote-r","r");
        }
        @SuppressWarnings("unchecked") Map<String,String> senders()throws Exception{return (Map<String,String>)CallInitOnlyReleaseTest.get(hardware.rtc,"senders");}
        @SuppressWarnings("unchecked") Map<String,String> events()throws Exception{return (Map<String,String>)CallInitOnlyReleaseTest.get(hardware.rtc,"remoteEvents");}
        void setRemote(boolean enabled,long operation)throws Exception{
            Method method=AndroidPersistentRtc.class.getDeclaredMethod("setRemote",boolean.class,long.class);method.setAccessible(true);
            try{method.invoke(hardware.rtc,enabled,operation);}catch(InvocationTargetException failure){throw (Exception)failure.getCause();}
        }
        void rejected()throws Exception{try{setRemote(true,1);fail();}catch(IOException expected){assertEquals("MEDIA_PERSISTENT_REMOTE_INVALID",expected.getMessage());}
            assertEquals(0,localAudio.writes);assertEquals(0,localVideo.writes);assertEquals(0,remote.writes);}
        public void close()throws Exception{try{hardware.rtc.close();}finally{facade.close();logs.restoreLibraryLogs();}}
    }
    @Test public void photoActivationKeepsPreparedRemoteAndNativeOutputUnchanged()throws Exception{try(Fixture f=new Fixture()){
        f.setRemote(true,0);
        f.hardware.rtc.activate(1,"photo","front");assertEquals(0,f.localAudio.writes);assertEquals(0,f.localVideo.writes);
        assertEquals(1,f.remote.writes);assertEquals(1,f.remote.reads);assertTrue(f.remote.enabled);assertEquals(0,f.pc.playoutCalls);assertEquals(0,f.pc.receiverEnumerations);
    }}
    @Test public void pttActivationAndNormalStopOnlyToggleSoftwareMute()throws Exception{try(Fixture f=new Fixture()){
        f.setRemote(true,0);
        f.hardware.rtc.activate(1,"ptt","front");assertTrue(f.remote.enabled);f.hardware.rtc.stop(System.nanoTime()+2000000000L);
        assertTrue(f.remote.enabled);assertEquals(1,f.remote.writes);assertEquals(0,f.localAudio.writes);assertEquals(0,f.localVideo.writes);
        assertEquals(true,CallInitOnlyReleaseTest.get(f.hardware.audio.output,"speakerMute"));
        assertEquals(0,f.pc.playoutCalls);assertEquals(1,f.pc.transceiverEnumerations);assertEquals(0,f.pc.receiverEnumerations);
    }}
    @Test public void repeatedTogglesDoNotRetainDisposedWrappers()throws Exception{try(Fixture f=new Fixture()){
        for(int i=0;i<4;i++)f.setRemote(i%2==0,1);assertEquals(4,f.remote.writes);assertEquals(4,f.pc.transceiverEnumerations);assertEquals(0,f.pc.receiverEnumerations);
    }}
    @Test public void unchangedEnabledAndDisabledStatesPassActualReadback()throws Exception{try(Fixture f=new Fixture()){
        for(boolean enabled:new boolean[]{false,false,true,true,false,false}){
            f.setRemote(enabled,1);assertEquals(enabled,f.remote.enabled);
        }
        assertEquals(6,f.remote.writes);assertEquals(6,f.remote.reads);assertEquals(0,f.pc.receiverEnumerations);
    }}
    @Test public void nullLocalSendOnlyReceiversAreIgnored()throws Exception{try(Fixture f=new Fixture()){
        f.localAudio.kind=null;f.localVideo.kind=null;f.setRemote(true,1);assertTrue(f.remote.enabled);
    }}
    @Test public void remoteMayShareTheLocalAudioTransceiver()throws Exception{try(Fixture f=new Fixture()){
        f.pc.rows.remove(f.localAudio);f.remote.sender="local-a";f.remote.direction=RtpTransceiver.RtpTransceiverDirection.SEND_RECV;
        f.setRemote(true,1);assertTrue(f.remote.enabled);assertEquals(0,f.localVideo.writes);
    }}
    @Test public void wrongMidIsRejected()throws Exception{try(Fixture f=new Fixture()){f.remote.mid="other";f.rejected();}}
    @Test public void wrongReceiverEventIsRejected()throws Exception{try(Fixture f=new Fixture()){f.remote.receiver="other";f.rejected();}}
    @Test public void absentRemoteEventIsRejected()throws Exception{try(Fixture f=new Fixture()){f.events().clear();f.rejected();}}
    @Test public void duplicateRemoteEventsAreRejected()throws Exception{try(Fixture f=new Fixture()){f.events().put("extra","r");f.rejected();}}
    @Test public void wrongRemoteDirectionIsRejected()throws Exception{try(Fixture f=new Fixture()){f.remote.direction=RtpTransceiver.RtpTransceiverDirection.SEND_ONLY;f.rejected();}}
    @Test public void remoteVideoIsRejected()throws Exception{try(Fixture f=new Fixture()){f.remote.kind="video";f.rejected();}}
    @Test public void missingRemoteTrackIsRejected()throws Exception{try(Fixture f=new Fixture()){f.remote.kind=null;f.rejected();}}
    @Test public void missingBoundReceiverIsRejected()throws Exception{try(Fixture f=new Fixture()){f.pc.rows.remove(f.remote);f.rejected();}}
    @Test public void duplicateMidIsRejectedBeforeAnyTrackIsEnabled()throws Exception{try(Fixture f=new Fixture()){f.pc.rows.add(f.remote);f.rejected();}}
    @Test public void unboundIncomingTrackIsRejectedBeforeAnyTrackIsEnabled()throws Exception{try(Fixture f=new Fixture()){
        f.pc.rows.add(new Row("extra","extra-s","extra-r","audio",RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));f.rejected();
    }}
    @Test public void unknownSendOnlyTrackIsRejected()throws Exception{try(Fixture f=new Fixture()){f.localVideo.sender="unknown";f.rejected();}}
    @Test public void enablingFailureKeepsSpecificCode()throws Exception{try(Fixture f=new Fixture()){
        f.remote.accept=false;try{f.setRemote(true,1);fail();}catch(IOException expected){assertEquals("MEDIA_PERSISTENT_REMOTE_ENABLE_FAILED",expected.getMessage());}
        assertFalse(f.remote.enabled);assertEquals(1,f.remote.reads);
    }}
    @Test public void operationInvalidatedDuringEnumerationCannotEnableRemote()throws Exception{try(Fixture f=new Fixture()){
        f.pc.enumerated=()->f.facade.peer.invalidateOperation(1);try{f.setRemote(true,1);fail();}catch(IOException expected){}
        assertEquals(0,f.remote.writes);assertEquals(0,f.localAudio.writes);assertEquals(0,f.localVideo.writes);
    }}
}
