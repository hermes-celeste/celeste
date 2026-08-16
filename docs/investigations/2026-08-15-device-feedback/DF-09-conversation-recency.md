# DF-09 — Conversation recency

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-09-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/DF-09-conversation-recency.md`

## Recorded investigation

The current Celeste model has no `last_active` value, decodes only `started_at`, publishes the list without a client sort, and renders the server result directly with `session.id` as the Compose key (`app/src/main/java/dev/hazydreams/hermesceleste/network/DashboardClient.kt:60-68,638-651`; `app/src/main/java/dev/hazydreams/hermesceleste/CelesteViewModel.kt:508-559`; `app/src/main/java/dev/hazydreams/hermesceleste/ui/sessions/SessionListScreen.kt:157-199`). The foreground `session.list` call is currently a health check whose result is discarded (`CelesteViewModel.kt:788-815`).

Hermes is the authority for recency. The dashboard REST list already exposes `last_active`, orders with `order=recent`, includes profile ownership, and returns pagination metadata (`Hermes Agent snapshot: hermes_cli/web_routers/sessions.py:53-159`; `Hermes Agent snapshot: hermes_cli/web_routers/profiles.py:82-229`; `Hermes Agent snapshot: apps/desktop/src/types/hermes.ts:464-523`). The current gateway `session.list` is ordered by the same server value but omits it (`Hermes Agent snapshot: tui_gateway/methods_session.py:162-209`).

Hermes Desktop merges by durable identity, carries optimistic activity monotonically, guards stale list responses, and tests fallback/ties/lineage behavior. Hermes Conduit also treats dashboard history as authoritative and uses a bounded in-memory cache only as a stale-response guard, not as a second history store.

## Unresolved source and behavior questions

- Does every supported deployment broadcast `sessions.changed`?
