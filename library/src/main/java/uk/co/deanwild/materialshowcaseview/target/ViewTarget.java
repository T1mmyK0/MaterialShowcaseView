package uk.co.deanwild.materialshowcaseview.target;

import android.app.Activity;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.view.View;


public class ViewTarget implements Target {

    private final View mView;

    public ViewTarget(View view) {
        if (view == null) throw new IllegalArgumentException("ViewTarget requires a View");
        mView = view;
    }

    public ViewTarget(int viewId, Activity activity) {
        this(activity.findViewById(viewId));
    }

    public boolean isReady() {
        Rect visible = new Rect();
        if (mView.getWindowToken() == null || !mView.isShown() || mView.getWidth() <= 0 || mView.getHeight() <= 0
                || !mView.getGlobalVisibleRect(visible)) return false;
        View view = mView;
        while (true) {
            if (view.getAlpha() <= 0) return false;
            if (!(view.getParent() instanceof View)) return true;
            view = (View) view.getParent();
        }
    }

    @Override
    public Point getPoint() {
        Rect bounds = getBounds();
        return new Point(bounds.centerX(), bounds.centerY());
    }

    @Override
    public Rect getBounds() {
        // Map the entire rectangle: getLocationInWindow only transforms its origin,
        // so adding the unscaled measured size misplaces scaled or rotated targets.
        RectF bounds = new RectF(0, 0, mView.getWidth(), mView.getHeight());
        Matrix matrix = new Matrix();
        View node = mView;
        while (node.getParent() instanceof View) {
            View parent = (View) node.getParent();
            matrix.postConcat(node.getMatrix());
            matrix.postTranslate(node.getLeft() - parent.getScrollX(), node.getTop() - parent.getScrollY());
            node = parent;
        }
        matrix.mapRect(bounds);
        int[] location = new int[2];
        node.getLocationInWindow(location);
        bounds.offset(location[0], location[1]);
        Rect result = new Rect(); bounds.roundOut(result);
        return result;
    }

    public View getView() {
        return mView;
    }
}
