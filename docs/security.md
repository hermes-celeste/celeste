# Security

## Trust boundary

Celeste connects directly to a user-supplied Hermes dashboard. The dashboard is authoritative and may expose private conversations, tool activity, files, credentials, and agent controls. Treat all received content and connection material as sensitive.

Celeste currently requests only `INTERNET`. Add Android permissions only for an implemented feature and document the data path before requesting them.

## Transport

Require HTTPS for public hosts. Plain HTTP is limited by `DashboardUrlPolicy` to loopback, private/LAN, link-local, and Tailscale addresses. Keep this validation at the network boundary rather than relying on UI copy.

`android:usesCleartextTraffic="true"` permits Android to make connections that the application policy then restricts. Do not weaken or bypass `DashboardUrlPolicy` when adding alternate connection entry points.

## Credentials

Supported credential forms are:

- no credential for open loopback development;
- an ephemeral machine session token;
- a provider-authenticated cookie session that mints one-use WebSocket tickets.

Passwords, static tokens, cookies, and WebSocket tickets are currently process-memory only. Clear password and token fields from UI state after connection. Do not place credentials in:

- Compose saved state or `rememberSaveable`;
- `Bundle`, DataStore, SharedPreferences, databases, or files;
- logs, crash messages, analytics, clipboard helpers, fixtures, screenshots, or documentation;
- command history or committed environment files.

When connection persistence is implemented, store only the minimum recoverable secret using Android Keystore-backed encryption. Define expiry, logout/revocation, device-lock behavior, migration, and deletion before shipping persistence.

## One-use WebSocket tickets

A cookie-authenticated connection mints a fresh ticket at `/api/auth/ws-ticket` for each WebSocket attempt. Never reuse, persist, or log a ticket. Reconnect through the endpoint provider so it can mint a new ticket.

WebSocket tokens and tickets appear in URL query parameters by protocol design. Do not log full WebSocket URLs, OkHttp requests, request-bearing exceptions, proxy traces, or network-inspection exports.

## Private application data

The manifest disables backup, and `backup_rules.xml` plus `data_extraction_rules.xml` exclude application root data from cloud backup and device transfer. Those root exclusions do not automatically describe future database or shared-preference storage. When persistence is added, exclude every actual credential-bearing domain and path explicitly.

Password fields are visually masked, but the app does not currently set `FLAG_SECURE`. Masking does not provide storage encryption, screenshot blocking, clipboard protection, or recording protection. Do not use real credentials in review screenshots or recordings.

Do not log or fixture:

- message bodies or assistant output;
- tool names paired with private arguments/results;
- attachment content or file paths;
- dashboard addresses when they identify a private network;
- profile/session identifiers from a real server;
- live-test credentials or raw authenticated payloads.

Use synthetic values in tests and `[REDACTED]` in documentation.

## Authentication changes

For a new authentication mode:

1. verify current Hermes server behavior;
2. identify every credential-bearing hop;
3. keep transport/authentication independent of Compose;
4. define memory, persistence, redaction, expiry, logout, and backup behavior;
5. add admission and failure-path tests;
6. update [`hermes-protocol.md`](hermes-protocol.md) for protocol facts and this document for security boundaries.
