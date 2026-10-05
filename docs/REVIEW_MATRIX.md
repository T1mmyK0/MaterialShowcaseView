# Structured library review

Review date: 2026-10-05. This review covers the core library and optional lifecycle adapter,
including the uncommitted repairs from the preceding review. The sample is an integration fixture.
The subsequent API 24 baseline cleanup expanded the suite to 400 passing tests; see
[current validation](VALIDATION.md). The findings below preserve the earlier review's counts.

## Completion criteria

1. Trace every owned asynchronous resource to cancellation and every application callback to
   its error/reentrancy boundary across sessions, Android hosts, legacy views and tooltips.
2. Exercise cancellation/disposal at each reachable execution phase, with synchronous,
   deferred, duplicate and obsolete completions. Teardown must release ownership without
   committing unfinished progress; old work must not affect a replacement.
3. Verify rendering and usable controls across the available API/layout matrix below,
   including both layout directions, constrained windows, large text, clipping and insets.
4. Reproduce and fix confirmed failures, add regression coverage at the shared mechanism,
   then pass the full regression suite, lint, sample APK and release AAR builds.
5. Record evidence and explicit unverified cells. A completed review is bounded by this matrix;
   it is not a claim that every Android device, extension or future code change is defect-free.

## Mechanism matrix

| ID | Mechanism / implementations | Required evidence | Status |
| --- | --- | --- | --- |
| L1 | Session phases and coordinator ownership | `SessionContractMatrixTest`: 8 phases × 3 exits (cancel, dispose, coordinator disposal); exact release before terminal notification; no unfinished progress | Pass: 24 cases |
| L2 | Asynchronous completion and reentrancy | Matrix repeats every obsolete callback/action against a replacement; `TutorialSessionTest`, `SessionSafetyTest`, `ReentrantSessionTest` cover synchronous registration, duplicate completion, restarts and mutation during callbacks | Pass |
| L3 | Resources and extension failures | `AndroidOwnershipMatrixTest`, `ExtensionFailureMatrixTest`, `AndroidSafetyTest`, `PresentationRegressionTest`: custom preparation/content/target/animation/listener errors and independent cleanup | Pass after fixes |
| L4 | Android attachment and lifecycle | External removal during entrance/show/exit on APIs 28/30; real window teardown; existing focus, lifecycle, Back and RecyclerView regressions | Pass after fixes; device limits below |
| P1 | Input and progress | Existing session, Android and sample suites: cancelled/moving/obsolete gestures, scoped actions, Previous/Next, branch history, CAS/reset/replay and policy acceptance | Pass |
| V1 | Legacy and session content | `LayoutContractMatrixTest`: 3 window sizes × 2 directions × 2 font scales × 2 renderers × 2 APIs; scroll to and click the final control | Pass: 48 configurations |
| V2 | Tooltip geometry and reuse | 4 placements × 3 alignments × 2 directions × 2 font scales × 2 APIs; natural-width recovery; live direction changes; existing edge/clipping/inset/motion/reuse tests | Pass: 96 placement configurations plus focused regressions |
| I1 | Build and integration | Full three-module tests/lint, sample debug APK and both release AARs; API 24 sample interactions | Pass; see device evidence |

## Ownership and callback audit

| Owner | Owned work and release boundary | Guard / evidence |
| --- | --- | --- |
| `TutorialSession` run and phase scopes | Observation, deadlines, delay, preparation/application cleanup, presentation and exit handles; phase transitions and terminal teardown close scopes before events | Run/step/generation checks; `ownOperation` registers resources before synchronous completion; 95 deterministic tests |
| `TutorialCoordinator` | Active request and queue; cancellation/disposal drains ownership even when callbacks throw | Existing replacement, queued revalidation and reentrant terminal tests; matrix checks no remaining owner |
| `AndroidTutorialHost` | Attach/focus/layout/scroll/pre-draw listeners, preparation/reveal settling and application scope, overlay | Individual registrations plus host disposal; observer transfer and stale resolver tests |
| `TutorialOverlay` | Posted reveal, alpha animator, modal focus/accessibility changes and attached view | Session callback boundary around framework/application callbacks; external detach cancels the current presentation even before show or during exit; independent restoration despite a throwing child |
| Legacy showcase and sequence | Handler, pending layout, geometry observers, mask bitmap, factory animations, tooltip and sequence listeners | Presentation generations; cleanup guards at rendering/input/animation boundaries; cancellation never commits dismissal; window detach releases resources without removing children during Android's traversal |
| `ShowcaseTooltip` | Deferred attachment/setup, target tracking, remeasurement, animation and custom content callbacks | Tooltip generation and captured owner generation; failures release both bubble and owning showcase; obsolete callbacks cannot remove replacements |
| Lifecycle / RecyclerView adapters | Lifecycle observer, Back registration, item-layout observer and preparation completion | Disposal and one-shot completion tests; stable item identity across scrolling/reorder |
| Progress stores | Immutable progress snapshots and revisions, rather than presentation Views | Existing branch/cursor/reset/replay/stale-writer tests; SharedPreferences contract remains single-process |

Application extensions still own work they allocate before throwing without returning/registering
a cancellation handle. Private custom animations must implement the documented cancellation
extension. The library cannot safely recover arbitrary application corruption or off-contract
cross-thread mutation; these are existing API boundaries, not claims established by the matrix.

## Device and layout matrix

Framework simulation and device evidence are separate; one does not substitute for the other.

| Surface | Variants | Evidence / status |
| --- | --- | --- |
| Robolectric framework | APIs 24, 28 and 30; 320×480, 480×320 and 240×320 content windows; LTR/RTL; font scales 1 and 2 | Pass; 216 combined content/tooltip configurations, plus live geometry checks |
| Usable viewport | API 30 bars/cutout/keyboard; padded/clipped parents; narrow windows; transformed/oversized targets | Pass in framework tests (`ModernGeometryTest`, `AndroidRegressionTest`, `AndroidHostTest`) |
| API 24 emulator | Portrait/landscape, RTL applied through Developer options, enlarged text, sample navigation and cancellation | Pass: legacy scroll/dismiss, both tooltips, lifecycle Next/Previous/Back, rotation; final runtime error log empty |
| Modern Android device | Edge-to-edge/IME and Android 13+ predictive Back | Unverified: only an API 24 system image is installed; Back adapter tests simulate dispatcher events |
| Accessibility service | TalkBack and switch access on a device | Unverified: services not installed; focus/property restoration and standard controls are tested |
| Minimum supported API 24 | Core, lifecycle adapter and sample | See the current [API 24 validation](VALIDATION.md); API 12–23 are no longer supported |

Split-screen device testing, heap profiling, every locale/font/device combination, and arbitrary
oversized custom tooltip content are outside this run. Tooltip placement tests use anchors with
room for their explicitly requested side; the API does not promise automatic side selection.

## Findings and evidence

The review added **55 executed test cases** to the previous 198. Final result: **253 passed,
0 failed, 0 skipped** (95 deterministic session, 79 legacy Android, 55 Android host/layout,
6 lifecycle and 18 sample). Lint has 0 errors and 34 existing warnings (1 core, 2 lifecycle,
31 sample). The sample APK and both release AARs build. Local log: `build/structured-final.log`.

Confirmed repairs:

1. External session-overlay removal during entrance/display/exit now cancels the session,
   releases coordinator ownership and restores background accessibility. Cleanup attempts all
   restorations even if a custom child throws during detach, preserving secondary failures.
2. Tooltip attachment, measurement, layout, drawing, touch and display-callback failures now
   clean up their owning showcase/sequence as well as the tooltip. Rejected sequence startup
   also releases ownership, without writing completion.
3. A side tooltip temporarily constrained by its anchor can recover its original width policy
   when more space becomes available.
4. A displayed legacy target becoming hidden, detached or fully transparent cancels its
   showcase/sequence without completing progress.
5. Real API 24 rotation revealed a window-teardown crash: the legacy view removed itself from
   the child list Android was traversing. It now releases its resources without changing that
   parent list. The same failure was reproduced in a framework regression with a following sibling.
6. The sample tooltip toolbar now sizes to its content, preventing the Show label from clipping
   at doubled font size.

Before-fix evidence (generated local logs): `structured-ownership-before.log`,
`structured-extensions-before.log`, `structured-layout-direction-before.log`,
`structured-owner-propagation-before.log`, `structured-sizing-before.log`,
`structured-target-before.log`, `structured-window-before.log`, and
`device-review/structured-crash.log`, all under `build/`. The size regression first verifies
that its fixture actually constrains the bubble; the corrected fixture failed with width
restoration removed and passed with it restored.

Device screenshots and UI dumps are retained under `build/device-review/structured-*`.
The original rotation failure is retained separately from the fixed run. Device evidence is
recorded in [VALIDATION.md](VALIDATION.md); earlier audit results there remain historical.

## Future change gate

For a lifecycle, callback or renderer change, identify the applicable row above, reproduce the
new failure (if any), extend the shared contract test, and rerun that row and the full suite.
Reopen a completed row when new evidence or implementation changes affect it. Do not treat
another broad request as evidence that more defects must exist, or relabel optional refactoring
as a bug. Unavailable device/service cells remain visible release limits until actually exercised.
