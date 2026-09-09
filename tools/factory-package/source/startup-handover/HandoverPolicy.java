import java.util.*;
final class HandoverPolicy {
    interface Ops {
        void journal(Map<String,Integer> states) throws Exception;
        void state(String pkg, int value) throws Exception;
        void stopped(String pkg) throws Exception;
        void mount(String id) throws Exception;
        void unmount(String id) throws Exception;
        void verify(String id) throws Exception;
        void clearJournal() throws Exception;
    }
    static void apply(Ops ops, LinkedHashMap<String,Integer> states, List<String> ids) throws Exception {
        for (int value : states.values())
            if (value != 0 && value != 1) throw new IllegalStateException("Target already disabled");
        ops.journal(states);
        List<String> mounted = new ArrayList<>();
        boolean ready = false;
        try {
            for (String pkg : states.keySet()) ops.state(pkg, 2);
            for (String pkg : states.keySet()) ops.stopped(pkg);
            for (String id : ids) {
                mounted.add(id);
                ops.mount(id);
                ops.verify(id);
            }
            ready = true;
        } finally {
            if (!ready) {
                Collections.reverse(mounted);
                for (String id : mounted) ops.unmount(id);
            }
            for (Map.Entry<String,Integer> item : states.entrySet()) ops.state(item.getKey(), item.getValue());
            ops.clearJournal();
        }
    }
}
