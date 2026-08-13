# Design

## Direction

Celeste uses an editorial, heavenly visual language: deep ink, warm light, cobalt, coral, and restrained halo geometry. The interface should feel calm, legible, and native to Android rather than like a miniature Desktop window.

The current implementation is dark-only. Light and dark modes are equal intended product surfaces; do not claim light-mode support until it is implemented and screenshot-tested.

Do not introduce visual-system codenames or revive rejected branding in source, tests, documentation, or assets.

## Principles

- **Content first.** Transcript, connection state, and the next available action outrank decoration.
- **Effects earn their cost.** Glow, blur, pulse, and motion must communicate hierarchy, connection state, or active work.
- **Nearby controls.** Place actions beside the state or content they affect.
- **Stable geometry.** Loading, streaming, reconnecting, and error states should not cause avoidable layout jumps.
- **State is semantic.** Derive visuals from authoritative gateway/session state, not from animation-local state.
- **More than color.** Pair color with copy, shape, motion, or iconography for state distinctions.
- **Android-native behavior.** Respect system back, IME, navigation insets, lifecycle, accessibility, and reduced motion.
- **Polish the common path.** Connection, session selection, composing, streaming, stopping, and recovery deserve complete states before decorative breadth.

## Current tokens

`app/src/main/java/dev/hazydreams/hermesceleste/ui/CelesteTheme.kt` owns executable color values. This document owns their intended roles:

- ink — page background and strongest contrast field;
- panel / raised panel — grouping and layered transcript surfaces;
- coral — primary action and connected/available emphasis;
- cobalt — secondary action, user authorship, and synchronization;
- gold — active Hermes work and streaming attention;
- warm text / muted text — reading hierarchy;
- error — actionable failure state.

Do not duplicate hexadecimal values here. Change tokens in code and validate affected screenshot states.

## Interaction states

Every interactive surface must account for:

- default, pressed, disabled, loading, and error;
- keyboard and IME behavior;
- TalkBack name, role, and state;
- font scaling and narrow-width layout;
- offline/reconnecting behavior when relevant;
- reduced-motion behavior for animation.

Connection state and active agent work are different signals. A connected idle conversation must not look like a running turn.

## Conversation presence and activity

**Status:** accepted backlog direction, not implemented.

Desktop reference: [`references/hermes-desktop-conversation-activity.png`](references/hermes-desktop-conversation-activity.png). Use it as interaction evidence, not a layout to copy.

When conversation presence is designed:

- use a small leading indicator for availability or connection state;
- use static neutral treatment for unavailable, disabled, or disconnected;
- use static semantic emphasis for connected and idle;
- pulse only during active work;
- give the selected row a restrained tinted surface;
- if active work uses traveling illumination, move it through the row or chat bar rather than scaling or flashing the whole surface;
- stop animations when the app or item is not visible;
- provide a reduced-motion treatment that preserves the distinction;
- verify several simultaneous sessions, dark and light themes, contrast, TalkBack, and battery impact.

Do not implement backlog items unchanged merely because they are documented. They still require a concrete design pass and project-owner review.

## Visual acceptance

Host-rendered Compose screenshots are the routine review surface. A changed reference means a design decision, not a test repair. Obtain project-owner review before updating accepted references. The command sequence and evidence bar live in [`testing.md`](testing.md).
