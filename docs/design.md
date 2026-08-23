# Design

## Direction

Celeste uses one neutral dark visual system: near-black canvas, quiet charcoal surfaces, high-contrast text, restrained boundaries, and a cool accent for interaction. It should feel native to mobile rather than like a reduced Desktop window.

Other conversational products are composition references, not identities to copy. Prefer clear hierarchy, compact navigation, nearby controls, stable geometry, and generous negative space.

## Principles

- **Content first.** Transcript, connection state, and the next action outrank decoration.
- **Tonal hierarchy.** Separate surfaces through restrained value and shape changes.
- **Nearby controls.** Place actions beside the state or content they affect.
- **Stable geometry.** Streaming, reconnecting, loading, and errors avoid unnecessary layout jumps.
- **Semantic state.** Visuals derive from authoritative session state, not animation state.
- **More than color.** Pair color with copy, shape, motion, or iconography.
- **Android-native behavior.** Respect system back, IME, insets, lifecycle, accessibility, and reduced motion.
- **Polish the common path.** Connection, navigation, composing, streaming, stopping, and recovery come before decorative breadth.

## Tokens

`CelesteTheme.kt` owns executable values. Their roles are:

- canvas — application background and system chrome;
- primary surface — controls and quiet grouping;
- raised surface — code, tables, menus, and stronger separation;
- selected surface — selected rows and user-message containment;
- primary and muted text — conversational and supporting hierarchy;
- hairline — boundaries used only when tonal separation is insufficient;
- accent — links, focus, progress, and primary actions;
- success, warning, and error — semantic states only.

## Production rules

- Use bundled Inter typography.
- Use flat tonal surfaces without blur, gradients, glow, or heavy shadows.
- Render user messages on a quiet rounded surface and assistant prose directly on the transcript canvas.
- Render canonical Markdown natively. Code and tables use raised surfaces and scroll internally within message width.
- Pause automatic transcript following when the reader scrolls up; resume at the bottom or through the jump-to-latest control.
- Keep tool and system activity labeled and contained; keep ordinary assistant prose conversational.
- Represent a turn’s file edits once with a compact filled Changes pill that opens bounded per-file detail.
- Keep active task progress in one compact filled pill, left-aligned immediately above the composer; acknowledge completion briefly, then clear it.
- Present pending clarifications inline with nearby choices and actions, then collapse answered or skipped requests into compact transcript content.
- Present active work with concise copy and restrained motion that stops when inactive or not visible.
- Expose only destinations and controls with working behavior.

## Interaction contract

Interactive surfaces account for default, pressed, disabled, loading, and error states as applicable. They remain usable with the keyboard open, TalkBack, larger font scales, narrow widths, offline recovery, and reduced motion.

Connection state and active agent work remain distinct signals.

## Visual acceptance

Host-rendered Compose screenshots are the routine review surface. A reference update is a design decision and requires project-owner review. Device-only behavior such as IME, lifecycle, accessibility services, and system insets requires device feedback.
