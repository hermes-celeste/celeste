# DF-05 — Conversation status ownership

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-05-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/DF-05-conversation-status-ownership.md`

## Recorded investigation

Current Celeste source, audited on this lane:

- `app/src/main/java/dev/hazydreams/hermesceleste/CelesteViewModel.kt:48-53` defines `TurnState` (`Synchronizing`, `Idle`, `Running`, `Reconnecting`); `:64-82` carries `turnState`, `streamingText`, `loadingMessage`, and `errorMessage` in `CelesteUiState`.
- `app/src/main/java/dev/hazydreams/hermesceleste/CelesteViewModel.kt:847-883` reconciles running state from authoritative session information/live projection; `:885-920` handles start, delta, and interim events; `:922-950` handles completion, error, and interruption; `:952-964` handles busy/info; `:966-998` handles tool start/complete; `:1076-1117` owns reconnect/retry flow.
- `app/src/main/java/dev/hazydreams/hermesceleste/ui/conversation/ConversationScreen.kt:101-119` renders a header spinner for running/synchronizing; `:120-143` renders header `Responding`; `:186-200` disables the composer while running and uses `Hermes is responding…`; `:230-256` renders footer `Responding`; `:258-272` makes send unavailable except in `Idle`.
- `app/src/main/java/dev/hazydreams/hermesceleste/ui/CelesteRoutes.kt:284-306` defines `StatusMessage` as a visual row without explicit live-region semantics.
- `app/src/main/java/dev/hazydreams/hermesceleste/ui/conversation/TranscriptItem.kt:38-59` defines transcript identity/key material and `:86-136` renders assistant/tool rows without live-region behavior.
- `app/src/main/java/dev/hazydreams/hermesceleste/network/HermesGateway.kt` and `GatewaySessionApi.kt` provide the existing event/session transport. The audited events already cover generic ownership; this item does not require a new protocol method.

Comparison evidence from the source snapshots used in the audit:

- Hermes Conduit, upstream snapshot `858162a8493300aa37980419ebf007e22dbe4191`: state-to-action mapping in `Conduit/Models/Models.swift:378-452`; synchronizing/reconnecting composer behavior in `Conduit/Views/Components/ComposerBar.swift:5-7,283-329`; explicit action labels in `ComposerBar.swift:478-510,902-920`; streaming/tool/reasoning ownership in `Conduit/Views/ChatView.swift:88-129,1548-1584,1638-1759,2063-2184`; stable tool-card mutation/deduplication in `AppState.swift:7072-7110,6927-7110,7130-7284`.
