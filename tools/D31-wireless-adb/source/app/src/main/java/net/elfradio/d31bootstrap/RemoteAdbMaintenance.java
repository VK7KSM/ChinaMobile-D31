package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import java.io.IOException;

final class RemoteAdbMaintenance {
    static String restartCommand() {
        return "stop adbd && start adbd && sleep 1 && [ \"$(getprop init.svc.adbd)\" = running ] && echo D31_ADBD_RESTARTED";
    }
    static final String START_IF_STOPPED="if [ \"$(getprop init.svc.adbd)\" = stopped ]; then start adbd; fi";
    static final long START_WAIT_MS=15000;
    static final long POLL_MS=250;

    interface Access {
        int port();
        boolean listening(int port);
        boolean occupied(int port);
        // 仅明确的HTTP 404返回null；其他失败必须抛出异常。
        JSONObject local(String path,JSONObject body)throws Exception;
        long now();
        void pause(long milliseconds)throws InterruptedException;
        void event(String stage,int status);
    }

    static final class StartFailure extends IOException {
        final String stage;
        final int httpStatus;
        StartFailure(String stage,int status,String message,Throwable cause){
            super(message+" ["+stage+(status==0?"":", HTTP "+status)+"]",cause);
            this.stage=stage;this.httpStatus=status;
        }
    }

    static int ensureListening(String session)throws Exception {
        return ensureListening(session,(RollingLog)null);
    }

    static int ensureListening(String session,RollingLog log)throws Exception {
        return ensureListening(session,new Access(){
            public int port(){return AdbControl.currentPort();}
            public boolean listening(int port){return TcpListenerState.isListening(port);}
            public boolean occupied(int port){return TcpListenerState.hasEstablishedClient(port);}
            public JSONObject local(String path,JSONObject body)throws Exception{return RemoteHttp.local(path,body);}
            public long now(){return android.os.SystemClock.elapsedRealtime();}
            public void pause(long milliseconds)throws InterruptedException{Thread.sleep(milliseconds);}
            public void event(String stage,int status){
                String line=System.currentTimeMillis()+" "+stage+" http_status="+status;
                if(log!=null)log.write(line);else System.out.println(line);
            }
        });
    }

    static int ensureListening(String session,Access access)throws Exception {
        int port=access.port();
        if(!access.listening(port)) {
            long until=access.now()+START_WAIT_MS;
            int lastRejected=0;
            String id=RemoteProtocol.hash("adb-start:"+session);
            JSONObject request=new JSONObject().put("id",id).put("command",START_IF_STOPPED).put("timeout",10);
            access.event("ADB_START_BEGIN",0);
            JSONObject state=query(access,id,until,lastRejected);
            boolean submitted=false;
            while(true) {
                within(access,until,lastRejected);
                if(state==null) {
                    if(submitted)throw failure(access,"ADB_START_RECEIPT_MISSING",404,"启动请求回执缺失，未重投",null);
                    try {
                        access.event("ADB_START_SUBMIT",0);
                        state=access.local("/exec",request);
                        submitted=true;
                        access.event("ADB_START_SUBMITTED",state==null?404:202);
                    }catch(RemoteHttp.Rejected rejected){
                        access.event("ADB_START_SUBMIT_REJECTED",rejected.status);
                        if(rejected.status!=409)throw failure(access,"ADB_START_REJECTED",rejected.status,"启动adbd请求被拒绝",rejected);
                        lastRejected=rejected.status;
                        // 409可能来自占用或同号已存在；必须先查原号，不能直接再投。
                        state=query(access,id,until,lastRejected);
                        if(state==null)pause(access,until,lastRejected);
                        continue;
                    }catch(IOException uncertain){
                        access.event("ADB_START_SUBMIT_UNCERTAIN",0);
                        // 请求可能已经执行；即使后续查到404，也不重新提交。
                        submitted=true;
                        state=query(access,id,until,lastRejected);
                    }
                    if(state==null)throw failure(access,"ADB_START_RECEIPT_MISSING",404,"启动请求回执缺失，未重投",null);
                    within(access,until,lastRejected);
                }
                if(!id.equals(state.optString("id")))
                    throw failure(access,"ADB_START_RECEIPT_INVALID",200,"启动请求回执标识不匹配",null);
                submitted=true;
                if("running".equals(state.optString("state"))) {
                    access.event("ADB_START_RUNNING",200);
                    pause(access,until,lastRejected);
                    state=query(access,id,until,lastRejected);
                    continue;
                }
                if(!"completed".equals(state.optString("state"))||state.optInt("exit_code",-1)!=0
                        ||state.optBoolean("truncated")||state.optBoolean("timed_out"))
                    throw failure(access,"ADB_START_COMMAND_FAILED",200,"启动adbd未确认，请查看设备急救状态",null);
                access.event("ADB_START_COMPLETED",200);
                break;
            }
            while(!access.listening(port)) {
                if(access.now()>=until)throw failure(access,"ADB_START_LISTENER_TIMEOUT",0,"启动回执已完成，但adbd仍未监听",null);
                pause(access,until,lastRejected);
            }
        }
        if(access.occupied(port))
            throw failure(access,"ADB_START_CLIENT_OCCUPIED",0,"D31的ADB已被其他客户端占用，请先断开刷机工具或电脑的D31连接后重试",null);
        access.event("ADB_START_LISTENER_READY",0);
        return port;
    }

    private static JSONObject query(Access access,String id,long until,int lastRejected)throws Exception {
        within(access,until,lastRejected);
        try {
            JSONObject state=access.local("/jobs/"+id,null);
            access.event(state==null?"ADB_START_QUERY_MISSING":"ADB_START_QUERY_RECEIVED",state==null?404:200);
            return state;
        }catch(RemoteHttp.Rejected rejected){
            throw failure(access,"ADB_START_QUERY_REJECTED",rejected.status,"查询启动adbd回执被拒绝，未重投",rejected);
        }catch(IOException unavailable){
            throw failure(access,"ADB_START_QUERY_FAILED",0,"查询启动adbd回执失败，未重投",unavailable);
        }
    }

    private static void within(Access access,long until,int lastRejected)throws StartFailure {
        if(access.now()>=until)throw failure(access,"ADB_START_TIMEOUT",lastRejected,"等待启动adbd超时，未继续提交",null);
    }

    private static void pause(Access access,long until,int lastRejected)throws Exception {
        long remaining=until-access.now();
        if(remaining<=0)throw failure(access,"ADB_START_TIMEOUT",lastRejected,"等待启动adbd超时，未继续提交",null);
        access.pause(Math.min(POLL_MS,remaining));
    }

    private static StartFailure failure(Access access,String stage,int status,String message,Throwable cause){
        access.event(stage,status);return new StartFailure(stage,status,message,cause);
    }
    private RemoteAdbMaintenance(){}
}
