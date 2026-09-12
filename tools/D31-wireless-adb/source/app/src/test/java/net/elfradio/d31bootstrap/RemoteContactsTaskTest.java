package net.elfradio.d31bootstrap;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteContactsTaskTest {
    private static final String HASH = repeat("a", 64), ID = repeat("b", 64);
    private static final String APK = "/data/local/d31-remote/releases/" + HASH + "/remote.apk";
    private static final String SNAP = "00000000-0000-0000-0000-000000000001";
    private static final String BOOT = "00000000-0000-0000-0000-000000000002";
    private static final String REQ = "00000000-0000-0000-0000-000000000003";
    interface Attempt { void run() throws Exception; }
    private static String repeat(String value, int count) { StringBuilder out = new StringBuilder(); while (count-- > 0) out.append(value); return out.toString(); }
    private static void rejects(Attempt action) throws Exception {
        try { action.run(); fail("应当拒绝"); } catch (IOException expected) { assertTrue(expected.getMessage().startsWith("CONTACTS_TASK_")); }
    }
    private static JSONObject params(String action) throws Exception {
        JSONObject p = new JSONObject().put("action", action);
        if ("open".equals(action)) p.put("source", "LOCAL");
        else p.put("snapshot_id", SNAP);
        if ("page".equals(action)) p.put("offset", 0).put("limit", 32);
        return p;
    }
    private static JSONObject descriptor(int count) throws Exception {
        return new JSONObject().put("ok", true).put("read_only", true).put("contact_type", "LOCAL")
                .put("source", "nexui_messenger").put("owner_package", "com.starnet.dial").put("status", "COMPLETED")
                .put("list_complete", true).put("end_observed", true).put("all_sources_complete", false)
                .put("sampled_at_ms", 1789000000000L).put("completion_scope", "SELECTED_SOURCE_ALL_CONTACTS_REPLY")
                .put("elapsed_ms", 100).put("frames_received", 1).put("received_chars", 200)
                .put("start_observed", false).put("max_records", 4096).put("max_frames", 128)
                .put("max_received_chars", 1048576).put("wait_budget_ms", 10000)
                .put("android_equivalence", "NOT_VERIFIED").put("service_implementation", "NOT_DECRYPTED")
                .put("snapshot_consistency", "NOT_PROVIDED_BY_VENDOR").put("page_consistency", "IMMUTABLE_RECEIVED_REPLY")
                .put("storage", "APP_PROCESS_MEMORY").put("cross_boot", false).put("cross_process_restart", false)
                .put("snapshot_id", SNAP).put("record_count", count).put("retention_ms", 120000).put("max_page_records", 32)
                .put("contact_values_emitted", false);
    }
    private static JSONObject raw(String action, int count) throws Exception {
        JSONObject r = new JSONObject().put("schemaVersion", 1).put("ok", true).put("readOnly", true)
                .put("maintenanceGatePassed", true).put("activeApkHashMatched", true).put("expectedApkSha256", HASH)
                .put("contact_type", "LOCAL").put("ownerPackage", "com.starnet.dial").put("all_sources_complete", false);
        if ("open".equals(action)) return r.put("kind", "NEXUI_APP_LOCAL_METADATA").put("operation", "read_local_pages")
                .put("operation_id", ID).put("remoteOutcomeKnown", true).put("unbindConfirmed", true).put("replyChannelClosed", true)
                .put("operation_request_id", REQ).put("page_snapshot", descriptor(count)).put("local", descriptor(count))
                .put("app_operation", new JSONObject().put("operation_id", ID).put("operation", "read-local-pages")
                        .put("apk_sha256", HASH).put("state", "RELEASED").put("reservation_released", true)
                        .put("release_reason", "MATCHED_RELEASE_RECEIPT").put("managed_media", false).put("network_write", false)
                        .put("record_boot_id", BOOT).put("current_boot_id", BOOT).put("operation_request_id", REQ));
        r.put("kind", "NEXUI_APP_LOCAL_PAGE").put("source", "nexui_messenger").put("vendorRequestSent", false)
                .put("snapshot_id", SNAP).put("snapshot_closed", "close".equals(action));
        if ("page".equals(action)) {
            JSONArray items = new JSONArray();
            for (int i = 0; i < Math.min(count, 32); i++) items.put(new JSONObject().put("mId", i).put("mName", "SYNTHETIC_PRIVATE_" + i));
            r.put("page", descriptor(count).put("items", items).put("offset", 0).put("next_offset", items.length())
                    .put("has_more", items.length() < count).put("page_complete", items.length() == count)
                    .put("contact_values_emitted", items.length() != 0).put("cursor_scope", "SAME_SNAPSHOT_ONLY"));
        }
        return r;
    }
    private static JSONObject outcome(JSONObject envelope) throws Exception {
        return new JSONObject().put("id", ID).put("state", "completed").put("truncated", false)
                .put("exit_code", envelope.getJSONObject("data").getBoolean("ok") ? 0 : 1).put("output", envelope.toString() + "\n");
    }
    private static JSONObject execute(String action, JSONObject raw) throws Exception {
        return RemoteContactsTask.execute(ID, HASH, params(action), args -> raw);
    }

    @Test public void exactActionsHaveDetachedStrictParameters() throws Exception {
        for (String action : new String[] {"open", "page", "close"}) {
            JSONObject p = params(action), result = RemoteContactsTask.validate(p);
            assertTrue(RemoteProtocol.sameJson(p, result)); p.put("action", "CHANGED"); assertEquals(action, result.getString("action"));
            assertTrue(RemoteProtocol.sameJson(result, RemoteContactsTask.validateParams(params(action))));
            assertEquals("open".equals(action), result.has("source"));
        }
        assertEquals("contacts_page", RemoteContactsTask.TYPE);
        assertEquals("managed_contacts_page_v1", RemoteContactsTask.CAPABILITY);
        for (String action : new String[] {"page", "close"})
            rejects(() -> RemoteContactsTask.validate(params(action).put("source", "LOCAL")));
    }
    @Test public void rejectsAdditionalSourcesAndWrites() throws Exception {
        for (String source : new String[] {"local", "ALL", "BLUETOOTH", "EAB", "CONFER", "FAVORITE", "ANDROID"})
            rejects(() -> RemoteContactsTask.validateParams(params("open").put("source", source)));
        for (String action : new String[] {"refresh", "add", "update", "delete", "OPEN", ""})
            rejects(() -> RemoteContactsTask.validateParams(new JSONObject().put("action", action).put("source", "LOCAL")));
    }
    @Test public void rejectsMissingExtraOrNullFieldsWithoutDefaults() throws Exception {
        for (String action : new String[] {"open", "page", "close"}) {
            JSONObject p = params(action);
            java.util.Iterator<String> fields = p.keys();
            while (fields.hasNext()) {
                String key = fields.next();
                JSONObject missing = new JSONObject(p.toString()); missing.remove(key);
                rejects(() -> RemoteContactsTask.validateParams(missing));
                rejects(() -> RemoteContactsTask.validateParams(new JSONObject(p.toString()).put(key, JSONObject.NULL)));
            }
            rejects(() -> RemoteContactsTask.validateParams(new JSONObject(p.toString()).put("ttl_ms", 120000)));
        }
        rejects(() -> RemoteContactsTask.validateParams(null));
    }
    @Test public void onlyIntegerNumbersWithinPageBoundaries() throws Exception {
        for (Object limit : new Object[] {0, 33, -1, 1.0, "1", true, Long.MAX_VALUE, new JSONObject()})
            rejects(() -> RemoteContactsTask.validateParams(params("page").put("limit", limit)));
        for (Object offset : new Object[] {-1, 4097, 0.0, "0", false, Long.MAX_VALUE})
            rejects(() -> RemoteContactsTask.validateParams(params("page").put("offset", offset)));
        assertEquals(4096, RemoteContactsTask.validateParams(params("page").put("offset", 4096L)).getInt("offset"));
    }
    @Test public void identifiersAndPayloadPathsCannotInjectCommands() throws Exception {
        for (String snap : new String[] {"", "../x", SNAP + "\n", "$(id)", "A" + SNAP.substring(1)})
            rejects(() -> RemoteContactsTask.validateParams(params("close").put("snapshot_id", snap)));
        rejects(() -> RemoteContactsTask.command(APK + "'", ID, params("open")));
        rejects(() -> RemoteContactsTask.command(APK, "cloud-task", params("open")));
        rejects(() -> RemoteContactsTask.command("/system/priv-app/unknown.apk", ID, params("open")));
    }
    @Test public void commandIsDeterministicAndOpenUsesOriginalLocalTaskId() throws Exception {
        String command = RemoteContactsTask.command(APK, ID, params("open"));
        assertEquals(command, RemoteContactsTask.command(APK, ID, params("open")));
        assertTrue(command.endsWith(HASH + " " + ID + " open"));
        assertFalse(command.contains("recover")); assertFalse(command.contains("ContactsNexui"));
        RemoteContactsTask.execute(ID, HASH, params("open"), args -> {
            assertArrayEquals(new String[] {"open", HASH, ID}, args); return raw("open", 3);
        });
    }
    @Test public void pageAndCloseCallExistingBridgeCommandExactlyOnce() throws Exception {
        for (String action : new String[] {"page", "close"}) {
            AtomicInteger calls = new AtomicInteger();
            JSONObject envelope = RemoteContactsTask.execute(ID, HASH, params(action), args -> {
                calls.incrementAndGet();
                assertArrayEquals("page".equals(action) ? new String[] {action, HASH, SNAP, "0", "32"} : new String[] {action, HASH, SNAP}, args);
                return raw(action, 32);
            });
            assertTrue(envelope.getJSONObject("data").getBoolean("ok")); assertEquals(1, calls.get());
        }
    }
    @Test public void resultCanBeReadRepeatedlyWithoutInvokingOperation() throws Exception {
        AtomicInteger calls = new AtomicInteger(); JSONObject p = params("open");
        JSONObject envelope = RemoteContactsTask.execute(ID, HASH, p, args -> { calls.incrementAndGet(); return raw("open", 33); });
        JSONObject saved = outcome(envelope);
        JSONObject first = RemoteContactsTask.readResult(p, ID, APK, saved), again = RemoteContactsTask.readResult(p, ID, APK, saved);
        assertTrue(RemoteProtocol.sameJson(first, again)); assertEquals(1, calls.get());
        assertEquals(120000, first.getInt("retention_ms")); assertEquals(32, first.getInt("max_page_records"));
        assertFalse(first.has("items"));
    }
    @Test public void upTo32ItemsReturnWithOriginalVendorFields() throws Exception {
        JSONObject result = RemoteContactsTask.readResult(params("page"), ID, APK, outcome(execute("page", raw("page", 33))));
        assertEquals(32, result.getJSONArray("items").length()); assertTrue(result.getBoolean("has_more"));
        assertEquals(32, result.getInt("next_offset")); assertEquals(33, result.getInt("record_count"));
        assertEquals("SYNTHETIC_PRIVATE_0", result.getJSONArray("items").getJSONObject(0).getString("mName"));
    }
    @Test public void emptyCompleteListIsSuccessButMissingOrErrorIsNeverEmpty() throws Exception {
        JSONObject empty = RemoteContactsTask.readResult(params("page"), ID, APK, outcome(execute("page", raw("page", 0))));
        assertTrue(empty.getBoolean("ok")); assertEquals(0, empty.getJSONArray("items").length());
        for (String code : new String[] {"CONTACTS_SNAPSHOT_GONE", "CONTACTS_MAINTENANCE_BUSY", "CONTACTS_CANCELLED"}) {
            JSONObject bad = execute("page", new JSONObject().put("ok", false).put("state", code).put("page", raw("page", 2)));
            JSONObject data = RemoteContactsTask.readResult(params("page"), ID, APK, outcome(bad));
            assertFalse(data.getBoolean("ok")); assertEquals(code, data.getString("code")); assertFalse(data.has("items"));
        }
        assertFalse(execute("page", null).getJSONObject("data").getBoolean("ok"));
    }
    @Test public void wrongOperationOrUnreleasedOpenCannotPublishSnapshot() throws Exception {
        for (String field : new String[] {"operation", "reservation_released", "current_boot_id", "operation_request_id"}) {
            JSONObject r = raw("open", 2), op = r.getJSONObject("app_operation");
            op.put(field, "reservation_released".equals(field) ? false : "WRONG");
            JSONObject data = execute("open", r).getJSONObject("data");
            assertFalse(data.getBoolean("ok")); assertFalse(data.has("snapshot_id")); assertFalse(data.has("release_confirmed"));
        }
    }
    @Test public void snapshotSourceTtlAndIdentityAreNotNegotiable() throws Exception {
        for (String field : new String[] {"snapshot_id", "contact_type", "retention_ms", "cross_boot"}) {
            JSONObject r = raw("page", 2);
            r.getJSONObject("page").put(field, "retention_ms".equals(field) ? 120001 : "WRONG");
            assertFalse(execute("page", r).getJSONObject("data").getBoolean("ok"));
        }
        JSONObject r = raw("close", 0).put("expectedApkSha256", ID);
        assertFalse(execute("close", r).getJSONObject("data").getBoolean("ok"));
    }
    @Test public void invalidCursorOrTooManyItemsCannotBeReportedAsComplete() throws Exception {
        for (String field : new String[] {"offset", "next_offset", "has_more", "page_complete", "contact_values_emitted"}) {
            JSONObject r = raw("page", 33);
            r.getJSONObject("page").put(field, "offset".equals(field) || "next_offset".equals(field) ? 1 : "true");
            assertFalse(execute("page", r).getJSONObject("data").getBoolean("ok"));
        }
        JSONObject r = raw("page", 32); r.getJSONObject("page").getJSONArray("items").put(new JSONObject());
        assertFalse(execute("page", r).getJSONObject("data").getBoolean("ok"));
    }
    @Test public void closeRequiresActualConfirmationAndNoPage() throws Exception {
        assertTrue(execute("close", raw("close", 0)).getJSONObject("data").getBoolean("snapshot_closed"));
        assertFalse(execute("close", raw("close", 0).put("snapshot_closed", false)).getJSONObject("data").getBoolean("ok"));
        assertFalse(execute("close", raw("close", 0).put("page", new JSONObject())).getJSONObject("data").getBoolean("ok"));
    }
    @Test public void resultLimitFailsWithoutTruncatingOrLeakingContent() throws Exception {
        JSONObject r = raw("page", 1); r.getJSONObject("page").getJSONArray("items").getJSONObject(0).put("mName", repeat("SYNTHETIC_PRIVATE_", 2000));
        JSONObject envelope = execute("page", r), data = envelope.getJSONObject("data");
        assertFalse(data.getBoolean("ok")); assertEquals("CONTACTS_TASK_RESULT_LIMIT", data.getString("code"));
        assertFalse(envelope.toString().contains("SYNTHETIC_PRIVATE_")); assertTrue(envelope.toString().length() < 1024);
    }
    @Test public void unknownExceptionsEmitOnlyFixedErrorAndNoPrivateMessage() throws Exception {
        JSONObject envelope = RemoteContactsTask.execute(ID, HASH, params("page"), args -> { throw new IOException("SYNTHETIC_PRIVATE_0"); });
        assertEquals("CONTACTS_TASK_FAILED", envelope.getJSONObject("data").getString("code"));
        assertFalse(envelope.toString().contains("SYNTHETIC_PRIVATE_0"));
    }
    @Test public void invalidOrCancelledInputDoesNotCallBridge() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        rejects(() -> RemoteContactsTask.execute(ID, HASH, params("page").put("limit", 33), args -> { calls.incrementAndGet(); return null; }));
        Thread.currentThread().interrupt();
        try {
            JSONObject data = RemoteContactsTask.execute(ID, HASH, params("open"), args -> { calls.incrementAndGet(); return null; }).getJSONObject("data");
            assertEquals("CONTACTS_TASK_CANCELLED", data.getString("code")); assertEquals(0, calls.get());
        } finally { Thread.interrupted(); }
    }
    @Test public void outcomeIdentityParamsAndExitMustMatchOriginalTask() throws Exception {
        JSONObject valid = outcome(execute("page", raw("page", 2)));
        for (String field : new String[] {"id", "state", "truncated", "exit_code"}) {
            JSONObject bad = new JSONObject(valid.toString()).put(field, "truncated".equals(field) ? true : "WRONG");
            rejects(() -> RemoteContactsTask.result(params("page"), ID, APK, bad));
        }
        rejects(() -> RemoteContactsTask.result(params("page").put("offset", 1), ID, APK, valid));
        rejects(() -> RemoteContactsTask.result(params("close"), ID, APK, valid));
        JSONObject changed = new JSONObject(valid.getString("output")).put("local_task_id", HASH);
        rejects(() -> RemoteContactsTask.result(params("page"), ID, APK, new JSONObject(valid.toString()).put("output", changed.toString())));
        rejects(() -> RemoteContactsTask.result(params("page"), ID, APK, new JSONObject(valid.toString()).put("exit_code", 1)));
    }
    @Test public void oversizedOrPartialStoredOutputCannotBecomeSuccessfulData() throws Exception {
        JSONObject valid = outcome(execute("page", raw("page", 2)));
        rejects(() -> RemoteContactsTask.result(params("page"), ID, APK, new JSONObject(valid.toString()).put("output", repeat("x", 16001))));
        JSONObject changed = new JSONObject(valid.getString("output")); changed.getJSONObject("data").put("next_offset", 100);
        rejects(() -> RemoteContactsTask.result(params("page"), ID, APK, new JSONObject(valid.toString()).put("output", changed.toString())));
    }

    @Test public void frozenResultWrapperPreservesDescriptorAndNeverUsesLegacyFields() throws Exception {
        for (String action : new String[] {"open", "page", "close"}) {
            JSONObject r = raw(action, 33), saved = outcome(execute(action, r));
            JSONObject result = RemoteContactsTask.result(params(action), ID, APK, saved);
            assertEquals(1, result.length());
            JSONObject data = result.getJSONObject("contacts_page");
            assertEquals(1, data.getInt("schema_version")); assertEquals(action, data.getString("action"));
            assertEquals("nexui_messenger", data.getString("source")); assertEquals("LOCAL", data.getString("contact_type"));
            assertTrue(data.getBoolean("read_only")); assertTrue(data.getBoolean("ok"));
            for (String old : new String[] {"total", "max_limit", "closed", "release_confirmed", "operation_id", "limit"}) assertFalse(data.has(old));
            if (!"close".equals(action)) {
                JSONObject expected = new JSONObject(r.getJSONObject("open".equals(action) ? "page_snapshot" : "page").toString())
                        .put("schema_version", 1).put("action", action);
                assertTrue(RemoteProtocol.sameJson(expected, data));
            } else assertTrue(data.getBoolean("snapshot_closed"));
            if (!"page".equals(action)) assertFalse(data.has("items"));
        }
    }

    @Test public void wrappedFailuresRetainOriginalSnapshotAndCannotContainContactValues() throws Exception {
        for (String action : new String[] {"open", "page", "close"}) {
            JSONObject saved = outcome(execute(action, new JSONObject().put("ok", false).put("state", "CONTACTS_SNAPSHOT_GONE")));
            JSONObject data = RemoteContactsTask.result(params(action), ID, APK, saved).getJSONObject("contacts_page");
            assertFalse(data.getBoolean("ok")); assertEquals("CONTACTS_SNAPSHOT_GONE", data.getString("code"));
            assertEquals(!"open".equals(action), data.has("snapshot_id"));
            if (data.has("snapshot_id")) assertEquals(SNAP, data.getString("snapshot_id"));
            assertFalse(data.has("items")); assertFalse(data.has("snapshot_closed"));
        }
    }

    @Test public void byteBudgetShortPageUsesActualCursorAndPreservesUnknownVendorFields() throws Exception {
        JSONObject r = raw("page", 33), page = r.getJSONObject("page");
        JSONObject item = new JSONObject().put("mName", "SYNTHETIC_PRIVATE_0").put("mNumbers", new JSONArray().put("TEST_ONLY"))
                .put("unknown_vendor_field", new JSONObject().put("version", 7));
        page.put("items", new JSONArray().put(item)).put("next_offset", 1);
        JSONObject saved = outcome(execute("page", r));
        JSONObject data = RemoteContactsTask.readResult(params("page"), ID, APK, saved);
        assertEquals(1, data.getInt("next_offset")); assertTrue(data.getBoolean("has_more"));
        assertTrue(RemoteProtocol.sameJson(item, data.getJSONArray("items").getJSONObject(0)));
        data.getJSONArray("items").getJSONObject(0).put("mName", "CHANGED");
        assertEquals("SYNTHETIC_PRIVATE_0", RemoteContactsTask.readResult(params("page"), ID, APK, saved)
                .getJSONArray("items").getJSONObject(0).getString("mName"));
    }

    @Test public void metadataOrCursorTamperingInSavedOutcomeIsRejected() throws Exception {
        for (String field : new String[] {"source", "contact_type", "owner_package", "completion_scope", "sampled_at_ms",
                "retention_ms", "max_page_records", "page_consistency", "cross_boot", "page_complete", "cursor_scope"}) {
            JSONObject envelope = execute("page", raw("page", 33)); envelope.getJSONObject("data").put(field, "WRONG");
            rejects(() -> RemoteContactsTask.readResult(params("page"), ID, APK, outcome(envelope)));
        }
        JSONObject r = raw("page", 33);
        r.getJSONObject("page").put("items", new JSONArray()).put("next_offset", 0).put("contact_values_emitted", false);
        assertFalse(execute("page", r).getJSONObject("data").getBoolean("ok"));
        JSONObject open = execute("open", raw("open", 1)); open.getJSONObject("data").put("items", new JSONArray());
        rejects(() -> RemoteContactsTask.readResult(params("open"), ID, APK, outcome(open)));
    }

    @Test public void trailingOutputAndMismatchedEnvelopeAreRejected() throws Exception {
        JSONObject saved = outcome(execute("page", raw("page", 2)));
        for (String suffix : new String[] {"{}", "SYNTHETIC_PRIVATE_TRAILING"})
            rejects(() -> RemoteContactsTask.readResult(params("page"), ID, APK,
                    new JSONObject(saved.toString()).put("output", saved.getString("output") + suffix)));
        JSONObject envelope = new JSONObject(saved.getString("output")).put("apk_sha256", ID);
        rejects(() -> RemoteContactsTask.readResult(params("page"), ID, APK, outcome(envelope)));
    }

    @Test public void multibytePageFitsCompleteCommandOutputIncludingNewline() throws Exception {
        JSONObject r = raw("page", 2);
        for (int i = 0; i < 2; i++) r.getJSONObject("page").getJSONArray("items").getJSONObject(i).put("mName", repeat("\u4e2d", 5500));
        JSONObject saved = outcome(execute("page", r));
        assertTrue(RemoteContactsTask.readResult(params("page"), ID, APK, saved).getBoolean("ok"));
        assertTrue(saved.getString("output").length() <= RemoteContactsTask.MAX_RESULT_CHARS);
        assertTrue(saved.getString("output").getBytes("UTF-8").length <= RemoteContactsTask.MAX_RESULT_BYTES);
    }

    @Test public void publicFailureResultBindsCancellationAndSanitizesRejectedParameters() throws Exception {
        for (String action : new String[] {"open", "page", "close"}) {
            JSONObject result = RemoteContactsTask.failureResult(params(action), "CONTACTS_TASK_CANCELLED");
            assertEquals(1, result.length()); JSONObject data = result.getJSONObject("contacts_page");
            assertEquals(action, data.getString("action")); assertFalse(data.getBoolean("ok"));
            assertEquals("CONTACTS_TASK_CANCELLED", data.getString("code"));
            assertEquals(!"open".equals(action), data.has("snapshot_id"));
            assertFalse(data.has("items")); assertFalse(data.has("snapshot_closed"));
        }
        JSONObject invalid = RemoteContactsTask.failureResult(params("page").put("limit", 33), "CONTACTS_TASK_CANCELLED").getJSONObject("contacts_page");
        assertEquals("page", invalid.getString("action")); assertEquals(SNAP, invalid.getString("snapshot_id"));
        assertEquals("CONTACTS_TASK_PARAMS_INVALID", invalid.getString("code"));
        for (JSONObject p : new JSONObject[] {null, new JSONObject(), params("page").put("snapshot_id", "SYNTHETIC_PRIVATE"),
                params("open").put("source", "ALL"), params("open").put("action", "SYNTHETIC_PRIVATE")}) {
            JSONObject data = RemoteContactsTask.failureResult(p, "CONTACTS_TASK_CANCELLED").getJSONObject("contacts_page");
            assertEquals("CONTACTS_TASK_PARAMS_INVALID", data.getString("code"));
            assertFalse(data.has("snapshot_id")); assertFalse(data.toString().contains("SYNTHETIC_PRIVATE"));
        }
        assertEquals("CONTACTS_TASK_FAILED", RemoteContactsTask.failureResult(params("close"), "SYNTHETIC_PRIVATE").getJSONObject("contacts_page").getString("code"));
        assertEquals("CONTACTS_TASK_FAILED", RemoteContactsTask.failureResult(params("close"), null).getJSONObject("contacts_page").getString("code"));
    }

    @Test public void appOperationFailuresUseWebAcceptedNamespace() throws Exception {
        JSONObject envelope = execute("open", new JSONObject().put("ok", false).put("state", "APP_OPERATION_BUSY"));
        JSONObject result = RemoteContactsTask.result(params("open"), ID, APK, outcome(envelope));
        assertEquals("CONTACTS_APP_OPERATION_BUSY", result.getJSONObject("contacts_page").getString("code"));
        assertTrue(RemoteProtocol.sameJson(result, RemoteContactsTask.failureResult(params("open"), "APP_OPERATION_BUSY")));
    }

    @Test public void vendorObjectsRespectWebSizeAndDepthWithoutDiscardingFields() throws Exception {
        JSONObject r = raw("page", 1);
        r.getJSONObject("page").getJSONArray("items").getJSONObject(0).put("mName", repeat("x", 8192));
        assertFalse(execute("page", r).getJSONObject("data").getBoolean("ok"));
        JSONObject deep = new JSONObject().put("value", 1);
        for (int i = 0; i < 17; i++) deep = new JSONObject().put("next", deep);
        r.getJSONObject("page").put("items", new JSONArray().put(deep));
        assertFalse(execute("page", r).getJSONObject("data").getBoolean("ok"));
    }

    @Test public void frozenWebFixturesCanBeExportedWithoutDeviceOrNetwork() throws Exception {
        JSONArray cases = new JSONArray();
        for (String action : new String[] {"open", "page", "close"}) {
            JSONObject p = params(action);
            cases.put(new JSONObject().put("params", p).put("result", RemoteContactsTask.result(p, ID, APK, outcome(execute(action, raw(action, 33))))));
            for (String code : new String[] {"CONTACTS_TASK_CANCELLED", "CONTACTS_SNAPSHOT_GONE", "APP_OPERATION_BUSY"})
                cases.put(new JSONObject().put("params", p).put("result", RemoteContactsTask.failureResult(p, code)));
        }
        String destination = System.getProperty("contacts.task.fixtures");
        if (destination != null) java.nio.file.Files.write(java.nio.file.Paths.get(destination), cases.toString().getBytes("UTF-8"), java.nio.file.StandardOpenOption.CREATE_NEW);
        assertEquals(12, cases.length());
    }
}
