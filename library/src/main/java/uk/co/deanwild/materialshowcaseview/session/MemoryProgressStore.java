package uk.co.deanwild.materialshowcaseview.session;
import java.util.*;
public final class MemoryProgressStore implements ProgressStore {
    private final Map<String, Progress> records = new HashMap<>();
    @Override public synchronized Progress load(Tutorial tutorial) { Progress p = records.get(tutorial.id); return p == null ? Progress.empty(tutorial.version) : p.afterReset(tutorial.version); }
    @Override public synchronized boolean save(String id, long expected, Progress update) {
        Progress old = records.get(id);
        if ((old == null ? 0 : old.revision) != expected || update.revision != expected + 1) return false;
        records.put(id, update); return true;
    }
    @Override public synchronized void reset(String id) {
        Progress old = records.get(id);
        records.put(id, new Progress(old == null ? 1 : old.revision + 1, 0,
                Collections.emptySet(), Collections.emptySet(), Outcome.ACTIVE));
    }
}
