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
import dev.hazydreams.hermesceleste.network.DashboardService
import dev.hazydreams.hermesceleste.network.DashboardUrlPolicy
import dev.hazydreams.hermesceleste.network.DisplayedDetail
import dev.hazydreams.hermesceleste.network.GatewayConnection
import dev.hazydreams.hermesceleste.network.GatewayConnectionState
import dev.hazydreams.hermesceleste.network.GatewayCredential
import dev.hazydreams.hermesceleste.network.ActivityBinding
import dev.hazydreams.hermesceleste.network.ActivityCapabilityState
import dev.hazydreams.hermesceleste.network.ActivityDisclosurePreferenceStore
import dev.hazydreams.hermesceleste.network.ActivityDisclosureScope
import dev.hazydreams.hermesceleste.network.AgentActivityProjection
import dev.hazydreams.hermesceleste.network.AgentActivityReducer
import dev.hazydreams.hermesceleste.network.InMemoryActivityDisclosurePreferenceStore
import dev.hazydreams.hermesceleste.network.GatewayEvent
import dev.hazydreams.hermesceleste.network.ServerReasoningActivity
import dev.hazydreams.hermesceleste.network.initialActivityProjection
import dev.hazydreams.hermesceleste.network.normalizeActivityOrigin
import dev.hazydreams.hermesceleste.network.ResumedSession
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.network.boolean
import dev.hazydreams.hermesceleste.network.createSession
import dev.hazydreams.hermesceleste.network.sanitizeActivityText
import dev.hazydreams.hermesceleste.network.interruptSession
import dev.hazydreams.hermesceleste.network.resumeStoredSession
import dev.hazydreams.hermesceleste.network.string
import dev.hazydreams.hermesceleste.network.submitPrompt
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    val agentActivity: AgentActivityProjection? = null,
    val agentActivityReasoningDisclosureEnabled: Boolean = true,
    val streamingText: String = "",
    val draft: String = "",
    val turnState: TurnState = TurnState.Idle,
    val loadingMessage: String? = null,
    val errorMessage: String? = null,
) {
    /** Compatibility alias for callers that name the projection explicitly. */
    val activityProjection: AgentActivityProjection? get() = agentActivity
}

private data class LoadedDashboard(
    val credential: GatewayCredential,
    val sessions: List<StoredSession>,
    val profiles: List<DashboardProfile>,
)

private data class RememberedDashboard(
    val loaded: LoadedDashboard,
    val descriptor: SavedConnectionDescriptor,
    val persistenceError: Throwable?,
)

private data class SessionContextGuard(
    val gateway: GatewayConnection,
    val gatewayGeneration: Long,
    val sessionGeneration: Long,
    val storedSessionId: String?,
)

private data class ReconciliationGuard(
    val epoch: Long,
    val context: SessionContextGuard,
)

private class ReconciliationBatch(
    var guard: ReconciliationGuard,
) {
    val events = mutableListOf<GatewayEvent>()
}

private data class PendingReasoningDelta(
    val event: GatewayEvent,
    val context: SessionContextGuard,
    val reconciliationGuard: ReconciliationGuard?,
)

internal class CelesteViewModel(
    private val dashboard: DashboardService = DashboardClient(),
    private val connectionStore: ConnectionStore = InMemoryConnectionStore(),
    private val reconnectDelayMillis: (attempt: Int, wasRunning: Boolean) -> Long = { attempt, wasRunning ->
        if (wasRunning && attempt == 0) 100L else min(5_000L, 1_000L shl attempt.coerceAtMost(2))
    },
    private val activityDisclosurePreferences: ActivityDisclosurePreferenceStore =
        InMemoryActivityDisclosurePreferenceStore(),
    private val activityDiscoveryTimeoutMillis: Long = 1_000L,
    private val reasoningCoalescingWindowMillis: Long = 75L,
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
    private var connectionJob: Job? = null
    private var activityDiscoveryJob: Job? = null
    private var reasoningCoalescingJob: Job? = null
    private var pendingReasoningDeltas = mutableListOf<PendingReasoningDelta>()
    private var connectionAttempt = 0L
    private val connectionStoreMutex = Mutex()
    private val reconciliationMutex = Mutex()
    private var reconciliationEpoch = 0L
    private var gatewayGeneration = 0L
    private var sessionGeneration = 0L
    private var activeReconciliationBatch: ReconciliationBatch? = null
    private var currentDescriptor: SavedConnectionDescriptor? = null
    private var reconnectAttempts = 0
    private var currentSessionCanResume = true
    private var activityReasoningDisclosureEnabled = runCatching {
        activityDisclosurePreferences.isServerReasoningDisclosureEnabled()
    }.getOrDefault(true)
    private var activityDisclosureScope: ActivityDisclosureScope? = null

    init {
        mutableState.value = mutableState.value.copy(
            agentActivityReasoningDisclosureEnabled = activityReasoningDisclosureEnabled,
        )
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

    /**
     * Device-local disclosure only. It never requests a new server payload and
     * disabling it drops the in-memory reasoning projection rather than writing
     * private text to preferences or a local transcript.
     */
    fun setActivityReasoningDisclosureEnabled(enabled: Boolean) {
        activityReasoningDisclosureEnabled = enabled
        runCatching {
            activityDisclosureScope?.let { scope ->
                activityDisclosurePreferences.setServerReasoningDisclosureEnabled(scope, enabled)
            } ?: activityDisclosurePreferences.setServerReasoningDisclosureEnabled(enabled)
        }
        val activity = mutableState.value.agentActivity
        mutableState.value = mutableState.value.copy(
            agentActivityReasoningDisclosureEnabled = enabled,
            agentActivity = if (enabled || activity == null) {
                activity
            } else {
                AgentActivityReducer.withoutServerReasoning(activity)
            },
        )
    }

    fun selectProfile(name: String) {
        if (mutableState.value.profiles.none { it.name == name }) return
        mutableState.value = mutableState.value.copy(selectedProfile = name)
    }

    fun findDashboard() {
        val rawUrl = mutableState.value.dashboardUrl
        if (rawUrl.isBlank()) return
        val attempt = beginConnectionAttempt()
        closeGateway()
        clearActivityDisclosureScope()
        credential = null
        currentDescriptor = null
        dashboard.clearAuthentication()
        mutableState.value = mutableState.value.copy(
            connectionPhase = ConnectionPhase.ManualSetup,
            sessions = null,
            activeSummary = null,
            messages = emptyList(),
            agentActivity = null,
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
        clearActivityDisclosureScope()
        credential = null
        mutableState.value = mutableState.value.copy(
            connectionPhase = ConnectionPhase.ManualSetup,
            sessions = null,
            activeSummary = null,
            messages = emptyList(),
            agentActivity = null,
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
        clearActivityDisclosureScope()
        credential = null
        currentDescriptor = null
        dashboard.clearAuthentication()
        mutableState.value = CelesteUiState(
            connectionPhase = ConnectionPhase.ManualSetup,
            agentActivityReasoningDisclosureEnabled = activityReasoningDisclosureEnabled,
        )
    }

    fun signOut() {
        val snapshot = mutableState.value
        val activeCredential = credential
        val attempt = beginConnectionAttempt()
        closeGateway()
        clearActivityDisclosureScope()
        credential = null
        currentDescriptor = null
        mutableState.value = snapshot.copy(
            connectionPhase = ConnectionPhase.ManualSetup,
            sessions = null,
            activeSummary = null,
            messages = emptyList(),
            agentActivity = null,
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
        clearActivityDisclosureScope()
        credential = null
        currentDescriptor = null
        mutableState.value = CelesteUiState(
            connectionPhase = ConnectionPhase.ManualSetup,
            agentActivityReasoningDisclosureEnabled = activityReasoningDisclosureEnabled,
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
                agentActivityReasoningDisclosureEnabled = activityReasoningDisclosureEnabled,
                errorMessage = if (error == null) null else {
                    "Celeste could not remove the saved connection. Try again."
                },
            )
        }
    }

    private fun restoreSavedConnection() {
        val attempt = beginConnectionAttempt()
        closeGateway()
        clearActivityDisclosureScope()
        credential = null
        mutableState.value = CelesteUiState(
            connectionPhase = ConnectionPhase.CheckingSavedConnection,
            agentActivityReasoningDisclosureEnabled = activityReasoningDisclosureEnabled,
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
            agentActivityReasoningDisclosureEnabled = activityReasoningDisclosureEnabled,
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
                    agentActivityReasoningDisclosureEnabled = activityReasoningDisclosureEnabled,
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
        val sessions = dashboard.listSessions(baseUrl, selectedCredential)
        val profiles = dashboard.listProfiles(baseUrl, selectedCredential)
        return LoadedDashboard(selectedCredential, sessions, profiles)
    }

    private suspend fun invalidateReusableAuthentication(
        descriptor: SavedConnectionDescriptor?,
        probe: DashboardProbeResult? = null,
    ) {
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
            sessions = loaded.sessions,
            profiles = loaded.profiles,
            selectedProfile = selectedProfile,
            activeSummary = null,
            messages = emptyList(),
            agentActivity = null,
            password = password,
            sessionToken = sessionToken,
            loadingMessage = null,
            errorMessage = errorMessage,
        )
    }

    private fun manualState(
        descriptor: SavedConnectionDescriptor? = null,
        phase: ConnectionPhase = ConnectionPhase.ManualSetup,
        probe: DashboardProbeResult? = null,
        errorMessage: String? = null,
    ): CelesteUiState = CelesteUiState(
        connectionPhase = phase,
        agentActivityReasoningDisclosureEnabled = activityReasoningDisclosureEnabled,
        dashboardUrl = descriptor?.baseUrl.orEmpty(),
        probe = probe,
        savedAuthMode = descriptor?.authMode,
        username = descriptor?.username.orEmpty(),
        errorMessage = errorMessage,
    )

    private fun useActivityDisclosureScope(scope: ActivityDisclosureScope?) {
        activityDisclosureScope = scope
        activityReasoningDisclosureEnabled = runCatching {
            scope?.let { activityDisclosurePreferences.isServerReasoningDisclosureEnabled(it) }
                ?: activityDisclosurePreferences.isServerReasoningDisclosureEnabled()
        }.getOrDefault(true)
        mutableState.value = mutableState.value.copy(
            agentActivityReasoningDisclosureEnabled = activityReasoningDisclosureEnabled,
        )
    }

    private fun clearActivityDisclosureScope() {
        useActivityDisclosureScope(null)
    }

    private fun activityDisclosureScope(
        originKey: String,
        profile: String,
        storedSessionId: String,
    ): ActivityDisclosureScope = ActivityDisclosureScope(
        originKey = normalizeActivityOrigin(originKey),
        profile = profile,
        storedSessionId = storedSessionId,
    )

    private fun beginConnectionAttempt(): Long {
        connectionAttempt += 1
        connectionJob?.cancel()
        connectionJob = null
        return connectionAttempt
    }

    private fun isCurrentConnectionAttempt(attempt: Long): Boolean = connectionAttempt == attempt

    private fun installGateway(activeGateway: GatewayConnection) {
        invalidateReconciliationEpoch()
        gatewayGeneration += 1
        sessionGeneration += 1
        gateway = activeGateway
    }

    private fun currentSessionContext(): SessionContextGuard? {
        val activeGateway = gateway ?: return null
        return SessionContextGuard(
            gateway = activeGateway,
            gatewayGeneration = gatewayGeneration,
            sessionGeneration = sessionGeneration,
            storedSessionId = currentStoredSessionId?.trim()?.takeIf(String::isNotBlank),
        )
    }

    private fun currentSessionContext(
        activeGateway: GatewayConnection,
        storedSessionId: String?,
    ): SessionContextGuard = SessionContextGuard(
        gateway = activeGateway,
        gatewayGeneration = gatewayGeneration,
        sessionGeneration = sessionGeneration,
        storedSessionId = storedSessionId?.trim()?.takeIf(String::isNotBlank),
    )

    private fun isCurrent(context: SessionContextGuard): Boolean =
        gateway === context.gateway &&
            gatewayGeneration == context.gatewayGeneration &&
            sessionGeneration == context.sessionGeneration &&
            currentStoredSessionId?.trim()?.takeIf(String::isNotBlank) == context.storedSessionId

    private fun isActiveSession(activeGateway: GatewayConnection, storedSessionId: String): Boolean =
        gateway === activeGateway &&
            currentStoredSessionId?.trim() == storedSessionId.trim()

    private fun beginReconciliationGuard(
        activeGateway: GatewayConnection,
        storedSessionId: String?,
    ): ReconciliationGuard? {
        val normalizedStoredId = storedSessionId?.trim()?.takeIf(String::isNotBlank)
        val currentStoredId = currentStoredSessionId?.trim()?.takeIf(String::isNotBlank)
        if (gateway !== activeGateway || currentStoredId != normalizedStoredId) return null
        val context = currentSessionContext(activeGateway, normalizedStoredId)
        return ReconciliationGuard(
            epoch = ++reconciliationEpoch,
            context = context,
        )
    }

    private fun isCurrent(guard: ReconciliationGuard): Boolean =
        reconciliationEpoch == guard.epoch && isCurrent(guard.context)

    private suspend fun <T> withReconciliationBatch(
        activeGateway: GatewayConnection,
        storedSessionId: String?,
        block: suspend (ReconciliationBatch) -> T,
    ): T? = reconciliationMutex.withLock {
        val guard = beginReconciliationGuard(activeGateway, storedSessionId)
            ?: return@withLock null
        reasoningCoalescingJob?.cancel()
        reasoningCoalescingJob = null
        pendingReasoningDeltas.clear()
        activityDiscoveryJob?.cancel()
        activityDiscoveryJob = null
        val batch = ReconciliationBatch(guard)
        activeReconciliationBatch = batch
        try {
            block(batch)
        } finally {
            if (activeReconciliationBatch === batch) {
                activeReconciliationBatch = null
            }
        }
    }

    private fun invalidateReconciliationEpoch() {
        reconciliationEpoch += 1
        activeReconciliationBatch = null
        reasoningCoalescingJob?.cancel()
        reasoningCoalescingJob = null
        pendingReasoningDeltas.clear()
    }

    fun openSession(summary: StoredSession) {
        val connection = mutableState.value.probe ?: return
        val activeCredential = credential ?: return
        closeGateway()
        currentRuntimeSessionId = null
        currentStoredSessionId = summary.id
        currentSessionCanResume = true
        val profile = summary.profile.ifBlank { mutableState.value.selectedProfile }
        useActivityDisclosureScope(
            activityDisclosureScope(connection.baseUrl, profile, summary.id),
        )
        val initialActivity = initialActivityProjection(
            originKey = connection.baseUrl,
            profile = profile,
            storedSessionId = summary.id,
            capability = connection.activityCapability,
        )
        mutableState.value = mutableState.value.copy(
            activeSummary = summary,
            messages = emptyList(),
            agentActivity = initialActivity,
            streamingText = "",
            draft = "",
            turnState = TurnState.Synchronizing,
            loadingMessage = "Opening ${summary.title.ifBlank { "conversation" }}…",
            errorMessage = null,
        )

        val newGateway = dashboard.createGateway(connection.baseUrl, activeCredential)
        installGateway(newGateway)
        observeGateway(newGateway)
        val operationContext = currentSessionContext(newGateway, summary.id)
        viewModelScope.launch {
            runCatching {
                newGateway.connect()
                if (!isCurrent(operationContext)) return@runCatching
                reconcile(newGateway, summary.id)
            }.onSuccess {
                if (!isCurrent(operationContext)) return@onSuccess
                reconnectAttempts = 0
                mutableState.value = mutableState.value.copy(loadingMessage = null)
            }.onFailure { error ->
                if (!isCurrent(operationContext)) return@onFailure
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
        closeGateway()
        mutableState.value = snapshot.copy(
            activeSummary = null,
            messages = emptyList(),
            agentActivity = null,
            streamingText = "",
            draft = "",
            turnState = TurnState.Synchronizing,
            loadingMessage = "Starting a new $selectedProfile conversation…",
            errorMessage = null,
        )

        val newGateway = dashboard.createGateway(connection.baseUrl, activeCredential)
        installGateway(newGateway)
        observeGateway(newGateway)
        val operationContext = currentSessionContext(newGateway, null)
        var createdStoredSessionId: String? = null
        viewModelScope.launch {
            runCatching {
                newGateway.connect()
                if (!isCurrent(operationContext)) return@runCatching
                withReconciliationBatch(newGateway, null) { batch ->
                    val created = newGateway.createSession(selectedProfile)
                    if (!isCurrent(batch.guard)) return@withReconciliationBatch
                    val returnedProfile = created.profile?.takeIf(String::isNotBlank)
                    if (returnedProfile != null && !returnedProfile.equals(selectedProfile, ignoreCase = true)) {
                        throw IOException("Hermes created this conversation in $returnedProfile instead of $selectedProfile.")
                    }
                    val activityProfile = returnedProfile ?: selectedProfile
                    currentRuntimeSessionId = created.runtimeSessionId
                    currentStoredSessionId = created.storedSessionId
                    currentSessionCanResume = false
                    sessionGeneration += 1
                    reconciliationEpoch += 1
                    batch.guard = ReconciliationGuard(
                        epoch = reconciliationEpoch,
                        context = currentSessionContext(newGateway, created.storedSessionId),
                    )
                    createdStoredSessionId = created.storedSessionId
                    val summary = StoredSession(
                        id = created.storedSessionId,
                        title = "New conversation",
                        preview = "",
                        startedAt = 0.0,
                        messageCount = 0,
                        source = "android",
                        profile = activityProfile,
                    )
                    useActivityDisclosureScope(
                        activityDisclosureScope(connection.baseUrl, activityProfile, created.storedSessionId),
                    )
                    mutableState.value = mutableState.value.copy(
                        sessions = listOf(summary) + mutableState.value.sessions.orEmpty()
                            .filterNot { it.id == summary.id },
                        activeSummary = summary,
                        agentActivity = initialActivityProjection(
                            originKey = connection.baseUrl,
                            profile = activityProfile,
                            storedSessionId = created.storedSessionId,
                            runtimeSessionId = created.runtimeSessionId,
                            capability = connection.activityCapability,
                        ),
                        turnState = TurnState.Idle,
                        loadingMessage = null,
                        errorMessage = null,
                    )
                    replayBufferedEvents(batch)
                    if (isCurrent(batch.guard)) {
                        scheduleActivityDiscoveryFallback()
                    }
                }
            }.onSuccess {
                if (gateway === newGateway && currentStoredSessionId == createdStoredSessionId) {
                    reconnectAttempts = 0
                }
            }.onFailure { error ->
                if (gateway !== newGateway || !isCurrent(operationContext)) return@onFailure
                closeGateway()
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
        clearActivityDisclosureScope()
        mutableState.value = mutableState.value.copy(
            activeSummary = null,
            messages = emptyList(),
            agentActivity = null,
            streamingText = "",
            draft = "",
            turnState = TurnState.Idle,
            loadingMessage = null,
            errorMessage = null,
        )
    }

    fun sendMessage() {
        val activeGateway = gateway ?: return
        val snapshot = mutableState.value
        val runtimeId = currentRuntimeSessionId ?: return
        val storedSessionId = currentStoredSessionId ?: return
        val operationContext = currentSessionContext(activeGateway, storedSessionId)
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
        viewModelScope.launch {
            runCatching {
                if (!isCurrent(operationContext)) return@runCatching
                activeGateway.submitPrompt(runtimeId, text)
            }
                .onSuccess {
                    if (!isCurrent(operationContext)) return@onSuccess
                    mutableState.value = mutableState.value.copy(
                        messages = mutableState.value.messages.map { message ->
                            if (message.id == localId) message.copy(pending = false) else message
                        },
                    )
                }
                .onFailure { error ->
                    if (!isCurrent(operationContext)) return@onFailure
                    mutableState.value = mutableState.value.copy(
                        errorMessage = error.message ?: "Hermes could not send that message.",
                    )
                    runCatching { reconcile(activeGateway, storedSessionId) }
                }
        }
    }

    fun interrupt() {
        val activeGateway = gateway ?: return
        val runtimeId = currentRuntimeSessionId ?: return
        val storedSessionId = currentStoredSessionId ?: return
        val operationContext = currentSessionContext(activeGateway, storedSessionId)
        if (mutableState.value.turnState != TurnState.Running) return
        mutableState.value = mutableState.value.copy(
            turnState = TurnState.Synchronizing,
            errorMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                if (!isCurrent(operationContext)) return@runCatching
                activeGateway.interruptSession(runtimeId)
                reconcile(activeGateway, storedSessionId)
            }.onFailure { error ->
                if (!isCurrent(operationContext)) return@onFailure
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
        val activeGateway = gateway ?: return
        val storedSessionId = currentStoredSessionId ?: return
        val operationContext = currentSessionContext(activeGateway, storedSessionId)
        if (foregroundCheckJob?.isActive == true) return
        if (activeGateway.state.value != GatewayConnectionState.Connected) {
            reconnectNow()
            return
        }
        foregroundCheckJob = viewModelScope.launch {
            val health = runCatching {
                if (!isCurrent(operationContext)) return@runCatching
                activeGateway.request(
                    method = "session.list",
                    params = buildJsonObject { put("limit", 1) },
                    timeoutMillis = 8_000,
                )
                if (currentSessionCanResume && isCurrent(operationContext)) {
                    reconcile(activeGateway, storedSessionId)
                }
            }
            if (health.isFailure && isCurrent(operationContext)) {
                val wasRunning = mutableState.value.turnState == TurnState.Running
                invalidateReconciliationEpoch()
                activeGateway.close()
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Reconnecting,
                    agentActivity = mutableState.value.agentActivity?.let(AgentActivityReducer::markStale),
                    errorMessage = health.exceptionOrNull()?.message ?: "Reconnecting to Hermes…",
                )
                scheduleReconnect(wasRunning = wasRunning, immediate = true)
            }
            foregroundCheckJob = null
        }
    }

    private var currentRuntimeSessionId: String? = null
    private var currentStoredSessionId: String? = null

    private fun observeGateway(activeGateway: GatewayConnection) {
        val observedGatewayGeneration = gatewayGeneration
        gatewayEventsJob = viewModelScope.launch {
            activeGateway.events.collect { event ->
                if (gateway !== activeGateway || gatewayGeneration != observedGatewayGeneration) return@collect
                val batch = activeReconciliationBatch
                if (batch != null) {
                    if (isCurrent(batch.guard)) {
                        batch.events += event
                    }
                    return@collect
                }
                applyEvent(event)
            }
        }
        gatewayStateJob = viewModelScope.launch {
            activeGateway.state.collect { connectionState ->
                if (gateway !== activeGateway || gatewayGeneration != observedGatewayGeneration) return@collect
                if (connectionState is GatewayConnectionState.Disconnected) {
                    invalidateReconciliationEpoch()
                    val wasRunning = mutableState.value.turnState == TurnState.Running
                    mutableState.value = mutableState.value.copy(
                        turnState = TurnState.Reconnecting,
                        agentActivity = mutableState.value.agentActivity?.let(AgentActivityReducer::markStale),
                        errorMessage = connectionState.reason,
                    )
                    scheduleReconnect(wasRunning)
                }
            }
        }
    }

    private suspend fun reconcile(activeGateway: GatewayConnection, storedSessionId: String) {
        var guard: ReconciliationGuard? = null
        try {
            withReconciliationBatch(activeGateway, storedSessionId) { batch ->
                guard = batch.guard
                if (!isCurrent(batch.guard)) return@withReconciliationBatch
                mutableState.value = mutableState.value.copy(
                    agentActivity = mutableState.value.agentActivity?.let(AgentActivityReducer::markRestoring),
                )
                val activity = mutableState.value.agentActivity
                val resumed = activeGateway.resumeStoredSession(
                    storedSessionId = storedSessionId,
                    profile = activity?.profile
                        ?: mutableState.value.activeSummary?.profile
                        ?: mutableState.value.selectedProfile,
                    originKey = activity?.originKey
                        ?: mutableState.value.probe?.baseUrl,
                )
                if (!isCurrent(batch.guard)) return@withReconciliationBatch
                applyResumedSession(resumed)
                if (!isCurrent(batch.guard)) return@withReconciliationBatch
                replayBufferedEvents(batch)
                if (!isCurrent(batch.guard)) return@withReconciliationBatch
                scheduleActivityDiscoveryFallback()
            }
        } catch (error: CancellationException) {
            if (guard?.let(::isCurrent) == true) {
                mutableState.value = mutableState.value.copy(
                    agentActivity = mutableState.value.agentActivity?.let(AgentActivityReducer::markStale),
                )
            }
            throw error
        } catch (error: Throwable) {
            if (guard?.let(::isCurrent) == true) {
                mutableState.value = mutableState.value.copy(
                    agentActivity = mutableState.value.agentActivity?.let(AgentActivityReducer::markStale),
                )
            }
            throw error
        }
    }

    private suspend fun replayBufferedEvents(batch: ReconciliationBatch) {
        var index = 0
        while (index < batch.events.size) {
            if (!isCurrent(batch.guard)) return
            applyEvent(batch.events[index], batch.guard)
            index += 1
        }
    }

    private fun applyResumedSession(resumed: ResumedSession) {
        val currentActivity = mutableState.value.agentActivity
        val expectedStoredId = currentStoredSessionId
            ?: mutableState.value.activeSummary?.id
            ?: resumed.storedSessionId
        if (resumed.storedSessionId.trim() != expectedStoredId.trim()) {
            throw IOException("Hermes returned activity for a different stored conversation.")
        }
        if (currentActivity != null) {
            if (
                resumed.originKey != null &&
                normalizeActivityOrigin(resumed.originKey) != currentActivity.originKey
            ) {
                throw IOException("Hermes returned activity for a different dashboard origin.")
            }
            if (
                resumed.profile != null &&
                resumed.profile.trim().isNotBlank() &&
                resumed.profile.trim() != currentActivity.profile
            ) {
                throw IOException("Hermes returned activity for a different profile.")
            }
        }
        currentRuntimeSessionId = resumed.runtimeSessionId.trim()
        currentStoredSessionId = resumed.storedSessionId.trim()
        currentSessionCanResume = true
        val streamingSuffix = unpersistedInflightText(
            inflight = resumed.inflightAssistantText,
            messages = resumed.messages,
        )
        val activity = currentActivity?.let { projection ->
            val effectiveServerReasoningAllowed = resumed.serverReasoningAllowed
                ?: projection.serverReasoningAllowed
            val snapshotItems = resumed.activityItems.ifEmpty {
                dev.hazydreams.hermesceleste.network.activityItemsFromMessages(resumed.messages)
            }.filter { item ->
                (activityReasoningDisclosureEnabled && effectiveServerReasoningAllowed != false) ||
                    item !is ServerReasoningActivity
            }
            AgentActivityReducer.applySnapshot(
                projection = projection,
                items = snapshotItems,
                binding = ActivityBinding(
                    originKey = projection.originKey,
                    profile = projection.profile,
                    storedSessionId = resumed.storedSessionId,
                    runtimeSessionId = resumed.runtimeSessionId,
                ),
                running = resumed.running == true || resumed.hasLiveProjection,
                serverReasoningAllowed = effectiveServerReasoningAllowed,
            )
        }
        mutableState.value = mutableState.value.copy(
            messages = resumed.messages,
            agentActivity = activity,
            streamingText = streamingSuffix,
            turnState = if (resumed.running == true || resumed.hasLiveProjection) {
                TurnState.Running
            } else {
                TurnState.Idle
            },
            errorMessage = null,
        )
    }

    private fun scheduleActivityDiscoveryFallback() {
        activityDiscoveryJob?.cancel()
        val activity = mutableState.value.agentActivity ?: return
        if (activity.presentation != dev.hazydreams.hermesceleste.network.ActivityPresentationState.Discovering) {
            return
        }
        val expectedOrigin = activity.originKey
        val expectedProfile = activity.profile
        val expectedStoredSession = activity.storedSessionId
        val expectedRuntime = activity.runtimeSessionId
        val expectedContext = currentSessionContext() ?: return
        activityDiscoveryJob = viewModelScope.launch {
            delay(activityDiscoveryTimeoutMillis.coerceAtLeast(0L))
            val current = mutableState.value.agentActivity
            if (
                isCurrent(expectedContext) &&
                current != null &&
                current.presentation == dev.hazydreams.hermesceleste.network.ActivityPresentationState.Discovering &&
                current.originKey == expectedOrigin &&
                current.profile == expectedProfile &&
                current.storedSessionId == expectedStoredSession &&
                current.runtimeSessionId == expectedRuntime
            ) {
                val resolved = withContext(Dispatchers.Default) {
                    AgentActivityReducer.markAbsent(current)
                }
                mutableState.value = mutableState.value.copy(agentActivity = resolved)
            }
            activityDiscoveryJob = null
        }
    }

    private suspend fun applyEvent(
        event: GatewayEvent,
        reconciliationGuard: ReconciliationGuard? = null,
    ) {
        val context = reconciliationGuard?.context ?: currentSessionContext() ?: return
        if (reconciliationGuard != null && !isCurrent(reconciliationGuard)) return
        if (event.type == "reasoning.delta" && event.payload.boolean("verbose") == true) {
            pendingReasoningDeltas += PendingReasoningDelta(
                event = event,
                context = context,
                reconciliationGuard = reconciliationGuard,
            )
            if (reasoningCoalescingJob?.isActive != true) {
                reasoningCoalescingJob = viewModelScope.launch {
                    delay(reasoningCoalescingWindowMillis.coerceAtLeast(0L))
                    flushReasoningDeltas()
                }
            }
            return
        }
        flushReasoningDeltas()
        applyEventNow(event, context, reconciliationGuard)
    }

    private suspend fun applyEventNow(
        event: GatewayEvent,
        context: SessionContextGuard,
        reconciliationGuard: ReconciliationGuard?,
    ) {
        if (!isCurrent(context)) return
        if (reconciliationGuard != null && !isCurrent(reconciliationGuard)) return
        val runtimeId = currentRuntimeSessionId?.trim()?.takeIf(String::isNotBlank) ?: return
        val eventSessionId = event.sessionId.trim()
        if (eventSessionId.isNotBlank() && eventSessionId != runtimeId) return
        val projection = mutableState.value.agentActivity
        if (projection != null) {
            val updated = withContext(Dispatchers.Default) {
                AgentActivityReducer.applyEvent(
                    projection = projection,
                    event = event,
                    reasoningEnabled = activityReasoningDisclosureEnabled,
                )
            }
            if (!isCurrent(context)) return
            if (reconciliationGuard != null && !isCurrent(reconciliationGuard)) return
            if (updated !== projection) {
                mutableState.value = mutableState.value.copy(agentActivity = updated)
                if (updated.presentation != dev.hazydreams.hermesceleste.network.ActivityPresentationState.Discovering) {
                    activityDiscoveryJob?.cancel()
                    activityDiscoveryJob = null
                }
            }
        }
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
                val status = event.payload.string("status")?.lowercase()
                val failureReason = event.payload.string("failure_reason")
                    ?.takeIf(String::isNotBlank)
                val explicitError = event.payload.string("error")
                    ?.takeIf(String::isNotBlank)
                val failed = status in setOf("error", "failed", "failure") ||
                    failureReason != null ||
                    explicitError != null
                val content = event.payload.string("text")
                    ?: event.payload.string("content")
                    ?: event.payload.string("rendered")
                    ?: ""
                finalizeAssistant(content, keepRunning = false)
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Idle,
                    errorMessage = if (failed) {
                        sanitizeActivityText(
                            failureReason ?: explicitError ?: "Hermes could not finish that response.",
                            240,
                        )
                    } else {
                        mutableState.value.errorMessage
                    },
                )
            }

            "error", "message.error" -> {
                finalizeAssistant(keepRunning = false)
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Idle,
                    errorMessage = (
                        event.payload.string("failure_reason")
                            ?: event.payload.string("error")
                            ?: event.payload.string("message")
                    )?.let { sanitizeActivityText(it, 240) }
                        ?: "Hermes reported an error.",
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
                mutableState.value = mutableState.value.copy(turnState = TurnState.Running)
            }

            "tool.complete", "tool_result" -> {
                // Tool completion is activity state, not an assistant transcript row.
                // Keep the turn running because Hermes may execute another tool or
                // continue streaming assistant content after this event.
                mutableState.value = mutableState.value.copy(turnState = TurnState.Running)
            }
        }
    }

    private suspend fun flushReasoningDeltas() {
        reasoningCoalescingJob = null
        val pending = pendingReasoningDeltas.toList()
        pendingReasoningDeltas.clear()
        val events = pending.filter { item ->
            isCurrent(item.context) &&
                (item.reconciliationGuard == null || isCurrent(item.reconciliationGuard))
        }
        if (events.isEmpty()) return

        val first = events.first()
        val sameContext = events.all { item ->
            item.context == first.context && item.reconciliationGuard == first.reconciliationGuard
        }
        val sameBinding = events.all { item ->
            val event = item.event
            val firstEvent = first.event
            event.sessionId == firstEvent.sessionId &&
                event.originKey == firstEvent.originKey &&
                event.profile == firstEvent.profile &&
                event.storedSessionId == firstEvent.storedSessionId
        }
        val hasExplicitReplayIdentity = events.any { item ->
            val event = item.event
            event.eventId?.isNotBlank() == true ||
                event.payload.keys.any {
                    it == "event_id" || it == "eventId" || it == "event_seq" || it == "seq"
                }
        }
        if (events.size == 1 || !sameContext || !sameBinding || hasExplicitReplayIdentity) {
            events.forEach { item ->
                applyEventNow(item.event, item.context, item.reconciliationGuard)
            }
            return
        }

        val mergedText = mergeReasoningDeltaText(events.map(PendingReasoningDelta::event))
        if (mergedText.isBlank()) return
        val representative = events.last()
        val combinedPayload = buildJsonObject {
            representative.event.payload.forEach { (key, value) -> put(key, value) }
            put("text", mergedText)
            put("verbose", true)
        }
        applyEventNow(
            representative.event.copy(payload = combinedPayload, eventId = null),
            representative.context,
            representative.reconciliationGuard,
        )
    }

    private fun mergeReasoningDeltaText(events: List<GatewayEvent>): String {
        var merged = ""
        events.forEach { event ->
            val text = event.payload.string("text").orEmpty()
            if (text.isBlank()) return@forEach
            merged = when {
                merged.isBlank() -> text
                text.startsWith(merged) -> text
                merged.startsWith(text) || merged.endsWith(text) -> merged
                else -> merged + text
            }
        }
        return merged
    }

    private fun finalizeAssistant(
        suppliedContent: String = "",
        keepRunning: Boolean = mutableState.value.turnState == TurnState.Running,
        interim: Boolean = false,
    ) {
        val streamed = mutableState.value.streamingText
        val rawFinalText = when {
            suppliedContent.isBlank() -> streamed
            streamed.isBlank() -> suppliedContent
            suppliedContent.startsWith(streamed) -> suppliedContent
            streamed.startsWith(suppliedContent) -> streamed
            else -> suppliedContent
        }.trimEnd()
        // Hermes can include the same explicitly disclosed reasoning summary in
        // the assistant's final content. Keep the labelled activity item, but do
        // not render that summary a second time as ordinary assistant text.
        val finalText = if (interim) {
            rawFinalText
        } else {
            deduplicateReasoningFromFinalContent(rawFinalText)
        }
        val currentMessages = mutableState.value.messages
        val previous = currentMessages.lastOrNull()
        val previousTextForMerge = previous
            ?.takeIf { it.role == "assistant" && it.interim }
            ?.let { deduplicateReasoningFromFinalContent(it.text) }
            ?: previous?.text
        val continuesInterim = !interim &&
            previous?.role == "assistant" &&
            previous.interim &&
            finalText.isNotBlank() &&
            (finalText.startsWith(previousTextForMerge.orEmpty()) ||
                previousTextForMerge.orEmpty().startsWith(finalText))
        val messages = when {
            !interim && previous?.let(::isReasoningOnlyInterim) == true && finalText.isBlank() ->
                currentMessages.dropLast(1)
            continuesInterim -> currentMessages.dropLast(1) + previous.copy(
                text = if (finalText.length >= previousTextForMerge.orEmpty().length) {
                    finalText
                } else {
                    previousTextForMerge.orEmpty()
                },
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

    private fun deduplicateReasoningFromFinalContent(raw: String): String {
        var content = raw
        val reasoningDetails = mutableState.value.agentActivity?.items
            .filterIsInstance<ServerReasoningActivity>()
            .map(ServerReasoningActivity::text)
            .filter { it.text.isNotBlank() }
            .distinctBy { it.text }
            .sortedByDescending { it.text.length }
        reasoningDetails.forEach { detail ->
            content = removeDisclosedReasoning(content, detail)
        }
        return content.trimEnd()
    }

    private fun removeDisclosedReasoning(content: String, detail: DisplayedDetail): String {
        var result = removeExactReasoningOccurrences(content, detail.text.trim())
        if (!detail.wasTruncated) return result

        // The projection intentionally retains only a bounded prefix. If the
        // final assistant field repeats the full disclosed body, use the safe
        // prefix plus its original code-point count to remove that occurrence
        // without retaining the undisplayed tail locally.
        val marker = " … truncated (original length: ${detail.originalLength} chars)"
        val prefix = detail.text.removeSuffix(marker)
        if (prefix.isBlank()) return result
        var start = result.indexOf(prefix)
        while (start >= 0) {
            val end = runCatching {
                result.offsetByCodePoints(start, detail.originalLength)
            }.getOrNull()
            if (end == null) break
            result = joinAfterReasoningRemoval(result, start, end)
            start = result.indexOf(prefix)
        }
        return result
    }

    private fun removeExactReasoningOccurrences(content: String, reasoning: String): String {
        if (reasoning.isBlank()) return content
        var result = content
        var start = result.indexOf(reasoning)
        while (start >= 0) {
            result = joinAfterReasoningRemoval(result, start, start + reasoning.length)
            start = result.indexOf(reasoning)
        }
        return result
    }

    private fun joinAfterReasoningRemoval(content: String, start: Int, end: Int): String {
        val before = content.substring(0, start)
        val after = content.substring(end)
        val hasLineBreak = before.takeLastWhile(Char::isWhitespace).any { it == '\n' || it == '\r' } ||
            after.takeWhile(Char::isWhitespace).any { it == '\n' || it == '\r' }
        val cleanBefore = before.trimEnd()
        val cleanAfter = after.trimStart()
        val separator = if (cleanBefore.isNotEmpty() && cleanAfter.isNotEmpty()) {
            if (hasLineBreak) "\n" else " "
        } else {
            ""
        }
        return cleanBefore + separator + cleanAfter
    }

    private fun isReasoningOnlyInterim(message: ConversationMessage): Boolean {
        if (message.role != "assistant" || !message.interim) return false
        return deduplicateReasoningFromFinalContent(message.text).isBlank()
    }

    private suspend fun recreateBlankSession(
        activeGateway: GatewayConnection,
        profile: String,
    ) {
        val previousStoredId = currentStoredSessionId
        var guard: ReconciliationGuard? = null
        try {
            withReconciliationBatch(activeGateway, previousStoredId) { batch ->
                guard = batch.guard
                val created = activeGateway.createSession(profile)
                if (!isCurrent(batch.guard)) return@withReconciliationBatch
                val previousSummary = mutableState.value.activeSummary
                    ?: throw IOException("No draft conversation is open.")
                val previousActivity = mutableState.value.agentActivity
                    ?: throw IOException("No activity projection is open.")
                val activityProfile = created.profile?.takeIf(String::isNotBlank) ?: profile
                currentRuntimeSessionId = created.runtimeSessionId
                currentStoredSessionId = created.storedSessionId
                currentSessionCanResume = false
                sessionGeneration += 1
                reconciliationEpoch += 1
                batch.guard = ReconciliationGuard(
                    epoch = reconciliationEpoch,
                    context = currentSessionContext(activeGateway, created.storedSessionId),
                )
                val updatedSummary = previousSummary.copy(id = created.storedSessionId, profile = activityProfile)
                useActivityDisclosureScope(
                    activityDisclosureScope(previousActivity.originKey, activityProfile, created.storedSessionId),
                )
                val updatedActivity = initialActivityProjection(
                    originKey = previousActivity.originKey,
                    profile = activityProfile,
                    storedSessionId = created.storedSessionId,
                    runtimeSessionId = created.runtimeSessionId,
                    capability = mutableState.value.probe?.activityCapability
                        ?: ActivityCapabilityState.Unknown,
                )
                mutableState.value = mutableState.value.copy(
                    activeSummary = updatedSummary,
                    agentActivity = updatedActivity,
                    sessions = mutableState.value.sessions?.map { session ->
                        if (session.id == previousStoredId) updatedSummary else session
                    },
                    turnState = TurnState.Idle,
                    errorMessage = null,
                )
                replayBufferedEvents(batch)
                if (isCurrent(batch.guard)) {
                    scheduleActivityDiscoveryFallback()
                }
            }
        } catch (error: CancellationException) {
            if (guard?.let(::isCurrent) == true) throw error
            throw error
        } catch (error: Throwable) {
            throw error
        }
    }

    private fun scheduleReconnect(wasRunning: Boolean, immediate: Boolean = false) {
        val activeGateway = gateway ?: return
        val storedSessionId = currentStoredSessionId ?: mutableState.value.activeSummary?.id ?: return
        val reconnectGatewayGeneration = gatewayGeneration
        val reconnectSessionGeneration = sessionGeneration
        if (reconnectJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(turnState = TurnState.Reconnecting)
        reconnectJob = viewModelScope.launch {
            while (
                isActiveSession(activeGateway, storedSessionId) &&
                gatewayGeneration == reconnectGatewayGeneration &&
                sessionGeneration == reconnectSessionGeneration
            ) {
                val delayMillis = if (immediate && reconnectAttempts == 0) {
                    0L
                } else {
                    reconnectDelayMillis(reconnectAttempts, wasRunning)
                }
                if (delayMillis > 0) delay(delayMillis)
                if (
                    !isActiveSession(activeGateway, storedSessionId) ||
                    gatewayGeneration != reconnectGatewayGeneration ||
                    sessionGeneration != reconnectSessionGeneration
                ) break
                val result = runCatching {
                    activeGateway.connect()
                    if (
                        !isActiveSession(activeGateway, storedSessionId) ||
                        gatewayGeneration != reconnectGatewayGeneration ||
                        sessionGeneration != reconnectSessionGeneration
                    ) return@runCatching
                    if (currentSessionCanResume) {
                        reconcile(activeGateway, storedSessionId)
                    } else {
                        recreateBlankSession(activeGateway, mutableState.value.selectedProfile)
                    }
                }
                if (result.isSuccess) {
                    reconnectAttempts = 0
                    reconnectJob = null
                    return@launch
                }
                if (
                    !isActiveSession(activeGateway, storedSessionId) ||
                    gatewayGeneration != reconnectGatewayGeneration ||
                    sessionGeneration != reconnectSessionGeneration
                ) break
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
        val activeGateway = gateway
        invalidateReconciliationEpoch()
        gatewayGeneration += 1
        sessionGeneration += 1
        gateway = null
        reconnectJob?.cancel()
        reconnectJob = null
        activityDiscoveryJob?.cancel()
        activityDiscoveryJob = null
        foregroundCheckJob?.cancel()
        foregroundCheckJob = null
        gatewayEventsJob?.cancel()
        gatewayEventsJob = null
        gatewayStateJob?.cancel()
        gatewayStateJob = null
        currentRuntimeSessionId = null
        currentStoredSessionId = null
        currentSessionCanResume = true
        activeGateway?.close()
    }

    override fun onCleared() {
        connectionJob?.cancel()
        connectionJob = null
        closeGateway()
        dashboard.clearAuthentication()
        super.onCleared()
    }

    companion object {
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
