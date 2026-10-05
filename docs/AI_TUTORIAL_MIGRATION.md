# Migrating an AI tutorial controller

Set the consuming app's `minSdk` to at least 24 (Android 7.0) before upgrading.
Both the core library and optional lifecycle adapter now require API 24; this version
drops support for older Android releases while preserving the existing Java APIs.

The executable Java reference is `sample/.../AiTutorialActivity.java`. Launch **Lifecycle AI
tutorials** from the sample menu. It declares four stable steps for each provider:

| Step ID | Target | Purpose |
| --- | --- | --- |
| prompt | reply_message_text | Explain the AI prompt |
| default | no_reply | Import the default prompt |
| replacements | add_tags | Insert answer replacements |
| api-key | info_gpt / info_gemini | Obtain an API key |

Use tutorial IDs **sc_gpt** and **sc_gemini**, matching the suffixes of the existing keys. One
screen-owned coordinator is the exclusive AI group; provider sessions use REPLACE. The app
continues to own selected-provider state, database/draft restoration and policy acceptance.

1. Create views and load the saved rule.
2. Restore unsaved editor state, including provider selection.
3. Create a fresh host and session for each provider, with lazy target resolution and eligibility
   tied to the final selected provider. Bind each to the Activity lifecycle or fragment view lifecycle.
4. Start only after restoration. For asynchronous restoration, hold a blocker until both loads
   have settled; changing provider must invalidate/cancel the previous session.
5. Before showing a policy dialog acquire a blocker and track explicit acceptance. On every
   dismissal path (negative button, Back, outside touch or programmatic dismissal), cancel the run
   unless accepted, then release the blocker. Do not use a postDelayed estimate.
6. On provider changes cancel the previous provider's run, update app state/visibility, then start
   the selected provider. Queued and delayed callbacks cannot outlive that cancellation.
7. Dispose the coordinator with the screen. Preparation resource releases belong to `Scope`,
   not a final-step callback. The lifecycle binding disposes each session and its host.

Remove application-owned tutorial handlers, delayed-runnable lists, generation counters,
per-step ScrollView logic and overlay removal work once the new session owns presentation.
Keep application-specific preparation (expanding panels, data loading, keyboard decisions) in
the preparation hook and register cancellation there. The host reveals and settles every target,
including when legacy progress resumes directly at the API-key icon below a long prompt.

## Existing records

`SharedPreferencesProgressStore` reads **material_showcaseview_prefs** and imports on first use:

| Existing key/value | Import |
| --- | --- |
| status_sc_gpt / status_sc_gemini = 0 | No committed steps |
| 1–3 | Mark that many leading stable IDs completed; display the next uncompleted ID |
| -1 | LEGACY_FINISHED: suppress automatic display, preserving ambiguous old completion/skip semantics |

The original keys remain available; stable records use `tutorial_v2_<id>`. Import using the original
four-step order before shipping reordered definitions, since a numeric legacy record cannot recover
identity from a new order. Later schema changes operate on stable IDs. Malformed numeric values
restart conservatively; malformed new records fail diagnostically and require explicit reset.
Reset removes only the selected legacy key and resets only its stable record. Avoid the legacy
`resetAll` API unless intentionally resetting every tutorial.

Do not run the legacy sequence and new session for the same tutorial concurrently: the legacy
writer does not participate in revision checks. Replay from Help uses `replay=true` and leaves
both legacy and normal onboarding history intact.
