# Celeste Codebase Cleanup Plan

## Goal

Simplify Celeste without changing product behavior, protocol semantics, security guarantees, or the single-module packaging boundary.

## Direction

Preserve Compose → application state → dashboard/protocol → transport. Consolidate duplication at existing seams and split files only when ownership is already distinct.

Priority boundaries:

1. share canonical session-message decoding across transports;
2. centralize authentication-rejection transitions while preserving lifecycle-specific work;
3. keep Activity, route, screen, transcript, and gateway presentation ownership separate;
4. use compact immutable UI state and action contracts instead of long parameter lists.

## Exclusions

Cleanup does not introduce additional Gradle modules, dependency injection, generic repository/domain layers, reducer frameworks, navigation frameworks, WebViews, local APK distribution, or speculative performance machinery.

## Verification

- Preserve visible behavior and accepted screenshots unless a visual change is separately approved.
- Retain focused coverage for authentication, restoration, session identity, reconnect, and reconciliation.
- Keep transport lifecycle separate from canonical message decoding.
- Keep credentials, persistence, and protocol decisions outside Compose.
- Run focused unit tests, lint, affected screenshot validation, and `git diff --check`.
- Let GitHub Actions own APK assembly and signing.

## Deferred work

Revisit new service splits, state-transition abstractions, stream coalescing, event-buffer policy, and parallel discovery only when current boundaries obstruct a concrete feature or measured behavior demonstrates a need.
