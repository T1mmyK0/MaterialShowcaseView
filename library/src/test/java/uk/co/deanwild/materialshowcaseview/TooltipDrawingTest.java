package uk.co.deanwild.materialshowcaseview;

import android.app.Activity;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Checks the drawn arrow, independently of the tooltip's outer layout bounds. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = {24, 28, 30})
public class TooltipDrawingTest {
    private ActivityController<Activity> controller;
    private ShowcaseTooltip.TooltipView bubble;
    private final Rect anchor = new Rect(300, 300, 340, 380);

    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        FrameLayout root = new FrameLayout(activity); activity.setContentView(root);
        bubble = new ShowcaseTooltip.TooltipView(activity);
        bubble.setCorner(0);
        bubble.setTooltipAnimation(new ShowcaseTooltip.TooltipAnimation() {
            public void animateEnter(View view, android.animation.Animator.AnimatorListener listener) { }
            public void animateExit(View view, android.animation.Animator.AnimatorListener listener) { }
        });
        root.addView(bubble, new FrameLayout.LayoutParams(180, 160));
        bubble.layout(0, 0, 180, 160);
    }

    @After public void cleanup() { bubble.removeNow(); controller.pause().stop().destroy(); }

    private boolean pathContains(float x, float y) {
        Path path = ReflectionHelpers.getField(bubble, "bubblePath");
        return Shadows.shadowOf(path).getPoints().stream()
                .anyMatch(point -> Math.abs(point.getX() - x) < .01f && Math.abs(point.getY() - y) < .01f);
    }

    @Test public void sideArrowsPointAtTargetForEveryAlignment() {
        for (ShowcaseTooltip.Position position : new ShowcaseTooltip.Position[]{ShowcaseTooltip.Position.LEFT, ShowcaseTooltip.Position.RIGHT}) {
            for (ShowcaseTooltip.ALIGN align : ShowcaseTooltip.ALIGN.values()) {
                bubble.setPosition(position); bubble.setAlign(align); bubble.setup(anchor, 800);
                float tipX = position == ShowcaseTooltip.Position.LEFT ? bubble.getWidth() : 0;
                assertTrue(position + "/" + align + " arrow does not point to the target center",
                        pathContains(tipX, anchor.exactCenterY() - bubble.getY()));
            }
        }
    }

    @Test public void sideArrowDepthUsesConfiguredHeight() {
        bubble.setArrowHeight(45);
        for (ShowcaseTooltip.Position position : new ShowcaseTooltip.Position[]{ShowcaseTooltip.Position.LEFT, ShowcaseTooltip.Position.RIGHT}) {
            bubble.setPosition(position); bubble.setup(anchor, 800);
            float baseX = position == ShowcaseTooltip.Position.LEFT ? bubble.getWidth() - 45 : 45;
            assertTrue(position + " ignores arrowHeight", pathContains(baseX, bubble.getHeight() / 2f - bubble.getArrowWidth()));
        }
    }

    @Test public void changingArrowWidthUpdatesAlreadyDisplayedPath() {
        bubble.setup(anchor, 800);
        bubble.setArrowWidth(25);
        assertTrue("Arrow width change left the displayed path stale",
                pathContains(anchor.exactCenterX() - bubble.getX() - 25, bubble.getArrowHeight()));
    }

    @Test public void changingArrowMarginsUpdatesAlreadyDisplayedPath() {
        bubble.setup(anchor, 800);
        bubble.setArrowSourceMargin(12); bubble.setArrowTargetMargin(8);
        float center = anchor.exactCenterX() - bubble.getX();
        assertTrue("Arrow source margin left the displayed path stale",
                pathContains(center + 12 - bubble.getArrowWidth(), bubble.getArrowHeight()));
        assertTrue("Arrow target margin left the displayed path stale", pathContains(center + 8, 0));
    }

    @Test public void changingCornersUpdatesAlreadyDisplayedPath() {
        bubble.setup(anchor, 800);
        bubble.setCorner(40);
        assertTrue("Corner change left the displayed path stale", pathContains(50, bubble.getArrowHeight()));
        assertFalse("The obsolete square corner remains in the path", pathContains(30, bubble.getArrowHeight()));
    }
}
