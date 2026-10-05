# Unreleased — lifecycle-aware onboarding

- **Breaking platform requirement:** raised the core, optional lifecycle adapter and sample
  to Android 7.0 (API 24). Consumers must also use minSdk 24 or newer.
- Removed pre-24 animation, accessibility, clipping and inset compatibility branches; retained
  explicit fade animations and API 26/30 behavior guards. Updated layout-listener removal and HTML parsing.
- Extended legacy, session, lifecycle and sample regression suites to run on API 24 alongside
  their existing API 28/30 coverage.
  Validation: 400 tests pass (147 added API 24 executions), no lint errors, sample APK and both
  release AARs build successfully. Device evidence remains from the earlier review.
- Added immutable tutorial/step definitions, explicit sessions, cancellation scopes, injectable
  scheduling, typed diagnostic callbacks and mutually exclusive terminal outcomes.
- Added lifecycle/window/application gates, nested blockers, lazy validated targets, asynchronous
  preparation, scrolling/settling and host-level queue/reject/replace/deduplication.
- Added stable-ID progress, schema policy, compare-and-set persistence, legacy progress import
  and isolated replay.
- Added standard navigation controls, completed-tap handling, modal accessibility restoration,
  safe-area placement, scrollable content, reduced motion and bitmap-free session masks.
- Added optional AndroidX lifecycle/Back/RecyclerView module, conditional/branch/action steps,
  targetless content, custom content and theming.
- Repaired legacy delayed callbacks, detach/dismiss ambiguity, repeated starts/dismissals,
  listener removal, unsafe preferences, zero-duration animations and circular reveal bounds.
- Added the dynamic Java AI-provider sample and deterministic/Android regression tests.
- Fixed queued schema migration, reset/replay progress isolation and coordinator cleanup when
  application listeners throw.
- Fixed content panels covering additional highlights, oversized mask holes, ignored shape padding,
  blank Skip hit targets and inaccessible dismissal controls in long legacy content.
- Fixed delayed entrances during dismissal, invalid tooltip attachment and Back registration during exit.
- Fixed Previous after resuming saved progress and Next skipping completed steps during backtracking;
  reviewing a completed step preserves its completion and original forward destination.
- Made terminal teardown and event dispatch safe against reentrant restart; restarts requested
  during cleanup/listeners are deferred until the ending run is fully released.
- Bound presentation actions to their run/step/generation and added `completionHandle()` for
  one-shot asynchronous application confirmation. Explicit pauses now survive automatic gates.
- Revalidated touch and accessible target clicks at activation, rejecting changed targets and exit-time taps.
- Routed asynchronous Android callbacks and presentation failures through session cleanup/error reporting.
- Added transformed geometry and shared usable viewport handling, and required explicit policy-dialog acceptance.
- Fixed cancellation of deferred restarts, reentrant callback continuations, nested coordinator
  replacement, and terminal snapshots taken before application diagnostics.
- Registered synchronous host resources before dispatching their completions; failing error-handler
  restarts also release their resources without recursively reporting errors.
- Guarded custom-content drawing/touch failures, retained hint gestures outside panel bounds,
  and preserved policy acceptance requirements across activity recreation.
- Restored legacy tutorial startup from `onCreate` by waiting for initial layout while preserving
  cancellation and rejecting hidden or foreign-window targets.
- Fixed stale tooltip attachments after target replacement/hiding, clipped-anchor coordinates,
  screen-edge alignment and negative side-placement widths.
- Fixed Back-binding disposal from terminal listeners during event dispatch.
- Fixed legacy highlight geometry for transformed targets and rejected taps on clipped target pixels.
- Made direct Android host cancellation release observers and pending preparation resources.
- Kept primary-target enabled/clickable requirements separate from informational highlights.
- Fixed tooltip default content padding and setter-order-dependent arrow spacing.
- Cancelled superseded visibility and movement animations; zero-duration fades now set their final alpha.
- Preserved background keyboard and accessibility focus when showing or closing nonmodal hints.
- Released sample policy dialogs on recreation/provider changes and ignored obsolete dialog callbacks.
- Removed legacy window observers before detachment and refreshed moving/scaling highlights before drawing.
- Prevented stale and duplicate legacy display notifications across reentrant presentations.
- Made animation and tooltip cleanup failures release overlays and sequence ownership without
  persisting unsuccessful dismissal; direct host disposal now also releases preparation-scoped app resources.
- Fixed tooltip positioning in padded parents and released animation/layout work on external
  detachment, entrance failures and throwing display listeners. Duplicate and obsolete callbacks
  cannot notify or tear down replacement presentations.
- Released legacy overlays and sequence ownership when custom target geometry updates fail.
- Preserved progress revisions during global reset so stale sessions cannot restore cleared progress;
  legacy flags and malformed records still reset together.
- Released legacy overlays and sequence ownership after custom drawing, touch geometry and
  pre-draw target failures; removal during drawing cannot reuse a recycled mask bitmap.
- Kept visible tooltips anchored during target motion and placement changes without replaying
  entrance animations. Hidden/detached targets release the tooltip and its tracking observer.
- Constrained tooltip placement to the usable window width, fixing landscape navigation-bar clipping.
- Removed focus listeners transferred into the window during attachment and prevented duplicate
  RecyclerView preparation completions when scrolling synchronously attaches the target.
- Cancelled sessions when their Android overlay is removed during entrance, display or exit;
  background focus/accessibility restoration continues even when custom content detachment throws.
- Routed tooltip content and asynchronous failures through complete showcase/sequence cleanup,
  and released sequence ownership after rejected startup without persisting completion.
- Restored tooltip width when a moving anchor provides more space, and cancelled visible legacy
  showcases whose targets become hidden, detached or transparent.
- Fixed the legacy rotation crash caused by removing children during Android's window teardown.
- Made the sample tooltip toolbar grow with enlarged text instead of clipping its Show control.
- Added a structured ownership, lifecycle and layout [review matrix](docs/REVIEW_MATRIX.md).
  Validation: 253 passing tests; no lint errors; sample APK and release AAR builds pass.
  API 24 checks include rotation, RTL and doubled text; modern-device insets and real TalkBack
  remain unverified.

Core and optional AndroidX module minSdk are both 24.
No Compose or analytics service dependency is added. See
[API contracts](docs/SESSION_API.md), [consumer migration](docs/AI_TUTORIAL_MIGRATION.md) and
[audit](docs/AUDIT.md) before adopting corrected legacy dismissal behavior.
