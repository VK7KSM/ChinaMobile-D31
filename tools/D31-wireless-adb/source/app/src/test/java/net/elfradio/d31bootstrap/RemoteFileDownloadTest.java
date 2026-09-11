package net.elfradio.d31bootstrap;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

public class RemoteFileDownloadTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    static final String URL_BASE=RemoteProtocol.BASE+"/api/elfremote/file-download?id=test";
    static String sha(byte[] bytes)throws Exception{return FileCommit.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static JSONObject metadata(byte[] bytes)throws Exception {
        JSONObject parts=new JSONObject();
        for(int i=0;i*RemoteFileDownload.CHUNK<bytes.length;i++) {
            byte[] part=Arrays.copyOfRange(bytes,i*RemoteFileDownload.CHUNK,Math.min(bytes.length,(i+1)*RemoteFileDownload.CHUNK));
            parts.put(String.valueOf(i),new JSONObject().put("bytes",part.length).put("sha256",sha(part)));
        }
        return new JSONObject().put("size",bytes.length).put("sha256",sha(bytes)).put("chunk_size",RemoteFileDownload.CHUNK).put("parts",parts);
    }
    static final class Reply extends HttpURLConnection {
        byte[] bytes; int code=200,limit=-1; String contentRange,range,retry;
        Reply(String url,byte[] bytes)throws Exception{super(new URL(url));this.bytes=bytes;}
        public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
        public void setRequestProperty(String key,String value){if(key.equals("Range"))range=value;}
        public int getResponseCode(){return code;}
        public String getHeaderField(String key){return key.equals("Content-Length")?String.valueOf(bytes.length):key.equals("Content-Range")?contentRange:key.equals("Retry-After")?retry:null;}
        public InputStream getInputStream(){return new ByteArrayInputStream(limit<0?bytes:Arrays.copyOf(bytes,limit));}
    }
    @Test public void resumesInterruptedChunkWithExactRangeAndFullHash()throws Exception {
        byte[] bytes=new byte[200000];new Random(31).nextBytes(bytes);JSONObject m=metadata(bytes);
        File payload=new File(temp.newFolder(),"payload");List<Reply> replies=new ArrayList<>();
        RemoteFileDownload download=new RemoteFileDownload(url->{
            int have=(int)payload.length();Reply r=new Reply(url,Arrays.copyOfRange(bytes,have,bytes.length));
            if(have==0)r.limit=65537;else {r.code=206;r.contentRange="bytes "+have+"-"+(bytes.length-1)+"/"+bytes.length;}
            replies.add(r);return r;
        });
        assertThrows(IOException.class,()->download.receive(URL_BASE,"test",m,m,payload,()->{},(a,b)->{}));
        assertEquals(65537,payload.length());
        download.receive(URL_BASE,"test",m,m,payload,()->{},(a,b)->{});
        assertEquals("bytes=65537-",replies.get(1).range);assertEquals(sha(bytes),RescueFiles.sha256(payload));
    }
    @Test public void multiChunkCorruptionOnlyDiscardsBadChunk()throws Exception {
        byte[] bytes=new byte[RemoteFileDownload.CHUNK+100];new Random(9).nextBytes(bytes);JSONObject m=metadata(bytes);
        File payload=new File(temp.newFolder(),"payload");
        RemoteFileDownload download=new RemoteFileDownload(url->{int index=url.endsWith("=0")?0:1;
            byte[] part=Arrays.copyOfRange(bytes,index*RemoteFileDownload.CHUNK,Math.min(bytes.length,(index+1)*RemoteFileDownload.CHUNK));
            if(index==1)part[0]^=1;return new Reply(url,part);});
        assertThrows(IOException.class,()->download.receive(URL_BASE,"test",m,m,payload,()->{},(a,b)->{}));
        assertEquals(RemoteFileDownload.CHUNK,payload.length());
    }
    @Test public void wrongRangeCannotAppendAnd503PreservesServerDelay()throws Exception {
        byte[] bytes={1,2,3,4};JSONObject m=metadata(bytes);File payload=temp.newFile();try(FileOutputStream out=new FileOutputStream(payload)){out.write(1);}
        RemoteFileDownload wrong=new RemoteFileDownload(url->{Reply r=new Reply(url,new byte[]{2,3,4});r.code=206;r.contentRange="bytes 0-2/4";return r;});
        assertThrows(IOException.class,()->wrong.receive(URL_BASE,"test",m,m,payload,()->{},(a,b)->{}));assertEquals(1,payload.length());
        RemoteFileDownload unavailable=new RemoteFileDownload(url->{Reply r=new Reply(url,new byte[0]);r.code=503;r.retry="900";return r;});
        RemoteHttp.Rejected e=assertThrows(RemoteHttp.Rejected.class,()->unavailable.manifest(URL_BASE,"test"));assertEquals(900000,e.retryAfterMillis);
    }
    @Test public void emptyFileCompletesWithoutPartRequestAndCancellationKeepsTargetAbsent()throws Exception {
        JSONObject m=metadata(new byte[0]);File payload=new File(temp.newFolder(),"payload");
        RemoteFileDownload download=new RemoteFileDownload(url->{throw new AssertionError("空文件不应读取分块");});
        download.receive(URL_BASE,"test",m,m,payload,()->{},(a,b)->{});assertTrue(payload.isFile());assertEquals(0,payload.length());
        byte[] bytes={1};JSONObject one=metadata(bytes);File cancelled=new File(temp.newFolder(),"payload");
        assertThrows(IOException.class,()->download.receive(URL_BASE,"test",one,one,cancelled,()->{throw new IOException("cancel");},(a,b)->{}));assertEquals(0,cancelled.length());
    }
}
