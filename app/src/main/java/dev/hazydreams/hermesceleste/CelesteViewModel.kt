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
import dev.hazydreams.hermesceleste.network.GatewayConnection
import dev.hazydreams.hermesceleste.network.GatewayConnectionState
import dev.hazydreams.hermesceleste.network.GatewayCredential
import dev.hazydreams.hermesceleste.network.GatewayEvent
import dev.hazydreams.hermesceleste.network.ResumedSession
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.network.boolean
import dev.hazydreams.hermesceleste.network.createSession
import dev.hazydreams.hermesceleste.network.interruptSession
import dev.hazydreams.hermesceleste.network.resumeStoredSession
import dev.hazydreams.hermesceleste.network.string
import dev.hazydreams.hermesceleste.network.submitPrompt
import dev.hazydreams.hermesceleste.network.toolCallIdentity
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    val activityCandidates: ConversationActivityCandidates = ConversationActivityCandidates(),
    val conversationActionModel: ConversationActionModel = ConversationActionModel(),
    val loadingMessage: String? = null,
    val errorMessage: String? = null,
)

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

internal class CelesteViewModel(
    private val dashboard: DashboardService = DashboardClient(),
    private val connectionStore: ConnectionStore = InMemoryConnectionStore(),
    private val reconnectDelayMillis: (attempt: Int, wasRunning: Boolean) -> Long = { attempt, wasRunning ->
        if (wasRunning && attempt == 0) 100L else min(5_000L, 1_000L shl attempt.coerceAtMost(2))
    },
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
    private var conversationOperationsJob: Job? = null
    private var connectionJob: Job? = null
    private var connectionAttempt = 0L
    private var conversationGeneration = 0L
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
        mutableState.value = mutableState.value.copy(selectedProfile = name)
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
            activityCandidates = ConversationActivityCandidates(),
            draft = "",
            loadingMessage = "Finding Hermes…",
            errorMessage = null,
        )
        connectionJob = viewModelScope.launch {
            runCatchingCancellable { dashboard.probe(rawUrl) }
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
                        errorMessage = sanitizeFailure(error, "Could not reach the Hermes dashboard."),
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
            runCatchingCancellable {
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
                    runCatchingCancellable {
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
                    errorMessage = sanitizeFailure(error, "Could not load Hermes conversations."),
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
            activityCandidates = ConversationActivityCandidates(),
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
            activityCandidates = ConversationActivityCandidates(),
            draft = "",
            password = "",
            sessionToken = "",
            loadingMessage = "Signing out…",
            errorMessage = null,
        )
        connectionJob = viewModelScope.launch {
            val (error, saved) = connectionStoreMutex.withLock {
                val clearError = runCatchingCancellable { connectionStore.clearSecret() }.exceptionOrNull()
                clearError to runCatchingCancellable { connectionStore.load() }.getOrNull()
            }
            if (activeCredential == GatewayCredential.CookieSession && snapshot.probe != null) {
                runCatchingCancellable { dashboard.logout(snapshot.probe.baseUrl) }
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
                runCatchingCancellable { connectionStore.forget() }.exceptionOrNull()
            }
            if (activeCredential == GatewayCredential.CookieSession && snapshot.probe != null) {
                runCatchingCancellable { dashboard.logout(snapshot.probe.baseUrl) }
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
                runCatchingCancellable { connectionStore.load() }
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
        runCatchingCancellable {
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
                        runCatchingCancellable {
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
                    errorMessage = sanitizeFailure(error, "Could not reconnect to Hermes."),
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
        expectedGateway: GatewayConnection? = null,
        expectedGeneration: Long? = null,
    ) {
        fun isExpectedConversationCurrent(): Boolean =
            expectedGateway == null || expectedGeneration == null ||
                isCurrentConversation(expectedGateway, expectedGeneration)

        if (!isExpectedConversationCurrent()) return
        credential = null
        currentDescriptor = null
        dashboard.clearAuthentication()
        connectionStoreMutex.withLock {
            if (!isExpectedConversationCurrent()) return@withLock
            runCatchingCancellable { connectionStore.clearSecret() }
        }
        if (!isExpectedConversationCurrent()) return
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
            activityCandidates = ConversationActivityCandidates(),
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
        return connectionAttempt
    }

    private fun isCurrentConnectionAttempt(attempt: Long): Boolean = connectionAttempt == attempt

    private fun beginConversation(): Long {
        closeGateway()
        conversationOperationsJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        return conversationGeneration
    }

    private fun launchConversationOperation(block: suspend () -> Unit): Job =
        viewModelScope.launch(conversationOperationsJob ?: error("No conversation is open")) {
            block()
        }

    private fun isCurrentConversation(
        activeGateway: GatewayConnection,
        generation: Long,
    ): Boolean = gateway === activeGateway && conversationGeneration == generation

    private suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }

    fun openSession(summary: StoredSession) {
        val connection = mutableState.value.probe ?: return
        val activeCredential = credential ?: return
        val generation = beginConversation()
        currentSessionCanResume = true
        mutableState.value = mutableState.value.copy(
            activeSummary = summary,
            messages = emptyList(),
            streamingText = "",
            activityCandidates = ConversationActivityCandidates(),
            draft = "",
            turnState = TurnState.Synchronizing,
            loadingMessage = "Opening ${summary.title.ifBlank { "conversation" }}…",
            errorMessage = null,
        )

        val newGateway = dashboard.createGateway(connection.baseUrl, activeCredential)
        gateway = newGateway
        observeGateway(newGateway, generation)
        launchConversationOperation {
            try {
                newGateway.connect()
                if (!isCurrentConversation(newGateway, generation)) return@launchConversationOperation
                reconcile(newGateway, summary.id, generation)
                if (!isCurrentConversation(newGateway, generation)) return@launchConversationOperation
                reconnectAttempts = 0
                mutableState.value = mutableState.value.copy(loadingMessage = null)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!isCurrentConversation(newGateway, generation)) return@launchConversationOperation
                mutableState.value = mutableState.value.copy(
                    loadingMessage = null,
                    errorMessage = sanitizeFailure(error, "Could not open that Hermes conversation."),
                    turnState = TurnState.Reconnecting,
                )
                scheduleReconnect(
                    activeGateway = newGateway,
                    generation = generation,
                    wasRunning = false,
                )
            }
        }
    }

    fun createNewConversation() {
        val snapshot = mutableState.value
        val connection = snapshot.probe ?: return
        val activeCredential = credential ?: return
        val selectedProfile = snapshot.selectedProfile
        val generation = beginConversation()
        mutableState.value = snapshot.copy(
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            activityCandidates = ConversationActivityCandidates(),
            draft = "",
            turnState = TurnState.Synchronizing,
            loadingMessage = "Starting a new $selectedProfile conversation…",
            errorMessage = null,
        )

        val newGateway = dashboard.createGateway(connection.baseUrl, activeCredential)
        gateway = newGateway
        observeGateway(newGateway, generation)
        launchConversationOperation {
            try {
                newGateway.connect()
                if (!isCurrentConversation(newGateway, generation)) return@launchConversationOperation
                reconciling = true
                bufferedEvents.clear()
                val created = newGateway.createSession(selectedProfile)
                if (!isCurrentConversation(newGateway, generation)) return@launchConversationOperation
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
                )
                val events = bufferedEvents.toList()
                bufferedEvents.clear()
                reconciling = false
                if (!isCurrentConversation(newGateway, generation)) return@launchConversationOperation
                mutableState.value = mutableState.value.copy(
                    sessions = listOf(summary) + mutableState.value.sessions.orEmpty()
                        .filterNot { it.id == summary.id },
                    activeSummary = summary,
                    turnState = TurnState.Idle,
                    loadingMessage = null,
                    errorMessage = null,
                )
                events.forEach { event ->
                    if (isCurrentConversation(newGateway, generation)) applyEvent(event)
                }
                reconnectAttempts = 0
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!isCurrentConversation(newGateway, generation)) return@launchConversationOperation
                closeGateway()
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Idle,
                    loadingMessage = null,
                    errorMessage = sanitizeFailure(error, "Could not create a Hermes conversation."),
                )
            }
        }
    }

    fun leaveConversation() {
        closeGateway()
        mutableState.value = mutableState.value.copy(
            activeSummary = null,
            messages = emptyList(),
            activityCandidates = ConversationActivityCandidates(),
            streamingText = "",
            draft = "",
            turnState = TurnState.Idle,
            loadingMessage = null,
            errorMessage = null,
        )
    }

    fun sendMessage() {
        val activeGateway = gateway ?: return
        val generation = conversationGeneration
        val snapshot = mutableState.value
        val runtimeId = currentRuntimeSessionId ?: return
        val storedId = currentStoredSessionId
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
        launchConversationOperation {
            try {
                activeGateway.submitPrompt(runtimeId, text)
                if (!isCurrentConversation(activeGateway, generation) || currentRuntimeSessionId != runtimeId) {
                    return@launchConversationOperation
                }
                mutableState.value = mutableState.value.copy(
                    messages = mutableState.value.messages.map { message ->
                        if (message.id == localId) message.copy(pending = false) else message
                    },
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!isCurrentConversation(activeGateway, generation) || currentRuntimeSessionId != runtimeId) {
                    return@launchConversationOperation
                }
                mutableState.value = mutableState.value.copy(
                    errorMessage = sanitizeFailure(error, "Hermes could not send that message."),
                )
                if (storedId != null) {
                    try {
                        reconcile(activeGateway, storedId, generation)
                    } catch (reconcileError: Throwable) {
                        if (reconcileError is CancellationException) throw reconcileError
                    }
                }
            }
        }
    }

    fun interrupt() {
        val activeGateway = gateway ?: return
        val generation = conversationGeneration
        val runtimeId = currentRuntimeSessionId ?: return
        val storedId = currentStoredSessionId ?: return
        if (mutableState.value.turnState != TurnState.Running) return
        mutableState.value = mutableState.value.copy(
            turnState = TurnState.Synchronizing,
            errorMessage = null,
        )
        launchConversationOperation {
            try {
                activeGateway.interruptSession(runtimeId)
                if (!isCurrentConversation(activeGateway, generation) || currentRuntimeSessionId != runtimeId) {
                    return@launchConversationOperation
                }
                reconcile(activeGateway, storedId, generation)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!isCurrentConversation(activeGateway, generation) || currentRuntimeSessionId != runtimeId) {
                    return@launchConversationOperation
                }
                mutableState.value = mutableState.value.copy(
                    errorMessage = sanitizeFailure(error, "Hermes could not stop that turn."),
                )
            }
        }
    }

    fun reconnectNow() {
        val activeGateway = gateway ?: return
        if (mutableState.value.activeSummary == null) return
        val generation = conversationGeneration
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        scheduleReconnect(
            activeGateway = activeGateway,
            generation = generation,
            wasRunning = mutableState.value.turnState == TurnState.Running,
            immediate = true,
        )
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
                runCatchingCancellable {
                    connectionStore.replace(descriptor, ReusableSecret(refreshed.value))
                }
            }
        }
    }

    fun onForeground() {
        val activeGateway = gateway ?: return
        val generation = conversationGeneration
        val storedSessionId = currentStoredSessionId ?: return
        if (foregroundCheckJob?.isActive == true || reconciling) return
        if (activeGateway.state.value != GatewayConnectionState.Connected) {
            reconnectNow()
            return
        }
        foregroundCheckJob = launchConversationOperation {
            val health = runCatchingCancellable {
                activeGateway.request(
                    method = "session.list",
                    params = buildJsonObject { put("limit", 1) },
                    timeoutMillis = 8_000,
                )
                if (currentSessionCanResume && isCurrentConversation(activeGateway, generation)) {
                    reconcile(activeGateway, storedSessionId, generation)
                }
            }
            if (health.isFailure && isCurrentConversation(activeGateway, generation)) {
                val wasRunning = mutableState.value.turnState == TurnState.Running
                activeGateway.close()
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Reconnecting,
                    errorMessage = sanitizeFailure(health.exceptionOrNull(), "Reconnecting to Hermes…"),
                )
                scheduleReconnect(
                    activeGateway = activeGateway,
                    generation = generation,
                    wasRunning = wasRunning,
                    immediate = true,
                )
            }
        }
    }

    private var currentRuntimeSessionId: String? = null
    private var currentStoredSessionId: String? = null

    private fun observeGateway(activeGateway: GatewayConnection, generation: Long) {
        gatewayEventsJob = launchConversationOperation {
            activeGateway.events.collect { event ->
                if (!isCurrentConversation(activeGateway, generation)) return@collect
                if (reconciling) {
                    bufferedEvents += event
                } else {
                    applyEvent(event)
                }
            }
        }
        gatewayStateJob = launchConversationOperation {
            activeGateway.state.collect { connectionState ->
                if (!isCurrentConversation(activeGateway, generation)) return@collect
                if (connectionState is GatewayConnectionState.Disconnected) {
                    val wasRunning = mutableState.value.turnState == TurnState.Running
                    mutableState.value = mutableState.value.copy(
                        turnState = TurnState.Reconnecting,
                        errorMessage = sanitizeFailureMessage(connectionState.reason, "Reconnecting to Hermes…"),
                    )
                    scheduleReconnect(
                        activeGateway = activeGateway,
                        generation = generation,
                        wasRunning = wasRunning,
                    )
                }
            }
        }
    }

    private suspend fun reconcile(
        activeGateway: GatewayConnection,
        storedSessionId: String,
        generation: Long,
    ) {
        if (!isCurrentConversation(activeGateway, generation)) return
        reconciling = true
        bufferedEvents.clear()
        try {
            val resumed = activeGateway.resumeStoredSession(storedSessionId)
            if (!isCurrentConversation(activeGateway, generation)) return
            applyResumedSession(resumed)
            val events = bufferedEvents.toList()
            if (!isCurrentConversation(activeGateway, generation)) return
            bufferedEvents.clear()
            reconciling = false
            events.forEach { event ->
                if (isCurrentConversation(activeGateway, generation)) applyEvent(event)
            }
        } catch (error: Throwable) {
            if (isCurrentConversation(activeGateway, generation)) {
                bufferedEvents.clear()
                reconciling = false
            }
            throw error
        }
    }

    private fun applyResumedSession(resumed: ResumedSession) {
        currentRuntimeSessionId = resumed.runtimeSessionId
        currentStoredSessionId = resumed.storedSessionId
        currentSessionCanResume = true
        val remainsLive = resumed.running == true || resumed.hasLiveProjection
        val streamingSuffix = if (remainsLive) {
            unpersistedInflightText(
                inflight = resumed.inflightAssistantText,
                messages = resumed.messages,
            )
        } else {
            ""
        }
        mutableState.value = mutableState.value.copy(
            messages = resumed.messages,
            streamingText = streamingSuffix,
            activityCandidates = if (remainsLive) {
                activityCandidatesFor(resumed.messages)
            } else {
                ConversationActivityCandidates()
            },
            turnState = if (remainsLive) TurnState.Running else TurnState.Idle,
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
                    activityCandidates = mutableState.value.activityCandidates.copy(
                        pendingTool = null,
                        interimAssistant = null,
                    ),
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
                        sanitizeFailureMessage(
                            event.payload.string("error"),
                            "Hermes could not finish that response.",
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
                    errorMessage = sanitizeFailureMessage(
                        event.payload.string("message"),
                        "Hermes reported an error.",
                    ),
                )
            }

            "message.interrupted", "session.interrupted" -> {
                finalizeAssistant(keepRunning = false)
                mutableState.value = mutableState.value.copy(turnState = TurnState.Idle)
            }

            "session.busy" -> {
                val busy = event.payload.boolean("busy") == true
                mutableState.value = mutableState.value.copy(
                    turnState = if (busy) TurnState.Running else TurnState.Idle,
                    streamingText = if (busy) mutableState.value.streamingText else "",
                    activityCandidates = if (busy) {
                        mutableState.value.activityCandidates
                    } else {
                        ConversationActivityCandidates()
                    },
                )
            }

            "session.info" -> {
                event.payload.boolean("running")?.let { running ->
                    mutableState.value = mutableState.value.copy(
                        turnState = if (running) TurnState.Running else TurnState.Idle,
                        streamingText = if (running) mutableState.value.streamingText else "",
                        activityCandidates = if (running) {
                            mutableState.value.activityCandidates
                        } else {
                            ConversationActivityCandidates()
                        },
                    )
                }
            }

            "tool.start", "tool_call" -> {
                if (mutableState.value.streamingText.isNotBlank()) finalizeAssistant(keepRunning = true)
                val name = event.payload.string("name") ?: "Tool"
                val input = event.payload.string("args_text")
                    ?: event.payload.string("context")
                    ?: event.payload["args"]?.toString().orEmpty()
                val toolCallId = event.payload.toolCallIdentity()
                val messages = mutableState.value.messages.toMutableList()
                val existingIndex = if (toolCallId != null) {
                    messages.indexOfLast {
                        it.role == "tool" && it.toolCallId == toolCallId
                    }
                } else {
                    messages.indexOfLast {
                        it.role == "tool" && it.pending && it.toolName == name && it.text == input
                    }
                }
                if (existingIndex >= 0) {
                    val existing = messages[existingIndex]
                    if (!existing.pending) return
                    messages[existingIndex] = existing.copy(
                        text = input,
                        toolName = name,
                        toolCallId = existing.toolCallId ?: toolCallId,
                        pending = true,
                    )
                } else {
                    val toolId = "tool-${localMessageCounter.incrementAndGet()}"
                    messages += ConversationMessage(
                        role = "tool",
                        text = input,
                        toolName = name,
                        id = toolId,
                        pending = true,
                        toolCallId = toolCallId,
                    )
                }
                mutableState.value = mutableState.value.copy(
                    messages = messages,
                    activityCandidates = mutableState.value.activityCandidates.copy(
                        pendingTool = activityCandidatesFor(messages).pendingTool,
                        interimAssistant = null,
                    ),
                    turnState = TurnState.Running,
                )
            }

            "tool.complete", "tool_result" -> {
                val name = event.payload.string("name") ?: "Tool"
                val output = event.payload.string("output")
                    ?: event.payload["result"]?.toString().orEmpty()
                val toolCallId = event.payload.toolCallIdentity()
                val messages = mutableState.value.messages.toMutableList()
                val pendingIndices = messages.mapIndexedNotNull { index, message ->
                    if (message.role == "tool" && message.pending) index else null
                }
                val index = if (toolCallId != null) {
                    pendingIndices.lastOrNull { messages[it].toolCallId == toolCallId } ?: -1
                } else {
                    pendingIndices.filter { messages[it].toolName == name }.singleOrNull() ?: -1
                }
                if (index < 0) return
                val existing = messages[index]
                messages[index] = existing.copy(
                    text = output,
                    pending = false,
                    toolCallId = existing.toolCallId ?: toolCallId,
                )
                mutableState.value = mutableState.value.copy(
                    messages = messages,
                    activityCandidates = mutableState.value.activityCandidates.copy(
                        pendingTool = activityCandidatesFor(messages).pendingTool,
                    ),
                )
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
        val interimAssistant = if (interim && messages.lastOrNull()?.role == "assistant") {
            ConversationActivityCandidate(
                index = messages.lastIndex,
                identity = messages.lastOrNull()?.id,
            )
        } else {
            null
        }
        mutableState.value = mutableState.value.copy(
            messages = messages,
            streamingText = "",
            activityCandidates = mutableState.value.activityCandidates.copy(
                pendingTool = null,
                interimAssistant = interimAssistant,
            ),
            turnState = if (keepRunning) TurnState.Running else TurnState.Idle,
        )
    }

    private suspend fun recreateBlankSession(
        activeGateway: GatewayConnection,
        profile: String,
        generation: Long,
    ) {
        val previousStoredId = currentStoredSessionId
        if (!isCurrentConversation(activeGateway, generation)) return
        reconciling = true
        bufferedEvents.clear()
        try {
            val created = activeGateway.createSession(profile)
            if (!isCurrentConversation(activeGateway, generation)) return
            currentRuntimeSessionId = created.runtimeSessionId
            currentStoredSessionId = created.storedSessionId
            val previousSummary = mutableState.value.activeSummary
                ?: throw IOException("No draft conversation is open.")
            val updatedSummary = previousSummary.copy(id = created.storedSessionId, profile = profile)
            mutableState.value = mutableState.value.copy(
                activeSummary = updatedSummary,
                sessions = mutableState.value.sessions?.map { session ->
                    if (session.id == previousStoredId) updatedSummary else session
                },
                turnState = TurnState.Idle,
                errorMessage = null,
            )
            val events = bufferedEvents.toList()
            if (!isCurrentConversation(activeGateway, generation)) return
            bufferedEvents.clear()
            reconciling = false
            events.forEach { event ->
                if (isCurrentConversation(activeGateway, generation)) applyEvent(event)
            }
        } catch (error: Throwable) {
            if (isCurrentConversation(activeGateway, generation)) {
                bufferedEvents.clear()
                reconciling = false
            }
            throw error
        }
    }

    private fun scheduleReconnect(
        activeGateway: GatewayConnection,
        generation: Long,
        wasRunning: Boolean,
        immediate: Boolean = false,
    ) {
        if (!isCurrentConversation(activeGateway, generation)) return
        val storedSessionId = currentStoredSessionId ?: mutableState.value.activeSummary?.id ?: return
        if (reconnectJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(turnState = TurnState.Reconnecting)
        reconnectJob = launchConversationOperation {
            while (isCurrentConversation(activeGateway, generation)) {
                val delayMillis = if (immediate && reconnectAttempts == 0) {
                    0L
                } else {
                    reconnectDelayMillis(reconnectAttempts, wasRunning)
                }
                if (delayMillis > 0) delay(delayMillis)
                if (!isCurrentConversation(activeGateway, generation)) return@launchConversationOperation
                try {
                    activeGateway.connect()
                    if (!isCurrentConversation(activeGateway, generation)) return@launchConversationOperation
                    if (currentSessionCanResume) {
                        reconcile(activeGateway, storedSessionId, generation)
                    } else {
                        recreateBlankSession(
                            activeGateway = activeGateway,
                            profile = mutableState.value.selectedProfile,
                            generation = generation,
                        )
                    }
                    if (!isCurrentConversation(activeGateway, generation)) return@launchConversationOperation
                    reconnectAttempts = 0
                    reconnectJob = null
                    return@launchConversationOperation
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (!isCurrentConversation(activeGateway, generation)) return@launchConversationOperation
                    if (error is AuthenticationRejected) {
                        val descriptor = currentDescriptor
                        invalidateReusableAuthentication(
                            descriptor = descriptor,
                            expectedGateway = activeGateway,
                            expectedGeneration = generation,
                        )
                        if (isCurrentConversation(activeGateway, generation)) closeGateway()
                        return@launchConversationOperation
                    }
                    reconnectAttempts += 1
                    mutableState.value = mutableState.value.copy(
                        turnState = TurnState.Reconnecting,
                        errorMessage = sanitizeFailure(error, "Reconnecting to Hermes…"),
                    )
                }
            }
        }
    }

    private fun closeGateway() {
        val activeGateway = gateway
        gateway = null
        conversationGeneration += 1
        conversationOperationsJob?.cancel()
        conversationOperationsJob = null
        reconnectJob?.cancel()
        reconnectJob = null
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
