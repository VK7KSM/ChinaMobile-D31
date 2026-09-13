package net.elfradio.d31bootstrap.media;

import java.io.IOException;

public final class Cancellation {
    private volatile boolean cancelled;
    public void cancel() { cancelled=true; }
    public boolean isCancelled() { return cancelled; }
    public void check() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("MEDIA_CANCELLED");
    }
}
