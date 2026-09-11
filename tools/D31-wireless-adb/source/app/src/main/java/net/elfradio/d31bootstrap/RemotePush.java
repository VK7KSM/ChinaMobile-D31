package net.elfradio.d31bootstrap;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;

final class RemotePush implements Closeable {
    private MqttClient client;
    private final RemoteState state;
    private final Runnable wake;
    private long retryAt;
    private int failures;
    RemotePush(RemoteState state, Runnable wake) { this.state = state; this.wake = wake; }

    boolean connected() { return client != null && client.isConnected(); }

    boolean ensure(JSONObject config, long now) throws Exception {
        if (connected() || now < retryAt) return false;
        RemoteProtocol.validateConnection(config);
        close();
        try {
            String topic = config.getString("topic");
            client = new MqttClient("ssl://" + config.getString("host") + ":" + config.getInt("port"),
                    config.getString("client_id"), new MemoryPersistence());
            client.setTimeToWait(18000);
            client.setManualAcks(true);
            final MqttClient source = client;
            client.setCallback(new MqttCallback() {
                public void deliveryComplete(IMqttDeliveryToken token) { }
                public void connectionLost(Throwable error) { wake.run(); }
                public void messageArrived(String received, MqttMessage message) throws Exception {
                    byte[] bytes = message.getPayload();
                    if (received.equals(topic) && bytes.length <= 4096) {
                        JSONObject notice = null;
                        try { notice = new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }
                        catch (org.json.JSONException invalid) { }
                        if (notice != null) state.notice(notice, System.currentTimeMillis());
                    }
                    source.messageArrivedComplete(message.getId(), message.getQos());
                    wake.run();
                }
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            options.setCleanSession(false); options.setAutomaticReconnect(false);
            options.setConnectionTimeout(15); options.setKeepAliveInterval(300);
            options.setUserName(config.getString("username"));
            options.setPassword(config.getString("password").toCharArray());
            options.setSocketFactory(RemoteTls.factory());
            // Android 6通过显式验证器检查域名，避免Paho忽略较新SSLParameters接口缺失。
            options.setHttpsHostnameVerificationEnabled(false);
            options.setSSLHostnameVerifier(javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier());
            client.connect(options);
            IMqttToken subscribed = client.subscribeWithResponse(topic, 1);
            if (subscribed.getGrantedQos().length != 1 || subscribed.getGrantedQos()[0] == 128)
                throw new java.io.IOException("推送订阅被拒绝");
            failures = 0; retryAt = 0; return true;
        } catch (Exception error) {
            close(); retryAt = android.os.SystemClock.elapsedRealtime() + Math.min(300000L, 5000L << Math.min(failures++, 6));
            throw error;
        }
    }

    @Override public void close() {
        MqttClient old = client; client = null;
        if (old != null) {
            try { old.disconnectForcibly(0, 1000, false); } catch (Exception ignored) { }
            try { old.close(true); } catch (Exception ignored) { }
        }
    }
}
