package net.elfradio.d31bootstrap.repair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AndroidRepairXattrsTest {
    @Test public void unsupportedFilesystemAllowsOnlyOptionalAclAndCapabilityQueries() {
        for (String name : new String[]{"security.capability", "system.posix_acl_access", "system.posix_acl_default"}) {
            assertTrue(name, AndroidRepairFileIo.absentXattr(name, false, true));
            assertTrue(name, AndroidRepairFileIo.absentXattr(name, true, false));
        }
        for (String name : new String[]{"security.selinux", "user.extra", "system.other_acl"})
            assertFalse(name, AndroidRepairFileIo.absentXattr(name, false, true));
    }
    @Test public void permissionAndIoErrorsCannotBeConvertedToMissingAttributes() {
        for (String name : new String[]{"security.capability", "system.posix_acl_access", "system.posix_acl_default", "security.selinux"})
            assertFalse(name, AndroidRepairFileIo.absentXattr(name, false, false));
    }
    @Test public void missingSelinuxAndMalformedLabelsRemainFailures() throws Exception {
        assertTrue(AndroidRepairFileIo.absentXattr("security.selinux", true, false));
        assertThrows(IOException.class, () -> AndroidRepairFileIo.requireSelinuxContext(null));
        assertThrows(IOException.class, () -> AndroidRepairFileIo.requireSelinuxContext(new byte[0]));
        assertThrows(IOException.class, () -> AndroidRepairFileIo.requireSelinuxContext(new byte[]{0}));
        assertThrows(IOException.class, () -> AndroidRepairFileIo.requireSelinuxContext("u:object_r:system_data_file:s0".getBytes(StandardCharsets.UTF_8)));
        assertEquals("u:object_r:system_data_file:s0", AndroidRepairFileIo.requireSelinuxContext(
                "u:object_r:system_data_file:s0\0".getBytes(StandardCharsets.UTF_8)));
    }
}
