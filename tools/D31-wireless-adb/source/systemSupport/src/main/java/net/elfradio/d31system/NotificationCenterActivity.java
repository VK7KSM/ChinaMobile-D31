package net.elfradio.d31system;

import android.app.Activity;
import android.app.PendingIntent;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

public final class NotificationCenterActivity extends Activity {
    private LinearLayout list;
    private final ContentObserver observer=new ContentObserver(new Handler()) {
        @Override public void onChange(boolean self) { render(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.WHITE);
        LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(16),0,dp(16),0);
        TextView back=label("\u2190",28,Color.rgb(30,40,45)); back.setGravity(Gravity.CENTER); back.setContentDescription("返回");
        back.setBackgroundColor(Color.TRANSPARENT); back.setOnClickListener(v->finish());
        bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title=label("通知",24,Color.rgb(30,40,45)); title.setTypeface(null,Typeface.BOLD);
        bar.addView(title); root.addView(bar,new LinearLayout.LayoutParams(-1,dp(64)));
        ScrollView scroll=new ScrollView(this);
        list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(24),0,dp(24),dp(16));
        scroll.addView(list); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }
    @Override protected void onStart() { super.onStart(); getContentResolver().registerContentObserver(NoticeRepository.URI,false,observer); render(); }
    @Override protected void onStop() { getContentResolver().unregisterContentObserver(observer); super.onStop(); }
    private void render() {
        list.removeAllViews();
        NoticeRepository repository=NoticeRepository.get(this);
        Bundle state=repository.snapshot();
        int unread=state.getInt("sms_unread");
        if(unread>0) {
            TextView sms=label("短信 · "+unread+" 条未读",20,Color.rgb(175,35,76));
            sms.setPadding(dp(16),dp(16),dp(16),dp(16));
            sms.setOnClickListener(v->{
                android.content.Intent intent=getPackageManager().getLaunchIntentForPackage(NoticePolicy.SMS);
                if(intent!=null) startActivity(intent);
            });
            list.addView(sms);
        }
        List<NoticeRepository.Item> items=repository.items();
        if(items.isEmpty() && unread==0) list.addView(label("暂无新消息",20,Color.GRAY));
        for(NoticeRepository.Item item:items) {
            LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(16),dp(12),dp(16),dp(12));
            TextView title=label((NoticePolicy.telegram(item.app)?"Telegram · ":"短信 · ")+item.title,19,Color.rgb(25,35,42));
            title.setTypeface(null,Typeface.BOLD); row.addView(title);
            TextView body=label(item.body,18,Color.rgb(75,85,92)); body.setMaxLines(3); row.addView(body);
            row.setOnClickListener(v->{
                if(item.open!=null) try { item.open.send(); } catch(PendingIntent.CanceledException ignored) { render(); }
            });
            list.addView(row,new LinearLayout.LayoutParams(-1,-2));
            View divider=new View(this); divider.setBackgroundColor(Color.rgb(230,233,235)); list.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        }
    }
    private TextView label(String value,int size,int color) { TextView text=new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); return text; }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
