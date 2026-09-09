using System;
using System.IO;
using System.Diagnostics;
using System.Reflection;
using System.Threading.Tasks;
using System.Security.Cryptography;

class ReleasedArtifactAudit
{
    static object Call(Type type, string name, params object[] args)
    { return type.GetMethod(name, BindingFlags.NonPublic | BindingFlags.Static).Invoke(null, args); }
    static string Hash(string file)
    { using (var sha=SHA256.Create()) using(var input=File.OpenRead(file)) return BitConverter.ToString(sha.ComputeHash(input)); }
    static int Main(string[] args)
    {
        if (args.Length > 0 && args[0] == "--echo") {
            for (int i=1;i<args.Length;i++) Console.WriteLine("ARG="+args[i]);
            return 0;
        }
        string assemblyPath=Path.GetFullPath(args[0]), output=Path.GetFullPath(args[1]);
        Directory.CreateDirectory(output);
        Assembly app=Assembly.LoadFile(assemblyPath);
        Type manager=app.GetType("D31FlashTool.FirmwareManager",true);
        string quoted=(string)Call(manager,"QuoteArgument","C:\\");
        var command=new ProcessStartInfo {
            FileName=typeof(ReleasedArtifactAudit).Assembly.Location,
            Arguments="--echo --dir="+quoted+" --out=package.zip",
            UseShellExecute=false, CreateNoWindow=true, RedirectStandardOutput=true
        };
        using(var process=Process.Start(command)) {
            string actual=process.StandardOutput.ReadToEnd(); process.WaitForExit();
            File.WriteAllText(Path.Combine(output,"argument-roundtrip.txt"),actual);
            Console.WriteLine("ROOT_PATH_ARGUMENTS="+actual.Replace("\r","").Replace("\n","|"));
        }
        string runtime=(string)Call(app.GetType("D31FlashTool.RuntimeAssets",true),"Prepare");
        Console.WriteLine("RUNTIME="+runtime);
        string package=(string)manager.GetField("PackageName",BindingFlags.NonPublic|BindingFlags.Static).GetValue(null);
        long size=(long)app.GetType("D31FlashTool.BuildConstants",true).GetField("OfficialPackageBytes",BindingFlags.NonPublic|BindingFlags.Static).GetValue(null);
        string partial=Path.Combine(output,package+".高速下载中");
        if(File.Exists(partial)) throw new IOException("测试原件已存在");
        using(var file=new FileStream(partial,FileMode.CreateNew,FileAccess.Write)) file.SetLength(size);
        string before=Hash(partial);
        object source=Enum.Parse(app.GetType("D31FlashTool.FirmwareDownloadSource",true),"Cloudflare");
        for(int attempt=1;attempt<=2;attempt++) {
            var timer=Stopwatch.StartNew();
            try {
                Task task=(Task)Call(manager,"DownloadAsync",runtime,output,source,new Action<int,string>((p,m)=>{}));
                task.GetAwaiter().GetResult();
                Console.WriteLine("ATTEMPT="+attempt+" COMPLETED");
            } catch(Exception error) {
                Console.WriteLine("ATTEMPT="+attempt+" ERROR="+error.GetBaseException().Message.Replace("\r","").Replace("\n","|"));
            }
            Console.WriteLine("SECONDS="+timer.Elapsed.TotalSeconds.ToString("F2")+" CORRUPT_PARTIAL_RETAINED="+(File.Exists(partial)&&Hash(partial)==before));
        }
        return 0;
    }
}
