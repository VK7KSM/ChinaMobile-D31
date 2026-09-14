package net.elfradio.d31bootstrap;

import java.net.InetAddress;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteFileNetworkTest {
    private RemoteFileNetwork.Evidence wifi() {
        RemoteFileNetwork.Evidence e = new RemoteFileNetwork.Evidence();
        e.active = e.complete = e.stable = e.internet = e.wifi = true;
        return e;
    }
    @Test public void activePolicyIpv6DefaultDoesNotRequireMainIpv4Default() {
        RemoteFileNetwork.Evidence e = wifi(); e.source6 = e.default6 = true;
        assertEquals("wifi", RemoteFileNetwork.classify(e));
        e.wifi = false; e.ethernet = true;
        assertEquals("ethernet", RemoteFileNetwork.classify(e));
    }
    @Test public void arbitraryAddressAndMismatchedFamilyDoNotProveExit() {
        RemoteFileNetwork.Evidence e = wifi(); e.source6 = true;
        assertEquals("unknown", RemoteFileNetwork.classify(e));
        e.default4 = true;
        assertEquals("unknown", RemoteFileNetwork.classify(e));
        e.source4 = true;
        assertEquals("wifi", RemoteFileNetwork.classify(e));
    }
    @Test public void cellularAndVpnNeverBecomeWifi() {
        RemoteFileNetwork.Evidence e = wifi(); e.default6 = e.source6 = e.cellular = true;
        assertEquals("cellular", RemoteFileNetwork.classify(e));
        e.cellular = false; e.vpn = true;
        assertEquals("other", RemoteFileNetwork.classify(e));
        e.vpn = false; e.ethernet = true;
        assertEquals("unknown", RemoteFileNetwork.classify(e));
    }
    @Test public void missingOrSwitchingActiveNetworkFailsClosed() {
        RemoteFileNetwork.Evidence e = wifi(); e.default4 = e.source4 = true;
        e.stable = false; assertEquals("unknown", RemoteFileNetwork.classify(e));
        e.stable = true; e.complete = false; assertEquals("unknown", RemoteFileNetwork.classify(e));
        e.complete = true; e.internet = false; assertEquals("unknown", RemoteFileNetwork.classify(e));
        e.active = false; assertEquals("offline", RemoteFileNetwork.classify(e));
    }
    @Test public void linkLocalLoopbackAndMulticastAreNotInternetSources() throws Exception {
        for (String literal : new String[]{"::", "::1", "fe80::1", "ff02::1", "0.0.0.0", "127.0.0.1", "169.254.1.1"})
            assertFalse(RemoteFileNetwork.usable(InetAddress.getByName(literal)));
        assertTrue(RemoteFileNetwork.usable(InetAddress.getByName("2001:db8::1")));
        assertTrue(RemoteFileNetwork.usable(InetAddress.getByName("192.168.1.2")));
        for (int flag : new int[]{0x08, 0x20, 0x40})
            assertFalse(RemoteFileNetwork.preferred(InetAddress.getByName("2001:db8::1"), flag));
        assertTrue(RemoteFileNetwork.preferred(InetAddress.getByName("2001:db8::1"), 0));
    }
}
