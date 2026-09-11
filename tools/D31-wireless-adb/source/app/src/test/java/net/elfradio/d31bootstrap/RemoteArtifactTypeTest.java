package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RemoteArtifactTypeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private File archive(String name, String content) throws Exception {
        File file = temp.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            if (name != null) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(content.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test public void basicArchiveCannotPassFullClientCheck() throws Exception {
        assertFalse(RemoteUpdatePlatform.fullClient(archive(null, null)));
        assertFalse(RemoteUpdatePlatform.fullClient(archive("assets/remote-basic.marker", "d31-basic-v1\n")));
        assertFalse(RemoteUpdatePlatform.fullClient(archive("remote-full.marker", "d31-full-v1\n")));
    }

    @Test public void markerMustMatchExactSupportedBytes() throws Exception {
        assertTrue(RemoteUpdatePlatform.fullClient(archive("assets/remote-full.marker", "d31-full-v1\n")));
        for (String value : new String[]{"d31-full-v2\n", "d31-full-v1", "d31-full-v1\r\n", "d31-full-v1\nextra"})
            assertFalse(RemoteUpdatePlatform.fullClient(archive("assets/remote-full.marker", value)));
    }
}
