package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 合成分片与故障适配器；不读设备或真实联系人。 */
public class ContactsNexuiTest {
    private static final SystemManagement.Control CONTROL = new SystemManagement.Control() {
        public void check() throws Exception { if (Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
        public void before(JSONObject unused) { throw new AssertionError("只读路径禁止修改"); }
    };
    private static class Transport implements ContactsNexui.Transport {
        ContactsNexui.Receiver receiver;
        boolean closed;
        public void start(String source, ContactsNexui.Receiver receiver) throws Exception { this.receiver = receiver; }
        public void close() throws Exception { closed = true; }
    }
    private static String item(int id) throws Exception {
        return new JSONObject().put("mId", id).put("mLookup", "synthetic-" + id)
                .put("mName", "SYNTHETIC_NAME").put("mNumbers", new JSONArray().put("SYNTHETIC_PHONE")).toString();
    }
    private static ContactsNexui.Accumulator accumulator() { return new ContactsNexui.Accumulator("LOCAL"); }
    private static void frame(ContactsNexui.Accumulator a, int state, String json) { a.frame("LOCAL", "all_contacts", state, json); }
    private static String status(ContactsNexui.Accumulator a) throws Exception {
        try (ContactsNexui.Snapshot s = a.snapshot(1)) { return s.metadata().getString("status"); }
    }
    private static String repeat(char c, int n) { char[] chars = new char[n]; java.util.Arrays.fill(chars, c); return new String(chars); }

    @Test public void allFiveFrozenSourcesAndNoArbitrarySource() throws Exception {
        for (String s : new String[]{"LOCAL", "BLUETOOTH", "EAB", "CONFER", "FAVORITE"}) assertEquals(s, ContactsNexui.validateSource(s));
        for (String s : new String[]{null, "", "local", "ALL", "content://contacts", "LOCAL\n"}) {
            try { ContactsNexui.validateSource(s); fail(); } catch (IOException expected) { }
        }
    }
    @Test public void completeMultipartPagesKeepNativeShapeAndSnapshotIdentity() throws Exception {
        ContactsNexui.Accumulator a = accumulator();
        frame(a, 0, "[" + item(1) + "]"); frame(a, 1, "[" + item(2) + "]"); frame(a, 2, "[" + item(3) + "]");
        try (ContactsNexui.Snapshot s = a.snapshot(12)) {
            JSONObject metadata = s.metadata();
            assertTrue(metadata.getBoolean("list_complete")); assertFalse(metadata.getBoolean("all_sources_complete"));
            assertEquals(3, metadata.getInt("frames_received"));
            JSONObject first = s.page(0, 2, CONTROL), second = s.page(first.getInt("next_offset"), 2, CONTROL);
            assertEquals(metadata.getString("snapshot_id"), second.getString("snapshot_id"));
            assertTrue(first.getBoolean("has_more")); assertFalse(second.getBoolean("has_more"));
            assertEquals(3, second.getJSONArray("items").getJSONObject(0).getInt("mId"));
            assertFalse(first.getJSONArray("items").getJSONObject(0).has("phone"));
            assertEquals("SYNTHETIC_PHONE", first.getJSONArray("items").getJSONObject(0).getJSONArray("mNumbers").getString(0));
        }
    }
    @Test public void singleEndIsVendorEndOnFreshReplyChannelNotAllUiSources() throws Exception {
        ContactsNexui.Accumulator a = accumulator(); frame(a, 2, "[]");
        try (ContactsNexui.Snapshot s = a.snapshot(1)) {
            assertTrue(s.metadata().getBoolean("list_complete")); assertFalse(s.metadata().getBoolean("start_observed"));
            assertEquals("SELECTED_SOURCE_ALL_CONTACTS_REPLY", s.metadata().getString("completion_scope"));
            assertEquals(0, s.metadata().getInt("record_count"));
        }
    }
    @Test public void metadataNeverContainsNamesNumbersLookupsOrNativeIds() throws Exception {
        ContactsNexui.Accumulator a = accumulator(); frame(a, 2, "[" + item(91347) + "]");
        try (ContactsNexui.Snapshot s = a.snapshot(1)) {
            String json = s.metadata().toString();
            for (String secret : new String[]{"SYNTHETIC_NAME", "SYNTHETIC_PHONE", "synthetic-", "mId", "mLookup", "items"}) assertFalse(json.contains(secret));
            assertFalse(s.metadata().getBoolean("contact_values_emitted"));
        }
    }
    @Test public void wrongSourceActionOrSequenceFailsWithoutExposingEarlierRows() throws Exception {
        for (int scenario = 0; scenario < 5; scenario++) {
            ContactsNexui.Accumulator a = accumulator();
            if (scenario != 4) frame(a, 0, "[" + item(1) + "]");
            if (scenario == 0) a.frame("EAB", "all_contacts", 2, "[]");
            if (scenario == 1) a.frame("LOCAL", "root_contacts", 2, "[]");
            if (scenario == 2) frame(a, 0, "[]");
            if (scenario == 3) frame(a, 3, "[]");
            if (scenario == 4) frame(a, 1, "[]");
            assertEquals("PROTOCOL_ERROR", status(a)); assertEquals(0, a.records.size());
        }
    }
    @Test public void malformedNullDeepAndTrailingResponsesNeverBecomeEmptySuccess() throws Exception {
        for (String json : new String[]{null, "null", "{}", "[] junk", "[1]", "[{} , null]", "[", repeat('[', 17) + repeat(']', 17)}) {
            ContactsNexui.Accumulator a = accumulator(); frame(a, 2, json);
            assertEquals("PROTOCOL_ERROR", status(a)); assertEquals(0, a.records.size());
        }
    }
    @Test public void nestedStringsEscapesAreNotMistakenForNesting() throws Exception {
        ContactsNexui.Accumulator a = accumulator();
        frame(a, 2, new JSONArray().put(new JSONObject().put("mName", "[\"\\{}]" + repeat('[', 50))).toString());
        assertEquals("COMPLETED", status(a));
    }
    @Test public void frameAndRecordSizeCapsAreExplicitNotComplete() throws Exception {
        ContactsNexui.Accumulator a = accumulator(); frame(a, 2, repeat(' ', ContactsNexui.MAX_FRAME_CHARS + 1));
        assertEquals("TEXT_LIMIT", status(a));
        a = accumulator(); frame(a, 2, "[{\"mName\":\"" + repeat('x', ContactsNexui.MAX_RECORD_CHARS) + "\"}]");
        assertEquals("RECORD_SIZE_LIMIT", status(a)); assertFalse(a.endObserved);
    }
    @Test public void frameBudgetAlsoBoundsEmptyReplyFlood() throws Exception {
        ContactsNexui.Accumulator a = accumulator(); frame(a, 0, "[]");
        for (int i = 0; i < ContactsNexui.MAX_FRAMES; i++) frame(a, 1, "[]");
        assertEquals("FRAME_LIMIT", status(a)); assertEquals(ContactsNexui.MAX_FRAMES, a.frames);
    }
    @Test public void lateEndCannotBypassDeadlineEvenBeforeWaitLoopStarts() throws Exception {
        ContactsNexui.Accumulator a = new ContactsNexui.Accumulator("LOCAL", System.nanoTime() - 1000000000L, 20);
        frame(a, 2, "[]"); assertEquals("TIMEOUT", status(a)); assertFalse(a.endObserved);
    }
    @Test public void pageCancellationDoesNotEmitPartOfPage() throws Exception {
        ContactsNexui.Accumulator a = accumulator(); frame(a, 2, "[" + item(1) + "," + item(2) + "]");
        try (ContactsNexui.Snapshot s = a.snapshot(1)) {
            SystemManagement.Control cancel = new SystemManagement.Control() {
                int checks;
                public void check() throws Exception { if (++checks == 3) throw new InterruptedException(); }
                public void before(JSONObject unused) { fail(); }
            };
            try { s.page(0, 2, cancel); fail(); } catch (InterruptedException expected) { }
            assertEquals(2, s.page(0, 2, CONTROL).getJSONArray("items").length());
        }
    }
    @Test public void totalTextBudgetAndRecordCountStopWithoutDroppingBoundariesSilently() throws Exception {
        ContactsNexui.Accumulator a = accumulator(); String json = "[]" + repeat(' ', 131000);
        frame(a, 0, json);
        for (int i = 0; i < 9; i++) frame(a, 1, json);
        assertEquals("TEXT_LIMIT", status(a)); assertTrue(a.chars <= ContactsNexui.MAX_TOTAL_CHARS);
        a = accumulator(); JSONArray batch = new JSONArray(); for (int i = 0; i < 1000; i++) batch.put(new JSONObject().put("mId", i));
        frame(a, 0, batch.toString()); for (int i = 0; i < 4; i++) frame(a, 1, batch.toString());
        assertEquals("RECORD_LIMIT", status(a)); assertEquals(ContactsNexui.MAX_RECORDS, a.records.size());
    }
    @Test public void pageBudgetHandlesEscapedTextAndProgressesUntilAllStoredRows() throws Exception {
        ContactsNexui.Accumulator a = accumulator(); JSONArray batch = new JSONArray();
        for (int i = 0; i < 20; i++) batch.put(new JSONObject().put("mId", i).put("mName", repeat('"', 1500)));
        frame(a, 2, batch.toString());
        try (ContactsNexui.Snapshot s = a.snapshot(1)) {
            int at = 0;
            while (at < 20) {
                JSONObject page = s.page(at, 32, CONTROL); assertTrue(page.toString().length() < 16000);
                assertTrue(page.getInt("next_offset") > at); at = page.getInt("next_offset");
            }
            assertEquals(20, at);
        }
    }
    @Test public void invalidPageAndClosedSnapshotFailAndReturnedJsonCannotMutateSnapshot() throws Exception {
        ContactsNexui.Accumulator a = accumulator(); frame(a, 2, "[" + item(1) + "]");
        ContactsNexui.Snapshot s = a.snapshot(1);
        for (int[] page : new int[][]{{-1, 1}, {2, 1}, {0, 0}, {0, 33}}) {
            try { s.page(page[0], page[1], CONTROL); fail(); } catch (IOException expected) { }
        }
        s.page(0, 1, CONTROL).getJSONArray("items").getJSONObject(0).put("mName", "CHANGED");
        assertEquals("SYNTHETIC_NAME", s.page(0, 1, CONTROL).getJSONArray("items").getJSONObject(0).getString("mName"));
        s.close(); s.close();
        try { s.metadata(); fail(); } catch (IOException expected) { }
        try { s.page(0, 1, CONTROL); fail(); } catch (IOException expected) { }
    }
    @Test public void timeoutWithPartialRowsClosesTransportAndIgnoresLateReply() throws Exception {
        Transport t = new Transport() {
            public void start(String source, ContactsNexui.Receiver receiver) throws Exception {
                super.start(source, receiver); receiver.frame(source, "all_contacts", 0, "[" + item(1) + "]");
            }
        };
        try (ContactsNexui.Snapshot s = ContactsNexui.collect(t, "LOCAL", CONTROL, 20)) {
            assertTrue(t.closed); assertEquals("TIMEOUT", s.metadata().getString("status"));
            assertFalse(s.metadata().getBoolean("list_complete"));
            t.receiver.frame("LOCAL", "all_contacts", 2, "[]");
            assertEquals("TIMEOUT", s.metadata().getString("status")); assertEquals(1, s.metadata().getInt("record_count"));
        }
    }
    @Test public void cancellationDuringWaitClosesTransportAndDoesNotReturnSuccess() throws Exception {
        final AtomicBoolean started = new AtomicBoolean();
        Transport t = new Transport() {
            public void start(String source, ContactsNexui.Receiver receiver) { started.set(true); }
        };
        SystemManagement.Control cancel = new SystemManagement.Control() {
            public void check() throws Exception { if (started.get()) throw new InterruptedException(); }
            public void before(JSONObject unused) { fail(); }
        };
        try { ContactsNexui.collect(t, "LOCAL", cancel, 30); fail(); } catch (InterruptedException expected) { }
        assertTrue(t.closed);
    }
    @Test public void transportStartAndCloseFailuresCannotBecomeSuccessfulRead() throws Exception {
        Transport t = new Transport() { public void start(String s, ContactsNexui.Receiver r) throws Exception { throw new IOException("synthetic"); } };
        try { ContactsNexui.collect(t, "LOCAL", CONTROL, 30); fail(); } catch (IOException expected) { }
        assertTrue(t.closed);
        t = new Transport() {
            public void start(String s, ContactsNexui.Receiver r) { r.frame(s, "all_contacts", 2, "[]"); }
            public void close() throws Exception { closed = true; throw new IOException("synthetic"); }
        };
        try { ContactsNexui.collect(t, "LOCAL", CONTROL, 30); fail(); } catch (IOException expected) { }
        assertTrue(t.closed);
    }
    @Test public void disconnectIsNotAnEmptyListAndErrorTextIsNotEchoed() throws Exception {
        for (String code : new String[]{"SERVICE_UNAVAILABLE", "SERVICE_DISCONNECTED", "SYNTHETIC_PRIVATE_FAILURE"}) {
            ContactsNexui.Accumulator a = accumulator(); a.failed(code);
            try (ContactsNexui.Snapshot s = a.snapshot(1)) {
                assertFalse(s.metadata().getBoolean("ok")); assertFalse(s.metadata().toString().contains("SYNTHETIC_PRIVATE_FAILURE"));
            }
        }
    }
    @Test public void helperMetadataRequiresExplicitKnownSource() throws Exception {
        assertEquals("nexui-metadata", ContactsReadCommand.validateArgs(new String[]{"nexui-metadata", "LOCAL"}));
        for (String[] args : new String[][]{{"nexui-metadata"}, {"nexui-metadata", "ALL"}, {"nexui-metadata", "LOCAL", "values"}}) {
            try { ContactsReadCommand.validateArgs(args); fail(); } catch (IOException expected) { }
        }
    }
}
