package uk.co.deanwild.materialshowcaseview;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.graphics.Point;
import android.os.Build;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.animation.AccelerateDecelerateInterpolator;


public class CircularRevealAnimationFactory implements CancellableAnimationFactory {

    private final AnimationRegistry animations = new AnimationRegistry();
    private final AnimationRegistry movements = new AnimationRegistry();
    @Override public void cancel(View view) { animations.cancel(view); movements.cancel(view); }
    private static final String ALPHA = "alpha";
    private static final float INVISIBLE = 0f;
    private static final float VISIBLE = 1f;

    private final AccelerateDecelerateInterpolator interpolator;

    public CircularRevealAnimationFactory() {
        interpolator = new AccelerateDecelerateInterpolator();
    }

    static float revealRadius(View target, Point point) {
        return (float) Math.hypot(Math.max(point.x, target.getWidth() - point.x), Math.max(point.y, target.getHeight() - point.y));
    }

    @Override
    public void animateInView(View target, Point point, long duration, final AnimationStartListener listener) {
        animations.cancel(target);
        if (duration <= 0 || (Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) || !target.isAttachedToWindow()) {
            listener.onAnimationStart();
            return;
        }
        Animator animator = ViewAnimationUtils.createCircularReveal(target, point.x, point.y, 0,
                revealRadius(target, point));
        animator.setDuration(duration).addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {
                listener.onAnimationStart();
            }

            @Override
            public void onAnimationEnd(Animator animation) {

            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        });

        animations.start(target, animator);
    }

    @Override
    public void animateOutView(View target, Point point, long duration, final AnimationEndListener listener) {
        animations.cancel(target);
        if (duration <= 0 || (Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) || !target.isAttachedToWindow()) {
            listener.onAnimationEnd();
            return;
        }
        Animator animator = ViewAnimationUtils.createCircularReveal(target, point.x, point.y,
                revealRadius(target, point), 0);
        animator.setDuration(duration).addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                listener.onAnimationEnd();
            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        });

        animations.start(target, animator);
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
