package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.net.*;
import java.io.IOException;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/** 绑定本次允许的实际Network；默认路由变化不会把普通照片转发到蜂窝。 */
@android.annotation.TargetApi(23)
final class AutomaticPhotoNetwork implements PhotoAlarmUpload.ConnectionPolicy {
    private final ConnectivityManager manager;
    private final Network network;
    AutomaticPhotoNetwork(Context context,boolean legacyCritical)throws Exception {
        manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        network=allowed();if(network==null)throw new IOException("AUTO_PHOTO_NETWORK_PAUSED");
    }
    private Network allowed() {
        if(manager==null)return null;
        Network n=manager.getActiveNetwork();NetworkCapabilities c=n==null?null:manager.getNetworkCapabilities(n);
        return c!=null&&allowsTransports(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))?n:null;
    }
    // 旧持久队列的低电量标志也不能绕过D31的蜂窝禁传规则。
    static boolean allowsTransports(boolean wifi,boolean ethernet,boolean cellular) {
        return !cellular&&(wifi||ethernet);
    }
    public void check()throws IOException {if(!network.equals(allowed()))throw new IOException("AUTO_PHOTO_NETWORK_PAUSED");}
    public HttpsURLConnection open(URL url)throws Exception {check();return (HttpsURLConnection)network.openConnection(url);}
}
