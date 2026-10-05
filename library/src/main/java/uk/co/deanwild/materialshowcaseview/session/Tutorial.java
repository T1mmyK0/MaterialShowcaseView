package uk.co.deanwild.materialshowcaseview.session;

import java.util.*;

/** Reusable definition. Never capture an Activity in a process-wide definition's predicates. */
public final class Tutorial {
    public enum Migration { KEEP_STABLE_IDS, RESET, FAIL }
    public final String id;
    public final int version;
    public final List<Step> steps;
    public final Migration migration;
    public Tutorial(String id, int version, Step... steps) { this(id, version, Migration.KEEP_STABLE_IDS, steps); }
    public Tutorial(String id, int version, Migration migration, Step... steps) {
        if (id == null || id.isEmpty() || version < 1 || migration == null) throw new IllegalArgumentException("tutorial identity");
        Set<String> ids = new HashSet<>();
        for (Step step : steps) if (step == null || !ids.add(step.id)) throw new IllegalArgumentException("null/duplicate step");
        this.id = id; this.version = version; this.migration = migration;
        this.steps = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(steps)));
    }
    public int indexOf(String id) { for (int i = 0; i < steps.size(); i++) if (steps.get(i).id.equals(id)) return i; return -1; }
}
