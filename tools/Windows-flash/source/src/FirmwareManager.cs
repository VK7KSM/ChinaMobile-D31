using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace D31FlashTool
{
    internal sealed class FirmwareSelection
    {
        public string PackagePath;
        public string Sha256;
        public long Bytes;
    }

    internal enum FirmwareDownloadSource
    {
        GitHub,
        Cloudflare
    }

    internal sealed class Aria2Progress
    {
        public int Percent;
        public string Downloaded;
        public string Total;
        public string Connections;
        public string Speed;
        public string Eta;
    }

    internal static class FirmwareManager
    {
        internal const string PackageName = "D31_SVP3390_Factory_Flash_v1.4.3_testkey.zip";
        private const string GitHubDownloadUrl =
            "https://github.com/VK7KSM/ChinaMobile-D31/releases/download/v1.4.3/" + PackageName;
        private const string CloudflareDownloadUrl =
            "https://cdn.elfradio.net/d31/" + PackageName;
        private static readonly Regex Aria2ProgressPattern = new Regex(
            @"\[#\w+\s+(?<downloaded>\S+)/(?<total>\S+)\((?<percent>\d+)%\)\s+CN:(?<connections>\d+)\s+DL:(?<speed>\S+?)(?:/s)?(?:\s+ETA:(?<eta>[^\]]+))?\]",
            RegexOptions.Compiled | RegexOptions.CultureInvariant);

        internal static async Task<FirmwareSelection> SelectPackageAsync(
            string packagePath,
            Action<int, string> report)
        {
            if (!File.Exists(packagePath)) { throw new FileNotFoundException("刷机包不存在", packagePath); }
            FileInfo item = new FileInfo(packagePath);
            if (item.Length != PackageValidator.ExpectedPackageBytes)
            {
                throw new InvalidDataException(
                    "所选文件长度不匹配，不是本工具批准的D31刷机包。\r\n" +
                    "实际字节数：" + item.Length.ToString(CultureInfo.InvariantCulture));
            }
            report(0, "正在校验所选D31签名刷机包。");
            string actualHash = await Task.Run(delegate { return ComputeSha256(packagePath, report); });
            if (!actualHash.Equals(BuildConstants.OfficialPackageSha256, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException(
                    "所选文件不是本工具批准的D31签名刷机包，或文件已经损坏。\r\n" +
                    "实际SHA-256：" + actualHash);
            }
            report(100, "D31签名刷机包长度和SHA-256全部通过。");
            return new FirmwareSelection { PackagePath = packagePath, Sha256 = actualHash, Bytes = item.Length };
        }

        internal static Task<FirmwareSelection> DownloadAsync(
            string toolRoot,
            string outputRoot,
            FirmwareDownloadSource source,
            Action<int, string> report)
        {
            return DownloadFromAsync(toolRoot, outputRoot,
                source == FirmwareDownloadSource.GitHub ? GitHubDownloadUrl : CloudflareDownloadUrl,
                source == FirmwareDownloadSource.GitHub ? "GitHub" : "Cloudflare",
                source == FirmwareDownloadSource.GitHub ? 8 : 16, report);
        }

        internal static async Task<FirmwareSelection> DownloadFromAsync(
            string toolRoot, string outputRoot, string sourceUrl, string sourceName,
            int connections, Action<int, string> report)
        {
            string aria2Path = Path.Combine(toolRoot, "tools", "aria2c.exe");
            if (!File.Exists(aria2Path))
            {
                throw new FileNotFoundException("工具包缺少高速下载组件aria2c.exe", aria2Path);
            }

            string packagePath = Path.Combine(outputRoot, PackageName);
            if (File.Exists(packagePath))
            {
                report(0, "同目录已有同名刷机包，正在核对是否可直接使用。");
                try
                {
                    return await SelectPackageAsync(packagePath, report);
                }
                catch (InvalidDataException)
                {
                    string timestamp = DateTime.Now.ToString("yyyyMMdd_HHmmss", CultureInfo.InvariantCulture);
                    packagePath = Path.Combine(outputRoot,
                        "D31_SVP3390_Factory_Flash_v1.4.3_testkey_" + timestamp + ".zip");
                    report(0, "现有同名文件校验不匹配，将保留原文件并下载到新文件。");
                }
            }

            string partialPath = packagePath + ".高速下载中";
            report(0, "准备从" + sourceName + "高速下载，最多" + connections + "个并发连接；支持断点续传。");
            try
            {
                await Task.Run(delegate
                {
                    Exception multiConnectionFailure = null;
                    try
                    {
                        RunAria2Download(aria2Path, partialPath, sourceUrl, sourceName, connections, report);
                    }
                    catch (Exception exception)
                    {
                        multiConnectionFailure = exception;
                    }

                    if (multiConnectionFailure != null)
                    {
                        report(0, sourceName + "多连接下载失败；已保留断点，正在自动切换为单连接兼容模式。");
                        try
                        {
                            RunAria2Download(aria2Path, partialPath, sourceUrl, sourceName, 1, report);
                        }
                        catch (Exception singleConnectionFailure)
                        {
                            throw new IOException(
                                sourceName + "的多连接和单连接兼容模式均失败。\r\n" +
                                "多连接错误：" + multiConnectionFailure.Message + "\r\n" +
                                "单连接错误：" + singleConnectionFailure.Message,
                                singleConnectionFailure);
                        }
                    }
                });
                FirmwareSelection selection;
                try { selection = await SelectPackageAsync(partialPath, report); }
                catch (InvalidDataException) {
                    string quarantine = QuarantineDownload(partialPath);
                    throw new InvalidDataException("下载文件校验失败，已隔离到：" + quarantine +
                        "。再次点击下载将重新获取刷机包。");
                }
                File.Move(partialPath, packagePath);
                selection.PackagePath = packagePath;
                return selection;
            }
            catch (InvalidDataException)
            {
                report(0, "下载文件校验失败，已停止使用该文件；请重新下载。");
                throw;
            }
            catch
            {
                report(0, "下载未完成；已保留临时文件和断点记录，再次选择任一高速下载源可继续。");
                throw;
            }
        }

        internal static string QuarantineDownload(string partialPath)
        {
            string quarantine = partialPath + ".invalid-" + Guid.NewGuid().ToString("N");
            // 先移走控制记录，避免下次把旧分块状态用于新的数据文件。
            if (File.Exists(partialPath + ".aria2")) File.Move(partialPath + ".aria2", quarantine + ".aria2");
            if (File.Exists(partialPath)) File.Move(partialPath, quarantine);
            return quarantine;
        }

        private static void RunAria2Download(
            string aria2Path,
            string partialPath,
            string sourceUrl,
            string sourceName,
            int connections,
            Action<int, string> report)
        {
            string directory = Path.GetDirectoryName(partialPath);
            string outputName = Path.GetFileName(partialPath);
            string arguments =
                "--no-conf=true --allow-overwrite=true --auto-file-renaming=false --continue=true " +
                "--file-allocation=none --enable-color=false --check-certificate=true " +
                "--max-connection-per-server=" + connections.ToString(CultureInfo.InvariantCulture) + " " +
                "--split=" + connections.ToString(CultureInfo.InvariantCulture) + " " +
                "--min-split-size=1M --max-tries=10 --retry-wait=3 --connect-timeout=20 --timeout=30 " +
                "--summary-interval=1 --console-log-level=notice --download-result=hide " +
                "--stop-with-process=" + Process.GetCurrentProcess().Id.ToString(CultureInfo.InvariantCulture) + " " +
                "--user-agent=" + QuoteArgument("D31-Flash-Tool/" + BuildConstants.ToolVersion) + " " +
                "--dir=" + QuoteArgument(directory) + " --out=" + QuoteArgument(outputName) + " " +
                QuoteArgument(sourceUrl);

            ProcessStartInfo start = new ProcessStartInfo
            {
                FileName = aria2Path,
                Arguments = arguments,
                WorkingDirectory = directory,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };

            StringBuilder diagnostics = new StringBuilder();
            object diagnosticsLock = new object();
            using (Process process = new Process())
            using (ManualResetEvent stdoutClosed = new ManualResetEvent(false))
            using (ManualResetEvent stderrClosed = new ManualResetEvent(false))
            {
                process.StartInfo = start;
                DataReceivedEventHandler receive = delegate(object sender, DataReceivedEventArgs eventArgs)
                {
                    if (eventArgs.Data == null) { return; }

                    lock (diagnosticsLock)
                    {
                        diagnostics.AppendLine(eventArgs.Data);
                        if (diagnostics.Length > 16000) { diagnostics.Remove(0, diagnostics.Length - 12000); }
                    }
                    Aria2Progress progress;
                    if (TryParseAria2Progress(eventArgs.Data, out progress))
                    {
                        string speed = progress.Speed.EndsWith("/s", StringComparison.OrdinalIgnoreCase)
                            ? progress.Speed : progress.Speed + "/s";
                        string message = sourceName + "高速下载：" + progress.Downloaded + " / " + progress.Total +
                            "（" + progress.Percent.ToString(CultureInfo.InvariantCulture) + "%），" +
                            progress.Connections + "个连接，" + speed;
                        if (!String.IsNullOrWhiteSpace(progress.Eta))
                        {
                            message += "，预计剩余" + progress.Eta;
                        }
                        report(progress.Percent, message);
                    }
                };
                process.OutputDataReceived += receive;
                process.ErrorDataReceived += receive;
                process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs eventArgs)
                {
                    if (eventArgs.Data == null) { stdoutClosed.Set(); }
                };
                process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs eventArgs)
                {
                    if (eventArgs.Data == null) { stderrClosed.Set(); }
                };

                process.Start();
                process.BeginOutputReadLine();
                process.BeginErrorReadLine();
                process.WaitForExit();
                if (!stdoutClosed.WaitOne(5000) || !stderrClosed.WaitOne(5000))
                {
                    throw new IOException("高速下载组件退出后未能完整读取状态输出");
                }
                if (process.ExitCode != 0)
                {
                    string detail;
                    lock (diagnosticsLock) { detail = diagnostics.ToString().Trim(); }
                    throw new IOException(sourceName + "高速下载失败，aria2退出码" +
                        process.ExitCode.ToString(CultureInfo.InvariantCulture) +
                        (String.IsNullOrWhiteSpace(detail) ? String.Empty : "。\r\n" + detail));
                }
            }

            if (!File.Exists(partialPath)) { throw new IOException("高速下载完成后没有找到临时刷机包"); }
        }

        internal static bool TryParseAria2Progress(string line, out Aria2Progress progress)
        {
            progress = null;
            if (String.IsNullOrWhiteSpace(line)) { return false; }
            Match match = Aria2ProgressPattern.Match(line);
            if (!match.Success) { return false; }
            int percent;
            if (!Int32.TryParse(match.Groups["percent"].Value, NumberStyles.None,
                CultureInfo.InvariantCulture, out percent)) { return false; }
            progress = new Aria2Progress
            {
                Percent = percent,
                Downloaded = match.Groups["downloaded"].Value,
                Total = match.Groups["total"].Value,
                Connections = match.Groups["connections"].Value,
                Speed = match.Groups["speed"].Value,
                Eta = match.Groups["eta"].Success ? match.Groups["eta"].Value : String.Empty
            };
            return true;
        }

        internal static void RunProgressParserTests(TextWriter output)
        {
            string[] lines = new string[]
            {
                "[#73909a 528KiB/4.6MiB(10%) CN:5 DL:266KiB ETA:16s]",
                "[#abcdef 1.00GiB/1.17GiB(85%) CN:16 DL:12.5MiB ETA:13s]",
                "[#123456 4.5MiB/4.6MiB(96%) CN:2 DL:512KiB]"
            };
            int[] expected = new int[] { 10, 85, 96 };
            string[] expectedConnections = new string[] { "5", "16", "2" };
            string[] expectedSpeeds = new string[] { "266KiB", "12.5MiB", "512KiB" };
            for (int index = 0; index < lines.Length; ++index)
            {
                Aria2Progress progress;
                if (!TryParseAria2Progress(lines[index], out progress) ||
                    progress.Percent != expected[index] ||
                    progress.Connections != expectedConnections[index] ||
                    progress.Speed != expectedSpeeds[index])
                {
                    throw new InvalidDataException("aria2进度解析测试失败：" + lines[index]);
                }
            }
            output.WriteLine("ARIA2 PROGRESS PARSER TEST PASS");
        }

        private static string ComputeSha256(string path, Action<int, string> report)
        {
            FileInfo item = new FileInfo(path);
            long processed = 0;
            int lastPercent = -1;
            using (SHA256 sha256 = SHA256.Create())
            using (FileStream stream = File.OpenRead(path))
            {
                byte[] buffer = new byte[8 * 1024 * 1024];
                int read;
                while ((read = stream.Read(buffer, 0, buffer.Length)) > 0)
                {
                    sha256.TransformBlock(buffer, 0, read, null, 0);
                    processed += read;
                    int percent = item.Length == 0 ? 100 : (int)(processed * 100L / item.Length);
                    if (percent != lastPercent)
                    {
                        lastPercent = percent;
                        report(percent, "正在校验刷机包SHA-256：" + percent + "%");
                    }
                }
                sha256.TransformFinalBlock(new byte[0], 0, 0);
                return BitConverter.ToString(sha256.Hash).Replace("-", "");
            }
        }

        private static string FormatBytes(long bytes)
        {
            if (bytes < 0) { return "未知"; }
            if (bytes >= 1024L * 1024L * 1024L)
            {
                return (bytes / (1024d * 1024d * 1024d)).ToString("0.00", CultureInfo.InvariantCulture) + " GB";
            }
            if (bytes >= 1024L * 1024L)
            {
                return (bytes / (1024d * 1024d)).ToString("0.0", CultureInfo.InvariantCulture) + " MB";
            }
            return (bytes / 1024d).ToString("0", CultureInfo.InvariantCulture) + " KB";
        }

        internal static string QuoteArgument(string value)
        {
            var result = new System.Text.StringBuilder("\"");
            int slashes = 0;
            foreach (char c in value) {
                if (c == '\\') { slashes++; continue; }
                result.Append('\\', c == '"' ? slashes * 2 + 1 : slashes);
                result.Append(c);
                slashes = 0;
            }
            result.Append('\\', slashes * 2);
            return result.Append('"').ToString();
        }
    }
}
