package uk.co.deanwild.materialshowcaseview;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import uk.co.deanwild.materialshowcaseview.shape.CircleShape;
import uk.co.deanwild.materialshowcaseview.shape.NoShape;
import uk.co.deanwild.materialshowcaseview.shape.OvalShape;
import uk.co.deanwild.materialshowcaseview.shape.RectangleShape;
import uk.co.deanwild.materialshowcaseview.shape.Shape;
import uk.co.deanwild.materialshowcaseview.target.Target;
import uk.co.deanwild.materialshowcaseview.target.ViewTarget;


/**
 * Helper class to show a sequence of showcase views.
 */
@androidx.annotation.MainThread
public class MaterialShowcaseView extends FrameLayout implements View.OnTouchListener, View.OnClickListener {

    public static final int DEFAULT_SHAPE_PADDING = 10;
    public static final int DEFAULT_TOOLTIP_MARGIN = 10;
    long DEFAULT_DELAY = 0;
    long DEFAULT_FADE_TIME = 300;

    private int mOldHeight;
    private int mOldWidth;
    private Bitmap mBitmap;// = new WeakReference<>(null);
    private Canvas mCanvas;
    private Paint mEraser;
    private Target mTarget;
    private Shape mShape;
    private int mXPosition;
    private int mYPosition;
    private boolean mWasDismissed = false, mWasSkipped = false;
    private int mShapePadding = DEFAULT_SHAPE_PADDING;
    private int tooltipMargin = DEFAULT_TOOLTIP_MARGIN;

    private View mContentBox;
    private TextView mTitleTextView;
    private TextView mContentTextView;
    private TextView mDismissButton;
    private boolean mHasCustomGravity;
    private TextView mSkipButton;
    private int mGravity;
    private int mContentBottomMargin;
    private int mContentTopMargin;
    private boolean mDismissOnTouch = false;
    private boolean mShouldRender = false; // flag to decide when we should actually render
    private boolean mRenderOverNav = false;
    private int mMaskColour;
    private IAnimationFactory mAnimationFactory;
    private boolean mShouldAnimate = true;
    private boolean mUseFadeAnimation = false;
    private long mFadeDurationInMillis = DEFAULT_FADE_TIME;
    private Handler mHandler;
    private long mDelayInMillis = DEFAULT_DELAY;
    private int mBottomMargin = 0;
    private final Rect mSystemBarInsets = new Rect();
    private int mNavigationBarBottomInset;
    private boolean mSingleUse = false; // should display only once
    private PrefsManager mPrefsManager; // used to store state doe single use mode
    List<IShowcaseListener> mListeners; // external listeners who want to observe when we show and dismiss
    private UpdateOnGlobalLayout mLayoutListener;
    private ViewTreeObserver.OnPreDrawListener mPendingShowListener;
    private IDetachedListener mDetachedListener;
    private boolean mTargetTouchable = false;
    private boolean mDismissOnTargetTouch = true;

    private boolean isSequence = false;

    private ShowcaseTooltip toolTip;
    private boolean toolTipShown;
    private long generation;
    private boolean active, hiding, notified, displayNotified, removing, committing, detaching;
    private boolean tap;
    private float downX, downY;
    private Target touchTarget;
    private final Rect touchTargetBounds = new Rect();
    private final Rect touchVisibleBounds = new Rect();
    private final int[] touchOrigin = new int[2];
    private final Rect lastTargetBounds = new Rect();

    private void checkMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Call on main thread");
    }

    public MaterialShowcaseView(Context context) {
        super(context);
        init(context);
    }

    public MaterialShowcaseView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public MaterialShowcaseView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public MaterialShowcaseView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }


    private void init(Context context) {
        setWillNotDraw(false);

        mListeners = new ArrayList<>();

        // make sure we add a global layout listener so we can adapt to changes
        mLayoutListener = new UpdateOnGlobalLayout();
        getViewTreeObserver().addOnGlobalLayoutListener(mLayoutListener);
        getViewTreeObserver().addOnPreDrawListener(mLayoutListener);

        // consume touch events
        setOnTouchListener(this);

        mMaskColour = Color.parseColor(ShowcaseConfig.DEFAULT_MASK_COLOUR);
        setVisibility(INVISIBLE);


        View contentView = LayoutInflater.from(getContext()).inflate(R.layout.showcase_content, this, true);
        mContentBox = contentView.findViewById(R.id.content_box);
        mTitleTextView = contentView.findViewById(R.id.tv_title);
        mContentTextView = contentView.findViewById(R.id.tv_content);
        mDismissButton = contentView.findViewById(R.id.tv_dismiss);
        mDismissButton.setOnClickListener(this);

        mSkipButton = contentView.findViewById(R.id.tv_skip);
        mSkipButton.setOnClickListener(this);
    }


    /**
     * Interesting drawing stuff.
     * We draw a block of semi transparent colour to fill the whole screen then we draw of transparency
     * to create a circular "viewport" through to the underlying content
     *
     * @param canvas
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final long token = generation;
        try { drawMask(canvas, token); }
        catch (RuntimeException error) { throw presentationFailure(token, error); }
    }

    private void drawMask(Canvas canvas, long token) {
        // don't bother drawing if we're not ready
        if (!mShouldRender) return;

        // get current dimensions
        final int width = getMeasuredWidth();
        final int height = getMeasuredHeight();

        // don't bother drawing if there is nothing to draw on
        if (width <= 0 || height <= 0) return;

        // build a new canvas if needed i.e first pass or new dimensions
        if (mBitmap == null || mCanvas == null || mOldHeight != height || mOldWidth != width) {

            if (mBitmap != null) mBitmap.recycle();

            mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

            mCanvas = new Canvas(mBitmap);
        }

        // save our 'old' dimensions
        mOldWidth = width;
        mOldHeight = height;

        // clear canvas
        mCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        // draw solid background
        mCanvas.drawColor(mMaskColour);

        // Prepare eraser Paint if needed
        if (mEraser == null) {
            mEraser = new Paint();
            mEraser.setColor(0xFFFFFFFF);
            mEraser.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            mEraser.setFlags(Paint.ANTI_ALIAS_FLAG);
        }

        // draw (erase) shape
        mShape.draw(mCanvas, mEraser, mXPosition, mYPosition);
        // A custom shape may remove or replace this presentation while drawing.
        if (generation != token || !mShouldRender) return;

        // Draw the bitmap on our views  canvas.
        canvas.drawBitmap(mBitmap, 0, 0, null);
    }

    @Override
    protected void onDetachedFromWindow() {
        detaching = true;
        try {
            super.onDetachedFromWindow();
            RuntimeException failure = cleanup(null, this::removeFromWindow);
            failure = cleanup(failure, () -> {
                if (mDetachedListener != null && !committing)
                    mDetachedListener.onShowcaseDetached(this, false, false);
            });
            if (failure != null) throw failure;
        } finally { detaching = false; }
    }

    @Override protected void onMeasure(int width, int height) {
        long token = generation;
        try { super.onMeasure(width, height); }
        catch (RuntimeException error) { throw presentationFailure(token, error); }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        long token = generation;
        try { super.onLayout(changed, left, top, right, bottom); }
        catch (RuntimeException error) { throw presentationFailure(token, error); }
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        long token = generation; int saved = canvas.save();
        try { super.dispatchDraw(canvas); }
        catch (RuntimeException error) { throw presentationFailure(token, error); }
        finally { canvas.restoreToCount(saved); }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        long token = generation;
        try { return super.dispatchTouchEvent(event); }
        catch (RuntimeException error) { throw presentationFailure(token, error); }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        final long token = generation;
        try { return handleTouch(event); }
        catch (RuntimeException error) { throw presentationFailure(token, error); }
    }

    private boolean handleTouch(MotionEvent event) {
        if (!active || hiding || getVisibility() != VISIBLE) { tap = false; touchTarget = null; return true; }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tap = true; downX = event.getX(); downY = event.getY(); touchTarget = null;
                if (mTargetTouchable && targetCanBeClicked()) {
                    getLocationInWindow(touchOrigin);
                    touchTargetBounds.set(mTarget.getBounds());
                    touchVisibleBounds.set(visibleTargetBounds());
                    if (touchVisibleBounds.contains((int) downX + touchOrigin[0], (int) downY + touchOrigin[1])) touchTarget = mTarget;
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN: case MotionEvent.ACTION_CANCEL: tap = false; touchTarget = null; break;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()) tap = false;
                break;
            case MotionEvent.ACTION_UP:
                boolean clicked = tap && Math.hypot(event.getX() - downX, event.getY() - downY)
                        <= android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
                tap = false;
                Target pressed = touchTarget; touchTarget = null;
                if (clicked && pressed != null) {
                    getLocationInWindow(touchOrigin);
                    if (pressed != mTarget || !targetCanBeClicked() || !touchTargetBounds.equals(mTarget.getBounds())
                            || !touchVisibleBounds.equals(visibleTargetBounds())
                            || !touchVisibleBounds.contains((int) event.getX() + touchOrigin[0], (int) event.getY() + touchOrigin[1])) break;
                    long token = generation;
                    if (mTarget instanceof ViewTarget) ((ViewTarget) mTarget).getView().performClick();
                    if (generation == token && mDismissOnTargetTouch) hide();
                } else if (clicked && mDismissOnTouch) hide();
                break;
        }
        return true;
    }

    private Rect visibleTargetBounds() {
        Rect bounds = mTarget.getBounds();
        if (mTarget instanceof ViewTarget) {
            View view = ((ViewTarget) mTarget).getView();
            Rect visible = new Rect();
            if (!view.getGlobalVisibleRect(visible)) { bounds.setEmpty(); return bounds; }
            int[] origin = new int[2]; view.getRootView().getLocationInWindow(origin);
            visible.offset(origin[0], origin[1]);
            if (!bounds.intersect(visible)) bounds.setEmpty();
        }
        return bounds;
    }

    private boolean targetCanBeClicked() {
        if (mTarget == null) return false;
        if (!(mTarget instanceof ViewTarget)) return true;
        ViewTarget target = (ViewTarget) mTarget;
        View view = target.getView();
        if (!target.isReady() || view.getWindowToken() != getWindowToken() || !view.isEnabled() || !view.isClickable()) return false;
        while (true) {
            if (view.getAlpha() <= 0) return false;
            if (!(view.getParent() instanceof View)) return true;
            view = (View) view.getParent();
        }
    }


    private void notifyOnDisplayed() {
        if (displayNotified) return;
        displayNotified = true;
        final long token = generation;
        if (mListeners != null) {
            for (IShowcaseListener listener : new ArrayList<>(mListeners)) {
                if (!active || hiding || generation != token) break;
                listener.onShowcaseDisplayed(this);
            }
        }
    }

    private void notifyOnDismissed() {
        if (notified) return;
        notified = true;
        RuntimeException error = null;
        if (mListeners != null) {
            for (IShowcaseListener listener : new ArrayList<>(mListeners)) {
                try { listener.onShowcaseDismissed(this); }
                catch (RuntimeException failure) { if (error == null) error = failure; }
            }
        }

        /**
         * internal listener used by sequence for storing progress within the sequence
         */
        if (mDetachedListener != null) {
            mDetachedListener.onShowcaseDetached(this, error == null && mWasDismissed, error == null && mWasSkipped);
        }
        if (error != null) throw error;
    }

    /**
     * Dismiss button clicked
     *
     * @param v
     */
    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.tv_dismiss) {
            hide();
        } else if (v.getId() == R.id.tv_skip) {
            skip();
        }
    }

    /**
     * Overrides the automatic handling of gravity and sets it to a specific one. Due to this,
     * margins are also reset to zero.
     *
     * @param gravity
     */
    public void setGravity(int gravity) {
        mHasCustomGravity = Gravity.NO_GRAVITY != gravity;
        if (mHasCustomGravity) {
            mGravity = gravity;
            mContentTopMargin = mContentBottomMargin = 0;
        }
        applyLayoutParams();
    }

    /**
     * Tells us about the "Target" which is the view we want to anchor to.
     * We figure out where it is on screen and (optionally) how big it is.
     * We also figure out whether to place our content and dismiss button above or below it.
     *
     * @param target
     */
    public void setTarget(Target target) {
        final long token = generation;
        try { updateTarget(target); }
        catch (RuntimeException error) {
            // Geometry callbacks also run during layout and delayed presentation.
            // Release an active overlay before propagating an application Shape/Target failure.
            throw active ? presentationFailure(token, error) : error;
        }
    }

    private void updateTarget(Target target) {
        mTarget = target;

        // update dismiss button state
        updateDismissButton();

        if (mTarget != null) {

            mBottomMargin = mRenderOverNav ? 0 : getSoftButtonsBarSizePort();
            FrameLayout.LayoutParams contentLP = (LayoutParams) getLayoutParams();
            if (contentLP != null && contentLP.bottomMargin != mBottomMargin) {
                contentLP.bottomMargin = mBottomMargin;
                setLayoutParams(contentLP);
            }

            // apply the target position
            Point targetPoint = mTarget.getPoint();
            Rect targetBounds = mTarget.getBounds();
            lastTargetBounds.set(targetBounds);
            setPosition(targetPoint);

            // now figure out whether to put content above or below it
            int height = getMeasuredHeight();
            int midPoint = height / 2;
            int yPos = targetPoint.y;

            int radius = Math.max(targetBounds.height(), targetBounds.width()) / 2;
            if (mShape != null) {
                mShape.updateTarget(mTarget);
                radius = mShape.getHeight() / 2;
            }

            // If there's no custom gravity in place, we'll do automatic gravity calculation.
            if (!mHasCustomGravity) {
                if (yPos > midPoint) {
                    // target is in lower half of screen, we'll sit above it
                    mContentTopMargin = 0;
                    mContentBottomMargin = (height - yPos) + radius + mShapePadding;
                    mGravity = Gravity.BOTTOM;
                } else {
                    // target is in upper half of screen, we'll sit below it
                    mContentTopMargin = yPos + radius + mShapePadding;
                    mContentBottomMargin = 0;
                    mGravity = Gravity.TOP;
                }
            }
        }

        applyLayoutParams();
        invalidate();
    }

    private void applyLayoutParams() {

        if (mContentBox != null && mContentBox.getLayoutParams() != null) {
            FrameLayout.LayoutParams contentLP = (LayoutParams) mContentBox.getLayoutParams();

            boolean layoutParamsChanged = false;

            int safeBottomMargin = Math.max(mContentBottomMargin,
                    Math.max(0, mSystemBarInsets.bottom - mBottomMargin));
            int safeTopMargin = Math.max(mContentTopMargin, mSystemBarInsets.top);
            // A large target/custom shape may cover the entire window. Keep a bounded,
            // scrollable panel available so the user can still reach its dismiss control.
            int height = getHeight();
            int insetBottom = Math.max(0, mSystemBarInsets.bottom - mBottomMargin);
            int usableHeight = Math.max(0, height - mSystemBarInsets.top - insetBottom);
            float density = getResources().getDisplayMetrics().density;
            if (!mHasCustomGravity && height > 0
                    && height - safeTopMargin - safeBottomMargin < Math.min(usableHeight, Math.round(144 * density))) {
                int panelHeight = Math.min(usableHeight, Math.max(Math.round(48 * density), usableHeight / 2));
                safeTopMargin = mSystemBarInsets.top;
                safeBottomMargin = insetBottom;
                if (mGravity == Gravity.BOTTOM) safeBottomMargin += usableHeight - panelHeight;
                else safeTopMargin += usableHeight - panelHeight;
            }
            if (contentLP.bottomMargin != safeBottomMargin) {
                contentLP.bottomMargin = safeBottomMargin;
                layoutParamsChanged = true;
            }

            if (contentLP.topMargin != safeTopMargin) {
                contentLP.topMargin = safeTopMargin;
                layoutParamsChanged = true;
            }

            if (contentLP.leftMargin != mSystemBarInsets.left
                    || contentLP.rightMargin != mSystemBarInsets.right) {
                contentLP.leftMargin = mSystemBarInsets.left;
                contentLP.rightMargin = mSystemBarInsets.right;
                layoutParamsChanged = true;
            }

            if (contentLP.gravity != mGravity) {
                contentLP.gravity = mGravity;
                layoutParamsChanged = true;
            }

            /**
             * Only apply the layout params if we've actually changed them, otherwise we'll get stuck in a layout loop
             */
            if (layoutParamsChanged)
                mContentBox.setLayoutParams(contentLP);

            updateToolTip();
        }
    }

    void updateToolTip() {
        /**
         * Adjust tooltip gravity if needed
         */
        if (toolTip != null && active && mTarget != null && mShape != null) {

            if (!toolTipShown) {
                toolTipShown = true;

                int shapeDiameter = mShape.getTotalRadius() * 2;
                int toolTipDistance = (shapeDiameter - mTarget.getBounds().height()) / 2;
                toolTipDistance += tooltipMargin;

                toolTip.show(toolTipDistance);
            }

            if (mGravity == Gravity.BOTTOM) {
                toolTip.position(ShowcaseTooltip.Position.TOP);
            } else {
                toolTip.position(ShowcaseTooltip.Position.BOTTOM);
            }
        }
    }

    /**
     * SETTERS
     */

    void setPosition(Point point) {
        setPosition(point.x, point.y);
    }

    void setPosition(int x, int y) {
        mXPosition = x;
        mYPosition = y;
    }

    // ObjectAnimator uses these properties to animate the showcase position.
    public int getShowcaseX() {
        return mXPosition;
    }

    public void setShowcaseX(int x) {
        mXPosition = x;
        invalidate();
    }

    public int getShowcaseY() {
        return mYPosition;
    }

    public void setShowcaseY(int y) {
        mYPosition = y;
        invalidate();
    }

    private void setTitleText(CharSequence contentText) {
        if (mTitleTextView != null && !TextUtils.isEmpty(contentText)) {
            mContentTextView.setAlpha(0.5F);
            mTitleTextView.setText(contentText);
        }
    }

    private void setContentText(CharSequence contentText) {
        if (mContentTextView != null) {
            mContentTextView.setText(contentText);
        }
    }


    private void setToolTip(ShowcaseTooltip toolTip) {
        this.toolTip = toolTip;
    }


    private void setIsSequence(Boolean isSequenceB) {
        isSequence = isSequenceB;
    }

    private void setDismissText(CharSequence dismissText) {
        if (mDismissButton != null) {
            mDismissButton.setText(dismissText);
            updateDismissButton();
        }
    }

    private void setSkipText(CharSequence skipText) {
        if (mSkipButton != null) {
            mSkipButton.setText(skipText);
            updateSkipButton();
        }
    }

    private void setDismissStyle(Typeface dismissStyle) {
        if (mDismissButton != null) {
            mDismissButton.setTypeface(dismissStyle);
            updateDismissButton();
        }
    }

    private void setSkipStyle(Typeface skipStyle) {
        if (mSkipButton != null) {
            mSkipButton.setTypeface(skipStyle);
            updateSkipButton();
        }
    }

    private void setTitleTextColor(int textColour) {
        if (mTitleTextView != null) {
            mTitleTextView.setTextColor(textColour);
        }
    }

    private void setContentTextColor(int textColour) {
        if (mContentTextView != null) {
            mContentTextView.setTextColor(textColour);
        }
    }

    private void setDismissTextColor(int textColour) {
        if (mDismissButton != null) {
            mDismissButton.setTextColor(textColour);
        }
    }

    private void setShapePadding(int padding) {
        mShapePadding = padding;
        if (mShape != null) mShape.setPadding(padding);
        setTarget(mTarget);
        invalidate();
    }

    private void setTooltipMargin(int margin) {
        tooltipMargin = margin;
    }

    private void setDismissOnTouch(boolean dismissOnTouch) {
        mDismissOnTouch = dismissOnTouch;
    }

    private void setShouldRender(boolean shouldRender) {
        mShouldRender = shouldRender;
    }

    private void setMaskColour(int maskColour) {
        mMaskColour = maskColour;
    }

    private void setDelay(long delayInMillis) {
        mDelayInMillis = delayInMillis;
    }

    private void setFadeDuration(long fadeDurationInMillis) {
        mFadeDurationInMillis = fadeDurationInMillis;
    }

    private void setTargetTouchable(boolean targetTouchable) {
        mTargetTouchable = targetTouchable;
    }

    private void setDismissOnTargetTouch(boolean dismissOnTargetTouch) {
        mDismissOnTargetTouch = dismissOnTargetTouch;
    }

    private void setUseFadeAnimation(boolean useFadeAnimation) {
        mUseFadeAnimation = useFadeAnimation;
    }

    public void addShowcaseListener(IShowcaseListener showcaseListener) {
        if (showcaseListener != null && !mListeners.contains(showcaseListener))
            mListeners.add(showcaseListener);
    }

    public void removeShowcaseListener(IShowcaseListener listener) { mListeners.remove(listener); }

    /** @deprecated Use removeShowcaseListener(IShowcaseListener). */
    @Deprecated
    public void removeShowcaseListener(MaterialShowcaseSequence showcaseListener) {

        if ((mListeners != null) && mListeners.contains(showcaseListener)) {
            mListeners.remove(showcaseListener);
        }
    }

    void setDetachedListener(IDetachedListener detachedListener) {
        mDetachedListener = detachedListener;
    }

    public void setShape(Shape mShape) {
        this.mShape = mShape;
        if (mShape != null) mShape.setPadding(mShapePadding);
        setTarget(mTarget);
        invalidate();
    }

    public void setAnimationFactory(IAnimationFactory animationFactory) {
        this.mAnimationFactory = animationFactory;
    }

    /**
     * Set properties based on a config object
     *
     * @param config
     */
    public void setConfig(ShowcaseConfig config) {

        if (config.getDelay() > -1) {
            setDelay(config.getDelay());
        }

        if (config.getFadeDuration() >= 0) {
            setFadeDuration(config.getFadeDuration());
        }

        setContentTextColor(config.getContentTextColor());

        setDismissTextColor(config.getDismissTextColor());

        setMaskColour(config.getMaskColor());

        if (config.getDismissTextStyle() != null) {
            setDismissStyle(config.getDismissTextStyle());
        }

        if (config.getShape() != null) {
            setShape(config.getShape());
        }

        if (config.getShapePadding() > -1) {
            setShapePadding(config.getShapePadding());
        }

        if (config.getRenderOverNavigationBar() != null) {
            setRenderOverNavigationBar(config.getRenderOverNavigationBar());
        }
    }

    void updateDismissButton() {
        // hide or show button
        if (mDismissButton != null) {
            if (TextUtils.isEmpty(mDismissButton.getText())) {
                mDismissButton.setVisibility(GONE);
            } else {
                mDismissButton.setVisibility(VISIBLE);
            }
        }
    }

    void updateSkipButton() {
        // hide or show button
        if (mSkipButton != null) {
            if (TextUtils.isEmpty(mSkipButton.getText())) {
                mSkipButton.setVisibility(GONE);
            } else {
                mSkipButton.setVisibility(VISIBLE);
            }
        }
    }

    public boolean hasFired() {
        return mPrefsManager != null && mPrefsManager.hasFired();
    }

    /**
     * REDRAW LISTENER - this ensures we redraw after activity finishes laying out
     */
    private class UpdateOnGlobalLayout implements ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener {

        @Override
        public void onGlobalLayout() {
            setTarget(mTarget);
        }

        @Override public boolean onPreDraw() {
            // Property animations and scrolling can move a target without a layout pass.
            // Only refresh changed bounds so explicit showcase-position animations still work.
            final long token = generation;
            try {
                if (active && !hiding && mTarget instanceof ViewTarget) {
                    ViewTarget target = (ViewTarget) mTarget;
                    if (getVisibility() == VISIBLE && (!target.isReady()
                            || target.getView().getWindowToken() != getWindowToken())) {
                        removeFromWindow();
                    } else if (!lastTargetBounds.equals(target.getBounds())) setTarget(target);
                }
            } catch (RuntimeException error) { throw presentationFailure(token, error); }
            return true;
        }
    }


    /**
     * BUILDER CLASS
     * Gives us a builder utility class with a fluent API for eaily configuring showcase views
     */
    public static class Builder {
        private static final int CIRCLE_SHAPE = 0;
        private static final int RECTANGLE_SHAPE = 1;
        private static final int NO_SHAPE = 2;
        private static final int OVAL_SHAPE = 3;

        private boolean fullWidth = false;
        private int shapeType = CIRCLE_SHAPE;

        final MaterialShowcaseView showcaseView;

        private final Activity activity;

        public Builder(Activity activity) {
            this.activity = activity;

            showcaseView = new MaterialShowcaseView(activity);
        }

        /**
         * Enforces a user-specified gravity instead of relying on the library to do that.
         */
        public Builder setGravity(int gravity) {
            showcaseView.setGravity(gravity);
            return this;
        }

        /**
         * Set the title text shown on the ShowcaseView.
         */
        public Builder setTarget(View target) {
            showcaseView.setTarget(target == null ? null : new ViewTarget(target));
            return this;
        }

        public Builder setSequence(Boolean isSequence) {
            showcaseView.setIsSequence(isSequence);
            return this;
        }

        /**
         * Set the dismiss button properties
         */
        public Builder setDismissText(int resId) {
            return setDismissText(activity.getString(resId));
        }

        public Builder setDismissText(CharSequence dismissText) {
            showcaseView.setDismissText(dismissText);
            return this;
        }

        public Builder setDismissStyle(Typeface dismissStyle) {
            showcaseView.setDismissStyle(dismissStyle);
            return this;
        }


        /**
         * Set the skip button properties
         */
        public Builder setSkipText(int resId) {
            return setSkipText(activity.getString(resId));
        }

        public Builder setSkipText(CharSequence skipText) {
            showcaseView.setSkipText(skipText);
            return this;
        }

        public Builder setSkipStyle(Typeface skipStyle) {
            showcaseView.setSkipStyle(skipStyle);
            return this;
        }

        /**
         * Set the content text shown on the ShowcaseView.
         */
        public Builder setContentText(int resId) {
            return setContentText(activity.getString(resId));
        }

        /**
         * Set the descriptive text shown on the ShowcaseView.
         */
        public Builder setContentText(CharSequence text) {
            showcaseView.setContentText(text);
            return this;
        }

        /**
         * Set the title text shown on the ShowcaseView.
         */
        public Builder setTitleText(int resId) {
            return setTitleText(activity.getString(resId));
        }

        /**
         * Set the descriptive text shown on the ShowcaseView as the title.
         */
        public Builder setTitleText(CharSequence text) {
            showcaseView.setTitleText(text);
            return this;
        }


        /**
         * Tooltip mode config options
         *
         * @param toolTip
         */
        public Builder setToolTip(ShowcaseTooltip toolTip) {
            showcaseView.setToolTip(toolTip);
            return this;
        }


        /**
         * Set whether or not the target view can be touched while the showcase is visible.
         * <p>
         * False by default.
         */
        public Builder setTargetTouchable(boolean targetTouchable) {
            showcaseView.setTargetTouchable(targetTouchable);
            return this;
        }

        /**
         * Set whether or not the showcase should dismiss when the target is touched.
         * <p>
         * True by default.
         */
        public Builder setDismissOnTargetTouch(boolean dismissOnTargetTouch) {
            showcaseView.setDismissOnTargetTouch(dismissOnTargetTouch);
            return this;
        }

        public Builder setDismissOnTouch(boolean dismissOnTouch) {
            showcaseView.setDismissOnTouch(dismissOnTouch);
            return this;
        }

        public Builder setMaskColour(int maskColour) {
            showcaseView.setMaskColour(maskColour);
            return this;
        }

        public Builder setTitleTextColor(int textColour) {
            showcaseView.setTitleTextColor(textColour);
            return this;
        }

        public Builder setContentTextColor(int textColour) {
            showcaseView.setContentTextColor(textColour);
            return this;
        }

        public Builder setDismissTextColor(int textColour) {
            showcaseView.setDismissTextColor(textColour);
            return this;
        }

        public Builder setDelay(int delayInMillis) {
            showcaseView.setDelay(delayInMillis);
            return this;
        }

        public Builder setFadeDuration(int fadeDurationInMillis) {
            showcaseView.setFadeDuration(fadeDurationInMillis);
            return this;
        }

        public Builder setListener(IShowcaseListener listener) {
            showcaseView.addShowcaseListener(listener);
            return this;
        }

        public Builder singleUse(String showcaseID) {
            showcaseView.singleUse(showcaseID);
            return this;
        }

        public Builder setShape(Shape shape) {
            showcaseView.setShape(shape);
            return this;
        }

        public Builder withCircleShape() {
            shapeType = CIRCLE_SHAPE;
            return this;
        }

        public Builder withOvalShape() {
            shapeType = OVAL_SHAPE;
            return this;
        }

        public Builder withoutShape() {
            shapeType = NO_SHAPE;
            return this;
        }

        public Builder setShapePadding(int padding) {
            showcaseView.setShapePadding(padding);
            return this;
        }

        public Builder setTooltipMargin(int margin) {
            showcaseView.setTooltipMargin(margin);
            return this;
        }

        public Builder withRectangleShape() {
            return withRectangleShape(false);
        }

        public Builder withRectangleShape(boolean fullWidth) {
            this.shapeType = RECTANGLE_SHAPE;
            this.fullWidth = fullWidth;
            return this;
        }

        public Builder renderOverNavigationBar() {
            // Note: This only has an effect in Lollipop or above.
            showcaseView.setRenderOverNavigationBar(true);
            return this;
        }

        public Builder useFadeAnimation() {
            showcaseView.setUseFadeAnimation(true);
            return this;
        }

        public MaterialShowcaseView build() {
            if (showcaseView.mTarget == null) shapeType = NO_SHAPE;
            if (showcaseView.mShape == null) {
                switch (shapeType) {
                    case RECTANGLE_SHAPE: {
                        showcaseView.setShape(new RectangleShape(showcaseView.mTarget.getBounds(), fullWidth));
                        break;
                    }
                    default:
                    case CIRCLE_SHAPE: {
                        showcaseView.setShape(new CircleShape(showcaseView.mTarget));
                        break;
                    }
                    case NO_SHAPE: {
                        showcaseView.setShape(new NoShape());
                        break;
                    }
                    case OVAL_SHAPE: {
                        showcaseView.setShape(new OvalShape(showcaseView.mTarget));
                        break;
                    }
                }
            }

            if (showcaseView.mAnimationFactory == null) {
                // create our animation factory
                if (!showcaseView.mUseFadeAnimation) {
                    showcaseView.setAnimationFactory(new CircularRevealAnimationFactory());
                } else {
                    showcaseView.setAnimationFactory(new FadeAnimationFactory());
                }
            }

            showcaseView.mShape.setPadding(showcaseView.mShapePadding);

            return showcaseView;
        }

        public MaterialShowcaseView show() {
            build().show(activity);
            return showcaseView;
        }
    }

    private void singleUse(String showcaseID) {
        mSingleUse = true;
        mPrefsManager = new PrefsManager(getContext(), showcaseID);
    }

    public void removeFromWindow() {
        checkMainThread();
        if (removing) return;
        removing = true;
        if (!committing) mWasDismissed = mWasSkipped = false;
        generation++; active = false; mShouldRender = false; tap = false; touchTarget = null;
        removePendingShowListener();
        if (mHandler != null) mHandler.removeCallbacksAndMessages(null);
        // Detachment replaces this view's tree observer. Unregister from the window first.
        ViewTreeObserver tree = getViewTreeObserver();
        if (mLayoutListener != null && tree.isAlive()) {
            tree.removeOnGlobalLayoutListener(mLayoutListener);
            tree.removeOnPreDrawListener(mLayoutListener);
        }
        mLayoutListener = null;
        RuntimeException failure = null;
        try {
            failure = cleanup(failure, () -> {
                if (mAnimationFactory instanceof CancellableAnimationFactory)
                    ((CancellableAnimationFactory) mAnimationFactory).cancel(this);
            });
            failure = cleanup(failure, () -> animate().cancel());
            failure = cleanup(failure, () -> { if (toolTip != null) toolTip.cancel(); });
            failure = cleanup(failure, () -> {
                // Window teardown traverses the parent's children itself. Mutating that
                // list from onDetachedFromWindow can crash Android's traversal.
                if (detaching) setVisibility(GONE);
                else if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
            });
        } finally {
            if (mBitmap != null) { mBitmap.recycle(); mBitmap = null; }
            mEraser = null; mCanvas = null; mHandler = null; removing = false;
        }
        if (failure != null) throw failure;
    }

    private static RuntimeException cleanup(RuntimeException failure, Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) {
            if (failure == null) return error;
            if (failure != error) failure.addSuppressed(error);
        }
        return failure;
    }

    private void removePendingShowListener() {
        if (mPendingShowListener != null && getViewTreeObserver().isAlive())
            getViewTreeObserver().removeOnPreDrawListener(mPendingShowListener);
        mPendingShowListener = null;
    }

    private boolean targetBelongsToWindow(View windowRoot) {
        if (!(mTarget instanceof ViewTarget)) return true;
        View view = ((ViewTarget) mTarget).getView();
        while (true) {
            if (view.getVisibility() != VISIBLE || view.getAlpha() <= 0) return false;
            if (view == windowRoot) return true;
            if (!(view.getParent() instanceof View)) return false;
            view = (View) view.getParent();
        }
    }


    /**
     * Request presentation after the configured delay and first layout. Returns whether the request was accepted.
     *
     * @param activity
     * @return
     */
    public boolean show(final Activity activity) {
        checkMainThread();
        if (active || removing || detaching || activity.isFinishing()
                || activity.isDestroyed()) return false;
        if (toolTip != null && !(mTarget instanceof ViewTarget)) {
            throw new IllegalArgumentException("The target must be of type: " + ViewTarget.class.getCanonicalName());
        }

        /**
         * if we're in single use mode and have already shot our bolt then do nothing
         */
        if (mSingleUse) {
            if (mPrefsManager.hasFired()) {
                return false;
            }
        }

        // onCreate callers have a valid hierarchy before attachment/measurement. Validate
        // ownership now and defer geometry checks until the first layout and requested delay.
        if (!targetBelongsToWindow(activity.getWindow().getDecorView())) return false;
        active = true; hiding = false; notified = false; displayNotified = false; toolTipShown = false;
        mWasDismissed = mWasSkipped = false;
        final long token = ++generation;
        setVisibility(INVISIBLE); setAlpha(1);
        if (mLayoutListener == null) {
            mLayoutListener = new UpdateOnGlobalLayout(); getViewTreeObserver().addOnGlobalLayoutListener(mLayoutListener);
            getViewTreeObserver().addOnPreDrawListener(mLayoutListener);
        }

        ((ViewGroup) activity.getWindow().getDecorView()).addView(this);
        requestApplyInsets();

        setShouldRender(true);


        if (toolTip != null) {

            ViewTarget viewTarget = (ViewTarget) mTarget;

            toolTip.configureTarget(this, viewTarget.getView());

        }


        mHandler = new Handler(Looper.getMainLooper());
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!active || hiding || generation != token) return;
                if (getWidth() <= 0 || getHeight() <= 0) {
                    removePendingShowListener();
                    mPendingShowListener = () -> {
                        removePendingShowListener();
                        run();
                        return true;
                    };
                    getViewTreeObserver().addOnPreDrawListener(mPendingShowListener);
                    return;
                }
                if (!isAttachedToWindow() || !targetBelongsToWindow(activity.getWindow().getDecorView())
                        || (mTarget instanceof ViewTarget && !((ViewTarget) mTarget).isReady())) { removeFromWindow(); return; }
                if (mTarget != null) {
                    setTarget(mTarget);
                }
                if (mShouldAnimate) {
                    fadeIn();
                } else {
                    setVisibility(VISIBLE);
                    notifyOnDisplayed();
                }
            }
        }, mDelayInMillis);

        updateDismissButton();

        return true;
    }


    public void hide() {
        checkMainThread();
        if (!active || hiding) return;
        hiding = true;

        /**
         * This flag is used to indicate to onDetachedFromWindow that the showcase view was dismissed purposefully (by the user or programmatically)
         */
        mWasDismissed = true;

        if (mShouldAnimate) {
            animateOut();
        } else {
            finishDismissal();
        }
    }


    public void skip() {
        checkMainThread();
        if (!active || hiding) return;
        hiding = true;

        /**
         * This flag is used to indicate to onDetachedFromWindow that the showcase view was skipped purposefully (by the user or programmatically)
         */
        mWasSkipped = true;

        if (mShouldAnimate) {
            animateOut();
        } else {
            finishDismissal();
        }
    }

    public void fadeIn() {
        final long token = generation;
        setVisibility(INVISIBLE);
        try {
            mAnimationFactory.animateInView(this, mTarget == null ? new Point(getWidth()/2, getHeight()/2) : mTarget.getPoint(), mFadeDurationInMillis,
                    new IAnimationFactory.AnimationStartListener() {
                        @Override
                        public void onAnimationStart() {
                            if (!active || generation != token || hiding) return;
                            setVisibility(View.VISIBLE);
                            try { notifyOnDisplayed(); }
                            catch (RuntimeException error) { throw presentationFailure(token, error); }
                        }
                    }
            );
        } catch (RuntimeException error) { throw presentationFailure(token, error); }
    }

    private RuntimeException presentationFailure(long token, RuntimeException error) {
        // Application callbacks may have started another presentation before throwing.
        return generation == token ? cleanup(error, this::removeFromWindow) : error;
    }

    Runnable capturePresentationRemoval() {
        final long token = generation;
        return () -> { if (generation == token) removeFromWindow(); };
    }

    public void animateOut() {
        final long token = generation;

        if (mAnimationFactory == null || mTarget == null) {
            finishDismissal();
            return;
        }

        try {
            mAnimationFactory.animateOutView(this, mTarget.getPoint(), mFadeDurationInMillis, new IAnimationFactory.AnimationEndListener() {
                @Override
                public void onAnimationEnd() {
                    if (!active || generation != token) return;
                    setVisibility(INVISIBLE);
                    finishDismissal();
                }
            });
        } catch (RuntimeException error) { throw presentationFailure(token, error); }
    }

    private void finishDismissal() {
        committing = true;
        try {
            try { removeFromWindow(); }
            catch (RuntimeException error) {
                mWasDismissed = mWasSkipped = false;
                // Intentional removal suppresses the detach callback. On cleanup failure,
                // release sequence ownership explicitly without committing its progress.
                throw cleanup(error, () -> {
                    if (mDetachedListener != null) mDetachedListener.onShowcaseDetached(this, false, false);
                });
            }
            if (mSingleUse && mPrefsManager != null) mPrefsManager.setFired();
            notifyOnDismissed();
        }
        finally { committing = false; }
    }

    public void resetSingleUse() {
        if (mSingleUse && mPrefsManager != null) mPrefsManager.resetShowcase();
    }

    /**
     * Static helper method for resetting single use flag
     *
     * @param context
     * @param showcaseID
     */
    public static void resetSingleUse(Context context, String showcaseID) {
        PrefsManager.resetShowcase(context, showcaseID);
    }

    /**
     * Static helper method for resetting all single use flags
     *
     * @param context
     */
    public static void resetAll(Context context) {
        PrefsManager.resetAll(context);
    }


    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        updateSystemBarInsets(insets);
        if (mTarget != null) {
            setTarget(mTarget);
        } else {
            applyLayoutParams();
        }
        return insets;
    }

    private void updateSystemBarInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            mSystemBarInsets.set(bars.left, bars.top, bars.right, bars.bottom);
            mNavigationBarBottomInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        } else {
            mSystemBarInsets.set(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            mNavigationBarBottomInset = insets.getSystemWindowInsetBottom();
        }
    }

    public int getSoftButtonsBarSizePort() {
        WindowInsets insets = getRootWindowInsets();
        if (insets != null) {
            updateSystemBarInsets(insets);
        }
        return mNavigationBarBottomInset;
    }

    private void setRenderOverNavigationBar(boolean mRenderOverNav) {
        this.mRenderOverNav = mRenderOverNav;
    }
}
