package uk.co.deanwild.materialshowcaseview.session;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 30}, qualifiers = "w411dp-h891dp-560dpi")
public class TutorialPlacementTest {
    private static final String EXPLANATION = "Use this control to customize the information shown in your workspace.";
    private ActivityController<Activity> controller;
    private Activity activity;
    private PlacementRoot root;
    private View target;
    private AndroidTutorialHost host;
    private TutorialOverlay overlay;
    private ScrollView panel;
    private int width = 1440, height = 3120, next;

    private final class PlacementRoot extends FrameLayout {
        WindowInsets insets;
        PlacementRoot() { super(activity); }
        @Override public WindowInsets getRootWindowInsets() { return insets; }
        @Override public void getWindowVisibleDisplayFrame(Rect out) {
            int[] origin = new int[2]; getRootView().getLocationOnScreen(origin);
            out.set(origin[0], origin[1] + dp(24), origin[0] + width, origin[1] + height - dp(24));
        }
    }

    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class);
        activity = controller.get();
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        activity.requestWindowFeature(Window.FEATURE_NO_TITLE);
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        controller.setup().visible();
        activity.getApplicationInfo().flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        configure(false, false, 1);
    }
    @After public void cleanup() {
        if (host != null) host.cancel();
        controller.pause().stop().destroy();
    }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void configure(boolean landscape, boolean rtl, float fontScale) {
        if (host != null) host.cancel();
        RuntimeEnvironment.setQualifiers(landscape ? "w891dp-h411dp-land-560dpi" : "w411dp-h891dp-port-560dpi");
        width = landscape ? 3120 : 1440; height = landscape ? 1440 : 3120;
        Shadows.shadowOf(activity.getWindowManager().getDefaultDisplay()).setWidth(width);
        Shadows.shadowOf(activity.getWindowManager().getDefaultDisplay()).setHeight(height);
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.fontScale = fontScale;
        activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics());
        root = new PlacementRoot();
        root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(Color.rgb(232, 238, 242));
        target = new View(activity); target.setBackgroundColor(Color.rgb(36, 160, 100));
        root.addView(target, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.LEFT));
        activity.setContentView(root); layout(); controller.windowFocusChanged(true);
        Object info = ReflectionHelpers.getField(activity.getWindow().getDecorView(), "mAttachInfo");
        ReflectionHelpers.setField(info, "mWindowVisibility", View.VISIBLE);
        host = new AndroidTutorialHost(root, id -> target); host.setResumed(true);
        host.observe(() -> { });
        moveTarget(usable().centerY(), false);
    }
    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
    }
    private void settle() {
        for (int i = 0; i < 8; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
            layout(); root.getViewTreeObserver().dispatchOnPreDraw();
        }
    }
    private Rect usable() {
        Rect bounds = new Rect(); assertTrue(TargetGeometry.usableOnScreen(root, bounds));
        int[] origin = new int[2]; root.getLocationOnScreen(origin); bounds.offset(-origin[0], -origin[1]);
        return bounds;
    }
    private void moveTarget(int centerY, boolean left) {
        Rect viewport = usable();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) target.getLayoutParams();
        lp.leftMargin = left ? viewport.left + dp(8) : viewport.right - dp(56);
        lp.topMargin = centerY - lp.height / 2;
        target.setLayoutParams(lp); layout();
    }
    private TutorialTheme theme() {
        TutorialTheme theme = new TutorialTheme();
        theme.reducedMotion = true; theme.showProgress = false; theme.showSkipTour = false;
        theme.next = "Continue";
        return theme;
    }
    private void show(String text) { show(text, theme(), true); }
    private void show(String text, TutorialTheme theme, boolean withTarget) {
        host.setTheme(theme);
        Step.Builder builder = Step.builder("placement").content(null, text);
        if (withTarget) builder.target("control");
        Step step = builder.build();
        boolean valid = host.valid(step, true);
        assertTrue(host.diagnostic(), valid);
        host.show(step, new TutorialHost.Actions() {
            public void next() { next++; } public void previous() { } public void skipStep() { }
            public void skipTour() { } public void close() { } public void actionCompleted() { }
        }, () -> { });
        settle();
        overlay = (TutorialOverlay) root.getChildAt(root.getChildCount() - 1);
        panel = (ScrollView) overlay.getChildAt(0);
    }
    private RectF highlight() { return new RectF(ReflectionHelpers.<RectF>getField(overlay, "highlightBounds")); }
    private Rect panelBounds() { return new Rect(panel.getLeft(), panel.getTop(), panel.getRight(), panel.getBottom()); }
    private void assertContainedAndSeparate() {
        assertTrue("Panel " + panelBounds() + " outside " + usable(), usable().contains(panelBounds()));
        assertFalse("Panel overlaps highlight " + highlight(), RectF.intersects(new RectF(panelBounds()), highlight()));
    }
    private void assertAdjacent() {
        assertContainedAndSeparate();
        RectF hole = highlight(); assertFalse(hole.isEmpty());
        assertEquals(usable().width(), panel.getWidth());
        assertTrue("Outer panel edge must meet highlight: " + panelBounds() + " / " + hole,
                panel.getBottom() == (int) Math.floor(hole.top) || panel.getTop() == (int) Math.ceil(hole.bottom));
    }
    private void assertCenteredBeside() {
        assertContainedAndSeparate();
        assertTrue(panel.getWidth() < usable().width());
        int expected = Math.round(highlight().centerY() - panel.getHeight() / 2f);
        expected = Math.max(usable().top, Math.min(expected, usable().bottom - panel.getHeight()));
        assertEquals(expected, panel.getTop());
    }
    private Button navigation() {
        LinearLayout content = (LinearLayout) panel.getChildAt(0);
        ViewGroup row = (ViewGroup) content.getChildAt(content.getChildCount() - 1);
        assertEquals(1, row.getChildCount());
        return (Button) row.getChildAt(0);
    }
    private void assertNavigationReachable() {
        panel.scrollTo(0, panel.getChildAt(0).getHeight());
        assertContainedAndSeparate();
        Rect visible = new Rect(), bounds = new Rect();
        assertTrue(TargetGeometry.visibleOnScreen(navigation(), visible));
        TargetGeometry.boundsOnScreen(navigation(), bounds);
        assertEquals("The whole navigation button must be reachable", bounds, visible);
        int before = next; navigation().performClick(); assertEquals(before + 1, next);
    }
    private String lines(int count) { return String.join("\n", Collections.nCopies(count, "More details about this control.")); }

    @Test public void normalCopyStaysAdjacentAcrossMidpointInPortraitLandscapeAndRtl() {
        for (boolean landscape : new boolean[]{false, true}) for (boolean rtl : new boolean[]{false, true}) {
            configure(landscape, rtl, 1);
            assertEquals(560, activity.getResources().getDisplayMetrics().densityDpi);
            assertEquals(width, activity.getWindow().getDecorView().getWidth());
            assertEquals(height, activity.getWindow().getDecorView().getHeight());
            show(EXPLANATION);
            for (int offset : new int[]{-4, 0, 4}) {
                moveTarget(usable().centerY() + dp(offset), false); settle(); assertAdjacent();
                LinearLayout content = (LinearLayout) panel.getChildAt(0);
                assertEquals(View.GONE, content.getChildAt(0).getVisibility());
                assertEquals(View.GONE, content.getChildAt(1).getVisibility());
                assertEquals(content.getPaddingTop(), content.getChildAt(2).getTop());
                assertEquals(panel.getHeight(), content.getHeight());
                navigation();
            }
        }
    }
    @Test public void onlyFittingVerticalRegionWinsNearEitherViewportBoundary() {
        for (boolean atTop : new boolean[]{true, false}) {
            configure(false, false, 1);
            moveTarget(atTop ? usable().top + dp(26) : usable().bottom - dp(26), false);
            show(EXPLANATION); assertAdjacent();
            if (atTop) assertEquals((int) Math.ceil(highlight().bottom), panel.getTop());
            else assertEquals((int) Math.floor(highlight().top), panel.getBottom());
        }
    }
    @Test public void necessarySidePlacementTracksTargetAcrossMidpoint() {
        for (boolean landscape : new boolean[]{false, true}) for (boolean left : new boolean[]{false, true}) {
            configure(landscape, left, 1);
            moveTarget(usable().centerY(), left);
            show(lines(landscape ? 9 : 24));
            int previousY = panel.getTop();
            for (int offset : new int[]{-4, 0, 4}) {
                moveTarget(usable().centerY() + dp(offset), left); settle(); assertCenteredBeside();
                assertTrue("Midpoint movement must not jump to a viewport edge", Math.abs(panel.getTop() - previousY) <= dp(8));
                previousY = panel.getTop();
            }
        }
    }
    @Test @Config(sdk = 30) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void sideColumnRemeasuresWrappedCopyBeforeCentering() {
        show(String.join(" ", Collections.nCopies(120, "Details")));
        assertCenteredBeside();
        int columnHeight = panel.getChildAt(0).getHeight();
        panel.measure(View.MeasureSpec.makeMeasureSpec(usable().width(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int fullWidthHeight = panel.getMeasuredHeight();
        assertTrue("Narrowing the panel must wrap the copy", columnHeight > fullWidthHeight);
        assertTrue("Unclipped full-width copy must not fit above or below",
                fullWidthHeight > Math.max(highlight().top - usable().top, usable().bottom - highlight().bottom));
        overlay.reposition(); settle(); assertCenteredBeside();
    }
    @Test public void longCopyAndLargeFontScrollToNavigationInClampedSideColumn() {
        for (boolean landscape : new boolean[]{false, true}) for (float fontScale : new float[]{1, 2}) {
            configure(landscape, true, fontScale);
            moveTarget(usable().top + dp(30), false);
            show(lines(80)); assertCenteredBeside();
            assertEquals(usable().top, panel.getTop());
            assertEquals(usable().height(), panel.getHeight());
            assertTrue(panel.getChildAt(0).getHeight() > panel.getHeight());
            assertNavigationReachable();
        }
    }
    @Test public void narrowWindowRetainsScrollableVerticalFallback() {
        width = dp(240); layout(); moveTarget(usable().centerY(), false);
        show(lines(80)); assertAdjacent();
        assertTrue(panel.getChildAt(0).getHeight() > panel.getHeight());
        assertNavigationReachable();
    }
    @Test public void targetlessCopyKeepsBottomFallback() {
        show(EXPLANATION, theme(), false);
        assertTrue(highlight().isEmpty());
        assertEquals(usable().bottom, panel.getBottom());
        assertTrue(usable().contains(panelBounds()));
    }
    @Test public void scrollingAndRotationRefreshPanelCoordinates() {
        root.removeView(target);
        ScrollView scroll = new ScrollView(activity); FrameLayout contents = new FrameLayout(activity);
        contents.setMinimumHeight(height * 2);
        scroll.addView(contents, new ScrollView.LayoutParams(-1, height * 2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = width - dp(56); lp.topMargin = usable().centerY() - dp(24);
        contents.addView(target, lp); layout(); show(EXPLANATION); assertAdjacent();
        Rect before = panelBounds(); RectF oldHighlight = highlight();
        scroll.scrollTo(0, dp(72)); settle(); assertAdjacent();
        assertNotEquals(before, panelBounds()); assertEquals(oldHighlight.top - scroll.getScrollY(), highlight().top, 1f);
        RuntimeEnvironment.setQualifiers("w891dp-h411dp-land-560dpi");
        width = 3120; height = 1440;
        Shadows.shadowOf(activity.getWindowManager().getDefaultDisplay()).setWidth(width);
        Shadows.shadowOf(activity.getWindowManager().getDefaultDisplay()).setHeight(height);
        layout(); settle();
        lp.leftMargin = usable().right - dp(56); lp.topMargin = usable().centerY() + scroll.getScrollY() - dp(24);
        target.setLayoutParams(lp); settle(); assertAdjacent();
    }
    @Test @Config(sdk = 30) public void changingInsetsAndOffsetWindowUseFreshLocalCoordinates() {
        // The display must contain the offset window, including its right/bottom edges.
        RuntimeEnvironment.setQualifiers("w600dp-h1100dp-port-560dpi"); layout();
        Object info = ReflectionHelpers.getField(activity.getWindow().getDecorView(), "mAttachInfo");
        ReflectionHelpers.setField(info, "mWindowLeft", 130);
        ReflectionHelpers.setField(info, "mWindowTop", 210);
        int[] origin = new int[2]; root.getLocationOnScreen(origin);
        assertEquals(130, origin[0]); assertEquals(210, origin[1]);
        root.insets = new WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(),
                Insets.of(dp(12), dp(30), dp(8), dp(24))).build();
        moveTarget(usable().centerY(), false); show(EXPLANATION); assertAdjacent();
        Rect before = panelBounds();
        root.insets = new WindowInsets.Builder(root.insets).setInsets(WindowInsets.Type.ime(),
                Insets.of(0, 0, 0, dp(280))).build();
        settle(); assertAdjacent(); assertNotEquals(before, panelBounds());
        Rect onScreen = new Rect(), viewport = new Rect();
        TargetGeometry.boundsOnScreen(panel, onScreen); TargetGeometry.usableOnScreen(root, viewport);
        assertTrue(viewport.contains(onScreen));
        root.insets = new WindowInsets.Builder().build(); settle(); assertAdjacent();
    }
    @Test @Config(sdk = 30) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void renderedPlacementUsesNativeTextMetrics() throws Exception {
        for (boolean landscape : new boolean[]{false, true}) for (boolean rtl : new boolean[]{false, true}) {
            configure(landscape, rtl, 1); show(EXPLANATION); assertAdjacent();
            render((landscape ? "landscape" : "portrait") + (rtl ? "-rtl" : "-ltr"));
        }
        configure(false, false, 1); show(lines(18)); assertCenteredBeside(); render("side-centered");
        configure(false, true, 2); show(lines(80)); assertCenteredBeside();
        assertNavigationReachable(); render("large-font-scrolled");
    }
    private void render(String name) throws Exception {
        File directory = new File("build/reports/tutorial-placement");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            root.draw(new Canvas(bitmap)); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            assertEquals("Highlight must remain visible in the rendered overlay", Color.rgb(36, 160, 100),
                    bitmap.getPixel(target.getLeft() + target.getWidth() / 2, target.getTop() + target.getHeight() / 2));
        } finally { bitmap.recycle(); }
    }
}
