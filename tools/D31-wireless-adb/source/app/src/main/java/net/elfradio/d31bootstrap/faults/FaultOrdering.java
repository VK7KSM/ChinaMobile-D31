package net.elfradio.d31bootstrap.faults;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class FaultOrdering {
    static final String[] CATEGORIES = {"ANR", "TOMBSTONE", "DROPBOX", "BOOT"};
    static List<FaultSources.Candidate> fair(List<FaultSources.Candidate> candidates, int turn) {
        List<List<FaultSources.Candidate>> groups = new ArrayList<List<FaultSources.Candidate>>();
        for (String category : CATEGORIES) {
            List<FaultSources.Candidate> group = new ArrayList<FaultSources.Candidate>();
            for (FaultSources.Candidate candidate : candidates) if (category.equals(candidate.category)) group.add(candidate);
            Collections.sort(group, new Comparator<FaultSources.Candidate>() {
                @Override public int compare(FaultSources.Candidate a, FaultSources.Candidate b) {
                    int time = Long.compare(b.sourceTimeMs, a.sourceTimeMs);
                    return time == 0 ? a.id().compareTo(b.id()) : time;
                }
            });
            groups.add(group);
        }
        List<FaultSources.Candidate> result = new ArrayList<FaultSources.Candidate>();
        for (int row = 0; row < candidates.size(); row++) for (int step = 0; step < CATEGORIES.length; step++) {
            List<FaultSources.Candidate> group = groups.get((turn + step) % CATEGORIES.length);
            if (row < group.size()) result.add(group.get(row));
        }
        return result;
    }
    static List<String> after(List<String> sorted, String cursor) {
        List<String> result = new ArrayList<String>();
        for (String id : sorted) if (id.compareTo(cursor) > 0) result.add(id);
        for (String id : sorted) if (id.compareTo(cursor) <= 0) result.add(id);
        return result;
    }
    private FaultOrdering() { }
}
