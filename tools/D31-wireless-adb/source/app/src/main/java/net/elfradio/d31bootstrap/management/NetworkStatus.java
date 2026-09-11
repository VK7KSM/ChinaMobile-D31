package net.elfradio.d31bootstrap.management;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.telephony.TelephonyManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 只调用系统查询；不探测服务器、不切网络、不读取或返回保存的密码。 */
@TargetApi(23)
final class NetworkStatus {
    // 生产调用者为受控系统上下文；权限拒绝仍由实际Binder调用报告，不以声明代替授权。
    @SuppressLint("MissingPermission")
    static void read(Context context, JSONObject params, JSONObject out) throws Exception {
        JSONObject unavailable = new JSONObject();
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) throw new IOException("网络状态服务不可用");
        Network active = cm.getActiveNetwork();
        NetworkInfo activeInfo = cm.getActiveNetworkInfo();
        out.put("connected", activeInfo != null && activeInfo.isConnected());
        Network[] all = cm.getAllNetworks();
        if (all == null || all.length > 32) throw new IOException("网络清单不可用或超限");
        JSONArray networks = new JSONArray();
        for (Network network : all) {
            JSONObject row = new JSONObject().put("active", network.equals(active));
            NetworkCapabilities cap = cm.getNetworkCapabilities(network);
            JSONArray transport = new JSONArray();
            if (cap != null) {
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transport.put("ethernet");
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transport.put("wifi");
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transport.put("cellular");
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transport.put("vpn");
                row.put("transports", transport).put("validated", cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            } else row.put("capabilities_unavailable", true);
            LinkProperties link = cm.getLinkProperties(network);
            if (link != null) {
                JSONArray addresses = new JSONArray(), dns = new JSONArray(), routes = new JSONArray();
                for (LinkAddress address : link.getLinkAddresses()) addresses.put(address.toString());
                for (InetAddress address : link.getDnsServers()) dns.put(address.getHostAddress());
                for (RouteInfo route : link.getRoutes()) routes.put(new JSONObject().put("destination", route.getDestination().toString())
                        .put("gateway", route.getGateway() == null ? JSONObject.NULL : route.getGateway().getHostAddress()).put("default", route.isDefaultRoute()));
                row.put("interface", link.getInterfaceName()).put("addresses", addresses).put("dns", dns).put("routes", routes);
            } else row.put("link_unavailable", true);
            networks.put(row);
        }
        out.put("networks", networks);
        WifiBinderAccess wifi = WifiBinderAccess.connect();
        int wifiState = wifi.wifiState();
        out.put("wifi_state", wifiState);
        if (wifiState == 1 || wifiState == 3) out.put("wifi_enabled", wifiState == 3);
        else unavailable.put("wifi_enabled", "Wi-Fi处于过渡或未知状态");
        WifiInfo info = wifi.connectionInfo();
        if (info != null) out.put("ssid", unquote(info.getSSID())).put("network_id", info.getNetworkId());
        else unavailable.put("wifi_connection", "当前连接不可读");
        List<WifiConfiguration> saved = wifi.configuredNetworks();
        Collections.sort(saved, new Comparator<WifiConfiguration>() {
            public int compare(WifiConfiguration a, WifiConfiguration b) { return Integer.compare(a.networkId, b.networkId); }
        });
        int offset = params.getInt("offset");
        JSONArray rows = new JSONArray();
        for (int i = offset; i < Math.min(saved.size(), offset + 15); i++) {
            WifiConfiguration config = saved.get(i);
            rows.put(new JSONObject().put("id", config.networkId).put("ssid", unquote(config.SSID)));
        }
        out.put("saved", rows).put("saved_total", saved.size()).put("offset", offset)
                .put("saved_next", AndroidManagementAccess.next(offset, saved.size()));
        if ("network".equals(params.getString("group"))) {
            try {
                TelephonyManager phone = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (phone == null) throw new IOException("蜂窝服务不存在");
                out.put("mobile_available", phone.getSimState() == TelephonyManager.SIM_STATE_READY);
                Object value = phone.getClass().getMethod("getDataEnabled").invoke(phone);
                if (!(value instanceof Boolean)) throw new IOException("蜂窝开关查询类型不符");
                out.put("mobile_data", value);
            } catch (Exception error) { unavailable.put("mobile_data", SystemManagement.root(error).getClass().getSimpleName()); }
            try {
                BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
                out.put("bluetooth_supported", bt != null);
                if (bt != null) out.put("bluetooth", bt.isEnabled());
            } catch (Exception error) { unavailable.put("bluetooth", SystemManagement.root(error).getClass().getSimpleName()); }
            try {
                int code = wifi.hotspotState();
                JSONObject hotspot = new JSONObject().put("state", code);
                if (code == 11 || code == 13) hotspot.put("enabled", code == 13);
                else unavailable.put("hotspot_enabled", "热点处于过渡或失败状态");
                // 只取名称，绝不序列化含preSharedKey的WifiConfiguration。
                WifiConfiguration config = wifi.hotspotConfiguration();
                if (config != null) hotspot.put("ssid", unquote(config.SSID));
                out.put("hotspot", hotspot);
            } catch (Exception error) { unavailable.put("hotspot", SystemManagement.root(error).getClass().getSimpleName()); }
        }
        unavailable.put("network_write", "未完成独立回退守护、配置备份与管理链路确认接线");
        out.put("unavailable", unavailable);
    }

    private static Object unquote(String value) {
        if (value == null || "<unknown ssid>".equals(value)) return JSONObject.NULL;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length() - 1);
        return value;
    }
    private NetworkStatus() { }
}
