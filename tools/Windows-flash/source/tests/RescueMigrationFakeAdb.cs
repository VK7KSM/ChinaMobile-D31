using System;
using System.IO;
using System.IO.Compression;
using System.Text.RegularExpressions;
using System.Security.Cryptography;

// 所有分区、压缩和传输只在测试目录内模拟，不启动进程、不连接网络。
class RescueMigrationFakeAdb {
    static string Hash(string path) {
        using(var h=SHA256.Create()) using(var f=File.OpenRead(path)) return BitConverter.ToString(h.ComputeHash(f)).Replace("-", "");
    }
    static int Main(string[] args) {
        string transcript=Environment.GetEnvironmentVariable("D31_RESCUE_TRANSCRIPT");
        string mode=Environment.GetEnvironmentVariable("D31_RESCUE_CASE");
        string command=String.Join(" ",args);
        File.AppendAllText(transcript,command+"\n");
        if(args.Length<3 || args[0]!="-P" || args[1]!="5042") return 90;
        if(args[2]!="connect" && (args[2]!="-s" || args[3]!="192.0.2.31:5654")) return 91;
        string root=transcript+".files";
        Directory.CreateDirectory(root);
        string[] names={"boot","recovery","system","userdata","logo","nvram","nvdata","protect1","protect2","proinfo","secro","seccfg","frp"};
        long[] sizes={2048,2048,4096,8192,512,512,512,512,512,512,512,512,512};
        for(int i=0;i<names.Length;i++) {
            string p=Path.Combine(root,names[i]+".img");
            if(!File.Exists(p)) {var data=new byte[sizes[i]]; data[0]=(byte)(i+1); File.WriteAllBytes(p,data);}
        }
        string result="";
        if(command.Contains(" connect ")) result="connected";
        else if(command.EndsWith(" get-state")) result="device";
        else if(command.EndsWith(" shell id")) result="uid=0(root) gid=0(root)";
        else if(command.EndsWith("ro.build.fingerprint")) result="offline/third-party/build:6.0/test";
        else if(command.EndsWith("ro.product.model") || command.EndsWith("ro.product.device")) result="hct6737t_66_m0";
        else if(command.Contains("ip -4 addr")) result="inet 192.0.2.31/24";
        else if(command.Contains("blockdev --getsize64")) {
            string n=Regex.Match(command,@"by-name/([a-z0-9]+)").Groups[1].Value;
            result=sizes[Array.IndexOf(names,n)].ToString();
        } else if(command.Contains("readlink -f")) {
            int i=Array.IndexOf(names,Regex.Match(command,@"by-name/([a-z0-9]+)").Groups[1].Value);
            result="/dev/block/mmcblk0p"+(i+1)+"|"+(2048+i*100)+"|"+(sizes[i]/512);
        } else if(command.Contains("then echo YES")) result="YES";
        else if(command.Contains("df /data")) result="10G";
        else if(command.Contains("busybox sha256sum ")) {
            string path=command.Substring(command.IndexOf("busybox sha256sum ")+18);
            string file=Path.GetFileName(path)+(path.Contains("/by-name/") ? ".img" : "");
            result=Hash(Path.Combine(root,file))+"  "+path;
        } else if(command.Contains("busybox gzip ")) {
            string destination=Regex.Match(command,@"> ([^;\s]+)").Groups[1].Value;
            using(var f=File.Create(Path.Combine(root,Path.GetFileName(destination))))
            using(var g=new GZipStream(f,CompressionMode.Compress)) {
                var raw=File.ReadAllBytes(Path.Combine(root,"system.img"));g.Write(raw,0,raw.Length);
            }
        } else if(command.Contains("busybox dd ")) {
            var io=Regex.Match(command,@"dd if=(\S+) of=(\S+)");
            string source=Path.Combine(root,Path.GetFileName(io.Groups[1].Value)+".img");
            string target=Path.Combine(root,Path.GetFileName(io.Groups[2].Value));
            if(io.Groups[2].Value.Contains("/by-name/")) return 92;
            if(source!=target) File.Copy(source,target,false);
        } else if(command.Contains("stat -c %s ")) {
            string path=command.Substring(command.IndexOf("stat -c %s ")+11);
            result=new FileInfo(Path.Combine(root,Path.GetFileName(path))).Length.ToString();
        } else if(command.Contains(" pull ")) {
            if(mode!="missing-boot" || !args[args.Length-2].EndsWith("BOOT.img")) {
                File.Copy(Path.Combine(root,Path.GetFileName(args[args.Length-2])),args[args.Length-1],false);
                if(mode=="corrupt-boot" && args[args.Length-1].EndsWith("BOOT.img")) using(var f=File.OpenWrite(args[args.Length-1])) f.WriteByte(99);
            }
        } else if(command.Contains("mkdir '") || command.Contains("rm -f ") || command.Contains("rm -rf ")) { }
        else {Console.Error.WriteLine("未知备份模拟命令："+command);return 98;}
        Console.OutputEncoding=System.Text.Encoding.UTF8;
        Console.WriteLine(result);
        var marker=Regex.Match(command,@"D31_RESCUE_EXIT_[a-f0-9]{32}_");
        if(marker.Success && !(mode=="missing-receipt" && command.Contains("busybox dd "))) {
            Console.WriteLine();Console.WriteLine(marker.Value+"0");
        }
        return 0;
    }
}
