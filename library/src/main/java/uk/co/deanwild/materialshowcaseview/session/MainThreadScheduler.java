package uk.co.deanwild.materialshowcaseview.session;
import android.os.Handler;
import android.os.Looper;
public final class MainThreadScheduler implements Scheduler {
    @Override public long nowMillis() { return android.os.SystemClock.uptimeMillis(); }
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Override public void checkThread() { if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Call on the main thread"); }
    @Override public Cancellation post(long delay, Runnable task) {
        checkThread(); handler.postDelayed(task, delay); return () -> handler.removeCallbacks(task);
    }
}
