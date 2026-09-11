package net.elfradio.d31bootstrap;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public final class AdbControlTest {
    @Test public void networkAdbDoesNotRequireUsbGadget() {
        assertTrue(AdbControl.tcpHealthy("5555", "running", true));
        assertTrue(AdbControl.tcpHealthy("5654", "running", true));
        assertFalse(AdbControl.tcpHealthy("-1", "running", true));
        assertFalse(AdbControl.tcpHealthy("65536", "running", true));
        assertFalse(AdbControl.tcpHealthy("5555", "stopped", true));
        assertFalse(AdbControl.tcpHealthy("5555", "running", false));
    }
    @Test public void activePortWinsOverStalePersistentPort() {
        assertEquals(5654, AdbControl.selectPort("5654", "5555"));
        assertEquals(5654, AdbControl.selectPort("", "5654"));
        assertEquals(5654, AdbControl.selectPort("-1", "5654"));
        assertEquals(5555, AdbControl.selectPort(null, ""));
    }

    @Test public void invalidPropertiesCannotEnterShellCommands() {
        for (String value : new String[]{"0", "65536", "99999999999999", "5555;reboot", "5\n5", " 5"}) {
            assertEquals(5555, AdbControl.selectPort(value, value));
        }
        assertEquals(65535, AdbControl.selectPort("65535", ""));
    }

    @Test public void systemRestartKeepsUsbPersistentAndFirewallState() {
        String command = AdbControl.restartCommand(true, 5654);
        assertTrue(command.contains("service.adb.tcp.port 5654"));
        assertTrue(command.contains("stop adbd"));
        assertTrue(command.contains("start adbd"));
        assertFalse(command.contains("persist."));
        assertFalse(command.contains("usb"));
        assertFalse(command.contains("iptables"));
        assertFalse(command.contains("5555"));
    }

    @Test public void factoryBootstrapRetainsUsbCompatibilityWithSelectedPort() {
        String command = AdbControl.restartCommand(false, 5654);
        assertTrue(command.contains("sys.usb.config mass_storage,adb"));
        assertTrue(command.contains("persist.adb.tcp.port 5654"));
        assertFalse(command.contains("5555"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void restartRejectsInvalidPort() { AdbControl.restartCommand(true, -1); }
    @Test
    public void onlyTransportFailuresStartCooldown() {
        assertTrue(AdbControl.isRootTransportFailure(-1));
        assertTrue(AdbControl.isRootTransportFailure(75));
        assertTrue(AdbControl.isRootTransportFailure(124));
        assertFalse(AdbControl.isRootTransportFailure(0));
        assertFalse(AdbControl.isRootTransportFailure(1));
        assertFalse(AdbControl.isRootTransportFailure(-3));
    }
}
