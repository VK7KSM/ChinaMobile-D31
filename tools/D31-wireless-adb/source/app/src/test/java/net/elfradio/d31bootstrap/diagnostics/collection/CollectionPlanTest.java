package net.elfradio.d31bootstrap.diagnostics.collection;

import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static net.elfradio.d31bootstrap.diagnostics.collection.CollectionFixture.*;

/** 合成文件树验证拆批、恢复和失败关闭，不访问Android或实际设备。 */
public class CollectionPlanTest {
    private CollectionPlan plan(int entries, long read, long file, int tasks, long total) throws Exception {
        return CollectionPlan.create(identity(), "synthetic-boot", Arrays.asList("/system"),
                new CollectionLimits(entries, read, file, 30000, 8, 131072, 60000), tasks, total, 60000);
    }
    private JSONObject step(CollectionPlan p, Access a, Clock clock, Map<String, JSONObject> evidence) throws Exception {
        JSONObject e = p.collectNext(a, clock, "synthetic-boot", "page-" + evidence.size());
        evidence.put(CollectionPlan.digest(e), new JSONObject(e.toString())); p.accept(e); return e;
    }
    private void finish(CollectionPlan p, Access a, Clock c, Map<String, JSONObject> evidence) throws Exception {
        int n = 0; while (p.next() != null) { assertTrue(++n < 100); step(p, a, c, evidence); }
    }
    private void rejected(Action action) throws Exception {
        try { action.run(); fail("应拒绝输入"); } catch (IllegalArgumentException expected) { }
    }
    private interface Action { void run() throws Exception; }

    @Test public void smallTreeUsesOriginalCompleteManifest() throws Exception {
        Clock c = new Clock(); Access a = new Access(c).add("/system", "directory", "").add("/system/a", "file", "abc");
        CollectionPlan p = plan(10, 100, 100, 20, 1000);
        JSONObject e = step(p, a, c, new HashMap<String, JSONObject>());
        assertEquals("COMPLETE", e.getJSONObject("manifest").getString("completeness"));
        assertNull(p.next()); JSONObject s = ManifestBatchAggregation.summarize(p);
        assertEquals("CLOSED_WITHIN_PLAN", s.getString("inventory"));
        assertEquals("NOT_ASSESSED", s.getString("systemConsistency"));
        assertEquals(5, s.getJSONArray("metadataNotCollected").length());
    }

    @Test public void directorySplitsResumeAndSealWithoutPromotingPartialManifest() throws Exception {
        Clock c = new Clock(); Access a = new Access(c).add("/system", "directory", "")
                .add("/system/a", "file", "abc").add("/system/sub", "directory", "")
                .add("/system/sub/b", "file", "def");
        CollectionPlan p = plan(2, 100, 100, 20, 10000);
        final Map<String, JSONObject> evidence = new LinkedHashMap<String, JSONObject>();
        JSONObject first = step(p, a, c, evidence);
        assertEquals("PARTIAL", first.getJSONObject("manifest").getString("completeness"));
        assertEquals("EXPAND", p.next().getString("phase"));
        step(p, a, c, evidence);
        JSONObject checkpoint = p.checkpoint();
        p = CollectionPlan.restore(checkpoint, "synthetic-boot", evidence::get);
        finish(p, a, c, evidence);
        JSONObject s = ManifestBatchAggregation.summarize(p);
        assertEquals("CLOSED_WITHIN_PLAN", s.getString("inventory"));
        assertTrue(s.getInt("originalPartialBatches") > 0);
        assertEquals("PARTIAL", first.getJSONObject("manifest").getString("completeness"));
        assertEquals(CollectionPlan.digest(p.checkpoint()), CollectionPlan.digest(
                CollectionPlan.restore(p.checkpoint(), "synthetic-boot", evidence::get).checkpoint()));
    }

    @Test public void fileOverOldEightMiBLimitIsHashedTwiceWithoutReturningContent() throws Exception {
        Clock c = new Clock(); Access a = new Access(c).add("/system", "file", "");
        a.nodes.get("/system").bytes = new byte[9 * 1024 * 1024];
        CollectionPlan p = plan(2, 32L * 1024 * 1024, 16L * 1024 * 1024, 10, 64L * 1024 * 1024);
        JSONObject e = step(p, a, c, new HashMap<String, JSONObject>());
        assertEquals(18L * 1024 * 1024, e.getJSONObject("index").getLong("readBytes"));
        assertEquals(2, a.opens); assertTrue(e.toString().length() < 20000);
        assertEquals("CLOSED_WITHIN_PLAN", ManifestBatchAggregation.summarize(p).getString("inventory"));
    }

    @Test public void oversizedSingleFileAndReadFailureRemainBlocked() throws Exception {
        for (boolean failure : new boolean[]{false, true}) {
            Clock c = new Clock(); Access a = new Access(c).add("/system", "file", "abcdef");
            if (failure) a.failingStat = "/system";
            CollectionPlan p = plan(2, 100, 3, 10, 1000);
            step(p, a, c, new HashMap<String, JSONObject>());
            assertNull(p.next());
            assertEquals("INCOMPLETE", ManifestBatchAggregation.summarize(p).getString("inventory"));
        }
    }

    @Test public void incompleteListingDoesNotScheduleGuessedChildren() throws Exception {
        Clock c = new Clock(); Access a = new Access(c).add("/system", "directory", "").add("/system/a", "file", "x");
        CollectionPlan p = plan(1, 100, 100, 10, 1000); Map<String, JSONObject> evidence = new HashMap<String, JSONObject>();
        step(p, a, c, evidence); a.failingList = "/system"; step(p, a, c, evidence);
        assertNull(p.next()); assertEquals(1, ManifestBatchAggregation.summarize(p).getInt("tasks"));
        assertEquals("INCOMPLETE", ManifestBatchAggregation.summarize(p).getString("inventory"));
    }

    @Test public void postChildrenDirectoryChangeCannotSeal() throws Exception {
        Clock c = new Clock(); Access a = new Access(c).add("/system", "directory", "").add("/system/a", "file", "x");
        CollectionPlan p = plan(1, 100, 100, 10, 1000); Map<String, JSONObject> evidence = new HashMap<String, JSONObject>();
        step(p, a, c, evidence); step(p, a, c, evidence); step(p, a, c, evidence);
        assertEquals("SEAL", p.next().getString("phase"));
        a.add("/system/new", "file", "y"); step(p, a, c, evidence);
        assertEquals("INCOMPLETE", ManifestBatchAggregation.summarize(p).getString("inventory"));
    }

    @Test public void taskAndSessionAndEvidenceChangesAreRejectedWithoutAdvance() throws Exception {
        Clock c = new Clock(); Access a = new Access(c).add("/system", "file", "abc");
        CollectionPlan p = plan(2, 100, 100, 10, 1000);
        rejected(() -> p.collectNext(a, c, "other-boot", "page"));
        JSONObject event = p.collectNext(a, c, "synthetic-boot", "page");
        JSONObject bad = new JSONObject(event.toString()); bad.getJSONObject("task").put("path", "/system/other");
        rejected(() -> p.accept(bad)); assertEquals(0, p.next().getInt("sequence"));
        p.accept(event); JSONObject checkpoint = p.checkpoint();
        rejected(() -> CollectionPlan.restore(checkpoint, "other-boot", sha -> event));
        rejected(() -> CollectionPlan.restore(checkpoint, "synthetic-boot", sha -> new JSONObject()));
        rejected(() -> CollectionPlan.restore(checkpoint, "synthetic-boot", sha -> null));
    }

    @Test public void taskBudgetAndByteBudgetCannotBecomeComplete() throws Exception {
        Clock c = new Clock(); Access a = new Access(c).add("/system", "directory", "").add("/system/a", "file", "abc");
        CollectionPlan p = plan(1, 100, 100, 1, 1000); Map<String, JSONObject> evidence = new HashMap<String, JSONObject>();
        step(p, a, c, evidence);
        JSONObject expansion = p.collectNext(a, c, "synthetic-boot", "page");
        p.accept(expansion); assertNull(p.next());
        assertEquals("INCOMPLETE", ManifestBatchAggregation.summarize(p).getString("inventory"));
        CollectionPlan bytes = plan(2, 100, 100, 10, 5);
        step(bytes, a, c, new HashMap<String, JSONObject>());
        assertEquals("INCOMPLETE", ManifestBatchAggregation.summarize(bytes).getString("inventory"));
    }

    @Test public void privateAndPseudoRootsRefusedRatherThanDeclaredEqual() throws Exception {
        for (String root : Arrays.asList("/", "/data", "/data/data", "/sdcard", "/proc", "/dev"))
            rejected(() -> CollectionPlan.create(identity(), "synthetic-boot", Arrays.asList(root),
                    limits(2, 10, 0), 10, 1000, 1000));
    }

    @Test public void forgedCompleteWithUnknownHashOrEnumerationRemainsIncomplete() throws Exception {
        Clock c = new Clock(); Access a = new Access(c).add("/system", "file", "abc");
        JSONObject m = new ManifestCollector(a, c).collect(identity(), "/system", limits(2, 100, 0)).manifest().toJson();
        m.getJSONArray("entries").getJSONObject(0).getJSONObject("fields").put("sha256",
                new JSONObject().put("state", "NOT_CHECKED").put("source", "fixture").put("reason", "MISSING"));
        assertFalse(ManifestBatchAggregation.contentClosed(m));
    }

    @Test public void moreThan512EntriesSplitWithoutLosingChildren() throws Exception {
        Clock c = new Clock(); Access a = new Access(c).add("/system", "directory", "");
        for (int i = 0; i < 513; i++) a.add("/system/f" + i, "file", "x");
        CollectionPlan p = plan(512, 10000, 100, 1024, 100000);
        Map<String, JSONObject> evidence = new HashMap<String, JSONObject>();
        int count = 0; while (p.next() != null) { assertTrue(++count < 520); step(p, a, c, evidence); }
        JSONObject summary = ManifestBatchAggregation.summarize(p);
        assertEquals("CLOSED_WITHIN_PLAN", summary.getString("inventory"));
        assertEquals(514, summary.getInt("tasks")); assertEquals(513, summary.getInt("originalCompleteBatches"));
    }

    @Test public void enumerationOver4096RemainsAnExplicitGap() throws Exception {
        Clock c = new Clock(); Access a = new Access(c).add("/system", "directory", "");
        List<String> names = new ArrayList<String>(); for (int i = 0; i < 4097; i++) names.add("f" + i);
        a.injectedNames = names;
        CollectionPlan p = plan(1, 10000, 100, 8192, 100000);
        Map<String, JSONObject> evidence = new HashMap<String, JSONObject>();
        step(p, a, c, evidence); step(p, a, c, evidence);
        assertNull(p.next()); assertEquals("INCOMPLETE", ManifestBatchAggregation.summarize(p).getString("inventory"));
    }

    @Test public void duplicateUnsafeChildrenAndSymlinkTraversalAreRejected() throws Exception {
        for (List<String> names : Arrays.asList(Arrays.asList("x", "x"), Arrays.asList("../private"))) {
            Clock c = new Clock(); Access a = new Access(c).add("/system", "directory", "");
            CollectionPlan p = plan(1, 10000, 100, 10, 100000); Map<String, JSONObject> evidence = new HashMap<String, JSONObject>();
            a.add("/system/a", "file", "x"); step(p, a, c, evidence); a.injectedNames = names; step(p, a, c, evidence);
            assertEquals("INCOMPLETE", ManifestBatchAggregation.summarize(p).getString("inventory"));
        }
        Clock c = new Clock(); Access a = new Access(c).add("/system", "symlink", "").add("/outside", "file", "secret");
        CollectionPlan p = plan(2, 100, 100, 10, 1000); step(p, a, c, new HashMap<String, JSONObject>());
        assertEquals(0, a.opens); assertEquals(0, a.lists);
    }

    @Test public void timeLimitAndCancelledExpansionCannotClose() throws Exception {
        Clock c = new Clock(); Access a = new Access(c).add("/system", "directory", "").add("/system/a", "file", "x");
        CollectionPlan p = plan(1, 100, 100, 10, 1000); Map<String, JSONObject> evidence = new HashMap<String, JSONObject>();
        step(p, a, c, evidence); int stats = a.stats;
        Thread.currentThread().interrupt();
        try { step(p, a, c, evidence); } finally { Thread.interrupted(); }
        assertEquals(stats, a.stats); assertEquals("INCOMPLETE", ManifestBatchAggregation.summarize(p).getString("inventory"));
        CollectionPlan timed = plan(2, 100, 100, 10, 1000); a.statCost = 40000;
        step(timed, a, c, new HashMap<String, JSONObject>());
        assertEquals("INCOMPLETE", ManifestBatchAggregation.summarize(timed).getString("inventory"));
    }

    @Test public void originalFixedProductRootsAreAllowedButPrivateNeighborsAreNot() throws Exception {
        for (String root : Arrays.asList("/data/local/d31-patches", "/data/local/d31-system-support",
                "/data/local/d31-startup-handover", "/data/local/d31-recovery-entry", "/data/local/d31-rescue",
                "/data/local/d31-startup-curtain", "/data/local/d31-diagnostic-input", "/data/system/devices/keylayout")) {
            CollectionPlan p = CollectionPlan.create(identity(), "synthetic-boot", Arrays.asList(root), limits(2, 100, 1), 10, 1000, 1000);
            assertEquals(root, p.next().getString("path"));
        }
        for (String root : Arrays.asList("/data/local/d31-patches-private", "/data/system", "/data/local/d31-remote"))
            rejected(() -> CollectionPlan.create(identity(), "synthetic-boot", Arrays.asList(root), limits(2, 100, 1), 10, 1000, 1000));
    }

    @Test public void timedOutDirectoryCanSplitButTimedOutLeafCannot() throws Exception {
        Clock c = new Clock(); Access a = new Access(c).add("/system", "directory", "").add("/system/a", "file", "abc");
        a.readCost = 40000;
        CollectionPlan p = plan(10, 100, 100, 10, 1000); Map<String, JSONObject> evidence = new HashMap<String, JSONObject>();
        JSONObject first = step(p, a, c, evidence);
        assertEquals("PARTIAL", first.getJSONObject("manifest").getString("completeness"));
        assertEquals("EXPAND", p.next().getString("phase"));
        a.readCost = 0; finish(p, a, c, evidence);
        assertEquals("CLOSED_WITHIN_PLAN", ManifestBatchAggregation.summarize(p).getString("inventory"));
        CollectionPlan leaf = CollectionPlan.create(identity(), "synthetic-boot", Arrays.asList("/system/a"),
                new CollectionLimits(2, 100, 100, 30000, 1, 131072, 60000), 10, 1000, 60000);
        a.readCost = 40000; step(leaf, a, c, new HashMap<String, JSONObject>());
        assertNull(leaf.next()); assertEquals("INCOMPLETE", ManifestBatchAggregation.summarize(leaf).getString("inventory"));
    }
}
