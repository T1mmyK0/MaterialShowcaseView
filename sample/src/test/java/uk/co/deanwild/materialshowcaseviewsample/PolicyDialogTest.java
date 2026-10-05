package uk.co.deanwild.materialshowcaseviewsample;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.*;
import android.widget.Button;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import uk.co.deanwild.materialshowcaseview.session.TutorialSession;
import uk.co.deanwild.materialshowcaseview.session.SharedPreferencesProgressStore;
import uk.co.deanwild.materialshowcaseview.session.ProgressStore;
import uk.co.deanwild.materialshowcaseview.session.Tutorial;
import uk.co.deanwild.materialshowcaseview.session.Step;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 30)
public class PolicyDialogTest {
    ActivityController<AiTutorialActivity> controller; AiTutorialActivity activity;
    @Before public void setup() {
        controller = Robolectric.buildActivity(AiTutorialActivity.class).setup().visible(); activity = controller.get();
    }
    @After public void cleanup() { controller.pause().stop().destroy(); }
    AlertDialog open() {
        Button provider = button(activity.getWindow().getDecorView(), "Gemini"); assertNotNull(provider); provider.performClick();
        return ShadowAlertDialog.getLatestAlertDialog();
    }
    Button button(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { Button result = button(((ViewGroup) view).getChildAt(i), text); if (result != null) return result; }
        return null;
    }
    TutorialSession session() { return ReflectionHelpers.getField(activity, "gemini"); }
    void assertCancelled() { Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals(TutorialSession.State.CANCELLED, session().getState()); assertFalse(session().isActive()); }
    @Test public void negativeButtonCancels() { open().getButton(DialogInterface.BUTTON_NEGATIVE).performClick(); assertCancelled(); }
    @Test public void backCancels() {
        // Exercise the dialog's framework Back callback; real key dispatch is also checked on-device.
        open().onBackPressed(); assertCancelled();
    }
    @Test public void outsideTouchCancels() {
        AlertDialog dialog = open(); MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -100, -100, 0);
        try { dialog.onTouchEvent(event); } finally { event.recycle(); } assertCancelled();
    }
    @Test public void programmaticDismissalDoesNotAccept() { open().dismiss(); assertCancelled(); }
    @Test public void acceptingReleasesGateWithoutCancellingSession() {
        open().getButton(DialogInterface.BUTTON_POSITIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(session().isActive()); assertNotEquals(TutorialSession.State.CANCELLED, session().getState());
    }
    @Test public void recreationCannotBypassUnacceptedPolicy() {
        AlertDialog original = open(); controller.recreate(); activity = controller.get();
        AlertDialog restored = ShadowAlertDialog.getLatestAlertDialog();
        assertNotSame(original, restored); assertTrue(restored.isShowing());
        assertFalse("The destroyed activity must release its policy window", original.isShowing());
        restored.cancel(); assertCancelled();
    }
    @Test public void dismissedPolicyStillRequiresAcceptanceAfterRecreation() {
        AlertDialog original = open(); original.cancel(); assertCancelled();
        controller.recreate(); activity = controller.get();
        AlertDialog restored = ShadowAlertDialog.getLatestAlertDialog();
        assertNotSame(original, restored); assertTrue(restored.isShowing());
        restored.getButton(DialogInterface.BUTTON_POSITIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(session().isActive());
    }
    @Test public void acceptedPolicyDoesNotReappearAfterRecreation() {
        AlertDialog original = open(); original.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle(); controller.recreate(); activity = controller.get();
        assertSame(original, ShadowAlertDialog.getLatestAlertDialog()); assertFalse(original.isShowing()); assertTrue(session().isActive());
    }

    @Test public void resetCannotBypassDismissedPolicy() {
        assertStartControlRequiresPolicy("Reset selected progress");
    }

    @Test public void replayCannotBypassDismissedPolicy() {
        assertStartControlRequiresPolicy("Replay selected tutorial");
    }

    @Test public void targetTestCannotBypassDismissedPolicy() {
        assertStartControlRequiresPolicy("Start blocked tutorial for target tests");
    }

    private void assertStartControlRequiresPolicy(String label) {
        AlertDialog original = open(); original.cancel(); assertCancelled();
        button(activity.getWindow().getDecorView(), label).performClick();
        AlertDialog next = ShadowAlertDialog.getLatestAlertDialog();
        assertNotSame(original, next); assertTrue(next.isShowing());
        next.cancel(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        uk.co.deanwild.materialshowcaseview.session.TutorialCoordinator coordinator = ReflectionHelpers.getField(activity, "aiGroup");
        assertEquals(0, coordinator.pendingCount());
    }

    @Test public void unsupportedProviderControlsCannotResetGeminiProgress() {
        SharedPreferencesProgressStore store = new SharedPreferencesProgressStore(activity);
        Tutorial tutorial = new Tutorial("sc_gemini", 1, Step.builder("prompt").build());
        ProgressStore.Progress before = store.load(tutorial);
        assertTrue(store.save(tutorial.id, before.revision, new ProgressStore.Progress(before.revision + 1,
                1, java.util.Collections.singleton("prompt"), java.util.Collections.emptySet(), ProgressStore.Outcome.COMPLETED)));
        button(activity.getWindow().getDecorView(), "Other").performClick();
        for (String label : new String[]{"Reset selected progress", "Replay selected tutorial", "Start blocked tutorial for target tests"})
            button(activity.getWindow().getDecorView(), label).performClick();
        assertEquals(ProgressStore.Outcome.COMPLETED, store.load(tutorial).outcome);
        assertFalse(session().isActive());
    }

    @Test public void selectingOtherReleasesActiveReplay() {
        open().getButton(DialogInterface.BUTTON_POSITIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        button(activity.getWindow().getDecorView(), "Replay selected tutorial").performClick();
        button(activity.getWindow().getDecorView(), "Other").performClick();
        uk.co.deanwild.materialshowcaseview.session.TutorialCoordinator coordinator = ReflectionHelpers.getField(activity, "aiGroup");
        assertEquals(0, coordinator.pendingCount());
    }

    @Test public void changingProviderClosesThePreviousPolicyDialog() {
        AlertDialog original = open();
        button(activity.getWindow().getDecorView(), "ChatGPT").performClick();
        AlertDialog replacement = ShadowAlertDialog.getLatestAlertDialog();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotSame(original, replacement);
        assertFalse("An obsolete provider policy window remains attached", original.isShowing());
        assertTrue(replacement.isShowing());
        replacement.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        TutorialSession gpt = ReflectionHelpers.getField(activity, "gpt");
        assertTrue(gpt.isActive());
    }
}
