# DF-07 — Cancellation and error projection

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-07-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/DF-07-cancellation-error-projection.md`

## Recorded investigation

The most important source facts are:

- `CelesteViewModel` owns application/session state, turn reduction, lifecycle recovery, and actions; `DashboardClient` owns short HTTP/auth work and gateway construction; `HermesGateway` is thin transport: `architecture.md:5-15`.
- Stored and runtime session IDs have different authority and purposes: `architecture.md:70-77`.
- Resume ordering, no-resend, stale-socket replacement, and runtime-session event filtering are existing invariants: `architecture.md:79-90`.
- Current UI state has one untyped nullable `errorMessage`, and the Compose routes pass it to conversation, session-list, and gateway surfaces: `CelesteViewModel.kt:64-82`, `CelesteRoutes.kt:79-119`.
- Current connection/session operations use broad `runCatching` and publish `error.message`, while only the connection-attempt counter guards some paths: `CelesteViewModel.kt:155-190`, `CelesteViewModel.kt:193-280`, `CelesteViewModel.kt:413-506`.
- Current open/create/send/interrupt/foreground/reconnect paths launch work and project raw exception messages; gateway close cancels observer/reconnect jobs but not every action job: `CelesteViewModel.kt:584-617`, `CelesteViewModel.kt:620-684`, `CelesteViewModel.kt:700-761`, `CelesteViewModel.kt:788-816`, `CelesteViewModel.kt:1076-1145`.
- Current event reduction is already silent for interruption but copies `message.complete`/`error` payloads into UI state: `CelesteViewModel.kt:922-950`, `hermes-protocol.md:71-82`.
- `HermesGateway` has socket-generation stale-callback protection but is intentionally not an application-state owner: transport evidence.
- The documented test owner is `CelesteViewModelTest` for lifecycle/session/reduction/no-resend and the lower transport tests for socket cancellation; real device behavior is not covered by host screenshots and there is no `androidTest` suite: `testing.md:18-32`, `testing.md:40-61`, `testing.md:76-86`.
- Public-repository security prohibits message/tool/attachment/private-path/private-address/profile/session data in logs, fixtures, screenshots, and docs: `security.md:44-59`.
- Hermes Desktop treats AbortSignal cancellation separately, uses `running=false` as a settle signal after Stop, preserves structured partial turn failures, and guards stale workspace/session completions: Desktop evidence.
- Conduit increments operation generations, cancels owned work, and makes stale/cancellation results non-errors; its chat-resume design also scopes restoration by profile/session and treats stale tasks as silent: Conduit evidence.

## Unresolved source and behavior questions

- Which exact Celeste operation produced the device’s message, and was it a Kotlin `CancellationException`, a wrapped cause, a gateway close error, or a server-originated string?
- What current Hermes payload/error-code distinction is authoritative for intended turn cancellation, peer disconnect, provider failure, and infrastructure failure?
