package net.elfradio.d31bootstrap.media;

import java.io.IOException;
import org.json.JSONObject;

/** 先持久化原值和意图再改路由；音量及静音值从不写入。仅在专用媒体工作线程使用。 */
public final class DownlinkRouteLease implements AutoCloseable {
    public interface Port {
        int mode()throws Exception;
        boolean speaker()throws Exception;
        void speaker(boolean value)throws Exception;
        boolean focus()throws Exception;
        void abandonFocus()throws Exception;
    }
    public interface Journal {
        JSONObject read()throws Exception;
        void write(JSONObject record)throws Exception;
        void clear()throws Exception;
    }
    private final Port port;
    private final Journal journal;
    private final AudioGuard idle;
    private boolean focused,opened;
    public DownlinkRouteLease(Port port,Journal journal,AudioGuard idle){this.port=port;this.journal=journal;this.idle=idle;}
    public void openPtt()throws Exception {
        if(opened)throw new IOException("MEDIA_ROUTE_NOT_REUSABLE");
        if(journal.read()!=null)throw new IOException("MEDIA_ROUTE_RECOVERY_PENDING");
        idle.requireIdle();
        int oldMode=port.mode();boolean oldSpeaker=port.speaker();
        if(oldMode!=0)throw new IOException("MEDIA_ROUTE_BUSY");
        opened=true;
        journal.write(new JSONObject().put("schema",1).put("old_mode",oldMode).put("old_speaker",oldSpeaker)
                .put("target_mode",0).put("target_speaker",true));
        try{
            focused=port.focus();if(!focused)throw new IOException("MEDIA_FOCUS_DENIED");
            if(!oldSpeaker)port.speaker(true);
            if(port.mode()!=0||!port.speaker())throw new IOException("MEDIA_ROUTE_APPLY_UNCONFIRMED");
        }catch(Exception failure){
            try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;
        }
    }
    /** 播放已停止、焦点已释放后调用；空闲门必须保留蜂窝/Nexui/其它音频检查。 */
    public void recover()throws Exception {
        JSONObject saved=journal.read();if(saved==null)return;
        if(saved.getInt("schema")!=1||saved.getInt("old_mode")!=0||saved.getInt("target_mode")!=0
                ||!saved.getBoolean("target_speaker"))throw new IOException("MEDIA_ROUTE_JOURNAL_INVALID");
        boolean old=saved.getBoolean("old_speaker");
        idle.requireIdle();
        if(port.mode()!=0)throw new IOException("MEDIA_ROUTE_OWNERSHIP_LOST");
        // 已被用户改回原值就不再写；不同通信模式不作任何恢复猜测。
        if(!old&&port.speaker()){
            port.speaker(false);
            if(port.speaker())throw new IOException("MEDIA_ROUTE_RESTORE_UNCONFIRMED");
        }
        journal.clear();
    }
    @Override public void close()throws Exception {
        releaseFocus();
        recover();
    }
    public void releaseFocus()throws Exception {
        if(focused){port.abandonFocus();focused=false;}
    }
}
