package uk.co.deanwild.materialshowcaseview.session;

import java.util.*;

/** One screen/window, one active session. Owner must dispose on destruction; no global registry. */
@androidx.annotation.MainThread
public final class TutorialCoordinator implements Cancellation {
    public enum Conflict { QUEUE, REJECT, REPLACE, DEDUPLICATE }
    private TutorialSession active;
    private final List<TutorialSession> queue = new ArrayList<>();
    private boolean closed;
    private int replacing;
    void acquire(TutorialSession session, Conflict policy) {
        if (!session.isActive()) return;
        if (closed) { session.cancel(TutorialSession.Reason.DESTROYED); return; }
        if (active == session || queue.contains(session)) return;
        if (policy == Conflict.DEDUPLICATE && ((active != null && active.tutorialId().equals(session.tutorialId()))
                || containsId(session.tutorialId()))) { session.cancel(TutorialSession.Reason.DUPLICATE); return; }
        if (policy == Conflict.REPLACE) {
            List<TutorialSession> previous = new ArrayList<>(queue);
            if (active != null) previous.add(active);
            // Reserve the request before invoking any outgoing callbacks. Cancellation,
            // disposal, or a nested replacement can then remove this exact request.
            queue.clear(); queue.add(session);
            replacing++;
            RuntimeException failure = null;
            try {
                for (TutorialSession old : previous) {
                    if (!old.isActive()) continue;
                    try { old.cancel(TutorialSession.Reason.REPLACED); }
                    catch (RuntimeException error) {
                        if (failure == null) failure = error;
                        else if (failure != error) failure.addSuppressed(error);
                    }
                }
            } finally { replacing--; }
            // Wait until the outermost replacement has released all outgoing resources.
            // Nested requests join the queue and cannot present during that teardown.
            try { drain(); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
            return;
        }
        if (policy == Conflict.REJECT && (active != null || !queue.isEmpty())) { session.cancel(TutorialSession.Reason.CONFLICT); return; }
        queue.add(session);
        if (active != null || replacing > 0) session.queued();
        drain();
    }
    private boolean containsId(String id) { for (TutorialSession s : queue) if (s.tutorialId().equals(id)) return true; return false; }
    void release(TutorialSession session) {
        queue.remove(session);
        if (active == session) active = null;
        drain();
    }
    private void drain() {
        if (!closed && replacing == 0 && active == null && !queue.isEmpty()) {
            active = queue.remove(0);
            active.acquired();
        }
    }
    public int pendingCount() { return queue.size() + (active == null ? 0 : 1); }
    @Override public void cancel() {
        if (closed) return;
        closed = true;
        List<TutorialSession> sessions = new ArrayList<>(queue);
        if (active != null) sessions.add(active);
        queue.clear(); active = null;
        RuntimeException failure = null;
        for (TutorialSession session : sessions) {
            try { session.dispose(); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
