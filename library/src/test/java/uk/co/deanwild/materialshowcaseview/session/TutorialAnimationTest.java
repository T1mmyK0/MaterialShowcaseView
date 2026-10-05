package uk.co.deanwild.materialshowcaseview.session;

import android.animation.*;
import android.app.Activity;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.FrameLayout;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 30}, qualifiers = "w400dp-h640dp-mdpi")
public class TutorialAnimationTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private View target;
    private AndroidTutorialHost host;
    private TutorialOverlay overlay;
    private TutorialSession session;
    private final TutorialCoordinator coordinator = new TutorialCoordinator();
    private int shown, hidden;
    private final TutorialHost.Actions actions = new TutorialHost.Actions() {
        public void next() { } public void previous() { } public void skipStep() { }
        public void skipTour() { } public void close() { } public void actionCompleted() { }
    };
    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible(); activity = controller.get();
        FrameLayout outer = new FrameLayout(activity); root = new FrameLayout(activity);
        FrameLayout.LayoutParams rootParams = new FrameLayout.LayoutParams(320, 500, Gravity.TOP | Gravity.LEFT);
        rootParams.leftMargin = 30; rootParams.topMargin = 40; outer.addView(root, rootParams);
        target = new View(activity);
        FrameLayout.LayoutParams targetParams = new FrameLayout.LayoutParams(48, 48, Gravity.TOP | Gravity.LEFT);
        targetParams.leftMargin = 250; targetParams.topMargin = 220; root.addView(target, targetParams);
        activity.setContentView(outer); layout(); controller.windowFocusChanged(true);
        Object info = ReflectionHelpers.getField(activity.getWindow().getDecorView(), "mAttachInfo");
        ReflectionHelpers.setField(info, "mWindowVisibility", View.VISIBLE);
        host = new AndroidTutorialHost(root, id -> target); host.setResumed(true);
        // Advance animation frames only when the test advances the main looper.
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
    }
    @After public void cleanup() {
        if (session != null) session.dispose();
        coordinator.cancel(); host.cancel(); controller.pause().stop().destroy();
    }
    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 400, 640);
    }
    private void idle(long millis) {
        while (millis > 0) {
            long frame = Math.min(16, millis);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(frame));
            millis -= frame;
        }
    }
    private TutorialTheme circular() {
        TutorialTheme theme = new TutorialTheme();
        theme.animationStyle = TutorialTheme.AnimationStyle.CIRCULAR_REVEAL;
        theme.animationMillis = 1000; return theme;
    }
    private void show(TutorialTheme theme, boolean withTarget) {
        host.setTheme(theme);
        Step.Builder step = Step.builder("first").content(null, "An explanation");
        if (withTarget) step.target("target");
        host.show(step.build(), actions, () -> shown++);
        overlay = (TutorialOverlay) root.getChildAt(root.getChildCount() - 1);
        layout(); idle(20); layout(); assertEquals(1, shown);
    }
    private Animator animator() { return ReflectionHelpers.getField(overlay, "animator"); }
    private ValueAnimator radius() { return (ValueAnimator) animator(); }

    @Test public void fadeRemainsDefaultAndUsesConfiguredDurationForBothTransitions() {
        TutorialTheme theme = new TutorialTheme();
        assertEquals(TutorialTheme.AnimationStyle.FADE, theme.animationStyle);
        assertEquals(180, theme.animationMillis); theme.animationMillis = 1000;
        show(theme, true);
        assertTrue(animator() instanceof ObjectAnimator); assertEquals(1000, animator().getDuration());
        idle(1100); assertEquals(1f, overlay.getAlpha(), 0f);
        overlay.hide(() -> hidden++);
        assertTrue(animator() instanceof ObjectAnimator); assertEquals(1000, animator().getDuration());
        idle(400); assertEquals(0, hidden);
        idle(700); assertEquals(1, hidden); assertEquals(0f, overlay.getAlpha(), 0f);
    }
    @Test public void circularRevealUsesLocalTargetCenterAndReachesEveryOverlayCorner() {
        TutorialTheme theme = circular(); show(theme, true);
        assertEquals(ValueAnimator.class, animator().getClass()); assertEquals(1000, animator().getDuration());
        assertEquals(1f, overlay.getAlpha(), 0f);
        Rect visible = new Rect(); assertTrue(TargetGeometry.visibleOnScreen(target, visible));
        int[] origin = new int[2]; overlay.getLocationOnScreen(origin);
        assertTrue(origin[0] > 0 && origin[1] > 0); visible.offset(-origin[0], -origin[1]);
        assertEquals(visible.centerX(), (int) ReflectionHelpers.getField(overlay, "revealX"));
        assertEquals(visible.centerY(), (int) ReflectionHelpers.getField(overlay, "revealY"));
        ValueAnimator entranceRadius = radius();
        // The host copies options into the presentation; later caller changes cannot alter its exit.
        theme.animationStyle = TutorialTheme.AnimationStyle.FADE; theme.animationMillis = 5;
        idle(1100);
        float fullRadius = (Float) entranceRadius.getAnimatedValue();
        for (int x : new int[]{0, overlay.getWidth()}) for (int y : new int[]{0, overlay.getHeight()})
            assertTrue(fullRadius + .001 >= Math.hypot(x - visible.centerX(), y - visible.centerY()));
        target.setTranslationX(-40); target.setTranslationY(25);
        overlay.hide(() -> hidden++);
        assertEquals(ValueAnimator.class, animator().getClass()); assertEquals(1000, animator().getDuration());
        assertEquals(visible.centerX() - 40, (int) ReflectionHelpers.getField(overlay, "revealX"));
        assertEquals(visible.centerY() + 25, (int) ReflectionHelpers.getField(overlay, "revealY"));
        idle(400); assertEquals(0, hidden);
        idle(700); assertEquals(1, hidden); assertEquals(0f, overlay.getAlpha(), 0f);
    }
    @Test public void earlyExitReversesFromCurrentRadiusAndCancelsEntrance() {
        show(circular(), true); idle(200);
        Animator entrance = animator(); float partialRadius = (Float) radius().getAnimatedValue();
        assertTrue(partialRadius > 0);
        overlay.hide(() -> hidden++);
        assertFalse(entrance.isStarted());
        ValueAnimator exitRadius = radius();
        idle(20);
        assertTrue("Early exit must not flash the fully revealed overlay", (Float) exitRadius.getAnimatedValue() <= partialRadius);
        idle(1100); assertEquals(1, hidden); assertEquals(0f, overlay.getAlpha(), 0f);
        assertEquals(0f, (Float) exitRadius.getAnimatedValue(), 0f);
    }
    @Test @Config(sdk = 30) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void renderedCircleReversesWithoutFlashingOrAcceptingStaleFrames() throws Exception {
        root.setBackgroundColor(Color.WHITE); target.setBackgroundColor(Color.GREEN);
        show(circular(), true);
        ValueAnimator entrance = radius(); entrance.setCurrentPlayTime(400);
        float fullRadius = (float) Math.hypot(274, 256);
        float partialRadius = fullRadius * entrance.getInterpolator().getInterpolation(.4f);
        Bitmap entering = render("entrance");
        try {
            assertCircle(entering, partialRadius);
            overlay.hide(() -> hidden++);
            Bitmap reversing = render("interrupted-exit");
            try { assertTrue("Reversal must start with exactly the visible circle", entering.sameAs(reversing)); }
            finally { reversing.recycle(); }
            // A late update from a cancelled entrance must not expand the replacement circle.
            entrance.setCurrentPlayTime(1000);
            Bitmap stale = render("stale-frame");
            try { assertTrue("Cancelled entrance must not alter rendered pixels", entering.sameAs(stale)); }
            finally { stale.recycle(); }
            ValueAnimator exit = radius(); exit.setCurrentPlayTime(300);
            Bitmap exiting = render("shrinking-exit");
            try { assertCircle(exiting, partialRadius * (1 - exit.getInterpolator().getInterpolation(.3f))); }
            finally { exiting.recycle(); }
            idle(1100); assertEquals(1, hidden);
            Bitmap finished = render("exit-finished");
            try { assertEquals(Color.WHITE, finished.getPixel(20, 244)); }
            finally { finished.recycle(); }
        } finally { entering.recycle(); }
    }
    @Test @Config(sdk = 30) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void losingTargetDuringEntranceFadesOnlyTheVisibleCircle() throws Exception {
        root.setBackgroundColor(Color.WHITE); target.setBackgroundColor(Color.GREEN);
        show(circular(), true); radius().setCurrentPlayTime(400);
        target.setVisibility(View.INVISIBLE);
        Bitmap before = render("target-lost-before-exit");
        try {
            overlay.hide(() -> hidden++);
            assertTrue(animator() instanceof ObjectAnimator);
            Bitmap after = render("target-lost-fade-start");
            try { assertTrue("Fade fallback must not reveal previously clipped content", before.sameAs(after)); }
            finally { after.recycle(); }
            radius().setCurrentPlayTime(300);
            Bitmap fading = render("target-lost-fading");
            try {
                assertEquals(Color.WHITE, fading.getPixel(0, 0));
                assertNotEquals(before.getPixel(200, 244), fading.getPixel(200, 244));
            } finally { fading.recycle(); }
            idle(1100); assertEquals(1, hidden);
        } finally { before.recycle(); }
    }
    @Test @Config(sdk = 30) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void renderedEntranceAndExitCoverTheOverlayAndRestoreTheCanvasClip() throws Exception {
        root.setBackgroundColor(Color.WHITE); target.setBackgroundColor(Color.GREEN);
        show(circular(), true); radius().setCurrentPlayTime(0);
        Bitmap first = render("entrance-start");
        try { assertEquals(Color.WHITE, first.getPixel(200, 244)); }
        finally { first.recycle(); }
        idle(1100);
        Bitmap full = render("entrance-finished");
        try {
            for (int x : new int[]{0, root.getWidth() - 1}) for (int y : new int[]{0, root.getHeight() - 1})
                assertNotEquals("Completed reveal must cover all corners", Color.WHITE, full.getPixel(x, y));
            assertEquals(Color.GREEN, full.getPixel(274, 244));
        } finally { full.recycle(); }
        overlay.hide(() -> hidden++); radius().setCurrentPlayTime(600);
        Bitmap bitmap = Bitmap.createBitmap(320, 500, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap); Rect before = canvas.getClipBounds();
            overlay.draw(canvas); assertEquals(before, canvas.getClipBounds());
            canvas.drawColor(Color.MAGENTA);
            assertEquals(Color.MAGENTA, bitmap.getPixel(0, 0));
        } finally { bitmap.recycle(); }
        idle(1100); assertEquals(1, hidden);
    }
    private void assertCircle(Bitmap bitmap, float radius) {
        // Check the actual composited mask and controls against the expected animation geometry.
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) {
            if (Math.hypot(x - 274, y - 244) > radius + 2)
                assertEquals("Reveal leaked at " + x + "," + y, Color.WHITE, bitmap.getPixel(x, y));
        }
        assertNotEquals("The visible circle must include the scrim", Color.WHITE,
                bitmap.getPixel(Math.round(274 - radius * .8f), 244));
        assertEquals("The target remains visible through the reveal", Color.GREEN, bitmap.getPixel(274, 244));
    }
    private Bitmap render(String name) throws Exception {
        File directory = new File("build/reports/tutorial-animation");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            root.draw(new Canvas(bitmap)); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        return bitmap;
    }
    @Test public void exitImmediatelyAfterEntranceStartsDoesNotRequireAnAnimationFrame() {
        TutorialTheme theme = circular();
        overlay = new TutorialOverlay(root, () -> target, Step.builder("early").build(), actions, theme);
        overlay.attach(() -> { shown++; overlay.hide(() -> hidden++); });
        layout(); idle(1100);
        assertEquals(1, shown); assertEquals(1, hidden); assertEquals(0f, overlay.getAlpha(), 0f);
        overlay.cancel();
    }
    @Test public void cancellationDuringEitherTransitionCannotCompleteOrRestoreOverlay() {
        show(circular(), true); idle(100);
        Animator entrance = animator(); overlay.cancel(); idle(1100);
        assertFalse(entrance.isStarted()); assertNull(overlay.getParent()); assertEquals(View.GONE, overlay.getVisibility());
        shown = 0; show(circular(), true); idle(1100);
        overlay.hide(() -> hidden++); Animator exit = animator(); idle(100);
        overlay.cancel(); idle(1100);
        assertFalse(exit.isStarted()); assertEquals(0, hidden); assertNull(overlay.getParent());
    }
    @Test public void exitCancellationHandleStopsCompletionAndCannotCancelAReplacementExit() {
        show(circular(), true); idle(1100);
        Cancellation first = overlay.hide(() -> hidden += 10);
        first.cancel(); idle(1100); assertEquals(0, hidden);
        overlay.hide(() -> hidden++); first.cancel(); idle(1100);
        assertEquals(1, hidden);
    }
    @Test public void reducedMotionAndNonpositiveDurationsFinishWithoutCreatingAnimators() {
        for (long duration : new long[]{0, -1, 1000}) {
            TutorialTheme theme = circular(); theme.animationMillis = duration; theme.reducedMotion = duration > 0;
            shown = 0; show(theme, true);
            assertNull(animator()); assertEquals(1f, overlay.getAlpha(), 0f);
            int before = hidden; overlay.hide(() -> hidden++);
            assertEquals(before + 1, hidden); assertEquals(0f, overlay.getAlpha(), 0f);
            overlay.cancel(); idle(1100); assertEquals(before + 1, hidden);
        }
    }
    @Test @Config(sdk = {28, 30}) public void disabledSystemAnimationsFinishSynchronously() {
        float previousScale = ReflectionHelpers.getStaticField(ValueAnimator.class, "sDurationScale");
        ReflectionHelpers.setStaticField(ValueAnimator.class, "sDurationScale", 0f);
        try {
            assertFalse(ValueAnimator.areAnimatorsEnabled()); show(circular(), true);
            assertNull(animator()); assertEquals(1f, overlay.getAlpha(), 0f);
            overlay.hide(() -> hidden++); assertEquals(1, hidden); assertEquals(0f, overlay.getAlpha(), 0f);
        } finally { ReflectionHelpers.setStaticField(ValueAnimator.class, "sDurationScale", previousScale); }
    }
    @Test public void missingVisibleTargetFallsBackToFade() {
        show(circular(), false); assertTrue(animator() instanceof ObjectAnimator);
        idle(1100); overlay.hide(() -> hidden++); assertTrue(animator() instanceof ObjectAnimator);
        idle(1100); assertEquals(1, hidden); overlay.cancel();
        shown = 0; show(circular(), true); idle(1100);
        target.setVisibility(View.INVISIBLE);
        overlay.hide(() -> hidden++); assertTrue(animator() instanceof ObjectAnimator);
        idle(1100); assertEquals(2, hidden);
    }
    @Test public void exitBeforePostedEntranceCannotRevealOrAnnounceAgain() {
        overlay = new TutorialOverlay(root, () -> target, Step.builder("early").build(), actions, circular());
        overlay.attach(() -> shown++); layout(); overlay.hide(() -> hidden++); idle(1100);
        assertEquals(0, shown); assertEquals(1, hidden); assertEquals(0f, overlay.getAlpha(), 0f);
        overlay.cancel();
    }
    @Test public void sessionCommitsOnlyAfterCircularExitAndDisposalCancelsPendingCommit() {
        host.setTheme(circular());
        Tutorial tutorial = new Tutorial("animations", 1,
                Step.builder("first").target("target").content(null, "An explanation").build(),
                Step.builder("second").target("target").content(null, "Next explanation").build());
        MemoryProgressStore store = new MemoryProgressStore();
        session = new TutorialSession(tutorial, host, new MainThreadScheduler(), store, coordinator, TutorialCoordinator.Conflict.QUEUE);
        session.start();
        for (int i = 0; i < 6; i++) { layout(); root.getViewTreeObserver().dispatchOnPreDraw(); idle(20); }
        assertEquals(TutorialSession.State.SHOWING, session.getState());
        session.next(); assertEquals(TutorialSession.State.HIDING, session.getState());
        assertTrue(session.getProgress().completed.isEmpty()); idle(400);
        assertTrue(session.getProgress().completed.isEmpty()); idle(700);
        assertTrue(session.getProgress().completed.contains("first"));
        for (int i = 0; i < 6; i++) { layout(); root.getViewTreeObserver().dispatchOnPreDraw(); idle(20); }
        assertEquals("second", session.step().id);
        session.next(); assertEquals(TutorialSession.State.HIDING, session.getState());
        session.dispose(); idle(1100);
        assertTrue(store.load(tutorial).completed.contains("first"));
        assertFalse(store.load(tutorial).completed.contains("second"));
    }
}
