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
import dev.hazydreams.hermesceleste.network.ResumedSession
import dev.hazydreams.hermesceleste.network.RuntimeControlsApplyResult
import dev.hazydreams.hermesceleste.network.RuntimeControlsCapabilities
import dev.hazydreams.hermesceleste.network.RuntimeControlsDraft
import dev.hazydreams.hermesceleste.network.RuntimeControlsInfo
import dev.hazydreams.hermesceleste.network.RuntimeControlsPartialApplyException
import dev.hazydreams.hermesceleste.network.RuntimeControlsSnapshot
import dev.hazydreams.hermesceleste.network.RuntimeControlsSource
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.network.applyRuntimeControls
import dev.hazydreams.hermesceleste.network.boolean
import dev.hazydreams.hermesceleste.network.createSession
import dev.hazydreams.hermesceleste.network.decodeRuntimeControlsInfo
import dev.hazydreams.hermesceleste.network.interruptSession
import dev.hazydreams.hermesceleste.network.readRuntimeControlsConfig
import dev.hazydreams.hermesceleste.network.readRuntimeControlsOptions
import dev.hazydreams.hermesceleste.network.resumeStoredSession
import dev.hazydreams.hermesceleste.network.string
import dev.hazydreams.hermesceleste.network.submitPrompt
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
}

internal enum class RuntimeControlsOperation {
    Idle,
    Applying,
    Queued,
    Unknown,
}

internal enum class RuntimeControlsLifecycle {
    Synchronizing,
    Available,
    Reconnecting,
}

internal data class RuntimeControlsUiState(
    val lifecycle: RuntimeControlsLifecycle = RuntimeControlsLifecycle.Synchronizing,
    val snapshot: RuntimeControlsSnapshot? = null,
    val draft: RuntimeControlsDraft? = null,
    val pickerOpen: Boolean = false,
    val optionsLoading: Boolean = false,
    val operation: RuntimeControlsOperation = RuntimeControlsOperation.Idle,
    val message: String? = null,
    val canApply: Boolean = false,
) {
    val operationAnnouncement: String?
        get() = when (operation) {
            RuntimeControlsOperation.Idle -> null
            RuntimeControlsOperation.Applying -> "Applying"
            RuntimeControlsOperation.Queued -> "Queued for next response"
            RuntimeControlsOperation.Unknown -> "Unknown result; reconnect to verify"
        }

    val modelLabel: String
        get() {
            val provider = snapshot?.provider?.takeIf(String::isNotBlank)
            val model = snapshot?.model?.takeIf(String::isNotBlank)
            return when {
                provider != null && model != null -> "$provider / $model"
                model != null -> model
                provider != null -> provider
                else -> "Model unavailable"
            }
        }

    val reasoningLabel: String
        get() = snapshot?.reasoningEffort?.takeIf(String::isNotBlank)?.let { effort ->
            if (effort.equals("none", ignoreCase = true)) "Reasoning off" else "Reasoning $effort"
        } ?: "Reasoning unavailable"

    val accessibilityLabel: String
        get() = "$modelLabel, $reasoningLabel, change settings"
}

internal fun shouldRestoreRuntimeControlsFocus(
    wasPickerOpen: Boolean,
    pickerOpen: Boolean,
): Boolean = wasPickerOpen && !pickerOpen

private data class PendingRuntimeApply(
    val origin: String,
    val profile: String,
    val storedSessionId: String,
    val runtimeSessionId: String,
    val target: RuntimeControlsDraft,
    val modelChanged: Boolean,
    val reasoningChanged: Boolean,
)

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
    val runtimeControls: RuntimeControlsUiState = RuntimeControlsUiState(),
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
    private var connectionJob: Job? = null
    private var connectionAttempt = 0L
    private val connectionStoreMutex = Mutex()
    private var currentDescriptor: SavedConnectionDescriptor? = null
    private var reconnectAttempts = 0
    private var reconciling = false
    private var currentSessionCanResume = true
    private val bufferedEvents = mutableListOf<GatewayEvent>()
    private var pendingRuntimeApply: PendingRuntimeApply? = null

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

    fun openRuntimeControls() {
        val current = mutableState.value
        val controls = current.runtimeControls
        val snapshot = controls.snapshot ?: return
        val activeGateway = gateway ?: return
        if (controls.operation != RuntimeControlsOperation.Idle ||
            controls.lifecycle != RuntimeControlsLifecycle.Available ||
            activeGateway.state.value != GatewayConnectionState.Connected
        ) {
            return
        }
        val draft = controls.draft?.takeIf { draftMatchesSnapshot(it, snapshot) }
            ?: RuntimeControlsDraft(
                origin = snapshot.origin,
                profile = snapshot.profile,
                storedSessionId = snapshot.storedSessionId,
                runtimeSessionId = snapshot.runtimeSessionId,
                provider = snapshot.provider,
                model = snapshot.model,
                reasoningEffort = snapshot.reasoningEffort,
            )
        mutableState.value = current.copy(
            runtimeControls = controls.copy(
                draft = draft,
                pickerOpen = true,
                optionsLoading = true,
                message = null,
                canApply = false,
            ),
        )
        val identity = RuntimeControlIdentity.from(snapshot)
        viewModelScope.launch {
            val optionsResult = runCatching {
                activeGateway.readRuntimeControlsOptions(snapshot.runtimeSessionId)
            }
            val needsConfigFallback = snapshot.provider == null ||
                snapshot.model == null ||
                snapshot.reasoningEffort == null
            val configInfo = if (needsConfigFallback) {
                runCatching {
                    activeGateway.readRuntimeControlsConfig(snapshot.runtimeSessionId)
                }.getOrNull()
            } else {
                null
            }
            if (!isCurrentRuntimeControlIdentity(activeGateway, identity)) return@launch
            var next = mutableState.value.runtimeControls
            val nextSnapshot = next.snapshot ?: return@launch
            optionsResult.onSuccess { capabilities ->
                next = next.copy(
                    snapshot = nextSnapshot.copy(capabilities = capabilities),
                )
            }
            val refreshedSnapshot = next.snapshot ?: nextSnapshot
            if (configInfo?.authoritative == true) {
                applyRuntimeControlsInfo(
                    info = configInfo,
                    source = RuntimeControlsSource.SessionInfo,
                    expected = refreshedSnapshot,
                )?.let { merged ->
                    next = next.copy(snapshot = merged)
                }
            }
            val message = when {
                optionsResult.isFailure -> "This gateway cannot change model or reasoning settings."
                next.snapshot?.capabilities?.available != true -> {
                    "Model and reasoning choices are unavailable on this gateway."
                }
                else -> null
            }
            val finalState = mutableState.value
            val finalControls = next.copy(
                optionsLoading = false,
                message = message,
                canApply = canApplyRuntimeControls(next, finalState.turnState),
            )
            mutableState.value = finalState.copy(runtimeControls = finalControls)
        }
    }

    fun selectRuntimeModel(provider: String, model: String) {
        val current = mutableState.value
        val controls = current.runtimeControls
        val snapshot = controls.snapshot ?: return
        if (!controls.pickerOpen || controls.operation != RuntimeControlsOperation.Idle) return
        val option = snapshot.capabilities.modelOptions.firstOrNull {
            it.provider == provider && it.model == model
        } ?: return
        val draft = controls.draft ?: return
        val nextControls = controls.copy(
            draft = draft.copy(provider = option.provider, model = option.model),
            message = null,
        )
        mutableState.value = current.copy(
            runtimeControls = nextControls.copy(
                canApply = canApplyRuntimeControls(nextControls, current.turnState),
            ),
        )
    }

    fun selectRuntimeReasoning(effort: String) {
        val current = mutableState.value
        val controls = current.runtimeControls
        val snapshot = controls.snapshot ?: return
        if (!controls.pickerOpen || controls.operation != RuntimeControlsOperation.Idle) return
        val normalized = effort.trim().lowercase()
        if (normalized.isBlank() || normalized !in snapshot.capabilities.reasoningEfforts) return
        val draft = controls.draft ?: return
        val nextControls = controls.copy(
            draft = draft.copy(reasoningEffort = normalized),
            message = null,
        )
        mutableState.value = current.copy(
            runtimeControls = nextControls.copy(
                canApply = canApplyRuntimeControls(nextControls, current.turnState),
            ),
        )
    }

    fun cancelRuntimeControls() {
        val current = mutableState.value
        val controls = current.runtimeControls
        mutableState.value = current.copy(
            runtimeControls = if (controls.operation == RuntimeControlsOperation.Idle) {
                controls.copy(
                    pickerOpen = false,
                    draft = null,
                    message = null,
                    canApply = false,
                )
            } else {
                controls.copy(pickerOpen = false, canApply = false)
            },
        )
    }

    fun applyRuntimeControls() {
        val current = mutableState.value
        val controls = current.runtimeControls
        val snapshot = controls.snapshot ?: return
        val draft = controls.draft ?: return
        val activeGateway = gateway ?: return
        if (!canApplyRuntimeControls(controls, current.turnState)) return
        if (activeGateway.state.value != GatewayConnectionState.Connected) {
            markRuntimeControlsReconnecting("Reconnect before applying this setting.")
            return
        }
        val modelChanged = draft.model != snapshot.model || draft.provider != snapshot.provider
        val reasoningChanged = draft.reasoningEffort != snapshot.reasoningEffort
        if (!modelChanged && !reasoningChanged) return
        val pending = PendingRuntimeApply(
            origin = snapshot.origin,
            profile = snapshot.profile,
            storedSessionId = snapshot.storedSessionId,
            runtimeSessionId = snapshot.runtimeSessionId,
            target = draft,
            modelChanged = modelChanged,
            reasoningChanged = reasoningChanged,
        )
        pendingRuntimeApply = pending
        mutableState.value = current.copy(
            runtimeControls = controls.copy(
                operation = RuntimeControlsOperation.Applying,
                message = "Applying…",
                canApply = false,
            ),
        )
        viewModelScope.launch {
            val result = runCatching {
                activeGateway.applyRuntimeControls(
                    runtimeSessionId = pending.runtimeSessionId,
                    provider = pending.target.provider,
                    model = pending.target.model,
                    reasoningEffort = pending.target.reasoningEffort,
                    applyModel = pending.modelChanged,
                    applyReasoning = pending.reasoningChanged,
                )
            }
            if (!isCurrentRuntimeControlIdentity(activeGateway, pending.identity())) return@launch
            result.onFailure { error ->
                handleRuntimeControlsApplyFailure(activeGateway, pending, error)
            }.onSuccess { acknowledgement ->
                handleRuntimeControlsAcknowledgement(activeGateway, pending, acknowledgement)
            }
        }
    }

    private fun canApplyRuntimeControls(
        controls: RuntimeControlsUiState,
        turnState: TurnState,
    ): Boolean {
        val snapshot = controls.snapshot ?: return false
        val draft = controls.draft ?: return false
        if (controls.lifecycle != RuntimeControlsLifecycle.Available ||
            controls.optionsLoading ||
            controls.operation != RuntimeControlsOperation.Idle ||
            !controls.pickerOpen ||
            !snapshot.capabilities.available ||
            gateway?.state?.value != GatewayConnectionState.Connected
        ) {
            return false
        }
        val modelOption = snapshot.capabilities.modelOptions.firstOrNull {
            it.provider == draft.provider && it.model == draft.model
        }
        if (draft.model != snapshot.model || draft.provider != snapshot.provider) {
            if (modelOption == null) return false
        }
        val reasoningChanged = draft.reasoningEffort != snapshot.reasoningEffort
        if (reasoningChanged && (
                draft.reasoningEffort == null ||
                    draft.reasoningEffort !in snapshot.capabilities.reasoningEfforts ||
                    modelOption?.supportsReasoning == false
            )
        ) {
            return false
        }
        if (draft.model == snapshot.model && draft.provider == snapshot.provider && !reasoningChanged) {
            return false
        }
        return turnState != TurnState.Running || snapshot.capabilities.canApplyWhileRunning == true
    }

    private fun draftMatchesSnapshot(
        draft: RuntimeControlsDraft,
        snapshot: RuntimeControlsSnapshot,
    ): Boolean = draft.origin == snapshot.origin &&
        draft.profile == snapshot.profile &&
        draft.storedSessionId == snapshot.storedSessionId

    private data class RuntimeControlIdentity(
        val origin: String,
        val profile: String,
        val storedSessionId: String,
        val runtimeSessionId: String,
    ) {
        companion object {
            fun from(snapshot: RuntimeControlsSnapshot) = RuntimeControlIdentity(
                origin = snapshot.origin,
                profile = snapshot.profile,
                storedSessionId = snapshot.storedSessionId,
                runtimeSessionId = snapshot.runtimeSessionId,
            )

            fun from(pending: PendingRuntimeApply) = RuntimeControlIdentity(
                origin = pending.origin,
                profile = pending.profile,
                storedSessionId = pending.storedSessionId,
                runtimeSessionId = pending.runtimeSessionId,
            )
        }
    }

    private fun PendingRuntimeApply.identity(): RuntimeControlIdentity =
        RuntimeControlIdentity.from(this)

    private fun isCurrentRuntimeControlIdentity(
        activeGateway: GatewayConnection,
        identity: RuntimeControlIdentity,
    ): Boolean {
        val current = mutableState.value.runtimeControls.snapshot ?: return false
        return gateway === activeGateway &&
            current.origin == identity.origin &&
            current.profile == identity.profile &&
            current.storedSessionId == identity.storedSessionId &&
            current.runtimeSessionId == identity.runtimeSessionId &&
            currentRuntimeSessionId == identity.runtimeSessionId
    }

    private fun applyRuntimeControlsInfo(
        info: RuntimeControlsInfo,
        source: RuntimeControlsSource,
        expected: RuntimeControlsSnapshot,
    ): RuntimeControlsSnapshot? {
        if (!info.authoritative) return expected
        if (info.runtimeSessionId?.takeIf(String::isNotBlank)?.let { it != expected.runtimeSessionId } == true ||
            info.storedSessionId?.takeIf(String::isNotBlank)?.let { it != expected.storedSessionId } == true ||
            info.profile?.takeIf(String::isNotBlank)?.let { it != expected.profile } == true
        ) {
            return null
        }
        return expected.copy(
            provider = info.provider ?: expected.provider,
            model = info.model ?: expected.model,
            reasoningEffort = info.reasoningEffort ?: expected.reasoningEffort,
            reasoningEnabled = info.reasoningEnabled ?: expected.reasoningEnabled,
            running = info.running ?: expected.running,
            source = source,
        )
    }

    private fun authoritativeRuntimeControlsMatch(
        snapshot: RuntimeControlsSnapshot,
        pending: PendingRuntimeApply,
    ): Boolean = (!pending.modelChanged || (
        snapshot.model == pending.target.model && snapshot.provider == pending.target.provider
        )) && (!pending.reasoningChanged || snapshot.reasoningEffort == pending.target.reasoningEffort)

    private fun shouldRetainPendingEffectiveState(
        info: RuntimeControlsInfo,
        pending: PendingRuntimeApply,
    ): Boolean {
        if (!pending.modelChanged) return false
        if (info.pendingModelSwitch == true) return true
        if (info.pendingModelSwitch == false) return false
        return info.running == true &&
            info.model == pending.target.model &&
            (info.provider == null || info.provider == pending.target.provider)
    }

    private fun handleRuntimeControlsAcknowledgement(
        activeGateway: GatewayConnection,
        pending: PendingRuntimeApply,
        acknowledgement: RuntimeControlsApplyResult,
    ) {
        if (!acknowledgement.acknowledged) {
            handleRuntimeControlsApplyFailure(
                activeGateway,
                pending,
                GatewayRpcException(null, "Hermes did not accept that setting."),
            )
            return
        }
        val current = mutableState.value
        val controls = current.runtimeControls
        val updated = acknowledgement.authoritativeInfo
            ?.takeUnless { acknowledgement.deferred }
            ?.let { info ->
                controls.snapshot?.let { expected ->
                    applyRuntimeControlsInfo(info, RuntimeControlsSource.ApplyAcknowledgement, expected)
                }
            }
        val withAcknowledgement = if (updated != null) controls.copy(snapshot = updated) else controls
        val confirmed = withAcknowledgement.snapshot?.let { authoritativeRuntimeControlsMatch(it, pending) } == true
        mutableState.value = current.copy(
            runtimeControls = withAcknowledgement.copy(
                pickerOpen = false,
                draft = if (confirmed) null else withAcknowledgement.draft,
                operation = when {
                    confirmed -> RuntimeControlsOperation.Idle
                    acknowledgement.deferred -> RuntimeControlsOperation.Queued
                    else -> RuntimeControlsOperation.Applying
                },
                message = when {
                    confirmed -> null
                    acknowledgement.deferred -> "Queued for next response"
                    else -> "Applying…"
                },
                canApply = false,
            ),
        )
        if (confirmed) {
            pendingRuntimeApply = null
            return
        }
        viewModelScope.launch {
            val reconciled = runCatching { reconcile(activeGateway, pending.storedSessionId) }
            if (reconciled.isFailure && gateway === activeGateway) {
                mutableState.value = mutableState.value.copy(
                    runtimeControls = mutableState.value.runtimeControls.copy(
                        operation = RuntimeControlsOperation.Unknown,
                        message = "Could not confirm that change. Reconnect to verify.",
                        canApply = false,
                    ),
                )
                markRuntimeControlsReconnecting("Reconnect and try again.")
            }
        }
    }

    private fun handleRuntimeControlsApplyFailure(
        activeGateway: GatewayConnection,
        pending: PendingRuntimeApply,
        error: Throwable,
    ) {
        if (error is RuntimeControlsPartialApplyException) {
            viewModelScope.launch {
                val reconciliation = runCatching { reconcile(activeGateway, pending.storedSessionId) }
                if (gateway !== activeGateway) return@launch
                pendingRuntimeApply = null
                val current = mutableState.value
                val nextControls = current.runtimeControls.copy(
                    pickerOpen = true,
                    draft = pending.target,
                    operation = RuntimeControlsOperation.Idle,
                    message = if (reconciliation.isSuccess) {
                        "Only part of that change was applied. Review the current setting."
                    } else {
                        "Reconnect and review the current setting."
                    },
                    canApply = false,
                )
                mutableState.value = current.copy(
                    runtimeControls = nextControls.copy(
                        canApply = if (reconciliation.isSuccess) {
                            canApplyRuntimeControls(nextControls, current.turnState)
                        } else {
                            false
                        },
                    ),
                )
            }
            return
        }
        if (error is GatewayRpcException) {
            pendingRuntimeApply = null
            mutableState.value = mutableState.value.copy(
                runtimeControls = mutableState.value.runtimeControls.copy(
                    pickerOpen = true,
                    draft = pending.target,
                    operation = RuntimeControlsOperation.Idle,
                    message = runtimeControlsError(error),
                    canApply = canApplyRuntimeControls(
                        mutableState.value.runtimeControls.copy(
                            draft = pending.target,
                            operation = RuntimeControlsOperation.Idle,
                        ),
                        mutableState.value.turnState,
                    ),
                ),
            )
            return
        }
        pendingRuntimeApply = pending
        mutableState.value = mutableState.value.copy(
            runtimeControls = mutableState.value.runtimeControls.copy(
                operation = RuntimeControlsOperation.Unknown,
                message = "Could not confirm that change. Reconnect to verify.",
                canApply = false,
            ),
        )
        if (activeGateway.state.value != GatewayConnectionState.Connected) {
            markRuntimeControlsReconnecting("Reconnect and try again.")
        } else {
            viewModelScope.launch {
                val reconciliation = runCatching { reconcile(activeGateway, pending.storedSessionId) }
                if (gateway !== activeGateway) return@launch
                val snapshot = mutableState.value.runtimeControls.snapshot
                if (reconciliation.isSuccess && snapshot != null &&
                    !authoritativeRuntimeControlsMatch(snapshot, pending)
                ) {
                    pendingRuntimeApply = null
                    val current = mutableState.value
                    val nextControls = current.runtimeControls.copy(
                        pickerOpen = true,
                        draft = pending.target,
                        operation = RuntimeControlsOperation.Idle,
                        message = "Reconnect and try again.",
                        canApply = false,
                    )
                    mutableState.value = current.copy(
                        runtimeControls = nextControls.copy(
                            canApply = canApplyRuntimeControls(nextControls, current.turnState),
                        ),
                    )
                } else if (reconciliation.isFailure) {
                    markRuntimeControlsReconnecting("Reconnect and try again.")
                }
            }
        }
    }

    private fun runtimeControlsError(error: GatewayRpcException): String {
        val text = error.message.orEmpty().lowercase()
        return when {
            "busy" in text || "running" in text -> "Apply when this response finishes."
            "unsupported" in text || "unknown method" in text || "not found" in text -> {
                "This gateway cannot change that setting."
            }
            else -> "Hermes rejected that setting. Choose another supported value."
        }
    }

    private fun markRuntimeControlsReconnecting(message: String? = null) {
        val controls = mutableState.value.runtimeControls
        mutableState.value = mutableState.value.copy(
            runtimeControls = controls.copy(
                lifecycle = RuntimeControlsLifecycle.Reconnecting,
                operation = when (controls.operation) {
                    RuntimeControlsOperation.Applying,
                    RuntimeControlsOperation.Queued -> RuntimeControlsOperation.Unknown
                    else -> controls.operation
                },
                message = message ?: controls.message ?: "Reconnecting to Hermes…",
                canApply = false,
            ),
        )
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
        currentSessionCanResume = true
        mutableState.value = mutableState.value.copy(
            activeSummary = summary,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            turnState = TurnState.Synchronizing,
            runtimeControls = RuntimeControlsUiState(),
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
        closeGateway()
        mutableState.value = snapshot.copy(
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            turnState = TurnState.Synchronizing,
            runtimeControls = RuntimeControlsUiState(),
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
                )
                val createdInfo = created.runtimeControls
                val createdSnapshot = RuntimeControlsSnapshot(
                    origin = connection.baseUrl,
                    profile = createdInfo.profile?.takeIf(String::isNotBlank) ?: selectedProfile,
                    storedSessionId = created.storedSessionId,
                    runtimeSessionId = created.runtimeSessionId,
                    provider = createdInfo.provider,
                    model = createdInfo.model,
                    reasoningEffort = createdInfo.reasoningEffort,
                    reasoningEnabled = createdInfo.reasoningEnabled,
                    running = createdInfo.running,
                    source = RuntimeControlsSource.ResumedSnapshot,
                )
                val events = bufferedEvents.toList()
                bufferedEvents.clear()
                reconciling = false
                mutableState.value = mutableState.value.copy(
                    sessions = listOf(summary) + mutableState.value.sessions.orEmpty()
                        .filterNot { it.id == summary.id },
                    activeSummary = summary,
                    turnState = TurnState.Idle,
                    runtimeControls = RuntimeControlsUiState(
                        lifecycle = RuntimeControlsLifecycle.Available,
                        snapshot = createdSnapshot,
                    ),
                    loadingMessage = null,
                    errorMessage = null,
                )
                events.forEach(::applyEvent)
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
        mutableState.value = mutableState.value.copy(
            activeSummary = null,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            turnState = TurnState.Idle,
            runtimeControls = RuntimeControlsUiState(),
            loadingMessage = null,
            errorMessage = null,
        )
    }

    fun sendMessage() {
        val activeGateway = gateway ?: return
        val snapshot = mutableState.value
        val runtimeId = currentRuntimeSessionId ?: return
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
            runtimeControls = snapshot.runtimeControls.copy(canApply = false),
            errorMessage = null,
        )
        // prompt.submit creates the durable row before work begins. From this point on,
        // uncertain delivery must reconcile by stored ID and must never create/resend.
        currentSessionCanResume = true
        viewModelScope.launch {
            runCatching { activeGateway.submitPrompt(runtimeId, text) }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        messages = mutableState.value.messages.map { message ->
                            if (message.id == localId) message.copy(pending = false) else message
                        },
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        errorMessage = error.message ?: "Hermes could not send that message.",
                    )
                    if (gateway === activeGateway) {
                        runCatching { reconcile(activeGateway, currentStoredSessionId ?: return@launch) }
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
            runtimeControls = mutableState.value.runtimeControls.copy(canApply = false),
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
                if (currentSessionCanResume) reconcile(activeGateway, storedSessionId)
            }
            if (health.isFailure && gateway === activeGateway) {
                val wasRunning = mutableState.value.turnState == TurnState.Running
                activeGateway.close()
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Reconnecting,
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
                    mutableState.value = mutableState.value.copy(
                        turnState = TurnState.Reconnecting,
                        errorMessage = connectionState.reason,
                    )
                    markRuntimeControlsReconnecting(connectionState.reason)
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
        val previousControls = mutableState.value.runtimeControls
        val previousSnapshot = previousControls.snapshot
        val origin = mutableState.value.probe?.baseUrl
            ?: mutableState.value.dashboardUrl
        val profile = resumed.runtimeControls.profile
            ?.takeIf(String::isNotBlank)
            ?: mutableState.value.activeSummary?.profile
            ?: mutableState.value.selectedProfile
        val storedSessionId = resumed.storedSessionId
        val sameStoredScope = previousSnapshot != null &&
            previousSnapshot.origin == origin &&
            previousSnapshot.profile == profile &&
            previousSnapshot.storedSessionId == storedSessionId
        val info = resumed.runtimeControls
        val pendingForSnapshot = pendingRuntimeApply?.takeIf {
            it.origin == origin &&
                it.profile == profile &&
                it.storedSessionId == storedSessionId
        }
        val retainPendingEffectiveState = pendingForSnapshot?.let {
            shouldRetainPendingEffectiveState(info, it)
        } == true
        val retainedSnapshot = previousSnapshot?.takeIf { sameStoredScope }
        val snapshot = RuntimeControlsSnapshot(
            origin = origin,
            profile = profile,
            storedSessionId = storedSessionId,
            runtimeSessionId = resumed.runtimeSessionId,
            provider = if (retainPendingEffectiveState) retainedSnapshot?.provider
            else info.provider ?: retainedSnapshot?.provider,
            model = if (retainPendingEffectiveState) retainedSnapshot?.model
            else info.model ?: retainedSnapshot?.model,
            reasoningEffort = if (retainPendingEffectiveState) retainedSnapshot?.reasoningEffort
            else info.reasoningEffort ?: retainedSnapshot?.reasoningEffort,
            reasoningEnabled = if (retainPendingEffectiveState) retainedSnapshot?.reasoningEnabled
            else info.reasoningEnabled ?: retainedSnapshot?.reasoningEnabled,
            running = info.running ?: resumed.running
                ?: retainedSnapshot?.running,
            capabilities = previousSnapshot
                ?.takeIf { sameStoredScope && it.runtimeSessionId == resumed.runtimeSessionId }
                ?.capabilities
                ?: RuntimeControlsCapabilities.Unavailable,
            source = RuntimeControlsSource.ResumedSnapshot,
        )
        currentRuntimeSessionId = resumed.runtimeSessionId
        currentStoredSessionId = storedSessionId
        currentSessionCanResume = true
        val pending = pendingRuntimeApply
        if (pending != null &&
            pending.origin == snapshot.origin &&
            pending.profile == snapshot.profile &&
            pending.storedSessionId == snapshot.storedSessionId
        ) {
            pendingRuntimeApply = pending.copy(runtimeSessionId = snapshot.runtimeSessionId)
        }
        val streamingSuffix = unpersistedInflightText(
            inflight = resumed.inflightAssistantText,
            messages = resumed.messages,
        )
        val existingDraft = previousControls.draft
            ?.takeIf { draftMatchesSnapshot(it, snapshot) }
            ?.copy(runtimeSessionId = snapshot.runtimeSessionId)
        val updatedPending = pendingRuntimeApply
        val confirmed = updatedPending?.let { authoritativeRuntimeControlsMatch(snapshot, it) } == true
        val nextTurnState = if (resumed.running == true || resumed.hasLiveProjection) {
            TurnState.Running
        } else {
            TurnState.Idle
        }
        val controls = previousControls.copy(
            lifecycle = RuntimeControlsLifecycle.Available,
            snapshot = snapshot,
            draft = if (confirmed) null else existingDraft,
            pickerOpen = if (confirmed) false else previousControls.pickerOpen,
            optionsLoading = false,
            operation = when {
                confirmed -> RuntimeControlsOperation.Idle
                previousControls.operation == RuntimeControlsOperation.Unknown -> RuntimeControlsOperation.Idle
                else -> previousControls.operation
            },
            message = when {
                confirmed -> null
                previousControls.operation == RuntimeControlsOperation.Unknown -> "Reconnect and try again."
                else -> previousControls.message
            },
            canApply = false,
        )
        if (confirmed) pendingRuntimeApply = null
        mutableState.value = mutableState.value.copy(
            messages = resumed.messages,
            streamingText = streamingSuffix,
            turnState = nextTurnState,
            runtimeControls = controls.copy(
                canApply = canApplyRuntimeControls(controls, nextTurnState),
            ),
            errorMessage = null,
        )
    }

    private fun applySessionInfoEvent(event: GatewayEvent) {
        val current = mutableState.value
        val controls = current.runtimeControls
        val expected = controls.snapshot ?: return
        val info = decodeRuntimeControlsInfo(event.payload, authoritative = true)
        val pending = pendingRuntimeApply
        val retainPendingEffectiveState = pending?.takeIf {
            it.origin == expected.origin &&
                it.profile == expected.profile &&
                it.storedSessionId == expected.storedSessionId
        }?.let {
            shouldRetainPendingEffectiveState(info, it)
        } == true
        val effectiveInfo = if (retainPendingEffectiveState) {
            info.copy(
                provider = null,
                model = null,
                reasoningEffort = null,
                reasoningEnabled = null,
            )
        } else {
            info
        }
        val updated = applyRuntimeControlsInfo(
            info = effectiveInfo,
            source = RuntimeControlsSource.SessionInfo,
            expected = expected,
        ) ?: return
        val confirmed = pending?.let { authoritativeRuntimeControlsMatch(updated, it) } == true
        if (confirmed) pendingRuntimeApply = null
        val nextTurnState = info.running?.let { if (it) TurnState.Running else TurnState.Idle }
            ?: current.turnState
        val nextControls = controls.copy(
            snapshot = updated,
            operation = if (confirmed) RuntimeControlsOperation.Idle else controls.operation,
            pickerOpen = if (confirmed) false else controls.pickerOpen,
            draft = if (confirmed) null else controls.draft,
            message = if (confirmed) null else controls.message,
            canApply = false,
        )
        mutableState.value = current.copy(
            turnState = nextTurnState,
            runtimeControls = nextControls.copy(
                canApply = canApplyRuntimeControls(nextControls, nextTurnState),
            ),
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
                    runtimeControls = mutableState.value.runtimeControls.copy(canApply = false),
                    errorMessage = null,
                )
            }

            "message.delta" -> {
                val delta = event.payload.string("text").orEmpty()
                if (delta.isNotEmpty()) {
                    mutableState.value = mutableState.value.copy(
                        streamingText = mutableState.value.streamingText + delta,
                        turnState = TurnState.Running,
                        runtimeControls = mutableState.value.runtimeControls.copy(canApply = false),
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
                val nextTurnState = if (event.payload.boolean("busy") == true) {
                    TurnState.Running
                } else {
                    TurnState.Idle
                }
                val controls = mutableState.value.runtimeControls
                mutableState.value = mutableState.value.copy(
                    turnState = nextTurnState,
                    runtimeControls = controls.copy(
                        canApply = canApplyRuntimeControls(controls, nextTurnState),
                    ),
                )
            }

            "session.info" -> {
                applySessionInfoEvent(event)
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
                    runtimeControls = mutableState.value.runtimeControls.copy(canApply = false),
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
        val nextTurnState = if (keepRunning) TurnState.Running else TurnState.Idle
        val nextControls = mutableState.value.runtimeControls
        mutableState.value = mutableState.value.copy(
            messages = messages,
            streamingText = "",
            turnState = nextTurnState,
            runtimeControls = nextControls.copy(
                canApply = canApplyRuntimeControls(nextControls, nextTurnState),
            ),
        )
    }

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
            val createdInfo = created.runtimeControls
            val createdSnapshot = RuntimeControlsSnapshot(
                origin = mutableState.value.probe?.baseUrl ?: mutableState.value.dashboardUrl,
                profile = createdInfo.profile?.takeIf(String::isNotBlank) ?: profile,
                storedSessionId = created.storedSessionId,
                runtimeSessionId = created.runtimeSessionId,
                provider = createdInfo.provider,
                model = createdInfo.model,
                reasoningEffort = createdInfo.reasoningEffort,
                reasoningEnabled = createdInfo.reasoningEnabled,
                running = createdInfo.running,
                source = RuntimeControlsSource.ResumedSnapshot,
            )
            mutableState.value = mutableState.value.copy(
                activeSummary = updatedSummary,
                sessions = mutableState.value.sessions?.map { session ->
                    if (session.id == previousStoredId) updatedSummary else session
                },
                turnState = TurnState.Idle,
                runtimeControls = RuntimeControlsUiState(
                    lifecycle = RuntimeControlsLifecycle.Available,
                    snapshot = createdSnapshot,
                ),
                errorMessage = null,
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
        val storedSessionId = currentStoredSessionId ?: mutableState.value.activeSummary?.id ?: return
        if (reconnectJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(turnState = TurnState.Reconnecting)
        markRuntimeControlsReconnecting()
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
        pendingRuntimeApply = null
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
