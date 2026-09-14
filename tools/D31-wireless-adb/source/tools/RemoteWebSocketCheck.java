package net.elfradio.d31bootstrap;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

public final class RemoteWebSocketCheck {
    public static void main(String[] args)throws Exception {
        final boolean layered=args.length>0&&args[0].equals("layered");
        URI uri=new URI("wss://v.elfradio.net/api/elfremote/adb/device?session_id=00000000-0000-0000-0000-000000000000");
        WebSocketClient client=new WebSocketClient(uri){
            protected void onSetSSLParameters(SSLParameters parameters){
                try{
                    SSLSocket s=(SSLSocket)getSocket();s.setSoTimeout(10000);s.startHandshake();
                    boolean valid=HttpsURLConnection.getDefaultHostnameVerifier().verify(uri.getHost(),s.getSession());
                    if(!valid)throw new SSLPeerUnverifiedException("域名不匹配");
                    System.out.println("TLS_HOST_VERIFIED protocol="+s.getSession().getProtocol());s.setSoTimeout(0);
                }catch(Exception error){throw new IllegalStateException(error);}
            }
            public void onOpen(ServerHandshake handshake){System.out.println("UNEXPECTED_OPEN");}
            public void onMessage(String message){System.out.println("MESSAGE length="+message.length());}
            public void onClose(int code,String reason,boolean remote){System.out.println("CLOSED code="+code+" reason="+reason);}
            public void onError(Exception error){error.printStackTrace(System.out);}
        };
        if(layered){
            java.net.Socket tcp=new java.net.Socket();tcp.connect(new java.net.InetSocketAddress(uri.getHost(),443),10000);
            client.setSocket(RemoteTls.factory().createSocket(tcp,uri.getHost(),443,true));
        }else client.setSocketFactory(RemoteTls.factory());
        try{System.out.println("CONNECTED="+client.connectBlocking(15,TimeUnit.SECONDS));}
        finally{client.closeBlocking();}
    }
}
