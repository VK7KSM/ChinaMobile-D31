using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using D31FlashTool;

internal static class FlashWorkflowTests
{
    static readonly List<string> results = new List<string>();
    const BindingFlags Private = BindingFlags.Instance | BindingFlags.NonPublic;
    static object Get(MainForm f, string name) { return typeof(MainForm).GetField(name, Private).GetValue(f); }
    static void Set(MainForm f, string name, object value) { typeof(MainForm).GetField(name, Private).SetValue(f, value); }
    static object Call(MainForm f, string name, params object[] args)
    {
        // 离线测试没有Application.Run，异步入口显式使用窗体消息队列。
        if (name.StartsWith("Start")) SynchronizationContext.SetSynchronizationContext(new WindowsFormsSynchronizationContext());
        return typeof(MainForm).GetMethod(name, Private).Invoke(f, args);
    }
    static void Check(bool ok, string name) { if (!ok) throw new Exception(name); results.Add("通过：" + name); }
    static CheckBox Erase(MainForm f) { return (CheckBox)Get(f, "eraseCheck"); }
    static Button Flash(MainForm f) { return (Button)Get(f, "flashButton"); }
    static void Wait(MainForm f)
    {
        var timer = Stopwatch.StartNew();
        while ((bool)Call(f, "IsBusy"))
        {
            if (Flash(f).Enabled || Erase(f).Enabled) throw new Exception("执行期间确认框或刷机按钮被错误开放");
            Application.DoEvents(); Thread.Sleep(10);
            if (timer.ElapsedMilliseconds > 30000) throw new Exception("流程超时：" + Get(f, "currentOperation"));
        }
        Application.DoEvents();
    }
    [STAThread]
    static int Main(string[] args)
    {
        // 此测试程序作为隔离目录的ADB替身，只允许维护状态读取。
        if (args.Length > 0 && args[0] == "-P")
        {
            if (args.Length != 6 || args[1] != "5042" || args[2] != "-s" || args[3] != "192.0.2.31:5654" ||
                args[4] != "shell" || !args[5].Contains("D31_MAINTENANCE_ABSENT_V1")) return 91;
            string mode = File.ReadAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "mode.txt")).Trim();
            Console.WriteLine(mode == "maintenance-fail" ? MaintenanceGuard.Present : MaintenanceGuard.Absent);
            return 0;
        }
        string report = Path.Combine(args[0], "结果.txt");
        try
        {
            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.ThrowException);
            Application.EnableVisualStyles();
            string[] cases = {"backup", "skip", "preflight-fail", "no-marker", "backup-fail", "backup-no-marker", "readonly", "maintenance-fail", "missing-script", "backup-directory-fail"};
            foreach (string mode in cases)
            {
                string root = Path.Combine(args[0], mode);
                Directory.CreateDirectory(Path.Combine(root, "tools"));
                File.Copy(typeof(FlashWorkflowTests).Assembly.Location, Path.Combine(root, "tools", "adb.exe"));
                File.WriteAllText(Path.Combine(root, "mode.txt"), mode);
                if (mode != "missing-script") File.WriteAllText(Path.Combine(root, "flash_d31_recovery.ps1"), File.ReadAllText(args[1]), new UTF8Encoding(true));
                File.WriteAllText(Path.Combine(root, "create_d31_rescue.ps1"), File.ReadAllText(args[1]), new UTF8Encoding(true));
                using (var f = new MainForm(root, root))
                {
                    IntPtr handle = f.Handle;
                    Set(f, "toolReady", true);
                    Set(f, "device", new DeviceInfo { Model="hct6737t_66_m0", Serial="192.0.2.31:5654", AdbPort=5042, TargetAddressIsEthernet=true });
                    Set(f, "firmware", new FirmwareSelection { PackagePath=Path.Combine(root, "离线固件.zip") });
                    Call(f, "UpdateControls");
                    Check(Erase(f).Enabled && !Flash(f).Enabled, mode + "：无需手动预检即可确认，但未确认时不能开始");
                    Erase(f).Checked=true;
                    Check(Flash(f).Enabled, mode + "：勾选后开始可用");
                    ((CheckBox)Get(f, "backupCheck")).Checked = mode != "skip";
                    Check(Flash(f).Enabled, mode + "：备份选择不阻塞开始");
                    if (mode == "readonly")
                    {
                        Set(f,"adbPrepared",true);
                        f.ShowInTaskbar=false;
                        f.StartPosition=FormStartPosition.Manual;
                        f.Location=new System.Drawing.Point(-3000,-3000);
                        f.Show(); Application.DoEvents();
                        using (var bitmap = new System.Drawing.Bitmap(f.Width, f.Height))
                        {
                            f.DrawToBitmap(bitmap, new System.Drawing.Rectangle(System.Drawing.Point.Empty, f.Size));
                            bitmap.Save(Path.Combine(args[0],"可直接开始.png"),System.Drawing.Imaging.ImageFormat.Png);
                        }
                        f.Hide();
                    }
                    if (mode == "backup-directory-fail") File.WriteAllText(Path.Combine(root,"D31备份"), "阻塞目录创建");
                    if (mode == "no-marker") Set(f, "preflightPassed", true);
                    if (mode == "readonly") Call(f, "StartPreflight", false);
                    else Call(f, "StartConfirmedFlash");
                    if (mode == "backup")
                    {
                        var timer=Stopwatch.StartNew();
                        while (Get(f,"runningProcess") == null && (bool)Call(f,"IsBusy"))
                        {
                            Application.DoEvents(); Thread.Sleep(10);
                            if(timer.ElapsedMilliseconds>30000) throw new Exception("等待后端启动超时");
                        }
                        object active=Get(f,"runningProcess");
                        using(var stale=new Process())
                        {
                            Call(f,"ProcessFinished",stale,0);
                            Check(active != null && Object.ReferenceEquals(active,Get(f,"runningProcess")) && (bool)Call(f,"IsBusy"), "新进程运行时旧回调不能解除忙碌");
                            Check(!(bool)Call(f,"ReadBackendOutput",stale,false), "日志流不可读时返回失败而不抛出界面异常");
                        }
                    }
                    Wait(f);
                    string tracePath = Path.Combine(root, "trace.txt");
                    string trace = File.Exists(tracePath) ? File.ReadAllText(tracePath) : "";
                    string expected = mode == "backup" ? "检查\n备份\n刷机有备份\n" : mode == "skip" ? "检查\n刷机无备份\n" :
                        mode.StartsWith("backup-") && mode != "backup-directory-fail" ? "检查\n备份\n" :
                        mode == "maintenance-fail" || mode == "missing-script" ? "" : "检查\n";
                    Check(trace == expected, mode + "：实际后端调用次序正确，失败不会继续");
                    Check(!(bool)Get(f, "flashAfterPreflight") && !(bool)Get(f, "flashAfterBackup"), mode + "：没有残留自动继续意图");
                    if (mode == "preflight-fail" || mode == "no-marker" || mode == "backup-fail")
                    {
                        File.WriteAllText(Path.Combine(root, "mode.txt"), "readonly");
                        Call(f, "StartPreflight", false); Wait(f);
                        File.WriteAllText(Path.Combine(root,"重查日志.txt"), ((TextBox)Get(f,"log")).Text, new UTF8Encoding(true));
                        Check(File.ReadAllText(tracePath) == trace + "检查\n", mode + "：失败后单独检查不触发刷机；busy=" + Call(f,"IsBusy") + "，operation=" + Get(f,"currentOperation") + "，status=" + ((Label)Get(f,"statusLabel")).Text);
                    }
                    if (mode == "readonly")
                    {
                        using (var stale = new Process())
                        {
                            Call(f,"ProcessFinished",stale,0);
                            Check(Get(f,"device") != null && (bool)Get(f,"preflightPassed"), "旧进程回调不能干扰当前状态");
                        }
                        var device = (DeviceInfo)Get(f, "device");
                        device.TargetAddressIsEthernet=false; Call(f,"UpdateControls");
                        Check(!Erase(f).Enabled && !Flash(f).Enabled, "WiFi目标不能刷机");
                        device.TargetAddressIsEthernet=true; Set(f,"firmware",null); Call(f,"UpdateControls");
                        Check(!Erase(f).Enabled && !Flash(f).Enabled, "缺少固件不能刷机");
                        Call(f,"ResetDevice"); Call(f,"UpdateControls");
                        Check(!Erase(f).Enabled && !Flash(f).Enabled, "目标断开不能刷机");
                    }
                    File.WriteAllText(Path.Combine(root,"界面日志.txt"), ((TextBox)Get(f,"log")).Text, new UTF8Encoding(true));
                }
            }
            File.WriteAllLines(report, results, new UTF8Encoding(true));
            Console.WriteLine("一键流程离线验收通过：" + results.Count + "项；没有连接设备。"); return 0;
        }
        catch (Exception ex) { results.Add(ex.ToString()); File.WriteAllLines(report,results,new UTF8Encoding(true)); Console.WriteLine(ex); return 1; }
    }
}
