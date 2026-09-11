package net.elfradio.d31bootstrap;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import org.json.JSONObject;

/** 沿用8MB分块协议，失败时保留已收到的字节；只有完整校验后才交给提交器。 */
final class RemoteFileDownload {
    static final int CHUNK = 8 * 1024 * 1024;
    interface Open { HttpURLConnection connection(String url) throws Exception; }
    interface Check { void check() throws Exception; }
    interface Progress { void received(long bytes, long total) throws Exception; }
    private final Open open;
    private volatile HttpURLConnection connection;

    RemoteFileDownload() { this(url -> (HttpURLConnection)new URL(url).openConnection()); }
    RemoteFileDownload(Open open) { this.open = open; }
    void stop() { HttpURLConnection c=connection; if(c!=null)c.disconnect(); }

    private HttpURLConnection connect(String url,String token) throws Exception {
        if(!url.startsWith(RemoteProtocol.BASE+"/api/elfremote/file-download?"))throw new IOException("文件下载地址不符");
        HttpURLConnection c=open.connection(url); connection=c;
        c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
        c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Accept-Encoding","identity");
        return c;
    }
    JSONObject manifest(String url,String token) throws Exception {
        HttpURLConnection c=connect(url,token);
        try {
            require(c,200);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){if(out.size()+n>150000)throw new IOException("文件清单过大");out.write(buffer,0,n);}
                JSONObject reply=new JSONObject(out.toString("UTF-8"));
                if(!reply.optBoolean("ok"))throw new IOException("文件清单未确认");
                return reply.getJSONObject("file");
            }
        } finally { c.disconnect();connection=null; }
    }
    void receive(String url,String token,JSONObject params,JSONObject manifest,File payload,Check check,Progress progress) throws Exception {
        long size=params.getLong("size");String sha=params.getString("sha256");
        if(size<0||size>FileCommit.MAX_BYTES||size!=manifest.getLong("size")||!sha.matches("[a-f0-9]{64}")
                ||!sha.equals(manifest.getString("sha256"))||manifest.getInt("chunk_size")!=CHUNK)
            throw new IOException("文件清单与任务不一致");
        if(payload.length()>size)throw new IOException("本地断点超过文件长度");
        if(payload.getParentFile().getUsableSpace()<size-payload.length()+32L*1024*1024)throw new IOException("接收位置剩余空间不足");
        try(RandomAccessFile out=new RandomAccessFile(payload,"rw")) {
            for(int index=0;(long)index*CHUNK<size;index++) {
                check.check();long begin=(long)index*CHUNK,count=Math.min(CHUNK,size-begin);
                JSONObject part=manifest.getJSONObject("parts").getJSONObject(String.valueOf(index));
                if(part.getLong("bytes")!=count||!part.getString("sha256").matches("[a-f0-9]{64}"))throw new IOException("分块元数据不符");
                long have=Math.max(0,Math.min(count,out.length()-begin));
                if(have<count)part(url+"&part="+index,token,out,begin,have,count,check);
                if(!hash(out,begin,count).equals(part.getString("sha256"))) {
                    out.setLength(begin);out.getFD().sync();throw new IOException("分块校验失败，保留此前有效分块");
                }
                progress.received(begin+count,size);
            }
            out.getFD().sync();
        }
        check.check();
        if(payload.length()!=size||!sha.equals(RescueFiles.sha256(payload)))throw new IOException("整文件校验不符");
    }
    private void part(String url,String token,RandomAccessFile out,long begin,long have,long count,Check check) throws Exception {
        HttpURLConnection c=connect(url,token);
        if(have>0)c.setRequestProperty("Range","bytes="+have+"-");
        try {
            require(c,have>0?206:200);
            if(!String.valueOf(count-have).equals(c.getHeaderField("Content-Length")))throw new IOException("分块响应长度不符");
            if(have>0&&!("bytes "+have+"-"+(count-1)+"/"+count).equals(c.getHeaderField("Content-Range")))throw new IOException("断点响应位置不符");
            out.seek(begin+have);long received=have;
            try(InputStream in=c.getInputStream()) {
                byte[] buffer=new byte[65536];int n;
                while((n=in.read(buffer))!=-1){check.check();received+=n;if(received>count)throw new IOException("分块超出长度");out.write(buffer,0,n);}
            }
            if(received!=count)throw new IOException("分块下载中断");
        } finally { out.getFD().sync();c.disconnect();connection=null; }
    }
    static void require(HttpURLConnection c,int expected) throws Exception {
        int status=c.getResponseCode();
        if(status!=expected)throw new RemoteHttp.Rejected(status,"文件服务响应不符",
                RemoteHttp.retryAfterDelay(c.getHeaderField("Retry-After"),System.currentTimeMillis()));
    }
    static String hash(RandomAccessFile file,long start,long count) throws Exception {
        file.seek(start);MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];
        while(count>0){int n=file.read(buffer,0,(int)Math.min(count,buffer.length));if(n<0)throw new EOFException();digest.update(buffer,0,n);count-=n;}
        return FileCommit.hex(digest.digest());
    }
}
