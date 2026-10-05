package uk.co.deanwild.materialshowcaseview.session;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static uk.co.deanwild.materialshowcaseview.session.TutorialSession.*;

public class TutorialSessionTest {
    static class Clock implements Scheduler {
        long now; final List<Task> tasks = new ArrayList<>();
        static class Task { long time; Runnable run; boolean cancelled; }
        public void checkThread() { }
        public long nowMillis() { return now; }
        public Cancellation post(long delay, Runnable run) { Task t = new Task(); t.time = now + delay; t.run = run; tasks.add(t); return () -> t.cancelled = true; }
        void advance(long amount) { long end = now + amount;
            while (true) { Task next = null; for (Task t : tasks) if (!t.cancelled && t.time <= end && (next == null || t.time < next.time)) next = t;
                if (next == null) break; tasks.remove(next); now = next.time; next.run.run(); }
            now = end;
        }
    }
    static class Host implements TutorialHost {
        boolean ready = true, valid = true, autoPrepare = true, autoHide = true, overlay;
        Runnable changed, prepared, hidden, shown; int displays, cleanups, observations;
        public boolean ready() { return ready; }
        public boolean valid(Step step, boolean visible) { return valid; }
        public Cancellation observe(Runnable r) { changed = r; observations++; return () -> { changed = null; observations--; }; }
        public Cancellation prepare(Step s, Scope scope, Runnable complete) { prepared = complete; if (autoPrepare) complete.run(); return () -> cleanups++; }
        public Cancellation show(Step s, Actions actions, Runnable complete) { overlay = true; displays++; shown = complete; complete.run(); return () -> overlay = false; }
        public Cancellation hide(Runnable complete) { hidden = complete; if (autoHide) complete.run(); return Cancellation.NONE; }
        public void cancel() { overlay = false; }
    }
    Clock clock = new Clock(); Host host = new Host(); MemoryProgressStore store = new MemoryProgressStore();
    TutorialCoordinator coordinator = new TutorialCoordinator();
    Tutorial tutorial = new Tutorial("sc_gpt", 1, Step.builder("prompt").delay(100).build(), Step.builder("key").build());
    TutorialSession create() { return new TutorialSession(tutorial, host, clock, store, coordinator, TutorialCoordinator.Conflict.QUEUE); }
    @Test public void cancelBeforeDelayCancelsWorkAndPreservesProgress() {
        TutorialSession s = create(); s.start(); Runnable stale = clock.tasks.get(clock.tasks.size()-1).run;
        s.cancel(Reason.USER); clock.advance(1000); stale.run(); assertFalse(host.overlay); assertEquals(0, host.displays);
        assertTrue(store.load(tutorial).completed.isEmpty()); assertEquals(0, coordinator.pendingCount());
    }
    @Test public void repeatedStartHasOnePresentation() { TutorialSession s = create(); s.start(); s.start(); clock.advance(100); assertEquals(1,host.displays); }
    @Test public void cancellationDuringExitDoesNotCommit() {
        TutorialSession s = create(); s.start(); clock.advance(100); host.autoHide = false; s.next(); assertEquals(State.HIDING,s.getState());
        s.cancel(Reason.USER); host.hidden.run(); assertTrue(store.load(tutorial).completed.isEmpty()); assertFalse(host.overlay);
    }
    @Test public void duplicatePreparationAndOldRunAreIgnored() {
        host.autoPrepare = false; TutorialSession s = create(); s.start(); clock.advance(100); Runnable stale = host.prepared;
        s.cancel(Reason.USER); s.start(); clock.advance(100); stale.run(); assertEquals(0,host.displays);
        host.prepared.run(); host.prepared.run(); assertEquals(1,host.displays);
    }
    @Test public void targetInvalidatedDuringPreparationNeverAppears() {
        host.autoPrepare = false; TutorialSession s = create(); s.start(); clock.advance(100); host.valid = false; host.prepared.run();
        assertEquals(State.WAITING,s.getState()); assertFalse(host.overlay); assertEquals(1,host.cleanups);
    }
    @Test public void displayedTargetDisappearsWaitsWithoutCommit() {
        TutorialSession s = create(); s.start(); clock.advance(100); host.valid = false; host.changed.run();
        assertEquals(State.WAITING,s.getState()); assertFalse(host.overlay); assertTrue(store.load(tutorial).completed.isEmpty());
    }
    @Test public void blockersNestAndBackgroundSuspendsTimeout() {
        TutorialSession s = create(); Cancellation a=s.block("dialog"), b=s.block("dialog2"); s.start(); clock.advance(20000);
        a.cancel(); assertEquals(State.PAUSED,s.getState()); b.cancel(); clock.advance(100); assertEquals(State.SHOWING,s.getState());
        host.ready=false; host.changed.run(); clock.advance(20000); assertEquals(State.PAUSED,s.getState());
        host.ready=true; host.changed.run(); clock.advance(100); assertEquals(2,host.displays);
    }
    @Test public void providerSwitchInvalidatesDelayedPresentation() {
        TutorialSession gpt=create(); gpt.start(); gpt.setEligible(false); gpt.cancel(Reason.INELIGIBLE);
        Host geminiHost=new Host(); TutorialSession gemini=new TutorialSession(new Tutorial("sc_gemini",1,Step.builder("prompt").build()),geminiHost,clock,store,coordinator,TutorialCoordinator.Conflict.REPLACE);
        gemini.start(); clock.advance(100); assertEquals(0,host.displays); assertEquals(1,geminiHost.displays);
    }
    @Test public void restoreGateUsesFinalDraftProvider() {
        TutorialSession s=create(); Cancellation loading=s.block("restore"); s.start(); s.setEligible(false); loading.cancel(); clock.advance(1000); assertEquals(0,host.displays);
    }
    @Test public void finalStepAlwaysPreparesAfterResume() {
        TutorialSession s=create(); s.start(); clock.advance(100); s.next(); clock.advance(0); s.cancel(Reason.USER);
        int count=host.cleanups; s.start(); clock.advance(0); assertEquals("key",s.step().id); assertEquals(State.SHOWING,s.getState()); s.cancel(Reason.USER); assertTrue(host.cleanups>count);
    }
    @Test public void completionReleasesBeforeTerminalCallback() {
        TutorialSession s=create(); List<State> terminal=new ArrayList<>(); s.addListener(e->{ if(e.state==State.COMPLETED) { assertFalse(host.overlay); assertEquals(0,host.observations); terminal.add(e.state); }});
        s.start(); clock.advance(100); s.next(); clock.advance(0); s.next(); s.next(); assertEquals(1,terminal.size());
    }
    @Test public void cancelInShownCallbackPreventsAdvance() {
        TutorialSession s=create(); s.addListener(e->{if(e.kind==Kind.SHOWN)s.cancel(Reason.USER);}); s.start(); clock.advance(100); assertFalse(host.overlay); assertTrue(store.load(tutorial).completed.isEmpty());
    }
    @Test public void throwingCallbackCleansThenReports() {
        TutorialSession s=create(); List<RuntimeException> errors=new ArrayList<>(); s.setErrorHandler(errors::add);
        s.addListener(e->{if(e.kind==Kind.SHOWN)throw new IllegalStateException("app");}); s.start(); clock.advance(100);
        assertEquals(State.FAILED,s.getState()); assertFalse(host.overlay); assertEquals(1,errors.size()); assertEquals(0,coordinator.pendingCount());
    }
    @Test public void listenersMayRemoveThemselves() {
        TutorialSession s=create(); Listener[] listener=new Listener[1]; int[] calls={0}; listener[0]=e->{calls[0]++;s.removeListener(listener[0]);}; s.addListener(listener[0]);s.addListener(listener[0]);s.start();clock.advance(100);assertEquals(1,calls[0]);
    }
    @Test public void waitTimesOutAndIgnoresLateCompletion() {
        host.autoPrepare=false;TutorialSession s=create();s.start();clock.advance(15001);host.prepared.run();assertEquals(State.FAILED,s.getState());assertEquals(0,host.displays);
    }
    @Test public void queuedEligibilityRevalidated() {
        TutorialSession first=create();first.start();Host secondHost=new Host();TutorialSession second=new TutorialSession(tutorial,secondHost,clock,store,coordinator,TutorialCoordinator.Conflict.QUEUE);
        second.setEligible(false);second.start();assertEquals(State.QUEUED,second.getState());first.cancel(Reason.USER);clock.advance(100);assertEquals(State.PAUSED,second.getState());assertEquals(0,secondHost.displays);
    }
    @Test public void disposeCoordinatorReleasesQueueAndRejectsWork() {
        TutorialSession s=create();s.start();TutorialSession other=create();other.start();coordinator.cancel();assertEquals(0,coordinator.pendingCount());
        try{s.start();fail();}catch(IllegalStateException expected){}
    }
    @Test public void emptyAndOptionalAreDistinctFromMissingRequired() {
        tutorial=new Tutorial("empty",1);TutorialSession s=create();s.start();assertEquals(State.SKIPPED,s.getState());
        tutorial=new Tutorial("optional",1,Step.builder("a").optional(true).when(()->false).build());s=create();s.start();assertEquals(State.COMPLETED,s.getState());assertTrue(store.load(tutorial).skipped.contains("a"));
    }
    @Test public void stableIdsSurviveReorderingAndInsertion() {
        TutorialSession s=create();s.start();clock.advance(100);s.next();s.cancel(Reason.USER);
        tutorial=new Tutorial("sc_gpt",2,Step.builder("key").build(),Step.builder("inserted").build(),Step.builder("prompt").build());s=create();s.start();clock.advance(0);assertEquals("key",s.step().id);
        s.next();clock.advance(0);assertEquals("inserted",s.step().id);
    }
    @Test public void staleWriterFailsAndResetOnlyTouchesOneTutorial() {
        TutorialSession s=create();s.start();clock.advance(100);store.reset(tutorial.id);s.next();assertEquals(State.FAILED,s.getState());
        Tutorial other=new Tutorial("other",1);ProgressStore.Progress p=store.load(other);assertEquals(0,p.revision);
    }
    @Test public void actionStepRequiresAppConfirmation() {
        tutorial=new Tutorial("action",1,Step.builder("a").interaction(Step.Interaction.APPLICATION_ACTION).build());TutorialSession s=create();s.start();clock.advance(0);s.next();assertEquals(State.SHOWING,s.getState());s.actionCompleted();assertEquals(State.COMPLETED,s.getState());
    }
    @Test public void invalidBranchFailsWithoutCommitting() {
        tutorial=new Tutorial("branch",1,Step.builder("a").branch(()->"a").build());TutorialSession s=create();s.start();clock.advance(0);s.next();assertEquals(State.FAILED,s.getState());assertTrue(store.load(tutorial).completed.isEmpty());
    }
    @Test public void replayDoesNotChangeNormalHistory() {
        TutorialSession s=new TutorialSession(tutorial,host,clock,store,coordinator,TutorialCoordinator.Conflict.QUEUE,true);s.start();clock.advance(100);s.skipTour();assertEquals(0,store.load(tutorial).revision);
    }
    @Test public void scopeClosesLateResourcesAndContinuesAfterError() {
        Scope scope=new Scope();int[] count={0};scope.own(()->count[0]++);scope.own(()->{throw new IllegalStateException();});
        try{scope.cancel();fail();}catch(IllegalStateException expected){}scope.own(()->count[0]++);scope.cancel();assertEquals(2,count[0]);
    }
    @Test public void timeoutPoliciesPauseCancelOrExplicitlySkipOptional() {
        for (Step.Timeout policy : Step.Timeout.values()) {
            tutorial=new Tutorial("timeout-"+policy,1,Step.builder("a").optional(true).timeout(5).onTimeout(policy).build());
            Host h=new Host();h.valid=false;TutorialSession s=new TutorialSession(tutorial,h,clock,store,coordinator,TutorialCoordinator.Conflict.QUEUE);
            s.start();clock.advance(6);
            State expected=policy==Step.Timeout.PAUSE?State.PAUSED:policy==Step.Timeout.CANCEL?State.CANCELLED:policy==Step.Timeout.SKIP_OPTIONAL?State.COMPLETED:State.FAILED;
            assertEquals(expected,s.getState());s.dispose();
        }
    }
    @Test public void previousDoesNotDuplicateCompletion() {
        tutorial=new Tutorial("history",1,Step.builder("a").build(),Step.builder("b").build(),Step.builder("c").build());
        TutorialSession s=create();int[] completions={0};s.addListener(e->{if(e.kind==Kind.STEP_COMPLETED)completions[0]++;});s.start();clock.advance(0);s.next();clock.advance(0);s.previous();clock.advance(0);s.next();clock.advance(0);assertEquals(1,completions[0]);assertEquals("b",s.step().id);
    }
    @Test public void resumedSecondStepCanReturnToFirstWithoutLosingProgress() {
        for (boolean recreate : new boolean[]{false, true}) {
            tutorial = new Tutorial("resume-previous-" + recreate, 1, Step.builder("first").build(), Step.builder("second").build());
            TutorialSession session = create(); session.start(); clock.advance(0); session.next(); clock.advance(0);
            session.cancel(Reason.USER);
            if (recreate) { session.dispose(); session = create(); }
            int[] completions = {0};
            session.addListener(event -> { if (event.kind == Kind.STEP_COMPLETED) completions[0]++; });
            session.start(); clock.advance(0); assertEquals("second", session.step().id);
            ProgressStore.Progress before = store.load(tutorial);
            session.previous(); clock.advance(0);
            assertEquals("first", session.step().id); assertEquals(State.SHOWING, session.getState());
            assertEquals(before.revision, store.load(tutorial).revision);
            session.next(); clock.advance(0);
            assertEquals("second", session.step().id); assertEquals(0, completions[0]);
            assertEquals(Collections.singleton("first"), store.load(tutorial).completed);
            session.dispose();
        }
    }

    @Test public void restoredBacktrackingReturnsThroughEachReviewedStep() {
        tutorial = new Tutorial("review-history", 1, Step.builder("a").build(), Step.builder("b").build(), Step.builder("c").build());
        assertTrue(store.save(tutorial.id, 0, new ProgressStore.Progress(1, 1,
                new HashSet<>(Arrays.asList("a", "b")), Collections.emptySet(), ProgressStore.Outcome.ACTIVE, "c")));
        TutorialSession session = create(); session.start(); clock.advance(0);
        session.previous(); clock.advance(0); assertEquals("b", session.step().id);
        session.previous(); clock.advance(0); assertEquals("a", session.step().id);
        session.next(); clock.advance(0); assertEquals("b", session.step().id);
        session.skipStep(); clock.advance(0); assertEquals("c", session.step().id);
        assertTrue(store.load(tutorial).skipped.isEmpty());
        session.next(); assertEquals(State.COMPLETED, session.getState()); session.dispose();
    }

    @Test public void resumedPreviousSkipsUnvisitedStepsAndReturnsToOriginalBranch() {
        final String[] branch = {"c"};
        tutorial = new Tutorial("resume-branch", 1, Step.builder("a").branch(() -> branch[0]).build(),
                Step.builder("unvisited").build(), Step.builder("c").build());
        TutorialSession session = create(); session.start(); clock.advance(0); session.next(); clock.advance(0);
        session.cancel(Reason.USER); session.start(); clock.advance(0);
        branch[0] = "unvisited";
        session.previous(); clock.advance(0); assertEquals("a", session.step().id);
        session.next(); clock.advance(0); assertEquals("c", session.step().id);
        session.dispose();
    }

    @Test public void queuedResumeRestoresPreviousFromFreshProgress() {
        tutorial = new Tutorial("queued-history", 1, Step.builder("first").build(), Step.builder("second").build());
        TutorialSession first = create(); first.start(); clock.advance(0);
        TutorialSession queued = new TutorialSession(tutorial, new Host(), clock, store, coordinator, TutorialCoordinator.Conflict.QUEUE);
        queued.start(); first.next(); clock.advance(0); first.cancel(Reason.USER); clock.advance(0);
        assertEquals("second", queued.step().id);
        queued.previous(); clock.advance(0); assertEquals("first", queued.step().id);
        queued.cancel(Reason.USER); queued.start(); clock.advance(0);
        assertEquals("second", queued.step().id);
        first.dispose(); queued.dispose();
    }
    @Test public void queuedSameIdentityReloadsCompletedHistory() {
        tutorial=new Tutorial("same",1,Step.builder("a").build());TutorialSession first=create();first.start();Host other=new Host();TutorialSession second=new TutorialSession(tutorial,other,clock,store,coordinator,TutorialCoordinator.Conflict.QUEUE);second.start();clock.advance(0);first.next();clock.advance(0);assertEquals(State.COMPLETED,second.getState());assertEquals(0,other.displays);
    }
    @Test public void rejectReplaceAndDeduplicateReleaseAllOwnership() {
        for(TutorialCoordinator.Conflict policy:new TutorialCoordinator.Conflict[]{TutorialCoordinator.Conflict.REJECT,TutorialCoordinator.Conflict.REPLACE,TutorialCoordinator.Conflict.DEDUPLICATE}){
            TutorialSession first=create();first.start();TutorialSession second=new TutorialSession(tutorial,new Host(),clock,store,coordinator,policy);second.start();
            assertEquals(policy==TutorialCoordinator.Conflict.REPLACE?State.CANCELLED:State.PREPARING,first.getState());
            if(policy!=TutorialCoordinator.Conflict.REPLACE)assertEquals(State.CANCELLED,second.getState());first.dispose();second.dispose();assertEquals(0,coordinator.pendingCount());
        }
    }
    @Test public void disposeRejectsReentrantStartFromTerminalListener() {
        TutorialSession s=create();s.start();int[] rejected={0};s.addListener(e->{if(e.state==State.CANCELLED)try{s.start();}catch(IllegalStateException expected){rejected[0]++;}});s.dispose();clock.advance(1000);assertEquals(1,rejected[0]);assertEquals(0,host.observations);assertEquals(0,host.displays);
    }
    @Test public void cancelledEntranceAndSkippedTourEmitOneOutcome() {
        Host delayed=new Host(){@Override public Cancellation show(Step step,Actions actions,Runnable callback){shown=callback;overlay=true;return ()->overlay=false;}};
        TutorialSession s=new TutorialSession(tutorial,delayed,clock,store,coordinator,TutorialCoordinator.Conflict.QUEUE);List<State> outcomes=new ArrayList<>();s.addListener(e->{if(e.state==State.SKIPPED||e.state==State.CANCELLED||e.state==State.COMPLETED)outcomes.add(e.state);});s.start();clock.advance(100);s.skipTour();delayed.shown.run();assertEquals(Collections.singletonList(State.SKIPPED),outcomes);assertFalse(delayed.overlay);
    }
    @Test public void branchMissingAndCycleFailBeforeProgressChange() {
        tutorial=new Tutorial("branchcycle",1,Step.builder("a").branch(()->"b").build(),Step.builder("b").branch(()->"a").build());TutorialSession s=create();s.start();clock.advance(0);s.next();clock.advance(0);s.next();assertEquals(State.FAILED,s.getState());assertEquals(Collections.singleton("a"),store.load(tutorial).completed);
    }
    @Test public void repeatedDisposalReleasesEveryObserver() {
        for(int i=0;i<100;i++){TutorialSession s=create();s.start();clock.advance(100);s.dispose();s.dispose();assertEquals(0,host.observations);assertEquals(0,coordinator.pendingCount());assertFalse(host.overlay);}
    }
    @Test public void cancellationFromPreparationCleanupCannotCommit() {
        TutorialSession[] s={null};Host reentrant=new Host(){
            @Override public Cancellation prepare(Step step,Scope scope,Runnable complete){scope.own(()->s[0].cancel(Reason.USER));complete.run();return Cancellation.NONE;}
        };
        s[0]=new TutorialSession(tutorial,reentrant,clock,store,coordinator,TutorialCoordinator.Conflict.QUEUE);s[0].start();clock.advance(100);s[0].next();assertEquals(State.CANCELLED,s[0].getState());assertTrue(store.load(tutorial).completed.isEmpty());assertFalse(reentrant.overlay);
    }
    @Test public void pausedQueuedSessionDoesNotAcquireUntilResumed() {
        TutorialSession first=create();first.start();Host secondHost=new Host();TutorialSession second=new TutorialSession(tutorial,secondHost,clock,store,coordinator,TutorialCoordinator.Conflict.QUEUE);second.start();second.pause(Reason.USER);first.cancel(Reason.USER);clock.advance(1000);assertEquals(0,secondHost.displays);second.resume();clock.advance(100);assertEquals(1,secondHost.displays);
    }
    @Test public void repeatedReadinessAttemptsCannotExtendTimeout() {
        Host clipped=new Host(){public boolean valid(Step step,boolean visible){return !visible;}};
        tutorial=new Tutorial("clipped",1,Step.builder("a").timeout(100).build());TutorialSession s=new TutorialSession(tutorial,clipped,clock,store,coordinator,TutorialCoordinator.Conflict.QUEUE);s.start();clock.advance(0);
        for(int i=0;i<9;i++){clock.advance(10);clipped.changed.run();clock.advance(0);}clock.advance(11);assertEquals(State.FAILED,s.getState());
    }
    @Test public void backgroundImmediatelyBeforeShownDoesNotSkipOptionalStep() {
        Host delayed=new Host(){@Override public Cancellation show(Step step,Actions actions,Runnable callback){shown=callback;return Cancellation.NONE;}};
        tutorial=new Tutorial("optional-gate",1,Step.builder("a").optional(true).unavailable(Step.Unavailable.SKIP_OPTIONAL).build());TutorialSession s=new TutorialSession(tutorial,delayed,clock,store,coordinator,TutorialCoordinator.Conflict.QUEUE);s.start();clock.advance(0);delayed.ready=false;delayed.shown.run();assertEquals(State.PAUSED,s.getState());assertTrue(store.load(tutorial).skipped.isEmpty());
    }
    @Test public void branchingResumePreservesStableDestinationAcrossInsertion() {
        tutorial=new Tutorial("branch-resume",1,Step.builder("a").branch(()->"c").build(),Step.builder("b").build(),Step.builder("c").build());TutorialSession s=create();s.start();clock.advance(0);s.next();clock.advance(0);s.cancel(Reason.USER);
        tutorial=new Tutorial("branch-resume",2,Step.builder("new").build(),Step.builder("c").build(),Step.builder("b").build(),Step.builder("a").build());s=create();s.start();clock.advance(0);assertEquals("c",s.step().id);
    }

    @Test public void queuedTutorialAppliesMigrationAfterReloadingProgress() {
        for (Tutorial.Migration migration : new Tutorial.Migration[]{Tutorial.Migration.RESET, Tutorial.Migration.FAIL}) {
            Tutorial older = new Tutorial("queued-" + migration, 1, Step.builder("a").build());
            Tutorial newer = new Tutorial(older.id, 2, migration, Step.builder("a").build());
            TutorialSession first = new TutorialSession(older, new Host(), clock, store, coordinator, TutorialCoordinator.Conflict.QUEUE);
            Host secondHost = new Host();
            TutorialSession second = new TutorialSession(newer, secondHost, clock, store, coordinator, TutorialCoordinator.Conflict.QUEUE);
            first.start(); second.start();
            assertEquals(State.QUEUED, second.getState());
            clock.advance(0); first.next(); clock.advance(0);
            assertEquals(migration == Tutorial.Migration.RESET ? State.SHOWING : State.FAILED, second.getState());
            assertEquals(migration == Tutorial.Migration.RESET ? 1 : 0, secondHost.displays);
            first.dispose(); second.dispose();
        }
    }

    @Test public void coordinatorDisposesEverySessionEvenWhenOneListenerThrows() {
        TutorialSession first = create(); first.start(); clock.advance(100);
        TutorialSession queued = new TutorialSession(tutorial, new Host(), clock, store, coordinator, TutorialCoordinator.Conflict.QUEUE);
        queued.start();
        queued.addListener(event -> { if (event.state == State.CANCELLED) throw new IllegalStateException("cleanup"); });
        try { coordinator.cancel(); fail("Expected listener failure"); } catch (IllegalStateException expected) { }
        assertEquals(0, coordinator.pendingCount());
        assertEquals(0, host.observations);
        assertFalse(host.overlay);
        assertFalse(first.isActive());
    }

    @Test public void resetAllowsStrictMigrationAtCurrentVersion() {
        tutorial = new Tutorial("reset-version", 3, Tutorial.Migration.FAIL, Step.builder("a").build());
        store.reset(tutorial.id);
        TutorialSession session = create(); session.start(); clock.advance(0);
        assertEquals(State.SHOWING, session.getState());
        session.next(); session.resetProgress(); session.start(); clock.advance(0);
        assertEquals(State.SHOWING, session.getState());
        session.dispose();
    }

    @Test public void resettingReplayDoesNotEraseNormalProgress() {
        TutorialSession normal = create(); normal.start(); clock.advance(100); normal.next(); normal.cancel(Reason.USER);
        ProgressStore.Progress saved = store.load(tutorial);
        TutorialSession replay = new TutorialSession(tutorial, new Host(), clock, store, coordinator, TutorialCoordinator.Conflict.QUEUE, true);
        replay.start(); clock.advance(100); replay.resetProgress();
        assertEquals(saved.revision, store.load(tutorial).revision);
        assertEquals(saved.completed, store.load(tutorial).completed);
        assertTrue(replay.getProgress().completed.isEmpty()); replay.dispose(); normal.dispose();
    }
}
