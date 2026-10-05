package uk.co.deanwild.materialshowcaseview;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import java.util.*;

/** Legacy view-backed sequence. For lifecycle/async preparation use session.TutorialSession. */
@androidx.annotation.MainThread
public class MaterialShowcaseSequence implements IDetachedListener {
    PrefsManager mPrefsManager;
    Activity mActivity;
    private final List<MaterialShowcaseView> items = new ArrayList<>();
    private ShowcaseConfig mConfig;
    private int position;
    private boolean running;
    private long generation;
    private MaterialShowcaseView current;
    private IShowcaseListener displayListener;
    private OnSequenceItemShownListener shown;
    private OnSequenceItemDismissedListener dismissed;
    public MaterialShowcaseSequence(Activity activity) { mActivity = activity; }
    public MaterialShowcaseSequence(Activity activity, String id) { this(activity); singleUse(id); }
    public MaterialShowcaseSequence addSequenceItem(View target, String text, String dismiss) { return addSequenceItem(target, "", text, dismiss); }
    public MaterialShowcaseSequence addSequenceItem(View target, String title, String text, String dismiss) {
        return addSequenceItem(new MaterialShowcaseView.Builder(mActivity).setTarget(target).setTitleText(title)
                .setContentText(text).setDismissText(dismiss).setSequence(true).build());
    }
    public MaterialShowcaseSequence addSequenceItem(MaterialShowcaseView item) {
        if (item == null) throw new IllegalArgumentException("null sequence item");
        if (mConfig != null) item.setConfig(mConfig); items.add(item); return this;
    }
    public MaterialShowcaseSequence singleUse(String id) { mPrefsManager = new PrefsManager(mActivity, id); return this; }
    public void setOnItemShownListener(OnSequenceItemShownListener listener) { shown = listener; }
    public void setOnItemDismissedListener(OnSequenceItemDismissedListener listener) { dismissed = listener; }
    public boolean hasFired() { return mPrefsManager != null && mPrefsManager.hasFired(); }
    public boolean isRunning() { return running; }
    private void check() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Call on main thread"); }
    public void start() {
        check(); if (running || hasFired() || items.isEmpty()) return;
        position = mPrefsManager == null ? 0 : mPrefsManager.getSequenceStatus();
        if (position < 0 || position >= items.size()) position = 0;
        generation++; running = true; showNext();
    }
    public void cancel() {
        check(); generation++; running = false;
        MaterialShowcaseView view = current; current = null;
        IShowcaseListener oldListener = displayListener; displayListener = null;
        if (view != null) { view.setDetachedListener(null); view.removeShowcaseListener(oldListener); view.removeFromWindow(); }
    }
    private void showNext() {
        if (!running) return;
        if (mActivity.isFinishing()) { cancel(); return; }
        if (position >= items.size()) { running = false; if (mPrefsManager != null) mPrefsManager.setFired(); return; }
        current = items.get(position); long token = generation;
        displayListener = new IShowcaseListener() {
            public void onShowcaseDisplayed(MaterialShowcaseView view) {
                if (running && token == generation && shown != null) {
                    try { shown.onShow(view, position); } catch (RuntimeException error) { cancel(); throw error; }
                }
            }
            public void onShowcaseDismissed(MaterialShowcaseView view) { }
        };
        current.addShowcaseListener(displayListener); current.setDetachedListener(this);
        try { if (!current.show(mActivity)) cancel(); }
        catch (RuntimeException error) {
            if (generation == token) {
                try { cancel(); } catch (RuntimeException cleanup) { if (cleanup != error) error.addSuppressed(cleanup); }
            }
            throw error;
        }
    }
    @Override public void onShowcaseDetached(MaterialShowcaseView view, boolean wasDismissed, boolean wasSkipped) {
        if (!running || view != current) return;
        view.setDetachedListener(null); view.removeShowcaseListener(displayListener); current = null;
        if (!wasDismissed && !wasSkipped) { cancel(); return; }
        long token = generation;
        try { if (dismissed != null) dismissed.onDismiss(view, position); }
        catch (RuntimeException error) { cancel(); throw error; }
        if (!running || token != generation) return;
        position++;
        if (mPrefsManager != null) mPrefsManager.setSequenceStatus(position);
        if (wasSkipped) { running = false; if (mPrefsManager != null) mPrefsManager.setFired(); }
        else showNext();
    }
    public void setConfig(ShowcaseConfig config) { mConfig = config; }
    public interface OnSequenceItemShownListener { void onShow(MaterialShowcaseView itemView, int position); }
    public interface OnSequenceItemDismissedListener { void onDismiss(MaterialShowcaseView itemView, int position); }
}
