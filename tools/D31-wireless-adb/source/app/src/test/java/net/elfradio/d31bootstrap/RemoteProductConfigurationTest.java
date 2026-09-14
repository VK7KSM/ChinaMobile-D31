package net.elfradio.d31bootstrap;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class RemoteProductConfigurationTest {
    private static final int TOTAL = 38;
    private static final String REMOTE = "net.elfradio.d31bootstrap";
    private static final String SUPPORT = "net.elfradio.d31system";
    private static final String SMS = "net.elfradio.d31phone.debug";
    private static final String GUARD = "net.elfradio.d31zelloguard";
    private static final String LISTENERS = "enabled_notification_listeners";
    private static final String PRIVATE_DATA = "synthetic-private-value-for-redaction";
    private static final String[] PACKAGES = {REMOTE, SUPPORT, SMS, GUARD,
            "com.starnet.nexui", "com.starnet.getnumber", "com.loudtalks"};
    private static final String[] COMPONENTS = {
            SUPPORT + "/" + SUPPORT + ".SystemReceiver",
            SUPPORT + "/" + SUPPORT + ".MessageNotificationListener",
            GUARD + "/" + GUARD + ".GuardAccessibilityService",
            GUARD + "/" + GUARD + ".GuardNotificationListener",
            "com.starnet.getnumber/com.starnet.getnumber.BootReceiver",
            "com.starnet.getnumber/com.starnet.getnumber.GetNumberService",
            "com.starnet.getnumber/com.starnet.getnumber.SmsListener",
            "com.starnet.getnumber/com.starnet.getnumber.MainActivity",
            REMOTE + "/" + REMOTE + ".BootReceiver",
            REMOTE + "/" + REMOTE + ".VendorNetworkReceiver",
            REMOTE + "/" + REMOTE + ".RemoteManualReceiver"};
    private static final String[] PACKAGE_FIELDS = {"installed", "versionCode", "system",
            "updatedSystem", "privileged", "enabled", "enabledSetting"};
    private static final String[] COMPONENT_FIELDS = {"installed", "enabled", "enabledSetting", "manifestEnabled"};

    private static class Access implements RemoteProductConfiguration.Access {
        final List<String> reads = new ArrayList<>();
        final Map<String, JSONObject> packages = new HashMap<>();
        final Map<String, JSONObject> components = new HashMap<>();
        final Map<String, String> settings = new HashMap<>();
        final Set<String> failures = new HashSet<>();
        final JSONObject defaultPackage = packageValue();
        final JSONObject defaultComponent = componentValue();
        boolean failAll;
        boolean granted = true;
        boolean exempt = true;

        Access() throws Exception {
            settings.put("sms_default_application", SMS);
            settings.put(LISTENERS, COMPONENTS[1] + ":" + COMPONENTS[3]);
        }

        private void read(String key) throws IOException {
            reads.add(key);
            if (failAll || failures.contains(key)) throw new IOException(PRIVATE_DATA);
        }

        @Override public JSONObject packageState(String pkg) throws Exception {
            read("package:" + pkg);
            return packages.containsKey(pkg) ? packages.get(pkg) : defaultPackage;
        }

        @Override public JSONObject componentState(String component) throws Exception {
            read("component:" + component);
            return components.containsKey(component) ? components.get(component) : defaultComponent;
        }

        @Override public boolean permission(String pkg, String permission) throws Exception {
            read("permission:" + pkg + ":" + permission);
            return granted;
        }

        @Override public String secureSetting(String key) throws Exception {
            read("setting:" + key);
            return settings.get(key);
        }

        @Override public boolean batteryExempt(String pkg) throws Exception {
            read("battery_exempt:" + pkg);
            return exempt;
        }
    }

    private static JSONObject packageValue() throws Exception {
        return new JSONObject().put("installed", true).put("versionCode", 184)
                .put("system", true).put("updatedSystem", false).put("privileged", true)
                .put("enabled", true).put("enabledSetting", 0);
    }

    private static JSONObject componentValue() throws Exception {
        return new JSONObject().put("installed", true).put("enabled", true)
                .put("enabledSetting", 0).put("manifestEnabled", true);
    }

    private static Set<String> keys(JSONObject value) {
        Set<String> result = new HashSet<>();
        java.util.Iterator<String> names = value.keys();
        while (names.hasNext()) result.add(names.next());
        return result;
    }

    private static Set<String> keys(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    // 名单独立写出，不从被测类常量生成，防止名单意外扩张仍然自证通过。
    private static List<String> expectedIds() {
        List<String> ids = new ArrayList<>();
        for (String pkg : PACKAGES) ids.add("package:" + pkg);
        for (String component : COMPONENTS) ids.add("component:" + component);
        for (String name : new String[]{"READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CONTACTS", "READ_EXTERNAL_STORAGE"})
            ids.add("permission:" + SMS + ":android.permission." + name);
        ids.add("permission:" + SUPPORT + ":android.permission.READ_PHONE_STATE");
        ids.add("permission:com.loudtalks:android.permission.RECORD_AUDIO");
        for (String name : new String[]{"READ_LOGS", "DUMP", "WRITE_SECURE_SETTINGS", "CAMERA",
                "RECORD_AUDIO", "READ_PHONE_STATE", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"})
            ids.add("permission:" + REMOTE + ":android.permission." + name);
        ids.add("setting:default_sms_is_quik");
        ids.add("listener:" + COMPONENTS[1]);
        ids.add("listener:" + COMPONENTS[3]);
        ids.add("battery_exempt:com.loudtalks");
        ids.add("battery_exempt:" + GUARD);
        return ids;
    }

    private static List<String> expectedReads() {
        List<String> reads = expectedIds();
        reads.set(33, "setting:sms_default_application");
        reads.set(34, "setting:" + LISTENERS);
        reads.set(35, "setting:" + LISTENERS);
        return reads;
    }

    private static JSONObject row(JSONObject result, String id) throws Exception {
        JSONArray facts = result.getJSONArray("facts");
        for (int i = 0; i < facts.length(); i++) {
            JSONObject row = facts.getJSONObject(i);
            if (id.equals(row.getString("id"))) return row;
        }
        throw new AssertionError("Missing expected fact: " + id);
    }

    private static void observed(JSONObject result, String id, Object expected) throws Exception {
        JSONObject row = row(result, id);
        assertEquals(keys("id", "source", "state", "value"), keys(row));
        assertEquals("OBSERVED", row.getString("state"));
        assertEquals(expected, row.get("value"));
    }

    private static void failed(JSONObject result, String id) throws Exception {
        JSONObject row = row(result, id);
        assertEquals("READ_FAILED", row.getString("state"));
        assertEquals(keys("id", "source", "state", "reason"), keys(row));
        assertFalse(row.toString().contains(PRIVATE_DATA));
    }

    private static void bounded(JSONObject result, int observed) throws Exception {
        assertEquals(TOTAL, result.getInt("total"));
        assertEquals(TOTAL, result.getJSONArray("facts").length());
        assertEquals(observed, result.getInt("observed"));
        assertEquals(observed == TOTAL ? "COMPLETE" : "PARTIAL", result.getString("status"));
        assertFalse(result.getBoolean("atomicSnapshot"));
        assertEquals("NOT_CHECKED", result.getString("executionState"));
        assertEquals("NOT_ASSESSED", result.getString("systemConsistency"));
        assertFalse(result.getBoolean("repairPlanGenerated"));
    }

    @Test public void catalogHasExactlyThirtyEightIndependentAllowlistedFacts() throws Exception {
        Access access = new Access();
        JSONObject result = RemoteProductConfiguration.collect(access, 1234);
        assertEquals(keys("schemaVersion", "catalog", "capturedAtMs", "userId", "facts", "observed",
                "total", "status", "atomicSnapshot", "executionState", "systemConsistency", "repairPlanGenerated"), keys(result));
        assertEquals(1, result.getInt("schemaVersion"));
        assertEquals("d31-product-runtime-1", result.getString("catalog"));
        assertEquals(1234L, result.getLong("capturedAtMs"));
        assertEquals(0, result.getInt("userId"));
        List<String> ids = new ArrayList<>();
        JSONArray facts = result.getJSONArray("facts");
        for (int i = 0; i < facts.length(); i++) {
            JSONObject fact = facts.getJSONObject(i);
            String id = fact.getString("id");
            ids.add(id);
            String source = id.startsWith("permission:") ? "package_manager.checkPermission"
                    : id.startsWith("listener:") ? "secure.enabled_notification_listeners"
                    : id.startsWith("setting:") ? "secure.sms_default_application"
                    : id.startsWith("battery_exempt:") ? "power_manager" : "package_manager";
            assertEquals(source, fact.getString("source"));
            assertEquals(keys("id", "source", "state", "value"), keys(fact));
        }
        assertEquals(TOTAL, expectedIds().size());
        assertEquals(expectedIds(), ids);
        assertEquals(TOTAL, new HashSet<>(ids).size());
        assertEquals(expectedReads(), access.reads);
        bounded(result, TOTAL);
    }

    @Test public void readsDoNotMutateInputObjectsOrCollectExtraPrivateFields() throws Exception {
        Access access = new Access();
        access.defaultPackage.put("account", PRIVATE_DATA).put("allPackages", new JSONArray().put(PRIVATE_DATA))
                .put("nested", new JSONObject().put("private", PRIVATE_DATA));
        access.defaultComponent.put("privateSetting", PRIVATE_DATA);
        access.packages.put("synthetic.unlisted.package", new JSONObject().put("private", PRIVATE_DATA));
        access.settings.put("unrelated_private_setting", PRIVATE_DATA);
        String packageBefore = access.defaultPackage.toString();
        String componentBefore = access.defaultComponent.toString();
        Map<String, String> settingsBefore = new HashMap<>(access.settings);
        JSONObject result = RemoteProductConfiguration.collect(access, 1);
        for (String pkg : PACKAGES)
            assertEquals(keys(PACKAGE_FIELDS), keys(row(result, "package:" + pkg).getJSONObject("value")));
        for (String component : COMPONENTS)
            assertEquals(keys(COMPONENT_FIELDS), keys(row(result, "component:" + component).getJSONObject("value")));
        assertFalse(result.toString().contains(PRIVATE_DATA));
        assertFalse(result.toString().contains("synthetic.unlisted.package"));
        assertEquals(packageBefore, access.defaultPackage.toString());
        assertEquals(componentBefore, access.defaultComponent.toString());
        assertEquals(settingsBefore, access.settings);
        assertEquals(1, access.packages.size());
        assertTrue(access.components.isEmpty());
        assertEquals(expectedReads(), access.reads);
        bounded(result, TOTAL);
    }

    @Test public void absentPackagesAndComponentsRemainObservedAndNeedNoOtherFields() throws Exception {
        Access access = new Access();
        JSONObject absent = new JSONObject().put("installed", false).put("versionCode", PRIVATE_DATA)
                .put("enabled", JSONObject.NULL).put("private", PRIVATE_DATA);
        String before = absent.toString();
        for (String pkg : PACKAGES) access.packages.put(pkg, absent);
        for (String component : COMPONENTS) access.components.put(component, absent);
        JSONObject result = RemoteProductConfiguration.collect(access, 1);
        for (String id : expectedIds().subList(0, 18)) {
            JSONObject fact = row(result, id);
            assertEquals("OBSERVED", fact.getString("state"));
            assertFalse(fact.has("reason"));
            assertEquals(keys("installed"), keys(fact.getJSONObject("value")));
            assertEquals(Boolean.FALSE, fact.getJSONObject("value").get("installed"));
        }
        assertFalse(result.toString().contains(PRIVATE_DATA));
        assertEquals(before, absent.toString());
        bounded(result, TOTAL);
    }

    @Test public void nullPackageAndComponentStatesAreUnknownNotAbsent() throws Exception {
        Access access = new Access();
        access.packages.put(REMOTE, null);
        access.components.put(COMPONENTS[0], null);
        JSONObject result = RemoteProductConfiguration.collect(access, 1);
        failed(result, "package:" + REMOTE);
        failed(result, "component:" + COMPONENTS[0]);
        observed(result, "battery_exempt:" + GUARD, true);
        bounded(result, TOTAL - 2);
    }

    @Test public void everyReadFailureIsIsolatedWithoutLeakingExceptionDetails() throws Exception {
        for (String failingRead : new HashSet<>(expectedReads())) {
            Access access = new Access();
            access.failures.add(failingRead);
            JSONObject result = RemoteProductConfiguration.collect(access, 1);
            int failureCount = 0;
            for (int i = 0; i < expectedReads().size(); i++) {
                String id = expectedIds().get(i);
                if (expectedReads().get(i).equals(failingRead)) {
                    failed(result, id);
                    assertEquals("IOException", row(result, id).getString("reason"));
                    failureCount++;
                } else {
                    assertEquals("OBSERVED", row(result, id).getString("state"));
                }
            }
            bounded(result, TOTAL - failureCount);
            assertFalse(result.toString().contains(PRIVATE_DATA));
            assertEquals(expectedReads(), access.reads);
        }
    }

    @Test public void mixedFailuresProduceExactPartialCountsAndStillReadEveryFact() throws Exception {
        Access access = new Access();
        access.failures.addAll(Arrays.asList("package:" + REMOTE, "component:" + COMPONENTS[0],
                "permission:" + SMS + ":android.permission.READ_SMS", "setting:sms_default_application",
                "setting:" + LISTENERS, "battery_exempt:com.loudtalks"));
        JSONObject result = RemoteProductConfiguration.collect(access, 1);
        bounded(result, TOTAL - 7);
        assertEquals(expectedReads(), access.reads);
        observed(result, "battery_exempt:" + GUARD, true);
        assertFalse(result.toString().contains(PRIVATE_DATA));
    }

    @Test public void unexpectedRuntimeExceptionDoesNotExposeMessageCauseOrStack() throws Exception {
        Access access = new Access() {
            @Override public JSONObject packageState(String pkg) throws Exception {
                JSONObject value = super.packageState(pkg);
                if (REMOTE.equals(pkg))
                    throw new IllegalStateException(PRIVATE_DATA, new IOException(PRIVATE_DATA));
                return value;
            }
        };
        JSONObject result = RemoteProductConfiguration.collect(access, 1);
        failed(result, "package:" + REMOTE);
        assertEquals("IllegalStateException", row(result, "package:" + REMOTE).getString("reason"));
        assertFalse(result.toString().contains(PRIVATE_DATA));
        assertFalse(result.toString().contains("RemoteProductConfigurationTest"));
        assertEquals(expectedReads(), access.reads);
        bounded(result, TOTAL - 1);
    }

    @Test public void totalCollectionFailureDoesNotClaimSystemConsistencyOrExecution() throws Exception {
        Access access = new Access();
        access.failAll = true;
        JSONObject result = RemoteProductConfiguration.collect(access, 1);
        bounded(result, 0);
        for (String id : expectedIds()) failed(result, id);
        assertEquals(expectedReads(), access.reads);
        assertFalse(result.toString().contains(PRIVATE_DATA));
    }

    private static void invalidField(boolean component, String field, Object value, boolean missing) throws Exception {
        Access access = new Access();
        JSONObject raw = component ? componentValue() : packageValue();
        if (missing) raw.remove(field); else raw.put(field, value);
        String before = raw.toString();
        if (component) access.components.put(COMPONENTS[0], raw); else access.packages.put(REMOTE, raw);
        JSONObject result = RemoteProductConfiguration.collect(access, 1);
        failed(result, component ? "component:" + COMPONENTS[0] : "package:" + REMOTE);
        bounded(result, TOTAL - 1);
        assertEquals(before, raw.toString());
        assertEquals(expectedReads(), access.reads);
    }

    @Test public void booleansRejectCoercionNullContainersAndMissingFields() throws Exception {
        Object[] invalid = {"true", "false", 0, 1L, 1.0, JSONObject.NULL, new JSONObject(), new JSONArray()};
        for (boolean component : new boolean[]{false, true}) {
            String[] fields = component ? new String[]{"installed", "enabled", "manifestEnabled"}
                    : new String[]{"installed", "system", "updatedSystem", "privileged", "enabled"};
            for (String field : fields) {
                for (Object value : invalid) invalidField(component, field, value, false);
                invalidField(component, field, null, true);
            }
        }
    }

    @Test public void integerFieldsRejectCoercionFractionalTypesNullAndOutOfRange() throws Exception {
        Object[] invalid = {"1", true, 1.0, 1.5, Float.valueOf(1), Short.valueOf((short) 1),
                new BigDecimal("1"), JSONObject.NULL, new JSONObject(), -1, Long.MIN_VALUE,
                ((long) Integer.MAX_VALUE) + 1, Long.MAX_VALUE};
        for (boolean component : new boolean[]{false, true}) {
            for (String field : component ? new String[]{"enabledSetting"} : new String[]{"versionCode", "enabledSetting"}) {
                for (Object value : invalid) invalidField(component, field, value, false);
                invalidField(component, field, null, true);
            }
            invalidField(component, "enabledSetting", 5, false);
            invalidField(component, "enabledSetting", (long) Integer.MAX_VALUE, false);
        }
    }

    @Test public void integerBoundariesAndFalseBooleansRemainTypedObservations() throws Exception {
        for (Number version : new Number[]{0, Integer.MAX_VALUE, 0L, (long) Integer.MAX_VALUE}) {
            for (Number enabled : new Number[]{0, 4, 0L, 4L}) {
                Access access = new Access();
                access.defaultPackage.put("versionCode", version).put("enabledSetting", enabled)
                        .put("system", false).put("updatedSystem", false).put("privileged", false).put("enabled", false);
                access.defaultComponent.put("enabledSetting", enabled).put("enabled", false).put("manifestEnabled", false);
                access.granted = false;
                access.exempt = false;
                JSONObject result = RemoteProductConfiguration.collect(access, 1);
                bounded(result, TOTAL);
                JSONObject pkg = row(result, "package:" + REMOTE).getJSONObject("value");
                assertEquals(version, pkg.get("versionCode"));
                assertEquals(enabled, pkg.get("enabledSetting"));
                assertEquals(Boolean.FALSE, pkg.get("enabled"));
                JSONObject component = row(result, "component:" + COMPONENTS[0]).getJSONObject("value");
                assertEquals(enabled, component.get("enabledSetting"));
                assertEquals(Boolean.FALSE, component.get("manifestEnabled"));
                for (String id : expectedIds())
                    if (id.startsWith("permission:") || id.startsWith("battery_exempt:")) observed(result, id, false);
            }
        }
    }

    @Test public void notificationShortComponentsNormalizeWithoutPrefixMatches() throws Exception {
        String shortSupport = SUPPORT + "/.MessageNotificationListener";
        String shortGuard = GUARD + "/.GuardNotificationListener";
        assertEquals(COMPONENTS[1], RemoteProductConfiguration.canonicalComponent(shortSupport));
        assertEquals(COMPONENTS[3], RemoteProductConfiguration.canonicalComponent(shortGuard));
        assertTrue(RemoteProductConfiguration.containsComponent(COMPONENTS[1], shortSupport));
        Access access = new Access();
        access.settings.put(LISTENERS, "synthetic.other/.Listener:" + shortSupport + ":" + shortGuard + ":" + shortSupport);
        JSONObject result = RemoteProductConfiguration.collect(access, 1);
        bounded(result, TOTAL);
        observed(result, "listener:" + COMPONENTS[1], true);
        observed(result, "listener:" + COMPONENTS[3], true);
        assertFalse(result.toString().contains("synthetic.other"));
        access.settings.put(LISTENERS, shortSupport + "Suffix:" + SUPPORT + "/.MessageNotificationListene");
        result = RemoteProductConfiguration.collect(access, 1);
        observed(result, "listener:" + COMPONENTS[1], false);
        observed(result, "listener:" + COMPONENTS[3], false);
    }

    @Test public void nullEmptyAndUnrelatedNotificationSettingsAreObservedFalse() throws Exception {
        for (String setting : new String[]{null, "", "synthetic.other/.Listener"}) {
            Access access = new Access();
            access.settings.put(LISTENERS, setting);
            JSONObject result = RemoteProductConfiguration.collect(access, 1);
            observed(result, "listener:" + COMPONENTS[1], false);
            observed(result, "listener:" + COMPONENTS[3], false);
            bounded(result, TOTAL);
        }
    }

    @Test public void malformedNotificationListsAreUnknownEvenAfterAnExactMatch() throws Exception {
        String valid = COMPONENTS[1];
        for (String setting : new String[]{" ", "bad", "/Listener", SUPPORT + "/", SUPPORT + "//Listener",
                SUPPORT + "/.Listener\n", ":" + valid, valid + ":", valid + "::" + COMPONENTS[3],
                valid + ":bad", "bad:" + valid}) {
            Access access = new Access();
            access.settings.put(LISTENERS, setting);
            JSONObject result = RemoteProductConfiguration.collect(access, 1);
            failed(result, "listener:" + COMPONENTS[1]);
            failed(result, "listener:" + COMPONENTS[3]);
            bounded(result, TOTAL - 2);
        }
    }

    @Test public void dotOnlyNotificationClassMustNotBecomeAFalseObservation() throws Exception {
        Access access = new Access();
        access.settings.put(LISTENERS, SUPPORT + "/.");
        JSONObject result = RemoteProductConfiguration.collect(access, 1);
        failed(result, "listener:" + COMPONENTS[1]);
        failed(result, "listener:" + COMPONENTS[3]);
        bounded(result, TOTAL - 2);
    }

    @Test public void notificationSettingLengthLimitIsInclusiveAndOverlongFailsClosed() throws Exception {
        StringBuilder setting = new StringBuilder("synthetic.other/");
        while (setting.length() < 16384) setting.append('A');
        Access access = new Access();
        access.settings.put(LISTENERS, setting.toString());
        JSONObject result = RemoteProductConfiguration.collect(access, 1);
        observed(result, "listener:" + COMPONENTS[1], false);
        observed(result, "listener:" + COMPONENTS[3], false);
        bounded(result, TOTAL);
        access.settings.put(LISTENERS, setting.append('A').toString());
        result = RemoteProductConfiguration.collect(access, 1);
        failed(result, "listener:" + COMPONENTS[1]);
        failed(result, "listener:" + COMPONENTS[3]);
        bounded(result, TOTAL - 2);
    }

    @Test public void defaultSmsPublishesOnlyBooleanAndSeparatesMissingFromMalformed() throws Exception {
        StringBuilder boundary = new StringBuilder("synthetic.");
        while (boundary.length() < 1024) boundary.append('A');
        for (String setting : new String[]{null, "", SMS, "synthetic.other.sms", boundary.toString()}) {
            Access access = new Access();
            access.settings.put("sms_default_application", setting);
            JSONObject result = RemoteProductConfiguration.collect(access, 1);
            observed(result, "setting:default_sms_is_quik", SMS.equals(setting));
            assertFalse(result.toString().contains("synthetic.other.sms"));
            bounded(result, TOTAL);
        }
        for (String setting : new String[]{PRIVATE_DATA + "\n", "synthetic..sms", boundary + "A"}) {
            Access access = new Access();
            access.settings.put("sms_default_application", setting);
            JSONObject result = RemoteProductConfiguration.collect(access, 1);
            failed(result, "setting:default_sms_is_quik");
            bounded(result, TOTAL - 1);
            assertFalse(result.toString().contains(PRIVATE_DATA));
        }
    }

    @Test public void invalidCollectionInputsFailBeforeAnyReadAndZeroTimeIsAllowed() throws Exception {
        try {
            RemoteProductConfiguration.collect(null, 0);
            fail("Null access must be rejected");
        } catch (IllegalArgumentException expected) { }
        Access access = new Access();
        try {
            RemoteProductConfiguration.collect(access, -1);
            fail("Negative time must be rejected");
        } catch (IllegalArgumentException expected) { }
        assertTrue(access.reads.isEmpty());
        JSONObject result = RemoteProductConfiguration.collect(access, 0);
        assertEquals(0L, result.getLong("capturedAtMs"));
        bounded(result, TOTAL);
    }
}
