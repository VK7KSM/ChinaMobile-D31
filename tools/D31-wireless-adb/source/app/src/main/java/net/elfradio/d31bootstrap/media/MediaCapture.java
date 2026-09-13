package net.elfradio.d31bootstrap.media;

import java.io.*;
import org.json.JSONObject;

/** 有界同步采集，宿主必须在工作线程调用；结果落盘不等于Web已接收。 */
public final class MediaCapture {
    public interface Clock { long wall(); long elapsed(); }
    public static final Clock SYSTEM_CLOCK=new Clock() {
        public long wall(){return System.currentTimeMillis();}
        public long elapsed(){return System.nanoTime()/1000000L;}
    };
    public interface Device {
        Captured capture(CaptureRequest request, File output, Cancellation cancel, long deadlineElapsed, AudioGuard guard) throws Exception;
    }
    public static final class Captured {
        public final long startedAt, endedAt;
        public final String source, mime;
        public Captured(long startedAt,long endedAt,String source,String mime){this.startedAt=startedAt;this.endedAt=endedAt;this.source=source;this.mime=mime;}
    }
    private final File root;
    private final Device device;
    private final AudioGuard guard;
    private final Clock clock;
    public MediaCapture(File privateRoot,Device device,AudioGuard guard,Clock clock) throws IOException {
        if(device==null||guard==null||clock==null) throw new IllegalArgumentException("MEDIA_MISSING_DEPENDENCY");
        this.root=MediaFiles.directory(privateRoot.getAbsoluteFile());this.device=device;this.guard=guard;this.clock=clock;
    }
    public JSONObject photo(CaptureRequest request,Cancellation cancel) throws Exception {
        if(!"photo".equals(request.kind)) throw new IOException("MEDIA_WRONG_CAPTURE_KIND"); return capture(request,cancel);
    }
    public JSONObject audio(CaptureRequest request,Cancellation cancel) throws Exception {
        if(!"audio".equals(request.kind)) throw new IOException("MEDIA_WRONG_CAPTURE_KIND"); return capture(request,cancel);
    }
    private JSONObject capture(CaptureRequest request,Cancellation cancel) throws Exception {
        if(cancel==null) throw new IllegalArgumentException("MEDIA_MISSING_CANCELLATION");
        try(MediaFiles.Lease lease=MediaFiles.lease(root)) {
            File records=MediaFiles.directory(new File(root,"captures"));
            File folder=new File(records,request.id);
            File intent=new File(folder,"intent.json"),receipt=new File(folder,"result.json");
            if(folder.exists()) {
                MediaFiles.directory(folder);
                if(!intent.isFile()||!MediaFiles.read(intent).toString().equals(request.identity().toString())) throw new IOException("MEDIA_ID_CONFLICT");
                if(!receipt.isFile()) throw new IOException("MEDIA_INTERRUPTED_NO_REPLAY");
                JSONObject saved=MediaFiles.read(receipt);
                if("completed".equals(saved.optString("state"))) {
                    File artifact=MediaFiles.plain(new File(folder,"photo".equals(request.kind)?"capture.jpg":"capture.m4a"));
                    if(!artifact.isFile()||artifact.length()!=saved.getLong("bytes")||!MediaFiles.hash(artifact).equals(saved.getString("sha256")))
                        throw new IOException("MEDIA_ARTIFACT_MISSING_OR_CHANGED");
                }
                return saved;
            }
            File[] entries=records.listFiles(); if(entries==null||entries.length>=128) throw new IOException("MEDIA_ARCHIVE_FULL");
            long occupied=0;for(File entry:entries){File[] files=entry.listFiles();if(files!=null)for(File f:files)occupied+=f.length();}
            if(occupied>=64L*1024*1024||root.getUsableSpace()<16L*1024*1024) throw new IOException("MEDIA_SPACE_UNAVAILABLE");
            cancel.check(); if(request.expiresAt<=clock.wall()) throw new IOException("MEDIA_EXPIRED");
            guard.requireIdle(); MediaFiles.directory(folder); MediaFiles.writeNew(intent,request.identity());
            File partial=MediaFiles.plain(new File(folder,"capture.part"));
            JSONObject result=new JSONObject().put("schemaVersion",1).put("task_id",request.id).put("report_id",request.reportId)
                    .put("kind",request.kind).put("uploaded",false).put("report_association","EXPLICIT_ID");
            try {
                if(!partial.createNewFile()) throw new IOException("MEDIA_NO_OVERWRITE");
                long budget="photo".equals(request.kind)?15000L:request.durationMs+5000L;
                long deadline=clock.elapsed()+Math.min(budget,request.expiresAt-clock.wall());
                Captured captured=device.capture(request,partial,cancel,deadline,guard);
                cancel.check(); guard.requireIdle();
                if(clock.elapsed()>deadline) throw new IOException("MEDIA_TIMED_OUT");
                if(captured.startedAt<=0||captured.endedAt<captured.startedAt||captured.source==null||captured.source.isEmpty()
                        ||!(("photo".equals(request.kind)?"image/jpeg":"audio/mp4").equals(captured.mime))) throw new IOException("MEDIA_CAPTURE_METADATA_INVALID");
                if(!partial.isFile()||partial.length()==0||partial.length()>MediaFiles.MAX_BYTES) throw new IOException("MEDIA_OUTPUT_INVALID");
                try(RandomAccessFile sync=new RandomAccessFile(partial,"rw")){sync.getFD().sync();}
                File output=MediaFiles.plain(new File(folder,"photo".equals(request.kind)?"capture.jpg":"capture.m4a"));
                if(output.exists()||!partial.renameTo(output)) throw new IOException("MEDIA_OUTPUT_COMMIT_FAILED");
                result.put("state","completed").put("path",output.getAbsolutePath()).put("bytes",output.length()).put("sha256",MediaFiles.hash(output))
                        .put("started_at_ms",captured.startedAt).put("ended_at_ms",captured.endedAt).put("captured_at",captured.startedAt)
                        .put("duration_ms",captured.endedAt-captured.startedAt).put("source",captured.source).put("mime",captured.mime);
            } catch(Exception failure) {
                String code=failure.getMessage();if(code==null||!code.matches("[A-Z0-9_]{1,100}"))code="MEDIA_PLATFORM_FAILED";
                result.put("state",cancel.isCancelled()?"cancelled":"failed").put("error",code);
                if(partial.exists()&&!partial.delete())result.put("partial_cleanup","PENDING");
            }
            MediaFiles.writeNew(receipt,result); return result;
        }
    }
}
