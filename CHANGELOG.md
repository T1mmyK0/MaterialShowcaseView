# Unreleased — lifecycle-aware onboarding

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
- Validation: 188 passing tests; no lint errors; sample APK and release AAR builds pass.
  API 24 emulator smoke checks completed; modern-device insets and real TalkBack remain unverified.

Core minSdk remains 12. The optional AndroidX module requires API 23.
No Compose or analytics service dependency is added. See
[API contracts](docs/SESSION_API.md), [consumer migration](docs/AI_TUTORIAL_MIGRATION.md) and
[audit](docs/AUDIT.md) before adopting corrected legacy dismissal behavior.
