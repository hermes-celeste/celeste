# CF-04 — Background completion and notifications

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-04-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/CF-04-background-completion-and-notifications.md`

## Recorded investigation

| Evidence |
| --- |
| `MainActivity` currently forwards `ON_START` to `CelesteViewModel.onForeground()` and `ON_STOP` to `onBackground()` (`app/src/main/java/dev/hazydreams/hermesceleste/MainActivity.kt:52-70`). |
| `CelesteViewModel` reduces `message.complete`, `error`/`message.error`, interruptions, `session.busy`, and `session.info`, and reconnects/resumes by durable stored ID (`app/src/main/java/dev/hazydreams/hermesceleste/CelesteViewModel.kt:821-883`, `:885-964`, `:1076-1117`). |
| `HermesGateway` owns a thin WebSocket/RPC transport and emits typed `GatewayEvent`s; it fails pending requests on disconnect (`app/src/main/java/dev/hazydreams/hermesceleste/network/HermesGateway.kt:38-65`, `:227-274`). |
| The current Android manifest requests only `INTERNET` (`app/src/main/AndroidManifest.xml:1-4`), and repository testing rules require device-only lifecycle/permission checks and forbid local distributable APKs (`docs/testing.md:76-86`). |
| Android background-task guidance and notification permission/deep-link guidance were audited during research: [background tasks](https://developer.android.com/develop/background-work/background-tasks), [notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission), [App Links/deep links](https://developer.android.com/training/app-links), [FCM receive behavior](https://firebase.google.com/docs/cloud-messaging/android/receive-messages), and [background FGS restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). |

## Unresolved source and behavior questions

- Does the current Hermes server/gateway expose a supported mobile/device registration and background-event delivery capability?
- What exact durable event/request/message identity is available for completion, failure, and approval/clarification, including after `session.resume`?
- Which official payload fields identify origin, profile, durable session, event kind, and request identity, and how are replay, expiry, revocation, and token rotation handled?
- Does current Hermes require a provider relay for background delivery?
