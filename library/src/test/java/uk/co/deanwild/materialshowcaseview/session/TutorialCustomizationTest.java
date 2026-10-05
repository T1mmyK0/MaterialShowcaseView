package uk.co.deanwild.materialshowcaseview.session;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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

@RunWith(RobolectricTestRunner.class) @Config(sdk = {24, 28, 30}, qualifiers = "w400dp-h640dp-mdpi")
public class TutorialCustomizationTest {
    ActivityController<Activity> controller;
    Activity activity;
    FrameLayout root;
    Button target;
    AndroidTutorialHost host;
    TutorialHost.Actions actions = new TutorialHost.Actions() {
        public void next() { } public void previous() { } public void skipStep() { }
        public void skipTour() { } public void close() { } public void actionCompleted() { }
    };
    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible(); activity = controller.get();
        root = new FrameLayout(activity); target = new Button(activity); target.setText("Target");
        root.addView(target, new FrameLayout.LayoutParams(120, 60)); activity.setContentView(root);
        layout(); controller.windowFocusChanged(true);
        Object info = ReflectionHelpers.getField(activity.getWindow().getDecorView(), "mAttachInfo");
        ReflectionHelpers.setField(info, "mWindowVisibility", View.VISIBLE);
        host = new AndroidTutorialHost(root, id -> target); host.setResumed(true);
    }
    @After public void cleanup() { host.cancel(); controller.pause().stop().destroy(); }
    void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 400, 640);
    }
    void settle() {
        for (int i = 0; i < 8; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
            layout(); root.getViewTreeObserver().dispatchOnPreDraw();
        }
    }
    Button button(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = button(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    int fill(Button button) {
        return ((GradientDrawable) ((RippleDrawable) button.getBackground()).getDrawable(0)).getColor().getDefaultColor();
    }
    @Test public void primaryColorsSurviveHostThemeCopyWithoutChangingFlatButtons() {
        for (Step.Interaction interaction : new Step.Interaction[]{Step.Interaction.NEXT, Step.Interaction.TARGET_ACTION}) {
            TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; theme.textColor = Color.CYAN;
            theme.primaryButtonColor = Color.rgb(37, 99, 235); theme.primaryButtonTextColor = Color.WHITE;
            host.setTheme(theme);
            Cancellation shown = host.show(Step.builder("colors").target("target").interaction(interaction).build(), actions, () -> { });
            try {
                settle();
                String label = activity.getString(interaction == Step.Interaction.NEXT
                        ? uk.co.deanwild.materialshowcaseview.R.string.showcase_next : uk.co.deanwild.materialshowcaseview.R.string.showcase_target_action);
                Button primary = button(root, label), skip = button(root, "Skip"); assertNotNull(primary); assertNotNull(skip);
                assertEquals((int) theme.primaryButtonColor, fill(primary)); assertEquals(Color.WHITE, primary.getCurrentTextColor());
                assertEquals(Color.TRANSPARENT, fill(skip)); assertEquals(Color.CYAN, skip.getCurrentTextColor());
                theme.primaryButtonColor = Color.RED; theme.primaryButtonTextColor = Color.BLACK;
                settle(); assertEquals(Color.rgb(37, 99, 235), fill(primary)); assertEquals(Color.WHITE, primary.getCurrentTextColor());
            } finally { shown.cancel(); }
        }
    }
    @Test public void automaticPrimaryLabelContrastPreservesExplicitFillAlphaAndDefaults() {
        Integer[] fills = {null, Color.rgb(37, 99, 235), 0x20ffffff, Color.TRANSPARENT};
        for (Integer color : fills) {
            TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; theme.primaryButtonColor = color;
            host.setTheme(theme);
            Cancellation shown = host.show(Step.builder("contrast").build(), actions, () -> { });
            try {
                settle(); Button next = button(root, "Next"); assertNotNull(next);
                assertEquals(color == null ? Color.WHITE : (int) color, fill(next));
                assertEquals(color == null ? theme.maskColor | 0xff000000 : Color.WHITE, next.getCurrentTextColor());
            } finally { shown.cancel(); }
        }
    }
    @Test public void explicitPrimaryLabelColorTakesPrecedenceOverGeneralTextAppearance() {
        TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true;
        theme.buttonTextAppearance = android.R.style.TextAppearance_Material_Widget_Button;
        theme.primaryButtonColor = Color.BLUE; theme.primaryButtonTextColor = Color.YELLOW; host.setTheme(theme);
        Cancellation shown = host.show(Step.builder("appearance").build(), actions, () -> { });
        try { settle(); assertEquals(Color.YELLOW, button(root, "Next").getCurrentTextColor()); }
        finally { shown.cancel(); }
    }
    @Test public void configuredScrollMarginKeepsTheRevealedTargetAwayFromViewportEdges() {
        root.removeView(target);
        ScrollView scroll = new ScrollView(activity); LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content);
        content.addView(new View(activity), new LinearLayout.LayoutParams(-1, 900));
        content.addView(target, new LinearLayout.LayoutParams(120, 60));
        content.addView(new View(activity), new LinearLayout.LayoutParams(-1, 900));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1)); layout();
        host.setAlignment(AndroidTutorialHost.Alignment.NEAREST, 48);
        Step step = Step.builder("scroll").target("target").build(); Scope scope = new Scope(); int[] ready = {0};
        scope.own(host.prepare(step, scope, () -> ready[0]++));
        try {
            settle(); assertEquals(1, ready[0]); assertTrue(scroll.getScrollY() > 0); assertTrue(host.valid(step, true));
            Rect viewport = new Rect(), bounds = new Rect(); TargetGeometry.usableOnScreen(scroll, viewport);
            TargetGeometry.boundsOnScreen(target, bounds);
            assertTrue(bounds.top >= viewport.top + 48); assertTrue(bounds.bottom <= viewport.bottom - 48);
        } finally { scope.cancel(); }
    }
}
