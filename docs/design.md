# Design

## Direction

Celeste uses a neutral dark visual system: a near-black canvas, quiet charcoal surfaces, high-contrast text, restrained borders, and a single cool accent for interactive emphasis. The interface should feel calm, direct, and native to mobile rather than like a miniature Desktop window.

Contemporary conversational apps are composition references, not identities to copy. Prefer compact navigation, clear conversational hierarchy, generous negative space, and nearby controls. Do not reproduce another product’s branding, icons, reactions, destinations, or ornamental activity indicators.

Dark mode is the only implemented product surface for this milestone. Do not claim light-theme support or add a theme toggle until a separately designed and screenshot-tested light system exists.

Do not introduce visual-system codenames or retain superseded light/glow terminology in source, tests, documentation, or assets.

## Principles

- **Content first.** Transcript, connection state, and the next available action outrank decoration.
- **Tonal hierarchy.** Separate canvas, controls, selected state, and structured content through restrained surface values and borders rather than glow, blur, gradients, or heavy shadows.
- **Nearby controls.** Place actions beside the state or content they affect.
- **Stable geometry.** Loading, streaming, reconnecting, and error states should not cause avoidable layout jumps.
- **State is semantic.** Derive visuals from authoritative gateway/session state, not animation-local state.
- **More than color.** Pair color with copy, shape, motion, or iconography for state distinctions.
- **Android-native behavior.** Respect system back, IME, navigation insets, lifecycle, accessibility, and reduced motion.
- **One visual system from launch onward.** The launch window, adaptive icon, system bars, popups, dialogs, and Compose content use the same dark neutral roles.
- **Polish the common path.** Connection, session selection, composing, streaming, stopping, and recovery deserve complete states before decorative breadth.

## Current tokens

`app/src/main/java/dev/hazydreams/hermesceleste/ui/CelesteTheme.kt` owns executable color values. This document owns their intended roles:

- canvas — application background and system chrome;
- primary surface — controls and quiet grouping;
- raised surface — code, tables, menus, and elements requiring stronger separation;
- selected surface — selected rows and user-message containment;
- primary text — conversation content and strongest hierarchy;
- muted text — supporting labels and metadata;
- hairline — restrained boundaries;
- accent — links, focus, progress, and primary actions;
- success, warning, and error — semantic states only.

Do not duplicate hexadecimal values here. Change tokens in code and validate affected screenshot states.

## Current production rules

- Bundle and use Inter for all interface typography. The checked-in font comes from Google Fonts under the SIL Open Font License.
- Use flat tonal surfaces with restrained one-pixel boundaries. Do not add blur, luminous shadows, traveling edge light, or page-wide effects.
- Standard user messages use a quiet rounded surface without a speaker label. Standard assistant responses are plain transcript text without a speaker label, rail, or generic response container.
- User and assistant message bodies derive native rich Markdown from their canonical raw text. Tool input and output remain literal. Code and tables use a neutral raised surface, size from the available message width, and scroll internally rather than widening the transcript.
- Sending a message opts into following the latest transcript content. Scrolling upward pauses automatic following during streaming; tapping the centered jump-to-latest control or manually reaching the bottom resumes it. Show the control immediately above the composer whenever content continues below the viewport.
- Tool and system events remain labeled because their role is not inferable from conversational position; render them as neutral contained surfaces.
- Active work uses concise copy, semantic status color, and restrained progress treatment. It must not frame or illuminate the page.
- Only expose controls and destinations with working behavior. Future drawer locations do not justify inert placeholders.

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

**Status:** neutral dark foundation implemented; conversation-shell and compact session-navigation work remain under issue #36.

When conversation presence is designed:

- use a small leading indicator for availability or connection state;
- use static neutral treatment for unavailable, disabled, or disconnected;
- use restrained semantic emphasis for connected and idle;
- reserve motion for active work and stop it when the app or item is not visible;
- give the selected row a quiet tonal surface;
- preserve the distinction when system animation is disabled;
- verify several simultaneous sessions, contrast, TalkBack, and battery impact.

Do not implement backlog items unchanged merely because they are documented. They still require a concrete design pass and project-owner review.

## Visual acceptance

Host-rendered Compose screenshots are the routine review surface. A changed reference means a design decision, not a test repair. Obtain project-owner review before updating accepted references. The command sequence and evidence bar live in [`testing.md`](testing.md).
