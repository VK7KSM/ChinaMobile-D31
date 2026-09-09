package net.elfradio.d31system;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;

final class SipNetworkMonitor {
    private static final String TAG = "D31SipNetwork";
    private static final String VENDOR_NETWORK_ACTION =
            "com.starnet.systemapi.action.ETHERNET_STATE_CHANGED";
    private static final String PREFS = "d31_sip_network";
    private static final String LAST_FINGERPRINT = "last_notified_fingerprint";
    private static final int NETWORK_SETTLE_SECONDS = 3;
    private static final int NETWORK_RETRY_SECONDS = 5;
    private static final int CALL_RETRY_SECONDS = 10;
    private static final int MAX_NETWORK_RETRIES = 12;
    private static final int MAX_CALL_RETRIES = 60;
    private static final long VENDOR_EVENT_SUPPRESSION_MS = 10_000L;
    private static final Object SCHEDULE_LOCK = new Object();
    private static Handler handler;
    private static Runnable pendingCheck;
    private static int recentVendorNetworkType = -1;
    private static long recentVendorEventAt = -1L;

    private SipNetworkMonitor() {
    }

    static void onConnectivityBroadcast(Context context, Intent source) {
        if (source.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)) {
            preferences(context).edit()
                    .remove(LAST_FINGERPRINT)
                    .apply();
            SystemLog.append(context, "网络已完全断开，清除SIP网络指纹");
        }
        schedule(context, NETWORK_SETTLE_SECONDS, 0);
    }

    static void onVendorNetworkBroadcast(Context context, Intent source) {
        String state = source.getStringExtra("connect_state_type");
        int networkType = source.getIntExtra("network_type", -1);
        if (!isVendorConnectedState(state) || networkType < 1 || networkType > 3) {
            return;
        }

        synchronized (SCHEDULE_LOCK) {
            recentVendorNetworkType = networkType;
            recentVendorEventAt = SystemClock.elapsedRealtime();
        }
        SystemLog.append(context, "原厂已发送有效网络事件，等待记录活动网络指纹：类型="
                + networkType + "，状态=" + state);
        schedule(context, 1, 0);
    }

    static void schedule(Context context, int delaySeconds, int retryCount) {
        Context appContext = context.getApplicationContext();
        synchronized (SCHEDULE_LOCK) {
            Handler mainHandler = handler();
            if (pendingCheck != null) mainHandler.removeCallbacks(pendingCheck);
            pendingCheck = () -> {
                synchronized (SCHEDULE_LOCK) {
                    pendingCheck = null;
                }
                handleSettled(appContext, retryCount);
            };
            mainHandler.postDelayed(pendingCheck, delaySeconds * 1000L);
        }
    }

    static void handleSettled(Context context, int retryCount) {
        NetworkSnapshot snapshot = readActiveNetwork(context);
        if (snapshot == null) {
            if (retryCount < MAX_NETWORK_RETRIES) {
                schedule(context, NETWORK_RETRY_SECONDS, retryCount + 1);
            }
            SystemLog.append(context, "SIP网络尚未收敛，延后复核，次数=" + retryCount);
            return;
        }

        SharedPreferences preferences = preferences(context);
        String previous = preferences.getString(LAST_FINGERPRINT, null);
        boolean networkChanged = !snapshot.fingerprint.equals(previous);

        if (isCallBusy(context)) {
            if (retryCount < MAX_CALL_RETRIES) {
                schedule(context, CALL_RETRY_SECONDS, retryCount + 1);
            }
            SystemLog.append(context, "当前处于响铃或通话状态，延后SIP网络切换，次数=" + retryCount);
            return;
        }

        int vendorType;
        long vendorEventAt;
        synchronized (SCHEDULE_LOCK) {
            vendorType = recentVendorNetworkType;
            vendorEventAt = recentVendorEventAt;
        }
        boolean vendorEventCovered = shouldSuppressForVendorEvent(
                snapshot.vendorNetworkType,
                vendorType,
                vendorEventAt,
                SystemClock.elapsedRealtime());

        if (networkChanged) {
            if (vendorEventCovered) {
                SystemLog.append(context, "原厂已通知当前网络，跳过重复SIP网络广播："
                        + snapshot.fingerprint);
                Log.i(TAG, "原厂事件已覆盖当前网络，跳过补发：" + snapshot.fingerprint);
            } else {
                Intent vendorEvent = new Intent(VENDOR_NETWORK_ACTION)
                        .putExtra("network_type", snapshot.vendorNetworkType)
                        .putExtra("connect_state_type", "CONNECTED")
                        .putExtra("extra_msg", "D31 active network settled");
                context.sendBroadcast(vendorEvent);
                SystemLog.append(context, "已通知Nexui按新网络重新注册SIP："
                        + snapshot.fingerprint);
                Log.i(TAG, "已发送Nexui网络事件：" + snapshot.fingerprint);
            }
            preferences.edit().putString(LAST_FINGERPRINT, snapshot.fingerprint).apply();
        } else {
            Log.i(TAG, "活动网络未变化，跳过SIP网络广播：" + snapshot.fingerprint);
        }

    }

    static boolean shouldNotify(String previous, String current, boolean callBusy) {
        return !callBusy && current != null && !current.equals(previous);
    }

    static String buildFingerprint(int vendorNetworkType, String interfaceName, String ipv4) {
        return vendorNetworkType + "|" + interfaceName + "|" + ipv4;
    }

    static boolean isVendorConnectedState(String state) {
        return "CONNECTED".equals(state) || "CONNECTED_LOCAL".equals(state);
    }

    static boolean shouldSuppressForVendorEvent(
            int activeNetworkType,
            int vendorNetworkType,
            long vendorEventAt,
            long now) {
        return activeNetworkType == vendorNetworkType
                && vendorEventAt >= 0L
                && now >= vendorEventAt
                && now - vendorEventAt <= VENDOR_EVENT_SUPPRESSION_MS;
    }

    private static NetworkSnapshot readActiveNetwork(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;

        ConnectivityManager connectivity = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) return null;

        NetworkInfo info = connectivity.getActiveNetworkInfo();
        Network active = connectivity.getActiveNetwork();
        if (info == null || !info.isConnected() || active == null) return null;

        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(active);
        LinkProperties links = connectivity.getLinkProperties(active);
        if (capabilities == null || links == null) return null;

        int vendorNetworkType;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            vendorNetworkType = 1;
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            vendorNetworkType = 2;
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            vendorNetworkType = 3;
        } else {
            return null;
        }

        String ipv4 = null;
        for (LinkAddress address : links.getLinkAddresses()) {
            InetAddress value = address.getAddress();
            if (value instanceof Inet4Address && !value.isLoopbackAddress()) {
                ipv4 = value.getHostAddress();
                break;
            }
        }
        if (ipv4 == null || links.getInterfaceName() == null) return null;

        boolean hasIpv4DefaultRoute = false;
        for (RouteInfo route : links.getRoutes()) {
            if (!route.isDefaultRoute()) continue;
            InetAddress gateway = route.getGateway();
            if (gateway == null || gateway instanceof Inet4Address) {
                hasIpv4DefaultRoute = true;
                break;
            }
        }
        if (!hasIpv4DefaultRoute) return null;

        return new NetworkSnapshot(
                vendorNetworkType,
                buildFingerprint(vendorNetworkType, links.getInterfaceName(), ipv4));
    }

    private static boolean isCallBusy(Context context) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return false;
        int mode = audio.getMode();
        return mode == AudioManager.MODE_RINGTONE
                || mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Handler handler() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        return handler;
    }

    private static final class NetworkSnapshot {
        final int vendorNetworkType;
        final String fingerprint;

        NetworkSnapshot(int vendorNetworkType, String fingerprint) {
            this.vendorNetworkType = vendorNetworkType;
            this.fingerprint = fingerprint;
        }
    }
}
