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
        java.util.Map<String, String> addresses = new java.util.HashMap<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        addresses.put(network.getName(), address.getHostAddress());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return lanIpv4(addresses);
    }

    static String lanIpv4(java.util.Map<String, String> addresses) {
        for (String name : new String[]{"eth0", "wlan0"}) {
            String address = addresses.get(name);
            if (address != null && !address.isEmpty()) return address;
        }
        return "未取得有线或Wi-Fi地址";
    }
}
