# Celeste

Celeste is a native Android client for a self-hosted Hermes Agent dashboard. Its Android launcher and app-list label is **Hermes Celeste**; the product, repository, and documentation name is **Celeste**.

Run agent sessions from the repository root. Hermes loads `AGENTS.md` from the working directory rather than walking parent directories.

## Repository map

- `app/src/main/java/dev/hazydreams/hermesceleste` — Compose UI, application state, and lifecycle coordination
- `app/src/main/java/dev/hazydreams/hermesceleste/network` — dashboard HTTP, authentication, JSON-RPC, and gateway transport
- `app/src/test` — unit, protocol, lifecycle, and opt-in live contract tests
- `app/src/screenshotTest` — host-rendered Compose screenshot scenarios
- `app/src/screenshotTestDebug/reference` — accepted screenshot references
- `app/src/main/res` — Android manifest resources, launcher assets, backup rules, and theme resources
- `scripts/celeste-env` — project JDK and Android SDK environment wrapper
- `docs` — source of truth for system-level and process-level knowledge

## Docs

“The docs,” “check the docs,” and “check the X docs” mean `docs/`, not the web. At the start of non-trivial work, list `docs/` and read the relevant files before changing code or fetching external guidance.

| Doc | Owns |
| --- | --- |
| [`docs/product.md`](docs/product.md) | Product identity, scope, principles, and roadmap boundaries |
| [`docs/architecture.md`](docs/architecture.md) | Runtime layers, state ownership, data flow, and lifecycle invariants |
| [`docs/hermes-protocol.md`](docs/hermes-protocol.md) | Protocol authority, routes, JSON-RPC methods/events, authentication, and compatibility workflow |
| [`docs/design.md`](docs/design.md) | Visual language, interaction principles, accessibility, and accepted design backlog |
| [`docs/development.md`](docs/development.md) | Toolchain, build setup, repository policy, and local development workflow |
| [`docs/testing.md`](docs/testing.md) | Test layers, command selection, screenshots, live contracts, and device milestones |
| [`docs/security.md`](docs/security.md) | Trust boundaries, credential handling, transport rules, backup policy, and sensitive data |
| [`docs/investigations/2026-08-15-device-feedback/README.md`](docs/investigations/2026-08-15-device-feedback/README.md) | Historical, unvalidated source maps from the first-device investigations |
| [`docs/plans/2026-08-14-codebase-cleanup.md`](docs/plans/2026-08-14-codebase-cleanup.md) | Ordered cleanup scope, exclusions, tasks, and verification gates |

## Writing docs

- **Integrate, don’t append.** Find the document that owns the subject and rewrite the part that changed. Do not collect discoveries at the bottom in task order.
- **Document what code cannot explain.** Capture reasons, cross-file constraints, conventions, failure modes, and hard-won gotchas. Keep line-by-line behavior next to the code.
- **One fact, one doc.** Link to the owning document instead of copying its explanation elsewhere.
- **Respect the layers.** This file maps the repository and names critical rules. Activity docs define a workflow. Subject docs own one domain completely.
- **One subject per doc.** Split a document when its ownership no longer fits in one sentence, not whenever a new class appears.
- **Delete stale knowledge.** Replace obsolete rules rather than preserving history in active docs. Prefer a source path over a pasted implementation.
- **Register new docs.** Add every new document to the table above and link it from any document that should route readers there.
- Write plainly and briefly. State the rule, then the reason when it is not obvious. Avoid slogans, filler, repeated conclusions, and setup-punchline prose.

## Quick start

```bash
# Unit and protocol work
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest

# Android static analysis
scripts/celeste-env ./gradlew --no-daemon lintDebug

# Validate accepted Compose references
scripts/celeste-env ./gradlew --no-daemon validateDebugScreenshotTest
```

GitHub Actions owns APK assembly and test-build signing; do not create distributable APKs locally. Choose checks by change boundary; see [`docs/testing.md`](docs/testing.md). Always finish with `git diff --check`.

## Critical rules

- The current official Hermes server and Desktop implementation is protocol authority. Check it before changing routes, payloads, events, or lifecycle behavior; see [`docs/hermes-protocol.md`](docs/hermes-protocol.md).
- The Hermes dashboard owns profiles, sessions, messages, and capabilities. Do not create a second mobile session store or synchronization layer; see [`docs/architecture.md`](docs/architecture.md).
- Keep transport, authentication, protocol models, and session state independent of Compose.
- Never log, commit, fixture, or screenshot credentials or private conversation data. Persist only supported reusable authentication through the Keystore-backed connection store; raw passwords and private conversation data remain memory-only. See [`docs/security.md`](docs/security.md).
- The source repository is public. Do not publish releases, sign distributable builds, create store infrastructure, or expose credentials/private user data; see [`docs/development.md`](docs/development.md) and [`docs/security.md`](docs/security.md).
- Build the application UI with Kotlin and Jetpack Compose. Do not introduce a WebView UI.
- Do not accept a visual change by updating screenshot references without project-owner review; see [`docs/testing.md`](docs/testing.md).
