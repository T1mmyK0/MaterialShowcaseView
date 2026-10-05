# Review order

The working-tree implementation is organized into four reviewable layers. No Git commits or
publication were performed, and pre-existing changes to the wrapper JAR and SampleActivity were
left intact.

1. **Reliability foundation:** review `docs/AUDIT.md`, the legacy view/sequence/animation repairs,
   then `session/{Tutorial,Step,TutorialSession,Scope,Scheduler}` and `TutorialSessionTest`.
   Pay particular attention to generation invalidation, cancellation from cleanup callbacks,
   cancellation during exit, exactly-once terminal reporting and the exit-completion commit point.
2. **Host integration and progress:** review `AndroidTutorialHost`, `TargetValidator`,
   `TargetGeometry`, progress stores, coordinator, `LifecycleTutorial` and `RecyclerViewTarget`.
   Verify stable next-step cursors, legacy -1 suppression, queue reload, readiness deadlines,
   nested blockers and ownership release. `AI_TUTORIAL_MIGRATION.md` explains consumer adoption.
3. **Presentation:** review `TutorialOverlay`, `TutorialTheme`, localized resources and
   `AndroidHostTest`. Mask geometry is separate from content placement. Standard controls,
   accessible focus restoration, completed gestures and zero-duration transitions share the same
   session cleanup. There is no persistent overlay reused by the new engine.
4. **Extensions and release integration:** review conditional/branch/action steps, replay,
   multi-region/custom shapes, custom content, progress indicators, the AI sample and
   `SESSION_API.md`, `CHANGELOG.md`, `VALIDATION.md`.

The legacy API remains independently usable. New consumers should adopt the session package
and optional lifecycle adapter instead of assembling lifecycle behavior around legacy view objects.
