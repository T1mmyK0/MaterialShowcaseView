package uk.co.deanwild.materialshowcaseview.session;

import java.util.ArrayList;
import java.util.List;

/** Owns preparation, observers, animations and app cleanup, including synchronous completions. */
public final class Scope implements Cancellation {
    private final List<Cancellation> resources = new ArrayList<>();
    private boolean closed;
    public void own(Cancellation resource) {
        if (resource == null) return;
        if (closed) resource.cancel(); else resources.add(resource);
    }
    public boolean isClosed() { return closed; }
    @Override public void cancel() {
        if (closed) return;
        closed = true;
        RuntimeException error = null;
        for (int i = resources.size() - 1; i >= 0; i--) {
            try { resources.get(i).cancel(); }
            catch (RuntimeException e) {
                if (error == null) error = e;
                else if (error != e) error.addSuppressed(e);
            }
        }
        resources.clear();
        if (error != null) throw error;
    }
}
