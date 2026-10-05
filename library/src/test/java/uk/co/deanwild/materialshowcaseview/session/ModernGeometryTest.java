package uk.co.deanwild.materialshowcaseview.session;

import android.app.Activity;
import android.graphics.Insets;
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
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 30, qualifiers = "w400dp-h800dp")
public class ModernGeometryTest {
    ActivityController<Activity> controller; Activity activity; InsetRoot root;
    TutorialSession session; AndroidTutorialHost host; TutorialCoordinator coordinator = new TutorialCoordinator();
    class InsetRoot extends FrameLayout {
        WindowInsets insets;
        InsetRoot() { super(activity); }
        @Override public WindowInsets getRootWindowInsets() { return insets; }
    }
    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible(); activity = controller.get();
        activity.getWindow().setDecorFitsSystemWindows(false);
        root = new InsetRoot(); activity.setContentView(root); layout(); controller.windowFocusChanged(true);
        Object info = org.robolectric.util.ReflectionHelpers.getField(activity.getWindow().getDecorView(), "mAttachInfo");
        org.robolectric.util.ReflectionHelpers.setField(info, "mWindowVisibility", View.VISIBLE);
        root.insets = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 30, 0, 20))
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(12, 0, 0, 0))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 260)).build();
    }
    void layout() { View decor = activity.getWindow().getDecorView(); decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)); decor.layout(0, 0, 400, 800); }
    void settle() { for (int i = 0; i < 10; i++) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); layout(); root.getViewTreeObserver().dispatchOnPreDraw(); } }
    @After public void cleanup() { if (session != null) session.dispose(); coordinator.cancel(); if (host != null) host.cancel(); controller.pause().stop().destroy(); }
    @Test public void keyboardCutoutAndBarsConstrainValidationAndPanelPlacement() {
        Button target = new Button(activity); FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(100, 60); lp.leftMargin = 30; lp.topMargin = 60; root.addView(target, lp); layout();
        Rect usable = new Rect(); assertTrue(TargetGeometry.usableOnScreen(root, usable));
        assertTrue(usable.left >= 12); assertTrue(usable.top >= 30); assertTrue(usable.bottom <= 540);
        Step step = Step.builder("a").target("a").build();
        assertTrue(TargetValidator.valid(target, root, step, true));
        target.setTranslationY(550); assertFalse(TargetValidator.valid(target, root, step, true)); target.setTranslationY(0);
        host = new AndroidTutorialHost(root, id -> target); host.setResumed(true); TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; host.setTheme(theme);
        session = new TutorialSession(new Tutorial("insets", 1, step), host, new MainThreadScheduler(), null, coordinator, TutorialCoordinator.Conflict.QUEUE);
        session.start(); settle(); assertEquals(TutorialSession.State.SHOWING, session.getState());
        TutorialOverlay overlay = (TutorialOverlay) root.getChildAt(root.getChildCount() - 1);
        View panel = overlay.getChildAt(0); int[] xy = new int[2]; panel.getLocationOnScreen(xy);
        assertTrue(xy[0] >= usable.left); assertTrue(xy[1] >= usable.top); assertTrue(xy[1] + panel.getHeight() <= usable.bottom);
    }
    @Test public void restoredApiKeyBelowLongPromptRevealsThroughNestedScrolling() {
        // Match the reference edge-to-edge editor: its inset listener keeps the scroll viewport
        // above the keyboard, including enough scroll range to reveal the final item.
        root.setPadding(12, 30, 0, 260);
        ScrollView outer = new ScrollView(activity); LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        outer.addView(content); root.addView(outer, new FrameLayout.LayoutParams(-1, -1));
        TextView prompt = new TextView(activity); prompt.setText("Long prompt"); content.addView(prompt, new LinearLayout.LayoutParams(-1, 1400));
        ScrollView inner = new ScrollView(activity); LinearLayout innerContent = new LinearLayout(activity); innerContent.setOrientation(LinearLayout.VERTICAL);
        inner.addView(innerContent); content.addView(inner, new LinearLayout.LayoutParams(-1, 300));
        innerContent.addView(new TextView(activity), new LinearLayout.LayoutParams(-1, 900));
        Button icon = new Button(activity); icon.setText("API key"); innerContent.addView(icon, new LinearLayout.LayoutParams(120, 60)); layout();
        Tutorial tutorial = new Tutorial("api-key-resume", 1, Step.builder("prompt").target("prompt").build(), Step.builder("api-key").target("key").build());
        MemoryProgressStore store = new MemoryProgressStore(); store.save(tutorial.id, 0, new ProgressStore.Progress(1, 1, Collections.singleton("prompt"), Collections.emptySet(), ProgressStore.Outcome.ACTIVE, "api-key"));
        host = new AndroidTutorialHost(root, id -> id.equals("key") ? icon : prompt); host.setResumed(true); TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; host.setTheme(theme);
        session = new TutorialSession(tutorial, host, new MainThreadScheduler(), store, coordinator, TutorialCoordinator.Conflict.QUEUE);
        session.start(); settle();
        assertEquals("api-key", session.step().id); assertEquals(host.diagnostic(), TutorialSession.State.SHOWING, session.getState());
        assertTrue(outer.getScrollY() > 0); assertTrue(inner.getScrollY() > 0); assertTrue(host.valid(session.step(), true));
    }
}
