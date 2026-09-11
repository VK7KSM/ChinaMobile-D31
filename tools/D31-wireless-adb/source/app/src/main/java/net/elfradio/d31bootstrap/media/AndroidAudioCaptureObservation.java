package net.elfradio.d31bootstrap.media;

import android.content.Context;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** API23 APP身份Binder转储，管道有界读取；不创建dumpsys进程、不落盘客户私有数据。 */
final class AndroidAudioCaptureObservation implements AudioCaptureObservation.Reader {
    private final Context context;
    AndroidAudioCaptureObservation(Context context) { this.context = context; }
    public AudioCaptureObservation.Sample read(Cancellation cancellation) throws Exception {
        cancellation.check();
        if (android.os.Build.VERSION.SDK_INT != 23 || !"hct6735_66_m0".equals(android.os.Build.DEVICE))
            throw new IOException("MEDIA_INPUT_PLATFORM_UNKNOWN");
        MediaReadiness.requireApplicationIdentity(context, AppMediaContract.PACKAGE);
        long deadline = SystemClock.elapsedRealtime() + AudioInputOwnership.MAX_SAMPLE_MS;
        AudioInputOwnership.Dump flinger = dump("media.audio_flinger", cancellation, deadline);
        AudioInputOwnership.Dump policy = dump("media.audio_policy", cancellation, deadline);
        cancellation.check(); long began = SystemClock.elapsedRealtime();
        org.json.JSONObject external = AndroidAudioOccupancyCheck.inspect(context);
        cancellation.check(); long ended = SystemClock.elapsedRealtime();
        if (ended > deadline) throw new IOException("MEDIA_INPUT_SAMPLE_TIMEOUT");
        return new AudioCaptureObservation.Sample(flinger, policy, external, began, ended);
    }
    private static AudioInputOwnership.Dump dump(String name, Cancellation cancellation, long pairDeadline) throws Exception {
        long began = SystemClock.elapsedRealtime(), deadline = Math.min(pairDeadline, began + 600);
        Object raw = Class.forName("android.os.ServiceManager").getMethod("checkService", String.class).invoke(null, name);
        if (!(raw instanceof IBinder)) throw new IOException("MEDIA_INPUT_SERVICE_MISSING");
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
            try { ((IBinder) raw).dumpAsync(pipe[1].getFileDescriptor(), new String[0]); }
            finally { pipe[1].close(); }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096];
            StructPollfd fd = new StructPollfd(); fd.fd = pipe[0].getFileDescriptor();
            fd.events = (short) (OsConstants.POLLIN | OsConstants.POLLHUP);
            for (;;) {
                cancellation.check(); long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new IOException("MEDIA_INPUT_DUMP_TIMEOUT");
                if (Os.poll(new StructPollfd[]{fd}, (int) Math.min(100, remaining)) == 0) continue;
                if ((fd.revents & (OsConstants.POLLERR | OsConstants.POLLNVAL)) != 0) throw new IOException("MEDIA_INPUT_DUMP_PIPE");
                int count = input.read(buffer);
                if (count == -1) break;
                if (bytes.size() + count > AudioInputOwnership.MAX_CHARS) throw new IOException("MEDIA_INPUT_DUMP_LIMIT");
                bytes.write(buffer, 0, count);
            }
            return new AudioInputOwnership.Dump(new String(bytes.toByteArray(), "UTF-8"), true, began, SystemClock.elapsedRealtime());
        } finally {
            // 写端可能在事务异常之前尚未关闭；PFD.close可重复，禁止另造共享FD的流包装。
            pipe[1].close();
        }
    }
}
