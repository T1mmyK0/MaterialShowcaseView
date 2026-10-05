package uk.co.deanwild.materialshowcaseview;

import android.view.View;

/** Optional extension: old IAnimationFactory implementations remain source/binary compatible. */
public interface CancellableAnimationFactory extends IAnimationFactory {
    /** Cancel all entrance, exit and movement work owned by this view without callbacks. */
    void cancel(View view);
}
