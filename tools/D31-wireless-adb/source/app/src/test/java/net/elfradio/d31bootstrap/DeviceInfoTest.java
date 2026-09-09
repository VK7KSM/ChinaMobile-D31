package net.elfradio.d31bootstrap;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class DeviceInfoTest {
    @Test public void cellularInterfaceIsNeverAdvertisedAsLan() {
        Map<String,String> addresses = new HashMap<>();
        addresses.put("ccmni0", "10.0.0.2");
        assertEquals("未取得有线或Wi-Fi地址", DeviceInfo.lanIpv4(addresses));
        addresses.put("wlan0", "192.0.2.2");
        assertEquals("192.0.2.2", DeviceInfo.lanIpv4(addresses));
        addresses.put("eth0", "192.0.2.1");
        assertEquals("192.0.2.1", DeviceInfo.lanIpv4(addresses));
    }
}
