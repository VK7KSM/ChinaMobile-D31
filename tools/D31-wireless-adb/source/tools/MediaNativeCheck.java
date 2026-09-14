package net.elfradio.d31bootstrap;

import java.io.File;
import net.elfradio.d31bootstrap.media.ApkMediaLibrary;
import org.json.JSONObject;

/** 仅验证当前APK的JNI装载，不建立连接、不创建或启动音频录制。 */
public final class MediaNativeCheck {
    public static void main(String[] args) {
        try {
            if (args.length != 1 || !args[0].matches("[a-f0-9]{64}") || android.os.Process.myUid() != 0
                    || android.os.Build.VERSION.SDK_INT != 23 || !"hct6735_66_m0".equals(android.os.Build.DEVICE))
                throw new IllegalArgumentException("仅限D31的当前载荷JNI检查");
            String[] paths = System.getenv("CLASSPATH").split(":");
            File apk = new File(paths[paths.length - 1]);
            if (!apk.getPath().equals("/data/local/d31-remote/releases/" + args[0] + "/remote.apk"))
                throw new IllegalArgumentException("必须使用已验证活动载荷");
            File cache = new File("/data/local/tmp/d31-native-check-" + java.util.UUID.randomUUID());
            JSONObject result = ApkMediaLibrary.inspect(apk, args[0], "armeabi-v7a");
            boolean loaded = new ApkMediaLibrary(apk, args[0], cache).load("jingle_peerconnection_so");
            result.put("loaded", loaded).put("process_64bit", android.os.Process.is64Bit())
                    .put("audio_record_created", false).put("network_started", false)
                    .put("cache", cache.getAbsolutePath());
            System.out.println(result.toString());
            System.exit(loaded ? 0 : 1);
        } catch (Throwable error) {
            error.printStackTrace(); System.exit(1);
        }
    }
}
