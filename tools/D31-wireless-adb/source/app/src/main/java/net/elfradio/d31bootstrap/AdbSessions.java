package net.elfradio.d31bootstrap;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLParameters;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

/** 按需启动的云端ADB会话；凭据仅在内存中存在，不传给shell。 */
final class AdbSessions implements Closeable {
    private final RollingLog log;
    private Session current;
    AdbSessions(File root){log=new RollingLog(new File(root,"adb-log"),32768,2);}
    static URI validate(JSONObject request,long now)throws Exception {
        String id=request.getString("session_id"),token=request.getString("token");
        if(!id.matches("[a-f0-9-]{36}")||!token.matches("[a-f0-9]{64}"))throw new IOException("ADB会话参数无效");
        long expires=request.getLong("expires_at");if(expires<=now||expires>now+120000)throw new IOException("ADB连接请求已到期");
        URI uri=new URI(request.getString("url")),control=new URI(RemoteProtocol.BASE);
        if(!"wss".equals(uri.getScheme())||!control.getHost().equals(uri.getHost())||uri.getUserInfo()!=null||uri.getFragment()!=null
                ||(uri.getPort()!=-1&&uri.getPort()!=443)||!"/api/elfremote/adb/device".equals(uri.getPath())||!("session_id="+id).equals(uri.getRawQuery()))
            throw new IOException("ADB中继地址不属于当前管理服务器");
        return uri;
    }
    synchronized JSONObject open(JSONObject request)throws Exception {
        URI uri=validate(request,System.currentTimeMillis());String id=request.getString("session_id");
        if(current!=null&&current.id.equals(id))return new JSONObject().put("accepted",true);

        if(current!=null)current.finish(null,"已打开新的ADB会话");
        current=new Session(id,uri,request.getString("token"));
        Thread thread=new Thread(current::connect,"elfremote-adb-connect");thread.setDaemon(true);thread.start();
        return new JSONObject().put("accepted",true);
    }
    public synchronized void close(){if(current!=null)current.finish(null,"维护核心正在更新，ADB已断开");}
    private final class Session {
        final String id;final WebSocketClient websocket;
        volatile AdbShell shell;volatile boolean ended;
        volatile Socket relaySocket;
        Object power;android.os.IBinder wakeToken;
        final java.util.Timer deadline=new java.util.Timer("elfremote-adb-deadline",true);
        Session(String id,URI uri,String token)throws Exception{
            this.id=id;
            websocket=new WebSocketClient(uri,Collections.singletonMap("Authorization","Bearer "+token)){
                @Override protected void onSetSSLParameters(SSLParameters parameters){
                    // Android 6无API24域名接口；在发送HTTP会话凭据前完成TLS与域名验证。
                    try {
                        javax.net.ssl.SSLSocket socket=(javax.net.ssl.SSLSocket)getSocket();
                        socket.setSoTimeout(10000);socket.startHandshake();
                        if(!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(uri.getHost(),socket.getSession()))
                            throw new javax.net.ssl.SSLPeerUnverifiedException("ADB中继证书域名不匹配");
                        socket.setSoTimeout(0);
                    }catch(IOException failure){throw new IllegalStateException("ADB中继TLS验证失败",failure);}
                }
                public void onOpen(ServerHandshake handshake){}
                public void onMessage(String raw){try{
                    if(raw.length()>90000)throw new IOException("终端输入过大");
                    JSONObject data=new JSONObject(raw);String type=data.optString("type");
                    if("closed".equals(type)){finish(null,"管理端已断开ADB");return;}
                    AdbShell active=shell;if(active==null||ended)throw new IOException("ADB尚未连接");
                    if("input".equals(type))active.input(android.util.Base64.decode(data.getString("data"),android.util.Base64.DEFAULT));
                    else if("resize".equals(type))active.resize(data.getInt("rows"),data.getInt("columns"));
                    else throw new IOException("不支持的终端操作");
                }catch(Exception failure){finish(null,"ADB输入失败");}}
                public void onClose(int code,String reason,boolean remote){finish(null,"ADB连接已关闭");}
                public void onError(Exception error){
                    log.write(System.currentTimeMillis()+" ADB_SOCKET_ERROR "+error.getClass().getSimpleName()
                            +" cause="+(error.getCause()==null?"none":error.getCause().getClass().getSimpleName()));
                    finish(null,"ADB网络连接失败");
                }
            };
            websocket.setConnectionLostTimeout(30);
        }
        void connect(){
            try{
                log.write(System.currentTimeMillis()+" ADB_CONNECT_BEGIN");
                holdAwake();deadline.schedule(new java.util.TimerTask(){public void run(){finish(null,"本次终端已到时");}},1800000);
                // Android 6必须由带host的分层工厂设置SNI，裸SSLSocket再connect会丢失域名。
                Socket tcp=new Socket();relaySocket=tcp;
                if(ended)throw new IOException("ADB请求已取消");
                tcp.connect(new InetSocketAddress(websocket.getURI().getHost(),443),10000);
                Socket tls=RemoteTls.factory().createSocket(tcp,websocket.getURI().getHost(),443,true);
                relaySocket=tls;
                if(ended)throw new IOException("ADB请求已取消");
                websocket.setSocket(tls);
                if(!websocket.connectBlocking(10,TimeUnit.SECONDS)||ended)throw new IOException("无法连接ADB中继");
                int port=RemoteAdbMaintenance.ensureListening(id);
                Socket connection=new Socket();
                try{connection.connect(new InetSocketAddress("127.0.0.1",port),3000);}
                catch(IOException failure){connection.close();throw failure;}
                shell=new AdbShell(connection,new AdbShell.Listener(){
                    public void output(int channel,byte[] bytes){
                        // 每条云消息有界；UTF-8的跨块拼接由浏览器解码器负责。
                        for(int at=0;at<bytes.length;at+=32768)try{send(new JSONObject().put("type","output").put("channel",channel)
                                .put("data",android.util.Base64.encodeToString(Arrays.copyOfRange(bytes,at,Math.min(bytes.length,at+32768)),android.util.Base64.NO_WRAP)));}
                        catch(Exception e){finish(null,"ADB输出连接中断");}
                    }
                    public void closed(Integer exit,String error){finish(exit,error.isEmpty()?"ADB 已断开":error);}
                });
                if(ended){shell.close();return;}
                send(new JSONObject().put("type","ready").put("protocol",shell.shellV2()?"shell_v2":"shell").put("resize_supported",shell.shellV2()));log.write(System.currentTimeMillis()+" ADB_CONNECTED");shell.start();
            }catch(Exception error){
                log.write(System.currentTimeMillis()+" ADB_FAILED "+error.getClass().getSimpleName()+" cause="+(error.getCause()==null?"none":error.getCause().getClass().getSimpleName())
                        + " at="+(error.getStackTrace().length==0?"none":error.getStackTrace()[0].toString()));
                finish(null,error instanceof IOException?error.getMessage():"ADB会话启动失败，请查看维护日志");
            }
        }
        void send(JSONObject data)throws IOException {
            if(ended||!websocket.isOpen())throw new IOException("ADB中继已关闭");
            if(websocket.getConnection() instanceof org.java_websocket.WebSocketImpl) {
                long pending=0;for(java.nio.ByteBuffer buffer:((org.java_websocket.WebSocketImpl)websocket.getConnection()).outQueue)pending+=buffer.remaining();
                if(pending>262144)throw new IOException("ADB输出积压，连接已关闭");
            }
            websocket.send(data.toString());
        }
        synchronized void holdAwake()throws Exception {
            if(ended)throw new IOException("ADB请求已取消");
            android.os.IBinder binder=(android.os.IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"power");
            power=Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface",android.os.IBinder.class).invoke(null,binder);
            wakeToken=new android.os.Binder();
            Class.forName("android.os.IPowerManager").getMethod("acquireWakeLock",android.os.IBinder.class,int.class,String.class,String.class,android.os.WorkSource.class,String.class)
                    .invoke(power,wakeToken,1,"elfRemote:adb-session","net.elfradio.d31bootstrap",null,null);
        }
        synchronized void finish(Integer exit,String reason){
            if(ended)return;ended=true;
            deadline.cancel();
            if(shell!=null)shell.close();
            try{if(power!=null&&wakeToken!=null)Class.forName("android.os.IPowerManager").getMethod("releaseWakeLock",android.os.IBinder.class,int.class).invoke(power,wakeToken,0);}catch(Exception ignored){}
            boolean open=websocket.isOpen();
            try{if(open)websocket.send(new JSONObject().put("type","closed").put("exit",exit==null?JSONObject.NULL:exit).put("message",reason).toString());}catch(Exception ignored){}
            websocket.close();
            if(!open)try{if(relaySocket!=null)relaySocket.close();}catch(IOException ignored){}
            log.write(System.currentTimeMillis()+" ADB_CLOSED exit="+exit);
        }
    }
}
