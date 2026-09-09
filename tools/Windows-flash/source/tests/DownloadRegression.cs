using System;
using System.IO;
using System.Diagnostics;
using System.Reflection;
using System.Threading.Tasks;

class DownloadRegression
{
    static object Call(Type type, string name, params object[] args) {
        return type.GetMethod(name, BindingFlags.NonPublic | BindingFlags.Static).Invoke(null, args);
    }
    static int Main(string[] args) {
        if (args.Length > 0 && args[0] == "--echo") {
            for (int i=1;i<args.Length;i++) Console.WriteLine(Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(args[i])));
            return 0;
        }
        try { Run(args); return 0; }
        catch(Exception error) { Console.Error.WriteLine(error); return 1; }
    }
    static void Run(string[] args) {
        Assembly app = Assembly.LoadFile(Path.GetFullPath(args[0]));
        Type manager = app.GetType("D31FlashTool.FirmwareManager",true);
        Type constants = app.GetType("D31FlashTool.BuildConstants",true);
        foreach (string value in new[]{"C:\\", "D:\\中文 目录\\", "a\\\"b", "", "abc\\\\", "hello world"}) {
            string quoted = (string)Call(manager,"QuoteArgument",value);
            using(var process=Process.Start(new ProcessStartInfo {
                FileName=typeof(DownloadRegression).Assembly.Location,
                Arguments="--echo "+quoted+" sentinel",UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true
            })) {
                string output=process.StandardOutput.ReadToEnd(); process.WaitForExit();
                string expected=Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(value))+Environment.NewLine+
                    Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes("sentinel"))+Environment.NewLine;
                if(output!=expected) throw new Exception("参数往返失败："+value);
            }
        }
        Console.WriteLine("参数往返六项通过");
        string runtime=(string)Call(app.GetType("D31FlashTool.RuntimeAssets",true),"Prepare");
        string package=(string)manager.GetField("PackageName",BindingFlags.NonPublic|BindingFlags.Static).GetValue(null);
        long size=(long)constants.GetField("OfficialPackageBytes",BindingFlags.NonPublic|BindingFlags.Static).GetValue(null);
        string outputRoot=Path.GetFullPath(args[1]);
        if(Directory.Exists(outputRoot)) throw new IOException("测试目录已存在");
        Directory.CreateDirectory(outputRoot);
        string partial=Path.Combine(outputRoot,package+".高速下载中");
        using(var file=new FileStream(partial,FileMode.CreateNew,FileAccess.Write)) file.SetLength(size);
        bool rejected=false;
        try { Download(manager,runtime,outputRoot,args[2]); }
        catch(InvalidDataException) { rejected=true; }
        if(!rejected || File.Exists(partial) || Directory.GetFiles(outputRoot,"*.invalid-*").Length!=1)
            throw new Exception("完整坏文件没有被隔离");
        Console.WriteLine("完整坏文件拒绝并隔离通过");
        Download(manager,runtime,outputRoot,args[2]);
        if(!File.Exists(Path.Combine(outputRoot,package))) throw new Exception("重新下载未生成文件");
        Console.WriteLine("重新下载和最终SHA-256检查通过");
        string resumed=Path.Combine(outputRoot,"中文 续传"); Directory.CreateDirectory(resumed);
        using(var input=File.OpenRead(Path.Combine(outputRoot,package)))
        using(var output=File.Create(Path.Combine(resumed,package+".高速下载中"))) {
            byte[] bytes=new byte[16*1024*1024]; int n=input.Read(bytes,0,bytes.Length); output.Write(bytes,0,n);
        }
        Download(manager,runtime,resumed,args[2]);
        Console.WriteLine("有效部分下载续传和最终SHA-256检查通过");
    }
    static void Download(Type manager,string runtime,string output,string url) {
        Task task=(Task)Call(manager,"DownloadFromAsync",runtime,output,url,"本机测试",4,new Action<int,string>((p,m)=>{}));
        task.GetAwaiter().GetResult();
    }
}
