package uk.co.deanwild.materialshowcaseview;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.graphics.Point;
import android.os.Build;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;


public class FadeAnimationFactory implements CancellableAnimationFactory{

    private final AnimationRegistry animations = new AnimationRegistry();
    private final AnimationRegistry movements = new AnimationRegistry();
    @Override public void cancel(View view) { animations.cancel(view); movements.cancel(view); }
    private static final String ALPHA = "alpha";
    private static final float INVISIBLE = 0f;
    private static final float VISIBLE = 1f;

    private final AccelerateDecelerateInterpolator interpolator;

    public FadeAnimationFactory() {
        interpolator = new AccelerateDecelerateInterpolator();
    }

    @Override
    public void animateInView(View target, Point point, long duration, final AnimationStartListener listener) {
        animations.cancel(target);
        if (duration <= 0 || (Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) || !target.isAttachedToWindow()) {
            target.setAlpha(VISIBLE);
            listener.onAnimationStart();
            return;
        }
        ObjectAnimator oa = ObjectAnimator.ofFloat(target, ALPHA, INVISIBLE, VISIBLE);
        oa.setDuration(duration).addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animator) {
                listener.onAnimationStart();
            }

            @Override
            public void onAnimationEnd(Animator animator) {
            }

            @Override
            public void onAnimationCancel(Animator animator) {
            }

            @Override
            public void onAnimationRepeat(Animator animator) {
            }
        });
        animations.start(target, oa);
    }

    @Override
    public void animateOutView(View target, Point point, long duration, final AnimationEndListener listener) {
        animations.cancel(target);
        if (duration <= 0 || (Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) || !target.isAttachedToWindow()) {
            target.setAlpha(INVISIBLE);
            listener.onAnimationEnd();
            return;
        }
        ObjectAnimator oa = ObjectAnimator.ofFloat(target, ALPHA, INVISIBLE);
        oa.setDuration(duration).addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animator) {
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                listener.onAnimationEnd();
            }

            @Override
            public void onAnimationCancel(Animator animator) {
            }

            @Override
            public void onAnimationRepeat(Animator animator) {
            }
        });
        animations.start(target, oa);
    }

    @Override
    public void animateTargetToPoint(MaterialShowcaseView showcaseView, Point point) {
        AnimatorSet set = new AnimatorSet();
        ObjectAnimator xAnimator = ObjectAnimator.ofInt(showcaseView, "showcaseX", point.x);
        ObjectAnimator yAnimator = ObjectAnimator.ofInt(showcaseView, "showcaseY", point.y);
        set.playTogether(xAnimator, yAnimator);
        set.setInterpolator(interpolator);
        movements.cancel(showcaseView);
        movements.start(showcaseView, set);
    }
}
