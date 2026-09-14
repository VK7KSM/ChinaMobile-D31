package net.elfradio.d31bootstrap.diagnostics.collection;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;
import static net.elfradio.d31bootstrap.diagnostics.collection.CollectionFixture.*;

/** 元数据能力缺失、失败和变化用合成访问边界测试，不调用设备。 */
public class ExtendedMetadataTest {
    private static class Reader implements ExtendedMetadata.Reader {
        final Map<String, byte[]> values = new TreeMap<String, byte[]>();
        String error; boolean noEnumeration, changingNames; int lists;
        Reader() throws Exception { values.put("security.selinux", "u:object_r:system_file:s0\0".getBytes("UTF-8")); }
        public byte[] get(String name, int maximum) throws IOException {
            if (error != null) throw new CollectionAccess.Failure(error);
            return values.get(name);
        }
        public List<String> names() throws IOException {
            if (noEnumeration) throw new CollectionAccess.Failure("XATTR_ENUMERATION_UNAVAILABLE");
            lists++; return changingNames && lists % 2 == 0 ? Collections.<String>emptyList() : new ArrayList<String>(values.keySet());
        }
    }
    @Test public void labelAndBinaryValuesPreserveOriginalFieldContract() throws Exception {
        Reader reader = new Reader(); reader.values.put("security.capability", new byte[]{0, -1, 1});
        ExtendedMetadata m = ExtendedMetadata.read(reader, 4096);
        assertEquals("u:object_r:system_file:s0", m.field("selinux", "test").getString("value"));
        assertEquals("hex:00ff01", m.field("xattrs", "test").getJSONObject("value").getString("security.capability"));
        assertTrue(m.bytesRead > 0 && m.bytesRead <= 4096);
    }
    @Test public void missingEnumerationDoesNotInventEmptyXattrs() throws Exception {
        Reader reader = new Reader(); reader.noEnumeration = true;
        ExtendedMetadata m = ExtendedMetadata.read(reader, 4096);
        assertEquals("OBSERVED", m.field("selinux", "test").getString("state"));
        JSONObject attrs = m.field("xattrs", "test");
        assertEquals("NOT_CHECKED", attrs.getString("state")); assertFalse(attrs.has("value"));
        assertEquals("XATTR_ENUMERATION_UNAVAILABLE", attrs.getString("reason"));
    }
    @Test public void unsupportedIsNotEmptyAndPermissionFailureIsNotUnsupported() throws Exception {
        for (String code : Arrays.asList("XATTR_UNSUPPORTED", "XATTR_READ_FAILED")) {
            Reader reader = new Reader(); reader.error = code;
            JSONObject label = ExtendedMetadata.read(reader, 4096).field("selinux", "test");
            assertEquals(code.equals("XATTR_UNSUPPORTED") ? "NOT_CHECKED" : "READ_FAILED", label.getString("state"));
            assertFalse(label.has("value"));
        }
    }
    @Test public void absentMalformedAndUnterminatedLabelsRemainUnknown() throws Exception {
        for (byte[] bytes : Arrays.asList(null, new byte[]{-1, 0}, new byte[]{'a'}, new byte[]{'a', 0, 'b', 0})) {
            Reader reader = new Reader(); reader.values.put("security.selinux", bytes);
            assertEquals("READ_FAILED", ExtendedMetadata.read(reader, 4096).field("selinux", "test").getString("state"));
        }
    }
    @Test public void enumerationChangesAndLimitsAreNotObserved() throws Exception {
        Reader reader = new Reader(); reader.changingNames = true;
        assertEquals("UNSTABLE", ExtendedMetadata.read(reader, 4096).field("xattrs", "test").getString("state"));
        reader = new Reader(); reader.values.put("user.large", new byte[1025]);
        assertEquals("NOT_CHECKED", ExtendedMetadata.read(reader, 4096).field("xattrs", "test").getString("state"));
        assertEquals("NOT_CHECKED", ExtendedMetadata.read(new Reader(), 0).field("xattrs", "test").getString("state"));
    }

    private static final class MetadataAccess implements CollectionAccess {
        final Access delegate; final Reader reader; int metadataReads; boolean change;
        MetadataAccess(Access delegate, Reader reader) { this.delegate = delegate; this.reader = reader; }
        public Stat lstat(String p) throws IOException { return delegate.lstat(p); }
        public String readLink(String p) throws IOException { return delegate.readLink(p); }
        public Handle openRegular(String p, Stat s) throws IOException { return delegate.openRegular(p, s); }
        public Listing list(String p, Stat s, int n, long b, long t) throws IOException { return delegate.list(p, s, n, b, t); }
        public ExtendedMetadata readMetadata(String p, Stat s, long b, long t) throws IOException {
            metadataReads++;
            if (change && metadataReads == 2) reader.values.put("user.value", new byte[]{2});
            return ExtendedMetadata.read(reader, b);
        }
    }
    @Test public void collectorRecordsMetadataAndRemovesOnlyProvenGaps() throws Exception {
        Clock c = new Clock(); Access base = new Access(c).add("/system/a", "file", "abc");
        MetadataAccess a = new MetadataAccess(base, new Reader());
        ManifestCollector.Result result = new ManifestCollector(a, c).collect(identity(), "/system/a", limits(2, 10000, 0));
        JSONObject manifest = result.manifest().toJson();
        assertEquals("OBSERVED", field(manifest, "/system/a", "selinux").getString("state"));
        assertEquals("OBSERVED", field(manifest, "/system/a", "xattrs").getString("state"));
        assertEquals(3, result.index().getJSONArray("metadataNotCollected").length());
        assertEquals(2, base.opens); assertEquals(2, a.metadataReads);
    }
    @Test public void changingMetadataKeepsContentHashButBlocksCompleteness() throws Exception {
        Clock c = new Clock(); Access base = new Access(c).add("/system/a", "file", "abc");
        Reader r = new Reader(); r.values.put("user.value", new byte[]{1});
        MetadataAccess a = new MetadataAccess(base, r); a.change = true;
        JSONObject manifest = new ManifestCollector(a, c).collect(identity(), "/system/a", limits(2, 10000, 0)).manifest().toJson();
        assertEquals("PARTIAL", manifest.getString("completeness"));
        assertEquals("UNSTABLE", field(manifest, "/system/a", "xattrs").getString("state"));
        assertEquals("OBSERVED", field(manifest, "/system/a", "sha256").getString("state"));
    }
    @Test public void legacyAccessStillReportsUnknownWithoutAdditionalByteCharges() throws Exception {
        Clock c = new Clock(); Access a = new Access(c).add("/system/a", "file", "abc");
        ManifestCollector.Result result = new ManifestCollector(a, c).collect(identity(), "/system/a", limits(2, 6, 0));
        assertEquals(6, result.index().getLong("readBytes"));
        assertEquals("NOT_CHECKED", field(result.manifest().toJson(), "/system/a", "xattrs").getString("state"));
    }
}
