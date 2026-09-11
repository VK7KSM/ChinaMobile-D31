using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Forms;

internal static class BasicProbeExeTests
{
    private static readonly List<string> lines = new List<string>();
    private static int checks;
    private static Type runtime;
    private static string expectedHash;

    private static object Call(Type type, string method, params object[] args)
    {
        try { return type.GetMethod(method, BindingFlags.Static | BindingFlags.NonPublic).Invoke(null, args); }
        catch (TargetInvocationException e) { throw e.InnerException; }
    }
    private static string Hash(string file)
    {
        using (SHA256 hash = SHA256.Create())
        using (Stream input = File.OpenRead(file)) { return BitConverter.ToString(hash.ComputeHash(input)).Replace("-", "").ToLowerInvariant(); }
    }
    private static void Check(bool ok, string name)
    {
        if (!ok) { throw new Exception(name); }
        checks++; lines.Add("通过：" + name);
    }
    private static void Reject(Action action, string name)
    {
        bool rejected = false;
        try { action(); } catch (IOException) { rejected = true; } catch (InvalidDataException) { rejected = true; }
        Check(rejected, name);
    }
    private static void Layout(Control parent)
    {
        foreach (Control control in parent.Controls)
        {
            if (control is Button && control.Visible)
            {
                Check(control.Left >= 0 && control.Right <= parent.ClientSize.Width && control.Bottom <= parent.ClientSize.Height,
                    "按钮在容器内：" + control.Text);
            }
            Layout(control);
        }
    }
    private static void Snapshot(Form form, string path)
    {
        form.PerformLayout(); Application.DoEvents(); Layout(form);
        using (Bitmap bitmap = new Bitmap(form.Width, form.Height))
        {
            form.DrawToBitmap(bitmap, new Rectangle(Point.Empty, form.Size));
            bitmap.Save(path, System.Drawing.Imaging.ImageFormat.Png);
        }
    }
    [STAThread]
    private static int Main(string[] args)
    {
        string report = Path.Combine(args[1], "integration.txt");
        try
        {
            Assembly assembly = Assembly.LoadFrom(args[0]);
            expectedHash = args[2];
            runtime = assembly.GetType("D31FlashTool.RuntimeAssets", true);
            Type constants = assembly.GetType("D31FlashTool.BuildConstants", true);
            string version = (string)constants.GetField("ToolVersion", BindingFlags.Static | BindingFlags.NonPublic).GetRawConstantValue();
            Check(version.StartsWith("1.6.7-rc", StringComparison.Ordinal), "独立候选版本");
            string resource = "D31FlashTool.Runtime.BasicProbe" + args[3];
            int count = 0;
            foreach (string name in assembly.GetManifestResourceNames()) { if (name.StartsWith("D31FlashTool.Runtime.BasicProbe")) { count++; Check(name == resource, "内置基础资源版本来自合同"); } }
            Check(count == 1, "基础资源恰好一份");
            string root = (string)Call(runtime, "Prepare");
            string apk = (string)Call(runtime, "ValidateBasicProbe", root);
            Check(Path.GetFileName(apk) == args[4] && Hash(apk) == expectedHash, "实际EXE解包的文件名和摘要匹配");
            Check(!File.Exists(Path.Combine(root, "首次引导工具", "D31-wireless-adb-v1.11.6.apk")), "新候选缓存不依赖旧APK");
            Type validator = assembly.GetType("D31FlashTool.PackageValidator", true);
            using (StringWriter writer = new StringWriter()) { Call(validator, "ValidateToolRoot", root, writer); Check(writer.ToString().Contains(args[4]), "实际Program自检已核验当前基础探针"); }
            try
            {
                File.WriteAllText(apk, "corrupted-candidate-cache");
                Reject(delegate { Call(runtime, "ValidateBasicProbe", root); }, "自检拒绝缓存摘要不符");
                string export = Path.Combine(args[1], "embedded-export.apk");
                Call(runtime, "ExportBasicProbe", export);
                Check(Hash(export) == expectedHash, "导出直接使用内置原件，不信任损坏缓存");
                DateTime before = File.GetLastWriteTimeUtc(export);
                Call(runtime, "ExportBasicProbe", export);
                Check(File.GetLastWriteTimeUtc(export) == before, "已有相同APK不重写");
                File.WriteAllText(export, "do-not-overwrite");
                Reject(delegate { Call(runtime, "ExportBasicProbe", export); }, "已有不同内容拒绝覆盖");
                Check(File.ReadAllText(export) == "do-not-overwrite", "冲突目标原件保持不变");
                Reject(delegate { Call(runtime, "ExportBasicProbe", Path.Combine(args[1], "missing", "probe.apk")); }, "不存在目录明确拒绝");
            }
            finally { Call(runtime, "Prepare"); }
            Check(Hash(apk) == expectedHash, "候选缓存损坏后修复");
            Array assets = (Array)constants.GetField("RuntimeFiles", BindingFlags.Static | BindingFlags.NonPublic).GetValue(null);
            object first = assets.GetValue(0);
            object basic = Call(runtime, "BasicProbe");
            try { assets.SetValue(basic, 0); Reject(delegate { Call(runtime, "BasicProbe"); }, "重复基础资源描述符拒绝"); }
            finally { assets.SetValue(first, 0); }
            Application.EnableVisualStyles();
            Type formType = assembly.GetType("D31FlashTool.MainForm", true);
            using (Form form = (Form)Activator.CreateInstance(formType, BindingFlags.Instance | BindingFlags.NonPublic, null, new object[] { root, args[1] }, null))
            {
                // Skip only the existing Shown ADB-server initialization; no device API is called.
                formType.GetField("adbPrepared", BindingFlags.Instance | BindingFlags.NonPublic).SetValue(form, true);
                ((TextBox)formType.GetField("ipInput", BindingFlags.Instance | BindingFlags.NonPublic).GetValue(form)).Text = "192.0.2.31";
                form.ShowInTaskbar = false; form.Show(); Application.DoEvents();
                Check((bool)formType.GetField("toolReady", BindingFlags.Instance | BindingFlags.NonPublic).GetValue(form), "实际MainForm工具包预检通过");
                string guiExport = Path.Combine(args[1], "gui-export.apk");
                formType.GetMethod("ExportBasicProbeTo", BindingFlags.Instance | BindingFlags.NonPublic).Invoke(form, new object[] { guiExport });
                Check(Hash(guiExport) == expectedHash, "实际MainForm导出路径输出正确APK");
                Snapshot(form, Path.Combine(args[1], "main-default.png"));
                form.Size = form.MinimumSize;
                Snapshot(form, Path.Combine(args[1], "main-minimum.png"));
                form.Close();
            }
            Check(Directory.GetFiles(args[1], "*.tmp-*").Length == 0, "导出没有残留半成品");
            lines.Add("合计通过：" + checks + "项；使用实际候选EXE，未调用真实ADB，未连接设备。");
            File.WriteAllLines(report, lines.ToArray(), Encoding.UTF8);
            Console.WriteLine(lines[lines.Count - 1]); return 0;
        }
        catch (Exception error)
        {
            lines.Add(error.ToString()); File.WriteAllLines(report, lines.ToArray(), Encoding.UTF8);
            Console.Error.WriteLine(error); return 1;
        }
    }
}
