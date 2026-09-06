package net.elfradio.d31bootstrap;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SipNetworkMonitorTest {
    @Test
    public void firstReadyNetworkNotifies() {
        assertTrue(SipNetworkMonitor.shouldNotify(null, "2|wlan0|192.168.2.63", false));
    }

    @Test
    public void changedNetworkNotifies() {
        assertTrue(SipNetworkMonitor.shouldNotify(
                "1|eth0|192.168.2.62", "2|wlan0|192.168.2.63", false));
    }

    @Test
    public void duplicateNetworkDoesNotNotify() {
        assertFalse(SipNetworkMonitor.shouldNotify(
                "2|wlan0|192.168.2.63", "2|wlan0|192.168.2.63", false));
    }

    @Test
    public void callStateBlocksNotification() {
        assertFalse(SipNetworkMonitor.shouldNotify(
                "1|eth0|192.168.2.62", "2|wlan0|192.168.2.63", true));
    }

    @Test
    public void missingNetworkDoesNotNotify() {
        assertFalse(SipNetworkMonitor.shouldNotify("1|eth0|192.168.2.62", null, false));
    }

    @Test
    public void fingerprintContainsTypeInterfaceAndAddress() {
        assertEquals("2|wlan0|192.168.2.63",
                SipNetworkMonitor.buildFingerprint(2, "wlan0", "192.168.2.63"));
    }

    @Test
    public void recentMatchingVendorEventSuppressesSupplementalEvent() {
        assertTrue(SipNetworkMonitor.shouldSuppressForVendorEvent(1, 1, 10_000L, 15_000L));
    }

    @Test
    public void expiredVendorEventDoesNotSuppressSupplementalEvent() {
        assertFalse(SipNetworkMonitor.shouldSuppressForVendorEvent(1, 1, 10_000L, 20_001L));
    }

    @Test
    public void differentVendorNetworkTypeDoesNotSuppressSupplementalEvent() {
        assertFalse(SipNetworkMonitor.shouldSuppressForVendorEvent(2, 1, 10_000L, 15_000L));
    }

    @Test
    public void missingOrFutureVendorEventDoesNotSuppressSupplementalEvent() {
        assertFalse(SipNetworkMonitor.shouldSuppressForVendorEvent(2, 2, -1L, 15_000L));
        assertFalse(SipNetworkMonitor.shouldSuppressForVendorEvent(2, 2, 20_000L, 15_000L));
    }

    @Test
    public void onlyConnectedVendorStatesAreAccepted() {
        assertTrue(SipNetworkMonitor.isVendorConnectedState("CONNECTED"));
        assertTrue(SipNetworkMonitor.isVendorConnectedState("CONNECTED_LOCAL"));
        assertFalse(SipNetworkMonitor.isVendorConnectedState("DISCONNECTED"));
        assertFalse(SipNetworkMonitor.isVendorConnectedState(null));
    }

}
