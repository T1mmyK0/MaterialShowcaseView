package uk.co.deanwild.materialshowcaseview.lifecycle;

import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import uk.co.deanwild.materialshowcaseview.session.*;

/** Stable-ID resolver/preparation. Never captures a ViewHolder. */
public final class RecyclerViewTarget {
    private final RecyclerView recycler;
    private final long itemId;
    private final int descendantId;
    public RecyclerViewTarget(RecyclerView recycler, long itemId, int descendantId) {
        this.recycler = recycler; this.itemId = itemId; this.descendantId = descendantId;
    }
    public View resolve() {
        RecyclerView.ViewHolder holder = recycler.findViewHolderForItemId(itemId);
        return holder == null ? null : descendantId == 0 ? holder.itemView : holder.itemView.findViewById(descendantId);
    }
    public Cancellation prepare(Scope scope, Runnable ready) {
        RecyclerView.Adapter<?> adapter = recycler.getAdapter();
        if (adapter == null || !adapter.hasStableIds()) throw new IllegalStateException("Stable IDs required");
        Scope work = new Scope();
        RecyclerView.OnChildAttachStateChangeListener attach = new RecyclerView.OnChildAttachStateChangeListener() {
            public void onChildViewAttachedToWindow(View view) { if (!work.isClosed() && resolve() != null) { work.cancel(); ready.run(); } }
            public void onChildViewDetachedFromWindow(View view) { }
        };
        Runnable scroll = () -> {
            if (work.isClosed()) return;
            for (int i = 0; i < adapter.getItemCount(); i++) if (adapter.getItemId(i) == itemId) { recycler.scrollToPosition(i); break; }
            if (resolve() != null) { work.cancel(); ready.run(); }
        };
        RecyclerView.AdapterDataObserver data = new RecyclerView.AdapterDataObserver() {
            public void onChanged() { scroll.run(); }
            public void onItemRangeInserted(int start, int count) { scroll.run(); }
            public void onItemRangeChanged(int start, int count) { scroll.run(); }
            public void onItemRangeRemoved(int start, int count) { scroll.run(); }
            public void onItemRangeMoved(int from, int to, int count) { scroll.run(); }
        };
        recycler.addOnChildAttachStateChangeListener(attach); adapter.registerAdapterDataObserver(data);
        work.own(() -> { recycler.removeOnChildAttachStateChangeListener(attach); adapter.unregisterAdapterDataObserver(data); });
        scope.own(work); scroll.run(); return work;
    }
}
