package uk.co.deanwild.materialshowcaseview;

import android.app.Activity;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.time.Duration;
import static org.junit.Assert.*;

/** L3: every framework callback into supplied tooltip content uses the same cleanup contract. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = {24, 28})
public class ExtensionFailureMatrixTest {
    ActivityController<Activity> controller; Activity activity; FrameLayout root; View target;
    boolean broken;
    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible(); activity = controller.get();
        root = new FrameLayout(activity); target = new View(activity);
        root.addView(target, new FrameLayout.LayoutParams(200, 100)); activity.setContentView(root); layout();
    }
    void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 600, 900);
    }
    void idle() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1)); }
    void settle() { for (int i = 0; i < 4; i++) { idle(); layout(); root.getViewTreeObserver().dispatchOnPreDraw(); } }
    @After public void cleanup() { broken = false; controller.pause().stop().destroy(); }
    View custom(String stage) {
        View content = new View(activity) {
            void failAt(String callback) { if (broken && stage.equals(callback)) throw new IllegalStateException(stage); }
            @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); failAt("attach"); }
            @Override protected void onMeasure(int w, int h) { failAt("measure"); setMeasuredDimension(140, 60); }
            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) { failAt("layout"); }
            @Override protected void onDraw(Canvas canvas) { failAt("draw"); }
            @Override public boolean onTouchEvent(MotionEvent event) { failAt("touch"); return true; }
        };
        content.setWillNotDraw(false); content.setClickable(true); return content;
    }
    @Test public void tooltipAttachmentFailureReleasesPresentation() { tooltipFailure("attach"); }
    @Test public void tooltipMeasurementFailureReleasesPresentation() { tooltipFailure("measure"); }
    @Test public void tooltipLayoutFailureReleasesPresentation() { tooltipFailure("layout"); }
    @Test public void tooltipDrawingFailureReleasesPresentation() { tooltipFailure("draw"); }
    @Test public void tooltipTouchFailureReleasesPresentation() { tooltipFailure("touch"); }
    void tooltipFailure(String stage) {
        View content = custom(stage);
        ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity).customView(content);
        tooltip.configureTarget(root, target); ShowcaseTooltip.TooltipView bubble = tooltip.show(0);
        if (!stage.equals("attach")) settle();
        broken = true;
        Bitmap bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888);
        try {
            try {
                switch (stage) {
                    case "attach": idle(); break;
                    case "measure": bubble.forceLayout(); bubble.measure(View.MeasureSpec.makeMeasureSpec(350, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST)); break;
                    case "layout": content.forceLayout(); content.measure(View.MeasureSpec.makeMeasureSpec(141, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(61, View.MeasureSpec.EXACTLY)); bubble.layout(0, 0, bubble.getWidth() + 1, bubble.getHeight() + 1); break;
                    case "draw": bubble.draw(new Canvas(bitmap)); break;
                    case "touch":
                        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, content.getLeft() + 5, content.getTop() + 5, 0);
                        try { bubble.dispatchTouchEvent(event); } finally { event.recycle(); }
                        break;
                }
                fail("Expected " + stage + " callback failure");
            } catch (IllegalStateException expected) { assertEquals(stage, expected.getMessage()); }
            assertNull(stage + " failure retained tooltip", bubble.getParent());
            assertNull(org.robolectric.util.ReflectionHelpers.getField(tooltip, "trackingLayout"));
            assertNull(org.robolectric.util.ReflectionHelpers.getField(tooltip, "pendingLayout"));
        } finally { broken = false; bitmap.recycle(); tooltip.cancel(); }
    }
    @Test public void tooltipFailureAlsoReleasesOwningLegacySequence() {
        ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity).customView(custom("measure"));
        MaterialShowcaseView view = new MaterialShowcaseView.Builder(activity).setTarget(target).setToolTip(tooltip)
                .setDismissOnTouch(true).useFadeAnimation().setFadeDuration(0).build();
        MaterialShowcaseSequence sequence = new MaterialShowcaseSequence(activity, "extension-failure");
        sequence.addSequenceItem(view); sequence.start(); settle(); broken = true;
        ShowcaseTooltip.TooltipView bubble = org.robolectric.util.ReflectionHelpers.getField(tooltip, "tooltip_view");
        bubble.forceLayout(); view.forceLayout();
        try {
            try { view.measure(View.MeasureSpec.makeMeasureSpec(590, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(890, View.MeasureSpec.EXACTLY)); fail(); }
            catch (IllegalStateException expected) { assertEquals("measure", expected.getMessage()); }
            assertNull(view.getParent()); assertFalse(sequence.isRunning()); assertFalse(sequence.hasFired());
        } finally { broken = false; sequence.cancel(); }
    }
    @Test public void rejectedLegacyPresentationReleasesSequenceOwnership() {
        MaterialShowcaseView invalid = new MaterialShowcaseView.Builder(activity)
                .setToolTip(ShowcaseTooltip.build(activity).text("Requires a View target")).build();
        MaterialShowcaseSequence sequence = new MaterialShowcaseSequence(activity, "invalid-presentation");
        sequence.addSequenceItem(invalid);
        try {
            try { sequence.start(); fail(); } catch (IllegalArgumentException expected) { }
            assertFalse("Rejected show left the sequence running", sequence.isRunning());
            assertFalse(sequence.hasFired()); assertNull(invalid.getParent());
        } finally { sequence.cancel(); }
    }
    @Test public void postedTooltipAttachFailureReleasesOwningSequence() { asynchronousOwnerFailure(false); }
    @Test public void tooltipDisplayFailureReleasesOwningSequence() { asynchronousOwnerFailure(true); }
    @Test public void hiddenLegacyAnchorCancelsWithoutProgress() { unavailableAnchor("hidden"); }
    @Test public void detachedLegacyAnchorCancelsWithoutProgress() { unavailableAnchor("detached"); }
    @Test public void transparentLegacyAnchorCancelsWithoutProgress() { unavailableAnchor("transparent"); }
    @Test public void windowTeardownDoesNotRemoveChildrenFromTheTraversedParent() {
        MaterialShowcaseView view = new MaterialShowcaseView.Builder(activity).setTarget(target)
                .setDismissText("Next").useFadeAnimation().setFadeDuration(0).build();
        MaterialShowcaseSequence sequence = new MaterialShowcaseSequence(activity, "window-teardown");
        sequence.addSequenceItem(view); sequence.start(); settle();
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        // A following sibling makes removal during the framework's indexed traversal unsafe.
        View sibling = new View(activity); decor.addView(sibling);
        try {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(decor, "dispatchDetachedFromWindow");
            assertSame("Window owns its child list during detachment", decor, view.getParent());
            assertFalse(sequence.isRunning()); assertFalse(sequence.hasFired());
            assertNull(org.robolectric.util.ReflectionHelpers.getField(view, "mLayoutListener"));
        } finally { sequence.cancel(); view.removeFromWindow(); decor.removeView(sibling); }
    }
    private void unavailableAnchor(String change) {
        MaterialShowcaseView view = new MaterialShowcaseView.Builder(activity).setTarget(target)
                .setDismissText("Next").useFadeAnimation().setFadeDuration(0).build();
        MaterialShowcaseSequence sequence = new MaterialShowcaseSequence(activity, "unavailable-anchor");
        sequence.addSequenceItem(view); sequence.start(); settle();
        try {
            if (change.equals("hidden")) target.setVisibility(View.INVISIBLE);
            else if (change.equals("detached")) root.removeView(target);
            else target.setAlpha(0);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertNull(change + " anchor retained its showcase", view.getParent());
            assertFalse(sequence.isRunning()); assertFalse(sequence.hasFired());
        } finally { sequence.cancel(); }
    }
    private void asynchronousOwnerFailure(boolean display) {
        ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity).customView(custom(display ? "none" : "attach"));
        if (display) tooltip.animation(new ShowcaseTooltip.TooltipAnimation() {
            public void animateEnter(View view, android.animation.Animator.AnimatorListener listener) { listener.onAnimationEnd(null); }
            public void animateExit(View view, android.animation.Animator.AnimatorListener listener) { }
        }).onDisplay(view -> { throw new IllegalStateException("display"); });
        MaterialShowcaseView view = new MaterialShowcaseView.Builder(activity).setTarget(target).setToolTip(tooltip)
                .setDismissOnTouch(true).useFadeAnimation().setFadeDuration(0).build();
        MaterialShowcaseSequence sequence = new MaterialShowcaseSequence(activity, "async-tooltip-failure");
        sequence.addSequenceItem(view); sequence.start(); broken = true;
        try {
            try { settle(); fail("Expected asynchronous tooltip failure"); }
            catch (IllegalStateException expected) { assertEquals(display ? "display" : "attach", expected.getMessage()); }
            assertNull("Tooltip failure left the owning mask attached", view.getParent());
            assertFalse(sequence.isRunning()); assertFalse(sequence.hasFired());
        } finally { broken = false; sequence.cancel(); }
    }
}
