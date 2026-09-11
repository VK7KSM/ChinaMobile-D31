package net.elfradio.d31bootstrap;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView output;
    private boolean running;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("elfRemote 基础探针\n" + BuildConfig.VERSION_NAME);
        title.setTextSize(22);
        content.addView(title);
        addAction(content, "刷新状态", 0);
        addAction(content, "开启或恢复ADB", 1);
        addAction(content, "初始化维护权限", 2);
        addAction(content, "启动或检查8765", 3);
        addAction(content, "本地急救", 4);
        addAction(content, "启用开机救援", 5);
        output = new TextView(this);
        output.setTextIsSelectable(true);
        content.addView(output);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        runAction(0);
    }

    private void addAction(LinearLayout parent, String label, int action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> runAction(action));
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }

    private void runAction(int action) {
        if (running) return;
        if (BuildConfig.DEBUG && action != 0) { output.setText("预览制品不执行维护操作"); return; }
        running = true;
        new Thread(() -> {
            String result;
            try {
                if (action != 0 && RemoteDeployment.systemManaged()) {
                    result = "系统已部署完整elfRemote；基础探针不接管固定载荷或开机入口。";
                } else if (action == 1) result = AdbControl.restartEnabled(this).log;
                else if (action == 2) result = BasicPermissions.initialize(this);
                else if (action == 3) result = RescueInstaller.ensure(this);
                else if (action == 4) {
                    AdbControl.ActionResult adb = AdbControl.ensureEnabled(this);
                    result = adb.log;
                    if (adb.succeeded) result += "\n" + RescueInstaller.ensure(this);
                } else if (action == 5) result = RescueInstaller.enableBoot(this);
                else result = "ADB：\n" + AdbControl.status(this)
                        + "\n8765同版本健康回读：" + RescueInstaller.healthy();
                if (action != 0) ProbeLog.append(this, result);
            } catch (Exception error) { result = "操作未完成：" + error; }
            final String text = result;
            runOnUiThread(() -> { running = false; output.setText(text); });
        }, "d31-basic-action").start();
    }
}
