package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.util.ArrayDeque;
import org.json.JSONArray;
import org.json.JSONObject;

/** 无网络/JNI/等待的PTT状态机；动作由主线串行媒体执行器执行，RPC回执直接喂回。 */
public final class PttProtocol {
    public static final class Action {
        public final String kind;public final JSONObject body;
        Action(String kind,JSONObject body){this.kind=kind;this.body=body;}
    }
    private final MediaCapture.Clock clock;
    private final ArrayDeque<Action> actions=new ArrayDeque<>();
    private final long inviteDeadline;
    private String state="waiting_hello",reason="",cleanupReason="",remoteSession="",pending="";
    private int sequence,pendingId;
    private long deadline,rpcDeadline;
    private boolean hello,opened,created,tracks,subscribing,answerAck,ice,mutedRequested,outputOwned,frames,unmuted,ready,stopping;
    public PttProtocol(MediaCapture.Clock clock,long expiresAt)throws Exception{
        this.clock=clock;long remaining=expiresAt-clock.wall();
        if(remaining<=0||remaining>45000)throw new IOException("MEDIA_OFFER_INVALID");
        inviteDeadline=clock.elapsed()+remaining;deadline=inviteDeadline;
    }
    public synchronized Action poll(){return actions.poll();}
    public synchronized boolean hasActions(){return !actions.isEmpty();}
    public synchronized boolean stopping(){return stopping;}
    public synchronized void receive(JSONObject message){
        if(stopping)return;
        try{
            String type=message.optString("type");
            if("closed".equals(type)){stop("MEDIA_REMOTE_CLOSED");return;}
            if("waiting".equals(type)||"pong".equals(type)||"ready".equals(type))return;
            if("hello".equals(type)){
                if(hello||!"ptt".equals(message.optString("mode")))throw new IOException("MEDIA_PTT_HELLO_INVALID");
                hello=true;state="opening";emit("OPEN",new JSONObject());return;
            }
            if("tracks".equals(type)){
                String session=message.optString("sessionId");JSONArray list=message.optJSONArray("tracks");
                if(!session.matches("[A-Za-z0-9_-]{1,128}")||list==null||list.length()!=1)throw new IOException("MEDIA_PTT_TRACKS_INVALID");
                JSONObject track=list.getJSONObject(0);
                if(!"audio".equals(track.optString("trackName"))||!"remote".equals(track.optString("location"))
                        ||!session.equals(track.optString("sessionId")))throw new IOException("MEDIA_PTT_TRACKS_INVALID");
                if(tracks&&!remoteSession.equals(session))throw new IOException("MEDIA_PTT_TRACKS_CHANGED");
                remoteSession=session;tracks=true;maybeSubscribe();return;
            }
            if("rpc".equals(type)){
                Object id=message.opt("id");
                if(!(id instanceof Integer)||message.getInt("id")!=pendingId||pending.isEmpty())throw new IOException("MEDIA_RPC_UNEXPECTED");
                if(message.has("error"))throw new IOException("MEDIA_RPC_REJECTED");
                JSONObject result=message.getJSONObject("result");String action=pending;pending="";pendingId=0;
                if("new".equals(action)){created=true;state="waiting_tracks";maybeSubscribe();}
                else if("subscribe".equals(action)){state="negotiating";emit("APPLY_SUBSCRIBE",result);}
                else if("answer".equals(action)){answerAck=true;state="connected_wait";emit("ANSWER_ACK",new JSONObject());maybeMuted();}
                return;
            }
            throw new IOException("MEDIA_PTT_MESSAGE_UNEXPECTED");
        }catch(Exception failure){stop(code(failure,"MEDIA_PTT_PROTOCOL_FAILED"));}
    }
    public synchronized void opened(){
        if(stopping)return;
        if(!hello||opened){stop("MEDIA_PTT_OPEN_STATE");return;}
        opened=true;rpc("new",new JSONObject());
    }
    public synchronized void answerCreated(JSONObject answer){
        if(stopping)return;
        if(!"negotiating".equals(state)){stop("MEDIA_PTT_ANSWER_STATE");return;}
        state="answering";rpc("answer",answer);
    }
    public synchronized void ice(boolean connected){
        if(stopping)return;
        if(ice&&!connected){stop("MEDIA_PTT_ICE_LOST");return;}ice=connected;maybeMuted();
    }
    private void maybeSubscribe(){
        if(created&&tracks&&!subscribing){subscribing=true;state="subscribing";rpc("subscribe",new JSONObject());}
    }
    private void maybeMuted(){
        if(answerAck&&ice&&!mutedRequested){mutedRequested=true;state="muted_verification";emit("PREPARE_MUTED",new JSONObject());}
    }
    /** 由可信本地输出守卫调用，不能从Web消息里读取布尔值。 */
    public synchronized void outputVerified(){
        if(stopping)return;
        if(!mutedRequested){stop("MEDIA_PTT_OUTPUT_STATE");return;}
        if(!outputOwned){outputOwned=true;emit("UNMUTE",new JSONObject());}
    }
    public synchronized void unmuted(){if(stopping)return;if(!outputOwned){stop("MEDIA_PTT_UNMUTE_STATE");return;}unmuted=true;markReady();}
    public synchronized void playbackFrames(){if(stopping)return;frames=true;markReady();}
    private void markReady(){
        if(!ready&&answerAck&&ice&&outputOwned&&unmuted&&frames){ready=true;state="streaming";
            deadline=clock.elapsed()+60000;emit("SEND",object("type","ready"));}
    }
    public synchronized void tick(){
        if(stopping)return;
        if(clock.elapsed()>=deadline)stop(ready?"MEDIA_PTT_DURATION_EXPIRED":"MEDIA_SESSION_TIMEOUT");
        else if(!pending.isEmpty()&&clock.elapsed()>=rpcDeadline)stop("MEDIA_RPC_TIMEOUT");
    }
    public synchronized void stop(String error){
        if(stopping)return;stopping=true;state="closing";reason=error!=null&&error.matches("MEDIA_[A-Z0-9_]{1,80}")?error:"MEDIA_STOPPED";
        pending="";pendingId=0;actions.clear();emit("CLOSE",new JSONObject());
    }
    public synchronized void released(String failure){
        if(!stopping)return;
        cleanupReason=failure==null?"":failure;state=cleanupReason.isEmpty()?"closed":"release_unconfirmed";
    }
    public synchronized JSONObject snapshot()throws Exception{return new JSONObject().put("state",state).put("reason",reason)
            .put("cleanup_reason",cleanupReason).put("cleanup_complete","closed".equals(state)).put("ready",ready&&!stopping)
            .put("subscribed",answerAck).put("ice_connected",ice).put("output_verified",outputOwned)
            .put("playback_frames_seen",frames).put("mode","ptt");}
    private void rpc(String action,JSONObject body){
        if(!pending.isEmpty()){stop("MEDIA_RPC_OVERLAP");return;}
        pending=action;pendingId=++sequence;rpcDeadline=Math.min(deadline,clock.elapsed()+20000);
        try{emit("SEND",new JSONObject().put("type","rpc").put("id",pendingId).put("action",action).put("body",body));}
        catch(Exception e){stop("MEDIA_PTT_JSON_FAILED");}
    }
    private void emit(String kind,JSONObject body){
        if(actions.size()>=8){stop("MEDIA_PTT_ACTION_OVERFLOW");return;}
        try{actions.add(new Action(kind,new JSONObject(body.toString())));}catch(Exception e){stop("MEDIA_PTT_JSON_FAILED");}
    }
    private static JSONObject object(String key,String value){try{return new JSONObject().put(key,value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String code(Exception e,String fallback){String message=e.getMessage();return message!=null&&message.matches("MEDIA_[A-Z0-9_]{1,80}")?message:fallback;}
}
