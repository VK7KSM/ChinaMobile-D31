package net.elfradio.d31bootstrap.telemetry;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** 按需只读helper；仅输出调用身份、失败链及缓存元数据，不注册监听、不输出坐标。 */
public final class LocationCacheProbe {
    private final JSONArray steps = new JSONArray();
    private final TelemetryCollector.Limits limits = new TelemetryCollector.Limits(0, 300000);
    private final TelemetryCollector.Clock clock = AndroidTelemetryAccess.clock();

    public static void main(String[] args) {
        try {
            if (args.length != 0 || Build.VERSION.SDK_INT != 23 || Process.myUid() != 0)
                throw new IllegalArgumentException("REQUIRES_API23_ROOT_NO_ARGUMENTS");
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            System.out.println(inspect(context).toString());
            System.exit(0);
        } catch (Throwable failure) {
            try { System.out.println(new JSONObject().put("state", "PROBE_FAILED")
                    .put("failure", LocationCacheDiagnostics.failure(failure)).toString()); }
            catch (Exception unavailable) { System.err.println("LOCATION_PROBE_FAILED"); }
            System.exit(1);
        }
    }

    /** 可在主任务已有的真实Context中调用；不接受替代包名、UID或定位请求参数。 */
    public static JSONObject inspect(Context context) throws Exception {
        if (context == null || Build.VERSION.SDK_INT != 23) throw new IllegalArgumentException("REQUIRES_API23_CONTEXT");
        return new LocationCacheProbe().run(context);
    }

    private JSONObject run(final Context context) throws Exception {
        final int uid = Process.myUid(), pid = Process.myPid();
        final String actualPackage = context.getPackageName();
        final PackageManager packages = context.getPackageManager();
        read("identity", () -> new JSONObject().put("uid", uid).put("pid", pid).put("sdk", Build.VERSION.SDK_INT)
                .put("context_class", context.getClass().getName()).put("context_package", actualPackage)
                .put("op_package", Context.class.getMethod("getOpPackageName").invoke(context))
                .put("binder_calling_uid", android.os.Binder.getCallingUid())
                .put("binder_calling_pid", android.os.Binder.getCallingPid()));
        read("uid_packages", () -> {
            String[] names = packages.getPackagesForUid(uid);
            return new JSONObject().put("packages", names == null ? JSONObject.NULL : new JSONArray(names))
                    .put("package_check", LocationCacheDiagnostics.packageMatch(actualPackage, names));
        });
        read("context_package_uid", () -> packages.getApplicationInfo(actualPackage, 0).uid);
        for (final String permission : new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION}) {
            read("permission." + permission, () -> new JSONObject()
                    .put("process_result", context.checkPermission(permission, pid, uid))
                    .put("package_result", packages.checkPermission(permission, actualPackage)));
        }
        final LocationManager[] manager = new LocationManager[1];
        read("framework.location_service", () -> {
            manager[0] = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            return manager[0] == null ? null : manager[0].getClass().getName();
        });
        final Object[] binderService = new Object[1];
        final Class<?>[] binderInterface = new Class<?>[1], requestType = new Class<?>[1];
        read("binder.location_service", () -> {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "location");
            if (binder == null) return null;
            binderInterface[0] = Class.forName("android.location.ILocationManager");
            requestType[0] = Class.forName("android.location.LocationRequest");
            binderService[0] = Class.forName("android.location.ILocationManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            JSONArray methods = new JSONArray();
            for (Method method : binderInterface[0].getMethods()) {
                if ("getLastLocation".equals(method.getName()) || "getProviderProperties".equals(method.getName())
                        || "isProviderEnabled".equals(method.getName())) methods.put(method.toGenericString());
            }
            return new JSONObject().put("descriptor", binder.getInterfaceDescriptor()).put("methods", methods);
        });
        for (final String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            read(provider + ".framework.getProvider", () -> {
                require(manager[0]); return manager[0].getProvider(provider) != null;
            });
            read(provider + ".framework.isProviderEnabled", () -> {
                require(manager[0]); return manager[0].isProviderEnabled(provider);
            });
            // 三个框架调用独立记录，属性读取失败也不能冒称已经尝试缓存读取。
            read(provider + ".framework.getLastKnownLocation", () -> {
                require(manager[0]); return describe(manager[0].getLastKnownLocation(provider));
            });
            read(provider + ".binder.getLastLocation", () -> {
                require(binderService[0]);
                Object request = requestType[0].getMethod("createFromDeprecatedProvider", String.class, long.class, float.class, boolean.class)
                        .invoke(null, provider, 0L, 0f, true);
                Object location = LocationCacheDiagnostics.lastLocation(binderInterface[0], binderService[0], requestType[0], request, actualPackage);
                if (location != null && !(location instanceof Location)) throw new IllegalStateException("INVALID_LOCATION_RETURN_TYPE");
                return describe((Location) location);
            });
        }
        return new JSONObject().put("schemaVersion", 1).put("state", "PROBE_COMPLETED")
                .put("read_only", true).put("active_sampling", false).put("max_location_age_ms", limits.maxLocationAgeMs)
                .put("coordinates_omitted", true).put("steps", steps);
    }

    private JSONObject describe(Location location) throws Exception {
        TelemetryCollector.Fix fix = location == null ? null : new TelemetryCollector.Fix(location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : null, location.getProvider(), location.getTime(),
                location.getElapsedRealtimeNanos(), location.isFromMockProvider());
        return LocationCacheDiagnostics.cache(fix, limits, clock);
    }
    private void read(String name, LocationCacheDiagnostics.Read action) throws Exception {
        System.err.println("LOCATION_PROBE_BEGIN " + name);
        JSONObject result = LocationCacheDiagnostics.step(name, action);
        steps.put(result);
        System.err.println("LOCATION_PROBE_END " + name + " " + result.getString("state"));
    }
    private static void require(Object service) {
        if (service == null) throw new IllegalStateException("LOCATION_SERVICE_UNAVAILABLE");
    }
    private LocationCacheProbe() { }
}
