package net.elfradio.d31bootstrap.media;

import java.io.*;
import org.json.JSONObject;

/** 必须由单一长驻core持有；accept立即返回，不能在短命exec中创建后退出。 */
public final class AlarmTasks implements AutoCloseable {
    public static final int DURATION_MS=10000;
    public interface Tone extends AutoCloseable { void close() throws Exception; }
    public interface Player { Tone start(int durationMs) throws Exception; }
    public interface Ticket { void cancel(); }
    public interface Scheduler { Ticket after(Runnable callback,long delayMs) throws Exception; }
    public interface Changed { void changed(JSONObject state); }
    private final File root,records;
    private final Player player;
    private final Scheduler scheduler;
    private final AudioGuard guard;
    private final MediaCapture.Clock clock;
    private final Changed listener;
    private final MediaFiles.Lease ownerLease;
    private MediaFiles.Lease lease;
    private Tone tone;
    private Ticket ticket;
    private String owner="",state="idle",error="";
    private long startedAt,deadline,generation;
    private boolean closed;

    public AlarmTasks(File privateRoot,Player player,Scheduler scheduler,AudioGuard guard,MediaCapture.Clock clock,Changed listener)throws Exception {
        if(player==null||scheduler==null||clock==null)throw new IllegalArgumentException("ALARM_MISSING_DEPENDENCY");
        this.root=MediaFiles.directory(privateRoot.getAbsoluteFile());this.records=MediaFiles.directory(new File(root,"alarms"));
        this.player=player;this.scheduler=scheduler;this.guard=guard;this.clock=clock;this.listener=listener;
        ownerLease=MediaFiles.lease(new File(root,"alarm-owner"));
        try {
            File[] entries=records.listFiles();if(entries==null)throw new IOException("ALARM_JOURNAL_UNREADABLE");
            for(File entry:entries){File intent=new File(entry,"intent.json"),result=new File(entry,"result.json");
                if(intent.isFile()&&!result.exists())MediaFiles.writeNew(result,new JSONObject().put("task_id",entry.getName()).put("state","interrupted").put("replayed",false));}
        }catch(Exception failure){ownerLease.close();throw failure;}
    }
    public synchronized JSONObject accept(JSONObject task)throws Exception {
        if(closed)throw new IOException("ALARM_OWNER_CLOSED");
        String id=task.optString("id"),type=task.optString("type");
        if(!id.matches("[A-Za-z0-9_-]{1,96}")||(!"play_alarm".equals(type)&&!"stop_alarm".equals(type)))throw new IOException("ALARM_TASK_INVALID");
        JSONObject identity=new JSONObject().put("id",id).put("type",type).put("params",task.getJSONObject("params"));
        File folder=MediaFiles.plain(new File(records,id)),intent=new File(folder,"intent.json"),result=new File(folder,"result.json");
        if(folder.exists()) {
            if(!intent.isFile()||!MediaFiles.read(intent).toString().equals(identity.toString()))throw new IOException("ALARM_ID_CONFLICT");
            if(id.equals(owner)&&tone!=null){if(task.optBoolean("cancel_requested"))finish("cancelled");return snapshot();}
            if(result.isFile())return MediaFiles.read(result);
            JSONObject interrupted=new JSONObject().put("task_id",id).put("state","interrupted").put("replayed",false);
            MediaFiles.writeNew(result,interrupted);return interrupted;
        }
        if(task.optBoolean("cancel_requested")||task.optLong("expires_at")<=clock.wall())throw new IOException("ALARM_CANCELLED_OR_EXPIRED");
        File[] files=records.listFiles();if(files==null||files.length>=256)throw new IOException("ALARM_JOURNAL_FULL");
        if("stop_alarm".equals(type)) {
            MediaFiles.directory(folder);MediaFiles.writeNew(intent,identity);
            finish("stopped");JSONObject stopped=snapshot().put("stop_task_id",id);MediaFiles.writeNew(result,stopped);return stopped;
        }
        if(tone!=null)throw new IOException("MEDIA_BUSY");
        if(guard==null)throw new IOException("MEDIA_AUDIO_STATE_UNKNOWN");
        guard.requireIdle();lease=MediaFiles.lease(root);
        try {
            MediaFiles.directory(folder);MediaFiles.writeNew(intent,identity);owner=id;state="starting";error="";startedAt=0;
            tone=player.start(DURATION_MS);if(tone==null)throw new IOException("ALARM_START_FAILED");
            startedAt=clock.wall();deadline=clock.elapsed()+DURATION_MS;state="playing";final long token=++generation;
            ticket=scheduler.after(new Runnable(){public void run(){pump(token);}},100);
            if(ticket==null)throw new IOException("ALARM_TIMER_UNAVAILABLE");
            notifyChanged();return snapshot();
        } catch(Exception failure) {error="ALARM_START_FAILED";finish("failed");throw failure;}
    }
    private synchronized void pump(final long token) {
        if(token!=generation||tone==null)return;
        try {
            if(clock.elapsed()>=deadline){finish("completed");return;}
            guard.requireIdle();
            ticket=scheduler.after(new Runnable(){public void run(){pump(token);}},Math.min(100,deadline-clock.elapsed()));
            if(ticket==null)throw new IOException("ALARM_TIMER_UNAVAILABLE");
        } catch(Exception failure){error="ALARM_INTERRUPTED_OR_AUDIO_BUSY";try{finish("failed");}catch(Exception ignored){}}
    }
    public synchronized JSONObject snapshot()throws Exception {
        return new JSONObject().put("task_id",owner).put("state",state).put("started_at_ms",startedAt)
                .put("duration_ms",DURATION_MS).put("error",error).put("owner_scope","LONG_LIVED_CORE");
    }
    /** 返回既有normalizeAlarm合同；宿主放入result.alarm或周期报告alarm。 */
    public synchronized JSONObject reportSnapshot()throws Exception {
        return new JSONObject().put("state","cancelled".equals(state)?"interrupted":state)
                .put("started_at_ms",startedAt).put("duration_ms",DURATION_MS);
    }
    private void finish(String outcome)throws Exception {
        ++generation;Exception failed=null;
        if(ticket!=null){try{ticket.cancel();}catch(Exception failure){failed=failure;}finally{ticket=null;}}
        if(tone!=null){try{tone.close();}catch(Exception failure){failed=failure;}finally{tone=null;}}
        if(lease!=null){try{lease.close();}catch(Exception failure){failed=failure;}finally{lease=null;}}
        state=outcome;if(failed!=null){state="failed";error="ALARM_RELEASE_FAILED";}
        if(!owner.isEmpty()) {
            File result=new File(new File(records,owner),"result.json");
            if(!result.exists())try{MediaFiles.writeNew(result,snapshot().put("ended_at_ms",clock.wall()));}
            catch(Exception failure){error="ALARM_RECEIPT_SAVE_FAILED";failed=failure;}
        }
        notifyChanged();if(failed!=null)throw failed;
    }
    private void notifyChanged(){if(listener!=null)try{listener.changed(snapshot());}catch(Exception ignored){}}
    public synchronized void close()throws Exception {if(closed)return;closed=true;try{finish("interrupted");}finally{ownerLease.close();}}
}
