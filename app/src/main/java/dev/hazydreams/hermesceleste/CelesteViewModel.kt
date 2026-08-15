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
import dev.hazydreams.hermesceleste.network.AttachmentDraft
import dev.hazydreams.hermesceleste.network.AttachmentReadiness
import dev.hazydreams.hermesceleste.network.AttachmentReference
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
import dev.hazydreams.hermesceleste.network.RedirectOutcome
import dev.hazydreams.hermesceleste.network.ResumedSession
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.network.SubmitOptions
import dev.hazydreams.hermesceleste.network.SteerOutcome
import dev.hazydreams.hermesceleste.network.boolean
import dev.hazydreams.hermesceleste.network.createSession
import dev.hazydreams.hermesceleste.network.explicitRedirectCapability
import dev.hazydreams.hermesceleste.network.interruptSession
import dev.hazydreams.hermesceleste.network.redirectSession
import dev.hazydreams.hermesceleste.network.resumeStoredSession
import dev.hazydreams.hermesceleste.network.steerSession
import dev.hazydreams.hermesceleste.network.string
import dev.hazydreams.hermesceleste.network.submitPrompt
import dev.hazydreams.hermesceleste.network.submitQueuedPrompt
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    UnsupportedGateway,
}

enum class BusyInputPolicy {
    Steer,
    Queue,
    Redirect,
}

enum class ComposerAction {
    None,
    Send,
    Steer,
    Queue,
    Redirect,
    Stop,
}

enum class DeliveryStatus {
    None,
    Pending,
    Accepted,
    Rejected,
    Uncertain,
}

data class ActiveTurnPayload(
    val text: String,
    val attachments: List<AttachmentDraft> = emptyList(),
)

internal fun composerAction(
    turnState: TurnState,
    payload: ActiveTurnPayload,
    policy: BusyInputPolicy,
    redirectSupported: Boolean,
): ComposerAction {
    if (payload.attachments.any { it.readiness != AttachmentReadiness.Ready }) {
        return ComposerAction.None
    }
    return when (turnState) {
        TurnState.Idle -> if (payload.text.isNotBlank() || payload.attachments.isNotEmpty()) {
            ComposerAction.Send
        } else {
            ComposerAction.None
        }
        TurnState.Synchronizing, TurnState.Reconnecting, TurnState.UnsupportedGateway -> ComposerAction.None
        TurnState.Running -> when {
            payload.attachments.isNotEmpty() -> ComposerAction.Queue
            payload.text.isBlank() -> ComposerAction.Stop
            policy == BusyInputPolicy.Queue -> ComposerAction.Queue
            policy == BusyInputPolicy.Redirect && redirectSupported -> ComposerAction.Redirect
            else -> ComposerAction.Steer
        }
    }
}

private enum class ActiveTurnOperationKind {
    Submit,
    Steer,
    Queue,
    Redirect,
}

private fun ActiveTurnOperationKind.composerAction(): ComposerAction = when (this) {
    ActiveTurnOperationKind.Submit -> ComposerAction.Send
    ActiveTurnOperationKind.Steer -> ComposerAction.Steer
    ActiveTurnOperationKind.Queue -> ComposerAction.Queue
    ActiveTurnOperationKind.Redirect -> ComposerAction.Redirect
}

private data class AdmissionEvidence(
    val scopeKey: String,
    val durableMessages: List<DurableMessageEvidence>,
    val inflightUserText: String,
    val inflightCorrections: List<CorrectionEvidence>,
    val queuedUserTexts: List<String>,
)

private data class DurableMessageEvidence(
    val identity: String,
    val occurrence: Int,
    val role: String,
    val text: String,
)
private data class CorrectionEvidence(
    val text: String,
    val assistantOffset: Int?,
)

private data class PendingOperation(
    val sequence: Long,
    val kind: ActiveTurnOperationKind,
    val gateway: GatewayConnection,
    val generation: Long,
    val runtimeSessionId: String,
    val storedSessionId: String,
    val profile: String,
    val scopeKey: String,
    val text: String,
    val draftSnapshot: String,
    val attachments: List<AttachmentReference>,
    val admissionBaseline: AdmissionEvidence?,
    val localMessageId: String? = null,
    val uncertain: Boolean = false,
    val cancelledByStop: Boolean = false,
)

private data class AcceptedGuidanceProjection(
    val operation: PendingOperation,
)

private data class PendingStop(
    val sequence: Long,
    val gateway: GatewayConnection,
    val generation: Long,
    val runtimeSessionId: String,
    val storedSessionId: String,
    val profile: String,
    val scopeKey: String,
    val correctionSequence: Long? = null,
    val rpcAccepted: Boolean = false,
    val uncertain: Boolean = false,
)
private enum class OperationResult {
    Accepted,
    Rejected,
    Unsupported,
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
    val attachments: List<AttachmentDraft> = emptyList(),
    val turnState: TurnState = TurnState.Idle,
    val busyInputPolicy: BusyInputPolicy = BusyInputPolicy.Steer,
    val redirectSupported: Boolean = false,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.None,
    val lastAction: ComposerAction? = null,
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

private data class PreservedDraft(
    val scopeKey: String?,
    val text: String,
    val attachments: List<AttachmentDraft>,
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
    private val operationSequence = AtomicLong(0)
    private var pendingOperation: PendingOperation? = null
    private var pendingStop: PendingStop? = null
    private var gatewayGeneration = 0L
    private val busyInputPolicies = mutableMapOf<String, BusyInputPolicy>()
    private var credential: GatewayCredential? = null
    private var gateway: GatewayConnection? = null
    private var gatewayEventsJob: Job? = null
    private var gatewayStateJob: Job? = null
    private var reconnectJob: Job? = null
    private var foregroundCheckJob: Job? = null
    private var connectionJob: Job? = null
    private var connectionAttempt = 0L
    private val connectionStoreMutex = Mutex()
    private val reconciliationMutex = Mutex()
    private var currentDescriptor: SavedConnectionDescriptor? = null
    private var preservedDraft: PreservedDraft? = null
    private var reconnectAttempts = 0
    private var reconciling = false
    private var reconciliationEpoch = 0L
    private var activeReconciliationEpoch: Long? = null
    private var activeReconciliationGateway: GatewayConnection? = null
    private var activeReconciliationGeneration: Long? = null
    private var currentSessionCanResume = true
    private var authoritativeAdmissionEvidence: AdmissionEvidence? = null
    private val acceptedGuidanceProjections = mutableListOf<AcceptedGuidanceProjection>()
    private var pendingAcceptedReconciliation: PendingOperation? = null
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

    fun updateAttachments(value: List<AttachmentDraft>) {
        mutableState.value = mutableState.value.copy(attachments = value.toList())
    }

    fun selectBusyInputPolicy(policy: BusyInputPolicy) {
        val snapshot = mutableState.value
        if (policy == BusyInputPolicy.Redirect && !snapshot.redirectSupported) return
        val key = profilePreferenceKey()
        busyInputPolicies[key] = policy
        mutableState.value = snapshot.copy(busyInputPolicy = policy)
    }

    fun selectProfile(name: String) {
        val snapshot = mutableState.value
        if (snapshot.profiles.none { it.name == name }) return
        if (snapshot.activeSummary != null && snapshot.activeSummary.profile != name) {
            closeGateway()
            preservedDraft = null
            mutableState.value = snapshot.copy(
                selectedProfile = name,
                activeSummary = null,
                messages = emptyList(),
                streamingText = "",
                draft = "",
                attachments = emptyList(),
                turnState = TurnState.Idle,
                busyInputPolicy = busyInputPolicies[profilePreferenceKey(name)] ?: BusyInputPolicy.Steer,
                redirectSupported = false,
                deliveryStatus = DeliveryStatus.None,
                lastAction = null,
                loadingMessage = null,
                errorMessage = null,
            )
            return
        }
        mutableState.value = snapshot.copy(
            selectedProfile = name,
            busyInputPolicy = busyInputPolicies[profilePreferenceKey(name)] ?: BusyInputPolicy.Steer,
        )
    }

    fun findDashboard() {
        val rawUrl = mutableState.value.dashboardUrl
        if (rawUrl.isBlank()) return
        val attempt = beginConnectionAttempt()
        closeGateway()
        preservedDraft = null
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
            attachments = emptyList(),
            deliveryStatus = DeliveryStatus.None,
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
        preservedDraft = null
        credential = null
        mutableState.value = mutableState.value.copy(
            connectionPhase = ConnectionPhase.ManualSetup,
            sessions = null,
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            attachments = emptyList(),
            deliveryStatus = DeliveryStatus.None,
            errorMessage = null,
        )
    }

    fun retrySavedConnection() {
        restoreSavedConnection()
    }

    fun useAnotherConnection() {
        beginConnectionAttempt()
        closeGateway()
        preservedDraft = null
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
        preservedDraft = null
        credential = null
        currentDescriptor = null
        mutableState.value = snapshot.copy(
            connectionPhase = ConnectionPhase.ManualSetup,
            sessions = null,
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            attachments = emptyList(),
            password = "",
            sessionToken = "",
            deliveryStatus = DeliveryStatus.None,
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
        preservedDraft = null
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
        val sessions = dashboard.listSessions(baseUrl, selectedCredential)
        val profiles = dashboard.listProfiles(baseUrl, selectedCredential)
        return LoadedDashboard(selectedCredential, sessions, profiles)
    }

    private suspend fun invalidateReusableAuthentication(
        descriptor: SavedConnectionDescriptor?,
        probe: DashboardProbeResult? = null,
    ) {
        val current = mutableState.value
        val currentScope = current.activeSummary?.let { summary ->
            sessionScopeKey(summary.id, summary.profile)
        }
        if (current.draft.isNotEmpty() || current.attachments.isNotEmpty()) {
            preservedDraft = PreservedDraft(
                scopeKey = currentScope,
                text = current.draft,
                attachments = current.attachments,
            )
        }
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
        ).copy(
            draft = current.draft,
            attachments = current.attachments,
            deliveryStatus = if (current.draft.isNotEmpty() || current.attachments.isNotEmpty()) {
                DeliveryStatus.Uncertain
            } else {
                DeliveryStatus.None
            },
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

    fun openSession(summary: StoredSession) {
        val connection = mutableState.value.probe ?: return
        val activeCredential = credential ?: return
        closeGateway()
        val restoredDraft = preservedDraft?.takeIf {
            it.scopeKey == sessionScopeKey(summary.id, summary.profile)
        }
        preservedDraft = null
        currentSessionCanResume = true
        mutableState.value = mutableState.value.copy(
            activeSummary = summary,
            messages = emptyList(),
            streamingText = "",
            draft = restoredDraft?.text.orEmpty(),
            attachments = restoredDraft?.attachments.orEmpty(),
            turnState = TurnState.Synchronizing,
            busyInputPolicy = busyInputPolicies[profilePreferenceKey(summary.profile)] ?: BusyInputPolicy.Steer,
            redirectSupported = false,
            deliveryStatus = if (restoredDraft == null) DeliveryStatus.None else DeliveryStatus.Uncertain,
            lastAction = null,
            loadingMessage = "Opening ${summary.title.ifBlank { "conversation" }}…",
            errorMessage = null,
        )

        val newGateway = dashboard.createGateway(connection.baseUrl, activeCredential)
        gatewayGeneration += 1
        gateway = newGateway
        observeGateway(newGateway)
        viewModelScope.launch {
            runCatching {
                newGateway.connect()
                reconcile(newGateway, summary.id, summary.profile)
            }.onSuccess {
                if (gateway !== newGateway) return@onSuccess
                reconnectAttempts = 0
                mutableState.value = mutableState.value.copy(loadingMessage = null)
            }.onFailure { error ->
                if (gateway !== newGateway) return@onFailure
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
            streamingText = "",
            draft = "",
            attachments = emptyList(),
            turnState = TurnState.Synchronizing,
            busyInputPolicy = busyInputPolicies[profilePreferenceKey(selectedProfile)] ?: BusyInputPolicy.Steer,
            redirectSupported = false,
            deliveryStatus = DeliveryStatus.None,
            lastAction = null,
            loadingMessage = "Starting a new $selectedProfile conversation…",
            errorMessage = null,
        )

        val newGateway = dashboard.createGateway(connection.baseUrl, activeCredential)
        gatewayGeneration += 1
        gateway = newGateway
        observeGateway(newGateway)
        viewModelScope.launch {
            runCatching {
                reconciliationMutex.withLock {
                    val generation = gatewayGeneration
                    val epoch = beginReconciliation(newGateway, generation)
                    try {
                        newGateway.connect()
                        val created = newGateway.createSession(selectedProfile)
                        if (!isCurrentReconciliation(newGateway, generation, epoch)) {
                            throw IOException("The Hermes connection changed while creating the conversation.")
                        }
                        val returnedProfile = created.profile?.takeIf(String::isNotBlank)
                        if (returnedProfile != null && !returnedProfile.equals(selectedProfile, ignoreCase = true)) {
                            throw IOException("Hermes created this conversation in $returnedProfile instead of $selectedProfile.")
                        }
                        currentRuntimeSessionId = created.runtimeSessionId
                        currentStoredSessionId = created.storedSessionId
                        authoritativeAdmissionEvidence = AdmissionEvidence(
                            scopeKey = sessionScopeKey(created.storedSessionId, selectedProfile),
                            durableMessages = emptyList(),
                            inflightUserText = "",
                            inflightCorrections = emptyList(),
                            queuedUserTexts = emptyList(),
                        )
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
                        mutableState.value = mutableState.value.copy(
                            sessions = listOf(summary) + mutableState.value.sessions.orEmpty()
                                .filterNot { it.id == summary.id },
                            activeSummary = summary,
                            turnState = TurnState.Idle,
                            loadingMessage = null,
                            errorMessage = null,
                        )
                        replayBufferedEvents(newGateway, generation, epoch)
                    } finally {
                        finishReconciliation(epoch)
                    }
                }
            }.onSuccess {
                if (gateway !== newGateway) return@onSuccess
                reconnectAttempts = 0
            }.onFailure { error ->
                if (gateway !== newGateway) return@onFailure
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
        preservedDraft = null
        mutableState.value = mutableState.value.copy(
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            attachments = emptyList(),
            turnState = TurnState.Idle,
            redirectSupported = false,
            deliveryStatus = DeliveryStatus.None,
            lastAction = null,
            loadingMessage = null,
            errorMessage = null,
        )
    }

    fun sendMessage() {
        val snapshot = mutableState.value
        val action = composerAction(
            turnState = snapshot.turnState,
            payload = ActiveTurnPayload(snapshot.draft, snapshot.attachments),
            policy = snapshot.busyInputPolicy,
            redirectSupported = snapshot.redirectSupported,
        )
        when (action) {
            ComposerAction.Send -> dispatchActiveTurn(ActiveTurnOperationKind.Submit)
            ComposerAction.Steer -> dispatchActiveTurn(ActiveTurnOperationKind.Steer)
            ComposerAction.Queue -> dispatchActiveTurn(ActiveTurnOperationKind.Queue)
            ComposerAction.Redirect -> dispatchActiveTurn(ActiveTurnOperationKind.Redirect)
            ComposerAction.Stop -> interrupt()
            ComposerAction.None -> Unit
        }
    }

    fun steerMessage() {
        dispatchActiveTurn(ActiveTurnOperationKind.Steer)
    }

    fun queueMessage() {
        dispatchActiveTurn(ActiveTurnOperationKind.Queue)
    }

    fun redirectMessage() {
        if (mutableState.value.redirectSupported) {
            dispatchActiveTurn(ActiveTurnOperationKind.Redirect)
        }
    }

    private fun guidanceProjectionId(operation: PendingOperation): String =
        "active-guidance-${operation.sequence}-${operation.storedSessionId}"

    private fun guidanceProjectionMessage(operation: PendingOperation): ConversationMessage =
        ConversationMessage(
            role = "user",
            text = operation.text.ifBlank { "Attachment queued for the next turn." },
            id = guidanceProjectionId(operation),
            pending = true,
        )

    private fun retainAcceptedGuidance(operation: PendingOperation) {
        if (operation.kind == ActiveTurnOperationKind.Submit) return
        acceptedGuidanceProjections.removeAll { it.operation.sequence == operation.sequence }
        acceptedGuidanceProjections += AcceptedGuidanceProjection(operation)
    }

    private fun removeAcceptedGuidance(operation: PendingOperation) {
        acceptedGuidanceProjections.removeAll { it.operation.sequence == operation.sequence }
    }

    private fun guidanceOperationsForScope(scopeKey: String): List<PendingOperation> {
        val pending = pendingOperation
            ?.takeIf { operation ->
                operation.kind != ActiveTurnOperationKind.Submit && operation.scopeKey == scopeKey
            }
        val accepted = acceptedGuidanceProjections
            .asSequence()
            .map(AcceptedGuidanceProjection::operation)
            .filter { operation -> operation.scopeKey == scopeKey }
        return (listOfNotNull(pending) + accepted.toList()).distinctBy(PendingOperation::sequence)
    }

    private fun removeGuidanceProjectionFromState(operation: PendingOperation) {
        val projectionId = guidanceProjectionId(operation)
        mutableState.value = mutableState.value.copy(
            messages = mutableState.value.messages.filterNot { it.id == projectionId },
        )
    }

    private fun dispatchActiveTurn(kind: ActiveTurnOperationKind) {
        val activeGateway = gateway ?: return
        val snapshot = mutableState.value
        val runtimeId = currentRuntimeSessionId ?: return
        val storedId = currentStoredSessionId ?: return
        val draftSnapshot = snapshot.draft
        val text = draftSnapshot.trim()
        if (text.isBlank() && snapshot.attachments.isEmpty()) return
        if (pendingOperation != null) return
        if (kind != ActiveTurnOperationKind.Submit && isAcceptedReconciliationPending(activeGateway)) return
        if (snapshot.attachments.any { it.readiness != AttachmentReadiness.Ready }) {
            mutableState.value = snapshot.copy(
                deliveryStatus = DeliveryStatus.Rejected,
                lastAction = kind.composerAction(),
                errorMessage = "Attachment is still uploading or needs to be retried.",
            )
            return
        }
        val attachments = snapshot.attachments.map(AttachmentDraft::reference)
        if (kind == ActiveTurnOperationKind.Steer || kind == ActiveTurnOperationKind.Redirect) {
            if (attachments.isNotEmpty() || text.isBlank()) return
            if (kind == ActiveTurnOperationKind.Redirect && !snapshot.redirectSupported) return
        }
        if (kind != ActiveTurnOperationKind.Submit && snapshot.turnState != TurnState.Running) return
        if (kind == ActiveTurnOperationKind.Submit && snapshot.turnState != TurnState.Idle) return

        val localMessageId = if (kind == ActiveTurnOperationKind.Submit) {
            "local-${localMessageCounter.incrementAndGet()}"
        } else {
            null
        }
        val operationProfile = snapshot.activeSummary?.profile ?: snapshot.selectedProfile
        val operationScopeKey = sessionScopeKey(storedId, operationProfile)
        val operation = PendingOperation(
            sequence = operationSequence.incrementAndGet(),
            kind = kind,
            gateway = activeGateway,
            generation = gatewayGeneration,
            runtimeSessionId = runtimeId,
            storedSessionId = storedId,
            profile = operationProfile,
            scopeKey = operationScopeKey,
            text = text,
            draftSnapshot = draftSnapshot,
            attachments = attachments,
            admissionBaseline = authoritativeAdmissionEvidence?.takeIf {
                it.scopeKey == operationScopeKey
            },
            localMessageId = localMessageId,
        )
        pendingOperation = operation
        mutableState.value = snapshot.copy(
            messages = when {
                localMessageId != null -> snapshot.messages + ConversationMessage(
                    role = "user",
                    text = text,
                    id = localMessageId,
                    pending = true,
                )
                kind != ActiveTurnOperationKind.Submit -> snapshot.messages + guidanceProjectionMessage(operation)
                else -> snapshot.messages
            },
            streamingText = if (kind == ActiveTurnOperationKind.Submit) "" else snapshot.streamingText,
            turnState = if (kind == ActiveTurnOperationKind.Submit) TurnState.Running else snapshot.turnState,
            deliveryStatus = DeliveryStatus.Pending,
            lastAction = operation.kind.composerAction(),
            errorMessage = null,
        )
        if (kind == ActiveTurnOperationKind.Submit) currentSessionCanResume = true

        viewModelScope.launch {
            runCatching {
                when (kind) {
                    ActiveTurnOperationKind.Submit -> activeGateway.submitPrompt(
                        operation.runtimeSessionId,
                        operation.text,
                        SubmitOptions(attachments = operation.attachments),
                    ).let(::submitOperationResult)
                    ActiveTurnOperationKind.Queue -> activeGateway.submitQueuedPrompt(
                        operation.runtimeSessionId,
                        operation.text,
                        SubmitOptions(attachments = operation.attachments),
                    ).let(::submitOperationResult)
                    ActiveTurnOperationKind.Steer -> activeGateway.steerSession(
                        operation.runtimeSessionId,
                        operation.text,
                    ).let { outcome ->
                        when (outcome) {
                            SteerOutcome.Steered, SteerOutcome.Queued -> OperationResult.Accepted
                            SteerOutcome.Rejected -> OperationResult.Rejected
                            SteerOutcome.Unsupported -> OperationResult.Unsupported
                        }
                    }
                    ActiveTurnOperationKind.Redirect -> activeGateway.redirectSession(
                        operation.runtimeSessionId,
                        operation.text,
                    ).let { outcome ->
                        when (outcome) {
                            RedirectOutcome.Redirected, RedirectOutcome.Queued -> OperationResult.Accepted
                            RedirectOutcome.Rejected -> OperationResult.Rejected
                            RedirectOutcome.Unsupported -> OperationResult.Unsupported
                        }
                    }
                }
            }.onSuccess { result ->
                if (!isCurrentOperation(operation)) return@onSuccess
                if (operation.cancelledByStop || pendingStop != null) {
                    // Stop and the correction may cross on the same socket. The
                    // correction response is not authoritative once Stop has
                    // started; keep it until the following resume snapshot.
                    pendingOperation = operation.copy(
                        uncertain = true,
                        cancelledByStop = true,
                    )
                    return@onSuccess
                }
                pendingOperation = null
                when (result) {
                    OperationResult.Accepted -> {
                        retainAcceptedGuidance(operation)
                        clearAcceptedDraft(operation)
                        mutableState.value = mutableState.value.copy(
                            deliveryStatus = DeliveryStatus.Accepted,
                            lastAction = operation.kind.composerAction(),
                            errorMessage = null,
                        )
                        if (operation.kind != ActiveTurnOperationKind.Submit && gateway === activeGateway) {
                            val reconciliation = try {
                                pendingAcceptedReconciliation = operation
                                runCatching {
                                    // An accepted correction is only a gateway admission. Reconcile
                                    // immediately so the local acknowledgement can be replaced by
                                    // the authoritative projection before a turn-complete event.
                                    reconcile(activeGateway, operation.storedSessionId, operation.profile)
                                }
                            } finally {
                                if (pendingAcceptedReconciliation?.sequence == operation.sequence) {
                                    pendingAcceptedReconciliation = null
                                }
                            }
                            if (reconciliation.isFailure && gateway === activeGateway) {
                                mutableState.value = mutableState.value.copy(
                                    deliveryStatus = DeliveryStatus.Accepted,
                                    lastAction = operation.kind.composerAction(),
                                    errorMessage = "Guidance accepted; confirming its placement with Hermes…",
                                )
                            }
                        }
                    }

                    OperationResult.Rejected, OperationResult.Unsupported -> {
                        discardOptimisticSubmission(operation)
                        mutableState.value = mutableState.value.copy(
                            turnState = if (operation.kind == ActiveTurnOperationKind.Submit) {
                                TurnState.Idle
                            } else {
                                mutableState.value.turnState
                            },
                            redirectSupported = if (result == OperationResult.Unsupported &&
                                operation.kind == ActiveTurnOperationKind.Redirect
                            ) {
                                false
                            } else {
                                mutableState.value.redirectSupported
                            },
                            deliveryStatus = DeliveryStatus.Rejected,
                            lastAction = operation.kind.composerAction(),
                            errorMessage = if (result == OperationResult.Unsupported) {
                                "That active-turn action is unavailable. You can queue it for the next turn."
                            } else {
                                when (operation.kind) {
                                    ActiveTurnOperationKind.Redirect -> "Redirect was not accepted. You can queue it for the next turn."
                                    ActiveTurnOperationKind.Steer -> "Guidance was not accepted. You can queue it for the next turn."
                                    else -> "Hermes did not accept that message."
                                }
                            },
                        )
                    }
                }
            }.onFailure { error ->
                if (!isCurrentOperation(operation)) return@onFailure
                if (operation.cancelledByStop || pendingStop != null) {
                    pendingOperation = operation.copy(
                        uncertain = true,
                        cancelledByStop = true,
                    )
                    return@onFailure
                }
                val definiteRejection = error is GatewayRpcException &&
                    (error.code == 4010 || error.code == -32601)
                if (definiteRejection) {
                    pendingOperation = null
                    discardOptimisticSubmission(operation)
                } else {
                    // Keep the operation envelope, including its draft and
                    // sequence, until resume can prove admission or
                    // non-admission. This is deliberately not a resend queue.
                    pendingOperation = operation.copy(uncertain = true)
                }
                mutableState.value = mutableState.value.copy(
                    turnState = if (definiteRejection && operation.kind == ActiveTurnOperationKind.Submit) {
                        TurnState.Idle
                    } else {
                        mutableState.value.turnState
                    },
                    deliveryStatus = if (definiteRejection) DeliveryStatus.Rejected else DeliveryStatus.Uncertain,
                    lastAction = operation.kind.composerAction(),
                    errorMessage = if (definiteRejection) {
                        "That active-turn action is unavailable. You can queue it for the next turn."
                    } else {
                        "Delivery uncertain; reconnecting before you try again."
                    },
                )
                if (!definiteRejection && gateway === activeGateway) {
                    val reconciliation = runCatching {
                        reconcile(activeGateway, operation.storedSessionId, operation.profile)
                    }
                    if (reconciliation.isFailure && gateway === activeGateway) {
                        mutableState.value = mutableState.value.copy(
                            turnState = TurnState.Reconnecting,
                        )
                        scheduleReconnect(wasRunning = true, immediate = true)
                    }
                }
            }
        }
    }

    private fun submitOperationResult(result: JsonObject): OperationResult =
        when (val status = result.string("status")?.lowercase()) {
            "accepted", "queued", "streaming", "submitted", "running" -> OperationResult.Accepted
            "rejected", "failed", "error" -> OperationResult.Rejected
            null -> throw IOException("Hermes returned no prompt status.")
            else -> throw IOException("Hermes returned an unknown prompt status: $status")
        }

    private fun discardOptimisticSubmission(operation: PendingOperation) {
        removeAcceptedGuidance(operation)
        val projectionId = guidanceProjectionId(operation)
        val localId = operation.localMessageId
        mutableState.value = mutableState.value.copy(
            messages = mutableState.value.messages.filterNot { message ->
                message.id == projectionId || (localId != null && message.id == localId)
            },
        )
    }

    private fun isAcceptedReconciliationPending(activeGateway: GatewayConnection): Boolean =
        pendingAcceptedReconciliation?.let { operation ->
            operation.gateway === activeGateway && operation.generation == gatewayGeneration
        } == true

    private fun isCurrentOperation(operation: PendingOperation): Boolean =
        pendingOperation?.sequence == operation.sequence &&
            gateway === operation.gateway &&
            gatewayGeneration == operation.generation &&
            currentRuntimeSessionId == operation.runtimeSessionId &&
            currentStoredSessionId == operation.storedSessionId &&
            mutableState.value.activeSummary?.profile == operation.profile &&
            sessionScopeKey(operation.storedSessionId, operation.profile) == operation.scopeKey

    private fun clearAcceptedDraft(operation: PendingOperation) {
        val snapshot = mutableState.value
        val sameDraft = snapshot.draft == operation.draftSnapshot &&
            snapshot.attachments.map(AttachmentDraft::reference) == operation.attachments
        mutableState.value = snapshot.copy(
            draft = if (sameDraft) "" else snapshot.draft,
            attachments = if (sameDraft) emptyList() else snapshot.attachments,
            messages = operation.localMessageId?.let { localId ->
                snapshot.messages.map { message ->
                    if (message.id == localId) message.copy(pending = false) else message
                }
            } ?: snapshot.messages,
        )
    }

    fun interrupt() {
        val activeGateway = gateway ?: return
        val runtimeId = currentRuntimeSessionId ?: return
        val storedId = currentStoredSessionId ?: return
        val snapshot = mutableState.value
        if (snapshot.turnState != TurnState.Running || pendingStop != null) return
        val correction = pendingOperation?.let { operation ->
            operation.copy(
                uncertain = true,
                cancelledByStop = true,
            )
        }
        pendingOperation = correction
        val stop = PendingStop(
            sequence = operationSequence.incrementAndGet(),
            gateway = activeGateway,
            generation = gatewayGeneration,
            runtimeSessionId = runtimeId,
            storedSessionId = storedId,
            profile = snapshot.activeSummary?.profile ?: snapshot.selectedProfile,
            scopeKey = sessionScopeKey(
                storedId,
                snapshot.activeSummary?.profile ?: snapshot.selectedProfile,
            ),
            correctionSequence = correction?.sequence,
        )
        pendingStop = stop
        mutableState.value = snapshot.copy(
            turnState = TurnState.Synchronizing,
            deliveryStatus = DeliveryStatus.Pending,
            lastAction = ComposerAction.Stop,
            errorMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                activeGateway.interruptSession(runtimeId)
                if (!isCurrentStop(stop)) return@runCatching
                pendingStop = pendingStop?.copy(rpcAccepted = true, uncertain = false)
                reconcile(activeGateway, storedId, stop.profile)
            }.onSuccess {
                val currentStop = pendingStop
                if (currentStop == null || !isCurrentStop(currentStop)) return@onSuccess
                if (currentStop.rpcAccepted && mutableState.value.turnState != TurnState.Running) {
                    pendingStop = null
                    val correctionWasUncertain = pendingOperation?.sequence == currentStop.correctionSequence
                    mutableState.value = mutableState.value.copy(
                        deliveryStatus = if (correctionWasUncertain) {
                            DeliveryStatus.Uncertain
                        } else {
                            DeliveryStatus.Accepted
                        },
                        lastAction = ComposerAction.Stop,
                        errorMessage = if (correctionWasUncertain) {
                            "Turn stopped; the correction was not confirmed, so your draft is still here."
                        } else {
                            null
                        },
                    )
                }
            }.onFailure { error ->
                if (!isCurrentStop(stop)) return@onFailure
                pendingStop = pendingStop?.copy(uncertain = true)
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Reconnecting,
                    deliveryStatus = DeliveryStatus.Uncertain,
                    lastAction = ComposerAction.Stop,
                    errorMessage = error.message ?: "Stop delivery uncertain; reconnecting before you try again.",
                )
                scheduleReconnect(wasRunning = true, immediate = true)
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
        if (foregroundCheckJob?.isActive == true || reconciling) return
        if (activeGateway.state.value != GatewayConnectionState.Connected) {
            reconnectNow()
            return
        }
        foregroundCheckJob = viewModelScope.launch {
            val health = runCatching {
                activeGateway.request(
                    method = "session.list",
                    params = buildJsonObject { put("limit", 1) },
                    timeoutMillis = 8_000,
                )
                if (currentSessionCanResume) {
                    reconcile(
                        activeGateway,
                        storedSessionId,
                        mutableState.value.activeSummary?.profile,
                    )
                }
            }
            if (health.isFailure && gateway === activeGateway) {
                val wasRunning = mutableState.value.turnState == TurnState.Running
                val operationWasPending = pendingOperation != null || pendingStop != null
                pendingOperation = pendingOperation?.copy(uncertain = true)
                pendingStop = pendingStop?.copy(uncertain = true)
                gatewayGeneration += 1
                activeGateway.close()
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Reconnecting,
                    deliveryStatus = if (operationWasPending) {
                        DeliveryStatus.Uncertain
                    } else {
                        mutableState.value.deliveryStatus
                    },
                    errorMessage = if (operationWasPending) {
                        "Delivery uncertain; reconnecting before you try again."
                    } else {
                        health.exceptionOrNull()?.message ?: "Reconnecting to Hermes…"
                    },
                )
                scheduleReconnect(wasRunning = wasRunning, immediate = true)
            }
            foregroundCheckJob = null
        }
    }

    private var currentRuntimeSessionId: String? = null
    private var currentStoredSessionId: String? = null

    private fun normalizedEndpoint(): String =
        runCatching { DashboardUrlPolicy.normalize(mutableState.value.dashboardUrl) }
            .getOrDefault(mutableState.value.dashboardUrl.trim())
            .trimEnd('/')

    private fun profilePreferenceKey(profile: String? = null): String =
        "${normalizedEndpoint()}|${profile ?: mutableState.value.activeSummary?.profile ?: mutableState.value.selectedProfile}"

    private fun sessionScopeKey(storedId: String, profile: String): String =
        "${profilePreferenceKey(profile)}|$storedId"

    private fun beginReconciliation(
        activeGateway: GatewayConnection,
        generation: Long,
    ): Long {
        val epoch = ++reconciliationEpoch
        activeReconciliationEpoch = epoch
        activeReconciliationGateway = activeGateway
        activeReconciliationGeneration = generation
        reconciling = true
        bufferedEvents.clear()
        return epoch
    }

    private fun finishReconciliation(epoch: Long) {
        if (activeReconciliationEpoch != epoch) return
        activeReconciliationEpoch = null
        activeReconciliationGateway = null
        activeReconciliationGeneration = null
        reconciling = false
        bufferedEvents.clear()
    }

    private fun invalidateReconciliation() {
        reconciliationEpoch += 1
        activeReconciliationEpoch = null
        activeReconciliationGateway = null
        activeReconciliationGeneration = null
        reconciling = false
        bufferedEvents.clear()
    }

    private fun isCurrentReconciliation(
        activeGateway: GatewayConnection,
        generation: Long,
        epoch: Long,
    ): Boolean = activeReconciliationEpoch == epoch &&
        activeReconciliationGateway === activeGateway &&
        activeReconciliationGeneration == generation &&
        isCurrentGateway(activeGateway, generation)

    private fun replayBufferedEvents(
        activeGateway: GatewayConnection,
        generation: Long,
        epoch: Long,
    ) {
        while (isCurrentReconciliation(activeGateway, generation, epoch)) {
            if (bufferedEvents.isEmpty()) return
            val events = bufferedEvents.toList()
            bufferedEvents.clear()
            events.forEach { event ->
                if (isCurrentReconciliation(activeGateway, generation, epoch)) {
                    applyEvent(event)
                }
            }
        }
    }

    private fun observeGateway(activeGateway: GatewayConnection) {
        gatewayEventsJob = viewModelScope.launch {
            activeGateway.events.collect { event ->
                if (gateway !== activeGateway) return@collect
                if (reconciling &&
                    activeReconciliationGateway === activeGateway &&
                    activeReconciliationGeneration == gatewayGeneration
                ) {
                    bufferedEvents += event
                } else if (!reconciling) {
                    applyEvent(event)
                }
            }
        }
        gatewayStateJob = viewModelScope.launch {
            activeGateway.state.collect { connectionState ->
                if (gateway !== activeGateway) return@collect
                if (connectionState is GatewayConnectionState.Disconnected) {
                    pendingOperation = pendingOperation?.copy(uncertain = true)
                    pendingStop = pendingStop?.copy(uncertain = true)
                    if (pendingOperation != null || pendingStop != null) {
                        mutableState.value = mutableState.value.copy(
                            deliveryStatus = DeliveryStatus.Uncertain,
                            errorMessage = "Delivery uncertain; reconnecting before you try again.",
                        )
                    }
                    gatewayGeneration += 1
                    val wasRunning = mutableState.value.turnState == TurnState.Running
                    mutableState.value = mutableState.value.copy(
                        turnState = TurnState.Reconnecting,
                        errorMessage = mutableState.value.errorMessage ?: connectionState.reason,
                    )
                    scheduleReconnect(wasRunning)
                }
            }
        }
    }

    private suspend fun reconcile(
        activeGateway: GatewayConnection,
        storedSessionId: String,
        expectedProfile: String? = null,
    ) = reconciliationMutex.withLock {
        reconcileLocked(activeGateway, storedSessionId, expectedProfile)
    }

    private suspend fun reconcileLocked(
        activeGateway: GatewayConnection,
        storedSessionId: String,
        expectedProfile: String?,
    ) {
        val generation = gatewayGeneration
        if (!isCurrentGateway(activeGateway, generation)) return
        val profile = (expectedProfile
            ?: mutableState.value.activeSummary?.profile
            ?: mutableState.value.selectedProfile)
            .trim()
            .ifBlank { "default" }
        val expectedOrigin = normalizedEndpoint()
        val epoch = beginReconciliation(activeGateway, generation)
        try {
            val resumed = activeGateway.resumeStoredSession(storedSessionId, profile)
            if (!isCurrentReconciliation(activeGateway, generation, epoch)) return
            if (resumed.storedSessionId != storedSessionId) {
                throw IOException("Hermes resumed a different conversation.")
            }
            val resumedProfile = resumed.profile?.trim().orEmpty()
            if (resumedProfile.isBlank() || resumedProfile != profile) {
                throw IOException("Hermes resumed this conversation in a different profile.")
            }
            if (resumed.origin != null && !resumeOriginMatches(resumed.origin, expectedOrigin)) {
                throw IOException("Hermes resumed this conversation from a different gateway origin.")
            }
            applyResumedSession(resumed, activeGateway, generation)
            replayBufferedEvents(activeGateway, generation, epoch)
        } finally {
            // The mutex makes resume/apply/replay one transaction. Epoch and
            // generation guards keep a stale socket from clearing a newer
            // reconciliation's event buffer or publishing its snapshot.
            finishReconciliation(epoch)
        }
    }

    private fun isCurrentGateway(activeGateway: GatewayConnection, generation: Long): Boolean =
        gateway === activeGateway && gatewayGeneration == generation

    private fun resumeOriginMatches(origin: String, expectedOrigin: String): Boolean {
        val trimmedOrigin = origin.trim().trimEnd('/')
        if (trimmedOrigin == expectedOrigin) return true
        return runCatching {
            DashboardUrlPolicy.normalize(trimmedOrigin).trimEnd('/') == expectedOrigin
        }.getOrDefault(false)
    }


    private enum class PendingOperationResolution {
        Accepted,
        Rejected,
        Unresolved,
    }

    private fun applyResumedSession(
        resumed: ResumedSession,
        activeGateway: GatewayConnection,
        generation: Long,
    ) {
        currentRuntimeSessionId = resumed.runtimeSessionId
        currentStoredSessionId = resumed.storedSessionId
        currentSessionCanResume = true
        val profile = mutableState.value.activeSummary?.profile ?: mutableState.value.selectedProfile
        val scopeKey = sessionScopeKey(resumed.storedSessionId, profile)
        rebindPendingOperations(resumed, activeGateway, generation)
        val operationResolution = resolvePendingOperation(resumed)
        reconcileAcceptedGuidance(resumed, scopeKey)
        val projection = projectResumedTurn(resumed)
        val localGuidance = guidanceOperationsForScope(scopeKey)
            .map(::guidanceProjectionMessage)
        val current = mutableState.value
        mutableState.value = current.copy(
            messages = projection.messages + localGuidance,
            streamingText = projection.streamingText,
            turnState = resumedTurnState(resumed),
            redirectSupported = resumed.supportsActiveTurnRedirect,
            errorMessage = if (current.deliveryStatus == DeliveryStatus.Uncertain) {
                current.errorMessage
            } else {
                null
            },
        )
        resolvePendingStopAfterResume(resumed, operationResolution)
        authoritativeAdmissionEvidence = admissionEvidence(resumed, scopeKey)
    }

    private fun reconcileAcceptedGuidance(
        resumed: ResumedSession,
        scopeKey: String,
    ) {
        val currentEvidence = admissionEvidence(resumed, scopeKey)
        acceptedGuidanceProjections.removeAll { projection ->
            val operation = projection.operation
            if (operation.scopeKey != scopeKey || operation.storedSessionId != resumed.storedSessionId) {
                false
            } else {
                when (operation.kind) {
                    ActiveTurnOperationKind.Steer,
                    ActiveTurnOperationKind.Redirect -> hasNewInflightCorrection(operation, currentEvidence)
                    ActiveTurnOperationKind.Queue -> hasNewDurableUser(operation, currentEvidence) ||
                        hasNewQueuedInput(operation, currentEvidence)
                    ActiveTurnOperationKind.Submit -> false
                }
            }
        }
    }

    private fun rebindPendingOperations(
        resumed: ResumedSession,
        activeGateway: GatewayConnection,
        generation: Long,
    ) {
        val profile = mutableState.value.activeSummary?.profile ?: mutableState.value.selectedProfile
        val scopeKey = sessionScopeKey(resumed.storedSessionId, profile)
        pendingOperation = pendingOperation?.let { operation ->
            if (operation.uncertain &&
                operation.storedSessionId == resumed.storedSessionId &&
                operation.scopeKey == scopeKey
            ) {
                operation.copy(
                    gateway = activeGateway,
                    generation = generation,
                    runtimeSessionId = resumed.runtimeSessionId,
                    profile = profile,
                    scopeKey = scopeKey,
                )
            } else {
                operation
            }
        }
        pendingStop = pendingStop?.let { stop ->
            if ((stop.uncertain || (stop.gateway === activeGateway && stop.generation == generation)) &&
                stop.storedSessionId == resumed.storedSessionId &&
                stop.scopeKey == scopeKey
            ) {
                stop.copy(
                    gateway = activeGateway,
                    generation = generation,
                    runtimeSessionId = resumed.runtimeSessionId,
                    profile = profile,
                    scopeKey = scopeKey,
                )
            } else {
                stop
            }
        }
    }

    private fun admissionEvidence(
        resumed: ResumedSession,
        scopeKey: String,
    ): AdmissionEvidence = AdmissionEvidence(
        scopeKey = scopeKey,
        durableMessages = durableMessageEvidence(resumed.messages),
        inflightUserText = resumed.inflightUserText.trim(),
        inflightCorrections = resumed.inflightCorrections.mapNotNull { correction ->
            val text = correction.text.trim()
            text.takeIf(String::isNotBlank)?.let {
                CorrectionEvidence(text = it, assistantOffset = correction.assistantOffset)
            }
        },
        queuedUserTexts = (
            resumed.queuedUserTexts.ifEmpty {
                listOf(resumed.queuedUserText)
            }
        ).map(String::trim).filter(String::isNotBlank),
    )

    private fun durableMessageEvidence(
        messages: List<ConversationMessage>,
    ): List<DurableMessageEvidence> {
        val occurrences = mutableMapOf<String, Int>()
        return messages.map { message ->
            val role = message.role.trim()
            val text = message.text.trim()
            val identity = message.id
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { "id:$it" }
                ?: "content:$role\u0000$text"
            val occurrence = occurrences[identity] ?: 0
            occurrences[identity] = occurrence + 1
            DurableMessageEvidence(
                identity = identity,
                occurrence = occurrence,
                role = role,
                text = text,
            )
        }
    }

    private fun hasNewDurableUser(
        operation: PendingOperation,
        current: AdmissionEvidence,
    ): Boolean {
        val baseline = operation.admissionBaseline ?: return false
        if (baseline.scopeKey != current.scopeKey) return false
        val target = operation.text.trim()
        val baselineOccurrences = baseline.durableMessages
            .map { it.identity to it.occurrence }
            .toSet()
        // A matching text is admission evidence only when this occurrence was
        // not present in the authoritative snapshot taken before dispatch.
        return current.durableMessages.any { message ->
            message.role == "user" &&
                message.text == target &&
                (message.identity to message.occurrence) !in baselineOccurrences
        }
    }

    private fun hasNewInflightCorrection(
        operation: PendingOperation,
        current: AdmissionEvidence,
    ): Boolean {
        val baseline = operation.admissionBaseline ?: return false
        if (baseline.scopeKey != current.scopeKey) return false
        val target = operation.text.trim()
        val previous = baseline.inflightCorrections
        val now = current.inflightCorrections
        if (previous.isEmpty()) return now.any { it.text == target }

        val previousOffsetsAreAuthoritative = previous.all { it.assistantOffset != null }
        if (previousOffsetsAreAuthoritative) {
            val previousOffsets = previous.mapNotNull(CorrectionEvidence::assistantOffset).toSet()
            if (now.any {
                    it.text == target &&
                        it.assistantOffset != null &&
                        it.assistantOffset !in previousOffsets
                }
            ) {
                return true
            }
        }

        if (now.size <= previous.size) return false
        val preservedPrefix = now.take(previous.size).zip(previous).all { (current, prior) ->
            current.text == prior.text && current.assistantOffset == prior.assistantOffset
        }
        return preservedPrefix && now.drop(previous.size).any { it.text == target }
    }

    private fun hasNewQueuedInput(
        operation: PendingOperation,
        current: AdmissionEvidence,
    ): Boolean {
        val baseline = operation.admissionBaseline ?: return false
        if (baseline.scopeKey != current.scopeKey) return false
        val target = operation.text.trim()
        val previous = baseline.queuedUserTexts
        val now = current.queuedUserTexts
        if (previous.isEmpty()) return now.any { it == target }
        if (now.size <= previous.size) return false
        if (now.take(previous.size) != previous) return false
        return now.drop(previous.size).any { it == target }
    }

    private fun hasNewInflightUser(
        operation: PendingOperation,
        current: AdmissionEvidence,
    ): Boolean {
        val baseline = operation.admissionBaseline ?: return false
        if (baseline.scopeKey != current.scopeKey) return false
        return current.inflightUserText == operation.text.trim() &&
            baseline.inflightUserText != current.inflightUserText
    }

    private fun resolvePendingOperation(
        resumed: ResumedSession,
    ): PendingOperationResolution? {
        val operation = pendingOperation ?: return null
        if (!operation.uncertain) return null
        val currentEvidence = admissionEvidence(resumed, operation.scopeKey)
        val admitted = when (operation.kind) {
            ActiveTurnOperationKind.Steer,
            ActiveTurnOperationKind.Redirect -> hasNewInflightCorrection(operation, currentEvidence)
            ActiveTurnOperationKind.Queue -> hasNewDurableUser(operation, currentEvidence) ||
                hasNewQueuedInput(operation, currentEvidence)
            ActiveTurnOperationKind.Submit -> hasNewDurableUser(operation, currentEvidence) ||
                hasNewQueuedInput(operation, currentEvidence) ||
                hasNewInflightUser(operation, currentEvidence)
        }
        if (admitted) {
            pendingOperation = null
            removeAcceptedGuidance(operation)
            removeGuidanceProjectionFromState(operation)
            clearAcceptedDraft(operation)
            mutableState.value = mutableState.value.copy(
                deliveryStatus = DeliveryStatus.Accepted,
                lastAction = operation.kind.composerAction(),
                errorMessage = null,
            )
            return PendingOperationResolution.Accepted
        }

        if (resumeIsSettled(resumed)) {
            pendingOperation = null
            discardOptimisticSubmission(operation)
            mutableState.value = mutableState.value.copy(
                turnState = if (operation.kind == ActiveTurnOperationKind.Submit) {
                    TurnState.Idle
                } else {
                    mutableState.value.turnState
                },
                deliveryStatus = DeliveryStatus.Rejected,
                lastAction = operation.kind.composerAction(),
                errorMessage = "Hermes did not confirm that action; your draft is still here.",
            )
            return PendingOperationResolution.Rejected
        }

        mutableState.value = mutableState.value.copy(
            deliveryStatus = DeliveryStatus.Uncertain,
            lastAction = operation.kind.composerAction(),
            errorMessage = "Delivery uncertain; reconnecting before you try again.",
        )
        return PendingOperationResolution.Unresolved
    }

    private fun resolvePendingStopAfterResume(
        resumed: ResumedSession,
        correctionResolution: PendingOperationResolution?,
    ) {
        val stop = pendingStop ?: return
        if (!resumeIsSettled(resumed)) {
            mutableState.value = mutableState.value.copy(
                deliveryStatus = DeliveryStatus.Uncertain,
                lastAction = ComposerAction.Stop,
                errorMessage = "Stop delivery uncertain; reconnecting before you try again.",
            )
            return
        }

        pendingStop = null
        val correctionUnresolved = correctionResolution == PendingOperationResolution.Unresolved ||
            (correctionResolution == null &&
                stop.correctionSequence != null &&
                pendingOperation?.sequence == stop.correctionSequence)
        val correctionRejected = correctionResolution == PendingOperationResolution.Rejected
        mutableState.value = mutableState.value.copy(
            deliveryStatus = when {
                !stop.rpcAccepted -> DeliveryStatus.Rejected
                correctionUnresolved -> DeliveryStatus.Uncertain
                else -> DeliveryStatus.Accepted
            },
            lastAction = ComposerAction.Stop,
            errorMessage = when {
                !stop.rpcAccepted -> "Stop was not confirmed; reconnect before trying again."
                correctionUnresolved -> "Turn stopped; the correction was not confirmed, so your draft is still here."
                correctionRejected -> "Turn stopped; the correction was not delivered, so your draft is still here."
                else -> null
            },
        )
    }

    private fun resumeIsSettled(resumed: ResumedSession): Boolean {
        val hasQueuedInput = resumed.queuedUserTexts.isNotEmpty() || resumed.queuedUserText.isNotBlank()
        if (hasQueuedInput || resumed.inflightStreaming) return false
        if (resumed.running == false) return true
        return resumed.status?.lowercase() in setOf("idle", "complete", "completed", "interrupted")
    }

    private fun isCurrentStop(stop: PendingStop): Boolean =
        pendingStop?.sequence == stop.sequence &&
            gateway === stop.gateway &&
            gatewayGeneration == stop.generation &&
            currentRuntimeSessionId == stop.runtimeSessionId &&
            currentStoredSessionId == stop.storedSessionId &&
            mutableState.value.activeSummary?.profile == stop.profile &&
            sessionScopeKey(stop.storedSessionId, stop.profile) == stop.scopeKey


    private fun resumedTurnState(resumed: ResumedSession): TurnState {
        if (resumed.running == true) return TurnState.Running
        if (resumed.running == false) {
            return if (
                resumed.queuedUserTexts.isNotEmpty() ||
                resumed.queuedUserText.isNotBlank() ||
                resumed.inflightStreaming
            ) {
                TurnState.Running
            } else {
                TurnState.Idle
            }
        }
        if (resumed.hasLiveProjection) return TurnState.Running
        return when (resumed.status?.lowercase()) {
            "running", "streaming", "busy", "working", "queued" -> TurnState.Running
            "idle", "complete", "completed", "interrupted" -> TurnState.Idle
            else -> TurnState.UnsupportedGateway
        }
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
                val running = event.payload.boolean("running")
                val redirect = event.payload.explicitRedirectCapability()
                if (running != null || redirect != null) {
                    mutableState.value = mutableState.value.copy(
                        turnState = running?.let { if (it) TurnState.Running else TurnState.Idle }
                            ?: mutableState.value.turnState,
                        redirectSupported = redirect ?: mutableState.value.redirectSupported,
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
        profile: String,
    ) = reconciliationMutex.withLock {
        val generation = gatewayGeneration
        if (!isCurrentGateway(activeGateway, generation)) return@withLock
        val previousStoredId = currentStoredSessionId
        val epoch = beginReconciliation(activeGateway, generation)
        try {
            val created = activeGateway.createSession(profile)
            if (!isCurrentReconciliation(activeGateway, generation, epoch)) return@withLock
            currentRuntimeSessionId = created.runtimeSessionId
            currentStoredSessionId = created.storedSessionId
            val previousSummary = mutableState.value.activeSummary
                ?: throw IOException("No draft conversation is open.")
            val updatedSummary = previousSummary.copy(id = created.storedSessionId, profile = profile)
            authoritativeAdmissionEvidence = AdmissionEvidence(
                scopeKey = sessionScopeKey(created.storedSessionId, profile),
                durableMessages = emptyList(),
                inflightUserText = "",
                inflightCorrections = emptyList(),
                queuedUserTexts = emptyList(),
            )
            mutableState.value = mutableState.value.copy(
                activeSummary = updatedSummary,
                sessions = mutableState.value.sessions?.map { session ->
                    if (session.id == previousStoredId) updatedSummary else session
                },
                turnState = TurnState.Idle,
                errorMessage = null,
            )
            replayBufferedEvents(activeGateway, generation, epoch)
        } finally {
            finishReconciliation(epoch)
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
                        reconcile(
                            activeGateway,
                            storedSessionId,
                            mutableState.value.activeSummary?.profile
                                ?: mutableState.value.selectedProfile,
                        )
                    } else {
                        recreateBlankSession(activeGateway, mutableState.value.selectedProfile)
                    }
                }
                if (result.isSuccess) {
                    reconnectAttempts = 0
                    reconnectJob = null
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
        val activeGateway = gateway
        gateway = null
        pendingOperation = null
        pendingStop = null
        gatewayGeneration += 1
        reconnectJob?.cancel()
        reconnectJob = null
        foregroundCheckJob?.cancel()
        foregroundCheckJob = null
        gatewayEventsJob?.cancel()
        gatewayEventsJob = null
        gatewayStateJob?.cancel()
        gatewayStateJob = null
        invalidateReconciliation()
        acceptedGuidanceProjections.clear()
        pendingAcceptedReconciliation = null
        currentRuntimeSessionId = null
        currentStoredSessionId = null
        currentSessionCanResume = true
        authoritativeAdmissionEvidence = null
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
