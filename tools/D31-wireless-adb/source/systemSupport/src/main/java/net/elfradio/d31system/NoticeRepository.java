package net.elfradio.d31system;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class NoticeRepository {
    interface Clock { long now(); }
    static final Uri URI = Uri.parse("content://net.elfradio.d31system.notices");
    private static NoticeRepository instance;
    static synchronized NoticeRepository get(Context context) {
        if (instance == null) instance = new NoticeRepository(context.getApplicationContext());
        return instance;
    }
    static final class Item {
        final String key, app, title, body, event;
        final PendingIntent open;
        final long when;
        Item(String key, String app, String title, String body, String event, PendingIntent open, long when) {
            this.key=key; this.app=app; this.title=title; this.body=body;
            this.event=event; this.open=open; this.when=when;
        }
    }
    private final Context context;
    private final Clock clock;
    private final Runnable publish;
    final NoticeTasks tasks=new NoticeTasks(error -> android.util.Log.w(
            "D31Notifications","通知任务失败："+error.getClass().getSimpleName()));
    private final LinkedHashMap<String, Item> items = new LinkedHashMap<>();
    private final LinkedHashMap<String, Boolean> alerted = new LinkedHashMap<>();
    private int smsUnread;
    private Item banner;
    private long bannerUntil;
    private boolean callIdle;
    private NoticeRepository(Context context) {
        this(context,SystemClock::elapsedRealtime,() -> context.getContentResolver().notifyChange(URI,null));
    }
    NoticeRepository(Context context,Clock clock,Runnable publish) {
        this.context=context; this.clock=clock; this.publish=publish;
    }
    synchronized void disconnected() {
        items.clear(); smsUnread=0; banner=null; bannerUntil=0; callIdle=false; changed();
    }
    synchronized void callState(boolean idle) {
        if (callIdle==idle) return;
        callIdle=idle;
        if (!idle) { banner=null; bannerUntil=0; }
        changed();
    }
    synchronized boolean callIdle() { return callIdle; }
    synchronized Item currentBanner() { return banner!=null && bannerUntil>clock.now() ? banner : null; }
    synchronized void update(String key,Item item,boolean alert) {
        if (item==null) remove(key); else post(item,alert);
    }
    synchronized void replace(List<Item> active) {
        items.clear();
        for (Item item : active) { items.put(item.key, item); alerted.put(item.event,true); }
        trim(items, 100);
        trim(alerted, 128);
        banner=null; bannerUntil=0;
        changed();
    }
    synchronized void post(Item item, boolean alert) {
        items.put(item.key, item);
        trim(items, 100);
        boolean fresh = !alerted.containsKey(item.event);
        alerted.put(item.event, true);
        trim(alerted, 128);
        if (alert && fresh) { banner=item; bannerUntil=clock.now()+5000; }
        changed();
    }
    synchronized void remove(String key) {
        boolean removed=items.remove(key)!=null;
        if (banner!=null && banner.key.equals(key)) { banner=null; bannerUntil=0; removed=true; }
        if (removed) changed();
    }
    synchronized void unread(int value) {
        value=Math.max(0, value);
        if (smsUnread==value) return;
        smsUnread=value;
        changed();
    }
    synchronized Bundle snapshot() {
        Bundle state = new Bundle();
        state.putInt("sms_unread", smsUnread);
        boolean telegram=false;
        for (Item item : items.values()) telegram |= NoticePolicy.telegram(item.app);
        state.putBoolean("telegram_pending", telegram);
        state.putBoolean("banner_allowed", callIdle && NoticeCallState.allowed(context));
        if (currentBanner()!=null) {
            state.putString("banner_id", banner.event);
            state.putString("banner_title", banner.title);
            state.putString("banner_body", banner.body);
            state.putLong("banner_until", bannerUntil);
            state.putParcelable("banner_intent", banner.open);
        }
        state.putParcelable("center_intent", PendingIntent.getActivity(context, 0,
                new Intent(context, NotificationCenterActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return state;
    }
    synchronized List<Item> items() {
        ArrayList<Item> result = new ArrayList<>(items.values());
        java.util.Collections.sort(result, (a,b) -> Long.compare(b.when,a.when));
        return result;
    }
    private static void trim(LinkedHashMap<?,?> map, int max) {
        while(map.size()>max) map.remove(map.keySet().iterator().next());
    }
    private void changed() { tasks.guard(publish); }
}
