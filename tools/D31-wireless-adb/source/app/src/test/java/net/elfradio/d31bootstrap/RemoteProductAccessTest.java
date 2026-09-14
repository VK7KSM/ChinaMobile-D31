package net.elfradio.d31bootstrap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class RemoteProductAccessTest {
    private static final String GUARD = "net.elfradio.d31zelloguard/"
            + "net.elfradio.d31zelloguard.GuardAccessibilityService";
    private static final String ENABLED = "setting:accessibility_enabled";
    private static final String SELECTED = "accessibility_service:" + GUARD;
    private static final String BOUND = "accessibility_bound:" + GUARD;
    private static final String PRIVATE_DATA = "synthetic-private-detail";
    private static final List<String> IDS = Arrays.asList(
            "appop:net.elfradio.d31phone.debug:READ_SMS",
            "appop:net.elfradio.d31phone.debug:SEND_SMS",
            "appop:net.elfradio.d31phone.debug:WRITE_SMS",
            "appop:com.starnet.getnumber:SEND_SMS",
            "appop:net.elfradio.d31bootstrap:CAMERA",
            "appop:net.elfradio.d31bootstrap:RECORD_AUDIO",
            "appop:net.elfradio.d31bootstrap:FINE_LOCATION",
            "appop:net.elfradio.d31bootstrap:COARSE_LOCATION", ENABLED, SELECTED, BOUND);

    private static class Access implements RemoteProductAccess.Access {
        final List<String> reads = new ArrayList<>();
        final Set<String> failures = new HashSet<>();
        final Map<String, String> settings = new HashMap<>();
        int mode;
        boolean bound;

        Access() {
            settings.put("accessibility_enabled", "1");
            settings.put("enabled_accessibility_services", GUARD);
        }

        private void read(String id) throws IOException {
            reads.add(id);
            if (failures.contains(id)) throw new IOException(PRIVATE_DATA);
        }

        @Override public int appOp(String pkg, String op) throws Exception {
            read("appop:" + pkg + ":" + op);
            return mode;
        }

        @Override public String secureSetting(String key) throws Exception {
            if ("accessibility_enabled".equals(key)) read(ENABLED);
            else if ("enabled_accessibility_services".equals(key)) read(SELECTED);
            else throw new AssertionError("Unexpected setting read");
            return settings.get(key);
        }

        @Override public boolean accessibilityBound(String component) throws Exception {
            read("accessibility_bound:" + component);
            return bound;
        }
    }

    private static Set<String> keys(JSONObject object) {
        Set<String> result = new HashSet<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }

    private static Set<String> names(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static JSONObject row(JSONObject result, String id) throws Exception {
        JSONArray facts = result.getJSONArray("facts");
        for (int i = 0; i < facts.length(); i++) {
            JSONObject fact = facts.getJSONObject(i);
            if (id.equals(fact.getString("id"))) return fact;
        }
        throw new AssertionError("Missing fixed fact");
    }

    private static void observed(JSONObject result, String id, boolean value) throws Exception {
        JSONObject fact = row(result, id);
        assertEquals("OBSERVED", fact.getString("state"));
        assertEquals(names("id", "source", "state", "value"), keys(fact));
        assertEquals(Boolean.valueOf(value), fact.get("value"));
    }

    private static void failed(JSONObject result, String id) throws Exception {
        JSONObject fact = row(result, id);
        assertEquals("READ_FAILED", fact.getString("state"));
        assertEquals(names("id", "source", "state", "reason"), keys(fact));
        assertFalse(fact.toString().contains(PRIVATE_DATA));
    }

    private static void bounded(JSONObject result, int count) throws Exception {
        assertEquals(11, result.getInt("total"));
        assertEquals(11, result.getJSONArray("facts").length());
        assertEquals(count, result.getInt("observed"));
        assertEquals(count == 11 ? "COMPLETE" : "PARTIAL", result.getString("status"));
        assertFalse(result.getBoolean("atomicSnapshot"));
        assertEquals("NOT_CHECKED", result.getString("executionState"));
        assertEquals("NOT_ASSESSED", result.getString("systemConsistency"));
        assertFalse(result.getBoolean("repairPlanGenerated"));
    }

    @Test public void elevenFixedFactsHaveIndependentCatalogAndExplicitSources() throws Exception {
        Access access = new Access();
        JSONObject result = RemoteProductAccess.collect(access, 1234);
        assertEquals(names("schemaVersion", "catalog", "capturedAtMs", "userId", "facts", "observed",
                "total", "status", "atomicSnapshot", "executionState", "systemConsistency", "repairPlanGenerated"), keys(result));
        assertEquals(1, result.getInt("schemaVersion"));
        assertEquals("d31-product-access-1", result.getString("catalog"));
        assertEquals(1234L, result.getLong("capturedAtMs"));
        assertEquals(0, result.getInt("userId"));
        JSONArray facts = result.getJSONArray("facts");
        List<String> actual = new ArrayList<>();
        for (int i = 0; i < facts.length(); i++) {
            JSONObject fact = facts.getJSONObject(i);
            actual.add(fact.getString("id"));
            assertEquals(i < 8 ? "app_ops" : i == 8 ? "secure.accessibility_enabled"
                    : i == 9 ? "secure.enabled_accessibility_services" : "accessibility_manager", fact.getString("source"));
            assertEquals(names("id", "source", "state", "value"), keys(fact));
        }
        assertEquals(IDS, actual);
        assertEquals(11, new HashSet<>(actual).size());
        assertEquals(IDS, access.reads);
        bounded(result, 11);
    }

    @Test public void allFourModesRemainRawAndDefaultIsNotAllowed() throws Exception {
        String[] expected = {"allowed", "ignored", "errored", "default"};
        for (int mode = 0; mode <= 3; mode++) {
            Access access = new Access();
            access.mode = mode;
            JSONObject result = RemoteProductAccess.collect(access, 0);
            for (String id : IDS.subList(0, 8)) {
                JSONObject value = row(result, id).getJSONObject("value");
                assertEquals(names("mode", "modeName"), keys(value));
                assertEquals(Integer.valueOf(mode), value.get("mode"));
                assertEquals(expected[mode], value.getString("modeName"));
                assertEquals(mode == 0, "allowed".equals(value.getString("modeName")));
            }
            bounded(result, 11);
        }
    }

    @Test public void unsupportedModesAreUnknownWithoutLosingAuxiliaryFacts() throws Exception {
        for (int mode : new int[]{-1, 4, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            Access access = new Access();
            access.mode = mode;
            JSONObject result = RemoteProductAccess.collect(access, 0);
            for (String id : IDS.subList(0, 8)) failed(result, id);
            observed(result, ENABLED, true);
            observed(result, SELECTED, true);
            observed(result, BOUND, false);
            assertEquals(IDS, access.reads);
            bounded(result, 3);
        }
    }

    @Test public void accessibilityEnabledAcceptsOnlyExactZeroAndOne() throws Exception {
        for (String setting : new String[]{"0", "1"}) {
            Access access = new Access();
            access.settings.put("accessibility_enabled", setting);
            JSONObject result = RemoteProductAccess.collect(access, 0);
            observed(result, ENABLED, "1".equals(setting));
            bounded(result, 11);
        }
        for (String setting : new String[]{null, "", "true", "false", "01", "2", "-1", " 1", "1\n", PRIVATE_DATA}) {
            Access access = new Access();
            access.settings.put("accessibility_enabled", setting);
            JSONObject result = RemoteProductAccess.collect(access, 0);
            failed(result, ENABLED);
            observed(result, SELECTED, true);
            assertFalse(result.toString().contains(PRIVATE_DATA));
            bounded(result, 10);
        }
    }

    @Test public void serviceProjectionNormalizesShortNameAndHidesOtherServices() throws Exception {
        Access access = new Access();
        access.settings.put("enabled_accessibility_services",
                "synthetic.private/.Other:net.elfradio.d31zelloguard/.GuardAccessibilityService");
        JSONObject result = RemoteProductAccess.collect(access, 0);
        observed(result, SELECTED, true);
        assertFalse(result.toString().contains("synthetic.private"));
        bounded(result, 11);
        for (String setting : new String[]{null, "", GUARD + "Suffix", "synthetic.private/.Other"}) {
            access.settings.put("enabled_accessibility_services", setting);
            result = RemoteProductAccess.collect(access, 0);
            observed(result, SELECTED, false);
            bounded(result, 11);
        }
    }

    @Test public void malformedAndOverlongServiceListsAreUnknownEvenAfterMatching() throws Exception {
        char[] large = new char[16385];
        Arrays.fill(large, 'A');
        for (String setting : new String[]{GUARD + ":bad", GUARD + ":", "net.elfradio.d31zelloguard/.",
                "bad", ":" + GUARD, new String(large)}) {
            Access access = new Access();
            access.settings.put("enabled_accessibility_services", setting);
            JSONObject result = RemoteProductAccess.collect(access, 0);
            failed(result, SELECTED);
            observed(result, BOUND, false);
            bounded(result, 10);
        }
    }

    @Test public void boundStateIsReadIndependentlyAndDoesNotProveExecution() throws Exception {
        for (boolean bound : new boolean[]{false, true}) {
            Access access = new Access();
            access.bound = bound;
            access.settings.put("accessibility_enabled", "0");
            access.settings.put("enabled_accessibility_services", "");
            JSONObject result = RemoteProductAccess.collect(access, 0);
            observed(result, ENABLED, false);
            observed(result, SELECTED, false);
            observed(result, BOUND, bound);
            assertEquals(IDS, access.reads);
            bounded(result, 11);
        }
    }

    @Test public void eachFailureIsIsolatedAndDoesNotExposeDetails() throws Exception {
        for (String failure : IDS) {
            Access access = new Access();
            access.failures.add(failure);
            JSONObject result = RemoteProductAccess.collect(access, 0);
            for (String id : IDS) {
                if (id.equals(failure)) {
                    failed(result, id);
                    assertEquals("IOException", row(result, id).getString("reason"));
                } else assertEquals("OBSERVED", row(result, id).getString("state"));
            }
            assertEquals(IDS, access.reads);
            assertFalse(result.toString().contains(PRIVATE_DATA));
            bounded(result, 10);
        }
    }

    @Test public void totalFailureDoesNotClaimConsistencyOrModifyInputs() throws Exception {
        Access access = new Access();
        access.failures.addAll(IDS);
        access.settings.put("unrelated_private_setting", PRIVATE_DATA);
        Map<String, String> before = new HashMap<>(access.settings);
        JSONObject result = RemoteProductAccess.collect(access, 0);
        for (String id : IDS) failed(result, id);
        assertEquals(IDS, access.reads);
        assertEquals(before, access.settings);
        assertFalse(result.toString().contains(PRIVATE_DATA));
        bounded(result, 0);
    }

    @Test public void runtimeFailureDoesNotLeakCauseOrPreventRemainingReads() throws Exception {
        Access access = new Access() {
            @Override public int appOp(String pkg, String op) throws Exception {
                int result = super.appOp(pkg, op);
                if ("CAMERA".equals(op)) throw new IllegalStateException(PRIVATE_DATA, new IOException(PRIVATE_DATA));
                return result;
            }
        };
        JSONObject result = RemoteProductAccess.collect(access, 0);
        failed(result, IDS.get(4));
        assertEquals("IllegalStateException", row(result, IDS.get(4)).getString("reason"));
        assertFalse(result.toString().contains(PRIVATE_DATA));
        assertFalse(result.toString().contains("RemoteProductAccessTest"));
        assertEquals(IDS, access.reads);
        bounded(result, 10);
    }

    @Test public void successfulReadsDoNotChangeSettingsOrQueryOutsideAllowlist() throws Exception {
        Access access = new Access();
        access.settings.put("unrelated_private_setting", PRIVATE_DATA);
        Map<String, String> before = new HashMap<>(access.settings);
        JSONObject result = RemoteProductAccess.collect(access, 0);
        assertEquals(before, access.settings);
        assertEquals(IDS, access.reads);
        assertFalse(result.toString().contains(PRIVATE_DATA));
        bounded(result, 11);
    }

    @Test public void invalidCollectionInputsFailBeforeReadingAndZeroTimeIsValid() throws Exception {
        try {
            RemoteProductAccess.collect(null, 0);
            fail("Null access must fail");
        } catch (IllegalArgumentException expected) { }
        Access access = new Access();
        try {
            RemoteProductAccess.collect(access, -1);
            fail("Negative time must fail");
        } catch (IllegalArgumentException expected) { }
        assertTrue(access.reads.isEmpty());
        JSONObject result = RemoteProductAccess.collect(access, 0);
        assertEquals(0L, result.getLong("capturedAtMs"));
        bounded(result, 11);
    }
}
