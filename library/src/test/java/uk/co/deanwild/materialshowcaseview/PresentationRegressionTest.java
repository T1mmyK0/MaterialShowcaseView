package uk.co.deanwild.materialshowcaseview;

import android.app.Activity;
import android.graphics.Point;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28)
public class PresentationRegressionTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private View target;

    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get(); root = new FrameLayout(activity);
        target = new View(activity); root.addView(target, new FrameLayout.LayoutParams(200, 100));
        activity.setContentView(root);
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 600, 900);
    }

    @After public void cleanup() { controller.pause().stop().destroy(); }
    private void idle(long millis) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); }

    @Test public void tooltipPlacementUsesParentCoordinatesEvenWithPadding() {
        root.setPadding(35, 45, 0, 0);
        ShowcaseTooltip.TooltipView bubble = new ShowcaseTooltip.TooltipView(activity);
        root.addView(bubble, new FrameLayout.LayoutParams(100, 60));
        bubble.layout(35, 45, 135, 105);
        bubble.setAlign(ShowcaseTooltip.ALIGN.START);
        bubble.setupPosition(new android.graphics.Rect(120, 140, 200, 180));
        assertEquals("Parent padding must not shift the tooltip away from its anchor", 120f, bubble.getX(), 0f);
        assertEquals(180f, bubble.getY(), 0f);
    }

    @Test public void throwingTooltipEntranceRemovesBubbleAndPendingSetup() {
        ShowcaseTooltip.TooltipView bubble = new ShowcaseTooltip.TooltipView(activity);
        root.addView(bubble, new FrameLayout.LayoutParams(200, 60)); bubble.layout(0, 0, 200, 60);
        int[] cancelled = {0};
        bubble.setTooltipAnimation(new ShowcaseTooltip.CancellableTooltipAnimation() {
            public void animateEnter(View view, android.animation.Animator.AnimatorListener listener) { throw new IllegalStateException("entrance"); }
            public void animateExit(View view, android.animation.Animator.AnimatorListener listener) { }
            public void cancel(View view) { cancelled[0]++; }
        });
        try { bubble.setup(new android.graphics.Rect(100, 100, 200, 150), 600); fail("Expected entrance failure"); }
        catch (IllegalStateException expected) { assertEquals("entrance", expected.getMessage()); }
        assertNull("Failed tooltip entrance must release its view", bubble.getParent());
        assertEquals(1, cancelled[0]);
    }

    @Test public void externalTooltipDetachCancelsAnimationAndObsoleteDisplayCallback() {
        ShowcaseTooltip.TooltipView bubble = new ShowcaseTooltip.TooltipView(activity);
        root.addView(bubble, new FrameLayout.LayoutParams(200, 60)); bubble.layout(0, 0, 200, 60);
        int[] cancelled = {0}, displayed = {0};
        android.animation.Animator.AnimatorListener[] callback = {null};
        bubble.setTooltipAnimation(new ShowcaseTooltip.CancellableTooltipAnimation() {
            public void animateEnter(View view, android.animation.Animator.AnimatorListener listener) { callback[0] = listener; }
            public void animateExit(View view, android.animation.Animator.AnimatorListener listener) { }
            public void cancel(View view) { cancelled[0]++; }
        });
        bubble.setListenerDisplay(view -> displayed[0]++);
        bubble.setup(new android.graphics.Rect(100, 100, 200, 150), 600);
        root.removeView(bubble);
        assertEquals("External detach must release custom animation resources", 1, cancelled[0]);
        root.addView(bubble); callback[0].onAnimationEnd(null);
        assertEquals("A callback from before detachment cannot display a reused tooltip", 0, displayed[0]);
        bubble.removeNow();
    }

    @Test public void throwingTooltipDisplayListenerReleasesBubble() {
        ShowcaseTooltip.TooltipView bubble = new ShowcaseTooltip.TooltipView(activity);
        root.addView(bubble, new FrameLayout.LayoutParams(200, 60)); bubble.layout(0, 0, 200, 60);
        android.animation.Animator.AnimatorListener[] callback = {null};
        bubble.setTooltipAnimation(new ShowcaseTooltip.TooltipAnimation() {
            public void animateEnter(View view, android.animation.Animator.AnimatorListener listener) { callback[0] = listener; }
            public void animateExit(View view, android.animation.Animator.AnimatorListener listener) { }
        });
        bubble.setListenerDisplay(view -> { throw new IllegalStateException("display"); });
        bubble.setup(new android.graphics.Rect(100, 100, 200, 150), 600);
        try { callback[0].onAnimationEnd(null); fail("Expected display failure"); }
        catch (IllegalStateException expected) { assertEquals("display", expected.getMessage()); }
        assertNull("Failed display callbacks must release the tooltip", bubble.getParent());
    }

    @Test public void duplicateTooltipCompletionDeliversDisplayOnce() {
        ShowcaseTooltip.TooltipView bubble = new ShowcaseTooltip.TooltipView(activity);
        root.addView(bubble, new FrameLayout.LayoutParams(200, 60)); bubble.layout(0, 0, 200, 60);
        int[] displayed = {0}; android.animation.Animator.AnimatorListener[] callback = {null};
        bubble.setTooltipAnimation(new ShowcaseTooltip.TooltipAnimation() {
            public void animateEnter(View view, android.animation.Animator.AnimatorListener listener) { callback[0] = listener; }
            public void animateExit(View view, android.animation.Animator.AnimatorListener listener) { }
        });
        bubble.setListenerDisplay(view -> displayed[0]++);
        bubble.setup(new android.graphics.Rect(100, 100, 200, 150), 600);
        callback[0].onAnimationEnd(null); callback[0].onAnimationEnd(null);
        assertEquals(1, displayed[0]); bubble.removeNow();
    }

    @Test public void oldTooltipFailureCannotRemoveReplacementPresentation() {
        ShowcaseTooltip.TooltipView bubble = new ShowcaseTooltip.TooltipView(activity);
        root.addView(bubble, new FrameLayout.LayoutParams(200, 60)); bubble.layout(0, 0, 200, 60);
        android.animation.Animator.AnimatorListener[] callback = {null};
        bubble.setTooltipAnimation(new ShowcaseTooltip.TooltipAnimation() {
            public void animateEnter(View view, android.animation.Animator.AnimatorListener listener) { callback[0] = listener; }
            public void animateExit(View view, android.animation.Animator.AnimatorListener listener) { }
        });
        bubble.setListenerDisplay(view -> {
            bubble.removeNow(); root.addView(bubble); bubble.setListenerDisplay(null);
            bubble.setup(new android.graphics.Rect(100, 100, 200, 150), 600);
            throw new IllegalStateException("old presentation");
        });
        bubble.setup(new android.graphics.Rect(100, 100, 200, 150), 600);
        try { callback[0].onAnimationEnd(null); fail("Expected callback failure"); }
        catch (IllegalStateException expected) { assertEquals("old presentation", expected.getMessage()); }
        assertSame(root, bubble.getParent()); bubble.removeNow();
    }

    @Test public void externallyDetachedTooltipReleasesDeferredResizeObserver() {
        ShowcaseTooltip.TooltipView bubble = new ShowcaseTooltip.TooltipView(activity);
        root.addView(bubble, new FrameLayout.LayoutParams(200, 60)); bubble.layout(0, 0, 200, 60);
        int[] entrances = {0};
        bubble.setTooltipAnimation(new ShowcaseTooltip.TooltipAnimation() {
            public void animateEnter(View view, android.animation.Animator.AnimatorListener listener) { entrances[0]++; }
            public void animateExit(View view, android.animation.Animator.AnimatorListener listener) { }
        });
        bubble.setup(new android.graphics.Rect(20, 100, 80, 150), 100);
        assertEquals(0, entrances[0]);
        android.view.ViewTreeObserver.OnPreDrawListener stale = org.robolectric.util.ReflectionHelpers.getField(bubble, "setupListener");
        root.removeView(bubble); root.addView(bubble);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("Cancelled resize must not start an entrance after reuse", 0, entrances[0]);
        bubble.setup(new android.graphics.Rect(20, 100, 80, 150), 100);
        Object replacement = org.robolectric.util.ReflectionHelpers.getField(bubble, "setupListener");
        stale.onPreDraw();
        assertSame(replacement, org.robolectric.util.ReflectionHelpers.getField(bubble, "setupListener"));
        assertEquals(0, entrances[0]); bubble.removeNow();
    }

    @Test public void defaultTooltipKeepsContentInsideBubbleBelowArrow() {
        ShowcaseTooltip.TooltipView bubble = new ShowcaseTooltip.TooltipView(activity);
        bubble.setText("Tooltip content");
        bubble.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST));
        bubble.layout(0, 0, bubble.getMeasuredWidth(), bubble.getMeasuredHeight());
        assertTrue("Content overlaps the top arrow", bubble.getChildAt(0).getTop() > bubble.getArrowHeight());
        assertTrue("Content extends outside the bubble's inset left edge", bubble.getChildAt(0).getLeft() >= 30);
    }

    @Test public void tooltipPaddingAndArrowHeightDoNotDependOnSetterOrder() {
        ShowcaseTooltip first = ShowcaseTooltip.build(activity).text("Tip")
                .position(ShowcaseTooltip.Position.RIGHT).padding(40, 25, 45, 35).arrowHeight(28);
        ShowcaseTooltip second = ShowcaseTooltip.build(activity).text("Tip")
                .padding(40, 25, 45, 35).arrowHeight(28).position(ShowcaseTooltip.Position.RIGHT);
        first.configureTarget(root, target); second.configureTarget(root, target);
        ShowcaseTooltip.TooltipView a = first.show(0), b = second.show(0);
        try {
            assertEquals(b.getPaddingLeft(), a.getPaddingLeft());
            assertEquals(b.getPaddingTop(), a.getPaddingTop());
            assertEquals(b.getPaddingRight(), a.getPaddingRight());
            assertEquals(b.getPaddingBottom(), a.getPaddingBottom());
            assertEquals(68, a.getPaddingLeft());
        } finally { first.cancel(); second.cancel(); }
    }

    @Test public void zeroDurationExitCannotBeUndoneByRunningEntrance() {
        FadeAnimationFactory factory = new FadeAnimationFactory();
        factory.animateInView(target, new Point(), 500, () -> { }); idle(100);
        factory.animateOutView(target, new Point(), 0, () -> { });
        idle(600);
        assertEquals("An old entrance restored the dismissed view", 0f, target.getAlpha(), 0f);
        factory.cancel(target);
    }

    @Test public void newEntranceCancelsPreviousExitCompletion() {
        for (CancellableAnimationFactory factory : new CancellableAnimationFactory[]{
                new FadeAnimationFactory(), new CircularRevealAnimationFactory()}) {
            int[] exits = {0};
            factory.animateOutView(target, new Point(), 500, () -> exits[0]++);
            assertEquals(factory.getClass().getSimpleName() + " exit must still be pending", 0, exits[0]);
            factory.animateInView(target, new Point(), 0, () -> { }); idle(600);
            assertEquals(factory.getClass().getSimpleName() + " superseded exit dismissed the new presentation", 0, exits[0]);
            assertEquals(1f, target.getAlpha(), 0f);
            factory.cancel(target);
        }
    }
}
