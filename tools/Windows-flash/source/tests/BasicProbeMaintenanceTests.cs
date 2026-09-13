using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using D31FlashTool;

internal static class BasicProbeMaintenanceTests
{
    private static readonly List<string> lines = new List<string>();
    private static int checks;
    private static void Check(bool ok, string name)
    {
        if (!ok) { throw new Exception(name); }
        checks++; lines.Add("通过：" + name);
    }
    private static void Reject(Func<string, string> read, string name)
    {
        bool rejected = false;
        try { MaintenanceGuard.AssertAbsent(read); }
        catch (IOException) { rejected = true; }
        catch (InvalidDataException) { rejected = true; }
        catch (InvalidOperationException) { rejected = true; }
        Check(rejected, name);
    }
    private static void Shell(string bash, string fixture, string root, string failure, string expected)
    {
        ProcessStartInfo start = new ProcessStartInfo(bash, "--noprofile --norc \"" + fixture.Replace('\\', '/') + "\"");
        start.UseShellExecute = false; start.CreateNoWindow = true;
        start.RedirectStandardOutput = true; start.RedirectStandardError = true;
        start.EnvironmentVariables["D31_MAINTENANCE_FAIL"] = failure;
        start.EnvironmentVariables["D31_MAINTENANCE_ROOT"] = root.Replace('\\', '/');
        start.EnvironmentVariables["D31_MAINTENANCE_COMMAND"] = MaintenanceGuard.Command.Replace("p=/data/local", "p=\"$D31_MAINTENANCE_ROOT\"");
        using (Process process = Process.Start(start))
        {
            string output = process.StandardOutput.ReadToEnd();
            string error = process.StandardError.ReadToEnd();
            if (!process.WaitForExit(10000)) { throw new Exception("宿主只读夹具未完成"); }
            if (expected == "ERROR") { Check(process.ExitCode != 0, "读取错误或权限不符关闭门：" + failure); }
            else { Check(process.ExitCode == 0 && output.Trim() == expected && error.Trim().Length == 0, "目录实物判定：" + expected); }
        }
    }
    private static int Main(string[] args)
    {
        try
        {
            Directory.CreateDirectory(args[0]);
            int calls = 0;
            MaintenanceGuard.AssertAbsent(delegate(string command) { calls++; Check(command == MaintenanceGuard.Command, "精确只读命令"); return MaintenanceGuard.Absent + "\r\n"; });
            Check(calls == 1, "只读一次，无重试或额外等待");
            Reject(delegate { return MaintenanceGuard.Present; }, "占用标记拒绝");
            Reject(delegate { return null; }, "空回执拒绝");
            Reject(delegate { return ""; }, "缺失回执拒绝");
            Reject(delegate { return "ABSENT"; }, "旧或未知协议拒绝");
            Reject(delegate { return MaintenanceGuard.Absent + "\nerror"; }, "混入错误输出拒绝");
            Reject(delegate { return MaintenanceGuard.Absent + "\n" + MaintenanceGuard.Present; }, "矛盾回执拒绝");
            Reject(delegate { throw new IOException("fixture"); }, "读取异常拒绝");
            string root = Path.Combine(args[0], "empty local"); Directory.CreateDirectory(root);
            Shell(args[1], args[2], root, "", MaintenanceGuard.Absent);
            root = Path.Combine(args[0], "partial local"); Directory.CreateDirectory(Path.Combine(root, "d31-remote"));
            Shell(args[1], args[2], root, "", MaintenanceGuard.Absent);
            Directory.CreateDirectory(Path.Combine(root, "d31-remote", "runtime"));
            Shell(args[1], args[2], root, "", MaintenanceGuard.Absent);
            string maintenance = Path.Combine(root, "d31-remote", "runtime", "maintenance"); Directory.CreateDirectory(maintenance);
            Shell(args[1], args[2], root, "", MaintenanceGuard.Absent);
            string repair = Path.Combine(maintenance, "repair.json"); File.WriteAllText(repair, "");
            Shell(args[1], args[2], root, "", MaintenanceGuard.Present);
            File.WriteAllText(repair, "invalid-json");
            Shell(args[1], args[2], root, "", MaintenanceGuard.Present);
            // This file is a newly created local fixture, never a device maintenance record.
            File.Delete(repair); Directory.CreateDirectory(repair);
            Shell(args[1], args[2], root, "", MaintenanceGuard.Present);
            foreach (string failure in new string[] { "root", "ls", "grep" }) { Shell(args[1], args[2], root, failure, "ERROR"); }
            string badParent = Path.Combine(args[0], "bad parent"); Directory.CreateDirectory(badParent);
            File.WriteAllText(Path.Combine(badParent, "d31-remote"), "not-a-directory");
            Shell(args[1], args[2], badParent, "", "ERROR");
            Check(!MaintenanceGuard.Command.Contains("rm ") && !MaintenanceGuard.Command.Contains("cat ") &&
                !MaintenanceGuard.Command.Contains("reboot") && !MaintenanceGuard.Command.Contains("sleep"), "不读取内容、不清理、不重启、不等待");
            lines.Add("合计通过：" + checks + "项；仅宿主目录夹具，未调用ADB或设备。");
            File.WriteAllLines(Path.Combine(args[0], "maintenance.txt"), lines.ToArray(), Encoding.UTF8);
            Console.WriteLine(lines[lines.Count - 1]); return 0;
        }
        catch (Exception error)
        {
            lines.Add(error.ToString()); File.WriteAllLines(Path.Combine(args[0], "maintenance.txt"), lines.ToArray(), Encoding.UTF8);
            Console.Error.WriteLine(error); return 1;
        }
    }
}
