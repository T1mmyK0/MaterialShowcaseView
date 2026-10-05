# Lifecycle-aware tutorials

`library` keeps the original `uk.co.deanwild.materialshowcaseview` API and minSdk 12.
The additive `session` package separates definitions, execution, persistence and rendering.
`lifecycle` is an optional AndroidX adapter (minSdk 23); it includes LifecycleOwner,
OnBackPressedDispatcher and RecyclerView integration. No Compose dependency is introduced.

All session mutations, host methods, listener callbacks, preparation completions, blocker releases
and cancellation handles belong to the main thread. `MainThreadScheduler` enforces this contract;
the pure engine accepts an injected `Scheduler` for deterministic tests. Post a background result
to the main thread before completing preparation. Do not move a session into a ViewModel or static
field. A definition contains no Views, but its predicates/branches can capture application objects;
keep those definitions scoped accordingly.

## Java setup

```java
Tutorial tutorial = new Tutorial("welcome", 1,
    Step.builder("intro").content("Welcome", "Here is your workspace.").build(),
    Step.builder("save").target("save-button")
        .content("Save", "Save your changes here.").requireEnabled(true).build());
AndroidTutorialHost host = new AndroidTutorialHost(activity.getWindow(),
    id -> activity.findViewById(R.id.save));
TutorialCoordinator screen = new TutorialCoordinator();
TutorialSession session = new TutorialSession(tutorial, host,
    new MainThreadScheduler(), new SharedPreferencesProgressStore(activity),
    screen, TutorialCoordinator.Conflict.QUEUE);
new LifecycleTutorial(owner, host, session)
    .interceptBack(activity.getOnBackPressedDispatcher(), owner);
session.setEligibility(() -> editorIsReady());
session.start();
```

For fragments pass `getViewLifecycleOwner()` from `onViewCreated`, resolve from the current view,
and create a new host/session for each new view. The fragment view lifecycle is separate from
the fragment lifecycle ([Android guidance](https://developer.android.com/guide/fragments/lifecycle)).
The adapter disposes on destruction. Dispose the screen coordinator when the screen is destroyed.
Without the adapter forward resumed/paused state to `host.setResumed()` and call `session.dispose()`
on destruction. On API 12–17 also forward window focus changes through `host.invalidate()`.

Pass a Dialog's Window to deliberately scope a tutorial to a dialog or bottom sheet; use that
presentation's owner and dispose on its dismissal. Do not use an Activity host for targets inside
another window.

## Operations and events

| Operation | Semantics |
| --- | --- |
| `start()` | Read persisted progress; active/queued repeated calls are no-ops. After cancellation, starts a new run. |
| `pause(reason)` | Cancel presentation/preparation work, preserve the step. Explicit pauses require `resume()`. |
| `resume()` | Recheck gates and resolve/prepare again. Never revives cancelled or disposed sessions. |
| `cancel(reason)` | Cancel the run and any pending restart without writing completion; release coordinator ownership. |
| `next()` | Only advances a currently displayed, valid step; unavailable in action-driven modes. |
| `actionCompleted()` | Immediate confirmation of the currently displayed step; do not retain a session method reference for async work. |
| `completionHandle()` | Capture a one-shot `Runnable` while SHOWING for asynchronous confirmation of this presentation only. |
| `previous()` | Revisit the preceding step in navigation history, including completed predecessors restored on resume; persisted completions remain intact. |
| `skipStep()` | Explicitly record a skipped step after exit; distinct from completion. |
| `skipTour()` | Record a terminal user skip and release presentation immediately. |
| `dispose()` | Idempotently release host, callbacks and observers; reject subsequent work. |
| `resetProgress()` | Cancel if active, reset just this tutorial. Does not automatically start. |

The states are `IDLE`, `QUEUED`, `WAITING`, `PREPARING`, `SHOWING`, `HIDING`, `PAUSED`,
`COMPLETED`, `CANCELLED`, `SKIPPED`, `FAILED`. Runtime state is never persisted.

**Commit point:** a Next/action/step-skip commits after the exit animation's completion callback.
Cancellation during entrance or exit invalidates the generation first, then tears down resources;
late animation callbacks cannot commit. Zero-duration exits commit synchronously. Cancellation
after that commit preserves the already committed step. Showing/requesting never completes a step.

`Listener` receives structured `Event` values containing tutorial ID, stable step ID, session ID,
run ID, state, kind and typed reason. State events include queued, waiting, preparing, paused and
terminal outcomes. Additional kinds identify shown, resumed, step completed and step skipped.
Shown means attached and visible at entrance start, not entrance completion. A resumed presentation
emits another shown event. Revisiting an already committed step does not repeat its completion event.
On a fresh run, Previous history is restored from earlier completed IDs in the current definition's
order. Skipped, removed and never-visited IDs are not restored. The store does not record historical
branch order, so reordering a definition also reorders its restored predecessors. During a run,
Previous follows the actual route, and Next returns along that review path without skipping completed
steps or evaluating their branches again. Backtracking alone does not change the saved resume cursor.
Listener registration is deduplicated; dispatch uses a snapshot. Removal during dispatch applies
to future dispatches. A terminal event is emitted once per run, after resources and ownership are
released. There is no analytics service or network telemetry.
Restart requests from cleanup or terminal listeners are coalesced and posted to the next scheduler
turn, after all ending resources, coordinator ownership and terminal listeners have been released.
The terminal event is an immutable snapshot of the ending run; a restart cannot change that event
or prevent later listeners in its dispatch snapshot from receiving it. Cancellation, reset and disposal
cancel deferred restarts. Event data is captured before invoking diagnostic callbacks as well.
Synchronous host completions are delivered after their returned cleanup handle is registered.
Replacement reserves the incoming request before invoking outgoing listeners; nested replacements
wait for all outgoing teardown to finish, and cancellation/disposal can withdraw a pending request.

Install `setErrorHandler` to report application callback errors. The default rethrows on the main
thread after cleanup; a custom handler can log/report instead. A callback failure while active
ends with `FAILED/CALLBACK_ERROR`. A failure in an already terminal callback cannot change that
terminal outcome. Cleanup scopes attempt all registered releases even if one throws.
The Android host routes posted presentation work, pre-draw observations, asynchronous preparation
completion, reveal strategies, custom-content drawing/touch dispatch and target activation through
the same failure path. Custom hosts
should override the `Callbacks` overloads and execute asynchronous work through their supplied guard.
These guards ignore work whose run/presentation has ended and report current callback failures only
after tearing down presentation resources and ownership.
Operations stop if a predicate, branch, resolver or other application callback changes their run or
presentation. An error handler may restart a session; if that new run also fails inside the handler,
its resources are released and its exception propagates without recursively invoking the handler.
`Event.diagnostic` includes blocker labels or the target ID and validation detail.
`TargetValidator.diagnose()` distinguishes missing, detached, wrong-window, hidden, zero-size,
disabled, nonclickable and clipped targets.

## Eligibility, blockers and readiness

Use `setEligible(boolean)` or `setEligibility(predicate)` plus `invalidateEligibility()` when
application state changes. Predicates are not polled. A lifecycle/focus change is also an
invalidation. A blocking gate pauses resources and automatically resumes when ready; an explicit
pause does not automatically resume.
An explicit pause also takes effect while already paused by a gate. Releasing blockers or returning
to the foreground will not override it. Calling `resume()` clears the explicit pause, but presentation
still waits for every automatic gate to become ready.

```java
Cancellation policy = session.block("policy-dialog");
boolean[] accepted = {false};
// Set accepted[0] = true only from the dialog's positive acceptance action.
dialog.setOnDismissListener(ignored -> {
    try {
        if (!accepted[0] && session.isActive()) session.cancel(TutorialSession.Reason.USER);
    } finally {
        policy.cancel();
    }
});
dialog.show();
session.start();
```

Blocker tokens are independently idempotent. Releasing one does not release another. A resumed
lifecycle and visible rectangle cannot prove that another window is not covering a target; focus
and application blockers remain necessary.

Targets are resolved before preparation, after completion, before shown notification, and on
layout/scroll/attachment invalidations. Validation covers window membership, ancestors' visibility
and alpha, size and clipped usable geometry. Enabled/clickable checks are opt-in, so labels are
valid. For changing enabled/alpha properties that do not schedule layout, explicitly invalidate.

Unavailable policies: `WAIT` installs event-driven readiness with a timeout (default 15 seconds),
`PAUSE` requires explicit resume, `CANCEL` preserves progress, `SKIP_OPTIONAL` only skips an explicitly
optional step, and `FAIL` ends diagnostically. Missing required targets never count as completion.
`Step.Builder.onTimeout()` chooses pause, cancel, optional skip or failure. The timeout covers one
readiness episode including display delay and preparation; repeated layout/preparation attempts
cannot extend it. Background/focus/application gating cancels the current timeout; a fresh full timeout starts on
resumption. There is no background countdown and no busy timer loop. Preparation uses a small
number of pre-draw observations to confirm stable geometry, with a timeout for ongoing movement.

## Preparation and scrolling

`host.setPreparation((step, scope, ready) -> ...)` can asynchronously expand UI, load data,
accommodate the keyboard or settle application animations. Return a cancellation handle and use
`scope.own(resourceCleanup)` for resources whose lifetime should include the visible step.
Duplicate and late completions are ignored. Cleanup does not depend on reaching a final step.
Every presentation, including a resumed final step, repeats preparation and lazy resolution.
For collapsed/disabled/detached targets that preparation is responsible for repairing, explicitly
enable `host.setPrepareUnavailableTargets(true)`. Validation still runs; this opt-in allows the
preparation phase to repair it. All post-preparation and presentation checks remain mandatory.

The built-in reveal requests a padded rectangle through scrolling ancestors, including ScrollView
and NestedScrollView, then waits for stable geometry. `setAlignment` supports nearest, center,
start and end; custom containers can implement `RevealStrategy`. Oversized targets use the usable
clipped viewport as the required visible extent instead of waiting for an impossible full fit.
Target and ancestor scaling is included in screen-space bounds. Reveal, validation and panel
placement share the usable viewport, including keyboard, system-bar and cutout insets. The app
must still resize/inset its scrolling content for the keyboard (as the sample does), or use
preparation to dismiss the keyboard; the host cannot create extra scroll range in application content.

For RecyclerView use `RecyclerViewTarget` in the adapter: it resolves by stable item ID on each
call, observes item/data changes and scrolls to the current adapter position. Use
`host.setPrepareMissingTargets(true)` so preparation can materialize a missing holder, and install
`item.prepare(scope, ready)` as the preparation hook. The final validator still requires a real,
attached target. The adapter and stable IDs must remain consistent while preparation is active.

## Persistence and coordination

`ProgressStore` uses compare-and-set revisions. SharedPreferences operations are synchronized
across store instances in this process; multi-process apps should supply a transactional store.
Completed/skipped IDs and the next stable destination survive insertion/reordering, including a
branch interrupted before its destination is completed. Resumption prefers that destination;
if it was removed, it finds the first remaining uncommitted definition. New steps encountered
after the cursor are eligible; newly inserted earlier steps are not retroactively forced into
the current run. A terminal tour remains suppressed until explicit reset/replay.
`Tutorial.Migration` selects keeping stable IDs, resetting progress on a schema change, or failure
requiring an explicit migration. Removed IDs can remain harmlessly in the record.

No store supplied means an in-memory store with safe queries. Empty definitions end as
`SKIPPED/EMPTY` without writing completion. Optional conditions evaluate when reached; false optional
steps are recorded as skipped. A tour whose optional steps are all inapplicable finishes traversal
with only skipped step IDs. Required false conditions wait under their unavailable policy.

One `TutorialCoordinator` represents an exclusive screen/window group. `QUEUE` waits for ownership,
`REJECT` cancels the new request, `REPLACE` cancels active and queued requests, and `DEDUPLICATE`
rejects an existing tutorial identity while queuing different identities. Queued requests reload
progress and recheck eligibility on acquisition. Keep one coordinator for all competing tutorials
on a window. There is no static Activity registry.

## Presentation and extensions

`NEXT` uses explicit controls; `BACKGROUND_TAP` advances only on a completed single-pointer tap;
`TARGET_ACTION` invokes the existing target click handler on a completed highlighted tap or an
accessible button; `APPLICATION_ACTION` waits for application confirmation; `HINT` allows background
interaction and preserves background input focus. Closing a hint also preserves focus changes made
in the application while it was visible. Target action does not automatically advance: the app confirms successful work.
Application click/touch listeners are never replaced. Target-action mode supports click actions,
not arbitrary text editing/drag gestures through the mask.
Each presentation receives its own guarded `Actions`. A stale action cannot affect another step,
run or provider. Touch and accessible activation both recheck current eligibility, window membership,
visibility, usable geometry, enabled state and clickability. Exit animations reject further activation;
a touch gesture also requires the same target and geometry at its start and end.

For asynchronous work, capture the completion handle when the displayed step starts the operation:

```java
Runnable complete = session.completionHandle();
// In your operation's success callback, post this captured handle to the main thread:
mainHandler.post(complete);
```

Do not capture `session::actionCompleted` for asynchronous results: that immediate command refers to
the current step at invocation time. Captured completion handles are one-shot and become inert on
navigation, pause, cancellation, replacement or disposal; obtain a new handle after resumption.

Navigation labels come from localizable resources or `TutorialTheme`. Previous, Next, Skip step,
Skip tour and Close visibility and `Navigation` actions can be configured independently. Keep at least one exit action.
The AndroidX Back callback is enabled only while showing/hiding. It commits cancellation in
`handleOnBackPressed`; a cancelled predictive gesture does nothing, consistent with
[Android Back guidance](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture).

Text uses sp, standard focusable buttons and scrollable content. Modal presentation temporarily
hides sibling content from accessibility and restores its importance and keyboard focus on exit.
The mask and content safe area are independent; the latter respects visible-window bounds and,
on API 30+, system bars, cutouts and IME insets. See
[edge-to-edge guidance](https://developer.android.com/develop/ui/views/layout/edge-to-edge).
The renderer uses a reusable Path rather than a full-window bitmap. Rounded corners and padding
are configurable; `TutorialTheme.highlightShape` adds application-defined closed paths. Additional
`Step.Builder.highlight(id)` regions are informational and must be simultaneously visible with
the primary scrolling anchor; incompatible offscreen regions follow the unavailable policy.
Enabled/clickable requirements apply to the primary target only; informational regions still
require valid attachment, visibility and geometry.
Overlapping regions combine into one cutout. Legacy custom shapes remain available in MaterialShowcaseView.

`TutorialTheme` controls colors, text size, spacing, navigation labels and animation duration;
title/content/button text appearance resources are supported. Use resource-selected colors for light/dark styling. `reducedMotion` disables transitions; system
animation disabling is respected through
[ValueAnimator.areAnimatorsEnabled](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled()).
`contentFactory` creates a fresh custom View for each presentation; navigation remains library-owned.

Branches return stable step IDs. Missing destinations, self-loops and revisiting a traversed branch
destination fail without committing the current step. A null destination follows definition order.
Replay is a constructor option that uses ephemeral progress without resetting normal onboarding
history. Application-confirmed actions and conditional steps use the same cancellation model.

The optional progress label displays the current **definition position** and definition count,
including conditional and branching destinations. It does not predict a route's remaining length
or show a misleading percentage; a branch or Previous may move the number in either direction.
`Event.completedSteps/definedSteps` are available for custom diagnostics; `getProgress()` returns
an immutable persistence snapshot (or replay progress).

Predicates and branch selectors should be side-effect-free. Mutate the session from event or
preparation callbacks instead; these paths explicitly support cancellation and scoped cleanup.
