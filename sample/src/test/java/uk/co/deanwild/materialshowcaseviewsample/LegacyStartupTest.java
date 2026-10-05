package uk.co.deanwild.materialshowcaseviewsample;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.time.Duration;
import uk.co.deanwild.materialshowcaseview.MaterialShowcaseView;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 28)
public class LegacyStartupTest {
    @Test public void singleExampleStartsOnFirstVisit() { assertStarts(SimpleSingleExample.class); }
    @Test public void customExampleStartsOnFirstVisit() { assertStarts(CustomExample.class); }
    @Test public void sequenceExampleStartsOnFirstVisit() { assertStarts(SequenceExample.class); }
    @Test public void tooltipExampleStartsOnFirstVisit() { assertStarts(TooltipExample.class); }

    private <T extends SampleActivity> void assertStarts(Class<T> type) {
        ActivityController<T> controller = Robolectric.buildActivity(type).setup().visible();
        try {
            ViewGroup decor = (ViewGroup) controller.get().getWindow().getDecorView();
            for (int i = 0; i < 5; i++) {
                decor.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
                decor.layout(0, 0, 600, 900);
                decor.getViewTreeObserver().dispatchOnPreDraw();
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            }
            MaterialShowcaseView showcase = null;
            for (int i = 0; i < decor.getChildCount(); i++)
                if (decor.getChildAt(i) instanceof MaterialShowcaseView) showcase = (MaterialShowcaseView) decor.getChildAt(i);
            assertNotNull("First visit must show a tutorial in " + type.getSimpleName(), showcase);
            assertEquals(View.VISIBLE, showcase.getVisibility());
            showcase.removeFromWindow();
        } finally { controller.pause().stop().destroy(); }
    }
}
