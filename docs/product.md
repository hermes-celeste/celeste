# Product

## Identity

**Celeste** is an Android-first native client for a self-hosted [Hermes Agent](https://github.com/NousResearch/hermes-agent) dashboard. Desktop and Celeste are clients of the same Hermes server and shared state. Celeste does not copy conversations into a separate mobile service.

Android is the only application target today. The product direction is one Compose Multiplatform Celeste whose protocol behavior, application state, and custom UI can be shared with a future iOS target. This direction does not promise an iOS application, release date, or store presence.

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
- **Android first, shared by design.** Deliver and verify Android now while keeping protocol rules, application state, and custom Compose UI portable. Do not build a separately interpreted iOS client or wrap a web client in a WebView.
- **Platform-native edges.** Preserve Hermes capabilities while adapting lifecycle, navigation, input, secure storage, and system integration to each operating system through narrow adapters.
- **Independent design.** Celeste should feel related to Hermes without mechanically copying Desktop layout.
- **Direct connection.** The current product connects to the user’s dashboard without a Celeste account, relay, or copied history.
- **Least privilege.** Ask for platform permissions only when a shipped feature needs them.

## Current functional boundary

Celeste can discover a dashboard, authenticate with a supported password provider or session token, manage the connection directly under **Settings → Gateway**, remember every successful supported connection with Android Keystore-backed encryption, automatically restore into an empty composer without creating a durable conversation, browse and search grouped conversations from the navigation drawer, pin or rename a conversation from its row actions, explicitly sign out or forget the connection, create and resume conversations, send prompts, render native rich Markdown in user and assistant messages, stream responses and tools, interrupt turns, and reconcile after reconnecting.

The following surfaces are not implemented yet:

- browser/OAuth sign-in;
- attachments;
- approvals and clarifications;
- profile management;
- the broader Hermes Desktop management surfaces.

This list describes the current boundary, not a promised order of delivery. Update it when a capability crosses from absent to working.
