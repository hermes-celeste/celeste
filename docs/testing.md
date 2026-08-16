# Testing

## Evidence by boundary

Match verification to what changed:

| Change | Required evidence |
| --- | --- |
| Documentation only | Link/terminology audit and `git diff --check` |
| Kotlin state or protocol logic | Focused unit test during iteration, then `testDebugUnitTest` |
| URL, authentication, HTTP, or WebSocket behavior | MockWebServer/gateway regression plus `testDebugUnitTest`; use the live contract when server admission or shape changed |
| Compose layout, copy, color, or interaction state | Relevant unit checks plus host screenshot review and `validateDebugScreenshotTest` |
| Manifest, resources, launcher, packaging, or install behavior | Local `lintDebug`, GitHub Actions packaging, and device verification when behavior crosses onto Android |
| Release milestone | Local unit tests, lint, screenshots, a successful GitHub-built APK, and meaningful real-device flows |

Always run `git diff --check`. Disclose any changed runtime surface that was not exercised.

## Unit and protocol tests

Tests live under `app/src/test`.

- `DashboardUrlPolicyTest` owns URL normalization and cleartext admission.
- `DashboardClientTest` owns HTTP/authentication and short WebSocket operations.
- `ConnectionStoreTest` owns bootstrap decisions, secret redaction, Sign out versus Forget semantics, and ciphertext endpoint binding.
- `BackupExclusionTest` owns named descriptor exclusions across legacy backup, cloud backup, and device transfer rules.
- `HermesGatewayTest` owns readiness, request correlation, events, endpoint refresh, and disconnect behavior.
- `CelesteViewModelTest` owns session creation/resume, event reduction, interruption, reconnect, and no-resend invariants; `CelesteViewModelAutoLoginTest` owns cold restore, typed recovery, remembered login, and cleanup transitions.
- `LiveHermesDashboardTest` is the opt-in real-server contract.

Add a regression at the lowest layer that owns the failure. Cross-layer lifecycle invariants belong in the ViewModel tests even when a socket symptom exposed them.

Mock WebSocket tests use real time with `runBlocking`. Do not convert them to `runTest`: virtual-time advancement can outrun real MockWebServer callbacks and create false timeouts. Pure coroutine/state tests can use `runTest`.

Run all local unit tests with:

```bash
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest
```

## Host-rendered Compose screenshots

The screenshot scenarios live in `app/src/screenshotTest`; accepted PNGs live in `app/src/screenshotTestDebug/reference`. The current matrix covers Gateway setup, password sign-in, Settings and connected Gateway management, saved-connection restoration and recovery, conversation listing, a blank new conversation, composing, streaming, completion, and reconnection.

Validate accepted references:

```bash
scripts/celeste-env ./gradlew --no-daemon validateDebugScreenshotTest
```

For an intentionally accepted visual change, update references only after project-owner review:

```bash
scripts/celeste-env ./gradlew --no-daemon updateDebugScreenshotTest
scripts/celeste-env ./gradlew --no-daemon validateDebugScreenshotTest
```

Run update and validation in separate Gradle invocations. A combined task graph can validate stale references before the update runs.

A reference update is not proof by itself. Inspect the generated images for clipping, hierarchy, contrast, copy, and state accuracy.

The screenshot plugin and validation API are experimental, and the current references use exact image comparison. Toolchain, font, renderer, dimensions, preview names, and dependency changes can alter baselines. Host-rendered LayoutLib screenshots do not verify real-device rendering, IME behavior, lifecycle/process death, platform accessibility, or networking.

## Live Hermes contract

Pass the dashboard URL and optional ephemeral token only through the process environment:

```bash
HERMES_CELESTE_LIVE_URL=http://127.0.0.1:9119 \
HERMES_CELESTE_LIVE_TOKEN='[REDACTED]' \
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest \
  --tests 'dev.hazydreams.hermesceleste.network.LiveHermesDashboardTest'
```

The test lists and resumes a real stored session. It skips when the URL is absent. Never print or persist the token. Remove temporary token files and stop any dashboard process created for the test.

## APK and device cadence

Do not assemble, retrieve, inspect, or install APKs in agent workflows. Use host tests per change; GitHub Actions verifies packaging on pull requests and produces the consistently signed test APK only from successful `main` runs. The project owner retrieves that artifact from GitHub and handles real-device installation and updates.

Real-device feedback remains valuable for Android-only behavior such as lifecycle transitions, IME/insets, system back, permissions, network changes, launcher assets, and performance. Record owner-reported flows and device conditions rather than reporting “tested on device” without specifics.

There is currently no `app/src/androidTest` suite. The configured instrumentation runner and connected-device tasks do not constitute device coverage; report Android runtime behavior as untested unless it was explicitly exercised.

Base-path-prefixed dashboard routing is supported in source but does not yet have a direct MockWebServer regression. Add one when route joining changes.

## GitHub Actions

`.github/workflows/android.yml` runs the repository checks on pull requests. Pull requests must pass unit tests, lint, screenshot validation, and debug APK assembly without publishing an artifact.

The `Verify` job generates `app/build/reports/kover/reportDebug.xml` from local JVM unit tests and uploads it to Codecov using GitHub OIDC. Codecov project and patch statuses are informational, and its pull-request comment includes both project and patch coverage as the primary summary; coverage does not gate merges. The report includes the full application source, including Compose UI, but Kover does not measure screenshot or device execution.

A successful `main` run, including a manually dispatched run, publishes `Hermes-Celeste-latest.apk` as the current test build. The workflow uploads the new APK before deleting older artifacts with the same name, then verifies that exactly one remains. A failed build cannot remove the last known-good package. GitHub requires artifacts to expire; the current APK uses the maximum 90-day retention and requires GitHub sign-in to download.

The test APK is a debug build signed with a dedicated test-only identity, not a release or store artifact. Install it only for project testing. Each successful build uses the same application ID and test signing identity so Android can update-install it over an earlier GitHub Actions build while preserving application data. A locally built debug APK has a different signing identity and cannot update a GitHub-built installation.
