# DF-03 — Image attachments

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-03-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/DF-03-image-attachments.md`

## Recorded investigation

- The current conversation surface is text-only: `ConversationScreen.kt:66-80` exposes text-only callbacks, `:156-175` renders text/tool rows, and `:177-275` contains the single text composer. There is no picker, attachment list, preview, retry, progress, or remove action.
- `CelesteViewModel.kt:48-82,96-120,146-148` owns a `String` draft and turn state. `:584-617` clears the draft while opening a session; `:700-741` appends an optimistic text row, clears the draft, submits text, and reconciles uncertain delivery without resending. There is no attachment state or operation owner.
- `GatewaySessionApi.kt:84-95` currently sends only `{session_id,text}`. `HermesGateway.kt:30-83,94-183,185-215,227-273` has request correlation and connection/auth failure handling but no attachment upload, progress, or cancellation operation.
- The current Hermes server contract has `image.attach_bytes` at `methods_prompt.py:801-859`: `content_base64`/`data`, optional filename/extension, strict image validation, a 25 MiB limit, synchronous staging, and errors for invalid/oversize input. It has no progress, cancellation, checksum, or idempotency field. `image.detach` at `:1035-1052` removes session metadata but does not delete the staged file.
- Android Photo Picker is the least-privilege path: `PickMultipleVisualMedia(ImageOnly)` uses the platform/fallback picker and does not require broad media permission.
