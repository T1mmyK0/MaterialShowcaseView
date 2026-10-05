package uk.co.deanwild.materialshowcaseview.session;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import java.util.*;
import static org.junit.Assert.*;
import static uk.co.deanwild.materialshowcaseview.session.TutorialSession.*;

/** L1/L2: each exit path must obey the same ownership and stale-work contract. */
@RunWith(Parameterized.class)
public class SessionContractMatrixTest {
    enum Phase { QUEUED, WAITING, DELAY, PREPARATION, ENTRANCE, SHOWING, HIDING, PAUSED }
    enum Exit { CANCEL, DISPOSE, COORDINATOR }
    @Parameterized.Parameters(name = "{0}/{1}") public static Collection<Object[]> cases() {
        List<Object[]> result = new ArrayList<>();
        for (Phase phase : Phase.values()) for (Exit exit : Exit.values()) result.add(new Object[]{phase, exit});
        return result;
    }
    private final Phase phase;
    private final Exit exit;
    public SessionContractMatrixTest(Phase phase, Exit exit) { this.phase = phase; this.exit = exit; }

    static class Host implements TutorialHost {
        boolean valid = true, immediate = false;
        int resources, displays;
        final List<Runnable> callbacks = new ArrayList<>();
        final List<Actions> actions = new ArrayList<>();
        Runnable preparation, entrance, hidden;
        private Cancellation resource() {
            resources++; boolean[] closed = {false};
            return () -> { assertFalse("resource cancelled twice", closed[0]); closed[0] = true; resources--; };
        }
        public boolean ready() { return true; }
        public boolean valid(Step step, boolean visible) { return valid; }
        public Cancellation observe(Runnable changed) { callbacks.add(changed); return resource(); }
        public Cancellation prepare(Step step, Scope scope, Runnable ready) {
            preparation = ready; callbacks.add(ready); Cancellation resource = resource();
            if (immediate) { ready.run(); ready.run(); }
            return resource;
        }
        public Cancellation show(Step step, Actions action, Runnable shown) {
            displays++; actions.add(action); entrance = shown; callbacks.add(shown); Cancellation resource = resource();
            if (immediate) { shown.run(); shown.run(); }
            return resource;
        }
        public Cancellation hide(Runnable complete) {
            hidden = complete; callbacks.add(complete); Cancellation resource = resource();
            if (immediate) { complete.run(); complete.run(); }
            return resource;
        }
        public void cancel() { assertEquals("host disposed with unowned work", 0, resources); }
    }

    @Test public void everyExitReleasesResourcesAndRejectsOldCallbacks() {
        TutorialSessionTest.Clock clock = new TutorialSessionTest.Clock();
        TutorialCoordinator coordinator = new TutorialCoordinator();
        MemoryProgressStore store = new MemoryProgressStore(); Host host = new Host();
        Tutorial tutorial = new Tutorial("matrix", 1, Step.builder("first").delay(10).build(), Step.builder("second").build());
        TutorialSession session = new TutorialSession(tutorial, host, clock, store, coordinator, TutorialCoordinator.Conflict.QUEUE);
        TutorialSession blocker = null;
        if (phase == Phase.QUEUED) {
            blocker = new TutorialSession(new Tutorial("owner", 1, Step.builder("owner").build()),
                    new Host(), clock, null, coordinator, TutorialCoordinator.Conflict.QUEUE);
            blocker.start();
        }
        List<State> terminal = new ArrayList<>();
        session.addListener(event -> {
            if (event.state == State.CANCELLED) {
                assertEquals("cleanup must precede terminal events", 0, host.resources);
                terminal.add(event.state);
            }
        });
        if (phase == Phase.WAITING) host.valid = false;
        session.start();
        if (phase != Phase.QUEUED && phase != Phase.WAITING && phase != Phase.DELAY) {
            clock.advance(10);
            if (phase != Phase.PREPARATION) {
                host.preparation.run();
                if (phase != Phase.ENTRANCE) host.entrance.run();
            }
            if (phase == Phase.HIDING) session.next();
            if (phase == Phase.PAUSED) session.pause(Reason.USER);
        }
        State expected = phase == Phase.QUEUED ? State.QUEUED : phase == Phase.WAITING ? State.WAITING
                : phase == Phase.HIDING ? State.HIDING : phase == Phase.PAUSED ? State.PAUSED
                : phase == Phase.SHOWING ? State.SHOWING : State.PREPARING;
        assertEquals(expected, session.getState());
        List<Runnable> stale = new ArrayList<>(host.callbacks);
        List<TutorialHost.Actions> staleActions = new ArrayList<>(host.actions);
        switch (exit) {
            case CANCEL: session.cancel(Reason.USER); session.cancel(Reason.USER); break;
            case DISPOSE: session.dispose(); session.dispose(); break;
            case COORDINATOR: coordinator.cancel(); coordinator.cancel(); break;
        }
        assertEquals(State.CANCELLED, session.getState()); assertFalse(session.isActive());
        assertEquals(1, terminal.size()); assertEquals(0, host.resources);
        assertTrue(store.load(tutorial).completed.isEmpty());
        assertEquals(ProgressStore.Outcome.ACTIVE, store.load(tutorial).outcome);
        if (blocker != null) blocker.dispose();
        assertEquals(0, coordinator.pendingCount());

        // A later owner must remain unchanged even if a host delivers every old callback twice.
        TutorialCoordinator replacementCoordinator = exit == Exit.COORDINATOR ? new TutorialCoordinator() : coordinator;
        Host replacementHost = new Host(); replacementHost.immediate = true;
        TutorialSession replacement = new TutorialSession(tutorial, replacementHost, clock, store,
                replacementCoordinator, TutorialCoordinator.Conflict.QUEUE);
        replacement.start(); clock.advance(10); assertEquals(State.SHOWING, replacement.getState());
        for (Runnable callback : stale) { callback.run(); callback.run(); }
        for (TutorialHost.Actions action : staleActions) {
            action.next(); action.previous(); action.skipStep(); action.skipTour(); action.close(); action.actionCompleted();
        }
        clock.advance(20000);
        assertEquals(State.SHOWING, replacement.getState()); assertEquals("first", replacement.step().id);
        assertEquals(1, replacementHost.displays); assertTrue(store.load(tutorial).completed.isEmpty());
        replacement.dispose(); session.dispose(); replacementCoordinator.cancel();
        assertEquals(0, replacementHost.resources);
    }
}
