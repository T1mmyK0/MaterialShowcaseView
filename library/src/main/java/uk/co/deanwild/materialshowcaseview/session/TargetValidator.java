package uk.co.deanwild.materialshowcaseview.session;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;

/** Geometry cannot detect a covering window: callers must also check focus and app blockers. */
public final class TargetValidator {
    public enum Reason { READY, MISSING, DETACHED, OTHER_WINDOW, HIDDEN, ZERO_SIZE, DISABLED, NOT_CLICKABLE, CLIPPED }
    private TargetValidator() { }
    public static boolean valid(View target, View host, Step step, boolean visible) {
        return diagnose(target, host, step, visible) == Reason.READY;
    }
    public static Reason diagnose(View target, View host, Step step, boolean visible) {
        boolean targetTap = step.interaction == Step.Interaction.TARGET_TAP;
        return diagnose(target, host, visible, step.enabledRequired || targetTap, step.clickableRequired || targetTap);
    }
    static Reason diagnoseHighlight(View target, View host, boolean visible) {
        return diagnose(target, host, visible, false, false);
    }
    private static Reason diagnose(View target, View host, boolean visible, boolean enabledRequired, boolean clickableRequired) {
        if (target == null) return Reason.MISSING;
        if (target.getWindowToken() == null) return Reason.DETACHED;
        if (target.getWindowToken() != host.getWindowToken()) return Reason.OTHER_WINDOW;
        if (!target.isShown()) return Reason.HIDDEN;
        if (target.getWidth() <= 0 || target.getHeight() <= 0) return Reason.ZERO_SIZE;
        if (enabledRequired && !target.isEnabled()) return Reason.DISABLED;
        if (clickableRequired && !target.isClickable()) return Reason.NOT_CLICKABLE;
        View node = target;
        while (true) {
            if (node.getAlpha() <= 0 || node.getVisibility() != View.VISIBLE) return Reason.HIDDEN;
            ViewParent parent = node.getParent(); if (!(parent instanceof View)) break; node = (View) parent;
        }
        if (!visible) return Reason.READY;
        Rect shown = new Rect(), viewport = new Rect();
        if (!TargetGeometry.visibleOnScreen(target, shown) || !TargetGeometry.usableOnScreen(host, viewport)) return Reason.CLIPPED;
        ViewParent ancestor = target.getParent();
        while (ancestor instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) ancestor;
            if (group.getClipChildren()) {
                Rect clipping = new Rect();
                if (!TargetGeometry.usableOnScreen(group, clipping) || !viewport.intersect(clipping)) return Reason.CLIPPED;
            }
            ancestor = group.getParent();
        }
        if (!shown.intersect(viewport)) return Reason.CLIPPED;
        Rect transformed = new Rect(); TargetGeometry.boundsOnScreen(target, transformed);
        if (transformed.isEmpty()) return Reason.ZERO_SIZE;
        return enoughVisible(shown.width(), transformed.width(), viewport.width())
                && enoughVisible(shown.height(), transformed.height(), viewport.height()) ? Reason.READY : Reason.CLIPPED;
    }
    private static boolean enoughVisible(int shown, int target, int viewport) {
        // A target that cannot fit only needs half the usable extent on that axis.
        // Requiring every pixel rejects the padding deliberately left by reveal strategies.
        // Ordinary targets still need a full fit; a sliver of an oversized target is not ready.
        int required = target > viewport ? (viewport + 1) / 2 : target;
        return shown + 1 >= required; // Allow transformed-edge rounding by Android.
    }
}
