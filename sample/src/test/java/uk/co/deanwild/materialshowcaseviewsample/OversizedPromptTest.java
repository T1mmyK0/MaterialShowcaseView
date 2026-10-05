package uk.co.deanwild.materialshowcaseviewsample;

import android.graphics.Rect;
import android.os.Looper;
import android.view.*;
import android.widget.Button;
import android.widget.EditText;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import uk.co.deanwild.materialshowcaseview.session.TutorialSession;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = {24, 30}, qualifiers = "w400dp-h800dp-mdpi")
public class OversizedPromptTest {
    ActivityController<AiTutorialActivity> controller;
    AiTutorialActivity activity;
    @Before public void setup() {
        controller = Robolectric.buildActivity(AiTutorialActivity.class).setup().visible(); activity = controller.get();
        controller.windowFocusChanged(true);
        Object info = ReflectionHelpers.getField(activity.getWindow().getDecorView(), "mAttachInfo");
        ReflectionHelpers.setField(info, "mWindowVisibility", View.VISIBLE);
        settle();
    }
    @After public void cleanup() { controller.pause().stop().destroy(); }
    void settle() {
        View decor = activity.getWindow().getDecorView();
        for (int i = 0; i < 25; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
            decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 400, 800);
            decor.getViewTreeObserver().dispatchOnGlobalLayout(); decor.getViewTreeObserver().dispatchOnPreDraw();
        }
    }
    Button button(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = button(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    @Test public void longPromptShowsAndNextRevealsTheFollowingControl() {
        View decor = activity.getWindow().getDecorView();
        button(decor, "Long prompt").performClick();
        button(decor, "Reset selected progress").performClick(); settle();
        TutorialSession session = ReflectionHelpers.getField(activity, "gpt");
        EditText prompt = ReflectionHelpers.getField(activity, "prompt");
        assertTrue("Fixture must exceed the screen height", prompt.getHeight() > decor.getHeight());
        assertEquals("prompt", session.step().id); assertEquals(TutorialSession.State.SHOWING, session.getState());
        Button next = button(decor, "Next"); assertNotNull(next);
        Rect visible = new Rect(); assertTrue(describe(next), next.getGlobalVisibleRect(visible));
        next.performClick(); settle();
        assertEquals("default", session.step().id); assertEquals(TutorialSession.State.SHOWING, session.getState());
        View importPrompt = ReflectionHelpers.getField(activity, "importPrompt");
        assertTrue("The next control below the oversized prompt must be revealed", importPrompt.getGlobalVisibleRect(visible));
        assertTrue(visible.height() > 0); assertNotNull(button(decor, "Next"));
    }
    String describe(View view) {
        StringBuilder text = new StringBuilder();
        while (view != null) {
            text.append(view.getClass().getSimpleName()).append(" ").append(view.getLeft()).append(",").append(view.getTop())
                    .append(" ").append(view.getWidth()).append("x").append(view.getHeight())
                    .append(" scroll=").append(view.getScrollY()).append(" visibility=").append(view.getVisibility()).append("; ");
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return text.toString();
    }
}
