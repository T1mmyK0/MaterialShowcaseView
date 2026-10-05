package uk.co.deanwild.materialshowcaseview.session;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static uk.co.deanwild.materialshowcaseview.session.TutorialSession.*;

public class ReentrantSessionTest {
    final TutorialSessionTest.Clock clock = new TutorialSessionTest.Clock();
    final TutorialCoordinator coordinator = new TutorialCoordinator();
    final MemoryProgressStore store = new MemoryProgressStore();
    final Tutorial tutorial = new Tutorial("reentrant", 1, Step.builder("a").build(), Step.builder("b").build());
    TutorialSession session(Tutorial definition, TutorialHost host, TutorialCoordinator.Conflict policy) {
        return new TutorialSession(definition, host, clock, store, coordinator, policy);
    }
    TutorialSession session(TutorialHost host) { return session(tutorial, host, TutorialCoordinator.Conflict.QUEUE); }

    @Test public void completionListenerPauseResumesAtCommittedDestination() {
        TutorialSession s = session(new TutorialSessionTest.Host());
        List<String> completed = new ArrayList<>();
        s.addListener(event -> {
            if (event.kind == Kind.STEP_COMPLETED) {
                completed.add(event.stepId);
                s.pause(Reason.USER);
            }
        });
        s.start(); clock.advance(0); s.next();
        assertEquals(State.PAUSED, s.getState());
        assertEquals("b", store.load(tutorial).nextStepId);
        s.resume(); clock.advance(0);
        assertEquals(State.SHOWING, s.getState());
        assertEquals("b", s.step().id);
        assertEquals(Collections.singletonList("a"), completed);
        s.previous(); clock.advance(0);
        assertEquals("a", s.step().id);
        s.dispose();
    }

    @Test public void skippedStepListenerBlockPreservesBranchDestination() {
        Tutorial branched = new Tutorial("skip-branch", 1,
                Step.builder("a").branch(() -> "c").build(), Step.builder("b").build(), Step.builder("c").build());
        TutorialSession s = session(branched, new TutorialSessionTest.Host(), TutorialCoordinator.Conflict.QUEUE);
        Cancellation[] blocker = {Cancellation.NONE};
        s.addListener(event -> {
            if (event.kind == Kind.STEP_SKIPPED) {
                assertEquals("a", event.stepId);
                blocker[0] = s.block("dialog opened by step listener");
            }
        });
        s.start(); clock.advance(0); s.skipStep();
        assertEquals(State.PAUSED, s.getState());
        blocker[0].cancel(); clock.advance(0);
        assertEquals(State.SHOWING, s.getState());
        assertEquals("c", s.step().id);
        s.dispose();
    }

    @Test public void finalCompletionListenerPauseDoesNotRedisplayFinishedStep() {
        TutorialSession s = session(new Tutorial("last-step", 1, Step.builder("a").build()),
                new TutorialSessionTest.Host(), TutorialCoordinator.Conflict.QUEUE);
        s.addListener(event -> { if (event.kind == Kind.STEP_COMPLETED) s.pause(Reason.USER); });
        s.start(); clock.advance(0); s.next(); s.resume(); clock.advance(0);
        assertEquals(State.COMPLETED, s.getState());
        assertEquals(0, coordinator.pendingCount());
        s.dispose();
    }

    @Test public void cancellationAlsoCancelsRestartRequestedByTerminalListener() {
        for (boolean duringDispatch : new boolean[]{true, false}) {
            TutorialSessionTest.Host host = new TutorialSessionTest.Host(); TutorialSession s = session(host);
            List<Event> ended = new ArrayList<>();
            s.addListener(e -> { if (SessionSafetyTest.terminal(e)) { ended.add(e); s.start(); if (duringDispatch) s.cancel(Reason.USER); } });
            s.start(); clock.advance(0); s.cancel(Reason.USER);
            if (!duringDispatch) s.cancel(Reason.USER);
            clock.advance(0);
            assertEquals(State.CANCELLED, s.getState()); assertFalse(s.isActive());
            assertEquals(1, ended.size()); assertEquals(0, host.observations); assertEquals(0, coordinator.pendingCount()); s.dispose();
        }
    }

    @Test public void observationRegistrationCannotOverrideExplicitPause() {
        TutorialSession[] s = {null};
        TutorialSessionTest.Host host = new TutorialSessionTest.Host() {
            @Override public Cancellation observe(Runnable changed) { s[0].pause(Reason.USER); return super.observe(changed); }
        };
        s[0] = session(host); s[0].start(); clock.advance(0);
        assertEquals(State.PAUSED, s[0].getState()); assertEquals(0, host.displays);
        s[0].resume(); clock.advance(0); assertEquals(State.SHOWING, s[0].getState()); s[0].dispose();
    }

    @Test public void targetValidationCannotAdvanceAfterPausing() {
        TutorialSession[] s = {null}; boolean[] pause = {false};
        TutorialSessionTest.Host host = new TutorialSessionTest.Host() {
            @Override public boolean valid(Step step, boolean visible) {
                if (pause[0]) { pause[0] = false; s[0].pause(Reason.USER); } return true;
            }
        };
        s[0] = session(host); s[0].start(); clock.advance(0); pause[0] = true; s[0].next(); clock.advance(0);
        assertEquals(State.PAUSED, s[0].getState()); assertEquals("a", s[0].step().id);
        assertTrue(store.load(tutorial).completed.isEmpty()); assertFalse(host.overlay); s[0].dispose();
    }

    @Test public void branchCannotCommitIntoRestartedRun() {
        TutorialSession[] s = {null}; boolean[] restart = {true};
        Tutorial definition = new Tutorial("branch", 1, Step.builder("a").branch(() -> {
            if (restart[0]) { restart[0] = false; s[0].cancel(Reason.USER); s[0].start(); } return "b";
        }).build(), Step.builder("b").build());
        TutorialSessionTest.Host host = new TutorialSessionTest.Host(); s[0] = session(definition, host, TutorialCoordinator.Conflict.QUEUE);
        s[0].start(); clock.advance(0); s[0].next(); clock.advance(0);
        assertEquals(State.SHOWING, s[0].getState()); assertEquals("a", s[0].step().id);
        assertTrue(store.load(definition).completed.isEmpty()); assertEquals(1, host.observations);
        assertEquals(1, coordinator.pendingCount()); s[0].dispose();
    }

    @Test public void disposalFromEligibilityIsAnOrdinaryCancellation() {
        TutorialSessionTest.Host host = new TutorialSessionTest.Host(); TutorialSession s = session(host);
        List<RuntimeException> errors = new ArrayList<>(); s.setErrorHandler(errors::add);
        s.setEligibility(() -> { s.dispose(); return true; }); s.start(); clock.advance(0);
        assertEquals(State.CANCELLED, s.getState()); assertTrue(errors.isEmpty());
        assertEquals(0, coordinator.pendingCount()); assertEquals(0, host.observations);
    }

    @Test public void terminalEventIsCapturedBeforeDiagnosticCallback() {
        TutorialSession[] s = {null}; boolean[] dispose = {false};
        TutorialSessionTest.Host host = new TutorialSessionTest.Host() {
            @Override public String diagnostic() { if (dispose[0]) s[0].dispose(); return "diagnostic"; }
        };
        s[0] = session(host); List<Event> ended = new ArrayList<>();
        s[0].addListener(e -> { if (SessionSafetyTest.terminal(e)) ended.add(e); });
        s[0].start(); clock.advance(0); dispose[0] = true; s[0].cancel(Reason.USER);
        assertEquals(1, ended.size()); assertEquals("a", ended.get(0).stepId); assertEquals(2, ended.get(0).definedSteps);
        assertEquals(1, ended.get(0).runId); assertEquals(0, host.observations); assertEquals(0, coordinator.pendingCount());
    }

    @Test public void replacementCannotAcquireCancelledOrDisposedRequester() {
        for (boolean closeCoordinator : new boolean[]{true, false}) {
            TutorialCoordinator local = new TutorialCoordinator(); TutorialSessionTest.Host firstHost = new TutorialSessionTest.Host(), secondHost = new TutorialSessionTest.Host();
            TutorialSession first = new TutorialSession(tutorial, firstHost, clock, store, local, TutorialCoordinator.Conflict.QUEUE);
            TutorialSession second = new TutorialSession(tutorial, secondHost, clock, store, local, TutorialCoordinator.Conflict.REPLACE);
            first.addListener(e -> { if (e.state == State.CANCELLED) { if (closeCoordinator) local.cancel(); else second.cancel(Reason.USER); } });
            first.start(); clock.advance(0); second.start(); clock.advance(0);
            assertFalse(first.isActive()); assertFalse(second.isActive()); assertEquals(0, local.pendingCount());
            assertEquals(0, firstHost.observations); assertEquals(0, secondHost.observations); first.dispose(); second.dispose();
        }
    }

    @Test public void nestedReplacementWaitsForAllOutgoingResources() {
        TutorialSession[] third = {null}; boolean[] replace = {true};
        TutorialSessionTest.Host thirdHost = new TutorialSessionTest.Host();
        TutorialSessionTest.Host firstHost = new TutorialSessionTest.Host() {
            @Override public Cancellation show(Step step, Actions actions, Runnable complete) {
                Cancellation presentation = super.show(step, actions, complete);
                return () -> {
                    if (replace[0]) {
                        replace[0] = false; assertTrue(overlay); third[0].start();
                        assertEquals(0, thirdHost.observations); assertEquals(0, thirdHost.displays);
                    }
                    presentation.cancel();
                };
            }
        };
        TutorialSession first = session(firstHost), second = session(tutorial, new TutorialSessionTest.Host(), TutorialCoordinator.Conflict.REPLACE);
        third[0] = session(tutorial, thirdHost, TutorialCoordinator.Conflict.REPLACE);
        first.start(); clock.advance(0); second.start(); clock.advance(0);
        assertEquals(State.CANCELLED, first.getState()); assertEquals(State.CANCELLED, second.getState());
        assertEquals(State.SHOWING, third[0].getState()); assertEquals(1, coordinator.pendingCount());
        assertEquals(0, firstHost.observations); assertEquals(1, thirdHost.observations);
        first.dispose(); second.dispose(); third[0].dispose();
    }

    @Test public void requesterCanPauseDuringReplacementThenExplicitlyResume() {
        TutorialSession first = session(new TutorialSessionTest.Host()); TutorialSessionTest.Host host = new TutorialSessionTest.Host();
        TutorialSession second = session(tutorial, host, TutorialCoordinator.Conflict.REPLACE);
        first.addListener(e -> { if (e.state == State.CANCELLED) second.pause(Reason.USER); });
        first.start(); clock.advance(0); second.start(); clock.advance(0);
        assertEquals(State.PAUSED, second.getState()); assertEquals(0, coordinator.pendingCount()); assertEquals(0, host.observations);
        second.resume(); clock.advance(0); assertEquals(State.SHOWING, second.getState()); assertEquals(1, coordinator.pendingCount());
        first.dispose(); second.dispose();
    }

    @Test public void throwingOutgoingListenerCannotLeaveOtherSessionsOrphaned() {
        TutorialSessionTest.Host host = new TutorialSessionTest.Host(); TutorialSession first = session(host), queued = session(new TutorialSessionTest.Host());
        queued.addListener(e -> { if (e.state == State.CANCELLED) throw new IllegalStateException("outgoing listener"); });
        TutorialSession second = session(tutorial, new TutorialSessionTest.Host(), TutorialCoordinator.Conflict.REPLACE);
        List<RuntimeException> errors = new ArrayList<>(); second.setErrorHandler(errors::add);
        first.start(); clock.advance(0); queued.start(); second.start(); clock.advance(0);
        assertFalse(first.isActive()); assertFalse(queued.isActive()); assertFalse(second.isActive()); assertEquals(State.FAILED, second.getState());
        assertEquals(1, errors.size()); assertEquals(0, coordinator.pendingCount()); assertEquals(0, host.observations); assertFalse(host.overlay);
        first.dispose(); queued.dispose(); second.dispose();
    }

    @Test public void resetCancelsRestartFromTerminalListener() {
        TutorialSession s = session(new TutorialSessionTest.Host());
        s.addListener(e -> { if (SessionSafetyTest.terminal(e)) s.start(); });
        s.start(); clock.advance(0); s.resetProgress(); clock.advance(0);
        assertFalse(s.isActive()); assertEquals(0, coordinator.pendingCount()); assertTrue(store.load(tutorial).completed.isEmpty()); s.dispose();
    }

    @Test public void failureInsideErrorHandlerRestartStillReleasesNewRun() {
        TutorialSessionTest.Host host = new TutorialSessionTest.Host() {
            @Override public boolean ready() { throw new IllegalStateException("host failure"); }
        };
        TutorialSession s = session(host); List<Event> ended = new ArrayList<>(); int[] reported = {0};
        s.addListener(e -> { if (SessionSafetyTest.terminal(e)) ended.add(e); });
        s.setErrorHandler(error -> { reported[0]++; s.start(); });
        try { s.start(); fail("The failure inside the error handler must propagate"); }
        catch (IllegalStateException expected) { assertEquals("host failure", expected.getMessage()); }
        assertFalse(s.isActive()); assertEquals(State.FAILED, s.getState()); assertEquals(1, reported[0]);
        assertEquals(2, ended.size()); assertEquals(1, ended.get(0).runId); assertEquals(2, ended.get(1).runId);
        assertEquals(0, host.observations); assertEquals(0, coordinator.pendingCount()); s.dispose();
    }

    @Test public void synchronousShownCancellationReleasesResourcesBeforeTerminalDispatch() {
        TutorialSessionTest.Host host = new TutorialSessionTest.Host(); TutorialSession s = session(host);
        List<Boolean> cleanAtTerminal = new ArrayList<>();
        s.addListener(e -> {
            if (e.kind == Kind.SHOWN) s.cancel(Reason.USER);
            if (SessionSafetyTest.terminal(e)) cleanAtTerminal.add(!host.overlay && host.cleanups == 1 && host.observations == 0 && coordinator.pendingCount() == 0);
        });
        s.start(); clock.advance(0);
        assertEquals(Collections.singletonList(true), cleanAtTerminal); s.dispose();
    }

    @Test public void synchronousExitResourceIsReleasedBeforeCompletionDispatch() {
        int[] animations = {0}; TutorialSessionTest.Host host = new TutorialSessionTest.Host() {
            @Override public Cancellation hide(Runnable complete) { animations[0]++; complete.run(); return () -> animations[0]--; }
        };
        TutorialSession s = session(new Tutorial("sync-exit", 1, Step.builder("a").build()), host, TutorialCoordinator.Conflict.QUEUE);
        List<Integer> activeAtTerminal = new ArrayList<>();
        s.addListener(e -> { if (SessionSafetyTest.terminal(e)) activeAtTerminal.add(animations[0]); });
        s.start(); clock.advance(0); s.next(); assertEquals(Collections.singletonList(0), activeAtTerminal); s.dispose();
    }
}
