package uk.co.deanwild.materialshowcaseview.session;

import android.graphics.Color;
import android.view.View;
import android.content.Context;
import uk.co.deanwild.materialshowcaseview.ShowcaseConfig;

/** Copied by each presentation. Labels may be loaded from application resources. Dimensions are dp/sp. */
public final class TutorialTheme {
    // Keep the original showcase palette; lifecycle support does not imply a new design.
    public int maskColor = Color.parseColor(ShowcaseConfig.DEFAULT_MASK_COLOUR);
    public int surfaceColor = Color.TRANSPARENT, textColor = Color.WHITE;
    /** ARGB fill for Next/target-action pills. Null uses the opaque textColor default. */
    public Integer primaryButtonColor;
    /** ARGB label color for primary pills. Null selects a contrasting color. */
    public Integer primaryButtonTextColor;
    public float textSizeSp = 18, paddingDp = 16, cornerDp = 12;
    public long animationMillis = 180;
    // The default footer is Skip + Next. Additional navigation is explicitly opt-in.
    public boolean reducedMotion, showPrevious, showNext = true, showSkipStep, showSkipTour = true, showClose;
    public boolean showProgress = true;
    public enum Navigation { PREVIOUS, NEXT, SKIP_STEP, SKIP_TOUR, CLOSE, NONE }
    public Navigation previousAction=Navigation.PREVIOUS, nextAction=Navigation.NEXT,
            skipStepAction=Navigation.SKIP_STEP, skipTourAction=Navigation.SKIP_TOUR, closeAction=Navigation.CLOSE;
    public int titleTextAppearance, contentTextAppearance, buttonTextAppearance;
    /** Null uses the default label; an empty or whitespace-only label omits that control. */
    public CharSequence previous, next, skipStep, skipTour, close;
    public interface ContentFactory { View create(Context context, Step step); }
    public ContentFactory contentFactory;
    /** Fill a closed path inside bounds; invoked only during geometry updates. */
    public interface HighlightShape { void path(android.graphics.Path path, android.graphics.RectF bounds); }
    public HighlightShape highlightShape;
    public TutorialTheme() { }
    public TutorialTheme(TutorialTheme other) {
        maskColor=other.maskColor; surfaceColor=other.surfaceColor; textColor=other.textColor;
        primaryButtonColor=other.primaryButtonColor; primaryButtonTextColor=other.primaryButtonTextColor;
        textSizeSp=other.textSizeSp; paddingDp=other.paddingDp; cornerDp=other.cornerDp;
        animationMillis=other.animationMillis; reducedMotion=other.reducedMotion;
        showPrevious=other.showPrevious; showNext=other.showNext; showSkipStep=other.showSkipStep;
        showSkipTour=other.showSkipTour; showClose=other.showClose;
        previous=other.previous; next=other.next; skipStep=other.skipStep; skipTour=other.skipTour; close=other.close;
        contentFactory=other.contentFactory; highlightShape=other.highlightShape;
        showProgress=other.showProgress;
        previousAction=other.previousAction;nextAction=other.nextAction;skipStepAction=other.skipStepAction;
        skipTourAction=other.skipTourAction;closeAction=other.closeAction;
        titleTextAppearance=other.titleTextAppearance;contentTextAppearance=other.contentTextAppearance;buttonTextAppearance=other.buttonTextAppearance;
    }
}
