package net.elfradio.d31bootstrap.telemetry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Android6独立root进程只读粘性广播；路由和反射参数可在宿主注入核验。 */
final class StickyBatteryReader {
    interface Access {
        TelemetryCollector.BatteryReading contextSticky() throws Exception;
        TelemetryCollector.BatteryReading rootSticky() throws Exception;
    }

    static TelemetryCollector.BatteryReading read(Access access, int sdk, int uid) throws Exception {
        // root app_process没有AMS登记的应用线程，不能经Context隐式传入该线程。
        return sdk == 23 && uid == 0 ? access.rootSticky() : access.contextSticky();
    }

    static Object query(Class<?> managerInterface, Object manager, Class<?> applicationThread,
                        Class<?> receiver, Class<?> filterType, Object filter) throws Exception {
        if (manager == null || filter == null) throw new IllegalArgumentException("STICKY_SERVICE_UNAVAILABLE");
        // 使用公开接口上的精确六参数方法，避免依赖包内ActivityManagerProxy的反射可访问性。
        Method method = managerInterface.getMethod("registerReceiver", applicationThread, String.class,
                receiver, filterType, String.class, int.class);
        try {
            // Android6 AMS在receiver为空时直接返回sticky，不登记实体receiver或回调。
            return method.invoke(manager, new Object[]{null, null, null, filter, null, Integer.valueOf(0)});
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw wrapped;
        }
    }

    private StickyBatteryReader() { }
}
