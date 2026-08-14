# Architecture

## Shape

Celeste is currently a single-module Android application:

- `MainActivity.kt` owns Activity setup, ViewModel wiring, and lifecycle forwarding.
- `CelesteViewModel.kt` owns application/session state, turn reduction, lifecycle recovery, and user actions.
- `ui/CelesteRoutes.kt` owns top-level destination selection and the shared screen backdrop.
- `ui/gateway/`, `ui/sessions/`, and `ui/conversation/` own their existing screen areas; transcript row identity and rendering stay with conversation UI.
- `network/DashboardClient.kt` owns dashboard HTTP operations, authentication setup, profile/session discovery, and gateway construction.
- `network/HermesGateway.kt` is a thin persistent JSON-RPC transport.
- `network/GatewaySessionApi.kt` owns typed session RPC requests and response decoding.
- `network/DashboardUrlPolicy.kt` owns dashboard URL normalization and cleartext admission.
- `connection/` owns saved-connection modeling, cold-start decisions, and the Android Keystore storage adapter.
- `ui/CelesteTheme.kt` owns current Compose color tokens and Material theme wiring.

Keep this map updated when ownership moves. Do not add a new layer only to match a generic architecture diagram.

## Layer boundaries

### Compose

Compose renders `CelesteUiState` and emits user intent. Top-level routing keeps conversations separate from **Settings → Gateway**; first-run setup and failed-restore recovery reuse the same Gateway editor rather than introducing a second connection flow. Compose must not own credentials, sockets, RPC framing, retry policy, or authoritative session history.

### Application state

`CelesteViewModel` coordinates cold-start restoration, the selected dashboard, in-memory credential, profile/session selection, persistent gateway, transcript projection, draft, and turn state. It is the boundary between UI intent and protocol operations. Restoration stops after loading the session list; only explicit user intent opens a conversation.

The four turn states are intentionally user-facing projections:

- `Synchronizing` — Celeste is establishing or reconciling authoritative state.
- `Idle` — the active runtime can accept a prompt.
- `Running` — Hermes owns an active turn.
- `Reconnecting` — the draft remains local while Celeste restores the server relationship.

Do not derive protocol truth from animation or view-local state.

### Dashboard client

`DashboardClient` handles short HTTP operations and creates a persistent `GatewayConnection`. Its private cookie jar keeps authenticated HTTP and WebSocket ticket minting in one process-owned boundary. The client can export and restore only Hermes session cookies through a redacted value type; it rejects material that does not match the normalized endpoint host and cookie path.

Session listing uses a disposable WebSocket. `DashboardClient.resumeSession` also uses a disposable, minimally decoded resume path for the live contract test; the production conversation flow uses `GatewayConnection.resumeStoredSession` over the lifecycle-owned persistent gateway. Keep the two paths distinct when changing readiness or decoding behavior.

### Saved connection

`ConnectionStore` separates non-secret endpoint/account metadata from reusable authentication material. Production uses `AndroidConnectionStore`: metadata is stored in a private preference file, while static tokens or provider session cookies are encrypted with an unlocked-device AES-GCM key in Android Keystore and written under `noBackupFilesDir`. AES-GCM additional authenticated data binds ciphertext to the application ID, descriptor version, normalized endpoint including path prefix, and authentication mode. There is no plaintext fallback.

The Gateway settings surface edits the endpoint directly and applies a change only through an explicit reconnect action. `Sign out` deletes encrypted authentication material and disables automatic restoration while retaining safe endpoint/account prefill. `Forget connection` also deletes the descriptor and Keystore key. Definitive authentication rejection deletes reusable authentication while retaining safe prefill; transient network and server failures preserve the saved connection for explicit Retry.

Provider cookies may rotate while Hermes refreshes a session. Celeste snapshots the latest private cookie-jar state after cold restore and on app background. A mutex serializes persistence with Sign out and Forget connection, and connection-generation checks prevent stale async work from resurrecting cleared authentication.

### Gateway transport

`HermesGateway` correlates JSON-RPC requests, emits gateway events, and reports connection state. It deliberately does not decide session ownership, turn state, reconciliation, or reconnect policy. Keep those decisions above the transport.

## Runtime flow

1. Load the one saved descriptor, if present, and re-run URL admission before any restore attempt.
2. Restore origin-bound encrypted authentication material when automatic login remains enabled, otherwise prefill manual connection fields.
3. Normalize and probe the dashboard base URL.
4. Establish an in-memory credential: no credential for open loopback, a static machine token, or an authenticated cookie session.
5. List sessions over JSON-RPC and profiles over HTTP without selecting a conversation.
6. Create or resume a session through a persistent gateway only after user selection.
7. Reduce gateway events into the transcript and turn state.
8. On interruption, disconnect, or foreground recovery, ask the server for authoritative state before continuing.

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
- Give every rendered transcript row a unique UI identity. Prefer a scalar, nonblank Hermes `row_id`; synthesize a deterministic per-resume identity when projections such as tool rows omit one. Namespace Compose keys separately from protocol IDs and occurrence-qualify duplicates so malformed or reused server IDs cannot collide with local, tool, fallback, or streaming rows.
- Recovered in-flight assistant text may include a prefix already present in persisted history. Render only the unpersisted suffix.
- Attach the event collector before connecting. `HermesGateway.events` has no replay and a bounded extra buffer, so late collectors can miss conversational events.

## Projection limitations

- Tool completion currently pairs with the most recent pending tool by tool name, not by a tool-call ID. Concurrent tools with the same name are ambiguous.
- Interim/final assistant merging and completion deduplication are text- and prefix-based projection rules, not protocol identity guarantees.
- The synthetic summary for a newly created conversation is not refreshed from `session.list` while that conversation remains active.

Regression coverage for these invariants belongs in `CelesteViewModelTest` and `HermesGatewayTest`; see [`testing.md`](testing.md).

## Growth rule

Split files when an ownership boundary becomes real. A capability-specific protocol adapter is a likely future seam if the current dashboard client boundary becomes obstructive. Preserve the layer direction: Compose → application state → dashboard/protocol → transport, with connection persistence injected into application state.
