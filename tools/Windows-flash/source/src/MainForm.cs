using System;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace D31FlashTool
{
    internal sealed class MainForm : Form
    {
        private readonly string toolRoot;
        private readonly string userRoot;
        private readonly string backupRoot;
        private readonly Label statusLabel;
        private readonly Panel statusMark;
        private readonly Label packagePathValue;
        private readonly TextBox ipInput;
        private readonly Label deviceValue;
        private readonly Label buildValue;
        private readonly Label networkValue;
        private readonly Button selectPackageButton;
        private readonly Button githubDownloadButton;
        private readonly Button cloudflareDownloadButton;
        private readonly Button connectButton;
        private readonly Button disconnectButton;
        private readonly Button rescueButton;
        private readonly Button detectButton;
        private readonly Button preflightButton;
        private readonly Button flashButton;
        private readonly Button openBackupsButton;
        private readonly CheckBox backupCheck;
        private readonly CheckBox eraseCheck;
        private readonly ProgressBar progress;
        private readonly TextBox log;
        private FirmwareSelection firmware;
        private DeviceInfo device;
        private Process runningProcess;
        private bool packageBusy;
        private bool adbPrepared;
        private bool toolReady;
        private bool preflightPassed;
        private bool flashAfterBackup;
        private bool recoveryTriggered;
        private string currentOperation;
        private string rescueDirectory;
        private string connectedSerial;
        private int lastPackageLogPercent = -10;

        internal MainForm(string runtimeRoot, string executableRoot)
        {
            toolRoot = runtimeRoot;
            userRoot = executableRoot;
            backupRoot = Path.Combine(userRoot, "D31备份");
            Text = "星网锐捷 SVP3390（中国移动云视讯 D31）刷机与备份工具 v" + BuildConstants.ToolVersion;
            Icon = LoadBrandIcon();
            StartPosition = FormStartPosition.CenterScreen;
            MinimumSize = new Size(900, 720);
            ClientSize = new Size(1000, 840);
            BackColor = Color.FromArgb(246, 248, 250);
            Font = new Font("Microsoft YaHei UI", 9F, FontStyle.Regular, GraphicsUnit.Point);

            Panel header = new Panel { Dock = DockStyle.Top, Height = 78, BackColor = Color.White };
            Controls.Add(header);
            PictureBox logo = new PictureBox
            {
                Image = LoadBrandImage(),
                SizeMode = PictureBoxSizeMode.Zoom
            };
            logo.SetBounds(18, 13, 50, 50);
            header.Controls.Add(logo);
            LinkLabel title = new LinkLabel
            {
                Text = "星网锐捷 SVP3390（中国移动云视讯 D31）刷机与备份工具 v" + BuildConstants.ToolVersion,
                Font = new Font("Microsoft YaHei UI", 15F, FontStyle.Bold),
                ForeColor = Color.Black,
                LinkColor = Color.FromArgb(23, 92, 211),
                ActiveLinkColor = Color.FromArgb(180, 35, 24),
                VisitedLinkColor = Color.FromArgb(23, 92, 211),
                LinkBehavior = LinkBehavior.HoverUnderline,
                AutoSize = true,
                Location = new Point(78, 14)
            };
            int versionLinkStart = title.Text.LastIndexOf("v" + BuildConstants.ToolVersion, StringComparison.Ordinal);
            title.Links.Add(versionLinkStart, BuildConstants.ToolVersion.Length + 1, "https://github.com/VK7KSM/ChinaMobile-D31");
            title.LinkClicked += OpenLink;
            header.Controls.Add(title);
            LinkLabel subtitle = new LinkLabel
            {
                Text = "作者：VK7KSM  电台精灵官网：https://www.elfradio.net",
                ForeColor = Color.FromArgb(71, 84, 103),
                LinkColor = Color.FromArgb(23, 92, 211),
                ActiveLinkColor = Color.FromArgb(180, 35, 24),
                VisitedLinkColor = Color.FromArgb(23, 92, 211),
                LinkBehavior = LinkBehavior.HoverUnderline,
                AutoSize = true,
                Location = new Point(80, 48)
            };
            int githubLinkStart = subtitle.Text.IndexOf("VK7KSM", StringComparison.Ordinal);
            int websiteLinkStart = subtitle.Text.IndexOf("https://www.elfradio.net", StringComparison.Ordinal);
            subtitle.Links.Add(githubLinkStart, "VK7KSM".Length, "https://github.com/VK7KSM");
            subtitle.Links.Add(websiteLinkStart, "https://www.elfradio.net".Length, "https://www.elfradio.net");
            subtitle.LinkClicked += OpenLink;
            header.Controls.Add(subtitle);

            statusMark = new Panel();
            statusMark.SetBounds(24, 98, 8, 22);
            Controls.Add(statusMark);
            statusLabel = new Label
            {
                Font = new Font("Microsoft YaHei UI", 10F, FontStyle.Bold),
                Text = "第一步：请选择D31签名刷机包，或选择GitHub/Cloudflare高速下载。",
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
            };
            statusLabel.SetBounds(42, 97, 930, 26);
            Controls.Add(statusLabel);

            selectPackageButton = CreateButton("选择刷机包", "\uE8E5", Color.FromArgb(23, 92, 211), 20, 132, 150);
            selectPackageButton.Click += async delegate { await SelectPackageAsync(); };
            Controls.Add(selectPackageButton);
            githubDownloadButton = CreateButton("GitHub高速下载", "\uE896", Color.FromArgb(23, 92, 211), 182, 132, 178);
            githubDownloadButton.Click += async delegate { await DownloadPackageAsync(FirmwareDownloadSource.GitHub); };
            Controls.Add(githubDownloadButton);
            cloudflareDownloadButton = CreateButton("Cloudflare高速下载", "\uE896", Color.FromArgb(210, 87, 24), 372, 132, 202);
            cloudflareDownloadButton.Click += async delegate { await DownloadPackageAsync(FirmwareDownloadSource.Cloudflare); };
            Controls.Add(cloudflareDownloadButton);
            Button bootstrapButton = CreateButton("首次引导/急救APK", "\uE8B7", Color.FromArgb(71, 84, 103), 586, 132, 210);
            bootstrapButton.Click += delegate { OpenBootstrapDirectory(); };
            Controls.Add(bootstrapButton);

            Label packagePathName = new Label
            {
                Text = "刷机包位置",
                TextAlign = ContentAlignment.MiddleLeft
            };
            packagePathName.SetBounds(20, 178, 84, 24);
            Controls.Add(packagePathName);
            packagePathValue = new Label
            {
                Text = "如未下载，请点击上方下载按钮",
                ForeColor = Color.FromArgb(71, 84, 103),
                AutoEllipsis = true,
                TextAlign = ContentAlignment.MiddleLeft,
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
            };
            packagePathValue.SetBounds(112, 178, 868, 24);
            Controls.Add(packagePathValue);

            Panel addressPanel = CreateInfoPanel(20, 212, 960, 76);
            Controls.Add(addressPanel);
            Label addressLabel = new Label { Text = "D31有线IPv4" };
            addressLabel.SetBounds(14, 14, 90, 22);
            addressPanel.Controls.Add(addressLabel);
            ipInput = new TextBox { Text = "192.168.2.62" };
            ipInput.SetBounds(108, 9, 110, 28);
            addressPanel.Controls.Add(ipInput);
            connectButton = CreateButton("连接ADB", "\uE71B", Color.FromArgb(2, 122, 72), 234, 7, 120);
            connectButton.Click += async delegate { await ConnectAdbAsync(); };
            addressPanel.Controls.Add(connectButton);
            disconnectButton = CreateButton("断开ADB", "\uE711", Color.FromArgb(180, 35, 24), 366, 7, 120);
            disconnectButton.Click += async delegate { await DisconnectAdbAsync(); };
            addressPanel.Controls.Add(disconnectButton);
            rescueButton = CreateButton("设备急救", "\uE90F", Color.FromArgb(23, 92, 211), 498, 7, 132);
            rescueButton.Click += delegate
            {
                using (var dialog = new RescueForm(toolRoot, ipInput.Text.Trim()))
                {
                    dialog.Icon = Icon;
                    dialog.ShowDialog(this);
                    if (dialog.RecoveryAttempted)
                    {
                        ResetDevice();
                        connectedSerial = null;
                        SetStatus("急救后请重新连接并核验D31，旧刷机预检已失效。", Color.FromArgb(71, 84, 103));
                    }
                    if (dialog.ConnectedEndpoint != null) ipInput.Text = dialog.ConnectedEndpoint;
                    UpdateControls();
                }
            };
            addressPanel.Controls.Add(rescueButton);
            Label portLabel = new Label
            {
                Text = "请插入网线后刷机，只连接Wi-Fi网络会被拒绝。",
                ForeColor = Color.FromArgb(71, 84, 103)
            };
            portLabel.SetBounds(14, 47, 900, 22);
            addressPanel.Controls.Add(portLabel);

            Panel devicePanel = CreateInfoPanel(20, 300, 960, 76);
            Controls.Add(devicePanel);
            AddInfoRow(devicePanel, "设备", 14, out deviceValue);
            AddInfoRow(devicePanel, "构建", 38, out buildValue);
            AddInfoRow(devicePanel, "网络", 62, out networkValue);

            detectButton = CreateButton("检测D31", "\uE721", Color.FromArgb(23, 92, 211), 20, 388, 122);
            detectButton.Click += async delegate { await DetectAsync(); };
            Controls.Add(detectButton);
            preflightButton = CreateButton("只读检查", "\uE73E", Color.FromArgb(0, 112, 122), 152, 388, 126);
            preflightButton.Click += delegate { StartPreflight(); };
            Controls.Add(preflightButton);
            flashButton = CreateButton("开始刷机", "\uE768", Color.FromArgb(2, 122, 72), 288, 388, 132);
            flashButton.BackColor = Color.FromArgb(228, 245, 233);
            flashButton.ForeColor = Color.FromArgb(0, 92, 54);
            flashButton.FlatAppearance.BorderColor = Color.FromArgb(2, 122, 72);
            flashButton.Click += delegate { StartFlash(); };
            Controls.Add(flashButton);
            openBackupsButton = CreateButton("打开备份目录", "\uE8B7", Color.FromArgb(158, 92, 0), 430, 388, 156);
            openBackupsButton.Click += delegate { OpenBackups(); };
            Controls.Add(openBackupsButton);

            backupCheck = new CheckBox
            {
                Text = "刷机前自动备份原系统到电脑硬盘（推荐，默认勾选，可取消）",
                Checked = true,
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
            };
            backupCheck.SetBounds(22, 432, 930, 28);
            backupCheck.CheckedChanged += delegate { UpdateControls(); };
            Controls.Add(backupCheck);

            eraseCheck = new CheckBox
            {
                Text = "我已确认：刷机会清空账号、应用数据、通信录、邮件、Wi-Fi和SIP配置",
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
            };
            eraseCheck.SetBounds(22, 460, 930, 28);
            eraseCheck.CheckedChanged += delegate { UpdateControls(); };
            Controls.Add(eraseCheck);

            progress = new ProgressBar
            {
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right,
                Minimum = 0,
                Maximum = 100,
                Style = ProgressBarStyle.Continuous
            };
            progress.SetBounds(22, 496, 956, 18);
            Controls.Add(progress);

            Shown += async delegate { await PrepareAdbAsync(); };

            Label logTitle = new Label
            {
                Text = "运行日志",
                Font = new Font("Microsoft YaHei UI", 10F, FontStyle.Bold),
                AutoSize = true,
                Location = new Point(20, 527)
            };
            Controls.Add(logTitle);
            log = new TextBox
            {
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Vertical,
                BackColor = Color.FromArgb(17, 24, 39),
                ForeColor = Color.FromArgb(229, 231, 235),
                Font = new Font("Consolas", 9F),
                Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right
            };
            log.SetBounds(20, 554, 960, 240);
            Controls.Add(log);
            Label footer = new Label
            {
                Text = "Recovery触发后请勿断电、关闭窗口或让电脑休眠。",
                ForeColor = Color.FromArgb(180, 35, 24),
                AutoSize = true,
                Location = new Point(22, 812),
                Anchor = AnchorStyles.Bottom | AnchorStyles.Left
            };
            Controls.Add(footer);

            FormClosing += OnFormClosing;
            ResetDevice();
            try
            {
                using (StringWriter writer = new StringWriter(CultureInfo.InvariantCulture))
                {
                    PackageValidator.ValidateToolRoot(toolRoot, writer);
                }
                toolReady = true;
                SetStatus("可先填写IP并连接ADB检查D31，也可先选择或下载刷机包。", Color.FromArgb(102, 112, 133));
            }
            catch (Exception exception)
            {
                toolReady = false;
                SetStatus(exception.Message, Color.FromArgb(180, 35, 24));
                AppendLog("工具包不完整：" + exception.Message);
            }
            UpdateControls();
        }

        private static Image LoadBrandImage()
        {
            using (Stream stream = typeof(MainForm).Assembly.GetManifestResourceStream("D31FlashTool.Logo.png"))
            {
                if (stream == null) { throw new InvalidOperationException("工具内置Logo缺失"); }
                using (Image image = Image.FromStream(stream))
                {
                    return new Bitmap(image);
                }
            }
        }

        private static Icon LoadBrandIcon()
        {
            using (Stream stream = typeof(MainForm).Assembly.GetManifestResourceStream("D31FlashTool.AppIcon.ico"))
            {
                if (stream == null) { throw new InvalidOperationException("工具内置图标缺失"); }
                using (Icon icon = new Icon(stream))
                {
                    return (Icon)icon.Clone();
                }
            }
        }

        private void OpenLink(object sender, LinkLabelLinkClickedEventArgs eventArgs)
        {
            try
            {
                Process.Start((string)eventArgs.Link.LinkData);
            }
            catch (Exception exception)
            {
                MessageBox.Show(exception.Message, "无法打开链接", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private Panel CreateInfoPanel(int x, int y, int width, int height)
        {
            Panel panel = new Panel
            {
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right,
                BackColor = Color.White,
                BorderStyle = BorderStyle.FixedSingle
            };
            panel.SetBounds(x, y, width, height);
            return panel;
        }

        private void AddInfoRow(Panel parent, string name, int y, out Label value)
        {
            Label key = new Label { Text = name, ForeColor = Color.FromArgb(71, 84, 103) };
            key.SetBounds(14, y - 10, 58, 21);
            parent.Controls.Add(key);
            value = new Label
            {
                Text = "-",
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right,
                AutoEllipsis = true
            };
            value.SetBounds(78, y - 10, parent.Width - 94, 21);
            parent.Controls.Add(value);
        }

        private static Image CreateGlyphIcon(string glyph, Color color)
        {
            Bitmap image = new Bitmap(18, 18);
            using (Graphics graphics = Graphics.FromImage(image))
            using (Font glyphFont = new Font("Segoe MDL2 Assets", 11F, FontStyle.Regular, GraphicsUnit.Point))
            {
                graphics.Clear(Color.Transparent);
                TextRenderer.DrawText(
                    graphics,
                    glyph,
                    glyphFont,
                    new Rectangle(0, 0, image.Width, image.Height),
                    color,
                    TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter |
                    TextFormatFlags.NoPadding | TextFormatFlags.NoClipping);
            }
            return image;
        }

        internal static Button CreateButton(string text, string glyph, Color iconColor, int x, int y, int width)
        {
            Button button = new Button
            {
                Text = text,
                Image = CreateGlyphIcon(glyph, iconColor),
                Font = new Font("Microsoft YaHei UI", 11F, FontStyle.Bold, GraphicsUnit.Point),
                TextImageRelation = TextImageRelation.ImageBeforeText,
                ImageAlign = ContentAlignment.MiddleCenter,
                TextAlign = ContentAlignment.MiddleCenter,
                Padding = new Padding(0),
                FlatStyle = FlatStyle.Flat,
                BackColor = Color.White
            };
            button.FlatAppearance.BorderColor = Color.FromArgb(208, 213, 221);
            button.SetBounds(x, y, width, 36);
            return button;
        }

        private async Task SelectPackageAsync()
        {
            if (IsBusy() || !toolReady) { return; }
            using (OpenFileDialog dialog = new OpenFileDialog())
            {
                dialog.Title = "选择D31 SVP3390签名刷机包";
                dialog.Filter = "D31签名刷机包 (*.zip)|*.zip";
                dialog.Multiselect = false;
                dialog.CheckFileExists = true;
                if (dialog.ShowDialog(this) != DialogResult.OK) { return; }
                await LoadFirmwareAsync(delegate
                {
                    return FirmwareManager.SelectPackageAsync(dialog.FileName, ReportPackageProgress);
                });
            }
        }

        private async Task DownloadPackageAsync(FirmwareDownloadSource source)
        {
            if (IsBusy() || !toolReady) { return; }
            await LoadFirmwareAsync(delegate
            {
                return FirmwareManager.DownloadAsync(toolRoot, userRoot, source, ReportPackageProgress);
            });
        }

        private async Task LoadFirmwareAsync(Func<Task<FirmwareSelection>> loader)
        {
            packageBusy = true;
            firmware = null;
            eraseCheck.Checked = false;
            progress.Value = 0;
            lastPackageLogPercent = -10;
            UpdateControls();
            try
            {
                firmware = await loader();
                packagePathValue.Text = Path.GetDirectoryName(firmware.PackagePath) ?? firmware.PackagePath;
                AppendLog("刷机包已由用户明确选择或下载，并通过长度与SHA-256校验：" + firmware.PackagePath);
                if (preflightPassed && device != null && device.TargetAddressIsEthernet)
                {
                    SetStatus("刷机包和设备只读检查均已通过。确认清空数据后即可开始刷机。", Color.FromArgb(2, 122, 72));
                }
                else if (connectedSerial != null)
                {
                    SetStatus("刷机包已就绪。可继续检测D31并执行只读检查。", Color.FromArgb(2, 122, 72));
                }
                else
                {
                    SetStatus("刷机包已就绪。填写D31的IP并点击“连接ADB”。", Color.FromArgb(2, 122, 72));
                }
                progress.Value = 100;
            }
            catch (Exception exception)
            {
                packagePathValue.Text = "刷机包加载失败，请重新选择或点击上方下载按钮";
                SetStatus(exception.Message, Color.FromArgb(180, 35, 24));
                AppendLog("刷机包加载失败：" + exception.Message.Replace("\r", " ").Replace("\n", " "));
            }
            finally
            {
                packageBusy = false;
                UpdateControls();
            }
        }

        private async Task ConnectAdbAsync()
        {
            if (IsBusy() || !toolReady || connectedSerial != null) { return; }
            ResetDevice();
            packageBusy = true;
            UpdateControls();
            string address = ipInput.Text.Trim();
            SetStatus("正在连接指定IP的D31 ADB。", Color.FromArgb(23, 92, 211));
            AppendLog("开始识别ADB端口并连接目标" + address + "；电脑端使用专用ADB服务器端口5042。可填写IPv4:端口。");
            try
            {
                if (!adbPrepared)
                {
                    string preparation = await Task.Run(delegate
                    {
                        return DeviceDetector.PrepareDedicatedServer(toolRoot);
                    });
                    AppendLog(preparation);
                    adbPrepared = true;
                }
                connectedSerial = await Task.Run(delegate { return DeviceDetector.Connect(toolRoot, address); });
                AppendLog("ADB已连接：" + connectedSerial);
                device = await Task.Run(delegate { return DeviceDetector.Inspect(toolRoot, connectedSerial, address); });
                ShowDevice(address);
                SetStatus("ADB已连接并识别D31。无需下载刷机包即可执行只读检查。", Color.FromArgb(2, 122, 72));
            }
            catch (Exception exception)
            {
                ResetDevice();
                SetStatus(exception.Message, Color.FromArgb(180, 35, 24));
                AppendLog("ADB连接或设备识别失败：" + exception.Message.Replace("\r", " ").Replace("\n", " "));
            }
            finally
            {
                packageBusy = false;
                UpdateControls();
            }
        }

        private async Task DisconnectAdbAsync()
        {
            if (IsBusy() || connectedSerial == null) { return; }
            string serial = connectedSerial;
            packageBusy = true;
            UpdateControls();
            SetStatus("正在断开" + serial + "。", Color.FromArgb(23, 92, 211));
            try
            {
                string result = await Task.Run(delegate { return DeviceDetector.Disconnect(toolRoot, serial); });
                AppendLog("ADB断开结果：" + result.Replace("\r", " ").Replace("\n", " "));
            }
            catch (Exception exception)
            {
                AppendLog("ADB断开返回异常：" + exception.Message.Replace("\r", " ").Replace("\n", " "));
            }
            finally
            {
                connectedSerial = null;
                ResetDevice();
                packageBusy = false;
                SetStatus("ADB已断开。可修改IP并连接另一台D31。", Color.FromArgb(102, 112, 133));
                UpdateControls();
            }
        }

        private void ReportPackageProgress(int percent, string text)
        {
            if (InvokeRequired)
            {
                BeginInvoke((MethodInvoker)delegate { ReportPackageProgress(percent, text); });
                return;
            }
            progress.Value = Math.Max(0, Math.Min(100, percent));
            SetStatus(text, Color.FromArgb(23, 92, 211));
            if (percent == 0 || percent == 100 || percent >= lastPackageLogPercent + 10)
            {
                lastPackageLogPercent = percent;
                AppendLog(text);
            }
        }

        private async Task DetectAsync()
        {
            if (connectedSerial == null || IsBusy()) { return; }
            ResetDevice();
            packageBusy = true;
            UpdateControls();
            string address = ipInput.Text.Trim();
            SetStatus("正在读取已连接D31的设备状态。", Color.FromArgb(23, 92, 211));
            AppendLog("开始检测已连接ADB目标" + connectedSerial + "。");
            try
            {
                device = await Task.Run(delegate { return DeviceDetector.Inspect(toolRoot, connectedSerial, address); });
                ShowDevice(address);
                if (!device.IsRoot)
                {
                    throw new InvalidOperationException("设备已连接，但adb shell不是root，不能执行备份和Recovery刷机。");
                }
                SetStatus("D31状态已刷新。无需刷机包即可点击“只读检查”。", Color.FromArgb(2, 122, 72));
                AppendLog("设备识别完成：" + device.Model + "，ADB端口" + device.AdbPort + "。");
            }
            catch (Exception exception)
            {
                ResetDevice();
                SetStatus(exception.Message, Color.FromArgb(180, 35, 24));
                AppendLog("设备检测失败：" + exception.Message.Replace("\r", " ").Replace("\n", " "));
            }
            finally
            {
                packageBusy = false;
                UpdateControls();
            }
        }

        private void ShowDevice(string address)
        {
            deviceValue.Text = device.Model + " · " + device.Serial + " · ADB服务器端口" + device.AdbPort;
            buildValue.Text = device.Fingerprint;
            networkValue.Text = "目标=" + address +
                (device.TargetAddressIsEthernet ? "（有线eth0）" : "（Wi-Fi或其他接口）") +
                " · eth0=" + (String.IsNullOrWhiteSpace(device.EthernetAddress) ? "未连接" : device.EthernetAddress) +
                " · " + device.Power + (device.IsRoot ? " · root ADB" : " · 非root ADB");
        }

        private async Task PrepareAdbAsync()
        {
            if (packageBusy || adbPrepared) { return; }
            packageBusy = true;
            UpdateControls();
            SetStatus("正在初始化D31专用ADB端口5042，保留已有连接。", Color.FromArgb(23, 92, 211));
            try
            {
                string result = await Task.Run(delegate
                {
                    return DeviceDetector.PrepareDedicatedServer(toolRoot);
                });
                AppendLog(result);
                adbPrepared = true;
                SetStatus("ADB服务器5042已就绪。填写D31的IP或IPv4:端口并连接。", Color.FromArgb(2, 122, 72));
            }
            catch (Exception exception)
            {
                AppendLog("ADB端口5042初始化失败：" + exception.Message.Replace("\r", " ").Replace("\n", " "));
                SetStatus("ADB端口5042初始化失败；检测设备时将自动重试。", Color.FromArgb(180, 35, 24));
            }
            finally
            {
                packageBusy = false;
                UpdateControls();
            }
        }

        private async void StartPreflight()
        {
            if (device == null || IsBusy()) { return; }
            if (!await CheckMaintenanceAsync()) { return; }
            preflightPassed = false;
            rescueDirectory = null;
            flashAfterBackup = false;
            recoveryTriggered = false;
            eraseCheck.Checked = false;
            progress.Value = 0;
            currentOperation = "只读检查";
            SetStatus("正在检查root、构建、网络、分区尺寸、Recovery入口和空间。", Color.FromArgb(23, 92, 211));
            AppendLog("启动设备只读检查；不需要刷机包，不写设备分区、不重启设备。");
            StartPowerShell(Path.Combine(toolRoot, "flash_d31_recovery.ps1"), DeviceArguments() + " -DevicePreflightOnly");
        }

        private void StartAutomaticBackup()
        {
            if (device == null || firmware == null || !device.TargetAddressIsEthernet || !preflightPassed || IsBusy()) { return; }
            Directory.CreateDirectory(backupRoot);
            rescueDirectory = null;
            progress.Value = 0;
            currentOperation = "自动备份原系统";
            SetStatus("正在从目标机只读备份原始system、boot及私有分区。请勿断电或中断网络。", Color.FromArgb(23, 92, 211));
            AppendLog("开始把当前D31的原系统和本机私有分区备份到电脑硬盘；不写设备分区、不重启设备。");
            StartPowerShell(Path.Combine(toolRoot, "create_d31_rescue.ps1"),
                DeviceArguments() + " -OutputBase " + DeviceDetector.Quote(backupRoot));
        }

        private void StartFlash()
        {
            if (device == null || firmware == null || !device.TargetAddressIsEthernet || !preflightPassed || !eraseCheck.Checked || IsBusy()) { return; }
            bool createBackup = backupCheck.Checked;
            DialogResult answer = MessageBox.Show(
                "目标设备：" + device.Model + " / " + device.Serial + "\r\n\r\n" +
                (createBackup
                    ? "已选择备份：工具会先把原系统保存到电脑硬盘的D31备份目录，完成后自动继续刷机。\r\n\r\n"
                    : "已取消备份：刷机失败时将没有本机急救包可供恢复。\r\n\r\n") +
                "工具随后会上传并校验签名ZIP，最后重启到原厂Recovery。\r\n" +
                "Recovery会覆盖system及开机图片logo分区并清空userdata，保留boot。\r\n\r\n" +
                "确认这是可承担风险的同构建备用D31，并立即开始吗？",
                "确认开始D31 Recovery刷机",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning,
                MessageBoxDefaultButton.Button2);
            if (answer != DialogResult.Yes) { return; }

            flashAfterBackup = createBackup;
            if (createBackup)
            {
                StartAutomaticBackup();
                return;
            }
            rescueDirectory = null;
            BeginFlashProcess();
        }

        private async void BeginFlashProcess()
        {
            if (device == null || IsBusy()) { return; }
            if (!await CheckMaintenanceAsync()) { return; }
            progress.Value = 0;
            recoveryTriggered = false;
            currentOperation = "完整Recovery刷机";
            SetStatus("刷机已开始；Recovery触发前会先完成设备端ZIP校验。", Color.FromArgb(180, 35, 24));
            AppendLog(String.IsNullOrWhiteSpace(rescueDirectory)
                ? "用户取消原系统备份并确认清空数据，启动完整Recovery刷机。"
                : "原系统已经备份到电脑硬盘，用户确认清空数据，启动完整Recovery刷机。");
            string arguments = CommonArguments();
            arguments += String.IsNullOrWhiteSpace(rescueDirectory)
                ? " -SkipBackup"
                : " -RescueDirectory " + DeviceDetector.Quote(rescueDirectory);
            StartPowerShell(Path.Combine(toolRoot, "flash_d31_recovery.ps1"), arguments);
        }

        private async Task<bool> CheckMaintenanceAsync()
        {
            packageBusy = true;
            UpdateControls();
            SetStatus("正在确认D31没有进行系统修复。", Color.FromArgb(23, 92, 211));
            string serial = device.Serial;
            try
            {
                await Task.Run(delegate { DeviceDetector.AssertNoMaintenance(toolRoot, serial); });
                AppendLog("已确认当前D31维护标记不存在。");
                return true;
            }
            catch (Exception error)
            {
                preflightPassed = false;
                eraseCheck.Checked = false;
                flashAfterBackup = false;
                SetStatus(error.Message, Color.FromArgb(180, 35, 24));
                AppendLog(error.Message);
                return false;
            }
            finally { packageBusy = false; UpdateControls(); }
        }

        private string DeviceArguments()
        {
            return "-Serial " + DeviceDetector.Quote(device.Serial) +
                " -AdbPort " + device.AdbPort;
        }

        private string CommonArguments()
        {
            return DeviceArguments() +
                " -PackagePath " + DeviceDetector.Quote(firmware.PackagePath);
        }

        private void StartPowerShell(string script, string arguments)
        {
            if (!File.Exists(script))
            {
                SetStatus("缺少脚本：" + Path.GetFileName(script), Color.FromArgb(180, 35, 24));
                return;
            }
            ProcessStartInfo start = new ProcessStartInfo();
            start.FileName = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),
                "WindowsPowerShell", "v1.0", "powershell.exe");
            if (!File.Exists(start.FileName)) { start.FileName = "powershell.exe"; }
            start.Arguments = "-NoProfile -ExecutionPolicy Bypass -File " +
                DeviceDetector.Quote(script) + " " + arguments;
            start.WorkingDirectory = toolRoot;
            start.UseShellExecute = false;
            start.CreateNoWindow = true;
            start.RedirectStandardOutput = true;
            start.RedirectStandardError = true;
            start.StandardOutputEncoding = Encoding.UTF8;
            start.StandardErrorEncoding = Encoding.UTF8;
            runningProcess = new Process { StartInfo = start, EnableRaisingEvents = true };
            runningProcess.OutputDataReceived += delegate(object sender, DataReceivedEventArgs eventArgs)
            {
                ReceiveLine(eventArgs.Data);
            };
            runningProcess.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs eventArgs)
            {
                ReceiveLine(eventArgs.Data);
            };
            runningProcess.Exited += delegate(object sender, EventArgs eventArgs)
            {
                Process completed = (Process)sender;
                completed.WaitForExit();
                int exitCode = completed.ExitCode;
                BeginInvoke((MethodInvoker)delegate { ProcessFinished(exitCode); });
            };
            try
            {
                runningProcess.Start();
                runningProcess.BeginOutputReadLine();
                runningProcess.BeginErrorReadLine();
                UpdateControls();
            }
            catch (Exception exception)
            {
                AppendLog("无法启动PowerShell：" + exception.Message);
                SetStatus("无法启动刷机后端。", Color.FromArgb(180, 35, 24));
                runningProcess.Dispose();
                runningProcess = null;
                UpdateControls();
            }
        }

        private void ReceiveLine(string line)
        {
            if (String.IsNullOrWhiteSpace(line)) { return; }
            BeginInvoke((MethodInvoker)delegate
            {
                AppendLog(line);
                Match step = Regex.Match(line, @"\[(\d+)/(\d+)\]");
                if (step.Success)
                {
                    int number = Int32.Parse(step.Groups[1].Value, CultureInfo.InvariantCulture);
                    int total = Int32.Parse(step.Groups[2].Value, CultureInfo.InvariantCulture);
                    progress.Value = total == 0 ? 0 : Math.Min(100, number * 100 / total);
                    if (number >= 7 && currentOperation == "完整Recovery刷机") { recoveryTriggered = true; }
                }
                if (line.Contains("设备只读检查通过") || line.Contains("只读准入检查通过"))
                {
                    preflightPassed = true;
                }
                const string rescueMarker = "D31本机急救包创建通过：";
                if (line.StartsWith(rescueMarker, StringComparison.Ordinal))
                {
                    rescueDirectory = line.Substring(rescueMarker.Length).Trim();
                }
            });
        }

        private void ProcessFinished(int exitCode)
        {
            string completedOperation = currentOperation;
            if (runningProcess != null) { runningProcess.Dispose(); runningProcess = null; }
            if (completedOperation == "只读检查")
            {
                if (exitCode == 0 && preflightPassed)
                {
                    progress.Value = 100;
                    if (!device.TargetAddressIsEthernet)
                    {
                        SetStatus("设备只读检查通过；刷机前请断开并改用D31有线IP重新连接。", Color.FromArgb(158, 92, 0));
                    }
                    else if (firmware == null)
                    {
                        SetStatus("设备只读检查通过。选择或下载刷机包后即可继续。", Color.FromArgb(2, 122, 72));
                    }
                    else
                    {
                        SetStatus("设备只读检查和刷机包校验均已通过。确认清空数据后即可刷机。", Color.FromArgb(2, 122, 72));
                    }
                    AppendLog("设备只读检查通过；本次检查不依赖本地刷机包。");
                }
                else
                {
                    preflightPassed = false;
                    eraseCheck.Checked = false;
                    string reason = exitCode == 0
                        ? "后端退出码为0，但界面没有收到完整的通过标记"
                        : "退出码：" + exitCode;
                    SetStatus("只读检查未通过，开始刷机仍保持锁定。", Color.FromArgb(180, 35, 24));
                    AppendLog("只读检查失败或证据不完整，" + reason + "。请保留日志并重新检查。");
                }
            }
            else if (completedOperation == "自动备份原系统")
            {
                if (exitCode == 0 && !String.IsNullOrWhiteSpace(rescueDirectory) && Directory.Exists(rescueDirectory))
                {
                    progress.Value = 100;
                    SetStatus("原系统已备份到电脑硬盘，正在继续刷机。", Color.FromArgb(2, 122, 72));
                    AppendLog("电脑硬盘备份创建并双端校验通过：" + rescueDirectory);
                    if (flashAfterBackup)
                    {
                        flashAfterBackup = false;
                        BeginFlashProcess();
                        return;
                    }
                }
                else
                {
                    flashAfterBackup = false;
                    rescueDirectory = null;
                    SetStatus("电脑硬盘备份失败，刷机尚未开始。可重试，或取消备份后继续。", Color.FromArgb(180, 35, 24));
                    AppendLog("备份后端退出码：" + exitCode + "；未取得完整创建通过标记，未启动刷机。");
                }
            }
            else if (completedOperation == "完整Recovery刷机" && exitCode == 0)
            {
                progress.Value = 100;
                SetStatus("Recovery刷机和首次启动验证全部完成。", Color.FromArgb(2, 122, 72));
                AppendLog(String.IsNullOrWhiteSpace(rescueDirectory)
                    ? "完整刷机成功；用户本次选择不创建原系统备份。"
                    : "完整刷机成功；原系统备份保存在电脑的D31备份目录。");
                preflightPassed = false;
                eraseCheck.Checked = false;
                device = null;
                connectedSerial = null;
                deviceValue.Text = "操作完成后请重新检测";
                buildValue.Text = "-";
                networkValue.Text = "-";
            }
            else
            {
                preflightPassed = false;
                eraseCheck.Checked = false;
                SetStatus(completedOperation == "完整Recovery刷机" && !recoveryTriggered
                    ? "刷机准备失败，尚未触发Recovery或写入分区。请保留日志。"
                    : completedOperation + "失败，请保留窗口、设备现场和备份目录。", Color.FromArgb(180, 35, 24));
                AppendLog(completedOperation + "退出码：" + exitCode);
                if (completedOperation == "完整Recovery刷机" && recoveryTriggered)
                {
                    MessageBox.Show(
                        "失败发生在Recovery命令写入或触发以后。不要盲目断电或重复刷写；先查看D31屏幕、运行日志和D31备份目录。",
                        "D31刷机未完成",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Error);
                }
            }
            recoveryTriggered = false;
            UpdateControls();
        }

        private void OpenBackups()
        {
            Directory.CreateDirectory(backupRoot);
            try { Process.Start("explorer.exe", DeviceDetector.Quote(backupRoot)); }
            catch (Exception exception)
            {
                MessageBox.Show(exception.Message, "无法打开备份目录", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private void OpenBootstrapDirectory()
        {
            try
            {
                using (SaveFileDialog dialog = new SaveFileDialog())
                {
                    dialog.Title = "导出基础探针APK";
                    dialog.Filter = "Android APK (*.apk)|*.apk";
                    dialog.FileName = Path.GetFileName(RuntimeAssets.BasicProbe().RelativePath);
                    dialog.DefaultExt = "apk";
                    dialog.OverwritePrompt = false;
                    if (dialog.ShowDialog(this) != DialogResult.OK) { return; }
                    ExportBasicProbeTo(dialog.FileName);
                    MessageBox.Show(this, "基础探针APK已导出。", "导出完成", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
            }
            catch (Exception exception)
            {
                MessageBox.Show(exception.Message, "无法导出基础探针APK", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        internal string ExportBasicProbeTo(string destination)
        {
            string exported = RuntimeAssets.ExportBasicProbe(destination);
            AppendLog("基础探针APK已导出：" + exported);
            return exported;
        }

        private void ResetDevice()
        {
            device = null;
            preflightPassed = false;
            rescueDirectory = null;
            flashAfterBackup = false;
            recoveryTriggered = false;
            eraseCheck.Checked = false;
            deviceValue.Text = "未检测";
            buildValue.Text = "-";
            networkValue.Text = "-";
        }

        private void UpdateControls()
        {
            bool busy = IsBusy();
            selectPackageButton.Enabled = !busy && toolReady;
            githubDownloadButton.Enabled = !busy && toolReady;
            cloudflareDownloadButton.Enabled = !busy && toolReady;
            ipInput.Enabled = !busy && connectedSerial == null;
            connectButton.Enabled = !busy && toolReady && connectedSerial == null;
            disconnectButton.Enabled = !busy && connectedSerial != null;
            rescueButton.Enabled = !busy && toolReady;
            detectButton.Enabled = !busy && connectedSerial != null;
            preflightButton.Enabled = !busy && device != null;
            openBackupsButton.Enabled = !busy;
            backupCheck.Enabled = !busy;
            eraseCheck.Enabled = !busy && firmware != null && device != null &&
                device.TargetAddressIsEthernet && preflightPassed;
            flashButton.Enabled = !busy && firmware != null && device != null &&
                device.TargetAddressIsEthernet && preflightPassed && eraseCheck.Checked;
        }

        private bool IsBusy()
        {
            return packageBusy || (runningProcess != null && !runningProcess.HasExited);
        }

        private void SetStatus(string text, Color color)
        {
            statusLabel.Text = text;
            statusLabel.ForeColor = color;
            statusMark.BackColor = color;
        }

        private void AppendLog(string line)
        {
            log.AppendText(DateTime.Now.ToString("HH:mm:ss", CultureInfo.InvariantCulture) +
                "  " + line + Environment.NewLine);
        }

        private void OnFormClosing(object sender, FormClosingEventArgs eventArgs)
        {
            if (!IsBusy()) { return; }
            eventArgs.Cancel = true;
            MessageBox.Show(
                recoveryTriggered ? "Recovery已经触发，禁止关闭窗口或中断供电。" : "当前校验、下载、检查或传输尚未结束，请等待完成。",
                "操作进行中",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
        }
    }
}
