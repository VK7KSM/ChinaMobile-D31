package net.elfradio.d31bootstrap.diagnostics.collection;

import net.elfradio.d31bootstrap.diagnostics.DiagnosticManifest;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
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
    public static final String VERSION = "d31-collection-4";
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
                .put("metadataNotCollected", metadataGaps(session.entries))
                .put("atomicSnapshot", false).put("aggregationStatus", "NOT_IMPLEMENTED").put("systemConsistency", "NOT_ASSESSED");
        return new Result(DiagnosticManifest.parse(base), index);
    }

    private static JSONArray metadataGaps(JSONArray entries) throws JSONException {
        JSONArray gaps = new JSONArray();
        for (String name : Arrays.asList("selinux", "xattrs", "activeSource", "mountSource", "activation")) {
            boolean all = entries.length() > 0;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i).getJSONObject("fields").optJSONObject(name);
                all &= e != null && e.optString("state").equals("OBSERVED");
            }
            if (!all) gaps.put(name);
        }
        return gaps;
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
        final String scope;
        int outputChars;
        boolean stopped;

        Session(JSONObject base, String scope, String source, CollectionLimits limits) {
            this.source = source; this.scope = scope; this.limits = limits;
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
            if (file.equals(SystemSupportConfiguration.PATH))
                fields.put(SystemSupportConfiguration.FIELD, missing("NOT_CHECKED", "REGULAR_SCRIPT_NOT_CONFIRMED", source));
            String switchField = ConfigurationSwitches.field(file);
            if (switchField != null)
                fields.put(switchField, missing("NOT_CHECKED", "REGULAR_SCRIPT_NOT_CONFIRMED", source));
            fields.put("type", before.type.equals("unsupported") ? missing("NOT_CHECKED", "UNSUPPORTED_FILE_TYPE", source) : observed(before.type, source))
                    .put("mode", observed(String.format(Locale.US, "%04o", before.mode), source))
                    .put("uid", observed(before.uid, source)).put("gid", observed(before.gid, source));
            int oldLength = entry.toString().length();
            add(entry);
            try {
                budget.remainingMs();
                ExtendedMetadata firstMetadata = metadata(file, before);
                if (before.type.equals("file")) {
                    fields.put("link", missing("NOT_APPLICABLE", "REGULAR_FILE", source));
                    if (before.size > limits.maxFileBytes) throw new CollectionAccess.Failure("FILE_BYTE_LIMIT");
                    if (before.size > (limits.maxReadBytes - budget.readBytes) / 2) throw new CollectionAccess.Failure("BYTE_LIMIT");
                    ByteArrayOutputStream configuration = switchField != null
                            && before.size <= SystemSupportConfiguration.MAX_BYTES ? new ByteArrayOutputStream() : null;
                    String first = hash(file, before, configuration), second = hash(file, before, null);
                    if (!first.equals(second) || !before.same(access.lstat(file))) throw new CollectionAccess.Failure("UNSTABLE_FILE");
                    budget.remainingMs();
                    fields.put("sha256", observed(first, source));
                    if (file.equals(SystemSupportConfiguration.PATH))
                        fields.put(SystemSupportConfiguration.FIELD, SystemSupportConfiguration.evidence(
                                configuration == null ? null : configuration.toByteArray(), source));
                    if (switchField != null)
                        fields.put(switchField, switchEvidence(file, before,
                                configuration == null ? null : configuration.toByteArray()));
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
                ExtendedMetadata lastMetadata = metadata(file, before);
                if (!before.same(access.lstat(file))) throw new CollectionAccess.Failure("UNSTABLE_METADATA");
                budget.remainingMs();
                for (String name : Arrays.asList("selinux", "xattrs")) {
                    JSONObject evidence = firstMetadata.sameField(lastMetadata, name) ? lastMetadata.field(name, source)
                            : missing("UNSTABLE", "UNSTABLE_METADATA", source);
                    fields.put(name, evidence);
                    if (evidence.getString("state").equals("READ_FAILED")) issue("METADATA_READ_FAILED");
                    if (evidence.getString("state").equals("UNSTABLE")) issue("UNSTABLE_METADATA");
                }
            } catch (IOException failure) {
                String code = error(failure);
                issue(code);
                String state = code.startsWith("UNSTABLE") ? "UNSTABLE" : isBudget(code) || code.equals("UNSUPPORTED_FILE_TYPE") ? "NOT_CHECKED" : "READ_FAILED";
                if (before.type.equals("file")) fields.put("sha256", missing(state, code, source));
                else if (before.type.equals("symlink")) fields.put("link", missing(state, code, source));
                else fields.put("semantic.enumeration", missing(state, code, source));
                if (file.equals(SystemSupportConfiguration.PATH))
                    fields.put(SystemSupportConfiguration.FIELD, missing(state, code, source));
                if (switchField != null) fields.put(switchField, missing(state, code, source));
                if (state.equals("UNSTABLE")) {
                    for (String name : new String[]{"type", "mode", "uid", "gid", "selinux", "xattrs"}) fields.put(name, missing("UNSTABLE", code, source));
                }
            }
            outputChars += Math.max(0, entry.toString().length() - oldLength);
        }

        ExtendedMetadata metadata(String path, CollectionAccess.Stat stat) throws IOException {
            long remaining = limits.maxReadBytes - budget.readBytes;
            ExtendedMetadata result = access.readMetadata(path, stat, remaining, budget.remainingMs());
            if (result == null || result.bytesRead < 0 || result.bytesRead > remaining) throw new CollectionAccess.Failure("METADATA_CONTRACT");
            budget.readBytes += result.bytesRead; budget.remainingMs(); return result;
        }

        void add(JSONObject entry) { entries.put(entry); outputChars += entry.toString().length() + 1; }

        JSONObject switchEvidence(String script, CollectionAccess.Stat expected, byte[] bytes) throws JSONException {
            String marker = ConfigurationSwitches.marker(script, bytes);
            if (marker == null) return ConfigurationSwitches.evidence(script, bytes, "NOT_CHECKED", source);
            if (!(scope.equals("/") || marker.equals(scope) || marker.startsWith(scope + "/")))
                return missing("NOT_CHECKED", "SWITCH_MARKER_OUTSIDE_SCOPE", source);
            try {
                budget.remainingMs();
                String parent = marker.substring(0, marker.lastIndexOf('/'));
                CollectionAccess.Stat directory = access.lstat(parent);
                if (directory == null || !"directory".equals(directory.type))
                    throw new CollectionAccess.Failure("DIRECTORY_REQUIRED");
                String consumer = ConfigurationSwitches.consumer(script), consumerHash = null;
                CollectionAccess.Stat consumerStat = null;
                if (consumer != null) {
                    if (!(scope.equals("/") || consumer.equals(scope) || consumer.startsWith(scope + "/")))
                        return missing("NOT_CHECKED", "SWITCH_CONSUMER_OUTSIDE_SCOPE", source);
                    budget.remainingMs();
                    consumerStat = access.lstat(consumer);
                    if (consumerStat == null) throw new CollectionAccess.Failure("ACCESS_CONTRACT");
                    if (!"file".equals(consumerStat.type))
                        return missing("NOT_CHECKED", "SWITCH_CONSUMER_NOT_REGULAR", source);
                    if (consumerStat.size > limits.maxFileBytes) throw new CollectionAccess.Failure("FILE_BYTE_LIMIT");
                    if (consumerStat.size > (limits.maxReadBytes - budget.readBytes) / 2)
                        throw new CollectionAccess.Failure("BYTE_LIMIT");
                    consumerHash = hash(consumer, consumerStat, null);
                    if (!consumerHash.equals(hash(consumer, consumerStat, null)))
                        throw new CollectionAccess.Failure("UNSTABLE_SWITCH_CONSUMER");
                }
                CollectionAccess.Stat first = markerStat(marker), second = markerStat(marker);
                if (first == null ? second != null : !first.same(second))
                    throw new CollectionAccess.Failure("UNSTABLE_SWITCH_MARKER");
                if (!directory.same(access.lstat(parent)) || !expected.same(access.lstat(script)))
                    throw new CollectionAccess.Failure("UNSTABLE_SWITCH_INPUT");
                if (consumerStat != null && !consumerStat.same(access.lstat(consumer)))
                    throw new CollectionAccess.Failure("UNSTABLE_SWITCH_CONSUMER");
                budget.remainingMs();
                return ConfigurationSwitches.evidence(script, bytes, first == null ? "ABSENT" : first.type, source, consumerHash);
            } catch (IOException failure) {
                String code = error(failure);
                issue(code);
                return missing(code.startsWith("UNSTABLE") ? "UNSTABLE" : isBudget(code) ? "NOT_CHECKED" : "READ_FAILED", code, source);
            }
        }

        CollectionAccess.Stat markerStat(String marker) throws IOException {
            budget.remainingMs();
            try {
                CollectionAccess.Stat stat = access.lstat(marker);
                if (stat == null) throw new CollectionAccess.Failure("ACCESS_CONTRACT");
                return stat;
            } catch (CollectionAccess.Failure failure) {
                // 父目录已真实读取；只有叶节点ENOENT可作为不存在，权限/链接错误仍传播。
                if ("NOT_FOUND".equals(failure.code)) return null;
                throw failure;
            }
        }

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

        String hash(String file, CollectionAccess.Stat before, ByteArrayOutputStream configuration) throws IOException {
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
                    if (configuration != null) configuration.write(buffer, 0, read);
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
