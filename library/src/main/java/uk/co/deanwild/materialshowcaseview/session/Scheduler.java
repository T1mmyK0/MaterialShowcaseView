package uk.co.deanwild.materialshowcaseview.session;

/** Injectable monotonic scheduler. Tasks must not run inline from post(). */
public interface Scheduler {
    void checkThread();
    Cancellation post(long delayMillis, Runnable task);
    default long nowMillis() { return System.nanoTime() / 1000000L; }
}
