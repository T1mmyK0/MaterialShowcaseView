package uk.co.deanwild.materialshowcaseview.session;

import android.graphics.Rect;
import android.os.Build;
import android.view.*;
import java.util.*;

/** Window-scoped host. Resolve fresh Views; use a fragment's view lifecycle in the AndroidX adapter. */
@androidx.annotation.MainThread
public final class AndroidTutorialHost implements TutorialHost {
    public interface TargetResolver { View resolve(String logicalId); }
    public interface Preparation { Cancellation prepare(Step step, Scope scope, Runnable ready); }
    public interface RevealStrategy { void reveal(View view, Alignment alignment, int padding); }
    public enum Alignment { NEAREST, CENTER, START, END }
    private ViewGroup root;
    private TargetResolver resolver;
    private Preparation preparation = (step, scope, ready) -> { ready.run(); return Cancellation.NONE; };
    private RevealStrategy reveal = this::revealAncestors;
    private final MainThreadScheduler scheduler = new MainThreadScheduler();
    private final List<Runnable> observers = new ArrayList<>();
    private final Set<Cancellation> resources = new LinkedHashSet<>();
    private boolean resumed, disposed;
    private boolean prepareMissingTargets;
    private boolean prepareUnavailableTargets;
    private TutorialOverlay overlay;
    private Alignment alignment = Alignment.NEAREST;
    private int padding = 12;
    private int progressPosition, progressCount;
    private TutorialTheme theme = new TutorialTheme();
    private String diagnostic = "";
    private Step observedStep;
    public AndroidTutorialHost(Window window, TargetResolver resolver) {
        this((ViewGroup) window.getDecorView(), resolver);
    }
    public AndroidTutorialHost(ViewGroup windowRoot, TargetResolver resolver) {
        scheduler.checkThread();
        root = Objects.requireNonNull(windowRoot); this.resolver = Objects.requireNonNull(resolver);
    }
    public void setResumed(boolean value) { scheduler.checkThread(); resumed = value; changed(); }
    public void setPreparation(Preparation value) { scheduler.checkThread(); preparation = Objects.requireNonNull(value); }
    /** Enable for preparation which materializes absent targets, such as RecyclerView items. */
    public void setPrepareMissingTargets(boolean value) { scheduler.checkThread(); prepareMissingTargets = value; }
    /** Opt in when preparation expands/enables/attaches a presently unavailable target. */
    public void setPrepareUnavailableTargets(boolean value) { scheduler.checkThread(); prepareUnavailableTargets = value; }
    public void setRevealStrategy(RevealStrategy value) { scheduler.checkThread(); reveal = Objects.requireNonNull(value); }
    public void setAlignment(Alignment value, int paddingPixels) { scheduler.checkThread(); alignment = Objects.requireNonNull(value); padding = Math.max(0, paddingPixels); }
    public void setTheme(TutorialTheme value) { scheduler.checkThread(); theme = Objects.requireNonNull(value); }
    public void invalidate() { scheduler.checkThread(); changed(); }
    private void changed() { for (Runnable r : new ArrayList<>(observers)) r.run(); }
    private Cancellation own(Cancellation resource) {
        Cancellation registration = new Cancellation() {
            private boolean closed;
            @Override public void cancel() {
                if (closed) return;
                closed = true; resources.remove(this); resource.cancel();
            }
        };
        if (disposed) registration.cancel(); else resources.add(registration);
        return registration;
    }
    @Override public boolean ready() { return !disposed && resumed && root.getWindowToken() != null && root.hasWindowFocus()
            && root.getWidth() > 0 && root.getHeight() > 0 && root.isShown() && root.getWindowVisibility() == View.VISIBLE; }
    private View resolve(Step step) { return step.targetId == null ? null : resolver.resolve(step.targetId); }
    @Override public boolean valid(Step step, boolean visible) {
        if (disposed) return false;
        observedStep = step;
        View view = resolve(step);
        if (disposed) return false;
        TargetValidator.Reason reason = step.targetId == null ? TargetValidator.Reason.READY : TargetValidator.diagnose(view, root, step, visible);
        diagnostic = String.valueOf(step.targetId) + ": " + reason;
        boolean targetValid = reason == TargetValidator.Reason.READY;
        if (!targetValid && !(!visible && (prepareUnavailableTargets || (prepareMissingTargets && view == null)))) return false;
        for (String id : step.additionalTargetIds) {
            View additional = resolver.resolve(id);
            if (disposed) return false;
            reason = TargetValidator.diagnoseHighlight(additional, root, visible);
            if (reason != TargetValidator.Reason.READY) { diagnostic = id + ": " + reason; return false; }
        }
        return true;
    }
    @Override public Cancellation observe(Runnable changed) {
        return observe(changed, Runnable::run);
    }
    @Override public Cancellation observe(Runnable changed, Callbacks callbacks) {
        scheduler.checkThread();
        if (disposed) return Cancellation.NONE;
        final boolean[] closed = {false};
        Runnable notify = () -> callbacks.run(() -> {
            if (closed[0] || disposed) return;
            TutorialOverlay current = overlay;
            changed.run();
            if (!closed[0] && current != null && overlay == current) current.reposition();
        });
        observers.add(notify);
        ViewTreeObserver tree = root.getViewTreeObserver();
        ViewTreeObserver.OnGlobalLayoutListener layout = notify::run;
        ViewTreeObserver.OnScrollChangedListener scroll = notify::run;
        tree.addOnGlobalLayoutListener(layout); tree.addOnScrollChangedListener(scroll);
        final long[] previousGeometry = {Long.MIN_VALUE};
        final int[] xy = new int[2];
        final Rect visible = new Rect();
        ViewTreeObserver.OnPreDrawListener geometry = () -> {
            callbacks.run(() -> {
            if (closed[0] || disposed) return;
            Step step = observedStep;
            if (step != null) {
                View target = resolve(step);
                if (closed[0]) return;
                long signature = geometrySignature(target, xy, visible);
                Rect viewport = new Rect(); TargetGeometry.usableOnScreen(root, viewport);
                signature = signature * 31 + viewport.hashCode();
                for (String id : step.additionalTargetIds) {
                    View additional = resolver.resolve(id);
                    if (closed[0]) return;
                    signature = signature * 31 + geometrySignature(additional, xy, visible);
                }
                if (signature != previousGeometry[0]) {
                    previousGeometry[0] = signature;
                    notify.run();
                }
            }
            });
            return true;
        };
        tree.addOnPreDrawListener(geometry);
        // Window focus changes need no Activity override on API 18+.
        Cancellation focus = Build.VERSION.SDK_INT >= 18 ? observeFocus(root, tree, notify) : Cancellation.NONE;
        View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { notify.run(); }
            public void onViewDetachedFromWindow(View v) { notify.run(); }
        };
        root.addOnAttachStateChangeListener(attachment);
        ViewGroup observedRoot = root;
        return own(() -> {
            closed[0] = true;
            observers.remove(notify); focus.cancel(); observedRoot.removeOnAttachStateChangeListener(attachment);
            ViewTreeObserver current = tree.isAlive() ? tree : observedRoot.getViewTreeObserver();
            if (current.isAlive()) { current.removeGlobalOnLayoutListener(layout); current.removeOnScrollChangedListener(scroll); current.removeOnPreDrawListener(geometry); }
        });
    }
    private static long geometrySignature(View view, int[] xy, Rect visible) {
        if (view == null) return 0;
        view.getLocationOnScreen(xy); visible.setEmpty(); view.getGlobalVisibleRect(visible);
        long result = System.identityHashCode(view);
        result = result * 31 + xy[0]; result = result * 31 + xy[1];
        result = result * 31 + view.getWidth(); result = result * 31 + view.getHeight();
        result = result * 31 + visible.hashCode(); result = result * 31 + (view.isShown() ? 1 : 0);
        TargetGeometry.boundsOnScreen(view, visible); result = result * 31 + visible.hashCode();
        result = result * 31 + (view.isEnabled() ? 1 : 0); result = result * 31 + (view.isClickable() ? 1 : 0);
        result = result * 31 + Float.floatToIntBits(view.getAlpha());
        ViewParent ancestor = view.getParent();
        while (ancestor instanceof View) { result = result * 31 + Float.floatToIntBits(((View) ancestor).getAlpha()); ancestor = ancestor.getParent(); }
        return result;
    }
    @androidx.annotation.RequiresApi(18)
    private Cancellation observeFocus(View observedRoot, ViewTreeObserver tree, Runnable changed) {
        ViewTreeObserver.OnWindowFocusChangeListener listener = focused -> changed.run();
        tree.addOnWindowFocusChangeListener(listener);
        return () -> {
            // A listener registered before attachment moves from the floating tree into
            // the window's observer, invalidating the original tree.
            ViewTreeObserver current = tree.isAlive() ? tree : observedRoot.getViewTreeObserver();
            if (current.isAlive()) current.removeOnWindowFocusChangeListener(listener);
        };
    }
    @Override public Cancellation prepare(Step step, Scope scope, Runnable complete) {
        return prepare(step, scope, complete, Runnable::run);
    }
    @Override public Cancellation prepare(Step step, Scope scope, Runnable complete, Callbacks callbacks) {
        scheduler.checkThread();
        if (disposed) return Cancellation.NONE;
        Scope work = new Scope();
        Cancellation release = own(work);
        scope.own(release);
        if (work.isClosed()) return release;
        // Application resources may span preparation and presentation. Both phase cleanup
        // and direct host disposal must own them, without closing the caller's whole scope.
        Scope application = new Scope();
        scope.own(own(application));
        final boolean[] once = {false};
        work.own(preparation.prepare(step, application, () -> callbacks.run(() -> {
            scheduler.checkThread(); if (work.isClosed() || once[0]) return; once[0] = true;
            View view = resolve(step);
            if (work.isClosed()) return;
            if (step.targetId != null && view == null) { release.cancel(); complete.run(); return; }
            if (view != null) reveal.reveal(view, alignment, padding);
            if (work.isClosed()) return;
            final Rect previous = new Rect(); final int[] stable = {0};
            ViewGroup observed = root; ViewTreeObserver tree = observed.getViewTreeObserver();
            ViewTreeObserver.OnPreDrawListener listener = () -> {
                callbacks.run(() -> {
                if (work.isClosed()) return;
                View fresh = resolve(step);
                if (work.isClosed()) return;
                boolean valid = ready() && valid(step, false);
                if (work.isClosed()) return;
                if (!valid) { release.cancel(); complete.run(); return; }
                Rect now = new Rect();
                if (fresh != null) TargetGeometry.boundsOnScreen(fresh, now);
                if (now.equals(previous)) stable[0]++; else { previous.set(now); stable[0] = 0; }
                if (stable[0] >= 2) { release.cancel(); complete.run(); }
                else observed.postInvalidate();
                });
                return true;
            };
            tree.addOnPreDrawListener(listener);
            work.own(() -> { ViewTreeObserver current = tree.isAlive() ? tree : observed.getViewTreeObserver(); if (current.isAlive()) current.removeOnPreDrawListener(listener); });
            observed.invalidate();
        })));
        return release;
    }
    private void revealAncestors(View view, Alignment alignment, int padding) {
        Rect rect = new Rect(-padding, -padding, view.getWidth() + padding, view.getHeight() + padding);
        view.requestRectangleOnScreen(rect, true);
        View child = view;
        while (child.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) child.getParent();
            if (isVerticalScrollContainer(parent)) {
                Rect visible = new Rect(), targetBounds = new Rect(), parentBounds = new Rect(), hostViewport = new Rect();
                if (!TargetGeometry.usableOnScreen(parent, visible) || !TargetGeometry.usableOnScreen(root, hostViewport)
                        || !visible.intersect(hostViewport)) { child = parent; continue; }
                TargetGeometry.boundsOnScreen(view, targetBounds); TargetGeometry.boundsOnScreen(parent, parentBounds);
                int inset = Math.min(padding, Math.max(0, (visible.height() - 1) / 2));
                int top = visible.top + inset, bottom = visible.bottom - inset;
                int targetTop = targetBounds.top, targetBottom = targetBounds.bottom;
                int delta = 0;
                if (alignment == Alignment.CENTER) delta = (targetTop + targetBottom - top - bottom) / 2;
                else if (alignment == Alignment.START || targetBounds.height() > bottom - top) delta = targetTop - top;
                else if (alignment == Alignment.END) delta = targetBottom - bottom;
                else if (targetTop < top) delta = targetTop - top;
                else if (targetBottom > bottom) delta = targetBottom - bottom;
                float scale = parent.getHeight() == 0 ? 0 : (float) parentBounds.height() / parent.getHeight();
                if (scale > 0) parent.scrollBy(0, Math.round(delta / scale));
            }
            child = parent;
        }
    }
    private static boolean isVerticalScrollContainer(View view) {
        if (view instanceof android.widget.ScrollView) return true;
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("androidx.core.widget.NestedScrollView")) return true;
        return false;
    }
    @Override public Cancellation show(Step step, Actions actions, Runnable shown) {
        return show(step, actions, shown, Runnable::run);
    }
    @Override public Cancellation show(Step step, Actions actions, Runnable shown, Callbacks callbacks) {
        if (!ready() || !valid(step, true)) return Cancellation.NONE;
        TutorialOverlay presentation = new TutorialOverlay(root, () -> resolve(step), step, actions, new TutorialTheme(theme), callbacks);
        presentation.setProgress(progressPosition, progressCount);
        presentation.additionalTargets = () -> {
            List<View> views = new ArrayList<>();
            for (String id : step.additionalTargetIds) views.add(resolver.resolve(id));
            return views;
        };
        overlay = presentation;
        try { presentation.attach(shown); }
        catch (RuntimeException error) { presentation.cancel(); overlay = null; throw error; }
        return () -> { presentation.cancel(); if (overlay == presentation) overlay = null; };
    }
    @Override public Cancellation hide(Runnable hidden) {
        if (overlay == null) { hidden.run(); return Cancellation.NONE; }
        return overlay.hide(hidden);
    }
    @Override public void setProgress(int position, int count) { progressPosition = position; progressCount = count; }
    @Override public String diagnostic() { return diagnostic; }
    @Override public void cancel() {
        scheduler.checkThread(); if (disposed) return; disposed = true;
        Scope cleanup = new Scope();
        for (Cancellation resource : new ArrayList<>(resources)) cleanup.own(resource);
        cleanup.own(overlay);
        try { cleanup.cancel(); }
        finally {
            overlay = null; resources.clear(); observers.clear(); observedStep = null;
            root = null; resolver = null; preparation = null; reveal = null; theme = null;
        }
    }
}
