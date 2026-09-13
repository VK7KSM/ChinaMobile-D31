package net.elfradio.d31bootstrap.management;

import android.os.IBinder;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** 仅由有维护预留的父进程启动的短命root调用器；不接受shell片段或任意设置名。 */
public final class NetworkWifiCommand {
    enum Stage { DEVICE_CHECK, ARGUMENT_CHECK, APK_CHECK, SERVICE_LOOKUP, WIFI_READ,
        INTENT_CHECK, WIFI_SET, RECEIPT_SAVE, TARGET_OBSERVE }
    static String failure(Stage stage, Throwable error) {
        String code = "CALL_FAILED";
        for (int i=0; i<8 && error instanceof java.lang.reflect.InvocationTargetException; i++) {
            Throwable cause=error.getCause(); if (cause==null || cause==error) break; error=cause;
        }
        if (error instanceof ExceptionInInitializerError) code="INITIALIZATION_FAILED";
        else if (error instanceof NoSuchMethodException) code="METHOD_MISSING";
        else if (error instanceof ClassNotFoundException) code="CLASS_MISSING";
        else if (error instanceof IllegalAccessException) code="REFLECTION_ACCESS_DENIED";
        else if (error instanceof SecurityException) code="PERMISSION_DENIED";
        else if (error instanceof LinkageError) code="LINKAGE_FAILED";
        else if (error instanceof IOException) {
            code="IO_FAILED";
            String message=error.getMessage();
            for (String known:new String[]{"NETWORK_PLATFORM_REJECTED","NETWORK_WIFI_ARGS",
                    "NETWORK_APK_HASH_INVALID","NETWORK_PINNED_APK_REQUIRED","NETWORK_PARENT_REJECTED",
                    "NETWORK_APK_PATH_REJECTED","NETWORK_APK_HASH_MISMATCH","NETWORK_WIFI_BINDER_MISSING",
                    "NETWORK_WIFI_STATE_INVALID","NETWORK_WIFI_SET_REPLY_INVALID","NETWORK_CALL_MISMATCH",
                    "NETWORK_WIFI_SET_REJECTED","NETWORK_WIFI_CHANGE_UNCONFIRMED"})
                if (known.equals(message)) { code=known; break; }
        }
        return "NETWORK_WIFI_CALL_FAILED stage="+(stage==null ? "UNKNOWN" : stage.name())+" code="+code;
    }
    static Boolean state(int value) { return value == 1 ? Boolean.FALSE : value == 3 ? Boolean.TRUE : null; }
    static Object service() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "wifi");
        if (!(binder instanceof IBinder)) throw new IOException("NETWORK_WIFI_BINDER_MISSING");
        return Class.forName("android.net.wifi.IWifiManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }
    static Boolean read(Object service) throws Exception {
        Object value = Class.forName("android.net.wifi.IWifiManager").getMethod("getWifiEnabledState").invoke(service);
        if (!(value instanceof Integer)) throw new IOException("NETWORK_WIFI_STATE_INVALID");
        return state((Integer) value);
    }
    static boolean set(Object service, boolean target) throws Exception {
        // Android6合同仅接受这一精确签名；不存在时拒绝，不探测其它厂商写方法。
        Method method = Class.forName("android.net.wifi.IWifiManager").getMethod("setWifiEnabled", boolean.class);
        Object accepted = method.invoke(service, target);
        if (!(accepted instanceof Boolean)) throw new IOException("NETWORK_WIFI_SET_REPLY_INVALID");
        return (Boolean) accepted;
    }
    static JSONObject returnedReceipt(JSONObject intent, boolean accepted) throws Exception {
        return new JSONObject(intent.toString()).put("setter_returned", true)
                .put("request_accepted", accepted).put("target_observed", false);
    }
    public static void main(String[] args) {
        int exit = 1;
        Stage stage = Stage.DEVICE_CHECK;
        try {
            NetworkAndroidFiles.requireDevice();
            stage=Stage.ARGUMENT_CHECK;
            if (args.length != 3 && args.length != 5) throw new IOException("NETWORK_WIFI_ARGS");
            NetworkChangeTransaction.token(args[1]);
            stage=Stage.APK_CHECK;
            NetworkAndroidFiles.apk(args[2]);
            stage=Stage.SERVICE_LOOKUP;
            Object wifi = service();
            if (args.length == 3 && args[0].equals("get")) {
                stage=Stage.WIFI_READ;
                Boolean value = read(wifi);
                System.out.println(value == null ? "UNKNOWN" : value ? "ENABLED" : "DISABLED"); exit = 0;
            } else if (args.length == 5 && args[0].equals("set") && args[3].matches("[a-f0-9]{32}")
                    && (args[4].equals("true") || args[4].equals("false"))) {
                stage=Stage.INTENT_CHECK;
                File folder = NetworkAndroidFiles.task(args[1]);
                JSONObject intent = NetworkAndroidFiles.read(new File(folder, "call-intent.json"));
                boolean target = Boolean.parseBoolean(args[4]);
                if (!args[3].equals(intent.getString("id")) || !args[1].equals(intent.getString("task_id"))
                        || !args[2].equals(intent.getString("apk_sha256")) || intent.getBoolean("target") != target
                        || !NetworkAndroidFiles.boot().equals(intent.getString("boot_id"))) throw new IOException("NETWORK_CALL_MISMATCH");
                stage=Stage.WIFI_SET;
                boolean accepted = set(wifi, target);
                JSONObject receipt = returnedReceipt(intent, accepted);
                File receiptFile = new File(folder, "call-" + args[3] + ".json");
                // Binder同步返回先独立落盘；异步开关过渡超时不能抹掉这一证据。
                stage=Stage.RECEIPT_SAVE;
                NetworkAndroidFiles.write(receiptFile, receipt);
                if (!accepted) throw new IOException("NETWORK_WIFI_SET_REJECTED");
                stage=Stage.TARGET_OBSERVE;
                long deadline = SystemClock.elapsedRealtime() + 2000;
                boolean observed = false;
                do {
                    if (Boolean.valueOf(target).equals(read(wifi))) { observed = true; break; }
                    Thread.sleep(50);
                } while (SystemClock.elapsedRealtime() < deadline);
                if (!observed) throw new IOException("NETWORK_WIFI_CHANGE_UNCONFIRMED");
                stage=Stage.RECEIPT_SAVE;
                NetworkAndroidFiles.write(receiptFile, receipt.put("target_observed", true));
                System.out.println("SET_OBSERVED"); exit = 0;
            } else { stage=Stage.ARGUMENT_CHECK; throw new IOException("NETWORK_WIFI_ARGS"); }
        } catch (Throwable error) { System.err.println(failure(stage,error)); }
        System.exit(exit);
    }
    private NetworkWifiCommand() { }
}
