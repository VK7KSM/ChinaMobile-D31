package net.elfradio.d31bootstrap;

import android.annotation.TargetApi;
import android.annotation.SuppressLint;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/** 从活动Network的策略路由视图识别出口，不从地址或main表推断网络类型。 */
@TargetApi(23)
final class RemoteFileNetwork {
    static final class Evidence {
        boolean active, complete, stable, internet, wifi, ethernet, cellular, vpn;
        boolean default4, default6, source4, source6;
    }

    static String classify(Evidence e) {
        if (!e.active) return "offline";
        if (!e.complete || !e.stable) return "unknown";
        // 混合或VPN路径不推定为免费网络；蜂窝标志优先保持流量限制。
        if (e.cellular) return "cellular";
        if (e.vpn) return "other";
        if (!e.internet || !(e.default4 && e.source4 || e.default6 && e.source6)) return "unknown";
        if (e.wifi == e.ethernet) return "unknown";
        return e.wifi ? "wifi" : "ethernet";
    }

    static boolean usable(InetAddress address) {
        return address != null && !address.isAnyLocalAddress() && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress() && !address.isMulticastAddress();
    }

    static boolean preferred(InetAddress address, int flags) {
        // Linux地址标志：DADFAILED、DEPRECATED、TENTATIVE均不作为新连接源地址证据。
        return usable(address) && (flags & (0x08 | 0x20 | 0x40)) == 0;
    }

    @SuppressLint("MissingPermission")
    static String read(ConnectivityManager manager) {
        if (manager == null) return "unknown";
        try {
            Network active = manager.getActiveNetwork();
            if (active == null) return "offline";
            NetworkCapabilities caps = manager.getNetworkCapabilities(active);
            LinkProperties link = manager.getLinkProperties(active);
            if (caps == null || link == null || link.getInterfaceName() == null) return "unknown";
            Evidence e = new Evidence(); e.active = true; e.complete = true;
            e.internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            e.wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            e.ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            e.cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
            e.vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            for (LinkAddress entry : link.getLinkAddresses()) {
                InetAddress address = entry.getAddress();
                if (preferred(address, entry.getFlags())) {
                    e.source4 |= address instanceof Inet4Address;
                    e.source6 |= address instanceof Inet6Address;
                }
            }
            for (RouteInfo route : link.getRoutes()) {
                if (!route.isDefaultRoute() || !link.getInterfaceName().equals(route.getInterface())) continue;
                InetAddress destination = route.getDestination().getAddress();
                e.default4 |= destination instanceof Inet4Address;
                e.default6 |= destination instanceof Inet6Address;
            }
            e.stable = active.equals(manager.getActiveNetwork());
            return classify(e);
        } catch (Exception unavailable) { return "unknown"; }
    }
    private RemoteFileNetwork() { }
}
