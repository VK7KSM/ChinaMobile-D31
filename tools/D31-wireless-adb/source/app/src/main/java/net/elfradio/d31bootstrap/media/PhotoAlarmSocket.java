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
    public synchronized void send(JSONObject value)throws Exception {
        if(closed||client==null||!client.isOpen())throw new IOException("VISUAL_SOCKET_CLOSED");client.send(value.toString());
    }
    public void close(){Socket socket;WebSocketClient ws;
        synchronized(this){if(closed)return;closed=true;socket=opening;ws=client;}
        if(ws!=null)ws.close();if(socket!=null)try{socket.close();}catch(Exception ignored){}
    }
}
