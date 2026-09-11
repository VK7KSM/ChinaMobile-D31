package net.elfradio.d31bootstrap.diagnostics.collection;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class CollectionFixture {
    static final class Clock implements CollectionAccess.Clock {
        long elapsed = 100;
        @Override public long wallTimeMillis() { return 10000 + elapsed; }
        @Override public long elapsedRealtimeMillis() { return elapsed; }
    }
    static final class Node {
        String type;
        byte[] bytes = new byte[0];
        long inode, revision;
        String target = "/outside";
        Node(String type, long inode, byte[] bytes) { this.type = type; this.inode = inode; this.bytes = bytes; }
        CollectionAccess.Stat stat() {
            return new CollectionAccess.Stat(type, 1, inode, bytes.length, revision, revision, 0644, 0, 0);
        }
    }
    static final class Access implements CollectionAccess {
        final Map<String, Node> nodes = new LinkedHashMap<String, Node>();
        final CollectionFixture.Clock clock;
        int opens, closes, stats, lists, reads;
        String failingStat, failingList;
        long statCost, readCost, listCharge;
        int chunk = 8192, failAfter = Integer.MAX_VALUE;
        Runnable onRead, onList;
        byte[] secondOpenBytes;
        List<String> injectedNames;
        Access(CollectionFixture.Clock clock) { this.clock = clock; }
        Access add(String path, String type, String content) throws Exception {
            nodes.put(path, new Node(type, nodes.size() + 1, content.getBytes("UTF-8"))); return this;
        }
        @Override public Stat lstat(String path) throws IOException {
            stats++; clock.elapsed += statCost;
            if (path.equals(failingStat)) throw new Failure("ACCESS_DENIED");
            Node node = nodes.get(path);
            if (node == null) throw new Failure("NOT_FOUND");
            return node.stat();
        }
        @Override public String readLink(String path) { return nodes.get(path).target; }
        @Override public Handle openRegular(String path, Stat expected) throws IOException {
            final Node node = nodes.get(path);
            if (node == null || !node.type.equals("file")) throw new Failure("LINK_REFUSED");
            opens++;
            if (opens == 2 && secondOpenBytes != null) node.bytes = secondOpenBytes;
            final byte[] bytes = node.bytes.clone();
            return new Handle() {
                int position;
                @Override public Stat stat() { return node.stat(); }
                @Override public int read(byte[] into, int offset, int requested) throws IOException {
                    reads++;
                    if (position >= failAfter) throw new IOException("此错误原文不得进入索引");
                    if (position == bytes.length) return -1;
                    int count = Math.min(Math.min(requested, chunk), bytes.length - position);
                    System.arraycopy(bytes, position, into, offset, count); position += count;
                    clock.elapsed += readCost;
                    if (onRead != null) onRead.run();
                    return count;
                }
                @Override public void close() { closes++; }
            };
        }
        @Override public Listing list(String path, Stat expected, int maximumNames, long maximumBytes, long timeoutMs) throws IOException {
            lists++;
            if (path.equals(failingList)) throw new Failure("ACCESS_DENIED");
            if (listCharge > maximumBytes) return new Listing(new ArrayList<String>(), false, "BYTE_LIMIT", maximumBytes);
            TreeSet<String> names = new TreeSet<String>();
            String prefix = path.equals("/") ? "/" : path + "/";
            for (String candidate : nodes.keySet()) {
                if (candidate.startsWith(prefix)) {
                    String tail = candidate.substring(prefix.length());
                    if (!tail.isEmpty() && !tail.contains("/")) names.add(tail);
                }
            }
            List<String> all = injectedNames == null ? new ArrayList<String>(names) : injectedNames;
            List<String> returned = all.subList(0, Math.min(all.size(), maximumNames));
            if (onList != null) onList.run();
            return new Listing(returned, all.size() <= maximumNames, "ENTRY_LIMIT", listCharge);
        }
    }

    static JSONObject identity() throws Exception {
        return new JSONObject().put("snapshotId", "fixture-target").put("baselineId", "fixture-baseline")
                .put("baselineRevision", "1").put("firmwareId", "fixture-firmware").put("build", "fixture-build")
                .put("context", new JSONObject().put("model", "D31").put("hardwareClass", "fixture")
                        .put("firmwareFamily", "fixture").put("stage", "initialized").put("network", "offline")
                        .put("sim", "absent").put("storage", "internal"));
    }
    static CollectionLimits limits(int count, long bytes, int depth) {
        return new CollectionLimits(count, bytes, bytes, 1000, depth, 8 * 1024 * 1024 - 65536, 1000);
    }
    static String hash(String text) throws Exception { return CollectionSupport.hex(CollectionSupport.digest().digest(text.getBytes("UTF-8"))); }
    static JSONObject entry(JSONObject manifest, String path) throws Exception {
        for (int i = 0; i < manifest.getJSONArray("entries").length(); i++) {
            JSONObject entry = manifest.getJSONArray("entries").getJSONObject(i);
            if (entry.getString("path").equals(path)) return entry;
        }
        throw new AssertionError("路径未列入结果");
    }
    static JSONObject field(JSONObject manifest, String path, String name) throws Exception {
        return entry(manifest, path).getJSONObject("fields").getJSONObject(name);
    }
}
