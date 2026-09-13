using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Web.Script.Serialization;
using D31FlashTool;

internal static class AdbPortTests
{
    private static int checks;
    private static readonly List<string> results = new List<string>();
    private static void Check(bool value, string name)
    {
        if (!value) throw new Exception("检查失败：" + name);
        checks++; results.Add("通过：" + name);
    }
    private static void Reject(Action action, string name)
    {
        bool rejected = false;
        try { action(); } catch (IOException) { rejected = true; } catch (InvalidOperationException) { rejected = true; }
        Check(rejected, name);
    }
    private static Dictionary<string, object> Receipt(string output)
    {
        return new Dictionary<string, object> { { "state", "completed" }, { "exit_code", 0 }, { "output", output } };
    }
    private static string Snapshot(string service, string persistent, string state)
    {
        return new JavaScriptSerializer().Serialize(Receipt("D31_ADB_PORT_V1\n" + service + "\n" + persistent + "\n" + state + "\nD31_ADB_PORT_END\n"));
    }
    private static void Shell(string shell, string fixture, string service, string persistent, string state, int expected, string failure, string command = null)
    {
        var info = new ProcessStartInfo(shell, DeviceDetector.Quote(fixture.Replace('\\', '/')))
        {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true
        };
        info.EnvironmentVariables["D31_TEST_COMMAND"] = command ?? RescueClient.StartAdb;
        info.EnvironmentVariables["PATH"] = Path.GetDirectoryName(shell) + ";" + info.EnvironmentVariables["PATH"];
        info.EnvironmentVariables["D31_TEST_SERVICE"] = service;
        info.EnvironmentVariables["D31_TEST_PERSIST"] = persistent;
        info.EnvironmentVariables["D31_TEST_STATE"] = state;
        info.EnvironmentVariables["D31_TEST_FAIL"] = failure;
        using (var process = Process.Start(info))
        {
            string output = process.StandardOutput.ReadToEnd(), error = process.StandardError.ReadToEnd();
            if (!process.WaitForExit(10000)) { process.Kill(); throw new Exception("离线shell替身超时"); }
            string label = "脚本运行值=" + service + "，持久值=" + persistent + "，初态=" + state + "，故障=" + failure;
            if (failure.Length == 0)
            {
                Check(process.ExitCode == 0 && error.Length == 0, label + " 正常退出；退出码=" + process.ExitCode + "；错误=" + error + "；输出=" + output);
                Check(output.Contains("PORT=" + expected + "\n") && output.Contains("STATE=running"), label + " 选定并启动");
                Check(output.Contains("STOP=adbd\nWAIT=1\nSTART=adbd\n"), label + " 停止后等待一秒再启动");
                Check(output == "PORT=" + expected + "\nSTOP=adbd\nWAIT=1\nSTART=adbd\nPERSIST=" + persistent + "\nSTATE=running\n",
                    label + " 仅产生预期调用与原值回读，异常属性不能外插到命令");
            }
            else if (failure == "missing_wait" || failure == "reordered_wait" || failure == "sleep")
            {
                Check(process.ExitCode != 0 && !output.Contains("START="), label + " 时序错误或等待失败不能启动");
                if (failure == "missing_wait") Check(output.Contains("EARLY_START_REJECTED"), "删除等待的反例命中异步停止风险");
            }
            else Check(process.ExitCode != 0 && !output.Contains("START=") && !output.Contains("STOP="), label + " 失败不继续操作");
            Check(output.Contains("PERSIST=" + persistent + "\n"), label + " 持久值不变");
        }
    }
    private static int Main(string[] args)
    {
        try
        {
            string[,] cases = { { "5654", "5555", "5654" }, { "", "5555", "5555" }, { "-1", "5654", "5654" },
                { "65536", "5654", "5654" }, { "0", "", "5555" }, { "abc", "bad", "5555" },
                { "65535", "5555", "65535" }, { "1", "", "1" }, { "05654", "5555", "5654" },
                { "+5654", "5555", "5555" }, { "5e3", "5654", "5654" }, { "999999999999999999", "", "5555" },
                { "5654;printf INJECTED", "5555", "5555" }, { "$(printf INJECTED)", "5654", "5654" },
                { "*", "`printf INJECTED`", "5555" }, { "5654 5555", "5555", "5555" } };
            for (int i = 0; i < cases.GetLength(0); i++)
            {
                int port = Int32.Parse(cases[i, 2]);
                Check(RescueClient.SelectPort(cases[i, 0], cases[i, 1]) == port, "端口选择案例" + i);
                Check(RescueClient.PortFromReceipt(Snapshot(cases[i, 0], cases[i, 1], "stopped")) == port, "旧exec合同快照案例" + i);
                Shell(args[1], args[2], cases[i, 0], cases[i, 1], i == 0 ? "running" : "stopped", port, "");
            }
            Shell(args[1], args[2], "5654", "5555", "running", 5654, "setprop");
            Shell(args[1], args[2], "5654", "5555", "stopped", 5654, "awk");
            Shell(args[1], args[2], "5654", "5555", "running", 5654, "missing_wait", RescueClient.StartAdb.Replace("&&sleep 1", ""));
            Shell(args[1], args[2], "5654", "5555", "running", 5654, "reordered_wait", RescueClient.StartAdb.Replace("&&stop adbd&&sleep 1", "&&sleep 1&&stop adbd"));
            Shell(args[1], args[2], "5654", "5555", "running", 5654, "sleep");
            Check(Encoding.ASCII.GetByteCount(RescueClient.StartAdb) == 199 && RescueClient.StartAdb.All(c => c < 128) &&
                !RescueClient.StartAdb.Contains(";"), "uptool单槽为199字节ASCII且无分号");
            Reject(() => RescueClient.PortFromReceipt("{\"state\":\"running\"}"), "运行中回执不能确认端口");
            Reject(() => RescueClient.PortFromReceipt(new JavaScriptSerializer().Serialize(Receipt("5654\n"))), "旧结果未含快照不能冒充成功");
            var truncated = Receipt("D31_ADB_PORT_V1\n5654\n5555\nrunning\nD31_ADB_PORT_END\n");
            truncated["truncated"] = true;
            Reject(() => RescueClient.PortFromReceipt(new JavaScriptSerializer().Serialize(truncated)), "截断回执不能确认端口");

            int posts = 0, queries = 0; string id = null;
            string recovered = RescueClient.ExecuteCore(RescueClient.RestoreAdb, (path, body) =>
            {
                if (path == "/exec")
                {
                    posts++; id = Convert.ToString(body.GetType().GetProperty("id").GetValue(body, null));
                    throw new WebException("替身模拟丢失POST回执");
                }
                queries++; Check(path == "/jobs/" + id, "丢回执只查询原任务"); return Receipt("已完成");
            }, () => { throw new Exception("不应等待"); });
            Check(posts == 1 && queries == 1 && recovered.Contains("completed"), "恢复丢失回执不重放");
            posts = 0; queries = 0;
            Reject(() => RescueClient.ExecuteCore(RescueClient.RestoreAdb, (path, body) =>
            {
                if (path == "/exec") { posts++; throw new WebException("模拟超时"); }
                queries++; return null;
            }, () => { throw new Exception("不应等待"); }), "原任务未知时失败关闭");
            Check(posts == 1 && queries == 1, "未知回执不切通道重放");
            posts = 0; queries = 0;
            Reject(() => RescueClient.ExecuteCore(RescueClient.RestoreAdb, (path, body) =>
            {
                if (path == "/exec") { posts++; return new Dictionary<string, object> { { "state", "running" } }; }
                queries++; throw new WebException("查询断线");
            }, () => { }), "执行中查询断线保留未知结果");
            Check(posts == 1 && queries == 1, "查询断线后不重发或重试");

            Func<string, int> unavailable = ip => { throw new IOException("旧设备无8765"); };
            Check(DeviceDetector.ResolvePort("192.168.1.31", ip => 5654, () => { throw new Exception("不得查询其他候选"); }) == 5654, "可读配置优先且不增加候选查询");
            Check(DeviceDetector.ResolvePort("192.168.1.31", unavailable, () => "192.168.1.31:5654 offline\n192.168.1.32:5555 device\n") == 5654, "旧设备复用目标已有端口，忽略其他设备");
            Check(DeviceDetector.ResolvePort("192.168.1.31", unavailable, () => "List of devices attached\n") == 5555, "未知旧设备仅以5555作握手候选");
            Check(DeviceDetector.ResolvePort("192.168.1.31:5654", ip => { throw new Exception("不应调用"); }, () => { throw new Exception("不应调用"); }) == 5654, "显式端口不增加网络发现等待");
            Reject(() => DeviceDetector.ResolvePort("192.168.1.31", unavailable, () => "192.168.1.31:5654 device\n192.168.1.31:5555 offline\n"), "同IP多端口记录不猜测");
            Reject(() => DeviceDetector.NormalizeAddress("192.168.1.31:0"), "非法显式端口拒绝");
            Reject(() => DeviceDetector.NormalizeAddress("192.168.1.31:5555:5654"), "多重端口拒绝");
            DeviceDetector.ValidateSerial("192.168.1.31:5654", "192.168.1.31"); checks++;
            Reject(() => DeviceDetector.ValidateSerial("192.168.1.31:5654", "192.168.1.31:5555"), "显式目标必须一致");
            Reject(() => DeviceDetector.ValidateSerial("192.168.1.32:5654", "192.168.1.31"), "不能检查其他设备");

            var commands = new List<string>();
            string serial = DeviceDetector.ConnectCore("192.168.1.31", command =>
            { commands.Add(command); return command.EndsWith("get-state") ? "device\n" : "connected"; }, ip => 5654);
            Check(serial == "192.168.1.31:5654" && commands.Count == 3, "定向清理后连接正确端口并验证get-state，无重试");
            Check(commands[0] == "-P 5042 disconnect " + DeviceDetector.Quote(serial), "陈旧连接仅清理所选完整端点");
            Check(commands.All(c => c.StartsWith("-P 5042 ") && !c.Contains("5555") && !c.Contains("kill-server")) &&
                commands.Count(c => c.Contains("disconnect")) == 1, "仅用5042且不重置服务器或其他连接");
            Check(DeviceDetector.ConnectCore("192.168.1.31:5654", c =>
            {
                if (c.Contains("disconnect")) throw new IOException("无陈旧连接");
                return c.EndsWith("get-state") ? "device" : "connected";
            }, unavailable) == "192.168.1.31:5654", "目标清理失败仍按原流程尝试握手");
            Reject(() => DeviceDetector.ConnectCore("192.168.1.31:5654", c => c.EndsWith("get-state") ? "offline" : "connected", unavailable), "connect文字成功不能代替握手");
            Reject(() => DeviceDetector.ConnectCore("192.168.1.31", c => { throw new IOException("握手拒绝"); }, ip => 5654), "属性有效但握手失败不得通过");
            results.Add("合计通过：" + checks + "项。未连接设备，未调用真实ADB或Npcap。");
            File.WriteAllLines(args[0], results.ToArray(), Encoding.UTF8);
            Console.WriteLine(results.Last()); return 0;
        }
        catch (Exception ex)
        {
            results.Add(ex.ToString()); File.WriteAllLines(args[0], results.ToArray(), Encoding.UTF8);
            Console.Error.WriteLine(ex); return 1;
        }
    }
}
