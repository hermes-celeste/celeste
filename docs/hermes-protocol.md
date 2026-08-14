# Hermes protocol

## Authority

The installed current Hermes server and Desktop implementation is the authority for routes, response shapes, events, and lifecycle behavior. Before implementing or changing a protocol feature, inspect the matching official source. Relevant locations include:

- `hermes_cli/dashboard_auth/routes.py` — providers, password login, cookies, and WebSocket tickets;
- `hermes_cli/web_server.py` — dashboard status and `/api/ws` admission;
- `tui_gateway/methods_session.py` — session listing, creation, resume, interruption, and mutation;
- `tui_gateway/ws.py` — JSON-RPC framing, events, and WebSocket lifecycle.

External documentation cannot override current source. Record cross-version assumptions and non-obvious compatibility decisions here.

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

Static-token profile requests use `X-Hermes-Session-Token`. Cookie-authenticated requests use the client’s private cookie jar. Celeste may Keystore-encrypt unexpired Hermes access, refresh, and provider cookies for the exact normalized endpoint; it never persists PKCE cookies, one-use WebSocket tickets, or passwords.

Current Hermes exposes `GET /api/profiles`. A `404` from that route alone is treated as compatibility with an older single-profile dashboard and yields the `default` profile. Authentication rejection, rate limiting, other HTTP or transport failures, and malformed profile responses remain failures.

The shared HTTP client does not follow redirects. Reverse proxies must expose the expected routes directly under the normalized base path.

## WebSocket admission

The gateway endpoint is `/api/ws` using `ws` or `wss` to match the base URL.

- Provider-authenticated sessions mint a fresh one-use ticket for each connection and use `?ticket=...`.
- Desktop-launched or test dashboards may provide an ephemeral machine session token through `?token=...`.
- Tokenless access is a loopback development behavior, not a public deployment mode.

A successful HTTP WebSocket upgrade is not protocol readiness. Wait for the `gateway.ready` event before setting `Connected`.

The disposable session-list and live-contract resume paths send their request from `onOpen` and do not wait for `gateway.ready`. The lifecycle-owned production gateway does wait. Do not generalize the disposable-path behavior to persistent conversation RPCs.

## JSON-RPC

Requests use JSON-RPC 2.0 with a unique string ID, method, and params. `HermesGateway` correlates responses by ID and fails all pending requests when a socket is replaced or disconnected.

Celeste currently uses:

| Method | Identity | Purpose |
| --- | --- | --- |
| `session.list` | none | Read stored conversations; also used as a foreground health check |
| `session.create` | profile | Start a new profile-scoped runtime |
| `session.resume` | stored session ID | Attach to durable history and recover runtime state |
| `prompt.submit` | runtime session ID | Persist and begin a user turn |
| `session.interrupt` | runtime session ID | Stop active work before reconciling history |

Creation and resume include `source: "android"` and a terminal column count. Treat these as protocol inputs, not UI labels.

## Event projection

The current reducer recognizes:

- `message.start`, `message.delta`, `message.interim`, `message.complete`, `message.error`;
- `message.interrupted`, `session.interrupted`, `session.busy`, `session.info`;
- `tool.start`, `tool.complete`, plus legacy aliases `tool_call` and `tool_result`;
- top-level `error`.

Do not add an event name from guesswork. Verify its payload and ordering against current Hermes source, add decoding/state tests, and document only cross-event semantics that code alone cannot make clear.

Notifications with a blank `session_id` are accepted for the active conversation. Only a non-empty mismatched runtime ID is filtered out. `gateway.ready` is consumed as transport readiness and is not emitted as a conversation event.

## Compatibility workflow

For a protocol change:

1. Inspect the installed official Hermes implementation.
2. Compare the existing Celeste request, decoder, and state reducer.
3. Evaluate lifecycle, failure, and mobile edge cases against Celeste’s architecture.
4. Add a focused MockWebServer or state-reducer regression.
5. Run the relevant unit suite.
6. Use the opt-in real-dashboard contract test when route admission, authentication, or server response shape changed.
7. Update this document only when a route, invariant, compatibility rule, or source location changed.

Never paste live payloads containing credentials, messages, tool output, attachments, or personal identifiers into docs or fixtures.
