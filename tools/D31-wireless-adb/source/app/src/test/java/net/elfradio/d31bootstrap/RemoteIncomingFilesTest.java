package net.elfradio.d31bootstrap;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RemoteIncomingFilesTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private JSONObject offer(File target,byte[] bytes,long now)throws Exception {
        String path=target.getAbsolutePath().replace('\\','/').replaceFirst("^[A-Za-z]:","");
        assertEquals(target.getCanonicalFile(),new File(path).getCanonicalFile());
        return new JSONObject().put("id","receive-test").put("type","send_file").put("managed_file_v1",true)
                .put("expires_at",now+3600000).put("params",new JSONObject().put("path",path)
                        .put("transfer_id","0123456789abcdef0123456789abcdef").put("size",bytes.length)
                        .put("sha256",RemoteFileDownloadTest.sha(bytes)).put("chunk_size",RemoteFileDownload.CHUNK));
    }
    private JSONObject saved(File state)throws Exception {
        return new JSONObject(RescueFiles.read(new File(state,"incoming-files/"+RemoteProtocol.localJobId("test","receive-test")+"/state.json"),128000));
    }
    private void await(File state,boolean ack)throws Exception {
        long end=System.nanoTime()+5000000000L;
        while(System.nanoTime()<end){JSONObject s=saved(state);if(ack?s.optBoolean("acknowledged"):s.has("receipt")&&s.optInt("failures")>0)return;Thread.sleep(10);}
        fail("任务未完成预期持久状态");
    }
    @Test public void cloudFailureAfterCommitRetriesReceiptWithoutDownloadingOrOverwriting()throws Exception {
        File state=temp.newFolder(),target=new File(temp.newFolder(),"目标.txt");byte[] bytes="test bytes".getBytes("UTF-8");long[] now={System.currentTimeMillis()};
        AtomicInteger calls=new AtomicInteger(),success=new AtomicInteger();JSONObject m=RemoteFileDownloadTest.metadata(bytes);
        RemoteFileDownload download=new RemoteFileDownload(url->{calls.incrementAndGet();return new RemoteFileDownloadTest.Reply(url,url.contains("&part=")?bytes:new JSONObject().put("ok",true).put("file",m).toString().getBytes("UTF-8"));});
        RemoteFileTransfers.Reporter reporter=body->{
            if("success".equals(body.getString("state"))&&success.getAndIncrement()==0)throw new RemoteHttp.Rejected(503,"offline",900000);
            return new JSONObject().put("ok",true).put("task",new JSONObject().put("id",body.getString("task_id")).put("state",body.getString("state")));
        };
        JSONObject task=offer(target,bytes,now[0]);RemoteFileTransfers first=new RemoteFileTransfers(state,"test","token",reporter,download,new RemoteFileUpload(),()->now[0],()->"ethernet");
        first.accept(task,now[0]);first.tick();await(state,false);first.close();
        assertEquals(900000,saved(state).getLong("retry_at")-now[0]);assertEquals(2,calls.get());
        assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(target.toPath()));
        RescueFiles.write(target,"changed after completed commit");
        RemoteFileTransfers second=new RemoteFileTransfers(state,"test","token",reporter,download,new RemoteFileUpload(),()->now[0],()->"ethernet");
        second.accept(task,now[0]);second.tick();assertFalse(saved(state).optBoolean("acknowledged"));
        now[0]+=900001;second.tick();await(state,true);second.close();
        assertEquals(2,calls.get());assertEquals("changed after completed commit",RescueFiles.read(target,100));
    }
    @Test public void cancelledAndMalformedTasksNeverDownload()throws Exception {
        File state=temp.newFolder();long now=System.currentTimeMillis();JSONObject task=offer(new File(temp.newFolder(),"file"),new byte[0],now).put("cancel_requested",true);
        RemoteFileTransfers files=new RemoteFileTransfers(state,"test","token",body->new JSONObject().put("ok",true).put("task",new JSONObject().put("id",body.getString("task_id")).put("state",body.getString("state"))),new RemoteFileDownload(url->{throw new AssertionError("取消任务不能下载");}),new RemoteFileUpload(),()->now,()->"ethernet");
        files.accept(task,now);files.tick();await(state,true);files.close();assertEquals("rejected",saved(state).getJSONObject("receipt").getString("state"));
        task.remove("cancel_requested");task.getJSONObject("params").put("path","/a\nb\nc");
        assertThrows(IOException.class,()->RemoteFileTransfers.validate(task,now));
        task.getJSONObject("params").put("path","/a/../b");assertThrows(IOException.class,()->RemoteFileTransfers.validate(task,now));
    }
}
