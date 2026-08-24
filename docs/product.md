# Product

## Identity

**Celeste** is an Android-first native client for a self-hosted [Hermes Agent](https://github.com/NousResearch/hermes-agent) dashboard. Desktop and Celeste are clients of the same server and shared state; Celeste does not copy conversations into a separate service.

Android is the shipping target. Protocol behavior, application state, and custom UI are designed to move to Compose Multiplatform without promising an iOS release.

| Surface | Name |
| --- | --- |
| Product, repository, README, and documentation | Celeste |
| Android launcher and app list | Hermes Celeste |
| GitHub organization | `hermes-celeste` |
| Android application ID and Kotlin namespace | `dev.hazydreams.hermesceleste` |

## Principles

- **One Hermes, another surface.** The server owns profiles, sessions, messages, and agent work.
- **Android first, shared by design.** Keep portable behavior shared and native integration narrow.
- **Platform-native edges.** Adapt lifecycle, navigation, input, storage, and system integration to the operating system.
- **Independent design.** Preserve Hermes capabilities without copying Desktop layout.
- **Direct connection.** Use the user’s dashboard without a Celeste account, relay, or copied history.
- **Least privilege.** Request platform permissions only for shipped features.

## Scope

Celeste connects and authenticates to a Hermes dashboard, securely restores supported connections, browses and manages conversations, and runs native mobile conversation sessions with image and file attachments, rich transcript rendering, streaming activity, interruption, and recovery.
