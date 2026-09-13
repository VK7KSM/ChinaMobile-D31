package net.elfradio.d31bootstrap;

import android.app.Application;
import java.net.URI;
import net.elfradio.d31bootstrap.media.AndroidAudioOccupancy;
import net.elfradio.d31bootstrap.media.AppMediaService;

/** 仅登记按需工厂；应用启动不读取音频状态、不加载JNI、不连接媒体服务器。 */
public final class RemoteApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppMediaService.configure(context -> new AndroidAudioOccupancy(context).start(),
                URI.create("https://v.elfradio.net"));
    }
}
