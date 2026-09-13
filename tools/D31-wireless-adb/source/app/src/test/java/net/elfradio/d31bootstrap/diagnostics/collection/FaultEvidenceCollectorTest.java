package net.elfradio.d31bootstrap.diagnostics.collection;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static net.elfradio.d31bootstrap.diagnostics.collection.CollectionFixture.*;
import static org.junit.Assert.*;

public class FaultEvidenceCollectorTest {
    static final class Store implements FaultEvidenceCollector.Store {
        final Map<String, byte[]> bytes = new HashMap<String, byte[]>();
        boolean fail, badReceipt;
        @Override public FaultEvidenceCollector.Receipt storeNew(String id, byte[] value) throws IOException {
            if (bytes.containsKey(id)) throw new CollectionAccess.Failure("ARTIFACT_EXISTS");
            bytes.put(id, value.clone());
            if (fail) throw new IOException("不可公开的错误原文");
            return new FaultEvidenceCollector.Receipt(id + ".bin", badReceipt ? value.length + 1 : value.length,
                    CollectionSupport.hex(CollectionSupport.digest().digest(value)));
        }
    }
    static FaultEvidenceCollector.Source source(String id, String path) { return new FaultEvidenceCollector.Source(id, "LOGCAT", path); }

    @Test public void explicitSourceSavesRawBytesAndSeparateHashIndex() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/evidence/log", "file", "event\n"); Store store = new Store();
        JSONObject index = new FaultEvidenceCollector(access, clock).collect("event-one", Arrays.asList(source("log", "/evidence/log")), limits(1, 20, 0), store);
        JSONObject item = index.getJSONArray("items").getJSONObject(0);
        assertEquals("COMPLETE", index.getString("state"));
        assertArrayEquals("event\n".getBytes("UTF-8"), store.bytes.get("event-one-log"));
        assertEquals(hash("event\n"), item.getString("storedSha256"));
        assertEquals(6, item.getLong("storedBytes")); assertEquals(0, item.getInt("offset"));
        assertFalse(index.toString().contains("event\\n")); assertFalse(index.getBoolean("rawContentInIndex"));
        assertEquals(access.opens, access.closes);
    }

    @Test public void byteCapSavesPrefixWithTruncationNotCompleteHash() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/evidence/log", "file", "abcdef"); Store store = new Store();
        JSONObject index = new FaultEvidenceCollector(access, clock).collect("event-one", Arrays.asList(source("log", "/evidence/log")), limits(1, 3, 0), store);
        JSONObject item = index.getJSONArray("items").getJSONObject(0);
        assertEquals("PARTIAL", index.getString("state")); assertEquals("TRUNCATED", item.getString("state"));
        assertEquals(hash("abc"), item.getString("storedSha256")); assertEquals(3, index.getLong("readBytes"));
        assertArrayEquals("abc".getBytes("UTF-8"), store.bytes.get("event-one-log"));
    }

    @Test public void failureAfterFirstChunkPersistsAcquiredPrefixWithoutErrorText() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/evidence/log", "file", "abcdef");
        access.chunk = 3; access.failAfter = 3; Store store = new Store();
        JSONObject index = new FaultEvidenceCollector(access, clock).collect("event-one", Arrays.asList(source("log", "/evidence/log")), limits(1, 20, 0), store);
        JSONObject item = index.getJSONArray("items").getJSONObject(0);
        assertEquals("READ_FAILED", item.getString("state")); assertEquals("READ_ERROR", item.getString("reason"));
        assertEquals(hash("abc"), item.getString("storedSha256")); assertEquals(1, access.closes);
        assertFalse(index.toString().contains("此错误原文"));
    }

    @Test public void timeoutRetainsPartialBytesAndSkipsNextSource() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/evidence/a", "file", "abcdef").add("/evidence/b", "file", "next");
        access.chunk = 3; access.readCost = 1000; Store store = new Store();
        JSONObject index = new FaultEvidenceCollector(access, clock).collect("event-one", Arrays.asList(source("a", "/evidence/a"), source("b", "/evidence/b")), limits(2, 20, 0), store);
        assertEquals("TRUNCATED", index.getJSONArray("items").getJSONObject(0).getString("state"));
        assertEquals("NOT_CHECKED", index.getJSONArray("items").getJSONObject(1).getString("state"));
        assertEquals(1, access.opens); assertEquals(1, store.bytes.size()); assertFalse(index.getBoolean("withinReadDeadline"));
    }

    @Test public void mutationKeepsBytesButLabelsThemUnstable() throws Exception {
        Clock clock = new Clock(); final Access access = new Access(clock).add("/evidence/log", "file", "abc");
        access.onRead = new Runnable() { public void run() { access.nodes.get("/evidence/log").revision++; } };
        Store store = new Store();
        JSONObject index = new FaultEvidenceCollector(access, clock).collect("event-one", Arrays.asList(source("log", "/evidence/log")), limits(1, 20, 0), store);
        assertEquals("UNSTABLE", index.getJSONArray("items").getJSONObject(0).getString("state"));
        assertEquals(hash("abc"), index.getJSONArray("items").getJSONObject(0).getString("storedSha256"));
    }

    @Test public void symlinkAndSpecialFilesAreNotOpened() throws Exception {
        for (String kind : Arrays.asList("symlink", "directory", "block", "unsupported")) {
            Clock clock = new Clock(); Access access = new Access(clock).add("/evidence/log", kind, "abc"); Store store = new Store();
            JSONObject index = new FaultEvidenceCollector(access, clock).collect("event-one", Arrays.asList(source("log", "/evidence/log")), limits(1, 20, 0), store);
            assertEquals("READ_FAILED", index.getJSONArray("items").getJSONObject(0).getString("state"));
            assertEquals(0, access.opens); assertEquals(0, store.bytes.size());
        }
    }

    @Test public void nonexistentSourceIsReadFailureNotSyntheticEmptyArtifact() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock); Store store = new Store();
        JSONObject index = new FaultEvidenceCollector(access, clock).collect("event-one", Arrays.asList(source("log", "/missing")), limits(1, 20, 0), store);
        assertEquals("READ_FAILED", index.getJSONArray("items").getJSONObject(0).getString("state"));
        assertEquals(0, store.bytes.size());
    }

    @Test public void emptyRealFileIsSavedAndIndexedAsZeroBytes() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/evidence/log", "file", ""); Store store = new Store();
        JSONObject index = new FaultEvidenceCollector(access, clock).collect("event-one", Arrays.asList(source("log", "/evidence/log")), limits(1, 0, 0), store);
        assertEquals("COMPLETE", index.getString("state")); assertEquals(1, store.bytes.size());
        assertEquals(hash(""), index.getJSONArray("items").getJSONObject(0).getString("storedSha256"));
    }

    @Test public void storeFailureNeverClaimsKnownDurableByteCount() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/evidence/log", "file", "abc"); Store store = new Store(); store.fail = true;
        JSONObject index = new FaultEvidenceCollector(access, clock).collect("event-one", Arrays.asList(source("log", "/evidence/log")), limits(1, 20, 0), store);
        JSONObject item = index.getJSONArray("items").getJSONObject(0);
        assertEquals("STORE_FAILED", item.getString("state")); assertFalse(item.getBoolean("storedBytesKnown"));
        assertFalse(item.has("storedBytes")); assertEquals(hash("abc"), item.getString("capturedSha256"));
        assertFalse(index.toString().contains("不可公开")); assertEquals(1, store.bytes.size());
    }

    @Test public void duplicateCaptureCannotOverwriteEarlierArtifact() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/evidence/log", "file", "abc"); Store store = new Store();
        FaultEvidenceCollector collector = new FaultEvidenceCollector(access, clock);
        collector.collect("event-one", Arrays.asList(source("log", "/evidence/log")), limits(1, 20, 0), store);
        access.nodes.get("/evidence/log").bytes = "xyz".getBytes("UTF-8");
        JSONObject index = collector.collect("event-one", Arrays.asList(source("log", "/evidence/log")), limits(1, 20, 0), store);
        assertEquals("STORE_FAILED", index.getJSONArray("items").getJSONObject(0).getString("state"));
        assertArrayEquals("abc".getBytes("UTF-8"), store.bytes.get("event-one-log"));
    }

    @Test public void missingDefaultSourcesNeverTriggerFilesystemAccess() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock); Store store = new Store();
        JSONObject index = new FaultEvidenceCollector(access, clock).collect("event-one", Collections.<FaultEvidenceCollector.Source>emptyList(), limits(1, 20, 0), store);
        assertEquals("PARTIAL", index.getString("state")); assertEquals(0, access.stats); assertEquals(0, store.bytes.size());
    }

    @Test public void sourceCountLimitRetainsExplicitUncheckedRowsAndDuplicateRequestsFailBeforeIo() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/evidence/a", "file", "abc").add("/evidence/b", "file", "def"); Store store = new Store();
        FaultEvidenceCollector collector = new FaultEvidenceCollector(access, clock);
        JSONObject index = collector.collect("event-one", Arrays.asList(source("a", "/evidence/a"), source("b", "/evidence/b")), limits(1, 20, 0), store);
        assertEquals(2, index.getJSONArray("items").length());
        assertEquals("SOURCE_LIMIT", index.getJSONArray("items").getJSONObject(1).getString("reason"));
        int before = access.stats;
        try { collector.collect("event-two", Arrays.asList(source("a", "/evidence/a"), source("b", "/evidence/a")), limits(2, 20, 0), store); fail(); }
        catch (IllegalArgumentException expected) { assertEquals(before, access.stats); }
    }

    @Test public void badStoreReceiptCannotClaimEvidenceComplete() throws Exception {
        Clock clock = new Clock(); Access access = new Access(clock).add("/evidence/log", "file", "abc"); Store store = new Store(); store.badReceipt = true;
        JSONObject index = new FaultEvidenceCollector(access, clock).collect("event-one", Arrays.asList(source("log", "/evidence/log")), limits(1, 20, 0), store);
        assertEquals("STORE_FAILED", index.getJSONArray("items").getJSONObject(0).getString("state"));
    }
}
