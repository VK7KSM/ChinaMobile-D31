package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 只保存稳定标识及值，不保存会被下一次getTransceivers销毁的包装。 */
final class CallTrackTopology {
    static final class Track {
        final String mid,senderId,senderKind,receiverId,receiverKind,direction;
        final boolean event;
        Track(String mid,String senderId,String senderKind,String receiverId,String receiverKind,String direction,boolean event){
            this.mid=mid;this.senderId=senderId;this.senderKind=senderKind;this.receiverId=receiverId;this.receiverKind=receiverKind;this.direction=direction;this.event=event;
        }
    }
    static int validate(List<Track> tracks,String senderId,DownlinkTrackBinding binding)throws IOException {
        if(senderId==null||tracks.isEmpty()||tracks.size()>2)throw new IOException("MEDIA_CALL_TOPOLOGY_INVALID");
        int senders=0,remote=-1;Set<String> mids=new HashSet<>();
        for(int i=0;i<tracks.size();i++){
            Track t=tracks.get(i);
            if(t.mid==null||!mids.add(t.mid))throw new IOException("MEDIA_CALL_TOPOLOGY_INVALID");
            if(t.senderKind!=null){
                if(!"audio".equals(t.senderKind)||!senderId.equals(t.senderId)
                        ||!("SEND_ONLY".equals(t.direction)||"SEND_RECV".equals(t.direction)))throw new IOException("MEDIA_CALL_SENDER_INVALID");
                senders++;
            }
            if(binding.expected(t.mid)){
                if(!binding.matches(t.mid,t.direction,t.receiverKind,t.event)||t.receiverId==null||remote!=-1)
                    throw new IOException("MEDIA_CALL_RECEIVER_INVALID");
                remote=i;
            }else if(t.event||"RECV_ONLY".equals(t.direction)||"SEND_RECV".equals(t.direction)||t.senderKind==null)
                throw new IOException("MEDIA_CALL_UNEXPECTED_RECEIVER");
        }
        if(senders!=1||remote<0)throw new IOException("MEDIA_CALL_TOPOLOGY_INVALID");
        return remote;
    }
    private CallTrackTopology(){}
}
