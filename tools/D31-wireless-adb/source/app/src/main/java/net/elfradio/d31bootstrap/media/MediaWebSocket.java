package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import javax.net.ssl.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

/** 复用现有wss及Bearer合同；不关闭TLS主机名校验，不记录信令/令牌。 */
public final class MediaWebSocket implements MicrophoneSession.Transport {
    private WebSocketClient client;
    private boolean closed;
    public synchronized void connect(RtcOffer offer,final MicrophoneSession.Events events)throws Exception {
        if(closed||client!=null)throw new IOException("MEDIA_SOCKET_NOT_REUSABLE");
        Socket plain=new Socket();SSLSocket tls=null;
        try{
            int port=offer.uri.getPort()<0?443:offer.uri.getPort();
            plain.connect(new InetSocketAddress(offer.uri.getHost(),port),10000);
            tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(plain,offer.uri.getHost(),port,true);
            tls.setSoTimeout(10000);tls.startHandshake();
            verifyPeer(offer.uri.getHost(),tls.getSession(),HttpsURLConnection.getDefaultHostnameVerifier());
            tls.setSoTimeout(0);
        client=new WebSocketClient(offer.uri,Collections.singletonMap("Authorization","Bearer "+offer.token)){
            // 默认实现调用API24方法；此套接字在发送任何鉴权前已独立校验。
            protected void onSetSSLParameters(SSLParameters parameters){}
            public void onOpen(ServerHandshake handshake){}
            public void onMessage(String raw){events.message(raw);}
            public void onClose(int code,String reason,boolean remote){events.disconnected();}
            public void onError(Exception failure){events.disconnected();}
        };
            client.setSocket(tls);client.setConnectionLostTimeout(20);client.connect();
        }catch(Exception failure){if(tls!=null)try{tls.close();}catch(Exception ignored){}try{plain.close();}catch(Exception ignored){}throw new IOException("MEDIA_TLS_CONNECT_FAILED");}
    }
    public synchronized void send(JSONObject message)throws Exception {
        if(closed||client==null||!client.isOpen())throw new IOException("MEDIA_SOCKET_CLOSED");
        client.send(message.toString());
    }
    public synchronized void close(){closed=true;if(client!=null)client.close();}
    static void verifyPeer(String host,SSLSession session,HostnameVerifier verifier)throws IOException {
        if(session==null||verifier==null||!verifier.verify(host,session))throw new IOException("MEDIA_TLS_HOST_REJECTED");
    }
}
