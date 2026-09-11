package net.elfradio.d31bootstrap;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.fail;

public final class RemoteMediaCommandTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test public void acceptsOnlyNonCaptureOperationsAndExactDigest() throws Exception {
        RemoteMediaCommand.validate(new String[]{"prepare", HASH});
        RemoteMediaCommand.validate(new String[]{"query", HASH});
        for (String[] args : new String[][]{
                {"start", HASH}, {"stop", HASH}, {"query", HASH, "session"},
                {"prepare", "/data/local/remote.apk"}, {"prepare", HASH + "0"},
                {"query", HASH.toUpperCase(java.util.Locale.ROOT)}, {"query"}, {}}) {
            try { RemoteMediaCommand.validate(args); fail("未拒绝未支持或不完整的请求"); }
            catch (IOException expected) { }
        }
    }
}
