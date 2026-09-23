package net.elfradio.d31bootstrap;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 设备本机的代理客户端界面：首页（连接开关、当前节点、订阅、分流应用、流量）、代理（节点列表、测速、选择）、订阅。
 * 取 Clash Verge 三个页面的核心元素，不照抄样式；所有数据来自核心的 `overview`，界面不自己算状态。
 */
public final class ProxyActivity extends Activity {
    private static final String[] TABS = {"首页", "代理", "订阅"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private final Button[] tabs = new Button[TABS.length];
    private int tab;
    private JSONObject view = new JSONObject();
    private boolean busy;
    private final Runnable refresh = new Runnable() { public void run() { load(); main.postDelayed(this, 5000); } };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(24, 27, 34));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView title = new TextView(this);
        title.setText("代理客户端");
        title.setTextColor(Color.WHITE); title.setTextSize(20); title.setTypeface(null, Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            Button button = new Button(this);
            button.setText(TABS[i]);
            button.setOnClickListener(v -> { tab = index; render(); });
            tabs[i] = button;
            bar.addView(button, new LinearLayout.LayoutParams(dp(110), -2));
        }
        Button back = new Button(this);
        back.setText("返回");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(90), -2));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(4), dp(12), dp(12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
        render();
    }

    @Override protected void onResume() { super.onResume(); main.post(refresh); }
    @Override protected void onPause() { super.onPause(); main.removeCallbacks(refresh); }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }

    /** 取总览；正在执行操作时不打断（核心一次只处理一条请求）。 */
    private void load() {
        if (busy) return;
        new Thread(() -> {
            try {
                JSONObject result = DeviceProxy.call(ProxyLocal.OP_OVERVIEW, null, 30000);
                JSONObject next = result.optJSONObject("view");
                if (next != null) main.post(() -> { view = next; render(); });
            } catch (Exception ignored) { /* 下一轮再取 */ }
        }, "d31-proxy-ui-load").start();
    }

    /** 执行一个操作，完成后用核心回的总览重画；失败把核心给的原因原样显示。 */
    private void run(String op, JSONObject params, String label, long timeoutMs) {
        if (busy) { Toast.makeText(this, "上一个操作还在进行", Toast.LENGTH_SHORT).show(); return; }
        busy = true;
        Toast.makeText(this, label + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String detail; boolean ok; JSONObject next = null;
            try {
                JSONObject result = DeviceProxy.call(op, params, timeoutMs);
                ok = result.optBoolean("ok"); detail = result.optString("detail"); next = result.optJSONObject("view");
            } catch (Exception failure) { ok = false; detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(); }
            final boolean done = ok; final String text = detail; final JSONObject updated = next;
            main.post(() -> {
                busy = false;
                if (updated != null) view = updated;
                render();
                if (done) Toast.makeText(this, label + "：" + text, Toast.LENGTH_LONG).show();
                else new AlertDialog.Builder(this).setTitle(label + "失败").setMessage(text).setPositiveButton("关闭", null).show();
            });
        }, "d31-proxy-ui-op").start();
    }

    private void render() {
        for (int i = 0; i < tabs.length; i++) tabs[i].setEnabled(i != tab);
        content.removeAllViews();
        if (tab == 0) renderHome(); else if (tab == 1) renderProxies(); else renderSubscription();
    }

    private JSONObject status() { JSONObject s = view.optJSONObject("status"); return s == null ? new JSONObject() : s; }

    private void renderHome() {
        JSONObject status = status();
        boolean installed = status.optBoolean("asset_verified"), configured = status.optBoolean("configured"), running = status.optBoolean("running");
        // 连接开关
        LinearLayout card = card("连接");
        String line = !installed ? "核心未安装（按需下载）" : !configured ? "尚无订阅配置" : running
                ? "已连接 · " + (status.optBoolean("proxy_reachable") ? "节点可达" : "节点未验证或不可达") + " · 模式：规则（按应用分流）"
                : "已断开";
        text(card, line, 15, Color.WHITE);
        Button toggle = new Button(this);
        toggle.setText(running ? "断开" : "连接");
        toggle.setEnabled(installed || !running);
        toggle.setOnClickListener(v -> run(running ? ProxyLocal.OP_DISCONNECT : ProxyLocal.OP_CONNECT, null, running ? "断开" : "连接", 240000));
        card.addView(toggle, new LinearLayout.LayoutParams(dp(200), -2));
        text(card, "管理连接：" + ("proxy".equals(status.optString("management_via")) ? "经代理" : "直连（不受代理影响）")
                + "    本机监听：127.0.0.1:" + status.optInt("http_port") + " / " + status.optInt("socks_port"), 12, Color.LTGRAY);
        String error = status.optString("error_category");
        if (!error.isEmpty() && !"none".equals(error) && !"core_missing".equals(error) && !"not_configured".equals(error))
            text(card, "错误类别：" + error, 12, Color.rgb(255, 170, 120));

        // 当前节点
        card = card("当前节点");
        String selected = view.optString("selected", ProxyConfig.AUTO);
        JSONObject node = node(selected);
        text(card, (ProxyConfig.AUTO.equals(selected) ? "自动选择（最快节点）" : selected)
                + (node != null ? "   " + delay(node) : ""), 16, Color.WHITE);
        Button choose = new Button(this);
        choose.setText("选择节点 ›");
        choose.setOnClickListener(v -> { tab = 1; render(); });
        card.addView(choose, new LinearLayout.LayoutParams(dp(200), -2));

        // 订阅
        card = card("订阅");
        JSONArray nodes = view.optJSONArray("nodes");
        text(card, configured ? "版本 " + view.optString("config_version") + " · " + (nodes == null ? 0 : nodes.length()) + " 个节点 · 更新于 "
                + time(view.optLong("configured_at_ms")) : "尚未获取订阅", 14, Color.WHITE);
        Button update = new Button(this);
        update.setText("更新订阅");
        update.setOnClickListener(v -> run(ProxyLocal.OP_UPDATE, null, "更新订阅", 240000));
        card.addView(update, new LinearLayout.LayoutParams(dp(200), -2));

        // 分流应用
        card = card("走代理的应用");
        JSONArray apps = view.optJSONArray("apps");
        StringBuilder names = new StringBuilder();
        for (int i = 0; apps != null && i < apps.length(); i++) names.append(i > 0 ? "、" : "").append(label(apps.optString(i)));
        text(card, names.length() == 0 ? "（无）" : names.toString(), 14, Color.WHITE);
        text(card, "其余应用与本管理程序始终直连。改动立即生效，管理面板下发的名单会覆盖本机选择。", 12, Color.LTGRAY);
        Button edit = new Button(this);
        edit.setText("选择应用");
        edit.setOnClickListener(v -> pickApps(apps));
        card.addView(edit, new LinearLayout.LayoutParams(dp(200), -2));

        // 流量与核心
        card = card("流量与核心");
        JSONObject traffic = view.optJSONObject("traffic");
        text(card, "上传 " + DeviceProxy.bytes(traffic == null ? 0 : traffic.optLong("upload_bytes"))
                + "    下载 " + DeviceProxy.bytes(traffic == null ? 0 : traffic.optLong("download_bytes"))
                + "    活跃连接 " + (traffic == null ? 0 : traffic.optInt("connections")), 14, Color.WHITE);
        text(card, "核心 Mihomo " + (status.isNull("version") ? "未安装" : status.optString("version"))
                + "    运行时间 " + DeviceProxy.uptime(running ? view.optLong("started_at_ms") : 0, System.currentTimeMillis()), 12, Color.LTGRAY);
    }

    private void renderProxies() {
        LinearLayout card = card("节点（点选切换）");
        Button test = new Button(this);
        test.setText("全部测速");
        test.setEnabled(status().optBoolean("configured"));
        test.setOnClickListener(v -> run(ProxyLocal.OP_TEST_NODES, null, "测速", 120000));
        card.addView(test, new LinearLayout.LayoutParams(dp(200), -2));
        String selected = view.optString("selected", ProxyConfig.AUTO);
        row(card, ProxyConfig.AUTO, "自动选择（最快节点）", "", ProxyConfig.AUTO.equals(selected));
        JSONArray nodes = view.optJSONArray("nodes");
        if (nodes == null || nodes.length() == 0) text(card, "尚无节点，请先更新订阅", 14, Color.LTGRAY);
        for (int i = 0; nodes != null && i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) continue;
            row(card, node.optString("name"), node.optString("name"), delay(node), node.optBoolean("selected"));
        }
    }

    private void row(LinearLayout card, String name, String label, String delay, boolean selected) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(8), dp(6), dp(8), dp(6));
        line.setBackgroundColor(selected ? Color.rgb(44, 82, 140) : Color.rgb(40, 44, 54));
        TextView text = new TextView(this);
        text.setText((selected ? "✓ " : "   ") + label);
        text.setTextColor(Color.WHITE); text.setTextSize(15);
        line.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView ms = new TextView(this);
        ms.setText(delay);
        ms.setTextColor(delay.endsWith("ms") ? Color.rgb(120, 220, 120) : Color.LTGRAY); ms.setTextSize(14);
        line.addView(ms, new LinearLayout.LayoutParams(-2, -2));
        line.setOnClickListener(v -> {
            try { run(ProxyLocal.OP_SELECT, new JSONObject().put("name", name), "切换节点", 30000); }
            catch (Exception ignored) { }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(4);
        card.addView(line, params);
    }

    private void renderSubscription() {
        JSONObject status = status();
        LinearLayout card = card("订阅");
        if (status.optBoolean("configured")) {
            text(card, "版本：" + view.optString("config_version"), 14, Color.WHITE);
            text(card, "校验值：" + view.optString("config_sha256").substring(0, Math.min(16, view.optString("config_sha256").length())) + "…", 12, Color.LTGRAY);
            text(card, "更新于：" + time(view.optLong("configured_at_ms")), 12, Color.LTGRAY);
            JSONArray nodes = view.optJSONArray("nodes");
            text(card, "节点数：" + (nodes == null ? 0 : nodes.length()), 12, Color.LTGRAY);
        } else text(card, "尚未获取订阅。订阅由管理面板为本机准备，按「更新订阅」即可取回。", 14, Color.WHITE);
        Button update = new Button(this);
        update.setText("更新订阅");
        update.setOnClickListener(v -> run(ProxyLocal.OP_UPDATE, null, "更新订阅", 240000));
        card.addView(update, new LinearLayout.LayoutParams(dp(200), -2));

        card = card("代理模块");
        text(card, "核心 Mihomo " + (status.isNull("version") ? "未安装（连接或更新订阅时自动下载）" : status.optString("version")), 14, Color.WHITE);
        Button remove = new Button(this);
        remove.setText("移除代理模块");
        remove.setEnabled(status.optBoolean("asset_verified"));
        remove.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("移除代理模块")
                .setMessage("将停止代理并删除核心与订阅，之后可随时重新下载。")
                .setPositiveButton("移除", (d, w) -> run(ProxyLocal.OP_REMOVE, null, "移除", 60000)).setNegativeButton("取消", null).show());
        card.addView(remove, new LinearLayout.LayoutParams(dp(200), -2));
    }

    private LinearLayout card(String heading) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.rgb(32, 36, 45));
        card.setPadding(dp(12), dp(8), dp(12), dp(10));
        TextView title = new TextView(this);
        title.setText(heading);
        title.setTextColor(Color.rgb(150, 190, 255)); title.setTextSize(13); title.setTypeface(null, Typeface.BOLD);
        card.addView(title);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        content.addView(card, params);
        return card;
    }

    private void text(LinearLayout card, String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setPadding(0, dp(3), 0, dp(3));
        card.addView(text);
    }

    private JSONObject node(String name) {
        JSONArray nodes = view.optJSONArray("nodes");
        for (int i = 0; nodes != null && i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null && name.equals(node.optString("name"))) return node;
        }
        return null;
    }

    private static String delay(JSONObject node) {
        if (node.isNull("delay_ms")) return node.optLong("tested_at_ms") > 0 ? "超时" : "未测";
        return node.optInt("delay_ms") + " ms";
    }

    private static String time(long ms) {
        return ms <= 0 ? "—" : new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    /** 本机可启动的应用 + 名单里已有的（哪怕现在不可启动），勾选后经核心保存并立即重下路由。管理程序自己永不出现。 */
    private void pickApps(JSONArray current) {
        java.util.TreeMap<String, String> candidates = new java.util.TreeMap<>();
        android.content.Intent launcher = new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo info : getPackageManager().queryIntentActivities(launcher, 0)) {
            String pkg = info.activityInfo.packageName;
            if (ProxyRuntime.management(pkg)) continue;
            candidates.put(String.valueOf(info.loadLabel(getPackageManager())) + "\n" + pkg, pkg);
        }
        java.util.Set<String> chosen = new java.util.HashSet<>();
        for (int i = 0; current != null && i < current.length(); i++) {
            String pkg = current.optString(i);
            chosen.add(pkg);
            if (!candidates.containsValue(pkg)) candidates.put(label(pkg) + "\n" + pkg, pkg);
        }
        final String[] labels = candidates.keySet().toArray(new String[0]);
        final String[] packages = candidates.values().toArray(new String[0]);
        final boolean[] checked = new boolean[packages.length];
        for (int i = 0; i < packages.length; i++) checked[i] = chosen.contains(packages[i]);
        new AlertDialog.Builder(this).setTitle("走代理的应用")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("保存", (d, w) -> {
                    JSONArray selected = new JSONArray();
                    for (int i = 0; i < packages.length; i++) if (checked[i]) selected.put(packages[i]);
                    try { run(ProxyLocal.OP_SET_APPS, new JSONObject().put("apps", selected), "保存分流名单", 60000); }
                    catch (Exception ignored) { }
                })
                .setNegativeButton("取消", null).show();
    }

    private String label(String pkg) {
        try { return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (PackageManager.NameNotFoundException missing) { return pkg + "（未安装）"; }
    }
}
