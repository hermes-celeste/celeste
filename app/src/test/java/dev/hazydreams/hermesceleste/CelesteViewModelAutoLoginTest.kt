package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.connection.ConnectionStore
import dev.hazydreams.hermesceleste.connection.InMemoryConnectionStore
import dev.hazydreams.hermesceleste.connection.ReusableSecret
import dev.hazydreams.hermesceleste.connection.SavedAuthMode
import dev.hazydreams.hermesceleste.connection.SavedConnectionDescriptor
import dev.hazydreams.hermesceleste.connection.StoredConnection
import dev.hazydreams.hermesceleste.network.AuthenticationRejected
import dev.hazydreams.hermesceleste.network.AuthenticationMaterial
import dev.hazydreams.hermesceleste.network.AuthProvider
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.network.DashboardService
import dev.hazydreams.hermesceleste.network.GatewayConnection
import dev.hazydreams.hermesceleste.network.GatewayConnectionState
import dev.hazydreams.hermesceleste.network.GatewayCredential
import dev.hazydreams.hermesceleste.network.GatewayEvent
import dev.hazydreams.hermesceleste.network.GatewayRpcException
import dev.hazydreams.hermesceleste.network.InvalidDashboardResponse
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.network.TransportUnavailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CelesteViewModelAutoLoginTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun coldLaunchRestoresTheConnectionButDoesNotChooseAConversation() = runTest {
        val descriptor = SavedConnectionDescriptor(
            baseUrl = "https://hermes.example.net",
            authMode = SavedAuthMode.Open,
            expectsSecret = false,
        )
        val store = InMemoryConnectionStore(StoredConnection(descriptor, null))
        val dashboard = AutoLoginDashboard(openProbe)

        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ConnectionPhase.Connected, state.connectionPhase)
        assertEquals("https://hermes.example.net", state.dashboardUrl)
        assertEquals(listOf("stored-1"), state.sessions?.map { it.id })
        assertNull(state.activeSummary)
        assertEquals(1, dashboard.probeCalls)
    }

    @Test
    fun successfulPasswordConnectionIsRememberedAndVisibleSecretsAreCleared() = runTest {
        val store = InMemoryConnectionStore()
        val dashboard = AutoLoginDashboard(passwordProbe)
        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        advanceUntilIdle()

        viewModel.updateDashboardUrl("https://hermes.example.net")
        viewModel.findDashboard()
        advanceUntilIdle()
        viewModel.updateUsername("celeste")
        viewModel.updatePassword("synthetic-password")
        viewModel.loadSessions()
        advanceUntilIdle()

        val state = viewModel.state.value
        val saved = store.load()
        assertEquals(ConnectionPhase.Connected, state.connectionPhase)
        assertEquals("", state.password)
        assertEquals("", state.sessionToken)
        assertEquals(SavedAuthMode.ProviderSession, saved?.descriptor?.authMode)
        assertEquals("password", saved?.descriptor?.provider)
        assertEquals("celeste", saved?.descriptor?.username)
        assertTrue(saved?.secret?.value == "synthetic-session-cookies")

        dashboard.exportedMaterial = "rotated-session-cookies"
        viewModel.onBackground()
        advanceUntilIdle()
        assertTrue(store.load()?.secret?.value == "rotated-session-cookies")
    }

    @Test
    fun coldProviderRestoreReEncryptsTheLatestRotatingSessionCookies() = runTest {
        val descriptor = SavedConnectionDescriptor(
            baseUrl = "https://hermes.example.net",
            authMode = SavedAuthMode.ProviderSession,
            provider = "password",
            username = "celeste",
            expectsSecret = true,
        )
        val store = InMemoryConnectionStore(
            StoredConnection(descriptor, ReusableSecret("older-session-cookies")),
        )
        val dashboard = AutoLoginDashboard(passwordProbe).apply {
            exportedMaterial = "rotated-session-cookies"
        }

        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        advanceUntilIdle()

        assertEquals(ConnectionPhase.Connected, viewModel.state.value.connectionPhase)
        assertEquals(0, dashboard.passwordLoginCalls)
        assertEquals(1, dashboard.restoreAuthenticationCalls)
        assertTrue(store.load()?.secret?.value == "rotated-session-cookies")
    }

    @Test
    fun authenticationRejectionDeletesReusableAuthAndPausesLaterColdLaunches() = runTest {
        val descriptor = SavedConnectionDescriptor(
            baseUrl = "https://hermes.example.net",
            authMode = SavedAuthMode.ProviderSession,
            provider = "password",
            username = "celeste",
            expectsSecret = true,
        )
        val store = InMemoryConnectionStore(
            StoredConnection(descriptor, ReusableSecret("synthetic-session-cookies")),
        )
        val rejectedDashboard = AutoLoginDashboard(passwordProbe).apply {
            restoreAccepted = false
        }

        val rejected = CelesteViewModel(dashboard = rejectedDashboard, connectionStore = store)
        advanceUntilIdle()

        assertEquals(ConnectionPhase.AuthenticationRequired, rejected.state.value.connectionPhase)
        assertEquals("", rejected.state.value.password)
        assertFalse(store.load()?.descriptor?.autoLoginEnabled ?: true)
        assertNull(store.load()?.secret)
        assertEquals(0, rejectedDashboard.passwordLoginCalls)
        assertEquals(1, rejectedDashboard.restoreAuthenticationCalls)

        val nextDashboard = AutoLoginDashboard(passwordProbe)
        val nextLaunch = CelesteViewModel(dashboard = nextDashboard, connectionStore = store)
        advanceUntilIdle()

        assertEquals(ConnectionPhase.ManualSetup, nextLaunch.state.value.connectionPhase)
        assertEquals("https://hermes.example.net", nextLaunch.state.value.dashboardUrl)
        assertEquals("celeste", nextLaunch.state.value.username)
        assertEquals(0, nextDashboard.probeCalls)
        assertEquals(0, nextDashboard.passwordLoginCalls)
    }

    @Test
    fun jsonRpcAuthenticationFailureDuringRestoreClearsReusableAuth() = runTest {
        listOf(401, 403).forEach { code ->
            val descriptor = SavedConnectionDescriptor(
                baseUrl = "https://hermes.example.net",
                authMode = SavedAuthMode.ProviderSession,
                provider = "password",
                username = "celeste",
                expectsSecret = true,
            )
            val store = InMemoryConnectionStore(
                StoredConnection(descriptor, ReusableSecret("synthetic-session-cookies")),
            )
            val dashboard = AutoLoginDashboard(passwordProbe).apply {
                sessionFailure = GatewayRpcException(
                    code = code,
                    message = "raw JSON-RPC auth failure at https://private.example/path",
                )
            }

            val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
            advanceUntilIdle()

            val state = viewModel.state.value
            val notice = requireNotNull(state.notice)
            assertEquals(ConnectionPhase.AuthenticationRequired, state.connectionPhase)
            assertEquals(UiNoticeCategory.AuthenticationRequired, notice.category)
            assertEquals(UiRecoveryAction.SignIn, notice.recovery)
            assertEquals("Your Hermes sign-in has expired. Sign in again.", notice.message)
            assertEquals("https://hermes.example.net", state.dashboardUrl)
            assertEquals("celeste", state.username)
            assertNull(store.load()?.secret)
            assertFalse(store.load()?.descriptor?.autoLoginEnabled ?: true)
            assertFalse(notice.message.contains("private.example"))
        }
    }

    @Test
    fun transientRestoreFailureKeepsTheSavedConnectionAndRetrySucceeds() = runTest {
        val descriptor = SavedConnectionDescriptor(
            baseUrl = "https://hermes.example.net",
            authMode = SavedAuthMode.Open,
            expectsSecret = false,
        )
        val store = InMemoryConnectionStore(StoredConnection(descriptor, null))
        val dashboard = AutoLoginDashboard(openProbe).apply {
            probeFailure = TransportUnavailable("Hermes is unavailable.")
        }
        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        advanceUntilIdle()

        assertEquals(ConnectionPhase.RestoreFailed, viewModel.state.value.connectionPhase)
        assertEquals(descriptor, store.load()?.descriptor)

        dashboard.probeFailure = null
        viewModel.retrySavedConnection()
        advanceUntilIdle()

        assertEquals(ConnectionPhase.Connected, viewModel.state.value.connectionPhase)
        assertEquals(2, dashboard.probeCalls)
    }

    @Test
    fun malformedDashboardResponseKeepsReusableAuthenticationForRetry() = runTest {
        val descriptor = SavedConnectionDescriptor(
            baseUrl = "https://hermes.example.net",
            authMode = SavedAuthMode.ProviderSession,
            provider = "password",
            username = "celeste",
            expectsSecret = true,
        )
        val store = InMemoryConnectionStore(
            StoredConnection(descriptor, ReusableSecret("synthetic-session-cookies")),
        )
        val dashboard = AutoLoginDashboard(passwordProbe).apply {
            probeFailure = InvalidDashboardResponse("Malformed response.")
        }

        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        advanceUntilIdle()

        assertEquals(ConnectionPhase.RestoreFailed, viewModel.state.value.connectionPhase)
        assertEquals("synthetic-session-cookies", store.load()?.secret?.value)
        assertTrue(store.load()?.descriptor?.autoLoginEnabled == true)
    }

    @Test
    fun gatewayAddressCanBeReplacedDirectlyWithoutForgettingTheSavedConnectionFirst() = runTest {
        val original = SavedConnectionDescriptor(
            baseUrl = "https://old-hermes.example.net",
            authMode = SavedAuthMode.Open,
            expectsSecret = false,
        )
        val store = InMemoryConnectionStore(StoredConnection(original, null))
        val dashboard = AutoLoginDashboard(openProbe)
        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        advanceUntilIdle()

        viewModel.updateDashboardUrl("https://new-hermes.example.net")
        viewModel.findDashboard()
        advanceUntilIdle()

        assertEquals("https://new-hermes.example.net", viewModel.state.value.dashboardUrl)
        assertNull(viewModel.state.value.sessions)
        assertEquals(original, store.load()?.descriptor)

        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals(ConnectionPhase.Connected, viewModel.state.value.connectionPhase)
        assertEquals("https://new-hermes.example.net", store.load()?.descriptor?.baseUrl)
        assertEquals(SavedAuthMode.Open, viewModel.state.value.savedAuthMode)
        assertNull(viewModel.state.value.activeSummary)
    }

    @Test
    fun backgroundDuringSignOutResumesIdempotentSecretCleanupOnForeground() = runTest {
        val descriptor = SavedConnectionDescriptor(
            baseUrl = "https://hermes.example.net",
            authMode = SavedAuthMode.ProviderSession,
            provider = "password",
            username = "celeste",
            expectsSecret = true,
        )
        val clearGate = CompletableDeferred<Unit>()
        val store = BackgroundCleanupStore(
            saved = StoredConnection(descriptor, ReusableSecret("synthetic-session-cookies")),
            clearGate = clearGate,
        )
        val dashboard = AutoLoginDashboard(passwordProbe)
        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        advanceUntilIdle()
        val clearCallsBeforeSignOut = dashboard.clearAuthenticationCalls
        dashboard.onLogout = {
            assertTrue(dashboard.authenticationPresent)
            assertTrue(store.load()?.secret != null)
            assertEquals(clearCallsBeforeSignOut, dashboard.clearAuthenticationCalls)
        }

        viewModel.signOut()
        store.clearEntered.await()
        viewModel.onBackground()
        runCurrent()

        assertEquals("Signing out…", viewModel.state.value.loadingMessage)
        assertTrue(store.load()?.secret != null)
        assertEquals(1, dashboard.logoutCalls)
        assertEquals(clearCallsBeforeSignOut + 1, dashboard.clearAuthenticationCalls)

        clearGate.complete(Unit)
        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(ConnectionPhase.ManualSetup, viewModel.state.value.connectionPhase)
        assertNull(store.load()?.secret)
        assertFalse(store.load()?.descriptor?.autoLoginEnabled ?: true)
        assertEquals(2, store.clearCalls)
        assertEquals(1, dashboard.logoutCalls)
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun backgroundDuringForgetResumesIdempotentLogoutOnForeground() = runTest {
        val descriptor = SavedConnectionDescriptor(
            baseUrl = "https://hermes.example.net",
            authMode = SavedAuthMode.ProviderSession,
            provider = "password",
            username = "celeste",
            expectsSecret = true,
        )
        val store = BackgroundCleanupStore(
            saved = StoredConnection(descriptor, ReusableSecret("synthetic-session-cookies")),
        )
        val logoutGate = CompletableDeferred<Unit>()
        val dashboard = AutoLoginDashboard(passwordProbe).apply {
            this.logoutGate = logoutGate
            logoutEntered = CompletableDeferred()
        }
        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        advanceUntilIdle()
        val clearCallsBeforeForget = dashboard.clearAuthenticationCalls
        dashboard.onLogout = {
            assertTrue(dashboard.authenticationPresent)
            assertTrue(store.load() != null)
            assertEquals(clearCallsBeforeForget, dashboard.clearAuthenticationCalls)
        }

        viewModel.forgetConnection()
        dashboard.logoutEntered?.await()
        viewModel.onBackground()
        runCurrent()

        assertTrue(store.load() != null)
        assertEquals("Forgetting this connection…", viewModel.state.value.loadingMessage)
        assertEquals(1, dashboard.logoutCalls)
        assertEquals(clearCallsBeforeForget, dashboard.clearAuthenticationCalls)

        logoutGate.complete(Unit)
        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(ConnectionPhase.ManualSetup, viewModel.state.value.connectionPhase)
        assertNull(store.load())
        assertEquals(2, dashboard.logoutCalls)
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun failedSecretClearStillPublishesAuthenticationRequiredWithRetryableLocalCleanup() = runTest {
        val descriptor = SavedConnectionDescriptor(
            baseUrl = "https://hermes.example.net",
            authMode = SavedAuthMode.ProviderSession,
            provider = "password",
            username = "celeste",
            expectsSecret = true,
        )
        val store = RetryingClearConnectionStore(
            StoredConnection(descriptor, ReusableSecret("synthetic-session-cookies")),
        )
        val dashboard = AutoLoginDashboard(passwordProbe).apply {
            sessionFailure = GatewayRpcException(
                code = 401,
                message = "raw auth detail https://private.example/path",
            )
        }
        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        advanceUntilIdle()

        val failedState = viewModel.state.value
        assertEquals(ConnectionPhase.AuthenticationRequired, failedState.connectionPhase)
        assertEquals(UiNoticeCategory.AuthenticationRequired, failedState.notice?.category)
        assertEquals(UiRecoveryAction.SignIn, failedState.notice?.recovery)
        assertEquals(UiNoticeCategory.Persistence, failedState.localCleanupNotice?.category)
        assertEquals(UiRecoveryAction.Retry, failedState.localCleanupNotice?.recovery)
        assertEquals("Your Hermes sign-in has expired. Sign in again.", failedState.notice?.message)
        assertTrue(dashboard.clearAuthenticationCalls > 0)
        assertFalse(failedState.localCleanupNotice?.message.orEmpty().contains("synthetic-session-cookies"))

        viewModel.retrySavedConnection()
        advanceUntilIdle()

        assertNull(store.load()?.secret)
        assertEquals(ConnectionPhase.AuthenticationRequired, viewModel.state.value.connectionPhase)
        assertEquals(UiNoticeCategory.AuthenticationRequired, viewModel.state.value.notice?.category)
        assertNull(viewModel.state.value.localCleanupNotice)
    }

    @Test
    fun failedCleanupCannotRetryAgainstAReplacementOrigin() = runTest {
        val originalDescriptor = SavedConnectionDescriptor(
            baseUrl = "https://old-hermes.example.net",
            authMode = SavedAuthMode.ProviderSession,
            provider = "password",
            username = "celeste",
            expectsSecret = true,
        )
        val store = RetryingClearConnectionStore(
            StoredConnection(originalDescriptor, ReusableSecret("old-session-cookies")),
        )
        val dashboard = AutoLoginDashboard(passwordProbe)
        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        advanceUntilIdle()

        viewModel.signOut()
        advanceUntilIdle()
        assertEquals(UiNoticeCategory.Persistence, viewModel.state.value.localCleanupNotice?.category)

        viewModel.useAnotherConnection()
        viewModel.updateDashboardUrl("https://new-hermes.example.net")
        viewModel.findDashboard()
        advanceUntilIdle()
        viewModel.updateUsername("celeste")
        viewModel.updatePassword("synthetic-password")
        viewModel.loadSessions()
        advanceUntilIdle()

        assertEquals("https://new-hermes.example.net", store.load()?.descriptor?.baseUrl)
        assertEquals("synthetic-session-cookies", store.load()?.secret?.value)

        viewModel.retrySavedConnection()
        advanceUntilIdle()

        assertEquals("https://new-hermes.example.net", store.load()?.descriptor?.baseUrl)
        assertEquals("synthetic-session-cookies", store.load()?.secret?.value)
    }

    @Test
    fun supersededSignOutCannotClearReplacementOrigin() = runTest {
        val descriptor = SavedConnectionDescriptor(
            baseUrl = "https://old-hermes.example.net",
            authMode = SavedAuthMode.ProviderSession,
            provider = "password",
            username = "celeste",
            expectsSecret = true,
        )
        val loadGate = CompletableDeferred<Unit>()
        val store = GenerationGuardConnectionStore(
            saved = StoredConnection(descriptor, ReusableSecret("old-session-cookies")),
            loadGate = loadGate,
        )
        val dashboard = AutoLoginDashboard(passwordProbe)
        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        advanceUntilIdle()

        store.blockNextLoad()
        viewModel.signOut()
        store.loadEntered.await()
        viewModel.useAnotherConnection()
        loadGate.complete(Unit)
        advanceUntilIdle()

        val replacement = SavedConnectionDescriptor(
            baseUrl = "https://new-hermes.example.net",
            authMode = SavedAuthMode.ProviderSession,
            provider = "password",
            username = "celeste",
            expectsSecret = true,
        )
        store.replace(replacement, ReusableSecret("new-session-cookies"))

        assertEquals(0, store.clearCalls)
        assertEquals(replacement, store.load()?.descriptor)
        assertEquals("new-session-cookies", store.load()?.secret?.value)
    }

    @Test
    fun supersededForgetCannotDeleteReplacementOrigin() = runTest {
        val descriptor = SavedConnectionDescriptor(
            baseUrl = "https://old-hermes.example.net",
            authMode = SavedAuthMode.ProviderSession,
            provider = "password",
            username = "celeste",
            expectsSecret = true,
        )
        val loadGate = CompletableDeferred<Unit>()
        val store = GenerationGuardConnectionStore(
            saved = StoredConnection(descriptor, ReusableSecret("old-session-cookies")),
            loadGate = loadGate,
        )
        val dashboard = AutoLoginDashboard(passwordProbe)
        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        advanceUntilIdle()

        store.blockNextLoad()
        viewModel.forgetConnection()
        store.loadEntered.await()
        viewModel.useAnotherConnection()
        loadGate.complete(Unit)
        advanceUntilIdle()

        val replacement = SavedConnectionDescriptor(
            baseUrl = "https://new-hermes.example.net",
            authMode = SavedAuthMode.ProviderSession,
            provider = "password",
            username = "celeste",
            expectsSecret = true,
        )
        store.replace(replacement, ReusableSecret("new-session-cookies"))

        assertEquals(0, store.forgetCalls)
        assertEquals(replacement, store.load()?.descriptor)
        assertEquals("new-session-cookies", store.load()?.secret?.value)
    }

    @Test
    fun signOutRetainsSafePrefillWhileForgetRemovesEverything() = runTest {
        val descriptor = SavedConnectionDescriptor(
            baseUrl = "https://hermes.example.net",
            authMode = SavedAuthMode.ProviderSession,
            provider = "password",
            username = "celeste",
            expectsSecret = true,
        )
        val store = InMemoryConnectionStore(
            StoredConnection(descriptor, ReusableSecret("synthetic-session-cookies")),
        )
        val dashboard = AutoLoginDashboard(passwordProbe)
        val viewModel = CelesteViewModel(dashboard = dashboard, connectionStore = store)
        advanceUntilIdle()
        val clearCallsBeforeSignOut = dashboard.clearAuthenticationCalls
        dashboard.onLogout = {
            assertTrue(dashboard.authenticationPresent)
            assertTrue(store.load()?.secret != null)
            assertEquals(clearCallsBeforeSignOut, dashboard.clearAuthenticationCalls)
        }

        viewModel.signOut()
        advanceUntilIdle()

        assertEquals(ConnectionPhase.ManualSetup, viewModel.state.value.connectionPhase)
        assertEquals("https://hermes.example.net", viewModel.state.value.dashboardUrl)
        assertEquals("celeste", viewModel.state.value.username)
        assertNull(store.load()?.secret)
        assertFalse(store.load()?.descriptor?.autoLoginEnabled ?: true)
        assertEquals(1, dashboard.logoutCalls)
        assertTrue(dashboard.clearAuthenticationCalls > 0)

        val forgetStore = InMemoryConnectionStore(
            StoredConnection(descriptor, ReusableSecret("synthetic-session-cookies")),
        )
        val forgetDashboard = AutoLoginDashboard(passwordProbe)
        val forgetViewModel = CelesteViewModel(
            dashboard = forgetDashboard,
            connectionStore = forgetStore,
        )
        advanceUntilIdle()
        val clearCallsBeforeForget = forgetDashboard.clearAuthenticationCalls
        forgetDashboard.onLogout = {
            assertTrue(forgetDashboard.authenticationPresent)
            assertTrue(forgetStore.load() != null)
            assertEquals(clearCallsBeforeForget, forgetDashboard.clearAuthenticationCalls)
        }

        forgetViewModel.forgetConnection()
        advanceUntilIdle()

        assertNull(forgetStore.load())
        assertEquals("", forgetViewModel.state.value.dashboardUrl)
        assertEquals("", forgetViewModel.state.value.username)
        assertEquals(ConnectionPhase.ManualSetup, forgetViewModel.state.value.connectionPhase)
        assertEquals(1, forgetDashboard.logoutCalls)
    }

    private class BackgroundCleanupStore(
        private var saved: StoredConnection?,
        private val clearGate: CompletableDeferred<Unit>? = null,
    ) : ConnectionStore {
        var clearCalls = 0
        val clearEntered = CompletableDeferred<Unit>()

        override suspend fun load(): StoredConnection? = saved

        override suspend fun replace(
            descriptor: SavedConnectionDescriptor,
            secret: ReusableSecret?,
        ) {
            saved = StoredConnection(descriptor, secret)
        }

        override suspend fun clearSecret() {
            clearCalls += 1
            if (clearCalls == 1) {
                clearEntered.complete(Unit)
                clearGate?.await()
            }
            saved = saved?.copy(
                descriptor = saved!!.descriptor.copy(autoLoginEnabled = false),
                secret = null,
            )
        }

        override suspend fun forget() {
            saved = null
        }
    }

    private class RetryingClearConnectionStore(
        private var saved: StoredConnection?,
    ) : ConnectionStore {
        private var failuresRemaining = 1

        override suspend fun load(): StoredConnection? = saved

        override suspend fun replace(
            descriptor: SavedConnectionDescriptor,
            secret: ReusableSecret?,
        ) {
            saved = StoredConnection(descriptor, secret)
        }

        override suspend fun clearSecret() {
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                throw IllegalStateException("synthetic local cleanup failure")
            }
            saved = saved?.copy(
                descriptor = saved!!.descriptor.copy(autoLoginEnabled = false),
                secret = null,
            )
        }

        override suspend fun forget() {
            saved = null
        }
    }

    private class GenerationGuardConnectionStore(
        private var saved: StoredConnection?,
        private val loadGate: CompletableDeferred<Unit>,
    ) : ConnectionStore {
        val loadEntered = CompletableDeferred<Unit>()
        var clearCalls = 0
        var forgetCalls = 0
        private var blockLoad = false

        fun blockNextLoad() {
            blockLoad = true
        }

        override suspend fun load(): StoredConnection? {
            if (blockLoad) {
                blockLoad = false
                loadEntered.complete(Unit)
                withContext(NonCancellable) { loadGate.await() }
            }
            return saved
        }

        override suspend fun replace(
            descriptor: SavedConnectionDescriptor,
            secret: ReusableSecret?,
        ) {
            saved = StoredConnection(descriptor, secret)
        }

        override suspend fun clearSecret() {
            clearCalls += 1
            saved = saved?.copy(
                descriptor = saved!!.descriptor.copy(autoLoginEnabled = false),
                secret = null,
            )
        }

        override suspend fun forget() {
            forgetCalls += 1
            saved = null
        }
    }

    private class AutoLoginDashboard(
        private val probeResult: DashboardProbeResult,
    ) : DashboardService {
        var probeFailure: Throwable? = null
        var passwordFailure: Throwable? = null
        var sessionFailure: Throwable? = null
        var restoreAccepted = true
        var exportedMaterial = "synthetic-session-cookies"
        var probeCalls = 0
        var passwordLoginCalls = 0
        var restoreAuthenticationCalls = 0
        var logoutCalls = 0
        var clearAuthenticationCalls = 0
        var authenticationPresent = false
        var logoutGate: CompletableDeferred<Unit>? = null
        var logoutEntered: CompletableDeferred<Unit>? = null
        var onLogout: (suspend () -> Unit)? = null

        override suspend fun probe(rawBaseUrl: String): DashboardProbeResult {
            probeCalls += 1
            probeFailure?.let { throw it }
            return probeResult.copy(baseUrl = rawBaseUrl)
        }

        override suspend fun passwordLogin(
            baseUrl: String,
            provider: String,
            username: String,
            password: String,
        ) {
            passwordLoginCalls += 1
            passwordFailure?.let { throw it }
            authenticationPresent = true
        }

        override suspend fun listSessions(
            baseUrl: String,
            credential: GatewayCredential,
            limit: Int,
        ): List<StoredSession> {
            sessionFailure?.let { throw it }
            return listOf(
                StoredSession(
                    id = "stored-1",
                    title = "Choose me manually",
                    preview = "",
                    startedAt = 1.0,
                    messageCount = 1,
                    source = "desktop",
                ),
            )
        }

        override suspend fun listProfiles(
            baseUrl: String,
            credential: GatewayCredential,
        ): List<DashboardProfile> = listOf(DashboardProfile(name = "default", isDefault = true))

        override fun exportAuthentication(baseUrl: String): AuthenticationMaterial =
            AuthenticationMaterial(exportedMaterial)

        override fun restoreAuthentication(
            baseUrl: String,
            material: AuthenticationMaterial,
        ): Boolean {
            restoreAuthenticationCalls += 1
            authenticationPresent = restoreAccepted
            return restoreAccepted
        }

        override suspend fun logout(baseUrl: String) {
            logoutCalls += 1
            logoutEntered?.complete(Unit)
            logoutGate?.await()
            onLogout?.invoke()
        }

        override fun clearAuthentication() {
            clearAuthenticationCalls += 1
            authenticationPresent = false
        }

        override fun createGateway(
            baseUrl: String,
            credential: GatewayCredential,
        ): GatewayConnection = IdleGateway
    }

    private data object IdleGateway : GatewayConnection {
        override val state: StateFlow<GatewayConnectionState> = MutableStateFlow(GatewayConnectionState.Idle)
        override val events: SharedFlow<GatewayEvent> = MutableSharedFlow()
        override suspend fun connect() = Unit
        override suspend fun request(
            method: String,
            params: JsonObject,
            timeoutMillis: Long,
        ): JsonElement = JsonObject(emptyMap())
        override fun close() = Unit
    }

    private companion object {
        val openProbe = DashboardProbeResult(
            baseUrl = "https://hermes.example.net",
            authRequired = false,
            providers = emptyList(),
            version = "test",
        )
        val passwordProbe = DashboardProbeResult(
            baseUrl = "https://hermes.example.net",
            authRequired = true,
            providers = listOf(AuthProvider("password", "Password", supportsPassword = true)),
            version = "test",
        )
    }
}
