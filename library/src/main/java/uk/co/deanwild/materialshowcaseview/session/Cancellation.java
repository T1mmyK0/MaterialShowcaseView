package uk.co.deanwild.materialshowcaseview.session;

/** Idempotent cancellation; all session APIs and callbacks run on the scheduler thread. */
public interface Cancellation {
    void cancel();
    Cancellation NONE = () -> { };
}
