package uk.co.deanwild.materialshowcaseview.session;

import org.junit.Test;
import static org.junit.Assert.*;
import static uk.co.deanwild.materialshowcaseview.session.TutorialSession.State;

public class TargetTapSessionTest {
    @Test public void targetTapRequiresATargetId() {
        for (String id : new String[]{null, ""}) {
            try { Step.builder("tap").target(id).interaction(Step.Interaction.TARGET_TAP).build(); fail("Missing target accepted"); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("target")); }
        }
    }
    @Test public void targetTapIsGuardedByModeReadinessAndPresentationLifetime() {
        TutorialSessionTest.Clock clock = new TutorialSessionTest.Clock();
        SessionSafetyTest.ActionHost host = new SessionSafetyTest.ActionHost();
        TutorialCoordinator coordinator = new TutorialCoordinator();
        TutorialSession session = new TutorialSession(new Tutorial("tap", 1,
                Step.builder("a").target("a").interaction(Step.Interaction.TARGET_TAP).build(), Step.builder("b").build()),
                host, clock, null, coordinator, TutorialCoordinator.Conflict.QUEUE);
        try {
            session.start(); clock.advance(0); TutorialHost.Actions old = host.actions.get(0);
            old.next(); old.skipStep(); old.actionCompleted(); session.completionHandle().run();
            old.targetTapped(() -> false); assertEquals("a", session.step().id);
            host.ready = false; old.targetTapped(() -> { fail("Readiness gate bypassed"); return true; });
            assertEquals(State.PAUSED, session.getState());
            host.ready = true; session.resume(); clock.advance(0);
            old.targetTapped(() -> { fail("Old presentation evaluated"); return true; });
            host.actions.get(1).targetTapped(() -> true); clock.advance(0);
            assertEquals("b", session.step().id);
            old.targetTapped(() -> true);
            host.actions.get(2).targetTapped(() -> { fail("Wrong mode evaluated"); return true; });
            assertEquals(State.SHOWING, session.getState());
        } finally { session.dispose(); coordinator.cancel(); }
    }
}
