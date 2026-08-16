# DF-10 — Mobile conversation browser

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-10-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/DF-10-conversation-browser.md`

## Recorded investigation

The current functional boundary already includes session-list restoration without opening a conversation, explicit create/resume, streaming, interruption, and reconnect reconciliation, while broader Desktop management is absent (`docs/product.md:27-39`).

The original investigation recorded that `CelesteRoutes` has only Content/Settings/Gateway destinations, `SessionListScreen` is a flat list with profile selection, new conversation, and row-open callbacks, and `StoredSession` lacks last-activity, running, pinned, and archived fields. It also found no Celeste session-mutation RPCs in the inspected source. The discarded working note did not retain durable primary-source line pointers for those observations.

Current architecture already separates Compose, application state, dashboard operations, gateway transport, and saved connection storage (`docs/architecture.md:5-18,20-55`). Durable stored session IDs locate history; runtime IDs address the attached gateway and prompt/interrupt operations (`docs/architecture.md:70-77`). The existing connection store persists only connection descriptors and reusable authentication, not sessions (`docs/security.md:23-36,44-59`; `app/src/main/java/dev/hazydreams/hermesceleste/connection/ConnectionStore.kt:3-14`).

## Unresolved source and behavior questions

- What exact target-server fields and route parameters define last activity, status/running, source, archive, pin, and profile ownership?
- Does the target support profile-filtered list, server search, and bounded paging?
- Which exact rename/pin/archive/restore/delete methods exist, and can they safely address an actively running session?
- Is pin server-owned?
