# Validation and release limits

## Lifecycle binding follow-up — 2026-10-05

Two constructor regressions were reproduced and repaired:

- A resume callback may destroy the lifecycle owner during synchronous observer registration.
  The binding now returns safely after disposal instead of dereferencing its cleared lifecycle.
- A throwing resume callback can abort construction before the caller receives the binding.
  Failed construction now unregisters the observer and disposes the session/host while preserving
  the original exception.

`LifecycleTutorialTest` covers both cases on APIs 24 and 28, including observer removal,
coordinator release and rejection of further work on the disposed session. Before-fix evidence
is in `build/remaining-bugs-lifecycle-before.log` and `build/remaining-bugs-binding-before.log`;
the focused fixed suite is in `build/remaining-bugs-lifecycle-after.log`.

Full validation passed with `--offline --rerun-tasks`: **529 tests, 0 failed, 0 skipped**
(475 core, 16 lifecycle and 38 sample). Lint reports 0 errors and the same 34 existing warnings.
The sample debug APK and both release AARs build successfully; all 207 Gradle tasks executed.
The log is `build/remaining-bugs-final.log`. This follow-up adds four test executions to the
525-test suite following the overlay/target-tap changes. No new device or accessibility-service
checks were performed; the device coverage limits below still apply.

## Earlier validation

Validation performed on 2026-10-05 using the repository's Gradle 9.8.0 / AGP 9.4.1 setup,
installed JetBrains JDK 25 and Android SDK 37.2. Android framework tests use Robolectric 4.17,
APIs 24, 28 and 30; deterministic engine tests use a virtual clock and deliberately stale callbacks.
The core, lifecycle adapter and sample now all require API 24.

Final result: **415 tests passed, 0 failed, 0 skipped** (367 core, 12 lifecycle/Back/RecyclerView
and 36 sample startup/policy-dialog tests). This includes 95 deterministic session tests and
147 added API 24 executions of existing Android regression tests, plus 15 tooltip drawing checks.
Lint reports **0 errors** across all modules:
1 core dependency-version warning, 2 lifecycle dependency-version warnings, and 31 sample warnings.
The sample APK and both release AARs build successfully. Gradle also reports deprecations for
future Gradle 10, and Robolectric/Conscrypt emits a JDK native-access warning; neither failed checks.

The completed [structured review matrix](REVIEW_MATRIX.md) records the ownership/callback audit,
8-phase cancellation matrix, content/tooltip layouts, confirmed repairs and unverified device cells.
The layout matrix now covers 216 configurations across APIs 24/28/30. The earlier structured review
added 55 test cases to the previous 198; the API 24 expansion brings the suite from 253 to 400.
The subsequent tooltip drawing review brings the suite to 415. Its full tests/lint/APK/AAR run
used `--offline --rerun-tasks`: all 207 Gradle tasks executed successfully. The latest build log
is `build/review-20261005-final.log`; `build/min-sdk24-validation.log`, `build/structured-final.log`
and the dated review sections below preserve earlier evidence.

The tooltip review corrected three rendering mechanisms:

- Left/right arrows now point toward the target center for START, CENTER and END alignment.
- Left/right arrow depth now follows the configured `arrowHeight` instead of a fixed 30 pixels.
- Changing arrow width, source/target margins or corner size rebuilds the displayed bubble path.

`TooltipDrawingTest` adds five tests on each of APIs 24/28/30. The first four tests all failed
against the original implementation (`build/review-20261005-drawing-before.log`); the fifth
covers the related live corner update. The focused drawing/layout/presentation run passed in
`build/review-20261005-drawing-after.log` before the full run.

The freshly built APK was installed on the API 24 emulator. Both tooltip sequence bubbles
were inspected visually; lifecycle Next advanced to step 2, Previous returned to step 1, and
Back cancelled the session after dismissing the keyboard. The AndroidRuntime/AiTutorial error
log was empty (`build/review-20261005-runtime.log`). Screenshots are
`build/review-20261005-tooltip.png`, `build/review-20261005-tooltip-second.png` and
`build/review-20261005-session.png`. This smoke run does not extend the existing modern-device,
predictive-Back or accessibility-service coverage limits.

The expanded tests retain the existing API 28/30 coverage, with modern inset tests on API 30.
The tooltip width-recovery fixture uses content with a fixed intrinsic size so differences in
simulated text metrics do not determine whether it is constrained. The outside-dialog test sends
a complete DOWN/UP gesture to exercise dismissal on both API 24 and API 30. All module merged
manifests declare minSdk 24. No new emulator/device run was performed for that SDK cleanup;
the subsequent smoke run is described above, and the device observations below are historical.

Reproduce:

```text
gradlew :library:testDebugUnitTest :lifecycle:testDebugUnitTest :sample:testDebugUnitTest
gradlew :library:lintDebug :lifecycle:lintDebug :sample:lintDebug
gradlew :sample:assembleDebug :library:assembleRelease :lifecycle:assembleRelease
```

The suite covers:

- First-visit startup of all four legacy samples, waiting for the first layout, and cancellation
  before a pending presentation can become visible.
- Tooltip target replacement and hiding during deferred attachment, clipped target coordinates,
  start/end alignment at screen edges and nonnegative side-placement widths.
- Disposing a lifecycle/Back binding from a terminal session listener during event dispatch.
- Cancellation before delay, stale callbacks, repeated start, provider switching and final draft
  eligibility after database restoration.
- Entrance/exit cancellation, duplicate/late preparation, cancellation from application cleanup,
  idempotent disposal, throwing listeners and listener mutation.
- Nested blockers, background gating, readiness timeout policy and deadlines that cannot be
  extended indefinitely by repeated layout/preparation attempts.
- Queue/reject/replace/deduplication, queued eligibility/progress revalidation and disposal of
  active/queued ownership.
- Stable IDs and next-step cursor across insertion/reordering and branching, legacy 0/1–3/-1
  import, malformed legacy data, isolated reset, stale writers and replay isolation.
- Conditional/action steps, invalid branches/loops, Previous without duplicate completion and
  targetless/empty definitions.
- Previous after resuming at step two (reused, recreated and queued sessions), ordered forward
  navigation through reviewed steps, preserved branch destinations and a real Previous button click.
- Legacy listener removal, nonpersistent queries, repeated dismissal, delay cancellation/reuse,
  external detach, tooltip cancellation, all factory animation categories and corner reveal radius.
- Real Android View attachment/visibility/disabled checks, clipped and oversized geometry,
  ScrollView reveal of the final target below long content, animation-driven invalidation and
  dialog window coordinate conversion.
- Standard focusable navigation controls, accessibility-property/focus restoration, modal touch
  isolation, zero-duration rendering and cancelled reveal callbacks.
- LifecycleOwner resume/pause/destroy, predictive Back cancellation versus committed Back,
  RecyclerView stable identity after scrolling/reordering, and repeated creation/disposal.
- Queued schema migration after progress reload, strict migration after an explicit reset,
  stale writes after reset, replay-reset isolation, and coordinator disposal despite listener failures.
- Additional-highlight panel avoidance, clipping oversized highlights to visible target geometry,
  configured/replacement shape padding, hidden empty Skip controls and scrollable legacy content.
- Suppression of delayed entrance during dismissal, tooltip target validation before attachment,
  and Back interception registered while an exit animation is already running.
- Reentrant restart from preparation, overlay and observation cleanup; deferred terminal-listener
  restart; immutable terminal dispatch to multiple listeners; exact ownership/observer counts.
- Run/step/presentation-scoped actions and one-shot async completions across navigation, pause,
  restart, provider replacement and disposal; manual pauses independent of all automatic gates.
- Target taps rejected after hiding, detachment, movement, replacement or loss of clickability;
  touch/accessibility activation suppressed during exit and revalidated against current eligibility.
- Cleanup before reporting async resolver, reveal, pre-draw, reposition, custom-content measurement,
  target-click and diagnostic failures; stale preparation callbacks cannot invoke their resolver.
- Scaled targets/ancestors, API 30 keyboard/system-bar/cutout viewport constraints, and restoration
  directly to an API-key icon below a long prompt through nested scrolling.
- Actual sample AlertDialog acceptance versus negative, Back, outside-touch and programmatic dismissal.
- Cancellation/reset of deferred restarts, reentrant predicates/validation/branches, diagnostic
  snapshot stability, nested replacement and cancellation/disposal/pause of an incoming request.
- Cleanup after failures inside an error-handler restart, and registration of synchronous
  preparation/presentation/exit resources before their completion callbacks reach listeners.
- Custom-content drawing/touch failures, complete hint gestures outside panel bounds, and
  disposal inside Android resolvers without spurious failure reports.
- Policy acceptance retained across recreation; an open or dismissed unaccepted dialog cannot
  be bypassed by recreating the sample activity.
- Pausing or opening a blocker inside step-completion/skip listeners preserves the committed
  destination, branch route and Previous history, including completion of the final step.
- Legacy target gestures reject distant DOWN/UP endpoints, disabled or hidden targets, and
  activation during exit animations. Oversized highlights leave a usable scrollable content panel.
- Sample replay, reset and target-test controls enforce unaccepted policy dialogs; choosing Other
  cannot reset Gemini progress, and provider changes release replay ownership and lifecycle bindings.

The 11 additional regression tests above failed against the reviewed implementation before the
fixes and pass afterward. The follow-up build log is `build/review-final-checks.log`.

A subsequent correctness review added five regressions, each confirmed failing before its fix:

- Legacy highlight coordinates include target and ancestor transformations.
- Legacy target taps reject clipped pixels and clipping changes between DOWN and UP.
- Direct Android host cancellation releases framework observers and pending preparation work;
  late preparation callbacks cannot resolve a disposed host or complete a cancelled request.
- Additional informational highlights do not inherit the primary target's enabled/clickable
  requirements, while attachment, visibility and clipping validation remain enforced.

All 162 tests, the three lint tasks, the sample debug APK and both release AAR builds pass.
The latest build log is `build/correctness-final-checks.log`; the before-fix regression logs are
`build/correctness-regressions-before.log` and `build/correctness-highlight-before.log`.

The latest project review added seven tests and strengthened the existing policy-recreation test:

- Default tooltip content stays inside the bubble and below its arrow; padding and arrow-height
  configuration produce the same layout regardless of setter order.
- Starting a new entrance or exit cancels the previous visibility transition. Zero-duration fades
  apply the final alpha immediately, and an obsolete exit cannot dismiss a new presentation.
- Nonmodal hints preserve background keyboard focus when shown and do not undo a user's subsequent
  focus change when closed. Modal overlays retain their focus restoration behavior.
- Policy dialogs release their old window on recreation or provider replacement. Obsolete dismiss
  callbacks cannot cancel a replacement session, and explicit acceptance remains required.

These failures were reproduced before the fixes in `build/project-review-regressions-before.log`
and `build/project-review-hint-before.log`. All 169 tests and the lint/APK/AAR checks now pass;
the latest build log is `build/project-review-final.log`.

The subsequent library safety review added nine regressions, all reproduced before their fixes:

- Legacy removal unregisters layout and pre-draw observers from the attached window before
  detaching, preventing retained showcases and callbacks after removal.
- Translation and scale changes update legacy highlight position and dimensions without a layout pass.
- Reentrant restart and duplicate animation callbacks cannot deliver stale or repeated display events.
- Throwing entrance, exit or cancellation implementations release the overlay. Cleanup failures
  also release sequence ownership, preserve single-use progress and allow later reuse.
- Throwing custom tooltip animation cleanup still removes the tooltip from its parent.
- Direct Android host disposal releases application resources registered through preparation's
  scope, including after preparation completes. These resources still survive throughout a normal
  visible step; disposal does not close unrelated caller-owned resources.

All **178 tests**, all three lint tasks, the sample debug APK and both release AAR builds pass.
Logs: `build/safety-review-before.log`, `build/safety-review-callbacks-before.log`,
`build/safety-review-dismissal-before.log` and `build/safety-review-final.log`.
This pass used automated geometry and lifecycle regressions; it did not repeat the earlier device
smoke checks or expand the device/accessibility matrix described below.

The latest follow-up added ten regression tests covering tooltip placement in padded parents,
external detachment and deferred resize cleanup, throwing entrance/display callbacks, duplicate
notifications, reentrant replacement, custom legacy geometry failures, and global progress reset.
The placement, external animation cleanup, entrance/display failure, legacy geometry cleanup and
stale-writer reset cases were reproduced before their fixes in `build/current-review-before.log`
and `build/current-review-reset-before.log`. Global reset now retains version-zero revision
records for modern tutorials while clearing legacy flags, including malformed records.

All **188 tests**, the three lint tasks, the sample debug APK and both release AAR builds pass.
The latest build log is `build/current-review-final.log`. This follow-up used automated Android
regressions; it did not perform new emulator or TalkBack checks.

The latest library audit added ten regressions covering:

- Cleanup after custom shape drawing, touch geometry and pre-draw target failures, without
  committing single-use or sequence progress; safe removal from inside a shape's draw callback.
- Visible tooltips following moving targets and placement changes without repeating entrance
  animations; release when a target disappears, and rejection of obsolete tracking callbacks.
- Tooltip placement inside both horizontal window insets, including a right-side navigation bar.
- Focus-listener cleanup after registration on an unattached root and transfer to its window tree.
- Exactly one RecyclerView preparation completion when scrolling synchronously attaches the item.

Seven regressions were reproduced before their respective fixes in `build/library-audit-before.log`,
`build/library-audit-lifecycle-before.log` and `build/library-audit-insets-before.log`; the other three
cover related cancellation and drawing paths. All **198 tests**, all three lint tasks, the sample
debug APK and both release AAR builds pass. The final build log is `build/library-audit-final.log`.

Build outputs:

- `library/build/outputs/aar/library-release.aar`
- `lifecycle/build/outputs/aar/lifecycle-release.aar`
- `sample/build/outputs/apk/debug/sample-debug.apk`

## Limits that remain

- The structured review installed the final sample APK on a temporary read-only API 24 emulator.
  Portrait and doubled-text landscape checks verified the legacy highlight, scrolling to Got it,
  and actual dismissal. Rotation exposed a real window-teardown crash; it was reproduced in a
  regression and repaired before repeating the device checks. RTL was applied through Android's
  Developer options, then screenshots verified both tooltip steps, mirrored anchors, readable
  enlarged toolbar text, and landscape navigation-bar clearance. Actual Next/Previous taps under
  RTL and doubled text moved from step one to two and back, followed by landscape recreation.
  Back then produced `CANCELLED / USER`. No AndroidRuntime, WindowManager or AiTutorial errors
  were recorded in `structured-final-runtime-errors.log`; the temporary emulator was stopped.
  Final screenshots/UI dumps use `build/device-review/structured-final-*`; legacy scrolling uses
  `structured-legacy-controls.*`. The original crash is retained in `structured-crash.log`.
- This audit inspected both tooltip placements on a temporary API 24 emulator. Rotation exposed
  navigation-bar clipping in the second tooltip; after the fix and APK reinstall, its complete
  text and arrow fit within the landscape viewport. Screenshots and UI dumps are under
  `build/device-review/library-audit-*`, including `library-audit-tooltip-landscape-fixed.png`.
  No AndroidRuntime or WindowManager errors were reported in `library-audit-runtime-errors.log`.
  The read-only emulator was stopped after verification. Target motion and observer cleanup were
  checked with automated regressions; this does not expand the modern-device or TalkBack coverage.
- This review used a temporary API 24 emulator to inspect both tooltip placements and to rotate an
  open Gemini policy dialog. The recreated dialog remained visible and acceptance displayed the
  landscape tutorial with scrollable controls. No AndroidRuntime, WindowManager or AiTutorial errors
  were logged. Screenshots, UI dumps and the error log are under `build/device-review/project-review-*`.
  The temporary emulator was stopped after verification.
- The latest rebuilt APK was installed on the API 24 emulator. Screenshots confirmed the legacy
  highlight and lifecycle panel placement with the keyboard open. Actual Next and Previous taps
  moved from step one to step two and back. Screenshots and UI dumps are in
  `build/device-review/correctness-*`. The background emulator was stopped after these checks.
- The installed **Medium_Phone_API_24** emulator was started and the rebuilt sample APK was installed.
  Device checks confirmed a visible tutorial with the keyboard open, a compressed accessibility
  hierarchy containing only modal tutorial content, cancellation via policy-dialog Back/outside/negative
  dismissal, presentation after explicit acceptance, Tab navigation through tutorial content to the
  Previous button, and Next advancing the final APK to step two. Screenshots, UI dumps and focus observations
  are retained locally under `build/device-review/` (generated, not source-controlled).
  A second check on the rebuilt APK confirmed resuming at step two, Previous returning to step one,
  and Next returning to step two with the keyboard open (`recheck-previous-api24.png` and matching XML dumps).
  The policy dialog also remained open after landscape recreation, and Back then cancelled the
  restored tutorial (`recheck-policy-rotation-api24.xml` / `recheck-policy-back-api24.xml`).
- The follow-up review installed the rebuilt APK on the API 24 emulator and inspected the tutorial
  with the keyboard open (`review-session-api24.png`). Back cancelled the Gemini policy dialog;
  Replay reopened that unaccepted dialog (`review-replay-policy-api24.xml` / `.png`), and explicit
  acceptance displayed the replay (`review-replay-accepted-api24.xml` / `.png`). These artifacts are
  also in `build/device-review/`.
- Only an API 24 system image is installed. **Modern window-inset behavior has not been verified on
  a modern device/emulator**; the API 30 keyboard/cutout tests use framework simulation. TalkBack
  is not installed on the emulator, so actual spoken navigation and switch access remain unverified.
  Predictive gestures on Android 13+, split-screen device QA and heap profiling remain outside
  this run. API 12–23 are no longer supported. Large-font RTL/landscape checks cover the recorded
  API 24 sample scenarios and APIs 24/28/30 framework matrix, not every device, locale or font.
- The built-in target interaction mode supports existing click actions. It deliberately does not
  forward arbitrary editable/drag/multitouch gestures through the mask; use application-confirmed
  actions or nonmodal hints for those flows.
- Additional regions must be simultaneously visible with the primary anchor. The library cannot
  reveal mutually incompatible regions in different scroll positions. Custom/virtualized containers
  require a reveal strategy; RecyclerView integration requires stable IDs and a consistent adapter.
- Custom legacy animation implementations remain supported, but must implement the optional
  cancellation extension to stop their privately owned animator resources. Late library callbacks
  are guarded even without the extension.
- SharedPreferences revision checks are single-process. A multi-process application must supply
  a transactional ProgressStore. Numeric legacy import must use the original step order once.
- There is no dedicated Compose artifact in this change. Compose-only targets need a View anchor
  and application adapter; View/Java consumers receive no Compose dependency.
- The old bitmap renderer remains for legacy custom Shape compatibility. New sessions use a path
  mask. System animation disabling is queried on API 26+; use reducedMotion on older systems.

The sample's **Start blocked tutorial for target tests** / **Release target-test blocker** controls
allow targets to be mutated while a run is pending without introducing a timer. The policy dialog
uses its actual dismissal callback, not a fixed delay. Test its four targets with a long prompt,
provider changes, rotation, backgrounding, font scaling and accessibility services before release.
