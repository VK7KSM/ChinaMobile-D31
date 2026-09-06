package net.elfradio.d31bootstrap;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView summary;
    private TextView output;
    private boolean actionRunning;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(28, 20, 28, 20);

        TextView title = new TextView(this);
        title.setText("D31 无线 ADB 启动器");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(title, new LinearLayout.LayoutParams(-1, -2));

        summary = new TextView(this);
        summary.setTextSize(18);
        summary.setPadding(0, 14, 0, 14);
        summary.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        Button recovery = new Button(this);
        recovery.setText("开始本地急救（开机卡顿与无线ADB）");
        recovery.setAllCaps(false);
        recovery.setOnClickListener(view -> runAction(4));
        content.addView(recovery, new LinearLayout.LayoutParams(-1, -2));

        Button enable = new Button(this);
        enable.setText("重新启动无线 ADB（端口 5555）");
        enable.setAllCaps(false);
        enable.setOnClickListener(view -> runAction(1));
        content.addView(enable, new LinearLayout.LayoutParams(-1, -2));

        Button refresh = new Button(this);
        refresh.setText("刷新状态");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(view -> runAction(0));
        content.addView(refresh, new LinearLayout.LayoutParams(-1, -2));

        Button probe = new Button(this);
        probe.setText("启动/检查命令探针（8765）");
        probe.setAllCaps(false);
        probe.setOnClickListener(view -> runAction(5));
        content.addView(probe, new LinearLayout.LayoutParams(-1, -2));

        Button probeBoot = new Button(this);
        probeBoot.setText("启用开机救援（8765）");
        probeBoot.setAllCaps(false);
        probeBoot.setOnClickListener(view -> runAction(6));
        content.addView(probeBoot, new LinearLayout.LayoutParams(-1, -2));

        Button firewallEnable = new Button(this);
        firewallEnable.setText("启用并刷新本机入站防火墙");
        firewallEnable.setAllCaps(false);
        firewallEnable.setOnClickListener(view -> runAction(2));
        content.addView(firewallEnable, new LinearLayout.LayoutParams(-1, -2));

        Button firewallDisable = new Button(this);
        firewallDisable.setText("关闭本机入站防火墙（回滚）");
        firewallDisable.setAllCaps(false);
        firewallDisable.setOnClickListener(view -> runAction(3));
        content.addView(firewallDisable, new LinearLayout.LayoutParams(-1, -2));

        output = new TextView(this);
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        output.setMovementMethod(new ScrollingMovementMethod());
        output.setPadding(0, 18, 0, 0);
        content.addView(output, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);

        startService(new Intent(this, ProbeService.class).setAction("activity-created"));
        runAction(0);
    }

    private void runAction(final int action) {
        if (actionRunning) return;
        actionRunning = true;
        summary.setText(action == 4 ? "正在执行本地急救，请勿重复点击..."
                : action == 1 ? "正在重新启动并验证..."
                : action == 2 ? "正在建立并验证防火墙..."
                : action == 3 ? "正在清理防火墙..." : "正在检查...");
        output.setText("请稍候...");
        new Thread(() -> {
            StringBuilder text = new StringBuilder();
            text.append("D31 无线 ADB 启动器root通道版\n")
                    .append("系统：Android ").append(Build.VERSION.RELEASE)
                    .append(" / API ").append(Build.VERSION.SDK_INT).append('\n')
                    .append("型号：").append(Build.MODEL).append('\n')
                    .append("硬件：").append(Build.HARDWARE).append('\n')
                    .append("地址：").append(DeviceInfo.localAddresses()).append('\n')
                    .append("状态报告：http://").append(DeviceInfo.firstIpv4()).append(":8765/\n")
                    .append("开发救援：8765 /exec，局域网免密码，支持root命令与结果回读\n");
            AdbControl.ActionResult result = null;
            if (action == 5 || action == 6) {
                text.append(action == 5 ? RescueInstaller.ensure(this) : RescueInstaller.enableBoot(this));
            }
            else if (action == 4) {
                result = LocalRecovery.run(this);
                text.append(result.log);
            }
            else if (action == 1) {
                result = AdbControl.restartEnabled(this);
                text.append(result.log);
            }
            else if (action == 2) {
                result = FirewallControl.enable(this);
                text.append(result.log);
            }
            else if (action == 3) {
                result = FirewallControl.disable(this);
                text.append(result.log);
            }
            else {
                text.append(AdbControl.status(this));
                text.append("\n").append(FirewallControl.status(this));
            }
            String report = text.toString();
            ProbeLog.append(this, "界面操作，动作=" + action + "\n" + report);
            final boolean healthy = action >= 5 ? RescueInstaller.healthy() : AdbControl.isHealthy(this);
            final boolean actionSucceeded = result == null || result.succeeded;
            runOnUiThread(() -> {
                actionRunning = false;
                if (action >= 5) {
                    summary.setText(healthy ? "命令探针正在运行，详见下方操作结果" : "命令探针未通过健康回读");
                } else if (action == 4) {
                    summary.setText(actionSucceeded
                            ? "本地急救完成，ADB已恢复"
                            : "本地急救未完成，请查看下方停止位置");
                } else if (action == 2) {
                    summary.setText(actionSucceeded
                            ? "本机入站防火墙已启用"
                            : "防火墙启用失败，规则保持回滚状态");
                } else if (action == 3) {
                    summary.setText(actionSucceeded
                            ? "本机入站防火墙已关闭"
                            : "防火墙回滚失败，请查看下方错误");
                } else if (action == 1) {
                    summary.setText(actionSucceeded
                            ? "设备本地ADB状态正常：" + DeviceInfo.firstIpv4() + ":5555"
                            : "设备本地ADB状态异常，请查看下方错误");
                } else {
                    summary.setText(healthy
                            ? "设备本地ADB状态正常：" + DeviceInfo.firstIpv4() + ":5555"
                            : "设备本地ADB状态异常，请查看下方错误");
                }
                output.setText(report);
            });
        }, "d31-adb-control").start();
    }
}
