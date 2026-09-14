package net.elfradio.d31bootstrap;

import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/** 仅在独立临时目录回放合成任务，不访问正式任务、文件或云服务。 */
public final class RemoteQueueRecoveryCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].matches("[0-9]+")
                || android.system.Os.getuid() != 0 || android.os.Build.VERSION.SDK_INT != 23
                || !"hct6735_66_m0".equals(android.os.Build.DEVICE)
                || BuildConfig.VERSION_CODE != Integer.parseInt(args[0]))
            throw new IOException("设备或候选版本不符");
        File root=new File("/data/local/tmp/d31-queue-check-"+java.util.UUID.randomUUID());
        if(!root.mkdir())throw new IOException("合成回放目录创建失败");
        android.system.Os.chmod(root.getPath(),0700);
        long now=System.currentTimeMillis();
        int[] submissions={0};
        RemoteTasks tasks=new RemoteTasks(root,"synthetic-device",new RemoteTasks.Transport(){
            public JSONObject local(String path,JSONObject body) throws Exception {
                if(!path.equals("/exec"))return null;
                submissions[0]++;
                return new JSONObject().put("state","completed").put("exit_code",0).put("output","synthetic");
            }
            public JSONObject progress(JSONObject body) throws Exception {return acknowledged(body);}
        });
        JSONObject task=new JSONObject().put("id","synthetic-command").put("type","root_exec")
                .put("expires_at",now+60000).put("params",new JSONObject().put("command","id").put("timeout",5)
                        .put("extra",new JSONObject().put("Aa",1).put("BB",2)));
        tasks.accept(task,now);
        JSONObject repeat=new JSONObject(task.toString());
        repeat.getJSONObject("params").put("extra",new JSONObject().put("BB",2).put("Aa",1));
        boolean reused=false;
        try {tasks.accept(repeat,now);reused=submissions[0]==1;} catch(IOException expected) { }
        boolean conflict=false;
        repeat.getJSONObject("params").getJSONObject("extra").put("Aa",3);
        try {tasks.accept(repeat,now);} catch(IOException expected) {conflict=true;}
        JSONObject fileTask=new JSONObject().put("id","synthetic-file").put("type","get_file")
                .put("managed_file_return_v1",true).put("cancel_requested",true)
                .put("expires_at",now+60000).put("params",new JSONObject().put("path","/synthetic-not-accessed"));
        RemoteFileTransfers files=new RemoteFileTransfers(root,"synthetic-device","synthetic-token",
                RemoteQueueRecoveryCheck::acknowledged,()->"offline");
        File broken=new File(root,"incoming-files/000-broken");
        File interrupted=new File(root,"incoming-files/001-interrupted");
        boolean errorReported=false,drained=false,original=false;
        try {
            files.accept(fileTask,now);
            if(!broken.mkdir() || !interrupted.mkdir())throw new IOException("合成目录冲突");
            RescueFiles.write(new File(broken,"state.json"),"synthetic-original-broken");
            try {files.tick();} catch(Exception expected) {errorReported=true;}
            File record=new File(root,"incoming-files/"+RemoteProtocol.localJobId("synthetic-device","synthetic-file")+"/state.json");
            long until=android.os.SystemClock.elapsedRealtime()+2500;
            do {
                JSONObject saved=new JSONObject(RescueFiles.read(record,128000));
                drained=saved.optBoolean("acknowledged") && "rejected".equals(saved.getJSONObject("receipt").getString("state"));
                if(drained)break;
                Thread.sleep(25);
            }while(android.os.SystemClock.elapsedRealtime()<until);
            original="synthetic-original-broken".equals(RescueFiles.read(new File(broken,"state.json"),1000))
                    && interrupted.list().length==0;
        } finally {files.close();}
        boolean ok=reused && conflict && submissions[0]==1 && drained && original && errorReported;
        JSONObject result=new JSONObject().put("ok",ok).put("version",BuildConfig.VERSION_CODE)
                .put("reorderedTaskReused",reused).put("differentTaskRejected",conflict)
                .put("syntheticSubmissions",submissions[0]).put("healthyFileReceiptDrained",drained)
                .put("damagedRecordsPreserved",original).put("damagedRecordReported",errorReported)
                .put("scope","SYNTHETIC_PRIVATE_QUEUE_NO_REAL_FILE_OR_CLOUD_OPERATION");
        RescueFiles.write(new File(root,"result.json"),result.toString());
        System.out.println(result);
        System.exit(ok?0:1);
    }
    private static JSONObject acknowledged(JSONObject body) throws Exception {
        return new JSONObject().put("ok",true).put("task",new JSONObject()
                .put("id",body.getString("task_id")).put("state",body.getString("state")));
    }
}
