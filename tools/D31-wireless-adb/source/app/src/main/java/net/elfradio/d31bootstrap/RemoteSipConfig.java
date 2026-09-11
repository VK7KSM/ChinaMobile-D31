package net.elfradio.d31bootstrap;

import java.io.IOException;
import org.json.JSONObject;

final class RemoteSipConfig {
    static JSONObject validate(JSONObject p) throws Exception {
        String target=p.getString("target"),slot=p.getString("account_id");
        if (!("quik".equals(target)&&"default".equals(slot))
                && !("nexui".equals(target)&&slot.matches("line-[1-4]")))throw new IOException("不支持的应用或线路");
        String server=p.getString("server"),user=p.getString("username"),password=p.getString("password");
        String auth=p.optString("auth_username",user),transport=p.getString("transport");
        if(!server.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")||!user.matches("[A-Za-z0-9_.+-]{1,128}")
                ||!user.equals(auth)||password.isEmpty()||password.length()>256||!password.equals(password.trim())
                ||password.matches("(?s).*[\\x00-\\x1f\\x7f].*")||!transport.matches("udp|tcp|tls")
                ||p.getInt("port")<1||p.getInt("port")>65535)throw new IOException("账号参数无效");
        if(p.has("realm")&&(!"quik".equals(target)||!p.getString("realm").matches("[A-Za-z0-9*_.@:-]{1,253}")))
            throw new IOException("认证域不受支持或无效");
        JSONObject result=new JSONObject().put("target",target).put("account_id",slot).put("server",server)
                .put("username",user).put("auth_username",auth).put("password",password).put("transport",transport).put("port",p.getInt("port"));
        if(p.has("realm"))result.put("realm",p.getString("realm"));
        return result;
    }
    static String registration(String state){
        if("SUCCESSFULLY".equals(state))return "registered";
        if("REGISTERING".equals(state))return "registering";
        if("FAILED".equals(state))return "failed";
        if("DISABLED".equals(state)||"UNREGISTERING".equals(state))return "unregistered";
        return "unknown";
    }
}
