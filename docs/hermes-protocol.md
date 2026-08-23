# Hermes protocol

## Authority

The installed official Hermes server and Desktop implementation define routes, payloads, events, and lifecycle behavior. Inspect current source before changing protocol code. Celeste supports that current surface rather than maintaining legacy fallbacks.

Primary references include dashboard auth routes, dashboard web routers, `tui_gateway/methods_session.py`, and `tui_gateway/ws.py`.

## Address and transport

`DashboardUrlPolicy` normalizes the user address and preserves any path prefix when joining routes.

- Accept HTTP and HTTPS dashboard addresses.
- Add HTTP to scheme-less private, LAN, Tailscale, local, and single-label hosts.
- Limit cleartext HTTP to loopback, private, link-local, LAN, and Tailscale destinations.
- Require HTTPS for public hosts.
- Reject user info, query strings, and fragments.

The gateway endpoint is `/api/ws`, using `ws` or `wss` to match the dashboard. Provider-authenticated sessions mint a fresh one-use ticket for each connection; static machine-token and open-loopback admission use their current server contracts.

A successful WebSocket upgrade is not readiness. Wait for `gateway.ready` before sending persistent-session RPCs or reporting Connected.

## HTTP surface

| Route | Purpose |
| --- | --- |
| `GET /api/status` | Probe reachability, version, and authentication requirement |
| `GET /api/auth/providers` | Discover password and provider support |
| `POST /auth/password-login` | Establish a cookie-authenticated session |
| `POST /auth/logout` | Best-effort provider logout |
| `POST /api/auth/ws-ticket` | Mint a one-use WebSocket ticket |
| `GET /api/profiles` | Read profiles |
| `GET /api/sessions` | Page stored-conversation metadata |
| `GET /api/sessions/search` | Search durable history |
| `GET /api/sessions/{id}/messages` | Read persisted transcript history |
| `PATCH /api/sessions/{id}` | Update title, pin, and read state |

Static-token requests use `X-Hermes-Session-Token`; cookie sessions use the private client cookie jar. The shared HTTP client does not follow redirects.

Session catalog pages use recent server ordering, 15-row windows, and response paging metadata. Preserve pinned backfill, deduplicate by stored session ID, and keep loaded rows when a later page fails. Search uses the server-default profile and a bounded server result set; stale responses cannot replace a newer query.

Pin and read changes may update the projection optimistically, then accept Hermes as authoritative. Rename keeps the existing title until Hermes accepts a trimmed nonblank replacement.

Persisted history loads with `order=latest` and `include_compacted=true`. The transcript decoder combines assistant prose, reasoning, tool calls/results, and structured process markers into the same projection used by live events.

## JSON-RPC surface

| Method | Identity | Purpose |
| --- | --- | --- |
| `session.list` | none | Foreground gateway health check |
| `session.create` | profile | Create the runtime for a local draft’s first Send |
| `session.resume` | stored session ID | Bind durable history to a runtime |
| `prompt.submit` | runtime session ID | Persist and begin a user turn |
| `session.interrupt` | runtime session ID | Stop work before reconciliation |

Creation and resume include `source: "android"` and terminal columns. Hermes creates the durable session row lazily on first prompt submission, so an untouched local draft stays out of the catalog.

## Event projection

Celeste recognizes message lifecycle, interim assistant prose, reasoning, tools, interruption, busy/session status, compaction, background-process completion, and top-level errors.

- Reasoning and tools form chronological Steps segments.
- Interim assistant messages remain ordinary transcript prose and split adjacent Steps segments.
- `thinking.delta` is transient activity status, not a persisted reasoning step.
- Tool start and completion correlate by stable tool-call identity.
- File-edit calls aggregate by assistant turn and path into one Changes projection backed by structured diffs.
- Todo lifecycle events replace the active session’s task projection from their ordered stable-ID snapshot; an empty snapshot or an ended turn clears active work.
- Compaction status begins and ends from structured lifecycle events.
- Background-process completion produces one compact result row with details available on demand.
- Notifications with a blank session ID apply to the active conversation; nonblank mismatched runtime IDs are ignored.

`session.resume` binds runtime state while the dashboard history route supplies persisted display history. Resume retries are bounded, preserve readable history, and end in an explicit Retry surface.

## Update workflow

1. Inspect the installed Hermes server and Desktop source.
2. Compare Celeste’s request, decoder, reducer, and lifecycle behavior.
3. Add a focused protocol or state regression using synthetic data.
4. Run the affected unit and lint boundaries.
5. Use the opt-in live contract when route admission or response shape changes.
6. Update this document only for durable protocol contracts.

Never commit real credentials, messages, tool output, attachments, paths, or identifiers as fixtures or documentation.
