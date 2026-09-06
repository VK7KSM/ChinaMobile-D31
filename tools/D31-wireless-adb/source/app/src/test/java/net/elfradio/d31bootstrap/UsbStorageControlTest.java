package net.elfradio.d31bootstrap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsbStorageControlTest {
    @Test
    public void acceptsOnlySafeVolumeIdentifiers() {
        assertTrue(UsbStorageControl.isSafeUuid("15ED-2840"));
        assertTrue(UsbStorageControl.isSafeUuid("disk_01.test"));
        assertFalse(UsbStorageControl.isSafeUuid(null));
        assertFalse(UsbStorageControl.isSafeUuid("../../data"));
        assertFalse(UsbStorageControl.isSafeUuid("bad;reboot"));
    }

    @Test
    public void ensureCommandsUseBoundedSourceAndTarget() {
        java.util.List<String> commands =
                UsbStorageControl.buildEnsureCommands("15ED-2840");
        String command = String.join("\n", commands);
        assertTrue(command.contains("/mnt/media_rw/15ED-2840"));
        assertTrue(command.contains("/data/media/0/USB-15ED-2840"));
        assertTrue(UsbStorageControl.publicPath("15ED-2840")
                .equals("/storage/emulated/0/USB-15ED-2840"));
        assertTrue(command.contains("mount -o bind"));
        assertTrue(commands.size() == 3);
    }

    @Test
    public void ensureCommandRejectsShellInput() {
        assertThrows(IllegalArgumentException.class,
                () -> UsbStorageControl.buildEnsureCommands("x;reboot"));
    }

    @Test
    public void removeCommandUnmountsBeforeRemovingDirectory() {
        String command = UsbStorageControl.buildRemoveCommand("15ED-2840");
        assertTrue(command.contains("/data/media/0/USB-15ED-2840"));
        assertTrue(command.indexOf("umount") < command.indexOf("rmdir"));
    }

    @Test
    public void separateVolumesReceiveSeparateMappings() {
        assertFalse(UsbStorageControl.buildEnsureCommands("15ED-2840")
                .equals(UsbStorageControl.buildEnsureCommands("ABCD-1234")));
        assertFalse(UsbStorageControl.publicPath("15ED-2840")
                .equals(UsbStorageControl.publicPath("ABCD-1234")));
    }

    @Test
    public void separateVolumesReceiveSeparatePublicPaths() {
        assertTrue(UsbStorageControl.publicPath("1101-2843")
                .equals("/storage/emulated/0/USB-1101-2843"));
        assertTrue(UsbStorageControl.publicPath("15ED-2840")
                .equals("/storage/emulated/0/USB-15ED-2840"));
        assertFalse(UsbStorageControl.publicPath("1101-2843")
                .equals(UsbStorageControl.publicPath("15ED-2840")));
    }

    @Test
    public void disablesOnlyVendorUsbPromptActivity() {
        String command = UsbStorageControl.buildDisableVendorPromptCommand();
        assertTrue(command.contains(UsbStorageControl.VENDOR_USB_PROMPT_COMPONENT));
        assertFalse(command.endsWith("com.starnet.files"));
    }

    @Test
    public void parsesOnlyRealPublicVolumeMounts() {
        assertTrue("1101-2843".equals(UsbStorageControl.publicUuidFromMountLine(
                "/dev/block/vold/public:179:129 /mnt/media_rw/1101-2843 vfat rw 0 0")));
        assertTrue(UsbStorageControl.publicUuidFromMountLine(
                "/dev/fuse /storage/1101-2843 fuse rw 0 0") == null);
        assertTrue(UsbStorageControl.publicUuidFromMountLine(
                "/dev/fuse /mnt/media_rw/0 fuse rw 0 0") == null);
        assertTrue(UsbStorageControl.publicUuidFromMountLine(
                "/dev/block/vold/public:8:1 /mnt/media_rw/../../data vfat rw 0 0") == null);
    }
}
