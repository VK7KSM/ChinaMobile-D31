// 离线宿主测试替身：不含网络或ADB实现；只记录参数并操作显式隔离目录。
using System;
using System.IO;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;
using System.Collections.Generic;

class SystemMigrationHostFixture {
    static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
    static string Root, Stage, Remote;
    static string Map(string path) {
        if (!path.StartsWith(Remote + "/",StringComparison.Ordinal)) throw new Exception("模型路径越界");
        string relative=path.Substring(Remote.Length+1);
        if (relative.Contains("..") || relative.Contains("\\")) throw new Exception("模型路径越界");
        return Path.Combine(Root,"device",relative.Replace('/',Path.DirectorySeparatorChar));
    }
    static string Hash(string path) {
        using (var stream=File.OpenRead(path)) using(var hash=SHA256.Create())
            return BitConverter.ToString(hash.ComputeHash(stream)).Replace("-","").ToLowerInvariant();
    }
    static void CopyTree(string source,string target) {
        Directory.CreateDirectory(target);
        foreach(string f in Directory.GetFiles(source))File.Copy(f,Path.Combine(target,Path.GetFileName(f)),false);
        foreach(string d in Directory.GetDirectories(source))CopyTree(d,Path.Combine(target,Path.GetFileName(d)));
    }
    static int Main(string[] args) {
        try {
            Root=Environment.GetEnvironmentVariable("D31_HOST_TEST_ROOT");
            if (String.IsNullOrEmpty(Root))throw new Exception("必须设置隔离目录");
            File.AppendAllText(Path.Combine(Root,"calls.jsonl"),Json.Serialize(args)+"\n");
            if(args.Length>0 && args[0]=="start-stop-daemon") {
                // Windows仅模拟原生派生；真实生产hook由bash执行，不冒称Android启动验收。
                if(args.Length!=5 || args[1]!="-S" || args[2]!="-b" || args[3]!="-x")throw new Exception("启动段未显式派生唯一脚本");
                var p=new ProcessStartInfo(Environment.GetEnvironmentVariable("D31_HOST_TEST_SHELL"),"\""+args[4]+"\"");
                p.UseShellExecute=false;p.CreateNoWindow=true;Process.Start(p);return 0;
            }
            Stage=Environment.GetEnvironmentVariable("D31_HOST_TEST_STAGE");
            var index=Json.Deserialize<Dictionary<string,object>>(File.ReadAllText(Path.Combine(Stage,"stage.json")));
            Remote=(string)index["remoteStage"];
            if(args.Length<5 || args[0]!="-P" || args[1]!="5042" || args[2]!="-s" || args[3]!="192.0.2.10:5555")throw new Exception("设备定向参数不符");
            if(args[4]=="get-state") { Console.WriteLine("device");return 0; }
            if(args[4]=="push" && args.Length==7) { File.Copy(args[5],Map(args[6]),false);return 0; }
            if(args[4]=="pull" && args.Length==7 && args[5]==Remote) { CopyTree(Path.Combine(Root,"device"),args[6]);return 0; }
            if(args[4]!="shell" || args.Length!=6)throw new Exception("不支持的模型命令");
            string wrapped=args[5], mode=Environment.GetEnvironmentVariable("D31_HOST_TEST_MODE") ?? "";
            string separator="\n)\nd31_migration_exit=$?\n";
            int end=wrapped.LastIndexOf(separator,StringComparison.Ordinal);
            Match nonce=Regex.Match(wrapped,"D31_DEVICE_EXIT_([a-f0-9]{32})_%s");
            if(!wrapped.StartsWith("(\n",StringComparison.Ordinal) || end<2 || !nonce.Success)throw new Exception("远端命令未包装唯一退出标记");
            string cmd=wrapped.Substring(2,end-2);
            TextWriter originalOutput=Console.Out;
            StringWriter crcrlfOutput=mode.StartsWith("crcrlf",StringComparison.Ordinal)?new StringWriter():null;
            if(crcrlfOutput!=null)Console.SetOut(crcrlfOutput);
            int remoteCode;
            if(mode=="host0-remote-failure" || mode=="crcrlf-remote-failure") {Console.Write("remote rejected without newline");remoteCode=7;}
            else remoteCode=DeviceCommand(cmd,index,mode);
            string sentinel="\nD31_DEVICE_EXIT_"+nonce.Groups[1].Value+"_"+remoteCode+"\n";
            if(mode!="no-sentinel" && mode!="crcrlf-missing")Console.Write(sentinel);
            if(mode=="crcrlf-duplicate")Console.Write(sentinel);
            if(crcrlfOutput!=null) {
                Console.SetOut(originalOutput);
                Console.Write(crcrlfOutput.ToString().Replace("\r\n","\n").Replace("\n","\r\r\n"));
                crcrlfOutput.Dispose();
            }
            // 模拟旧ADB：远端失败也返回宿主0，必须依靠调用标记判断。
            return 0;
        }catch(Exception error){Console.Error.WriteLine(error.Message);return 90;}
    }
    static int DeviceCommand(string cmd,Dictionary<string,object> index,string mode) {
            if(cmd.Contains("D31_HOST_TARGET_READY_V1")) {Console.Write("D31_HOST_TARGET_READY_V1");if(mode.StartsWith("crcrlf",StringComparison.Ordinal))Console.Write("\n");return 0;}
            if(cmd.Contains("start-stop-daemon --help")) {Console.Write(mode=="no-fork" ? "-x 匹配" : "-b 后台派生\n-x 匹配唯一程序");return 0;}
            if(cmd.StartsWith("ls -ldZ",StringComparison.Ordinal)) {Console.WriteLine("离线模型属性及PM输出");return 0;}
            if(cmd.StartsWith("umask 077;",StringComparison.Ordinal)) {
                string d=Path.Combine(Root,"device");if(Directory.Exists(d))return 75;Directory.CreateDirectory(d);return 0;
            }
            if(cmd.Contains("printf '%s\\n'")) {File.WriteAllText(Map(Remote+"/stage-manifest.sha256"),Hash(Path.Combine(Stage,"stage.json")));return 0;}
            if(cmd.Contains("sha256sum -c")) {
                foreach(Match m in Regex.Matches(cmd,"echo \"([a-f0-9]{64})  ([^\"]+)\"")) {
                    string path=m.Groups[2].Value;
                    string actual=Hash(path=="/system/bin/install-recovery.sh" ? Path.Combine(Stage,"original-hook.sh") : Map(path));
                    if(actual!=m.Groups[1].Value)return 74;
                }
                return 0;
            }
            if(cmd.StartsWith("sh ",StringComparison.Ordinal)) {
                string[] a=cmd.Split(' ');bool deploy=a[1]==Remote+"/install-remote-system.sh";
                int start=deploy?2:3;
                if(a.Length!=start+4 || a[start]!=Remote || a[start+1]!=(string)index["fullSha256"] ||
                    a[start+2]!=(string)index["originalHookSha256"] || a[start+3]!=(string)index["checkerSha256"])throw new Exception("四参数调用不匹配");
                string status=deploy?"SYSTEM_COMPONENT_STAGED_FINALIZE_REQUIRED":a[2]=="finalize"?
                    "INSTALLED_SYSTEM_IDENTITY_AND_CORE_MAPPING_VERIFIED":"RESTORED_APK_AND_SETTINGS_RUNTIME_NOT_VERIFIED";
                if(mode=="fail-finalize" && !deploy && a[2]=="finalize") {File.WriteAllText(Map(Remote+"/status"),"SYSTEM_IDENTITY_NOT_VERIFIED");return 1;}
                File.WriteAllText(Map(Remote+"/status"),status);
                Directory.CreateDirectory(Map(Remote+"/backup"));
                File.WriteAllText(Map(Remote+"/backup/model-only.txt"),"此处仅模拟证据回收，不代表Android迁移");
                Console.WriteLine(status);return mode=="stale-success"?1:0;
            }
            if(cmd.Contains("/status")) {Console.WriteLine(File.ReadAllText(Map(Remote+"/status")));return 0;}
            if(cmd.Contains("busybox find"))return 0;
            throw new Exception("模型未识别命令");
    }
}
