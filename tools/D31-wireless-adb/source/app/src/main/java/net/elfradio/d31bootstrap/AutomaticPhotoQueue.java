package net.elfradio.d31bootstrap;

import java.io.*;
import java.text.*;
import java.util.*;
import org.json.*;
import net.elfradio.d31bootstrap.telemetry.MediaAssociation;

/** 已确认文字的有限队列；所有方法只做本地状态读写，不调用相机、Binder或网络。 */
final class AutomaticPhotoQueue {
    static final long INTERVAL=900000L, TTL=86400000L;
    private final File file;
    private JSONObject state;
    AutomaticPhotoQueue(File root)throws Exception {
        file=new File(root,"automatic-photos.json");
        state=file.isFile()?new JSONObject(RescueFiles.read(file,262144)):
                new JSONObject().put("jobs",new JSONArray()).put("done",new JSONArray());
        if(state.getJSONArray("jobs").length()>16||state.getJSONArray("done").length()>256)
            throw new IOException("AUTO_PHOTO_QUEUE_INVALID");
    }
    static long sampled(JSONObject report)throws Exception {
        String value=report.getString("reported_at");
        for(String pattern:new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'","yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));f.setLenient(false);
            ParsePosition p=new ParsePosition(0);Date d=f.parse(value,p);if(d!=null&&p.getIndex()==value.length())return d.getTime();
        }
        throw new IOException("AUTO_PHOTO_REPORT_TIME_INVALID");
    }
    static boolean recent(long now,long sampled){return sampled>0&&now-sampled<=INTERVAL&&sampled<=now+60000;}
    static boolean due(long now,long previous){return previous<=0||now<previous||now-previous>=INTERVAL;}
    synchronized boolean acknowledged(JSONObject report,JSONObject reply,long now)throws Exception {
        if(!"ELIGIBLE".equals(MediaAssociation.automaticPhotoEligibility(report,reply)))return false;
        String id=report.getString("report_id"),device=report.getString("device_id");
        if(!device.matches("[A-Za-z0-9_-]{1,96}"))throw new IOException("AUTO_PHOTO_IDENTITY_INVALID");
        long time=sampled(report);
        if(!recent(now,time))return false;
        JSONObject next=new JSONObject(state.toString());JSONArray jobs=next.getJSONArray("jobs"),done=next.getJSONArray("done");
        for(int i=done.length()-1;i>=0;i--)if(now-done.getJSONObject(i).getLong("at")>TTL)done.remove(i);
        for(JSONArray rows:new JSONArray[]{jobs,done})for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);if(id.equals(row.getString("report_id"))&&device.equals(row.getString("device_id")))return false;
        }
        if(jobs.length()>=16||done.length()>=256)return false;
        long previous=device.equals(next.optString("cadence_device"))?Math.max(next.optLong("queued"),next.optLong("captured")):0;
        if(!due(now,previous))return false;
        jobs.put(new JSONObject().put("report_id",id).put("device_id",device).put("sampled_at",time)
                .put("critical",false).put("expires_at",time+INTERVAL).put("attempt",0).put("next_at",0));
        if(!device.equals(next.optString("cadence_device")))next.put("captured",0);
        next.put("cadence_device",device).put("queued",now);
        save(next);return true;
    }
    synchronized JSONObject next(long now,String device)throws Exception {
        JSONArray jobs=state.getJSONArray("jobs");
        for(int i=0;i<jobs.length();i++) {
            JSONObject j=new JSONObject(jobs.getJSONObject(i).toString());
            if(device.equals(state.optString("cadence_device"))&&now>=state.optLong("captured"))j.put("capture_not_before",state.optLong("captured")+INTERVAL);
            boolean expired=now-j.getLong("sampled_at")>TTL||j.getLong("sampled_at")>now+60000;
            if(!device.equals(j.getString("device_id"))||expired)j.put("terminal",true).put("error","AUTO_PHOTO_EXPIRED_OR_IDENTITY_CHANGED");
            if(j.optBoolean("terminal")||now>=j.optLong("next_at"))return j;
        }
        return null;
    }
    synchronized void result(JSONObject job,JSONObject result,long now)throws Exception {
        JSONObject next=new JSONObject(state.toString()),j=find(next,job);if(j==null)return;
        long captured=result.optLong("captured_at");
        if(captured>0&&!j.optBoolean("critical"))next.put("cadence_device",j.getString("device_id")).put("captured",Math.max(next.optLong("captured"),captured));
        String phase=result.optString("state");j.put("last_state",phase).put("error",result.optString("error"));
        for(String key:new String[]{"captured_at","source","sha256","bytes"})if(result.has(key))j.put(key,result.get(key));
        if("completed".equals(phase)||"discarded".equals(phase))j.put("terminal",true);
        else if("failed".equals(phase)) {
            int attempt=j.getInt("attempt")+1;j.put("attempt",attempt);
            if(attempt>=3||result.optBoolean("permanent"))j.put("terminal",true);
            else j.put("next_at",now+(attempt==1?30000:120000));
        } else j.put("next_at",now+("waiting".equals(phase)?30000:2000));
        save(next);
    }
    synchronized void removed(JSONObject job,long now)throws Exception {
        JSONObject next=new JSONObject(state.toString());JSONArray jobs=next.getJSONArray("jobs"),done=next.getJSONArray("done");
        for(int i=0;i<jobs.length();i++)if(same(jobs.getJSONObject(i),job)){jobs.remove(i);break;}
        for(int i=done.length()-1;i>=0;i--)if(now-done.getJSONObject(i).getLong("at")>TTL)done.remove(i);
        if(done.length()>=256)throw new IOException("AUTO_PHOTO_DONE_FULL");
        JSONObject row=new JSONObject().put("device_id",job.getString("device_id")).put("report_id",job.getString("report_id"))
                .put("at",now).put("state",job.optString("last_state","discarded")).put("error",job.optString("error"));
        for(String key:new String[]{"captured_at","source","sha256","bytes"})if(job.has(key))row.put(key,job.get(key));done.put(row);
        save(next);
    }
    private static boolean same(JSONObject a,JSONObject b)throws Exception{return a.getString("report_id").equals(b.getString("report_id"))&&a.getString("device_id").equals(b.getString("device_id"));}
    private static JSONObject find(JSONObject s,JSONObject job)throws Exception {JSONArray jobs=s.getJSONArray("jobs");for(int i=0;i<jobs.length();i++)if(same(jobs.getJSONObject(i),job))return jobs.getJSONObject(i);return null;}
    private void save(JSONObject next)throws Exception {RescueFiles.write(file,next.toString());state=next;}
    synchronized boolean pending()throws Exception{return state.getJSONArray("jobs").length()>0;}
    synchronized long reportAt(){long last=Math.max(state.optLong("queued"),state.optLong("captured"));return last>0?last+INTERVAL:0;}
}
