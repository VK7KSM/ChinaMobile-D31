package net.elfradio.d31system;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
import android.telephony.PhoneStateListener;
import java.util.ArrayList;
import java.util.List;

public final class MessageNotificationListener extends NotificationListenerService {
    private static final Uri UNREAD = Uri.parse("content://net.elfradio.d31phone.debug.unread/count");
    private HandlerThread thread;
    private volatile Handler worker;
    private ContentObserver unreadObserver;
    private NoticeRepository repository;
    private NoticeTasks tasks;
    private volatile long generation;
    private volatile boolean destroyed, lastCallIdle, phoneListening;
    private TelephonyManager phone;
    private PhoneStateListener callListener;
    @Override public void onCreate() {
        super.onCreate();
        repository=NoticeRepository.get(this);
        tasks=repository.tasks;
        generation=tasks.start();
        tasks.commit(generation,repository::disconnected);
        tasks.guard(() -> {
        thread=new HandlerThread("d31-notifications"); thread.start();
        worker=new Handler(thread.getLooper());
        unreadObserver=new ContentObserver(worker) {
            @Override public void onChange(boolean self) { queue(MessageNotificationListener.this::refreshUnread); }
            @Override public void onChange(boolean self, Uri uri) { onChange(self); }
        };
        getContentResolver().registerContentObserver(UNREAD,true,unreadObserver);
        });
        tasks.guard(() -> {
            phone=(TelephonyManager)getSystemService(TELEPHONY_SERVICE);
            if(phone==null) return;
            callListener=new PhoneStateListener() {
                @Override public void onCallStateChanged(int state,String ignoredNumber) {
                    if(destroyed) return;
                    lastCallIdle=state==TelephonyManager.CALL_STATE_IDLE;
                    boolean idle=lastCallIdle;
                    // 主线程立即发布，不排在可能阻塞的未读查询后面。
                    tasks.commit(generation,() -> repository.callState(idle));
                }
            };
            // 无有效订阅时原厂框架不一定补发初次回调；先读取当前通话状态。
            lastCallIdle=phone.getCallState()==TelephonyManager.CALL_STATE_IDLE;
            phone.listen(callListener,PhoneStateListener.LISTEN_CALL_STATE);
            phoneListening=true;
            tasks.commit(generation,() -> repository.callState(lastCallIdle));
        });
    }
    @Override public void onListenerConnected() {
        if(destroyed || tasks==null) return;
        generation=tasks.start();
        tasks.commit(generation,() -> {
            repository.disconnected(); repository.callState(phoneListening && lastCallIdle);
        });
        queue(token -> {
            List<NoticeRepository.Item> active=new ArrayList<>();
                StatusBarNotification[] notifications=getActiveNotifications();
                if(notifications==null) return;
                if (notifications!=null) for(StatusBarNotification notification:notifications) {
                    tasks.guard(() -> {
                    NoticeRepository.Item item=item(notification);
                    if(item!=null) active.add(item);
                    });
                }
                tasks.commit(token,() -> repository.replace(active));
        });
        queue(this::refreshUnread);
    }
    @Override public void onListenerDisconnected() {
        if(tasks!=null) tasks.stop(generation,repository::disconnected);
    }
    @Override public void onNotificationPosted(StatusBarNotification notification) {
        queue(token -> {
            if(notification==null) return;
            String key=notification.getKey();
            NoticeRepository.Item item=item(notification);
            if(item==null) {
                tasks.commit(token,() -> repository.update(key,null,false));
                if(NoticePolicy.SMS.equals(notification.getPackageName())) refreshUnread(token);
                return;
            }
            Notification n=notification.getNotification();
            boolean sms=NoticePolicy.SMS.equals(item.app);
            boolean alert=!sms || (n.extras!=null && n.extras.getBoolean("d31.alert",false));
            AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);
            NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            boolean quiet=audio==null || audio.getRingerMode()!=AudioManager.RINGER_MODE_NORMAL;
            boolean interrupted=manager==null || manager.getCurrentInterruptionFilter()!=NotificationManager.INTERRUPTION_FILTER_ALL;
            // Telegram的静音聊天和后台状态不应被桌面横幅重新唤醒。
            boolean silentTelegram=!sms && n.sound==null && n.vibrate==null
                    && (n.defaults&(Notification.DEFAULT_SOUND|Notification.DEFAULT_VIBRATE))==0;
            boolean allowed=NoticePolicy.canShowBanner(alert,!NoticeCallState.allowed(this),quiet||silentTelegram,interrupted);
            tasks.commit(token,() -> repository.update(key,item,allowed && repository.callIdle()));
            if(sms) refreshUnread(token);
        });
    }
    @Override public void onNotificationRemoved(StatusBarNotification notification) {
        queue(token -> {
            if(notification==null) return;
            String key=notification.getKey();
            tasks.commit(token,() -> repository.remove(key));
            if(NoticePolicy.SMS.equals(notification.getPackageName())) refreshUnread(token);
        });
    }
    private interface Work { void run(long token); }
    private void queue(Work action) {
        NoticeTasks gate=tasks;
        Handler handler=worker;
        long token=generation;
        if(destroyed || gate==null || handler==null || !gate.accepts(token)) return;
        gate.guard(() -> handler.post(() -> gate.run(token,() -> action.run(token))));
    }
    private NoticeRepository.Item item(StatusBarNotification notification) {
        if(notification==null) return null;
        String app=notification.getPackageName();
        if(!NoticePolicy.SMS.equals(app) && !NoticePolicy.telegram(app)) return null;
        Notification n=notification.getNotification();
        if(n==null) return null;
        Bundle extras=n.extras==null?Bundle.EMPTY:n.extras;
        CharSequence text=extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if(text==null) text=extras.getCharSequence(Notification.EXTRA_TEXT);
        if(text==null) {
            CharSequence[] lines=extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if(lines!=null && lines.length>0) text=lines[lines.length-1];
        }
        if(!NoticePolicy.message(notification.getPackageName(),(n.flags&Notification.FLAG_ONGOING_EVENT)!=0,
                (n.flags&Notification.FLAG_GROUP_SUMMARY)!=0,extras.getBoolean("d31.message",false),n.category,text!=null)) return null;
        String title=NoticePolicy.bounded(extras.getCharSequence(Notification.EXTRA_TITLE),80);
        String body=NoticePolicy.bounded(text,240);
        String event=eventId(notification.getKey()+"|"+n.when+"|"+title+"|"+body);
        return new NoticeRepository.Item(notification.getKey(),notification.getPackageName(),title,body,event,n.contentIntent,notification.getPostTime());
    }
    private static String eventId(String value) {
        try {
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result=new StringBuilder();
            for(byte part:hash) result.append(String.format(java.util.Locale.ROOT,"%02x",part&255));
            return result.toString();
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void refreshUnread(long token) {
        if(!tasks.accepts(token)) return;
        try(Cursor cursor=getContentResolver().query(UNREAD,new String[]{"total"},null,null,null)) {
            if(cursor!=null && cursor.moveToFirst()) {
                int unread=cursor.getInt(cursor.getColumnIndexOrThrow("total"));
                tasks.commit(token,() -> repository.unread(unread));
            }
        }
    }
    @Override public void onDestroy() {
        destroyed=true;
        if(tasks!=null) {
            tasks.stop(generation,repository::disconnected);
            tasks.guard(() -> { if(unreadObserver!=null) getContentResolver().unregisterContentObserver(unreadObserver); });
            tasks.guard(() -> { if(phone!=null && callListener!=null) phone.listen(callListener,PhoneStateListener.LISTEN_NONE); });
            tasks.guard(() -> { if(worker!=null) worker.removeCallbacksAndMessages(null); });
            tasks.guard(() -> { if(thread!=null) thread.quitSafely(); });
        }
        worker=null; phoneListening=false;
        super.onDestroy();
    }
}
