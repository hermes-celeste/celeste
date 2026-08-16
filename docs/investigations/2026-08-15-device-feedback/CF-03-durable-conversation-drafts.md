# CF-03 — Durable conversation drafts

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-03-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/CF-03-durable-conversation-drafts.md`

## Recorded investigation

- The current Celeste draft is volatile `CelesteUiState.draft` (`CelesteViewModel.kt:64-82`, updated at `:146-148`); opening a session clears it (`:584-617`). No draft persistence was found in the inspected Celeste source.
- `CelesteViewModel.kt:700-741` clears the text draft before asynchronous submission and reconciles failures with an explicit no-auto-resend invariant.
- `ConversationScreen.kt:186-223` is a text-only composer.
- `GatewaySessionApi.kt:84-95` currently submits only `session_id` and `text`; no server draft API or attachment/idempotency field exists. Hermes remains authoritative for messages and sessions.
- The original investigation recorded composite profile/session draft keys, runtime/stored-session alias handling, generation guards, non-clobbering restore, and memory-only attachment transfer state in Desktop and Conduit, but did not retain durable primary-source paths for that comparison.

## Unresolved source and behavior questions

- Can Hermes expose a stable client message/operation identity for post-process-death prompt reconciliation?
- Can an uploaded-but-unsubmitted image be queried/reused safely after process death?
