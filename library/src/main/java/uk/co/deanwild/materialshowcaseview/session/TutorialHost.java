package uk.co.deanwild.materialshowcaseview.session;

/** Rendering boundary. All completions must arrive on the Scheduler thread. */
public interface TutorialHost extends Cancellation {
    /** Execute asynchronous host work only while its originating run/presentation is current. */
    interface Callbacks { void run(Runnable work); }
    boolean ready();
    boolean valid(Step step, boolean requireVisible);
    Cancellation observe(Runnable changed);
    Cancellation prepare(Step step, Scope scope, Runnable complete);
    Cancellation show(Step step, Actions actions, Runnable shown);
    Cancellation hide(Runnable hidden);
    default Cancellation observe(Runnable changed, Callbacks callbacks) { return observe(changed); }
    default Cancellation prepare(Step step, Scope scope, Runnable complete, Callbacks callbacks) { return prepare(step, scope, complete); }
    default Cancellation show(Step step, Actions actions, Runnable shown, Callbacks callbacks) { return show(step, actions, shown); }
    /** Definition position, not a prediction of the number of steps in a dynamic route. */
    default void setProgress(int definitionPosition, int definitionCount) { }
    default String diagnostic() { return ""; }
    interface Actions {
        void next(); void previous(); void skipStep(); void skipTour(); void close(); void actionCompleted();
        /** Host supplies the final identity/geometry check and actual target click. */
        default void activateTarget(Runnable activation) { }
        /** Host validates and invokes the click; return true from the callback when activated. */
        default void targetTapped(java.util.function.BooleanSupplier activation) { }
    }
}
