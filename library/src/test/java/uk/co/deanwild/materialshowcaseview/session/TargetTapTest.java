package uk.co.deanwild.materialshowcaseview.session;

import android.app.Activity;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import static org.junit.Assert.*;
import static uk.co.deanwild.materialshowcaseview.session.TutorialSession.State;

@RunWith(RobolectricTestRunner.class) @Config(sdk = {24, 28, 30}, qualifiers = "w400dp-h600dp-mdpi")
public class TargetTapTest {
    ActivityController<Activity> controller;
    Activity activity;
    FrameLayout root;
    TextView target, extra;
    AndroidTutorialHost host;
    TutorialSession session;
    TutorialCoordinator coordinator = new TutorialCoordinator();
    int clicks;
    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible(); activity = controller.get();
        root = new FrameLayout(activity); activity.setContentView(root);
        target = new TextView(activity); target.setText("Primary target");
        target.setOnClickListener(v -> clicks++);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(160, 80); lp.leftMargin = 30; lp.topMargin = 50;
        root.addView(target, lp);
        extra = new TextView(activity); extra.setText("Informational highlight");
        lp = new FrameLayout.LayoutParams(120, 60); lp.leftMargin = 240; lp.topMargin = 50; root.addView(extra, lp);
        layout(); controller.windowFocusChanged(true);
        Object info = ReflectionHelpers.getField(activity.getWindow().getDecorView(), "mAttachInfo");
        ReflectionHelpers.setField(info, "mWindowVisibility", View.VISIBLE);
        host = new AndroidTutorialHost(root, id -> "extra".equals(id) ? extra : target); host.setResumed(true);
    }
    @After public void cleanup() {
        if (session != null) session.dispose();
        host.cancel(); coordinator.cancel(); controller.pause().stop().destroy();
    }
    void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 400, 600);
    }
    void settle() {
        for (int i = 0; i < 8; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
            layout(); root.getViewTreeObserver().dispatchOnPreDraw();
        }
    }
    TutorialTheme theme() { TutorialTheme t = new TutorialTheme(); t.reducedMotion = true; return t; }
    Step.Builder step() { return Step.builder("tap").target("primary").interaction(Step.Interaction.TARGET_TAP); }
    void start(Step step, TutorialTheme theme) {
        host.setTheme(theme);
        session = new TutorialSession(new Tutorial("target-tap", 1, step, Step.builder("next").build()),
                host, new MainThreadScheduler(), null, coordinator, TutorialCoordinator.Conflict.QUEUE);
        session.start(); settle(); assertEquals(host.diagnostic(), State.SHOWING, session.getState());
    }
    TutorialOverlay overlay() {
        for (int i = 0; i < root.getChildCount(); i++)
            if (root.getChildAt(i) instanceof TutorialOverlay) return (TutorialOverlay) root.getChildAt(i);
        throw new AssertionError("Missing overlay");
    }
    View control() { return ReflectionHelpers.getField(overlay(), "targetTapControl"); }
    void touch(TutorialOverlay overlay, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 10, action, x, y, 0);
        try { overlay.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    void tap(TutorialOverlay overlay, float x, float y) {
        touch(overlay, MotionEvent.ACTION_DOWN, x, y); touch(overlay, MotionEvent.ACTION_UP, x, y);
    }
    void tapControl() {
        View control = control(); assertEquals(View.VISIBLE, control.getVisibility());
        tap(overlay(), control.getLeft() + control.getWidth() / 2f, control.getTop() + control.getHeight() / 2f);
    }
    Button button(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = button(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    void assertStillOnTarget() { assertEquals(State.SHOWING, session.getState()); assertEquals("tap", session.step().id); }
    @Test public void onlyPrimaryTargetTapInvokesApplicationClickOnceThenAdvances() {
        int[] clicks = {0}; target.setOnClickListener(v -> clicks[0]++);
        TutorialTheme theme = theme(); theme.showSkipStep = true;
        start(step().highlight("extra").content("Tap target", "Continue here").build(), theme);
        assertNull(button(overlay(), "Next")); assertNull(button(overlay(), "Skip step")); assertNotNull(button(overlay(), "Skip"));
        tap(overlay(), 8, 8); tap(overlay(), 280, 80); overlay().performClick();
        session.next(); session.skipStep(); session.actionCompleted(); session.completionHandle().run(); assertStillOnTarget();
        tapControl(); settle(); assertEquals("next", session.step().id); assertEquals(State.SHOWING, session.getState());
        assertEquals(1, clicks[0]);
    }
    @Test public void noTextOrButtonsAreRequired() {
        TutorialTheme theme = theme(); theme.showSkipTour = false; theme.showProgress = false;
        start(step().build(), theme);
        assertEquals(View.GONE, overlay().getChildAt(0).getVisibility());
        assertTrue(control().isFocused()); tapControl(); settle(); assertEquals("next", session.step().id); assertEquals(1, clicks);
    }
    @Test public void incompleteGesturesAndTapsStartingOutsideTargetDoNotAdvance() {
        start(step().build(), theme()); View c = control(); TutorialOverlay overlay = overlay();
        float x = c.getLeft() + c.getWidth() / 2f, y = c.getTop() + c.getHeight() / 2f;
        touch(overlay, MotionEvent.ACTION_DOWN, x, y); touch(overlay, MotionEvent.ACTION_CANCEL, x, y); touch(overlay, MotionEvent.ACTION_UP, x, y);
        assertStillOnTarget();
        touch(overlay, MotionEvent.ACTION_DOWN, x, y); touch(overlay, MotionEvent.ACTION_MOVE, x + 100, y);
        touch(overlay, MotionEvent.ACTION_UP, x, y); assertStillOnTarget();
        touch(overlay, MotionEvent.ACTION_DOWN, x, y); touch(overlay, MotionEvent.ACTION_POINTER_DOWN, x, y);
        touch(overlay, MotionEvent.ACTION_UP, x, y); assertStillOnTarget();
        touch(overlay, MotionEvent.ACTION_DOWN, c.getLeft() - 2, y); touch(overlay, MotionEvent.ACTION_UP, c.getLeft() + 1, y);
        assertStillOnTarget(); tapControl(); settle(); assertEquals("next", session.step().id);
    }
    @Test public void movedReplacedAndHiddenTargetsRejectAnOldGesture() {
        for (int mutation = 0; mutation < 3; mutation++) {
            start(step().build(), theme()); View c = control(); TutorialOverlay overlay = overlay();
            float x = c.getLeft() + c.getWidth() / 2f, y = c.getTop() + c.getHeight() / 2f;
            touch(overlay, MotionEvent.ACTION_DOWN, x, y); TextView original = target;
            if (mutation == 0) target.setTranslationX(4);
            if (mutation == 1) { target = new TextView(activity); root.addView(target, original.getLayoutParams()); target.layout(original.getLeft(), original.getTop(), original.getRight(), original.getBottom()); }
            if (mutation == 2) target.setVisibility(View.INVISIBLE);
            touch(overlay, MotionEvent.ACTION_UP, x, y);
            assertTrue(session.getProgress().completed.isEmpty()); assertEquals(0, clicks);
            session.dispose(); session = null; host.cancel();
            if (target != original) root.removeView(target);
            target = original; target.setTranslationX(0); target.setVisibility(View.VISIBLE);
            host = new AndroidTutorialHost(root, id -> target); host.setResumed(true);
        }
    }
    @Test public void targetControlSupportsKeyboardAndAccessibilityWithoutMakingBackgroundClickable() {
        for (boolean keyboard : new boolean[]{true, false}) {
            start(step().build(), theme()); View control = control();
            AccessibilityNodeInfo node = control.createAccessibilityNodeInfo();
            assertTrue(node.isClickable()); assertEquals(Button.class.getName(), node.getClassName());
            assertFalse(overlay().isClickable());
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds); assertTrue(bounds.width() <= target.getWidth());
            if (keyboard) {
                assertTrue(control.requestFocus());
                control.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                control.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            } else assertTrue(control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
            settle(); assertEquals("next", session.step().id);
            session.dispose(); session = null; host.cancel();
            host = new AndroidTutorialHost(root, id -> target); host.setResumed(true);
        }
    }
    @Test public void oversizedTargetKeepsAnUncoveredHighlightAndScrollableContent() {
        target.setLayoutParams(new FrameLayout.LayoutParams(-1, 1800)); layout();
        String longBody = String.join("\n", java.util.Collections.nCopies(40, "Long explanation"));
        start(step().content("Long target", longBody).build(), theme());
        View control = control(), panel = overlay().getChildAt(0);
        RectF hole = ReflectionHelpers.getField(overlay(), "hole");
        assertTrue("control height=" + control.getHeight() + " lp=" + control.getLayoutParams().height + " visibility=" + control.getVisibility()
                + " hole=" + hole + " panel=" + panel.getTop() + ":" + panel.getBottom(), control.getHeight() >= 48);
        Rect controlBounds = new Rect(control.getLeft(), control.getTop(), control.getRight(), control.getBottom());
        assertFalse(Rect.intersects(controlBounds, new Rect(panel.getLeft(), panel.getTop(), panel.getRight(), panel.getBottom())));
        Path mask = ReflectionHelpers.getField(overlay(), "mask"); assertFalse(mask.isEmpty());
        assertTrue(((ScrollView) panel).getChildAt(0).getHeight() > panel.getHeight());
        tapControl(); settle(); assertEquals("next", session.step().id);
    }
    @Test public void remappedButtonsCannotBypassTargetTapButCloseCanStillExit() {
        TutorialTheme theme = theme(); theme.skipTourAction = TutorialTheme.Navigation.NEXT;
        theme.showPrevious = true; theme.previousAction = TutorialTheme.Navigation.SKIP_STEP;
        theme.showClose = true;
        start(step().build(), theme);
        assertNull(button(overlay(), "Skip")); assertNull(button(overlay(), "Previous")); assertNotNull(button(overlay(), "Close"));
        button(overlay(), "Close").performClick(); assertEquals(State.CANCELLED, session.getState());
    }
    @Test public void oldTargetControlCannotAffectTheNextStepOrAResumedPresentation() {
        start(step().build(), theme()); View stale = control(); TutorialOverlay old = overlay();
        session.pause(TutorialSession.Reason.USER); session.resume(); settle();
        stale.performClick(); assertStillOnTarget();
        tapControl(); settle(); stale.performClick(); old.performClick();
        assertEquals("next", session.step().id); assertEquals(State.SHOWING, session.getState());
        assertEquals(1, clicks);
    }
    @Test public void disabledOrNonClickableTargetCannotBeActivated() {
        start(step().build(), theme()); View control = control();
        target.setEnabled(false); control.performClick();
        assertEquals(State.WAITING, session.getState()); assertEquals(0, clicks);
        target.setEnabled(true); host.invalidate(); settle(); control = control();
        target.setClickable(false); control.performClick();
        assertEquals(State.WAITING, session.getState()); assertEquals(0, clicks);
    }
    @Test public void clickCancellationAndFailureDoNotAdvanceOrLeaveAnOverlay() {
        start(step().build(), theme());
        target.setOnClickListener(v -> { clicks++; session.cancel(TutorialSession.Reason.USER); });
        tapControl(); assertEquals(State.CANCELLED, session.getState()); assertEquals(1, clicks);
        assertTrue(session.getProgress().completed.isEmpty());
        session.start(); settle();
        java.util.List<RuntimeException> errors = new java.util.ArrayList<>(); session.setErrorHandler(errors::add);
        target.setOnClickListener(v -> { throw new IllegalStateException("click failure"); });
        tapControl(); assertEquals(State.FAILED, session.getState()); assertEquals(1, errors.size());
        assertTrue(session.getProgress().completed.isEmpty()); assertEquals(0, coordinator.pendingCount());
    }
    @Test public void clickMayRemoveItsTargetAndStillAdvance() {
        start(step().build(), theme());
        target.setOnClickListener(v -> { clicks++; root.removeView(target); });
        tapControl(); settle(); assertEquals("next", session.step().id); assertEquals(1, clicks);
    }
    @Test public void builtInWidgetActionWorksWithoutAnOnClickListener() {
        ViewGroup.LayoutParams lp = target.getLayoutParams(); root.removeView(target);
        CheckBox checkbox = new CheckBox(activity); checkbox.setText("Enable option");
        checkbox.setOnCheckedChangeListener((button, checked) -> clicks++);
        target = checkbox; root.addView(target, lp); layout();
        start(step().build(), theme()); tapControl(); settle();
        assertTrue(checkbox.isChecked()); assertEquals(1, clicks); assertEquals("next", session.step().id);
    }
    @Test public void reentrantTargetTapInvokesTheApplicationClickOnlyOnce() {
        start(step().build(), theme()); View control = control();
        target.setOnClickListener(v -> { if (++clicks == 1) control.performClick(); });
        tapControl(); settle(); assertEquals(1, clicks); assertEquals("next", session.step().id);
    }
    @Test public void reentrantTargetActionInvokesTheApplicationClickOnlyOnce() {
        start(step().interaction(Step.Interaction.TARGET_ACTION).build(), theme());
        Button action = button(overlay(), "Use highlighted control"); assertNotNull(action);
        target.setOnClickListener(v -> { if (++clicks == 1) action.performClick(); });
        action.performClick(); assertEquals(1, clicks); assertStillOnTarget();
    }
    @Test public void targetTapRespectsTheVisibleCustomHighlightShape() {
        TutorialTheme theme = theme();
        theme.highlightShape = (path, bounds) -> path.addOval(bounds, Path.Direction.CW);
        start(step().build(), theme); View control = control();
        Region primary = ReflectionHelpers.getField(overlay(), "primaryHighlight");
        RectF hole = ReflectionHelpers.getField(overlay(), "hole");
        assertFalse("region=" + primary.getBounds() + " hole=" + hole + " control=" + control.getLeft() + "," + control.getTop(),
                primary.contains(control.getLeft() + 1, control.getTop() + 1));
        tap(overlay(), control.getLeft() + 1, control.getTop() + 1);
        assertEquals(0, clicks); assertStillOnTarget();
        tapControl(); settle(); assertEquals(1, clicks); assertEquals("next", session.step().id);
    }
    @Test public void targetActionRespectsTheVisibleCustomHighlightShape() {
        TutorialTheme theme = theme();
        theme.highlightShape = (path, bounds) -> path.addOval(bounds, Path.Direction.CW);
        start(step().interaction(Step.Interaction.TARGET_ACTION).build(), theme);
        tap(overlay(), target.getLeft() + 1, target.getTop() + 1); assertEquals(0, clicks);
        tap(overlay(), target.getLeft() + target.getWidth() / 2f, target.getTop() + target.getHeight() / 2f);
        assertEquals(1, clicks); assertStillOnTarget();
    }
    @Test public void customShapeResetAndFillRulePreserveEveryHighlight() {
        TutorialTheme theme = theme();
        theme.highlightShape = (path, bounds) -> {
            path.reset(); path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(bounds, Path.Direction.CW);
            path.addCircle(bounds.centerX(), bounds.centerY(), 12, Path.Direction.CW);
        };
        start(step().highlight("extra").build(), theme);
        Path mask = ReflectionHelpers.getField(overlay(), "mask");
        Region drawn = new Region(); drawn.setPath(mask, new Region(0, 0, overlay().getWidth(), overlay().getHeight()));
        for (View view : new View[]{target, extra}) {
            int cx = view.getLeft() + view.getWidth() / 2, cy = view.getTop() + view.getHeight() / 2;
            assertTrue("A shape callback erased an earlier cutout", drawn.contains(view.getLeft() + 4, cy));
            assertFalse("The renderer lost the custom fill rule", drawn.contains(cx, cy));
            tap(overlay(), cx, cy); assertEquals(0, clicks); assertStillOnTarget();
        }
        tap(overlay(), target.getLeft() + 4, target.getTop() + target.getHeight() / 2f);
        settle(); assertEquals(1, clicks); assertEquals("next", session.step().id);
    }
}
