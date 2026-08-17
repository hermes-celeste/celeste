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
- a machine session token;
- a provider-authenticated cookie session that mints one-use WebSocket tickets.

Passwords and WebSocket tickets are process-memory only. Passwords are never persisted; successful provider login persists only the Hermes access, refresh, and provider cookies needed to restore the authenticated session. Static tokens and those session cookies may be remembered as encrypted reusable authentication material. Clear password and token fields from UI state after every connection attempt. Do not place credentials in:

- Compose saved state or `rememberSaveable`;
- `Bundle`, ordinary DataStore/SharedPreferences, databases, or plaintext files;
- logs, crash messages, analytics, clipboard helpers, fixtures, screenshots, or documentation;
- command history or committed environment files.

`AndroidConnectionStore` keeps the normalized endpoint, authentication mode, provider, and optional username in a private descriptor preference. Reusable authentication is AES-GCM encrypted with a non-exportable Android Keystore key that requires the device to be unlocked. Ciphertext lives in `noBackupFilesDir`; additional authenticated data binds it to the application ID, format version, exact normalized endpoint including path prefix, and authentication mode. There is no plaintext or weak-storage fallback.

`ConnectionStore` defines the platform-neutral persistence contract; Android Keystore is the only production implementation today. A future iOS target must provide equivalent Keychain-backed protection and preserve Sign out, Forget connection, endpoint binding, rotation, redaction, and failure semantics. Shared code must never introduce a plaintext fallback to avoid writing a platform adapter.

Restored provider cookies must be unexpired Hermes session cookies for the exact saved host and a path matching the normalized endpoint. PKCE and unrelated cookies are never exported. Definitive 401/403 rejection deletes reusable authentication and pauses later automatic attempts; offline, timeout, 429, malformed response, and server failures retain the encrypted material for explicit Retry.

Provider access and refresh cookies can rotate. After a successful cold restore and when the app moves to the background, Celeste re-encrypts the latest cookie-jar state through serialized store access. Sign out and Forget connection invalidate the active connection generation before cleanup so a late refresh write cannot recreate deleted authentication material.

`Sign out` makes a best-effort `/auth/logout` request, clears the in-memory cookie jar, deletes encrypted authentication material and its Keystore key, and retains only safe prefill metadata. `Forget connection` additionally deletes the descriptor. Neither action depends on the server being reachable before local cleanup can complete.

## One-use WebSocket tickets

A cookie-authenticated connection mints a fresh ticket at `/api/auth/ws-ticket` for each WebSocket attempt. Never reuse, persist, or log a ticket. Reconnect through the endpoint provider so it can mint a new ticket.

WebSocket tokens and tickets appear in URL query parameters by protocol design. Do not log full WebSocket URLs, OkHttp requests, request-bearing exceptions, proxy traces, or network-inspection exports.

## Private application data

The manifest disables backup. `backup_rules.xml` and `data_extraction_rules.xml` additionally exclude every application-data domain plus the named `celeste_connection.xml` descriptor from cloud backup and device transfer. Encrypted authentication material is under `noBackupFilesDir`. Keep the named exclusion and broad defense-in-depth exclusions aligned with any storage change.

Password fields are visually masked, but the app does not currently set `FLAG_SECURE`. Masking does not provide storage encryption, screenshot blocking, clipboard protection, or recording protection. Do not use real credentials in review screenshots or recordings.

Do not log or fixture:

- message bodies or assistant output;
- tool names paired with private arguments/results;
- attachment content or file paths;
- dashboard addresses when they identify a private network;
- profile/session identifiers from a real server;
- live-test credentials or raw authenticated payloads.

Use synthetic values in tests and `[REDACTED]` in documentation.

## Test-build signing

GitHub Actions signs the downloadable debug APK with a dedicated test-only key stored in repository Actions secrets. The key exists only to make successive test APKs update-compatible. It must never sign a release or store build.

Do not commit, print, upload as an artifact, or reuse the test keystore or its password. The workflow decodes it into the runner's temporary directory only for packaging and removes it in an `always()` cleanup step. A release signing identity requires a separate design and explicit project-owner approval.

## Authentication changes

For a new authentication mode:

1. verify current Hermes server behavior;
2. identify every credential-bearing hop;
3. keep transport/authentication independent of Compose;
4. define memory, persistence, redaction, expiry, logout, and backup behavior;
5. add admission and failure-path tests;
6. update [`hermes-protocol.md`](hermes-protocol.md) for protocol facts and this document for security boundaries.
