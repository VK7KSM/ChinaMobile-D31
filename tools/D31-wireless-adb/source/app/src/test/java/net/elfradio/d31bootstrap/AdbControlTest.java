package net.elfradio.d31bootstrap;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AdbControlTest {
    @Test public void networkAdbDoesNotRequireUsbGadget() {
        assertTrue(AdbControl.tcpHealthy("5555", "running", true));
        assertFalse(AdbControl.tcpHealthy("5556", "running", true));
        assertFalse(AdbControl.tcpHealthy("5555", "stopped", true));
        assertFalse(AdbControl.tcpHealthy("5555", "running", false));
    }
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
