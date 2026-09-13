package net.elfradio.d31bootstrap.management;

import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 只测游标消费、真实Web形状及来源边界，不冒充Android或原厂Provider已经实读。 */
public class ContactsReadTest {
    private static class Control implements SystemManagement.Control {
        int checks, cancelAt = -1;
        public void check() throws Exception { if (++checks == cancelAt) throw new InterruptedException("取消读取"); }
        public void before(JSONObject original) { throw new AssertionError("只读路径不得保存修改原像"); }
    }
    private static class Rows implements ContactsRead.Rows {
        final Object[][] values;
        int at = -1;
        Rows(Object[]... values) { this.values = values; }
        public boolean next() throws Exception { return ++at < values.length; }
        public long id() { return ((Number) values[at][0]).longValue(); }
        public String name() { return (String) values[at][1]; }
        public String phone() { return (String) values[at][2]; }
        public void close() { }
    }
    private static JSONObject read(Rows rows) throws Exception { return ContactsRead.readRows(rows, new Control(), 123456); }

    @Test public void onlyExistingReadTaskWithEmptyParamsIsAccepted() throws Exception {
        assertEquals(0, ContactsRead.validate("contacts_read", new JSONObject()).length());
        for (String type : new String[]{"contact_add", "contact_update", "contact_delete", "nexui_contacts", null}) {
            try { ContactsRead.validate(type, new JSONObject()); fail(); } catch (IOException expected) { }
        }
        try { ContactsRead.validate("contacts_read", new JSONObject().put("source", "nexui")); fail(); } catch (IOException expected) { }
        try { ContactsRead.validate("contacts_read", null); fail(); } catch (IOException expected) { }
    }
    @Test public void phoneRowsKeepDistinctIdsAndUnmodifiedPhoneText() throws Exception {
        JSONObject result = read(new Rows(new Object[]{11L, "甲", "+61 01"}, new Object[]{12L, "甲", "+61 01"}));
        assertEquals("android_contacts_phone_rows", result.getString("source"));
        assertEquals("NOT_VERIFIED", result.getString("nexui_equivalence"));
        JSONObject contacts = result.getJSONObject("contacts");
        assertEquals(2, contacts.getJSONArray("items").length());
        assertEquals("+61 01", contacts.getJSONArray("items").getJSONObject(1).getString("phone"));
        assertEquals(12, contacts.getJSONArray("items").getJSONObject(1).getLong("id"));
        assertFalse(contacts.getBoolean("truncated"));
    }
    @Test public void emptyProviderAndNullTextAreNotMissingProvider() throws Exception {
        assertEquals(0, read(new Rows()).getJSONObject("contacts").getJSONArray("items").length());
        JSONObject item = read(new Rows(new Object[]{1L, null, null})).getJSONObject("contacts").getJSONArray("items").getJSONObject(0);
        assertEquals("", item.getString("name")); assertEquals("", item.getString("phone"));
    }
    @Test public void duplicateInvalidOrUnsafeIdsFailWholeRead() throws Exception {
        for (Rows rows : new Rows[]{new Rows(new Object[]{0L, "", ""}), new Rows(new Object[]{-1L, "", ""}),
                new Rows(new Object[]{9007199254740992L, "", ""}),
                new Rows(new Object[]{1L, "甲", "1"}, new Object[]{1L, "乙", "2"})}) {
            try { read(rows); fail(); } catch (IOException expected) { }
        }
    }
    @Test public void tooLongFieldsAreRejectedRatherThanSilentlyCut() throws Exception {
        for (Rows rows : new Rows[]{new Rows(new Object[]{1L, repeat(501), ""}), new Rows(new Object[]{1L, "", repeat(201)})}) {
            try { read(rows); fail(); } catch (IOException expected) { }
        }
    }
    @Test public void escapedTextFitsReceiptAndHasExplicitPartialMarker() throws Exception {
        Object[][] rows = new Object[1001][3];
        for (int i = 0; i < rows.length; i++) rows[i] = new Object[]{(long) i + 1, new String(new char[500]).replace('\0', '"'), repeat(200)};
        JSONObject result = read(new Rows(rows));
        assertTrue(result.getJSONObject("contacts").getBoolean("truncated"));
        assertEquals("receipt_limit", result.getString("truncation_reason"));
        assertTrue(result.toString().length() < 16000);
        assertTrue(result.getJSONObject("contacts").getJSONArray("items").length() > 0);
    }
    @Test public void permissionFailureNeverBecomesEmptySuccess() throws Exception {
        Rows denied = new Rows() { public boolean next() { throw new SecurityException("读取被拒绝"); } };
        try { read(denied); fail(); } catch (SecurityException expected) { }
    }
    @Test public void cancellationAfterSomeRowsDoesNotReturnPartialSuccess() throws Exception {
        Control control = new Control(); control.cancelAt = 2;
        Rows rows = new Rows(new Object[]{1L, "甲", "1"}, new Object[]{2L, "乙", "2"});
        try { ContactsRead.readRows(rows, control, 123456); fail(); } catch (InterruptedException expected) { }
        assertEquals(0, rows.at);
    }
    @Test public void invalidClockAndMissingControlAreRejected() throws Exception {
        for (long time : new long[]{0, -1, 9007199254740992L}) {
            try { ContactsRead.readRows(new Rows(), new Control(), time); fail(); } catch (IOException expected) { }
        }
        try { ContactsRead.readRows(new Rows(), null, 1); fail(); } catch (IOException expected) { }
    }
    @Test public void vendorRecordRetainsNativeShapeWithoutInventingPhoneRows() throws Exception {
        JSONObject raw = new JSONObject().put("mLookup", "known-fixture-key").put("mId", 11).put("mNumbers", new org.json.JSONArray().put("123"));
        JSONObject result = ContactsRead.vendorResult(raw.toString(), 123456);
        assertEquals(raw.toString(), result.getJSONObject("record").toString());
        assertTrue(result.getBoolean("found")); assertFalse(result.getBoolean("list_complete"));
        assertFalse(result.has("contacts")); assertFalse(result.has("items"));
        assertEquals("NOT_VERIFIED", result.getString("android_equivalence"));
    }
    @Test public void vendorEmptyMalformedAndOversizeRemainDistinct() throws Exception {
        assertEquals("empty_cursor", ContactsRead.vendorResult(null, 1).getString("query_result"));
        assertEquals("json_null", ContactsRead.vendorResult("null", 1).getString("query_result"));
        for (String raw : new String[]{"[]", "bad-json", "{} trailing", repeat(8001)}) {
            try { ContactsRead.vendorResult(raw, 1); fail(); } catch (Exception expected) { }
        }
    }
    @Test public void helperRequiresExplicitReadModeAndLookup() throws Exception {
        assertEquals("inventory", ContactsReadCommand.validateArgs(new String[]{"inventory"}));
        assertEquals("android", ContactsReadCommand.validateArgs(new String[]{"android"}));
        assertEquals("nexui-lookup", ContactsReadCommand.validateArgs(new String[]{"nexui-lookup", "already-observed-key"}));
        for (String[] args : new String[][]{{}, {"delete"}, {"android", "extra"}, {"nexui-lookup"}, {"nexui-lookup", ""}, {"nexui-lookup", "a\nb"}}) {
            try { ContactsReadCommand.validateArgs(args); fail(); } catch (IOException expected) { }
        }
    }
    @Test public void exportWebContactFixture() throws Exception {
        JSONObject result = read(new Rows(new Object[]{1L, "合成联系人", "+61 000"}));
        String evidence = System.getProperty("management.evidence");
        if (evidence != null) java.nio.file.Files.write(java.nio.file.Paths.get(evidence, "contacts-web-fixture.json"),
                result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE_NEW);
        assertTrue(result.getBoolean("read_only"));
    }
    private static String repeat(int length) { return new String(new char[length]).replace('\0', 'x'); }
}
