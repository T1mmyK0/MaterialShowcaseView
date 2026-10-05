package uk.co.deanwild.materialshowcaseview.session;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static uk.co.deanwild.materialshowcaseview.session.TutorialSession.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = {24, 28})
public class AndroidSafetyTest {
    ActivityController<Activity> controller; Activity activity; FrameLayout root; Button target;
    TutorialCoordinator coordinator; AndroidTutorialHost host; TutorialSession session;
    final List<RuntimeException> errors = new ArrayList<>();
    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible(); activity = controller.get();
        root = new FrameLayout(activity); target = new Button(activity); target.setText("Target");
        root.addView(target, new FrameLayout.LayoutParams(160, 80)); activity.setContentView(root); layout();
        controller.windowFocusChanged(true);
        Object info = org.robolectric.util.ReflectionHelpers.getField(activity.getWindow().getDecorView(), "mAttachInfo");
        org.robolectric.util.ReflectionHelpers.setField(info, "mWindowVisibility", View.VISIBLE);
        coordinator = new TutorialCoordinator(); host = new AndroidTutorialHost(activity.getWindow(), id -> target); host.setResumed(true);
    }
    void layout() { View decor = activity.getWindow().getDecorView(); decor.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(470, View.MeasureSpec.EXACTLY)); decor.layout(0, 0, 320, 470); }
    void idle() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); }
    void settle() { for (int i = 0; i < 6; i++) { idle(); layout(); root.getViewTreeObserver().dispatchOnPreDraw(); } }
    void start(Step.Interaction interaction, TutorialTheme theme) {
        host.setTheme(theme);
        session = new TutorialSession(new Tutorial("android-safety", 1, Step.builder("a").target("target").interaction(interaction).build()),
                host, new MainThreadScheduler(), null, coordinator, TutorialCoordinator.Conflict.QUEUE);
        session.setErrorHandler(error -> { errors.add(error); assertReleased(); }); session.start();
    }
    TutorialTheme instant() { TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; return theme; }
    TutorialOverlay overlay() { return findOverlay(activity.getWindow().getDecorView()); }
    TutorialOverlay findOverlay(View view) {
        if (view instanceof TutorialOverlay) return (TutorialOverlay) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TutorialOverlay result = findOverlay(((ViewGroup) view).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    void assertReleased() {
        assertEquals(0, coordinator.pendingCount()); assertNull(overlay());
        List<?> observers = org.robolectric.util.ReflectionHelpers.getField(host, "observers"); assertTrue(observers.isEmpty());
    }
    void assertFailed() { assertEquals(State.FAILED, session.getState()); assertEquals(1, errors.size()); assertReleased(); }
    @After public void cleanup() { if (session != null) session.dispose(); coordinator.cancel(); host.cancel(); controller.pause().stop().destroy(); }

    @Test public void asynchronousPreparationResolutionFailureCleansBeforeReporting() {
        boolean[] fail = {false}; Runnable[] ready = {null};
        host = new AndroidTutorialHost(activity.getWindow(), id -> { if (fail[0]) throw new IllegalStateException("resolve"); return target; }); host.setResumed(true);
        host.setPreparation((step, scope, callback) -> { ready[0] = callback; return Cancellation.NONE; });
        start(Step.Interaction.NEXT, instant()); idle(); fail[0] = true; ready[0].run(); assertFailed();
        ready[0].run(); assertEquals(1, errors.size());
    }
    @Test public void asynchronousRevealFailureCleansBeforeReporting() {
        Runnable[] ready = {null}; host.setPreparation((step, scope, callback) -> { ready[0] = callback; return Cancellation.NONE; });
        host.setRevealStrategy((view, alignment, padding) -> { throw new IllegalStateException("reveal"); });
        start(Step.Interaction.NEXT, instant()); idle(); ready[0].run(); assertFailed();
    }
    @Test public void preDrawResolverFailureReleasesVisiblePresentationAndObserver() {
        boolean[] fail = {false}; host = new AndroidTutorialHost(activity.getWindow(), id -> { if (fail[0]) throw new IllegalStateException("pre-draw"); return target; }); host.setResumed(true);
        start(Step.Interaction.NEXT, instant()); settle(); assertEquals(State.SHOWING, session.getState());
        fail[0] = true; root.getViewTreeObserver().dispatchOnPreDraw(); assertFailed();
        root.getViewTreeObserver().dispatchOnPreDraw(); assertEquals(1, errors.size());
    }
    @Test public void postedPresentationAndRepositionFailuresReleaseOverlay() {
        TutorialTheme theme = instant(); boolean[] fail = {false};
        theme.highlightShape = (path, bounds) -> { if (fail[0]) throw new IllegalStateException("position"); path.addRect(bounds, android.graphics.Path.Direction.CW); };
        start(Step.Interaction.NEXT, theme); settle(); assertEquals(State.SHOWING, session.getState());
        fail[0] = true; target.setTranslationY(4); root.getViewTreeObserver().dispatchOnPreDraw(); assertFailed();
    }
    @Test public void postedRevealFailureBeforeShownCannotLeakOverlay() {
        TutorialTheme theme = instant(); theme.highlightShape = (path, bounds) -> { throw new IllegalStateException("initial position"); };
        start(Step.Interaction.NEXT, theme); settle(); assertFailed();
    }
    @Test public void cancelledPreparationNeverInvokesLateResolver() {
        Runnable[] ready = {null}; boolean[] fail = {false};
        host = new AndroidTutorialHost(activity.getWindow(), id -> { if (fail[0]) throw new IllegalStateException("stale"); return target; }); host.setResumed(true);
        host.setPreparation((step, scope, callback) -> { ready[0] = callback; return Cancellation.NONE; });
        start(Step.Interaction.NEXT, instant()); idle(); session.cancel(Reason.USER); fail[0] = true; ready[0].run();
        assertEquals(State.CANCELLED, session.getState()); assertTrue(errors.isEmpty()); assertReleased();
    }
    @Test public void resolverCanDisposeSessionDuringValidationOrPreDraw() {
        for (boolean preDraw : new boolean[]{false, true}) {
            boolean[] dispose = {false};
            host = new AndroidTutorialHost(activity.getWindow(), id -> { if (dispose[0]) session.dispose(); return target; }); host.setResumed(true);
            start(Step.Interaction.NEXT, instant()); settle(); dispose[0] = true;
            if (preDraw) root.getViewTreeObserver().dispatchOnPreDraw(); else session.next();
            assertEquals(State.CANCELLED, session.getState()); assertTrue(errors.isEmpty()); assertReleased();
        }
    }
    @Test public void asynchronousCustomContentMeasurementFailureUsesSessionErrorHandler() {
        TutorialTheme theme = instant(); theme.contentFactory = (context, step) -> new View(context) {
            @Override protected void onMeasure(int width, int height) { throw new IllegalStateException("custom measure"); }
        };
        start(Step.Interaction.NEXT, theme); settle(); assertFailed();
    }
    @Test public void customContentDrawingFailureReleasesOverlayAndObservers() {
        boolean[] fail = {false}; TutorialTheme theme = instant();
        theme.contentFactory = (context, step) -> new TextView(context) {
            { setText("Custom content"); }
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                if (fail[0]) throw new IllegalStateException("custom draw"); super.onDraw(canvas);
            }
        };
        start(Step.Interaction.NEXT, theme); settle(); assertEquals(State.SHOWING, session.getState());
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(320, 470, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap); int saves = canvas.getSaveCount();
        try { fail[0] = true; overlay().draw(canvas); assertFailed(); assertEquals(saves, canvas.getSaveCount()); }
        finally { bitmap.recycle(); }
    }
    @Test public void customContentTouchFailureUsesSessionErrorHandler() {
        View[] content = {null}; TutorialTheme theme = instant();
        theme.contentFactory = (context, step) -> {
            TextView view = new TextView(context); view.setText("Custom touch content"); content[0] = view;
            view.setOnTouchListener((v, event) -> { throw new IllegalStateException("custom touch"); }); return view;
        };
        start(Step.Interaction.NEXT, theme); settle(); TutorialOverlay shown = overlay();
        int[] child = new int[2], parent = new int[2]; content[0].getLocationOnScreen(child); shown.getLocationOnScreen(parent);
        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, child[0] - parent[0] + 1, child[1] - parent[1] + 1, 0);
        try { shown.dispatchTouchEvent(event); assertFailed(); } finally { event.recycle(); }
    }
    @Test public void hintKeepsPanelGestureUntilUpOutsidePanel() {
        List<Integer> events = new ArrayList<>(); View[] content = {null}; TutorialTheme theme = instant();
        theme.contentFactory = (context, step) -> {
            TextView view = new TextView(context); view.setText("Custom touch content"); content[0] = view;
            view.setOnTouchListener((v, event) -> { events.add(event.getActionMasked()); return true; }); return view;
        };
        start(Step.Interaction.HINT, theme); settle(); TutorialOverlay shown = overlay();
        int[] child = new int[2], parent = new int[2]; content[0].getLocationOnScreen(child); shown.getLocationOnScreen(parent);
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, child[0] - parent[0] + 1, child[1] - parent[1] + 1, 0);
        MotionEvent up = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, -1, -1, 0);
        try { assertTrue(shown.dispatchTouchEvent(down)); assertTrue(shown.dispatchTouchEvent(up)); }
        finally { down.recycle(); up.recycle(); }
        assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), events);
    }
    void touch(TutorialOverlay overlay, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 10, action, x, y, 0); try { overlay.onTouchEvent(event); } finally { event.recycle(); }
    }
    int[] targetPoint(TutorialOverlay overlay) {
        int[] point = new int[2], origin = new int[2]; target.getLocationOnScreen(point); overlay.getLocationOnScreen(origin);
        point[0] += target.getWidth() / 2 - origin[0]; point[1] += target.getHeight() / 2 - origin[1]; return point;
    }
    @Test public void targetChangeDuringGestureRejectsActivation() {
        for (int mutation = 0; mutation < 5; mutation++) {
            int[] clicks = {0}; target.setOnClickListener(v -> clicks[0]++);
            start(Step.Interaction.TARGET_ACTION, instant()); settle(); TutorialOverlay overlay = overlay(); int[] point = targetPoint(overlay);
            touch(overlay, MotionEvent.ACTION_DOWN, point[0], point[1]);
            Button original = target;
            if (mutation == 0) target.setVisibility(View.INVISIBLE);
            if (mutation == 1) root.removeView(target);
            if (mutation == 2) target.setTranslationX(8);
            if (mutation == 3) { target = new Button(activity); target.setOnClickListener(v -> clicks[0]++); root.addView(target, new FrameLayout.LayoutParams(160, 80)); target.layout(original.getLeft(), original.getTop(), original.getRight(), original.getBottom()); }
            if (mutation == 4) target.setClickable(false);
            touch(overlay, MotionEvent.ACTION_UP, point[0], point[1]); assertEquals(0, clicks[0]); session.dispose();
            root.removeAllViews(); target = original; target.setVisibility(View.VISIBLE); target.setTranslationX(0); root.addView(target, new FrameLayout.LayoutParams(160, 80)); layout();
            host = new AndroidTutorialHost(activity.getWindow(), id -> target); host.setResumed(true);
        }
    }
    Button actionButton(View view) {
        String label = activity.getString(uk.co.deanwild.materialshowcaseview.R.string.showcase_target_action);
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { Button result = actionButton(((ViewGroup) view).getChildAt(i)); if (result != null) return result; }
        return null;
    }
    @Test public void touchAndAccessibleActivationAreBlockedDuringExit() {
        int[] clicks = {0}; target.setOnClickListener(v -> { clicks[0]++; session.actionCompleted(); });
        TutorialTheme theme = new TutorialTheme(); theme.animationMillis = 1000;
        start(Step.Interaction.TARGET_ACTION, theme); settle(); TutorialOverlay overlay = overlay(); int[] point = targetPoint(overlay);
        Button accessible = actionButton(overlay); assertNotNull(accessible);
        touch(overlay, MotionEvent.ACTION_DOWN, point[0], point[1]); touch(overlay, MotionEvent.ACTION_UP, point[0], point[1]);
        assertEquals(1, clicks[0]); assertEquals(State.HIDING, session.getState());
        touch(overlay, MotionEvent.ACTION_DOWN, point[0], point[1]); touch(overlay, MotionEvent.ACTION_UP, point[0], point[1]); accessible.performClick();
        assertEquals(1, clicks[0]);
    }
    @Test public void accessibleActivationRevalidatesVisibilityClickabilityAndEligibility() {
        int[] clicks = {0}; target.setOnClickListener(v -> clicks[0]++);
        start(Step.Interaction.TARGET_ACTION, instant()); settle(); Button button = actionButton(overlay());
        target.setEnabled(false); button.performClick(); assertEquals(0, clicks[0]); target.setEnabled(true);
        target.setClickable(false); button.performClick(); assertEquals(0, clicks[0]);
        target.setClickable(true); target.setVisibility(View.INVISIBLE); button.performClick(); assertEquals(0, clicks[0]);
        target.setVisibility(View.VISIBLE); host.invalidate(); settle();
        button = actionButton(overlay()); session.setEligible(false); button.performClick(); assertEquals(0, clicks[0]);
    }
    @Test public void accessibleActivationRejectsTargetFromAnotherWindow() {
        int[] clicks = {0}; target.setOnClickListener(v -> clicks[0]++);
        start(Step.Interaction.TARGET_ACTION, instant()); settle(); Button button = actionButton(overlay());
        android.app.Dialog dialog = new android.app.Dialog(activity); Button replacement = new Button(activity);
        replacement.setOnClickListener(v -> clicks[0]++); dialog.setContentView(replacement); dialog.show();
        target = replacement;
        try { button.performClick(); assertEquals(0, clicks[0]); assertTrue(session.getProgress().completed.isEmpty()); }
        finally { dialog.dismiss(); }
    }
    @Test public void throwingTargetClickCleansUpBeforeErrorNotification() {
        target.setOnClickListener(v -> { throw new IllegalStateException("application click"); });
        start(Step.Interaction.TARGET_ACTION, instant()); settle(); actionButton(overlay()).performClick(); assertFailed();
    }
    @Test public void scaledTargetsAndAncestorsRemainReadyWhenFullyVisible() {
        target.setPivotX(0); target.setPivotY(0); target.setScaleX(.5f); target.setScaleY(.5f);
        root.setPivotX(0); root.setPivotY(0); root.setScaleX(.5f); root.setScaleY(.5f);
        Rect bounds = new Rect(); TargetGeometry.boundsOnScreen(target, bounds);
        assertEquals(40, bounds.width()); assertEquals(20, bounds.height());
        assertEquals(TargetValidator.Reason.READY, TargetValidator.diagnose(target, activity.getWindow().getDecorView(), Step.builder("a").build(), true));
    }
}
