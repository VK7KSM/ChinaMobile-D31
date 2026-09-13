package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/** 由原8765按任务启动独立进程；文件路径始终作为JSON数据传递。 */
public final class RemoteFileCommand {
    static String command(String apk, String id, JSONObject params) throws Exception {
        if (apk == null || !apk.matches("/data/local/d31-remote/releases/[a-f0-9]{64}/remote\\.apk")
                && !"/system/priv-app/D31ElfRemote/D31ElfRemote.apk".equals(apk))
            throw new IOException("文件执行载荷路径不符");
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IOException("文件任务编号不符");
        return "CLASSPATH=" + RescueFiles.quote(apk)
                + " /system/bin/app_process /system/bin net.elfradio.d31bootstrap.RemoteFileCommand "
                + RescueFiles.quote(id) + " " + RescueFiles.quote(FileOperations.normalize(params).toString());
    }

    public static void main(String[] args) {
        try {
            if (args.length != 2 || android.system.Os.getuid() != 0
                    || !args[0].matches("[a-f0-9]{64}")) throw new IOException("文件执行参数不符");
            File root = new File("/data/local/d31-remote/runtime/file-operations");
            JSONObject result = execute(root,args[0],new JSONObject(args[1]),new FileOperations.Access());
            System.out.println(result.getString("output"));
            System.exit(0);
        } catch (Exception error) {
            System.err.println("文件操作未完成：" + error.getMessage());
            System.exit(1);
        }
    }
    static JSONObject execute(File root,String id,JSONObject value,FileOperations.Access fs)throws Exception {
        if(id==null||!id.matches("[a-f0-9]{64}"))throw new IOException("文件任务编号不符");
        JSONObject params=FileOperations.normalize(value);
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("文件任务目录不可用");
        File job=new File(root,id);
        // 先独占任务目录；失败也保留编号，后续不得重新执行破坏性动作。
        if(!job.mkdir())throw new IOException("文件任务已存在，未重复执行");
        try {
            RescueFiles.write(new File(job,"request.json"),params.toString());
            JSONObject result=FileOperations.run(job,params,fs);
            RescueFiles.write(new File(job,"result.json"),result.toString());
            return result;
        }catch(Exception error) {
            try {
                RescueFiles.write(new File(job,"result.json"),new JSONObject().put("state","failed").put("exit_code",1)
                        .put("action",params.getString("action")).put("output",error.getMessage()).put("truncated",false).toString());
            }catch(Exception recordError){error.addSuppressed(recordError);}
            throw error;
        }
    }
    private RemoteFileCommand() { }
}
