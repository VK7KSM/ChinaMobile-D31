package net.elfradio.d31system;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class CellularDisplay {
    static final String REQUEST = "com.starnet.getnumber";
    private final Context context;
    private final ScheduledExecutorService worker;
    private final Handler main = new Handler();
    private final AtomicBoolean queued = new AtomicBoolean();
    private final AtomicBoolean forced = new AtomicBoolean();
    private final TelephonyManager phone;
    private volatile ServiceState serviceState;
    private volatile boolean closed;
    private String lastName, lastNetwork;
    private boolean listening;
    private final PhoneStateListener listener = new PhoneStateListener() {
        @Override public void onServiceStateChanged(ServiceState state) {
            serviceState = state;
            refresh(false);
        }
        @Override public void onDataConnectionStateChanged(int state, int type) {
            refresh(false);
        }
    };

    CellularDisplay(Context context, ScheduledExecutorService worker) {
        this.context = context;
        this.worker = worker;
        phone = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        ensureListening();
    }

    void ensureListening() {
        if (listening || phone == null || closed) return;
        try {
            phone.listen(listener, PhoneStateListener.LISTEN_SERVICE_STATE
                    | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE);
            listening = true;
        } catch (SecurityException error) {
            SystemLog.append(context, "蜂窝显示等待电话状态读取权限");
        }
    }

    void refresh(boolean force) {
        if (closed) return;
        if (force) forced.set(true);
        if (queued.compareAndSet(false, true)) worker.schedule(() -> {
            queued.set(false);
            boolean requested = forced.getAndSet(false);
            if (closed || phone == null) return;
            try {
                if (phone.getSimState() != TelephonyManager.SIM_STATE_READY) {
                    lastName = lastNetwork = null;
                    return;
                }
                String name = displayName(phone.getLine1Number(), phone.getNetworkOperatorName(),
                        phone.getSimOperatorName());
                ServiceState state = serviceState;
                String network = networkLabel(phone.getNetworkType(), state == null ? -1 : state.getState());
                if (!requested && name.equals(lastName) && network.equals(lastNetwork)) return;
                lastName = name;
                lastNetwork = network;
                // 原厂接收器在主线程通知界面；此处仅提供被动查询结果，不启动取号服务。
                Intent reply = new Intent("com.starnet.simNumber").setPackage("com.starnet.nexui")
                        .putExtra("simOperator", name).putExtra("simNumber", network);
                main.post(() -> {
                    if (!closed) context.sendBroadcast(reply);
                });
                SystemLog.append(context, "蜂窝显示已刷新，制式=" + network + "，桌面请求=" + requested);
            } catch (RuntimeException error) {
                SystemLog.append(context, "蜂窝显示读取失败：" + error.getClass().getSimpleName());
            }
        }, 150, TimeUnit.MILLISECONDS);
    }

    static String displayName(String number, String operator, String simOperator) {
        if (number != null && !number.trim().isEmpty()) return number.trim();
        if (operator != null && !operator.trim().isEmpty()) return operator.trim();
        if (simOperator != null && !simOperator.trim().isEmpty()) return simOperator.trim();
        return "移动网络";
    }

    static String networkLabel(int type, int state) {
        if (state == ServiceState.STATE_POWER_OFF) return "移动网络已关闭";
        if (state == ServiceState.STATE_EMERGENCY_ONLY) return "仅限紧急呼叫";
        if (state == ServiceState.STATE_OUT_OF_SERVICE) return "未注册网络";
        switch (type) {
            case 13: case 19: return "4G LTE";
            case 20: return "5G";
            case 3: case 5: case 6: case 8: case 9: case 10: case 12: case 14: case 15: case 17: return "3G";
            case 1: case 2: case 4: case 7: case 11: case 16: return "2G";
            case 18: return "Wi-Fi 通话";
            default: return "等待网络";
        }
    }

    void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (listening) phone.listen(listener, PhoneStateListener.LISTEN_NONE);
    }
}
