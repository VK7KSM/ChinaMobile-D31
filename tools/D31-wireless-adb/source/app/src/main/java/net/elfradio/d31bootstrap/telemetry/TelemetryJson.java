package net.elfradio.d31bootstrap.telemetry;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class TelemetryJson {
    static String utc(long time) {
        if (time <= 0) throw new IllegalArgumentException("INVALID_TIMESTAMP");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC")); return format.format(new Date(time));
    }
    static String id(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,96}")) throw new IllegalArgumentException("INVALID_REPORT_ID");
        return id;
    }
    private TelemetryJson() { }
}
