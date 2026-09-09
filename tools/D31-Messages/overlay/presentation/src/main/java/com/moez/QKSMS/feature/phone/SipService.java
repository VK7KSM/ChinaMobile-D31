package dev.octoshrimpy.quik.feature.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import dev.octoshrimpy.quik.R;
import dev.octoshrimpy.quik.feature.main.MainActivity;

public final class SipService extends Service implements SipEngine.Listener {
    public static final String ACTION_START = "net.elfradio.d31phone.action.SIP_START";
    public static final String ACTION_STOP = "net.elfradio.d31phone.action.SIP_STOP";
    public static final String ACTION_RESTART = "net.elfradio.d31phone.action.SIP_RESTART";
    public static final String ACTION_MESSAGE = "net.elfradio.d31phone.action.SIP_MESSAGE";
    public static final String ACTION_MESSAGES_CHANGED = "net.elfradio.d31phone.action.SIP_MESSAGES_CHANGED";
    public static final String EXTRA_PEER = "peer";
    public static final String EXTRA_BODY = "body";
    private static final String CHANNEL = "d31_sip_messages";
    private static final int NOTIFICATION_ID = 3108;
    private static final String TAG = "D31SipService";

    private SipMessageStore messages;

    @Override
    public void onCreate() {
        super.onCreate();
        messages = new SipMessageStore(this);
        createChannel();
        startForeground(NOTIFICATION_ID, notification("SIP消息服务正在启动"));
        SipEngine.get().addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        try {
            if (ACTION_STOP.equals(action)) {
                SipEngine.get().stop();
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_RESTART.equals(action)) SipEngine.get().stop();
            SipEngine.get().start(this);
            if (ACTION_MESSAGE.equals(action)) {
                String peer = intent.getStringExtra(EXTRA_PEER);
                String body = intent.getStringExtra(EXTRA_BODY);
                long messageId = messages.add(
                    peer, body, SipMessageStore.DIRECTION_OUT, SipMessageStore.STATE_PENDING);
                try {
                    SipEngine.get().sendMessage(messageId, peer, body);
                } catch (Exception e) {
                    messages.setState(messageId, SipMessageStore.STATE_FAILED, e.getMessage());
                    broadcastMessagesChanged();
                    throw e;
                }
                broadcastMessagesChanged();
            }
        } catch (Exception e) {
            Log.e(TAG, "SIP消息操作失败", e);
            updateNotification("SIP错误：" + e.getMessage());
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        SipEngine.get().removeListener(this);
        messages.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onRegistrationChanged(boolean registered, String detail) {
        updateNotification(registered ? "SIP消息已连接" : "SIP：" + detail);
    }

    @Override
    public void onMessageReceived(String peer, String body) {
        messages.add(peer, body, SipMessageStore.DIRECTION_IN, SipMessageStore.STATE_RECEIVED);
        broadcastMessagesChanged();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify((int) (System.currentTimeMillis() & 0x7fffffff),
            new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("SIP消息 · " + peer)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(mainPendingIntent())
                .build());
    }

    @Override
    public void onMessageStatus(long id, boolean sent, String detail) {
        messages.setState(id, sent ? SipMessageStore.STATE_SENT : SipMessageStore.STATE_FAILED, detail);
        broadcastMessagesChanged();
    }

    public static void ensureStarted(Context context) {
        SipConfigStore.Profile profile = new SipConfigStore(context).load();
        if (profile.isConfigured()) {
            context.startService(new Intent(context, SipService.class).setAction(ACTION_START));
        }
    }

    public static void restart(Context context) {
        context.startService(new Intent(context, SipService.class).setAction(ACTION_RESTART));
    }

    public static void sendMessage(Context context, String peer, String body) {
        context.startService(new Intent(context, SipService.class)
            .setAction(ACTION_MESSAGE).putExtra(EXTRA_PEER, peer).putExtra(EXTRA_BODY, body));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL, "D31 SIP消息", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("D31短信")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(mainPendingIntent())
            .build();
    }

    private PendingIntent mainPendingIntent() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
    }

    private void updateNotification(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
            .notify(NOTIFICATION_ID, notification(text));
    }

    private void broadcastMessagesChanged() {
        sendBroadcast(new Intent(ACTION_MESSAGES_CHANGED).setPackage(getPackageName()));
    }
}
