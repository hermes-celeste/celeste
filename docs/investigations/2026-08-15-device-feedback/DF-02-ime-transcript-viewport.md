# DF-02 — IME transcript viewport

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-02-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/DF-02-ime-transcript-viewport.md`

## Recorded investigation

| Evidence | What it establishes |
| --- | --- |
| `app/src/main/java/dev/hazydreams/hermesceleste/ui/conversation/ConversationScreen.kt:81-92,146-185` | The current `LazyListState` is local; streaming text length unconditionally animates to the last item; the transcript has no explicit IME clearance; the composer owns both navigation and IME padding without a documented consumption contract. |
| `app/src/main/java/dev/hazydreams/hermesceleste/ui/CelesteRoutes.kt:79-95` | Conversation Back currently delegates at the route boundary. |
| `app/src/main/java/dev/hazydreams/hermesceleste/MainActivity.kt:28-39` and `app/src/main/AndroidManifest.xml:15-22` | Edge-to-edge bars are enabled, but no explicit IME/soft-input, orientation, or cutout policy is declared. |
| `docs/architecture.md:5-18,20-37,53-89` | Activity owns setup/lifecycle; ViewModel owns authoritative session/turn/draft state; Compose emits intents; the Gateway is transport only; the dashboard/server remains authoritative. |
| `docs/testing.md:5-16,40-61,76-88` | Compose and screenshot checks are useful for layout regressions, but IME/insets/lifecycle/accessibility require instrumentation or a physical device; there is no current `androidTest` suite. |

The supplied phone screenshots were not available to this research pass.
