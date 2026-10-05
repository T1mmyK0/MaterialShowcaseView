package uk.co.deanwild.materialshowcaseview.session;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.time.Duration;
import java.util.Locale;
import uk.co.deanwild.materialshowcaseview.*;
import static org.junit.Assert.*;

/** V1/V2: reusable layout contracts, rather than one screenshot's coordinates. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = {28, 30}, qualifiers = "w1000dp-h1000dp-mdpi")
public class LayoutContractMatrixTest {
    ActivityController<Activity> controller; Activity activity; FrameLayout root; Button target;
    int width, height;
    final String longText = String.join(" ", java.util.Collections.nCopies(80, "Long tutorial content"));
    @Before public void setup() { controller = Robolectric.buildActivity(Activity.class).setup().visible(); activity = controller.get(); }
    @After public void cleanup() { controller.pause().stop().destroy(); }
    void configure(int width, int height, boolean rtl, float fontScale) {
        this.width = width; this.height = height;
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.fontScale = fontScale; config.setLayoutDirection(new Locale(rtl ? "ar" : "en"));
        activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics());
        root = new FrameLayout(activity); root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        target = new Button(activity); target.setText("Target");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(100, 60, Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = width / 2 - 50; lp.topMargin = height / 2 - 60;
        root.addView(target, lp); activity.setContentView(root); layout(); controller.windowFocusChanged(true);
    }
    void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
    }
    void settle() {
        for (int i = 0; i < 6; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10)); layout(); root.getViewTreeObserver().dispatchOnPreDraw();
        }
    }
    void assertReachable(ScrollView panel, View control) {
        assertTrue("Panel has no usable area", panel.getWidth() > 0 && panel.getHeight() >= 48);
        Rect viewport = new Rect(); assertTrue(TargetGeometry.usableOnScreen(root, viewport));
        Rect bounds = new Rect(); TargetGeometry.boundsOnScreen(panel, bounds);
        assertTrue("Panel " + bounds + " outside " + viewport, viewport.contains(bounds));
        panel.scrollTo(0, panel.getChildAt(0).getHeight());
        Rect shown = new Rect(); assertTrue("Last control is unreachable", TargetGeometry.visibleOnScreen(control, shown));
        assertTrue(shown.height() > 0 && shown.width() > 0);
        assertTrue("Last control remains outside viewport", viewport.contains(shown));
        assertTrue(control.isEnabled()); assertTrue(control.isClickable());
    }
    @Test public void sessionControlsRemainReachableAcrossWindowDirectionAndFontMatrix() {
        for (int[] size : new int[][]{{320, 480}, {480, 320}, {240, 320}})
            for (boolean rtl : new boolean[]{false, true}) for (float font : new float[]{1, 2}) {
                configure(size[0], size[1], rtl, font);
                TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; theme.close = "Close";
                int[] closed = {0};
                TutorialHost.Actions actions = new TutorialHost.Actions() {
                    public void next() { } public void previous() { } public void skipStep() { }
                    public void skipTour() { } public void close() { closed[0]++; } public void actionCompleted() { }
                };
                TutorialOverlay overlay = new TutorialOverlay(root, () -> target,
                        Step.builder("layout").content("A long tutorial title", longText).build(), actions, theme);
                try {
                    overlay.attach(() -> { }); settle();
                    ScrollView panel = (ScrollView) overlay.getChildAt(0);
                    LinearLayout content = (LinearLayout) panel.getChildAt(0);
                    View close = content.getChildAt(content.getChildCount() - 1);
                    assertReachable(panel, close); close.performClick(); assertEquals(1, closed[0]);
                } finally { overlay.cancel(); }
            }
    }
    @Test public void legacyControlsRemainReachableAcrossWindowDirectionAndFontMatrix() {
        for (int[] size : new int[][]{{320, 480}, {480, 320}, {240, 320}})
            for (boolean rtl : new boolean[]{false, true}) for (float font : new float[]{1, 2}) {
                configure(size[0], size[1], rtl, font);
                MaterialShowcaseView view = new MaterialShowcaseView.Builder(activity).setTarget(target)
                        .setTitleText("A long tutorial title").setContentText(longText).setDismissText("Close")
                        .useFadeAnimation().setFadeDuration(0).build();
                try {
                    assertTrue(view.show(activity)); settle();
                    ScrollView panel = view.findViewById(R.id.content_box);
                    View close = view.findViewById(R.id.tv_dismiss);
                    assertReachable(panel, close); close.performClick(); assertNull(view.getParent());
                } finally { view.removeFromWindow(); }
            }
    }
    @Test public void tooltipPlacementsRemainContainedAcrossAlignmentDirectionAndFontMatrix() {
        for (ShowcaseTooltip.Position position : ShowcaseTooltip.Position.values())
            for (ShowcaseTooltip.ALIGN align : ShowcaseTooltip.ALIGN.values())
                for (boolean rtl : new boolean[]{false, true}) for (float font : new float[]{1, 2}) {
                    configure(640, 700, rtl, font);
                    ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity).text("Tooltip").position(position).align(align);
                    tooltip.configureTarget(root, target); ShowcaseTooltip.TooltipView bubble = tooltip.show(0);
                    try {
                        settle();
                        assertTrue(position + "/" + align + " width", bubble.getWidth() > bubble.getPaddingLeft() + bubble.getPaddingRight());
                        assertTrue(position + "/" + align + " left", bubble.getX() >= 0);
                        assertTrue(position + "/" + align + " right", bubble.getX() + bubble.getWidth() <= root.getWidth());
                        assertTrue(position + "/" + align + " top", bubble.getY() >= 0);
                        assertTrue(position + "/" + align + " bottom", bubble.getY() + bubble.getHeight() <= root.getHeight());
                    } finally { tooltip.cancel(); }
                }
    }
    @Test public void sideTooltipRecoversNaturalWidthWhenSpaceIncreases() {
        configure(640, 700, false, 1);
        target.setTranslationX(-80);
        ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity)
                .text("This tooltip should recover its natural width after the target moves").position(ShowcaseTooltip.Position.LEFT);
        tooltip.configureTarget(root, target); ShowcaseTooltip.TooltipView bubble = tooltip.show(0);
        try {
            settle(); int narrow = bubble.getWidth();
            assertTrue("Fixture must actually constrain the initial bubble", bubble.getLayoutParams().width >= 0);
            target.setTranslationX(200); settle();
            assertTrue("Width remained " + bubble.getWidth() + " after constraint " + narrow
                    + "; params=" + bubble.getLayoutParams().width + "; target=" + target.getX()
                    + "; cap=" + org.robolectric.util.ReflectionHelpers.getField(bubble, "constrainedWidth"), bubble.getWidth() > narrow);
            assertTrue(bubble.getX() >= 0); assertTrue(bubble.getX() + bubble.getWidth() <= target.getX());
        } finally { tooltip.cancel(); }
    }
    @Test public void tooltipTracksDirectionChangeWithoutTargetMovement() {
        configure(640, 700, false, 1);
        ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity).text("Tooltip");
        tooltip.configureTarget(root, target); ShowcaseTooltip.TooltipView bubble = tooltip.show(0);
        try {
            settle(); float x = bubble.getX(), y = bubble.getY();
            root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); settle();
            assertEquals("Parent layout changed the bubble's origin", x, bubble.getX(), 0f);
            assertEquals(y, bubble.getY(), 0f);
        } finally { tooltip.cancel(); }
    }
}
