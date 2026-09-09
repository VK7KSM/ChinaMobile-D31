package dev.octoshrimpy.quik.feature.phone;

import android.content.Context;
import android.content.SharedPreferences;

public final class SipConfigStore {
    private static final String PREFS = "d31_sip_profile";

    public enum Transport {
        UDP(5060), TCP(5060), TLS(5061);

        public final int defaultPort;

        Transport(int defaultPort) {
            this.defaultPort = defaultPort;
        }
    }

    public static final class Profile {
        public final boolean enabled;
        public final String server;
        public final int port;
        public final String username;
        public final String password;
        public final String realm;
        public final Transport transport;

        Profile(boolean enabled, String server, int port, String username, String password,
                String realm, Transport transport) {
            this.enabled = enabled;
            this.server = server;
            this.port = port;
            this.username = username;
            this.password = password;
            this.realm = realm;
            this.transport = transport;
        }

        public boolean isConfigured() {
            return enabled && !server.isEmpty() && !username.isEmpty() && port > 0 && port <= 65535;
        }
    }

    private final SharedPreferences prefs;

    public SipConfigStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public Profile load() {
        Transport transport;
        try {
            transport = Transport.valueOf(prefs.getString("transport", Transport.TLS.name()));
        } catch (Exception ignored) {
            transport = Transport.TLS;
        }
        int port = prefs.getInt("port", transport.defaultPort);
        return new Profile(
            prefs.getBoolean("enabled", false),
            prefs.getString("server", "").trim(),
            port,
            prefs.getString("username", "").trim(),
            prefs.getString("password", ""),
            prefs.getString("realm", "*").trim(),
            transport
        );
    }

    public void save(boolean enabled, String server, int port, String username, String password,
                     String realm, Transport transport) {
        prefs.edit()
            .putBoolean("enabled", enabled)
            .putString("server", server.trim())
            .putInt("port", port)
            .putString("username", username.trim())
            .putString("password", password)
            .putString("realm", realm.trim().isEmpty() ? "*" : realm.trim())
            .putString("transport", transport.name())
            .apply();
    }
}
