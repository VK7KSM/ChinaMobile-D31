package net.elfradio.d31bootstrap;

import android.content.Intent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class UsbInsertPromptTest {
    @Test
    public void promptsOnlyForSuccessfulPhysicalMount() {
        assertTrue(BootReceiver.shouldPromptForUsbAction(Intent.ACTION_MEDIA_MOUNTED, true));
        assertFalse(BootReceiver.shouldPromptForUsbAction(Intent.ACTION_MEDIA_MOUNTED, false));
        assertFalse(BootReceiver.shouldPromptForUsbAction(
                "net.elfradio.d31bootstrap.REFRESH_USB", true));
        assertFalse(BootReceiver.shouldPromptForUsbAction(Intent.ACTION_MEDIA_UNMOUNTED, true));
    }

    @Test
    public void successfulRetryAlsoPrompts() {
        assertTrue(BootReceiver.shouldPromptForUsbAction(
                "net.elfradio.d31bootstrap.RETRY_USB_MOUNT", true));
        assertFalse(BootReceiver.shouldPromptForUsbAction(
                "net.elfradio.d31bootstrap.RETRY_USB_MOUNT", false));
    }

    @Test
    public void retryBackoffIsFinite() {
        assertEquals(3, BootReceiver.usbRetryDelaySeconds(1));
        assertEquals(10, BootReceiver.usbRetryDelaySeconds(2));
        assertThrows(IllegalArgumentException.class,
                () -> BootReceiver.usbRetryDelaySeconds(3));
    }
}
