package net.elfradio.d31bootstrap;

import java.io.*;
import java.net.*;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android真机数据流与断点验收；仅注入进程内HTTP响应，不访问生产服务器。 */
public final class RemoteReceiveCheck {
    private static final String URL_BASE=RemoteProtocol.BASE+"/api/elfremote/file-download?id=diagnostic";
    private static final class Reply extends HttpURLConnection {
        final File source;final long start,count;final boolean truncate;final JSONArray trace;
        long have;
        Reply(String url,File source,long start,long count,boolean truncate,JSONArray trace)throws Exception {
            super(new URL(url));this.source=source;this.start=start;this.count=count;this.truncate=truncate;this.trace=trace;
        }
        public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
        public void setRequestProperty(String key,String value){if("Range".equals(key))have=Long.parseLong(value.substring(6,value.length()-1));}
        public int getResponseCode(){return have>0?206:200;}
        public String getHeaderField(String key){
            if("Content-Length".equals(key))return String.valueOf(count-have);
            if("Content-Range".equals(key))return "bytes "+have+"-"+(count-1)+"/"+count;
            return null;
        }
        public InputStream getInputStream()throws IOException {
            try {trace.put(new JSONObject().put("chunk_start",start).put("offset",have).put("truncated",truncate));}
            catch(Exception error){throw new IOException(error);}
            RandomAccessFile file=new RandomAccessFile(source,"r");file.seek(start+have);
            return new InputStream(){long remaining=truncate?65537:count-have;
                public int read()throws IOException{if(remaining<=0)return -1;remaining--;return file.read();}
                public int read(byte[] b,int off,int len)throws IOException{if(remaining<=0)return -1;int n=file.read(b,off,(int)Math.min(len,remaining));if(n>0)remaining-=n;return n;}
                public void close()throws IOException{file.close();}
            };
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1||android.system.Os.getuid()!=0||!"hct6737t_66_m0".equals(android.os.Build.MODEL))throw new IllegalArgumentException("需要D31和独立目录");
        File root=new File(args[0]);if(!root.getCanonicalPath().matches("/data/local/d31-remote/receive-check-[a-z0-9-]+")||!root.mkdir())throw new IllegalArgumentException("需要新的独立证据目录");
        File source=new File(root,"source.bin"),payload=new File(root,"payload.part"),target=new File(root,"target.bin");
        long size=2L*RemoteFileDownload.CHUNK+317;
        try(FileOutputStream out=new FileOutputStream(source)){byte[] buffer=new byte[65536];Random random=new Random(31);long left=size;
            while(left>0){random.nextBytes(buffer);int n=(int)Math.min(left,buffer.length);out.write(buffer,0,n);left-=n;}out.getFD().sync();}
        JSONObject parts=new JSONObject();try(RandomAccessFile file=new RandomAccessFile(source,"r")){
            for(int i=0;(long)i*RemoteFileDownload.CHUNK<size;i++){long begin=(long)i*RemoteFileDownload.CHUNK,count=Math.min(RemoteFileDownload.CHUNK,size-begin);
                parts.put(String.valueOf(i),new JSONObject().put("bytes",count).put("sha256",RemoteFileDownload.hash(file,begin,count)));}}
        JSONObject m=new JSONObject().put("size",size).put("sha256",RescueFiles.sha256(source)).put("chunk_size",RemoteFileDownload.CHUNK).put("parts",parts);
        RescueFiles.write(new File(root,"manifest.json"),m.toString());JSONArray trace=new JSONArray();boolean[] fail={true};
        RemoteFileDownload.Open open=url->{int i=Integer.parseInt(url.substring(url.lastIndexOf('=')+1));long begin=(long)i*RemoteFileDownload.CHUNK;
            return new Reply(url,source,begin,Math.min(RemoteFileDownload.CHUNK,size-begin),fail[0]&&i==1,trace);};
        boolean interrupted=false;
        try{new RemoteFileDownload(open).receive(URL_BASE,"diagnostic",m,m,payload,()->{},(a,b)->{});}catch(IOException expected){interrupted=true;}
        if(!interrupted||payload.length()!=RemoteFileDownload.CHUNK+65537)throw new IllegalStateException("未保留实际中断断点");
        RescueFiles.write(new File(root,"partial.json"),new JSONObject().put("bytes",payload.length()).put("sha256",RescueFiles.sha256(payload)).toString());
        fail[0]=false;new RemoteFileDownload(open).receive(URL_BASE,"diagnostic",m,m,payload,()->{},(a,b)->{});
        if(!RescueFiles.sha256(source).equals(RescueFiles.sha256(payload))||trace.length()!=4||trace.getJSONObject(2).getLong("offset")!=65537)throw new IllegalStateException("续传或完整摘要不符");
        RescueFiles.write(target,"original content\n");JSONObject p=new JSONObject(m.toString()).put("source",payload.getPath()).put("path",target.getPath());
        File job=new File(root,"commit");if(!job.mkdir())throw new IOException("提交目录创建失败");
        boolean conflict=false;try{FileCommit.run(job,p);}catch(IOException expected){conflict=true;}
        if(!conflict||!"original content\n".equals(RescueFiles.read(target,100)))throw new IllegalStateException("未保护原文件");
        JSONObject result=FileCommit.run(job,p.put("overwrite",true));
        File backup=new File(target.getPath()+".elfremote-commit.bak");
        if(!"original content\n".equals(RescueFiles.read(backup,100))||!m.getString("sha256").equals(RescueFiles.sha256(target))||FileCommit.recover(job)==null)throw new IllegalStateException("提交及恢复核验失败");
        RescueFiles.write(new File(root,"requests.json"),trace.toString());
        RescueFiles.write(new File(root,"result.json"),new JSONObject().put("passed",true).put("cloud_tested",false)
                .put("bytes",size).put("sha256",m.getString("sha256")).put("range_offset",65537).put("original_preserved",true)
                .put("committed",result).put("version",BuildConfig.VERSION_CODE).toString());
        System.out.println("ANDROID_RECEIVE_RESUME_AND_COMMIT_OK bytes="+size+" cloud_tested=false");System.exit(0);
    }
}
