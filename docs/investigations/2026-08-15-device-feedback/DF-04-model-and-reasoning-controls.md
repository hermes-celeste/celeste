# DF-04 — Model and reasoning controls

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-04-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/DF-04-model-and-reasoning-controls.md`

## Recorded investigation

| Evidence |
| --- |
| The reference screenshot shows a compact `GPT-5.6-sol · XHigh`-style label beside composer controls. It does not establish persistence, protocol shape, or error behavior. |
| Current `ConversationScreen` exposes no runtime-control state or callback (`app/src/main/java/dev/hazydreams/hermesceleste/ui/conversation/ConversationScreen.kt:66-80`), and its visible status is derived only from `TurnState` (`:112-143`). |
| `GatewaySessionApi.resumeStoredSession` already separates runtime and stored identities and receives `running`, `status`, and in-flight data (`app/src/main/java/dev/hazydreams/hermesceleste/network/GatewaySessionApi.kt:49-81`). |
| `HermesGateway` is deliberately a thin JSON-RPC/event transport (`app/src/main/java/dev/hazydreams/hermesceleste/network/HermesGateway.kt:68-70`); `CelesteViewModel` owns reconciliation and event reduction (`app/src/main/java/dev/hazydreams/hermesceleste/CelesteViewModel.kt:821-883`). |
| The official Desktop/server audit found `model.options`, `session.info`, and `config.get`/`config.set` as the relevant current surfaces. Current gateways can defer a model switch while a turn runs; older gateways may reject it. |
| Celeste already ignores events for a different gateway/runtime session and buffers events during reconciliation (`CelesteViewModel.kt:821-861`, `:885-964`). |

## Unresolved source and behavior questions

- What exact current response fields and scope identify effective provider, model, reasoning-enabled state, and effort in `model.options`, `session.resume`, and `session.info`?
- Does the current Hermes apply surface accept model/provider and reasoning atomically?
- Which capability/error code distinguishes “busy but defer-queue supported” from an older gateway that rejects the same request?
- Is the effective runtime state session-scoped for every supported model/provider/reasoning value, or can a profile/global change affect the open session?
- What is the official compatibility behavior when an older gateway omits `model.options` but still returns partial effective fields?
