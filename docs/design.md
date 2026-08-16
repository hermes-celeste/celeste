# Design

## Direction

Celeste uses a luminous, minimal heavenly visual language: cloud-white surfaces, dark ink, contained cool-blue system light, warm amber active-work light, precise hairlines, and restrained halo geometry. The interface should feel calm, spacious, and native to Android rather than like a miniature Desktop window.

Apple software is a quality calibration, not a visual identity to copy: prefer quiet hierarchy, Inter typography, minimal explanatory copy, precise spacing, and controls that feel inevitable. Light must remain attached to the surface or active state that emits it. Do not add a page-wide color wash or detached atmospheric glow.

The current implementation establishes the light surface first. Dark mode remains an intended product surface; do not claim dark-mode support until it is implemented and screenshot-tested.

Do not introduce visual-system codenames or revive rejected branding in source, tests, documentation, or assets.

## Principles

- **Content first.** Transcript, connection state, and the next available action outrank decoration.
- **Effects earn their cost.** Glow, blur, pulse, and motion must communicate hierarchy, connection state, or active work.
- **Nearby controls.** Place actions beside the state or content they affect.
- **Stable geometry.** Loading, streaming, reconnecting, and error states should not cause avoidable layout jumps.
- **State is semantic.** Derive visuals from authoritative gateway/session state, not from animation-local state.
- **More than color.** Pair color with copy, shape, motion, or iconography for state distinctions.
- **Android-native behavior.** Respect system back, IME, navigation insets, lifecycle, accessibility, and reduced motion.
- **One visual system from launch onward.** The native launch window, adaptive icon, system bars, popups, and dialogs must use the same cloud-white, ink, blue, and amber roles as Compose.
- **Polish the common path.** Connection, session selection, composing, streaming, stopping, and recovery deserve complete states before decorative breadth.

## Current tokens

`app/src/main/java/dev/hazydreams/hermesceleste/ui/CelesteTheme.kt` owns executable color values. This document owns their intended roles:

- cloud white / white — page background and raised controls;
- ink — text, primary outlines, and strongest contrast;
- panel / raised panel — quiet grouping and transcript differentiation;
- cool blue — system light, selected or connected emphasis, and contained surface glow; use the deeper accessible blue for text and controls;
- warm amber — active-work light and warm semantic emphasis; use the deeper accessible amber for text and controls;
- success — connected confirmation where a distinct success signal is required;
- muted text / hairline — secondary hierarchy and boundaries;
- error — actionable failure state.

Do not duplicate hexadecimal values here. Change tokens in code and validate affected screenshot states.

## Current production rules

- Bundle and use Inter for all interface typography. The checked-in font comes from Google Fonts under the SIL Open Font License.
- A contained surface emits its own blurred blue or amber halo. The light may extend beyond the edge as blur, but it must remain visibly anchored to that surface's shape.
- Standard user messages use a quiet rounded bubble without a speaker label. Standard assistant responses are plain transcript text without a speaker label, vertical rail, or legacy response container.
- Tool and system events remain labeled because their role is not inferable from conversational position; render them as contained surfaces.
- Active-turn framing derives from authoritative `TurnState`, remains clipped to the conversation viewport, and preserves a static distinction when system animation is disabled.
- The page background is flat cloud white. Do not restore the retired gold top gradient or corner ornament.

## Editorial concept anchor

![Recovered Celeste editorial connection concept](references/celeste-editorial-concept.png)

This recovered connection screen is the selected early direction that established Celeste's editorial warmth, serif-led hierarchy, cobalt and coral accents, generous negative space, and restrained halo geometry. It is an iteration anchor, not a claim that the current production UI implements this composition.

The executable source is `app/src/screenshotTest/kotlin/dev/hazydreams/hermesceleste/CelesteEditorialConceptScreenshotTest.kt`. Its accepted screenshot reference is the reproducibility check; `references/celeste-editorial-concept.png` is the stable human-review artifact. Keep the original concept intact. Create additional named concept previews and reference images when exploring revisions, then deliberately promote accepted choices into production components and tokens.

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

**Status:** contained-light and active-turn foundations implemented; richer per-session activity remains backlog.

Desktop reference: [`references/hermes-desktop-conversation-activity.png`](references/hermes-desktop-conversation-activity.png). Use it as interaction evidence, not a layout to copy.

When conversation presence is designed:

- use a small leading indicator for availability or connection state;
- use static neutral treatment for unavailable, disabled, or disconnected;
- use static semantic emphasis for connected and idle;
- pulse only during active work;
- give the selected row a restrained tinted surface;
- when active work represents the whole open turn, clip traveling illumination to the conversation viewport; row-specific activity remains inside its row;
- stop animations when the app or item is not visible;
- provide a reduced-motion treatment that preserves the distinction;
- verify several simultaneous sessions, dark and light themes, contrast, TalkBack, and battery impact.

Do not implement backlog items unchanged merely because they are documented. They still require a concrete design pass and project-owner review.

## Visual acceptance

Host-rendered Compose screenshots are the routine review surface. A changed reference means a design decision, not a test repair. Obtain project-owner review before updating accepted references. The command sequence and evidence bar live in [`testing.md`](testing.md).
