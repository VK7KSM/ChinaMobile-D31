package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** 单文件只读取证；不输出号码、账号、焦点拥有者标识或SDP，不改变音频状态。 */
public final class AndroidAudioOccupancyCheck {
    static final String AUTHORITY = "com.starnet.vsdk.busyness.provider.callstatus";
    static final String URI = "content://" + AUTHORITY + "/callstatus";

    public static void main(String[] args) {
        try {
            if (args.length != 1 || !"snapshot".equals(args[0]) || android.os.Process.myUid() != 0
                    || android.os.Build.VERSION.SDK_INT != 23 || !"hct6735_66_m0".equals(android.os.Build.DEVICE))
                throw new IOException("仅允许D31的API23独立只读检查");
            if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            final Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            FutureTask<JSONObject> work = new FutureTask<JSONObject>(new Callable<JSONObject>() {
                public JSONObject call() throws Exception { return inspect(context); }
            });
            Thread worker = new Thread(work, "audio-occupancy-check"); worker.setDaemon(true); worker.start();
            JSONObject result = work.get(8, TimeUnit.SECONDS);
            System.out.println(result.toString());
            System.exit(0);
        } catch (Throwable error) {
            System.err.println("音频占用只读检查未完成：" + errorType(error));
            System.exit(1);
        }
    }

    public static JSONObject inspect(Context context) throws Exception {
        long start = android.os.SystemClock.elapsedRealtime();
        JSONObject result = new JSONObject().put("read_only", true).put("personal_values_emitted", false)
                .put("sdk", android.os.Build.VERSION.SDK_INT).put("sample_started_elapsed_ms", start);
        JSONObject cellular = new JSONObject(), audio = new JSONObject(), vendor = new JSONObject();
        try {
            Object phone = binder("phone", "com.android.internal.telephony.ITelephony");
            Class<?> api = Class.forName("com.android.internal.telephony.ITelephony");
            cellular.put("methods", methods(api, "getCallState", "getCallStateForSlot", "getCallStateForSubscriber"));
            cellular.put("call_state", integer(api.getMethod("getCallState").invoke(phone)));
            Object tm = context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) throw new IOException("蜂窝服务不可用");
            cellular.put("phone_count", integer(Class.forName("android.telephony.TelephonyManager")
                    .getMethod("getPhoneCount").invoke(tm)));
        } catch (Throwable error) { cellular.put("error_type", errorType(error)); }
        try {
            Object service = binder("audio", "android.media.IAudioService");
            Class<?> api = Class.forName("android.media.IAudioService");
            audio.put("service_methods", methods(api, "getMode", "getCurrentAudioFocus", "getActiveRecordingConfigurations",
                    "getActivePlaybackConfigurations"));
            audio.put("mode", integer(api.getMethod("getMode").invoke(service)));
            audio.put("focus_gain", integer(api.getMethod("getCurrentAudioFocus").invoke(service)));
        } catch (Throwable error) { audio.put("service_error_type", errorType(error)); }
        try {
            Class<?> api = Class.forName("android.media.AudioSystem");
            audio.put("system_methods", methods(api, "isStreamActive", "isStreamActiveRemotely", "isSourceActive", "getNumStreamTypes"));
            int count = integer(api.getMethod("getNumStreamTypes").invoke(null));
            if (count < 1 || count > 32) throw new IOException("音频流数量越界");
            JSONArray streams = new JSONArray();
            for (int stream = 0; stream < count; stream++) {
                streams.put(new JSONObject().put("stream", stream)
                        .put("active", bool(api.getMethod("isStreamActive", int.class, int.class).invoke(null, stream, 0)))
                        .put("remote_active", bool(api.getMethod("isStreamActiveRemotely", int.class, int.class).invoke(null, stream, 0))));
            }
            audio.put("streams", streams);
            JSONArray sources = new JSONArray();
            Method active = api.getMethod("isSourceActive", int.class);
            // API23已定义的录音源枚举；不存在的方法不回退为空闲。
            for (int source = 0; source <= 8; source++)
                sources.put(new JSONObject().put("source", source).put("active", bool(active.invoke(null, source))));
            audio.put("sources", sources).put("input_owner_attribution", "NOT_AVAILABLE_IN_BOOLEAN_SOURCE_API");
        } catch (Throwable error) { audio.put("system_error_type", errorType(error)); }
        try {
            android.content.pm.ProviderInfo info = context.getPackageManager().resolveContentProvider(AUTHORITY, 0);
            vendor.put("resolved", info != null);
            if (info == null || !info.enabled || !info.applicationInfo.enabled) throw new IOException("原厂占用Provider不可用");
            vendor.put("provider_component", info.name).put("provider_package", info.packageName);
            vendor.put("statuses", vendorStatuses(context));
            vendor.put("scope", "NEXUI_SESSION_MAP_AND_HFP_NOT_REGISTRATION");
        } catch (Throwable error) { vendor.put("error_type", errorType(error)); }
        return result.put("cellular", cellular).put("audio", audio).put("nexui", vendor)
                .put("sample_finished_elapsed_ms", android.os.SystemClock.elapsedRealtime())
                .put("occupancy_verified", false);
    }

    static JSONArray vendorStatuses(Context context) throws Exception {
        if (android.os.Process.myUid() != 0) {
            try (Cursor cursor = context.getContentResolver().query(Uri.parse(URI), new String[]{"status"}, null, null, null)) {
                return statuses(cursor);
            }
        }
        Class<?> type = Class.forName("android.app.IActivityManager");
        Object manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
        IBinder token = new Binder();
        Object holder = type.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class)
                .invoke(manager, AUTHORITY, 0, token);
        if (holder == null) throw new IOException("原厂占用Provider不存在");
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            if (provider == null) throw new IOException("原厂占用Provider未就绪");
            Object raw = Class.forName("android.content.IContentProvider").getMethod("query", String.class, Uri.class,
                    String[].class, String.class, String[].class, String.class, Class.forName("android.os.ICancellationSignal"))
                    .invoke(provider, null, Uri.parse(URI), new String[]{"status"}, null, null, null, null);
            if (!(raw instanceof Cursor)) throw new IOException("原厂占用Provider未返回游标");
            try (Cursor cursor = (Cursor) raw) { return statuses(cursor); }
        } finally { type.getMethod("removeContentProviderExternal", String.class, IBinder.class).invoke(manager, AUTHORITY, token); }
    }

    private static JSONArray statuses(Cursor cursor) throws Exception {
        if (cursor == null || cursor.getColumnCount() != 1 || !"status".equals(cursor.getColumnName(0)))
            throw new IOException("原厂占用列合同不符");
        JSONArray result = new JSONArray();
        while (cursor.moveToNext()) {
            if (result.length() >= 32) throw new IOException("原厂会话计数超限");
            String value = cursor.isNull(0) ? "UNKNOWN" : cursor.getString(0);
            // 未知原值不输出，防止错误Provider把个人字段返回到状态列。
            if (!Arrays.asList("IDLE", "CONNECTED", "INCOMING", "OUTGOING", "CALLING", "DISCONNECTED",
                    "HOLD", "HOLDING", "CONNECTING", "DISCONNECTING").contains(value)) value = "UNKNOWN";
            result.put(value);
        }
        return result;
    }

    static Object binder(String name, String api) throws Exception {
        Object value = Class.forName("android.os.ServiceManager").getMethod("checkService", String.class).invoke(null, name);
        if (!(value instanceof IBinder)) throw new IOException("系统Binder不可用");
        Object service = Class.forName(api + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, value);
        if (service == null) throw new IOException("系统Binder接口不可用");
        return service;
    }
    private static JSONArray methods(Class<?> type, String... names) {
        JSONArray result = new JSONArray();
        for (Method method : type.getMethods()) if (Arrays.asList(names).contains(method.getName()))
            result.put(method.getName() + Arrays.toString(method.getParameterTypes()) + ":" + method.getReturnType().getName());
        return result;
    }
    private static int integer(Object value) throws IOException {
        if (!(value instanceof Integer)) throw new IOException("系统状态不是整数"); return (Integer) value;
    }
    private static boolean bool(Object value) throws IOException {
        if (!(value instanceof Boolean)) throw new IOException("系统状态不是布尔值"); return (Boolean) value;
    }
    static String errorType(Throwable error) {
        while (error.getCause() != null && error.getCause() != error) error = error.getCause();
        return error.getClass().getSimpleName();
    }
    private AndroidAudioOccupancyCheck() { }
}
