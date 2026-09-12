package net.elfradio.d31bootstrap.media;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** 协议动作只在专用线程执行；接收和tick不做connect、Binder、SDP或JNI等待。 */
public final class PttSessionController implements AutoCloseable {
    public interface Operations {
        void open()throws Exception;
        void send(JSONObject body)throws Exception;
        JSONObject subscribe(JSONObject body)throws Exception;
        void answerAcknowledged()throws Exception;
        void prepareMuted()throws Exception;
        /** 0表示尚未取得实际播放样本；1必须来自可信输出归属验证，未知活动格式抛具体错误。 */
        int outputProof()throws Exception;
        boolean playbackFrames();
        void unmute()throws Exception;
        void cancel();
        void close()throws Exception;
    }
    private final PttProtocol protocol;
    private final Operations operations;
    private final Runnable changed;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean queued=new AtomicBoolean(),dirty=new AtomicBoolean();
    private final CountDownLatch finished=new CountDownLatch(1);
    private volatile String published="";
    public PttSessionController(MediaCapture.Clock clock,long expiresAt,Operations operations,Runnable changed)throws Exception{
        protocol=new PttProtocol(clock,expiresAt);this.operations=operations;this.changed=changed;
    }
    public void receive(String raw){
        try{if(raw==null||raw.length()>96000)throw new IllegalArgumentException();protocol.receive(new JSONObject(raw));}
        catch(Exception invalid){stop("MEDIA_PTT_MESSAGE_INVALID");}if(protocol.stopping())operations.cancel();wake();
    }
    public void ice(boolean connected){protocol.ice(connected);if(protocol.stopping())operations.cancel();wake();}
    public void tick(){protocol.tick();if(protocol.stopping())operations.cancel();wake();}
    public void mediaChanged(){wake();}
    public JSONObject snapshot()throws Exception{return protocol.snapshot();}
    public void stop(String reason){protocol.stop(reason);operations.cancel();wake();}
    private void wake(){
        if(finished.getCount()==0)return;
        dirty.set(true);if(!queued.compareAndSet(false,true))return;
        try{worker.execute(this::pump);}catch(RejectedExecutionException closed){queued.set(false);}
    }
    private void pump(){
        try{
            do{
                dirty.set(false);PttProtocol.Action action;
                while((action=protocol.poll())!=null){
                    try{
                        switch(action.kind){
                            case "OPEN":operations.open();protocol.opened();break;
                            case "SEND":operations.send(action.body);break;
                            case "APPLY_SUBSCRIBE":protocol.answerCreated(operations.subscribe(action.body));break;
                            case "ANSWER_ACK":operations.answerAcknowledged();break;
                            case "PREPARE_MUTED":operations.prepareMuted();break;
                            case "UNMUTE":operations.unmute();protocol.unmuted();break;
                            case "CLOSE":
                                operations.cancel();String failure="";
                                try{operations.close();}catch(Exception|LinkageError error){failure=code(error,"MEDIA_PTT_RELEASE_UNCONFIRMED");}
                                protocol.released(failure);publish();finished.countDown();worker.shutdown();return;
                            default:throw new IllegalStateException("MEDIA_PTT_ACTION_INVALID");
                        }
                    }catch(Exception|LinkageError error){protocol.stop(code(error,"MEDIA_PTT_ACTION_FAILED"));operations.cancel();}
                }
                String state=protocol.snapshot().getString("state");
                if("muted_verification".equals(state)||"streaming".equals(state)){
                    try{
                        if(operations.outputProof()==1)protocol.outputVerified();
                        if(operations.playbackFrames())protocol.playbackFrames();
                    }catch(Exception|LinkageError failure){protocol.stop(code(failure,"MEDIA_PTT_OUTPUT_UNKNOWN"));operations.cancel();}
                    // 上述回调可能产生UNMUTE、ready或CLOSE，不丢弃其排队动作。
                    if(protocol.hasActions())dirty.set(true);
                }
                publish();
            }while(dirty.get()&&finished.getCount()>0);
        }catch(Exception unexpected){protocol.stop("MEDIA_PTT_CONTROLLER_FAILED");operations.cancel();dirty.set(true);}
        finally{queued.set(false);if(dirty.get()&&finished.getCount()>0)wake();}
    }
    private void publish()throws Exception{
        String next=protocol.snapshot().toString();if(next.equals(published))return;published=next;
        if(changed!=null)try{changed.run();}catch(Exception ignored){}
    }
    private static String code(Throwable failure,String fallback){String value=failure.getMessage();return value!=null&&value.matches("MEDIA_[A-Z0-9_]{1,80}")?value:fallback;}
    public boolean awaitClosed(long timeout)throws InterruptedException{return finished.await(Math.max(0,timeout),TimeUnit.MILLISECONDS);}
    public void close(){stop("MEDIA_HOST_CLOSED");}
}
