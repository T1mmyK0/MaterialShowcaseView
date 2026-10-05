package uk.co.deanwild.materialshowcaseview;

import android.app.Activity;
import android.graphics.Point;
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
import uk.co.deanwild.materialshowcaseview.session.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = {24, 28})
public class AndroidRegressionTest {
    ActivityController<Activity> controller;
    Activity activity; Button target; FrameLayout content;
    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible(); activity = controller.get();
        content = new FrameLayout(activity); target = new Button(activity); target.setText("Target");
        content.addView(target, new FrameLayout.LayoutParams(200,100)); activity.setContentView(content);
        layout();
    }
    void layout() { View decor=activity.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(900,View.MeasureSpec.EXACTLY));decor.layout(0,0,600,900); }
    void idle(long ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
    @After public void cleanup() { controller.pause().stop().destroy(); }
    MaterialShowcaseView view(int delay) { return new MaterialShowcaseView.Builder(activity).setTarget(target).setContentText("Content").setDismissText("Next").setDelay(delay).useFadeAnimation().setFadeDuration(0).build(); }
    @Test public void throwingShapeDrawReleasesOverlayAndSequenceWithoutProgress() {
        MaterialShowcaseView showcase = view(0);
        showcase.setShape(new uk.co.deanwild.materialshowcaseview.shape.CircleShape() {
            @Override public void draw(android.graphics.Canvas canvas, android.graphics.Paint paint, int x, int y) {
                throw new IllegalStateException("draw");
            }
        });
        MaterialShowcaseSequence sequence = new MaterialShowcaseSequence(activity, "drawing-failure");
        sequence.addSequenceItem(showcase); sequence.start(); layout(); idle(1);
        try {
            try { showcase.onDraw(new android.graphics.Canvas()); fail("Expected drawing failure"); }
            catch (IllegalStateException expected) { assertEquals("draw", expected.getMessage()); }
            assertNull("Drawing failures must release the overlay", showcase.getParent());
            assertFalse(sequence.isRunning()); assertFalse(sequence.hasFired());
        } finally { sequence.cancel(); }
    }

    @Test public void throwingTouchGeometryReleasesOverlayWithoutProgress() {
        MaterialShowcaseView showcase = new MaterialShowcaseView.Builder(activity).setTarget(target)
                .setTargetTouchable(true).singleUse("touch-geometry-failure").useFadeAnimation().setFadeDuration(0).build();
        boolean[] broken = {false};
        showcase.setTarget(new uk.co.deanwild.materialshowcaseview.target.ViewTarget(target) {
            @Override public android.graphics.Rect getBounds() {
                if (broken[0]) throw new IllegalStateException("touch geometry");
                return super.getBounds();
            }
        });
        showcase.show(activity); layout(); idle(1); broken[0] = true;
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 20, 20, 0);
        try {
            try { showcase.onTouch(showcase, down); fail("Expected geometry failure"); }
            catch (IllegalStateException expected) { assertEquals("touch geometry", expected.getMessage()); }
            assertNull("Touch failures must release the overlay", showcase.getParent());
            assertFalse(showcase.hasFired());
        } finally { down.recycle(); broken[0] = false; showcase.removeFromWindow(); }
    }

    private void settleTooltip() {
        for (int i = 0; i < 4; i++) { idle(1); layout(); content.getViewTreeObserver().dispatchOnPreDraw(); }
    }

    @Test public void shapeMayRemoveItsPresentationWhileDrawing() {
        MaterialShowcaseView showcase = view(0);
        showcase.setShape(new uk.co.deanwild.materialshowcaseview.shape.CircleShape() {
            @Override public void draw(android.graphics.Canvas canvas, android.graphics.Paint paint, int x, int y) {
                showcase.removeFromWindow();
            }
        });
        showcase.show(activity); layout(); idle(1);
        showcase.onDraw(new android.graphics.Canvas());
        assertNull(showcase.getParent());
    }

    @Test public void throwingTargetBoundsDuringPredrawReleasesOverlay() {
        MaterialShowcaseView showcase = view(0); boolean[] broken = {false};
        showcase.setTarget(new uk.co.deanwild.materialshowcaseview.target.ViewTarget(target) {
            @Override public android.graphics.Rect getBounds() {
                if (broken[0]) throw new IllegalStateException("target bounds");
                return super.getBounds();
            }
        });
        showcase.show(activity); layout(); idle(1); broken[0] = true;
        try {
            try { content.getViewTreeObserver().dispatchOnPreDraw(); fail("Expected target failure"); }
            catch (IllegalStateException expected) { assertEquals("target bounds", expected.getMessage()); }
            assertNull(showcase.getParent());
        } finally { broken[0] = false; showcase.removeFromWindow(); }
    }

    @Test public void cancelledTooltipTrackingCannotAffectReplacement() {
        ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity).text("Tip");
        tooltip.configureTarget(content, target);
        ShowcaseTooltip.TooltipView bubble = tooltip.show(0); settleTooltip();
        ViewTreeObserver.OnPreDrawListener stale = org.robolectric.util.ReflectionHelpers.getField(tooltip, "trackingLayout");
        assertNotNull(stale); tooltip.cancel();
        assertNull(org.robolectric.util.ReflectionHelpers.getField(tooltip, "trackingLayout"));
        tooltip.show(0); settleTooltip();
        try {
            Object replacement = org.robolectric.util.ReflectionHelpers.getField(tooltip, "trackingLayout");
            target.setVisibility(View.INVISIBLE); stale.onPreDraw();
            assertSame(content, bubble.getParent());
            assertSame(replacement, org.robolectric.util.ReflectionHelpers.getField(tooltip, "trackingLayout"));
            content.getViewTreeObserver().dispatchOnPreDraw(); assertNull(bubble.getParent());
        } finally { tooltip.cancel(); }
    }

    @Test public void visibleTooltipTracksTargetAndPlacementWithoutRepeatingEntrance() {
        int[] entrances = {0};
        ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity).text("Tip").align(ShowcaseTooltip.ALIGN.START)
                .animation(new ShowcaseTooltip.TooltipAnimation() {
                    public void animateEnter(View view, android.animation.Animator.AnimatorListener listener) { entrances[0]++; }
                    public void animateExit(View view, android.animation.Animator.AnimatorListener listener) { }
                });
        tooltip.configureTarget(content, target);
        ShowcaseTooltip.TooltipView bubble = tooltip.show(0);
        try {
            settleTooltip(); float x = bubble.getX(), y = bubble.getY();
            target.setTranslationX(40); target.setTranslationY(200);
            content.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(x + 40, bubble.getX(), 0f); assertEquals(y + 200, bubble.getY(), 0f);
            tooltip.position(ShowcaseTooltip.Position.TOP); settleTooltip();
            assertEquals(target.getY() - bubble.getHeight(), bubble.getY(), 0f);
            assertEquals("Geometry updates must not replay the entrance", 1, entrances[0]);
        } finally { tooltip.cancel(); }
    }

    @Test public void hiddenOrDetachedTargetReleasesVisibleTooltip() {
        ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity).text("Tip");
        tooltip.configureTarget(content, target);
        ShowcaseTooltip.TooltipView bubble = tooltip.show(0);
        try {
            settleTooltip(); assertNotNull(bubble.getParent());
            target.setVisibility(View.INVISIBLE); content.getViewTreeObserver().dispatchOnPreDraw();
            assertNull("Hidden targets must not retain their tooltip", bubble.getParent());
            target.setVisibility(View.VISIBLE); tooltip.show(0); settleTooltip();
            assertNotNull(bubble.getParent()); content.removeView(target);
            content.getViewTreeObserver().dispatchOnPreDraw();
            assertNull("Detached targets must release their tooltip", bubble.getParent());
        } finally { tooltip.cancel(); }
    }

    @Test public void tooltipStaysInsideHorizontalWindowInsetsAsTargetMoves() {
        content.removeView(target);
        content = new FrameLayout(activity) {
            @Override public void getWindowVisibleDisplayFrame(android.graphics.Rect bounds) {
                super.getWindowVisibleDisplayFrame(bounds);
                bounds.left = 40; bounds.right = 500;
            }
        };
        content.addView(target, new FrameLayout.LayoutParams(200, 100));
        activity.setContentView(content); layout(); target.setTranslationX(280);
        ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity).text("A tooltip near the navigation bar");
        tooltip.configureTarget(content, target); ShowcaseTooltip.TooltipView bubble = tooltip.show(0);
        try {
            settleTooltip();
            assertTrue("Tooltip extends behind the right navigation bar", bubble.getX() + bubble.getWidth() <= 500);
            assertTrue(bubble.getX() >= 40);
            target.setTranslationX(0); content.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue("Tooltip extends behind the left inset", bubble.getX() >= 40);
            assertTrue(bubble.getX() + bubble.getWidth() <= 500);
        } finally { tooltip.cancel(); }
    }

    @Test public void globalResetRejectsWritersFromBeforeResetEvenAfterReload() {
        SharedPreferencesProgressStore first = new SharedPreferencesProgressStore(activity);
        SharedPreferencesProgressStore second = new SharedPreferencesProgressStore(activity);
        Tutorial tutorial = new Tutorial("global-reset-stale", 1, Step.builder("one").build());
        ProgressStore.Progress before = first.load(tutorial);
        MaterialShowcaseView.resetAll(activity);
        ProgressStore.Progress after = second.load(tutorial);
        assertFalse("A stale session must not undo a global reset", first.save(tutorial.id, before.revision,
                new ProgressStore.Progress(before.revision + 1, 1, Collections.singleton("one"),
                        Collections.emptySet(), ProgressStore.Outcome.COMPLETED)));
        assertTrue(after.revision > before.revision);
        assertTrue(second.load(tutorial).completed.isEmpty());
    }

    @Test public void globalResetClearsLegacyAndModernProgressIncludingMalformedRecords() {
        SharedPreferencesProgressStore store = new SharedPreferencesProgressStore(activity);
        Tutorial tutorial = new Tutorial("global-reset-completed", 1, Step.builder("one").build());
        ProgressStore.Progress before = store.load(tutorial);
        assertTrue(store.save(tutorial.id, before.revision, new ProgressStore.Progress(before.revision + 1,
                1, Collections.singleton("one"), Collections.emptySet(), ProgressStore.Outcome.COMPLETED)));
        new PrefsManager(activity, "global-reset-legacy").setFired();
        activity.getSharedPreferences("material_showcaseview_prefs", 0).edit()
                .putInt("tutorial_v2_global-reset-malformed", 7).apply();
        MaterialShowcaseView.resetAll(activity);
        assertFalse(new PrefsManager(activity, "global-reset-legacy").hasFired());
        ProgressStore.Progress after = store.load(new Tutorial(tutorial.id, 2, Tutorial.Migration.FAIL, Step.builder("one").build()));
        assertEquals(2, after.version); assertTrue(after.completed.isEmpty());
        assertEquals(ProgressStore.Outcome.ACTIVE, after.outcome);
        assertTrue(store.load(new Tutorial("global-reset-malformed", 1, Step.builder("one").build())).completed.isEmpty());
    }
    @Test public void throwingShapeDuringPredrawReleasesOverlayAndSequence() {
        MaterialShowcaseView showcase = view(0); boolean[] fail = {false};
        showcase.setShape(new uk.co.deanwild.materialshowcaseview.shape.CircleShape() {
            @Override public void updateTarget(uk.co.deanwild.materialshowcaseview.target.Target target) {
                if (fail[0]) throw new IllegalStateException("geometry"); super.updateTarget(target);
            }
        });
        MaterialShowcaseSequence sequence = new MaterialShowcaseSequence(activity, "shape-failure");
        sequence.addSequenceItem(showcase); sequence.start(); layout(); idle(1);
        fail[0] = true; target.setTranslationX(10);
        try {
            try { content.getViewTreeObserver().dispatchOnPreDraw(); fail("Expected geometry failure"); }
            catch (IllegalStateException expected) { assertEquals("geometry", expected.getMessage()); }
            assertNull("Geometry callbacks must not strand a modal overlay", showcase.getParent());
            assertFalse(sequence.isRunning()); assertFalse(sequence.hasFired());
        } finally { fail[0] = false; sequence.cancel(); }
    }

    @Test public void removalUnregistersLayoutListenerFromAttachedWindow() {
        MaterialShowcaseView showcase = view(0);
        int[] updates = {0};
        showcase.setShape(new uk.co.deanwild.materialshowcaseview.shape.CircleShape() {
            @Override public void updateTarget(uk.co.deanwild.materialshowcaseview.target.Target target) {
                updates[0]++; super.updateTarget(target);
            }
        });
        showcase.show(activity); layout(); idle(1);
        ViewTreeObserver windowTree = content.getViewTreeObserver();
        showcase.removeFromWindow(); int before = updates[0];
        windowTree.dispatchOnGlobalLayout(); windowTree.dispatchOnPreDraw();
        assertEquals("Detached showcases must release their window observers", before, updates[0]);
    }

    @Test public void throwingAnimationCleanupStillDetachesAndAllowsReuse() {
        MaterialShowcaseView showcase = view(0);
        showcase.setAnimationFactory(new CancellableAnimationFactory() {
            public void animateInView(View v, Point p, long duration, AnimationStartListener listener) { listener.onAnimationStart(); }
            public void animateOutView(View v, Point p, long duration, AnimationEndListener listener) { listener.onAnimationEnd(); }
            public void animateTargetToPoint(MaterialShowcaseView v, Point p) { }
            public void cancel(View v) { throw new IllegalStateException("cleanup"); }
        });
        showcase.show(activity); layout(); idle(1);
        try { showcase.removeFromWindow(); fail("Expected cleanup failure"); }
        catch (IllegalStateException expected) { assertEquals("cleanup", expected.getMessage()); }
        assertNull("A failed custom cleanup must not strand the modal overlay", showcase.getParent());
        showcase.setAnimationFactory(new FadeAnimationFactory());
        assertTrue(showcase.show(activity)); layout(); idle(1); showcase.removeFromWindow();
        assertNull(showcase.getParent());
    }

    @Test public void displayedListenerRestartDoesNotDispatchOldEventToNewPresentation() {
        MaterialShowcaseView showcase = view(0);
        IAnimationFactory.AnimationStartListener[] entrance = {null};
        showcase.setAnimationFactory(new IAnimationFactory() {
            public void animateInView(View v, Point p, long duration, AnimationStartListener listener) { entrance[0] = listener; }
            public void animateOutView(View v, Point p, long duration, AnimationEndListener listener) { listener.onAnimationEnd(); }
            public void animateTargetToPoint(MaterialShowcaseView v, Point p) { }
        });
        boolean[] restart = {true}; int[] laterListener = {0};
        showcase.addShowcaseListener(listener(() -> {
            if (restart[0]) { restart[0] = false; showcase.removeFromWindow(); showcase.show(activity); }
        }));
        showcase.addShowcaseListener(listener(() -> laterListener[0]++));
        try {
            showcase.show(activity); layout(); idle(1); entrance[0].onAnimationStart();
            assertEquals("The old display event crossed into a replacement presentation", 0, laterListener[0]);
            layout(); idle(1); entrance[0].onAnimationStart(); assertEquals(1, laterListener[0]);
        } finally { showcase.removeFromWindow(); }
    }

    @Test public void legacyHighlightTracksTranslationAndScaleWithoutLayout() {
        MaterialShowcaseView showcase = new MaterialShowcaseView.Builder(activity).setTarget(target)
                .withRectangleShape().useFadeAnimation().setFadeDuration(0).build();
        try {
            showcase.show(activity); layout(); idle(1);
            target.setTranslationX(70); target.setTranslationY(90);
            target.setPivotX(0); target.setPivotY(0); target.setScaleX(0.5f);
            content.getViewTreeObserver().dispatchOnPreDraw();
            Point expected = new uk.co.deanwild.materialshowcaseview.target.ViewTarget(target).getPoint();
            assertEquals(expected.x, showcase.getShowcaseX()); assertEquals(expected.y, showcase.getShowcaseY());
            uk.co.deanwild.materialshowcaseview.shape.Shape shape = org.robolectric.util.ReflectionHelpers.getField(showcase, "mShape");
            assertEquals(100, shape.getWidth());
        } finally { showcase.removeFromWindow(); }
    }

    @Test public void throwingEntranceOrExitCancelsSequenceWithoutRecordingProgress() {
        for (boolean entranceFailure : new boolean[]{true, false}) {
            String id = "animation-failure-" + entranceFailure;
            MaterialShowcaseView showcase = view(0);
            showcase.setAnimationFactory(new IAnimationFactory() {
                public void animateInView(View v, Point p, long duration, AnimationStartListener listener) {
                    if (entranceFailure) throw new IllegalStateException("entrance");
                    listener.onAnimationStart();
                }
                public void animateOutView(View v, Point p, long duration, AnimationEndListener listener) { throw new IllegalStateException("exit"); }
                public void animateTargetToPoint(MaterialShowcaseView v, Point p) { }
            });
            MaterialShowcaseSequence sequence = new MaterialShowcaseSequence(activity, id);
            sequence.addSequenceItem(showcase); sequence.start(); layout();
            try {
                idle(1); if (!entranceFailure) showcase.hide();
                fail("Expected animation failure");
            } catch (IllegalStateException expected) { assertEquals(entranceFailure ? "entrance" : "exit", expected.getMessage()); }
            try {
                assertNull("Animation failures must release the overlay", showcase.getParent());
                assertFalse(sequence.isRunning());
                assertEquals(0, new PrefsManager(activity, id).getSequenceStatus());
            } finally { sequence.cancel(); }
        }
    }

    @Test public void duplicateEntranceCallbackEmitsDisplayOnlyOnce() {
        MaterialShowcaseView showcase = view(0);
        IAnimationFactory.AnimationStartListener[] entrance = {null}; int[] displayed = {0};
        showcase.setAnimationFactory(new IAnimationFactory() {
            public void animateInView(View v, Point p, long duration, AnimationStartListener listener) { entrance[0] = listener; }
            public void animateOutView(View v, Point p, long duration, AnimationEndListener listener) { listener.onAnimationEnd(); }
            public void animateTargetToPoint(MaterialShowcaseView v, Point p) { }
        });
        showcase.addShowcaseListener(listener(() -> displayed[0]++));
        try {
            showcase.show(activity); layout(); idle(1);
            entrance[0].onAnimationStart(); entrance[0].onAnimationStart();
            assertEquals(1, displayed[0]);
        } finally { showcase.removeFromWindow(); }
    }

    @Test public void failedDismissalCleanupCancelsSequenceAndDoesNotMarkSingleUse() {
        MaterialShowcaseView showcase = new MaterialShowcaseView.Builder(activity).setTarget(target)
                .singleUse("failed-dismissal-item").build();
        showcase.setAnimationFactory(new CancellableAnimationFactory() {
            public void animateInView(View v, Point p, long duration, AnimationStartListener listener) { listener.onAnimationStart(); }
            public void animateOutView(View v, Point p, long duration, AnimationEndListener listener) { listener.onAnimationEnd(); }
            public void animateTargetToPoint(MaterialShowcaseView v, Point p) { }
            public void cancel(View v) { throw new IllegalStateException("cleanup"); }
        });
        MaterialShowcaseSequence sequence = new MaterialShowcaseSequence(activity, "failed-dismissal-sequence");
        sequence.addSequenceItem(showcase); sequence.start(); layout(); idle(1);
        try { showcase.hide(); fail("Expected cleanup failure"); }
        catch (IllegalStateException expected) { assertEquals("cleanup", expected.getMessage()); }
        assertNull(showcase.getParent());
        assertFalse("The sequence cannot remain running after losing its overlay", sequence.isRunning());
        assertFalse("An unsuccessful dismissal must not persist completion", showcase.hasFired());
        assertEquals(0, new PrefsManager(activity, "failed-dismissal-sequence").getSequenceStatus());
    }

    @Test public void throwingTooltipAnimationCleanupStillRemovesBubble() {
        ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity).text("Tip");
        tooltip.configureTarget(content, target);
        ShowcaseTooltip.TooltipView bubble = tooltip.show(0); idle(1); layout();
        assertNotNull(bubble.getParent());
        tooltip.animation(new ShowcaseTooltip.CancellableTooltipAnimation() {
            public void animateEnter(View view, android.animation.Animator.AnimatorListener listener) { }
            public void animateExit(View view, android.animation.Animator.AnimatorListener listener) { }
            public void cancel(View view) { throw new IllegalStateException("tooltip cleanup"); }
        });
        try { tooltip.cancel(); fail("Expected cleanup failure"); }
        catch (IllegalStateException expected) { assertEquals("tooltip cleanup", expected.getMessage()); }
        assertNull("Custom animation errors cannot strand a tooltip", bubble.getParent());
    }
    @Test public void removalCancelsDelayedDisplayAndAllowsReuse() {
        MaterialShowcaseView view=view(500);int[] shown={0};view.addShowcaseListener(listener(()->shown[0]++));view.show(activity);view.removeFromWindow();idle(600);assertEquals(0,shown[0]);assertNull(view.getParent());
        view.show(activity);idle(600);assertEquals(1,shown[0]);view.removeFromWindow();
    }
    IShowcaseListener listener(Runnable displayed) { return new IShowcaseListener(){public void onShowcaseDisplayed(MaterialShowcaseView v){displayed.run();}public void onShowcaseDismissed(MaterialShowcaseView v){}}; }
    @Test public void duplicateListenersAndCorrectRemoval() {
        MaterialShowcaseView v=view(0);int[] count={0};IShowcaseListener l=listener(()->count[0]++);v.addShowcaseListener(l);v.addShowcaseListener(l);v.removeShowcaseListener(l);v.show(activity);idle(1);assertEquals(0,count[0]);v.removeFromWindow();
    }
    @Test public void nonpersistentQueriesAndNullTargetAreSafe() {
        assertFalse(view(0).hasFired());assertFalse(new MaterialShowcaseSequence(activity).hasFired());
        MaterialShowcaseView intro=new MaterialShowcaseView.Builder(activity).setTarget(null).setContentText("Intro").build();intro.show(activity);idle(1);intro.removeFromWindow();
    }
    @Test public void externalDetachDoesNotDismissOrAdvance() {
        MaterialShowcaseSequence sequence=new MaterialShowcaseSequence(activity);MaterialShowcaseView first=view(0);sequence.addSequenceItem(first).addSequenceItem(view(0));int[] dismissed={0};sequence.setOnItemDismissedListener((v,p)->dismissed[0]++);
        sequence.start();idle(1);first.removeFromWindow();assertFalse(sequence.isRunning());assertEquals(0,dismissed[0]);
    }
    @Test public void sequenceShownWaitsForPresentationAndCancelFromCallback() {
        MaterialShowcaseSequence sequence=new MaterialShowcaseSequence(activity);sequence.addSequenceItem(view(100));int[] shown={0};sequence.setOnItemShownListener((v,p)->{shown[0]++;sequence.cancel();});sequence.start();sequence.start();assertEquals(0,shown[0]);idle(101);assertEquals(1,shown[0]);assertFalse(sequence.isRunning());
    }
    @Test public void repeatedDismissalNotifiesOnce() {
        MaterialShowcaseView v=view(0);int[] count={0};v.addShowcaseListener(new IShowcaseListener(){public void onShowcaseDisplayed(MaterialShowcaseView v){}public void onShowcaseDismissed(MaterialShowcaseView v){count[0]++;}});
        v.show(activity);idle(1);v.hide();v.hide();idle(20);assertEquals(1,count[0]);
    }
    @Test public void cancelledCustomAnimationCallbackCannotResurrect() {
        MaterialShowcaseView v=view(0);IAnimationFactory.AnimationStartListener[] callback={null};
        v.setAnimationFactory(new IAnimationFactory(){public void animateInView(View target,Point point,long time,AnimationStartListener l){callback[0]=l;}public void animateOutView(View target,Point point,long time,AnimationEndListener l){l.onAnimationEnd();}public void animateTargetToPoint(MaterialShowcaseView v,Point p){}});
        int[] shown={0};v.addShowcaseListener(listener(()->shown[0]++));v.show(activity);idle(1);v.removeFromWindow();callback[0].onAnimationStart();assertEquals(0,shown[0]);assertNull(v.getParent());
    }
    @Test public void dragAndCancelledGestureDoNotDismiss() {
        MaterialShowcaseView v=new MaterialShowcaseView.Builder(activity).setTarget(target).setDismissOnTouch(true).setFadeDuration(0).useFadeAnimation().build();v.show(activity);idle(1);
        touch(v,MotionEvent.ACTION_DOWN,10,10);touch(v,MotionEvent.ACTION_MOVE,100,100);touch(v,MotionEvent.ACTION_UP,100,100);assertNotNull(v.getParent());
        touch(v,MotionEvent.ACTION_DOWN,10,10);touch(v,MotionEvent.ACTION_CANCEL,10,10);touch(v,MotionEvent.ACTION_UP,10,10);assertNotNull(v.getParent());v.removeFromWindow();
    }
    void touch(MaterialShowcaseView v,int action,float x,float y){MotionEvent e=MotionEvent.obtain(0,0,action,x,y,0);v.onTouch(v,e);e.recycle();}

    @Test public void legacyHighlightFollowsTargetAndAncestorScale() {
        content.removeView(target);
        FrameLayout parent = new FrameLayout(activity);
        content.addView(parent, new FrameLayout.LayoutParams(300, 250));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(200, 100);
        params.leftMargin = 40; params.topMargin = 20;
        parent.addView(target, params); layout();
        parent.setPivotX(0); parent.setPivotY(0); parent.setScaleX(0.5f); parent.setScaleY(0.5f);
        target.setPivotX(0); target.setPivotY(0); target.setScaleX(0.5f); target.setScaleY(0.5f);
        int[] origin = new int[2]; parent.getLocationInWindow(origin);
        android.graphics.Rect expected = new android.graphics.Rect(origin[0] + 20, origin[1] + 10,
                origin[0] + 70, origin[1] + 35);
        uk.co.deanwild.materialshowcaseview.target.ViewTarget anchor = new uk.co.deanwild.materialshowcaseview.target.ViewTarget(target);
        assertEquals(expected, anchor.getBounds());
        assertEquals(new Point(expected.centerX(), expected.centerY()), anchor.getPoint());
        MaterialShowcaseView showcase = new MaterialShowcaseView.Builder(activity).setTarget(target)
                .withRectangleShape().useFadeAnimation().setFadeDuration(0).build();
        try {
            showcase.show(activity); idle(1); layout();
            assertEquals(expected.centerX(), showcase.getShowcaseX());
            assertEquals(expected.centerY(), showcase.getShowcaseY());
        } finally { showcase.removeFromWindow(); }
    }

    @Test public void legacyTargetTapRejectsClippedPixelsAndClippingChangesDuringGesture() {
        content.removeView(target);
        FrameLayout clipping = new FrameLayout(activity);
        content.addView(clipping, new FrameLayout.LayoutParams(80, 100));
        clipping.addView(target, new FrameLayout.LayoutParams(200, 100)); layout();
        int[] clicks = {0}; target.setOnClickListener(v -> clicks[0]++);
        MaterialShowcaseView showcase = new MaterialShowcaseView.Builder(activity).setTarget(target)
                .setTargetTouchable(true).setDismissOnTargetTouch(false).useFadeAnimation().setFadeDuration(0).build();
        try {
            showcase.show(activity); idle(1); layout();
            int[] origin = new int[2], overlayOrigin = new int[2];
            target.getLocationInWindow(origin); showcase.getLocationInWindow(overlayOrigin);
            float x = origin[0] - overlayOrigin[0], y = origin[1] - overlayOrigin[1] + 30;
            touch(showcase, MotionEvent.ACTION_DOWN, x + 120, y);
            touch(showcase, MotionEvent.ACTION_UP, x + 120, y);
            assertEquals("Clipped portions of a target are not actionable", 0, clicks[0]);
            touch(showcase, MotionEvent.ACTION_DOWN, x + 60, y);
            clipping.getLayoutParams().width = 40; clipping.requestLayout(); layout();
            touch(showcase, MotionEvent.ACTION_UP, x + 60, y);
            assertEquals("Clipping must be checked again on UP", 0, clicks[0]);
            touch(showcase, MotionEvent.ACTION_DOWN, x + 20, y);
            touch(showcase, MotionEvent.ACTION_UP, x + 20, y);
            assertEquals(1, clicks[0]);
        } finally { showcase.removeFromWindow(); }
    }

    @Test public void targetTapIsSuppressedDuringExitAnimation() {
        int[] clicks = {0}; target.setOnClickListener(v -> clicks[0]++);
        MaterialShowcaseView view = new MaterialShowcaseView.Builder(activity).setTarget(target)
                .setTargetTouchable(true).build();
        view.setAnimationFactory(new IAnimationFactory() {
            public void animateInView(View v, Point p, long duration, AnimationStartListener listener) { listener.onAnimationStart(); }
            public void animateOutView(View v, Point p, long duration, AnimationEndListener listener) { }
            public void animateTargetToPoint(MaterialShowcaseView v, Point p) { }
        });
        view.show(activity); idle(1); view.hide();
        Point point = new uk.co.deanwild.materialshowcaseview.target.ViewTarget(target).getPoint();
        touch(view, MotionEvent.ACTION_DOWN, point.x, point.y);
        touch(view, MotionEvent.ACTION_UP, point.x, point.y);
        assertEquals(0, clicks[0]); view.removeFromWindow();
    }

    @Test public void targetTapRequiresSameVisibleEnabledTargetAndCompleteTapGesture() {
        int[] clicks = {0}; target.setOnClickListener(v -> clicks[0]++);
        MaterialShowcaseView view = new MaterialShowcaseView.Builder(activity).setTarget(target)
                .setTargetTouchable(true).setDismissOnTargetTouch(false).useFadeAnimation().setFadeDuration(0).build();
        view.show(activity); idle(1);
        Point point = new uk.co.deanwild.materialshowcaseview.target.ViewTarget(target).getPoint();
        // No MOVE is guaranteed before UP; distant endpoints must still reject a drag.
        touch(view, MotionEvent.ACTION_DOWN, point.x + 300, point.y);
        touch(view, MotionEvent.ACTION_UP, point.x, point.y);
        assertEquals(0, clicks[0]);
        touch(view, MotionEvent.ACTION_DOWN, point.x, point.y);
        target.setEnabled(false);
        touch(view, MotionEvent.ACTION_UP, point.x, point.y);
        assertEquals(0, clicks[0]); target.setEnabled(true);
        touch(view, MotionEvent.ACTION_DOWN, point.x, point.y);
        target.setVisibility(View.INVISIBLE);
        touch(view, MotionEvent.ACTION_UP, point.x, point.y);
        assertEquals(0, clicks[0]); target.setVisibility(View.VISIBLE);
        touch(view, MotionEvent.ACTION_DOWN, point.x, point.y);
        touch(view, MotionEvent.ACTION_UP, point.x, point.y);
        assertEquals(1, clicks[0]); view.removeFromWindow();
    }

    @Test public void fullScreenHighlightKeepsDismissControlReachable() {
        MaterialShowcaseView view = new MaterialShowcaseView.Builder(activity).setTarget(target)
                .setContentText("A highlight can be larger than the available screen.")
                .setDismissText("Done").useFadeAnimation().setFadeDuration(0).build();
        uk.co.deanwild.materialshowcaseview.shape.CircleShape shape = new uk.co.deanwild.materialshowcaseview.shape.CircleShape(1200);
        shape.setAdjustToTarget(false); view.setShape(shape);
        view.show(activity); idle(1); layout(); layout();
        View panel = view.findViewById(R.id.content_box);
        assertTrue("Oversized highlights must leave a scrollable content area", panel.getHeight() >= 48);
        assertTrue(panel.getTop() >= 0); assertTrue(panel.getBottom() <= view.getHeight());
        view.removeFromWindow();
    }
    @Test public void targetValidationCoversEnabledHiddenDetachedAndWrongWindow() {
        Step step=Step.builder("a").requireEnabled(true).build();View root=activity.getWindow().getDecorView();assertTrue(TargetValidator.valid(target,root,step,true));
        target.setEnabled(false);assertFalse(TargetValidator.valid(target,root,step,true));target.setEnabled(true);content.setVisibility(View.INVISIBLE);assertFalse(TargetValidator.valid(target,root,step,true));content.setVisibility(View.VISIBLE);content.removeView(target);assertFalse(TargetValidator.valid(target,root,step,true));
    }
    @Test public void legacyMigrationPreservesFinishedAndStableIds() {
        android.content.SharedPreferences prefs=activity.getSharedPreferences("material_showcaseview_prefs",0);prefs.edit().clear().putInt("status_sc_gpt",-1).putInt("status_sc_gemini",3).commit();
        SharedPreferencesProgressStore store=new SharedPreferencesProgressStore(activity);Step[] steps={Step.builder("prompt").build(),Step.builder("default").build(),Step.builder("tags").build(),Step.builder("key").build()};
        assertEquals(ProgressStore.Outcome.LEGACY_FINISHED,store.load(new Tutorial("sc_gpt",1,steps)).outcome);
        ProgressStore.Progress p=store.load(new Tutorial("sc_gemini",1,steps));assertEquals(3,p.completed.size());assertFalse(p.completed.contains("key"));
        Tutorial reordered=new Tutorial("sc_gemini",2,steps[3],steps[0]);assertTrue(store.load(reordered).completed.contains("prompt"));store.reset("sc_gemini");assertEquals(ProgressStore.Outcome.LEGACY_FINISHED,store.load(new Tutorial("sc_gpt",1,steps)).outcome);
    }
    @Test public void malformedLegacyIsNotCompletionAndCasRejectsOldWriter() {
        android.content.SharedPreferences prefs=activity.getSharedPreferences("material_showcaseview_prefs",0);prefs.edit().clear().putString("status_bad","oops").commit();
        SharedPreferencesProgressStore a=new SharedPreferencesProgressStore(activity), b=new SharedPreferencesProgressStore(activity);Tutorial t=new Tutorial("bad",1,Step.builder("one").build());
        ProgressStore.Progress p=a.load(t);assertTrue(p.completed.isEmpty());b.reset("bad");assertFalse(a.save("bad",p.revision,new ProgressStore.Progress(p.revision+1,1,p.completed,p.skipped,ProgressStore.Outcome.COMPLETED)));
    }
    @Test public void zeroDurationConfigIsRespected() {
        MaterialShowcaseView v=view(0);ShowcaseConfig config=new ShowcaseConfig();config.setFadeDuration(0);v.setConfig(config);v.show(activity);idle(1);v.hide();assertNull(v.getParent());
    }
    @Test public void cancellingExitCannotAdvanceLegacySequence() {
        MaterialShowcaseView v=view(0);IAnimationFactory.AnimationEndListener[] hidden={null};v.setAnimationFactory(new IAnimationFactory(){public void animateInView(View t,Point p,long d,AnimationStartListener l){l.onAnimationStart();}public void animateOutView(View t,Point p,long d,AnimationEndListener l){hidden[0]=l;}public void animateTargetToPoint(MaterialShowcaseView t,Point p){}});
        MaterialShowcaseSequence sequence=new MaterialShowcaseSequence(activity,"cancel-exit");sequence.addSequenceItem(v).addSequenceItem(view(0));sequence.start();idle(1);v.hide();v.removeFromWindow();hidden[0].onAnimationEnd();assertFalse(sequence.isRunning());assertEquals(0,new PrefsManager(activity,"cancel-exit").getSequenceStatus());
    }
    @Test public void tooltipPendingAttachmentIsCancelled() {
        ShowcaseTooltip tooltip=ShowcaseTooltip.build(activity).text("Tip");tooltip.configureTarget(content,target);tooltip.show(10);tooltip.cancel();idle(200);assertEquals(1,content.getChildCount());
    }
    @Test public void showBeforeFirstLayoutWaitsForGeometry() {
        assertStartupPresentation(false);
    }
    @Test public void cancellationBeforeFirstLayoutCannotRestartPresentation() {
        assertStartupPresentation(true);
    }
    private void assertStartupPresentation(boolean cancel) {
        ActivityController<Activity> startup = Robolectric.buildActivity(Activity.class).create();
        Activity screen = startup.get();
        Button button = new Button(screen); screen.setContentView(button);
        MaterialShowcaseView showcase = new MaterialShowcaseView.Builder(screen).setTarget(button)
                .setDismissText("Next").useFadeAnimation().setFadeDuration(0).build();
        int[] shown = {0}; showcase.addShowcaseListener(listener(() -> shown[0]++));
        assertTrue(showcase.show(screen)); idle(1); assertEquals(0, shown[0]);
        if (cancel) showcase.removeFromWindow();
        startup.start().resume().visible(); idle(20);
        screen.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw(); idle(1);
        assertEquals(cancel ? 0 : 1, shown[0]);
        if (cancel) assertNull(showcase.getParent());
        else assertEquals(View.VISIBLE, showcase.getVisibility());
        showcase.removeFromWindow(); startup.pause().stop().destroy();
    }
    @Test public void hiddenTooltipTargetCannotAttachAfterShowWasScheduled() {
        ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity).text("Tip");
        tooltip.configureTarget(content, target);
        ShowcaseTooltip.TooltipView bubble = tooltip.show(0);
        target.setVisibility(View.INVISIBLE); idle(1);
        assertNull(bubble.getParent()); tooltip.cancel();
    }
    @Test public void retargetingTooltipCancelsOldPresentationRequest() {
        ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity).text("Tip");
        tooltip.configureTarget(content, target);
        ShowcaseTooltip.TooltipView bubble = tooltip.show(0);
        Button replacement = new Button(activity); content.addView(replacement);
        tooltip.configureTarget(content, replacement); layout(); idle(1);
        assertNull(bubble.getParent()); tooltip.cancel();
    }
    @Test public void tooltipUsesClippedTargetBoundsInItsOwnRootCoordinates() {
        target.setTranslationX(-50);
        ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity).text("Tip");
        tooltip.configureTarget(content, target);
        ShowcaseTooltip.TooltipView bubble = tooltip.show(0);
        idle(1); layout(); content.getViewTreeObserver().dispatchOnPreDraw();
        android.graphics.Rect anchor = org.robolectric.util.ReflectionHelpers.getField(bubble, "viewRect");
        assertNotNull(anchor); assertEquals(0, anchor.left); assertEquals(150, anchor.width());
        tooltip.cancel();
    }
    @Test public void alignedTooltipsStayInsideHorizontalViewport() {
        ShowcaseTooltip.TooltipView bubble = new ShowcaseTooltip.TooltipView(activity);
        content.addView(bubble, new FrameLayout.LayoutParams(100, 50));
        bubble.layout(0, 0, 100, 50); bubble.setPosition(ShowcaseTooltip.Position.BOTTOM);
        bubble.setAlign(ShowcaseTooltip.ALIGN.START);
        android.graphics.Rect start = new android.graphics.Rect(120, 0, 140, 80);
        bubble.adjustSize(start, 200); bubble.setupPosition(start);
        assertEquals(100f, bubble.getTranslationX(), 0f);
        bubble.setAlign(ShowcaseTooltip.ALIGN.END);
        android.graphics.Rect end = new android.graphics.Rect(60, 0, 80, 80);
        bubble.adjustSize(end, 200); bubble.setupPosition(end);
        assertEquals(0f, bubble.getTranslationX(), 0f);
        bubble.removeNow();
    }
    @Test public void sideTooltipNeverUsesNegativeLayoutWidth() {
        ShowcaseTooltip.TooltipView bubble = new ShowcaseTooltip.TooltipView(activity);
        content.addView(bubble, new FrameLayout.LayoutParams(100, 50));
        bubble.layout(0, 0, 100, 50); bubble.setPosition(ShowcaseTooltip.Position.LEFT);
        bubble.adjustSize(new android.graphics.Rect(10, 0, 30, 80), 200);
        assertTrue(bubble.getLayoutParams().width >= 0); bubble.removeNow();
    }
    @Test public void factoriesCancelEntranceExitAndMovement() {
        for(CancellableAnimationFactory factory:new CancellableAnimationFactory[]{new FadeAnimationFactory(),new CircularRevealAnimationFactory()}){
            MaterialShowcaseView v=view(0);v.show(activity);idle(1);int[] exits={0};factory.animateInView(v,new Point(0,0),500,()->{});factory.animateTargetToPoint(v,new Point(300,300));factory.animateOutView(v,new Point(0,0),500,()->exits[0]++);factory.cancel(v);int x=v.getShowcaseX();idle(1000);assertEquals(0,exits[0]);assertEquals(x,v.getShowcaseX());v.removeFromWindow();
        }
    }
    @Test public void revealRadiusCoversFarthestCorner() {
        target.layout(0,0,300,400);assertEquals(500f,CircularRevealAnimationFactory.revealRadius(target,new Point(0,0)),0.01f);assertEquals(250f,CircularRevealAnimationFactory.revealRadius(target,new Point(150,200)),0.01f);
    }
    @Test public void emptyAndMalformedLegacySequencesAreSafe() {
        MaterialShowcaseSequence empty=new MaterialShowcaseSequence(activity);empty.start();assertFalse(empty.isRunning());
        activity.getSharedPreferences("material_showcaseview_prefs",0).edit().putInt("status_malformed",999).commit();MaterialShowcaseSequence sequence=new MaterialShowcaseSequence(activity,"malformed");sequence.addSequenceItem(view(0));int[] shown={0};sequence.setOnItemShownListener((v,p)->shown[0]++);sequence.start();idle(1);assertEquals(1,shown[0]);sequence.cancel();
    }
    @Test public void throwingDismissListenerCancelsLegacySequence() {
        MaterialShowcaseView v=view(0);v.addShowcaseListener(new IShowcaseListener(){public void onShowcaseDisplayed(MaterialShowcaseView v){}public void onShowcaseDismissed(MaterialShowcaseView v){throw new IllegalStateException("app");}});MaterialShowcaseSequence sequence=new MaterialShowcaseSequence(activity);sequence.addSequenceItem(v);sequence.start();idle(1);try{v.hide();fail();}catch(IllegalStateException expected){}assertFalse(sequence.isRunning());assertNull(v.getParent());
    }

    @Test public void configuredShapePaddingAppliesToBuiltAndReplacementShapes() {
        uk.co.deanwild.materialshowcaseview.shape.CircleShape shape = new uk.co.deanwild.materialshowcaseview.shape.CircleShape(50);
        shape.setAdjustToTarget(false);
        MaterialShowcaseView view = new MaterialShowcaseView.Builder(activity).setTarget(target).setShape(shape).build();
        ShowcaseConfig config = new ShowcaseConfig(); config.setShapePadding(30); view.setConfig(config);
        assertEquals(80, shape.getTotalRadius());
        uk.co.deanwild.materialshowcaseview.shape.CircleShape replacement = new uk.co.deanwild.materialshowcaseview.shape.CircleShape(20);
        replacement.setAdjustToTarget(false); view.setShape(replacement);
        assertEquals(50, replacement.getTotalRadius());
    }

    @Test public void emptySkipControlIsNotAnInvisibleClickTarget() {
        assertEquals(View.GONE, view(0).findViewById(R.id.tv_skip).getVisibility());
    }

    @Test public void delayedEntranceCannotStartDuringDismissal() {
        MaterialShowcaseView view = view(100); int[] entrances = {0};
        view.setAnimationFactory(new IAnimationFactory() {
            public void animateInView(View v, Point p, long duration, AnimationStartListener listener) { entrances[0]++; listener.onAnimationStart(); }
            public void animateOutView(View v, Point p, long duration, AnimationEndListener listener) { }
            public void animateTargetToPoint(MaterialShowcaseView v, Point p) { }
        });
        view.show(activity); view.hide(); idle(101);
        assertEquals(0, entrances[0]); view.removeFromWindow();
    }

    @Test public void invalidTooltipTargetDoesNotLeaveAttachedOverlay() {
        MaterialShowcaseView view = new MaterialShowcaseView.Builder(activity).setToolTip(ShowcaseTooltip.build(activity).text("Tip")).build();
        try { view.show(activity); fail("Tooltip requires a View target"); } catch (IllegalArgumentException expected) { }
        assertNull(view.getParent());
    }

    @Test public void preferenceResetStartsFreshWithCurrentSchemaAndRejectsStaleWriter() {
        SharedPreferencesProgressStore store = new SharedPreferencesProgressStore(activity);
        Tutorial tutorial = new Tutorial("reset-schema", 3, Tutorial.Migration.FAIL, Step.builder("a").build());
        store.reset(tutorial.id);
        ProgressStore.Progress before = store.load(tutorial);
        assertEquals(3, before.version);
        store.reset(tutorial.id);
        ProgressStore.Progress after = store.load(tutorial);
        assertEquals(3, after.version); assertTrue(after.revision > before.revision);
        assertFalse(store.save(tutorial.id, before.revision, new ProgressStore.Progress(before.revision + 1,
                3, Collections.singleton("a"), Collections.emptySet(), ProgressStore.Outcome.COMPLETED)));
    }

    @Test public void longContentCanScrollDismissButtonIntoView() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++) text.append("Long tutorial content\n");
        MaterialShowcaseView view = new MaterialShowcaseView.Builder(activity).setTarget(target)
                .setContentText(text).setDismissText("Done").useFadeAnimation().setFadeDuration(0).build();
        view.show(activity); idle(1); layout(); layout();
        View dismiss = view.findViewById(R.id.tv_dismiss);
        android.graphics.Rect rectangle = new android.graphics.Rect(0, 0, dismiss.getWidth(), dismiss.getHeight());
        assertTrue("Dismiss control must be reachable by scrolling", dismiss.requestRectangleOnScreen(rectangle, true));
        layout();
        View panel = view.findViewById(R.id.content_box);
        int[] buttonPosition = new int[2], panelPosition = new int[2];
        dismiss.getLocationInWindow(buttonPosition); panel.getLocationInWindow(panelPosition);
        // This fixture manually sizes the decor; compare against its measured viewport,
        // rather than Robolectric's independently configured physical display rectangle.
        assertTrue(buttonPosition[1] >= panelPosition[1]);
        assertTrue(buttonPosition[1] + dismiss.getHeight() <= panelPosition[1] + panel.getHeight());
        view.removeFromWindow();
    }
}
