# Product

## Identity

**Celeste** is a native Android client for a self-hosted [Hermes Agent](https://github.com/NousResearch/hermes-agent) dashboard. Desktop and Celeste are clients of the same Hermes server and shared state. Celeste does not copy conversations into a separate mobile service.

Use these names consistently:

| Surface | Name |
| --- | --- |
| Product, repository, README, and documentation | Celeste |
| Android launcher, app list, and store listing | Hermes Celeste |
| GitHub organization handle | `hermes-celeste` |
| Android application ID and Kotlin namespace | `dev.hazydreams.hermesceleste` |

The Android name includes Hermes so the installed app is easy to find. It does not change the product name.

## Product principles

- **One Hermes, another surface.** Profiles, sessions, messages, and agent work remain on the user’s Hermes server.
- **Native Android.** Use Kotlin, Jetpack Compose, Android lifecycle primitives, coroutines, and Flow. Do not wrap a web client in a WebView.
- **Mobile-shaped control.** Preserve Hermes capabilities while adapting navigation, density, input, and system integration to Android.
- **Independent design.** Celeste should feel related to Hermes without mechanically copying Desktop layout.
- **Direct connection.** The current product connects to the user’s dashboard without a Celeste account, relay, or copied history.
- **Least privilege.** Ask for Android permissions only when a shipped feature needs them.

## Current functional boundary

Celeste can discover a dashboard, authenticate with a supported password provider or an ephemeral session token, list profiles and sessions, create and resume conversations, send prompts, stream responses and tools, interrupt turns, and reconcile after reconnecting.

The following surfaces are not implemented yet:

- browser/OAuth sign-in;
- Android Keystore-backed connection persistence;
- rich Markdown and attachments;
- approvals and clarifications;
- profile management;
- the broader Hermes Desktop management surfaces.

This list describes the current boundary, not a promised order of delivery. Update it when a capability crosses from absent to working.
