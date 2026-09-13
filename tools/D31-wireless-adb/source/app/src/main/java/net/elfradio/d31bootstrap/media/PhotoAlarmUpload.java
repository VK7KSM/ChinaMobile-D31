package net.elfradio.d31bootstrap.media;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import javax.net.ssl.HttpsURLConnection;
import net.elfradio.d31bootstrap.RemoteTls;
import org.json.JSONObject;

/** 单次原件上传；未知回执保留原件，不重复拍照或另换报告编号。 */
public final class PhotoAlarmUpload implements AutoCloseable {
    private final URI origin;
    private String device,token;
    private volatile HttpsURLConnection connection;
    private volatile boolean closed;
    public PhotoAlarmUpload(URI origin,JSONObject identity)throws Exception {
        this.origin=origin;device=identity.getString("device_id");token=identity.getString("token");
        if(!device.matches("[A-Za-z0-9_-]{1,96}")||token.isEmpty()||token.length()>4096||token.indexOf('\r')>=0||token.indexOf('\n')>=0)
            throw new IOException("VISUAL_IDENTITY_INVALID");
    }
    public JSONObject upload(JSONObject capture,Cancellation cancel)throws Exception {
        JSONObject params=PhotoReport.uploadParameters(capture);
        File file=MediaFiles.plain(new File(capture.getString("path")));
        if(file.length()<4||file.length()>256*1024||file.length()!=capture.getLong("bytes")
                ||!MediaFiles.hash(file).equals(capture.getString("sha256")))throw new IOException("VISUAL_PHOTO_SIZE_OR_HASH");
        HttpsURLConnection c=null;
        try {
            check(cancel);
            c=(HttpsURLConnection)origin.resolve("/api/elfremote/report-photo?device_id="+URLEncoder.encode(device,"UTF-8")
                    +"&report_id="+params.getString("report_id")+"&captured_at="+params.getLong("captured_at")).toURL().openConnection();
            synchronized(this){if(closed)throw new IOException("VISUAL_UPLOAD_CANCELLED");connection=c;}
            c.setSSLSocketFactory(RemoteTls.factory());c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(8000);c.setReadTimeout(12000);
            c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Content-Type","image/jpeg");
            c.setFixedLengthStreamingMode(file.length());
            try(InputStream in=new FileInputStream(file);OutputStream out=c.getOutputStream()){
                byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){check(cancel);out.write(bytes,0,n);}
            }
            check(cancel);if(c.getResponseCode()!=200)throw new IOException("VISUAL_UPLOAD_NOT_ACKNOWLEDGED");
            JSONObject reply;
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] bytes=new byte[1024];int n;while((n=in.read(bytes))!=-1){check(cancel);if(out.size()+n>4096)throw new IOException("VISUAL_UPLOAD_REPLY_SIZE");out.write(bytes,0,n);}
                reply=new JSONObject(out.toString("UTF-8"));
            }
            check(cancel);JSONObject result=PhotoReport.acknowledged(capture,reply);
            MediaFiles.writeNew(new File(file.getParentFile(),"upload-ack.json"),new JSONObject().put("report_id",params.getString("report_id"))
                    .put("bytes",file.length()).put("sha256",capture.getString("sha256")).put("acknowledged_at_ms",System.currentTimeMillis()));
            return result;
        }finally{if(c!=null)c.disconnect();connection=null;}
    }
    private void check(Cancellation cancel)throws Exception {cancel.check();if(closed)throw new IOException("VISUAL_UPLOAD_CANCELLED");}
    public void close(){closed=true;HttpsURLConnection c=connection;if(c!=null)c.disconnect();}
}
