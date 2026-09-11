package net.elfradio.d31bootstrap;

import org.json.JSONObject;
import java.io.IOException;

final class RemoteAdbMaintenance {
    static String restartCommand() {
        return "stop adbd && start adbd && sleep 1 && [ \"$(getprop init.svc.adbd)\" = running ] && echo D31_ADBD_RESTARTED";
    }
    static final String START_IF_STOPPED="if [ \"$(getprop init.svc.adbd)\" = stopped ]; then start adbd; fi";

    static int ensureListening(String session)throws Exception {
        int port=AdbControl.currentPort();
        if(!TcpListenerState.isListening(port)) {
            String id=RemoteProtocol.hash("adb-start:"+session);
            JSONObject request=new JSONObject().put("id",id).put("command",START_IF_STOPPED).put("timeout",10);
            JSONObject state=RemoteHttp.local("/jobs/"+id,null);
            if(state==null)state=RemoteHttp.local("/exec",request);
            long until=android.os.SystemClock.elapsedRealtime()+5000;
            while(android.os.SystemClock.elapsedRealtime()<until) {
                if(state!=null&&!"running".equals(state.optString("state")))break;
                Thread.sleep(100);state=RemoteHttp.local("/jobs/"+id,null);
            }
            if(state==null||state.optInt("exit_code",-1)!=0)throw new IOException("启动adbd未确认，请查看设备急救状态");
            while(android.os.SystemClock.elapsedRealtime()<until&&!TcpListenerState.isListening(port))Thread.sleep(100);
            if(!TcpListenerState.isListening(port))throw new IOException("adbd未监听，请通过重启adbd入口恢复");
        }
        if(TcpListenerState.hasEstablishedClient(port))
            throw new IOException("D31的ADB已被其他客户端占用，请先断开刷机工具或电脑的D31连接后重试");
        return port;
    }
    private RemoteAdbMaintenance(){}
}
