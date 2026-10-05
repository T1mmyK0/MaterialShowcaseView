package uk.co.deanwild.materialshowcaseview.session;

import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/** Shared screen-coordinate geometry; handles windows whose origin is not the screen origin. */
final class TargetGeometry {
    private TargetGeometry() { }
    static void boundsOnScreen(View view, Rect out) {
        RectF bounds = new RectF(0, 0, view.getWidth(), view.getHeight());
        mapToScreen(view, bounds); bounds.roundOut(out);
    }
    private static void mapToScreen(View view, RectF bounds) {
        Matrix matrix = new Matrix();
        View node = view;
        while (node.getParent() instanceof View) {
            View parent = (View) node.getParent();
            matrix.postConcat(node.getMatrix());
            matrix.postTranslate(node.getLeft() - parent.getScrollX(), node.getTop() - parent.getScrollY());
            node = parent;
        }
        matrix.mapRect(bounds);
        int[] origin = new int[2]; node.getLocationOnScreen(origin);
        bounds.offset(origin[0], origin[1]);
    }
    static boolean visibleOnScreen(View view, Rect out) {
        if (!view.getGlobalVisibleRect(out)) return false;
        int[] origin = new int[2];
        view.getRootView().getLocationOnScreen(origin);
        out.offset(origin[0], origin[1]);
        return true;
    }
    static boolean usableOnScreen(View host, Rect out) {
        host.getWindowVisibleDisplayFrame(out);
        Rect bounds = new Rect();
        if (!visibleOnScreen(host, bounds) || !out.intersect(bounds)) { out.setEmpty(); return false; }
        if (host instanceof android.view.ViewGroup && ((android.view.ViewGroup) host).getClipToPadding()) {
            RectF padded = new RectF(host.getPaddingLeft(), host.getPaddingTop(),
                    host.getWidth() - host.getPaddingRight(), host.getHeight() - host.getPaddingBottom());
            mapToScreen(host, padded); padded.roundOut(bounds);
            if (!out.intersect(bounds)) { out.setEmpty(); return false; }
        }
        if (Build.VERSION.SDK_INT >= 30 && host.getRootWindowInsets() != null) {
            android.graphics.Insets safe = host.getRootWindowInsets().getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            View windowRoot = host.getRootView(); int[] origin = new int[2]; windowRoot.getLocationOnScreen(origin);
            if (!out.intersect(origin[0] + safe.left, origin[1] + safe.top,
                    origin[0] + windowRoot.getWidth() - safe.right, origin[1] + windowRoot.getHeight() - safe.bottom)) {
                out.setEmpty(); return false;
            }
        }
        return !out.isEmpty();
    }
}
