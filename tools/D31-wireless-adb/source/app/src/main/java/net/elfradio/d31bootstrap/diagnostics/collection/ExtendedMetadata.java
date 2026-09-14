package net.elfradio.d31bootstrap.diagnostics.collection;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** 有界只读扩展元数据；属性值使用hex:前缀，不按文本猜测二进制内容。 */
public final class ExtendedMetadata {
    public interface Reader {
        byte[] get(String name, int maximumBytes) throws IOException;
        List<String> names() throws IOException;
    }
    private final String labelState, labelReason, label, attrsState, attrsReason;
    private final Map<String, String> attrs;
    public final long bytesRead;
    private ExtendedMetadata(String ls, String lr, String label, String as, String ar, Map<String, String> attrs, long bytes) {
        labelState = ls; labelReason = lr; this.label = label; attrsState = as; attrsReason = ar;
        this.attrs = Collections.unmodifiableMap(new TreeMap<String, String>(attrs)); bytesRead = bytes;
    }
    public static ExtendedMetadata unavailable(String reason) {
        return new ExtendedMetadata("NOT_CHECKED", reason, null, "NOT_CHECKED", reason, Collections.<String, String>emptyMap(), 0);
    }

    public static ExtendedMetadata read(Reader reader, long maximumBytes) {
        Counter counter = new Counter(maximumBytes);
        String ls = "OBSERVED", lr = null, label = null, as = "OBSERVED", ar = null;
        Map<String, String> attrs = new TreeMap<String, String>();
        try {
            byte[] bytes = get(reader, "security.selinux", counter);
            if (bytes == null || bytes.length < 2 || bytes[bytes.length - 1] != 0) throw new CollectionAccess.Failure("SELINUX_LABEL_UNCONFIRMED");
            label = Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, bytes.length - 1)).toString();
            if (label.isEmpty() || label.matches(".*[\\p{Cntrl}].*")) throw new CollectionAccess.Failure("SELINUX_LABEL_INVALID");
        } catch (IOException failure) { lr = CollectionSupport.error(failure); ls = state(lr); }
        try {
            List<String> before = names(reader, counter); long values = 0;
            for (String name : before) {
                byte[] bytes = get(reader, name, counter);
                if (bytes == null) throw new CollectionAccess.Failure("UNSTABLE_XATTRS");
                values += bytes.length;
                if (values > 1024) throw new CollectionAccess.Failure("XATTR_VALUE_LIMIT");
                attrs.put(name, "hex:" + CollectionSupport.hex(bytes));
            }
            if (!before.equals(names(reader, counter))) throw new CollectionAccess.Failure("UNSTABLE_XATTRS");
        } catch (IOException failure) { ar = CollectionSupport.error(failure); as = state(ar); attrs.clear(); }
        return new ExtendedMetadata(ls, lr, label, as, ar, attrs, counter.used);
    }

    private static String state(String code) {
        return code.startsWith("UNSTABLE") ? "UNSTABLE"
                : code.endsWith("UNAVAILABLE") || code.endsWith("UNSUPPORTED") || code.endsWith("LIMIT") ? "NOT_CHECKED" : "READ_FAILED";
    }
    private static byte[] get(Reader reader, String name, Counter count) throws IOException {
        int allowed = (int) Math.min(1024, count.remaining());
        if (allowed <= 0) throw new CollectionAccess.Failure("BYTE_LIMIT");
        byte[] bytes = reader.get(name, allowed);
        if (bytes != null) {
            if (bytes.length > allowed) throw new CollectionAccess.Failure("XATTR_VALUE_LIMIT");
            count.add(bytes.length);
        }
        return bytes;
    }
    private static List<String> names(Reader reader, Counter count) throws IOException {
        if (count.remaining() < 1024) throw new CollectionAccess.Failure("BYTE_LIMIT");
        List<String> names = reader.names();
        if (names == null || names.size() > 32) throw new CollectionAccess.Failure("XATTR_NAME_LIMIT");
        TreeSet<String> sorted = new TreeSet<String>(); long bytes = 0;
        for (String name : names) {
            if (name == null || name.isEmpty() || name.length() > 255 || name.matches(".*[\\p{Cntrl}].*") || !sorted.add(name))
                throw new CollectionAccess.Failure("XATTR_NAMES_INVALID");
            bytes += name.getBytes("UTF-8").length + 1;
        }
        if (bytes > 1024) throw new CollectionAccess.Failure("XATTR_NAME_LIMIT");
        count.add(bytes); return new ArrayList<String>(sorted);
    }
    private static final class Counter {
        final long maximum; long used;
        Counter(long max) { maximum = Math.max(0, max); }
        long remaining() { return maximum - used; }
        void add(long bytes) throws IOException {
            if (bytes < 0 || bytes > remaining()) throw new CollectionAccess.Failure("BYTE_LIMIT"); used += bytes;
        }
    }

    JSONObject field(String name, String source) throws org.json.JSONException {
        String state = name.equals("selinux") ? labelState : attrsState;
        String reason = name.equals("selinux") ? labelReason : attrsReason;
        return state.equals("OBSERVED") ? CollectionSupport.observed(name.equals("selinux") ? label : new JSONObject(attrs), source)
                : CollectionSupport.missing(state, reason, source);
    }
    boolean sameField(ExtendedMetadata other, String name) {
        if (name.equals("selinux")) return Objects.equals(labelState, other.labelState) && Objects.equals(labelReason, other.labelReason)
                && Objects.equals(label, other.label);
        return Objects.equals(attrsState, other.attrsState) && Objects.equals(attrsReason, other.attrsReason) && attrs.equals(other.attrs);
    }
}
