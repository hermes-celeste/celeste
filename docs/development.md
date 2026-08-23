# Development

## Toolchain

Use `scripts/celeste-env` with the checked-in Gradle wrapper for Android commands:

```bash
scripts/celeste-env ./gradlew --no-daemon tasks
```

The build host uses Temurin 21; Android source and bytecode target Java 17. Build versions and dependency coordinates belong in Gradle files.

`gradle.properties` constrains memory and parallelism for the development host. Measure before increasing either. Run screenshot rendering and packaging in separate processes when both are required.

## Portability

Android is the shipping target. Protocol behavior, application state, and custom Compose UI remain portable unless an operating-system API is essential. Platform adapters own lifecycle, secure storage, system navigation, keyboard and insets, notifications, and other native services.

Do not add Apple targets, signing, or store infrastructure without explicit approval.

## Common commands

```bash
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest
scripts/celeste-env ./gradlew --no-daemon lintDebug
scripts/celeste-env ./gradlew --no-daemon validateDebugScreenshotTest
git diff --check
```

Use [`testing.md`](testing.md) to select focused checks. GitHub Actions owns APK assembly and test-build signing.

## Change workflow

1. Read `AGENTS.md` and the docs that own the task.
2. Inspect current implementation, tests, and Hermes authority before editing.
3. Make the smallest coherent change across code, tests, and durable documentation.
4. Run focused checks during iteration and the gates required by the changed boundary.
5. Read [`review.md`](review.md) before review triage.
6. Inspect the complete diff, run `git diff --check`, and report untested runtime surfaces.

## Repository and Android identity

The public repository and product name are Celeste. The Android launcher label is `Hermes Celeste`; the application ID and Kotlin namespace are `dev.hazydreams.hermesceleste`.

Public source does not authorize releases, distributable signing, Play Store infrastructure, or artifact publication. Keep Gradle and IDE output, SDK configuration, keystores, signing properties, credentials, and private data out of the repository.
