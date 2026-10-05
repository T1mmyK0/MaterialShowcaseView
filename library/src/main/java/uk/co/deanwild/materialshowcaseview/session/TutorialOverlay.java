package uk.co.deanwild.materialshowcaseview.session;

import android.animation.*;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
    private final LinearLayout content;
    private final TextView progress;
    private View customContent;
    private View targetTapControl;
    private int customTopMargin;
    private ViewTreeObserver customContentTree;
    private ViewTreeObserver.OnPreDrawListener customContentChanges;
    private final Path mask = new Path();
    private final Path highlightPath = new Path();
    private final Region primaryHighlight = new Region(), viewportRegion = new Region();
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
    private boolean closed, exiting, tap, panelGesture, detaching;
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
        previousAccessibilityFocus = accessibilityFocus(root);
        setWillNotDraw(false); setFocusable(true);
        if (step.interaction == Step.Interaction.BACKGROUND_TAP) {
            // Expose the same continuation to keyboards and accessibility services,
            // including highlight-only steps whose visible controls were omitted.
            setClickable(true);
            setContentDescription(hasText(theme.next) ? theme.next : getContext().getString(R.string.showcase_next));
        }
        panel = new ScrollView(getContext()); panel.setFillViewport(false);
        // Hints have no scrim behind them, so give their text the same contrast locally.
        panel.setBackgroundColor(step.interaction == Step.Interaction.HINT
                && Color.alpha(theme.surfaceColor) == 0 ? theme.maskColor : theme.surfaceColor);
        content = new LinearLayout(getContext()); content.setOrientation(LinearLayout.VERTICAL);
        // Inset the typography independently of the existing highlight padding.
        int horizontalPadding = dp(theme.paddingDp + 16), verticalPadding = dp(theme.paddingDp + 8);
        content.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        // Leading text-action feedback extends into this padding while its label stays aligned.
        content.setClipToPadding(false);
        panel.addView(content, new ScrollView.LayoutParams(-1, -2));
        progress = text(""); progress.setTextSize(theme.textSizeSp * 14 / 18);
        progress.setAlpha(.7f);
        progress.setVisibility(GONE); content.addView(progress);
        if (theme.contentFactory != null) {
            customContent = theme.contentFactory.create(getContext(), step); content.addView(customContent);
            customTopMargin = ((LinearLayout.LayoutParams) customContent.getLayoutParams()).topMargin;
        }
        else {
            TextView title = text(step.title); title.setTextSize(theme.textSizeSp * 32 / 18);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setVisibility(hasText(step.title) ? VISIBLE : GONE);
            if (theme.titleTextAppearance != 0) title.setTextAppearance(getContext(), theme.titleTextAppearance);
            content.addView(title);
            TextView body = text(step.text);
            body.setLineSpacing(dp(4), 1);
            body.setVisibility(hasText(step.text) ? VISIBLE : GONE);
            body.setAlpha(.82f);
            content.addView(body);
        }
        ActionRow navigation = new ActionRow(getContext(), dp(8));
        if (theme.showPrevious && allowsNavigation(theme.previousAction)) button(navigation, theme.previous, R.string.showcase_previous, false, () -> navigate(theme.previousAction));
        navigation.trailingStart = navigation.getChildCount();
        if (theme.showSkipTour && allowsNavigation(theme.skipTourAction)) button(navigation, theme.skipTour, R.string.showcase_skip_tour, false, () -> navigate(theme.skipTourAction));
        if (theme.showNext && step.interaction != Step.Interaction.APPLICATION_ACTION && step.interaction != Step.Interaction.TARGET_ACTION
                && step.interaction != Step.Interaction.TARGET_TAP)
            button(navigation, theme.next, R.string.showcase_next, true, () -> navigate(theme.nextAction));
        if (step.interaction == Step.Interaction.TARGET_ACTION)
            button(navigation, null, R.string.showcase_target_action, true, () -> activateTarget(null, null, null));
        addActions(navigation);
        ActionRow secondary = new ActionRow(getContext(), dp(4));
        if (theme.showSkipStep && allowsNavigation(theme.skipStepAction)) button(secondary, theme.skipStep, R.string.showcase_skip_step, false, () -> navigate(theme.skipStepAction));
        if (theme.showClose && allowsNavigation(theme.closeAction)) button(secondary, theme.close, R.string.showcase_close, false, () -> navigate(theme.closeAction));
        addActions(secondary);
        addView(panel, new LayoutParams(-1, -2, Gravity.BOTTOM));
        if (step.interaction == Step.Interaction.TARGET_TAP) {
            // A transparent control at the highlight gives keyboard/TalkBack users the
            // same target-specific action, without adding another footer button.
            targetTapControl = new View(getContext()) {
                @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }
                @Override public boolean performClick() {
                    callbacks.run(() -> {
                        if (closed || exiting) return;
                        super.performClick(); completeTargetTap(null, null, null);
                    });
                    return true;
                }
                @android.annotation.SuppressLint("ClickableViewAccessibility") // Parent validates full gestures; performClick above supports accessible activation.
                @Override public boolean onTouchEvent(MotionEvent event) {
                    MotionEvent translated = MotionEvent.obtain(event);
                    translated.offsetLocation(getLeft(), getTop());
                    try { return TutorialOverlay.this.onTouchEvent(translated); }
                    finally { translated.recycle(); }
                }
            };
            targetTapControl.setFocusableInTouchMode(true); targetTapControl.setClickable(true);
            targetTapControl.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            targetTapControl.setContentDescription(getContext().getString(R.string.showcase_target_tap));
            targetTapControl.setVisibility(INVISIBLE);
            addView(targetTapControl, new LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT));
        }
        updateContentSpacing();
    }
    private static boolean hasText(CharSequence value) {
        if (value == null) return false;
        for (int i = 0; i < value.length();) {
            int codePoint = Character.codePointAt(value, i);
            if (codePoint > ' ' && !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) return true;
            i += Character.charCount(codePoint);
        }
        return false;
    }
    private TextView text(CharSequence text) {
        TextView view = new TextView(getContext()); view.setText(text); view.setTextColor(theme.textColor); view.setTextSize(theme.textSizeSp);
        view.setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        view.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        if(theme.contentTextAppearance!=0)view.setTextAppearance(getContext(),theme.contentTextAppearance);
        view.setFocusable(true); return view;
    }
    private void button(ActionRow row, CharSequence label, int resource, boolean primary, Runnable action) {
        CharSequence text = label == null ? getContext().getString(resource) : label;
        if (!hasText(text)) return;
        Button button = new Button(getContext()); button.setAllCaps(false); button.setText(text);
        button.setSingleLine(false); button.setEllipsize(null);
        // Retain native button semantics, without inheriting raised platform/host styling.
        button.setBackgroundTintList(null);
        int buttonColor = theme.primaryButtonColor == null ? theme.textColor | 0xff000000 : theme.primaryButtonColor;
        int labelColor = primary ? (theme.primaryButtonTextColor == null ? primaryLabelColor(buttonColor)
                : theme.primaryButtonTextColor) : theme.textColor;
        int ripple = (labelColor & 0x00ffffff) | 0x33000000;
        int cornerRadius = dp(primary ? 1000 : 8);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(primary ? buttonColor : Color.TRANSPARENT);
        shape.setCornerRadius(cornerRadius);
        GradientDrawable rippleMask = new GradientDrawable();
        rippleMask.setColor(Color.WHITE); rippleMask.setCornerRadius(cornerRadius);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), shape, rippleMask));
        button.setStateListAnimator(null); button.setElevation(0);
        button.setMinWidth(dp(primary ? 88 : 48)); button.setMinimumWidth(dp(primary ? 88 : 48));
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPaddingRelative(dp(primary ? 24 : 12), dp(12), dp(primary ? 24 : 12), dp(12));
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); button.setLetterSpacing(0);
        button.setTextSize(theme.textSizeSp * 16 / 18);
        button.setTextColor(labelColor);
        button.setOnClickListener(v -> callbacks.run(() -> { if (!closed && !exiting) action.run(); }));
        row.addView(button, new ViewGroup.LayoutParams(-2, -2));
        if (primary) row.primary = button;
        if(theme.buttonTextAppearance!=0)button.setTextAppearance(getContext(),theme.buttonTextAppearance);
        if (primary && (theme.primaryButtonColor != null || theme.primaryButtonTextColor != null))
            button.setTextColor(labelColor);
    }
    private int primaryLabelColor(int background) {
        int maskColor = theme.maskColor | 0xff000000;
        // Preserve configured fill alpha; estimate its visible color over the showcase surface.
        background = compositeColor(background, compositeColor(theme.surfaceColor, maskColor));
        float backgroundLuminance = Color.luminance(background), maskLuminance = Color.luminance(maskColor);
        float contrast = (Math.max(backgroundLuminance, maskLuminance) + .05f)
                / (Math.min(backgroundLuminance, maskLuminance) + .05f);
        // Match the showcase tint where readable, including applications with custom palettes.
        return contrast >= 4.5f ? maskColor : backgroundLuminance > .179f ? Color.BLACK : Color.WHITE;
    }
    private static int compositeColor(int foreground, int background) {
        int alpha = Color.alpha(foreground), inverse = 255 - alpha;
        return Color.rgb((Color.red(foreground) * alpha + Color.red(background) * inverse + 127) / 255,
                (Color.green(foreground) * alpha + Color.green(background) * inverse + 127) / 255,
                (Color.blue(foreground) * alpha + Color.blue(background) * inverse + 127) / 255);
    }
    private void addActions(ActionRow row) {
        if (row.getChildCount() == 0) return;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        // Let the hover/touch surface surround the leading text without indenting its label.
        if (row.trailingStart > 0 && row.getChildAt(0) != row.primary) {
            row.leadingInset = dp(12); params.setMarginStart(-row.leadingInset);
        }
        content.addView(row, params);
    }
    private void updateContentSpacing() {
        View previous = null;
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            int gap = previous == null ? 0 : child instanceof ActionRow
                    ? (previous instanceof ActionRow ? 8 : 28) : 16;
            int topMargin = dp(gap) + (child == customContent ? customTopMargin : 0);
            if (params.topMargin != topMargin) {
                params.topMargin = topMargin; child.setLayoutParams(params);
            }
            previous = child;
        }
        // A highlight-only step must not have an invisible scroll panel intercepting taps.
        panel.setVisibility(previous == null ? GONE : VISIBLE);
    }
    /** Keep trailing actions together when they fit, and wrap without overlapping touch targets. */
    private static final class ActionRow extends ViewGroup {
        private final int gap, lineGap;
        private final List<Row> rows = new ArrayList<>();
        private int rowCount;
        private View primary;
        private int trailingStart = Integer.MAX_VALUE;
        private int leadingInset;
        private static final class Row {
            final List<View> children = new ArrayList<>();
            int width, height, trailingWidth;
            View firstTrailing;
        }
        ActionRow(Context context, int gap) {
            super(context); this.gap = gap;
            lineGap = Math.round(8 * getResources().getDisplayMetrics().density);
        }
        @Override public void onViewAdded(View child) {
            super.onViewAdded(child);
            rows.add(new Row()); // At most one line per action; reuse it on subsequent measurements.
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            rowCount = 0;
            for (Row row : rows) {
                row.children.clear(); row.width = 0; row.height = 0;
                row.trailingWidth = 0; row.firstTrailing = null;
            }
            int available = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                    ? Integer.MAX_VALUE / 4 : Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
            int trailingWidth = 0;
            View firstTrailing = null;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int childWidth = i >= trailingStart ? Math.max(0, available - leadingInset) : available;
                child.measure(MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (i >= trailingStart) {
                    trailingWidth += (firstTrailing == null ? 0 : gap) + child.getMeasuredWidth();
                    if (firstTrailing == null) firstTrailing = child;
                }
            }
            Row row = rows.isEmpty() ? null : rows.get(0);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                // Move Skip and Next together if their pair fits on a fresh line.
                int needed = child == firstTrailing && trailingWidth <= available
                        ? trailingWidth : child.getMeasuredWidth();
                if (!row.children.isEmpty() && row.width + gap + needed > available) {
                    row = rows.get(++rowCount);
                }
                row.width += (row.children.isEmpty() ? 0 : gap) + child.getMeasuredWidth();
                row.height = Math.max(row.height, child.getMeasuredHeight()); row.children.add(child);
                if (i >= trailingStart) {
                    row.trailingWidth += (row.firstTrailing == null ? 0 : gap) + child.getMeasuredWidth();
                    if (row.firstTrailing == null) row.firstTrailing = child;
                }
            }
            if (row != null && !row.children.isEmpty()) rowCount++;
            int width = 0, height = 0;
            for (int i = 0; i < rowCount; i++) {
                Row line = rows.get(i); width = Math.max(width, line.width); height += line.height;
            }
            height += Math.max(0, rowCount - 1) * lineGap;
            setMeasuredDimension(resolveSize(width + getPaddingLeft() + getPaddingRight(), widthSpec),
                    resolveSize(height + getPaddingTop() + getPaddingBottom(), heightSpec));
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            int available = getWidth() - getPaddingLeft() - getPaddingRight(), y = getPaddingTop();
            for (int i = 0; i < rowCount; i++) {
                Row row = rows.get(i);
                int cursor = 0;
                for (View child : row.children) {
                    int width = child.getMeasuredWidth(), height = child.getMeasuredHeight();
                    if (child == row.firstTrailing) cursor = Math.max(cursor, available - row.trailingWidth);
                    int x = rtl ? getWidth() - getPaddingRight() - cursor - width : getPaddingLeft() + cursor;
                    int childY = y + (row.height - height) / 2;
                    child.layout(x, childY, x + width, childY + height);
                    cursor += width + gap;
                }
                y += row.height + lineGap;
            }
        }
    }
    private boolean allowsNavigation(TutorialTheme.Navigation action) {
        return step.interaction != Step.Interaction.TARGET_TAP
                || (action != TutorialTheme.Navigation.NEXT && action != TutorialTheme.Navigation.SKIP_STEP);
    }
    private void navigate(TutorialTheme.Navigation action) {
        if(action==null)return;
        switch(action){case PREVIOUS:actions.previous();break;case NEXT:actions.next();break;
            case SKIP_STEP:actions.skipStep();break;case SKIP_TOUR:actions.skipTour();break;case CLOSE:actions.close();break;default:break;}
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    void setProgress(int position, int count) {
        if (theme.showProgress && count > 0) { progress.setText(getResources().getQuantityString(R.plurals.showcase_progress, count, position, count)); progress.setVisibility(VISIBLE); }
        else progress.setVisibility(GONE);
        updateContentSpacing();
    }
    void attach(Runnable shown) {
        protectBackground();
        setVisibility(INVISIBLE); root.addView(this, new ViewGroup.LayoutParams(-1, -1));
        if (customContent != null) {
            // A GONE panel is not measured, so observe a custom view becoming visible again.
            int[] previousVisibility = {customContent.getVisibility()};
            customContentTree = getViewTreeObserver();
            customContentChanges = () -> {
                if (!closed && !exiting && previousVisibility[0] != customContent.getVisibility()) {
                    previousVisibility[0] = customContent.getVisibility();
                    callbacks.run(() -> { updateContentSpacing(); repositionNow(); });
                }
                return true;
            };
            customContentTree.addOnPreDrawListener(customContentChanges);
        }
        reveal = () -> callbacks.run(() -> {
            if (closed) return;
            repositionNow(); if (closed) return;
            setVisibility(VISIBLE);
            if (step.interaction != Step.Interaction.HINT && panel.getVisibility() == VISIBLE) panel.requestFocus();
            else if (targetTapControl != null) targetTapControl.requestFocus();
            if (closed) return;
            if (hasText(step.title) || hasText(step.text))
                announceForAccessibility(hasText(step.title) && hasText(step.text)
                        ? step.title + ". " + step.text : hasText(step.title) ? step.title : step.text);
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
                accessibility.put(child, child.getImportantForAccessibility());
                hideAccessibility(child);
            }
        }
    }
    private void hideAccessibility(View view) {
        if (!accessibility.containsKey(view)) accessibility.put(view, view.getImportantForAccessibility());
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
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
        primaryHighlight.setEmpty(); viewportRegion.set(viewport);
        if (!hole.isEmpty()) appendHole(hole, true);
        for (View extra : additionalTargets.get()) {
            updateHole(extra, extraHole);
            if (!extraHole.isEmpty()) { appendHole(extraHole, false); highlightBounds.union(extraHole); }
        }
        if (panel.getVisibility() == GONE) { updateTargetTapControl(); invalidate(); return; }
        int above = highlightBounds.isEmpty() ? 0 : Math.max(0, (int) Math.floor(highlightBounds.top) - viewport.top);
        int below = highlightBounds.isEmpty() ? viewport.height() : Math.max(0, viewport.bottom - (int) Math.ceil(highlightBounds.bottom));
        int left = highlightBounds.isEmpty() ? 0 : Math.max(0, (int) highlightBounds.left - viewport.left);
        int right = highlightBounds.isEmpty() ? 0 : Math.max(0, viewport.right - (int) Math.ceil(highlightBounds.right));
        // Measure the desired height before limiting it to any candidate region. A
        // scroll-clipped measurement would make content appear to fit on either side.
        panel.measure(MeasureSpec.makeMeasureSpec(Math.max(0, viewport.width()), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int desiredHeight = panel.getMeasuredHeight();
        boolean fitsAbove = desiredHeight <= above, fitsBelow = desiredHeight <= below;
        boolean beside = !fitsAbove && !fitsBelow && Math.max(left, right) >= dp(240);
        int cardWidth = beside ? Math.max(left, right) : viewport.width();
        // The larger vertical region also selects the only fitting region, if any.
        boolean top = above > below;
        int space = beside ? viewport.height() : Math.max(above, below);
        int minimumSpace = Math.min(desiredHeight, Math.min(dp(144), viewport.height()));
        if (!beside && highlightBounds.height() > viewport.height() / 2f) {
            // A fixed minimum can still leave Next below the fold. Reserve enough for
            // short explanations, or half the viewport for content that needs scrolling.
            minimumSpace = Math.min(desiredHeight, Math.max(minimumSpace, viewport.height() / 2));
        }
        // A large hole must not remove the scrim behind the explanation and its controls.
        // Suppress it for this geometry update, then recover it normally if the target shrinks.
        boolean partialTargetTap = false;
        if (!highlightBounds.isEmpty() && space < minimumSpace) {
            if (targetTapControl != null && !hole.isEmpty()) {
                // Target-only continuation must retain a tappable portion of an oversized
                // target. Place scrollable copy on whichever side leaves more room.
                View primary = target.get();
                if (primary != null && TargetGeometry.visibleOnScreen(primary, visibleTarget)) {
                    visibleTarget.offset(-origin[0], -origin[1]);
                    if (visibleTarget.intersect(viewport)) {
                        int touchHeight = Math.min(dp(48), visibleTarget.height());
                        above = Math.max(0, visibleTarget.bottom - touchHeight - viewport.top);
                        below = Math.max(0, viewport.bottom - visibleTarget.top - touchHeight);
                        partialTargetTap = true;
                    }
                }
            }
            mask.rewind(); primaryHighlight.setEmpty(); hole.setEmpty(); highlightBounds.setEmpty();
            beside = false; top = partialTargetTap && above > below; cardWidth = viewport.width();
            space = partialTargetTap ? Math.max(above, below) : viewport.height();
        }
        space = Math.max(0, Math.min(space, viewport.height()));
        panel.measure(MeasureSpec.makeMeasureSpec(Math.max(0, cardWidth), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(space, MeasureSpec.AT_MOST));
        LayoutParams lp = (LayoutParams) panel.getLayoutParams();
        int height = Math.min(space, panel.getMeasuredHeight());
        int y = top ? viewport.top : viewport.bottom - height;
        if (!highlightBounds.isEmpty()) {
            // Keep the panel adjacent vertically, or centered alongside the highlight.
            y = beside ? Math.round(highlightBounds.centerY() - height / 2f)
                    : top ? (int) Math.floor(highlightBounds.top) - height : (int) Math.ceil(highlightBounds.bottom);
        }
        y = Math.max(viewport.top, Math.min(y, viewport.bottom - height));
        int x = beside && right > left ? viewport.right - cardWidth : viewport.left;
        if (lp.width != cardWidth || lp.height != height || lp.topMargin != y || lp.leftMargin != x) {
            lp.gravity = Gravity.TOP | Gravity.LEFT; lp.width = Math.max(0, cardWidth); lp.height = height;
            lp.topMargin = y; lp.leftMargin = x; panel.setLayoutParams(lp);
        }
        if (partialTargetTap) {
            updateHole(target.get(), hole);
            if (hole.intersect(viewport.left, top ? y + height : viewport.top, viewport.right, top ? viewport.bottom : y)) appendHole(hole, true);
            else hole.setEmpty();
        }
        updateTargetTapControl();
        invalidate();
    }
    private void updateTargetTapControl() {
        if (targetTapControl == null) return;
        View primary = target.get(); Rect area = new Rect();
        if (primaryHighlight.isEmpty() || primary == null || !TargetGeometry.visibleOnScreen(primary, area)) {
            targetTapControl.setVisibility(INVISIBLE); return;
        }
        area.offset(-origin[0], -origin[1]);
        if (!area.intersect(primaryHighlight.getBounds())) {
            targetTapControl.setVisibility(INVISIBLE); return;
        }
        targetTapControl.measure(MeasureSpec.makeMeasureSpec(area.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(area.height(), MeasureSpec.EXACTLY));
        LayoutParams lp = (LayoutParams) targetTapControl.getLayoutParams();
        if (lp.width != area.width() || lp.height != area.height() || lp.leftMargin != area.left || lp.topMargin != area.top) {
            lp.width = area.width(); lp.height = area.height(); lp.leftMargin = area.left; lp.topMargin = area.top;
            targetTapControl.setLayoutParams(lp);
        }
        targetTapControl.setVisibility(VISIBLE);
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
    private void appendHole(RectF bounds, boolean primary) {
        // Each shape owns its path, so a custom callback can reset/fill it without
        // erasing earlier highlights. Touch activation uses only the primary cutout.
        highlightPath.rewind(); highlightPath.setFillType(Path.FillType.WINDING);
        if (theme.highlightShape == null) highlightPath.addRoundRect(bounds, dp(theme.cornerDp), dp(theme.cornerDp), Path.Direction.CW);
        else theme.highlightShape.path(highlightPath, bounds);
        if (mask.isEmpty()) mask.set(highlightPath);
        else mask.op(highlightPath, Path.Op.UNION);
        if (primary) primaryHighlight.setPath(highlightPath, viewportRegion);
    }
    private boolean insidePrimaryHighlight(float x, float y) {
        return primaryHighlight.contains((int) Math.floor(x), (int) Math.floor(y));
    }
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        boolean[] measured = {false};
        callbacks.run(() -> { updateContentSpacing(); super.onMeasure(widthMeasureSpec, heightMeasureSpec); measured[0] = true; });
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
            try {
                canvas.clipPath(mask, Region.Op.DIFFERENCE);
                canvas.drawColor(theme.maskColor);
            }
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
    private boolean insidePanel(MotionEvent e) { return panel.getVisibility() == VISIBLE && e.getX() >= panel.getLeft() && e.getX() < panel.getRight() && e.getY() >= panel.getTop() && e.getY() < panel.getBottom(); }
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
                gestureTarget = step.interaction == Step.Interaction.TARGET_ACTION || step.interaction == Step.Interaction.TARGET_TAP ? target.get() : null;
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
                else if (clicked && (step.interaction == Step.Interaction.TARGET_ACTION || step.interaction == Step.Interaction.TARGET_TAP)
                        && insidePrimaryHighlight(downX, downY) && insidePrimaryHighlight(e.getX(), e.getY())) {
                    getLocationOnScreen(origin);
                    if (gestureTarget != null && gestureBounds.contains((int) e.getX() + origin[0], (int) e.getY() + origin[1])) {
                        if (step.interaction == Step.Interaction.TARGET_TAP)
                            completeTargetTap(gestureTarget, new Rect(gestureBounds), new Rect(gestureTargetBounds));
                        else activateTarget(gestureTarget, new Rect(gestureBounds), new Rect(gestureTargetBounds));
                    }
                }
                break;
        }
    }
    private void completeTargetTap(View expected, Rect expectedBounds, Rect expectedTargetBounds) {
        actions.targetTapped(() -> {
            if (closed || exiting || primaryHighlight.isEmpty() || targetTapControl == null || targetTapControl.getVisibility() != VISIBLE) return false;
            View view = target.get();
            if (view == null || (expected != null && view != expected) || !TargetValidator.valid(view, root, step, true)) return false;
            Rect current = new Rect();
            if (!TargetGeometry.visibleOnScreen(view, current) || (expectedBounds != null && !expectedBounds.equals(current))) return false;
            TargetGeometry.boundsOnScreen(view, current);
            if (expectedTargetBounds != null && !expectedTargetBounds.equals(current)) return false;
            // Widgets such as CheckBox can perform their built-in action while returning
            // false when no OnClickListener is installed. The valid tap still completes.
            view.performClick();
            return true;
        });
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
        callbacks.run(() -> {
            if (closed || exiting) return;
            super.performClick();
            if (step.interaction == Step.Interaction.BACKGROUND_TAP) actions.next();
        });
        return true;
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
    @Override protected void onDetachedFromWindow() {
        detaching = true;
        try {
            if (!closed) callbacks.run(() -> {
                Scope cleanup = new Scope();
                cleanup.own(actions::close);
                cleanup.own(this::cancel);
                cleanup.cancel();
            });
        } finally { detaching = false; super.onDetachedFromWindow(); }
    }
    @Override public void cancel() {
        if (closed) return; closed = true;
        boolean restoreInputFocus = step.interaction != Step.Interaction.HINT || hasFocus();
        boolean restoreSpokenFocus = step.interaction != Step.Interaction.HINT
                || accessibilityFocus(this) != null;
        Scope cleanup = new Scope();
        // Register in reverse execution order. One application View throwing during
        // detachment/restoration must not prevent restoring the remaining background.
        cleanup.own(() -> { if (restoreSpokenFocus) restoreAccessibilityFocus(); });
        cleanup.own(() -> { if (restoreInputFocus && previousFocus != null && previousFocus.getWindowToken() != null) previousFocus.requestFocus(); });
        for (Map.Entry<View, Boolean> entry : focusable.entrySet())
            cleanup.own(() -> entry.getKey().setFocusable(entry.getValue()));
        for (Map.Entry<ViewGroup, Integer> entry : focusGroups.entrySet())
            cleanup.own(() -> entry.getKey().setDescendantFocusability(entry.getValue()));
        for (Map.Entry<View, Integer> entry : accessibility.entrySet())
            cleanup.own(() -> entry.getKey().setImportantForAccessibility(entry.getValue()));
        cleanup.own(() -> { if (!detaching && getParent() == root) root.removeView(this); });
        cleanup.own(() -> setVisibility(GONE));
        Animator ending = animator; animator = null;
        cleanup.own(() -> { if (ending != null) { ending.removeAllListeners(); ending.cancel(); } });
        Runnable pendingReveal = reveal; reveal = null;
        cleanup.own(() -> { if (pendingReveal != null) removeCallbacks(pendingReveal); });
        ViewTreeObserver contentTree = customContentTree; customContentTree = null;
        ViewTreeObserver.OnPreDrawListener contentChanges = customContentChanges; customContentChanges = null;
        if (contentTree != null) cleanup.own(() -> {
            ViewTreeObserver current = contentTree.isAlive() ? contentTree : root.getViewTreeObserver();
            if (current.isAlive()) current.removeOnPreDrawListener(contentChanges);
        });
        try { cleanup.cancel(); }
        finally { accessibility.clear(); focusGroups.clear(); focusable.clear(); }
    }
    @android.annotation.SuppressLint("AccessibilityFocus") // Restore the user's prior focus, never move it during presentation.
    private void restoreAccessibilityFocus() {
        if (root.hasWindowFocus() && previousAccessibilityFocus != null && previousAccessibilityFocus.getWindowToken() != null)
            previousAccessibilityFocus.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
    }
}
