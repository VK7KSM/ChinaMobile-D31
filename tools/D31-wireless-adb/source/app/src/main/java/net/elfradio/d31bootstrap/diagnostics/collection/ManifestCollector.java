package net.elfradio.d31bootstrap.diagnostics.collection;

import net.elfradio.d31bootstrap.diagnostics.DiagnosticManifest;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static net.elfradio.d31bootstrap.diagnostics.collection.CollectionSupport.*;

/** 按需采集单个明确范围；只证明本轮有界观察，不证明原子快照或全系统一致。 */
public final class ManifestCollector {
    public static final String VERSION = "d31-collection-1";
    private final CollectionAccess access;
    private final CollectionAccess.Clock clock;

    public ManifestCollector(CollectionAccess access, CollectionAccess.Clock clock) {
        if (access == null || clock == null) throw new IllegalArgumentException("MISSING_ACCESS");
        this.access = access; this.clock = clock;
    }

    public static final class Result {
        private final DiagnosticManifest manifest;
        private final JSONObject index;
        Result(DiagnosticManifest manifest, JSONObject index) { this.manifest = manifest; this.index = index; }
        public DiagnosticManifest manifest() { return manifest; }
        public JSONObject index() throws JSONException { return new JSONObject(index.toString()); }
    }

    /** identity只包含snapshotId、baselineId、baselineRevision、firmwareId、build、context。 */
    public Result collect(JSONObject identity, String absoluteScope, CollectionLimits limits) throws JSONException {
        if (identity == null || limits == null) throw new IllegalArgumentException("MISSING_REQUEST");
        path(absoluteScope);
        Set<String> keys = new TreeSet<String>(Arrays.asList("snapshotId", "baselineId", "baselineRevision", "firmwareId", "build", "context"));
        Iterator<String> names = identity.keys();
        while (names.hasNext()) if (!keys.contains(names.next())) throw new IllegalArgumentException("UNKNOWN_IDENTITY_FIELD");
        long wall = clock.wallTimeMillis(), uptime = clock.elapsedRealtimeMillis();
        if (wall < 0 || wall > Long.MAX_VALUE - limits.validForMs || uptime < 0) throw new IllegalArgumentException("INVALID_CLOCK");
        JSONObject base = new JSONObject().put("schemaVersion", 1).put("role", "TARGET").put("collectorVersion", VERSION)
                .put("capturedAtMs", wall).put("validUntilMs", wall + limits.validForMs).put("uptimeMs", uptime)
                .put("completeness", "PARTIAL").put("entries", new JSONArray());
        for (String key : keys) base.put(key, identity.get(key));
        String source = id(base.getString("snapshotId"));
        base.put("scope", new JSONArray().put(new JSONObject().put("path", absoluteScope).put("state", "NOT_CHECKED")
                .put("source", source).put("reason", "COLLECTION_NOT_STARTED")));
        base = DiagnosticManifest.parse(base).toJson();
        if (base.toString().length() > limits.maxManifestChars / 2) throw new IllegalArgumentException("IDENTITY_TOO_LARGE");
        Session session = new Session(base, absoluteScope, source, limits);
        session.walk(absoluteScope, 0);
        JSONObject scope = base.getJSONArray("scope").getJSONObject(0);
        boolean complete = session.reasons.isEmpty();
        scope.put("state", complete ? "COMPLETE" : "PARTIAL");
        if (complete) scope.remove("reason"); else scope.put("reason", join(session.reasons));
        base.put("completeness", complete ? "COMPLETE" : "PARTIAL").put("entries", session.entries);
        JSONObject index = new JSONObject().put("schemaVersion", 1).put("collectorVersion", VERSION).put("snapshotId", source)
                .put("scope", absoluteScope).put("state", complete ? "COMPLETE" : "PARTIAL")
                .put("reasons", new JSONArray(session.reasons)).put("entries", session.entries.length())
                .put("readBytes", session.budget.readBytes).put("elapsedMs", session.budget.elapsed())
                .put("maxEntries", limits.maxEntries).put("maxDepth", limits.maxDepth)
                .put("maxReadBytes", limits.maxReadBytes).put("maxFileBytes", limits.maxFileBytes)
                .put("durationMs", limits.durationMs).put("scopeSemantics", "SINGLE_INDEPENDENT_RANGE")
                .put("metadataNotCollected", new JSONArray(Arrays.asList("selinux", "xattrs", "activeSource", "mountSource", "activation")))
                .put("atomicSnapshot", false).put("aggregationStatus", "NOT_IMPLEMENTED").put("systemConsistency", "NOT_ASSESSED");
        return new Result(DiagnosticManifest.parse(base), index);
    }

    /** 仅验证分批范围不重叠，不生成聚合完成结论。 */
    public static void validateIndependentScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty() || scopes.size() > 32) throw new IllegalArgumentException("INVALID_SCOPE_SET");
        TreeSet<String> previous = new TreeSet<String>();
        for (String scope : scopes) {
            path(scope);
            for (String other : previous) if (scope.equals(other) || scope.equals("/") || other.equals("/")
                    || scope.startsWith(other + "/") || other.startsWith(scope + "/")) throw new IllegalArgumentException("OVERLAPPING_SCOPE");
            previous.add(scope);
        }
    }

    private final class Session {
        final JSONArray entries = new JSONArray();
        final TreeSet<String> reasons = new TreeSet<String>();
        final CollectionLimits limits;
        final Budget budget;
        final String source;
        int outputChars;
        boolean stopped;

        Session(JSONObject base, String scope, String source, CollectionLimits limits) {
            this.source = source; this.limits = limits;
            budget = new Budget(clock, limits.durationMs, limits.maxReadBytes);
            outputChars = base.toString().length() + 8192 + limits.maxDepth * 512;
        }

        void issue(String code) {
            if (reasons.size() < 24) reasons.add(code); else reasons.add("ADDITIONAL_FAILURES");
            if (code.equals("TIME_LIMIT") || code.equals("CANCELLED") || code.equals("CLOCK_ROLLBACK")
                    || code.equals("ENTRY_LIMIT") || code.equals("MANIFEST_LIMIT")) stopped = true;
        }

        void walk(String file, int depth) throws JSONException {
            if (stopped) return;
            if (entries.length() >= limits.maxEntries) { issue("ENTRY_LIMIT"); return; }
            // 每条预留上界，防止加入最后一条后才发现已超出清单内存预算。
            if (outputChars + file.length() * 3 + 6000 > limits.maxManifestChars) { issue("MANIFEST_LIMIT"); return; }
            JSONObject entry = new JSONObject().put("path", file).put("fields", new JSONObject());
            JSONObject fields = entry.getJSONObject("fields");
            CollectionAccess.Stat before;
            try {
                budget.remainingMs();
                before = access.lstat(file);
                if (before == null) throw new CollectionAccess.Failure("ACCESS_CONTRACT");
                budget.remainingMs();
            } catch (IOException failure) {
                String code = error(failure);
                entry.put("presence", missing(isBudget(code) ? "NOT_CHECKED" : "READ_FAILED", code, source));
                add(entry); issue(code); return;
            }
            entry.put("presence", observed("PRESENT", source));
            String[] unknown = {"sha256", "link", "selinux", "xattrs", "activeSource", "mountSource", "activation"};
            for (String name : unknown) fields.put(name, missing("NOT_CHECKED", "EVIDENCE_NOT_COLLECTED", source));
            fields.put("type", before.type.equals("unsupported") ? missing("NOT_CHECKED", "UNSUPPORTED_FILE_TYPE", source) : observed(before.type, source))
                    .put("mode", observed(String.format(Locale.US, "%04o", before.mode), source))
                    .put("uid", observed(before.uid, source)).put("gid", observed(before.gid, source));
            int oldLength = entry.toString().length();
            add(entry);
            try {
                if (before.type.equals("file")) {
                    fields.put("link", missing("NOT_APPLICABLE", "REGULAR_FILE", source));
                    if (before.size > limits.maxFileBytes) throw new CollectionAccess.Failure("FILE_BYTE_LIMIT");
                    if (before.size > (limits.maxReadBytes - budget.readBytes) / 2) throw new CollectionAccess.Failure("BYTE_LIMIT");
                    String first = hash(file, before), second = hash(file, before);
                    if (!first.equals(second) || !before.same(access.lstat(file))) throw new CollectionAccess.Failure("UNSTABLE_FILE");
                    budget.remainingMs();
                    fields.put("sha256", observed(first, source));
                } else if (before.type.equals("symlink")) {
                    fields.put("sha256", missing("NOT_APPLICABLE", "SYMLINK_NOT_FOLLOWED", source));
                    budget.remainingMs();
                    String target = access.readLink(file);
                    if (target == null || target.length() == 0 || target.length() > 4096 || hasControl(target)) throw new CollectionAccess.Failure("UNREPRESENTABLE_LINK");
                    if (!before.same(access.lstat(file))) throw new CollectionAccess.Failure("UNSTABLE_FILE");
                    budget.remainingMs();
                    fields.put("link", observed(target, source));
                } else if (before.type.equals("directory")) {
                    fields.put("link", missing("NOT_APPLICABLE", "DIRECTORY", source));
                    fields.put("sha256", missing("NOT_APPLICABLE", "DIRECTORY_CONTENT_NOT_FILE_BYTES", source));
                    int remaining = depth >= limits.maxDepth ? 0 : limits.maxEntries - entries.length();
                    CollectionAccess.Listing listing = list(file, before, remaining);
                    TreeSet<String> children = validateListing(file, listing, remaining);
                    for (String name : children) walk(child(file, name), depth + 1);
                    if (!listing.complete) {
                        String code = depth >= limits.maxDepth ? "DEPTH_LIMIT" : listingReason(listing);
                        issue(code);
                        fields.put("semantic.enumeration", missing(code.startsWith("UNSTABLE") ? "UNSTABLE"
                                : isBudget(code) ? "NOT_CHECKED" : "READ_FAILED", code, source));
                    } else if (!stopped) {
                        CollectionAccess.Listing after = list(file, before, remaining);
                        TreeSet<String> afterNames = validateListing(file, after, remaining);
                        if (!after.complete) throw new CollectionAccess.Failure(listingReason(after));
                        if (!children.equals(afterNames) || !before.same(access.lstat(file))) {
                            throw new CollectionAccess.Failure("UNSTABLE_DIRECTORY");
                        }
                        budget.remainingMs();
                    }
                } else {
                    throw new CollectionAccess.Failure("UNSUPPORTED_FILE_TYPE");
                }
            } catch (IOException failure) {
                String code = error(failure);
                issue(code);
                String state = code.startsWith("UNSTABLE") ? "UNSTABLE" : isBudget(code) || code.equals("UNSUPPORTED_FILE_TYPE") ? "NOT_CHECKED" : "READ_FAILED";
                if (before.type.equals("file")) fields.put("sha256", missing(state, code, source));
                else if (before.type.equals("symlink")) fields.put("link", missing(state, code, source));
                else fields.put("semantic.enumeration", missing(state, code, source));
                if (state.equals("UNSTABLE")) {
                    for (String name : new String[]{"type", "mode", "uid", "gid"}) fields.put(name, missing("UNSTABLE", code, source));
                }
            }
            outputChars += Math.max(0, entry.toString().length() - oldLength);
        }

        void add(JSONObject entry) { entries.put(entry); outputChars += entry.toString().length() + 1; }

        CollectionAccess.Listing list(String file, CollectionAccess.Stat stat, int count) throws IOException {
            CollectionAccess.Listing result = access.list(file, stat, count, budget.maximum - budget.readBytes, budget.remainingMs());
            if (result == null || result.names.size() > count || result.bytesRead < 0 || result.bytesRead > budget.maximum - budget.readBytes) {
                throw new CollectionAccess.Failure("ACCESS_CONTRACT");
            }
            budget.readBytes += result.bytesRead;
            budget.remainingMs();
            return result;
        }

        TreeSet<String> validateListing(String file, CollectionAccess.Listing result, int count) throws IOException {
            TreeSet<String> sorted = new TreeSet<String>();
            for (String name : result.names) {
                try { child(file, name); }
                catch (IllegalArgumentException invalid) { throw new CollectionAccess.Failure("UNREPRESENTABLE_CHILD"); }
                if (!sorted.add(name)) throw new CollectionAccess.Failure("DUPLICATE_CHILD");
            }
            return sorted;
        }

        String hash(String file, CollectionAccess.Stat before) throws IOException {
            budget.requireBytes(before.size);
            MessageDigest digest = digest();
            byte[] buffer = new byte[8192];
            try (CollectionAccess.Handle handle = access.openRegular(file, before)) {
                if (!before.same(handle.stat())) throw new CollectionAccess.Failure("UNSTABLE_FILE");
                long remaining = before.size;
                while (remaining > 0) {
                    budget.remainingMs();
                    int requested = (int) Math.min(buffer.length, remaining);
                    int read = handle.read(buffer, 0, requested);
                    if (read < 0) throw new CollectionAccess.Failure("UNSTABLE_FILE");
                    if (read == 0 || read > requested) throw new CollectionAccess.Failure("ACCESS_CONTRACT");
                    budget.readBytes += read; remaining -= read;
                    digest.update(buffer, 0, read);
                    budget.remainingMs();
                }
                if (!before.same(handle.stat())) throw new CollectionAccess.Failure("UNSTABLE_FILE");
            }
            if (!before.same(access.lstat(file))) throw new CollectionAccess.Failure("UNSTABLE_FILE");
            return hex(digest.digest());
        }
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }
    private static boolean isBudget(String code) {
        return code.endsWith("LIMIT") || code.equals("CANCELLED") || code.equals("CLOCK_ROLLBACK");
    }
    private static String listingReason(CollectionAccess.Listing listing) {
        return listing.reason != null && listing.reason.matches("[A-Z_]{1,64}") ? listing.reason : "INCOMPLETE_ENUMERATION";
    }
    private static String join(Set<String> reasons) {
        StringBuilder value = new StringBuilder();
        for (String reason : reasons) { if (value.length() > 0) value.append(','); value.append(reason); }
        return value.toString();
    }
}
