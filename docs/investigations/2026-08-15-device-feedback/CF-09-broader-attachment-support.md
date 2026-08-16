# CF-09 — Broader attachment support

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-09-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/CF-09-broader-attachment-support.md`

## Recorded investigation

Current Celeste has a text-only composer/message model and no attachment RPC (`app/src/main/java/dev/hazydreams/hermesceleste/ui/conversation/ConversationScreen.kt:66-80,156-275`; `app/src/main/java/dev/hazydreams/hermesceleste/network/GatewaySessionApi.kt:84-95`; `app/src/main/java/dev/hazydreams/hermesceleste/network/DashboardClient.kt:77-94`). The official Hermes server/Desktop contract is authoritative.

| Contract | Current behavior and validation |
| --- | --- |
| `image.attach_bytes` (DF-03 foundation) | Synchronous base64/data-URL upload; strict decoding; empty/invalid data rejected; 25 MiB cap; image magic/signature and extension allowlist; writes under the session profile home and queues an image; no progress, cancel, checksum, idempotency, or cleanup (`Hermes Agent snapshot: tui_gateway/methods_prompt.py:801-859`; `server.py:11119-11225`). |
| `pdf.attach` | Accepts host `path` or base64 `content_base64`/`data`; requires `pdftoppm`; base64 PDF must be non-empty, <=50 MiB, and start with `%PDF-`; local path must exist, end in `.pdf`, and be <=50 MiB; page range is integer, starts at 1, and is capped at 25 pages; renders PNG at 150 DPI with a 120-second subprocess timeout; queues rendered pages as attached images and returns `filename`, `pages`, `pages_attached`, `count`, and a display `text` (`Hermes Agent snapshot: tui_gateway/methods_prompt.py:862-985`; `server.py:11119-11123`). |
| `file.attach` | Accepts `path` or generic `data_url`, plus `name`; decodes a data URL with any MIME, sanitizes the basename, stages under the owning profile’s `attachments/` directory, and returns `path`, `ref_path`, `ref_text` such as `@file:<workspace-ref>`, and `uploaded` (`methods_prompt.py:988-1032`; `server.py:11263-11382`). Current server code does not enforce a document MIME allowlist, a byte cap, a detach RPC, or idempotency. |
| Capability/readiness | Current `gateway.ready` exposes `skin` and `change_events`, not attachment capabilities (`Hermes Agent snapshot: tui_gateway/ws.py:306-332`; `entry.py:435-440`). Desktop’s `DESKTOP_BACKEND_CONTRACT` is a GUI/backend skew contract, not a mobile attachment capability (`Hermes Agent snapshot: tui_gateway/server.py:5301-5311`; `Hermes Agent snapshot: apps/desktop/src/store/updates.ts:90-99`). |

## Unresolved source and behavior questions

- Does current Hermes expose a versioned attachment capability, server-side allowlist and limits, stable idempotency, and detach or TTL behavior?
- Does every supported Hermes deployment have `pdftoppm`?
