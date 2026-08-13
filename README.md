# Celeste

**Your Hermes, carried forward.**

Celeste is a native Android client for a self-hosted [Hermes Agent](https://github.com/NousResearch/hermes-agent) dashboard. It connects directly to the same Hermes server as Desktop, so your profiles, conversations, and agent work stay together.

The installed Android app is named **Hermes Celeste** so it is easy to find in the launcher and app list.

## What it does

- Connects directly to a Hermes dashboard over HTTPS, a private network, or Tailscale
- Uses the dashboard’s existing authentication and profiles
- Lists, creates, and resumes shared Hermes conversations
- Sends prompts and streams responses and tool activity live
- Stops active turns and reconciles safely after reconnecting
- Keeps Hermes as the source of truth without a separate Celeste account, relay, or copied history

## Status

Celeste is in early development. The native chat flow is functional; OAuth sign-in, persistent credentials, rich Markdown, attachments, approvals, and broader Hermes management features are still ahead.

## Development

```bash
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest lintDebug
```

Build a debug APK with `scripts/celeste-env ./gradlew --no-daemon assembleDebug`.
