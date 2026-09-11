package net.elfradio.d31bootstrap;

import org.json.JSONArray;
import org.json.JSONObject;

/** 配置完成后的有限注册回报；正常空闲不增加请求。 */
final class RemoteSipFollowup {
    private static final long[] DELAYS={0,5000,15000,30000,60000,120000};
    private String task="",target="",slot="";
    private long started;
    private int step=DELAYS.length;
    void completed(JSONObject receipt,long now){
        JSONObject r=receipt.optJSONObject("result");
        if(!"success".equals(receipt.optString("state"))||r==null||!r.optBoolean("applied")
                ||!r.has("target")||!r.has("account_id"))return;
        task=receipt.optString("task_id");target=r.optString("target");slot=r.optString("account_id");started=now;step=0;
    }
    boolean due(long now){
        if(step>=DELAYS.length||now<started+DELAYS[step])return false;
        step++;return true;
    }
    void observed(JSONObject snapshot){
        JSONArray rows=snapshot.optJSONArray("sip_registrations");if(rows==null)return;
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.optJSONObject(i);
            if(r!=null&&target.equals(r.optString("target"))&&slot.equals(r.optString("account_id"))
                    &&task.equals(r.optString("config_task_id"))&&"registered".equals(r.optString("state")))step=DELAYS.length;
        }
    }
}
