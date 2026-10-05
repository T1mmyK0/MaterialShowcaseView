package uk.co.deanwild.materialshowcaseview.session;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
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
@RunWith(RobolectricTestRunner.class) @Config(sdk = {24, 28, 30}, qualifiers = "w1000dp-h1000dp-mdpi")
public class LayoutContractMatrixTest {
    ActivityController<Activity> controller; Activity activity; FrameLayout root; Button target;
    int width, height;
    final String longText = String.join(" ", java.util.Collections.nCopies(80, "Long tutorial content"));
    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible(); activity = controller.get();
        activity.getApplicationInfo().flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
    }
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
                TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; theme.showClose = true; theme.close = "Close";
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
                    View close = findButton(content, "Close");
                    assertNotNull(close);
                    assertReachable(panel, close); close.performClick(); assertEquals(1, closed[0]);
                } finally { overlay.cancel(); }
            }
    }
    private Button findButton(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = findButton(((ViewGroup) view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }
    @Test public void defaultFooterGroupsSkipBesideNextWithSeparatedTouchTargets() {
        for (int windowWidth : new int[]{240, 320})
            for (boolean rtl : new boolean[]{false, true}) for (float font : new float[]{1, 2}) {
                configure(windowWidth, 480, rtl, font);
                TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true;
                int[] clicks = new int[2];
                TutorialHost.Actions actions = new TutorialHost.Actions() {
                    public void next() { clicks[1]++; } public void skipTour() { clicks[0]++; }
                    public void previous() { fail("Unexpected Previous action"); }
                    public void skipStep() { fail("Unexpected Skip step action"); }
                    public void close() { fail("Unexpected Close action"); }
                    public void actionCompleted() { }
                };
                TutorialOverlay overlay = new TutorialOverlay(root, () -> target,
                        Step.builder("footer").content("Title", "Body").build(), actions, theme);
                try {
                    overlay.attach(() -> { }); settle();
                    ScrollView panel = (ScrollView) overlay.getChildAt(0);
                    Button skip = findButton(panel, "Skip"), next = findButton(panel, "Next");
                    assertNotNull(skip); assertNotNull(next);
                    assertSame(skip.getParent(), next.getParent());
                    ViewGroup footer = (ViewGroup) skip.getParent();
                    assertEquals(2, footer.getChildCount());
                    assertNull(findButton(panel, "Previous"));
                    assertNull(findButton(panel, "Skip step"));
                    assertNull(findButton(panel, "Close"));
                    for (Button button : new Button[]{skip, next}) {
                        assertTrue(button.getWidth() >= 48); assertTrue(button.getHeight() >= 48);
                        assertTrue(button.getLeft() >= 0 && button.getRight() <= footer.getWidth());
                    }
                    if (skip.getTop() == next.getTop()) {
                        assertEquals(skip.getTop() + skip.getHeight() / 2, next.getTop() + next.getHeight() / 2);
                        assertEquals(rtl ? 0 : footer.getWidth(), rtl ? next.getLeft() : next.getRight());
                        assertEquals(8, rtl ? skip.getLeft() - next.getRight() : next.getLeft() - skip.getRight());
                    } else {
                        assertTrue(next.getTop() >= skip.getBottom());
                        assertEquals(rtl ? 0 : footer.getWidth(), rtl ? skip.getLeft() : skip.getRight());
                        assertEquals(rtl ? 0 : footer.getWidth(), rtl ? next.getLeft() : next.getRight());
                    }
                    skip.performClick(); next.performClick(); assertArrayEquals(new int[]{1, 1}, clicks);
                } finally { overlay.cancel(); }
            }
    }
    @Test public void previousLeadsTheMainRowWhileSkipAndNextStayTogetherWhenWrapping() {
        for (int windowWidth : new int[]{240, 360})
            for (boolean rtl : new boolean[]{false, true}) for (float font : new float[]{1, 2}) {
                configure(windowWidth, 640, rtl, font);
                TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; theme.showPrevious = true;
                TutorialOverlay overlay = new TutorialOverlay(root, () -> target,
                        Step.builder("navigation").content("Title", "Body").build(), noActions(), theme);
                try {
                    overlay.attach(() -> { }); settle();
                    Button previous = findButton(overlay, "Previous"), skip = findButton(overlay, "Skip"), next = findButton(overlay, "Next");
                    ViewGroup footer = (ViewGroup) previous.getParent();
                    assertSame(footer, skip.getParent()); assertSame(footer, next.getParent());
                    assertEquals(rtl ? footer.getWidth() : 0, rtl ? previous.getRight() : previous.getLeft());
                    assertEquals(rtl ? 0 : footer.getWidth(), rtl ? next.getLeft() : next.getRight());
                    if (skip.getWidth() + 8 + next.getWidth() <= footer.getWidth()) {
                        assertEquals(skip.getTop() + skip.getHeight() / 2, next.getTop() + next.getHeight() / 2);
                        assertEquals(8, rtl ? skip.getLeft() - next.getRight() : next.getLeft() - skip.getRight());
                        if (previous.getWidth() + skip.getWidth() + next.getWidth() + 16 <= footer.getWidth()) {
                            assertEquals(previous.getTop() + previous.getHeight() / 2, next.getTop() + next.getHeight() / 2);
                            assertTrue(rtl ? skip.getRight() < previous.getLeft() : previous.getRight() < skip.getLeft());
                        } else assertTrue(skip.getTop() >= previous.getBottom());
                    } else assertTrue(next.getTop() >= skip.getBottom());
                } finally { overlay.cancel(); }
            }
    }
    @Test public void longNavigationLabelsRemainFullyReachableAndActivateInNarrowRtlWindow() {
        configure(240, 320, true, 2);
        TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; theme.showSkipStep = true;
        theme.showPrevious = true; theme.showClose = true;
        String[] labels = {"Return to the previous tutorial step", "Continue to the next tutorial step",
                "Skip this tutorial step", "Skip the entire tutorial", "Close this tutorial"};
        theme.previous = labels[0]; theme.next = labels[1]; theme.skipStep = labels[2];
        theme.skipTour = labels[3]; theme.close = labels[4];
        int[] clicks = new int[5];
        TutorialHost.Actions actions = new TutorialHost.Actions() {
            public void previous() { clicks[0]++; } public void next() { clicks[1]++; }
            public void skipStep() { clicks[2]++; } public void skipTour() { clicks[3]++; }
            public void close() { clicks[4]++; } public void actionCompleted() { }
        };
        TutorialOverlay overlay = new TutorialOverlay(root, () -> target,
                Step.builder("actions").content("Title", "Body").build(), actions, theme);
        try {
            overlay.attach(() -> { }); settle();
            ScrollView panel = (ScrollView) overlay.getChildAt(0);
            for (int i = 0; i < labels.length; i++) {
                Button button = findButton(panel, labels[i]); assertNotNull(button);
                assertTrue(button.isFocusable()); assertTrue(button.getHeight() >= 48);
                Rect bounds = new Rect(); button.getDrawingRect(bounds);
                panel.offsetDescendantRectToMyCoords(button, bounds);
                assertTrue("Action clips horizontally: " + labels[i] + " " + bounds + " in " + panel.getWidth(),
                        bounds.left >= 0 && bounds.right <= panel.getWidth());
                button.requestRectangleOnScreen(new Rect(0, 0, button.getWidth(), Math.min(48, button.getHeight())), true);
                Rect visible = new Rect(); assertTrue(button.getGlobalVisibleRect(visible));
                button.performClick(); assertEquals(1, clicks[i]);
            }
        } finally { overlay.cancel(); }
    }
    @Test public void everyTextAndButtonCombinationCollapsesMissingContentAndKeepsActionsSeparate() {
        configure(320, 640, false, 1);
        for (int copy = 0; copy < 8; copy++) for (int controls = 0; controls < 32; controls++) {
            TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true;
            theme.showProgress = (copy & 4) != 0;
            theme.showPrevious = (controls & 1) != 0; theme.showNext = (controls & 2) != 0;
            theme.showSkipStep = (controls & 4) != 0; theme.showSkipTour = (controls & 8) != 0;
            theme.showClose = (controls & 16) != 0;
            TutorialOverlay overlay = new TutorialOverlay(root, () -> target,
                    Step.builder("combination").content((copy & 1) != 0 ? "Title" : "",
                            (copy & 2) != 0 ? "Helpful body text." : "").build(), noActions(), theme);
            try {
                overlay.setProgress(1, 4); overlay.attach(() -> { }); settle();
                ScrollView panel = (ScrollView) overlay.getChildAt(0);
                assertEquals("copy=" + copy + ", controls=" + controls,
                        copy == 0 && controls == 0 ? View.GONE : View.VISIBLE, panel.getVisibility());
                LinearLayout content = (LinearLayout) panel.getChildAt(0);
                java.util.List<Button> buttons = buttons(content);
                assertEquals(Integer.bitCount(controls), buttons.size());
                Button previous = findButton(content, "Previous"), skip = findButton(content, "Skip"), next = findButton(content, "Next");
                if (previous != null) assertEquals(0, previous.getLeft());
                if (next != null) assertEquals(((View) next.getParent()).getWidth(), next.getRight());
                if (skip != null) {
                    if (next == null) assertEquals(((View) skip.getParent()).getWidth(), skip.getRight());
                    else {
                        assertEquals(skip.getTop() + skip.getHeight() / 2, next.getTop() + next.getHeight() / 2);
                        assertEquals(8, next.getLeft() - skip.getRight());
                    }
                }
                if (panel.getVisibility() == View.GONE) continue;
                View first = null, last = null;
                for (int i = 0; i < content.getChildCount(); i++) {
                    View child = content.getChildAt(i);
                    if (child.getVisibility() == View.GONE) continue;
                    if (first == null) first = child;
                    last = child;
                }
                assertNotNull(first); assertNotNull(last);
                assertEquals("Empty content must not leave space above the first item", content.getPaddingTop(), first.getTop());
                assertEquals("Empty content must not leave space after the last item",
                        content.getHeight() - content.getPaddingBottom(), last.getBottom());
                java.util.List<Rect> bounds = new java.util.ArrayList<>();
                for (Button button : buttons) {
                    assertTrue(button.getWidth() >= 48); assertTrue(button.getHeight() >= 48);
                    assertTrue(button.getText().toString().trim().length() > 0);
                    Rect rect = new Rect(); button.getDrawingRect(rect); content.offsetDescendantRectToMyCoords(button, rect);
                    assertTrue(rect.left >= 0 && rect.right <= content.getWidth());
                    for (Rect other : bounds) assertFalse("Action touch areas overlap", Rect.intersects(rect, other));
                    bounds.add(rect);
                }
            } finally { overlay.cancel(); }
        }
    }
    @Test public void blankLabelsAndCopyLeaveOnlyTheHighlightAndBackgroundTap() {
        configure(320, 480, false, 1);
        TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; theme.showProgress = false;
        theme.showPrevious = true; theme.showSkipStep = true; theme.showClose = true;
        theme.previous = ""; theme.next = " "; theme.skipStep = "\t"; theme.skipTour = ""; theme.close = "\n";
        int[] advances = {0};
        TutorialHost.Actions actions = new TutorialHost.Actions() {
            public void next() { advances[0]++; } public void previous() { } public void skipStep() { }
            public void skipTour() { } public void close() { } public void actionCompleted() { }
        };
        TutorialOverlay overlay = new TutorialOverlay(root, () -> target,
                Step.builder("highlight-only").content(" ", "\t\n").interaction(Step.Interaction.BACKGROUND_TAP).build(), actions, theme);
        try {
            overlay.attach(() -> { }); settle();
            assertEquals(View.GONE, overlay.getChildAt(0).getVisibility()); assertTrue(buttons(overlay).isEmpty());
            MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 30, 30, 0);
            MotionEvent up = MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, 30, 30, 0);
            try { overlay.dispatchTouchEvent(down); overlay.dispatchTouchEvent(up); }
            finally { down.recycle(); up.recycle(); }
            assertEquals(1, advances[0]);
        } finally { overlay.cancel(); }
    }
    @Test public void progressOnlyCanAppearAndDisappearWithoutAnEmptyPanel() {
        configure(320, 480, true, 1);
        TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; theme.showNext = false; theme.showSkipTour = false;
        TutorialOverlay overlay = new TutorialOverlay(root, () -> target, Step.builder("progress-only").build(), noActions(), theme);
        try {
            overlay.attach(() -> { }); settle(); assertEquals(View.GONE, overlay.getChildAt(0).getVisibility());
            overlay.setProgress(1, 4); settle(); assertEquals(View.VISIBLE, overlay.getChildAt(0).getVisibility());
            overlay.setProgress(0, 0); settle(); assertEquals(View.GONE, overlay.getChildAt(0).getVisibility());
        } finally { overlay.cancel(); }
    }
    @Test public void unicodeWhitespaceDoesNotCreateBlankCopyOrButtons() {
        configure(320, 480, false, 1);
        TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; theme.showProgress = false;
        theme.showPrevious = true; theme.showSkipStep = true; theme.showClose = true;
        theme.previous = "\u2003"; theme.next = "\u00a0"; theme.skipStep = "\u202f";
        theme.skipTour = "\u2007"; theme.close = "\u3000";
        TutorialOverlay overlay = new TutorialOverlay(root, () -> target,
                Step.builder("blank").content("\u2003\u00a0", "\u3000\u202f").build(), noActions(), theme);
        try {
            overlay.attach(() -> { }); settle();
            assertTrue("Unicode spaces created empty action targets", buttons(overlay).isEmpty());
            assertEquals(View.GONE, overlay.getChildAt(0).getVisibility());
        } finally { overlay.cancel(); }
    }
    @Test public void highlightOnlyBackgroundTapSupportsKeyboardAndAccessibility() {
        configure(320, 480, false, 1);
        TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true;
        theme.showNext = false; theme.showSkipTour = false; theme.showProgress = false;
        int[] advances = {0};
        TutorialHost.Actions actions = new TutorialHost.Actions() {
            public void next() { advances[0]++; } public void previous() { } public void skipStep() { }
            public void skipTour() { } public void close() { } public void actionCompleted() { }
        };
        TutorialOverlay overlay = new TutorialOverlay(root, () -> target,
                Step.builder("keyboard").interaction(Step.Interaction.BACKGROUND_TAP).build(), actions, theme);
        try {
            overlay.attach(() -> { }); settle();
            assertTrue("A highlight-only step must receive keyboard focus", overlay.isFocused());
            android.view.accessibility.AccessibilityNodeInfo node = overlay.createAccessibilityNodeInfo();
            assertTrue("Background continuation must expose an accessibility click", node.isClickable());
            assertEquals(activity.getString(R.string.showcase_next), node.getContentDescription());
            assertTrue(overlay.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)));
            assertTrue(overlay.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)));
            settle(); assertEquals(1, advances[0]);
            assertTrue(overlay.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
            assertEquals(2, advances[0]);
        } finally { overlay.cancel(); }
    }
    @Test public void backgroundClickCannotBypassThePresentationCallbackGuard() {
        configure(320, 480, false, 1);
        TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true;
        boolean[] current = {true}; int[] advances = {0};
        TutorialHost.Actions actions = new TutorialHost.Actions() {
            public void next() { advances[0]++; } public void previous() { } public void skipStep() { }
            public void skipTour() { } public void close() { } public void actionCompleted() { }
        };
        TutorialOverlay overlay = new TutorialOverlay(root, () -> target,
                Step.builder("guard").interaction(Step.Interaction.BACKGROUND_TAP).build(), actions, theme,
                work -> { if (current[0]) work.run(); });
        try {
            overlay.attach(() -> { }); settle(); current[0] = false;
            overlay.performClick(); assertEquals("A stale accessibility click advanced the tutorial", 0, advances[0]);
        } finally { overlay.cancel(); }
    }
    @Test public void customContentPreservesMarginsAndCanHideWithoutLeavingAnEmptyPanel() {
        configure(320, 480, false, 1);
        TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true;
        theme.showNext = false; theme.showSkipTour = false; theme.showProgress = false;
        TextView custom = new TextView(activity); custom.setText("Application content");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = 13;
        custom.setLayoutParams(params); theme.contentFactory = (context, step) -> custom;
        TutorialOverlay overlay = new TutorialOverlay(root, () -> target, Step.builder("custom").build(), noActions(), theme);
        try {
            overlay.attach(() -> { }); settle();
            LinearLayout content = (LinearLayout) custom.getParent();
            assertEquals(content.getPaddingTop() + 13, custom.getTop());
            custom.setVisibility(View.GONE); settle(); assertEquals(View.GONE, overlay.getChildAt(0).getVisibility());
            custom.setVisibility(View.VISIBLE); settle(); assertEquals(View.VISIBLE, overlay.getChildAt(0).getVisibility());
            assertEquals(content.getPaddingTop() + 13, custom.getTop());
        } finally { overlay.cancel(); }
    }
    private TutorialHost.Actions noActions() {
        return new TutorialHost.Actions() {
            public void next() { } public void previous() { } public void skipStep() { }
            public void skipTour() { } public void close() { } public void actionCompleted() { }
        };
    }
    private java.util.List<Button> buttons(View view) {
        java.util.List<Button> found = new java.util.ArrayList<>();
        if (view instanceof Button) found.add((Button) view);
        else if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            found.addAll(buttons(((ViewGroup) view).getChildAt(i)));
        return found;
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
        // Keep the intrinsic size independent of the SDK's simulated text metrics.
        View content = new View(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                setMeasuredDimension(resolveSize(300, widthSpec), resolveSize(60, heightSpec));
            }
        };
        ShowcaseTooltip tooltip = ShowcaseTooltip.build(activity)
                .customView(content).position(ShowcaseTooltip.Position.LEFT);
        tooltip.configureTarget(root, target); ShowcaseTooltip.TooltipView bubble = tooltip.show(0);
        try {
            settle(); int narrow = bubble.getWidth();
            assertTrue("Fixture must actually constrain the initial bubble", bubble.getLayoutParams().width >= 0);
            target.setTranslationX(200); settle();
            assertTrue("Width remained " + bubble.getWidth() + " after constraint " + narrow
                    + "; params=" + bubble.getLayoutParams().width + "; target=" + target.getX()
                    + "; cap=" + org.robolectric.util.ReflectionHelpers.getField(bubble, "constrainedWidth"), bubble.getWidth() > narrow);
            assertEquals("Content did not recover its intrinsic width", 300, content.getWidth());
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, bubble.getLayoutParams().width);
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
