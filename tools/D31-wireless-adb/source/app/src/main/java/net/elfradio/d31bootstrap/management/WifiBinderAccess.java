package net.elfradio.d31bootstrap.management;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.os.IBinder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 独立root进程直接查询IWifiManager，避免构造WifiManager时注册应用接收器。 */
final class WifiBinderAccess {
    private final Class<?> contract;
    private final Object service;

    WifiBinderAccess(Class<?> contract, Object service) throws IOException {
        if (service == null || !contract.isInstance(service)) throw new IOException("Wi-Fi Binder服务不可用");
        this.contract = contract;
        this.service = service;
    }

    static WifiBinderAccess connect() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "wifi");
        if (!(binder instanceof IBinder)) throw new IOException("Wi-Fi Binder不存在");
        Class<?> contract = Class.forName("android.net.wifi.IWifiManager");
        Object service = Class.forName("android.net.wifi.IWifiManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        return new WifiBinderAccess(contract, service);
    }

    int wifiState() throws Exception { return state("getWifiEnabledState"); }
    int hotspotState() throws Exception { return state("getWifiApEnabledState"); }

    WifiInfo connectionInfo() throws Exception {
        return nullable(call("getConnectionInfo"), WifiInfo.class);
    }

    WifiConfiguration hotspotConfiguration() throws Exception {
        return nullable(call("getWifiApConfiguration"), WifiConfiguration.class);
    }

    List<WifiConfiguration> configuredNetworks() throws Exception {
        Object value = call("getConfiguredNetworks");
        // D31实测为List；其它返回形式仅接受框架ParceledListSlice，不猜任意包装类型。
        Class<?> slice = value == null || value instanceof List ? null : Class.forName("android.content.pm.ParceledListSlice");
        return unpack(value, WifiConfiguration.class, slice);
    }

    private Object call(String method) throws Exception {
        // 通过公开接口的方法调用，避免依赖厂商私有Proxy类的Java访问权限。
        return contract.getMethod(method).invoke(service);
    }

    private int state(String method) throws Exception {
        Object value = call(method);
        if (!(value instanceof Integer)) throw new IOException("Wi-Fi状态返回类型不符");
        return (Integer) value;
    }

    private static <T> T nullable(Object value, Class<T> type) throws IOException {
        if (value == null) return null;
        if (!type.isInstance(value)) throw new IOException("Wi-Fi查询返回类型不符");
        return type.cast(value);
    }

    static <T> List<T> unpack(Object value, Class<T> itemType, Class<?> sliceType) throws Exception {
        Object list = value;
        if (!(list instanceof List)) {
            if (list == null || sliceType == null || !sliceType.isInstance(list)) throw new IOException("保存网络列表不可读");
            list = sliceType.getMethod("getList").invoke(list);
        }
        if (!(list instanceof List)) throw new IOException("保存网络列表包装无效");
        List<T> copy = new ArrayList<>();
        for (Object item : (List<?>) list) {
            if (!itemType.isInstance(item)) throw new IOException("保存网络列表元素无效");
            copy.add(itemType.cast(item));
        }
        return copy;
    }
}
