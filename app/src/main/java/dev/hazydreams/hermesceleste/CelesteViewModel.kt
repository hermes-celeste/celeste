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
import dev.hazydreams.hermesceleste.network.GatewayRpcException
import dev.hazydreams.hermesceleste.network.InvalidDashboardResponse
import dev.hazydreams.hermesceleste.network.ProfileSessionList
import dev.hazydreams.hermesceleste.network.RateLimited
import dev.hazydreams.hermesceleste.network.TransportUnavailable
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

private const val SESSION_SEARCH_DEBOUNCE_MILLIS = 250L

internal enum class UiNotice(val message: String) {
    DashboardUnavailable("Could not reach the Hermes dashboard."),
    SessionsUnavailable("Could not load Hermes conversations."),
    SavedConnectionUnavailable("Celeste could not read the saved connection. Sign in again."),
    PersistenceUnavailable("Connected, but Celeste could not remember this connection."),
    PersistenceRefreshUnavailable("Connected, but Celeste could not refresh the saved sign-in."),
    SignOutUnavailable("Celeste could not remove the saved sign-in. Try Forget connection."),
    ForgetUnavailable("Celeste could not remove the saved connection. Try again."),
    RestoreUnavailable("Could not reconnect to Hermes."),
    CatalogUnavailable("Could not refresh Hermes conversations."),
    ConversationUnavailable("Could not open that Hermes conversation."),
    PreviousConversationUnavailable("Could not restore the previous Hermes conversation."),
    NewConversationUnavailable("Could not create a Hermes conversation."),
    SendUnavailable("Hermes could not send that message."),
    InterruptUnavailable("Hermes could not stop that turn."),
    Reconnecting("Reconnecting to Hermes…"),
    AuthenticationRequired("Hermes needs sign-in."),
    RateLimited("Hermes is busy right now. Try again shortly."),
    InvalidResponse("Hermes returned an unexpected response. Try again."),
    HermesReportedError("Hermes reported an error."),
}

/**
 * Convert transport/protocol failures to fixed product copy at the application
 * boundary. Exception messages and server-provided error text never enter UI
 * state; ordinary coroutine cancellation remains silent.
 */
internal fun uiNoticeFor(error: Throwable, fallback: UiNotice): UiNotice? {
    if (findCause(error) { cause ->
            cause is CancellationException && cause !is kotlinx.coroutines.TimeoutCancellationException
        } != null
    ) {
        return null
    }
    return when (val typed = findCause(error) { cause ->
        cause is AuthenticationRejected ||
            cause is RateLimited ||
            cause is InvalidDashboardResponse ||
            cause is TransportUnavailable ||
            cause is GatewayRpcException
    }) {
        is AuthenticationRejected -> UiNotice.AuthenticationRequired
        is RateLimited -> UiNotice.RateLimited
        is InvalidDashboardResponse -> UiNotice.InvalidResponse
        is GatewayRpcException -> when (typed.code) {
            401, 403 -> UiNotice.AuthenticationRequired
            429 -> UiNotice.RateLimited
            else -> fallback
        }
        else -> fallback
    }
}

private fun findCause(
    error: Throwable,
    predicate: (Throwable) -> Boolean,
): Throwable? {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        if (predicate(current)) return current
        current = current.cause
    }
    return null
}

internal enum class TurnState {
    Synchronizing,
    Idle,
    Running,
    Reconnecting,
}

internal enum class ConnectionPhase {
    CheckingSavedConnection,
    ManualSetup,
    LoadingSessions,
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
    val sessionCatalog: SessionCatalogState = SessionCatalogState(),
    val sessionQuery: String = "",
    val profiles: List<DashboardProfile> = listOf(DashboardProfile(name = "default", isDefault = true)),
    val selectedProfile: String = "default",
    val profileOwnershipVerified: Boolean = false,
    val activeSummary: StoredSession? = null,
    val messages: List<ConversationMessage> = emptyList(),
    val streamingText: String = "",
    val draft: String = "",
    val turnState: TurnState = TurnState.Idle,
    val loadingMessage: String? = null,
    val notice: UiNotice? = null,
    val conversationNavigationToken: Long = 0L,
)

private data class LoadedDashboard(
    val credential: GatewayCredential,
    val sessions: List<StoredSession>,
    val profiles: List<DashboardProfile>,
    val selectedProfile: String,
    val profileOwnershipVerified: Boolean,
)

private data class RememberedDashboard(
    val loaded: LoadedDashboard,
    val descriptor: SavedConnectionDescriptor,
    val persistenceError: Throwable?,
)

private data class OpeningToken(
    val id: Long,
    val key: SessionKey,
    val connectionAttempt: Long,
    val originGeneration: Long,
    val previousState: CelesteUiState,
    val previousKey: SessionKey?,
    val previousRuntimeId: String?,
    val previousCanResume: Boolean,
    val previousGateway: GatewayConnection?,
    val candidateGateway: GatewayConnection? = null,
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
    private var connectionJob: Job? = null
    private var connectionAttempt = 0L
    private var originGeneration = 0L
    private var profileGeneration = 0L
    private var catalogRequestGeneration = 0L
    private var catalogScope: SessionScope? = null
    private var catalogJob: Job? = null
    private var sessionSearchJob: Job? = null
    private var sessionQueryGeneration = 0L
    private val connectionStoreMutex = Mutex()
    private var currentDescriptor: SavedConnectionDescriptor? = null
    private var reconnectAttempts = 0
    private var reconciling = false
    private var currentSessionCanResume = true
    private var activeSessionKey: SessionKey? = null
    private var openingToken: OpeningToken? = null
    private var openingJob: Job? = null
    private var openingRollbackJob: Job? = null
    private var newConversationJob: Job? = null
    private var newConversationToken: Long? = null
    private var newConversationPreviousState: CelesteUiState? = null
    private var operationGeneration = 0L
    private val bufferedEvents = mutableListOf<GatewayEvent>()

    init {
        restoreSavedConnection()
    }

    fun updateDashboardUrl(value: String) {
        mutableState.value = mutableState.value.copy(
            dashboardUrl = value,
            probe = null,
            notice = null,
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
     * The selected profile is the creation target. session.list has no
     * verified profile filter, so changing it keeps the loaded catalog intact.
     */
    fun selectProfile(name: String) {
        val selected = mutableState.value.profiles.firstOrNull {
            it.name.equals(name, ignoreCase = true)
        } ?: return
        if (mutableState.value.selectedProfile.equals(selected.name, ignoreCase = true)) return

        // Profile selection is deliberately creation-only. session.list is not
        // profile-scoped on the mounted Hermes contract, so changing this
        // control must not relabel, filter, or refresh the loaded catalog.
        cancelNewConversation()
        profileGeneration += 1
        mutableState.value = mutableState.value.copy(selectedProfile = selected.name)
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
            sessionCatalog = SessionCatalogState(),
            sessionQuery = "",
            profiles = listOf(DashboardProfile(name = "default", isDefault = true)),
            selectedProfile = "default",
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            loadingMessage = "Finding Hermes…",
            notice = null,
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
                        notice = UiNotice.DashboardUnavailable,
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
        val initialScope = SessionScope.unscoped(connection.baseUrl)
        dashboard.clearAuthentication()
        mutableState.value = snapshot.copy(
            connectionPhase = ConnectionPhase.LoadingSessions,
            sessions = emptyList(),
            sessionCatalog = if (initialScope == null) {
                SessionCatalogState(
                    phase = SessionCatalogStatus.Error,
                    notice = UiNotice.SessionsUnavailable,
                )
            } else {
                SessionCatalogState(
                    phase = SessionCatalogStatus.Loading,
                    scope = initialScope,
                )
            },
            sessionQuery = "",
            loadingMessage = "Loading your conversations…",
            notice = null,
        )
        var attemptedDescriptor: SavedConnectionDescriptor? = currentDescriptor
        connectionJob = viewModelScope.launch {
            runCatching {
                val passwordProvider = connection.providers.firstOrNull { it.supportsPassword }
                val selectedCredential = if (connection.authRequired) {
                    passwordProvider
                        ?: error("This dashboard requires browser sign-in, which is not in this build yet.")
                    attemptedDescriptor = SavedConnectionDescriptor(
                        baseUrl = connection.baseUrl,
                        authMode = SavedAuthMode.ProviderSession,
                        provider = passwordProvider.name,
                        username = snapshot.username,
                        expectsSecret = true,
                    )
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
                        ?.let {
                            attemptedDescriptor = SavedConnectionDescriptor(
                                baseUrl = connection.baseUrl,
                                authMode = SavedAuthMode.StaticToken,
                                expectsSecret = true,
                            )
                            GatewayCredential.StaticToken(it)
                        }
                        ?: run {
                            attemptedDescriptor = SavedConnectionDescriptor(
                                baseUrl = connection.baseUrl,
                                authMode = SavedAuthMode.Open,
                                expectsSecret = false,
                            )
                            GatewayCredential.None
                        }
                }
                val loaded = loadDashboard(
                    baseUrl = connection.baseUrl,
                    selectedCredential = selectedCredential,
                    preferredProfile = snapshot.selectedProfile,
                )
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
                    notice = if (remembered.persistenceError == null) {
                        null
                    } else {
                        UiNotice.PersistenceUnavailable
                    },
                )
            }.onFailure { error ->
                if (!isCurrentConnectionAttempt(attempt)) return@onFailure
                if (error is AuthenticationRejected) {
                    invalidateReusableAuthentication(
                        descriptor = attemptedDescriptor ?: currentDescriptor,
                        probe = connection,
                    )
                } else {
                    dashboard.clearAuthentication()
                    mutableState.value = mutableState.value.copy(
                        connectionPhase = ConnectionPhase.ManualSetup,
                        sessions = null,
                        sessionCatalog = SessionCatalogState(
                            phase = SessionCatalogStatus.Error,
                            scope = initialScope,
                            notice = UiNotice.SessionsUnavailable,
                        ),
                        sessionQuery = "",
                        notice = UiNotice.SessionsUnavailable,
                        password = "",
                        sessionToken = "",
                    )
                }
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
            notice = null,
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
            sessionCatalog = SessionCatalogState(),
            sessionQuery = "",
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            password = "",
            sessionToken = "",
            loadingMessage = "Signing out…",
            notice = null,
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
                notice = if (error == null) null else UiNotice.SignOutUnavailable,
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
                notice = if (error == null) null else UiNotice.ForgetUnavailable,
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
                    notice = UiNotice.SavedConnectionUnavailable,
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
                notice = if (persistenceError == null) null else UiNotice.PersistenceRefreshUnavailable,
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
                    notice = UiNotice.RestoreUnavailable,
                )
            }
        }
    }

    private suspend fun loadDashboard(
        baseUrl: String,
        selectedCredential: GatewayCredential,
        preferredProfile: String = mutableState.value.selectedProfile,
    ): LoadedDashboard {
        val profiles = dashboard.listProfiles(baseUrl, selectedCredential)
        val selectedProfile = profiles.firstOrNull {
            it.name.equals(preferredProfile, ignoreCase = true)
        }?.name
            ?: profiles.firstOrNull(DashboardProfile::isDefault)?.name
            ?: profiles.firstOrNull()?.name
            ?: "default"
        val profileSessionList = dashboard.listProfileSessions(
            baseUrl = baseUrl,
            credential = selectedCredential,
            profile = "all",
        )
        return LoadedDashboard(
            credential = selectedCredential,
            sessions = profileSessionList.sessions,
            profiles = profiles,
            selectedProfile = selectedProfile,
            profileOwnershipVerified = profileSessionList.profileOwnershipVerified,
        )
    }

    private suspend fun invalidateReusableAuthentication(
        descriptor: SavedConnectionDescriptor?,
        probe: DashboardProbeResult? = null,
    ) {
        closeGateway()
        sessionQueryGeneration += 1
        cancelSessionSearch()
        catalogRequestGeneration += 1
        catalogJob?.cancel()
        catalogJob = null
        catalogScope = null
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
            notice = UiNotice.AuthenticationRequired,
        )
    }

    private fun publishConnectedDashboard(
        loaded: LoadedDashboard,
        password: String = "",
        sessionToken: String = "",
        notice: UiNotice? = null,
    ) {
        val selectedProfile = loaded.profiles.firstOrNull {
            it.name.equals(mutableState.value.selectedProfile, ignoreCase = true)
        }?.name ?: loaded.selectedProfile
        val scope = SessionScope.unscoped(mutableState.value.probe?.baseUrl.orEmpty())
        catalogScope = scope
        val catalog = if (scope == null) {
            SessionCatalogState(phase = SessionCatalogStatus.Error, notice = UiNotice.CatalogUnavailable)
        } else {
            val rows = SessionCatalogReducer.filterAuthoritativeRows(scope, loaded.sessions)
            SessionCatalogState(
                phase = if (rows.isEmpty()) SessionCatalogStatus.Empty else SessionCatalogStatus.Ready,
                scope = scope,
                rows = rows,
            )
        }
        mutableState.value = mutableState.value.copy(
            connectionPhase = ConnectionPhase.Connected,
            savedAuthMode = currentDescriptor?.authMode,
            sessions = catalog.rows,
            sessionCatalog = catalog,
            sessionQuery = "",
            profiles = loaded.profiles,
            selectedProfile = selectedProfile,
            profileOwnershipVerified = loaded.profileOwnershipVerified,
            activeSummary = null,
            messages = emptyList(),
            password = password,
            sessionToken = sessionToken,
            loadingMessage = null,
            notice = notice,
        )
    }

    private fun manualState(
        descriptor: SavedConnectionDescriptor? = null,
        phase: ConnectionPhase = ConnectionPhase.ManualSetup,
        probe: DashboardProbeResult? = null,
        notice: UiNotice? = null,
    ): CelesteUiState = CelesteUiState(
        connectionPhase = phase,
        dashboardUrl = descriptor?.baseUrl ?: probe?.baseUrl.orEmpty(),
        probe = probe,
        savedAuthMode = descriptor?.authMode,
        username = descriptor?.username.orEmpty(),
        notice = notice,
    )

    private fun invalidatePendingOperations() {
        val pendingOpening = openingToken
        openingToken = null
        openingJob?.cancel()
        openingJob = null
        openingRollbackJob?.cancel()
        openingRollbackJob = null
        val candidateGateway = pendingOpening?.candidateGateway
        if (candidateGateway != null && candidateGateway !== gateway && candidateGateway !== pendingOpening.previousGateway) {
            candidateGateway.close()
        }

        newConversationToken = null
        newConversationPreviousState = null
        newConversationJob?.cancel()
        newConversationJob = null
    }

    private fun beginConnectionAttempt(): Long {
        invalidatePendingOperations()
        connectionAttempt += 1
        originGeneration += 1
        profileGeneration += 1
        catalogRequestGeneration += 1
        sessionQueryGeneration += 1
        cancelSessionSearch()
        catalogJob?.cancel()
        catalogJob = null
        catalogScope = null
        mutableState.value = mutableState.value.copy(
            sessions = null,
            sessionCatalog = SessionCatalogState(),
            sessionQuery = "",
            profileOwnershipVerified = false,
        )
        connectionJob?.cancel()
        connectionJob = null
        return connectionAttempt
    }

    private fun isCurrentConnectionAttempt(attempt: Long): Boolean = connectionAttempt == attempt

    fun updateSessionQuery(value: String) {
        val generation = ++sessionQueryGeneration
        cancelSessionSearch()
        val snapshot = mutableState.value
        val nextCatalog = snapshot.sessionCatalog.withQuery(value)
        mutableState.value = snapshot.copy(
            sessionQuery = value,
            sessionCatalog = nextCatalog,
        )
        if (value.isBlank()) return

        val rows = nextCatalog.rows
        val scope = nextCatalog.scope
        val originAtStart = originGeneration
        val catalogRequestAtStart = catalogRequestGeneration
        val connectionAttemptAtStart = connectionAttempt
        sessionSearchJob = viewModelScope.launch {
            delay(SESSION_SEARCH_DEBOUNCE_MILLIS)
            val results = withContext(Dispatchers.Default) {
                searchLoadedSessions(rows, value)
            }
            val current = mutableState.value
            if (
                generation != sessionQueryGeneration ||
                originAtStart != originGeneration ||
                catalogRequestAtStart != catalogRequestGeneration ||
                connectionAttemptAtStart != connectionAttempt ||
                current.sessionCatalog.scope != scope ||
                current.sessionQuery != value
            ) return@launch
            mutableState.value = current.copy(
                sessionCatalog = current.sessionCatalog.withSearchResults(value, results),
            )
            if (generation == sessionQueryGeneration) sessionSearchJob = null
        }
    }

    fun clearSessionQuery() = updateSessionQuery("")

    private fun cancelSessionSearch() {
        sessionSearchJob?.cancel()
        sessionSearchJob = null
    }

    fun openConversationBrowser() {
        if (mutableState.value.sessions != null) refreshSessionCatalog()
    }

    fun closeConversationBrowser() {
        if (mutableState.value.sessions != null) refreshSessionCatalog()
    }

    /**
     * Refreshes the server-authoritative loaded window. The selected profile
     * controls new conversation creation only; it is not sent as an invented
     * session.list filter.
     */
    fun refreshSessionCatalog() {
        val snapshot = mutableState.value
        val probe = snapshot.probe ?: return
        val activeCredential = credential ?: return
        val scope = SessionScope.unscoped(probe.baseUrl) ?: return
        val refreshing = snapshot.sessionCatalog.scope == scope && snapshot.sessions != null
        sessionQueryGeneration += 1
        cancelSessionSearch()
        catalogJob?.cancel()
        val request = SessionCatalogRequest(
            scope = scope,
            originGeneration = originGeneration,
            requestGeneration = ++catalogRequestGeneration,
            connectionAttempt = connectionAttempt,
        )
        catalogScope = scope
        val started = SessionCatalogReducer.begin(snapshot.sessionCatalog, request, refreshing)
        mutableState.value = snapshot.copy(
            sessions = started.rows,
            sessionCatalog = started,
        )
        catalogJob = viewModelScope.launch {
            try {
                val rows = dashboard.listProfileSessions(
                    baseUrl = probe.baseUrl,
                    credential = activeCredential,
                    profile = "all",
                ).sessions
                if (!isCurrentCatalogRequest(request)) return@launch
                publishCatalog(
                    SessionCatalogReducer.succeeded(
                        mutableState.value.sessionCatalog,
                        request,
                        rows,
                    ),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!isCurrentCatalogRequest(request)) return@launch
                if (error is AuthenticationRejected) {
                    invalidateReusableAuthentication(
                        descriptor = currentDescriptor,
                        probe = probe,
                    )
                } else {
                    publishCatalog(
                        SessionCatalogReducer.failed(
                            mutableState.value.sessionCatalog,
                            request,
                            uiNoticeFor(error, UiNotice.CatalogUnavailable)
                                ?: return@launch,
                        ),
                    )
                }
            } finally {
                if (isCurrentCatalogRequest(request)) catalogJob = null
            }
        }
    }

    private fun isCurrentCatalogRequest(request: SessionCatalogRequest): Boolean {
        val current = mutableState.value
        return request.originGeneration == originGeneration &&
            request.requestGeneration == catalogRequestGeneration &&
            request.connectionAttempt == connectionAttempt &&
            request.scope == catalogScope &&
            current.probe?.baseUrl?.let {
                SessionScope.unscoped(it)
            } == request.scope &&
            credential != null
    }

    private fun publishCatalog(catalog: SessionCatalogState) {
        mutableState.value = mutableState.value.copy(
            sessions = catalog.rows,
            sessionCatalog = catalog,
        )
    }

    private fun markCatalogReconnecting() {
        val snapshot = mutableState.value
        val scope = snapshot.probe?.baseUrl?.let(SessionScope::unscoped) ?: return
        val keepRows = snapshot.sessions != null && snapshot.sessionCatalog.scope == scope
        sessionQueryGeneration += 1
        cancelSessionSearch()
        catalogRequestGeneration += 1
        catalogJob?.cancel()
        catalogJob = null
        catalogScope = scope
        val reconnecting = SessionCatalogReducer.reconnecting(
            state = snapshot.sessionCatalog,
            scope = scope,
            keepRows = keepRows,
        )
        mutableState.value = snapshot.copy(
            sessions = reconnecting.rows,
            sessionCatalog = reconnecting,
        )
    }

    fun openSession(summary: StoredSession) {
        if (openingToken != null) return
        val snapshot = mutableState.value
        val connection = snapshot.probe ?: return
        val activeCredential = credential ?: return
        val scope = catalogScope ?: SessionScope.unscoped(connection.baseUrl) ?: return
        val key = summary.keyFor(scope.originKey) ?: return
        if (snapshot.sessionCatalog.rows.none { it.keyFor(scope.originKey) == key }) return

        val attempt = connectionAttempt
        val previousGateway = gateway
        val token = OpeningToken(
            id = ++operationGeneration,
            key = key,
            connectionAttempt = attempt,
            originGeneration = originGeneration,
            previousState = snapshot,
            previousKey = activeSessionKey,
            previousRuntimeId = currentRuntimeSessionId,
            previousCanResume = currentSessionCanResume,
            previousGateway = previousGateway,
        )
        openingToken = token
        reconnectJob?.cancel()
        reconnectJob = null
        currentSessionCanResume = true
        val openingCatalog = SessionCatalogReducer.opening(snapshot.sessionCatalog, key)
        mutableState.value = snapshot.copy(
            sessions = openingCatalog.rows,
            sessionCatalog = openingCatalog,
            loadingMessage = "Opening ${summary.title.ifBlank { "conversation" }}…",
            notice = null,
        )

        val newGateway = runCatching {
            dashboard.createGateway(connection.baseUrl, activeCredential)
        }.getOrElse { error ->
            rollbackOpening(token, UiNotice.ConversationUnavailable)
            return
        }
        val activeToken = token.copy(candidateGateway = newGateway)
        openingToken = activeToken
        if (newGateway === previousGateway) {
            closeGateway()
            gateway = newGateway
            observeGateway(newGateway)
        } else {
            detachGatewayObservers()
            gateway = newGateway
            observeGateway(newGateway)
        }
        activeSessionKey = key
        openingJob = viewModelScope.launch {
            try {
                newGateway.connect()
                check(isCurrentOpening(activeToken)) {
                    "The Hermes conversation scope changed while opening."
                }
                reconcile(newGateway, key)
                check(isCurrentOpening(activeToken)) {
                    "The Hermes conversation scope changed while opening."
                }
                if (previousGateway != null && previousGateway !== newGateway) previousGateway.close()
                val openedCatalog = SessionCatalogReducer.openingSucceeded(
                    mutableState.value.sessionCatalog,
                )
                openingToken = null
                openingJob = null
                reconnectAttempts = 0
                mutableState.value = mutableState.value.copy(
                    sessions = openedCatalog.rows,
                    sessionCatalog = openedCatalog,
                    activeSummary = summary,
                    draft = "",
                    loadingMessage = null,
                    notice = null,
                    conversationNavigationToken = mutableState.value.conversationNavigationToken + 1,
                )
            } catch (error: Throwable) {
                if (openingToken?.id == activeToken.id) {
                    if (error is AuthenticationRejected) {
                        activeToken.previousGateway
                            ?.takeUnless { it === activeToken.candidateGateway }
                            ?.close()
                        invalidateReusableAuthentication(
                            descriptor = currentDescriptor,
                            probe = connection,
                        )
                    } else {
                        rollbackOpening(
                            activeToken,
                            if (error is CancellationException) null else UiNotice.ConversationUnavailable,
                        )
                    }
                }
            } finally {
                if (openingToken?.id == activeToken.id && errorCandidateIsClosed(activeToken)) {
                    openingToken = null
                }
                if (openingJob?.isCompleted == true) openingJob = null
            }
        }
    }

    fun cancelOpening() {
        val token = openingToken ?: return
        openingJob?.cancel()
        rollbackOpening(token, null)
    }

    private fun isCurrentOpening(token: OpeningToken): Boolean =
        openingToken?.id == token.id &&
            token.candidateGateway != null &&
            gateway === token.candidateGateway &&
            activeSessionKey == token.key &&
            connectionAttempt == token.connectionAttempt &&
            originGeneration == token.originGeneration

    private fun errorCandidateIsClosed(token: OpeningToken): Boolean =
        token.candidateGateway != null && token.candidateGateway !== gateway

    private fun rollbackOpening(token: OpeningToken, notice: UiNotice?) {
        if (openingToken?.id != token.id) return
        val candidate = token.candidateGateway
        detachGatewayObservers()
        if (candidate != null && candidate !== token.previousGateway) candidate.close()
        gateway = token.previousGateway
        activeSessionKey = token.previousKey
        currentRuntimeSessionId = token.previousRuntimeId
        currentSessionCanResume = token.previousCanResume
        val restoredCatalog = if (notice == null) {
            SessionCatalogReducer.openingCancelled(token.previousState.sessionCatalog)
        } else {
            SessionCatalogReducer.openingFailed(
                token.previousState.sessionCatalog,
                notice,
            )
        }
        openingToken = null
        openingJob = null
        val previousGateway = token.previousGateway
        val previousKey = token.previousKey
        if (previousGateway != null && previousKey != null) {
            // Arm the previous collector before publishing the rollback. Any
            // event received while its snapshot is loading is buffered and
            // replayed after the authoritative resume response.
            reconciling = true
            bufferedEvents.clear()
            observeGateway(previousGateway)
        }
        mutableState.value = token.previousState.copy(
            sessions = restoredCatalog.rows,
            sessionCatalog = restoredCatalog,
            loadingMessage = null,
            notice = notice ?: token.previousState.notice,
            turnState = if (
                token.previousGateway != null &&
                token.previousKey != null
            ) {
                TurnState.Synchronizing
            } else {
                token.previousState.turnState
            },
        )
        if (previousGateway != null && previousKey != null) {
            openingRollbackJob?.cancel()
            val wasRunning = token.previousState.turnState == TurnState.Running
            openingRollbackJob = viewModelScope.launch {
                try {
                    if (previousGateway.state.value != GatewayConnectionState.Connected) {
                        previousGateway.connect()
                    }
                    if (!isCurrentOpeningRollback(token, previousGateway, previousKey)) return@launch
                    reconcile(
                        activeGateway = previousGateway,
                        key = previousKey,
                        preserveBufferedEvents = true,
                    )
                    if (!isCurrentOpeningRollback(token, previousGateway, previousKey)) return@launch
                    mutableState.value = mutableState.value.copy(
                        loadingMessage = null,
                        notice = notice ?: mutableState.value.notice,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    reconciling = false
                    bufferedEvents.clear()
                    if (!isCurrentOpeningRollback(token, previousGateway, previousKey)) return@launch
                    mutableState.value = mutableState.value.copy(
                        turnState = TurnState.Reconnecting,
                        notice = UiNotice.PreviousConversationUnavailable,
                    )
                    scheduleReconnect(wasRunning = wasRunning, immediate = true)
                } finally {
                    openingRollbackJob = null
                }
            }
        }
    }

    private fun isCurrentOpeningRollback(
        token: OpeningToken,
        previousGateway: GatewayConnection,
        previousKey: SessionKey,
    ): Boolean = openingToken == null &&
        gateway === previousGateway &&
        activeSessionKey == previousKey &&
        connectionAttempt == token.connectionAttempt &&
        originGeneration == token.originGeneration

    fun createNewConversation() {
        if (openingToken != null || newConversationToken != null) return
        val snapshot = mutableState.value
        val connection = snapshot.probe ?: return
        val activeCredential = credential ?: return
        val selectedProfile = snapshot.selectedProfile
        val attempt = connectionAttempt
        val selectedProfileGeneration = profileGeneration
        val previousGateway = gateway
        val operationToken = ++operationGeneration
        newConversationToken = operationToken
        newConversationPreviousState = snapshot
        val actionStarted = SessionCatalogReducer.actionStarted(snapshot.sessionCatalog)
        mutableState.value = snapshot.copy(
            sessions = actionStarted.rows,
            sessionCatalog = actionStarted,
            // Keep the previous projection until Hermes accepts the new session.
            // This is deliberately not a synthetic catalog row or a persisted draft.
            turnState = TurnState.Synchronizing,
            loadingMessage = "Starting a new $selectedProfile conversation…",
            notice = null,
        )

        val newGateway = runCatching {
            dashboard.createGateway(connection.baseUrl, activeCredential)
        }.getOrElse { error ->
            newConversationToken = null
            newConversationPreviousState = null
            mutableState.value = snapshot.copy(
                sessions = snapshot.sessionCatalog.rows,
                sessionCatalog = SessionCatalogReducer.actionFailed(
                    snapshot.sessionCatalog,
                    UiNotice.NewConversationUnavailable,
                ),
                loadingMessage = null,
                notice = UiNotice.NewConversationUnavailable,
            )
            return
        }
        val candidateEvents = mutableListOf<GatewayEvent>()
        val candidateEventsJob = viewModelScope.launch {
            newGateway.events.collect { event -> candidateEvents += event }
        }
        var committed = false
        newConversationJob = viewModelScope.launch {
            try {
                newGateway.connect()
                val created = newGateway.createSession(selectedProfile)
                if (
                    connectionAttempt != attempt ||
                    profileGeneration != selectedProfileGeneration ||
                    mutableState.value.selectedProfile != selectedProfile ||
                    newConversationToken != operationToken
                ) {
                    throw CancellationException("The Hermes profile changed while creating the conversation.")
                }
                val returnedProfile = created.profile?.takeIf(String::isNotBlank)
                if (returnedProfile != null && !returnedProfile.equals(selectedProfile, ignoreCase = true)) {
                    throw IOException("Hermes created this conversation in $returnedProfile instead of $selectedProfile.")
                }
                val events = candidateEvents.toList()
                if (newGateway !== previousGateway) {
                    closeGateway()
                    gateway = newGateway
                    observeGateway(newGateway)
                }
                currentRuntimeSessionId = created.runtimeSessionId
                currentSessionCanResume = false
                activeSessionKey = requireNotNull(
                    SessionKey.from(connection.baseUrl, selectedProfile, created.storedSessionId),
                )
                val summary = StoredSession(
                    id = created.storedSessionId,
                    title = "New conversation",
                    preview = "",
                    startedAt = 0.0,
                    messageCount = 0,
                    source = "android",
                    profile = selectedProfile,
                )
                mutableState.value = mutableState.value.copy(
                    sessionCatalog = snapshot.sessionCatalog.copy(
                        phase = if (snapshot.sessionCatalog.rows.isEmpty()) {
                            SessionCatalogStatus.Empty
                        } else {
                            SessionCatalogStatus.Ready
                        },
                        notice = null,
                        request = null,
                    ),
                    activeSummary = summary,
                    messages = emptyList(),
                    streamingText = "",
                    draft = "",
                    turnState = TurnState.Idle,
                    loadingMessage = null,
                    notice = null,
                )
                committed = true
                newConversationToken = null
                newConversationPreviousState = null
                reconnectAttempts = 0
                events.forEach(::applyEvent)
            } catch (error: Throwable) {
                if (
                    newConversationToken == operationToken &&
                    connectionAttempt == attempt &&
                    profileGeneration == selectedProfileGeneration &&
                    mutableState.value.selectedProfile == selectedProfile
                ) {
                    if (error is AuthenticationRejected) {
                        invalidateReusableAuthentication(
                            descriptor = currentDescriptor,
                            probe = connection,
                        )
                    } else {
                        val notice = if (error is CancellationException) {
                            null
                        } else {
                            UiNotice.NewConversationUnavailable
                        }
                        val failedCatalog = if (notice == null) {
                            SessionCatalogReducer.actionCancelled(snapshot.sessionCatalog)
                        } else {
                            SessionCatalogReducer.actionFailed(snapshot.sessionCatalog, notice)
                        }
                        mutableState.value = snapshot.copy(
                            sessions = snapshot.sessionCatalog.rows,
                            sessionCatalog = failedCatalog,
                            loadingMessage = null,
                            notice = notice,
                        )
                    }
                } else if (newConversationToken == operationToken) {
                    rollbackNewConversation(
                        snapshot = snapshot,
                        notice = if (error is CancellationException) null else UiNotice.NewConversationUnavailable,
                    )
                }
            } finally {
                candidateEventsJob.cancel()
                if (!committed && newGateway !== previousGateway) newGateway.close()
                if (newConversationToken == operationToken) newConversationToken = null
                if (newConversationToken == null) newConversationPreviousState = null
                if (newConversationJob?.isCompleted == true) newConversationJob = null
            }
        }
    }

    private fun cancelNewConversation() {
        val snapshot = newConversationPreviousState ?: return
        newConversationJob?.cancel()
        rollbackNewConversation(snapshot, null)
    }

    private fun rollbackNewConversation(snapshot: CelesteUiState, notice: UiNotice?) {
        if (newConversationPreviousState == null && newConversationToken == null) return
        val restoredCatalog = if (notice == null) {
            SessionCatalogReducer.actionCancelled(snapshot.sessionCatalog)
        } else {
            SessionCatalogReducer.actionFailed(snapshot.sessionCatalog, notice)
        }
        val current = mutableState.value
        mutableState.value = current.copy(
            sessions = restoredCatalog.rows,
            sessionCatalog = restoredCatalog,
            activeSummary = snapshot.activeSummary,
            messages = snapshot.messages,
            streamingText = snapshot.streamingText,
            draft = snapshot.draft,
            turnState = snapshot.turnState,
            loadingMessage = null,
            notice = notice,
        )
        newConversationToken = null
        newConversationPreviousState = null
    }

    fun leaveConversation() {
        closeGateway()
        mutableState.value = mutableState.value.copy(
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            turnState = TurnState.Idle,
            loadingMessage = null,
            notice = null,
        )
        refreshSessionCatalog()
    }

    fun sendMessage() {
        val activeGateway = gateway ?: return
        val snapshot = mutableState.value
        val runtimeId = currentRuntimeSessionId ?: return
        val activeKey = activeSessionKey ?: return
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
            notice = null,
        )
        // prompt.submit creates the durable row before work begins. From this point on,
        // uncertain delivery must reconcile by stored ID and must never create/resend.
        currentSessionCanResume = true
        viewModelScope.launch {
            try {
                activeGateway.submitPrompt(runtimeId, text)
                if (!isCurrentSession(activeGateway, activeKey, runtimeId)) return@launch
                mutableState.value = mutableState.value.copy(
                    messages = mutableState.value.messages.map { message ->
                        if (message.id == localId) message.copy(pending = false) else message
                    },
                )
                // prompt.submit is the first durable write for a blank
                // runtime; the catalog learns its row only from Hermes.
                refreshSessionCatalog()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrentSession(activeGateway, activeKey, runtimeId)) return@launch
                val notice = uiNoticeFor(error, UiNotice.SendUnavailable) ?: return@launch
                mutableState.value = mutableState.value.copy(notice = notice)
                try {
                    reconcile(activeGateway, activeKey)
                } catch (reconciliationError: CancellationException) {
                    throw reconciliationError
                } catch (_: Throwable) {
                    // The fixed send notice is already visible; a later
                    // reconnect will reconcile the authoritative snapshot.
                }
            }
        }
    }

    fun interrupt() {
        val activeGateway = gateway ?: return
        val runtimeId = currentRuntimeSessionId ?: return
        val activeKey = activeSessionKey ?: return
        if (mutableState.value.turnState != TurnState.Running) return
        mutableState.value = mutableState.value.copy(
            turnState = TurnState.Synchronizing,
            notice = null,
        )
        viewModelScope.launch {
            try {
                activeGateway.interruptSession(runtimeId)
                if (!isCurrentSession(activeGateway, activeKey, runtimeId)) return@launch
                reconcile(activeGateway, activeKey)
                if (!isCurrentSession(activeGateway, activeKey, runtimeId)) return@launch
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrentSession(activeGateway, activeKey, runtimeId)) return@launch
                val notice = uiNoticeFor(error, UiNotice.InterruptUnavailable) ?: return@launch
                mutableState.value = mutableState.value.copy(
                    notice = notice,
                )
            }
        }
    }

    fun reconnectNow() {
        if (gateway == null || activeSessionKey == null) return
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
        val activeGateway = gateway
        if (activeGateway == null) {
            if (mutableState.value.sessions != null && catalogJob?.isActive != true) {
                refreshSessionCatalog()
            }
            return
        }
        val activeKey = activeSessionKey ?: return
        if (foregroundCheckJob?.isActive == true || reconciling) return
        if (activeGateway.state.value != GatewayConnectionState.Connected) {
            markCatalogReconnecting()
            reconnectNow()
            return
        }
        foregroundCheckJob = viewModelScope.launch {
            try {
                activeGateway.request(
                    method = "session.list",
                    params = buildJsonObject { put("limit", 1) },
                    timeoutMillis = 8_000,
                )
                if (!isCurrentSession(activeGateway, activeKey)) return@launch
                if (currentSessionCanResume) {
                    reconcile(activeGateway, activeKey)
                }
                if (!isCurrentSession(activeGateway, activeKey)) return@launch
                refreshSessionCatalog()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrentSession(activeGateway, activeKey)) return@launch
                val wasRunning = mutableState.value.turnState == TurnState.Running
                markCatalogReconnecting()
                activeGateway.close()
                val notice = uiNoticeFor(error, UiNotice.Reconnecting) ?: return@launch
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Reconnecting,
                    notice = notice,
                )
                scheduleReconnect(wasRunning = wasRunning, immediate = true)
            } finally {
                if (isCurrentSession(activeGateway, activeKey)) foregroundCheckJob = null
            }
        }
    }

    private var currentRuntimeSessionId: String? = null

    private fun isCurrentSession(
        activeGateway: GatewayConnection,
        activeKey: SessionKey,
        runtimeId: String? = null,
    ): Boolean = gateway === activeGateway &&
        activeSessionKey == activeKey &&
        (runtimeId == null || currentRuntimeSessionId == runtimeId)

    private fun detachGatewayObservers() {
        gatewayEventsJob?.cancel()
        gatewayEventsJob = null
        gatewayStateJob?.cancel()
        gatewayStateJob = null
    }

    private fun observeGateway(activeGateway: GatewayConnection) {
        detachGatewayObservers()
        gatewayEventsJob = viewModelScope.launch {
            activeGateway.events.collect { event ->
                if (gateway !== activeGateway) return@collect
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
                    markCatalogReconnecting()
                    mutableState.value = mutableState.value.copy(
                        turnState = TurnState.Reconnecting,
                        notice = UiNotice.Reconnecting,
                    )
                    scheduleReconnect(wasRunning)
                }
            }
        }
    }

    private suspend fun reconcile(
        activeGateway: GatewayConnection,
        key: SessionKey,
        preserveBufferedEvents: Boolean = false,
    ) {
        if (!preserveBufferedEvents) {
            reconciling = true
            bufferedEvents.clear()
        }
        try {
            val resumed = activeGateway.resumeStoredSession(key.durableId, key.profile)
            if (gateway !== activeGateway || activeSessionKey != key) {
                bufferedEvents.clear()
                reconciling = false
                return
            }
            applyResumedSession(key, resumed)
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

    private fun applyResumedSession(key: SessionKey, resumed: ResumedSession) {
        val resumedId = resumed.storedSessionId.trim()
        if (resumedId.isNotBlank() && resumedId != key.durableId) {
            throw IOException("Hermes resumed a different stored conversation.")
        }
        currentRuntimeSessionId = resumed.runtimeSessionId
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
            notice = null,
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
                    notice = null,
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
                    notice = if (status == "error") {
                        UiNotice.HermesReportedError
                    } else {
                        mutableState.value.notice
                    },
                )
            }

            "error", "message.error" -> {
                finalizeAssistant(keepRunning = false)
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Idle,
                    notice = UiNotice.HermesReportedError,
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

    private suspend fun recreateBlankSession(
        activeGateway: GatewayConnection,
        activeKey: SessionKey,
    ) {
        reconciling = true
        bufferedEvents.clear()
        try {
            val created = activeGateway.createSession(activeKey.profile)
            if (gateway !== activeGateway || this.activeSessionKey != activeKey) return
            val returnedProfile = created.profile?.takeIf(String::isNotBlank)
            if (returnedProfile != null && !returnedProfile.equals(activeKey.profile, ignoreCase = true)) {
                throw IOException("Hermes recreated this conversation in $returnedProfile instead of ${activeKey.profile}.")
            }
            val replacementKey = requireNotNull(
                SessionKey.from(activeKey.originKey, activeKey.profile, created.storedSessionId),
            )
            val previousSummary = mutableState.value.activeSummary
                ?: throw IOException("No draft conversation is open.")
            this.activeSessionKey = replacementKey
            currentRuntimeSessionId = created.runtimeSessionId
            val updatedSummary = previousSummary.copy(
                id = replacementKey.durableId,
                profile = replacementKey.profile,
            )
            mutableState.value = mutableState.value.copy(
                activeSummary = updatedSummary,
                turnState = TurnState.Idle,
                notice = null,
            )
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

    private fun scheduleReconnect(wasRunning: Boolean, immediate: Boolean = false) {
        val activeGateway = gateway ?: return
        val activeKey = activeSessionKey ?: return
        if (reconnectJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(turnState = TurnState.Reconnecting)
        reconnectJob = viewModelScope.launch {
            while (gateway === activeGateway && this@CelesteViewModel.activeSessionKey == activeKey) {
                val delayMillis = if (immediate && reconnectAttempts == 0) {
                    0L
                } else {
                    reconnectDelayMillis(reconnectAttempts, wasRunning)
                }
                if (delayMillis > 0) delay(delayMillis)
                val result = runCatching {
                    activeGateway.connect()
                    if (currentSessionCanResume) {
                        reconcile(activeGateway, activeKey)
                    } else {
                        recreateBlankSession(activeGateway, activeKey)
                    }
                }
                if (result.isSuccess) {
                    if (!isCurrentSession(activeGateway, activeKey)) return@launch
                    reconnectAttempts = 0
                    reconnectJob = null
                    refreshSessionCatalog()
                    return@launch
                }
                val failure = result.exceptionOrNull()
                if (failure is CancellationException) throw failure
                if (!isCurrentSession(activeGateway, activeKey)) return@launch
                if (findCause(failure ?: return@launch) { it is AuthenticationRejected } != null) {
                    val descriptor = currentDescriptor
                    reconnectJob = null
                    closeGateway()
                    invalidateReusableAuthentication(descriptor)
                    return@launch
                }
                reconnectAttempts += 1
                val notice = uiNoticeFor(failure ?: return@launch, UiNotice.Reconnecting) ?: return@launch
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Reconnecting,
                    notice = notice,
                )
            }
            reconnectJob = null
        }
    }

    private fun closeGateway() {
        val activeGateway = gateway
        gateway = null
        reconnectJob?.cancel()
        reconnectJob = null
        foregroundCheckJob?.cancel()
        foregroundCheckJob = null
        detachGatewayObservers()
        reconciling = false
        bufferedEvents.clear()
        currentRuntimeSessionId = null
        currentSessionCanResume = true
        activeSessionKey = null
        activeGateway?.close()
    }

    override fun onCleared() {
        connectionJob?.cancel()
        connectionJob = null
        catalogJob?.cancel()
        catalogJob = null
        sessionSearchJob?.cancel()
        sessionSearchJob = null
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
