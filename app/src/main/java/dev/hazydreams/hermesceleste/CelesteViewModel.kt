package dev.hazydreams.hermesceleste

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hazydreams.hermesceleste.connection.ConnectionBootstrapDecision
import dev.hazydreams.hermesceleste.connection.ConnectionStore
import dev.hazydreams.hermesceleste.connection.InMemoryConnectionStore
import dev.hazydreams.hermesceleste.connection.ReusableSecret
import dev.hazydreams.hermesceleste.connection.SavedAuthMode
import dev.hazydreams.hermesceleste.connection.SavedConnectionDescriptor
import dev.hazydreams.hermesceleste.connection.connectionBootstrapDecision
import dev.hazydreams.hermesceleste.network.AuthenticationRejected
import dev.hazydreams.hermesceleste.network.AuthenticationMaterial
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.DashboardClient
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.network.DashboardRpcException
import dev.hazydreams.hermesceleste.network.DashboardService
import dev.hazydreams.hermesceleste.network.DashboardUrlPolicy
import dev.hazydreams.hermesceleste.network.GatewayConnection
import dev.hazydreams.hermesceleste.network.GatewayConnectionState
import dev.hazydreams.hermesceleste.network.GatewayCredential
import dev.hazydreams.hermesceleste.network.GatewayEvent
import dev.hazydreams.hermesceleste.network.GatewayRpcException
import dev.hazydreams.hermesceleste.network.LocalActivityDelivery
import dev.hazydreams.hermesceleste.network.PendingLocalActivity
import dev.hazydreams.hermesceleste.network.ResumedSession
import dev.hazydreams.hermesceleste.network.SessionIdentity
import dev.hazydreams.hermesceleste.network.SessionListPage
import dev.hazydreams.hermesceleste.network.SessionOrdering
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.network.boolean
import dev.hazydreams.hermesceleste.network.createSession
import dev.hazydreams.hermesceleste.network.deduplicateSessions
import dev.hazydreams.hermesceleste.network.effectiveRemoteActivity
import dev.hazydreams.hermesceleste.network.interruptSession
import dev.hazydreams.hermesceleste.network.normalizedSessionProfile
import dev.hazydreams.hermesceleste.network.orderSessions
import dev.hazydreams.hermesceleste.network.reconcileSessionRows
import dev.hazydreams.hermesceleste.network.resumeStoredSession
import dev.hazydreams.hermesceleste.network.sessionIdentity
import dev.hazydreams.hermesceleste.network.string
import dev.hazydreams.hermesceleste.network.submitPrompt
import dev.hazydreams.hermesceleste.network.validEpochSeconds
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class TurnState {
    Synchronizing,
    Idle,
    Running,
    Reconnecting,
}

internal enum class ConnectionPhase {
    CheckingSavedConnection,
    ManualSetup,
    Restoring,
    RestoreFailed,
    AuthenticationRequired,
    Connected,
}

internal data class CelesteUiState(
    val connectionPhase: ConnectionPhase = ConnectionPhase.CheckingSavedConnection,
    val dashboardUrl: String = "",
    val probe: DashboardProbeResult? = null,
    val savedAuthMode: SavedAuthMode? = null,
    val username: String = "",
    val password: String = "",
    val sessionToken: String = "",
    val sessions: List<StoredSession>? = null,
    val profiles: List<DashboardProfile> = listOf(DashboardProfile(name = "default", isDefault = true)),
    val selectedProfile: String = "default",
    val activeSummary: StoredSession? = null,
    val messages: List<ConversationMessage> = emptyList(),
    val streamingText: String = "",
    val draft: String = "",
    val turnState: TurnState = TurnState.Idle,
    val loadingMessage: String? = null,
    val errorMessage: String? = null,
)

private data class LoadedDashboard(
    val credential: GatewayCredential,
    val sessionPage: SessionListPage,
    val profiles: List<DashboardProfile>,
)

private data class RememberedDashboard(
    val loaded: LoadedDashboard,
    val descriptor: SavedConnectionDescriptor,
    val persistenceError: Throwable?,
)

private data class LocalActivityOperation(
    val identity: SessionIdentity,
    val operationId: Long,
    val contextGeneration: Long,
)

internal class CelesteViewModel(
    private val dashboard: DashboardService = DashboardClient(),
    private val connectionStore: ConnectionStore = InMemoryConnectionStore(),
    private val reconnectDelayMillis: (attempt: Int, wasRunning: Boolean) -> Long = { attempt, wasRunning ->
        if (wasRunning && attempt == 0) 100L else min(5_000L, 1_000L shl attempt.coerceAtMost(2))
    },
    private val clockSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
) : ViewModel() {
    private val mutableState = MutableStateFlow(CelesteUiState())
    val state: StateFlow<CelesteUiState> = mutableState.asStateFlow()

    private val localMessageCounter = AtomicLong(0)
    private var credential: GatewayCredential? = null
    private var gateway: GatewayConnection? = null
    private var gatewayEventsJob: Job? = null
    private var gatewayStateJob: Job? = null
    private var reconnectJob: Job? = null
    private var foregroundCheckJob: Job? = null
    private var sessionRefreshJob: Job? = null
    private var sessionInvalidationJob: Job? = null
    private var sessionPollJob: Job? = null
    private var connectionJob: Job? = null
    private var connectionAttempt = 0L
    private var sessionRefreshGeneration = 0L
    private var sessionContextGeneration = 0L
    private var conversationGeneration = 0L
    private var sessionListScopeProfile = "all"
    private var sessionListOrdering = SessionOrdering.SERVER_ORDER
    private var sessionOperationCounter = 0L
    private val pendingLocalActivity = linkedMapOf<SessionIdentity, PendingLocalActivity>()
    private var conversationsVisible = false
    private val connectionStoreMutex = Mutex()
    private var currentDescriptor: SavedConnectionDescriptor? = null
    private var reconnectAttempts = 0
    private var reconciling = false
    private var currentSessionCanResume = true
    private val bufferedEvents = mutableListOf<GatewayEvent>()

    init {
        restoreSavedConnection()
    }

    fun updateDashboardUrl(value: String) {
        mutableState.value = mutableState.value.copy(
            dashboardUrl = value,
            probe = null,
            errorMessage = null,
        )
    }

    fun updateUsername(value: String) {
        mutableState.value = mutableState.value.copy(username = value)
    }

    fun updatePassword(value: String) {
        mutableState.value = mutableState.value.copy(password = value)
    }

    fun updateSessionToken(value: String) {
        mutableState.value = mutableState.value.copy(sessionToken = value)
    }

    fun updateDraft(value: String) {
        mutableState.value = mutableState.value.copy(draft = value)
    }

    fun selectProfile(name: String) {
        if (mutableState.value.profiles.none { it.name == name }) return
        if (mutableState.value.selectedProfile == name) return
        pendingLocalActivity.clear()
        beginSessionContextChange()
        sessionListScopeProfile = name
        mutableState.value = mutableState.value.copy(
            selectedProfile = name,
            sessions = if (mutableState.value.sessions == null) null else emptyList(),
            loadingMessage = if (mutableState.value.sessions == null) {
                mutableState.value.loadingMessage
            } else {
                "Loading conversations…"
            },
        )
        scheduleSessionRefresh(profile = name)
    }

    fun findDashboard() {
        val rawUrl = mutableState.value.dashboardUrl
        if (rawUrl.isBlank()) return
        val attempt = beginConnectionAttempt()
        closeGateway()
        credential = null
        currentDescriptor = null
        dashboard.clearAuthentication()
        mutableState.value = mutableState.value.copy(
            connectionPhase = ConnectionPhase.ManualSetup,
            sessions = null,
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            loadingMessage = "Finding Hermes…",
            errorMessage = null,
        )
        connectionJob = viewModelScope.launch {
            runCatching { dashboard.probe(rawUrl) }
                .onSuccess { result ->
                    if (!isCurrentConnectionAttempt(attempt)) return@onSuccess
                    mutableState.value = mutableState.value.copy(
                        dashboardUrl = result.baseUrl,
                        probe = result,
                    )
                }
                .onFailure { error ->
                    if (!isCurrentConnectionAttempt(attempt)) return@onFailure
                    mutableState.value = mutableState.value.copy(
                        errorMessage = error.message ?: "Could not reach the Hermes dashboard.",
                    )
                }
            if (!isCurrentConnectionAttempt(attempt)) return@launch
            mutableState.value = mutableState.value.copy(loadingMessage = null)
        }
    }

    fun loadSessions() {
        val snapshot = mutableState.value
        val connection = snapshot.probe ?: return
        val attempt = beginConnectionAttempt()
        dashboard.clearAuthentication()
        mutableState.value = snapshot.copy(
            connectionPhase = ConnectionPhase.ManualSetup,
            loadingMessage = "Loading your conversations…",
            errorMessage = null,
        )
        connectionJob = viewModelScope.launch {
            runCatching {
                val passwordProvider = connection.providers.firstOrNull { it.supportsPassword }
                val selectedCredential = if (connection.authRequired) {
                    passwordProvider
                        ?: error("This dashboard requires browser sign-in, which is not in this build yet.")
                    dashboard.passwordLogin(
                        baseUrl = connection.baseUrl,
                        provider = passwordProvider.name,
                        username = snapshot.username,
                        password = snapshot.password,
                    )
                    GatewayCredential.CookieSession
                } else {
                    snapshot.sessionToken
                        .takeIf(String::isNotBlank)
                        ?.let(GatewayCredential::StaticToken)
                        ?: GatewayCredential.None
                }
                val loaded = loadDashboard(connection.baseUrl, selectedCredential)
                val descriptor = when {
                    connection.authRequired -> SavedConnectionDescriptor(
                        baseUrl = connection.baseUrl,
                        authMode = SavedAuthMode.ProviderSession,
                        provider = requireNotNull(passwordProvider).name,
                        username = snapshot.username,
                        expectsSecret = true,
                    )
                    selectedCredential is GatewayCredential.StaticToken -> SavedConnectionDescriptor(
                        baseUrl = connection.baseUrl,
                        authMode = SavedAuthMode.StaticToken,
                        expectsSecret = true,
                    )
                    else -> SavedConnectionDescriptor(
                        baseUrl = connection.baseUrl,
                        authMode = SavedAuthMode.Open,
                        expectsSecret = false,
                    )
                }
                val reusableSecret = when (descriptor.authMode) {
                    SavedAuthMode.ProviderSession -> dashboard.exportAuthentication(connection.baseUrl)
                        ?.let { ReusableSecret(it.value) }
                    SavedAuthMode.StaticToken -> ReusableSecret(snapshot.sessionToken)
                    SavedAuthMode.Open -> null
                }
                val persistenceError = connectionStoreMutex.withLock {
                    if (!isCurrentConnectionAttempt(attempt)) throw CancellationException()
                    runCatching {
                        connectionStore.replace(descriptor, reusableSecret)
                    }.exceptionOrNull()
                }
                RememberedDashboard(loaded, descriptor, persistenceError)
            }.onSuccess { remembered ->
                if (!isCurrentConnectionAttempt(attempt)) return@onSuccess
                credential = remembered.loaded.credential
                currentDescriptor = remembered.descriptor
                publishConnectedDashboard(
                    loaded = remembered.loaded,
                    password = "",
                    sessionToken = "",
                    errorMessage = if (remembered.persistenceError == null) {
                        null
                    } else {
                        "Connected, but Celeste could not remember this connection."
                    },
                )
            }.onFailure { error ->
                if (!isCurrentConnectionAttempt(attempt)) return@onFailure
                dashboard.clearAuthentication()
                mutableState.value = mutableState.value.copy(
                    errorMessage = error.message ?: "Could not load Hermes conversations.",
                    password = "",
                    sessionToken = "",
                )
            }
            if (!isCurrentConnectionAttempt(attempt)) return@launch
            mutableState.value = mutableState.value.copy(loadingMessage = null)
        }
    }

    fun leaveSessionList() {
        beginConnectionAttempt()
        closeGateway()
        credential = null
        mutableState.value = mutableState.value.copy(
            connectionPhase = ConnectionPhase.ManualSetup,
            sessions = null,
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            errorMessage = null,
        )
    }

    fun retrySavedConnection() {
        restoreSavedConnection()
    }

    fun useAnotherConnection() {
        beginConnectionAttempt()
        closeGateway()
        credential = null
        currentDescriptor = null
        dashboard.clearAuthentication()
        mutableState.value = CelesteUiState(connectionPhase = ConnectionPhase.ManualSetup)
    }

    fun signOut() {
        val snapshot = mutableState.value
        val activeCredential = credential
        val attempt = beginConnectionAttempt()
        closeGateway()
        credential = null
        currentDescriptor = null
        mutableState.value = snapshot.copy(
            connectionPhase = ConnectionPhase.ManualSetup,
            sessions = null,
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            password = "",
            sessionToken = "",
            loadingMessage = "Signing out…",
            errorMessage = null,
        )
        connectionJob = viewModelScope.launch {
            val (error, saved) = connectionStoreMutex.withLock {
                val clearError = runCatching { connectionStore.clearSecret() }.exceptionOrNull()
                clearError to runCatching { connectionStore.load() }.getOrNull()
            }
            if (activeCredential == GatewayCredential.CookieSession && snapshot.probe != null) {
                runCatching { dashboard.logout(snapshot.probe.baseUrl) }
            }
            dashboard.clearAuthentication()
            if (!isCurrentConnectionAttempt(attempt)) return@launch
            currentDescriptor = saved?.descriptor
            mutableState.value = manualState(
                descriptor = saved?.descriptor,
                errorMessage = if (error == null) null else {
                    "Celeste could not remove the saved sign-in. Try Forget connection."
                },
            )
        }
    }

    fun forgetConnection() {
        val snapshot = mutableState.value
        val activeCredential = credential
        val attempt = beginConnectionAttempt()
        closeGateway()
        credential = null
        currentDescriptor = null
        mutableState.value = CelesteUiState(
            connectionPhase = ConnectionPhase.ManualSetup,
            loadingMessage = "Forgetting this connection…",
        )
        connectionJob = viewModelScope.launch {
            val error = connectionStoreMutex.withLock {
                runCatching { connectionStore.forget() }.exceptionOrNull()
            }
            if (activeCredential == GatewayCredential.CookieSession && snapshot.probe != null) {
                runCatching { dashboard.logout(snapshot.probe.baseUrl) }
            }
            dashboard.clearAuthentication()
            if (!isCurrentConnectionAttempt(attempt)) return@launch
            mutableState.value = CelesteUiState(
                connectionPhase = ConnectionPhase.ManualSetup,
                errorMessage = if (error == null) null else {
                    "Celeste could not remove the saved connection. Try again."
                },
            )
        }
    }

    private fun restoreSavedConnection() {
        val attempt = beginConnectionAttempt()
        closeGateway()
        credential = null
        mutableState.value = CelesteUiState(
            connectionPhase = ConnectionPhase.CheckingSavedConnection,
            loadingMessage = "Checking this device…",
        )
        connectionJob = viewModelScope.launch {
            val savedResult = connectionStoreMutex.withLock {
                runCatching { connectionStore.load() }
            }
            if (!isCurrentConnectionAttempt(attempt)) return@launch
            val saved = savedResult.getOrElse {
                mutableState.value = manualState(
                    descriptor = null,
                    errorMessage = "Celeste could not read the saved connection. Sign in again.",
                )
                return@launch
            }
            when (val decision = connectionBootstrapDecision(saved)) {
                ConnectionBootstrapDecision.ManualSetup -> {
                    mutableState.value = manualState()
                }
                is ConnectionBootstrapDecision.Prefill -> {
                    mutableState.value = manualState(decision.descriptor)
                }
                is ConnectionBootstrapDecision.Restore -> {
                    restoreConnection(decision, attempt)
                }
            }
        }
    }

    private suspend fun restoreConnection(
        decision: ConnectionBootstrapDecision.Restore,
        attempt: Long,
    ) {
        val descriptor = decision.descriptor
        var restoredProbe: DashboardProbeResult? = null
        mutableState.value = CelesteUiState(
            connectionPhase = ConnectionPhase.Restoring,
            dashboardUrl = descriptor.baseUrl,
            savedAuthMode = descriptor.authMode,
            username = descriptor.username.orEmpty(),
            loadingMessage = "Reconnecting to your Hermes…",
        )
        dashboard.clearAuthentication()
        runCatching {
            val normalized = DashboardUrlPolicy.normalize(descriptor.baseUrl)
            if (normalized != descriptor.baseUrl) {
                throw AuthenticationRejected("The saved dashboard address changed.")
            }
            val probe = dashboard.probe(normalized)
            restoredProbe = probe
            val restoredCredential = when (descriptor.authMode) {
                SavedAuthMode.Open -> {
                    if (probe.authRequired) throw AuthenticationRejected("Hermes now requires sign-in.")
                    GatewayCredential.None
                }
                SavedAuthMode.StaticToken -> {
                    if (probe.authRequired) throw AuthenticationRejected("Hermes now requires a different sign-in.")
                    GatewayCredential.StaticToken(requireNotNull(decision.secret).value)
                }
                SavedAuthMode.ProviderSession -> {
                    if (!probe.authRequired) throw AuthenticationRejected("Hermes authentication changed.")
                    probe.providers.firstOrNull {
                        it.name == descriptor.provider && it.supportsPassword
                    } ?: throw AuthenticationRejected("The saved Hermes sign-in provider is unavailable.")
                    val restored = dashboard.restoreAuthentication(
                        normalized,
                        AuthenticationMaterial(requireNotNull(decision.secret).value),
                    )
                    if (!restored) throw AuthenticationRejected("The saved Hermes session is unavailable.")
                    GatewayCredential.CookieSession
                }
            }
            probe to loadDashboard(normalized, restoredCredential)
        }.onSuccess { (probe, loaded) ->
            if (!isCurrentConnectionAttempt(attempt)) return@onSuccess
            val persistenceError = if (descriptor.authMode == SavedAuthMode.ProviderSession) {
                val refreshed = dashboard.exportAuthentication(descriptor.baseUrl)
                if (refreshed == null) {
                    IOException("The refreshed Hermes session was unavailable.")
                } else {
                    connectionStoreMutex.withLock {
                        if (!isCurrentConnectionAttempt(attempt)) throw CancellationException()
                        runCatching {
                            connectionStore.replace(descriptor, ReusableSecret(refreshed.value))
                        }.exceptionOrNull()
                    }
                }
            } else {
                null
            }
            credential = loaded.credential
            currentDescriptor = descriptor
            mutableState.value = mutableState.value.copy(
                dashboardUrl = probe.baseUrl,
                probe = probe,
            )
            publishConnectedDashboard(
                loaded,
                errorMessage = if (persistenceError == null) null else {
                    "Connected, but Celeste could not refresh the saved sign-in."
                },
            )
        }.onFailure { error ->
            if (!isCurrentConnectionAttempt(attempt)) return@onFailure
            if (error is AuthenticationRejected) {
                invalidateReusableAuthentication(
                    descriptor = descriptor,
                    probe = restoredProbe,
                )
            } else {
                dashboard.clearAuthentication()
                credential = null
                currentDescriptor = null
                mutableState.value = CelesteUiState(
                    connectionPhase = ConnectionPhase.RestoreFailed,
                    dashboardUrl = descriptor.baseUrl,
                    savedAuthMode = descriptor.authMode,
                    username = descriptor.username.orEmpty(),
                    errorMessage = error.message ?: "Could not reconnect to Hermes.",
                )
            }
        }
    }

    private suspend fun loadDashboard(
        baseUrl: String,
        selectedCredential: GatewayCredential,
    ): LoadedDashboard {
        val sessionPage = dashboard.listSessionPage(
            baseUrl = baseUrl,
            credential = selectedCredential,
            profile = "all",
            limit = SESSION_PAGE_LIMIT,
            offset = 0,
        )
        val profiles = dashboard.listProfiles(baseUrl, selectedCredential)
        return LoadedDashboard(selectedCredential, sessionPage, profiles)
    }

    private suspend fun invalidateReusableAuthentication(
        descriptor: SavedConnectionDescriptor?,
        probe: DashboardProbeResult? = null,
    ) {
        beginSessionContextChange()
        conversationsVisible = false
        closeGateway()
        credential = null
        currentDescriptor = null
        dashboard.clearAuthentication()
        connectionStoreMutex.withLock {
            runCatching { connectionStore.clearSecret() }
        }
        mutableState.value = manualState(
            descriptor = descriptor,
            phase = ConnectionPhase.AuthenticationRequired,
            probe = probe,
            errorMessage = "Saved sign-in is no longer valid. Sign in again.",
        )
    }

    private fun publishConnectedDashboard(
        loaded: LoadedDashboard,
        password: String = "",
        sessionToken: String = "",
        errorMessage: String? = null,
    ) {
        val selectedProfile = mutableState.value.selectedProfile
            .takeIf { selected -> loaded.profiles.any { it.name == selected } }
            ?: loaded.profiles.firstOrNull(DashboardProfile::isDefault)?.name
            ?: loaded.profiles.firstOrNull()?.name
            ?: "default"
        mutableState.value = mutableState.value.copy(
            connectionPhase = ConnectionPhase.Connected,
            savedAuthMode = currentDescriptor?.authMode,
            sessions = projectSessionPage(
                page = loaded.sessionPage,
                origin = normalizeOrigin(mutableState.value.probe?.baseUrl.orEmpty()),
                profile = "all",
                previous = emptyList(),
            ),
            profiles = loaded.profiles,
            selectedProfile = selectedProfile,
            activeSummary = null,
            messages = emptyList(),
            password = password,
            sessionToken = sessionToken,
            loadingMessage = null,
            errorMessage = errorMessage ?: loaded.sessionPage.errors
                .takeIf { it.isNotEmpty() }
                ?.let { "Some Hermes profiles could not refresh." },
        )
        sessionListScopeProfile = "all"
        startSessionPollingIfNeeded()
    }

    private fun manualState(
        descriptor: SavedConnectionDescriptor? = null,
        phase: ConnectionPhase = ConnectionPhase.ManualSetup,
        probe: DashboardProbeResult? = null,
        errorMessage: String? = null,
    ): CelesteUiState = CelesteUiState(
        connectionPhase = phase,
        dashboardUrl = descriptor?.baseUrl.orEmpty(),
        probe = probe,
        savedAuthMode = descriptor?.authMode,
        username = descriptor?.username.orEmpty(),
        errorMessage = errorMessage,
    )

    private fun beginConnectionAttempt(): Long {
        connectionAttempt += 1
        connectionJob?.cancel()
        connectionJob = null
        beginSessionContextChange()
        return connectionAttempt
    }

    private fun isCurrentConnectionAttempt(attempt: Long): Boolean = connectionAttempt == attempt

    private fun beginSessionContextChange() {
        sessionContextGeneration += 1
        sessionRefreshGeneration += 1
        sessionRefreshJob?.cancel()
        sessionRefreshJob = null
        sessionInvalidationJob?.cancel()
        sessionInvalidationJob = null
        sessionPollJob?.cancel()
        sessionPollJob = null
        sessionListOrdering = SessionOrdering.SERVER_ORDER
        pendingLocalActivity.clear()
    }

    fun openSession(summary: StoredSession) {
        val connection = mutableState.value.probe ?: return
        val activeCredential = credential ?: return
        conversationsVisible = false
        closeGateway()
        currentSessionCanResume = true
        mutableState.value = mutableState.value.copy(
            activeSummary = summary,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            turnState = TurnState.Synchronizing,
            loadingMessage = "Opening ${summary.title.ifBlank { "conversation" }}…",
            errorMessage = null,
        )

        val newGateway = dashboard.createGateway(connection.baseUrl, activeCredential)
        gateway = newGateway
        observeGateway(newGateway)
        viewModelScope.launch {
            runCatching {
                newGateway.connect()
                reconcile(newGateway, summary.id)
            }.onSuccess {
                reconnectAttempts = 0
                mutableState.value = mutableState.value.copy(loadingMessage = null)
                scheduleSessionRefresh(profile = sessionListScopeProfile)
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    loadingMessage = null,
                    errorMessage = error.message ?: "Could not open that Hermes conversation.",
                    turnState = TurnState.Reconnecting,
                )
                scheduleReconnect(wasRunning = false)
            }
        }
    }

    fun createNewConversation() {
        val snapshot = mutableState.value
        val connection = snapshot.probe ?: return
        val activeCredential = credential ?: return
        val selectedProfile = snapshot.selectedProfile
        conversationsVisible = false
        closeGateway()
        mutableState.value = snapshot.copy(
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            turnState = TurnState.Synchronizing,
            loadingMessage = "Starting a new $selectedProfile conversation…",
            errorMessage = null,
        )

        val newGateway = dashboard.createGateway(connection.baseUrl, activeCredential)
        gateway = newGateway
        observeGateway(newGateway)
        viewModelScope.launch {
            runCatching {
                newGateway.connect()
                reconciling = true
                bufferedEvents.clear()
                val created = newGateway.createSession(selectedProfile)
                if (gateway !== newGateway) throw IOException("The Hermes connection changed while creating the conversation.")
                val returnedProfile = created.profile?.takeIf(String::isNotBlank)
                if (returnedProfile != null && !returnedProfile.equals(selectedProfile, ignoreCase = true)) {
                    throw IOException("Hermes created this conversation in $returnedProfile instead of $selectedProfile.")
                }
                currentRuntimeSessionId = created.runtimeSessionId
                currentStoredSessionId = created.storedSessionId
                currentSessionCanResume = false
                val summary = StoredSession(
                    id = created.storedSessionId,
                    title = "New conversation",
                    preview = "",
                    startedAt = 0.0,
                    messageCount = 0,
                    source = "android",
                    profile = selectedProfile,
                    lastActive = null,
                )
                val events = bufferedEvents.toList()
                bufferedEvents.clear()
                reconciling = false
                val visibleSessions = mutableState.value.sessions.orEmpty().let { sessions ->
                    if (sessionListScopeProfile.equals("all", ignoreCase = true)) {
                        sessions
                    } else {
                        sessions.filter { session ->
                            normalizedSessionProfile(session.profile).equals(
                                normalizedSessionProfile(sessionListScopeProfile),
                                ignoreCase = true,
                            )
                        }
                    }
                }
                mutableState.value = mutableState.value.copy(
                    sessions = deduplicateSessions(
                        listOf(summary) + visibleSessions.filterNot { session ->
                            session.id == summary.id &&
                                normalizedSessionProfile(session.profile).equals(
                                    normalizedSessionProfile(summary.profile),
                                    ignoreCase = true,
                                )
                        },
                        origin = normalizeOrigin(connection.baseUrl),
                    ),
                    activeSummary = summary,
                    turnState = TurnState.Idle,
                    loadingMessage = null,
                    errorMessage = null,
                )
                events.forEach(::applyEvent)
                scheduleSessionRefresh(profile = sessionListScopeProfile)
            }.onSuccess {
                reconnectAttempts = 0
            }.onFailure { error ->
                if (gateway === newGateway) closeGateway()
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Idle,
                    loadingMessage = null,
                    errorMessage = error.message ?: "Could not create a Hermes conversation.",
                )
            }
        }
    }

    fun leaveConversation() {
        closeGateway()
        conversationsVisible = true
        mutableState.value = mutableState.value.copy(
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            turnState = TurnState.Idle,
            loadingMessage = null,
            errorMessage = null,
        )
        scheduleSessionRefresh(profile = sessionListScopeProfile)
        startSessionPollingIfNeeded()
    }

    fun sendMessage() {
        val activeGateway = gateway ?: return
        val snapshot = mutableState.value
        val runtimeId = currentRuntimeSessionId ?: return
        val storedId = currentStoredSessionId ?: snapshot.activeSummary?.id ?: return
        val text = snapshot.draft.trim()
        if (text.isBlank() || snapshot.turnState != TurnState.Idle) return

        val localId = "local-${localMessageCounter.incrementAndGet()}"
        mutableState.value = snapshot.copy(
            messages = snapshot.messages + ConversationMessage(
                role = "user",
                text = text,
                id = localId,
                pending = true,
            ),
            streamingText = "",
            draft = "",
            turnState = TurnState.Running,
            errorMessage = null,
        )
        // prompt.submit creates the durable row before work begins. From this point on,
        // uncertain delivery must reconcile by stored ID and must never create/resend.
        currentSessionCanResume = true
        val operation = markLocalActivity(storedId)
        val operationContextGeneration = sessionContextGeneration
        val operationConversationGeneration = conversationGeneration
        viewModelScope.launch {
            runCatching { activeGateway.submitPrompt(runtimeId, text) }
                .onSuccess { response ->
                    if (!isCurrentPromptContext(
                            activeGateway,
                            storedId,
                            operationContextGeneration,
                            operationConversationGeneration,
                        )
                    ) {
                        scheduleSessionRefresh(profile = sessionListScopeProfile)
                        return@onSuccess
                    }
                    val rejected = response.string("status")?.lowercase() in setOf(
                        "rejected",
                        "failed",
                        "error",
                    )
                    if (rejected) {
                        clearLocalActivity(operation)
                    }
                    mutableState.value = mutableState.value.copy(
                        messages = mutableState.value.messages.mapNotNull { message ->
                            if (message.id != localId) {
                                message
                            } else if (rejected) {
                                null
                            } else {
                                message.copy(pending = false)
                            }
                        },
                        turnState = if (rejected) TurnState.Idle else mutableState.value.turnState,
                        errorMessage = if (rejected) {
                            response.string("error") ?: "Hermes rejected that message."
                        } else {
                            mutableState.value.errorMessage
                        },
                    )
                    scheduleSessionRefresh(profile = sessionListScopeProfile)
                }
                .onFailure { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) {
                        throw error
                    }
                    if (!isCurrentPromptContext(
                            activeGateway,
                            storedId,
                            operationContextGeneration,
                            operationConversationGeneration,
                        )
                    ) {
                        if (isDefinitivePromptFailure(error)) clearLocalActivity(operation)
                        return@onFailure
                    }
                    val definitive = isDefinitivePromptFailure(error)
                    if (definitive) {
                        clearLocalActivity(operation)
                    } else {
                        markLocalActivityUncertain(operation)
                    }
                    mutableState.value = mutableState.value.copy(
                        messages = if (definitive) {
                            mutableState.value.messages.filterNot { it.id == localId }
                        } else {
                            mutableState.value.messages
                        },
                        turnState = if (definitive) TurnState.Idle else mutableState.value.turnState,
                        errorMessage = error.message ?: "Hermes could not send that message.",
                    )
                    scheduleSessionRefresh(profile = sessionListScopeProfile)
                    if (!definitive && gateway === activeGateway) {
                        runCatching { reconcile(activeGateway, currentStoredSessionId ?: storedId) }
                    }
                }
        }
    }

    fun interrupt() {
        val activeGateway = gateway ?: return
        val runtimeId = currentRuntimeSessionId ?: return
        if (mutableState.value.turnState != TurnState.Running) return
        mutableState.value = mutableState.value.copy(
            turnState = TurnState.Synchronizing,
            errorMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                activeGateway.interruptSession(runtimeId)
                reconcile(activeGateway, currentStoredSessionId ?: return@launch)
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    errorMessage = error.message ?: "Hermes could not stop that turn.",
                )
            }
        }
    }

    fun reconnectNow() {
        if (gateway == null || mutableState.value.activeSummary == null) return
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        scheduleReconnect(wasRunning = mutableState.value.turnState == TurnState.Running, immediate = true)
    }

    fun onBackground() {
        conversationsVisible = false
        sessionPollJob?.cancel()
        sessionPollJob = null
        val descriptor = currentDescriptor ?: return
        if (descriptor.authMode != SavedAuthMode.ProviderSession) return
        if (credential != GatewayCredential.CookieSession) return
        val refreshed = dashboard.exportAuthentication(descriptor.baseUrl) ?: return
        val attempt = connectionAttempt
        viewModelScope.launch {
            connectionStoreMutex.withLock {
                if (!isCurrentConnectionAttempt(attempt)) return@withLock
                if (credential != GatewayCredential.CookieSession || currentDescriptor != descriptor) return@withLock
                runCatching {
                    connectionStore.replace(descriptor, ReusableSecret(refreshed.value))
                }
            }
        }
    }

    fun onForeground() {
        if (mutableState.value.sessions != null && mutableState.value.activeSummary == null) {
            conversationsVisible = true
            scheduleSessionRefresh(profile = sessionListScopeProfile)
            startSessionPollingIfNeeded()
            return
        }
        conversationsVisible = false
        val activeGateway = gateway ?: return
        val storedSessionId = currentStoredSessionId ?: return
        if (foregroundCheckJob?.isActive == true || reconciling) return
        if (activeGateway.state.value != GatewayConnectionState.Connected) {
            reconnectNow()
            return
        }
        foregroundCheckJob = viewModelScope.launch {
            val refresh = runCatching {
                refreshSessionListNow(profile = sessionListScopeProfile)
                activeGateway.request(
                    method = "session.list",
                    params = buildJsonObject { put("limit", 1) },
                    timeoutMillis = 8_000,
                )
                if (currentSessionCanResume && gateway === activeGateway) {
                    reconcile(activeGateway, storedSessionId)
                }
            }
            if (refresh.isFailure && gateway === activeGateway) {
                val wasRunning = mutableState.value.turnState == TurnState.Running
                activeGateway.close()
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Reconnecting,
                    errorMessage = refresh.exceptionOrNull()?.message ?: "Reconnecting to Hermes…",
                )
                scheduleReconnect(wasRunning = wasRunning, immediate = true)
            }
            foregroundCheckJob = null
        }
    }

    private var currentRuntimeSessionId: String? = null
    private var currentStoredSessionId: String? = null

    private fun observeGateway(activeGateway: GatewayConnection) {
        gatewayEventsJob = viewModelScope.launch {
            activeGateway.events.collect { event ->
                if (gateway !== activeGateway) return@collect
                if (event.type == "sessions.changed") {
                    scheduleSessionRefresh(profile = sessionListScopeProfile, debounce = true)
                    return@collect
                }
                if (reconciling) {
                    bufferedEvents += event
                } else {
                    applyEvent(event)
                }
            }
        }
        gatewayStateJob = viewModelScope.launch {
            activeGateway.state.collect { connectionState ->
                if (gateway !== activeGateway) return@collect
                if (connectionState is GatewayConnectionState.Disconnected) {
                    val wasRunning = mutableState.value.turnState == TurnState.Running
                    mutableState.value = mutableState.value.copy(
                        turnState = TurnState.Reconnecting,
                        errorMessage = connectionState.reason,
                    )
                    scheduleReconnect(wasRunning)
                }
            }
        }
    }

    private suspend fun reconcile(activeGateway: GatewayConnection, storedSessionId: String) {
        reconciling = true
        bufferedEvents.clear()
        try {
            val resumed = activeGateway.resumeStoredSession(storedSessionId)
            if (gateway !== activeGateway) return
            applyResumedSession(resumed)
            val events = bufferedEvents.toList()
            bufferedEvents.clear()
            reconciling = false
            events.forEach(::applyEvent)
        } catch (error: Throwable) {
            bufferedEvents.clear()
            reconciling = false
            throw error
        }
    }

    private fun applyResumedSession(resumed: ResumedSession) {
        currentRuntimeSessionId = resumed.runtimeSessionId
        currentStoredSessionId = resumed.storedSessionId
        currentSessionCanResume = true
        val streamingSuffix = unpersistedInflightText(
            inflight = resumed.inflightAssistantText,
            messages = resumed.messages,
        )
        mutableState.value = mutableState.value.copy(
            messages = resumed.messages,
            streamingText = streamingSuffix,
            turnState = if (resumed.running == true || resumed.hasLiveProjection) {
                TurnState.Running
            } else {
                TurnState.Idle
            },
            errorMessage = null,
        )
    }

    private fun applyEvent(event: GatewayEvent) {
        val runtimeId = currentRuntimeSessionId ?: return
        if (event.sessionId.isNotBlank() && event.sessionId != runtimeId) return
        when (event.type) {
            "message.start" -> {
                if (mutableState.value.streamingText.isNotBlank()) finalizeAssistant()
                mutableState.value = mutableState.value.copy(
                    streamingText = "",
                    turnState = TurnState.Running,
                    errorMessage = null,
                )
            }

            "message.delta" -> {
                val delta = event.payload.string("text").orEmpty()
                if (delta.isNotEmpty()) {
                    mutableState.value = mutableState.value.copy(
                        streamingText = mutableState.value.streamingText + delta,
                        turnState = TurnState.Running,
                    )
                }
            }

            "message.interim" -> {
                val text = event.payload.string("text").orEmpty()
                val alreadyStreamed = event.payload.boolean("already_streamed") == true
                if (alreadyStreamed && mutableState.value.streamingText.isNotBlank()) {
                    finalizeAssistant(
                        text.ifBlank { mutableState.value.streamingText },
                        keepRunning = true,
                        interim = true,
                    )
                } else if (text.isNotBlank()) {
                    finalizeAssistant(text, keepRunning = true, interim = true)
                }
            }

            "message.complete" -> {
                val status = event.payload.string("status")
                val content = event.payload.string("text")
                    ?: event.payload.string("content")
                    ?: event.payload.string("rendered")
                    ?: ""
                finalizeAssistant(content, keepRunning = false)
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Idle,
                    errorMessage = if (status == "error") {
                        event.payload.string("error") ?: "Hermes could not finish that response."
                    } else {
                        mutableState.value.errorMessage
                    },
                )
            }

            "error", "message.error" -> {
                finalizeAssistant(keepRunning = false)
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Idle,
                    errorMessage = event.payload.string("message") ?: "Hermes reported an error.",
                )
            }

            "message.interrupted", "session.interrupted" -> {
                finalizeAssistant(keepRunning = false)
                mutableState.value = mutableState.value.copy(turnState = TurnState.Idle)
            }

            "session.busy" -> {
                mutableState.value = mutableState.value.copy(
                    turnState = if (event.payload.boolean("busy") == true) TurnState.Running else TurnState.Idle,
                )
            }

            "session.info" -> {
                event.payload.boolean("running")?.let { running ->
                    mutableState.value = mutableState.value.copy(
                        turnState = if (running) TurnState.Running else TurnState.Idle,
                    )
                }
            }

            "tool.start", "tool_call" -> {
                if (mutableState.value.streamingText.isNotBlank()) finalizeAssistant(keepRunning = true)
                val name = event.payload.string("name") ?: "Tool"
                val input = event.payload.string("args_text")
                    ?: event.payload.string("context")
                    ?: event.payload["args"]?.toString().orEmpty()
                mutableState.value = mutableState.value.copy(
                    messages = mutableState.value.messages + ConversationMessage(
                        role = "tool",
                        text = input,
                        toolName = name,
                        id = "tool-${localMessageCounter.incrementAndGet()}",
                        pending = true,
                    ),
                    turnState = TurnState.Running,
                )
            }

            "tool.complete", "tool_result" -> {
                val name = event.payload.string("name") ?: "Tool"
                val output = event.payload.string("output")
                    ?: event.payload["result"]?.toString().orEmpty()
                val messages = mutableState.value.messages.toMutableList()
                val index = messages.indexOfLast {
                    it.role == "tool" && it.toolName == name && it.pending
                }
                if (index >= 0) {
                    messages[index] = messages[index].copy(text = output, pending = false)
                } else {
                    messages += ConversationMessage(role = "tool", text = output, toolName = name)
                }
                mutableState.value = mutableState.value.copy(messages = messages)
            }
        }
    }

    private fun finalizeAssistant(
        suppliedContent: String = "",
        keepRunning: Boolean = mutableState.value.turnState == TurnState.Running,
        interim: Boolean = false,
    ) {
        val streamed = mutableState.value.streamingText
        val finalText = when {
            suppliedContent.isBlank() -> streamed
            streamed.isBlank() -> suppliedContent
            suppliedContent.startsWith(streamed) -> suppliedContent
            streamed.startsWith(suppliedContent) -> streamed
            else -> suppliedContent
        }.trimEnd()
        val currentMessages = mutableState.value.messages
        val previous = currentMessages.lastOrNull()
        val continuesInterim = !interim &&
            previous?.role == "assistant" &&
            previous.interim &&
            finalText.isNotBlank() &&
            (finalText.startsWith(previous.text) || previous.text.startsWith(finalText))
        val messages = when {
            continuesInterim -> currentMessages.dropLast(1) + previous.copy(
                text = if (finalText.length >= previous.text.length) finalText else previous.text,
                interim = false,
            )
            finalText.isNotBlank() && previous?.let { it.role == "assistant" && it.text == finalText } != true ->
                currentMessages + ConversationMessage(
                    role = "assistant",
                    text = finalText,
                    interim = interim,
                )
            else -> currentMessages
        }
        mutableState.value = mutableState.value.copy(
            messages = messages,
            streamingText = "",
            turnState = if (keepRunning) TurnState.Running else TurnState.Idle,
        )
    }

    private fun projectSessionPage(
        page: SessionListPage,
        origin: String,
        profile: String,
        previous: List<StoredSession>,
    ): List<StoredSession> {
        sessionListOrdering = page.ordering
        val retained = mutableSetOf<SessionIdentity>()
        val synthetic = mutableState.value.activeSummary
            ?.takeIf { it.startedAt <= 0.0 && effectiveRemoteActivity(it) == null }
        if (synthetic != null) retained += sessionIdentity(origin, synthetic)
        val reconciliation = reconcileSessionRows(
            previous = previous,
            page = page,
            origin = origin,
            profileScope = profile,
            overlays = pendingLocalActivity,
            retainedIdentities = retained,
        )
        reconciliation.overlaysConfirmed.forEach { identity ->
            pendingLocalActivity.remove(identity)
        }
        return orderSessions(
            sessions = reconciliation.sessions,
            origin = origin,
            ordering = page.ordering,
            overlays = pendingLocalActivity,
        )
    }

    private fun scheduleSessionRefresh(
        profile: String = sessionListScopeProfile,
        debounce: Boolean = false,
    ) {
        if (mutableState.value.probe == null || credential == null || mutableState.value.sessions == null) return
        sessionInvalidationJob?.cancel()
        sessionInvalidationJob = null
        if (debounce) {
            sessionInvalidationJob = viewModelScope.launch {
                delay(SESSION_CHANGED_DEBOUNCE_MILLIS)
                sessionInvalidationJob = null
                scheduleSessionRefresh(profile = profile)
            }
            return
        }
        if (sessionRefreshJob?.isActive == true) return
        sessionRefreshJob = viewModelScope.launch {
            refreshSessionListNow(profile)
        }
    }

    private suspend fun refreshSessionListNow(profile: String): Boolean {
        val snapshot = mutableState.value
        val probe = snapshot.probe ?: return false
        val activeCredential = credential ?: return false
        val origin = normalizeOrigin(probe.baseUrl)
        val requestedProfile = profile.trim().ifEmpty { "all" }
        val requestGeneration = ++sessionRefreshGeneration
        val contextGeneration = sessionContextGeneration
        val attempt = connectionAttempt
        val result = runCatching {
            dashboard.listSessionPage(
                baseUrl = probe.baseUrl,
                credential = activeCredential,
                profile = requestedProfile,
                limit = SESSION_PAGE_LIMIT,
                offset = 0,
            )
        }
        val page = result.getOrNull()
        val current = mutableState.value
        val stillCurrent = requestGeneration == sessionRefreshGeneration &&
            contextGeneration == sessionContextGeneration &&
            attempt == connectionAttempt &&
            normalizeOrigin(current.probe?.baseUrl.orEmpty()) == origin &&
            sessionListScopeProfile.equals(requestedProfile, ignoreCase = true)
        if (!stillCurrent) return false
        if (page == null) {
            val error = result.exceptionOrNull()
            if (error is AuthenticationRejected) {
                invalidateReusableAuthentication(currentDescriptor, current.probe)
            } else {
                mutableState.value = current.copy(
                    errorMessage = error?.message ?: "Could not refresh Hermes conversations.",
                )
            }
            return false
        }

        val projected = projectSessionPage(
            page = page,
            origin = origin,
            profile = requestedProfile,
            previous = current.sessions.orEmpty(),
        )
        val active = current.activeSummary
        val updatedActive = active?.let { summary ->
            projected.firstOrNull {
                it.id == summary.id &&
                    normalizedSessionProfile(it.profile).equals(
                        normalizedSessionProfile(summary.profile),
                        ignoreCase = true,
                    )
            } ?: summary
        }
        mutableState.value = current.copy(
            sessions = projected,
            activeSummary = updatedActive,
            loadingMessage = null,
            errorMessage = if (page.errors.isNotEmpty()) {
                "Some Hermes profiles could not refresh."
            } else if (current.activeSummary == null) {
                null
            } else {
                current.errorMessage
            },
        )
        startSessionPollingIfNeeded()
        return true
    }

    private fun startSessionPollingIfNeeded() {
        val snapshot = mutableState.value
        if (!conversationsVisible || snapshot.sessions == null || snapshot.activeSummary != null || credential == null) return
        if (gateway?.supportsSessionChangeEvents == true) return
        if (sessionPollJob?.isActive == true) return
        sessionPollJob = viewModelScope.launch {
            while (mutableState.value.sessions != null && mutableState.value.activeSummary == null) {
                delay(SESSION_POLL_MILLIS)
                if (mutableState.value.sessions != null && mutableState.value.activeSummary == null) {
                    scheduleSessionRefresh(profile = sessionListScopeProfile)
                }
            }
        }
    }

    private fun markLocalActivity(storedSessionId: String): LocalActivityOperation? {
        val snapshot = mutableState.value
        val origin = normalizeOrigin(snapshot.probe?.baseUrl.orEmpty())
        val summary = snapshot.activeSummary
        val row = snapshot.sessions.orEmpty().firstOrNull {
            it.id == storedSessionId &&
                (summary == null || normalizedSessionProfile(it.profile).equals(
                    normalizedSessionProfile(summary.profile),
                    ignoreCase = true,
                ))
        } ?: summary?.takeIf { it.id == storedSessionId }
        val identity = row?.let { sessionIdentity(origin, it) }
            ?: SessionIdentity(
                origin = origin,
                profile = normalizedSessionProfile(summary?.profile.orEmpty()),
                storedSessionId = storedSessionId,
            )
        val currentRemote = row?.let(::effectiveRemoteActivity)
            ?: summary?.let(::effectiveRemoteActivity)
        val existing = pendingLocalActivity[identity]
        val injected = validEpochSeconds(clockSeconds())
        val bump = listOfNotNull(currentRemote, existing?.bumpSeconds, injected).maxOrNull()
            ?: return null
        sessionOperationCounter += 1
        val operation = LocalActivityOperation(
            identity = identity,
            operationId = sessionOperationCounter,
            contextGeneration = sessionContextGeneration,
        )
        pendingLocalActivity[identity] = PendingLocalActivity(
            bumpSeconds = bump,
            operationId = operation.operationId,
            delivery = LocalActivityDelivery.PENDING,
            contextGeneration = operation.contextGeneration,
        )
        while (pendingLocalActivity.size > MAX_PENDING_LOCAL_ACTIVITY) {
            val oldest = pendingLocalActivity.keys.firstOrNull() ?: break
            if (oldest == identity && pendingLocalActivity.size == 1) break
            pendingLocalActivity.remove(oldest)
        }
        mutableState.value = snapshot.copy(
            sessions = orderSessions(
                sessions = snapshot.sessions.orEmpty(),
                origin = origin,
                ordering = sessionListOrdering,
                overlays = pendingLocalActivity,
            ),
        )
        return operation
    }

    private fun clearLocalActivity(operation: LocalActivityOperation?) {
        if (operation == null) return
        val pending = pendingLocalActivity[operation.identity] ?: return
        if (pending.operationId != operation.operationId ||
            pending.contextGeneration != operation.contextGeneration
        ) {
            return
        }
        pendingLocalActivity.remove(operation.identity)
        val snapshot = mutableState.value
        mutableState.value = snapshot.copy(
            sessions = orderSessions(
                sessions = snapshot.sessions.orEmpty(),
                origin = normalizeOrigin(snapshot.probe?.baseUrl.orEmpty()),
                ordering = sessionListOrdering,
                overlays = pendingLocalActivity,
            ),
        )
    }

    private fun markLocalActivityUncertain(operation: LocalActivityOperation?) {
        if (operation == null) return
        val pending = pendingLocalActivity[operation.identity] ?: return
        if (pending.operationId != operation.operationId ||
            pending.contextGeneration != operation.contextGeneration
        ) {
            return
        }
        pendingLocalActivity[operation.identity] = pending.copy(delivery = LocalActivityDelivery.UNCERTAIN)
    }

    private fun isCurrentPromptContext(
        activeGateway: GatewayConnection,
        storedSessionId: String,
        contextGeneration: Long,
        conversationGeneration: Long,
    ): Boolean =
        gateway === activeGateway &&
            sessionContextGeneration == contextGeneration &&
            this.conversationGeneration == conversationGeneration &&
            currentStoredSessionId == storedSessionId &&
            mutableState.value.activeSummary?.id == storedSessionId

    private fun isDefinitivePromptFailure(error: Throwable): Boolean =
        error is GatewayRpcException ||
            error is DashboardRpcException ||
            error is AuthenticationRejected

    private fun normalizeOrigin(baseUrl: String): String =
        runCatching { DashboardUrlPolicy.normalize(baseUrl) }
            .getOrElse { baseUrl.trim().trimEnd('/') }

    private suspend fun recreateBlankSession(
        activeGateway: GatewayConnection,
        profile: String,
    ) {
        val previousStoredId = currentStoredSessionId
        reconciling = true
        bufferedEvents.clear()
        try {
            val created = activeGateway.createSession(profile)
            if (gateway !== activeGateway) return
            currentRuntimeSessionId = created.runtimeSessionId
            currentStoredSessionId = created.storedSessionId
            val previousSummary = mutableState.value.activeSummary
                ?: throw IOException("No draft conversation is open.")
            val updatedSummary = previousSummary.copy(id = created.storedSessionId, profile = profile)
            val replaced = mutableState.value.sessions?.map { session ->
                if (session.id == previousStoredId &&
                    normalizedSessionProfile(session.profile).equals(
                        normalizedSessionProfile(previousSummary.profile),
                        ignoreCase = true,
                    )
                ) {
                    updatedSummary
                } else {
                    session
                }
            }
            mutableState.value = mutableState.value.copy(
                activeSummary = updatedSummary,
                sessions = replaced,
                turnState = TurnState.Idle,
                errorMessage = null,
            )
            val events = bufferedEvents.toList()
            bufferedEvents.clear()
            reconciling = false
            events.forEach(::applyEvent)
            scheduleSessionRefresh(profile = sessionListScopeProfile)
        } catch (error: Throwable) {
            bufferedEvents.clear()
            reconciling = false
            throw error
        }
    }

    private fun scheduleReconnect(wasRunning: Boolean, immediate: Boolean = false) {
        val activeGateway = gateway ?: return
        val storedSessionId = currentStoredSessionId ?: mutableState.value.activeSummary?.id ?: return
        if (reconnectJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(turnState = TurnState.Reconnecting)
        reconnectJob = viewModelScope.launch {
            while (gateway === activeGateway) {
                val delayMillis = if (immediate && reconnectAttempts == 0) {
                    0L
                } else {
                    reconnectDelayMillis(reconnectAttempts, wasRunning)
                }
                if (delayMillis > 0) delay(delayMillis)
                val result = runCatching {
                    activeGateway.connect()
                    if (currentSessionCanResume) {
                        reconcile(activeGateway, storedSessionId)
                    } else {
                        recreateBlankSession(activeGateway, mutableState.value.selectedProfile)
                    }
                }
                if (result.isSuccess) {
                    reconnectAttempts = 0
                    reconnectJob = null
                    scheduleSessionRefresh(profile = sessionListScopeProfile)
                    return@launch
                }
                val failure = result.exceptionOrNull()
                if (failure is AuthenticationRejected) {
                    val descriptor = currentDescriptor
                    reconnectJob = null
                    closeGateway()
                    invalidateReusableAuthentication(descriptor)
                    return@launch
                }
                reconnectAttempts += 1
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Reconnecting,
                    errorMessage = failure?.message ?: "Reconnecting to Hermes…",
                )
            }
            reconnectJob = null
        }
    }

    private fun closeGateway() {
        conversationGeneration += 1
        val activeGateway = gateway
        gateway = null
        reconnectJob?.cancel()
        reconnectJob = null
        sessionPollJob?.cancel()
        sessionPollJob = null
        foregroundCheckJob?.cancel()
        foregroundCheckJob = null
        gatewayEventsJob?.cancel()
        gatewayEventsJob = null
        gatewayStateJob?.cancel()
        gatewayStateJob = null
        reconciling = false
        bufferedEvents.clear()
        currentRuntimeSessionId = null
        currentStoredSessionId = null
        currentSessionCanResume = true
        activeGateway?.close()
    }

    override fun onCleared() {
        connectionJob?.cancel()
        connectionJob = null
        closeGateway()
        pendingLocalActivity.clear()
        dashboard.clearAuthentication()
        super.onCleared()
    }

    companion object {
        private const val SESSION_PAGE_LIMIT = 200
        private const val MAX_PENDING_LOCAL_ACTIVITY = SESSION_PAGE_LIMIT
        private const val SESSION_CHANGED_DEBOUNCE_MILLIS = 500L
        private const val SESSION_POLL_MILLIS = 30_000L

        internal fun unpersistedInflightText(
            inflight: String,
            messages: List<ConversationMessage>,
        ): String {
            val recovered = inflight.trim()
            if (recovered.isEmpty()) return ""
            val persisted = messages.lastOrNull {
                it.role == "assistant" && it.text.isNotBlank()
            }?.text?.trim().orEmpty()
            return if (persisted.isNotEmpty() && recovered.startsWith(persisted)) {
                recovered.removePrefix(persisted).trimStart()
            } else {
                recovered
            }
        }
    }
}
