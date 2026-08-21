# Hermes protocol

## Authority

The installed current Hermes server and Desktop implementation is the authority for routes, response shapes, events, and lifecycle behavior. Before implementing or changing a protocol feature, inspect the matching official source. Relevant locations include:

- `hermes_cli/dashboard_auth/routes.py` — providers, password login, cookies, and WebSocket tickets;
- `hermes_cli/web_server.py` — dashboard status and `/api/ws` admission;
- `tui_gateway/methods_session.py` — session listing, creation, resume, interruption, and mutation;
- `tui_gateway/ws.py` — JSON-RPC framing, events, and WebSocket lifecycle.

External documentation cannot override current source. Celeste supports only this current protocol surface; missing required routes, payloads, methods, or events are incompatible and must fail clearly rather than activating an older fallback.

## Base URL and route joining

`DashboardUrlPolicy` normalizes the user-entered base address. Preserve any dashboard path prefix when joining HTTP and WebSocket routes.

- Accept `http` and `https` only.
- Add `http://` to a scheme-less private or Tailscale address.
- Allow cleartext HTTP only for loopback, private IPv4/IPv6, link-local, Tailscale CGNAT or `*.ts.net`, `.local`, and single-label LAN hosts.
- Require HTTPS for public hosts.
- Reject user info, query strings, and fragments in a base address.

Transport security and sensitive data rules live in [`security.md`](security.md).

## HTTP surface

| Route | Purpose |
| --- | --- |
| `GET /api/status` | Probe reachability, version, and whether authentication is required |
| `GET /api/auth/providers` | Discover configured authentication providers and password support |
| `POST /auth/password-login` | Establish a provider-authenticated cookie session |
| `POST /auth/logout` | Best-effort provider session revocation and cookie clearing; returns a `302` login redirect |
| `POST /api/auth/ws-ticket` | Mint a short-lived, one-use WebSocket ticket from the cookie session |
| `GET /api/profiles` | Read the server’s profile catalog |
| `GET /api/sessions` | Read recent, pinned, scheduled, and unread stored-conversation metadata |
| `PATCH /api/sessions/{session_id}` | Update authoritative conversation metadata such as the read watermark |

Static-token profile requests use `X-Hermes-Session-Token`. Cookie-authenticated requests use the client’s private cookie jar. Celeste may Keystore-encrypt unexpired Hermes access, refresh, and provider cookies for the exact normalized endpoint; it never persists PKCE cookies, one-use WebSocket tickets, or passwords.

Current Hermes requires `GET /api/profiles`. A missing route, authentication rejection, rate limiting, other HTTP or transport failure, or malformed response remains a failure.

Session discovery requires `GET /api/sessions` with archived sessions excluded, 15-row `limit` and `offset` pages, and server-side recent ordering by `last_active` with `started_at` as the data fallback. The response's `total`, `limit`, and `offset` metadata controls exhaustion; advance by the requested page window rather than by response length because Hermes may append pinned sessions beyond that window. Deduplicate this pinned backfill by stable stored session ID while preserving the progressively loaded catalog. Compact rows are authoritative for profile, source, model, pinned, and unread state; `source: "cron"` identifies a scheduled run. Opening an unread row sends `PATCH /api/sessions/{session_id}` with `unread: false` and its profile context. A failed read acknowledgement does not block opening the conversation; later authoritative metadata may restore unread state. A definitive authentication rejection during any catalog page invalidates reusable authentication and returns to sign-in. Missing routes, authentication rejection, rate limiting, other HTTP or transport failures, and malformed responses remain failures instead of silently changing transports.

The shared HTTP client does not follow redirects. Reverse proxies must expose the expected routes directly under the normalized base path.

## WebSocket admission

The gateway endpoint is `/api/ws` using `ws` or `wss` to match the base URL.

- Provider-authenticated sessions mint a fresh one-use ticket for each connection and use `?ticket=...`.
- Desktop-launched or test dashboards may provide an ephemeral machine session token through `?token=...`.
- Tokenless access is a loopback development behavior, not a public deployment mode.

A successful HTTP WebSocket upgrade is not protocol readiness. Wait for the `gateway.ready` event before setting `Connected`.

The disposable live-contract resume path sends its request from `onOpen` and does not wait for `gateway.ready`. The lifecycle-owned production gateway does wait. Do not generalize the disposable-path behavior to persistent conversation RPCs.

## JSON-RPC

Requests use JSON-RPC 2.0 with a unique string ID, method, and params. `HermesGateway` correlates responses by ID and fails all pending requests when a socket is replaced or disconnected.

Celeste currently uses:

| Method | Identity | Purpose |
| --- | --- | --- |
| `session.list` | none | Foreground health check on the lifecycle-owned gateway |
| `session.create` | profile | Start a non-persisted profile-scoped draft runtime |
| `session.resume` | stored session ID | Attach to durable history and recover runtime state |
| `prompt.submit` | runtime session ID | Persist and begin a user turn |
| `session.interrupt` | runtime session ID | Stop active work before reconciling history |

Creation and resume include `source: "android"` and a terminal column count. Treat these as protocol inputs, not UI labels.

Hermes does not insert a durable session row during `session.create`. The row is created lazily by the first `prompt.submit`. Celeste may therefore prepare the empty composer on launch, but it must keep that draft out of the conversation catalog until prompt submission succeeds or authoritative reconciliation confirms the submission.

## Event projection

The current reducer recognizes:

- `message.start`, `message.delta`, `message.interim`, `message.complete`, `message.error`;
- `message.interrupted`, `session.interrupted`, `session.busy`, `session.info`;
- `tool.start` and `tool.complete`;
- top-level `error`.

Do not add an event name from guesswork. Verify its payload and ordering against current Hermes source, add decoding/state tests, and document only cross-event semantics that code alone cannot make clear.

Notifications with a blank `session_id` are accepted for the active conversation. Only a non-empty mismatched runtime ID is filtered out. `gateway.ready` is consumed as transport readiness and is not emitted as a conversation event.

## Protocol update workflow

For a protocol change:

1. Inspect the installed official Hermes implementation.
2. Compare the existing Celeste request, decoder, and state reducer.
3. Evaluate lifecycle, failure, and mobile edge cases against Celeste’s architecture.
4. Add a focused MockWebServer or state-reducer regression.
5. Run the relevant unit suite.
6. Use the opt-in real-dashboard contract test when route admission, authentication, or server response shape changed.
7. Update this document only when a route, invariant, protocol rule, or source location changed.

Never paste live payloads containing credentials, messages, tool output, attachments, or personal identifiers into docs or fixtures.
