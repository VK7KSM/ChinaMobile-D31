using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace D31FlashTool
{
    internal sealed class RescueForm : Form
    {
        private readonly TextBox host;
        private readonly TextBox mac;
        private readonly TextBox log;
        private readonly ComboBox adapters;
        private readonly FlowLayoutPanel probeActions;
        private readonly FlowLayoutPanel vendorActions;
        private readonly string toolRoot;
        private readonly Label status;
        private bool busy;
        internal string ConnectedEndpoint { get; private set; }
        internal bool RecoveryAttempted { get; private set; }

        internal RescueForm(string root, string address)
        {
            toolRoot = root;
            Text = "D31设备急救";
            StartPosition = FormStartPosition.CenterParent;
            ClientSize = new Size(880, 610);
            MinimumSize = new Size(820, 640);
            Font = new Font("Microsoft YaHei UI", 9F);
            BackColor = Color.FromArgb(246, 248, 250);
            var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(20), ColumnCount = 1, RowCount = 9 };
            foreach (int height in new[] { 42, 42, 30, 46, 42, 42, 46, 32 }) layout.RowStyles.Add(new RowStyle(SizeType.Absolute, height));
            layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
            Controls.Add(layout);
            layout.Controls.Add(new Label { Text = "设备急救", Font = new Font(Font.FontFamily, 16F, FontStyle.Bold), AutoSize = true });
            var addressRow = new FlowLayoutPanel { Dock = DockStyle.Fill, WrapContents = false };
            addressRow.Controls.Add(new Label { Text = "D31有线IPv4", AutoSize = true, Margin = new Padding(0, 8, 16, 0) });
            host = new TextBox { Text = address, Width = 150, Margin = new Padding(0, 4, 12, 0) };
            addressRow.Controls.Add(host);
            var card = MakeButton("卡刷恢复 · 暂未开放", "\uE7F1", delegate { });
            card.Enabled = false;
            addressRow.Controls.Add(card);
            layout.Controls.Add(addressRow);
            layout.Controls.Add(new Label { Text = "8765命令探针", AutoSize = true, Font = new Font(Font, FontStyle.Bold), Padding = new Padding(0, 5, 0, 0) });
            probeActions = new FlowLayoutPanel { Dock = DockStyle.Fill, WrapContents = false };
            probeActions.Controls.Add(MakeButton("检查探针", "\uE721", async delegate { await Run(async delegate { string addressText = DeviceDetector.NormalizeAddress(host.Text); return await Task.Run(() => RescueClient.Health(addressText)); }); }));
            probeActions.Controls.Add(MakeButton("恢复ADB", "\uE777", async delegate { await Restore(false); }));
            probeActions.Controls.Add(MakeButton("导出诊断", "\uE896", async delegate { await Export(); }));
            layout.Controls.Add(probeActions);
            layout.Controls.Add(new Label { Text = "原厂uptool · 有线备用通道", AutoSize = true, Font = new Font(Font, FontStyle.Bold), Padding = new Padding(0, 12, 0, 0) });
            var vendorRow = new FlowLayoutPanel { Dock = DockStyle.Fill, WrapContents = false };
            adapters = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 390 };
            adapters.Items.AddRange(UptoolClient.Adapters());
            if (adapters.Items.Count == 1) adapters.SelectedIndex = 0;
            vendorRow.Controls.Add(adapters);
            vendorRow.Controls.Add(new Label { Text = "D31有线MAC", AutoSize = true, Margin = new Padding(12, 5, 8, 0) });
            mac = new TextBox { Width = 170 };
            vendorRow.Controls.Add(mac);
            layout.Controls.Add(vendorRow);
            vendorActions = new FlowLayoutPanel { Dock = DockStyle.Fill, WrapContents = false };
            vendorActions.Controls.Add(MakeButton("查询设备", "\uE721", async delegate { await Run(async delegate { var a = (RescueAdapter)adapters.SelectedItem; string m = mac.Text; return await Task.Run(() => UptoolClient.Run(a, m, false)); }); }));
            vendorActions.Controls.Add(MakeButton("恢复ADB", "\uE777", async delegate { await Restore(true); }));
            vendorActions.Controls.Add(MakeButton("刷新网卡", "\uE72C", delegate { adapters.Items.Clear(); adapters.Items.AddRange(UptoolClient.Adapters()); if (adapters.Items.Count == 1) adapters.SelectedIndex = 0; }));
            vendorActions.Controls.Add(MakeButton("Npcap官网", "\uE774", delegate { Process.Start(new ProcessStartInfo("https://npcap.com/#download") { UseShellExecute = true }); }));
            layout.Controls.Add(vendorActions);
            status = new Label { Text = "未检查", Dock = DockStyle.Fill, ForeColor = Color.FromArgb(71, 84, 103) };
            layout.Controls.Add(status);
            log = new TextBox { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Both, WordWrap = false, Dock = DockStyle.Fill,
                BackColor = Color.FromArgb(17, 24, 39), ForeColor = Color.White, Font = new Font("Consolas", 10F) };
            layout.Controls.Add(log);
            FormClosing += delegate(object sender, FormClosingEventArgs e) { if (busy) e.Cancel = true; };
        }

        private Button MakeButton(string text, string glyph, EventHandler click)
        {
            var button = MainForm.CreateButton(text, glyph, Color.FromArgb(23, 92, 211), 0, 0, text.Length > 10 ? 210 : 150);
            button.Margin = new Padding(0, 0, 10, 0);
            button.Click += click;
            return button;
        }
        private async Task Run(Func<Task<string>> action)
        {
            if (busy) return;
            busy = true;
            probeActions.Enabled = vendorActions.Enabled = host.Enabled = mac.Enabled = adapters.Enabled = false;
            status.Text = "正在处理…";
            try { string result = await action(); log.AppendText(DateTime.Now.ToString("HH:mm:ss") + " " + result + "\r\n"); status.Text = "操作完成，请查看结果"; }
            catch (Exception ex) { status.Text = "操作未完成"; log.AppendText(DateTime.Now.ToString("HH:mm:ss") + " " + ex.Message + "\r\n"); }
            finally { busy = false; probeActions.Enabled = vendorActions.Enabled = host.Enabled = mac.Enabled = adapters.Enabled = true; }
        }
        private async Task Restore(bool vendor)
        {
            if (MessageBox.Show(this, "恢复所选D31的ADB，保留有效运行端口，其次使用持久端口，仅两者无效时默认5555。不改持久属性或USB配置。继续？", "恢复ADB", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
            await Run(async delegate
            {
                RecoveryAttempted = true;
                ConnectedEndpoint = null;
                string address = RescueClient.ValidateHost(DeviceDetector.NormalizeAddress(host.Text));
                string endpoint = host.Text.Trim();
                var adapter = (RescueAdapter)adapters.SelectedItem;
                string target = mac.Text;
                string result = await Task.Run(() => vendor ? UptoolClient.Run(adapter, target, true) : RescueClient.Execute(address, RescueClient.RestoreAdb));
                log.AppendText(result + "\r\n");
                if (!vendor)
                {
                    int port = RescueClient.PortFromReceipt(result);
                    endpoint = address + ":" + port;
                    log.AppendText("恢复后端口快照：" + port + "；尚未验证ADB握手。\r\n");
                }
                if (vendor) await Task.Delay(3500);
                await Task.Run(() => DeviceDetector.PrepareDedicatedServer(toolRoot));
                string serial = await Task.Run(() => DeviceDetector.Connect(toolRoot, endpoint));
                var device = await Task.Run(() => DeviceDetector.Inspect(toolRoot, serial, address));
                ConnectedEndpoint = serial;
                host.Text = serial;
                return "ADB握手和D31识别通过：" + serial + "，" + device.Model + "。可继续只读检查。";
            });
        }
        private async Task Export()
        {
            using (var dialog = new SaveFileDialog { Filter = "诊断文本 (*.txt)|*.txt", FileName = "D31诊断-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".txt" })
            {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                await Run(async delegate
                {
                    string address = DeviceDetector.NormalizeAddress(host.Text);
                    string result = await Task.Run(() => RescueClient.Execute(address, "id; uptime; getprop sys.boot_completed; getprop init.svc.adbd; getprop service.adb.tcp.port; getprop persist.adb.tcp.port; df /data /system; ps | grep -E 'd31-rescue|nexui|adbd'"));
                    File.WriteAllText(dialog.FileName, result, System.Text.Encoding.UTF8);
                    return "诊断已保存：" + dialog.FileName;
                });
            }
        }
    }
}
