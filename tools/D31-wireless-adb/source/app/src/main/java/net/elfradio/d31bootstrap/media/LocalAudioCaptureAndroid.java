package net.elfradio.d31bootstrap.media;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.IOException;

/** API23真实APP调用；不访问AudioManager，不设置模式、路由、焦点或音量。 */
@android.annotation.TargetApi(23)
final class LocalAudioCaptureAndroid implements LocalAudioCapture.Recorder {
    private final AudioRecord record;
    private boolean released;
    LocalAudioCaptureAndroid()throws IOException {
        int minimum=AudioRecord.getMinBufferSize(LocalAudioCapture.RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<=0||minimum>262144)throw new IOException("MEDIA_LOCAL_AUDIO_BUFFER_INVALID");
        try {
            record=new AudioRecord(MediaRecorder.AudioSource.MIC,LocalAudioCapture.RATE,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,1280));
        } catch(SecurityException denied) {
            // 前检与实际创建之间权限仍可能变化；不回显平台异常中的私有归属信息。
            throw new IOException("MEDIA_LOCAL_AUDIO_PERMISSION_DENIED");
        }
    }
    public int state(){return record.getState();}
    public int recordingState(){return record.getRecordingState();}
    public int sessionId(){return record.getAudioSessionId();}
    public synchronized void start(Cancellation cancellation)throws Exception {
        cancellation.check();if(released)throw new IllegalStateException("MEDIA_LOCAL_AUDIO_RELEASED");record.startRecording();
    }
    public int read(short[] samples,int count){return record.read(samples,0,count,AudioRecord.READ_NON_BLOCKING);}
    public synchronized void stop(){if(!released)record.stop();}
    public synchronized void release(){if(!released){record.release();released=true;}}
}
