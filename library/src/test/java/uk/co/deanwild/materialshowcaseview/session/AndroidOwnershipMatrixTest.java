package uk.co.deanwild.materialshowcaseview.session;

import android.app.Activity;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static uk.co.deanwild.materialshowcaseview.session.TutorialSession.*;

/** L3/L4: Android removal and custom content failures must release modal state. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = {28, 30})
public class AndroidOwnershipMatrixTest {
    ActivityController<Activity> controller;
    Activity activity; FrameLayout root; Button target;
    TutorialCoordinator coordinator; AndroidTutorialHost host; TutorialSession session;
    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible(); activity = controller.get();
        root = new FrameLayout(activity); target = new Button(activity); target.setText("Target");
        target.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.addView(target, new FrameLayout.LayoutParams(160, 80)); activity.setContentView(root); layout();
        controller.windowFocusChanged(true);
        Object info = org.robolectric.util.ReflectionHelpers.getField(activity.getWindow().getDecorView(), "mAttachInfo");
        org.robolectric.util.ReflectionHelpers.setField(info, "mWindowVisibility", View.VISIBLE);
        coordinator = new TutorialCoordinator(); host = new AndroidTutorialHost(root, id -> target); host.setResumed(true);
    }
    void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 400, 600);
    }
    void idle() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); }
    void settle() { for (int i = 0; i < 6; i++) { idle(); layout(); root.getViewTreeObserver().dispatchOnPreDraw(); } }
    void start(TutorialTheme theme) {
        host.setTheme(theme);
        session = new TutorialSession(new Tutorial("ownership", 1, Step.builder("first").target("target").build()),
                host, new MainThreadScheduler(), null, coordinator, TutorialCoordinator.Conflict.QUEUE);
        session.start();
    }
    TutorialOverlay overlay() {
        for (int i = 0; i < root.getChildCount(); i++) if (root.getChildAt(i) instanceof TutorialOverlay) return (TutorialOverlay) root.getChildAt(i);
        return null;
    }
    @After public void cleanup() { if (session != null) session.dispose(); host.cancel(); coordinator.cancel(); controller.pause().stop().destroy(); }

    @Test public void externalRemovalDuringEntranceReleasesSession() { externalRemoval(State.PREPARING); }
    @Test public void externalRemovalWhileShowingReleasesSession() { externalRemoval(State.SHOWING); }
    @Test public void externalRemovalDuringExitReleasesSession() { externalRemoval(State.HIDING); }
    private void externalRemoval(State phase) {
        TutorialTheme theme = new TutorialTheme(); theme.animationMillis = 1000;
        start(theme);
        // Run preparation, but remove the entrance before its posted reveal callback.
        idle();
        for (int i = 0; i < 6 && overlay() == null; i++) { layout(); root.getViewTreeObserver().dispatchOnPreDraw(); }
        assertNotNull(overlay());
        if (phase != State.PREPARING) { settle(); assertEquals(State.SHOWING, session.getState()); }
        if (phase == State.HIDING) session.next();
        assertEquals(phase, session.getState());
        root.removeView(overlay());
        assertEquals("External removal must terminate the presentation", State.CANCELLED, session.getState());
        assertEquals(0, coordinator.pendingCount()); assertTrue(session.getProgress().completed.isEmpty());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, target.getImportantForAccessibility());
        settle(); assertNull(overlay());
    }

    @Test public void throwingCustomContentDetachStillRestoresBackground() {
        boolean[] broken = {false};
        TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true;
        theme.contentFactory = (context, step) -> new TextView(context) {
            @Override protected void onDetachedFromWindow() {
                super.onDetachedFromWindow(); if (broken[0]) throw new IllegalStateException("custom detach");
            }
        };
        start(theme); settle(); assertEquals(State.SHOWING, session.getState());
        List<RuntimeException> errors = new ArrayList<>(); session.setErrorHandler(errors::add);
        broken[0] = true;
        try {
            session.cancel(Reason.USER);
            assertEquals(1, errors.size()); assertEquals("custom detach", errors.get(0).getMessage());
            assertEquals(0, coordinator.pendingCount());
            assertEquals("A throwing child cannot leave background accessibility disabled",
                    View.IMPORTANT_FOR_ACCESSIBILITY_YES, target.getImportantForAccessibility());
        } finally { broken[0] = false; }
    }
}
