package net.elfradio.d31bootstrap.faults;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess;

/** One bounded worker, zero queued collections, no scheduler and no network side effects. */
public final class FaultMonitor implements Closeable {
    public enum Submission { ACCEPTED, BUSY, THROTTLED, CLOSED }
    private final FaultArchive archive;
    private final FaultSources sources;
    private final CollectionAccess.Clock clock;
    private final FaultPolicy policy;
    private final ExecutorService executor;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Deque<Sample> history = new ArrayDeque<Sample>();
    private volatile boolean closed;
    private volatile String lastFailure = "NONE";
    private volatile String lastOutcome = "NOT_RUN";
    private long lastSubmitted = Long.MIN_VALUE;
    private String currentBoot;

    public FaultMonitor(File root, FaultSources sources, CollectionAccess.Clock clock, FaultPolicy policy) throws IOException {
        if (sources == null || clock == null || policy == null) throw new IllegalArgumentException("MISSING_FAULT_DEPENDENCY");
        this.sources = sources; this.clock = clock; this.policy = policy;
        this.archive = new FaultArchive(root, sources);
        executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable work) {
                Thread thread = new Thread(work, "d31-fault-evidence"); thread.setDaemon(true); return thread;
            }
        });
    }
    public Submission tick() { return collect(); }
    public synchronized Submission collect() {
        if (closed) return Submission.CLOSED;
        if (busy.get()) return Submission.BUSY;
        long now = clock.elapsedRealtimeMillis();
        if (lastSubmitted != Long.MIN_VALUE && now >= lastSubmitted && now - lastSubmitted < policy.tickMs)
            return Submission.THROTTLED;
        if (!busy.compareAndSet(false, true)) return Submission.BUSY;
        lastSubmitted = now;
        executor.execute(new Runnable() {
            @Override public void run() {
                try { cycle(); lastFailure = "NONE"; }
                catch (Exception failure) { lastFailure = "COLLECTION_FAILED"; lastOutcome = "FAILED"; }
                finally { busy.set(false); }
            }
        });
        return Submission.ACCEPTED;
    }
    public boolean isBusy() { return busy.get(); }
    public String lastFailure() { return lastFailure; }
    public String lastOutcome() { return lastOutcome; }
    public JSONObject query(String eventId) throws Exception { return archive.query(eventId); }
    public JSONObject index(int limit) throws Exception { return readIndex(archive.root, limit); }
    public static JSONObject readQuery(File root, String eventId) throws Exception {
        return new FaultArchive(root, null, false).query(eventId);
    }
    public static JSONObject readIndex(File root, int limit) throws Exception {
        return readIndex(root, limit, "");
    }
    public static JSONObject readIndex(File root, int limit, String afterEventId) throws Exception {
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("INVALID_INDEX_LIMIT");
        if (afterEventId == null || !afterEventId.isEmpty() && !afterEventId.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("INVALID_EVENT_CURSOR");
        FaultArchive archive = new FaultArchive(root, null, false); List<String> ids = archive.ids();
        JSONArray items = new JSONArray();
        int first = 0;
        while (first < ids.size() && ids.get(first).compareTo(afterEventId) <= 0) first++;
        String next = "";
        for (int i = first; i < ids.size() && i < first + limit; i++) {
            try { items.put(archive.query(ids.get(i))); }
            catch (Exception corrupt) { items.put(new JSONObject().put("eventId", ids.get(i)).put("state", "INDEX_CORRUPT")); }
            next = ids.get(i);
        }
        JSONObject scan;
        try { scan = FaultArchive.read(new File(root, "scan.json")); }
        catch (Exception missing) { scan = new JSONObject().put("state", "UNAVAILABLE"); }
        return new JSONObject().put("schemaVersion", 1).put("events", items).put("total", ids.size())
                .put("hasMore", ids.size() > first + limit).put("nextAfter", next).put("scan", scan).put("rawContentInSummary", false)
                .put("uploadState", "LOCAL_ONLY").put("rootCause", "NOT_ESTABLISHED");
    }
    @Override public synchronized void close() { closed = true; executor.shutdownNow(); }

    private void cycle() throws Exception {
        sources.checkPrivateRoot(archive.root);
        File lockPath = new File(archive.root, "collector.lock"); FaultArchive.checked(lockPath);
        try (RandomAccessFile file = new RandomAccessFile(lockPath, "rw")) {
            FileLock lock;
            try { lock = file.getChannel().tryLock(); }
            catch (OverlappingFileLockException overlap) { throw new IOException("COLLECTOR_BUSY"); }
            if (lock == null) throw new IOException("COLLECTOR_BUSY");
            try {
                String boot = sources.bootKey();
                if (boot == null || !boot.matches("[a-f0-9]{64}")) throw new IOException("BOOT_UNAVAILABLE");
                long elapsed = clock.elapsedRealtimeMillis();
                File scheduleFile = new File(archive.root, "schedule.json");
                if (scheduleFile.exists()) {
                    JSONObject schedule = FaultArchive.read(scheduleFile);
                    long previous = schedule.getLong("elapsedMs");
                    if (boot.equals(schedule.getString("bootKey")) && elapsed >= previous && elapsed - previous < policy.tickMs) {
                        lastOutcome = "THROTTLED"; return;
                    }
                }
                archive.state(archive.root, "schedule.json", new JSONObject().put("bootKey", boot).put("elapsedMs", elapsed));
                if (!boot.equals(currentBoot) || (!history.isEmpty() && elapsed < history.getLast().elapsed)) history.clear();
                currentBoot = boot;
                while (!history.isEmpty() && elapsed - history.getFirst().elapsed > policy.windowMs) history.removeFirst();
                int work = 0, indexErrors = 0;
                List<String> existing = archive.ids();
                java.util.Set<String> archived = new java.util.HashSet<String>();
                for (String id : existing) if (FaultExports.isArchived(archive.event(id))) archived.add(id);
                for (String id : existing) {
                    if (Thread.currentThread().isInterrupted()) return;
                    if (archived.contains(id)) continue;
                    if (work >= policy.maxCandidates) break;
                    try { if (advance(archive.event(id), boot, clock.elapsedRealtimeMillis())) work++; }
                    catch (Exception damaged) { indexErrors++; }
                }
                if (Thread.currentThread().isInterrupted()) return;
                FaultSources.Scan scan = sources.discover(policy);
                if (scan.candidates.size() > 256) throw new IOException("SOURCE_COUNT_LIMIT");
                int accepted = 0, deferred = 0; boolean capacity = false;
                long diskBytes = archive.bytes(); int retained = existing.size(), count = retained - archived.size();
                java.util.Set<String> sightings = new java.util.HashSet<String>();
                for (FaultSources.Candidate candidate : scan.candidates) {
                    if (Thread.currentThread().isInterrupted()) return;
                    if (!sightings.add(candidate.id())) continue;
                    File event = archive.event(candidate.id());
                    if (event.exists()) {
                        try { observe(event); } catch (Exception damaged) { indexErrors++; }
                        continue;
                    }
                    if (work >= policy.maxCandidates) { deferred++; continue; }
                    if (count >= policy.maxEvents || retained >= FaultExports.MAX_RETAINED_EVENTS
                            || (count + 1L) * policy.reservationBytes() > policy.maxArchiveBytes
                            || diskBytes + policy.reservationBytes() > policy.maxArchiveBytes
                            || archive.root.getUsableSpace() < policy.minFreeBytes + policy.reservationBytes()) {
                        capacity = true; deferred++; continue;
                    }
                    create(event, candidate, boot, clock.elapsedRealtimeMillis());
                    accepted++; count++; retained++; work++;
                    advance(event, boot, clock.elapsedRealtimeMillis());
                    diskBytes = archive.bytes();
                }
                JSONObject context;
                try { context = sources.context(); }
                catch (Exception unavailable) { context = new JSONObject().put("state", "UNAVAILABLE"); }
                if (context.toString().length() > 32768) context = new JSONObject().put("state", "CONTEXT_SIZE_LIMIT");
                if (policy.preSamples > 0) {
                    history.addLast(new Sample(clock.elapsedRealtimeMillis(), clock.wallTimeMillis(), context));
                    while (history.size() > policy.preSamples) history.removeFirst();
                }
                archive.state(archive.root, "scan.json", new JSONObject().put("capturedAtMs", clock.wallTimeMillis())
                        .put("state", capacity ? "CAPACITY_LIMIT" : deferred > 0 || indexErrors > 0 ? "PARTIAL" : "FINISHED")
                        .put("accepted", accepted).put("deferred", deferred).put("eventIndexErrors", indexErrors).put("coverage", scan.coverage)
                        .put("activeEvents", count).put("archivedEvents", archived.size()).put("retainedEvents", retained)
                        .put("retainedBytes", archive.bytes()).put("maxArchiveBytes", policy.maxArchiveBytes)
                        .put("maxActiveEvents", policy.maxEvents).put("maxRetainedEvents", FaultExports.MAX_RETAINED_EVENTS)
                        .put("contextRetention", "BOUNDED_PROCESS_MEMORY_UNTIL_EVENT_FREEZE")
                        .put("automaticEviction", false).put("rawContentInSummary", false));
                lastOutcome = capacity ? "CAPACITY_LIMIT" : "FINISHED";
            } finally { lock.release(); }
        }
    }
    private void create(File event, FaultSources.Candidate source, String boot, long elapsed) throws Exception {
        FaultArchive.directory(event);
        JSONArray pre = new JSONArray();
        int n = 0, usable = 0;
        for (Sample sample : history) {
            if (elapsed < sample.elapsed || elapsed - sample.elapsed > policy.windowMs) continue;
            if (contextAvailable(sample.context)) usable++;
            File path = new File(event, "pre-" + (++n) + ".json"); FaultArchive.jsonNew(path, sample.context);
            pre.put(new JSONObject().put("capturedAtMs", sample.wall).put("ageMs", elapsed - sample.elapsed)
                    .put("artifact", FaultArchive.reference(path)));
        }
        JSONObject descriptor = new JSONObject().put("schemaVersion", 1).put("source", source.json())
                .put("detectedAtMs", clock.wallTimeMillis()).put("detectedElapsedMs", elapsed).put("bootKey", boot)
                .put("preWindow", pre).put("preWindowUsableSamples", usable)
                .put("preWindowState", pre.length() == 0 ? "MISSING" : usable == pre.length() ? "SAMPLED" : "PARTIAL")
                .put("preWindowScope", "SYSTEM_MEMORY_AND_LOAD_BEFORE_DETECTION_NOT_CONTINUOUS_LOGS")
                .put("beforeFaultCoverage", "NOT_ESTABLISHED").put("preFaultLogcat", "UNAVAILABLE")
                .put("postDelayMs", policy.postMs).put("windowMs", policy.windowMs).put("maxAttempts", policy.maxAttempts)
                .put("diagnosticSchemaVersion", 1).put("rootCause", "NOT_ESTABLISHED");
        FaultArchive.jsonNew(new File(event, "event.json"), descriptor);
        archive.state(event, "state.json", initial());
    }
    private JSONObject initial() throws Exception {
        return new JSONObject().put("phase", "PENDING").put("attempts", 0).put("observations", 1)
                .put("observationMeaning", "POLL_SIGHTINGS_NOT_CRASH_COUNT").put("postWindow", "PENDING");
    }
    private void observe(File event) throws Exception {
        if (FaultExports.sealed(event)) return;
        File statePath = new File(event, "state.json");
        if (!statePath.isFile()) return;
        JSONObject state = FaultArchive.read(statePath);
        int observed = state.optInt("observations", 1);
        if (observed < 1000000) {
            state.put("observations", observed + 1).put("lastObservedAtMs", clock.wallTimeMillis());
            archive.state(event, "state.json", state);
        }
    }
    private boolean advance(File event, String boot, long elapsed) throws Exception {
        File descriptorPath = new File(event, "event.json");
        if (!descriptorPath.isFile()) return false;
        JSONObject descriptor = FaultArchive.read(descriptorPath);
        JSONObject state;
        try { state = FaultArchive.read(new File(event, "state.json")); }
        catch (Exception missing) { state = initial().put("reason", "STATE_RECOVERED_FROM_ATTEMPTS"); }
        String phase = state.getString("phase");
        if (phase.equals("COMPLETE") || phase.equals("PARTIAL")) return false;
        FaultSources.Candidate candidate = FaultSources.Candidate.parse(descriptor.getJSONObject("source"));
        if (!candidate.id().equals(event.getName())) throw new IOException("EVENT_ID_MISMATCH");
        int attempts = 0; JSONObject last = null;
        for (int i = 1; i <= 3; i++) {
            File path = new File(event, "attempt-" + i); FaultArchive.checked(path);
            if (!path.exists()) break;
            attempts = i;
            File report = new File(path, "report.json");
            last = report.isFile() ? FaultArchive.read(report) : null;
        }
        boolean captured = last != null && "COMPLETE".equals(last.getJSONObject("diagnostic").optString("state"));
        boolean retry = last == null || last.optBoolean("retryable", false);
        int maximum = Math.min(policy.maxAttempts, descriptor.getInt("maxAttempts"));
        if (!captured && retry && attempts < maximum) {
            boolean sameBoot = boot.equals(state.optString("attemptBoot"));
            long lastElapsed = state.optLong("attemptElapsedMs", -1);
            if (sameBoot && lastElapsed >= 0 && elapsed >= lastElapsed
                    && elapsed - lastElapsed < policy.retryMs * Math.max(1, attempts)) return false;
            if (archive.bytes() + policy.maxFileBytes + 65536L > policy.maxArchiveBytes
                    || archive.root.getUsableSpace() < policy.minFreeBytes + policy.maxFileBytes + 65536L) {
                archive.state(event, "state.json", state.put("phase", "CAPACITY_BLOCKED")); return false;
            }
            File attempt = new File(event, "attempt-" + (++attempts)); FaultArchive.directory(attempt);
            state.put("phase", "CAPTURING").put("attempts", attempts).put("attemptBoot", boot).put("attemptElapsedMs", elapsed);
            archive.state(event, "state.json", state);
            FaultSources.Capture capture;
            try { capture = sources.capture(candidate, attempt, policy); }
            catch (Exception failure) {
                capture = new FaultSources.Capture(new JSONObject().put("schemaVersion", 1).put("state", "PARTIAL")
                        .put("reason", AndroidFaultSources.code(failure)).put("items", new JSONArray()), true);
            }
            last = new JSONObject().put("schemaVersion", 1).put("diagnostic", capture.diagnostic).put("retryable", capture.retryable);
            FaultArchive.jsonNew(new File(attempt, "report.json"), last);
            captured = "COMPLETE".equals(capture.diagnostic.optString("state"));
            retry = capture.retryable;
        }
        state.put("attempts", attempts).put("capture", captured ? "COMPLETE" : "PARTIAL");
        if (!captured && retry && attempts < maximum) {
            archive.state(event, "state.json", state.put("phase", "RETRY_WAIT")); return true;
        }
        String post = state.optString("postWindow", "PENDING");
        if (post.equals("PENDING")) {
            long age = elapsed - descriptor.getLong("detectedElapsedMs");
            if (!boot.equals(descriptor.getString("bootKey"))) post = "MISSING_REBOOT";
            else if (age < 0) post = "MISSING_CLOCK_ROLLBACK";
            else if (age > descriptor.getLong("windowMs")) post = "MISSING_DEADLINE";
            else if (age >= descriptor.getLong("postDelayMs")) {
                File context = new File(event, "post.json");
                if (!context.exists()) {
                    JSONObject sample;
                    try { sample = sources.context(); }
                    catch (Exception unavailable) { sample = new JSONObject().put("state", "UNAVAILABLE"); }
                    FaultArchive.jsonNew(context, sample);
                }
                // Existing post evidence is reused after a restart, never replaced with a later sample.
                JSONObject sample = FaultArchive.read(context);
                post = contextAvailable(sample) ? "SAMPLED" : "UNAVAILABLE";
            }
        }
        state.put("postWindow", post);
        String next = post.equals("PENDING") ? "AWAITING_POST"
                : captured && post.equals("SAMPLED") && descriptor.optInt("preWindowUsableSamples", 0) > 0 ? "COMPLETE" : "PARTIAL";
        state.put("phase", next).put("verificationScope", "SOURCE_BYTES_AND_SAMPLED_CONTEXT")
                .put("continuousPreFaultLogs", "UNAVAILABLE");
        archive.state(event, "state.json", state);
        return true;
    }
    private static boolean contextAvailable(JSONObject sample) {
        JSONObject memory = sample.optJSONObject("meminfo"), load = sample.optJSONObject("loadavg");
        return memory != null && load != null && "CAPTURED".equals(memory.optString("state"))
                && "CAPTURED".equals(load.optString("state"));
    }
    private static final class Sample {
        final long elapsed, wall; final JSONObject context;
        Sample(long elapsed, long wall, JSONObject context) { this.elapsed = elapsed; this.wall = wall; this.context = context; }
    }
}
