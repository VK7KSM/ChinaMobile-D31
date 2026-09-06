package net.elfradio.d31bootstrap;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

final class DeviceInfo {
    private DeviceInfo() {
    }

    static String localAddresses() {
        StringBuilder result = new StringBuilder();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!address.isLoopbackAddress()) {
                        if (result.length() > 0) result.append(' ');
                        result.append(network.getName()).append('=').append(address.getHostAddress());
                    }
                }
            }
        } catch (Throwable error) {
            return error.toString();
        }
        return result.toString();
    }

    static String firstIpv4() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "未知地址";
    }
}
