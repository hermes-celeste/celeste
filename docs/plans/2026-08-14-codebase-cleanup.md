# Celeste Codebase Cleanup Implementation Plan

**Goal:** Simplify Celeste's current codebase without changing product behavior, transport semantics, security guarantees, or its intentionally single-module architecture.

**Architecture:** Preserve the existing direction of Compose → application state → dashboard/protocol → transport, with connection persistence injected into application state. Consolidate duplication at existing boundaries, split files only where ownership is already distinct, and avoid speculative frameworks or performance work without evidence.

**Tech Stack:** Kotlin, Jetpack Compose, Android ViewModel and coroutines, OkHttp WebSocket/HTTP, kotlinx.serialization, JUnit, Compose screenshot tests, Gradle.

---

## Review baseline

A four-angle whole-codebase review covered reuse, code quality, efficiency, and architectural altitude across the tracked source, tests, resources, and documentation.

The current architecture is sound and appropriately simple for Celeste:

- a single Android application module remains the right deployment and ownership boundary;
- Compose renders state and emits intent;
- `CelesteViewModel` owns application/session coordination;
- `DashboardClient` owns dashboard HTTP and authenticated gateway construction;
- `GatewaySessionApi` owns typed session RPC decoding;
- `HermesGateway` owns persistent JSON-RPC transport;
- `ConnectionStore` owns reusable connection persistence.

Cleanup pressure is concentrated in three large compilation units rather than spread throughout the system:

- `app/src/main/java/dev/hazydreams/hermesceleste/MainActivity.kt`
- `app/src/main/java/dev/hazydreams/hermesceleste/CelesteViewModel.kt`
- `app/src/main/java/dev/hazydreams/hermesceleste/network/DashboardClient.kt`

## Prerequisites and exclusions

### Resolve correctness before cleanup

Do not begin the cleanup on a branch that still has known correctness failures. In particular:

1. Preserve typed `AuthenticationRejected` failures from persistent WebSocket 401/403 responses through `HermesGateway.connect()`. The application reconnect policy must receive the type instead of a generic `IOException`.
2. Remove the broad profile-list fallback. The current `/api/profiles` route is required; authentication rejection, rate limiting, transport failure, missing routes, and malformed responses must remain failures.

These are correctness changes, not cleanup tasks. Implement and verify them separately before refactoring the affected paths.

### Explicitly out of scope

Do not introduce:

- additional Gradle modules;
- a dependency-injection framework;
- a generic repository/domain layer;
- a formal reducer or state-machine framework;
- a new navigation framework;
- a WebView;
- local distributable APK assembly;
- streaming or event-buffer optimizations without device/profile evidence;
- transactional storage machinery for extremely narrow process-death windows unless real failures justify it.

## Acceptance criteria

- Product behavior and visible screenshots remain unchanged unless separately approved.
- Dashboard authentication, cookie restoration, session identity, reconnect, and reconciliation invariants remain covered by tests.
- The disposable session-resume WebSocket retains focused lifecycle and error coverage; session discovery uses the required current REST route.
- Disposable and persistent session decoding share canonical message identity rules.
- Authentication-rejection cleanup is expressed once at the application-state boundary while callers retain lifecycle-specific work.
- `MainActivity.kt` owns Activity setup and top-level routing, not every Celeste screen.
- Gateway settings previews use a compact, coherent screen model instead of repeated long parameter lists.
- The app remains a single Android module with no new runtime dependencies.
- Unit tests, lint, screenshot validation, and `git diff --check` pass locally; GitHub Actions alone assembles the APK.

---

### Task 1: Establish a verified cleanup baseline

**Objective:** Start from merged `main` with the correctness prerequisites resolved and record a clean verification baseline.

**Files:**
- Read: `AGENTS.md`
- Read: `docs/architecture.md`
- Read: `docs/hermes-protocol.md`
- Read: `docs/security.md`
- Read: `docs/testing.md`

**Step 1: Confirm the branch base**

Run:

```bash
git fetch origin
git status --short --branch
git log -3 --oneline --decorate
```

Expected: a clean cleanup branch based on the intended merged `main` commit.

**Step 2: Run the unit baseline**

Run:

```bash
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

**Step 3: Run static and visual baselines**

Run:

```bash
scripts/celeste-env ./gradlew --no-daemon lintDebug
scripts/celeste-env ./gradlew --no-daemon validateDebugScreenshotTest
git diff --check
```

Expected: every command exits successfully and no screenshot reference changes are produced.

**Step 4: Commit**

No commit is needed when the baseline is clean and unchanged.

---

### Task 2: Specify shared disposable WebSocket behavior with tests

> Superseded: current Celeste discovers sessions through required REST routes and no longer opens a disposable `session.list` WebSocket. Keep focused coverage for the remaining disposable `session.resume` contract instead of preserving obsolete shared plumbing.

**Objective:** Lock down the existing session-list and disposable-resume WebSocket behavior before extracting shared plumbing.

**Files:**
- Modify: `app/src/test/java/dev/hazydreams/hermesceleste/network/DashboardClientTest.kt`
- Read: `app/src/main/java/dev/hazydreams/hermesceleste/network/DashboardClient.kt`

**Step 1: Add focused tests**

Cover both operations for:

- expected JSON-RPC response ID filtering;
- cancellation closing/canceling the socket;
- 401/403 → `AuthenticationRejected`;
- 429 → `RateLimited`;
- other upgrade/transport failure → `TransportUnavailable`;
- malformed response → `InvalidDashboardResponse`;
- operation-specific user-facing failure messages.

Use synthetic endpoints and credentials only.

**Step 2: Run the focused tests**

Run:

```bash
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest --tests 'dev.hazydreams.hermesceleste.network.DashboardClientTest'
```

Expected: the new tests pass against current behavior or expose an undocumented difference that must be resolved before extraction.

**Step 3: Commit**

```bash
git add app/src/test/java/dev/hazydreams/hermesceleste/network/DashboardClientTest.kt
git commit -m "test: define disposable gateway behavior"
```

---

### Task 3: Extract one disposable WebSocket request helper

**Objective:** Remove duplicated socket lifecycle, completion, cancellation, and failure mapping from `DashboardClient` without changing operation semantics.

**Files:**
- Modify: `app/src/main/java/dev/hazydreams/hermesceleste/network/DashboardClient.kt`
- Test: `app/src/test/java/dev/hazydreams/hermesceleste/network/DashboardClientTest.kt`

**Step 1: Introduce a private helper**

Use a focused shape such as:

```kotlin
private suspend fun <T> requestSingleWebSocketResponse(
    request: Request,
    frame: JsonObject,
    expectedId: String,
    operation: String,
    decode: (JsonElement) -> T,
): T
```

The helper owns:

- `suspendCancellableCoroutine`;
- one completion guard;
- socket cancellation;
- response-ID filtering;
- close/failure completion;
- shared HTTP status classification.

It must not own session-list or session-resume decoding.

**Step 2: Reduce the operation functions**

Keep `requestSessionList` and `requestSessionResume` responsible only for:

- constructing their JSON-RPC request;
- naming the operation;
- decoding the typed result.

**Step 3: Run focused tests**

Run:

```bash
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest --tests 'dev.hazydreams.hermesceleste.network.DashboardClientTest'
git diff --check
```

Expected: all focused tests pass and the two operation functions no longer duplicate socket plumbing.

**Step 4: Commit**

```bash
git add app/src/main/java/dev/hazydreams/hermesceleste/network/DashboardClient.kt app/src/test/java/dev/hazydreams/hermesceleste/network/DashboardClientTest.kt
git commit -m "refactor: share disposable gateway requests"
```

---

### Task 4: Share canonical session-message decoding

**Objective:** Reuse one decoder for message roles, tool names, text, and deterministic identity while preserving separate disposable and persistent transports.

**Files:**
- Modify: `app/src/main/java/dev/hazydreams/hermesceleste/network/GatewaySessionApi.kt`
- Modify: `app/src/main/java/dev/hazydreams/hermesceleste/network/DashboardClient.kt`
- Test: `app/src/test/java/dev/hazydreams/hermesceleste/network/DashboardClientTest.kt`
- Test: `app/src/test/java/dev/hazydreams/hermesceleste/network/HermesGatewayTest.kt`

**Step 1: Expose an internal decoder boundary**

Move or expose internal functions that decode:

- gateway messages;
- role and content aliases;
- `name` / `tool_name`;
- `row_id` / `id` / `message_id`;
- deterministic occurrence-qualified fallback identity.

Do not move transport lifecycle into the decoder.

**Step 2: Replace the disposable-path decoder**

Have `DashboardClient` call the canonical decoder for resumed messages while retaining its minimally decoded disposable WebSocket flow.

**Step 3: Add parity tests**

Feed the same synthetic tool-heavy transcript to both decode entry points and assert equivalent roles, text, tool names, and IDs.

**Step 4: Run focused tests**

Run:

```bash
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest --tests 'dev.hazydreams.hermesceleste.network.*Test'
```

Expected: all network tests pass.

**Step 5: Commit**

```bash
git add app/src/main/java/dev/hazydreams/hermesceleste/network app/src/test/java/dev/hazydreams/hermesceleste/network
git commit -m "refactor: share gateway message decoding"
```

---

### Task 5: Consolidate definitive authentication rejection

**Objective:** Express saved-auth invalidation once at the application-state boundary while preserving distinct cold-restore and active-reconnect lifecycle work.

**Files:**
- Modify: `app/src/main/java/dev/hazydreams/hermesceleste/CelesteViewModel.kt`
- Test: `app/src/test/java/dev/hazydreams/hermesceleste/CelesteViewModelAutoLoginTest.kt`
- Test: `app/src/test/java/dev/hazydreams/hermesceleste/CelesteViewModelTest.kt`

**Step 1: Keep separate regression tests**

Retain distinct tests for:

- rejected authentication during cold restoration;
- rejected authentication during active conversation reconnect;
- endpoint/account prefill retention;
- encrypted-secret deletion;
- no continued reconnect loop.

**Step 2: Extract the shared transition**

Use one suspend helper with only the minimal shared inputs, for example:

```kotlin
private suspend fun invalidateReusableAuthentication(
    descriptor: SavedConnectionDescriptor?,
    probe: DashboardProbeResult? = null,
)
```

The helper owns:

- clearing in-memory credential state;
- clearing dashboard authentication;
- serialized `connectionStore.clearSecret()`;
- publishing `AuthenticationRequired` with safe prefill.

Callers continue to own gateway closing, reconnect-job handling, and attempt-generation checks.

**Step 3: Run focused tests**

Run:

```bash
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest --tests 'dev.hazydreams.hermesceleste.CelesteViewModelAutoLoginTest' --tests 'dev.hazydreams.hermesceleste.CelesteViewModelTest'
```

Expected: both lifecycle paths pass without duplicated cleanup blocks.

**Step 4: Commit**

```bash
git add app/src/main/java/dev/hazydreams/hermesceleste/CelesteViewModel.kt app/src/test/java/dev/hazydreams/hermesceleste/CelesteViewModelAutoLoginTest.kt app/src/test/java/dev/hazydreams/hermesceleste/CelesteViewModelTest.kt
git commit -m "refactor: centralize authentication recovery"
```

---

### Task 6: Split Compose screens by existing ownership

**Objective:** Make UI ownership navigable without changing routing, state ownership, visuals, or introducing a navigation framework.

**Files:**
- Modify: `app/src/main/java/dev/hazydreams/hermesceleste/MainActivity.kt`
- Create: `app/src/main/java/dev/hazydreams/hermesceleste/ui/CelesteRoutes.kt`
- Create: `app/src/main/java/dev/hazydreams/hermesceleste/ui/gateway/GatewayScreens.kt`
- Create: `app/src/main/java/dev/hazydreams/hermesceleste/ui/sessions/SessionListScreen.kt`
- Create: `app/src/main/java/dev/hazydreams/hermesceleste/ui/conversation/ConversationScreen.kt`
- Create: `app/src/main/java/dev/hazydreams/hermesceleste/ui/conversation/TranscriptItem.kt`
- Test: `app/src/screenshotTest/kotlin/dev/hazydreams/hermesceleste/CelesteScreensScreenshotTest.kt`

**Step 1: Move one ownership area at a time**

Move code without rewriting it, in this order:

1. transcript row identity/rendering;
2. conversation screen;
3. session list;
4. Gateway setup/settings/recovery;
5. top-level route selection.

Keep `MainActivity.kt` responsible for Activity creation, ViewModel wiring, lifecycle forwarding, and the root `setContent` call.

**Step 2: Compile after each file move**

Run:

```bash
scripts/celeste-env ./gradlew --no-daemon compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL` after each move.

**Step 3: Validate screenshots without updating references**

Run:

```bash
scripts/celeste-env ./gradlew --no-daemon validateDebugScreenshotTest
```

Expected: every accepted reference remains unchanged.

**Step 4: Commit the pure move**

```bash
git add app/src/main/java/dev/hazydreams/hermesceleste app/src/screenshotTest
git commit -m "refactor: split Compose screen ownership"
```

---

### Task 7: Compact the Gateway settings contract

**Objective:** Replace the long Gateway settings parameter list with coherent immutable screen state and actions after the screen has its own file.

**Files:**
- Modify: `app/src/main/java/dev/hazydreams/hermesceleste/ui/gateway/GatewayScreens.kt`
- Modify: `app/src/main/java/dev/hazydreams/hermesceleste/ui/CelesteRoutes.kt`
- Modify: `app/src/screenshotTest/kotlin/dev/hazydreams/hermesceleste/CelesteScreensScreenshotTest.kt`

**Step 1: Introduce screen-only models**

Use immutable UI types such as:

```kotlin
internal data class GatewaySettingsUiState(
    val address: String,
    val sessionToken: String,
    val username: String,
    val password: String,
    val phase: ConnectionPhase,
    val status: GatewayStatus,
    val canSignOut: Boolean,
    val canForget: Boolean,
)

internal data class GatewaySettingsActions(
    val onAddressChanged: (String) -> Unit,
    val onSessionTokenChanged: (String) -> Unit,
    val onUsernameChanged: (String) -> Unit,
    val onPasswordChanged: (String) -> Unit,
    val onContinue: () -> Unit,
    val onConnect: () -> Unit,
    val onSignOut: () -> Unit,
    val onForget: () -> Unit,
    val onBack: (() -> Unit)?,
)
```

Keep these models UI-only. Do not move credentials, persistence, or protocol decisions into Compose.

**Step 2: Map route state once**

Have the route create one `GatewaySettingsUiState` and one `GatewaySettingsActions` instance rather than forwarding each field and callback independently.

**Step 3: Update previews**

Make screenshot scenarios construct named state fixtures so each preview shows its intended state without a long positional contract.

**Step 4: Verify**

Run:

```bash
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest
scripts/celeste-env ./gradlew --no-daemon validateDebugScreenshotTest
git diff --check
```

Expected: tests and screenshots pass with no visible changes.

**Step 5: Commit**

```bash
git add app/src/main/java/dev/hazydreams/hermesceleste/ui app/src/screenshotTest
git commit -m "refactor: compact Gateway screen state"
```

---

### Task 8: Run the full non-packaging verification gate

**Objective:** Prove the cleanup preserved behavior and repository policy.

**Files:**
- Verify all files changed by Tasks 2–7

**Step 1: Unit and protocol tests**

```bash
scripts/celeste-env ./gradlew --no-daemon testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

**Step 2: Android lint**

```bash
scripts/celeste-env ./gradlew --no-daemon lintDebug
```

Expected: `BUILD SUCCESSFUL` with no new errors.

**Step 3: Screenshot validation**

```bash
scripts/celeste-env ./gradlew --no-daemon validateDebugScreenshotTest
```

Expected: `BUILD SUCCESSFUL` and no reference updates.

**Step 4: Repository hygiene**

```bash
git diff --check
git status --short --branch
```

Expected: no whitespace errors and only the intended cleanup changes.

**Step 5: Commit verification-only corrections if needed**

Use a narrowly scoped conventional commit. Do not amend unrelated implementation commits.

## Deferred candidates

Revisit only with concrete evidence or when the existing boundary becomes actively obstructive:

- Split `DashboardService` into explicit dashboard-data and authentication/session-lifecycle capabilities. Do not keep silent no-op authentication defaults if an alternate implementation becomes real.
- Introduce focused ViewModel transition helpers if connection-state combinations continue to grow. Do not adopt a reducer framework preemptively.
- Coalesce `message.delta` state publication and bottom-follow scrolling only after Android profiling or user-visible jank demonstrates a problem.
- Define a bounded reconciliation-event policy only with an explicit overflow/recovery contract and tests for snapshot/event ordering.
- Parallelize session/profile discovery only after profile compatibility and failure semantics are explicit.

## Delivery strategy

Keep correctness prerequisites, network cleanup, and UI file organization in separate PRs when practical:

1. correctness prerequisites;
2. disposable WebSocket and decoder consolidation;
3. authentication-recovery consolidation;
4. pure Compose file split;
5. Gateway screen-contract cleanup.

GitHub Actions remains the only APK assembly/signing path. Each cleanup PR must use host tests, lint, and screenshot validation locally, then rely on the successful `main` workflow for a distributable test APK.