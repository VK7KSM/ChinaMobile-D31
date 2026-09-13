package net.elfradio.d31bootstrap;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.Random;
import static org.junit.Assert.*;

public class RemoteFileUploadTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final String BASE=RemoteProtocol.BASE+"/api/elfremote/file-return?device_id=test&task_id=test";
    private static final class Server implements RemoteFileDownload.Open {
        final JSONObject m;int puts;boolean loseFirst=true,badComplete;
        Server(JSONObject metadata)throws Exception{m=new JSONObject(metadata.toString()).put("parts",new JSONObject()).put("state","uploading");}
        public HttpURLConnection connection(String url)throws Exception {
            return new HttpURLConnection(new URL(url)) {
                final ByteArrayOutputStream request=new ByteArrayOutputStream();boolean handled;
                public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
                public OutputStream getOutputStream(){return request;}
                private void handle()throws IOException {
                    if(handled)return;handled=true;
                    try{
                        if("PUT".equals(method)) {
                            puts++;String query=this.url.getQuery();int part=Integer.parseInt(query.split("part=")[1].split("&")[0]);
                            String hash=query.split("sha256=")[1];assertEquals(hash,RemoteFileDownloadTest.sha(request.toByteArray()));
                            m.getJSONObject("parts").put(String.valueOf(part),new JSONObject().put("bytes",request.size()).put("sha256",hash));
                            if(loseFirst){loseFirst=false;throw new IOException("服务器已收到分块，回执丢失");}
                        }else {
                            JSONObject body=new JSONObject(request.toString("UTF-8"));
                            if("complete".equals(body.getString("action"))){m.put("state","ready");if(badComplete)m.put("sha256","bad");}
                        }
                    }catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}
                }
                public int getResponseCode()throws IOException{handle();return 200;}
                public InputStream getInputStream()throws IOException {
                    handle();try{return new ByteArrayInputStream(new JSONObject().put("ok",true).put("file",m).toString().getBytes("UTF-8"));}
                    catch(Exception e){throw new IOException(e);}
                }
            };
        }
    }
    @Test public void lostChunkReceiptResumesWithoutReuploadingConfirmedPart()throws Exception {
        byte[] bytes=new byte[RemoteFileDownload.CHUNK+123];new Random(31).nextBytes(bytes);File source=temp.newFile();java.nio.file.Files.write(source.toPath(),bytes);
        JSONObject metadata=RemoteFileDownloadTest.metadata(bytes),snapshot=new JSONObject().put("bytes",bytes.length).put("sha256",RemoteFileDownloadTest.sha(bytes));Server server=new Server(metadata);
        assertThrows(IOException.class,()->new RemoteFileUpload(server).send(BASE,"test",source,snapshot,()->{},(a,b)->{}));
        assertEquals(1,server.puts);assertTrue(server.m.getJSONObject("parts").has("0"));
        JSONObject result=new RemoteFileUpload(server).send(BASE,"test",source,snapshot,()->{},(a,b)->{});
        assertEquals(2,server.puts);assertEquals("uploaded",result.getString("action"));assertEquals(bytes.length,result.getLong("bytes"));
    }
    @Test public void wrongServerPartAndUnconfirmedCompletionAreRejected()throws Exception {
        byte[] bytes={1,2,3};File source=temp.newFile();java.nio.file.Files.write(source.toPath(),bytes);JSONObject m=RemoteFileDownloadTest.metadata(bytes),s=new JSONObject().put("bytes",3).put("sha256",RemoteFileDownloadTest.sha(bytes));
        Server wrong=new Server(m);wrong.m.getJSONObject("parts").put("0",new JSONObject().put("bytes",3).put("sha256","bad"));
        assertThrows(IOException.class,()->new RemoteFileUpload(wrong).send(BASE,"test",source,s,()->{},(a,b)->{}));assertEquals(0,wrong.puts);
        Server incomplete=new Server(m);incomplete.loseFirst=false;incomplete.badComplete=true;
        assertThrows(IOException.class,()->new RemoteFileUpload(incomplete).send(BASE,"test",source,s,()->{},(a,b)->{}));
    }
    @Test public void emptyUploadCompletesWithoutBinaryRequest()throws Exception {
        File source=temp.newFile();JSONObject m=RemoteFileDownloadTest.metadata(new byte[0]),s=new JSONObject().put("bytes",0).put("sha256",RemoteFileDownloadTest.sha(new byte[0]));Server server=new Server(m);
        JSONObject result=new RemoteFileUpload(server).send(BASE,"test",source,s,()->{},(a,b)->{});assertEquals(0,server.puts);assertEquals(0,result.getLong("bytes"));
    }
    @Test public void returnHonoursExplicitCellularChoiceWithoutRestrictingWiredOrWifi() {
        assertTrue(RemoteFileTransfers.returnNetworkAllowed("ethernet",false));
        assertTrue(RemoteFileTransfers.returnNetworkAllowed("wifi",false));
        assertFalse(RemoteFileTransfers.returnNetworkAllowed("cellular",false));
        assertTrue(RemoteFileTransfers.returnNetworkAllowed("cellular",true));
        assertFalse(RemoteFileTransfers.returnNetworkAllowed("offline",true));
    }
}
