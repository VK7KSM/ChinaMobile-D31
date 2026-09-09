package dev.octoshrimpy.quik.feature.phone;

public final class SipUri {
    private SipUri() {}

    public static String user(String uri) {
        if (uri == null) return "";
        String value = uri.trim();
        int left = value.indexOf('<'), right = value.indexOf('>');
        if (left >= 0 && right > left) value = value.substring(left + 1, right);
        value = value.replaceFirst("(?i)^sips?:", "");
        int at = value.indexOf('@');
        if (at >= 0) value = value.substring(0, at);
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) value = value.substring(0, semicolon);
        return value.replace("\"", "").trim();
    }

    public static String destination(String input, SipConfigStore.Profile profile) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty() || value.matches("(?s).*[\\r\\n<>\\s].*"))
            throw new IllegalArgumentException("收件人地址不合法");
        if (value.matches("(?i)^sips?:.*")) return value;
        if (value.contains("@")) return "sip:" + value;
        return "sip:" + value + "@" + authority(profile) + transport(profile);
    }

    public static String registrar(SipConfigStore.Profile profile) {
        return "sip:" + authority(profile) + transport(profile);
    }

    public static String proxy(SipConfigStore.Profile profile) {
        return registrar(profile) + ";lr";
    }

    private static String authority(SipConfigStore.Profile profile) {
        String host = profile.server;
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return host + ":" + profile.port;
    }

    private static String transport(SipConfigStore.Profile profile) {
        return ";transport=" + profile.transport.name().toLowerCase(java.util.Locale.ROOT);
    }
}
