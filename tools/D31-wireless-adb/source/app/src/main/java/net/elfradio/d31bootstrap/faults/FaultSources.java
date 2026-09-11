package net.elfradio.d31bootstrap.faults;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/** Read-only source boundary; implementations never return raw text in public summaries. */
public interface FaultSources {
    Scan discover(FaultPolicy policy) throws Exception;
    default Scan discover(FaultPolicy policy, JSONObject continuation) throws Exception { return discover(policy); }
    Capture capture(Candidate candidate, File newAttempt, FaultPolicy policy) throws Exception;
    JSONObject context() throws Exception;
    String bootKey() throws Exception;
    void checkPrivateRoot(File root) throws IOException;
    void replace(File temporary, File destination) throws IOException;

    final class Candidate {
        public final String category, path, fingerprint;
        public final long sourceTimeMs;
        public Candidate(String category, String path, String fingerprint, long sourceTimeMs) {
            if (category == null || !category.matches("ANR|TOMBSTONE|DROPBOX|BOOT")
                    || path == null || !path.startsWith("/") || path.length() > 1024
                    || path.contains("\\") || path.contains("//") || path.contains("/../")
                    || path.contains("/./") || path.endsWith("/..") || path.endsWith("/.")
                    || !fingerprint.matches("[a-f0-9]{64}") || sourceTimeMs < 0)
                throw new IllegalArgumentException("INVALID_FAULT_SOURCE");
            for (int i = 0; i < path.length(); i++) if (Character.isISOControl(path.charAt(i)))
                throw new IllegalArgumentException("INVALID_FAULT_SOURCE");
            this.category = category; this.path = path; this.fingerprint = fingerprint; this.sourceTimeMs = sourceTimeMs;
        }
        public String id() { return FaultArchive.hash(category + "\n" + path + "\n" + fingerprint); }
        JSONObject json() throws Exception {
            return new JSONObject().put("category", category).put("path", path)
                    .put("fingerprint", fingerprint).put("sourceTimeMs", sourceTimeMs);
        }
        static Candidate parse(JSONObject o) throws Exception {
            return new Candidate(o.getString("category"), o.getString("path"),
                    o.getString("fingerprint"), o.getLong("sourceTimeMs"));
        }
    }

    final class Scan {
        public final List<Candidate> candidates;
        public final JSONObject coverage;
        public final JSONObject continuation;
        public Scan(List<Candidate> candidates, JSONObject coverage) {
            this(candidates, coverage, new JSONObject());
        }
        public Scan(List<Candidate> candidates, JSONObject coverage, JSONObject continuation) {
            this.candidates = Collections.unmodifiableList(new ArrayList<Candidate>(candidates));
            this.coverage = coverage;
            this.continuation = continuation;
        }
    }

    final class Capture {
        public final JSONObject diagnostic;
        public final boolean retryable;
        public Capture(JSONObject diagnostic, boolean retryable) {
            this.diagnostic = diagnostic; this.retryable = retryable;
        }
    }
}
