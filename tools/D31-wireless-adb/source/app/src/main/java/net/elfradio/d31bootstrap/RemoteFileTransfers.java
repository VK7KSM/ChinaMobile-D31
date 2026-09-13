package net.elfradio.d31bootstrap;

import java.io.*;
import java.net.URLEncoder;
import org.json.JSONObject;

/** 双向文件与普通命令分离，断点、提交日志和最终回执跨核心更新保留。 */
final class RemoteFileTransfers {
    interface Reporter { JSONObject send(JSONObject receipt) throws Exception; }
    interface Clock { long now(); }
    interface Network { String type(); }
    private final File root;
    private final String device,token;
    private final Reporter reporter;
    private final RemoteFileDownload download;
    private final Clock clock;
    private final RemoteFileUpload upload;
    private final Network network;
    private volatile boolean stopped;
    private Thread worker;

    RemoteFileTransfers(File state,String device,String token,Reporter reporter,Network network) throws Exception {
        this(state,device,token,reporter,new RemoteFileDownload(),new RemoteFileUpload(),System::currentTimeMillis,network);
    }
    RemoteFileTransfers(File state,String device,String token,Reporter reporter,RemoteFileDownload download,RemoteFileUpload upload,Clock clock,Network network) throws Exception {
        this.root=new File(state,"incoming-files");this.device=device;this.token=token;this.reporter=reporter;
        this.download=download;this.upload=upload;this.clock=clock;this.network=network;
        if(!root.isDirectory()&&!root.mkdir())throw new IOException("文件任务存储不可用");
    }
    synchronized void accept(JSONObject task,long now) throws Exception {
        String id=task.getString("id");File dir=new File(root,RemoteProtocol.localJobId(device,id));
        File record=new File(dir,"state.json");
        if(record.isFile()) {
            JSONObject saved=read(record),old=saved.getJSONObject("task");
            if(!device.equals(saved.optString("device"))||!old.getString("type").equals(task.getString("type"))
                    ||!RemoteProtocol.sameJson(old.getJSONObject("params"),task.getJSONObject("params")))throw new IOException("同号文件任务冲突");
            if(task.optBoolean("cancel_requested"))RescueFiles.write(new File(dir,"cancel"),"cancel\n");
            return;
        }
        File[] jobs=root.listFiles(File::isDirectory);
        if(jobs==null||jobs.length>=128)throw new IOException("文件记录需要归档");
        if(!dir.mkdir())throw new IOException("文件任务目录已存在但记录不完整");
        JSONObject s=new JSONObject().put("device",device).put("task",task).put("failures",0);
        try { validate(task,now); }
        catch(Exception invalid){s.put("receipt",receipt(id,"rejected",invalid.getMessage(),null));}
        save(dir,s);
    }
    static void validate(JSONObject task,long now) throws Exception {
        boolean receive="send_file".equals(task.optString("type"))&&task.optBoolean("managed_file_v1");
        boolean send="get_file".equals(task.optString("type"))&&task.optBoolean("managed_file_return_v1");
        if(!receive&&!send)throw new IOException("文件任务协议不符");
        if(task.optBoolean("cancel_requested")||task.optLong("expires_at")<=now)throw new IOException("文件任务取消或过期");
        JSONObject p=task.getJSONObject("params");String path=p.getString("path");
        if(!path.startsWith("/")||path.endsWith("/")||path.length()>512)throw new IOException("目标文件路径无效");
        for(int i=0;i<path.length();i++)if(path.charAt(i)<32||path.charAt(i)==127)throw new IOException("目标路径含控制字符");
        for(String part:path.split("/"))if(".".equals(part)||"..".equals(part))throw new IOException("目标含相对目录");
        if(send)return;
        if(!p.getString("transfer_id").matches("[a-f0-9]{32}")||!p.getString("sha256").matches("[a-f0-9]{64}")
                ||p.getLong("size")<0||p.getLong("size")>FileCommit.MAX_BYTES||p.getInt("chunk_size")!=RemoteFileDownload.CHUNK)
            throw new IOException("文件接收参数无效");
    }
    synchronized void tick() throws Exception {
        if(stopped||worker!=null&&worker.isAlive())return;
        File[] dirs=root.listFiles(File::isDirectory);if(dirs==null)throw new IOException("文件任务不可读取");
        java.util.Arrays.sort(dirs,(a,b)->a.getName().compareTo(b.getName()));
        long now=clock.now();
        Exception first = null;
        for(File dir:dirs) {
            JSONObject s;
            try {
                s=read(new File(dir,"state.json"));
                JSONObject task=s.getJSONObject("task");
                if (!device.equals(s.getString("device"))
                        || !dir.getName().equals(RemoteProtocol.localJobId(device,task.getString("id"))))
                    throw new IOException("文件任务记录身份不符");
                task.getJSONObject("params");
                task.getString("type");
            } catch (Exception damaged) {
                // 保留原件并报告错误，但不能让一个中断目录阻塞其它任务。
                if (first == null) first = damaged;
                continue;
            }
            if(s.optBoolean("acknowledged")||now<s.optLong("retry_at"))continue;
            worker=new Thread(()->run(dir,s),"d31-file-receive");worker.start();
            break;
        }
        if (first != null) throw first;
    }
    private void run(File dir,JSONObject s) {
        JSONObject task=s.optJSONObject("task");String id=task.optString("id");
        try {
            if(!device.equals(s.getString("device")))throw new IOException("旧设备实例文件任务");
            if(s.has("receipt")){flush(dir,s);return;}
            if("get_file".equals(task.getString("type"))){returnFile(dir,s);return;}
            JSONObject recovered=FileCommit.recover(dir);
            if(recovered!=null){complete(dir,s,recovered);return;}
            check(dir,task);
            if(!s.optBoolean("claimed")){report(receipt(id,"claimed","设备已接收文件任务",null));s.put("claimed",true);save(dir,s);}
            report(receipt(id,"running","正在接收文件",null));
            JSONObject p=task.getJSONObject("params");
            String url=RemoteProtocol.BASE+"/api/elfremote/file-download?id="+enc(p.getString("transfer_id"))
                    +"&device_id="+enc(device)+"&task_id="+enc(id);
            File payload=new File(dir,"payload.part");
            JSONObject manifest=download.manifest(url,token);
            long[] last={0};
            download.receive(url,token,p,manifest,payload,()->check(dir,task),(bytes,total)->{
                s.put("received_bytes",bytes).put("total_bytes",total);save(dir,s);
                if(clock.now()-last[0]>=30000){
                    report(receipt(id,"running","设备接收 "+(bytes*100/Math.max(1,total))+"%",null));last[0]=clock.now();
                }
            });
            check(dir,task);
            JSONObject committed;
            try { committed=FileCommit.run(dir,new JSONObject(p.toString()).put("source",payload.getPath())); }
            catch(Exception failed) {
                if(stopped)throw failed;
                committed=FileCommit.recover(dir);
                if(committed==null)throw new Permanent("文件提交失败："+failed.getMessage());
            }
            complete(dir,s,committed);
        } catch(Exception error) {
            if(stopped)return;
            try {
                boolean rejected=error instanceof RemoteHttp.Rejected&&((RemoteHttp.Rejected)error).status>=400
                        &&((RemoteHttp.Rejected)error).status<500&&((RemoteHttp.Rejected)error).status!=408&&((RemoteHttp.Rejected)error).status!=429;
                if(!s.has("receipt")&&(error instanceof Permanent||rejected||new File(dir,"cancel").exists()||task.optLong("expires_at")<=clock.now()))
                    s.put("receipt",receipt(id,"failed",error.getMessage(),null));
                int failures=Math.min(16,s.optInt("failures")+1);
                long delay=Math.min(300000L,30000L<<Math.min(4,failures-1));
                if(error instanceof RemoteHttp.Rejected)delay=Math.max(delay,((RemoteHttp.Rejected)error).retryAfterMillis);
                s.put("failures",failures).put("retry_at",clock.now()+delay)
                        .put("last_error",error.getClass().getSimpleName());save(dir,s);
            } catch(Exception storageError){System.err.println("文件结果尚未可靠保存，保留现场");}
        }
    }
    private void returnFile(File dir,JSONObject s) throws Exception {
        JSONObject task=s.getJSONObject("task"),p=task.getJSONObject("params");String id=task.getString("id");
        check(dir,task);checkReturnNetwork(p);
        if(!s.optBoolean("claimed")){report(receipt(id,"claimed","设备已接收取回文件任务",null));s.put("claimed",true);save(dir,s);}
        report(receipt(id,"running","正在准备文件快照",null));
        File snapshot=new File(dir,"snapshot.bin");JSONObject metadata=FileSnapshot.recover(dir);
        if(metadata==null) {
            try {metadata=FileSnapshot.run(dir,new JSONObject().put("source",p.getString("path")).put("target",snapshot.getPath()).put("uid",0));}
            catch(Exception error){if(stopped)throw error;throw new Permanent("文件快照失败："+error.getMessage());}
        }
        check(dir,task);long[] last={0};
        String url=RemoteProtocol.BASE+"/api/elfremote/file-return?device_id="+enc(device)+"&task_id="+enc(id);
        JSONObject result=upload.send(url,token,snapshot,metadata,()->{check(dir,task);checkReturnNetwork(p);},(bytes,total)->{
            s.put("uploaded_bytes",bytes).put("total_bytes",total);save(dir,s);
            if(clock.now()-last[0]>=30000){report(receipt(id,"running","上传到服务器 "+bytes*100/Math.max(1,total)+"%",null));last[0]=clock.now();}
        });
        s.put("receipt",receipt(id,"success","文件已取回，可下载",result));save(dir,s);flush(dir,s);
    }
    private void checkReturnNetwork(JSONObject params) throws IOException {
        if(!returnNetworkAllowed(network.type(),params.optBoolean("allow_cellular")))
            throw new IOException("等待任务允许的网络连接");
    }
    static boolean returnNetworkAllowed(String type,boolean cellular) {
        return !"offline".equals(type)&&(cellular||"ethernet".equals(type)||"wifi".equals(type));
    }
    private void complete(File dir,JSONObject s,JSONObject committed) throws Exception {
        JSONObject result=new JSONObject().put("action","committed").put("stage","file")
                .put("bytes",committed.getLong("bytes")).put("sha256",committed.getString("sha256"))
                .put("text",committed.optString("output"));
        s.put("receipt",receipt(s.getJSONObject("task").getString("id"),"success","文件已保存",result));
        save(dir,s);flush(dir,s);
    }
    private void flush(File dir,JSONObject s) throws Exception {
        report(s.getJSONObject("receipt"));s.put("acknowledged",true).put("retry_at",0);save(dir,s);
        File payload=new File(dir,"payload.part");
        if(payload.isFile()&&!payload.delete())System.err.println("文件已确认，接收暂存等待清理");
        File snapshot=new File(dir,"snapshot.bin");
        if(snapshot.isFile()&&!snapshot.delete())System.err.println("文件已确认，快照暂存等待清理");
    }
    private void report(JSONObject body) throws Exception {
        JSONObject reply=reporter.send(new JSONObject(body.toString())),task=reply.optJSONObject("task");
        if(!reply.optBoolean("ok")||task==null||!body.getString("task_id").equals(task.optString("id"))
                ||!body.getString("state").equals(task.optString("state"))
                &&!("claimed".equals(body.getString("state"))&&"running".equals(task.optString("state"))))
            throw new IOException("文件回执未确认");
    }
    private JSONObject receipt(String id,String phase,String detail,JSONObject result) throws Exception {
        return new JSONObject().put("device_id",device).put("task_id",id).put("state",phase).put("detail",detail).put("result",result);
    }
    private void check(File dir,JSONObject task) throws Exception {
        if(stopped||Thread.currentThread().isInterrupted())throw new IOException("远程核心正在退出");
        if(new File(dir,"cancel").exists())throw new Permanent("文件接收已停止");
        if(task.optLong("expires_at")<=clock.now())throw new Permanent("文件任务已过期");
    }
    synchronized void close() throws InterruptedException {
        stopped=true;download.stop();upload.stop();if(worker!=null){worker.interrupt();worker.join(25000);}
    }
    private static JSONObject read(File file) throws Exception {return new JSONObject(RescueFiles.read(file,128000));}
    private static void save(File dir,JSONObject state) throws Exception {RescueFiles.write(new File(dir,"state.json"),state.toString());}
    private static String enc(String value) throws Exception {return URLEncoder.encode(value,"UTF-8");}
    private static final class Permanent extends IOException {Permanent(String message){super(message);}}
}
