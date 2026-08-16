# DF-06 — Active-turn steering

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-06-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/DF-06-active-turn-steering.md`

## Recorded investigation

The research handoff is a map, not protocol authority; the checked official source and the Celeste source below are authoritative.

| Evidence | Verified symbols and line ranges | Recorded observation |
| --- | --- | --- |
| Celeste state and send path | `app/src/main/java/dev/hazydreams/hermesceleste/CelesteViewModel.kt:77-80,700-740` (`CelesteUiState`, `sendMessage`) | Current send accepts only `Idle`, creates a local pending bubble, calls `prompt.submit`, and reconciles on failure. |
| Celeste interrupt/reconnect/reconcile | `app/src/main/java/dev/hazydreams/hermesceleste/CelesteViewModel.kt:743-768,821-883` (`interrupt`, `reconnectNow`, `observeGateway`, `reconcile`, `applyResumedSession`) | Reconnect already resumes by stored ID, replaces the runtime ID, and buffers events. |
| Current RPC seam | `app/src/main/java/dev/hazydreams/hermesceleste/network/GatewaySessionApi.kt:49-106` (`resumeStoredSession`, `submitPrompt`, `interruptSession`) | The typed API has resume, prompt-submit, and interrupt helpers, but no steer, redirect, or explicitly queued-submit helper. |
| Current composer gate | `app/src/main/java/dev/hazydreams/hermesceleste/ui/conversation/ConversationScreen.kt:69-73,185-210,246-265` | The field is currently enabled only for `Idle`/`Reconnecting`, Enter sends only from `Idle`, and `Running` exposes Stop. |
| Current protocol contract | `docs/hermes-protocol.md:43-80` | The recorded contract uses `session.resume` for recovery, `prompt.submit` as the durable turn boundary, and `session.interrupt` for interruption. |
| Current server busy policy | `Hermes Agent snapshot: tui_gateway/server.py:536-541,7879-7974` (`_load_busy_input_mode`, `_handle_busy_submit`) | Official busy modes are `queue`, `steer`, and `interrupt`; `queued: true` overrides the configured mode; attachments and unsupported active-turn payloads fall through to queue. |
| Current server RPCs | `Hermes Agent snapshot: tui_gateway/methods_session.py:2944-3015,3221-3297` (`session.interrupt`, `session.steer`, `session.redirect`) | `steer` returns `queued`/`rejected`; `redirect` returns `redirected`/`rejected`, with explicit `4010` unsupported behavior; interrupt clears the server queue. |
| Current agent semantics | `Hermes Agent snapshot: run_agent.py:3300-3459` (`AIAgent.steer`, `AIAgent.redirect`) | Steer concatenates FIFO guidance at a safe boundary. Redirect preserves completed work and retries the model request; during tools it degrades to steer. Both are gateway-owned, not client-generated transcript messages. |
| Redirect race handling | `Hermes Agent snapshot: agent/conversation_loop.py:2804-2858` | A response/redirect race is reconciled by discarding stale response context and rebuilding with the correction. |
| Desktop interaction reference | `Hermes Agent snapshot: apps/desktop/src/app/chat/composer/hooks/use-composer-submit.ts:181-257` (`submitDraft`, `steerDraft`, `queueDraft`) | Desktop permits active drafting, steers plain text, queues attachments, and queues on an explicit rejection; it does not resend an uncertain request. This is interaction evidence, not a mobile layout mandate. |
| Conduit state reference | `Hermes Conduit snapshot: Conduit/Models/Models.swift:380-443` (`TurnState`, `BusyInputMode`) | Conduit is comparison evidence only. |
| Conduit RPC reference | `Hermes Conduit snapshot: Conduit/Services/HermesClient.swift:894-923` (`sendPrompt`, `steer`, `redirect`, `cancel`) | The four operation boundaries and rejection mapping are already a proven mobile seam. |
