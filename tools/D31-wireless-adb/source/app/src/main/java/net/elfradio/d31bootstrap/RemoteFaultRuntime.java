package net.elfradio.d31bootstrap;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.elfradio.d31bootstrap.diagnostics.collection.AndroidCollectionAccess;
import net.elfradio.d31bootstrap.faults.AndroidFaultSources;
import net.elfradio.d31bootstrap.faults.FaultMonitor;
import net.elfradio.d31bootstrap.faults.FaultPolicy;

/** 本地取证不等待云注册；调度与扫描都不占用报告或开机主线程。 */
final class RemoteFaultRuntime implements AutoCloseable {
    interface Ready { boolean get() throws Exception; }
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "d31-fault-schedule"); thread.setDaemon(true); return thread;
    });
    private FaultMonitor monitor;
    private boolean closed;

    RemoteFaultRuntime(Ready ready) {
        scheduler.scheduleWithFixedDelay(() -> tick(ready), 60, 60, TimeUnit.SECONDS);
    }

    private synchronized void tick(Ready ready) {
        if (closed) return;
        try {
            if (!ready.get() || RemoteMaintenance.reserved()) return;
            if (monitor == null) monitor = new FaultMonitor(new File(AndroidFaultSources.ARCHIVE_ROOT),
                    new AndroidFaultSources(), AndroidCollectionAccess.systemClock(), FaultPolicy.continuousDefaults());
            monitor.tick();
        } catch (Exception unavailable) {
            // 下轮按原周期再查，不唤醒报告、不紧密重试、不阻塞本地维护。
        }
    }

    @Override public synchronized void close() {
        closed = true;
        scheduler.shutdownNow();
        if (monitor != null) monitor.close();
    }
}
