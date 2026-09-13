package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProductSettingsReadTest {
    @Test public void acceptsOnlyScalarSettingOutput() throws Exception {
        assertNull(SettingsCommand.productValue("null"));
        assertEquals("", SettingsCommand.productValue(""));
        assertEquals("net.elfradio.d31phone.debug", SettingsCommand.productValue("net.elfradio.d31phone.debug"));
        assertEquals("a.b/.Listener:c.d/c.d.Listener", SettingsCommand.productValue("a.b/.Listener:c.d/c.d.Listener"));
    }

    @Test public void errorsAndMultilineOutputAreNotMissingSettings() throws Exception {
        for (String value : new String[]{null, "Error while accessing settings provider", "java.lang.SecurityException: denied",
                "null\nwarning", "a.b\n", "a.b\r", " a.b", "a.b\u0000", "a=b"}) {
            try { SettingsCommand.productValue(value); fail("必须拒绝无效输出"); }
            catch (IOException expected) { }
        }
    }

    @Test public void rejectsOtherKeysBeforeStartingAnyCommand() throws Exception {
        for (String key : new String[]{null, "android_id", "enabled_accessibility_services", "sms_default_application;id"}) {
            try { SettingsCommand.readProductSecure(key); fail("不得启动额外查询"); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
