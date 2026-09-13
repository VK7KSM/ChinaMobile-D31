package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContactsPageStoreTest {
    private static final String HASH = new String(new char[64]).replace('\0', 'a');
    private static final String BOOT = "00000000-0000-0000-0000-000000000002";
    private static final SystemManagement.Control CONTROL = new SystemManagement.Control() {
        public void check() { }
        public void before(JSONObject value) throws Exception { throw new IOException(); }
    };
    interface Attempt { void run() throws Exception; }
    private static void rejects(String code, Attempt attempt) throws Exception {
        try { attempt.run(); fail("应当拒绝"); } catch (IOException expected) { assertEquals(code, expected.getMessage()); }
    }
    static JSONObject released() throws Exception {
        return ContactsLocalRead.metadata("LOCAL_METADATA_VERIFIED").put("ok", true)
                .put("remoteOutcomeKnown", true).put("bindingRequested", true)
                .put("unbindConfirmed", true).put("replyChannelClosed", true);
    }
    private static ContactsNexui.Snapshot snapshot(String source, int count) throws Exception {
        List<String> records = new ArrayList<>();
        for (int i = 0; i < count; i++) records.add(new JSONObject().put("mId", i)
                .put("mName", "SYNTHETIC_PRIVATE_" + i).put("mNumbers", new org.json.JSONArray().put("test-" + i)).toString());
        return new ContactsNexui.Snapshot(source, records, "COMPLETED", 1, 200, false, true, 1);
    }
    @Test public void pagesAndRepeatedOffsetsRemainSameImmutableReply() throws Exception {
        try (ContactsPageStore store = new ContactsPageStore(() -> 0)) {
            String id = store.publish(snapshot("LOCAL", 70), HASH, BOOT, released()).getString("snapshot_id");
            int seen = 0;
            while (seen < 70) {
                JSONObject page = store.page(HASH, BOOT, id, seen, 32, CONTROL);
                assertEquals(id, page.getString("snapshot_id")); assertEquals("LOCAL", page.getString("contact_type"));
                for (int i = 0; i < page.getJSONArray("items").length(); i++) assertEquals(seen + i, page.getJSONArray("items").getJSONObject(i).getInt("mId"));
                seen = page.getInt("next_offset");
                assertEquals(seen < 70, page.getBoolean("has_more"));
            }
            JSONObject first = store.page(HASH, BOOT, id, 0, 1, CONTROL);
            first.getJSONArray("items").getJSONObject(0).put("mName", "CHANGED_RETURN_OBJECT");
            assertEquals("SYNTHETIC_PRIVATE_0", store.page(HASH, BOOT, id, 0, 1, CONTROL).getJSONArray("items").getJSONObject(0).getString("mName"));
            assertFalse(first.getBoolean("all_sources_complete"));
            assertEquals("NOT_PROVIDED_BY_VENDOR", first.getString("snapshot_consistency"));
            assertEquals("IMMUTABLE_RECEIVED_REPLY", first.getString("page_consistency"));
        }
    }
    @Test public void emptyCompletedSnapshotReturnsAnEmptyFinalPage() throws Exception {
        try (ContactsPageStore store = new ContactsPageStore(() -> 0)) {
            String id = store.publish(snapshot("LOCAL", 0), HASH, BOOT, released()).getString("snapshot_id");
            JSONObject page = store.page(HASH, BOOT, id, 0, 1, CONTROL);
            assertEquals(0, page.getJSONArray("items").length()); assertFalse(page.getBoolean("has_more"));
            assertTrue(page.getBoolean("page_complete"));
        }
    }
    @Test public void exhaustedCursorIsNotMissingSnapshot() throws Exception {
        try (ContactsPageStore store = new ContactsPageStore(() -> 0)) {
            String id = store.publish(snapshot("LOCAL", 2), HASH, BOOT, released()).getString("snapshot_id");
            assertEquals(0, store.page(HASH, BOOT, id, 2, 1, CONTROL).getJSONArray("items").length());
            rejects("CONTACTS_PAGE_ARGUMENTS_INVALID", () -> store.page(HASH, BOOT, id, 3, 1, CONTROL));
        }
    }
    @Test public void foreignIdentityCannotReadOrDestroyAnotherSnapshot() throws Exception {
        try (ContactsPageStore store = new ContactsPageStore(() -> 0)) {
            String id = store.publish(snapshot("LOCAL", 1), HASH, BOOT, released()).getString("snapshot_id");
            rejects("CONTACTS_SNAPSHOT_MISMATCH", () -> store.page(HASH.replace('a', 'b'), BOOT, id, 0, 1, CONTROL));
            rejects("CONTACTS_SNAPSHOT_MISMATCH", () -> store.page(HASH, id, id, 0, 1, CONTROL));
            rejects("CONTACTS_SNAPSHOT_MISMATCH", () -> store.release(HASH, BOOT, BOOT));
            assertEquals(1, store.page(HASH, BOOT, id, 0, 1, CONTROL).getJSONArray("items").length());
        }
    }
    @Test public void expiredCursorDoesNotCreateANewReadAndPagesDoNotExtendTtl() throws Exception {
        final long[] time = {10};
        try (ContactsPageStore store = new ContactsPageStore(() -> time[0])) {
            String id = store.publish(snapshot("LOCAL", 1), HASH, BOOT, released()).getString("snapshot_id");
            time[0] += ContactsPageStore.TTL_MS - 1;
            store.page(HASH, BOOT, id, 0, 1, CONTROL);
            time[0]++;
            rejects("CONTACTS_SNAPSHOT_GONE", () -> store.page(HASH, BOOT, id, 0, 1, CONTROL));
            store.requireEmpty();
        }
    }
    @Test public void clockRegressionDiscardsInsteadOfExtendingTtl() throws Exception {
        final long[] time = {10};
        try (ContactsPageStore store = new ContactsPageStore(() -> time[0])) {
            String id = store.publish(snapshot("LOCAL", 1), HASH, BOOT, released()).getString("snapshot_id");
            time[0] = 9;
            rejects("CONTACTS_SNAPSHOT_GONE", () -> store.page(HASH, BOOT, id, 0, 1, CONTROL));
        }
    }
    @Test public void processRecreationCannotReturnPreviousCursorAsEmpty() throws Exception {
        String id;
        try (ContactsPageStore store = new ContactsPageStore(() -> 0)) { id = store.publish(snapshot("LOCAL", 1), HASH, BOOT, released()).getString("snapshot_id"); }
        try (ContactsPageStore fresh = new ContactsPageStore(() -> 0)) { rejects("CONTACTS_SNAPSHOT_GONE", () -> fresh.page(HASH, BOOT, id, 0, 1, CONTROL)); }
    }
    @Test public void fullStoreRejectsReplacementWithoutDestroyingExistingData() throws Exception {
        try (ContactsPageStore store = new ContactsPageStore(() -> 0); ContactsNexui.Snapshot other = snapshot("LOCAL", 1)) {
            String id = store.publish(snapshot("LOCAL", 2), HASH, BOOT, released()).getString("snapshot_id");
            rejects("CONTACTS_SNAPSHOT_BUSY", store::requireEmpty);
            rejects("CONTACTS_SNAPSHOT_BUSY", () -> store.publish(other, HASH, BOOT, released()));
            assertEquals(2, store.page(HASH, BOOT, id, 0, 32, CONTROL).getJSONArray("items").length());
            assertEquals(1, other.metadata().getInt("record_count"));
        }
    }
    @Test public void incompleteOrForeignSourceIsNotPublishable() throws Exception {
        try (ContactsPageStore store = new ContactsPageStore(() -> 0); ContactsNexui.Snapshot other = snapshot("BLUETOOTH", 1)) {
            rejects("CONTACTS_SNAPSHOT_INCOMPLETE", () -> store.publish(other, HASH, BOOT, released()));
            ContactsNexui.Accumulator partial = new ContactsNexui.Accumulator("LOCAL");
            partial.frame("LOCAL", "all_contacts", 0, "[{\"mId\":1}]"); partial.stop("TIMEOUT");
            try (ContactsNexui.Snapshot value = partial.snapshot(1)) { rejects("CONTACTS_SNAPSHOT_INCOMPLETE", () -> store.publish(value, HASH, BOOT, released())); }
            finally { partial.discard(); }
            store.requireEmpty();
        }
    }
    @Test public void cleanupUnknownOrBusinessFailureCannotPublish() throws Exception {
        for (String field : new String[]{"ok", "unbindConfirmed", "replyChannelClosed", "remoteOutcomeKnown"}) {
            try (ContactsPageStore store = new ContactsPageStore(() -> 0); ContactsNexui.Snapshot value = snapshot("LOCAL", 1)) {
                JSONObject receipt = released().put(field, JSONObject.NULL);
                rejects("CONTACTS_SNAPSHOT_NOT_RELEASED", () -> store.publish(value, HASH, BOOT, receipt));
                store.requireEmpty();
            }
        }
    }
    @Test public void cancellationClosesSnapshotWithoutReturningPartialPage() throws Exception {
        try (ContactsPageStore store = new ContactsPageStore(() -> 0)) {
            String id = store.publish(snapshot("LOCAL", 10), HASH, BOOT, released()).getString("snapshot_id");
            SystemManagement.Control cancel = new SystemManagement.Control() {
                int calls;
                public void check() throws Exception { if (++calls == 4) throw new IOException("CONTACTS_CANCELLED"); }
                public void before(JSONObject value) { }
            };
            rejects("CONTACTS_CANCELLED", () -> store.page(HASH, BOOT, id, 0, 10, cancel));
            rejects("CONTACTS_SNAPSHOT_GONE", () -> store.page(HASH, BOOT, id, 0, 1, CONTROL));
        }
    }
    @Test public void expiryDuringSerializationCannotReturnData() throws Exception {
        final long[] time = {0};
        try (ContactsPageStore store = new ContactsPageStore(() -> time[0])) {
            String id = store.publish(snapshot("LOCAL", 1), HASH, BOOT, released()).getString("snapshot_id");
            SystemManagement.Control advance = new SystemManagement.Control() {
                public void check() { time[0] = ContactsPageStore.TTL_MS; }
                public void before(JSONObject value) { }
            };
            rejects("CONTACTS_SNAPSHOT_GONE", () -> store.page(HASH, BOOT, id, 0, 1, advance));
        }
    }
    @Test public void maxUtf8RecordFitsExplicitPageReplyButNotMetadataBudget() throws Exception {
        String text = new String(new char[8000]).replace('\0', '\u4e2d');
        List<String> records = new ArrayList<>(); records.add(new JSONObject().put("mName", text).toString());
        try (ContactsPageStore store = new ContactsPageStore(() -> 0)) {
            String id = store.publish(new ContactsNexui.Snapshot("LOCAL", records, "COMPLETED", 1, 8012, false, true, 1), HASH, BOOT, released()).getString("snapshot_id");
            JSONObject page = store.page(HASH, BOOT, id, 0, 32, CONTROL);
            JSONObject result = ContactsPageCommand.receipt("CONTACTS_PAGE_VERIFIED").put("page", page);
            String encoded = ContactsAppContract.envelope(id, BOOT, 0, result, ContactsAppContract.PAGE_BYTES).toString();
            assertTrue(encoded.getBytes("UTF-8").length < ContactsAppContract.PAGE_BYTES);
            assertTrue(ContactsAppContract.reply(10001, 10001, id, BOOT, 0, 1, encoded, ContactsAppContract.PAGE_BYTES).has("result"));
            rejects("CONTACTS_REPLY_LIMIT", () -> ContactsAppContract.envelope(id, BOOT, 0, result));
            rejects("CONTACTS_REPLY_IDENTITY_OR_SIZE", () -> ContactsAppContract.reply(10002, 10001, id, BOOT, 0, 1, encoded, ContactsAppContract.PAGE_BYTES));
        }
    }
    @Test public void pageSizeShrinksForTextBudgetAndStillAdvances() throws Exception {
        List<String> records = new ArrayList<>();
        for (int i = 0; i < 3; i++) records.add(new JSONObject().put("mName", new String(new char[7900]).replace('\0', 'x')).toString());
        try (ContactsPageStore store = new ContactsPageStore(() -> 0)) {
            String id = store.publish(new ContactsNexui.Snapshot("LOCAL", records, "COMPLETED", 1, 24000, false, true, 1), HASH, BOOT, released()).getString("snapshot_id");
            assertEquals(1, store.page(HASH, BOOT, id, 0, 32, CONTROL).getInt("next_offset"));
        }
    }
    @Test public void onlyExplicitLocalReadVerbsAndBoundedIntegersAreAccepted() throws Exception {
        assertEquals(HASH, ContactsPageCommand.validate(new String[]{"open", HASH, "test-operation"}));
        assertEquals(HASH, ContactsPageCommand.validate(new String[]{"page", HASH, BOOT, "0", "32"}));
        assertEquals(HASH, ContactsPageCommand.validate(new String[]{"close", HASH, BOOT}));
        for (String[] args : new String[][]{{"open", HASH, "../escape"}, {"page", HASH, BOOT, "0", "33"},
                {"page", HASH, BOOT, "-1", "1"}, {"page", HASH, BOOT, "4097", "1"},
                {"page", HASH, BOOT, "1.0", "1"}, {"page", HASH, BOOT, "01", "1"}, {"write", HASH, BOOT},
                {"open", HASH, "op", "BLUETOOTH"}}) rejects("CONTACTS_PAGE_ARGUMENTS_INVALID", () -> ContactsPageCommand.validate(args));
    }
    @Test public void explicitCloseClearsDataAndReuseIsRejected() throws Exception {
        try (ContactsPageStore store = new ContactsPageStore(() -> 0)) {
            ContactsNexui.Snapshot value = snapshot("LOCAL", 1);
            String id = store.publish(value, HASH, BOOT, released()).getString("snapshot_id");
            store.release(HASH, BOOT, id); store.requireEmpty();
            rejects("CONTACTS_SNAPSHOT_GONE", () -> store.page(HASH, BOOT, id, 0, 1, CONTROL));
            try { value.metadata(); fail(); } catch (IOException expected) { }
        }
    }
}
