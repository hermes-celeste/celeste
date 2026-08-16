# DF-01 — Assistant display name

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-01-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/DF-01-assistant-display-name.md`

## Recorded investigation

The following evidence was checked during the preceding research pass. The current Hermes server/Desktop source is protocol authority; Conduit is interaction precedent only.

| Verified fact | Citation |
| --- | --- |
| The assistant transcript currently hard-codes `Hermes`; the assistant-specific idle/running composer copy does too. The transcript label renderer currently uppercases labels. | `app/src/main/java/dev/hazydreams/hermesceleste/ui/conversation/TranscriptItem.kt:86-112,139-153`; `app/src/main/java/dev/hazydreams/hermesceleste/ui/conversation/ConversationScreen.kt:186-200` |
| Top-level UI state and profile/session actions are ViewModel-owned; there is no alias field today. | `app/src/main/java/dev/hazydreams/hermesceleste/CelesteViewModel.kt:64-82,126-153` |
| Settings currently has a Connection section and Gateway row, while existing text-field components provide semantic labels/content descriptions. | `app/src/main/java/dev/hazydreams/hermesceleste/ui/gateway/GatewayScreens.kt:172-220,512-562`; `app/src/main/java/dev/hazydreams/hermesceleste/ui/CelesteRoutes.kt:49-119` |
| Session create/resume sends profile and `source:"android"`, with no display-name field; current tests assert the request shape. | `app/src/main/java/dev/hazydreams/hermesceleste/network/GatewaySessionApi.kt:23-81`; `docs/hermes-protocol.md:55-69`; `app/src/test/java/dev/hazydreams/hermesceleste/CelesteViewModelTest.kt:211-257` |
| Dashboard URL normalization preserves a path prefix, so host-only identity would be incorrect. | `app/src/main/java/dev/hazydreams/hermesceleste/network/DashboardUrlPolicy.kt`; `app/src/test/java/dev/hazydreams/hermesceleste/network/DashboardUrlPolicyTest.kt:7-29` |
| Forget removes the descriptor/key, while sign-out retains safe endpoint metadata. | `docs/architecture.md:45-51`; `app/src/main/java/dev/hazydreams/hermesceleste/connection/SavedConnection.kt:13-37`; `app/src/main/java/dev/hazydreams/hermesceleste/connection/AndroidConnectionStore.kt:24-54,118-153,175-216`; `app/src/test/java/dev/hazydreams/hermesceleste/connection/ConnectionStoreTest.kt:45-66` |
| Existing backup rules exclude shared preferences, app files/database, and external data. | `app/src/main/res/xml/backup_rules.xml:3-9`; `app/src/main/res/xml/data_extraction_rules.xml:3-20` |
| Hermes profile routing and runtime session state use the backend profile ID; Hermes skin has server-owned `branding.agent_name`, but Desktop does not use it as a chat label. Prompt assembly keeps gateway overlays out of cached prompt layers. | `Hermes Agent snapshot: docs/profile-routing.md:95-117`; `Hermes Agent snapshot: tui_gateway/methods_session.py:14-69,77-109,127-157`; `Hermes Agent snapshot: apps/shared/src/skin.ts:1-17,76-110`; `Hermes Agent snapshot: tui_gateway/server.py:3468-3539`; `Hermes Agent snapshot: apps/desktop/src/themes/skin.ts:1-16,40-77`; `Hermes Agent snapshot: website/docs/developer-guide/prompt-assembly.md:27-40,238-253` |
| Conduit documents profile presentation names as local-only, with a `Hermes` fallback. Its global key is precedent for the boundary, not a safe key for Celeste. | `Hermes Conduit snapshot: Conduit/Services/ProfileAppearanceStore.swift:1-29`; `Hermes Conduit snapshot: Conduit/Services/AppState.swift:334-341,834-894,1532-1546`; `Hermes Conduit snapshot: Conduit/Views/AuxiliaryViews.swift:652-699` |
| The repository's validation model is lower-boundary unit tests, Compose screenshot checks, and explicit physical-device reporting; there is no Android instrumentation suite today. | `docs/testing.md:3-16,18-32,40-61,76-88` |
