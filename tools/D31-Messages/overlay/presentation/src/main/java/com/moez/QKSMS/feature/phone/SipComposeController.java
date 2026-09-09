package dev.octoshrimpy.quik.feature.phone;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import dev.octoshrimpy.quik.R;
import dev.octoshrimpy.quik.databinding.ComposeActivityBinding;
import java.text.DateFormat;
import java.util.*;

/** 复用QUIK的编辑布局，仅替换SIP数据与发送通道。 */
public final class SipComposeController implements SipEngine.Listener {
    private final Activity activity;
    private final ComposeActivityBinding view;
    private final SipMessageStore store;
    private final SharedPreferences drafts;
    private final MessageAdapter adapter = new MessageAdapter();
    private String draftKey;
    private String peer;
    private List<SipMessageStore.Message> messages = new ArrayList<>();
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };

    public SipComposeController(Activity activity, ComposeActivityBinding view, String peer) {
        this.activity = activity;
        this.view = view;
        this.peer = peer;
        store = new SipMessageStore(activity);
        drafts = activity.getSharedPreferences("d31_sip_drafts", Context.MODE_PRIVATE);
        draftKey = "body:" + peer;
        view.chips.setVisibility(View.GONE);
        view.sipRecipient.setVisibility(peer.isEmpty() ? View.VISIBLE : View.GONE);
        view.toolbarTitle.setText(peer.isEmpty() ? "新短信" : peer);
        view.toolbarSubtitle.setVisibility(View.VISIBLE);
        view.sipRecipient.setText(peer.isEmpty() ? drafts.getString("recipient", "") : peer);
        view.message.setText(drafts.getString(draftKey, ""));
        view.messageList.setAdapter(adapter);
        view.messageList.setPadding(16, 12, 16, 12);
        view.composeBar.setReferencedIds(new int[] { R.id.messageBackground, R.id.message });
        view.attach.setVisibility(View.GONE);
        view.recordAudioMsg.setVisibility(View.GONE);
        view.sim.setVisibility(View.GONE);
        view.simIndex.setVisibility(View.GONE);
        view.counter.setVisibility(View.GONE);
        view.sendAsGroup.setVisibility(View.GONE);
        view.scheduledGroup.setVisibility(View.GONE);
        view.messageAttachments.setVisibility(View.GONE);
        view.send.setVisibility(View.VISIBLE);
        view.send.setColorFilter(Color.rgb(30, 136, 229));
        view.send.setOnClickListener(v -> send());
        view.toolbar.setNavigationOnClickListener(v -> activity.finish());
        activity.registerReceiver(receiver, new IntentFilter(SipService.ACTION_MESSAGES_CHANGED));
        SipEngine.get().addListener(this);
        SipService.ensureStarted(activity);
        refresh();
        if (peer.isEmpty()) view.sipRecipient.requestFocus();
    }
    public void saveDraft() {
        drafts.edit().putString(draftKey, view.message.getText().toString())
            .putString("recipient", view.sipRecipient.getText().toString()).apply();
    }
    public String getPeer() { return peer; }
    public void dispose() {
        SipEngine.get().removeListener(this);
        activity.unregisterReceiver(receiver);
        store.close();
    }
    public void refresh() {
        messages = peer.isEmpty() ? new ArrayList<>() : store.messages(peer);
        adapter.notifyDataSetChanged();
        view.messagesEmpty.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
        if (!messages.isEmpty()) view.messageList.scrollToPosition(messages.size() - 1);
    }
    private void send() {
        String target = peer.isEmpty() ? view.sipRecipient.getText().toString().trim() : peer;
        String body = view.message.getText().toString();
        if (target.isEmpty() || body.trim().isEmpty()) {
            Toast.makeText(activity, target.isEmpty() ? "请输入收件人" : "消息内容不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SipUri.destination(target, new SipConfigStore(activity).load());
            if (!SipEngine.get().isRegistered()) throw new IllegalStateException("SIP账户尚未注册");
            SipService.sendMessage(activity, target, body);
            drafts.edit().remove(draftKey).apply();
            peer = target;
            draftKey = "body:" + peer;
            view.toolbarTitle.setText(peer);
            view.sipRecipient.setVisibility(View.GONE);
            view.message.setText("");
            saveDraft();
        } catch (Exception error) {
            Toast.makeText(activity, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    @Override public void onRegistrationChanged(boolean registered, String detail) {
        view.toolbarSubtitle.setText("SIP · " + (registered ? "已连接" : detail));
    }
    @Override public void onMessageReceived(String peer, String body) {}
    @Override public void onMessageStatus(long id, boolean sent, String detail) {}

    private final class MessageAdapter extends RecyclerView.Adapter<Holder> {
        final DateFormat time = DateFormat.getTimeInstance(DateFormat.SHORT);
        @Override public int getItemCount() { return messages.size(); }
        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(activity).inflate(R.layout.d31_sip_message_item, parent, false));
        }
        @Override public void onBindViewHolder(Holder h, int position) {
            SipMessageStore.Message message = messages.get(position);
            boolean out = message.direction == SipMessageStore.DIRECTION_OUT;
            h.row.setGravity(out ? Gravity.END : Gravity.START);
            h.meta.setGravity(out ? Gravity.END : Gravity.START);
            h.bubble.setBackgroundResource(out ? R.drawable.d31_bubble_sip_out : R.drawable.d31_bubble_sip_in);
            h.bubble.setTextColor(out ? Color.WHITE : Color.rgb(23,36,43));
            h.bubble.setText(message.body);
            h.bubble.setTextIsSelectable(true);
            h.time.setText(time.format(new Date(message.timestamp)));
            h.network.setVisibility(View.GONE);
            h.status.setVisibility(out ? View.VISIBLE : View.GONE);
            boolean failed = message.state == SipMessageStore.STATE_FAILED;
            h.status.setText(failed ? "发送失败" : message.state == SipMessageStore.STATE_SENT ? "已发送" : "发送中");
            h.status.setTextColor(failed ? Color.rgb(177,56,44) : Color.rgb(80,105,120));
            h.retry.setVisibility(failed ? View.VISIBLE : View.GONE);
            View.OnClickListener inspect = v -> new AlertDialog.Builder(activity)
                .setTitle("发送失败")
                .setMessage(message.detail.isEmpty() ? "旧记录未保存具体错误，可重试查看结果。" : message.detail)
                .setNegativeButton("取消", null)
                .setPositiveButton("重试", (dialog, which) -> {
                    if (!SipEngine.get().isRegistered()) {
                        Toast.makeText(activity, "SIP账户尚未注册", Toast.LENGTH_SHORT).show(); return;
                    }
                    SipService.sendMessage(activity, message.peer, message.body);
                }).show();
            h.status.setOnClickListener(failed ? inspect : null);
            h.retry.setOnClickListener(failed ? inspect : null);
        }
    }
    private static final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout row, meta;
        final TextView bubble, time, status;
        final ImageView network, retry;
        Holder(View v) {
            super(v);
            row=v.findViewById(R.id.sipMessageRow); meta=v.findViewById(R.id.sipMetaContainer);
            bubble=v.findViewById(R.id.sipMessageBubble); time=v.findViewById(R.id.sipMessageTime);
            status=v.findViewById(R.id.sipMessageStatus); network=v.findViewById(R.id.sipNetworkIcon);
            retry=v.findViewById(R.id.sipRetryIcon);
        }
    }
}
