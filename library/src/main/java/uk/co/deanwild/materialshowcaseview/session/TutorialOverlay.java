package uk.co.deanwild.materialshowcaseview.session;

import android.animation.*;
import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.view.*;
import android.widget.*;
import java.util.*;
import uk.co.deanwild.materialshowcaseview.R;

/** Standard accessible controls over an allocation-free mask. Owned exclusively by one host. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic per-session view, never inflated.
final class TutorialOverlay extends FrameLayout implements Cancellation {
    interface Target { View get(); }
    interface Targets { List<View> get(); }
    Targets additionalTargets = Collections::emptyList;
    private final ViewGroup root;
    private final Target target;
    private final Step step;
    private final TutorialHost.Actions actions;
    private final TutorialTheme theme;
    private final TutorialHost.Callbacks callbacks;
    private final ScrollView panel;
    private final TextView progress;
    private final Path mask = new Path();
    private final RectF hole = new RectF();
    private final RectF highlightBounds = new RectF(), extraHole = new RectF();
    private final Rect visibleTarget = new Rect();
    private final Rect viewport = new Rect();
    private final int[] origin = new int[2];
    private final Map<View, Integer> accessibility = new IdentityHashMap<>();
    private final Map<ViewGroup, Integer> focusGroups = new IdentityHashMap<>();
    private final Map<View, Boolean> focusable = new IdentityHashMap<>();
    private final View previousFocus;
    private final View previousAccessibilityFocus;
    private Animator animator;
    private boolean closed, exiting, tap, panelGesture;
    private View gestureTarget;
    private final Rect gestureBounds = new Rect();
    private final Rect gestureTargetBounds = new Rect();
    private float downX, downY;
    private Runnable reveal;
    TutorialOverlay(ViewGroup root, Target target, Step step, TutorialHost.Actions actions, TutorialTheme theme) {
        this(root, target, step, actions, theme, Runnable::run);
    }
    TutorialOverlay(ViewGroup root, Target target, Step step, TutorialHost.Actions actions, TutorialTheme theme, TutorialHost.Callbacks callbacks) {
        super(root.getContext()); this.root = root; this.target = target; this.step = step; this.actions = actions; this.theme = theme;
        this.callbacks = callbacks;
        previousFocus = root.findFocus();
        previousAccessibilityFocus = Build.VERSION.SDK_INT >= 21 ? accessibilityFocus(root) : null;
        setWillNotDraw(false); setFocusable(true);
        if (Build.VERSION.SDK_INT < 18) setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        panel = new ScrollView(getContext()); panel.setFillViewport(false); panel.setBackgroundColor(theme.surfaceColor);
        LinearLayout content = new LinearLayout(getContext()); content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(theme.paddingDp); content.setPadding(padding, padding, padding, padding);
        panel.addView(content, new ScrollView.LayoutParams(-1, -2));
        progress = text(""); progress.setVisibility(GONE); content.addView(progress);
        if (theme.contentFactory != null) content.addView(theme.contentFactory.create(getContext(), step));
        else {
            TextView title = text(step.title); title.setTypeface(null, Typeface.BOLD);
            if(theme.titleTextAppearance!=0)title.setTextAppearance(getContext(),theme.titleTextAppearance);content.addView(title);
            content.addView(text(step.text));
        }
        if (theme.showPrevious) button(content, theme.previous, R.string.showcase_previous, () -> navigate(theme.previousAction));
        if (theme.showNext && step.interaction != Step.Interaction.APPLICATION_ACTION && step.interaction != Step.Interaction.TARGET_ACTION)
            button(content, theme.next, R.string.showcase_next, () -> navigate(theme.nextAction));
        if (step.interaction == Step.Interaction.TARGET_ACTION)
            button(content, null, R.string.showcase_target_action, () -> activateTarget(null, null, null));
        if (theme.showSkipStep) button(content, theme.skipStep, R.string.showcase_skip_step, () -> navigate(theme.skipStepAction));
        if (theme.showSkipTour) button(content, theme.skipTour, R.string.showcase_skip_tour, () -> navigate(theme.skipTourAction));
        if (theme.showClose) button(content, theme.close, R.string.showcase_close, () -> navigate(theme.closeAction));
        addView(panel, new LayoutParams(-1, -2, Gravity.BOTTOM));
    }
    private TextView text(CharSequence text) {
        TextView view = new TextView(getContext()); view.setText(text); view.setTextColor(theme.textColor); view.setTextSize(theme.textSizeSp);
        if(theme.contentTextAppearance!=0)view.setTextAppearance(getContext(),theme.contentTextAppearance);
        view.setFocusable(true); return view;
    }
    private void button(LinearLayout content, CharSequence label, int resource, Runnable action) {
        Button button = new Button(getContext()); if (Build.VERSION.SDK_INT >= 14) button.setAllCaps(false); button.setText(label == null ? getContext().getString(resource) : label);
        button.setMinHeight(dp(48)); button.setTextColor(theme.textColor); button.setOnClickListener(v -> callbacks.run(() -> { if (!closed && !exiting) action.run(); })); content.addView(button);
        if(theme.buttonTextAppearance!=0)button.setTextAppearance(getContext(),theme.buttonTextAppearance);
    }
    private void navigate(TutorialTheme.Navigation action) {
        if(action==null)return;
        switch(action){case PREVIOUS:actions.previous();break;case NEXT:actions.next();break;
            case SKIP_STEP:actions.skipStep();break;case SKIP_TOUR:actions.skipTour();break;case CLOSE:actions.close();break;default:break;}
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    void setProgress(int position, int count) {
        if (theme.showProgress && count > 0) { progress.setText(getResources().getQuantityString(R.plurals.showcase_progress, count, position, count)); progress.setVisibility(VISIBLE); }
    }
    void attach(Runnable shown) {
        protectBackground();
        setVisibility(INVISIBLE); root.addView(this, new ViewGroup.LayoutParams(-1, -1));
        reveal = () -> callbacks.run(() -> {
            if (closed) return;
            repositionNow(); if (closed) return;
            setVisibility(VISIBLE);
            if (step.interaction != Step.Interaction.HINT) panel.requestFocus();
            if (closed) return;
            if (Build.VERSION.SDK_INT >= 16) announceForAccessibility(step.title + ". " + step.text);
            // Actual shown means attached and visible at entrance start, including zero duration.
            animateAlpha(0, 1, null); shown.run();
        });
        post(reveal);
    }
    private void protectBackground() {
        if (step.interaction != Step.Interaction.HINT) {
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i); if (child == this || focusable.containsKey(child)) continue;
                focusable.put(child, child.isFocusable()); child.setFocusable(false);
                if (child instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) child; focusGroups.put(group, group.getDescendantFocusability());
                    group.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                }
                if (Build.VERSION.SDK_INT >= 16) {
                    accessibility.put(child, child.getImportantForAccessibility());
                    hideAccessibility(child);
                }
            }
        }
    }
    private void hideAccessibility(View view) {
        if (Build.VERSION.SDK_INT < 16) return;
        if (!accessibility.containsKey(view)) accessibility.put(view, view.getImportantForAccessibility());
        view.setImportantForAccessibility(Build.VERSION.SDK_INT >= 19 ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (Build.VERSION.SDK_INT < 19 && view instanceof ViewGroup)
            for (int i=0;i<((ViewGroup)view).getChildCount();i++) hideAccessibility(((ViewGroup)view).getChildAt(i));
    }
    @androidx.annotation.RequiresApi(21)
    private View accessibilityFocus(View view) {
        if (view.isAccessibilityFocused()) return view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View result=accessibilityFocus(((ViewGroup)view).getChildAt(i)); if(result!=null)return result;
        }
        return null;
    }
    void reposition() {
        callbacks.run(this::repositionNow);
    }
    private void repositionNow() {
        if (closed) return;
        protectBackground();
        getLocationOnScreen(origin); TargetGeometry.usableOnScreen(root, viewport);
        viewport.offset(-origin[0], -origin[1]);
        updateHole(target.get(), hole);
        highlightBounds.set(hole);
        mask.rewind(); mask.setFillType(Path.FillType.WINDING);
        if (!hole.isEmpty()) appendHole(hole);
        for (View extra : additionalTargets.get()) {
            updateHole(extra, extraHole);
            if (!extraHole.isEmpty()) { appendHole(extraHole); highlightBounds.union(extraHole); }
        }
        int above = highlightBounds.isEmpty() ? 0 : Math.max(0, (int) highlightBounds.top - viewport.top);
        int below = highlightBounds.isEmpty() ? viewport.height() : Math.max(0, viewport.bottom - (int) Math.ceil(highlightBounds.bottom));
        int left = highlightBounds.isEmpty() ? 0 : Math.max(0, (int) highlightBounds.left - viewport.left);
        int right = highlightBounds.isEmpty() ? 0 : Math.max(0, viewport.right - (int) Math.ceil(highlightBounds.right));
        boolean beside = Math.max(left, right) >= dp(240) && Math.max(above, below) < viewport.height() / 2;
        int cardWidth = beside ? Math.max(left, right) : viewport.width();
        boolean top = above > below;
        int space = beside ? viewport.height() : Math.max(above, below);
        // When neither side fits essential controls, use a scrollable half-window card.
        if (space < dp(144)) space = Math.max(dp(48), viewport.height() / 2);
        space = Math.max(0, Math.min(space, viewport.height()));
        panel.measure(MeasureSpec.makeMeasureSpec(Math.max(0, cardWidth), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(space, MeasureSpec.AT_MOST));
        LayoutParams lp = (LayoutParams) panel.getLayoutParams();
        int height = Math.min(space, panel.getMeasuredHeight());
        int y = top ? viewport.top : viewport.bottom - height;
        int x = beside && right > left ? viewport.right - cardWidth : viewport.left;
        if (lp.width != cardWidth || lp.height != height || lp.topMargin != y || lp.leftMargin != x) {
            lp.gravity = Gravity.TOP | Gravity.LEFT; lp.width = Math.max(0, cardWidth); lp.height = height;
            lp.topMargin = y; lp.leftMargin = x; panel.setLayoutParams(lp);
        }
        invalidate();
    }
    private void updateHole(View view, RectF bounds) {
        bounds.setEmpty();
        if (view == null || !TargetGeometry.visibleOnScreen(view, visibleTarget)) return;
        visibleTarget.offset(-origin[0], -origin[1]);
        if (!visibleTarget.intersect(viewport)) return;
        bounds.set(visibleTarget);
        float padding = dp(theme.paddingDp / 2);
        bounds.inset(-padding, -padding);
        if (!bounds.intersect(viewport.left, viewport.top, viewport.right, viewport.bottom)) bounds.setEmpty();
    }
    private void appendHole(RectF bounds) {
        if (theme.highlightShape == null) mask.addRoundRect(bounds, dp(theme.cornerDp), dp(theme.cornerDp), Path.Direction.CW);
        else theme.highlightShape.path(mask, bounds);
    }
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        boolean[] measured = {false};
        callbacks.run(() -> { super.onMeasure(widthMeasureSpec, heightMeasureSpec); measured[0] = true; });
        // A cancelled or failed asynchronous layout still has to satisfy View.measure's contract.
        if (!measured[0]) setMeasuredDimension(0, 0);
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        callbacks.run(() -> super.onLayout(changed, left, top, right, bottom));
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { reposition(); }
    @Override protected void onDraw(Canvas canvas) {
        callbacks.run(() -> {
            if (closed || step.interaction == Step.Interaction.HINT) return;
            int saved = canvas.save();
            try { canvas.clipPath(mask, Region.Op.DIFFERENCE); canvas.drawColor(theme.maskColor); }
            finally { canvas.restoreToCount(saved); }
        });
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        callbacks.run(() -> {
            int saved = canvas.save();
            try { super.dispatchDraw(canvas); }
            finally { canvas.restoreToCount(saved); }
        });
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) panelGesture = insidePanel(event);
        // A nonmodal hint decides ownership on DOWN, then delivers the whole gesture
        // to its controls, including UP/CANCEL outside the panel's current bounds.
        if (step.interaction == Step.Interaction.HINT && !panelGesture) return false;
        boolean[] handled = {true};
        callbacks.run(() -> { if (!closed && !exiting) handled[0] = super.dispatchTouchEvent(event); });
        return handled[0];
    }
    private boolean insidePanel(MotionEvent e) { return e.getX() >= panel.getLeft() && e.getX() < panel.getRight() && e.getY() >= panel.getTop() && e.getY() < panel.getBottom(); }
    @android.annotation.SuppressLint("ClickableViewAccessibility") // handleTouch calls performClick; target actions also have an accessible Button.
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (closed || exiting) return true;
        callbacks.run(() -> handleTouch(e));
        return true;
    }
    private void handleTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tap = true; downX = e.getX(); downY = e.getY();
                gestureTarget = step.interaction == Step.Interaction.TARGET_ACTION ? target.get() : null;
                gestureBounds.setEmpty();
                if (gestureTarget != null) {
                    TargetGeometry.visibleOnScreen(gestureTarget, gestureBounds);
                    TargetGeometry.boundsOnScreen(gestureTarget, gestureTargetBounds);
                    getLocationOnScreen(origin);
                    tap = gestureBounds.contains((int) downX + origin[0], (int) downY + origin[1]);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) tap = false; break;
            case MotionEvent.ACTION_POINTER_DOWN: case MotionEvent.ACTION_CANCEL: tap = false; break;
            case MotionEvent.ACTION_UP:
                boolean clicked = tap && Math.hypot(e.getX() - downX, e.getY() - downY) <= ViewConfiguration.get(getContext()).getScaledTouchSlop(); tap = false;
                if (clicked && step.interaction == Step.Interaction.BACKGROUND_TAP) performClick();
                else if (clicked && step.interaction == Step.Interaction.TARGET_ACTION && hole.contains(downX, downY) && hole.contains(e.getX(), e.getY())) {
                    getLocationOnScreen(origin);
                    if (gestureTarget != null && gestureBounds.contains((int) e.getX() + origin[0], (int) e.getY() + origin[1]))
                        activateTarget(gestureTarget, new Rect(gestureBounds), new Rect(gestureTargetBounds));
                }
                break;
        }
    }
    private void activateTarget(View expected, Rect expectedBounds, Rect expectedTargetBounds) {
        actions.activateTarget(() -> {
            if (closed || exiting) return;
            View view = target.get();
            if (view == null || (expected != null && view != expected) || !view.isEnabled() || !view.isClickable()
                    || !TargetValidator.valid(view, root, step, true)) return;
            Rect current = new Rect();
            if (!TargetGeometry.visibleOnScreen(view, current) || (expectedBounds != null && !expectedBounds.equals(current))) return;
            TargetGeometry.boundsOnScreen(view, current);
            if (expectedTargetBounds != null && !expectedTargetBounds.equals(current)) return;
            view.performClick();
        });
    }
    @Override public boolean performClick() {
        super.performClick(); if (!closed && !exiting && step.interaction == Step.Interaction.BACKGROUND_TAP) actions.next(); return true;
    }
    private void animateAlpha(float from, float to, Runnable complete) {
        if (closed) return;
        if (animator != null) { animator.removeAllListeners(); animator.cancel(); }
        long duration = theme.reducedMotion || (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) ? 0 : theme.animationMillis;
        if (duration <= 0) { setAlpha(to); if (complete != null) complete.run(); return; }
        animator = ObjectAnimator.ofFloat(this, "alpha", from, to); animator.setDuration(duration);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { callbacks.run(() -> { if (!closed && complete != null) complete.run(); }); }
        }); animator.start();
    }
    Cancellation hide(Runnable hidden) { exiting = true; tap = false; gestureTarget = null; animateAlpha(getAlpha(), 0, hidden); return () -> { if (animator != null) { animator.removeAllListeners(); animator.cancel(); } }; }
    @Override public void cancel() {
        if (closed) return; closed = true;
        boolean restoreInputFocus = step.interaction != Step.Interaction.HINT || hasFocus();
        boolean restoreSpokenFocus = step.interaction != Step.Interaction.HINT
                || (Build.VERSION.SDK_INT >= 21 && accessibilityFocus(this) != null);
        if (reveal != null) removeCallbacks(reveal); reveal = null;
        if (animator != null) { animator.removeAllListeners(); animator.cancel(); animator = null; }
        if (getParent() == root) root.removeView(this);
        if (Build.VERSION.SDK_INT >= 16) for (Map.Entry<View, Integer> entry : accessibility.entrySet()) entry.getKey().setImportantForAccessibility(entry.getValue());
        accessibility.clear();
        for (Map.Entry<ViewGroup, Integer> entry : focusGroups.entrySet()) entry.getKey().setDescendantFocusability(entry.getValue());
        for (Map.Entry<View, Boolean> entry : focusable.entrySet()) entry.getKey().setFocusable(entry.getValue());
        focusGroups.clear(); focusable.clear();
        if (restoreInputFocus && previousFocus != null && previousFocus.getWindowToken() != null) previousFocus.requestFocus();
        if (restoreSpokenFocus) restoreAccessibilityFocus();
    }
    @android.annotation.SuppressLint("AccessibilityFocus") // Restore the user's prior focus, never move it during presentation.
    private void restoreAccessibilityFocus() {
        if (Build.VERSION.SDK_INT >= 16 && root.hasWindowFocus() && previousAccessibilityFocus != null && previousAccessibilityFocus.getWindowToken() != null)
            previousAccessibilityFocus.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
    }
}
