# Celeste

**Your Hermes, carried forward.**

Celeste is an Android-first native client for a self-hosted [Hermes Agent](https://github.com/NousResearch/hermes-agent) dashboard. It connects directly to the same Hermes server as Desktop, so your profiles, conversations, and agent work stay together.

Android is the only application target today. Celeste is being structured as one Compose Multiplatform product so a future iOS target can share protocol behavior, application state, and custom UI instead of becoming a separately maintained client.

The installed Android app is named **Hermes Celeste** so it is easy to find in the launcher and app list.

## What it does

- Connects directly to a Hermes dashboard over HTTPS, a private network, or Tailscale
- Uses the dashboard’s existing authentication and profiles
- Lists, creates, and resumes shared Hermes conversations
- Sends prompts and streams responses and tool activity live
- Stops active turns and reconciles safely after reconnecting
- Keeps Hermes as the source of truth without a separate Celeste account, relay, or copied history

## Status

Celeste is in early development. The native Android chat flow and secure connection restoration are functional; OAuth sign-in, rich Markdown, attachments, approvals, and broader Hermes management features are still ahead. There is no iOS application or release commitment yet.

## Development

```bash
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest lintDebug
```

GitHub Actions verifies APK assembly on pull requests and publishes the current test APK from successful `main` builds. Local development uses unit tests, lint, and screenshot validation rather than distributable APK assembly.
