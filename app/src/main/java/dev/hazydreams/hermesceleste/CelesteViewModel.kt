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
import dev.hazydreams.hermesceleste.network.GatewayRpcException
import dev.hazydreams.hermesceleste.network.InvalidDashboardResponse
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

internal enum class CelesteOperation {
    Connection,
    OpenSession,
    CreateSession,
    Send,
    Interrupt,
    Reconcile,
    Foreground,
    Reconnect,
    Persistence,
    GatewayObserver,
}

/**
 * Application ownership is stricter than a gateway object check. A token
 * carries every identity that can change while a suspend function is away.
 */
internal data class OperationToken(
    val operation: CelesteOperation,
    val operationGeneration: Long,
    val contextGeneration: Long,
    val origin: String?,
    val authMode: SavedAuthMode?,
    val profile: String,
    val storedSessionId: String?,
    val runtimeSessionId: String?,
    val gateway: GatewayConnection?,
    val gatewayGeneration: Long,
    val lifecycleGeneration: Long,
)

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
    val notice: UiNotice? = null,
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

private class SupersededOperationCancellation : CancellationException()

internal class CelesteViewModel(
    private val dashboard: DashboardService = DashboardClient(),
    private val connectionStore: ConnectionStore = InMemoryConnectionStore(),
    private val reconnectDelayMillis: (attempt: Int, wasRunning: Boolean) -> Long = { attempt, wasRunning ->
        if (wasRunning && attempt == 0) 100L else min(5_000L, 1_000L shl attempt.coerceAtMost(2))
    },
    private val diagnosticsSink: DiagnosticsSink = NoopDiagnosticsSink,
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
    private var contextGeneration = 0L
    private var operationGeneration = 0L
    private var gatewayGeneration = 0L
    private var lifecycleGeneration = 0L
    private var currentOrigin: String? = null
    private var currentAuthMode: SavedAuthMode? = null
    private val activeOperations = mutableMapOf<CelesteOperation, Long>()
    private val ownedJobs = mutableMapOf<CelesteOperation, Job>()
    private val connectionStoreMutex = Mutex()
    private var currentDescriptor: SavedConnectionDescriptor? = null
    private var reconnectAttempts = 0
    private var reconciling = false
    private var reconciliationEpoch = 0L
    private var activeReconciliationEpoch: Long? = null
    private var currentSessionCanResume = true
    private var interruptionRequested = false
    private var turnGeneration = 0L
    private var stoppedTurnGeneration: Long? = null
    private val bufferedEvents = mutableListOf<GatewayEvent>()

    init {
        restoreSavedConnection()
    }

    private fun normalizedProfile(name: String): String =
        name.trim().ifBlank { "default" }

    private fun normalizedOrigin(baseUrl: String?): String? = baseUrl?.let { raw ->
        runCatching { DashboardUrlPolicy.normalize(raw) }
            .getOrElse { raw.trim().trimEnd('/') }
            .takeIf(String::isNotBlank)
    }

    private fun captureToken(
        operation: CelesteOperation,
        gateway: GatewayConnection? = this.gateway,
        storedSessionId: String? = currentStoredSessionId,
        runtimeSessionId: String? = currentRuntimeSessionId,
        profile: String = mutableState.value.selectedProfile,
    ): OperationToken {
        ownedJobs[operation]?.cancel()
        ownedJobs.remove(operation)
        val generation = ++operationGeneration
        activeOperations[operation] = generation
        return OperationToken(
            operation = operation,
            operationGeneration = generation,
            contextGeneration = contextGeneration,
            origin = currentOrigin,
            authMode = currentAuthMode,
            profile = normalizedProfile(profile),
            storedSessionId = storedSessionId,
            runtimeSessionId = runtimeSessionId,
            gateway = gateway,
            gatewayGeneration = gatewayGeneration,
            lifecycleGeneration = lifecycleGeneration,
        )
    }

    private fun isCurrent(token: OperationToken): Boolean {
        if (activeOperations[token.operation] != token.operationGeneration) return false
        if (token.contextGeneration != contextGeneration) return false
        if (token.origin != null && token.origin != currentOrigin) return false
        if (token.authMode != null && token.authMode != currentAuthMode) return false
        if (token.profile != normalizedProfile(mutableState.value.selectedProfile)) return false
        if (token.gateway != null && gateway !== token.gateway) return false
        if (token.gatewayGeneration != gatewayGeneration) return false
        if (token.lifecycleGeneration != lifecycleGeneration) return false
        token.storedSessionId?.let { stored ->
            if (currentStoredSessionId != stored && mutableState.value.activeSummary?.id != stored) return false
        }
        token.runtimeSessionId?.let { runtime ->
            if (currentRuntimeSessionId != runtime) return false
        }
        return true
    }

    private fun cancelOwnedOperations() {
        ownedJobs.values.toList().forEach(Job::cancel)
        ownedJobs.clear()
        activeOperations.clear()
    }

    private fun invalidateContext() {
        contextGeneration += 1
        cancelOwnedOperations()
    }

    private fun detachGatewayObservers() {
        gatewayEventsJob?.cancel()
        gatewayStateJob?.cancel()
        gatewayEventsJob = null
        gatewayStateJob = null
        ownedJobs.remove(CelesteOperation.GatewayObserver)?.cancel()
        activeOperations.remove(CelesteOperation.GatewayObserver)
    }

    private fun beginReconciliation(): Long {
        val epoch = ++reconciliationEpoch
        activeReconciliationEpoch = epoch
        reconciling = true
        bufferedEvents.clear()
        return epoch
    }

    private fun finishReconciliation(epoch: Long) {
        if (activeReconciliationEpoch != epoch) return
        activeReconciliationEpoch = null
        reconciling = false
        bufferedEvents.clear()
    }

    private fun invalidateReconciliation() {
        reconciliationEpoch += 1
        activeReconciliationEpoch = null
        reconciling = false
        bufferedEvents.clear()
    }

    private fun stoppedTurnIsActive(): Boolean = stoppedTurnGeneration == turnGeneration

    private fun cancelGatewayOperations() {
        listOf(
            CelesteOperation.OpenSession,
            CelesteOperation.CreateSession,
            CelesteOperation.Send,
            CelesteOperation.Interrupt,
            CelesteOperation.Reconcile,
            CelesteOperation.Foreground,
            CelesteOperation.Reconnect,
            CelesteOperation.GatewayObserver,
        ).forEach { operation ->
            ownedJobs.remove(operation)?.cancel()
            activeOperations.remove(operation)
        }
    }

    private fun launchOwned(
        operation: CelesteOperation,
        token: OperationToken,
        block: suspend () -> Unit,
    ): Job {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                if (activeOperations[operation] == token.operationGeneration) {
                    activeOperations.remove(operation)
                    ownedJobs.remove(operation)
                }
            }
        }
        ownedJobs[operation]?.cancel()
        ownedJobs[operation] = job
        job.start()
        return job
    }

    private suspend fun <T> awaitCurrent(token: OperationToken, block: suspend () -> T): T {
        val result = block()
        if (!isCurrent(token)) throw SupersededOperationCancellation()
        return result
    }

    private fun recordFailure(
        token: OperationToken,
        error: Throwable,
        operation: String,
        scope: UiNoticeScope,
        retryCount: Int = reconnectAttempts,
    ) {
        diagnosticsSink.record(
            SanitizedDiagnostic(
                category = diagnosticCategory(error, scope),
                reasonCode = diagnosticReason(error),
                operation = operation,
                exceptionClass = error::class.simpleName,
                operationGeneration = token.operationGeneration,
                gatewayGeneration = token.gatewayGeneration,
                lifecycleGeneration = token.lifecycleGeneration,
                retryCount = retryCount,
            ),
        )
    }

    private fun publishFailure(
        token: OperationToken,
        error: Throwable,
        operation: String,
        scope: UiNoticeScope,
    ) {
        if (!isCurrent(token) || isExpectedCancellation(error)) return
        recordFailure(token, error, operation, scope)
        mutableState.value = mutableState.value.copy(notice = projectUiNotice(error, scope))
    }

    private fun isAuthenticationFailure(error: Throwable): Boolean {
        val code = (error as? GatewayRpcException)?.code
        return error is AuthenticationRejected || code == 401 || code == 403
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

    fun selectProfile(name: String) {
        if (mutableState.value.profiles.none { it.name == name }) return
        if (normalizedProfile(mutableState.value.selectedProfile) == normalizedProfile(name)) return

        val snapshot = mutableState.value
        val connection = snapshot.probe
        val activeCredential = credential
        beginConnectionAttempt()
        closeGateway()
        mutableState.value = snapshot.copy(
            selectedProfile = name,
            sessions = null,
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            turnState = TurnState.Synchronizing,
            loadingMessage = if (connection != null && activeCredential != null) {
                "Loading your conversations…"
            } else {
                null
            },
            notice = null,
        )

        if (connection == null || activeCredential == null) return
        currentOrigin = normalizedOrigin(connection.baseUrl)
        val token = captureToken(
            operation = CelesteOperation.Connection,
            gateway = null,
            storedSessionId = null,
            runtimeSessionId = null,
            profile = name,
        )
        connectionJob = launchOwned(CelesteOperation.Connection, token) {
            try {
                val loaded = loadDashboard(connection.baseUrl, activeCredential, token)
                if (!isCurrent(token)) return@launchOwned
                publishConnectedDashboard(loaded)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(token)) return@launchOwned
                if (isAuthenticationFailure(error)) {
                    invalidateReusableAuthentication(
                        descriptor = currentDescriptor,
                        probe = connection,
                        token = token,
                    )
                    return@launchOwned
                }
                publishFailure(token, error, "select_profile", UiNoticeScope.Connection)
                mutableState.value = mutableState.value.copy(
                    loadingMessage = null,
                    turnState = TurnState.Idle,
                )
            }
        }
    }

    fun findDashboard() {
        val rawUrl = mutableState.value.dashboardUrl
        if (rawUrl.isBlank()) return
        currentOrigin = normalizedOrigin(rawUrl)
        currentAuthMode = null
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
            notice = null,
        )
        val token = captureToken(
            operation = CelesteOperation.Connection,
            gateway = null,
            storedSessionId = null,
            runtimeSessionId = null,
        )
        connectionJob = launchOwned(CelesteOperation.Connection, token) {
            try {
                val result = awaitCurrent(token) { dashboard.probe(rawUrl) }
                currentOrigin = normalizedOrigin(result.baseUrl)
                if (!isCurrentConnectionAttempt(attempt) || !isCurrent(token)) return@launchOwned
                mutableState.value = mutableState.value.copy(
                    dashboardUrl = result.baseUrl,
                    probe = result,
                )
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                publishFailure(token, error, "probe", UiNoticeScope.Connection)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                publishFailure(token, error, "probe", UiNoticeScope.Connection)
            } finally {
                if (isCurrentConnectionAttempt(attempt) && isCurrent(token)) {
                    mutableState.value = mutableState.value.copy(loadingMessage = null)
                }
            }
        }
    }

    fun loadSessions() {
        val snapshot = mutableState.value
        val connection = snapshot.probe ?: return
        currentOrigin = normalizedOrigin(connection.baseUrl)
        currentAuthMode = null
        val attempt = beginConnectionAttempt()
        closeGateway()
        credential = null
        currentDescriptor = null
        dashboard.clearAuthentication()
        mutableState.value = snapshot.copy(
            connectionPhase = ConnectionPhase.ManualSetup,
            sessions = null,
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            turnState = TurnState.Idle,
            loadingMessage = "Loading your conversations…",
            notice = null,
        )
        val token = captureToken(
            operation = CelesteOperation.Connection,
            gateway = null,
            storedSessionId = null,
            runtimeSessionId = null,
        )
        connectionJob = launchOwned(CelesteOperation.Connection, token) {
            try {
                val passwordProvider = connection.providers.firstOrNull { it.supportsPassword }
                val selectedCredential = if (connection.authRequired) {
                    passwordProvider
                        ?: error("This dashboard requires browser sign-in, which is not in this build yet.")
                    awaitCurrent(token) {
                        dashboard.passwordLogin(
                            baseUrl = connection.baseUrl,
                            provider = passwordProvider.name,
                            username = snapshot.username,
                            password = snapshot.password,
                        )
                    }
                    GatewayCredential.CookieSession
                } else {
                    snapshot.sessionToken
                        .takeIf(String::isNotBlank)
                        ?.let(GatewayCredential::StaticToken)
                        ?: GatewayCredential.None
                }
                val loaded = loadDashboard(connection.baseUrl, selectedCredential, token)
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
                val persistenceError = try {
                    awaitCurrent(token) {
                        connectionStoreMutex.withLock {
                            if (!isCurrentConnectionAttempt(attempt)) throw SupersededOperationCancellation()
                            connectionStore.replace(descriptor, reusableSecret)
                        }
                    }
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    error
                }
                val remembered = RememberedDashboard(loaded, descriptor, persistenceError)

                if (!isCurrentConnectionAttempt(attempt) || !isCurrent(token)) return@launchOwned
                credential = remembered.loaded.credential
                currentDescriptor = remembered.descriptor
                currentAuthMode = remembered.descriptor.authMode
                mutableState.value = mutableState.value.copy(connectionPhase = ConnectionPhase.Connected)
                publishConnectedDashboard(
                    loaded = remembered.loaded,
                    password = "",
                    sessionToken = "",
                    notice = if (remembered.persistenceError == null) null else UiNotice.persistence(),
                )
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                publishFailure(token, error, "load_sessions", UiNoticeScope.Connection)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrentConnectionAttempt(attempt) || !isCurrent(token)) return@launchOwned
                dashboard.clearAuthentication()
                credential = null
                if (isAuthenticationFailure(error)) {
                    currentAuthMode = null
                    connectionStoreMutex.withLock {
                        if (isCurrent(token)) {
                            try {
                                connectionStore.clearSecret()
                            } catch (clearError: CancellationException) {
                                throw clearError
                            } catch (clearError: Throwable) {
                                recordFailure(token, clearError, "clear_rejected_auth", UiNoticeScope.Connection)
                            }
                        }
                    }
                    mutableState.value = mutableState.value.copy(
                        connectionPhase = ConnectionPhase.AuthenticationRequired,
                        notice = UiNotice.authentication(),
                        password = "",
                        sessionToken = "",
                    )
                } else {
                    publishFailure(token, error, "load_sessions", UiNoticeScope.Connection)
                    mutableState.value = mutableState.value.copy(
                        password = "",
                        sessionToken = "",
                    )
                }
            } finally {
                if (isCurrentConnectionAttempt(attempt) && isCurrent(token)) {
                    mutableState.value = mutableState.value.copy(loadingMessage = null)
                }
            }
        }
    }

    fun leaveSessionList() {
        beginConnectionAttempt()
        closeGateway()
        credential = null
        currentOrigin = null
        currentAuthMode = null
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
        currentOrigin = null
        currentAuthMode = null
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
        currentAuthMode = null
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
            notice = null,
        )
        val token = captureToken(
            operation = CelesteOperation.Connection,
            gateway = null,
            storedSessionId = null,
            runtimeSessionId = null,
        )
        connectionJob = launchOwned(CelesteOperation.Connection, token) {
            val clearError = try {
                awaitCurrent(token) {
                    connectionStoreMutex.withLock { connectionStore.clearSecret() }
                }
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error
            }
            val saved = try {
                awaitCurrent(token) { connectionStoreMutex.withLock { connectionStore.load() } }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (activeCredential == GatewayCredential.CookieSession && snapshot.probe != null) {
                try {
                    awaitCurrent(token) { dashboard.logout(snapshot.probe.baseUrl) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    recordFailure(token, error, "sign_out", UiNoticeScope.Connection)
                }
            }
            dashboard.clearAuthentication()
            if (!isCurrentConnectionAttempt(attempt) || !isCurrent(token)) return@launchOwned
            currentDescriptor = saved?.descriptor
            mutableState.value = manualState(
                descriptor = saved?.descriptor,
                notice = if (clearError == null) null else UiNotice.persistence(),
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
        currentAuthMode = null
        mutableState.value = CelesteUiState(
            connectionPhase = ConnectionPhase.ManualSetup,
            loadingMessage = "Forgetting this connection…",
        )
        val token = captureToken(
            operation = CelesteOperation.Connection,
            gateway = null,
            storedSessionId = null,
            runtimeSessionId = null,
        )
        connectionJob = launchOwned(CelesteOperation.Connection, token) {
            val error = try {
                awaitCurrent(token) {
                    connectionStoreMutex.withLock { connectionStore.forget() }
                }
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error
            }
            if (activeCredential == GatewayCredential.CookieSession && snapshot.probe != null) {
                try {
                    awaitCurrent(token) { dashboard.logout(snapshot.probe.baseUrl) }
                } catch (error: CancellationException) {
                    throw error
                } catch (logoutError: Throwable) {
                    recordFailure(token, logoutError, "forget_connection", UiNoticeScope.Connection)
                }
            }
            dashboard.clearAuthentication()
            if (!isCurrentConnectionAttempt(attempt) || !isCurrent(token)) return@launchOwned
            mutableState.value = CelesteUiState(
                connectionPhase = ConnectionPhase.ManualSetup,
                notice = if (error == null) null else UiNotice.persistence(),
            )
        }
    }

    private fun restoreSavedConnection() {
        val attempt = beginConnectionAttempt()
        closeGateway()
        credential = null
        currentDescriptor = null
        currentOrigin = null
        currentAuthMode = null
        mutableState.value = CelesteUiState(
            connectionPhase = ConnectionPhase.CheckingSavedConnection,
            loadingMessage = "Checking this device…",
        )
        val token = captureToken(
            operation = CelesteOperation.Connection,
            gateway = null,
            storedSessionId = null,
            runtimeSessionId = null,
        )
        connectionJob = launchOwned(CelesteOperation.Connection, token) {
            try {
                val saved = awaitCurrent(token) {
                    connectionStoreMutex.withLock { connectionStore.load() }
                }
                if (!isCurrentConnectionAttempt(attempt) || !isCurrent(token)) return@launchOwned
                when (val decision = connectionBootstrapDecision(saved)) {
                    ConnectionBootstrapDecision.ManualSetup -> {
                        mutableState.value = manualState()
                    }
                    is ConnectionBootstrapDecision.Prefill -> {
                        currentOrigin = normalizedOrigin(decision.descriptor.baseUrl)
                        currentAuthMode = decision.descriptor.authMode
                        mutableState.value = manualState(decision.descriptor)
                    }
                    is ConnectionBootstrapDecision.Restore -> {
                        currentOrigin = normalizedOrigin(decision.descriptor.baseUrl)
                        currentAuthMode = decision.descriptor.authMode
                        restoreConnection(decision, attempt, token)
                    }
                }
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                publishFailure(token, error, "restore_saved_connection", UiNoticeScope.Connection)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrentConnectionAttempt(attempt) || !isCurrent(token)) return@launchOwned
                mutableState.value = manualState(
                    descriptor = null,
                    notice = UiNotice.persistence(),
                )
            }
        }
    }

    private suspend fun restoreConnection(
        decision: ConnectionBootstrapDecision.Restore,
        attempt: Long,
        token: OperationToken,
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
        try {
            val normalized = DashboardUrlPolicy.normalize(descriptor.baseUrl)
            if (normalized != descriptor.baseUrl) {
                throw AuthenticationRejected("The saved dashboard address changed.")
            }
            val probe = awaitCurrent(token) { dashboard.probe(normalized) }
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
            val loaded = loadDashboard(normalized, restoredCredential, token)
            if (!isCurrentConnectionAttempt(attempt) || !isCurrent(token)) return
            val persistenceError = if (descriptor.authMode == SavedAuthMode.ProviderSession) {
                val refreshed = dashboard.exportAuthentication(descriptor.baseUrl)
                if (refreshed == null) {
                    IOException("The refreshed Hermes session was unavailable.")
                } else {
                    try {
                        awaitCurrent(token) {
                            connectionStoreMutex.withLock {
                                if (!isCurrentConnectionAttempt(attempt)) {
                                    throw SupersededOperationCancellation()
                                }
                                connectionStore.replace(descriptor, ReusableSecret(refreshed.value))
                            }
                        }
                        null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        error
                    }
                }
            } else {
                null
            }
            credential = loaded.credential
            currentDescriptor = descriptor
            currentOrigin = normalizedOrigin(probe.baseUrl)
            currentAuthMode = descriptor.authMode
            mutableState.value = mutableState.value.copy(
                dashboardUrl = probe.baseUrl,
                probe = probe,
            )
            publishConnectedDashboard(
                loaded,
                notice = if (persistenceError == null) null else UiNotice.persistence(),
            )
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            if (isCurrentConnectionAttempt(attempt) && isCurrent(token)) {
                mutableState.value = mutableState.value.copy(
                    connectionPhase = ConnectionPhase.RestoreFailed,
                    notice = projectUiNotice(error, UiNoticeScope.Connection),
                    loadingMessage = null,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!isCurrentConnectionAttempt(attempt) || !isCurrent(token)) return
            if (isAuthenticationFailure(error)) {
                credential = null
                currentDescriptor = null
                dashboard.clearAuthentication()
                awaitCurrent(token) {
                    connectionStoreMutex.withLock { connectionStore.clearSecret() }
                }
                currentAuthMode = null
                mutableState.value = manualState(
                    descriptor = descriptor,
                    phase = ConnectionPhase.AuthenticationRequired,
                    probe = restoredProbe,
                    notice = UiNotice.authentication(),
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
                    notice = projectUiNotice(error, UiNoticeScope.Connection),
                )
            }
        } finally {
            if (isCurrentConnectionAttempt(attempt) && isCurrent(token)) {
                mutableState.value = mutableState.value.copy(loadingMessage = null)
            }
        }
    }

    private suspend fun loadDashboard(
        baseUrl: String,
        selectedCredential: GatewayCredential,
        token: OperationToken,
    ): LoadedDashboard {
        val sessions = awaitCurrent(token) { dashboard.listSessions(baseUrl, selectedCredential) }
        val profiles = awaitCurrent(token) { dashboard.listProfiles(baseUrl, selectedCredential) }
        return LoadedDashboard(selectedCredential, sessions, profiles)
    }

    private suspend fun invalidateReusableAuthentication(
        descriptor: SavedConnectionDescriptor?,
        probe: DashboardProbeResult? = null,
        token: OperationToken? = null,
    ) {
        val snapshot = mutableState.value
        val safeBaseUrl = descriptor?.baseUrl ?: probe?.baseUrl ?: snapshot.dashboardUrl
        val safeUsername = descriptor?.username ?: snapshot.username
        val safeProbe = probe ?: snapshot.probe
        credential = null
        currentDescriptor = null
        dashboard.clearAuthentication()
        if (token == null || isCurrent(token)) {
            if (token == null) {
                connectionStoreMutex.withLock { connectionStore.clearSecret() }
            } else {
                awaitCurrent(token) {
                    connectionStoreMutex.withLock { connectionStore.clearSecret() }
                }
            }
        }
        currentAuthMode = null
        mutableState.value = manualState(
            descriptor = descriptor,
            phase = ConnectionPhase.AuthenticationRequired,
            probe = safeProbe,
            notice = UiNotice.authentication(),
        ).copy(
            dashboardUrl = safeBaseUrl,
            savedAuthMode = descriptor?.authMode ?: snapshot.savedAuthMode,
            username = safeUsername,
        )
    }

    private fun publishConnectedDashboard(
        loaded: LoadedDashboard,
        password: String = "",
        sessionToken: String = "",
        notice: UiNotice? = null,
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
        dashboardUrl = descriptor?.baseUrl.orEmpty(),
        probe = probe,
        savedAuthMode = descriptor?.authMode,
        username = descriptor?.username.orEmpty(),
        notice = notice,
    )

    private fun beginConnectionAttempt(): Long {
        connectionAttempt += 1
        invalidateContext()
        connectionJob = null
        return connectionAttempt
    }

    private fun isCurrentConnectionAttempt(attempt: Long): Boolean = connectionAttempt == attempt

    fun openSession(summary: StoredSession) {
        val connection = mutableState.value.probe ?: return
        val activeCredential = credential ?: return
        invalidateContext()
        closeGateway()
        currentSessionCanResume = true
        currentRuntimeSessionId = null
        currentStoredSessionId = null
        mutableState.value = mutableState.value.copy(
            activeSummary = summary,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            turnState = TurnState.Synchronizing,
            loadingMessage = "Opening ${summary.title.ifBlank { "conversation" }}…",
            notice = null,
        )

        val newGateway = dashboard.createGateway(connection.baseUrl, activeCredential)
        gateway = newGateway
        gatewayGeneration += 1
        observeGateway(newGateway)
        val token = captureToken(
            operation = CelesteOperation.OpenSession,
            gateway = newGateway,
            storedSessionId = summary.id,
            runtimeSessionId = null,
        )
        launchOwned(CelesteOperation.OpenSession, token) {
            val reconciliationEpoch = beginReconciliation()
            try {
                awaitCurrent(token) { newGateway.connect() }
                reconcile(
                    activeGateway = newGateway,
                    storedSessionId = summary.id,
                    token = token,
                    reconciliationEpoch = reconciliationEpoch,
                )
                if (!isCurrent(token)) return@launchOwned
                reconnectAttempts = 0
                mutableState.value = mutableState.value.copy(loadingMessage = null)
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                if (!isCurrent(token)) return@launchOwned
                val failureNotice = projectUiNotice(error, UiNoticeScope.Session)
                publishFailure(token, error, "open_session", UiNoticeScope.Session)
                mutableState.value = mutableState.value.copy(
                    loadingMessage = null,
                    notice = failureNotice ?: UiNotice.reconnecting(),
                    turnState = TurnState.Reconnecting,
                )
                scheduleReconnect(wasRunning = false, initialNotice = failureNotice)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(token)) return@launchOwned
                if (isAuthenticationFailure(error)) {
                    val descriptor = currentDescriptor
                    invalidateReusableAuthentication(
                        descriptor = descriptor,
                        probe = connection,
                        token = token,
                    )
                    closeGateway()
                    return@launchOwned
                }
                val failureNotice = projectUiNotice(error, UiNoticeScope.Session)
                publishFailure(token, error, "open_session", UiNoticeScope.Session)
                mutableState.value = mutableState.value.copy(
                    loadingMessage = null,
                    notice = failureNotice ?: UiNotice.reconnecting(),
                    turnState = TurnState.Reconnecting,
                )
                scheduleReconnect(wasRunning = false, initialNotice = failureNotice)
            } finally {
                finishReconciliation(reconciliationEpoch)
            }
        }
    }

    fun createNewConversation() {
        val snapshot = mutableState.value
        val connection = snapshot.probe ?: return
        val activeCredential = credential ?: return
        val selectedProfile = normalizedProfile(snapshot.selectedProfile)
        invalidateContext()
        closeGateway()
        currentRuntimeSessionId = null
        currentStoredSessionId = null
        mutableState.value = snapshot.copy(
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            turnState = TurnState.Synchronizing,
            loadingMessage = "Starting a new $selectedProfile conversation…",
            notice = null,
        )

        val newGateway = dashboard.createGateway(connection.baseUrl, activeCredential)
        gateway = newGateway
        gatewayGeneration += 1
        observeGateway(newGateway)
        val token = captureToken(
            operation = CelesteOperation.CreateSession,
            gateway = newGateway,
            storedSessionId = null,
            runtimeSessionId = null,
            profile = selectedProfile,
        )
        launchOwned(CelesteOperation.CreateSession, token) {
            val reconciliationEpoch = beginReconciliation()
            try {
                awaitCurrent(token) { newGateway.connect() }
                if (!isCurrent(token)) return@launchOwned
                val created = awaitCurrent(token) { newGateway.createSession(selectedProfile) }
                if (!isCurrent(token)) return@launchOwned
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
                    profile = returnedProfile ?: selectedProfile,
                )
                mutableState.value = mutableState.value.copy(
                    sessions = listOf(summary) + mutableState.value.sessions.orEmpty()
                        .filterNot { it.id == summary.id },
                    activeSummary = summary,
                    turnState = TurnState.Idle,
                    loadingMessage = null,
                    notice = null,
                )
                replayBufferedEvents(reconciliationEpoch, token)
                reconnectAttempts = 0
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                if (!isCurrent(token)) return@launchOwned
                publishFailure(token, error, "create_session", UiNoticeScope.Session)
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Idle,
                    loadingMessage = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(token)) return@launchOwned
                if (isAuthenticationFailure(error)) {
                    val descriptor = currentDescriptor
                    invalidateReusableAuthentication(
                        descriptor = descriptor,
                        probe = connection,
                        token = token,
                    )
                    closeGateway()
                    return@launchOwned
                }
                publishFailure(token, error, "create_session", UiNoticeScope.Session)
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Idle,
                    loadingMessage = null,
                )
            } finally {
                finishReconciliation(reconciliationEpoch)
            }
        }
    }

    fun leaveConversation() {
        invalidateContext()
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
            notice = null,
        )
        // prompt.submit creates the durable row before work begins. From this point on,
        // uncertain delivery must reconcile by stored ID and must never create/resend.
        currentSessionCanResume = true
        turnGeneration += 1
        stoppedTurnGeneration = null
        interruptionRequested = false
        val token = captureToken(
            operation = CelesteOperation.Send,
            gateway = activeGateway,
            storedSessionId = storedId,
            runtimeSessionId = runtimeId,
        )
        launchOwned(CelesteOperation.Send, token) {
            try {
                awaitCurrent(token) { activeGateway.submitPrompt(runtimeId, text) }
                if (!isCurrent(token)) return@launchOwned
                mutableState.value = mutableState.value.copy(
                    messages = mutableState.value.messages.map { message ->
                        if (message.id == localId) message.copy(pending = false) else message
                    },
                )
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                if (!isCurrent(token)) return@launchOwned
                publishFailure(token, error, "send_message", UiNoticeScope.Turn)
                try {
                    reconcile(activeGateway, storedId, token)
                } catch (reconcileError: CancellationException) {
                    throw reconcileError
                } catch (reconcileError: Throwable) {
                    if (isAuthenticationFailure(reconcileError)) {
                        val descriptor = currentDescriptor
                        invalidateReusableAuthentication(
                            descriptor = descriptor,
                            probe = mutableState.value.probe,
                            token = token,
                        )
                        closeGateway()
                        return@launchOwned
                    }
                    publishFailure(token, reconcileError, "reconcile_after_send", UiNoticeScope.Turn)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(token)) return@launchOwned
                if (isAuthenticationFailure(error)) {
                    val descriptor = currentDescriptor
                    invalidateReusableAuthentication(
                        descriptor = descriptor,
                        probe = mutableState.value.probe,
                        token = token,
                    )
                    closeGateway()
                    return@launchOwned
                }
                publishFailure(token, error, "send_message", UiNoticeScope.Turn)
                try {
                    reconcile(activeGateway, storedId, token)
                } catch (reconcileError: CancellationException) {
                    throw reconcileError
                } catch (reconcileError: Throwable) {
                    if (isAuthenticationFailure(reconcileError)) {
                        val descriptor = currentDescriptor
                        invalidateReusableAuthentication(
                            descriptor = descriptor,
                            probe = mutableState.value.probe,
                            token = token,
                        )
                        closeGateway()
                        return@launchOwned
                    }
                    publishFailure(token, reconcileError, "reconcile_after_send", UiNoticeScope.Turn)
                }
            }
        }
    }

    fun interrupt() {
        val activeGateway = gateway ?: return
        val runtimeId = currentRuntimeSessionId ?: return
        val storedId = currentStoredSessionId ?: mutableState.value.activeSummary?.id ?: return
        if (mutableState.value.turnState != TurnState.Running) return
        ownedJobs.remove(CelesteOperation.Send)?.cancel()
        activeOperations.remove(CelesteOperation.Send)
        turnGeneration += 1
        stoppedTurnGeneration = turnGeneration
        interruptionRequested = true
        mutableState.value = mutableState.value.copy(
            turnState = TurnState.Synchronizing,
            notice = null,
        )
        val token = captureToken(
            operation = CelesteOperation.Interrupt,
            gateway = activeGateway,
            storedSessionId = storedId,
            runtimeSessionId = null,
        )
        launchOwned(CelesteOperation.Interrupt, token) {
            try {
                awaitCurrent(token) { activeGateway.interruptSession(runtimeId) }
                reconcile(activeGateway, storedId, token)
                if (!isCurrent(token)) return@launchOwned
                mutableState.value = mutableState.value.copy(turnState = TurnState.Idle, notice = null)
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                if (!isCurrent(token)) return@launchOwned
                publishFailure(token, error, "interrupt", UiNoticeScope.Turn)
                mutableState.value = mutableState.value.copy(turnState = TurnState.Idle)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(token)) return@launchOwned
                if (isAuthenticationFailure(error)) {
                    val descriptor = currentDescriptor
                    invalidateReusableAuthentication(
                        descriptor = descriptor,
                        probe = mutableState.value.probe,
                        token = token,
                    )
                    closeGateway()
                    return@launchOwned
                }
                publishFailure(token, error, "interrupt", UiNoticeScope.Turn)
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Idle,
                )
            }
        }
    }

    fun reconnectNow() {
        if (gateway == null || mutableState.value.activeSummary == null) return
        ownedJobs.remove(CelesteOperation.Reconnect)?.cancel()
        activeOperations.remove(CelesteOperation.Reconnect)
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        scheduleReconnect(wasRunning = mutableState.value.turnState == TurnState.Running, immediate = true)
    }

    fun onBackground() {
        lifecycleGeneration += 1
        detachGatewayObservers()
        listOf(
            CelesteOperation.Foreground,
            CelesteOperation.Reconnect,
            CelesteOperation.Reconcile,
        ).forEach { operation ->
            ownedJobs.remove(operation)?.cancel()
            activeOperations.remove(operation)
        }
        reconnectJob?.cancel()
        reconnectJob = null
        foregroundCheckJob?.cancel()
        foregroundCheckJob = null
        invalidateReconciliation()
        val descriptor = currentDescriptor ?: return
        if (descriptor.authMode != SavedAuthMode.ProviderSession) return
        if (credential != GatewayCredential.CookieSession) return
        val refreshed = dashboard.exportAuthentication(descriptor.baseUrl) ?: return
        val token = captureToken(
            operation = CelesteOperation.Persistence,
            gateway = null,
            storedSessionId = null,
            runtimeSessionId = null,
        )
        launchOwned(CelesteOperation.Persistence, token) {
            try {
                awaitCurrent(token) {
                    connectionStoreMutex.withLock {
                        if (credential != GatewayCredential.CookieSession || currentDescriptor != descriptor) {
                            throw SupersededOperationCancellation()
                        }
                        connectionStore.replace(descriptor, ReusableSecret(refreshed.value))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isCurrent(token)) recordFailure(token, error, "persist_background_auth", UiNoticeScope.Connection)
            }
        }
    }

    fun onForeground() {
        val activeGateway = gateway ?: return
        // ON_STOP invalidates the old collector token. Rebase both collectors
        // before health recovery so a healthy socket does not remain silent.
        observeGateway(activeGateway)
        val storedSessionId = currentStoredSessionId
            ?: mutableState.value.activeSummary?.id
            ?: return
        if (foregroundCheckJob?.isActive == true || reconciling) return
        if (activeGateway.state.value != GatewayConnectionState.Connected) {
            reconnectNow()
            return
        }
        val token = captureToken(
            operation = CelesteOperation.Foreground,
            gateway = activeGateway,
            storedSessionId = storedSessionId,
            runtimeSessionId = null,
        )
        foregroundCheckJob = launchOwned(CelesteOperation.Foreground, token) {
            try {
                awaitCurrent(token) {
                    activeGateway.request(
                        method = "session.list",
                        params = buildJsonObject { put("limit", 1) },
                        timeoutMillis = 8_000,
                    )
                }
                if (currentSessionCanResume) reconcile(activeGateway, storedSessionId, token)
                if (!isCurrent(token)) return@launchOwned
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                if (!isCurrent(token)) return@launchOwned
                val wasRunning = mutableState.value.turnState == TurnState.Running
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Reconnecting,
                    notice = UiNotice.reconnecting(),
                )
                activeGateway.close()
                scheduleReconnect(wasRunning = wasRunning, immediate = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isCurrent(token)) return@launchOwned
                if (isAuthenticationFailure(error)) {
                    val descriptor = currentDescriptor
                    invalidateReusableAuthentication(
                        descriptor = descriptor,
                        probe = mutableState.value.probe,
                        token = token,
                    )
                    closeGateway()
                    return@launchOwned
                }
                recordFailure(token, error, "foreground_health", UiNoticeScope.Session)
                val wasRunning = mutableState.value.turnState == TurnState.Running
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Reconnecting,
                    notice = UiNotice.reconnecting(),
                )
                activeGateway.close()
                scheduleReconnect(wasRunning = wasRunning, immediate = true)
            }
        }
    }

    private var currentRuntimeSessionId: String? = null
    private var currentStoredSessionId: String? = null

    private fun observeGateway(activeGateway: GatewayConnection) {
        detachGatewayObservers()
        val token = captureToken(
            operation = CelesteOperation.GatewayObserver,
            gateway = activeGateway,
            storedSessionId = null,
            runtimeSessionId = null,
        )
        gatewayEventsJob = viewModelScope.launch {
            activeGateway.events.collect { event ->
                if (!isCurrent(token)) return@collect
                if (reconciling) {
                    bufferedEvents += event
                } else {
                    applyEvent(event)
                }
            }
        }
        gatewayStateJob = viewModelScope.launch {
            activeGateway.state.collect { connectionState ->
                if (!isCurrent(token)) return@collect
                if (connectionState is GatewayConnectionState.Disconnected) {
                    val wasRunning = mutableState.value.turnState == TurnState.Running
                    mutableState.value = mutableState.value.copy(
                        turnState = TurnState.Reconnecting,
                        notice = UiNotice.reconnecting(),
                    )
                    scheduleReconnect(wasRunning)
                }
            }
        }
    }

    private fun replayBufferedEvents(epoch: Long, owner: OperationToken) {
        while (activeReconciliationEpoch == epoch && isCurrent(owner)) {
            if (bufferedEvents.isEmpty()) return
            val events = bufferedEvents.toList()
            bufferedEvents.clear()
            events.forEach { event ->
                if (isCurrent(owner)) applyEvent(event)
            }
        }
    }

    private suspend fun reconcile(
        activeGateway: GatewayConnection,
        storedSessionId: String,
        token: OperationToken? = null,
        reconciliationEpoch: Long? = null,
    ) {
        // Resume may replace the runtime session ID. Reconciliation owns the
        // stored-session relationship, so do not pin it to the pre-resume ID.
        val owner = (token ?: captureToken(
            operation = CelesteOperation.Reconcile,
            gateway = activeGateway,
            storedSessionId = storedSessionId,
            runtimeSessionId = null,
        )).copy(runtimeSessionId = null)
        if (!isCurrent(owner)) throw SupersededOperationCancellation()
        val epoch = reconciliationEpoch ?: beginReconciliation()
        try {
            val resumed = awaitCurrent(owner) { activeGateway.resumeStoredSession(storedSessionId) }
            if (!isCurrent(owner)) throw SupersededOperationCancellation()
            if (resumed.storedSessionId != storedSessionId) {
                throw InvalidDashboardResponse("Hermes resumed a different conversation.")
            }
            if (activeReconciliationEpoch != epoch || !isCurrent(owner)) {
                throw SupersededOperationCancellation()
            }
            applyResumedSession(resumed)
            replayBufferedEvents(epoch, owner)
            if (isCurrent(owner) && stoppedTurnIsActive()) {
                interruptionRequested = false
            }
        } finally {
            // Always release the global reconciliation gate. An older owner
            // must not clear a newer epoch's buffer or gate.
            finishReconciliation(epoch)
        }
    }

    private fun applyResumedSession(resumed: ResumedSession) {
        currentRuntimeSessionId = resumed.runtimeSessionId
        currentStoredSessionId = resumed.storedSessionId
        currentSessionCanResume = true
        if (resumed.running == false && interruptionRequested) {
            // Keep the stop barrier through buffered-event replay. The
            // authoritative snapshot settles the turn, but a late start/busy
            // event from the old turn must not re-arm it.
            stoppedTurnGeneration = turnGeneration
        }
        val streamingSuffix = unpersistedInflightText(
            inflight = resumed.inflightAssistantText,
            messages = resumed.messages,
        )
        mutableState.value = mutableState.value.copy(
            messages = resumed.messages,
            streamingText = streamingSuffix,
            turnState = when (resumed.running) {
                true -> TurnState.Running
                false -> TurnState.Idle
                null -> if (resumed.hasLiveProjection) TurnState.Running else TurnState.Idle
            },
            notice = null,
        )
    }

    private fun applyEvent(event: GatewayEvent) {
        val runtimeId = currentRuntimeSessionId ?: return
        if (event.sessionId.isNotBlank() && event.sessionId != runtimeId) return
        when (event.type) {
            "message.start" -> {
                if (interruptionRequested || stoppedTurnIsActive()) return
                if (mutableState.value.streamingText.isNotBlank()) finalizeAssistant()
                mutableState.value = mutableState.value.copy(
                    streamingText = "",
                    turnState = TurnState.Running,
                    notice = null,
                )
            }

            "message.delta" -> {
                if (interruptionRequested || stoppedTurnIsActive()) return
                val delta = event.payload.string("text").orEmpty()
                if (delta.isNotEmpty()) {
                    mutableState.value = mutableState.value.copy(
                        streamingText = mutableState.value.streamingText + delta,
                        turnState = TurnState.Running,
                    )
                }
            }

            "message.interim" -> {
                if (interruptionRequested || stoppedTurnIsActive()) return
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
                if (interruptionRequested || stoppedTurnIsActive()) return
                val status = event.payload.string("status")
                val content = event.payload.string("text")
                    ?: event.payload.string("content")
                    ?: event.payload.string("rendered")
                    ?: ""
                finalizeAssistant(
                    suppliedContent = if (status == "error") "" else content,
                    keepRunning = false,
                )
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Idle,
                    notice = if (status == "error") {
                        UiNotice.serverTurnFailure()
                    } else {
                        mutableState.value.notice
                    },
                )
            }

            "error", "message.error" -> {
                if (interruptionRequested || stoppedTurnIsActive()) return
                finalizeAssistant(keepRunning = false)
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Idle,
                    notice = UiNotice.genericTurnFailure(),
                )
            }

            "message.interrupted", "session.interrupted" -> {
                interruptionRequested = false
                finalizeAssistant(keepRunning = false)
                mutableState.value = mutableState.value.copy(turnState = TurnState.Idle)
            }

            "session.busy" -> {
                val busy = event.payload.boolean("busy") == true
                if (busy && (interruptionRequested || stoppedTurnIsActive())) return
                if (!busy) interruptionRequested = false
                mutableState.value = mutableState.value.copy(
                    turnState = if (busy) TurnState.Running else TurnState.Idle,
                )
            }

            "session.info" -> {
                event.payload.boolean("running")?.let { running ->
                    if (running && (interruptionRequested || stoppedTurnIsActive())) return
                    if (!running) interruptionRequested = false
                    mutableState.value = mutableState.value.copy(
                        turnState = if (running) TurnState.Running else TurnState.Idle,
                    )
                }
            }

            "tool.start", "tool_call" -> {
                if (interruptionRequested || stoppedTurnIsActive()) return
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
                if (interruptionRequested || stoppedTurnIsActive()) return
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
        profile: String,
        token: OperationToken,
        reconciliationEpoch: Long? = null,
    ) {
        if (!isCurrent(token)) throw SupersededOperationCancellation()
        val previousStoredId = currentStoredSessionId
        val epoch = reconciliationEpoch ?: beginReconciliation()
        try {
            val created = awaitCurrent(token) { activeGateway.createSession(normalizedProfile(profile)) }
            if (!isCurrent(token)) return
            currentRuntimeSessionId = created.runtimeSessionId
            currentStoredSessionId = created.storedSessionId
            val previousSummary = mutableState.value.activeSummary
                ?: throw IOException("No draft conversation is open.")
            val updatedSummary = previousSummary.copy(
                id = created.storedSessionId,
                profile = created.profile?.takeIf(String::isNotBlank) ?: normalizedProfile(profile),
            )
            mutableState.value = mutableState.value.copy(
                activeSummary = updatedSummary,
                sessions = mutableState.value.sessions?.map { session ->
                    if (session.id == previousStoredId) updatedSummary else session
                },
                turnState = TurnState.Idle,
                notice = null,
            )
            replayBufferedEvents(epoch, token)
        } finally {
            finishReconciliation(epoch)
        }
    }

    private fun scheduleReconnect(
        wasRunning: Boolean,
        immediate: Boolean = false,
        initialNotice: UiNotice? = null,
    ) {
        val activeGateway = gateway ?: return
        val storedSessionId = currentStoredSessionId ?: mutableState.value.activeSummary?.id ?: return
        if (reconnectJob?.isActive == true) return
        val token = captureToken(
            operation = CelesteOperation.Reconnect,
            gateway = activeGateway,
            // A blank draft may be recreated with a new stored ID. Do not
            // make that authoritative replacement look stale to the loop.
            storedSessionId = storedSessionId.takeIf { currentSessionCanResume },
            // A resume is allowed to replace the runtime ID.
            runtimeSessionId = null,
        )
        mutableState.value = mutableState.value.copy(
            turnState = TurnState.Reconnecting,
            notice = initialNotice ?: UiNotice.reconnecting(),
        )
        reconnectJob = launchOwned(CelesteOperation.Reconnect, token) {
            var lastFailureNotice: UiNotice? = null
            try {
                while (
                    gateway === activeGateway &&
                    isCurrent(token) &&
                    reconnectAttempts < MAX_RECONNECT_ATTEMPTS
                ) {
                    val delayMillis = if (immediate && reconnectAttempts == 0) {
                        0L
                    } else {
                        reconnectDelayMillis(reconnectAttempts, wasRunning)
                    }
                    if (delayMillis > 0) delay(delayMillis)
                    if (!isCurrent(token)) return@launchOwned
                    val reconciliationEpoch = beginReconciliation()
                    try {
                        awaitCurrent(token) { activeGateway.connect() }
                        if (currentSessionCanResume) {
                            reconcile(
                                activeGateway = activeGateway,
                                storedSessionId = storedSessionId,
                                token = token,
                                reconciliationEpoch = reconciliationEpoch,
                            )
                        } else {
                            recreateBlankSession(
                                activeGateway = activeGateway,
                                profile = mutableState.value.selectedProfile,
                                token = token,
                                reconciliationEpoch = reconciliationEpoch,
                            )
                        }
                        if (!isCurrent(token)) return@launchOwned
                        reconnectAttempts = 0
                        mutableState.value = mutableState.value.copy(notice = null)
                        return@launchOwned
                    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                        if (!isCurrent(token)) return@launchOwned
                        lastFailureNotice = projectUiNotice(error, UiNoticeScope.Session)
                        recordFailure(token, error, "reconnect", UiNoticeScope.Session)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        if (!isCurrent(token)) return@launchOwned
                        lastFailureNotice = projectUiNotice(error, UiNoticeScope.Session)
                        if (isAuthenticationFailure(error)) {
                            val descriptor = currentDescriptor
                            recordFailure(token, error, "reconnect", UiNoticeScope.Connection)
                            invalidateReusableAuthentication(descriptor, token = token)
                            closeGateway()
                            return@launchOwned
                        }
                        recordFailure(token, error, "reconnect", UiNoticeScope.Session)
                    } finally {
                        finishReconciliation(reconciliationEpoch)
                    }
                    reconnectAttempts += 1
                    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                        val exhaustedNotice = when (lastFailureNotice?.category) {
                            UiNoticeCategory.RateLimited,
                            UiNoticeCategory.InvalidResponse -> lastFailureNotice
                            else -> UiNotice.unavailable()
                        }
                        mutableState.value = mutableState.value.copy(
                            turnState = TurnState.Reconnecting,
                            notice = exhaustedNotice,
                        )
                        return@launchOwned
                    }
                    mutableState.value = mutableState.value.copy(
                        turnState = TurnState.Reconnecting,
                        notice = UiNotice.reconnecting(),
                    )
                }
            } finally {
                if (activeOperations[CelesteOperation.Reconnect] == token.operationGeneration) {
                    reconnectJob = null
                }
            }
        }
    }

    private fun closeGateway() {
        val activeGateway = gateway
        detachGatewayObservers()
        gatewayGeneration += 1
        cancelGatewayOperations()
        gateway = null
        reconnectJob = null
        foregroundCheckJob = null
        invalidateReconciliation()
        currentRuntimeSessionId = null
        currentStoredSessionId = null
        currentSessionCanResume = true
        interruptionRequested = false
        turnGeneration += 1
        stoppedTurnGeneration = null
        activeGateway?.close()
    }

    override fun onCleared() {
        invalidateContext()
        connectionJob = null
        closeGateway()
        dashboard.clearAuthentication()
        super.onCleared()
    }

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 3

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
