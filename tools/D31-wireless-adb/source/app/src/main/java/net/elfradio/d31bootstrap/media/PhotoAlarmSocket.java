package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import javax.net.ssl.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.RemoteTls;

/** 独立控制连接，复用D31证书链；TLS域名校验完成后才发送Bearer。 */
public final class PhotoAlarmSocket implements PhotoAlarmSession.Wire {
    private volatile Socket opening;
    private volatile WebSocketClient client;
    private volatile boolean closed;
    public void connect(PhotoAlarmOffer offer,final PhotoAlarmSession.Events events)throws Exception {
        Socket plain=new Socket();SSLSocket tls=null;
        try {
            synchronized(this){if(closed)throw new IOException();opening=plain;}
            plain.connect(new InetSocketAddress(offer.uri.getHost(),offer.uri.getPort()<0?443:offer.uri.getPort()),8000);
            tls=(SSLSocket)RemoteTls.factory().createSocket(plain,offer.uri.getHost(),offer.uri.getPort()<0?443:offer.uri.getPort(),true);
            synchronized(this){if(closed)throw new IOException();opening=tls;}
            tls.setSoTimeout(8000);tls.startHandshake();
            if(!HttpsURLConnection.getDefaultHostnameVerifier().verify(offer.uri.getHost(),tls.getSession()))throw new IOException();
            tls.setSoTimeout(0);
            synchronized(this){
                if(closed)throw new IOException();
                client=new WebSocketClient(offer.uri,Collections.singletonMap("Authorization","Bearer "+offer.token)) {
                    protected void onSetSSLParameters(SSLParameters ignored){} // API23：上面已校验域名。
                    public void onOpen(ServerHandshake h){}
                    public void onMessage(String raw){events.message(raw);}
                    public void onClose(int code,String reason,boolean remote){events.disconnected();}
                    public void onError(Exception error){events.disconnected();}
                };
                client.setSocket(tls);client.setConnectionLostTimeout(20);client.connect();
            }
        }catch(Exception failure){if(tls!=null)try{tls.close();}catch(Exception ignored){}try{plain.close();}catch(Exception ignored){}
            throw new IOException("VISUAL_TLS_CONNECT_FAILED");}
    }
    /**
     * 这里是持本对象的锁去调 client.send 的，刻意保留，但留个线索。
     *
     * 读线程投递 onMessage 后会经 PhotoAlarmSession.receive() 反过来调本方法，也要这把锁。
     * 不构成死锁：send 只是把帧放进发送队列，消费它的写线程不需要本对象的锁，
     * 队列满或网络背压时写线程仍能排空，卡住的一方终会往下走，属于暂时阻塞而非环。
     * 所以它和 finish/close 那类反向锁序不是一回事，WebSocketLockOrderAuditTest 只审 close。
     *
     * 但后果要知道：send 在锁内卡住期间，等这把锁的读线程也跟着停，
     * 于是照片/警报这一条通路会整体僵住直到网络恢复。它不会扩散到上报、不会让设备失联。
     * 现场若出现「照片传一半不动了、要重启才好」，这里是第一个该看的地方。
     */
    public synchronized void send(JSONObject value)throws Exception {
        if(closed||client==null||!client.isOpen())throw new IOException("VISUAL_SOCKET_CLOSED");client.send(value.toString());
    }
    public void close(){Socket socket;WebSocketClient ws;
        synchronized(this){if(closed)return;closed=true;socket=opening;ws=client;}
        if(ws!=null)ws.close();if(socket!=null)try{socket.close();}catch(Exception ignored){}
    }
}
