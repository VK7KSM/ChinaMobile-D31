package net.elfradio.d31bootstrap.telemetry;

import org.json.JSONObject;

/** 一次有界采集；定位缺失、电池不存在及证据不可读分别保留，不承担上报调度。 */
public final class TelemetryCollector {
    public interface Clock {
        long wallTimeMillis();
        long elapsedRealtimeNanos();
    }
    public interface Access {
        LocationReading location(Limits limits, Clock clock) throws Exception;
        BatteryReading battery() throws Exception;
    }
    public static final class Limits {
        public final long locationWindowMs, maxLocationAgeMs;
        public Limits(long locationWindowMs, long maxLocationAgeMs) {
            if (locationWindowMs < 0 || locationWindowMs > 10000 || maxLocationAgeMs < 1 || maxLocationAgeMs > 900000)
                throw new IllegalArgumentException("INVALID_TELEMETRY_LIMITS");
            this.locationWindowMs = locationWindowMs; this.maxLocationAgeMs = maxLocationAgeMs;
        }
    }
    public static final class Fix {
        public final double latitude, longitude;
        public final Float accuracyMetres;
        public final String provider;
        public final long sampledAtMs, elapsedNanos;
        public final boolean mock;
        public Fix(double latitude, double longitude, Float accuracyMetres, String provider,
                   long sampledAtMs, long elapsedNanos, boolean mock) {
            this.latitude = latitude; this.longitude = longitude; this.accuracyMetres = accuracyMetres;
            this.provider = provider; this.sampledAtMs = sampledAtMs; this.elapsedNanos = elapsedNanos; this.mock = mock;
        }
    }
    public static final class LocationReading {
        public final Fix fix;
        public final String reason;
        public final boolean listenerReleased;
        public LocationReading(Fix fix, String reason, boolean listenerReleased) {
            this.fix = fix; this.reason = reason; this.listenerReleased = listenerReleased;
        }
    }
    public static final class BatteryReading {
        public final Integer level, scale, plugged, status;
        public final Boolean present;
        public BatteryReading(Integer level, Integer scale, Integer plugged, Integer status, Boolean present) {
            this.level = level; this.scale = scale; this.plugged = plugged; this.status = status; this.present = present;
        }
    }
    public static final class Sample {
        private final String json;
        private Sample(JSONObject json) { this.json = json.toString(); }
        public JSONObject toJson() throws Exception { return new JSONObject(json); }
        public JSONObject reportFields() throws Exception { return toJson().getJSONObject("fields"); }
        /** 仅用于生成新报告；已冻结pending-report必须原样重传，不再次采样或合并。 */
        public JSONObject mergeReport(JSONObject report, long nowMs, long maximumSnapshotAgeMs) throws Exception {
            TelemetryJson.id(report.getString("report_id"));
            if (!report.has("reported_at") || maximumSnapshotAgeMs < 0 || maximumSnapshotAgeMs > 60000)
                throw new IllegalArgumentException("INVALID_REPORT_MERGE");
            JSONObject sample = toJson(), fields = sample.getJSONObject("fields");
            java.util.Iterator<String> keys = fields.keys();
            while (keys.hasNext()) if (report.has(keys.next())) throw new IllegalArgumentException("TELEMETRY_ALREADY_PRESENT");
            long captured = sample.getLong("captured_at_ms");
            if (!sample.getBoolean("clock_stable") || nowMs < captured || nowMs - captured > maximumSnapshotAgeMs) {
                fields = missingFields("snapshot_stale");
            } else if (!fields.isNull("gps")) {
                long fixAt = sample.getJSONObject("evidence").getJSONObject("location").getLong("sampled_at_ms");
                if (nowMs < fixAt || nowMs - fixAt > sample.getLong("max_location_age_ms"))
                    fields.put("gps", JSONObject.NULL).put("location_reason", "stale_or_invalid_location_time");
            }
            JSONObject result = new JSONObject(report.toString()); keys = fields.keys();
            while (keys.hasNext()) { String key = keys.next(); result.put(key, fields.get(key)); }
            return result;
        }
    }
    private static JSONObject missingFields(String reason) throws Exception {
        return new JSONObject().put("gps", JSONObject.NULL).put("location_reason", reason).put("battery", JSONObject.NULL)
                .put("charging", JSONObject.NULL).put("battery_present", JSONObject.NULL);
    }
    private final Access access;
    private final Clock clock;
    public TelemetryCollector(Access access, Clock clock) {
        if (access == null || clock == null) throw new IllegalArgumentException("MISSING_TELEMETRY_ACCESS");
        this.access = access; this.clock = clock;
    }
    public Sample collect(Limits limits) throws Exception {
        if (limits == null) throw new IllegalArgumentException("MISSING_TELEMETRY_LIMITS");
        long started = clock.wallTimeMillis(), elapsed = clock.elapsedRealtimeNanos();
        JSONObject fields = new JSONObject().put("gps", JSONObject.NULL).put("battery", JSONObject.NULL)
                .put("charging", JSONObject.NULL).put("battery_present", JSONObject.NULL);
        JSONObject location = new JSONObject(), power = new JSONObject();
        LocationReading reading;
        try { reading = access.location(limits, clock); }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw cancelled; }
        catch (SecurityException denied) { reading = new LocationReading(null, "permission_denied", true); }
        catch (Exception unavailable) { reading = new LocationReading(null, "provider_unavailable", false); }
        if (reading == null) reading = new LocationReading(null, "provider_unavailable", false);
        String invalid = invalidFix(reading.fix, limits, clock);
        String reason = reading.fix == null ? safeReason(reading.reason) : invalid;
        if (invalid == null) {
            Fix fix = reading.fix;
            fields.put("gps", new JSONObject().put("lat", fix.latitude).put("lng", fix.longitude)
                    .put("acc_m", fix.accuracyMetres == null ? JSONObject.NULL : fix.accuracyMetres)
                    .put("provider", fix.provider).put("at", TelemetryJson.utc(fix.sampledAtMs)));
            reason = "recent_cache".equals(reading.reason) ? "recent_cache" : "sampled";
            location.put("sample_elapsed_nanos", fix.elapsedNanos).put("sampled_at_ms", fix.sampledAtMs)
                    .put("accuracy_state", fix.accuracyMetres == null ? "NOT_REPORTED" : "OBSERVED");
        }
        fields.put("location_reason", reason);
        location.put("state", invalid == null ? "OBSERVED" : "MISSING").put("reason", reason)
                .put("listener_released", reading.listenerReleased);
        try {
            BatteryReading battery = access.battery();
            if (battery == null) throw new IllegalStateException("NO_BATTERY_SNAPSHOT");
            fields.put("battery_present", battery.present == null ? JSONObject.NULL : battery.present);
            boolean plugKnown = battery.plugged != null && battery.plugged >= 0 && (battery.plugged & ~7) == 0;
            if (plugKnown) fields.put("charging", battery.plugged > 0);
            boolean levelKnown = Boolean.TRUE.equals(battery.present) && battery.level != null && battery.scale != null
                    && battery.scale > 0 && battery.level >= 0 && battery.level <= battery.scale;
            if (levelKnown) fields.put("battery", Math.round(100d * battery.level / battery.scale));
            String supply = !plugKnown ? "unknown" : battery.plugged == 1 ? "ac" : battery.plugged == 2 ? "usb"
                    : battery.plugged == 4 ? "wireless" : battery.plugged > 0 ? "external"
                    : Boolean.TRUE.equals(battery.present) ? "battery" : "unknown";
            power.put("state", "OBSERVED").put("source", "ACTION_BATTERY_CHANGED")
                    .put("observed_at_ms", clock.wallTimeMillis()).put("supply", supply)
                    .put("level_state", levelKnown ? "OBSERVED" : Boolean.FALSE.equals(battery.present) ? "NO_BATTERY" : "UNKNOWN")
                    .put("raw_level", battery.level == null ? JSONObject.NULL : battery.level)
                    .put("raw_scale", battery.scale == null ? JSONObject.NULL : battery.scale)
                    .put("raw_plugged", battery.plugged == null ? JSONObject.NULL : battery.plugged)
                    .put("raw_status", battery.status == null ? JSONObject.NULL : battery.status);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt(); throw cancelled;
        } catch (Exception unavailable) {
            power.put("state", "READ_FAILED").put("reason", "battery_unavailable");
        }
        long ended = clock.wallTimeMillis(), endElapsed = clock.elapsedRealtimeNanos();
        return new Sample(new JSONObject().put("schemaVersion", 1).put("started_at_ms", started).put("captured_at_ms", ended)
                .put("max_location_age_ms", limits.maxLocationAgeMs)
                .put("elapsed_ms", Math.max(0, (endElapsed - elapsed) / 1000000))
                .put("clock_stable", started > 0 && ended >= started && elapsed > 0 && endElapsed >= elapsed
                        && Math.abs((ended - started) - (endElapsed - elapsed) / 1000000) <= 1000)
                .put("fields", fields).put("evidence", new JSONObject().put("location", location).put("power", power)));
    }
    static String invalidFix(Fix fix, Limits limits, Clock clock) {
        if (fix == null) return "no_location";
        if (fix.mock) return "mock_location_rejected";
        if (!"gps".equals(fix.provider) && !"network".equals(fix.provider)) return "unsupported_provider";
        if (Double.isNaN(fix.latitude) || Double.isInfinite(fix.latitude) || Math.abs(fix.latitude) > 90
                || Double.isNaN(fix.longitude) || Double.isInfinite(fix.longitude) || Math.abs(fix.longitude) > 180) return "invalid_coordinates";
        if (fix.accuracyMetres != null && (Float.isNaN(fix.accuracyMetres) || Float.isInfinite(fix.accuracyMetres)
                || fix.accuracyMetres <= 0)) return "invalid_accuracy";
        long now = clock.elapsedRealtimeNanos();
        if (fix.elapsedNanos <= 0 || fix.elapsedNanos > now || now - fix.elapsedNanos > limits.maxLocationAgeMs * 1000000)
            return "stale_or_invalid_location_time";
        long wallNow = clock.wallTimeMillis();
        if (fix.sampledAtMs <= 0 || fix.sampledAtMs > wallNow || wallNow - fix.sampledAtMs > limits.maxLocationAgeMs)
            return "invalid_location_wall_time";
        return null;
    }
    private static String safeReason(String reason) {
        for (String allowed : new String[]{"location_disabled", "permission_denied", "provider_unavailable", "timeout",
                "no_cached_location", "no_location", "cleanup_failed"}) if (allowed.equals(reason)) return reason;
        return "no_location";
    }
}
