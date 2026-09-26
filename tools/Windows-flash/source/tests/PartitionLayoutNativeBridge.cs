using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;

// 仅接收离线 shell 请求；绝不调用 ADB。收到的原生参数原样交给隔离的 Linux shell。
class PartitionLayoutNativeBridge
{
    static int Main(string[] args)
    {
        string prefix = Environment.GetEnvironmentVariable("D31_LAYOUT_TRANSCRIPT");
        string basePrefix = prefix;
        for (int request = 2; File.Exists(prefix + ".argv.json"); request++)
            prefix = basePrefix + ".request-" + request;
        var utf8 = new UTF8Encoding(false);
        File.WriteAllText(prefix + ".argv.json", new JavaScriptSerializer().Serialize(args), utf8);
        if (args.Length != 6 || args[0] != "-P" || args[1] != "5042" ||
            args[2] != "-s" || args[3] != "offline-layout" || args[4] != "shell") {
            Console.Error.WriteLine("离线桥拒绝非预期参数");
            return 90;
        }
        string command = args[5];
        File.WriteAllText(prefix + ".received.sh", command, utf8);
        string distro = Environment.GetEnvironmentVariable("D31_LAYOUT_DISTRO");
        string scenario = Environment.GetEnvironmentVariable("D31_LAYOUT_CASE");
        if (!Regex.IsMatch(distro ?? "", @"\A[a-zA-Z0-9_.-]+\z") ||
            !Regex.IsMatch(scenario ?? "", @"\A[a-z0-9-]+\z")) return 91;
        string script = File.ReadAllText(Environment.GetEnvironmentVariable("D31_LAYOUT_FIXTURE"))
            .Replace("__SCENARIO__", scenario)
            .Replace("__COMMAND_BASE64__", Convert.ToBase64String(utf8.GetBytes(command)))
            .Replace("\r\n", "\n");
        var start = new ProcessStartInfo {
            FileName = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "wsl.exe"),
            Arguments = "-d " + distro + " -u root -e /bin/sh",
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
            StandardOutputEncoding = utf8, StandardErrorEncoding = utf8
        };
        // .NET Framework 的重定向输入沿用控制台编码，禁止为 shell 输入添加 BOM。
        Console.InputEncoding = utf8;
        using (var process = Process.Start(start)) {
            var stdoutTask = process.StandardOutput.ReadToEndAsync();
            var stderrTask = process.StandardError.ReadToEndAsync();
            process.StandardInput.Write(script);
            process.StandardInput.Close();
            if (!process.WaitForExit(20000)) {
                process.Kill();
                Console.Error.WriteLine("离线 shell 超时");
                return 92;
            }
            string stdout = stdoutTask.Result, stderr = stderrTask.Result;
            File.WriteAllText(prefix + ".stdout.txt", stdout, utf8);
            File.WriteAllText(prefix + ".stderr.txt", stderr, utf8);
            File.WriteAllText(prefix + ".exit.txt", process.ExitCode.ToString(), utf8);
            Console.OutputEncoding = utf8;
            Console.Out.Write(stdout);
            Console.Error.Write(stderr);
            return process.ExitCode;
        }
    }
}
