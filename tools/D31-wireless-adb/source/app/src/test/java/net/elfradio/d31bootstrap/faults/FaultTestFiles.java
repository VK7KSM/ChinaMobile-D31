package net.elfradio.d31bootstrap.faults;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import net.elfradio.d31bootstrap.diagnostics.collection.*;

final class FaultTestFiles {
    static final class Clock implements CollectionAccess.Clock {
        long elapsed = 100000, wall = 1800000000000L;
        public long elapsedRealtimeMillis() { return elapsed; }
        public long wallTimeMillis() { return wall; }
        void add(long ms) { elapsed += ms; wall += ms; }
    }
    static final class Access implements CollectionAccess, FaultDirectoryWalker {
        final Path root;
        boolean deny, unstable, partialList;
        int fullStatCalls, metadataCalls;
        Access(File root) { this.root = root.toPath(); }
        Path resolve(String path) throws IOException {
            Path file = root.resolve(path.substring(1)).normalize();
            if (!file.startsWith(root)) throw new Failure("LINK_REFUSED");
            Path walk = file;
            while (!walk.equals(root)) { if (Files.isSymbolicLink(walk)) throw new Failure("LINK_REFUSED"); walk = walk.getParent(); }
            return file;
        }
        void write(String path, byte[] bytes) throws IOException {
            Path file = resolve(path); Files.createDirectories(file.getParent()); Files.write(file, bytes);
        }
        public Stat lstat(String path) throws IOException {
            fullStatCalls++;
            return fileStat(path);
        }
        private Stat fileStat(String path) throws IOException {
            if (deny) throw new Failure("ACCESS_DENIED");
            Path file = resolve(path);
            if (!Files.exists(file)) throw new Failure("NOT_FOUND");
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            long inode = a.fileKey() == null ? file.toString().hashCode() : a.fileKey().hashCode();
            return new Stat(a.isDirectory() ? "directory" : "file", 1, inode, a.isDirectory() ? 0 : a.size(),
                    a.lastModifiedTime().toMillis() / 1000, a.lastModifiedTime().toMillis() / 1000, 0600, 0, 0);
        }
        public String readLink(String path) throws IOException { throw new Failure("LINK_REFUSED"); }
        public Handle openRegular(final String path, Stat expected) throws IOException {
            if (!expected.same(lstat(path))) throw new Failure("UNSTABLE_FILE");
            final InputStream in = Files.newInputStream(resolve(path));
            return new Handle() {
                public Stat stat() throws IOException {
                    Stat s = lstat(path);
                    return unstable ? new Stat(s.type, s.device, s.inode + 1, s.size, s.modified, s.changed, s.mode, s.uid, s.gid) : s;
                }
                public int read(byte[] b, int off, int length) throws IOException { return in.read(b, off, length); }
                public void close() throws IOException { in.close(); }
            };
        }
        public Listing list(String path, Stat expected, int max, long bytes, long timeout) throws IOException {
            List<String> names = new ArrayList<String>();
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(resolve(path))) {
                for (Path p : stream) names.add(p.getFileName().toString());
            }
            Collections.sort(names); boolean all = names.size() <= max && !partialList;
            if (names.size() > max) names = new ArrayList<String>(names.subList(0, max));
            return new Listing(names, all, all ? "ENUMERATION_FINISHED" : "ENTRY_LIMIT", names.size() * 20L);
        }
        public String walk(String path, Stat expected, CollectionAccess.Clock clock, long deadline,
                           FaultDirectoryWalker.Visitor visitor) throws IOException {
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(resolve(path))) {
                for (Path child : stream) {
                    if (clock.elapsedRealtimeMillis() >= deadline) return "SCAN_TIME_LIMIT";
                    visitor.name(child.getFileName().toString(), name -> { metadataCalls++; return fileStat(path + "/" + name); });
                }
            }
            return partialList ? "SCAN_BYTE_LIMIT" : "ENUMERATION_FINISHED";
        }
    }
    static FaultEvidenceCollector.Store store(final File root) {
        return new FaultEvidenceCollector.Store() {
            public FaultEvidenceCollector.Receipt storeNew(String id, byte[] bytes) throws IOException {
                File file = new File(root, id + ".bin"); FaultArchive.writeNew(file, bytes);
                return new FaultEvidenceCollector.Receipt(file.getName(), bytes.length, FaultArchive.hash(bytes));
            }
        };
    }
    static void replace(File from, File to) throws IOException {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    static String identity(File file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new IOException("EXPORT_REGULAR_FILE_REQUIRED");
        return attrs.fileKey() + ":" + attrs.size() + ":" + attrs.lastModifiedTime() + ":" + attrs.creationTime();
    }
    static JSONObject context(long wall, long elapsed) throws Exception {
        return new JSONObject().put("capturedAtMs", wall).put("elapsedMs", elapsed)
                .put("meminfo", new JSONObject().put("state", "CAPTURED").put("text", "MemTotal: 1234 kB\n"))
                .put("loadavg", new JSONObject().put("state", "CAPTURED").put("text", "0.0 0.0 0.0 1/10 5\n"));
    }
    private FaultTestFiles() { }
}
