package uk.co.deanwild.materialshowcaseview.session;

import java.util.*;

/** CAS prevents an old run from overwriting newer progress. No Views or callbacks are persisted. */
public interface ProgressStore {
    enum Outcome { ACTIVE, COMPLETED, SKIPPED, LEGACY_FINISHED }
    final class Progress {
        public final long revision;
        public final int version;
        public final Set<String> completed, skipped;
        public final Outcome outcome;
        public final String nextStepId;
        public Progress(long revision, int version, Set<String> completed, Set<String> skipped, Outcome outcome) {
            this(revision, version, completed, skipped, outcome, null);
        }
        public Progress(long revision, int version, Set<String> completed, Set<String> skipped, Outcome outcome, String nextStepId) {
            this.revision = revision; this.version = version;
            this.completed = Collections.unmodifiableSet(new HashSet<>(completed));
            this.skipped = Collections.unmodifiableSet(new HashSet<>(skipped)); this.outcome = outcome;
            this.nextStepId = nextStepId;
        }
        public static Progress empty(int version) { return new Progress(0, version, Collections.emptySet(), Collections.emptySet(), Outcome.ACTIVE); }
        // Version zero is an explicit reset tombstone, independent of the next definition's
        // schema. Keep its revision so a writer from before the reset is still rejected.
        Progress afterReset(int currentVersion) {
            return version == 0 ? new Progress(revision, currentVersion, Collections.emptySet(), Collections.emptySet(), Outcome.ACTIVE) : this;
        }
    }
    Progress load(Tutorial tutorial);
    boolean save(String tutorialId, long expectedRevision, Progress update);
    void reset(String tutorialId);
}
