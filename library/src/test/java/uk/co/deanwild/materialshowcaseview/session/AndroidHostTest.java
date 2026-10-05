package uk.co.deanwild.materialshowcaseview.session;

import android.app.Activity;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = {24, 28})
public class AndroidHostTest {
    ActivityController<Activity> controller; Activity activity; FrameLayout root; Button target;
    TutorialHost.Actions actions = new TutorialHost.Actions() {
        public void next() { } public void previous() { } public void skipStep() { }
        public void skipTour() { } public void close() { } public void actionCompleted() { }
    };
    @Before public void setup() {
        controller=Robolectric.buildActivity(Activity.class).setup().visible();activity=controller.get();
        root=new FrameLayout(activity);target=new Button(activity);target.setText("Target");root.addView(target,new FrameLayout.LayoutParams(160,80));activity.setContentView(root);layout();
        controller.windowFocusChanged(true);
        // Manual layout in this fixture does not dispatch a WindowManager visibility event.
        Object attachInfo=org.robolectric.util.ReflectionHelpers.getField(activity.getWindow().getDecorView(), "mAttachInfo");
        org.robolectric.util.ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE);
    }
    void layout() { View decor=activity.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(470,View.MeasureSpec.EXACTLY));decor.layout(0,0,320,470); }
    void idle() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); }
    @After public void destroy() { controller.pause().stop().destroy(); }
    @Test public void observationBeforeAttachmentReleasesTransferredFocusListener() {
        FrameLayout pendingRoot = new FrameLayout(activity);
        ViewTreeObserver windowTree = root.getViewTreeObserver();
        java.util.List<?> before = org.robolectric.util.ReflectionHelpers.getField(windowTree, "mOnWindowFocusListeners");
        int baseline = before == null ? 0 : before.size();
        AndroidTutorialHost host = new AndroidTutorialHost(pendingRoot, id -> null);
        Cancellation observation = host.observe(() -> { });
        try {
            root.addView(pendingRoot, new FrameLayout.LayoutParams(-1, -1)); layout();
            java.util.List<?> attached = org.robolectric.util.ReflectionHelpers.getField(windowTree, "mOnWindowFocusListeners");
            assertEquals(baseline + 1, attached.size());
            observation.cancel();
            assertEquals("Cancellation must remove focus listeners merged into the window tree", baseline, attached.size());
        } finally { observation.cancel(); host.cancel(); root.removeView(pendingRoot); }
    }

    @Test public void overlayRestoresAccessibilityAndFocusOnCancel() {
        target.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);target.setFocusableInTouchMode(true);target.requestFocus();
        TutorialTheme theme=new TutorialTheme();theme.reducedMotion=true;
        TutorialOverlay overlay=new TutorialOverlay(root,()->target,Step.builder("a").content("Title","Body").build(),actions,theme);
        int[] shown={0};overlay.attach(()->shown[0]++);layout();idle();assertEquals(1,shown[0]);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,target.getImportantForAccessibility());
        assertEquals(2,countButtons(overlay));overlay.cancel();overlay.cancel();
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES,target.getImportantForAccessibility());assertTrue(target.hasFocus());assertEquals(1,root.getChildCount());
    }
    int countButtons(View view) { if(view instanceof Button) {assertTrue(view.isFocusable());return 1;} int count=0;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=countButtons(((ViewGroup)view).getChildAt(i));return count; }
    @Test public void cancelledEntranceNeverEmitsShown() {
        TutorialOverlay overlay=new TutorialOverlay(root,()->target,Step.builder("a").build(),actions,new TutorialTheme());int[] shown={0};overlay.attach(()->shown[0]++);overlay.cancel();idle();assertEquals(0,shown[0]);assertEquals(1,root.getChildCount());
    }
    @Test public void nonmodalHintPreservesBackgroundKeyboardFocus() {
        target.setFocusableInTouchMode(true); assertTrue(target.requestFocus());
        TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true;
        TutorialOverlay overlay = new TutorialOverlay(root, () -> target,
                Step.builder("hint").interaction(Step.Interaction.HINT).content("Hint", "Keep typing").build(), actions, theme);
        try {
            overlay.attach(() -> { }); layout(); idle();
            assertTrue("A nonmodal hint stole focus from the app", target.hasFocus());
        } finally { overlay.cancel(); }
    }

    @Test public void cancellingHintDoesNotUndoUserFocusChange() {
        target.setFocusableInTouchMode(true); assertTrue(target.requestFocus());
        TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true;
        TutorialOverlay overlay = new TutorialOverlay(root, () -> target,
                Step.builder("hint").interaction(Step.Interaction.HINT).build(), actions, theme);
        overlay.attach(() -> { }); layout(); idle();
        Button other = new Button(activity); other.setFocusableInTouchMode(true);
        root.addView(other, 0, new FrameLayout.LayoutParams(100, 50));
        assertTrue(other.requestFocus()); overlay.cancel();
        assertTrue("Closing a hint must preserve the user's current focus", other.hasFocus());
    }
    @Test public void cancellingHostReleasesObserversWithoutCallerCleanup() {
        AndroidTutorialHost host = new AndroidTutorialHost(root, id -> target);
        host.setResumed(true); host.valid(Step.builder("a").target("a").build(), true);
        int[] changes = {0}; Cancellation observation = host.observe(() -> changes[0]++);
        host.cancel(); int before = changes[0];
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals("Disposed hosts must remove their framework callbacks", before, changes[0]);
        observation.cancel(); host.cancel();
    }

    @Test public void cancellingHostReleasesPendingPreparationAndIgnoresLateCompletion() {
        AndroidTutorialHost host = new AndroidTutorialHost(root, id -> target);
        host.setResumed(true);
        Runnable[] ready = {null}; int[] cancelled = {0}, completed = {0};
        host.setPreparation((step, scope, callback) -> { ready[0] = callback; return () -> cancelled[0]++; });
        Scope scope = new Scope();
        Cancellation preparation = host.prepare(Step.builder("a").target("a").build(), scope, () -> completed[0]++);
        host.cancel();
        assertEquals(1, cancelled[0]);
        ready[0].run(); root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(0, completed[0]);
        preparation.cancel(); scope.cancel(); assertEquals(1, cancelled[0]);
    }

    @Test public void hostCancellationReleasesApplicationResourcesAfterPreparationCompletes() {
        AndroidTutorialHost host = new AndroidTutorialHost(root, id -> target); host.setResumed(true);
        int[] released = {0}, complete = {0};
        host.setPreparation((step, scope, ready) -> {
            scope.own(() -> released[0]++); ready.run(); return Cancellation.NONE;
        });
        Scope phase = new Scope();
        host.prepare(Step.builder("a").target("a").build(), phase, () -> complete[0]++);
        for (int i = 0; i < 4; i++) root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(1, complete[0]); assertEquals("Application resources must survive into the visible step", 0, released[0]);
        host.cancel();
        assertEquals("Disposing the host must release all preparation-owned resources", 1, released[0]);
        assertFalse("Host cleanup must not cancel unrelated caller-owned resources", phase.isClosed());
        phase.cancel(); assertEquals(1, released[0]);
    }
    @Test public void zeroDurationExitCompletesExactlyOnce() {
        TutorialTheme theme=new TutorialTheme();theme.animationMillis=0;TutorialOverlay overlay=new TutorialOverlay(root,()->target,Step.builder("a").build(),actions,theme);overlay.attach(()->{});layout();idle();int[] hidden={0};overlay.hide(()->hidden[0]++);overlay.cancel();idle();assertEquals(1,hidden[0]);
    }
    @Test public void longFinalTargetIsRevealedAndPreparationReleasesObserver() {
        root.removeAllViews();ScrollView scroll=new ScrollView(activity);LinearLayout list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        TextView large=new TextView(activity);large.setText("Long prompt");list.addView(large,new LinearLayout.LayoutParams(-1,2400));list.addView(target,new LinearLayout.LayoutParams(160,80));layout();
        Step step=Step.builder("api-key").target("key").build();AndroidTutorialHost host=new AndroidTutorialHost(activity.getWindow(),id->target);host.setResumed(true);
        assertFalse(host.valid(step,true));Scope scope=new Scope();int[] ready={0};scope.own(host.prepare(step,scope,()->ready[0]++));
        for(int i=0;i<5;i++){layout();root.getViewTreeObserver().dispatchOnPreDraw();}
        assertTrue("scroll="+scroll.getScrollY(),scroll.getScrollY()>0);android.graphics.Rect vr=new android.graphics.Rect(),wr=new android.graphics.Rect(),rr=new android.graphics.Rect(); target.getGlobalVisibleRect(vr);root.getWindowVisibleDisplayFrame(wr);root.getGlobalVisibleRect(rr); assertTrue("target="+vr+" window="+wr+" root="+rr+" size="+target.getWidth()+"x"+target.getHeight()+" scroll="+scroll.getScrollY(),host.valid(step,true));assertEquals("preparation completions",1,ready[0]);scope.cancel();root.getViewTreeObserver().dispatchOnPreDraw();assertEquals(1,ready[0]);host.cancel();
    }
    @Test public void oversizedTargetDoesNotWaitForever() {
        root.removeAllViews();target.setText("Oversized");root.addView(target,new FrameLayout.LayoutParams(600,2000));layout();
        assertTrue(TargetValidator.valid(target,root,Step.builder("a").build(),true));
    }
    @Test public void modalBackgroundDoesNotLeakClickToTarget() {
        int[] clicks={0};target.setOnClickListener(v->clicks[0]++);TutorialTheme theme=new TutorialTheme();theme.reducedMotion=true;
        TutorialOverlay overlay=new TutorialOverlay(root,()->target,Step.builder("a").build(),actions,theme);overlay.attach(()->{});layout();idle();
        MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,10,10,0),up=MotionEvent.obtain(0,10,MotionEvent.ACTION_UP,10,10,0);
        root.dispatchTouchEvent(down);root.dispatchTouchEvent(up);down.recycle();up.recycle();assertEquals(0,clicks[0]);overlay.cancel();
    }
    @Test public void animationGeometryAndEnabledChangesInvalidateWithoutLayout() {
        AndroidTutorialHost host=new AndroidTutorialHost(activity.getWindow(),id->target);host.setResumed(true);Step step=Step.builder("a").target("a").requireEnabled(true).build();host.valid(step,true);
        int[] changes={0};Cancellation observer=host.observe(()->changes[0]++);root.getViewTreeObserver().dispatchOnPreDraw();int before=changes[0];
        target.setTranslationY(20);root.getViewTreeObserver().dispatchOnPreDraw();assertTrue(changes[0]>before);before=changes[0];target.setEnabled(false);root.getViewTreeObserver().dispatchOnPreDraw();assertTrue(changes[0]>before);assertEquals(TargetValidator.Reason.DISABLED,TargetValidator.diagnose(target,root,step,true));observer.cancel();before=changes[0];target.setTranslationY(40);root.getViewTreeObserver().dispatchOnPreDraw();assertEquals(before,changes[0]);host.cancel();
    }
    @Test public void dialogHostUsesSameScreenCoordinatesAsItsTarget() {
        android.app.Dialog dialog=new android.app.Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);Button button=new Button(activity);button.setText("Dialog target");dialog.setContentView(button);dialog.show();idle();
        View decor=dialog.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(180,View.MeasureSpec.EXACTLY));decor.layout(0,0,240,180);
        android.graphics.Rect visible=new android.graphics.Rect();assertTrue("dialog target size="+button.getWidth()+"x"+button.getHeight()+" parent="+button.getParent(),TargetGeometry.visibleOnScreen(button,visible));int[] location=new int[2];button.getLocationOnScreen(location);assertEquals(location[0],visible.left);assertEquals(location[1],visible.top);
        assertEquals(TargetValidator.Reason.OTHER_WINDOW,TargetValidator.diagnose(button,root,Step.builder("a").build(),true));dialog.dismiss();
    }

    @Test public void panelAvoidsAdditionalHighlightedTargets() {
        Button extra = new Button(activity);
        FrameLayout.LayoutParams extraParams = new FrameLayout.LayoutParams(160, 80); extraParams.topMargin = 100;
        root.addView(extra, extraParams); layout();
        TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true;
        TutorialOverlay overlay = new TutorialOverlay(root, () -> target, Step.builder("a").build(), actions, theme);
        overlay.additionalTargets = () -> java.util.Collections.singletonList(extra);
        overlay.attach(() -> { }); layout(); idle(); overlay.reposition(); layout();
        View panel = overlay.getChildAt(0);
        assertTrue("Panel overlaps the extra highlight", panel.getTop() >= extra.getBottom());
        assertTrue(panel.getBottom() <= overlay.getHeight()); overlay.cancel();
    }

    @Test public void informationalHighlightDoesNotNeedPrimaryTargetsClickability() {
        TextView label = new TextView(activity); label.setText("Explanation"); label.setEnabled(false);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(160, 50); params.topMargin = 110;
        root.addView(label, params); layout();
        AndroidTutorialHost host = new AndroidTutorialHost(root, id -> id.equals("button") ? target : label);
        Step step = Step.builder("a").target("button").highlight("label")
                .requireEnabled(true).requireClickable(true).build();
        try {
            assertTrue("Informational highlights only need to be visible: " + host.diagnostic(), host.valid(step, true));
            target.setEnabled(false); assertFalse(host.valid(step, true)); target.setEnabled(true);
            target.setClickable(false); assertFalse(host.valid(step, true)); target.setClickable(true);
            label.setVisibility(View.INVISIBLE); assertFalse(host.valid(step, true));
            root.removeView(label); assertFalse(host.valid(step, true));
        } finally { host.cancel(); }
    }

    @Test public void oversizedHighlightIsClippedToVisibleRegion() {
        FrameLayout clipping = new FrameLayout(activity);
        root.removeView(target); clipping.addView(target, new FrameLayout.LayoutParams(600, 2000));
        root.addView(clipping, new FrameLayout.LayoutParams(200, 120)); layout();
        java.util.List<android.graphics.RectF> holes = new java.util.ArrayList<>();
        TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; theme.paddingDp = 0;
        theme.highlightShape = (path, bounds) -> { holes.add(new android.graphics.RectF(bounds)); path.addRect(bounds, android.graphics.Path.Direction.CW); };
        TutorialOverlay overlay = new TutorialOverlay(root, () -> target, Step.builder("a").build(), actions, theme);
        overlay.attach(() -> { }); layout(); idle(); holes.clear(); overlay.reposition();
        assertEquals(1, holes.size());
        assertEquals(200f, holes.get(0).width(), 0f); assertEquals(120f, holes.get(0).height(), 0f);
        overlay.cancel();
    }

    @Test public void previousButtonReturnsToCompletedStepAfterResume() {
        Tutorial tutorial = new Tutorial("resume-button", 1, Step.builder("first").build(), Step.builder("second").build());
        MemoryProgressStore store = new MemoryProgressStore();
        assertTrue(store.save(tutorial.id, 0, new ProgressStore.Progress(1, 1,
                java.util.Collections.singleton("first"), java.util.Collections.emptySet(), ProgressStore.Outcome.ACTIVE, "second")));
        AndroidTutorialHost host = new AndroidTutorialHost(activity.getWindow(), id -> target);
        TutorialTheme theme = new TutorialTheme(); theme.reducedMotion = true; theme.showPrevious = true; theme.previous = "Previous";
        host.setTheme(theme); host.setResumed(true);
        TutorialCoordinator coordinator = new TutorialCoordinator();
        TutorialSession session = new TutorialSession(tutorial, host, new MainThreadScheduler(), store,
                coordinator, TutorialCoordinator.Conflict.QUEUE);
        try {
            session.start(); settleSession();
            assertEquals("second", session.step().id); assertEquals(TutorialSession.State.SHOWING, session.getState());
            Button previous = findButton(activity.getWindow().getDecorView(), "Previous");
            assertNotNull(previous); assertTrue(previous.isEnabled()); assertTrue(previous.performClick());
            settleSession();
            assertEquals("first", session.step().id); assertEquals(TutorialSession.State.SHOWING, session.getState());
            assertEquals(java.util.Collections.singleton("first"), store.load(tutorial).completed);
        } finally { session.dispose(); coordinator.cancel(); }
    }

    private void settleSession() {
        for (int i = 0; i < 6; i++) { idle(); layout(); root.getViewTreeObserver().dispatchOnPreDraw(); }
    }

    private Button findButton(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = findButton(((ViewGroup) view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }
}
