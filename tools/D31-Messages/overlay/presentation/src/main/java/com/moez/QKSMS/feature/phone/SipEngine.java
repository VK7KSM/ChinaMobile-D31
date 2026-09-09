package dev.octoshrimpy.quik.feature.phone;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.pjsip.pjsua2.Account;
import org.pjsip.pjsua2.AccountConfig;
import org.pjsip.pjsua2.AccountInfo;
import org.pjsip.pjsua2.AuthCredInfo;
import org.pjsip.pjsua2.Buddy;
import org.pjsip.pjsua2.BuddyConfig;
import org.pjsip.pjsua2.Call;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.CodecInfoVector2;
import org.pjsip.pjsua2.Endpoint;
import org.pjsip.pjsua2.EpConfig;
import org.pjsip.pjsua2.OnIncomingCallParam;
import org.pjsip.pjsua2.OnInstantMessageParam;
import org.pjsip.pjsua2.OnInstantMessageStatusParam;
import org.pjsip.pjsua2.OnRegStateParam;
import org.pjsip.pjsua2.SendInstantMessageParam;
import org.pjsip.pjsua2.TlsConfig;
import org.pjsip.pjsua2.TransportConfig;
import org.pjsip.pjsua2.SWIGTYPE_p_void;
import org.pjsip.pjsua2.pjsip_status_code;
import org.pjsip.pjsua2.pjsip_transport_type_e;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

public final class SipEngine {
    private static final String TAG = "D31SipEngine";
    private static final SipEngine INSTANCE = new SipEngine();

    public interface Listener {
        void onRegistrationChanged(boolean registered, String detail);
        void onMessageReceived(String peer, String body);
        void onMessageStatus(long id, boolean sent, String detail);
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<D31Buddy> pendingBuddies = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Context context;
    private Endpoint endpoint;
    private D31Account account;
    private SipConfigStore.Profile profile;
    private volatile boolean registered;
    private String registrationDetail = "未启动";

    private SipEngine() {}

    public static SipEngine get() {
        return INSTANCE;
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
        listener.onRegistrationChanged(registered, registrationDetail);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized boolean isRegistered() {
        return registered;
    }

    public synchronized void start(Context appContext) throws Exception {
        if (endpoint != null) return;
        context = appContext.getApplicationContext();
        profile = new SipConfigStore(context).load();
        if (!profile.isConfigured()) {
            registrationDetail = "未配置SIP账户";
            notifyRegistration();
            return;
        }

        endpoint = new Endpoint();
        endpoint.libCreate();

        EpConfig epConfig = new EpConfig();
        epConfig.getUaConfig().setUserAgent("D31-Messages/0.3");
        epConfig.getUaConfig().setMaxCalls(1);
        epConfig.getLogConfig().setLevel(4);
        epConfig.getLogConfig().setConsoleLevel(3);
        endpoint.libInit(epConfig);

        TransportConfig transport = new TransportConfig();
        transport.setPort(0);
        int transportType;
        if (profile.transport == SipConfigStore.Transport.TLS) {
            TlsConfig tls = transport.getTlsConfig();
            tls.setCaBuf(loadCaBundle());
            tls.setVerifyServer(true);
            tls.setVerifyClient(false);
            tls.setRequireClientCert(false);
            transportType = pjsip_transport_type_e.PJSIP_TRANSPORT_TLS;
        } else if (profile.transport == SipConfigStore.Transport.TCP) {
            transportType = pjsip_transport_type_e.PJSIP_TRANSPORT_TCP;
        } else {
            transportType = pjsip_transport_type_e.PJSIP_TRANSPORT_UDP;
        }
        int transportId = endpoint.transportCreate(transportType, transport);
        endpoint.libStart();
        disableMedia();

        AccountConfig config = new AccountConfig();
        config.setIdUri("sip:" + profile.username + "@" + profile.server);
        config.getRegConfig().setRegistrarUri(SipUri.registrar(profile));
        config.getSipConfig().setTransportId(transportId);
        config.getSipConfig().getProxies().add(SipUri.proxy(profile));
        config.getRegConfig().setTimeoutSec(300);
        config.getRegConfig().setRetryIntervalSec(30);
        config.getSipConfig().getAuthCreds().add(new AuthCredInfo(
            "digest", profile.realm.isEmpty() ? "*" : profile.realm,
            profile.username, 0, profile.password));
        config.getNatConfig().setContactRewriteUse(1);
        config.getNatConfig().setViaRewriteUse(1);
        config.getNatConfig().setSipOutboundUse(1);

        account = new D31Account();
        registrationDetail = "正在注册";
        notifyRegistration();
        account.create(config);
    }

    public synchronized void stop() {
        for (D31Buddy buddy : pendingBuddies) {
            try { buddy.delete(); } catch (Exception ignored) {}
        }
        pendingBuddies.clear();
        if (account != null) {
            try { account.setRegistration(false); } catch (Exception ignored) {}
            try { account.delete(); } catch (Exception ignored) {}
            account = null;
        }
        if (endpoint != null) {
            try { endpoint.libDestroy(); } catch (Exception e) { Log.w(TAG, "libDestroy", e); }
            try { endpoint.delete(); } catch (Exception ignored) {}
            endpoint = null;
        }
        registered = false;
        registrationDetail = "未启动";
        notifyRegistration();
    }

    public synchronized void sendMessage(long id, String peer, String body) throws Exception {
        requireReady();
        BuddyConfig buddyConfig = new BuddyConfig();
        buddyConfig.setUri(SipUri.destination(peer, profile));
        buddyConfig.setSubscribe(false);
        D31Buddy buddy = new D31Buddy(id);
        buddy.create(account, buddyConfig);
        pendingBuddies.add(buddy);
        SendInstantMessageParam param = new SendInstantMessageParam();
        param.setContentType("text/plain");
        param.setContent(body);
        param.setUserData(new MessageToken(id));
        try {
            buddy.sendInstantMessage(param);
        } catch (Exception error) {
            pendingBuddies.remove(buddy);
            buddy.delete();
            throw error;
        }
    }

    private void requireReady() {
        if (!registered || account == null || profile == null) {
            throw new IllegalStateException("SIP账户尚未注册");
        }
    }

    private String loadCaBundle() throws Exception {
        try (InputStream input = context.getAssets().open("cacert.pem");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.US_ASCII);
        }
    }

    private void disableMedia() {
        try { endpoint.audDevManager().setNullDev(); }
        catch (Exception e) { Log.w(TAG, "禁用音频设备失败", e); }
        try {
            CodecInfoVector2 codecs = endpoint.videoCodecEnum2();
            for (int i = 0; i < codecs.size(); i++) {
                endpoint.videoCodecSetPriority(codecs.get(i).getCodecId(), (short) 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "禁用视频编解码器失败", e);
        }
    }

    private void notifyRegistration() {
        final boolean currentRegistered = registered;
        final String currentDetail = registrationDetail;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onRegistrationChanged(currentRegistered, currentDetail);
            }
        });
    }

    private final class D31Account extends Account {
        @Override
        public void onRegState(OnRegStateParam param) {
            try {
                AccountInfo info = getInfo();
                registered = info.getRegStatus() == pjsip_status_code.PJSIP_SC_OK;
                registrationDetail = info.getRegStatus() + " " + info.getRegStatusText();
            } catch (Exception e) {
                registered = false;
                registrationDetail = e.getMessage();
            }
            notifyRegistration();
        }

        @Override
        public void onIncomingCall(OnIncomingCallParam param) {
            RejectCall rejected = null;
            try {
                rejected = new RejectCall(this, param.getCallId());
                CallOpParam response = new CallOpParam();
                response.setStatusCode(pjsip_status_code.PJSIP_SC_BUSY_HERE);
                rejected.hangup(response);
            } catch (Exception e) {
                Log.w(TAG, "拒绝消息账户上的SIP呼叫失败", e);
            } finally {
                if (rejected != null) {
                    try { rejected.delete(); } catch (Exception ignored) {}
                }
            }
        }

        @Override
        public void onInstantMessage(OnInstantMessageParam param) {
            if (param.getContentType() != null &&
                !param.getContentType().toLowerCase().contains("text/plain")) return;
            final String peer = SipUri.user(param.getFromUri());
            final String body = param.getMsgBody();
            mainHandler.post(() -> {
                for (Listener listener : listeners) listener.onMessageReceived(peer, body);
            });
        }

        @Override
        public void onInstantMessageStatus(OnInstantMessageStatusParam param) {
            if (param.getCode() < 200) return;
            final long id = MessageToken.id(param.getUserData());
            final boolean sent = param.getCode() >= 200 && param.getCode() < 300;
            final String detail = param.getCode() + " " + param.getReason();
            mainHandler.post(() -> {
                for (D31Buddy buddy : pendingBuddies) {
                    if (buddy.id == id) {
                        pendingBuddies.remove(buddy);
                        try { buddy.delete(); } catch (Exception ignored) {}
                        break;
                    }
                }
                Log.i(TAG, "MESSAGE_STATUS id=" + id + " code=" + detail);
                for (Listener listener : listeners) {
                    listener.onMessageStatus(id, sent, detail);
                }
            });
        }
    }

    private static final class RejectCall extends Call {
        RejectCall(Account account, int callId) {
            super(account, callId);
        }
    }

    private final class D31Buddy extends Buddy {
        private final long id;
        D31Buddy(long id) { this.id = id; }
    }

    // PJSIP将userData作为不解引用的应用令牌原样回传。
    private static final class MessageToken extends SWIGTYPE_p_void {
        MessageToken(long id) { super(id, false); }
        static long id(SWIGTYPE_p_void value) { return getCPtr(value); }
    }
}
