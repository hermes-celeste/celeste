# Development

## Toolchain

Use `scripts/celeste-env` for every Gradle and Android SDK command. It selects the project toolchain under `~/.local/share/hermes-celeste-toolchain`:

```bash
scripts/celeste-env ./gradlew --no-daemon tasks
```

Use the checked-in Gradle wrapper, not a system Gradle installation. Keep `distributionSha256Sum` pinned in `gradle/wrapper/gradle-wrapper.properties`.

The project currently uses Java 17, a single Android `:app` module, Kotlin, Jetpack Compose, kotlinx.serialization, coroutines, and OkHttp. Build versions and dependency coordinates belong in Gradle files, not this document. No iOS target is configured yet.

## Portability boundary

Android is the current build, packaging, and runtime target. New protocol behavior, application state, and custom Compose UI must remain portable unless an operating-system API is essential. `CelesteController` is the present application boundary: it accepts a host scope, service/store contracts, client identity, and URL admission function, while AndroidX `ViewModel`, Activity lifecycle, Keystore, and other Android integrations remain outside it.

The intended Gradle direction is a Kotlin Multiplatform shared module/source set plus a thin Android application module and, later, an iOS host. Validate that split against lint, Kover, screenshot tests, and GitHub APK packaging before moving source. Do not add Apple targets, signing, or store infrastructure without explicit approval.

When adding a feature, put product rules and custom presentation on the portable side. Add a platform adapter only for lifecycle, secure storage, system navigation, keyboard/insets, pickers, notifications, haptics, or another concrete operating-system service. Do not add platform branches to shared state merely because only Android ships today.

## Host memory constraints

`gradle.properties` deliberately limits heap, workers, and parallelism for the development host. Do not increase the heap or parallel workers without measuring host memory and rerunning a clean build.

Do not combine screenshot rendering and APK packaging in one work phase. LayoutLib and Android packaging can exceed the host memory budget together. Run them in separate Gradle processes and only when the change needs both boundaries verified.

## Common commands

```bash
# Unit and protocol checks
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest

# Android static analysis
scripts/celeste-env ./gradlew --no-daemon lintDebug

# Accepted UI references
scripts/celeste-env ./gradlew --no-daemon validateDebugScreenshotTest
```

Use [`testing.md`](testing.md) to select the checks required for a change. GitHub Actions owns APK assembly and test-build signing; do not create distributable APKs locally.

GitHub Actions uses the checked-in Gradle wrapper directly on a standard Ubuntu runner. Workflow actions must be limited to necessary official actions and pinned to immutable commit SHAs. Dependabot checks Gradle and GitHub Actions weekly, groups minor and patch updates per ecosystem, and leaves major updates isolated for review. The CI and current-test-APK behavior is defined in [`testing.md`](testing.md).

## Change workflow

1. Read `AGENTS.md` and the documents that own the task.
2. Inspect the existing implementation and tests before changing architecture.
3. For Hermes-facing behavior, follow the authority workflow in [`hermes-protocol.md`](hermes-protocol.md).
4. Make the smallest coherent change across code, tests, and the owning doc.
5. Run targeted checks during iteration.
6. Run the full checks required by the changed boundaries.
7. Finish with `git diff --check` and inspect the complete diff.
8. Report any runtime surface that was not exercised.

## Android identity

The product and Gradle project are conceptually Celeste. The Android launcher and app-list label is `Hermes Celeste` for searchability. The current application ID and Kotlin namespace are `dev.hazydreams.hermesceleste`; changing an application ID after distribution creates a different Android app, so treat that as a release-level decision.

## Repository policy

The source repository is public at `hermes-celeste/celeste`. Use feature branches and pull requests for normal development once the initial public snapshot is established.

Public source does not authorize a release. Do not publish releases, sign distributable builds, create Play Store infrastructure, or upload artifacts without explicit project-owner approval.

The organization handle does not change the product name. Use `Celeste` in repository-facing branding; `Hermes Celeste` remains the Android launcher and app-list name.

## Generated and private files

Do not commit Gradle/IDE output, local SDK configuration, keystores, or signing properties. `.gitignore` owns the current patterns. Credential and private-data handling is defined in [`security.md`](security.md).
