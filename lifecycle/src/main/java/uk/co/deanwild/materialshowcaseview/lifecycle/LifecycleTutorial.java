package uk.co.deanwild.materialshowcaseview.lifecycle;

import androidx.lifecycle.*;
import androidx.activity.*;
import uk.co.deanwild.materialshowcaseview.session.*;

/** Bind to Fragment.getViewLifecycleOwner(), never the Fragment itself for view targets. */
public final class LifecycleTutorial implements LifecycleEventObserver, Cancellation {
    private Lifecycle lifecycle;
    private AndroidTutorialHost host;
    private TutorialSession session;
    private OnBackPressedCallback back;
    private TutorialSession.Listener listener;
    public LifecycleTutorial(LifecycleOwner owner, AndroidTutorialHost host, TutorialSession session) {
        new MainThreadScheduler().checkThread();
        this.lifecycle = owner.getLifecycle(); this.host = host; this.session = session;
        try {
            lifecycle.addObserver(this);
            // Registration synchronously dispatches existing lifecycle events. Resuming
            // the session may destroy its owner and dispose this binding before it returns.
            if (lifecycle == null) return;
            if (lifecycle.getCurrentState() == Lifecycle.State.DESTROYED) cancel();
            else host.setResumed(lifecycle.getCurrentState().isAtLeast(Lifecycle.State.RESUMED));
        } catch (RuntimeException error) {
            // A failed constructor cannot return a cancellation handle to its caller.
            try { cancel(); }
            catch (RuntimeException cleanup) { if (cleanup != error) error.addSuppressed(cleanup); }
            throw error;
        }
    }
    public LifecycleTutorial interceptBack(OnBackPressedDispatcher dispatcher, LifecycleOwner owner) {
        if (session == null) throw new IllegalStateException("Disposed binding");
        if (back != null) { back.remove(); session.removeListener(listener); }
        back = new OnBackPressedCallback(session.getState() == TutorialSession.State.SHOWING
                || session.getState() == TutorialSession.State.HIDING) {
            @Override public void handleOnBackPressed() { if (session != null) session.cancel(TutorialSession.Reason.USER); }
            // Predictive gesture start/progress/cancel intentionally do not commit navigation.
        };
        OnBackPressedCallback callback = back;
        listener = event -> callback.setEnabled(event.state == TutorialSession.State.SHOWING || event.state == TutorialSession.State.HIDING);
        session.addListener(listener); dispatcher.addCallback(owner, back); return this;
    }
    @Override public void onStateChanged(LifecycleOwner owner, Lifecycle.Event event) {
        if (event == Lifecycle.Event.ON_DESTROY) cancel();
        else if (host != null) host.setResumed(owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED));
    }
    @Override public void cancel() {
        if (lifecycle == null) return;
        lifecycle.removeObserver(this); lifecycle = null;
        if (back != null) { back.remove(); back = null; }
        TutorialSession old = session; session = null;
        try { if (listener != null) old.removeListener(listener); old.dispose(); }
        finally { host = null; listener = null; }
    }
}
