package net.elfradio.d31bootstrap;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import org.json.JSONObject;

/** 按已冻结快照上传；服务器已有同摘要分块时复用，不重新读取源文件。 */
final class RemoteFileUpload {
    private final RemoteFileDownload.Open open;
    private volatile HttpURLConnection connection;
    RemoteFileUpload(){this(url->(HttpURLConnection)new URL(url).openConnection());}
    RemoteFileUpload(RemoteFileDownload.Open open){this.open=open;}
    void stop(){HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    JSONObject send(String url,String token,File snapshot,JSONObject metadata,RemoteFileDownload.Check check,RemoteFileDownload.Progress progress)throws Exception {
        long size=metadata.getLong("bytes");String sha=metadata.getString("sha256");
        if(size<0||size>FileCommit.MAX_BYTES||!sha.matches("[a-f0-9]{64}")||!snapshot.isFile()||snapshot.length()!=size)throw new IOException("文件快照不可用");
        JSONObject m=json(url,token,new JSONObject().put("action","init").put("size",size).put("sha256",sha),check).getJSONObject("file");
        if(m.getLong("size")!=size||!sha.equals(m.getString("sha256"))||m.getInt("chunk_size")!=RemoteFileDownload.CHUNK)throw new IOException("服务端快照信息不一致");
        MessageDigest complete=MessageDigest.getInstance("SHA-256");long sent=0;
        try(InputStream in=new FileInputStream(snapshot)) {
            for(int index=0;sent<size;index++) {
                check.check();byte[] bytes=new byte[(int)Math.min(RemoteFileDownload.CHUNK,size-sent)];
                int at=0,n;while(at<bytes.length&&(n=in.read(bytes,at,bytes.length-at))!=-1)at+=n;
                if(at!=bytes.length)throw new IOException("快照长度发生变化");
                complete.update(bytes);String partHash=FileCommit.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
                JSONObject old=m.getJSONObject("parts").optJSONObject(String.valueOf(index));
                if(old!=null) {
                    if(old.getLong("bytes")!=bytes.length||!partHash.equals(old.getString("sha256")))throw new IOException("已上传分块与快照不一致");
                } else request(url+"&part="+index+"&sha256="+partHash,token,"PUT",bytes,"application/octet-stream",check);
                sent+=bytes.length;progress.received(sent,size);
            }
            if(in.read()!=-1)throw new IOException("快照长度增加");
        }
        check.check();if(!sha.equals(FileCommit.hex(complete.digest())))throw new IOException("快照完整摘要不一致");
        JSONObject done=json(url,token,new JSONObject().put("action","complete"),check).getJSONObject("file");
        if(!"ready".equals(done.optString("state"))||!sha.equals(done.optString("sha256"))||done.optLong("size",-1)!=size)throw new IOException("服务器未确认完整文件");
        return new JSONObject().put("action","uploaded").put("stage","file").put("bytes",size).put("sha256",sha);
    }
    private JSONObject json(String url,String token,JSONObject body,RemoteFileDownload.Check check)throws Exception {
        return request(url,token,"POST",body.toString().getBytes("UTF-8"),"application/json",check);
    }
    private JSONObject request(String url,String token,String method,byte[] bytes,String type,RemoteFileDownload.Check check)throws Exception {
        if(!url.startsWith(RemoteProtocol.BASE+"/api/elfremote/file-return?"))throw new IOException("文件取回地址不符");
        check.check();HttpURLConnection c=open.connection(url);connection=c;
        try {
            c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
            c.setRequestMethod(method);c.setDoOutput(true);c.setFixedLengthStreamingMode(bytes.length);
            c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Content-Type",type);
            try(OutputStream out=c.getOutputStream()){for(int offset=0;offset<bytes.length;offset+=65536){check.check();out.write(bytes,offset,Math.min(65536,bytes.length-offset));}}
            RemoteFileDownload.require(c,200);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>150000)throw new IOException("文件回执过大");out.write(buffer,0,n);}
                JSONObject result=new JSONObject(out.toString("UTF-8"));if(!result.optBoolean("ok"))throw new IOException("文件服务未确认");return result;
            }
        } finally {c.disconnect();connection=null;}
    }
}
