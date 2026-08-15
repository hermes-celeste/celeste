package dev.hazydreams.hermesceleste

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
import dev.hazydreams.hermesceleste.network.InvalidDashboardResponse
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.network.TransportUnavailable
import dev.hazydreams.hermesceleste.presentation.AssistantNameKey
import dev.hazydreams.hermesceleste.presentation.InMemoryAssistantNameStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
    fun recreatedViewModelRestoresPersistedConnectionAndAssistantNameAtStartup() = runTest {
        val connectionStore = InMemoryConnectionStore()
        val assistantNameStore = InMemoryAssistantNameStore()
        val firstDashboard = AutoLoginDashboard(openProbe)
        val firstViewModel = CelesteViewModel(
            dashboard = firstDashboard,
            connectionStore = connectionStore,
            assistantNameStore = assistantNameStore,
        )
        advanceUntilIdle()

        firstViewModel.updateDashboardUrl("https://hermes.example.net")
        firstViewModel.findDashboard()
        advanceUntilIdle()
        firstViewModel.loadSessions()
        advanceUntilIdle()
        firstViewModel.openAssistantNameEditor()
        firstViewModel.updateAssistantNameDraft("Juno")
        firstViewModel.saveAssistantName()
        advanceUntilIdle()

        assertEquals("https://hermes.example.net", connectionStore.load()?.descriptor?.baseUrl)
        assertEquals("Juno", assistantNameStore.read("https://hermes.example.net", "default"))

        val recreatedDashboard = AutoLoginDashboard(openProbe)
        val recreatedViewModel = CelesteViewModel(
            dashboard = recreatedDashboard,
            connectionStore = connectionStore,
            assistantNameStore = assistantNameStore,
        )
        advanceUntilIdle()

        assertEquals(ConnectionPhase.Connected, recreatedViewModel.state.value.connectionPhase)
        assertEquals("https://hermes.example.net", recreatedViewModel.state.value.dashboardUrl)
        assertEquals("Juno", recreatedViewModel.state.value.assistantDisplayName)
        assertEquals(
            AssistantNameKey("https://hermes.example.net", "default"),
            recreatedViewModel.state.value.assistantNameKey,
        )
        assertEquals(listOf("stored-1"), recreatedViewModel.state.value.sessions?.map { it.id })
        assertNull(recreatedViewModel.state.value.activeSummary)
        assertEquals(1, recreatedDashboard.probeCalls)
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
        dashboard.onLogout = { assertNull(store.load()?.secret) }

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
        forgetDashboard.onLogout = { assertNull(forgetStore.load()) }

        forgetViewModel.forgetConnection()
        advanceUntilIdle()

        assertNull(forgetStore.load())
        assertEquals("", forgetViewModel.state.value.dashboardUrl)
        assertEquals("", forgetViewModel.state.value.username)
        assertEquals(ConnectionPhase.ManualSetup, forgetViewModel.state.value.connectionPhase)
        assertEquals(1, forgetDashboard.logoutCalls)
    }

    private class AutoLoginDashboard(
        private val probeResult: DashboardProbeResult,
    ) : DashboardService {
        var probeFailure: Throwable? = null
        var passwordFailure: Throwable? = null
        var restoreAccepted = true
        var exportedMaterial = "synthetic-session-cookies"
        var probeCalls = 0
        var passwordLoginCalls = 0
        var restoreAuthenticationCalls = 0
        var logoutCalls = 0
        var clearAuthenticationCalls = 0
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
        }

        override suspend fun listSessions(
            baseUrl: String,
            credential: GatewayCredential,
            limit: Int,
        ): List<StoredSession> = listOf(
            StoredSession(
                id = "stored-1",
                title = "Choose me manually",
                preview = "",
                startedAt = 1.0,
                messageCount = 1,
                source = "desktop",
            ),
        )

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
            return restoreAccepted
        }

        override suspend fun logout(baseUrl: String) {
            onLogout?.invoke()
            logoutCalls += 1
        }

        override fun clearAuthentication() {
            clearAuthenticationCalls += 1
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
