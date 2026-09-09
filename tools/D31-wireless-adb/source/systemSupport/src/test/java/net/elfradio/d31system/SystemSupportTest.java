package net.elfradio.d31system;

import org.junit.Test;
import static org.junit.Assert.*;

public class SystemSupportTest {
    @Test public void doesNotEnableFirewallWithoutPreference() {
        assertFalse(FirewallControl.DEFAULT_ENABLED);
    }
    @Test public void detectsUsbAndCardIndependently() {
        assertEquals("A1B2-C3D4", UsbStorageControl.publicUuidFromMountLine(
                "/dev/block/vold/public:8:1 /mnt/media_rw/A1B2-C3D4 vfat rw 0 0"));
        assertEquals("EF12-3456", UsbStorageControl.publicUuidFromMountLine(
                "/dev/block/vold/public:179:129 /mnt/media_rw/EF12-3456 vfat rw 0 0"));
        assertNotEquals(UsbStorageControl.publicPath("A1B2-C3D4"), UsbStorageControl.publicPath("EF12-3456"));
        assertNull(UsbStorageControl.publicUuidFromMountLine("/dev/fuse /mnt/media_rw/0 fuse rw 0 0"));
    }
    @Test public void rejectsPathsOutsideVolumeRoot() {
        assertFalse(UsbStorageControl.isSafeUuid("../../data"));
        assertThrows(IllegalArgumentException.class, () -> UsbStorageControl.buildEnsureCommands("';reboot"));
    }
    @Test public void retainsSipDeduplication() {
        assertTrue(SipNetworkMonitor.shouldNotify(null, "1|eth0|192.168.2.62", false));
        assertFalse(SipNetworkMonitor.shouldNotify("1|eth0|192.168.2.62", "1|eth0|192.168.2.62", false));
    }
}
