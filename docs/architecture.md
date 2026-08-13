# Architecture

## Shape

Celeste is currently a single-module Android application:

- `MainActivity.kt` owns top-level Compose routing and screen composition.
- `CelesteViewModel.kt` owns application/session state, turn reduction, lifecycle recovery, and user actions.
- `network/DashboardClient.kt` owns dashboard HTTP operations, authentication setup, profile/session discovery, and gateway construction.
- `network/HermesGateway.kt` is a thin persistent JSON-RPC transport.
- `network/GatewaySessionApi.kt` owns typed session RPC requests and response decoding.
- `network/DashboardUrlPolicy.kt` owns dashboard URL normalization and cleartext admission.
- `ui/CelesteTheme.kt` owns current Compose color tokens and Material theme wiring.

Keep this map updated when ownership moves. Do not add a new layer only to match a generic architecture diagram.

## Layer boundaries

### Compose

Compose renders `CelesteUiState` and emits user intent. It must not own credentials, sockets, RPC framing, retry policy, or authoritative session history.

### Application state

`CelesteViewModel` coordinates the selected dashboard, in-memory credential, profile/session selection, persistent gateway, transcript projection, draft, and turn state. It is the boundary between UI intent and protocol operations.

The four turn states are intentionally user-facing projections:

- `Synchronizing` — Celeste is establishing or reconciling authoritative state.
- `Idle` — the active runtime can accept a prompt.
- `Running` — Hermes owns an active turn.
- `Reconnecting` — the draft remains local while Celeste restores the server relationship.

Do not derive protocol truth from animation or view-local state.

### Dashboard client

`DashboardClient` handles short HTTP operations and creates a persistent `GatewayConnection`. Its private cookie jar keeps authenticated HTTP and WebSocket ticket minting in one process-owned boundary.

Session listing uses a disposable WebSocket. `DashboardClient.resumeSession` also uses a disposable, minimally decoded resume path for the live contract test; the production conversation flow uses `GatewayConnection.resumeStoredSession` over the lifecycle-owned persistent gateway. Keep the two paths distinct when changing readiness or decoding behavior.

### Gateway transport

`HermesGateway` correlates JSON-RPC requests, emits gateway events, and reports connection state. It deliberately does not decide session ownership, turn state, reconciliation, or reconnect policy. Keep those decisions above the transport.

## Runtime flow

1. Normalize and probe the dashboard base URL.
2. Establish an in-memory credential: no credential for open loopback, a static machine token, or an authenticated cookie session.
3. List sessions over JSON-RPC and profiles over HTTP.
4. Create or resume a session through a persistent gateway.
5. Reduce gateway events into the transcript and turn state.
6. On interruption, disconnect, or foreground recovery, ask the server for authoritative state before continuing.

The dashboard remains the source of truth throughout this flow. Celeste holds a screen projection and unsent draft, not a competing history database.

## Session identity

Hermes distinguishes a stored session identity from a runtime session identity. Celeste must preserve both:

- the **stored ID** locates durable history and is used to resume/reconcile;
- the **runtime ID** addresses the currently attached gateway session and is used for prompt and interrupt RPCs.

Do not substitute one for the other because they happen to match in a test fixture.

## Reconciliation invariants

- Wait for `gateway.ready` before reporting a gateway as connected or sending RPCs.
- Buffer events while a session snapshot is being resumed, apply the snapshot first, then replay buffered events. This prevents a stale snapshot from overwriting newer stream events.
- Once `prompt.submit` begins, uncertain delivery is reconciled by stored session ID. Never automatically resend the prompt.
- A newly created blank runtime may not yet be resumable. If it disconnects before the first prompt, recreate only that untouched empty session and preserve the draft.
- On foreground, health-check the persistent socket. Replace and reconcile a stale connection rather than trusting an apparently open transport.
- Ignore events carrying a different non-empty runtime session ID.
- Recovered in-flight assistant text may include a prefix already present in persisted history. Render only the unpersisted suffix.
- Attach the event collector before connecting. `HermesGateway.events` has no replay and a bounded extra buffer, so late collectors can miss conversational events.

## Projection limitations

- Tool completion currently pairs with the most recent pending tool by tool name, not by a tool-call ID. Concurrent tools with the same name are ambiguous.
- Interim/final assistant merging and completion deduplication are text- and prefix-based projection rules, not protocol identity guarantees.
- The synthetic summary for a newly created conversation is not refreshed from `session.list` while that conversation remains active.

Regression coverage for these invariants belongs in `CelesteViewModelTest` and `HermesGatewayTest`; see [`testing.md`](testing.md).

## Growth rule

Split files when an ownership boundary becomes real. Likely future seams include screen packages, transcript rendering, connection persistence, and capability-specific protocol adapters. Preserve the layer direction: Compose → application state → dashboard/protocol → transport.
