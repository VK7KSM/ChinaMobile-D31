using System;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using D31FlashTool;

internal static class RescueTests
{
    private static int checks;
    private static void Check(bool value) { if (!value) throw new Exception("检查失败：" + (checks + 1)); checks++; }
    private static void Reject(Action action) { bool rejected = false; try { action(); } catch (ArgumentException) { rejected = true; } Check(rejected); }
    private static int Main(string[] args)
    {
        try
        {
            var source = UptoolClient.Mac("02:00:00:00:00:01");
            var target = UptoolClient.Mac("02:00:00:00:00:02");
            var query = UptoolClient.Frame(source, target, 0x12345678, false);
            Check(query.Length == 60);
            Check(query.Take(6).SequenceEqual(target));
            Check(query[18] == 1 && query[19] == 1);
            Check(query.Skip(20).Take(4).SequenceEqual(new byte[] { 0x12, 0x34, 0x56, 0x78 }));
            Check(query[32] == 0 && query[33] == 0);
            using (var md5 = MD5.Create()) Check(md5.ComputeHash(query.Skip(18).Take(16).ToArray()).Take(4).SequenceEqual(query.Skip(14).Take(4)));
            var restore = UptoolClient.Frame(source, target, 3, true);
            Check(restore[18] == 3 && restore[19] == 1 && restore[34] == 1);
            Check(Encoding.ASCII.GetString(restore, 37, RescueClient.StartAdb.Length) == RescueClient.StartAdb);
            Check(restore[restore.Length - 1] == 0 && restore[36] == RescueClient.StartAdb.Length);
            Check(RescueClient.StartAdb.Length < 200 && !RescueClient.StartAdb.Contains(";"));
            Reject(() => UptoolClient.Mac("ff:ff:ff:ff:ff:ff"));
            Reject(() => UptoolClient.Mac("00:00:00:00:00:00"));
            Reject(() => UptoolClient.Frame(source, source, 1, false));
            Reject(() => RescueClient.ValidateHost("8.8.8.8"));
            Reject(() => RescueClient.ValidateHost("::1"));
            Check(RescueClient.ValidateHost("192.168.1.1") == "192.168.1.1");
            Check(Enumerable.Range(0, 1000).Select(i => UptoolClient.Session()).Distinct().Count() == 1000);
            Check(Enumerable.Range(0, 1000).Select(i => UptoolClient.Session()).All(v => v > 0 && v <= Int32.MaxValue));
            Reject(() => UptoolClient.Frame(source, target, 0x80000000u, true));
            Reject(() => UptoolClient.Frame(source, target, 0, true));
            Check(!UptoolClient.ValidResponse(query, source, target));
            byte[] response = new byte[1482];
            Array.Copy(source, 0, response, 0, 6); Array.Copy(target, 0, response, 6, 6);
            response[12] = 0x99; response[13] = 0x74; response[18] = 1; response[19] = 2;
            response[32] = 5; response[33] = 168;
            using (var md5 = MD5.Create()) Array.Copy(md5.ComputeHash(response.Skip(18).ToArray()), 0, response, 14, 4);
            Check(UptoolClient.ValidResponse(response, source, target));
            response[100] ^= 1;
            Check(!UptoolClient.ValidResponse(response, source, target));
            Check(!UptoolClient.ValidResponse(new byte[10], source, target));
            File.WriteAllText(args[0], "通过：" + checks + "项急救协议离线检查。未连接设备。", Encoding.UTF8);
            return 0;
        }
        catch (Exception ex) { File.WriteAllText(args[0], ex.ToString()); return 1; }
    }
}
