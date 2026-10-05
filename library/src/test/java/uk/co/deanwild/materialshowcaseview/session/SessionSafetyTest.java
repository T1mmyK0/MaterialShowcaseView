package uk.co.deanwild.materialshowcaseview.session;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static uk.co.deanwild.materialshowcaseview.session.TutorialSession.*;

public class SessionSafetyTest {
    final TutorialSessionTest.Clock clock = new TutorialSessionTest.Clock();
    final TutorialCoordinator coordinator = new TutorialCoordinator();
    final MemoryProgressStore store = new MemoryProgressStore();
    final Tutorial tutorial = new Tutorial("safety", 1, Step.builder("a").build(), Step.builder("b").build());
    TutorialSession session(TutorialHost host) { return new TutorialSession(tutorial, host, clock, store, coordinator, TutorialCoordinator.Conflict.QUEUE); }
    static boolean terminal(Event event) { return event.state == State.CANCELLED || event.state == State.FAILED || event.state == State.COMPLETED || event.state == State.SKIPPED; }

    @Test public void restartsFromEveryCleanupWaitForOldRunToFinish() {
        for (int source = 0; source < 3; source++) {
            final int cleanupSource = source;
            TutorialSession[] session = {null}; boolean[] restart = {true};
            TutorialSessionTest.Host host = new TutorialSessionTest.Host() {
                void restart() { if (restart[0]) { restart[0] = false; session[0].start(); } }
                @Override public Cancellation prepare(Step s, Scope scope, Runnable ready) {
                    ready.run(); return () -> { cleanups++; if (cleanupSource == 0) restart(); };
                }
                @Override public Cancellation show(Step s, Actions actions, Runnable shown) {
                    overlay = true; shown.run(); return () -> { overlay = false; if (cleanupSource == 1) restart(); };
                }
                @Override public Cancellation observe(Runnable changed) {
                    Cancellation original = super.observe(changed);
                    return () -> { if (cleanupSource == 2) restart(); original.cancel(); };
                }
            };
            session[0] = session(host); List<Event> ended = new ArrayList<>();
            session[0].addListener(e -> { if (terminal(e)) { ended.add(e); assertEquals(0, host.observations); assertFalse(host.overlay); } });
            session[0].start(); clock.advance(0); session[0].cancel(Reason.USER); clock.advance(0);
            assertEquals(State.SHOWING, session[0].getState()); assertTrue(host.overlay);
            assertEquals(1, host.observations); assertEquals(1, coordinator.pendingCount());
            assertEquals(1, ended.size()); assertEquals(1, ended.get(0).runId);
            TutorialSession other = session(new TutorialSessionTest.Host()); other.start();
            assertEquals(State.QUEUED, other.getState()); other.dispose(); session[0].dispose();
            assertEquals(2, ended.size()); assertEquals(2, ended.get(1).runId);
        }
    }

    @Test public void terminalDispatchSnapshotSurvivesRestartFromFirstListener() {
        TutorialSessionTest.Host host = new TutorialSessionTest.Host(); TutorialSession s = session(host);
        List<Event> first = new ArrayList<>(), second = new ArrayList<>();
        s.addListener(e -> { if (terminal(e)) { first.add(e); if (e.runId == 1) s.start(); } });
        s.addListener(e -> { if (terminal(e)) { second.add(e); assertEquals(0, coordinator.pendingCount()); } });
        s.start(); clock.advance(0); s.cancel(Reason.USER); clock.advance(0);
        assertEquals(State.SHOWING, s.getState()); assertEquals(1, host.observations); assertEquals(1, coordinator.pendingCount());
        assertEquals(1, first.size()); assertEquals(1, second.size()); assertSame(first.get(0), second.get(0));
        s.dispose(); assertEquals(2, first.size()); assertEquals(2, second.size());
    }

    @Test public void terminalListenerFailureDoesNotCancelRequestedRestart() {
        TutorialSessionTest.Host host = new TutorialSessionTest.Host(); TutorialSession s = session(host);
        List<RuntimeException> errors = new ArrayList<>(); s.setErrorHandler(errors::add);
        s.addListener(e -> { if (terminal(e) && e.runId == 1) { s.start(); throw new IllegalStateException("terminal"); } });
        s.start(); clock.advance(0); s.cancel(Reason.USER); clock.advance(0);
        assertEquals(1, errors.size()); assertEquals(State.SHOWING, s.getState());
        assertEquals(1, host.observations); assertEquals(1, coordinator.pendingCount()); s.dispose();
    }

    static class ActionHost extends TutorialSessionTest.Host {
        final List<TutorialHost.Actions> actions = new ArrayList<>();
        @Override public Cancellation show(Step s, Actions a, Runnable shown) { actions.add(a); return super.show(s, a, shown); }
    }
    @Test public void duplicateAndOldPresentationActionsCannotActOnOtherStepsOrRuns() {
        ActionHost host = new ActionHost(); TutorialSession s = session(host); s.start(); clock.advance(0);
        TutorialHost.Actions old = host.actions.get(0);
        old.actionCompleted(); clock.advance(0); old.actionCompleted(); old.next(); old.skipTour(); old.close();
        assertEquals("b", s.step().id); assertEquals(State.SHOWING, s.getState());
        s.cancel(Reason.USER); s.start(); clock.advance(0);
        old.next(); old.actionCompleted(); assertEquals("b", s.step().id); assertEquals(State.SHOWING, s.getState());
        s.dispose(); old.close(); old.next(); old.actionCompleted(); assertEquals(0, coordinator.pendingCount());
    }

    @Test public void completionHandlesAreOneShotAndInvalidatedByEveryTransition() {
        for (int transition = 0; transition < 5; transition++) {
            MemoryProgressStore localStore = new MemoryProgressStore(); ActionHost host = new ActionHost();
            TutorialSession s = new TutorialSession(tutorial, host, clock, localStore, coordinator, TutorialCoordinator.Conflict.QUEUE);
            s.start(); clock.advance(0); Runnable old = s.completionHandle();
            if (transition == 0) { old.run(); clock.advance(0); }
            if (transition == 1) { s.pause(Reason.USER); s.resume(); clock.advance(0); }
            if (transition == 2) { s.cancel(Reason.USER); s.start(); clock.advance(0); }
            if (transition == 3) { s.next(); clock.advance(0); s.previous(); clock.advance(0); }
            if (transition == 4) { s.dispose(); }
            State before = s.getState(); long revision = localStore.load(tutorial).revision;
            old.run(); old.run(); assertEquals(before, s.getState()); assertEquals(revision, localStore.load(tutorial).revision);
            s.dispose();
        }
    }

    @Test public void providerReplacementInvalidatesOldActionsAndCompletion() {
        ActionHost host = new ActionHost(); TutorialSession first = session(host); first.start(); clock.advance(0);
        Runnable done = first.completionHandle(); TutorialHost.Actions actions = host.actions.get(0);
        TutorialSession second = new TutorialSession(new Tutorial("provider-b", 1, Step.builder("a").build()),
                new ActionHost(), clock, store, coordinator, TutorialCoordinator.Conflict.REPLACE);
        second.start(); clock.advance(0); done.run(); actions.next(); actions.activateTarget(() -> fail("stale activation"));
        assertEquals(State.CANCELLED, first.getState()); assertEquals(State.SHOWING, second.getState());
        assertTrue(store.load(tutorial).completed.isEmpty()); assertEquals(1, coordinator.pendingCount()); first.dispose(); second.dispose();
    }

    @Test public void manualPauseSurvivesBothBlockerOrderingsAndLifecycleChanges() {
        for (boolean manualFirst : new boolean[]{true, false}) {
            TutorialSessionTest.Host host = new TutorialSessionTest.Host(); TutorialSession s = session(host);
            s.start(); clock.advance(0);
            if (manualFirst) s.pause(Reason.USER);
            Cancellation one = s.block("one"), two = s.block("two");
            if (!manualFirst) s.pause(Reason.USER);
            host.ready = false; host.changed.run(); one.cancel(); s.setEligible(false);
            host.ready = true; host.changed.run(); two.cancel(); s.setEligible(true); clock.advance(0);
            assertEquals(State.PAUSED, s.getState()); assertFalse(host.overlay);
            s.resume(); clock.advance(0); assertEquals(State.SHOWING, s.getState()); s.dispose();
        }
    }

    @Test public void explicitPauseAfterBlockedStartRequiresExplicitResume() {
        TutorialSessionTest.Host host = new TutorialSessionTest.Host(); TutorialSession s = session(host);
        Cancellation blocker = s.block("dialog"); s.start(); s.pause(Reason.USER); blocker.cancel(); clock.advance(0);
        assertEquals(State.PAUSED, s.getState()); assertEquals(0, host.displays);
        s.resume(); clock.advance(0); assertEquals(State.SHOWING, s.getState()); s.dispose();
    }
    @Test public void diagnosticFailureCannotPreventTerminalEventOrCleanup() {
        boolean[] fail = {false}; TutorialSessionTest.Host host = new TutorialSessionTest.Host() {
            @Override public String diagnostic() { if (fail[0]) throw new IllegalStateException("diagnostic"); return ""; }
        };
        TutorialSession s = session(host); List<Event> ended = new ArrayList<>(); List<RuntimeException> errors = new ArrayList<>();
        s.setErrorHandler(errors::add); s.addListener(e -> { if (terminal(e)) ended.add(e); });
        s.start(); clock.advance(0); fail[0] = true; s.cancel(Reason.USER);
        assertEquals(1, ended.size()); assertEquals(State.CANCELLED, ended.get(0).state); assertEquals(1, errors.size());
        assertEquals(0, coordinator.pendingCount()); assertEquals(0, host.observations); assertFalse(host.overlay); s.dispose();
    }
}
