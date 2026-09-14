package net.elfradio.d31bootstrap;

import java.io.*;
import org.json.*;
import net.elfradio.d31bootstrap.telemetry.MediaAssociation;

/** 独立ACK交接，不占用文字pending槽，也不把报告中的凭据复制到照片目录。 */
final class AutomaticPhotoHandoff {
    private final File root;
    AutomaticPhotoHandoff(File root){this.root=new File(root,"automatic-photo-acks");}
    synchronized boolean offer(JSONObject report,JSONObject reply)throws Exception {
        if(!"ELIGIBLE".equals(MediaAssociation.automaticPhotoEligibility(report,reply)))return false;
        String id=report.getString("report_id");File file=new File(root,id+".json");
        if(file.isFile())return true;
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("AUTO_PHOTO_HANDOFF_UNAVAILABLE");
        if(entries().length>=16)throw new IOException("AUTO_PHOTO_HANDOFF_FULL");
        JSONObject frozen=new JSONObject();
        for(String key:new String[]{"report_id","device_id","network","reported_at","report_event"})if(report.has(key))frozen.put(key,report.get(key));
        RescueFiles.write(file,new JSONObject().put("report",frozen).put("reply",new JSONObject().put("ok",true).put("report_id",id)).toString());
        return true;
    }
    synchronized void drain(AutomaticPhotoQueue queue,long now)throws Exception {
        for(File file:entries()){
            JSONObject handoff=new JSONObject(RescueFiles.read(file,16384));
            queue.acknowledged(handoff.getJSONObject("report"),handoff.getJSONObject("reply"),now);
            if(!file.delete())throw new IOException("AUTO_PHOTO_HANDOFF_DELETE_FAILED");
        }
    }
    private File[] entries()throws IOException {
        if(!root.exists())return new File[0];File[] files=root.listFiles((dir,name)->name.endsWith(".json"));
        if(files==null)throw new IOException("AUTO_PHOTO_HANDOFF_UNAVAILABLE");
        java.util.Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));return files;
    }
}
