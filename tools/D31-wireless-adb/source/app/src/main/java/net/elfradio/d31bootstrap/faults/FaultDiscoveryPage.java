package net.elfradio.d31bootstrap.faults;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import net.elfradio.d31bootstrap.diagnostics.collection.CollectionAccess;

/** Latest 32 plus a lexical backlog page of 32, independent of enumeration order. */
final class FaultDiscoveryPage {
    static final int HALF = 32;
    static final class Entry {
        final String name; final CollectionAccess.Stat stat;
        Entry(String name, CollectionAccess.Stat stat) { this.name = name; this.stat = stat; }
    }
    private static final Comparator<Entry> NAME = new Comparator<Entry>() {
        @Override public int compare(Entry a, Entry b) { return a.name.compareTo(b.name); }
    };
    private final TreeSet<Entry> newest = new TreeSet<Entry>(new Comparator<Entry>() {
        @Override public int compare(Entry a, Entry b) {
            int time = Long.compare(b.stat.modified, a.stat.modified);
            return time == 0 ? NAME.compare(a, b) : time;
        }
    });
    private final TreeSet<Entry> after = new TreeSet<Entry>(NAME), beginning = new TreeSet<Entry>(NAME);
    private final String cursor;
    FaultDiscoveryPage(String cursor) { this.cursor = cursor; }
    void add(String name, CollectionAccess.Stat stat) {
        Entry item = new Entry(name, stat); keep(newest, item); keep(beginning, item);
        if (name.compareTo(cursor) > 0) keep(after, item);
    }
    private static void keep(TreeSet<Entry> set, Entry item) { set.add(item); if (set.size() > HALF) set.pollLast(); }
    List<Entry> backlog() {
        List<Entry> page = new ArrayList<Entry>(after);
        for (Entry entry : beginning) if (page.size() < HALF && entry.name.compareTo(cursor) <= 0) page.add(entry);
        return page;
    }
    String next(java.util.Set<String> processed) {
        String next = cursor;
        for (Entry entry : backlog()) { if (!processed.contains(entry.name)) break; next = entry.name; }
        return next;
    }
    List<Entry> selected() {
        List<Entry> latest = new ArrayList<Entry>(newest), page = backlog(), result = new ArrayList<Entry>();
        TreeSet<Entry> seen = new TreeSet<Entry>(NAME);
        for (int i = 0; i < HALF; i++) {
            if (i < latest.size() && seen.add(latest.get(i))) result.add(latest.get(i));
            if (i < page.size() && seen.add(page.get(i))) result.add(page.get(i));
        }
        return result;
    }
}
