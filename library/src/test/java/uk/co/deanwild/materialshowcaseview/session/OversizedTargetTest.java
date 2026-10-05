package uk.co.deanwild.materialshowcaseview.session;

import android.app.Activity;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = {24, 28, 30}, qualifiers = "w400dp-h600dp-mdpi")
public class OversizedTargetTest {
    ActivityController<Activity> controller;
    Activity activity;
    FrameLayout root;
    Button target;
    AndroidTutorialHost host;
    TutorialSession session;
    TutorialCoordinator coordinator = new TutorialCoordinator();
    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible(); activity = controller.get();
        root = new FrameLayout(activity); activity.setContentView(root);
        target = new Button(activity); target.setText("Long prompt");
        layout(); controller.windowFocusChanged(true);
        Object info = ReflectionHelpers.getField(activity.getWindow().getDecorView(), "mAttachInfo");
        ReflectionHelpers.setField(info, "mWindowVisibility", View.VISIBLE);
    }
    @After public void cleanup() {
        if (session != null) session.dispose();
        if (host != null) host.cancel();
        coordinator.cancel(); controller.pause().stop().destroy();
    }
    void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 400, 600);
    }
    void settle() {
        for (int i = 0; i < 12; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
            layout(); root.getViewTreeObserver().dispatchOnPreDraw();
        }
    }
    TutorialTheme theme() { TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; return theme; }
    TutorialOverlay overlay() {
        for (int i = 0; i < root.getChildCount(); i++)
            if (root.getChildAt(i) instanceof TutorialOverlay) return (TutorialOverlay) root.getChildAt(i);
        return null;
    }
    TutorialHost.Actions actions() {
        return new TutorialHost.Actions() {
            public void next() { } public void previous() { } public void skipStep() { }
            public void skipTour() { } public void close() { } public void actionCompleted() { }
            public void activateTarget(Runnable activate) { activate.run(); }
        };
    }
    @Test public void paddedRevealOfOversizedTargetReachesShowingAndCanAdvance() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(12, 12, 12, 12); scroll.addView(list);
        list.addView(new View(activity), new LinearLayout.LayoutParams(-1, 700));
        list.addView(target, new LinearLayout.LayoutParams(-1, 1800));
        list.addView(new View(activity), new LinearLayout.LayoutParams(-1, 700));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1)); layout();
        host = new AndroidTutorialHost(root, id -> target); host.setResumed(true); host.setTheme(theme());
        host.setAlignment(AndroidTutorialHost.Alignment.NEAREST, 16);
        session = new TutorialSession(new Tutorial("large", 1,
                Step.builder("prompt").target("prompt").content("AI prompt", "Describe how the AI should answer.").build(),
                Step.builder("next").content("Next step", "Ready").build()),
                host, new MainThreadScheduler(), null, coordinator, TutorialCoordinator.Conflict.QUEUE);
        session.start(); settle();
        assertTrue(scroll.getScrollY() > 0);
        assertEquals(host.diagnostic(), TutorialSession.State.SHOWING, session.getState());
        assertNotNull(overlay());
        session.next(); settle();
        assertEquals("next", session.step().id); assertEquals(TutorialSession.State.SHOWING, session.getState());
    }
    @Test public void oversizedVisibilityAllowsMarginsButRejectsSliversAndClippedOrdinaryTargets() {
        root.addView(target, new FrameLayout.LayoutParams(800, 1800)); layout();
        Step step = Step.builder("target").build();
        target.setTranslationX(16); target.setTranslationY(16);
        assertEquals(TargetValidator.Reason.READY, TargetValidator.diagnose(target, root, step, true));
        target.setTranslationY(root.getHeight() - 20);
        assertEquals(TargetValidator.Reason.CLIPPED, TargetValidator.diagnose(target, root, step, true));
        target.setTranslationY(16); target.setTranslationX(root.getWidth() - 20);
        assertEquals(TargetValidator.Reason.CLIPPED, TargetValidator.diagnose(target, root, step, true));
        target.setTranslationX(0); target.setTranslationY(-20);
        target.setLayoutParams(new FrameLayout.LayoutParams(100, 80)); layout();
        assertEquals(TargetValidator.Reason.CLIPPED, TargetValidator.diagnose(target, root, step, true));
        target.setVisibility(View.INVISIBLE);
        assertEquals(TargetValidator.Reason.HIDDEN, TargetValidator.diagnose(target, root, step, true));
    }
    @Test public void oversizedHoleIsSuppressedAndReturnsWhenTargetShrinks() {
        root.addView(target, new FrameLayout.LayoutParams(-1, 1800)); layout();
        TutorialOverlay overlay = new TutorialOverlay(root, () -> target,
                Step.builder("large").content("AI prompt", "Describe how the AI should answer.").build(), actions(), theme());
        try {
            overlay.setProgress(1, 4); overlay.attach(() -> { }); settle(); overlay.reposition(); layout();
            Path mask = ReflectionHelpers.getField(overlay, "mask");
            assertTrue("An oversized hole would remove contrast behind the tutorial", mask.isEmpty());
            RectF hole = ReflectionHelpers.getField(overlay, "hole"); assertTrue(hole.isEmpty());
            ScrollView panel = (ScrollView) overlay.getChildAt(0);
            Rect viewport = new Rect(), panelBounds = new Rect();
            assertTrue(TargetGeometry.usableOnScreen(root, viewport)); TargetGeometry.boundsOnScreen(panel, panelBounds);
            assertTrue(viewport.contains(panelBounds));
            assertTrue("Short tutorial content should fit without scrolling", panel.getChildAt(0).getHeight() <= panel.getHeight());
            target.setLayoutParams(new FrameLayout.LayoutParams(120, 60)); layout(); overlay.reposition(); layout();
            assertFalse("The normal highlight must recover after resizing", mask.isEmpty());
            assertFalse(hole.isEmpty()); assertTrue(panel.getTop() >= hole.bottom);
        } finally { overlay.cancel(); }
    }
    @Test public void targetActionRemainsAvailableWhenTheHoleIsSuppressed() {
        root.addView(target, new FrameLayout.LayoutParams(-1, 1800)); layout();
        int[] clicks = {0}; target.setOnClickListener(v -> clicks[0]++);
        TutorialOverlay overlay = new TutorialOverlay(root, () -> target,
                Step.builder("large-action").interaction(Step.Interaction.TARGET_ACTION).content("Title", "Body").build(), actions(), theme());
        try {
            overlay.attach(() -> { }); settle();
            Path mask = ReflectionHelpers.getField(overlay, "mask"); assertTrue(mask.isEmpty());
            MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 20, 20, 0);
            MotionEvent up = MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, 20, 20, 0);
            try { overlay.dispatchTouchEvent(down); overlay.dispatchTouchEvent(up); }
            finally { down.recycle(); up.recycle(); }
            assertEquals("The scrim must not activate an unhighlighted target", 0, clicks[0]);
            Button activate = findButton(overlay, activity.getString(uk.co.deanwild.materialshowcaseview.R.string.showcase_target_action));
            assertNotNull(activate); activate.performClick(); assertEquals(1, clicks[0]);
        } finally { overlay.cancel(); }
    }
    Button findButton(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = findButton(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
}
