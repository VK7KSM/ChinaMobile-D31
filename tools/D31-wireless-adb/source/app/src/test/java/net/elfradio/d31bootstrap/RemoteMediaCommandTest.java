package net.elfradio.d31bootstrap;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.fail;

public final class RemoteMediaCommandTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test public void acceptsPreparationAndQueryWithExactDigest() throws Exception {
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

    @Test public void acceptsOnlyBoundedLocalDiagnosticWithExplicitIdentity() throws Exception {
        RemoteMediaCommand.validate(new String[]{"local_audio_capture", HASH, "test-1", "1"});
        RemoteMediaCommand.validate(new String[]{"local_audio_capture", HASH, "test-5000", "5000"});
        for (String[] args : new String[][]{
                {"local_audio_capture", HASH}, {"local_audio_capture", HASH, "x", "0"},
                {"local_audio_capture", HASH, "x", "5001"}, {"local_audio_capture", HASH, "x", "-1"},
                {"local_audio_capture", HASH, "x", "1.0"}, {"local_audio_capture", HASH, "x", "0500"},
                {"local_audio_capture", HASH, "../x", "1000"}, {"local_audio_capture", HASH, "", "1000"},
                {"local_audio_capture", HASH, "x", "2500", "extra"}, {"start", HASH, "x", "2500"}}) {
            try { RemoteMediaCommand.validate(args); fail("未拒绝无界或不完整采音请求"); }
            catch (IOException expected) { }
        }
    }
}
