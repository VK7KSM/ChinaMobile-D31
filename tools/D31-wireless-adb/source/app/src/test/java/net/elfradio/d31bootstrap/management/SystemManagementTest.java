package net.elfradio.d31bootstrap.management;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

/** 桌面故障注入只验事务与回执，不代替D31的Binder/权限/业务验收。 */
public class SystemManagementTest {
    private static JSONObject set(String group, String key, Object value) throws Exception {
        return new JSONObject().put("group", group).put("action", "set").put("key", key).put("value", value)
                .put("package", "apps".equals(group) ? "example.app" : "");
    }

    private static class Device implements SystemManagement.Access {
        Object value = "41";
        int reads, writes, restores;
        boolean deniedRead, deniedWrite, failAfterWrite, ignoreWrite, failRestore, lieRestore, largeAfter, largeBefore;
        public JSONObject snapshot(JSONObject p) throws Exception {
            reads++;
            if (deniedRead) throw new SecurityException("权限拒绝");
            Object visible = "apps".equals(p.getString("group")) ? Integer.valueOf(1).equals(value)
                    : value == JSONObject.NULL ? JSONObject.NULL : Integer.parseInt(value.toString());
            JSONObject out = new JSONObject().put("group", p.getString("group")).put("sampled_at", 12345)
                    .put(p.optString("key", "brightness"), visible).put("enabled_state", value);
            if (largeBefore || largeAfter && writes > 0) out.put("oversize", new String(new char[16000]).replace('\0', 'x'));
            return out;
        }
        public Object capture(JSONObject p) throws Exception { return value; }
        public void apply(JSONObject p, Object wanted) throws Exception {
            writes++;
            if (deniedWrite) throw new SecurityException("写入权限拒绝");
            if (!ignoreWrite) value = "apps".equals(p.getString("group")) ? (Boolean) wanted ? 1 : 3 : wanted.toString();
            if (failAfterWrite) throw new IOException("写入后服务失败");
        }
        public void restore(JSONObject p, Object original) throws Exception {
            restores++;
            if (failRestore) throw new IOException("恢复服务不可用");
            if (!lieRestore) value = original;
        }
        public boolean matches(JSONObject p, JSONObject after) throws Exception { return p.get("value").equals(after.get(p.getString("key"))); }
        public boolean restored(JSONObject p, Object original) { return original.equals(value); }
    }

    private static class Control implements SystemManagement.Control {
        int checks, cancelAt = -1;
        boolean failSave;
        JSONObject saved;
        public void check() throws Exception { if (++checks == cancelAt) throw new InterruptedException("取消"); }
        public void before(JSONObject record) throws Exception {
            if (failSave) throw new IOException("原像磁盘已满");
            saved = new JSONObject(record.toString());
        }
    }

    private static SystemManagement.Failure failure(JSONObject p, Device d, Control c) throws Exception {
        try { SystemManagement.run("system_config", p, d, c); fail("不应成功"); return null; }
        catch (SystemManagement.Failure error) {
            assertFalse(error.result.getBoolean("ok")); assertFalse(error.result.getBoolean("applied")); return error;
        }
    }

    @Test public void existingTaskOnly() throws Exception {
        assertTrue(SystemManagement.supports("system_config"));
        for (String type : new String[]{"system_settings", "connect_wifi", "contacts_read", "contact_add", "component_set", null})
            assertFalse(SystemManagement.supports(type));
    }
    @Test public void strictParameterTypesAndGroups() throws Exception {
        for (JSONObject p : new JSONObject[]{set("sound", "brightness", "10"), set("sound", "brightness", 10.5),
                set("sound", "brightness", -1), set("sound", "brightness", 256), set("time", "auto_time", "false"),
                set("apps", "enabled", false).put("package", "example.app;reboot"), set("apps", "enabled", true).put("package", ""),
                set("time", "timezone", "no/such-zone"), new JSONObject().put("group", 1),
                new JSONObject().put("group", "sound").put("offset", "0"),
                new JSONObject().put("group", "apps").put("offset", 10001)}) {
            try { SystemManagement.validate("system_config", p); fail(p.toString()); } catch (Exception expected) { }
        }
    }
    @Test public void unsupportedWritesNeverReachServices() throws Exception {
        for (JSONObject p : new JSONObject[]{set("network", "mobile_data", false), set("wifi", "connect", new JSONObject().put("ssid", "test")),
                set("sound", "font_scale", 1.2), set("time", "locale", "zh-CN"), set("apps", "notifications", false),
                set("apps", "background", false), set("apps", "permission", new JSONObject().put("name", "android.permission.CAMERA").put("granted", true))}) {
            Device d = new Device();
            try { SystemManagement.run("system_config", p, d, new Control()); fail(); } catch (IOException expected) { }
            assertEquals(0, d.reads); assertEquals(0, d.writes);
        }
    }
    @Test public void writeReturnsRealReadbackAndOriginal() throws Exception {
        Device d = new Device(); Control c = new Control();
        JSONObject result = SystemManagement.run("system_config", set("sound", "brightness", 80), d, c);
        assertTrue(result.getBoolean("ok")); assertTrue(result.getBoolean("applied"));
        assertEquals(80, result.getInt("brightness")); assertEquals("41", c.saved.getString("original"));
        assertEquals(1, d.writes); assertEquals(0, d.restores);
    }
    @Test public void readDoesNotSaveOrMutate() throws Exception {
        Device d = new Device(); Control c = new Control();
        JSONObject result = SystemManagement.run("system_config", new JSONObject().put("group", "sound"), d, c);
        assertTrue(result.getBoolean("ok")); assertFalse(result.has("applied")); assertNull(c.saved);
        assertEquals(0, d.writes); assertEquals(0, d.restores);
    }
    @Test public void permissionDeniedReadDoesNotBecomeEmptySuccess() throws Exception {
        Device d = new Device(); d.deniedRead = true;
        SystemManagement.Failure error = failure(set("sound", "brightness", 80), d, new Control());
        assertEquals("SecurityException", error.result.getString("error_type"));
        assertFalse(error.result.getBoolean("change_attempted")); assertEquals(0, d.writes);
    }
    @Test public void permissionDeniedWriteDoesNotBecomeSuccess() throws Exception {
        Device d = new Device(); d.deniedWrite = true;
        SystemManagement.Failure error = failure(set("sound", "brightness", 80), d, new Control());
        assertEquals("SecurityException", error.result.getString("error_type"));
        assertTrue(error.result.getBoolean("restored")); assertEquals("41", d.value);
    }
    @Test public void failedOriginalPersistenceStopsBeforeWrite() throws Exception {
        Device d = new Device(); Control c = new Control(); c.failSave = true;
        failure(set("sound", "brightness", 80), d, c);
        assertEquals(0, d.writes); assertEquals(0, d.restores);
    }
    @Test public void readbackMismatchRestoresAndFails() throws Exception {
        Device d = new Device(); d.ignoreWrite = true;
        SystemManagement.Failure error = failure(set("sound", "brightness", 80), d, new Control());
        assertTrue(error.result.getBoolean("restored")); assertEquals(1, d.restores); assertEquals("41", d.value);
    }
    @Test public void serviceChangesThenThrowsStillRestores() throws Exception {
        Device d = new Device(); d.failAfterWrite = true;
        SystemManagement.Failure error = failure(set("sound", "brightness", 80), d, new Control());
        assertTrue(error.result.getBoolean("restored")); assertEquals("41", d.value);
    }
    @Test public void restorationFailureRequiresAttention() throws Exception {
        Device d = new Device(); d.failAfterWrite = true; d.failRestore = true;
        SystemManagement.Failure error = failure(set("sound", "brightness", 80), d, new Control());
        assertTrue(error.result.getBoolean("recovery_required")); assertFalse(error.result.getBoolean("restored"));
        assertEquals("80", d.value); assertEquals(1, error.getSuppressed().length);
    }
    @Test public void restoreMustBeReadBack() throws Exception {
        Device d = new Device(); d.failAfterWrite = true; d.lieRestore = true;
        assertTrue(failure(set("sound", "brightness", 80), d, new Control()).result.getBoolean("recovery_required"));
    }
    @Test public void cancelBeforeReadOrWriteDoesNotMutate() throws Exception {
        for (int stage : new int[]{1, 2}) {
            Device d = new Device(); Control c = new Control(); c.cancelAt = stage;
            failure(set("sound", "brightness", 80), d, c);
            assertEquals(0, d.writes); assertEquals(0, d.restores);
        }
    }
    @Test public void cancellationAfterWriteAndBeforeReturnRollsBack() throws Exception {
        for (int stage : new int[]{3, 4}) {
            Device d = new Device(); Control c = new Control(); c.cancelAt = stage;
            SystemManagement.Failure error = failure(set("sound", "brightness", 80), d, c);
            assertEquals("InterruptedException", error.result.getString("error_type"));
            assertTrue(error.result.getBoolean("restored")); assertEquals("41", d.value);
        }
    }
    @Test public void unsetOriginalIsNotReplacedWithDefault() throws Exception {
        Device d = new Device(); d.value = JSONObject.NULL; d.failAfterWrite = true; Control c = new Control();
        assertTrue(failure(set("sound", "brightness", 80), d, c).result.getBoolean("restored"));
        assertSame(JSONObject.NULL, d.value); assertTrue(c.saved.isNull("original"));
    }
    @Test public void applicationRawDefaultAndDisabledModesSurviveRollback() throws Exception {
        for (int state : new int[]{0, 1, 2, 3, 4}) {
            Device d = new Device(); d.value = state; d.failAfterWrite = true;
            assertTrue(failure(set("apps", "enabled", true), d, new Control()).result.getBoolean("restored"));
            assertEquals(state, d.value);
        }
    }
    @Test public void oversizeReadFailsBeforeMutation() throws Exception {
        Device d = new Device(); d.largeBefore = true;
        failure(set("sound", "brightness", 80), d, new Control()); assertEquals(0, d.writes);
    }
    @Test public void oversizeAfterWriteCannotCommitTruncatedSuccess() throws Exception {
        Device d = new Device(); d.largeAfter = true;
        assertTrue(failure(set("sound", "brightness", 80), d, new Control()).result.getBoolean("restored"));
        assertEquals("41", d.value);
    }
    @Test public void runtimeFailureCauseIsPreservedWithoutLeakingMessage() throws Exception {
        Device d = new Device() {
            public Object capture(JSONObject p) throws Exception { throw new IOException("private-value"); }
        };
        SystemManagement.Failure error = failure(set("sound", "brightness", 80), d, new Control());
        assertFalse(error.result.toString().contains("private-value")); assertEquals("private-value", error.getCause().getMessage());
    }
    @Test public void readCapabilitiesDoNotClaimNetworkOrContactCompletion() throws Exception {
        JSONObject caps = SystemManagement.capabilities();
        assertFalse(caps.getBoolean("network_write")); assertFalse(caps.getBoolean("contacts")); assertFalse(caps.getBoolean("write_verified"));
        assertEquals("read_only", caps.getString("components"));
    }
    @Test public void exportActualSuccessfulReplyForWebParser() throws Exception {
        JSONObject request = set("sound", "brightness", 80);
        JSONObject snapshot = SystemManagement.run("system_config", request, new Device(), new Control());
        JSONObject fixture = new JSONObject().put("params", SystemManagement.validate("system_config", request))
                .put("result", new JSONObject().put("exit_code", 0).put("action", "completed").put("truncated", false).put("text", snapshot.toString()));
        String evidence = System.getProperty("management.evidence");
        if (evidence != null) java.nio.file.Files.write(java.nio.file.Paths.get(evidence, "java-success.json"),
                fixture.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE_NEW);
        assertEquals("sound", snapshot.getString("group"));
        assertTrue(snapshot.getBoolean("applied"));
    }
    @Test public void realInterruptDoesNotPreventRestoration() throws Exception {
        Device d = new Device() {
            public void apply(JSONObject p, Object value) throws Exception {
                super.apply(p, value); Thread.currentThread().interrupt();
            }
            public void restore(JSONObject p, Object original) throws Exception {
                assertFalse(Thread.currentThread().isInterrupted()); super.restore(p, original);
            }
        };
        Control c = new Control() {
            public void check() throws Exception {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("取消");
            }
        };
        try {
            assertTrue(failure(set("sound", "brightness", 80), d, c).result.getBoolean("restored"));
            assertEquals("41", d.value); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static final class Child extends Process {
        final java.io.InputStream output;
        final int exit;
        final boolean running;
        boolean destroyed;
        Child(String output, int exit, boolean running) throws Exception {
            this.output = new java.io.ByteArrayInputStream(output.getBytes("UTF-8")); this.exit = exit; this.running = running;
        }
        public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
        public java.io.InputStream getInputStream() { return output; }
        public java.io.InputStream getErrorStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
        public int waitFor() { return exit; }
        public int exitValue() { if (running && !destroyed) throw new IllegalThreadStateException(); return exit; }
        public void destroy() { destroyed = true; }
    }
    @Test public void settingsCommandAcceptsOutputWithoutNewline() throws Exception {
        Child child = new Child("41", 0, false);
        assertEquals("41", SettingsCommand.collect(child, 1000)); assertTrue(child.destroyed);
    }
    @Test public void settingsCommandRejectsNonzeroEvenWithPlausibleOutput() throws Exception {
        Child child = new Child("41\n", 1, false);
        try { SettingsCommand.collect(child, 1000); fail(); } catch (IOException expected) { }
        assertTrue(child.destroyed);
    }
    @Test public void settingsCommandBoundsOutputAndTime() throws Exception {
        for (Child child : new Child[]{new Child(new String(new char[5000]), 0, false), new Child("", 0, true)}) {
            try { SettingsCommand.collect(child, 40); fail(); } catch (IOException expected) { }
            assertTrue(child.destroyed);
        }
    }
    @Test public void settingsCommandCancellationDestroysChild() throws Exception {
        Child child = new Child("", 0, true);
        Thread.currentThread().interrupt();
        try { SettingsCommand.collect(child, 1000); fail(); }
        catch (InterruptedException expected) { assertTrue(child.destroyed); }
        finally { Thread.interrupted(); }
    }

    public interface WifiReadFixture {
        Object getWifiEnabledState();
        Object getWifiApEnabledState();
        Object getConfiguredNetworks();
        Object getConnectionInfo();
        Object getWifiApConfiguration();
    }
    private static final class WifiService implements WifiReadFixture {
        Object state = 3;
        boolean denied;
        int listCalls;
        public Object getWifiEnabledState() { return state; }
        public Object getWifiApEnabledState() { return 11; }
        public Object getConfiguredNetworks() {
            listCalls++;
            if (denied) throw new SecurityException("拒绝读取");
            return java.util.Collections.emptyList();
        }
        public Object getConnectionInfo() { return null; }
        public Object getWifiApConfiguration() { return null; }
    }
    public static final class SliceFixture {
        final Object value;
        SliceFixture(Object value) { this.value = value; }
        public Object getList() { return value; }
    }
    public static final class DeniedSlice {
        public java.util.List<?> getList() { throw new SecurityException("列表读取失败"); }
    }
    @Test public void wifiBinderUsesInterfaceWithoutManagerConstruction() throws Exception {
        WifiService service = new WifiService();
        WifiBinderAccess wifi = new WifiBinderAccess(WifiReadFixture.class, service);
        assertEquals(3, wifi.wifiState()); assertEquals(11, wifi.hotspotState());
        assertNull(wifi.connectionInfo()); assertNull(wifi.hotspotConfiguration());
        assertTrue(wifi.configuredNetworks().isEmpty()); assertEquals(1, service.listCalls);
    }
    @Test public void wifiBinderRejectionCannotBecomeEmptyList() throws Exception {
        WifiService service = new WifiService(); service.denied = true;
        try { new WifiBinderAccess(WifiReadFixture.class, service).configuredNetworks(); fail(); }
        catch (Exception error) { assertTrue(SystemManagement.root(error) instanceof SecurityException); }
        assertEquals(1, service.listCalls);
    }
    @Test public void wifiBinderRejectsMissingServiceAndWrongStateType() throws Exception {
        try { new WifiBinderAccess(WifiReadFixture.class, null); fail(); } catch (IOException expected) { }
        try { new WifiBinderAccess(WifiReadFixture.class, new Object()); fail(); } catch (IOException expected) { }
        WifiService service = new WifiService(); service.state = "3";
        try { new WifiBinderAccess(WifiReadFixture.class, service).wifiState(); fail(); } catch (IOException expected) { }
    }
    @Test public void wifiListIsCopiedBeforeSorting() throws Exception {
        java.util.List<String> original = java.util.Collections.unmodifiableList(java.util.Arrays.asList("b", "a"));
        java.util.List<String> copy = WifiBinderAccess.unpack(original, String.class, null);
        java.util.Collections.sort(copy);
        assertEquals(java.util.Arrays.asList("a", "b"), copy);
        assertEquals(java.util.Arrays.asList("b", "a"), original);
    }
    @Test public void wifiSliceUnwrapsAndPreservesEveryEntry() throws Exception {
        java.util.List<String> source = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) source.add("entry-" + i);
        java.util.List<String> copy = WifiBinderAccess.unpack(new SliceFixture(source), String.class, SliceFixture.class);
        assertEquals(source, copy); copy.clear(); assertEquals(40, source.size());
    }
    @Test public void wifiMalformedListNeverSilentlyDropsEntries() throws Exception {
        for (Object value : new Object[]{null, new Object(), java.util.Arrays.asList("valid", 1), java.util.Arrays.asList("valid", null),
                new SliceFixture(null), new SliceFixture("not-a-list"), new SliceFixture(java.util.Arrays.asList("valid", 1))}) {
            try { WifiBinderAccess.unpack(value, String.class, SliceFixture.class); fail(); } catch (IOException expected) { }
        }
    }
    @Test public void wifiSliceFailureIsNotReinterpretedAsAbsentNetwork() throws Exception {
        try { WifiBinderAccess.unpack(new DeniedSlice(), String.class, DeniedSlice.class); fail(); }
        catch (Exception error) { assertTrue(SystemManagement.root(error) instanceof SecurityException); }
    }
}
