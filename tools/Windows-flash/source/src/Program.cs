using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

namespace D31FlashTool
{
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            string runtimeRoot;
            try
            {
                runtimeRoot = RuntimeAssets.Prepare();
            }
            catch (Exception exception)
            {
                if (args.Length > 0)
                {
                    Console.Error.WriteLine("运行资源初始化失败：" + exception.Message);
                    return 1;
                }
                MessageBox.Show(
                    "无法初始化刷机工具运行文件。\r\n\r\n" + exception.Message,
                    "D31刷机工具启动失败",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
                return 1;
            }

            if (args.Length == 3 && args[0] == "--export-basic-probe")
            {
                using (StreamWriter writer = new StreamWriter(args[2], false, new UTF8Encoding(false)))
                {
                    try
                    {
                        string exported = RuntimeAssets.ExportBasicProbe(args[1]);
                        writer.WriteLine("基础探针导出通过：{0}；SHA-256={1}", exported, RuntimeAssets.BasicProbe().Sha256);
                        return 0;
                    }
                    catch (Exception exception) { writer.WriteLine("基础探针导出失败：" + exception.Message); return 1; }
                }
            }

            if (args.Length >= 3 && args[0] == "--self-test")
            {
                using (StreamWriter writer = new StreamWriter(args[1], false, new UTF8Encoding(false)))
                {
                    return PackageValidator.Run(runtimeRoot, args[2], writer);
                }
            }

            if (args.Length >= 3 && args[0] == "--verify-package")
            {
                using (StreamWriter writer = new StreamWriter(args[2], false, new UTF8Encoding(false)))
                {
                    try
                    {
                        FirmwareSelection selection = FirmwareManager.SelectPackageAsync(
                            args[1],
                            delegate(int percent, string message)
                            {
                                writer.WriteLine("{0}%\t{1}", percent, message);
                                writer.Flush();
                            }).GetAwaiter().GetResult();
                        writer.WriteLine("VERIFY PACKAGE PASS\t{0}\t{1}", selection.Sha256, selection.PackagePath);
                        return 0;
                    }
                    catch (Exception exception)
                    {
                        writer.WriteLine("VERIFY PACKAGE FAIL\t{0}", exception);
                        return 1;
                    }
                }
            }

            if (args.Length >= 2 && args[0] == "--test-download-progress")
            {
                using (StreamWriter writer = new StreamWriter(args[1], false, new UTF8Encoding(false)))
                {
                    try
                    {
                        FirmwareManager.RunProgressParserTests(writer);
                        return 0;
                    }
                    catch (Exception exception)
                    {
                        writer.WriteLine("ARIA2 PROGRESS PARSER TEST FAIL\t" + exception);
                        return 1;
                    }
                }
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm(runtimeRoot, AppDomain.CurrentDomain.BaseDirectory));
            return 0;
        }
    }

    internal sealed class DeviceInfo
    {
        public string Serial;
        public int AdbPort;
        public string Model;
        public string Fingerprint;
        public string Ethernet;
        public string EthernetAddress;
        public string Power;
        public bool IsRoot;
        public bool TargetAddressIsEthernet;
    }

    internal static class PackageValidator
    {
        internal const long ExpectedPackageBytes = BuildConstants.OfficialPackageBytes;
        internal const string ExpectedFingerprint =
            "alps/full_hct6737t_66_m0/hct6737t_66_m0:6.0/MRA58K/1583081804:userdebug/test-keys";

        internal static bool IsToolRoot(string root)
        {
            return !String.IsNullOrWhiteSpace(root) &&
                File.Exists(Path.Combine(root, "flash_d31_recovery.ps1")) &&
                File.Exists(Path.Combine(root, "tools", "adb.exe")) &&
                File.Exists(Path.Combine(root, "tools", "AdbWinApi.dll")) &&
                File.Exists(Path.Combine(root, "tools", "AdbWinUsbApi.dll"));
        }

        internal static void ValidateToolRoot(string root, TextWriter output)
        {
            string[] required = new string[]
            {
                "flash_d31_recovery.ps1",
                "approved-package.json", "installed-files.json",
                "create_d31_rescue.ps1",
                Path.Combine("首次引导工具", "D31-setup-probe.apk"),
                Path.Combine("tools", "adb.exe"),
                Path.Combine("tools", "AdbWinApi.dll"),
                Path.Combine("tools", "AdbWinUsbApi.dll"),
                Path.Combine("tools", "aria2c.exe"),
                Path.Combine("tools", "aria2-COPYING.txt"),
                Path.Combine("rescue", "D31_RESCUE_UPDATE.zip"),
                Path.Combine("rescue", "D31_RESCUE_TEST.zip")
            };
            foreach (string relative in required)
            {
                if (!File.Exists(Path.Combine(root, relative)))
                {
                    throw new InvalidDataException("工具包缺少文件：" + relative);
                }
            }

            string basicProbe = RuntimeAssets.ValidateBasicProbe(root);
            output.WriteLine("通过：内置基础探针{0}，SHA-256匹配。", Path.GetFileName(basicProbe));

            string script = File.ReadAllText(Path.Combine(root, "flash_d31_recovery.ps1"), Encoding.UTF8);
            string[] markers = new string[]
            {
                "Invoke-AdbOptional", "Wait-ForAndroid", "$RemotePackage", "$PreflightOnly",
                "$PackagePath", "$RescueDirectory", "$SkipBackup", "Assert-RescueDirectory",
                "[1/8]", "[8/8]", "reboot recovery", "RequiredBackupPartitions",
                "Convert-AndroidSizeToBytes", "df /data", "stat -c %s",
                "Copy-PackageToDeviceResumable", "D31_UPLOAD_V2", "分块上传签名ZIP", "$PackagePreflightOnly"
            };
            foreach (string marker in markers)
            {
                if (!script.Contains(marker))
                {
                    throw new InvalidDataException("Recovery后端缺少必要标记：" + marker);
                }
            }
            if (script.Contains("flash_d22_full") ||
                Regex.IsMatch(script, @"(?i)dd\s+.*\bof=\S*(system|boot|userdata)\b"))
            {
                throw new InvalidDataException("Recovery后端包含禁止的目标分区直接写入逻辑");
            }
            string rescueScript = File.ReadAllText(Path.Combine(root, "create_d31_rescue.ps1"), Encoding.UTF8);
            foreach (string marker in new string[]
            {
                "D31本机急救包创建通过", "D31_RESCUE_MANIFEST.txt", "D31_RESCUE_SYSTEM.img.gz",
                "D31_RESCUE_BOOT.img", "Get-GzipRawInfo", "PrivatePartitions",
                "AvailableFreeSpace", "ExpectedRestoreLauncherHash", "ExpectedTestLauncherHash",
                "Convert-AndroidSizeToBytes", "df /data", "stat -c %s"
            })
            {
                if (!rescueScript.Contains(marker))
                {
                    throw new InvalidDataException("急救包后端缺少必要标记：" + marker);
                }
            }
            string rescueRestore = Path.Combine(root, "rescue", "D31_RESCUE_UPDATE.zip");
            string rescueTest = Path.Combine(root, "rescue", "D31_RESCUE_TEST.zip");
            if (!ComputeSha256(rescueRestore).Equals(BuildConstants.RescueRestoreSha256, StringComparison.OrdinalIgnoreCase) ||
                !ComputeSha256(rescueTest).Equals(BuildConstants.RescueTestSha256, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException("签名急救入口的SHA-256不匹配");
            }
            string aria2 = Path.Combine(root, "tools", "aria2c.exe");
            if (!ComputeSha256(aria2).Equals(BuildConstants.Aria2Sha256, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException("高速下载组件aria2c.exe的SHA-256不匹配");
            }
            output.WriteLine("通过：单文件EXE释放的刷机后端、本机急救包后端、ADB、高速下载组件和签名急救入口完整。");
        }

        private static string ComputeSha256(string path)
        {
            using (SHA256 sha256 = SHA256.Create())
            using (FileStream stream = File.OpenRead(path))
            {
                return BitConverter.ToString(sha256.ComputeHash(stream)).Replace("-", "");
            }
        }

        internal static void ValidatePackage(string packagePath, TextWriter output)
        {
            FileInfo item = new FileInfo(packagePath);
            if (!item.Exists) { throw new FileNotFoundException("刷机包不存在", packagePath); }
            if (item.Length != ExpectedPackageBytes) { throw new InvalidDataException("签名ZIP长度不匹配"); }
            string actual = ComputeSha256(packagePath);
            if (!actual.Equals(BuildConstants.OfficialPackageSha256, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException("签名ZIP的SHA-256不匹配");
            }
            output.WriteLine("通过：签名ZIP，{0}字节，SHA-256匹配。", item.Length);
        }

        internal static int Run(string root, string packagePath, TextWriter output)
        {
            try
            {
                ValidateToolRoot(root, output);
                ValidatePackage(packagePath, output);
                output.WriteLine("SELFTEST PASS：{0}与单文件工具通过离线检查；未执行真实Recovery刷写。", Path.GetFileName(packagePath));
                return 0;
            }
            catch (Exception exception)
            {
                output.WriteLine("SELFTEST FAIL：" + exception.Message);
                return 1;
            }
        }
    }

    internal static class ProcessRunner
    {
        internal static string Run(string fileName, string arguments, int timeoutMilliseconds)
        {
            ProcessStartInfo start = new ProcessStartInfo
            {
                FileName = fileName,
                Arguments = arguments,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };

            using (Process process = new Process())
            using (ManualResetEvent stdoutClosed = new ManualResetEvent(false))
            using (ManualResetEvent stderrClosed = new ManualResetEvent(false))
            {
                StringBuilder stdout = new StringBuilder();
                StringBuilder stderr = new StringBuilder();
                process.StartInfo = start;
                process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs eventArgs)
                {
                    if (eventArgs.Data == null) { stdoutClosed.Set(); } else { stdout.AppendLine(eventArgs.Data); }
                };
                process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs eventArgs)
                {
                    if (eventArgs.Data == null) { stderrClosed.Set(); } else { stderr.AppendLine(eventArgs.Data); }
                };
                process.Start();
                process.BeginOutputReadLine();
                process.BeginErrorReadLine();
                if (!process.WaitForExit(timeoutMilliseconds))
                {
                    try { process.Kill(); } catch { }
                    process.WaitForExit();
                    throw new TimeoutException("命令超时：" + Path.GetFileName(fileName));
                }
                if (!stdoutClosed.WaitOne(5000) || !stderrClosed.WaitOne(5000))
                {
                    throw new IOException("命令结束后未能完整读取输出：" + Path.GetFileName(fileName));
                }
                string combined = (stdout.ToString() + Environment.NewLine + stderr.ToString()).Trim();
                if (process.ExitCode != 0) { throw new InvalidOperationException(combined); }
                return combined;
            }
        }
    }

    internal static class DeviceDetector
    {
        internal const int DedicatedAdbPort = 5042;

        internal static string PrepareDedicatedServer(string toolRoot)
        {
            string adb = Path.Combine(toolRoot, "tools", "adb.exe");
            if (!File.Exists(adb)) { throw new FileNotFoundException("工具包缺少adb.exe", adb); }

            StringBuilder log = new StringBuilder();
            ProcessRunner.Run(adb, "-P 5042 start-server", 20000);
            log.AppendLine("D31专用ADB端口5042已就绪，保留已有设备连接：");
            log.AppendLine(ProcessRunner.Run(adb, "-P 5042 devices -l", 20000));
            return log.ToString().Trim();
        }

        internal static string NormalizeAddress(string address)
        {
            string input = (address ?? String.Empty).Trim();
            int colon = input.IndexOf(':');
            if (colon >= 0)
            {
                if (RescueClient.ValidPort(input.Substring(colon + 1)) == 0)
                    throw new InvalidOperationException("ADB端口必须是1至65535的十进制整数。");
                input = input.Substring(0, colon);
            }
            IPAddress parsed;
            if (!IPAddress.TryParse(input, out parsed) ||
                parsed.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork)
            {
                throw new InvalidOperationException("请输入D31的有效IPv4地址，例如192.168.2.62。");
            }
            return parsed.ToString();
        }

        internal static string Connect(string toolRoot, string address)
        {
            string adb = Path.Combine(toolRoot, "tools", "adb.exe");
            if (!File.Exists(adb)) { throw new FileNotFoundException("工具包缺少adb.exe", adb); }
            return ConnectCore(address, arguments => ProcessRunner.Run(adb, arguments, 20000), RescueClient.ReadAdbPort);
        }

        internal static int ResolvePort(string address, Func<string, int> readPort, Func<string> devices)
        {
            string ip = NormalizeAddress(address);
            string input = address.Trim();
            int colon = input.IndexOf(':');
            if (colon >= 0) return RescueClient.ValidPort(input.Substring(colon + 1));
            try
            {
                int observed = readPort(ip);
                if (observed > 0 && observed <= 65535) return observed;
            }
            catch (Exception) { /* 旧设备可能没有8765；只查询已有目标，不扫描网络端口。 */ }
            int found = 0;
            foreach (string line in devices().Split('\n'))
            {
                Match match = Regex.Match(line.Trim(), "^" + Regex.Escape(ip) + @":([0-9]+)\s+(device|offline|unauthorized)(\s|$)");
                int port = match.Success ? RescueClient.ValidPort(match.Groups[1].Value) : 0;
                if (port == 0) continue;
                if (found != 0 && found != port)
                    throw new InvalidOperationException("该IP存在多个ADB端口记录，请在IP框填写IPv4:端口，不自动猜测。");
                found = port;
            }
            // 仅作旧设备的连接候选，不写入设备、不声称已确认其配置。
            return found == 0 ? 5555 : found;
        }

        internal static string ConnectCore(string address, Func<string, string> run, Func<string, int> readPort)
        {
            string ip = NormalizeAddress(address);
            int port = ResolvePort(address, readPort, () => run("-P 5042 devices -l"));
            string serial = ip + ":" + port;
            try
            {
                // adbd重启后清理该目标的陈旧会话，不能全局断开其他D31。
                try { run("-P 5042 disconnect " + Quote(serial)); } catch (Exception) { }
                run("-P 5042 connect " + Quote(serial));
                string state = run("-P 5042 -s " + Quote(serial) + " get-state").Trim();
                if (state != "device") throw new IOException("ADB状态不是device：" + state);
                return serial;
            }
            catch (Exception ex)
            {
                throw new IOException("ADB握手未通过，目标" + serial + "。属性或恢复命令不代表连接成功；旧uptool不能回传实际端口，必要时在IP框填写IPv4:端口。", ex);
            }
        }

        internal static void ValidateSerial(string serial, string address)
        {
            string ip = NormalizeAddress(address);
            string prefix = ip + ":";
            if (serial == null || !serial.StartsWith(prefix, StringComparison.Ordinal) ||
                RescueClient.ValidPort(serial.Substring(prefix.Length)) == 0 ||
                (address.Trim().Contains(":") && NormalizeEndpoint(address) != serial))
                throw new InvalidOperationException("当前ADB连接与IP输入框不一致，请先断开后重新连接。");
        }

        private static string NormalizeEndpoint(string address)
        {
            string input = address.Trim();
            return NormalizeAddress(input) + ":" + RescueClient.ValidPort(input.Substring(input.IndexOf(':') + 1));
        }

        internal static string Disconnect(string toolRoot, string serial)
        {
            string adb = Path.Combine(toolRoot, "tools", "adb.exe");
            if (!File.Exists(adb)) { throw new FileNotFoundException("工具包缺少adb.exe", adb); }
            return RunOptional(adb, "-P 5042 disconnect " + Quote(serial));
        }

        internal static DeviceInfo Inspect(string toolRoot, string serial, string address)
        {
            string ip = NormalizeAddress(address);
            ValidateSerial(serial, address);
            string adb = Path.Combine(toolRoot, "tools", "adb.exe");
            if (!File.Exists(adb)) { throw new FileNotFoundException("工具包缺少adb.exe", adb); }
            string state = RunAdb(adb, DedicatedAdbPort, serial, "get-state").Trim();
            if (state != "device") { throw new InvalidOperationException("ADB状态不是device：" + state); }

            DeviceInfo info = new DeviceInfo
            {
                Serial = serial,
                AdbPort = DedicatedAdbPort,
                Model = RunAdb(adb, DedicatedAdbPort, serial, "shell getprop ro.product.model").Trim(),
                Fingerprint = RunAdb(adb, DedicatedAdbPort, serial, "shell getprop ro.build.fingerprint").Trim()
            };
            info.IsRoot = RunAdb(adb, DedicatedAdbPort, serial, "shell id").Contains("uid=0(root)");
            info.Ethernet = RunAdb(adb, DedicatedAdbPort, serial, "shell ip -4 addr show dev eth0").Trim();
            Match ethernetAddress = Regex.Match(info.Ethernet, @"(?m)\binet\s+([0-9.]+)/");
            info.EthernetAddress = ethernetAddress.Success ? ethernetAddress.Groups[1].Value : String.Empty;
            info.TargetAddressIsEthernet = Regex.IsMatch(
                info.Ethernet,
                @"(?m)\binet\s+" + Regex.Escape(ip) + @"/");
            if (info.Fingerprint != PackageValidator.ExpectedFingerprint)
            {
                throw new InvalidOperationException("D31构建指纹不匹配，禁止刷机：" + info.Fingerprint);
            }
            string battery = RunAdb(adb, DedicatedAdbPort, serial, "shell dumpsys battery");
            Match level = Regex.Match(battery, @"(?m)^\s*level:\s*(\d+)");
            bool ac = Regex.IsMatch(battery, @"(?m)^\s*AC powered:\s*true");
            bool usb = Regex.IsMatch(battery, @"(?m)^\s*USB powered:\s*true");
            info.Power = (level.Success ? level.Groups[1].Value + "%" : "未知") +
                (ac ? "，外部供电" : (usb ? "，USB供电" : "，未检测到外部供电"));
            return info;
        }

        internal static void AssertNoMaintenance(string toolRoot, string serial)
        {
            ValidateSerial(serial, serial);
            string adb = Path.Combine(toolRoot, "tools", "adb.exe");
            MaintenanceGuard.AssertAbsent(delegate(string command)
            {
                return RunAdb(adb, DedicatedAdbPort, serial, "shell " + Quote(command));
            });
        }

        private static string RunOptional(string adb, string arguments)
        {
            try { return ProcessRunner.Run(adb, arguments, 20000); }
            catch (Exception exception) { return "可忽略的清理结果：" + exception.Message; }
        }

        private static string RunAdb(string adb, int port, string serial, string arguments)
        {
            return ProcessRunner.Run(adb, "-P " + port + " -s " + Quote(serial) + " " + arguments, 30000);
        }

        internal static string Quote(string value)
        {
            return FirmwareManager.QuoteArgument(value);
        }
    }
}
