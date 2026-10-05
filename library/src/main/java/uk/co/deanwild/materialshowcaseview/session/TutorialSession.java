package uk.co.deanwild.materialshowcaseview.session;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Mutable execution. Every public operation and host callback is confined to the scheduler thread. */
@androidx.annotation.MainThread
public final class TutorialSession implements TutorialHost.Actions {
    public enum State { IDLE, QUEUED, WAITING, PREPARING, SHOWING, HIDING, PAUSED, COMPLETED, CANCELLED, SKIPPED, FAILED }
    public enum Reason { NONE, USER, INELIGIBLE, BLOCKED, HOST_NOT_READY, TARGET_UNAVAILABLE, TIMEOUT,
        DESTROYED, REPLACED, DUPLICATE, CONFLICT, STALE_PROGRESS, CALLBACK_ERROR, INVALID_BRANCH, EMPTY, NOT_APPLICABLE }
    public enum Kind { STATE, SHOWN, STEP_COMPLETED, STEP_SKIPPED, RESUMED }
    public static final class Event {
        public final String tutorialId, stepId;
        public final long sessionId, runId;
        public final State state;
        public final Kind kind;
        public final Reason reason;
        public final int completedSteps, definedSteps;
        public final String diagnostic;
        Event(TutorialSession s, Kind kind, Reason reason, String diagnostic) {
            tutorialId = s.tutorialId(); stepId = s.step() == null ? null : s.step().id;
            sessionId = s.identity; runId = s.run; state = s.state; this.kind = kind; this.reason = reason;
            int completed = 0;
            if (s.progress != null && s.tutorial != null) for (Step step : s.tutorial.steps) if (s.progress.completed.contains(step.id)) completed++;
            completedSteps = completed;
            definedSteps = s.tutorial == null ? 0 : s.tutorial.steps.size();
            this.diagnostic = diagnostic;
        }
        Event(Event snapshot, String diagnostic) {
            tutorialId = snapshot.tutorialId; stepId = snapshot.stepId;
            sessionId = snapshot.sessionId; runId = snapshot.runId; state = snapshot.state;
            kind = snapshot.kind; reason = snapshot.reason;
            completedSteps = snapshot.completedSteps; definedSteps = snapshot.definedSteps;
            this.diagnostic = diagnostic;
        }
    }
    public interface Listener { void onEvent(Event event); }
    public interface ErrorHandler { void onError(RuntimeException error); }
    private static final AtomicLong IDS = new AtomicLong();
    private final long identity = IDS.incrementAndGet();
    private Tutorial tutorial;
    private final String tutorialId;
    private TutorialHost host;
    private final Scheduler scheduler;
    private final ProgressStore store;
    private final TutorialCoordinator coordinator;
    private final TutorialCoordinator.Conflict conflict;
    private final boolean replay;
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<Object, String> blockers = new LinkedHashMap<>();
    private final Set<String> visited = new HashSet<>();
    private final List<Integer> history = new ArrayList<>();
    private final List<Integer> forwardHistory = new ArrayList<>();
    private Scope runScope = new Scope(), phase = new Scope();
    private ProgressStore.Progress progress;
    private State state = State.IDLE;
    private Step.Condition eligible = () -> true;
    private ErrorHandler errors = error -> { throw error; };
    private boolean enabled = true, disposed, disposing, active, manualPause, reportingError, ownsCoordinator;
    private boolean finishing, restartRequested;
    private Cancellation restartWork = Cancellation.NONE;
    private int guardDepth;
    private final Set<RuntimeException> propagatedErrors = Collections.newSetFromMap(new IdentityHashMap<>());
    private long generation, run;
    private long readinessDeadline = -1;
    private int index;

    public TutorialSession(Tutorial tutorial, TutorialHost host, Scheduler scheduler, ProgressStore store,
            TutorialCoordinator coordinator, TutorialCoordinator.Conflict conflict) {
        this(tutorial, host, scheduler, store, coordinator, conflict, false);
    }
    public TutorialSession(Tutorial tutorial, TutorialHost host, Scheduler scheduler, ProgressStore store,
            TutorialCoordinator coordinator, TutorialCoordinator.Conflict conflict, boolean replay) {
        this.tutorial = Objects.requireNonNull(tutorial); this.host = Objects.requireNonNull(host);
        tutorialId = tutorial.id;
        this.scheduler = Objects.requireNonNull(scheduler); this.store = store == null ? new MemoryProgressStore() : store;
        scheduler.checkThread();
        this.coordinator = Objects.requireNonNull(coordinator); this.conflict = Objects.requireNonNull(conflict); this.replay = replay;
    }
    public String tutorialId() { return tutorialId; }
    public State getState() { return state; }
    public boolean isActive() { return active; }
    public ProgressStore.Progress getProgress() { check(); return replay ? (progress == null ? ProgressStore.Progress.empty(tutorial.version) : progress) : store.load(tutorial); }
    public Step step() { return tutorial != null && index < tutorial.steps.size() ? tutorial.steps.get(index) : null; }
    public void addListener(Listener listener) { check(); if (listener != null && !listeners.contains(listener)) listeners.add(listener); }
    public void removeListener(Listener listener) { scheduler.checkThread(); listeners.remove(listener); }
    public void setErrorHandler(ErrorHandler handler) { check(); errors = Objects.requireNonNull(handler); }
    public void setEligibility(Step.Condition predicate) { check(); eligible = Objects.requireNonNull(predicate); invalidateEligibility(); }
    public void setEligible(boolean value) { check(); enabled = value; invalidateEligibility(); }
    public Cancellation block(String reason) {
        check(); Object key = new Object(); blockers.put(key, reason == null ? "blocker" : reason); invalidateEligibility();
        return () -> { scheduler.checkThread(); if (blockers.remove(key) != null && !disposed) invalidateEligibility(); };
    }
    public void start() {
        check();
        if (finishing) { restartRequested = true; return; }
        if (active) return;
        restartWork.cancel(); restartWork = Cancellation.NONE;
        run++;
        guarded(() -> {
            active = true; manualPause = false; ownsCoordinator = false; state = State.IDLE;
            readinessDeadline = -1;
            runScope = new Scope(); visited.clear(); history.clear(); forwardHistory.clear();
            index = 0;
            if (!loadProgress()) return;
            coordinator.acquire(this, conflict);
        });
    }
    private boolean loadProgress() {
        long token = generation;
        ProgressStore.Progress loaded = replay ? ProgressStore.Progress.empty(tutorial.version) : store.load(tutorial);
        if (!current(token)) return false;
        progress = loaded;
        if (progress.version != tutorial.version) {
            if (tutorial.migration == Tutorial.Migration.FAIL) { finish(State.FAILED, Reason.STALE_PROGRESS); return false; }
            if (tutorial.migration == Tutorial.Migration.RESET) progress = new ProgressStore.Progress(progress.revision,
                    tutorial.version, Collections.emptySet(), Collections.emptySet(), ProgressStore.Outcome.ACTIVE);
        }
        if (progress.outcome != ProgressStore.Outcome.ACTIVE) {
            finish(progress.outcome == ProgressStore.Outcome.COMPLETED ? State.COMPLETED : State.SKIPPED, Reason.NONE); return false;
        }
        index = startingIndex();
        if (step() == null) { finish(State.SKIPPED, tutorial.steps.isEmpty() ? Reason.EMPTY : Reason.NOT_APPLICABLE); return false; }
        // Persisted progress has stable IDs but no ordered route. Restore completed predecessors
        // in the current definition's order, excluding steps bypassed by branches or conditions.
        history.clear(); forwardHistory.clear();
        for (int i = 0; i < index; i++) {
            if (progress.completed.contains(tutorial.steps.get(i).id)) history.add(i);
        }
        return true;
    }
    void queued() { change(State.QUEUED, Reason.NONE); }
    void acquired() {
        if (!active) return;
        ownsCoordinator = true;
        guarded(() -> {
            // Ownership can be acquired after queueing or an explicit pause. Both paths must
            // apply the same migration and terminal checks as a new run.
            if (!loadProgress()) return;
            long token = run, observedGeneration = generation;
            Scope observing = runScope;
            observing.own(host.observe(() -> { if (active && run == token) invalidateEligibility(); },
                    work -> { if (active && run == token) guarded(work); }));
            if (current(observedGeneration)) attempt();
        });
    }
    public void invalidateEligibility() {
        check(); if (!active || state == State.QUEUED) return;
        guarded(() -> {
            long token = generation;
            Reason gate = gate(token);
            if (!current(token)) return;
            if (gate != Reason.NONE) { pauseInternal(gate, true); return; }
            if (state == State.PAUSED) { if (!manualPause) resume(); return; }
            if (state == State.WAITING) attempt();
            else if (state == State.SHOWING) validate(step(), true, token);
        });
    }
    private Reason gate(long token) {
        if (!enabled || !eligible.test()) return Reason.INELIGIBLE;
        if (!current(token)) return Reason.NONE;
        if (!blockers.isEmpty()) return Reason.BLOCKED;
        return host.ready() ? Reason.NONE : Reason.HOST_NOT_READY;
    }
    private boolean passGate(long token) {
        Reason reason = gate(token);
        if (!current(token)) return false;
        if (reason != Reason.NONE) { pauseInternal(reason, true); return false; }
        return true;
    }
    private boolean validate(Step step, boolean visible, long token) {
        if (!current(token)) return false;
        boolean valid = host.valid(step, visible);
        if (!current(token)) return false;
        if (!valid) unavailable();
        return valid;
    }
    private void attempt() {
        if (!active || manualPause) return;
        long previous = generation;
        if (!passGate(previous)) return;
        Step s = step();
        if (s == null) { finish(State.COMPLETED, Reason.NONE); return; }
        boolean applicable = s.condition.test();
        if (!current(previous)) return;
        if (!applicable) {
            if (s.optional) { commit(true); return; }
            unavailable(); return;
        }
        if (!validate(s, false, previous)) return;
        if (!clearPhase()) return; long token = generation; Scope resources = phase;
        change(State.PREPARING, Reason.NONE);
        if (!current(token, State.PREPARING)) return;
        resources.own(scheduler.post(readinessRemaining(), () -> guarded(() -> { if (current(token, State.PREPARING)) timedOut(); })));
        resources.own(scheduler.post(s.delayMillis, () -> guarded(() -> {
            if (!current(token, State.PREPARING)) return;
            if (!passGate(token) || !validate(s, false, token)) return;
            ownOperation(resources, complete -> host.prepare(s, resources, complete, callbacks(token)), () -> guarded(() -> {
                scheduler.checkThread();
                if (!current(token, State.PREPARING)) return;
                if (!passGate(token) || !validate(s, true, token)) return;
                host.setProgress(index + 1, tutorial.steps.size());
                if (!current(token, State.PREPARING)) return;
                ownOperation(resources, shown -> host.show(s, new PresentationActions(run, token, s), shown, callbacks(token)), () -> guarded(() -> {
                    if (!current(token, State.PREPARING)) return;
                    if (!passGate(token) || !validate(s, true, token)) return;
                    readinessDeadline = -1;
                    change(State.SHOWING, Reason.NONE);
                    if (current(token, State.SHOWING)) emit(Kind.SHOWN, Reason.NONE);
                }));
            }));
        })));
    }
    private interface Operation { Cancellation start(Runnable complete); }
    private void ownOperation(Scope resources, Operation operation, Runnable continuation) {
        // A host may signal completion before returning its resource. Buffer that signal
        // until cleanup is registered, so listeners can cancel/replace safely even then.
        class Completion implements Runnable {
            boolean registered, pending, consumed;
            @Override public void run() {
                scheduler.checkThread();
                if (!registered) { pending = true; return; }
                if (!consumed) { consumed = true; continuation.run(); }
            }
        }
        Completion complete = new Completion();
        resources.own(operation.start(complete));
        complete.registered = true;
        if (complete.pending) complete.run();
    }
    private void unavailable() {
        Step s = step();
        switch (s.unavailable) {
            case CANCEL: finish(State.CANCELLED, Reason.TARGET_UNAVAILABLE); break;
            case FAIL: finish(State.FAILED, Reason.TARGET_UNAVAILABLE); break;
            case PAUSE: pauseInternal(Reason.TARGET_UNAVAILABLE, false); break;
            case SKIP_OPTIONAL:
                if (s.optional) commit(true); else finish(State.FAILED, Reason.TARGET_UNAVAILABLE); break;
            default:
                if (state == State.WAITING) return;
                if (!clearPhase()) return; long token = generation;
                change(State.WAITING, Reason.TARGET_UNAVAILABLE);
                if (current(token, State.WAITING)) phase.own(scheduler.post(readinessRemaining(),
                        () -> guarded(() -> { if (current(token, State.WAITING)) timedOut(); })));
        }
    }
    private void timedOut() {
        switch (step().timeout) {
            case PAUSE: pauseInternal(Reason.TIMEOUT, false); break;
            case CANCEL: finish(State.CANCELLED, Reason.TIMEOUT); break;
            case SKIP_OPTIONAL: if (step().optional) commit(true); else finish(State.FAILED, Reason.TIMEOUT); break;
            default: finish(State.FAILED, Reason.TIMEOUT);
        }
    }
    private long readinessRemaining() {
        if (readinessDeadline < 0) readinessDeadline = scheduler.nowMillis() + step().timeoutMillis;
        return Math.max(0, readinessDeadline - scheduler.nowMillis());
    }
    public void pause(Reason reason) { check(); if (active) guarded(() -> pauseInternal(reason, false)); }
    private void pauseInternal(Reason reason, boolean auto) {
        if (!auto) manualPause = true;
        if (state == State.PAUSED) return;
        if (!ownsCoordinator) coordinator.release(this);
        if (!clearPhase()) return; readinessDeadline = -1; change(State.PAUSED, reason);
    }
    public void resume() {
        check(); if (!active || state != State.PAUSED) return;
        guarded(() -> { manualPause = false; long token = generation;
            Reason reason = gate(token);
            if (!current(token) || reason != Reason.NONE) return;
            emit(Kind.RESUMED, Reason.NONE);
            if (current(token)) {
                if (ownsCoordinator) attempt();
                else coordinator.acquire(this, conflict);
            }
        });
    }
    public void cancel(Reason reason) {
        check(); cancelRestart();
        if (active) guarded(() -> finish(State.CANCELLED, reason));
    }
    private void cancelRestart() { restartWork.cancel(); restartWork = Cancellation.NONE; restartRequested = false; }
    @Override public void close() { cancel(Reason.USER); }
    @Override public void next() {
        check(); if (state != State.SHOWING || step().interaction == Step.Interaction.APPLICATION_ACTION
                || step().interaction == Step.Interaction.TARGET_ACTION || step().interaction == Step.Interaction.TARGET_TAP) return;
        advance(false);
    }
    @Override public void actionCompleted() { check(); if (state == State.SHOWING && step().interaction != Step.Interaction.TARGET_TAP) advance(false); }
    /** Capture while SHOWING, then pass this one-shot handle to asynchronous application work.
     * Unlike actionCompleted(), it cannot act on a later step or resumed presentation. */
    public Runnable completionHandle() {
        check();
        if (state != State.SHOWING) throw new IllegalStateException("No displayed step");
        PresentationActions actions = new PresentationActions(run, generation, step());
        return actions::actionCompleted;
    }
    private TutorialHost.Callbacks callbacks(long token) {
        long owner = run;
        return work -> { scheduler.checkThread(); if (active && run == owner && generation == token) guarded(work); };
    }
    private final class PresentationActions implements TutorialHost.Actions {
        private final long owner, token;
        private final Step originatingStep;
        private boolean completed, activatingTarget;
        PresentationActions(long owner, long token, Step step) { this.owner = owner; this.token = token; originatingStep = step; }
        private boolean showing() { return !disposed && run == owner && step() == originatingStep && current(token, State.SHOWING); }
        private void invoke(Runnable action) {
            scheduler.checkThread();
            if (showing()) guarded(action);
        }
        public void next() { invoke(TutorialSession.this::next); }
        public void previous() { invoke(TutorialSession.this::previous); }
        public void skipStep() { invoke(TutorialSession.this::skipStep); }
        public void skipTour() { invoke(TutorialSession.this::skipTour); }
        public void close() {
            scheduler.checkThread();
            // Losing the actual Android view can cancel an entrance or exit as well
            // as a displayed step. Navigation still requires SHOWING.
            if (!disposed && run == owner && step() == originatingStep && current(token))
                guarded(TutorialSession.this::close);
        }
        public void actionCompleted() { invoke(() -> {
            if (!completed && originatingStep.interaction != Step.Interaction.TARGET_TAP) { completed = true; advance(false); }
        }); }
        public void activateTarget(Runnable activation) {
            invoke(() -> {
                if (activatingTarget || originatingStep.interaction != Step.Interaction.TARGET_ACTION) return;
                activatingTarget = true;
                try {
                    if (!passGate(token) || !validate(originatingStep, true, token)) return;
                    if (showing()) activation.run();
                } finally { activatingTarget = false; }
            });
        }
        public void targetTapped(java.util.function.BooleanSupplier activation) {
            invoke(() -> {
                if (activatingTarget || originatingStep.interaction != Step.Interaction.TARGET_TAP) return;
                activatingTarget = true;
                try { advance(false, Objects.requireNonNull(activation)); } finally { activatingTarget = false; }
            });
        }
    }
    @Override public void skipStep() { check(); if (state == State.SHOWING && step().interaction != Step.Interaction.TARGET_TAP) advance(true); }
    private void advance(boolean skip) {
        advance(skip, null);
    }
    private void advance(boolean skip, java.util.function.BooleanSupplier targetActivation) {
        guarded(() -> {
            long token = generation;
            if (!passGate(token) || !validate(step(), true, token)) return;
            if (targetActivation != null && (!targetActivation.getAsBoolean() || !current(token, State.SHOWING))) return;
            change(State.HIDING, Reason.NONE);
            if (!current(token, State.HIDING)) return;
            Scope resources = phase;
            ownOperation(resources, hidden -> host.hide(hidden), () -> guarded(() -> { if (current(token, State.HIDING)) commit(skip); }));
        });
    }
    private void commit(boolean skip) {
        long previous = generation;
        Step s = step(); int from = index;
        boolean reviewing = !forwardHistory.isEmpty();
        String destination = reviewing || s.branch == null ? null : s.branch.next();
        if (!current(previous)) return;
        int next = reviewing ? forwardHistory.get(forwardHistory.size() - 1)
                : destination == null ? firstIncomplete(index + 1) : tutorial.indexOf(destination);
        if (destination != null && (next < 0 || destination.equals(s.id) || visited.contains(destination))) {
            finish(State.FAILED, Reason.INVALID_BRANCH); return;
        }
        if (!clearPhase()) return; readinessDeadline = -1; visited.add(s.id);
        Set<String> done = new HashSet<>(progress.completed), skipped = new HashSet<>(progress.skipped);
        boolean firstCommit = !done.contains(s.id) && !skipped.contains(s.id);
        if (firstCommit) (skip ? skipped : done).add(s.id);
        if (!save(done, skipped, next >= tutorial.steps.size() ? ProgressStore.Outcome.COMPLETED : ProgressStore.Outcome.ACTIVE,
                next >= tutorial.steps.size() ? null : tutorial.steps.get(next).id)) return;
        long token = generation;
        Event committed = firstCommit ? new Event(this, skip ? Kind.STEP_SKIPPED : Kind.STEP_COMPLETED, Reason.NONE, "") : null;
        // Advance the in-memory route before application callbacks can pause or block it.
        // The event still identifies the step just committed, as does persisted progress.
        if (reviewing) forwardHistory.remove(forwardHistory.size() - 1);
        history.add(from); index = next;
        if (committed != null) emit(committed);
        if (!active || token != generation) return;
        if (step() == null) finish(State.COMPLETED, Reason.NONE); else attempt();
    }
    private int firstIncomplete(int start) {
        while (start < tutorial.steps.size() && (progress.completed.contains(tutorial.steps.get(start).id)
                || progress.skipped.contains(tutorial.steps.get(start).id))) start++;
        return start;
    }
    private int startingIndex() {
        int saved = progress.nextStepId == null ? -1 : tutorial.indexOf(progress.nextStepId);
        return saved < 0 ? firstIncomplete(0) : saved;
    }
    @Override public void previous() {
        check(); if (state != State.SHOWING || history.isEmpty()) return;
        guarded(() -> {
            if (!clearPhase()) return;
            readinessDeadline = -1;
            // Next returns along the review path, including completed steps and branch jumps.
            forwardHistory.add(index);
            index = history.remove(history.size() - 1);
            visited.remove(step().id);
            attempt();
        });
    }
    @Override public void skipTour() {
        check(); if (!active) return;
        guarded(() -> { if (!clearPhase()) return; if (save(progress.completed, progress.skipped, ProgressStore.Outcome.SKIPPED, null)) finish(State.SKIPPED, Reason.USER); });
    }
    public void resetProgress() {
        check(); cancel(Reason.USER); cancelRestart();
        if (replay) progress = null; else store.reset(tutorialId);
    }
    private boolean save(Set<String> done, Set<String> skipped, ProgressStore.Outcome outcome, String nextStepId) {
        long token = generation;
        ProgressStore.Progress update = new ProgressStore.Progress(progress.revision + 1, tutorial.version, done, skipped, outcome, nextStepId);
        boolean saved = replay || store.save(tutorialId, progress.revision, update);
        if (!current(token)) return false;
        if (!saved) { finish(State.FAILED, Reason.STALE_PROGRESS); return false; }
        progress = update; return true;
    }
    private boolean current(long token) { return active && generation == token; }
    private boolean current(long token, State expected) { return current(token) && state == expected; }
    private boolean clearPhase() { long token = ++generation; Scope old = phase; phase = new Scope(); old.cancel(); return active && generation == token; }
    private void finish(State terminal, Reason reason) {
        if (!active) return;
        active = false; generation++; state = terminal;
        ownsCoordinator = false;
        finishing = true;
        Scope endingPhase = phase, endingRun = runScope;
        phase = new Scope(); runScope = new Scope();
        List<Listener> endingListeners = new ArrayList<>(listeners);
        RuntimeException failure = null;
        Event snapshot = new Event(this, Kind.STATE, reason, "");
        Event event;
        try { event = new Event(snapshot, diagnostic(reason)); }
        catch (RuntimeException e) { failure = e; event = new Event(snapshot, "Diagnostic callback failed"); }
        try {
            try { endingPhase.cancel(); } catch (RuntimeException e) { failure = combine(failure, e); }
            try { endingRun.cancel(); } catch (RuntimeException e) { failure = combine(failure, e); }
            try { coordinator.release(this); } catch (RuntimeException e) { failure = combine(failure, e); }
            // Dispatch the immutable ending event to the entire snapshot, even if a listener
            // requests restart. A new run cannot begin until teardown and dispatch both finish.
            if (event != null) for (Listener listener : endingListeners) {
                try { listener.onEvent(event); } catch (RuntimeException e) { failure = combine(failure, e); }
            }
        } finally {
            finishing = false;
            boolean restart = restartRequested; restartRequested = false;
            long endingRunId = run;
            if (restart && !disposed && !disposing) restartWork = scheduler.post(0, () -> {
                if (!disposed && !disposing && !active && run == endingRunId) start();
            });
        }
        if (failure != null) throw failure;
    }
    private static RuntimeException combine(RuntimeException first, RuntimeException next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
    public void dispose() {
        scheduler.checkThread(); if (disposed || disposing) return;
        disposing = true;
        cancelRestart();
        try { if (active) finish(State.CANCELLED, Reason.DESTROYED); }
        finally { disposed = true; blockers.clear(); listeners.clear(); eligible = () -> false;
            try { host.cancel(); } finally { host = null; tutorial = null; errors = error -> { throw error; }; } }
    }
    private void change(State state, Reason reason) { this.state = state; emit(Kind.STATE, reason); }
    private String diagnostic(Reason reason) { return reason == Reason.BLOCKED ? blockers.values().toString() : host == null ? "" : host.diagnostic(); }
    private void emit(Kind kind, Reason reason) {
        emit(new Event(this, kind, reason, ""));
    }
    private void emit(Event snapshot) {
        long token = generation;
        Event event = new Event(snapshot, diagnostic(snapshot.reason));
        for (Listener listener : new ArrayList<>(listeners)) {
            if (disposed || token != generation || event.runId != run || event.state != state) break;
            listener.onEvent(event);
        }
    }
    private void check() { scheduler.checkThread(); if (disposed || disposing) throw new IllegalStateException("Session disposed"); }
    private void guarded(Runnable work) {
        long owner = run;
        ErrorHandler handler = errors;
        guardDepth++;
        try { work.run(); } catch (RuntimeException error) {
            if (propagatedErrors.contains(error)) throw error;
            if (reportingError) {
                // An error handler may start another run. If that run fails too, clean it
                // before propagating; recursively invoking the handler would never terminate.
                if (active && run == owner) {
                    try { finish(State.FAILED, Reason.CALLBACK_ERROR); }
                    catch (RuntimeException secondary) { combine(error, secondary); }
                }
                propagatedErrors.add(error);
                throw error;
            }
            reportingError = true;
            try { if (active && run == owner) { try { finish(State.FAILED, Reason.CALLBACK_ERROR); } catch (RuntimeException secondary) { combine(error, secondary); } }
                try { handler.onError(error); }
                catch (RuntimeException reported) { propagatedErrors.add(reported); throw reported; }
            } finally { reportingError = false; }
        } finally { if (--guardDepth == 0) propagatedErrors.clear(); }
    }
}
