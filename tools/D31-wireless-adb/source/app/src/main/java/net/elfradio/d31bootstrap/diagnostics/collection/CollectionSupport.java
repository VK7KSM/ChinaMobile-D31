package net.elfradio.d31bootstrap.diagnostics.collection;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class CollectionSupport {
    private CollectionSupport() { }

    static String path(String path) {
        if (path == null || path.length() == 0 || path.length() > 4096 || !path.startsWith("/")
                || path.contains("//") || path.contains("\\") || (path.length() > 1 && path.endsWith("/"))) {
            throw new IllegalArgumentException("INVALID_PATH");
        }
        for (int i = 0; i < path.length(); i++) if (Character.isISOControl(path.charAt(i))) throw new IllegalArgumentException("INVALID_PATH");
        for (String part : path.split("/")) if (part.equals(".") || part.equals("..")) throw new IllegalArgumentException("INVALID_PATH");
        return path;
    }

    static String child(String parent, String name) {
        if (name == null || name.length() == 0 || name.indexOf('/') >= 0 || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("INVALID_CHILD_NAME");
        }
        return path((parent.equals("/") ? "" : parent) + "/" + name);
    }

    static String id(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")) throw new IllegalArgumentException("INVALID_EVIDENCE_ID");
        return id;
    }

    static JSONObject observed(Object value, String source) throws JSONException {
        return new JSONObject().put("state", "OBSERVED").put("value", value).put("source", source);
    }

    static JSONObject missing(String state, String reason, String source) throws JSONException {
        return new JSONObject().put("state", state).put("reason", reason).put("source", source);
    }

    static String error(IOException failure) {
        if (failure instanceof CollectionAccess.Failure) {
            String code = ((CollectionAccess.Failure) failure).code;
            if (code != null && code.matches("[A-Z_]{1,64}")) return code;
        }
        return "READ_ERROR";
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }

    static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(digits[(b & 255) >>> 4]).append(digits[b & 15]);
        return value.toString();
    }

    static final class Budget {
        final CollectionAccess.Clock clock;
        final long start, duration, maximum;
        long readBytes;
        long last;
        Budget(CollectionAccess.Clock clock, long duration, long maximum) {
            this.clock = clock; this.duration = duration; this.maximum = maximum;
            start = clock.elapsedRealtimeMillis(); last = start;
            if (start < 0) throw new IllegalArgumentException("INVALID_CLOCK");
        }
        long remainingMs() throws CollectionAccess.Failure {
            long now = clock.elapsedRealtimeMillis();
            if (now < last) throw new CollectionAccess.Failure("CLOCK_ROLLBACK");
            last = now;
            if (Thread.currentThread().isInterrupted()) throw new CollectionAccess.Failure("CANCELLED");
            if (now - start >= duration) throw new CollectionAccess.Failure("TIME_LIMIT");
            return duration - (now - start);
        }
        void requireBytes(long count) throws CollectionAccess.Failure {
            remainingMs();
            if (count < 0 || count > maximum - readBytes) throw new CollectionAccess.Failure("BYTE_LIMIT");
        }
        long elapsed() { return Math.max(0, last - start); }
    }
}
