# Architecture

## Shape

Celeste is a single-module Android application with explicit ownership boundaries:

- `MainActivity.kt` and `CelesteViewModel.kt` adapt Android lifetime and dependencies.
- `CelesteController.kt` coordinates connection, session, and conversation state.
- `ConversationEventReducer.kt` projects Hermes events into transcript and turn state.
- `SessionCatalogCoordinator.kt` owns session paging, search, metadata, and row actions.
- `ui/` owns Compose routing and presentation.
- `network/DashboardClient.kt` owns dashboard HTTP and authenticated gateway creation.
- `network/GatewaySessionApi.kt` owns typed session RPCs and transcript decoding.
- `network/HermesGateway.kt` owns persistent JSON-RPC transport.
- `connection/` owns saved-connection models and the Android Keystore adapter.

The module is a packaging boundary, not permission to mix these responsibilities.

## Layer boundaries

### Compose

Compose renders `CelesteUiState` and emits intent. It owns navigation and ephemeral interaction state, not credentials, sockets, protocol framing, retry policy, or authoritative history.

### Application state

`CelesteController` owns the selected dashboard and profile, in-memory credential, active session identities, persistent gateway, transcript projection, task progress, draft, per-session prompt queues, turn state, and lifecycle recovery. It delegates deterministic event projection and catalog behavior to their focused owners. Changed-file projections remain durable turn content; task progress remains active-session state.

The controller runs on the serial dispatcher supplied by its host. Its child work inherits that context, and closing the host lifetime closes the gateway and clears in-memory authentication.

### Dashboard and transport

`DashboardClient` performs bounded HTTP operations and creates `GatewayConnection` instances. Its private cookie jar keeps authenticated HTTP and one-use WebSocket ticket minting in one boundary.

`HermesGateway` correlates JSON-RPC requests, emits events, and reports connection state. Session ownership, reconciliation, and retry policy remain above the transport.

### Saved connection

`ConnectionStore` separates safe endpoint/account metadata from encrypted reusable authentication. Gateway settings apply connection changes explicitly; Sign out removes reusable authentication, while Forget connection also removes the saved descriptor.

The dashboard remains authoritative for profiles, sessions, messages, and capabilities. Celeste keeps a screen projection and unsent draft, not a second history store.

## Runtime flow

1. Load and validate a saved connection descriptor.
2. Restore encrypted authentication when automatic login is enabled.
3. Probe the normalized dashboard and establish an in-memory credential.
4. Load profiles and the session catalog without selecting a conversation.
5. Create a runtime for a local draft on first Send, or resume a selected stored session.
6. Reduce persisted history and live gateway events into one transcript projection.
7. Reconcile authoritative state after interruption, reconnect, or foreground recovery.

## Shared and platform ownership

Protocol models, reducers, application state, and custom Compose UI stay free of Android, AndroidX, and JVM APIs. Platform code owns application entry points, lifecycle bridges, secure storage, system navigation, keyboard and insets, notifications, and other operating-system integrations.

A future platform host supplies equivalent lifetime and platform adapters rather than reimplementing product behavior.

## Session identity

Hermes has two relevant identities:

- the **stored session ID** locates durable history and reconciliation;
- the **runtime session ID** addresses prompt and interrupt RPCs on the attached gateway session.

Translate between them at protocol boundaries and never substitute one for the other.

## Reconciliation invariants

- Wait for `gateway.ready` before treating a persistent gateway as connected.
- Attach the event collector before connecting because gateway events have no replay.
- Buffer live events during resume, apply the snapshot first, then replay buffered events.
- Never automatically resend a prompt after uncertain delivery.
- Recreate only an untouched blank runtime that disconnected before its first prompt.
- Replace and reconcile a stale foreground socket.
- Ignore events carrying a different nonblank runtime session ID.
- Prefer Hermes row identity and synthesize deterministic, collision-free UI identity when needed.
- Render only the unpersisted suffix of recovered in-flight assistant text.

## Growth rule

Split code when ownership is already distinct or an existing boundary obstructs change. Preserve the dependency direction: Compose → application state → dashboard/protocol → transport, with lifetime, client identity, and secure persistence injected by the platform host.
