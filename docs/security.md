# Security

## Trust boundary

Celeste connects directly to a user-supplied Hermes dashboard. Dashboard content and connection material are sensitive. The app requests network access; additional Android permissions require a shipped feature and an explicit data path.

## Transport

Require HTTPS for public hosts. `DashboardUrlPolicy` limits plain HTTP to loopback, private, LAN, link-local, and Tailscale destinations even though the Android manifest permits cleartext traffic.

## Credentials

Supported credentials are open-loopback access, a machine session token, and a provider-authenticated cookie session.

Passwords and one-use WebSocket tickets remain in process memory. Reusable static tokens and the Hermes cookies required for session restoration may be persisted only through `ConnectionStore`.

Never place credentials in Compose saved state, ordinary preferences or databases, plaintext files, logs, exceptions, analytics, clipboard helpers, fixtures, screenshots, documentation, shell history, or committed environment files.

`AndroidConnectionStore` keeps safe endpoint/account metadata in private preferences and AES-GCM encrypts reusable authentication with a non-exportable, unlocked-device Android Keystore key. Ciphertext lives in `noBackupFilesDir`; authenticated data binds it to application ID, format version, endpoint, and authentication mode. There is no plaintext fallback.

Restored cookies must be unexpired, belong to the saved host, and match its path. PKCE and unrelated cookies are excluded. Definitive authentication rejection removes reusable authentication; connectivity, timeout, rate-limit, malformed-response, and server failures preserve it for explicit Retry.

Cookie rotation is persisted after successful restoration and on app background through serialized store access. Connection generations prevent late writes from recreating cleared authentication.

Sign out performs best-effort server logout, clears in-memory authentication, and removes the encrypted secret while retaining safe prefill metadata. Forget connection also removes the descriptor.

## One-use WebSocket tickets

Mint a fresh ticket for every cookie-authenticated WebSocket attempt. Never reuse, persist, or log tickets or full WebSocket URLs.

## Private application data

The manifest disables backup. Backup and extraction rules exclude application-data domains and the connection descriptor; encrypted material resides under `noBackupFilesDir`.

Celeste does not persist conversation content or transmit it outside the configured dashboard. Drafts, queued prompts, and selected attachment bytes remain in process memory; attachment bytes are Base64-encoded only while building the Hermes upload request. Attachment access uses Android’s system photo and document pickers with bounded content-URI reads; the app requests no broad media or storage permission. Explicit transcript selection and code-block Copy may place only user-selected content on the device clipboard. Automatic copying and credential copying are forbidden.

Conversation images use explicit HTTPS Markdown sources or authenticated dashboard media reads. Web redirects remain on HTTPS and image responses stay out of disk cache. Hermes `MEDIA:` paths are recognized only in assistant output outside code blocks, with up to four rendered per message; they are fetched through the configured dashboard with the active credential, decoded only for image media types, and bounded to 16 MiB.

Use synthetic data in tests. Never log or fixture real message bodies, assistant output, private tool context/results, attachments, file paths, dashboard addresses, profile/session identifiers, authenticated payloads, or live-test credentials.

Visual password masking is not screenshot or recording protection. Review media must use synthetic credentials.

## Test-build signing

GitHub Actions signs the downloadable debug APK with a dedicated test-only identity so successive test builds can update-install. The keystore and password remain in Actions secrets, exist only temporarily on the runner, and never sign a production or store build.

## Authentication changes

A new authentication mode must define every credential-bearing hop, memory and persistence behavior, redaction, expiry, logout, backup policy, failure semantics, and boundary tests before implementation.
