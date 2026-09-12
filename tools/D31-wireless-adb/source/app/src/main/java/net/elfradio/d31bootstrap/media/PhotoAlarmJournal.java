package net.elfradio.d31bootstrap.media;

import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

/** 原会话启动意图及终态只写一次；服务重建可读，不删除、不覆盖完成原件。 */
final class PhotoAlarmJournal {
    private final File root;
    PhotoAlarmJournal(File directory)throws Exception {root=MediaFiles.directory(directory);}
    private File folder(String id)throws Exception {
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,96}"))throw new IOException("VISUAL_SESSION_INVALID");
        return MediaFiles.plain(new File(root,id));
    }
    JSONObject read(String id)throws Exception {
        File folder=folder(id);if(!folder.exists())return null;
        File result=new File(folder,"completion.json");
        if(result.isFile()){
            JSONObject value=MediaFiles.read(result);
            if(!id.equals(value.optString("session_id"))||!Boolean.TRUE.equals(value.opt("closed"))
                    ||!Boolean.TRUE.equals(value.opt("cleanup_complete")))throw new IOException("VISUAL_TERMINAL_INVALID");
            return value;
        }
        File intent=new File(folder,"intent.json");
        if(!intent.isFile())throw new IOException("VISUAL_INTENT_INCOMPLETE_NO_REPLAY");
        JSONObject original=MediaFiles.read(intent);
        if(!id.equals(original.optString("session_id")))throw new IOException("VISUAL_INTENT_INVALID");
        return new JSONObject().put("session_id",id).put("mode",original.getString("mode")).put("state","interrupted")
                .put("closed",true).put("cleanup_complete",false).put("error","VISUAL_PREVIOUS_COMPLETION_UNKNOWN").put("replayed",false);
    }
    JSONObject begin(PhotoAlarmOffer offer)throws Exception {
        JSONObject prior=read(offer.id);if(prior!=null)return prior;
        File[] entries=root.listFiles();if(entries==null||entries.length>=1024)throw new IOException("VISUAL_JOURNAL_FULL");
        File directory=folder(offer.id);MediaFiles.directory(directory);
        MediaFiles.writeNew(new File(directory,"intent.json"),new JSONObject().put("session_id",offer.id).put("mode",offer.mode)
                .put("camera",offer.camera).put("expires_at",offer.expiresAt));
        return null;
    }
    JSONObject complete(JSONObject value)throws Exception {
        String id=value.getString("session_id");File directory=folder(id);
        if(!new File(directory,"intent.json").isFile())throw new IOException("VISUAL_TERMINAL_WITHOUT_INTENT");
        if(!Boolean.TRUE.equals(value.opt("closed"))||!Boolean.TRUE.equals(value.opt("cleanup_complete")))
            throw new IOException("VISUAL_TERMINAL_CLEANUP_PENDING");
        File destination=new File(directory,"completion.json");
        if(destination.isFile())return read(id);
        MediaFiles.writeNew(destination,value);return read(id);
    }
}
