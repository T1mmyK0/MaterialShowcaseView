# Source audit and compatibility decisions

The original implementation was inspected before introducing sessions: MaterialShowcaseView,
MaterialShowcaseSequence, PrefsManager, ShowcaseConfig, ViewTarget, ShowcaseTooltip,
FadeAnimationFactory and CircularRevealAnimationFactory.

| Confirmed defect | Repair / regression coverage |
| --- | --- |
| Handler reference cleared but pending callbacks remain | Cancel Handler work and invalidate generation before removal; delayed-removal/reuse test |
| Factory animators have no owner/cancellation | Add compatible CancellableAnimationFactory and per-view animation registry; custom callback staleness test |
| Sequence shown callback fires before visibility | Bridge to the view displayed callback; delayed shown/callback cancellation test |
| Detachment invokes dismissal | Separate intentional commit from external detach; external detach and exit cancellation tests |
| Repeated starts consume queue concurrently | Keep definitions as a list, guard active run; repeated start test |
| hasFired and preference access crash without singleUse | Null-safe queries; nonpersistent query test |
| Geometry read without target attachment checks | Legacy window/hierarchy validation followed by first-layout readiness checks; full validation in new host; hidden/disabled/detached tests |
| Listener removal uses sequence type | Add correct IShowcaseListener overload, keep old signature; removal/deduplication test |
| Teardown destroys animation/prefs/listener configuration | Preserve reusable configuration, reconstruct presentation observers; reuse test |
| Nonpersistent sequence position never increments | Advance position independent of preference storage |
| Tooltip delayed attachment survives teardown | Owned runnable/layout cancellation and detached-animation callback guards |
| Reveal radius misses far corners | Compute hypotenuse to farthest x/y edges |
| Zero fade config ignored | Accept zero; factory completes synchronously for zero/disabled animation |
| Raw DOWN/MOVE causes dismissal | Commit only completed taps; drag/cancel regression |
| Malformed legacy preferences throw | Conservative legacy import/read; malformed/CAS test |
| Cleanup callback exceptions can strand ownership | Scoped cleanup and terminal release before callbacks; throwing-listener tests |

Constructors, package names, builders, resource IDs and IAnimationFactory methods remain. No abstract
methods were added to existing interfaces. Implement CancellableAnimationFactory to stop private
custom animator resources; old implementations still compile and their late library callbacks are
generation-guarded, but the library cannot cancel an animator a custom factory does not expose.

The historical wrong listener-removal overload remains for binary compatibility. Prefer the
IShowcaseListener overload. `hasFired()` remains a persistence query; `isRunning()` is the legacy
pending/active query and `TutorialSession.isActive()/getState()` are the new equivalents.

Intentional behavior corrections: external removal no longer emits public dismissal or advances
progress; single-use completion is recorded after intentional dismissal instead of display request;
repeated dismissals are ignored. Legacy target-touch now invokes the existing click listener only
after a completed tap, consuming that gesture to avoid click-through. Applications relying on raw
touch forwarding should adopt explicit target/application action mode.

Legacy views still retain constructor Context and target references until the caller releases the
view; never store them across recreation. Use fresh sessions/hosts for new screens. New definitions
use logical target IDs and persistent records contain no Views. The original bitmap renderer is
kept for legacy custom Shape compatibility; the session renderer uses a Path mask.
