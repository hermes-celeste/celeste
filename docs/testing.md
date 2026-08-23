# Testing

## Evidence by boundary

| Change | Required evidence |
| --- | --- |
| Documentation | Link and terminology audit plus `git diff --check` |
| Controller, state, or protocol | Focused host-unit tests at the owning boundary |
| HTTP, authentication, or WebSocket | Focused MockWebServer or gateway regressions |
| Compose presentation or interaction | Focused logic checks, host rendering, and visual review |
| Manifest, resources, or packaging | `lintDebug`, GitHub packaging, and device verification when applicable |
| Release milestone | Targeted local evidence, successful GitHub Actions, GitHub-built APK, and meaningful device flows |

Always run `git diff --check` and disclose changed runtime surfaces that were not exercised.

## Local scope and CI

Run focused tests, lint, or screenshot scenarios during development. GitHub Actions owns the complete unit, lint, screenshot, coverage, and packaging matrix. Broaden local verification when a change crosses several boundaries or while diagnosing CI.

Pure state tests may use `runTest`. Mock WebSocket tests use real time with `runBlocking` because virtual time can outrun MockWebServer callbacks.

## Host-rendered Compose screenshots

Scenarios live in `app/src/screenshotTest`; accepted references live in `app/src/screenshotTestDebug/reference`.

Validate a focused scenario with:

```bash
scripts/celeste-env ./gradlew --no-daemon validateDebugScreenshotTest --tests '*PreviewScreenshot*'
```

Update accepted references only after project-owner visual approval, using separate update and validation invocations:

```bash
scripts/celeste-env ./gradlew --no-daemon updateDebugScreenshotTest
scripts/celeste-env ./gradlew --no-daemon validateDebugScreenshotTest
```

Inspect rendered output for clipping, hierarchy, contrast, copy, and state accuracy. Work-surface coverage includes compact Changes, opened diffs, active and completed Tasks, narrow width, and large text. LayoutLib does not verify IME behavior, lifecycle, device accessibility, networking, or physical-device rendering.

## Live Hermes contract

The opt-in live test lists and resumes a real stored session. Supply its dashboard URL and optional ephemeral token only through process environment:

```bash
HERMES_CELESTE_LIVE_URL=http://127.0.0.1:9119 \
HERMES_CELESTE_LIVE_TOKEN='[REDACTED]' \
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest \
  --tests 'dev.hazydreams.hermesceleste.network.LiveHermesDashboardTest'
```

The test skips when no URL is supplied. Never print, persist, or fixture the token or real transcript data.

## APK and device cadence

Do not assemble or install APKs in agent workflows. Pull requests verify packaging; successful `main` runs publish the consistently signed `Hermes-Celeste-latest.apk` to the rolling test pre-release and retain one Actions artifact as a fallback. The project owner retrieves and update-installs it so application data is preserved.

Host tests cannot establish Android runtime behavior. Record device, build, and observed flow for IME, lifecycle, system navigation, accessibility, networking, permissions, and performance claims.

## GitHub Actions

The Android workflow runs unit tests, lint, screenshot validation, coverage, and debug packaging. Codecov is informational.

The published test APK uses a dedicated test-only signing identity. The rolling pre-release is a test distribution surface, not a production or store release.
