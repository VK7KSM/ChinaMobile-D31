using System;
using System.IO;
using System.Text.RegularExpressions;
using System.Collections.Generic;
using System.Web.Script.Serialization;
using System.Security.Cryptography;

// 只返回固定测试数据，不启动任何外部进程或网络连接。
class BackendFakeAdb
{
    static int Main(string[] args)
    {
        string command = String.Join(" ", args);
        File.AppendAllText(Environment.GetEnvironmentVariable("D31_TEST_TRANSCRIPT"), command + "\n");
        string mode = Environment.GetEnvironmentVariable("D31_TEST_CASE");
        var json = new JavaScriptSerializer();
        string statePath = Environment.GetEnvironmentVariable("D31_TEST_TRANSCRIPT") + ".state";
        var state = File.Exists(statePath) ? json.Deserialize<Dictionary<string,string>>(File.ReadAllText(statePath)) : new Dictionary<string,string>();
        string root = Path.GetDirectoryName(Path.GetDirectoryName(typeof(BackendFakeAdb).Assembly.Location));
        var metadata = json.Deserialize<Dictionary<string, object>>(File.ReadAllText(Path.Combine(root, "approved-package.json")));
        string bytes = metadata["bytes"].ToString();
        string hash = metadata["sha256"].ToString();
        string remote = "/data/local/tmp/D31-factory-v" + metadata["version"] + ".zip";
        var files = json.Deserialize<Dictionary<string, object>[]>(File.ReadAllText(Path.Combine(root, "installed-files.json")));
        string result;
        if (command.Contains(" connect ")) result = "connected to 192.0.2.31:5555";
        else if (command.EndsWith(" get-state")) result = "device";
        else if (command.EndsWith(" shell id")) result = "uid=0(root) gid=0(root)";
        else if (command.EndsWith("getprop ro.build.fingerprint")) result = "alps/full_hct6737t_66_m0/hct6737t_66_m0:6.0/MRA58K/1583081804:userdebug/test-keys";
        else if (command.Contains("ip -4 addr show dev eth0")) result = "inet " + (mode == "wrong-network" ? "192.0.2.32" : "192.0.2.31") + "/24";
        else if (command.Contains("busybox 2>/dev/null")) result = "BusyBox v1.22.1";
        else if (command.Contains("blockdev --getsize64")) {
            string partition = command.Substring(command.LastIndexOf('/') + 1);
            var sizes = new Dictionary<string,string>{{"system","1610612736"},{"boot","16777216"},{"userdata","13517717504"},{"logo","8388608"}};
            result = mode == "bad-logo" && partition == "logo" ? "1" : sizes[partition];
        }
        else if (command.Contains("busybox sha256sum")) {
            string path = command.Substring(command.IndexOf("busybox sha256sum ") + 18);
            if (path.EndsWith("/recovery")) result = (mode == "bad-recovery" ? new string('0',64) : "173CB00459E4CDFC2B4BF04D7BED4A130947795F8ACB3B557BBEF2C218B2E7D5") + "  " + path;
            else if (path.EndsWith("/boot")) result = (mode == "bad-boot" ? new string('0',64) : metadata["bootSha256"].ToString()) + "  " + path;
            else if (path == remote) {
                int count = state.ContainsKey("hashCount") ? Int32.Parse(state["hashCount"]) : 0;
                state["hashCount"] = (count + 1).ToString();
                result = (mode == "bad-remote-hash" && count > 0 ? new string('0',64) : hash) + "  " + path;
            }
            else if (path == remote + ".partial") result = hash + "  " + path;
            else if (path == remote + ".chunk") result = state["chunkHash"] + "  " + path;
            else {
                var item = Array.Find(files, f => f["path"].ToString() == path);
                if (item == null) throw new Exception("未覆盖的哈希路径：" + path);
                result = (mode == "bad-installed" ? new string('0',64) : item["sha256"].ToString()) + "  " + path;
            }
        }
        else if (command.Contains("then echo YES; else echo NO")) result = "YES";
        else if (command.Contains("mount | grep")) result = "/dev/block/cache /cache ext4 rw 0 0";
        else if (command.Contains("df /data")) result = mode == "no-space" ? "1M" : "10G";
        else if (command.Contains("D31_UPLOAD_V2|")) result = "";
        else if (command.Contains("cat " + remote + ".upload")) result = "D31_UPLOAD_V2|" + hash + "|16777216";
        else if (command.Contains("cat " + remote + ".chunk >>")) {
            long offset = state.ContainsKey("offset") ? Int64.Parse(state["offset"]) : 16777216;
            state["offset"] = (offset + Int64.Parse(state["chunkBytes"])).ToString();
            result = state["offset"];
        }
        else if (command.Contains("stat -c %s " + remote + ".partial")) result = state.ContainsKey("offset") ? state["offset"] : "16777216";
        else if (command.Contains("stat -c %s " + remote + ".chunk")) result = state["chunkBytes"];
        else if (command.Contains("if [ -f " + remote + " ];")) result = mode == "upload-resume" ? "0" : bytes;
        else if (command.Contains("stat -c %s " + remote)) result = bytes;
        else if (command.Contains(" push ")) {
            string local = args[args.Length - 2];
            state["chunkBytes"] = new FileInfo(local).Length.ToString();
            using(var sha = SHA256.Create()) using(var stream = File.OpenRead(local)) state["chunkHash"] = BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "");
            result = "";
        }
        else if (command.Contains("shell rm -f " + remote) || command.Contains("shell mv " + remote + ".partial")) result = "";
        else if (command.EndsWith("shell getprop")) result = "[sys.boot_completed]: [1]";
        else if (command.Contains("shell ls -l")) result = "测试分区映射";
        else if (command.Contains("mkdir -p /cache/recovery")) result = "--update_package=" + remote;
        else if (command.EndsWith("reboot recovery")) result = "";
        else if (command.EndsWith("getprop sys.boot_completed")) result = "1";
        else if (command.Contains("shell pm path ")) {
            string pkg = command.Substring(command.LastIndexOf(' ') + 1);
            result = "package:/data/app/" + pkg + "-1/base.apk";
        }
        else if (command.Contains("for p in /system/vendor/3rd-app")) result = "";
        else if (command.Contains("/d31-startup-handover/runs/")) result = mode == "bad-handover" ? "HANDOVER_EXIT=1" : "52.933 HANDOVER_COMPLETE\nHANDOVER_EXIT=0";
        else if (command.Contains("FactoryInit --verify")) result = mode == "bad-initialization" ? "Permission mismatch" : "1.4.1\nFACTORY_STATE_VERIFIED";
        else if (command.Contains("test -S /dev/socket/d31-system-actions")) result = mode == "bad-storage-support" ? "" : "net.elfradio.d31system\n/data/local/d31-system-support/guard";
        else if (command.Contains("factory-runtime-complete")) result = mode == "bad-tcp-default" ? "" : "1.4.1\nTCP_DEFAULT_OK";
        else if (command.Contains("cat /data/starnet/launcher/config/config-tab")) {
            var apps = new List<object>();
            foreach(string pkg in new[]{"org.mozilla.firefox","org.videolan.vlc","com.loudtalks","org.telegram.messenger.web","com.android.calculator2","net.elfradio.d31bootstrap","com.android.settings","me.zhanghai.android.files"}) {
                string name = pkg == "me.zhanghai.android.files" ? (mode == "old-app-page" ? "质感文件" : "文件管理器") : pkg;
                apps.Add(new {info=new {app=new {name=name,packageName=pkg}}});
            }
            result=json.Serialize(new {tabs=new object[]{new {},new {items=apps}}});
        }
        else if (command.Contains("cat /cache/recovery/last_log")) result = "刷机完成：所有软件均为未配置状态";
        else { Console.Error.WriteLine("测试未覆盖此命令：" + command); return 98; }
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        File.WriteAllText(statePath, json.Serialize(state));
        Console.WriteLine(result);
        return 0;
    }
}
