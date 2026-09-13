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
        bool postSystem = metadata.ContainsKey("elfRemote") && (state.ContainsKey("rebooted") || mode.StartsWith("system-"));
        var system = metadata.ContainsKey("elfRemote") ? (Dictionary<string,object>)metadata["elfRemote"] : null;
        string result;
        if (command.Contains(" connect ")) result = "connected to " + args[args.Length - 1];
        else if (postSystem && command.Contains("D31_SYSTEM_RUNTIME_PRESENT_V1")) result = mode == "system-missing-health" ? "" : "D31_SYSTEM_RUNTIME_PRESENT_V1";
        else if (postSystem && command.Contains("runtime/updates/supervisor.json")) {
            result = json.Serialize(new {uid=0,pid=mode == "system-supervisor-pid" ? 999 : 202,time_ms=1789286400000L,version_code=170,maintenance_protocol=1});
        }
        else if (postSystem && command.Contains("runtime/state/health.json")) {
            result = json.Serialize(new {uid=mode == "system-health-uid" ? 2000 : 0,pid=101,time_ms=mode == "system-stale-health" ? 1789286300000L : 1789286400000L,
                version_code=mode == "system-health-version" ? 169 : 170,maintenance_protocol=1,apk_sha256=system["sha256"],local_ready=mode != "system-not-ready",instance="offline-instance"});
        }
        else if (postSystem && command.Contains("runtime/active.json")) {
            result=json.Serialize(new {path=mode == "system-active-data" ? "/data/local/tmp/remote.apk" : system["systemApk"],sha256=system["sha256"],versionCode=170});
        }
        else if (postSystem && command.Contains("runtime/state/remote.pid")) result="101";
        else if (postSystem && command.Contains("cat /proc/sys/kernel/random/boot_id") && !command.Contains("/d31-startup-handover/runs/")) {
            int reads=state.ContainsKey("bootReads") ? Int32.Parse(state["bootReads"]) : 0;
            state["bootReads"]=(reads+1).ToString();
            result=mode == "system-boot-changed" && reads > 0 ? "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb" : "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        }
        else if (postSystem && command.Contains("date +%s")) result="1789286400";
        else if (postSystem && command.Contains("busybox pidof ")) result=command.Contains("d31-remote-supervisor") ? "202" : "101";
        else if (postSystem && Regex.IsMatch(command,@"cat /proc/[0-9]+/status")) result=mode == "system-process-uid" ? "Uid:\t2000\t2000\t2000\t2000" : "Uid:\t0\t0\t0\t0";
        else if (postSystem && Regex.IsMatch(command,@"cat /proc/[0-9]+/maps")) result="1000-2000 r--p 0000 00:00 1 /data/dalvik-cache/arm64/"+
            (mode == "system-bad-map" ? "data@app@wrong.apk" : "system@priv-app@D31ElfRemote@D31ElfRemote.apk")+"@classes.dex";
        else if (command.Contains("for p in /data/local/d31-remote ")) result=mode == "system-runtime-mode" ? "755 0 0\n700 0 0\n700 0 0" : "700 0 0\n700 0 0\n700 0 0";
        else if (command.Contains("stat -c '%a %u %g'")) {
            var match=Regex.Match(command,@"stat -c '%a %u %g' '([^']+)'" );
            var item=Array.Find(files,f=>f["path"].ToString()==match.Groups[1].Value);
            result=mode == "system-file-mode" ? "777 0 0" : item["mode"].ToString().Substring(1)+" 0 0";
        }
        else if (command.Contains("ls -Z '")) result=mode == "system-file-context" ? "u:object_r:shell_data_file:s0 file" : "-rw-r--r-- root root u:object_r:system_file:s0 file";
        else if (command.EndsWith(" get-state")) result = "device";
        else if (command.EndsWith(" shell id")) result = "uid=0(root) gid=0(root)";
        else if (command.Contains("D31_MAINTENANCE_ABSENT_V1")) {
            int checks = state.ContainsKey("maintenanceChecks") ? Int32.Parse(state["maintenanceChecks"]) : 0;
            state["maintenanceChecks"] = (++checks).ToString();
            if (mode == "repair-read-failed") return 72;
            result = mode == "repair-unknown" ? "" :
                (mode == "repair-present" || (mode == "repair-late" && checks > 1)) ?
                "D31_MAINTENANCE_PRESENT_V1" : "D31_MAINTENANCE_ABSENT_V1";
        }
        else if (command.Contains("D31_LEGACY_DEPLOYMENT_ABSENT_V1")) {
            if (mode == "legacy-evidence-unreadable") return 72;
            result = mode == "full170-no-health" || mode == "full170-old-health" || mode == "legacy-active-present" || mode == "stock-active-present" ?
                "D31_MODERN_DEPLOYMENT_PRESENT_V1" : "D31_LEGACY_DEPLOYMENT_ABSENT_V1";
        }
        else if (command.Contains("pm list packages")) {
            result = mode == "stock-pm-unavailable" ? "" : mode == "stock-pm-inconsistent" ?
                "package:android\npackage:net.elfradio.d31bootstrap" : "package:android\npackage:com.android.settings";
        }
        else if (command.Contains("dumpsys package net.elfradio.d31bootstrap")) {
            result = postSystem ? "  versionCode="+(mode == "system-pm-version" ? "169" : "170")+" targetSdk=23\n  versionName="+system["versionName"] : mode == "legacy-pm-unknown" ? "" : "  versionCode=" +
                (mode == "full170-pm-no-health" ? "170" : mode == "legacy-version-mismatch" ? "94" : "95") + " targetSdk=23";
        }
        else if (command.Contains("runtime/state/health.json")) {
            result = mode == "health-invalid" ? "invalid-json" : mode == "legacy95" ? "{\"version_code\":95}" :
                mode == "full170-old-health" || mode == "legacy-version-mismatch" ? "{\"version_code\":95}" :
                mode.StartsWith("full96-") ? json.Serialize(new {version_code=96, maintenance_protocol=mode == "full96-no-protocol" ? 0 : 1}) : "{}";
        }
        else if (command.Contains("runtime/active.json")) {
            result = json.Serialize(new {path=mode == "full96-bad-path" ? "/data/local/tmp/not-approved.apk" :
                mode == "full96-system" ? "/system/priv-app/D31ElfRemote/D31ElfRemote.apk" :
                "/data/local/d31-remote/releases/" + new string('a',64) + "/remote.apk"});
        }
        else if (command.Contains("RemoteWindowsMaintenance reserve ")) {
            if (mode == "full96-reserve-failed") return 73;
            result = mode == "full96-reserve-unknown" ? "UNKNOWN" : "D31_WINDOWS_RESERVED_V1";
        }
        else if (command.Contains("RemoteWindowsMaintenance release ")) result = "D31_WINDOWS_RELEASED_V1";
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
        else if (command.Contains("mkdir -p /cache/recovery")) result = mode == "full96-command-mismatch" ? "UNKNOWN" : "--update_package=" + remote;
        else if (command.EndsWith("reboot recovery")) {
            if (mode == "full96-reboot-failed") return 74;
            state["rebooted"] = "true";
            result = "";
        }
        else if (command.EndsWith("getprop sys.boot_completed")) result = "1";
        else if (command.Contains("pm path ")) {
            string pkg = Regex.Match(command, @"pm path ([a-zA-Z0-9.]+)").Groups[1].Value;
            result = postSystem && pkg == "net.elfradio.d31bootstrap" && mode != "system-pm-data" ? "package:"+system["systemApk"] :
                !state.ContainsKey("rebooted") && (mode == "legacy-pm-absent" || mode.StartsWith("stock-")) ? "" : "package:/data/app/" + pkg + "-1/base.apk";
        }
        else if (command.Contains("for p in /system/vendor/3rd-app")) result = "";
        else if (command.Contains("/d31-startup-handover/runs/")) result = mode == "bad-handover" ? "HANDOVER_EXIT=1" : "52.933 HANDOVER_COMPLETE\nHANDOVER_EXIT=0";
        else if (command.Contains("FactoryInit --verify")) result = mode == "bad-initialization" ? "Permission mismatch" :
            (mode == "system-old-init" ? "1.4.3" : metadata["version"].ToString())+"\n"+(mode == "system-forged-init" ? "NOT_FACTORY_STATE_VERIFIED" : "FACTORY_STATE_VERIFIED");
        else if (command.Contains("test -S /dev/socket/d31-system-actions")) result = mode == "bad-storage-support" ? "" : "net.elfradio.d31system\n/data/local/d31-system-support/guard";
        else if (command.Contains("factory-runtime-complete")) result = mode == "bad-tcp-default" ? "" : metadata["version"].ToString()+"\nTCP_DEFAULT_OK";
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
        Match exitMarker = Regex.Match(command, @"D31_RECOVERY_EXIT_[a-f0-9]{32}_");
        if (exitMarker.Success) {
            if (mode == "full96-command-marker-missing" && command.Contains("mkdir -p /cache/recovery")) return 0;
            int remoteExit = mode == "full96-command-remote-failed" && command.Contains("mkdir -p /cache/recovery") ? 7 : 0;
            if (mode == "system-init-exit" && command.Contains("FactoryInit --verify")) remoteExit=9;
            Console.WriteLine();
            Console.WriteLine(exitMarker.Value + remoteExit);
        }
        return 0;
    }
}
