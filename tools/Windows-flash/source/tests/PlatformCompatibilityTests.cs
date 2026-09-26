using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Web.Script.Serialization;
using D31FlashTool;

internal static class PlatformCompatibilityTests
{
    private static int Main(string[] args)
    {
        if (args.Length != 1) return 2;
        var results = new List<object>();
        bool passed = true;
        string[][] cases = {
            new[] { "允许旧设备名", "hct6737t_66_m0", "hct6735_66_m0", "通过" },
            new[] { "允许同型号设备名", "hct6737t_66_m0", "hct6737t_66_m0", "通过" },
            new[] { "拒绝其他型号", "other", "hct6735_66_m0", "拒绝" },
            new[] { "拒绝其他设备名", "hct6737t_66_m0", "other", "拒绝" },
            new[] { "拒绝空型号", "", "hct6735_66_m0", "拒绝" },
            new[] { "拒绝空设备名", "hct6737t_66_m0", "", "拒绝" },
            new[] { "拒绝缺失型号", null, "hct6735_66_m0", "拒绝" },
            new[] { "拒绝缺失设备名", "hct6737t_66_m0", null, "拒绝" },
            new[] { "拒绝型号大小写变化", "HCT6737T_66_M0", "hct6735_66_m0", "拒绝" },
            new[] { "拒绝设备名大小写变化", "hct6737t_66_m0", "HCT6735_66_M0", "拒绝" },
            new[] { "拒绝型号空白", " ", "hct6735_66_m0", "拒绝" },
            new[] { "拒绝设备名空白", "hct6737t_66_m0", " ", "拒绝" }
        };
        foreach (var item in cases)
        {
            string error = null;
            bool rejected = false;
            bool unexpected = false;
            try { PackageValidator.ValidateDevicePlatform(item[1], item[2]); }
            catch (InvalidOperationException ex) { rejected = true; error = ex.Message; }
            catch (Exception ex) { unexpected = true; error = ex.ToString(); }
            bool ok = !unexpected && rejected == (item[3] == "拒绝") &&
                (!rejected || error.Contains("产品平台不受支持"));
            passed &= ok;
            results.Add(new { name = item[0], passed = ok, rejected = rejected, error = error });
        }
        File.WriteAllText(args[0], new JavaScriptSerializer().Serialize(new {
            passed = passed, checks = results.Count, cases = results,
            note = "直接调用生产PackageValidator.ValidateDevicePlatform；未启动生产入口或设备连接。"
        }), new UTF8Encoding(true));
        Console.WriteLine("平台方法离线测试：" + (passed ? "通过" : "失败") + "，共" + results.Count + "项");
        return passed ? 0 : 1;
    }
}
