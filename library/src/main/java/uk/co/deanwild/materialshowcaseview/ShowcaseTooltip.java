package uk.co.deanwild.materialshowcaseview;


import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;


import android.text.Html;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Arrays;
import uk.co.deanwild.materialshowcaseview.target.ViewTarget;

/**
 * Base on original code by florentchampigny
 * https://github.com/florent37/ViewTooltip
 */

public class ShowcaseTooltip {

    private Runnable pending;
    private ViewTreeObserver.OnPreDrawListener pendingLayout;
    private ViewTreeObserver pendingTree;
    private ViewTreeObserver.OnPreDrawListener trackingLayout;
    private ViewTreeObserver trackingTree;
    private long generation;
    private View rootView;
    private View view;
    private TooltipView tooltip_view;


    private ShowcaseTooltip(Context context){
        Activity activity = getActivityContext(context);
        if (activity == null) throw new IllegalArgumentException("Tooltip requires an Activity context");
        MyContext myContext = new MyContext(activity);
        this.tooltip_view = new TooltipView(myContext.getContext());
        this.tooltip_view.detachedCleanup = this::cancelPending;
    }

    public static ShowcaseTooltip build(Context context) {
        return new ShowcaseTooltip(context);
    }

    public void configureTarget(ViewGroup rootView, View view) {
        cancel();
        this.rootView = rootView;
        this.view = view;
        tooltip_view.failureCleanup = rootView instanceof MaterialShowcaseView
                ? ((MaterialShowcaseView) rootView).capturePresentationRemoval() : null;
    }

    private static Activity getActivityContext(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    public ShowcaseTooltip position(Position position) {
        this.tooltip_view.setPosition(position);
        return this;
    }

    public ShowcaseTooltip customView(View customView) {
        this.tooltip_view.setCustomView(customView);
        return this;
    }

    public ShowcaseTooltip customView(int viewId) {
        this.tooltip_view.setCustomView(((Activity) tooltip_view.getContext()).findViewById(viewId));
        return this;
    }

    public ShowcaseTooltip arrowWidth(int arrowWidth) {
        this.tooltip_view.setArrowWidth(arrowWidth);
        return this;
    }

    public ShowcaseTooltip arrowHeight(int arrowHeight) {
        this.tooltip_view.setArrowHeight(arrowHeight);
        return this;
    }

    public ShowcaseTooltip arrowSourceMargin(int arrowSourceMargin) {
        this.tooltip_view.setArrowSourceMargin(arrowSourceMargin);
        return this;
    }

    public ShowcaseTooltip arrowTargetMargin(int arrowTargetMargin) {
        this.tooltip_view.setArrowTargetMargin(arrowTargetMargin);
        return this;
    }

    public ShowcaseTooltip align(ALIGN align) {
        this.tooltip_view.setAlign(align);
        return this;
    }

    public TooltipView show(final int margin) {
        if (view == null) throw new IllegalStateException("Configure a tooltip target before showing it");
        final Context activityContext = tooltip_view.getContext();
        if (activityContext != null && activityContext instanceof Activity) {
            final ViewGroup decorView = rootView != null ?
                    (ViewGroup) rootView :
                    (ViewGroup) ((Activity) activityContext).getWindow().getDecorView();

            cancel();
            final long token = generation;
            pending = new Runnable() {
                @Override
                public void run() {
                    runInPresentation(token, () -> {
                        if (view == null || !new ViewTarget(view).isReady()
                                || view.getWindowToken() != decorView.getWindowToken()) return;
                        decorView.addView(tooltip_view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

                        pendingLayout = new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                if (pendingLayout != this || token != generation) return true;
                                removePendingLayout();
                                runInPresentation(token, () -> {
                                    final Rect rect = new Rect();
                                    if (readAnchor(decorView, margin, rect)) {
                                        Rect viewport = new Rect(); readViewport(decorView, viewport);
                                        tooltip_view.setup(rect, viewport.left, viewport.right);
                                        if (token == generation) trackTarget(decorView, margin, token);
                                    } else cancel();
                                });
                                return false;
                            }
                        };
                        pendingTree = tooltip_view.getViewTreeObserver();
                        pendingTree.addOnPreDrawListener(pendingLayout);
                    });
                }
            };
            view.post(pending);
        }
        return tooltip_view;
    }

    private void runInPresentation(long token, Runnable work) {
        if (token != generation) return;
        try { work.run(); }
        catch (RuntimeException error) {
            if (token == generation) {
                Runnable ownerCleanup = tooltip_view.failureCleanup;
                try { cancel(); } catch (RuntimeException cleanup) { if (cleanup != error) error.addSuppressed(cleanup); }
                if (ownerCleanup != null) {
                    try { ownerCleanup.run(); } catch (RuntimeException cleanup) { if (cleanup != error) error.addSuppressed(cleanup); }
                }
            }
            throw error;
        }
    }

    private boolean readAnchor(ViewGroup parent, int margin, Rect rect) {
        if (view == null || !new ViewTarget(view).isReady()
                || view.getWindowToken() != parent.getWindowToken() || !view.getGlobalVisibleRect(rect)) return false;
        // Preserve clipping and convert all edges from window to parent coordinates.
        int[] windowOrigin = new int[2], parentOrigin = new int[2];
        view.getRootView().getLocationOnScreen(windowOrigin);
        parent.getLocationOnScreen(parentOrigin);
        rect.offset(windowOrigin[0] - parentOrigin[0], windowOrigin[1] - parentOrigin[1]);
        rect.top -= margin; rect.bottom += margin;
        return true;
    }

    private void trackTarget(ViewGroup parent, int margin, long token) {
        trackingLayout = new ViewTreeObserver.OnPreDrawListener() {
            private final Rect anchor = new Rect(), previous = new Rect();
            private final Rect viewport = new Rect(), previousViewport = new Rect();
            private int width = -1, height = -1;
            @Override public boolean onPreDraw() {
                if (trackingLayout != this || token != generation) return true;
                runInPresentation(token, () -> {
                    if (!readAnchor(parent, margin, anchor)) { cancel(); return; }
                    if (tooltip_view.setupListener != null) return;
                    readViewport(parent, viewport);
                    if (!anchor.equals(previous) || !viewport.equals(previousViewport)
                            || width != tooltip_view.getWidth() || height != tooltip_view.getHeight()
                            || tooltip_view.placementDirty) {
                        previous.set(anchor); previousViewport.set(viewport);
                        width = tooltip_view.getWidth(); height = tooltip_view.getHeight();
                        tooltip_view.reposition(anchor, viewport.left, viewport.right);
                    }
                });
                return true;
            }
        };
        trackingTree = parent.getViewTreeObserver();
        trackingTree.addOnPreDrawListener(trackingLayout);
    }

    private void readViewport(ViewGroup parent, Rect viewport) {
        parent.getWindowVisibleDisplayFrame(viewport);
        int[] origin = new int[2]; parent.getLocationOnScreen(origin);
        viewport.offset(-origin[0], -origin[1]);
        if (!viewport.intersect(0, 0, parent.getWidth(), parent.getHeight())) viewport.setEmpty();
        // Edge-to-edge windows can include system bars in their visible display frame.
        if (Build.VERSION.SDK_INT >= 23) {
            WindowInsets insets = parent.getRootWindowInsets();
            if (insets != null) {
                View window = parent.getRootView(); int[] windowOrigin = new int[2]; window.getLocationOnScreen(windowOrigin);
                int left = insets.getSystemWindowInsetLeft(), right = insets.getSystemWindowInsetRight();
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    left = safe.left; right = safe.right;
                }
                viewport.left = Math.max(viewport.left, windowOrigin[0] + left - origin[0]);
                viewport.right = Math.max(viewport.left, Math.min(viewport.right,
                        windowOrigin[0] + window.getWidth() - right - origin[0]));
            }
        }
    }

    private void removePendingLayout() {
        if (pendingLayout != null && pendingTree != null && pendingTree.isAlive())
            pendingTree.removeOnPreDrawListener(pendingLayout);
        pendingLayout = null;
        pendingTree = null;
    }

    private void cancelPending() {
        generation++;
        if (view != null && pending != null) view.removeCallbacks(pending);
        pending = null;
        removePendingLayout();
        if (trackingLayout != null && trackingTree != null && trackingTree.isAlive())
            trackingTree.removeOnPreDrawListener(trackingLayout);
        trackingLayout = null; trackingTree = null;
    }

    public void cancel() {
        cancelPending();
        tooltip_view.animate().setListener(null);
        if (Build.VERSION.SDK_INT >= 14) tooltip_view.animate().cancel();
        tooltip_view.removeNow();
    }

    public ShowcaseTooltip color(int color) {
        this.tooltip_view.setColor(color);
        return this;
    }

    public ShowcaseTooltip color(Paint paint) {
        this.tooltip_view.setPaint(paint);
        return this;
    }

    public ShowcaseTooltip onDisplay(ListenerDisplay listener) {
        this.tooltip_view.setListenerDisplay(listener);
        return this;
    }

    public ShowcaseTooltip padding(int left, int top, int right, int bottom) {
        this.tooltip_view.paddingTop = top;
        this.tooltip_view.paddingBottom = bottom;
        this.tooltip_view.paddingLeft = left;
        this.tooltip_view.paddingRight = right;
        this.tooltip_view.setPosition(this.tooltip_view.position);
        return this;
    }

    public ShowcaseTooltip animation(TooltipAnimation tooltipAnimation) {
        this.tooltip_view.setTooltipAnimation(tooltipAnimation);
        return this;
    }

    public ShowcaseTooltip text(String text) {
        this.tooltip_view.setText(text);
        return this;
    }

    public ShowcaseTooltip text(int text) {
        this.tooltip_view.setText(text);
        return this;
    }

    public ShowcaseTooltip corner(int corner) {
        this.tooltip_view.setCorner(corner);
        return this;
    }

    public ShowcaseTooltip textColor(int textColor) {
        this.tooltip_view.setTextColor(textColor);
        return this;
    }

    public ShowcaseTooltip textTypeFace(Typeface typeface) {
        this.tooltip_view.setTextTypeFace(typeface);
        return this;
    }

    public ShowcaseTooltip textSize(int unit, float textSize) {
        this.tooltip_view.setTextSize(unit, textSize);
        return this;
    }

    public ShowcaseTooltip setTextGravity(int textGravity) {
        this.tooltip_view.setTextGravity(textGravity);
        return this;
    }

    public ShowcaseTooltip distanceWithView(int distance) {
        this.tooltip_view.setDistanceWithView(distance);
        return this;
    }

    public ShowcaseTooltip border(int color, float width) {
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(color);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(width);
        this.tooltip_view.setBorderPaint(borderPaint);
        return this;
    }

    public enum Position {
        LEFT,
        RIGHT,
        TOP,
        BOTTOM,
    }

    public enum ALIGN {
        START,
        CENTER,
        END
    }

    public interface TooltipAnimation {
        void animateEnter(View view, Animator.AnimatorListener animatorListener);

        void animateExit(View view, Animator.AnimatorListener animatorListener);
    }

    public interface ListenerDisplay {
        void onDisplay(View view);
    }

    /** Optional cancellation extension for custom tooltip animations. */
    public interface CancellableTooltipAnimation extends TooltipAnimation { void cancel(View view); }

    public static class FadeTooltipAnimation implements CancellableTooltipAnimation {
        @Override public void cancel(View view) {
            view.animate().setListener(null);
            if (Build.VERSION.SDK_INT >= 14) view.animate().cancel();
        }

        private long fadeDuration = 400;

        public FadeTooltipAnimation() {
        }

        public FadeTooltipAnimation(long fadeDuration) {
            this.fadeDuration = fadeDuration;
        }

        @Override
        public void animateEnter(View view, Animator.AnimatorListener animatorListener) {
            view.setAlpha(0);
            view.animate().alpha(1).setDuration(fadeDuration).setListener(animatorListener);
        }

        @Override
        public void animateExit(View view, Animator.AnimatorListener animatorListener) {
            view.animate().alpha(0).setDuration(fadeDuration).setListener(animatorListener);
        }
    }

    public static class TooltipView extends FrameLayout {
        private ViewTreeObserver.OnPreDrawListener setupListener;
        private ViewTreeObserver setupTree;
        private long animationGeneration;
        private boolean removing;
        private boolean placementDirty;
        private int constrainedWidth = -1, requestedWidth;
        private Runnable detachedCleanup;
        private Runnable failureCleanup;

        private static final int MARGIN_SCREEN_BORDER_TOOLTIP = 30;
        private int arrowHeight = 15;
        private int arrowWidth = 15;
        private int arrowSourceMargin = 0;
        private int arrowTargetMargin = 0;
        protected View childView;
        private int color = Color.parseColor("#FFFFFF");
        private Path bubblePath;
        private Paint bubblePaint;
        private Paint borderPaint;
        private Position position = Position.BOTTOM;
        private ALIGN align = ALIGN.CENTER;

        private ListenerDisplay listenerDisplay;

        private TooltipAnimation tooltipAnimation = new FadeTooltipAnimation();

        private int corner = 30;

        private int paddingTop = 20;
        private int paddingBottom = 30;
        private int paddingRight = 60;
        private int paddingLeft = 60;

        private Rect viewRect;
        private int distanceWithView = 0;

        public TooltipView(Context context) {
            super(context);

            setWillNotDraw(false);

            this.childView = new TextView(context);
            ((TextView) childView).setTextColor(Color.BLACK);
            addView(childView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            childView.setPadding(0, 0, 0, 0);

            bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bubblePaint.setColor(color);
            bubblePaint.setStyle(Paint.Style.FILL);

            borderPaint = null;

            setLayerType(LAYER_TYPE_SOFTWARE, bubblePaint);
            setPosition(position);
        }

        public void setCustomView(View customView) {
            if (customView == null) throw new IllegalArgumentException("Missing tooltip content view");
            this.removeView(childView);
            this.childView = customView;
            addView(childView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        public void setColor(int color) {
            this.color = color;
            bubblePaint.setColor(color);
            postInvalidate();
        }

        public void setPaint(Paint paint) {
            bubblePaint = paint;
            setLayerType(LAYER_TYPE_SOFTWARE, paint);
            postInvalidate();
        }

        public void setPosition(Position position) {
            this.position = position;
            placementDirty = true;
            switch (position) {
                case TOP:
                    setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom + arrowHeight);
                    break;
                case BOTTOM:
                    setPadding(paddingLeft, paddingTop + arrowHeight, paddingRight, paddingBottom);
                    break;
                case LEFT:
                    setPadding(paddingLeft, paddingTop, paddingRight + arrowHeight, paddingBottom);
                    break;
                case RIGHT:
                    setPadding(paddingLeft + arrowHeight, paddingTop, paddingRight, paddingBottom);
                    break;
            }
            bubblePath = drawBubble(new RectF(0, 0, getWidth(), getHeight()), corner, corner, corner, corner);
            postInvalidate();
        }

        public void setAlign(ALIGN align) {
            this.align = align;
            placementDirty = true;
            postInvalidate();
        }

        public void setText(String text) {
            if (childView instanceof TextView) {
                ((TextView) this.childView).setText(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        ? Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY) : Html.fromHtml(text));
            }
            postInvalidate();
        }

        public void setText(int text) {
            if (childView instanceof TextView) {
                ((TextView) this.childView).setText(text);
            }
            postInvalidate();
        }

        public void setTextColor(int textColor) {
            if (childView instanceof TextView) {
                ((TextView) this.childView).setTextColor(textColor);
            }
            postInvalidate();
        }

        public int getArrowHeight() {
            return arrowHeight;
        }

        public void setArrowHeight(int arrowHeight) {
            this.arrowHeight = arrowHeight;
            setPosition(position);
        }

        public int getArrowWidth() {
            return arrowWidth;
        }

        public void setArrowWidth(int arrowWidth) {
            this.arrowWidth = arrowWidth;
            postInvalidate();
        }

        public int getArrowSourceMargin() {
            return arrowSourceMargin;
        }

        public void setArrowSourceMargin(int arrowSourceMargin) {
            this.arrowSourceMargin = arrowSourceMargin;
            postInvalidate();
        }

        public int getArrowTargetMargin() {
            return arrowTargetMargin;
        }

        public void setArrowTargetMargin(int arrowTargetMargin) {
            this.arrowTargetMargin = arrowTargetMargin;
            postInvalidate();
        }

        public void setTextTypeFace(Typeface textTypeFace) {
            if (childView instanceof TextView) {
                ((TextView) this.childView).setTypeface(textTypeFace);
            }
            postInvalidate();
        }

        public void setTextSize(int unit, float size) {
            if (childView instanceof TextView) {
                ((TextView) this.childView).setTextSize(unit, size);
            }
            postInvalidate();
        }

        public void setTextGravity(int textGravity) {
            if (childView instanceof TextView) {
                ((TextView) this.childView).setGravity(textGravity);
            }
            postInvalidate();
        }

        public void setCorner(int corner) {
            this.corner = corner;
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldw, int oldh) {
            super.onSizeChanged(width, height, oldw, oldh);

            bubblePath = drawBubble(new RectF(0, 0, width, height), corner, corner, corner, corner);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (bubblePath != null) {
                canvas.drawPath(bubblePath, bubblePaint);
                if (borderPaint != null) {
                    canvas.drawPath(bubblePath, borderPaint);
                }
            }
        }

        public void setListenerDisplay(ListenerDisplay listener) {
            this.listenerDisplay = listener;
        }

        public void setTooltipAnimation(TooltipAnimation tooltipAnimation) {
            this.tooltipAnimation = tooltipAnimation;
        }

        protected void startEnterAnimation() {
            final long token = ++animationGeneration;
            try { tooltipAnimation.animateEnter(this, new AnimatorListenerAdapter() {
                private boolean delivered;
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    if (!delivered && token == animationGeneration && getParent() != null && listenerDisplay != null) {
                        delivered = true;
                        try { listenerDisplay.onDisplay(TooltipView.this); }
                        catch (RuntimeException error) { throw presentationFailure(token, error); }
                    }
                }
            }); } catch (RuntimeException error) { throw presentationFailure(token, error); }
        }

        @Override protected void onMeasure(int width, int height) {
            long token = animationGeneration;
            try { super.onMeasure(width, height); }
            catch (RuntimeException error) { throw presentationFailure(token, error); }
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            long token = animationGeneration;
            try { super.onLayout(changed, left, top, right, bottom); }
            catch (RuntimeException error) { throw presentationFailure(token, error); }
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            long token = animationGeneration; int saved = canvas.save();
            try { super.dispatchDraw(canvas); }
            catch (RuntimeException error) { throw presentationFailure(token, error); }
            finally { canvas.restoreToCount(saved); }
        }

        @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
            long token = animationGeneration;
            try { return super.dispatchTouchEvent(event); }
            catch (RuntimeException error) { throw presentationFailure(token, error); }
        }

        private RuntimeException presentationFailure(long token, RuntimeException error) {
            if (token == animationGeneration) {
                Runnable ownerCleanup = failureCleanup;
                try { removeNow(); }
                catch (RuntimeException cleanup) { if (cleanup != error) error.addSuppressed(cleanup); }
                if (ownerCleanup != null) {
                    try { ownerCleanup.run(); } catch (RuntimeException cleanup) { if (cleanup != error) error.addSuppressed(cleanup); }
                }
            }
            return error;
        }

        public void setupPosition(Rect rect) {

            int x, y;

            if (position == Position.LEFT || position == Position.RIGHT) {
                if (position == Position.LEFT) {
                    x = rect.left - getWidth() - distanceWithView;
                } else {
                    x = rect.right + distanceWithView;
                }
                y = rect.top + getAlignOffset(getHeight(), rect.height());
            } else {
                if (position == Position.BOTTOM) {
                    y = rect.bottom + distanceWithView;
                } else { // top
                    y = rect.top - getHeight() - distanceWithView;
                }
                x = rect.left + getAlignOffset(getWidth(), rect.width());
            }

            // The anchor is in parent coordinates; translation alone also adds layout padding/margins.
            setX(x);
            setY(y);
        }

        private int getAlignOffset(int myLength, int hisLength) {
            switch (align) {
                case END:
                    return hisLength - myLength;
                case CENTER:
                    return (hisLength - myLength) / 2;
            }
            return 0;
        }

        private Path drawBubble(RectF myRect, float topLeftDiameter, float topRightDiameter, float bottomRightDiameter, float bottomLeftDiameter) {
            final Path path = new Path();

            if (viewRect == null)
                return path;

            topLeftDiameter = topLeftDiameter < 0 ? 0 : topLeftDiameter;
            topRightDiameter = topRightDiameter < 0 ? 0 : topRightDiameter;
            bottomLeftDiameter = bottomLeftDiameter < 0 ? 0 : bottomLeftDiameter;
            bottomRightDiameter = bottomRightDiameter < 0 ? 0 : bottomRightDiameter;

            float spacingLeft = 30;
            final float spacingTop = this.position == Position.BOTTOM ? arrowHeight : 0;
            float spacingRight = 30;
            final float spacingBottom = this.position == Position.TOP ? arrowHeight : 0;

            final float left = spacingLeft + myRect.left;
            final float top = spacingTop + myRect.top;
            final float right = myRect.right - spacingRight;
            final float bottom = myRect.bottom - spacingBottom;
            final float centerX = viewRect.centerX() - getX();

            final float arrowSourceX = (Arrays.asList(Position.TOP, Position.BOTTOM).contains(this.position))
                    ? centerX + arrowSourceMargin
                    : centerX;
            final float arrowTargetX = (Arrays.asList(Position.TOP, Position.BOTTOM).contains(this.position))
                    ? centerX + arrowTargetMargin
                    : centerX;
            final float arrowSourceY = (Arrays.asList(Position.RIGHT, Position.LEFT).contains(this.position))
                    ? bottom / 2f - arrowSourceMargin
                    : bottom / 2f;
            final float arrowTargetY = (Arrays.asList(Position.RIGHT, Position.LEFT).contains(this.position))
                    ? bottom / 2f - arrowTargetMargin
                    : bottom / 2f;

            path.moveTo(left + topLeftDiameter / 2f, top);
            //LEFT, TOP

            if (position == Position.BOTTOM) {
                path.lineTo(arrowSourceX - arrowWidth, top);
                path.lineTo(arrowTargetX, myRect.top);
                path.lineTo(arrowSourceX + arrowWidth, top);
            }
            path.lineTo(right - topRightDiameter / 2f, top);

            path.quadTo(right, top, right, top + topRightDiameter / 2);
            //RIGHT, TOP

            if (position == Position.LEFT) {
                path.lineTo(right, arrowSourceY - arrowWidth);
                path.lineTo(myRect.right, arrowTargetY);
                path.lineTo(right, arrowSourceY + arrowWidth);
            }
            path.lineTo(right, bottom - bottomRightDiameter / 2);

            path.quadTo(right, bottom, right - bottomRightDiameter / 2, bottom);
            //RIGHT, BOTTOM

            if (position == Position.TOP) {
                path.lineTo(arrowSourceX + arrowWidth, bottom);
                path.lineTo(arrowTargetX, myRect.bottom);
                path.lineTo(arrowSourceX - arrowWidth, bottom);
            }
            path.lineTo(left + bottomLeftDiameter / 2, bottom);

            path.quadTo(left, bottom, left, bottom - bottomLeftDiameter / 2);
            //LEFT, BOTTOM

            if (position == Position.RIGHT) {
                path.lineTo(left, arrowSourceY + arrowWidth);
                path.lineTo(myRect.left, arrowTargetY);
                path.lineTo(left, arrowSourceY - arrowWidth);
            }
            path.lineTo(left, top + topLeftDiameter / 2);

            path.quadTo(left, top, left + topLeftDiameter / 2, top);

            path.close();

            return path;
        }

        public boolean adjustSize(Rect rect, int screenWidth) {
            return adjustSize(rect, 0, screenWidth);
        }

        private boolean adjustSize(Rect rect, int viewportLeft, int viewportRight) {
            boolean changed = false;
            final ViewGroup.LayoutParams layoutParams = getLayoutParams();
            int maxWidth = Math.max(0, viewportRight - viewportLeft);
            if (position == Position.LEFT || position == Position.RIGHT) {
                int available = position == Position.LEFT ? rect.left - viewportLeft : viewportRight - rect.right;
                maxWidth = Math.max(0, available - MARGIN_SCREEN_BORDER_TOOLTIP - distanceWithView);
            }
            if (constrainedWidth >= 0 && layoutParams.width != constrainedWidth) constrainedWidth = -1;
            if (constrainedWidth >= 0 && maxWidth > constrainedWidth) {
                // Internal clipping is temporary. Re-measure the caller's width policy
                // when the target/window gives the bubble more room.
                layoutParams.width = requestedWidth; constrainedWidth = -1;
                setLayoutParams(layoutParams); return true;
            }
            int width = Math.min(getWidth(), maxWidth);
            if (width != getWidth()) {
                if (constrainedWidth < 0) requestedWidth = layoutParams.width;
                constrainedWidth = width; layoutParams.width = width; changed = true;
            }
            if (position == Position.TOP || position == Position.BOTTOM) {
                int left = rect.left + getAlignOffset(width, rect.width());
                int clampedLeft = Math.max(viewportLeft, Math.min(left, viewportRight - width));
                // Offset only the placement copy; viewRect retains the true arrow anchor.
                rect.offset(clampedLeft - left, 0);
            }
            if (changed) setLayoutParams(layoutParams);
            postInvalidate();
            return changed;
        }

        private void onSetup(Rect myRect) {
            setupPosition(myRect);
            bubblePath = drawBubble(new RectF(0, 0, getWidth(), getHeight()), corner, corner, corner, corner);
            startEnterAnimation();
        }

        private void reposition(Rect anchor, int viewportLeft, int viewportRight) {
            placementDirty = false;
            viewRect = new Rect(anchor);
            Rect placement = new Rect(anchor);
            if (adjustSize(placement, viewportLeft, viewportRight)) return;
            setupPosition(placement);
            bubblePath = drawBubble(new RectF(0, 0, getWidth(), getHeight()), corner, corner, corner, corner);
        }

        public void setup(final Rect viewRect, int screenWidth) {
            setup(viewRect, 0, screenWidth);
        }

        private void setup(final Rect viewRect, int viewportLeft, int viewportRight) {
            removeSetupListener();
            this.viewRect = new Rect(viewRect);
            final Rect myRect = new Rect(viewRect);

            final boolean changed = adjustSize(myRect, viewportLeft, viewportRight);
            if (!changed) {
                onSetup(myRect);
            } else {
                setupListener = new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (setupListener != this) return true;
                        removeSetupListener();
                        if (getParent() != null) onSetup(myRect);
                        return false;
                    }
                };
                setupTree = getViewTreeObserver();
                setupTree.addOnPreDrawListener(setupListener);
            }
        }

        private void removeSetupListener() {
            if (setupListener != null && setupTree != null && setupTree.isAlive())
                setupTree.removeOnPreDrawListener(setupListener);
            setupListener = null;
            setupTree = null;
        }

        private void releasePresentation() {
            animationGeneration++;
            removeSetupListener();
            if (detachedCleanup != null) detachedCleanup.run();
            if (tooltipAnimation instanceof CancellableTooltipAnimation)
                ((CancellableTooltipAnimation) tooltipAnimation).cancel(this);
        }

        public void removeNow() {
            if (removing) return;
            removing = true;
            try {
                try { releasePresentation(); }
                finally { if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this); }
            } finally { removing = false; }
        }

        @Override protected void onDetachedFromWindow() {
            try {
                if (!removing) {
                    removing = true;
                    try { releasePresentation(); } finally { removing = false; }
                }
            } finally { super.onDetachedFromWindow(); }
        }

        public void closeNow() {
            removeNow();
        }

        public void setDistanceWithView(int distanceWithView) {
            this.distanceWithView = distanceWithView;
            placementDirty = true;
        }

        public void setBorderPaint(Paint borderPaint) {
            this.borderPaint = borderPaint;
            postInvalidate();
        }
    }

    public static class MyContext {
        private Fragment fragment;
        private Context context;
        private Activity activity;

        public MyContext(Activity activity) {
            this.activity = activity;
        }

        public MyContext(Fragment fragment) {
            this.fragment = fragment;
        }

        public MyContext(Context context) {
            this.context = context;
        }

        public Context getContext() {
            if (activity != null) {
                return activity;
            } else {
                return ((Context) fragment.getActivity());
            }
        }

        public Activity getActivity() {
            if (activity != null) {
                return activity;
            } else {
                return fragment.getActivity();
            }
        }


        public Window getWindow() {
            if (activity != null) {
                return activity.getWindow();
            } else {
                if (fragment instanceof DialogFragment) {
                    return ((DialogFragment) fragment).getDialog().getWindow();
                }
                return fragment.getActivity().getWindow();
            }
        }
    }
}
