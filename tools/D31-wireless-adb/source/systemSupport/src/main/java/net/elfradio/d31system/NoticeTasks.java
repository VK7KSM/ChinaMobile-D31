package net.elfradio.d31system;

/** 读取可在锁外阻塞；提交与代次失效互斥，拒绝销毁后的旧结果。 */
final class NoticeTasks {
    interface Reporter { void report(Throwable error); }
    private final Reporter reporter;
    private long generation;
    private boolean active;
    NoticeTasks(Reporter reporter) { this.reporter=reporter; }
    synchronized long start() { active=true; return ++generation; }
    synchronized boolean accepts(long token) { return active && token==generation; }
    void run(long token,Runnable task) { if (accepts(token)) guard(task); }
    synchronized boolean commit(long token,Runnable mutation) {
        if (!accepts(token)) return false;
        guard(mutation); return true;
    }
    synchronized void stop(long token,Runnable cleanup) {
        if (!accepts(token)) return;
        active=false; ++generation; guard(cleanup);
    }
    void guard(Runnable task) {
        try { task.run(); }
        catch (RuntimeException | LinkageError error) {
            try { reporter.report(error); } catch (RuntimeException | LinkageError ignored) { }
        }
    }
}
