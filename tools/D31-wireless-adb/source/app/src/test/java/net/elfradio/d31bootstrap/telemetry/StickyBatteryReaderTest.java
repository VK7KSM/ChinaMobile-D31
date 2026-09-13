package net.elfradio.d31bootstrap.telemetry;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 验证真实六参数Binder调用形状；宿主替身不代表设备AMS已经验收。 */
public class StickyBatteryReaderTest {
    public interface AppThread { }
    public interface Receiver { }
    public static final class Filter { }
    public interface Manager {
        Object registerReceiver(AppThread caller, String callerPackage, Receiver receiver,
                                Filter filter, String permission, int user) throws Exception;
        Object registerReceiver(AppThread caller, String callerPackage, Receiver receiver,
                                Filter filter, String permission, int user, int flags) throws Exception;
    }
    public interface WrongManager {
        Object registerReceiver(AppThread caller, String callerPackage, Receiver receiver,
                                Filter filter, String permission);
    }
    private static final class Binder implements Manager {
        final Object sticky = new Object();
        Object caller, callerPackage, receiver, filter, permission;
        int user = -99, reads, otherSignatureCalls;
        Exception failure;
        public Object registerReceiver(AppThread caller, String callerPackage, Receiver receiver,
                                       Filter filter, String permission, int user) throws Exception {
            reads++;
            this.caller = caller; this.callerPackage = callerPackage; this.receiver = receiver;
            this.filter = filter; this.permission = permission; this.user = user;
            if (failure != null) throw failure;
            return sticky;
        }
        public Object registerReceiver(AppThread caller, String callerPackage, Receiver receiver,
                                       Filter filter, String permission, int user, int flags) {
            otherSignatureCalls++; throw new AssertionError("不得猜测其它Android版本签名");
        }
    }
    private static class Access implements StickyBatteryReader.Access {
        int contextCalls, rootCalls;
        final TelemetryCollector.BatteryReading contextValue = new TelemetryCollector.BatteryReading(37, 100, 0, 3, true);
        TelemetryCollector.BatteryReading rootValue = new TelemetryCollector.BatteryReading(100, 100, 1, 5, false);
        public TelemetryCollector.BatteryReading contextSticky() {
            contextCalls++; return contextValue;
        }
        public TelemetryCollector.BatteryReading rootSticky() throws Exception {
            rootCalls++; return rootValue;
        }
    }
    private static Object query(Binder binder, Filter filter) throws Exception {
        return StickyBatteryReader.query(Manager.class, binder, AppThread.class, Receiver.class, Filter.class, filter);
    }
    private static TelemetryCollector.Sample collect(final StickyBatteryReader.Access access) throws Exception {
        TelemetryCollector.Clock clock = new TelemetryCollector.Clock() {
            public long wallTimeMillis() { return 1800000000000L; }
            public long elapsedRealtimeNanos() { return 1000000000L; }
        };
        return new TelemetryCollector(new TelemetryCollector.Access() {
            public TelemetryCollector.LocationReading location(TelemetryCollector.Limits limits, TelemetryCollector.Clock clock) {
                return new TelemetryCollector.LocationReading(null, "permission_denied", true);
            }
            public TelemetryCollector.BatteryReading battery() throws Exception { return StickyBatteryReader.read(access, 23, 0); }
        }, clock).collect(new TelemetryCollector.Limits(0, 300000));
    }

    @Test public void api23RootNeverPassesUnregisteredApplicationThreadThroughContext() throws Exception {
        Access access = new Access() {
            public TelemetryCollector.BatteryReading contextSticky() {
                contextCalls++; throw new SecurityException("Unable to find app for caller");
            }
        };
        assertSame(access.rootValue, StickyBatteryReader.read(access, 23, 0));
        assertEquals(0, access.contextCalls); assertEquals(1, access.rootCalls);
    }

    @Test public void otherPlatformOrUidKeepsContextRouteWithoutGuessingBinderLayout() throws Exception {
        for (int[] identity : new int[][]{{22, 0}, {24, 0}, {23, 1000}, {23, 10000}}) {
            Access access = new Access();
            assertSame(access.contextValue, StickyBatteryReader.read(access, identity[0], identity[1]));
            assertEquals(1, access.contextCalls); assertEquals(0, access.rootCalls);
        }
    }

    @Test public void exactSixArgumentInterfaceUsesNullCallerReceiverAndOwnerUser() throws Exception {
        Binder binder = new Binder(); Filter filter = new Filter();
        assertSame(binder.sticky, query(binder, filter));
        assertNull(binder.caller); assertNull(binder.callerPackage); assertNull(binder.receiver);
        assertNull(binder.permission); assertSame(filter, binder.filter); assertEquals(0, binder.user);
        assertEquals(1, binder.reads); assertEquals(0, binder.otherSignatureCalls);
    }

    @Test public void missingSixArgumentSignatureIsNotReplacedWithGuessedCall() throws Exception {
        try {
            StickyBatteryReader.query(WrongManager.class, new Object(), AppThread.class, Receiver.class, Filter.class, new Filter());
            fail("不匹配的固件方法必须失败关闭");
        } catch (NoSuchMethodException expected) { }
    }

    @Test public void absentServiceOrFilterCannotRegisterAnything() throws Exception {
        Binder binder = new Binder();
        try { query(binder, null); fail("过滤器不可缺失"); }
        catch (IllegalArgumentException expected) { }
        try { query(null, new Filter()); fail("服务不可缺失"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(0, binder.reads);
    }

    @Test public void reflectionPreservesActualBinderFailureRatherThanWrapper() throws Exception {
        Binder binder = new Binder(); SecurityException cause = new SecurityException("synthetic-denial");
        binder.failure = cause;
        try { query(binder, new Filter()); fail("应传播真实Binder拒绝"); }
        catch (SecurityException expected) { assertSame(cause, expected); }
        assertEquals(1, binder.reads);
    }

    @Test public void absentStickyKeepsAllPowerFieldsUnknown() throws Exception {
        Access access = new Access(); access.rootValue = null;
        TelemetryCollector.Sample sample = collect(access); JSONObject fields = sample.reportFields();
        assertTrue(fields.isNull("battery")); assertTrue(fields.isNull("charging")); assertTrue(fields.isNull("battery_present"));
        assertEquals("READ_FAILED", sample.toJson().getJSONObject("evidence").getJSONObject("power").getString("state"));
        assertEquals(1, access.rootCalls); assertEquals(0, access.contextCalls);
    }

    @Test public void rootBinderFailureNeitherFallsBackNorLeaksDetails() throws Exception {
        Access access = new Access() {
            public TelemetryCollector.BatteryReading rootSticky() {
                rootCalls++; throw new SecurityException("synthetic-private-stack");
            }
        };
        TelemetryCollector.Sample sample = collect(access); JSONObject fields = sample.reportFields();
        assertTrue(fields.isNull("battery")); assertTrue(fields.isNull("charging")); assertTrue(fields.isNull("battery_present"));
        assertFalse(sample.toJson().toString().contains("synthetic-private-stack"));
        assertEquals(1, access.rootCalls); assertEquals(0, access.contextCalls);
    }

    @Test public void realSupplyDoesNotInventBatteryOrOverrideLocationDenial() throws Exception {
        JSONObject fields = collect(new Access()).reportFields();
        assertTrue(fields.isNull("battery")); assertFalse(fields.getBoolean("battery_present"));
        assertTrue(fields.getBoolean("charging")); assertTrue(fields.isNull("gps"));
        assertEquals("permission_denied", fields.getString("location_reason"));
    }

    @Test public void interruptedBinderCallRemainsCancellation() throws Exception {
        final Binder binder = new Binder(); binder.failure = new InterruptedException("synthetic-cancel");
        Access access = new Access() {
            public TelemetryCollector.BatteryReading rootSticky() throws Exception { query(binder, new Filter()); return null; }
        };
        try { collect(access); fail("Binder中断不得改成普通缺失"); }
        catch (InterruptedException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }
}
