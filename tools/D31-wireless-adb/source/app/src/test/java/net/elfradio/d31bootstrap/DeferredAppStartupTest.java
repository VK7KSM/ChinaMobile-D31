package net.elfradio.d31bootstrap;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DeferredAppStartupTest {
    @Test
    public void firefoxCommandsTargetOnlyWorkManagerBootReceiver() {
        assertEquals(
                "pm disable --user 0 org.mozilla.firefox/"
                        + "androidx.work.impl.background.systemalarm.RescheduleReceiver",
                DeferredAppStartup.disableCommand(DeferredAppStartup.FIREFOX));
        assertEquals(
                "pm enable --user 0 org.mozilla.firefox/"
                        + "androidx.work.impl.background.systemalarm.RescheduleReceiver",
                DeferredAppStartup.enableCommand(DeferredAppStartup.FIREFOX));
    }

    @Test
    public void thunderbirdBroadcastIsExplicitAndIncludesStoppedPackages() {
        String command = DeferredAppStartup.bootBroadcastCommand(
                DeferredAppStartup.THUNDERBIRD);
        assertEquals(
                "am broadcast --user 0 -f 0x20 -a android.intent.action.BOOT_COMPLETED -n "
                        + "net.thunderbird.android/"
                        + "androidx.work.impl.background.systemalarm.RescheduleReceiver",
                command);
        assertFalse(command.contains("am start"));
        assertFalse(command.contains("force-stop"));
    }

    @Test
    public void appTargetsAreFixedPackageNames() {
        assertEquals("org.mozilla.firefox", DeferredAppStartup.FIREFOX.packageName);
        assertEquals("net.thunderbird.android",
                DeferredAppStartup.THUNDERBIRD.packageName);
    }
}
