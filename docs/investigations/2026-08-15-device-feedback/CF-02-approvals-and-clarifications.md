# CF-02 — approvals and clarifications

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-02-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/CF-02-approvals-and-clarifications.md`

## Recorded investigation

| Evidence | What it establishes |
| --- | --- |
| Hermes server `tui_gateway/server.py:3389-3395` | `_block()` creates request IDs and stores pending prompt payloads; the ID is application request identity, not merely a UI list index. |
| Hermes server `methods_prompt.py:1296-1302` and `server.py:11388-11399` | `clarify.respond` is request-ID keyed and a stale request can return `status: "expired"`; expiry is a terminal stale outcome, not successful input. |
| Hermes server `methods_prompt.py:1350-1402` and `tools/approval.py:2609-2686` | Approvals are queued per session, carry request IDs, and expose `approval.pending`, `approval.received`, and `approval.respond` behavior. |
| Hermes server `server.py:8488-8537` | Authoritative live-session payloads expose `pending_approval` and `pending_clarify` for reconnect/replay. These fields are the basis for reconciliation, not a client-owned durable queue. |
| Hermes Desktop `apps/desktop/src/store/prompts.ts`, `.../clarify.ts`, `.../tool/approval.tsx`, `.../gateway-event.ts` | Desktop keeps prompt projections per session, replays pending approval/clarification state, renders inline controls, and dispatches protocol notifications. It is comparison evidence for interaction and lifecycle, not mobile UI authority. |
| Hermes Conduit `Models/Models.swift`, `StreamEventParser.swift`, `ChatView.swift`, `AppState.swift`, `PushNotificationService.swift` | Native activity cards distinguish pending/submitting/settled/error, authenticate response paths, represent stale/elsewhere outcomes, and gate notification fallback. This is mobile comparison evidence, not Hermes protocol authority. |
| Celeste `app/src/main/java/dev/hazydreams/hermesceleste/network/GatewaySessionApi.kt:49-102` | Current typed API has session resume, prompt submit, and interrupt, but no typed approval/clarification adapter. |
| Celeste `app/src/main/java/dev/hazydreams/hermesceleste/network/HermesGateway.kt:185-255` | The Gateway assigns JSON-RPC transport IDs, correlates responses, emits generic events, and fails pending transport calls on disconnect. It deliberately does not own session reconciliation or user decisions. |
| `docs/architecture.md:20-37,53-89` | Compose emits intent; `CelesteViewModel` owns application, session, and reconnect state; the dashboard remains authoritative; stored and runtime session IDs are distinct. |
| `docs/security.md:1-7,23-30,44-59` | The repository treats received Gateway content and agent controls as sensitive. |
