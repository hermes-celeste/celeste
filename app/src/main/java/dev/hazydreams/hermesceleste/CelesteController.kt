package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.connection.ConnectionBootstrapDecision
import dev.hazydreams.hermesceleste.connection.ConnectionStore
import dev.hazydreams.hermesceleste.connection.ReusableSecret
import dev.hazydreams.hermesceleste.connection.SavedAuthMode
import dev.hazydreams.hermesceleste.connection.SavedConnectionDescriptor
import dev.hazydreams.hermesceleste.connection.connectionBootstrapDecision
import dev.hazydreams.hermesceleste.network.AuthenticationRejected
import dev.hazydreams.hermesceleste.network.AuthenticationMaterial
import dev.hazydreams.hermesceleste.network.appendCurrentTurnMessage
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.CreatedSession
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.network.DashboardService
import dev.hazydreams.hermesceleste.network.DelegateAgentActivity
import dev.hazydreams.hermesceleste.network.GatewayConnection
import dev.hazydreams.hermesceleste.network.GatewayConnectionState
import dev.hazydreams.hermesceleste.network.GatewayCredential
import dev.hazydreams.hermesceleste.network.GatewayEvent
import dev.hazydreams.hermesceleste.network.GatewayRpcException
import dev.hazydreams.hermesceleste.network.ResumedSession
import dev.hazydreams.hermesceleste.network.SessionCatalogPage
import dev.hazydreams.hermesceleste.network.SessionRedirectStatus
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.network.TaskProgress
import dev.hazydreams.hermesceleste.network.UserMessagePlacement
import dev.hazydreams.hermesceleste.network.bindClarificationRequest
import dev.hazydreams.hermesceleste.network.boolean
import dev.hazydreams.hermesceleste.network.closeRuntimeSession
import dev.hazydreams.hermesceleste.network.createSession
import dev.hazydreams.hermesceleste.network.detachImage
import dev.hazydreams.hermesceleste.network.interruptActiveDelegateAgents
import dev.hazydreams.hermesceleste.network.interruptSession
import dev.hazydreams.hermesceleste.network.markClarificationSubmitting
import dev.hazydreams.hermesceleste.network.resetClarificationSubmission
import dev.hazydreams.hermesceleste.network.respondToClarification
import dev.hazydreams.hermesceleste.network.redirectSession
import dev.hazydreams.hermesceleste.network.resumeStoredSession
import dev.hazydreams.hermesceleste.network.settleClarificationLocally
import dev.hazydreams.hermesceleste.network.settleCurrentTurnSteps
import dev.hazydreams.hermesceleste.network.stageAttachment
import dev.hazydreams.hermesceleste.network.submitPrompt
import dev.hazydreams.hermesceleste.network.string
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    val sessionCatalogTotal: Int = 0,
    val nextSessionOffset: Int = 0,
    val hasMoreSessions: Boolean = false,
    val isLoadingMoreSessions: Boolean = false,
    val sessionPageError: String? = null,
    val sessionSearchQuery: String = "",
    val sessionSearchResults: List<StoredSession> = emptyList(),
    val isSearchingSessions: Boolean = false,
    val sessionSearchError: String? = null,
    val sessionActionError: String? = null,
    val profiles: List<DashboardProfile> = listOf(DashboardProfile(name = "default", isDefault = true)),
    val selectedProfile: String = "default",
    val activeSummary: StoredSession? = null,
    val messages: List<ConversationMessage> = emptyList(),
    val taskProgress: TaskProgress? = null,
    val delegateAgents: List<DelegateAgentActivity> = emptyList(),
    val streamingText: String = "",
    val draft: String = "",
    val composerAttachmentGeneration: Long = 0L,
    val composerAttachments: List<ComposerAttachment> = emptyList(),
    val queuedPrompts: List<QueuedPrompt> = emptyList(),
    val isQueuePaused: Boolean = false,
    val turnState: TurnState = TurnState.Idle,
    val isCompacting: Boolean = false,
    val resumeExhausted: Boolean = false,
    val loadingMessage: String? = null,
    val errorMessage: String? = null,
)

private data class LoadedDashboard(
    val credential: GatewayCredential,
    val sessionPage: SessionCatalogPage,
    val profiles: List<DashboardProfile>,
)

private data class RememberedDashboard(
    val loaded: LoadedDashboard,
    val descriptor: SavedConnectionDescriptor,
    val persistenceError: Throwable?,
)

private data class SubmittedSession(
    val gateway: GatewayConnection,
    val connectionAttempt: Long,
    val storedSessionId: String,
    val summary: StoredSession,
    val firstPrompt: String,
    val projectedMessageCount: Int,
)

private data class StagedAttachmentBatch(
    val attachments: List<ComposerAttachment>,
    val runtimeSessionId: String,
    val storedSessionId: String,
)

private data class UncertainDirectPrompt(
    val text: String,
    val submittedDraft: String,
    val attachments: List<ComposerAttachment>,
    val userMessageCountBeforeSubmit: Int,
)

private class SubmissionSupersededException : Exception("The attachment submission was cancelled.")
private class AttachmentSessionChangedException :
    Exception("The conversation changed while Hermes restored its attachment session.")
private class RedirectSessionChangedException :
    Exception("The conversation changed while Hermes restored its redirect session.")

private data class RedirectAttempt(
    val status: SessionRedirectStatus,
    val fallbackCanDrain: Boolean = true,
)

private data class ResumedLiveProjection(
    val messages: List<ConversationMessage>,
    val streamingText: String,
)

private class SubmissionLease(
    val gateway: GatewayConnection,
    val profile: String,
    val queuedPromptId: String?,
    var storedSessionId: String,
    var runtimeSessionId: String,
    var attachments: List<ComposerAttachment>,
    var promptSubmitAttempted: Boolean = false,
    var cancelled: Boolean = false,
    var restoreAfterUserStop: Boolean = false,
)

/**
 * Owns Celeste's portable application and session state.
 *
 * The host must provide a serial UI scope and invoke controller actions and [close] on that
 * scope's dispatcher. Child work inherits the same dispatcher so reconciliation, event reduction,
 * and local identity generation stay confined without platform-specific synchronization. Closing
 * the controller or cancelling its parent scope releases the active gateway and authentication.
 */
internal class CelesteController(
    parentScope: CoroutineScope,
    private val dashboard: DashboardService,
    private val connectionStore: ConnectionStore,
    private val clientSource: String,
    private val normalizeDashboardUrl: (String) -> String,
    private val attachmentEncodingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val reconnectDelayMillis: (attempt: Int, wasRunning: Boolean) -> Long = { attempt, wasRunning ->
        if (wasRunning && attempt == 0) 100L else min(5_000L, 1_000L shl attempt.coerceAtMost(2))
    },
) {
    init {
        require(clientSource.isNotBlank()) { "A client source is required." }
    }

    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val controllerScope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val mutableState = MutableStateFlow(CelesteUiState())
    val state: StateFlow<CelesteUiState> = mutableState.asStateFlow()
    private val sessionCatalog = SessionCatalogCoordinator(
        scope = controllerScope,
        dashboard = dashboard,
        readState = { mutableState.value.sessionCatalogState() },
        writeState = { catalog ->
            mutableState.value = mutableState.value.withSessionCatalogState(catalog)
        },
    )

    private var localMessageCounter = 0L
    private var turnSettlementGeneration = 0L
    private var composerAttachmentGenerationCounter = 0L
    private var credential: GatewayCredential? = null
    private var gateway: GatewayConnection? = null
    private var gatewayEventsJob: Job? = null
    private var gatewayStateJob: Job? = null
    private var reconnectJob: Job? = null
    private var taskProgressClearJob: Job? = null
    private var foregroundCheckJob: Job? = null
    private var connectionJob: Job? = null
    private var connectionAttempt = 0L
    private val connectionStoreMutex = Mutex()
    private var currentDescriptor: SavedConnectionDescriptor? = null
    private var reconnectAttempts = 0
    private var reconciling = false
    private var currentSessionCanResume = true
    private var currentSessionPublished = true
    private val bufferedEvents = mutableListOf<GatewayEvent>()
    private val queuedPromptsBySession = mutableMapOf<String, MutableList<QueuedPrompt>>()
    private val composerAttachmentsBySession = mutableMapOf<String, MutableList<ComposerAttachment>>()
    private val directAttachmentsInFlightBySession = mutableMapOf<String, List<ComposerAttachment>>()
    private val uncertainDirectPromptsBySession = mutableMapOf<String, UncertainDirectPrompt>()
    private val submissionsBySession = mutableMapOf<String, SubmissionLease>()
    private val attachmentCleanupBySession = mutableMapOf<String, Deferred<Unit>>()
    private val runtimeRetirementBySession = mutableMapOf<String, String>()
    private val migratedSessionIds = mutableMapOf<String, String>()
    private val parkedQueueSessions = mutableSetOf<String>()
    private val redirectStopGenerationBySession = mutableMapOf<String, Long>()
    private val queueDrainsInFlight = mutableSetOf<String>()
    private var queuedTurnAwaitingActivitySessionId: String? = null
    private var resourcesReleased = false

    init {
        controllerJob.invokeOnCompletion { releaseResources() }
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

    fun attachmentImportBudget(): AttachmentImportBudget {
        val retained = retainedAttachments()
        return AttachmentImportBudget(
            attachmentCount = retained.size,
            byteSize = retained.sumOf(ComposerAttachment::byteSize),
        )
    }

    fun addPickedAttachments(
        attachments: List<PickedComposerAttachment>,
        expectedGeneration: Long,
    ) {
        if (attachments.isEmpty()) return
        if (mutableState.value.composerAttachmentGeneration != expectedGeneration) {
            mutableState.value = mutableState.value.copy(
                errorMessage = "Attachments weren't added because the conversation changed.",
            )
            return
        }
        val sessionId = activeComposerSessionId()
        val current = composerAttachmentsBySession.getOrPut(sessionId) { mutableListOf() }
        val budget = attachmentImportBudget()
        var totalCount = budget.attachmentCount
        var totalBytes = budget.byteSize
        val accepted = mutableListOf<ComposerAttachment>()
        attachments.forEach { picked ->
            if (
                totalCount < AttachmentLimits.MAX_COUNT &&
                picked.byteSize <= AttachmentLimits.MAX_ITEM_BYTES &&
                totalBytes + picked.byteSize <= AttachmentLimits.MAX_TOTAL_BYTES
            ) {
                totalCount += 1
                totalBytes += picked.byteSize
                accepted += ComposerAttachment(
                    id = nextLocalMessageId("attachment"),
                    kind = picked.kind,
                    name = picked.name,
                    mimeType = picked.mimeType,
                    contentBytes = picked.contentBytes,
                    byteSize = picked.byteSize,
                )
            }
        }
        current += accepted
        mutableState.value = mutableState.value.copy(
            composerAttachments = current.toList(),
            errorMessage = if (accepted.size < attachments.size) {
                "Some attachments were not added. Keep each under " +
                    "${AttachmentLimits.MAX_ITEM_MEGABYTES} MB, with up to " +
                    "${AttachmentLimits.MAX_COUNT} items and 50 MB total."
            } else {
                null
            },
        )
    }

    fun removeComposerAttachment(attachmentId: String) {
        val sessionId = activeComposerSessionId()
        val attachments = composerAttachmentsBySession[sessionId] ?: return
        val removed = attachments.firstOrNull { it.id == attachmentId } ?: return
        attachments.removeAll { it.id == attachmentId }
        if (attachments.isEmpty()) composerAttachmentsBySession.remove(sessionId)
        mutableState.value = mutableState.value.copy(
            composerAttachments = composerAttachmentsFor(sessionId),
            errorMessage = null,
        )
        scheduleStagedImageRemoval(sessionId, removed)
    }

    fun reportAttachmentError(message: String) {
        mutableState.value = mutableState.value.copy(errorMessage = message)
    }

    fun selectProfile(name: String) {
        if (mutableState.value.profiles.none { it.name == name }) return
        mutableState.value = mutableState.value.copy(selectedProfile = name)
    }

    suspend fun loadConversationImage(path: String): ByteArray? {
        val snapshot = mutableState.value
        val activeCredential = credential ?: return null
        val baseUrl = snapshot.probe?.baseUrl ?: return null
        return try {
            dashboard.loadGatewayImage(
                baseUrl = baseUrl,
                credential = activeCredential,
                path = path,
            )
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            null
        }
    }

    fun updateSessionSearchQuery(value: String) {
        val defaultProfile = mutableState.value.profiles
            .firstOrNull(DashboardProfile::isDefault)
            ?.name
            ?: "default"
        sessionCatalog.updateSearchQuery(
            value = value,
            access = sessionCatalogAccess(),
            profile = defaultProfile,
        )
    }

    fun setSessionPinned(summary: StoredSession, pinned: Boolean) {
        val access = sessionCatalogAccess() ?: return
        sessionCatalog.setPinned(summary, pinned, access)
    }

    fun renameSession(
        summary: StoredSession,
        title: String,
        onComplete: (String?) -> Unit,
    ) {
        sessionCatalog.rename(
            summary = summary,
            title = title,
            access = sessionCatalogAccess(),
            onComplete = onComplete,
        )
    }

    private fun sessionCatalogAccess(): SessionCatalogAccess? {
        val connection = mutableState.value.probe ?: return null
        val activeCredential = credential ?: return null
        return SessionCatalogAccess(connection.baseUrl, activeCredential)
    }

    fun findDashboard() {
        val rawUrl = mutableState.value.dashboardUrl
        if (rawUrl.isBlank()) return
        val attempt = beginConnectionAttempt()
        clearAllQueuedPrompts()
        closeGateway()
        credential = null
        currentDescriptor = null
        dashboard.clearAuthentication()
        mutableState.value = mutableState.value.copy(
            connectionPhase = ConnectionPhase.ManualSetup,
            sessions = null,
            sessionCatalogTotal = 0,
            nextSessionOffset = 0,
            hasMoreSessions = false,
            isLoadingMoreSessions = false,
            sessionPageError = null,
            sessionSearchQuery = "",
            sessionSearchResults = emptyList(),
            isSearchingSessions = false,
            sessionSearchError = null,
            activeSummary = null,
            messages = emptyList(),
            taskProgress = null,
            delegateAgents = emptyList(),
            streamingText = "",
            draft = "",
            composerAttachmentGeneration = nextComposerAttachmentGeneration(),
            composerAttachments = emptyList(),
            queuedPrompts = emptyList(),
            isQueuePaused = false,
            isCompacting = false,
            loadingMessage = "Finding Hermes…",
            errorMessage = null,
        )
        connectionJob = controllerScope.launch {
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
        connectionJob = controllerScope.launch {
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
                prepareConnectedDashboard(
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
                    loadingMessage = null,
                )
            }
        }
    }

    fun retrySavedConnection() {
        restoreSavedConnection()
    }

    fun useAnotherConnection() {
        beginConnectionAttempt()
        clearAllQueuedPrompts()
        closeGateway()
        credential = null
        currentDescriptor = null
        dashboard.clearAuthentication()
        mutableState.value = CelesteUiState(
            connectionPhase = ConnectionPhase.ManualSetup,
            composerAttachmentGeneration = nextComposerAttachmentGeneration(),
        )
    }

    fun signOut() {
        val snapshot = mutableState.value
        val activeCredential = credential
        val attempt = beginConnectionAttempt()
        clearAllQueuedPrompts()
        closeGateway()
        credential = null
        currentDescriptor = null
        mutableState.value = snapshot.copy(
            connectionPhase = ConnectionPhase.ManualSetup,
            sessions = null,
            sessionCatalogTotal = 0,
            nextSessionOffset = 0,
            hasMoreSessions = false,
            isLoadingMoreSessions = false,
            sessionPageError = null,
            sessionSearchQuery = "",
            sessionSearchResults = emptyList(),
            isSearchingSessions = false,
            sessionSearchError = null,
            activeSummary = null,
            messages = emptyList(),
            taskProgress = null,
            delegateAgents = emptyList(),
            streamingText = "",
            draft = "",
            composerAttachmentGeneration = nextComposerAttachmentGeneration(),
            composerAttachments = emptyList(),
            queuedPrompts = emptyList(),
            isQueuePaused = false,
            isCompacting = false,
            password = "",
            sessionToken = "",
            loadingMessage = "Signing out…",
            errorMessage = null,
        )
        connectionJob = controllerScope.launch {
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
        clearAllQueuedPrompts()
        closeGateway()
        credential = null
        currentDescriptor = null
        mutableState.value = CelesteUiState(
            connectionPhase = ConnectionPhase.ManualSetup,
            loadingMessage = "Forgetting this connection…",
            composerAttachmentGeneration = nextComposerAttachmentGeneration(),
        )
        connectionJob = controllerScope.launch {
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
                composerAttachmentGeneration = nextComposerAttachmentGeneration(),
                errorMessage = if (error == null) null else {
                    "Celeste could not remove the saved connection. Try again."
                },
            )
        }
    }

    private fun restoreSavedConnection() {
        val attempt = beginConnectionAttempt()
        clearAllQueuedPrompts()
        closeGateway()
        credential = null
        mutableState.value = CelesteUiState(
            connectionPhase = ConnectionPhase.CheckingSavedConnection,
            loadingMessage = "Checking this device…",
            composerAttachmentGeneration = nextComposerAttachmentGeneration(),
        )
        connectionJob = controllerScope.launch {
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
        val attachmentGeneration = mutableState.value.composerAttachmentGeneration
        mutableState.value = CelesteUiState(
            connectionPhase = ConnectionPhase.Restoring,
            dashboardUrl = descriptor.baseUrl,
            savedAuthMode = descriptor.authMode,
            username = descriptor.username.orEmpty(),
            loadingMessage = "Reconnecting to your Hermes…",
            composerAttachmentGeneration = attachmentGeneration,
        )
        dashboard.clearAuthentication()
        runCatching {
            val normalized = normalizeDashboardUrl(descriptor.baseUrl)
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
                    IllegalStateException("The refreshed Hermes session was unavailable.")
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
            prepareConnectedDashboard(
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
                    composerAttachmentGeneration = attachmentGeneration,
                    errorMessage = error.message ?: "Could not reconnect to Hermes.",
                )
            }
        }
    }

    private suspend fun loadDashboard(
        baseUrl: String,
        selectedCredential: GatewayCredential,
    ): LoadedDashboard {
        val sessionPage = dashboard.listSessions(
            baseUrl = baseUrl,
            credential = selectedCredential,
            limit = SESSION_PAGE_SIZE,
            offset = 0,
        )
        val profiles = dashboard.listProfiles(baseUrl, selectedCredential)
        return LoadedDashboard(selectedCredential, sessionPage, profiles)
    }

    private suspend fun invalidateReusableAuthentication(
        descriptor: SavedConnectionDescriptor?,
        probe: DashboardProbeResult? = null,
    ) {
        invalidateConnectionBoundWork()
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

    private fun prepareConnectedDashboard(
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
        startConnectedDashboardDraft(
            sessionPage = loaded.sessionPage,
            profiles = loaded.profiles,
            selectedProfile = selectedProfile,
            password = password,
            sessionToken = sessionToken,
            connectionWarning = errorMessage,
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
        composerAttachmentGeneration = nextComposerAttachmentGeneration(),
        errorMessage = errorMessage,
    )

    private fun beginConnectionAttempt(): Long {
        connectionJob?.cancel()
        connectionJob = null
        return invalidateConnectionBoundWork()
    }

    private fun invalidateConnectionBoundWork(): Long {
        connectionAttempt += 1
        sessionCatalog.invalidateConnection()
        return connectionAttempt
    }

    private fun isCurrentConnectionAttempt(attempt: Long): Boolean = connectionAttempt == attempt

    fun openSession(summary: StoredSession) {
        val catalogAccess = sessionCatalogAccess() ?: return
        val visibleSummary = sessionCatalog.prepareSessionOpen(summary, catalogAccess)
        val queuedPrompts = queuedPromptsFor(visibleSummary.id)
        closeGateway()
        currentSessionCanResume = true
        currentSessionPublished = true
        mutableState.value = mutableState.value.copy(
            activeSummary = visibleSummary,
            messages = emptyList(),
            taskProgress = null,
            delegateAgents = emptyList(),
            streamingText = "",
            draft = "",
            composerAttachmentGeneration = nextComposerAttachmentGeneration(),
            composerAttachments = composerAttachmentsFor(visibleSummary.id),
            queuedPrompts = queuedPrompts,
            isQueuePaused = visibleSummary.id in parkedQueueSessions && queuedPrompts.isNotEmpty(),
            turnState = TurnState.Synchronizing,
            isCompacting = false,
            resumeExhausted = false,
            loadingMessage = "Opening ${summary.title.ifBlank { "conversation" }}…",
            errorMessage = null,
        )

        val newGateway = dashboard.createGateway(catalogAccess.baseUrl, catalogAccess.credential)
        gateway = newGateway
        observeGateway(newGateway)
        controllerScope.launch {
            var resumeAttempted = false
            runCatching {
                newGateway.connect()
                resumeAttempted = true
                reconcile(newGateway, summary.id)
            }.onSuccess {
                if (gateway !== newGateway) return@onSuccess
                reconnectAttempts = 0
                mutableState.value = mutableState.value.copy(loadingMessage = null)
            }.onFailure { error ->
                if (gateway !== newGateway) return@onFailure
                currentCoroutineContext().ensureActive()
                mutableState.value = mutableState.value.copy(
                    loadingMessage = null,
                    errorMessage = null,
                    turnState = TurnState.Reconnecting,
                )
                scheduleReconnect(
                    wasRunning = false,
                    initialResumeFailures = if (resumeAttempted) 1 else 0,
                )
            }
        }
    }

    private fun startConnectedDashboardDraft(
        sessionPage: SessionCatalogPage,
        profiles: List<DashboardProfile>,
        selectedProfile: String,
        password: String,
        sessionToken: String,
        connectionWarning: String?,
    ) {
        val snapshot = mutableState.value
        closeGateway()
        mutableState.value = snapshot.copy(
            connectionPhase = ConnectionPhase.Connected,
            savedAuthMode = currentDescriptor?.authMode,
            sessions = sessionPage.sessions,
            sessionCatalogTotal = sessionPage.total,
            nextSessionOffset = sessionPage.nextOffset,
            hasMoreSessions = sessionPage.hasMore,
            isLoadingMoreSessions = false,
            sessionPageError = null,
            sessionSearchQuery = "",
            sessionSearchResults = emptyList(),
            isSearchingSessions = false,
            sessionSearchError = null,
            profiles = profiles,
            selectedProfile = selectedProfile,
            activeSummary = null,
            messages = emptyList(),
            taskProgress = null,
            delegateAgents = emptyList(),
            streamingText = "",
            draft = "",
            composerAttachmentGeneration = nextComposerAttachmentGeneration(),
            composerAttachments = emptyList(),
            queuedPrompts = emptyList(),
            isQueuePaused = false,
            password = password,
            sessionToken = sessionToken,
            turnState = TurnState.Idle,
            isCompacting = false,
            resumeExhausted = false,
            loadingMessage = null,
            errorMessage = connectionWarning,
        )
    }

    fun createNewConversation() = startUserConversation(clearDraft = true)

    private fun startUserConversation(clearDraft: Boolean) {
        val snapshot = mutableState.value
        if (snapshot.probe == null || credential == null) return
        closeGateway()
        if (clearDraft) composerAttachmentsBySession.remove(DRAFT_COMPOSER_SESSION_ID)
        mutableState.value = snapshot.copy(
            activeSummary = null,
            messages = emptyList(),
            taskProgress = null,
            delegateAgents = emptyList(),
            streamingText = "",
            draft = if (clearDraft) "" else snapshot.draft,
            composerAttachmentGeneration = if (clearDraft) {
                nextComposerAttachmentGeneration()
            } else {
                snapshot.composerAttachmentGeneration
            },
            composerAttachments = if (clearDraft) {
                emptyList()
            } else {
                composerAttachmentsFor(DRAFT_COMPOSER_SESSION_ID)
            },
            queuedPrompts = emptyList(),
            isQueuePaused = false,
            turnState = TurnState.Idle,
            isCompacting = false,
            resumeExhausted = false,
            loadingMessage = null,
            errorMessage = null,
        )
    }

    private fun createDraftRuntime(
        connection: DashboardProbeResult,
        activeCredential: GatewayCredential,
        selectedProfile: String,
        onRuntimeReady: (StoredSession) -> Unit,
        onRuntimeFailure: (Throwable) -> Unit,
    ) {
        val newGateway = dashboard.createGateway(connection.baseUrl, activeCredential)
        gateway = newGateway
        observeGateway(newGateway)
        controllerScope.launch {
            runCatching {
                newGateway.connect()
                reconciling = true
                bufferedEvents.clear()
                val created = newGateway.createSession(selectedProfile, clientSource)
                if (gateway !== newGateway) {
                    throw IllegalStateException("The Hermes connection changed while creating the conversation.")
                }
                if (!created.profile.equals(selectedProfile, ignoreCase = true)) {
                    throw IllegalStateException(
                        "Hermes created this conversation in ${created.profile} instead of $selectedProfile.",
                    )
                }
                currentRuntimeSessionId = created.runtimeSessionId
                currentStoredSessionId = created.storedSessionId
                currentSessionCanResume = false
                currentSessionPublished = false
                val summary = StoredSession(
                    id = created.storedSessionId,
                    title = "New conversation",
                    preview = "",
                    startedAt = 0.0,
                    messageCount = 0,
                    source = clientSource,
                    profile = selectedProfile,
                )
                val events = bufferedEvents.toList()
                bufferedEvents.clear()
                reconciling = false
                onRuntimeReady(summary)
                events.forEach(::applyEvent)
            }.onSuccess {
                if (gateway !== newGateway) return@onSuccess
                reconnectAttempts = 0
            }.onFailure { error ->
                if (gateway !== newGateway) return@onFailure
                currentCoroutineContext().ensureActive()
                closeGateway()
                if (error is AuthenticationRejected) {
                    invalidateReusableAuthentication(
                        descriptor = currentDescriptor,
                        probe = connection,
                    )
                    return@onFailure
                }
                onRuntimeFailure(error)
            }
        }
    }

    private fun completeDraftRuntime(
        summary: StoredSession,
        sessionPage: SessionCatalogPage?,
        connectionWarning: String?,
    ) {
        migrateComposerAttachmentSessionState(DRAFT_COMPOSER_SESSION_ID, summary.id)
        val snapshot = mutableState.value
        mutableState.value = snapshot.copy(
            connectionPhase = ConnectionPhase.Connected,
            sessions = sessionPage?.sessions ?: snapshot.sessions,
            sessionCatalogTotal = sessionPage?.total ?: snapshot.sessionCatalogTotal,
            nextSessionOffset = sessionPage?.nextOffset ?: snapshot.nextSessionOffset,
            hasMoreSessions = sessionPage?.hasMore ?: snapshot.hasMoreSessions,
            isLoadingMoreSessions = false,
            sessionPageError = null,
            activeSummary = summary,
            composerAttachments = composerAttachmentsFor(summary.id),
            queuedPrompts = emptyList(),
            isQueuePaused = false,
            turnState = TurnState.Idle,
            resumeExhausted = false,
            loadingMessage = null,
            errorMessage = connectionWarning,
        )
    }

    fun loadMoreSessions() {
        val access = sessionCatalogAccess() ?: return
        sessionCatalog.loadMore(
            access = access,
            conversationIsLoading = mutableState.value.loadingMessage != null,
        )
    }

    fun sendMessage() {
        val snapshot = mutableState.value
        val text = snapshot.draft.trim()
        val attachments = snapshot.composerAttachments
        if (text.isBlank() && attachments.isEmpty()) return
        when {
            snapshot.turnState == TurnState.Running &&
                attachments.isEmpty() &&
                !snapshot.isCompacting &&
                snapshot.messages.none { it.role == "clarification" && it.pending } -> {
                redirectRunningDraft(snapshot, text)
                return
            }
            snapshot.turnState == TurnState.Running || snapshot.turnState == TurnState.Reconnecting -> {
                enqueueDraft(snapshot, text, attachments)
                return
            }
            snapshot.turnState != TurnState.Idle -> return
            snapshot.queuedPrompts.isNotEmpty() -> {
                enqueueDraft(snapshot, text, attachments)
                return
            }
        }

        val activeGateway = gateway
        val runtimeId = currentRuntimeSessionId
        val storedSessionId = currentStoredSessionId
        val summary = snapshot.activeSummary
        if (activeGateway == null || runtimeId == null || storedSessionId == null || summary == null) {
            if (summary == null && activeGateway == null && runtimeId == null && storedSessionId == null) {
                createDraftRuntimeForFirstPrompt(
                    snapshot.copy(composerAttachmentGeneration = nextComposerAttachmentGeneration()),
                )
            }
            return
        }

        submitMessage(
            snapshot = snapshot,
            text = text,
            attachments = attachments,
            submittedDraft = snapshot.draft,
            queuedPrompt = null,
        )
    }

    private fun redirectRunningDraft(snapshot: CelesteUiState, text: String) {
        val activeGateway = gateway
        val runtimeSessionId = currentRuntimeSessionId
        val storedSessionId = currentStoredSessionId ?: snapshot.activeSummary?.id
        if (activeGateway == null || runtimeSessionId == null || storedSessionId == null) {
            enqueueDraft(snapshot, text, emptyList())
            return
        }

        val redirectMessageId = nextLocalMessageId("redirect")
        val settledMessages = settleCurrentTurnSteps(snapshot.messages)
        val messagesWithStream = if (snapshot.streamingText.isBlank()) {
            settledMessages
        } else {
            appendCurrentTurnMessage(
                messages = settledMessages,
                message = ConversationMessage(
                    role = "assistant",
                    text = snapshot.streamingText,
                    interim = true,
                ),
            )
        }
        mutableState.value = snapshot.copy(
            messages = appendCurrentTurnMessage(
                messages = messagesWithStream,
                message = ConversationMessage(
                    role = "user",
                    text = text,
                    id = redirectMessageId,
                    pending = true,
                    userPlacement = UserMessagePlacement.MidTurnCorrection,
                ),
            ),
            streamingText = "",
            draft = "",
            composerAttachmentGeneration = nextComposerAttachmentGeneration(),
            errorMessage = null,
        )
        val redirectConnectionAttempt = connectionAttempt
        val redirectStopGeneration = redirectStopGenerationBySession.getOrDefault(storedSessionId, 0L)
        val redirectTurnSettlementGeneration = turnSettlementGeneration
        val userMessageCountBeforeRedirect = snapshot.messages.count { it.role == "user" }
        controllerScope.launch {
            val attempt = try {
                redirectWithSessionRecovery(
                    activeGateway = activeGateway,
                    runtimeSessionId = runtimeSessionId,
                    storedSessionId = storedSessionId,
                    expectedConnectionAttempt = redirectConnectionAttempt,
                    redirectMessageId = redirectMessageId,
                    text = text,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                preserveUncertainRedirect(
                    activeGateway = activeGateway,
                    storedSessionId = storedSessionId,
                    expectedConnectionAttempt = redirectConnectionAttempt,
                    redirectMessageId = redirectMessageId,
                    text = text,
                    userMessageCountBeforeRedirect = userMessageCountBeforeRedirect,
                )
                return@launch
            }
            val accepted = attempt.status != SessionRedirectStatus.Rejected
            val resolvedSessionId = canonicalSessionId(storedSessionId)
            val stopped = resolvedSessionId?.let { sessionId ->
                redirectStopGenerationBySession.getOrDefault(sessionId, 0L) != redirectStopGeneration
            } == true
            if (
                gateway !== activeGateway ||
                connectionAttempt != redirectConnectionAttempt ||
                canonicalSessionId(currentStoredSessionId) != resolvedSessionId
            ) {
                if (!accepted) {
                    enqueueRedirectFallback(
                        sessionId = resolvedSessionId,
                        text = text,
                        paused = stopped || !attempt.fallbackCanDrain,
                    )
                }
                return@launch
            }

            if (accepted) {
                mutableState.value = projectAcceptedRedirect(
                    snapshot = mutableState.value,
                    redirectMessageId = redirectMessageId,
                    status = attempt.status,
                    crossedTurnBoundary = turnSettlementGeneration != redirectTurnSettlementGeneration,
                )
            } else {
                mutableState.value = mutableState.value.copy(
                    messages = mutableState.value.messages.filterNot { it.id == redirectMessageId },
                )
                enqueueRedirectFallback(
                    sessionId = resolvedSessionId,
                    text = text,
                    paused = stopped || !attempt.fallbackCanDrain,
                )
            }
        }
    }

    private fun projectAcceptedRedirect(
        snapshot: CelesteUiState,
        redirectMessageId: String,
        status: SessionRedirectStatus,
        crossedTurnBoundary: Boolean,
    ): CelesteUiState {
        val markerIndex = snapshot.messages.indexOfFirst { it.id == redirectMessageId }
        val marker = snapshot.messages.getOrNull(markerIndex) ?: return snapshot
        if (status != SessionRedirectStatus.Queued) {
            return snapshot.copy(
                messages = snapshot.messages.map { message ->
                    if (message.id == redirectMessageId) {
                        message.copy(
                            pending = false,
                            userPlacement = UserMessagePlacement.MidTurnCorrection,
                        )
                    } else {
                        message
                    }
                },
            )
        }

        val withoutMarker = snapshot.messages.filterNot { it.id == redirectMessageId }
        val withCurrentStream = snapshot.streamingText.takeIf(String::isNotBlank)?.let { text ->
            appendCurrentTurnMessage(
                messages = withoutMarker,
                message = ConversationMessage(role = "assistant", text = text, interim = true),
            )
        } ?: withoutMarker
        return snapshot.copy(
            messages = withCurrentStream + marker.copy(
                pending = false,
                userPlacement = if (crossedTurnBoundary) {
                    UserMessagePlacement.Prompt
                } else {
                    UserMessagePlacement.NextTurn
                },
            ),
            streamingText = "",
        )
    }

    private suspend fun redirectWithSessionRecovery(
        activeGateway: GatewayConnection,
        runtimeSessionId: String,
        storedSessionId: String,
        expectedConnectionAttempt: Long,
        redirectMessageId: String,
        text: String,
    ): RedirectAttempt {
        var runtimeId = runtimeSessionId
        var recovered = false
        while (true) {
            try {
                val status = activeGateway.redirectSession(runtimeId, text)
                if (status != SessionRedirectStatus.Rejected) return RedirectAttempt(status)
                if (!hasPostRedirectProjection(redirectMessageId)) return RedirectAttempt(status)
                val canonicalStoredId = canonicalSessionId(storedSessionId)
                val reconciled = if (
                    canonicalStoredId != null &&
                    gateway === activeGateway &&
                    connectionAttempt == expectedConnectionAttempt
                ) {
                    try {
                        reconcile(activeGateway, canonicalStoredId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        null
                    }
                } else {
                    null
                }
                return RedirectAttempt(
                    status = status,
                    fallbackCanDrain = reconciled != null,
                )
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                if (recovered || !failure.isSessionNotFoundFailure()) throw failure
                val canonicalStoredId = canonicalSessionId(storedSessionId)
                    ?: throw RedirectSessionChangedException()
                val resumed = reconcile(activeGateway, canonicalStoredId)
                    ?: throw RedirectSessionChangedException()
                currentCoroutineContext().ensureActive()
                if (
                    gateway !== activeGateway ||
                    connectionAttempt != expectedConnectionAttempt ||
                    canonicalSessionId(currentStoredSessionId) != canonicalSessionId(resumed.storedSessionId)
                ) {
                    throw RedirectSessionChangedException()
                }
                if (mutableState.value.turnState != TurnState.Running) {
                    return RedirectAttempt(SessionRedirectStatus.Rejected)
                }
                ensurePendingRedirectMarker(redirectMessageId, text)
                runtimeId = resumed.runtimeSessionId
                recovered = true
            }
        }
    }

    private fun hasPostRedirectProjection(redirectMessageId: String): Boolean {
        val snapshot = mutableState.value
        val markerIndex = snapshot.messages.indexOfFirst { it.id == redirectMessageId }
        return markerIndex >= 0 && (
            snapshot.streamingText.isNotBlank() || markerIndex < snapshot.messages.lastIndex
        )
    }

    private fun ensurePendingRedirectMarker(redirectMessageId: String, text: String) {
        val snapshot = mutableState.value
        if (snapshot.messages.any { it.id == redirectMessageId }) return
        val settledMessages = settleCurrentTurnSteps(snapshot.messages)
        val messagesWithStream = if (snapshot.streamingText.isBlank()) {
            settledMessages
        } else {
            appendCurrentTurnMessage(
                messages = settledMessages,
                message = ConversationMessage(
                    role = "assistant",
                    text = snapshot.streamingText,
                    interim = true,
                ),
            )
        }
        mutableState.value = snapshot.copy(
            messages = appendCurrentTurnMessage(
                messages = messagesWithStream,
                message = ConversationMessage(
                    role = "user",
                    text = text,
                    id = redirectMessageId,
                    pending = true,
                    userPlacement = UserMessagePlacement.MidTurnCorrection,
                ),
            ),
            streamingText = "",
        )
    }

    private suspend fun preserveUncertainRedirect(
        activeGateway: GatewayConnection,
        storedSessionId: String,
        expectedConnectionAttempt: Long,
        redirectMessageId: String,
        text: String,
        userMessageCountBeforeRedirect: Int,
    ) {
        val resolvedSessionId = canonicalSessionId(storedSessionId) ?: return
        queuedPromptsBySession.getOrPut(resolvedSessionId) { mutableListOf() } += QueuedPrompt(
            id = nextLocalMessageId("queued"),
            text = text,
            deliveryUncertain = true,
            userMessageCountBeforeSubmit = userMessageCountBeforeRedirect,
        )
        parkedQueueSessions += resolvedSessionId
        if (canonicalSessionId(currentStoredSessionId) == resolvedSessionId) {
            mutableState.value = mutableState.value.copy(
                messages = mutableState.value.messages.filterNot { it.id == redirectMessageId },
            )
            refreshActiveQueueProjection(resolvedSessionId)
        }
        val reconciled = if (
            gateway === activeGateway &&
            connectionAttempt == expectedConnectionAttempt &&
            canonicalSessionId(currentStoredSessionId) == resolvedSessionId
        ) {
            try {
                reconcile(activeGateway, resolvedSessionId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        val currentSessionId = canonicalSessionId(resolvedSessionId) ?: resolvedSessionId
        if (
            reconciled == null &&
            canonicalSessionId(currentStoredSessionId) == currentSessionId &&
            queuedPromptsFor(currentSessionId).any { it.deliveryUncertain }
        ) {
            mutableState.value = mutableState.value.copy(
                errorMessage = "Delivery could not be confirmed. Review the paused message before retrying.",
            )
        }
    }

    private fun enqueueRedirectFallback(sessionId: String?, text: String, paused: Boolean = false) {
        val resolvedSessionId = canonicalSessionId(sessionId) ?: return
        queuedPromptsBySession.getOrPut(resolvedSessionId) { mutableListOf() } += QueuedPrompt(
            id = nextLocalMessageId("queued"),
            text = text,
        )
        if (paused) {
            parkedQueueSessions += resolvedSessionId
        } else {
            parkedQueueSessions.remove(resolvedSessionId)
        }
        if (canonicalSessionId(currentStoredSessionId) == resolvedSessionId) {
            refreshActiveQueueProjection(resolvedSessionId)
            if (!paused) drainQueuedPromptIfPossible()
        }
    }

    fun removeQueuedPrompt(promptId: String) {
        val sessionId = currentStoredSessionId ?: mutableState.value.activeSummary?.id ?: return
        val prompt = queuedPromptsBySession[sessionId]?.firstOrNull { it.id == promptId }
        submissionsBySession[sessionId]
            ?.takeIf { it.queuedPromptId == promptId && !it.promptSubmitAttempted }
            ?.let { it.cancelled = true }
        prompt?.attachments.orEmpty().forEach { attachment ->
            scheduleStagedImageRemoval(sessionId, attachment)
        }
        removeQueuedPromptFromSession(sessionId, promptId)
    }

    fun resumeQueuedPrompts() {
        val sessionId = currentStoredSessionId ?: mutableState.value.activeSummary?.id ?: return
        if (queuedPromptsFor(sessionId).isEmpty()) return
        parkedQueueSessions.remove(sessionId)
        refreshActiveQueueProjection(sessionId)
        drainQueuedPromptIfPossible()
    }

    private fun enqueueDraft(
        snapshot: CelesteUiState,
        text: String,
        attachments: List<ComposerAttachment>,
    ) {
        val sessionId = currentStoredSessionId ?: snapshot.activeSummary?.id ?: return
        val queuedPrompt = QueuedPrompt(
            id = nextLocalMessageId("queued"),
            text = text,
            attachments = attachments,
        )
        queuedPromptsBySession.getOrPut(sessionId) { mutableListOf() } += queuedPrompt
        composerAttachmentsBySession.remove(sessionId)
        parkedQueueSessions.remove(sessionId)
        mutableState.value = snapshot.copy(
            draft = "",
            composerAttachments = emptyList(),
            composerAttachmentGeneration = nextComposerAttachmentGeneration(),
            queuedPrompts = queuedPromptsFor(sessionId),
            isQueuePaused = false,
            errorMessage = null,
        )
        drainQueuedPromptIfPossible()
    }

    private fun drainQueuedPromptIfPossible() {
        val snapshot = mutableState.value
        val sessionId = currentStoredSessionId ?: return
        val queuedPrompt = queuedPromptsBySession[sessionId]?.firstOrNull() ?: return
        if (
            snapshot.turnState != TurnState.Idle ||
            sessionId in parkedQueueSessions ||
            sessionId in queueDrainsInFlight ||
            queuedTurnAwaitingActivitySessionId == sessionId
        ) {
            return
        }
        submitMessage(
            snapshot = snapshot,
            text = queuedPrompt.text,
            attachments = queuedPrompt.attachments,
            submittedDraft = null,
            queuedPrompt = queuedPrompt,
        )
    }

    private fun submitMessage(
        snapshot: CelesteUiState,
        text: String,
        attachments: List<ComposerAttachment>,
        submittedDraft: String?,
        queuedPrompt: QueuedPrompt?,
    ) {
        val activeGateway = gateway ?: return
        val runtimeId = currentRuntimeSessionId ?: return
        val storedSessionId = currentStoredSessionId ?: return
        val summary = snapshot.activeSummary ?: return

        val localId = nextLocalMessageId("local")
        val submittedSession = SubmittedSession(
            gateway = activeGateway,
            connectionAttempt = connectionAttempt,
            storedSessionId = storedSessionId,
            summary = summary,
            firstPrompt = text.ifBlank { attachments.joinToString(", ") { it.name } },
            projectedMessageCount = snapshot.messages.count {
                it.role == "user" || it.role == "assistant"
            } + 1,
        )
        val shouldPublish = !currentSessionPublished
        val userMessageCountBeforeSubmit = snapshot.messages.count { it.role == "user" }
        val sessionCouldResumeBeforeSubmit = currentSessionCanResume
        val submission = SubmissionLease(
            gateway = activeGateway,
            profile = summary.profile,
            queuedPromptId = queuedPrompt?.id,
            storedSessionId = storedSessionId,
            runtimeSessionId = runtimeId,
            attachments = attachments,
        )
        submissionsBySession[storedSessionId] = submission
        if (queuedPrompt == null) {
            if (attachments.isNotEmpty()) {
                directAttachmentsInFlightBySession[storedSessionId] = attachments
            }
            composerAttachmentsBySession.remove(storedSessionId)
        }
        mutableState.value = snapshot.copy(
            messages = snapshot.messages + ConversationMessage(
                role = "user",
                text = text,
                id = localId,
                pending = true,
                attachments = attachments.map { it.conversationSummary() },
            ),
            streamingText = "",
            draft = if (queuedPrompt == null) "" else snapshot.draft,
            composerAttachments = if (queuedPrompt == null) emptyList() else snapshot.composerAttachments,
            composerAttachmentGeneration = if (queuedPrompt == null) {
                nextComposerAttachmentGeneration()
            } else {
                snapshot.composerAttachmentGeneration
            },
            turnState = TurnState.Running,
            isCompacting = false,
            errorMessage = null,
        )
        if (queuedPrompt != null) {
            queueDrainsInFlight += storedSessionId
            queuedTurnAwaitingActivitySessionId = storedSessionId
        }
        controllerScope.launch {
            var submittedStoredSessionId = storedSessionId
            var activeSubmittedSession = submittedSession
            try {
                val result = runCatching {
                    val stagedBatch = stageAttachmentsWithSessionRecovery(
                        submission = submission,
                    )
                    submission.attachments = stagedBatch.attachments
                    submittedStoredSessionId = stagedBatch.storedSessionId
                    activeSubmittedSession = activeSubmittedSession.copy(
                        storedSessionId = submittedStoredSessionId,
                        summary = mutableState.value.activeSummary
                            ?.takeIf { it.id == submittedStoredSessionId }
                            ?: activeSubmittedSession.summary.copy(id = submittedStoredSessionId),
                    )
                    ensureSubmissionActive(submission)
                    // prompt.submit creates the durable row before work begins. From this point on,
                    // uncertain delivery must reconcile by stored ID and must never create/resend.
                    currentSessionCanResume = true
                    submission.promptSubmitAttempted = true
                    activeGateway.submitPrompt(
                        runtimeSessionId = stagedBatch.runtimeSessionId,
                        text = modelPromptText(text, submission.attachments),
                        queued = queuedPrompt != null,
                    )
                }
                submittedStoredSessionId = submission.storedSessionId
                activeSubmittedSession = activeSubmittedSession.copy(
                    storedSessionId = submittedStoredSessionId,
                    summary = mutableState.value.activeSummary
                        ?.takeIf { it.id == submittedStoredSessionId }
                        ?: activeSubmittedSession.summary.copy(id = submittedStoredSessionId),
                )
                if (result.isSuccess) {
                    if (queuedPrompt == null) {
                        directAttachmentsInFlightBySession.remove(submittedStoredSessionId)
                    }
                    queuedPrompt?.let { removeQueuedPromptFromSession(submittedStoredSessionId, it.id) }
                    if (isActiveSession(activeSubmittedSession)) {
                        mutableState.value = mutableState.value.copy(
                            messages = mutableState.value.messages.map { message ->
                                if (message.id == localId) message.copy(pending = false) else message
                            },
                        )
                    }
                    if (shouldPublish) publishSubmittedSession(activeSubmittedSession)
                    return@launch
                }

                val failure = result.exceptionOrNull() ?: return@launch
                val failedBeforePromptSubmit = !submission.promptSubmitAttempted
                val definitiveRejection = failedBeforePromptSubmit ||
                    (
                        failure is GatewayRpcException &&
                            activeGateway.state.value == GatewayConnectionState.Connected
                    )
                if (submission.promptSubmitAttempted && definitiveRejection) {
                    currentSessionCanResume = sessionCouldResumeBeforeSubmit
                }
                if (queuedPrompt == null && attachments.isNotEmpty()) {
                    directAttachmentsInFlightBySession.remove(submittedStoredSessionId)
                    val shouldRestoreAttachments = definitiveRejection && when (failure) {
                        is SubmissionSupersededException ->
                            !submission.cancelled || submission.restoreAfterUserStop
                        is GatewayRpcException ->
                            !submission.cancelled &&
                                gateway === activeGateway &&
                                activeGateway.state.value == GatewayConnectionState.Connected
                        else -> false
                    }
                    if (shouldRestoreAttachments) {
                        restoreComposerAttachmentsAfterFailure(submittedStoredSessionId, submission.attachments)
                    } else if (!definitiveRejection) {
                        uncertainDirectPromptsBySession[submittedStoredSessionId] = UncertainDirectPrompt(
                            text = text,
                            submittedDraft = submittedDraft.orEmpty(),
                            attachments = submission.attachments,
                            userMessageCountBeforeSubmit = userMessageCountBeforeSubmit,
                        )
                    }
                }
                if (failure is SubmissionSupersededException) {
                    if (!isActiveSession(activeSubmittedSession)) return@launch
                    val current = mutableState.value
                    val queuedPrompts = queuedPromptsFor(submittedStoredSessionId)
                    mutableState.value = current.copy(
                        messages = current.messages.filterNot { it.id == localId },
                        draft = if (submittedDraft == null) current.draft else {
                            current.draft.ifBlank { submittedDraft }
                        },
                        composerAttachments = if (queuedPrompt == null) {
                            composerAttachmentsFor(submittedStoredSessionId)
                        } else {
                            current.composerAttachments
                        },
                        queuedPrompts = queuedPrompts,
                        isQueuePaused = submittedStoredSessionId in parkedQueueSessions &&
                            queuedPrompts.isNotEmpty(),
                        turnState = TurnState.Idle,
                        errorMessage = null,
                    )
                    return@launch
                }
                if (!isActiveSession(activeSubmittedSession)) {
                    if (queuedPrompt != null) {
                        if (queuedTurnAwaitingActivitySessionId == submittedStoredSessionId) {
                            queuedTurnAwaitingActivitySessionId = null
                        }
                        if (definitiveRejection) {
                            if (queuedPromptsFor(submittedStoredSessionId).isNotEmpty()) {
                                parkedQueueSessions += submittedStoredSessionId
                            }
                        } else {
                            markQueuedPromptDeliveryUncertain(
                                sessionId = submittedStoredSessionId,
                                promptId = queuedPrompt.id,
                                userMessageCountBeforeSubmit = userMessageCountBeforeSubmit,
                            )
                        }
                    }
                    return@launch
                }
                if (definitiveRejection) {
                    if (queuedPrompt != null) {
                        queuedTurnAwaitingActivitySessionId = null
                        if (queuedPromptsFor(submittedStoredSessionId).isNotEmpty()) {
                            parkedQueueSessions += submittedStoredSessionId
                        }
                    }
                    val current = mutableState.value
                    val queuedPrompts = queuedPromptsFor(submittedStoredSessionId)
                    mutableState.value = current.copy(
                        messages = current.messages.filterNot { it.id == localId },
                        draft = if (submittedDraft == null) current.draft else {
                            current.draft.ifBlank { submittedDraft }
                        },
                        composerAttachments = if (queuedPrompt == null) {
                            composerAttachmentsFor(submittedStoredSessionId)
                        } else {
                            current.composerAttachments
                        },
                        queuedPrompts = queuedPrompts,
                        isQueuePaused = queuedPrompts.isNotEmpty() &&
                            (queuedPrompt != null || current.isQueuePaused),
                        turnState = TurnState.Idle,
                        errorMessage = failure.message ?: "Hermes could not send that message.",
                    )
                    if (
                        failedBeforePromptSubmit &&
                        (
                            failure is AuthenticationRejected ||
                                activeGateway.state.value != GatewayConnectionState.Connected
                        )
                    ) {
                        recoverGatewayRequestFailure(
                            activeGateway = activeGateway,
                            failure = failure,
                            wasRunning = false,
                            definitiveTurnState = TurnState.Idle,
                            definitiveMessage = "Hermes could not stage that attachment.",
                        )
                    }
                    return@launch
                }
                if (queuedPrompt != null) {
                    queuedTurnAwaitingActivitySessionId = null
                    markQueuedPromptDeliveryUncertain(
                        sessionId = submittedStoredSessionId,
                        promptId = queuedPrompt.id,
                        userMessageCountBeforeSubmit = userMessageCountBeforeSubmit,
                    )
                }
                recoverGatewayRequestFailure(
                    activeGateway = activeGateway,
                    failure = failure,
                    wasRunning = true,
                    definitiveTurnState = TurnState.Idle,
                    definitiveMessage = "Hermes could not send that message.",
                )
            } finally {
                unregisterSubmission(submission)
                if (queuedPrompt != null) {
                    queueDrainsInFlight.remove(submittedStoredSessionId)
                    drainQueuedPromptIfPossible()
                }
            }
        }
    }

    fun respondToClarification(messageId: String, requestId: String, answer: String) {
        val snapshot = mutableState.value
        val activeGateway = gateway ?: return
        val runtimeId = currentRuntimeSessionId ?: return
        val requestIsPending = snapshot.messages.any { message ->
            message.id == messageId &&
                message.pending &&
                message.clarification?.requestId == requestId &&
                !message.clarification.submitting
        }
        if (!requestIsPending) return

        mutableState.value = snapshot.copy(
            messages = markClarificationSubmitting(snapshot.messages, messageId, requestId),
            errorMessage = null,
        )
        controllerScope.launch {
            val result = runCatching { activeGateway.respondToClarification(requestId, answer) }
            if (gateway !== activeGateway || currentRuntimeSessionId != runtimeId) return@launch
            if (result.isSuccess) {
                mutableState.value = mutableState.value.copy(
                    messages = settleClarificationLocally(
                        messages = mutableState.value.messages,
                        messageId = messageId,
                        requestId = requestId,
                        answer = answer,
                    ),
                    errorMessage = null,
                )
                return@launch
            }

            val failure = result.exceptionOrNull() ?: return@launch
            mutableState.value = mutableState.value.copy(
                messages = resetClarificationSubmission(
                    messages = mutableState.value.messages,
                    messageId = messageId,
                    requestId = requestId,
                ),
            )
            if (failure is GatewayRpcException && activeGateway.state.value == GatewayConnectionState.Connected) {
                mutableState.value = mutableState.value.copy(
                    errorMessage = failure.message ?: "Hermes could not send that answer.",
                )
                return@launch
            }
            recoverGatewayRequestFailure(
                activeGateway = activeGateway,
                failure = failure,
                wasRunning = true,
                definitiveTurnState = TurnState.Running,
                definitiveMessage = "Hermes could not send that answer.",
            )
        }
    }

    private fun createDraftRuntimeForFirstPrompt(snapshot: CelesteUiState) {
        val connection = snapshot.probe ?: return
        val activeCredential = credential ?: return
        mutableState.value = snapshot.copy(
            turnState = TurnState.Synchronizing,
            loadingMessage = null,
            errorMessage = null,
        )
        createDraftRuntime(
            connection = connection,
            activeCredential = activeCredential,
            selectedProfile = snapshot.selectedProfile,
            onRuntimeReady = { summary ->
                completeDraftRuntime(
                    summary = summary,
                    sessionPage = null,
                    connectionWarning = null,
                )
                sendMessage()
            },
            onRuntimeFailure = {
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Idle,
                    loadingMessage = null,
                    errorMessage = "Could not start the conversation. Your message is ready to send again.",
                )
            },
        )
    }

    private suspend fun recoverGatewayRequestFailure(
        activeGateway: GatewayConnection,
        failure: Throwable,
        wasRunning: Boolean,
        definitiveTurnState: TurnState,
        definitiveMessage: String,
    ) {
        currentCoroutineContext().ensureActive()
        if (gateway !== activeGateway) return
        if (failure is AuthenticationRejected) {
            invalidateReusableAuthentication(currentDescriptor, mutableState.value.probe)
            return
        }
        if (failure is GatewayRpcException && activeGateway.state.value == GatewayConnectionState.Connected) {
            mutableState.value = mutableState.value.copy(
                turnState = definitiveTurnState,
                errorMessage = failure.message ?: definitiveMessage,
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            turnState = TurnState.Reconnecting,
            errorMessage = null,
        )
        activeGateway.close()
        scheduleReconnect(wasRunning = wasRunning, immediate = true)
    }

    fun interrupt() {
        val activeGateway = gateway ?: return
        val runtimeId = currentRuntimeSessionId ?: return
        val sessionId = currentStoredSessionId ?: return
        if (mutableState.value.turnState != TurnState.Running) return
        redirectStopGenerationBySession[sessionId] =
            redirectStopGenerationBySession.getOrDefault(sessionId, 0L) + 1L
        if (queuedPromptsFor(sessionId).isNotEmpty()) parkedQueueSessions += sessionId
        submissionsBySession[sessionId]
            ?.takeIf { !it.promptSubmitAttempted }
            ?.let { submission ->
                submission.cancelled = true
                submission.restoreAfterUserStop = true
                mutableState.value = mutableState.value.copy(
                    isQueuePaused = sessionId in parkedQueueSessions,
                    turnState = TurnState.Synchronizing,
                    errorMessage = null,
                )
                return
            }
        mutableState.value = mutableState.value.copy(
            isQueuePaused = sessionId in parkedQueueSessions,
            turnState = TurnState.Synchronizing,
            errorMessage = null,
        )
        controllerScope.launch {
            val result = runCatching {
                activeGateway.interruptSession(runtimeId)
                if (gateway !== activeGateway || currentStoredSessionId != sessionId) return@runCatching
                mutableState.value = mutableState.value.copy(
                    delegateAgents = interruptActiveDelegateAgents(mutableState.value.delegateAgents),
                )
                reconcile(activeGateway, sessionId)
            }
            if (result.isFailure) {
                recoverGatewayRequestFailure(
                    activeGateway = activeGateway,
                    failure = result.exceptionOrNull() ?: return@launch,
                    wasRunning = true,
                    definitiveTurnState = TurnState.Running,
                    definitiveMessage = "Hermes could not stop that turn.",
                )
            }
        }
    }

    fun reconnectNow() {
        if (mutableState.value.activeSummary == null) {
            if (mutableState.value.sessions != null) startUserConversation(clearDraft = false)
            return
        }
        if (gateway == null) return
        val wasRunning = mutableState.value.turnState == TurnState.Running
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        mutableState.value = mutableState.value.copy(
            turnState = TurnState.Reconnecting,
            resumeExhausted = false,
            loadingMessage = null,
            errorMessage = null,
        )
        scheduleReconnect(wasRunning = wasRunning, immediate = true)
    }

    fun onBackground() {
        val descriptor = currentDescriptor ?: return
        if (descriptor.authMode != SavedAuthMode.ProviderSession) return
        if (credential != GatewayCredential.CookieSession) return
        val refreshed = dashboard.exportAuthentication(descriptor.baseUrl) ?: return
        val attempt = connectionAttempt
        controllerScope.launch {
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
        if (mutableState.value.resumeExhausted) return
        if (foregroundCheckJob?.isActive == true || reconciling) return
        if (activeGateway.state.value != GatewayConnectionState.Connected) {
            reconnectNow()
            return
        }
        foregroundCheckJob = controllerScope.launch {
            var resumeAttempted = false
            val health = runCatching {
                activeGateway.request(
                    method = "session.list",
                    params = buildJsonObject { put("limit", 1) },
                    timeoutMillis = 8_000,
                )
                if (currentSessionCanResume) {
                    resumeAttempted = true
                    reconcile(activeGateway, storedSessionId)
                }
            }
            if (health.isFailure && gateway === activeGateway) {
                currentCoroutineContext().ensureActive()
                val wasRunning = mutableState.value.turnState == TurnState.Running
                activeGateway.close()
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Reconnecting,
                    errorMessage = null,
                )
                scheduleReconnect(
                    wasRunning = wasRunning,
                    immediate = true,
                    initialResumeFailures = if (resumeAttempted) 1 else 0,
                )
            }
            foregroundCheckJob = null
        }
    }

    private var currentRuntimeSessionId: String? = null
    private var currentStoredSessionId: String? = null

    private fun observeGateway(activeGateway: GatewayConnection) {
        gatewayEventsJob = controllerScope.launch {
            activeGateway.events.collect { event ->
                if (gateway !== activeGateway) return@collect
                if (reconciling) {
                    bufferedEvents += event
                } else {
                    applyEvent(event)
                }
            }
        }
        gatewayStateJob = controllerScope.launch {
            activeGateway.state.collect { connectionState ->
                if (gateway !== activeGateway) return@collect
                if (connectionState is GatewayConnectionState.Disconnected) {
                    val wasRunning = mutableState.value.turnState == TurnState.Running
                    mutableState.value = mutableState.value.copy(
                        turnState = TurnState.Reconnecting,
                        errorMessage = null,
                    )
                    scheduleReconnect(wasRunning)
                }
            }
        }
    }

    private suspend fun reconcile(activeGateway: GatewayConnection, storedSessionId: String): ResumedSession? {
        reconciling = true
        bufferedEvents.clear()
        try {
            val snapshot = mutableState.value
            val connection = snapshot.probe
            val activeCredential = credential
            val profile = snapshot.activeSummary?.profile ?: snapshot.selectedProfile
            retireRuntimeIfNeeded(activeGateway, storedSessionId)
            val (resumedResult, persistedHistory) = coroutineScope {
                val persisted = if (connection != null && activeCredential != null && profile.isNotBlank()) {
                    async {
                        try {
                            dashboard.loadSessionHistory(
                                baseUrl = connection.baseUrl,
                                credential = activeCredential,
                                sessionId = storedSessionId,
                                profile = profile,
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            null
                        }
                    }
                } else {
                    null
                }
                val runtime = runCatching {
                    activeGateway.resumeStoredSession(storedSessionId, profile, clientSource)
                }
                runtime to persisted?.await()
            }
            if (gateway !== activeGateway) return null
            if (resumedResult.isFailure && persistedHistory?.messages?.isNotEmpty() == true) {
                mutableState.value = mutableState.value.copy(
                    messages = preserveLocalAttachmentPresentation(
                        authoritative = persistedHistory.messages,
                        local = snapshot.messages,
                    ),
                    streamingText = "",
                )
            }
            val resumed = resumedResult.getOrThrow()
            val running = resumed.running == true || resumed.hasLiveProjection
            val persistedTaskSnapshot = persistedHistory?.taskProgressSnapshot
            val authoritativeMessages = persistedHistory?.messages?.ifEmpty { resumed.messages } ?: resumed.messages
            val presentedMessages = preserveLocalAttachmentPresentation(
                authoritative = authoritativeMessages,
                local = snapshot.messages,
            )
            val reconciledMessages = resumed.pendingClarification
                ?.let { bindClarificationRequest(presentedMessages, it) }
                ?: presentedMessages
            if (storedSessionId != resumed.storedSessionId) {
                migrateSessionBoundState(storedSessionId, resumed.storedSessionId)
            }
            val reconciled = resumed.copy(
                messages = reconciledMessages,
                taskProgress = when {
                    !running -> null
                    persistedTaskSnapshot != null -> persistedTaskSnapshot.progress
                    else -> resumed.taskProgress
                },
            )
            applyResumedSession(reconciled)
            val events = bufferedEvents.toList()
            bufferedEvents.clear()
            reconciling = false
            events.forEach(::applyEvent)
            drainQueuedPromptIfPossible()
            return reconciled
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
        reconcileUncertainQueuedPrompts(resumed)
        val restoredDirectDraft = reconcileUncertainDirectPrompt(resumed)
        if (queuedTurnAwaitingActivitySessionId == resumed.storedSessionId) {
            queuedTurnAwaitingActivitySessionId = null
        }
        val liveProjection = resumedLiveProjection(resumed)
        val previousTaskProgress = mutableState.value.taskProgress
        val running = resumed.running == true || resumed.hasLiveProjection
        val restoredTaskProgress = resumed.taskProgress.takeIf { running }
        mutableState.value = mutableState.value.copy(
            messages = liveProjection.messages,
            draft = restoredDirectDraft?.let { mutableState.value.draft.ifBlank { it } }
                ?: mutableState.value.draft,
            taskProgress = restoredTaskProgress,
            streamingText = liveProjection.streamingText,
            composerAttachments = composerAttachmentsFor(resumed.storedSessionId),
            queuedPrompts = queuedPromptsFor(resumed.storedSessionId),
            isQueuePaused = resumed.storedSessionId in parkedQueueSessions &&
                queuedPromptsFor(resumed.storedSessionId).isNotEmpty(),
            turnState = if (running) {
                TurnState.Running
            } else {
                TurnState.Idle
            },
            isCompacting = mutableState.value.isCompacting && running,
            resumeExhausted = false,
            errorMessage = null,
        )
        updateTaskProgressClear(previousTaskProgress, restoredTaskProgress)
        publishCurrentSession()
    }

    private fun resumedLiveProjection(resumed: ResumedSession): ResumedLiveProjection {
        val inflightProjection = if (resumed.inflightCorrections.isEmpty()) {
            ResumedLiveProjection(
                messages = resumed.messages,
                streamingText = unpersistedInflightText(
                    inflight = resumed.inflightAssistantText,
                    messages = resumed.messages,
                ),
            )
        } else {
            var messages = resumed.messages
            val assistant = resumed.inflightAssistantText
            val offsetsUsable = resumed.inflightCorrections.all { correction ->
                correction.assistantOffset?.let { it in 0..assistant.length } == true
            }
            var persistedPrefixAvailable = true
            fun missingSegment(segment: String): String {
                if (segment.isBlank()) return ""
                if (!persistedPrefixAvailable) return segment
                persistedPrefixAvailable = false
                return unpersistedInflightText(segment, resumed.messages)
            }
            var cursor = 0
            resumed.inflightCorrections.forEachIndexed { index, correction ->
                if (offsetsUsable) {
                    val boundary = correction.assistantOffset!!.coerceIn(cursor, assistant.length)
                    val segment = missingSegment(assistant.substring(cursor, boundary))
                    if (segment.isNotBlank()) {
                        messages = appendCurrentTurnMessage(
                            messages = messages,
                            message = ConversationMessage(
                                role = "assistant",
                                text = segment,
                                id = "inflight-assistant-segment-$index-${resumed.runtimeSessionId}",
                                interim = true,
                            ),
                        )
                    }
                    cursor = boundary
                } else if (index == 0) {
                    val missingAssistant = missingSegment(assistant)
                    if (missingAssistant.isNotBlank()) {
                        messages = appendCurrentTurnMessage(
                            messages = messages,
                            message = ConversationMessage(
                                role = "assistant",
                                text = missingAssistant,
                                id = "inflight-assistant-${resumed.runtimeSessionId}",
                                interim = true,
                            ),
                        )
                    }
                }
                messages = appendCurrentTurnMessage(
                    messages = messages,
                    message = ConversationMessage(
                        role = "user",
                        text = correction.text,
                        id = "inflight-correction-$index-${resumed.runtimeSessionId}",
                        userPlacement = UserMessagePlacement.MidTurnCorrection,
                    ),
                )
            }
            ResumedLiveProjection(
                messages = messages,
                streamingText = if (offsetsUsable) assistant.substring(cursor) else "",
            )
        }

        val queuedText = resumed.queuedUserText.trim()
        if (queuedText.isEmpty()) return inflightProjection
        val messagesWithInflightTail = inflightProjection.streamingText
            .takeIf(String::isNotBlank)
            ?.let { text ->
                appendCurrentTurnMessage(
                    messages = inflightProjection.messages,
                    message = ConversationMessage(role = "assistant", text = text, interim = true),
                )
            }
            ?: inflightProjection.messages
        return ResumedLiveProjection(
            messages = messagesWithInflightTail + ConversationMessage(
                role = "user",
                text = queuedText,
                id = "queued-user-${resumed.runtimeSessionId}",
                userPlacement = UserMessagePlacement.NextTurn,
            ),
            streamingText = "",
        )
    }

    private fun isActiveSession(submitted: SubmittedSession): Boolean =
        connectionAttempt == submitted.connectionAttempt &&
            gateway === submitted.gateway &&
            currentStoredSessionId == submitted.storedSessionId

    private fun publishSubmittedSession(submitted: SubmittedSession) {
        if (connectionAttempt != submitted.connectionAttempt) return
        val snapshot = mutableState.value
        if (snapshot.sessions == null) return
        val isActive = isActiveSession(submitted)
        if (isActive) currentSessionPublished = true
        mutableState.value = snapshot.withPublishedSummary(
            summary = submitted.summary,
            fallbackPreview = submitted.firstPrompt,
            projectedMessageCount = submitted.projectedMessageCount,
            makeActive = isActive,
        )
    }

    private fun publishCurrentSession() {
        if (currentSessionPublished) return
        val snapshot = mutableState.value
        val summary = snapshot.activeSummary ?: return
        val firstUserText = snapshot.messages.firstOrNull { it.role == "user" }?.text.orEmpty()
        currentSessionPublished = true
        mutableState.value = snapshot.withPublishedSummary(
            summary = summary,
            fallbackPreview = firstUserText,
            projectedMessageCount = snapshot.messages.count {
                it.role == "user" || it.role == "assistant"
            },
            makeActive = true,
        )
    }

    private fun CelesteUiState.withPublishedSummary(
        summary: StoredSession,
        fallbackPreview: String,
        projectedMessageCount: Int,
        makeActive: Boolean,
    ): CelesteUiState {
        val alreadyVisible = sessions.orEmpty().any { it.id == summary.id }
        val visibleSummary = summary.copy(
            preview = summary.preview.ifBlank { fallbackPreview },
            messageCount = maxOf(summary.messageCount, projectedMessageCount),
        )
        val nextTotal = if (sessions != null && !alreadyVisible) {
            sessionCatalogTotal + 1
        } else {
            sessionCatalogTotal
        }
        return copy(
            activeSummary = if (makeActive) visibleSummary else activeSummary,
            sessions = listOf(visibleSummary) + sessions.orEmpty()
                .filterNot { it.id == visibleSummary.id },
            sessionCatalogTotal = nextTotal,
            hasMoreSessions = nextSessionOffset < nextTotal,
        )
    }

    private fun applyEvent(event: GatewayEvent) {
        val runtimeId = currentRuntimeSessionId ?: return
        if (event.type.startsWith("subagent.") && event.sessionId.isBlank()) return
        if (event.sessionId.isNotBlank() && event.sessionId != runtimeId) return
        val current = mutableState.value
        val storedSessionId = currentStoredSessionId
        val awaitingQueuedActivity = storedSessionId != null &&
            queuedTurnAwaitingActivitySessionId == storedSessionId
        val startsQueuedTurn = awaitingQueuedActivity && event.startsQueuedTurnActivity()
        if (startsQueuedTurn) queuedTurnAwaitingActivitySessionId = null
        if (awaitingQueuedActivity && !startsQueuedTurn && event.isStaleQueuedSettleCandidate()) return
        if (awaitingQueuedActivity && !startsQueuedTurn && event.settlesTurn()) {
            queuedTurnAwaitingActivitySessionId = null
        }
        if (event.settlesTurn()) turnSettlementGeneration += 1
        val reduction = reduceConversationEvent(
            projection = ConversationProjection(
                messages = current.messages,
                streamingText = current.streamingText,
                turnState = current.turnState,
                isCompacting = current.isCompacting,
                taskProgress = current.taskProgress,
                delegateAgents = current.delegateAgents,
                errorMessage = current.errorMessage,
            ),
            event = event,
            localMessageCounter = localMessageCounter,
        )
        localMessageCounter = reduction.localMessageCounter
        val nextTaskProgress = reduction.projection.taskProgress
        mutableState.value = current.copy(
            messages = reduction.projection.messages,
            taskProgress = nextTaskProgress,
            delegateAgents = reduction.projection.delegateAgents,
            streamingText = reduction.projection.streamingText,
            turnState = reduction.projection.turnState,
            isCompacting = reduction.projection.isCompacting,
            errorMessage = reduction.projection.errorMessage,
        )
        updateTaskProgressClear(current.taskProgress, nextTaskProgress)
        drainQueuedPromptIfPossible()
    }

    private fun updateTaskProgressClear(previous: TaskProgress?, next: TaskProgress?) {
        if (previous == next) return
        taskProgressClearJob?.cancel()
        taskProgressClearJob = null
        if (next?.isFinished != true) return
        val runtimeId = currentRuntimeSessionId ?: return
        taskProgressClearJob = controllerScope.launch {
            delay(TASK_PROGRESS_FINISHED_LINGER_MILLIS)
            if (currentRuntimeSessionId == runtimeId && mutableState.value.taskProgress == next) {
                mutableState.value = mutableState.value.copy(taskProgress = null)
            }
            taskProgressClearJob = null
        }
    }

    private suspend fun recreateBlankSession(
        activeGateway: GatewayConnection,
        profile: String,
        replacementTurnState: TurnState = TurnState.Idle,
        drainQueue: Boolean = true,
    ): CreatedSession? {
        val previousStoredId = currentStoredSessionId
        reconciling = true
        bufferedEvents.clear()
        try {
            val created = activeGateway.createSession(profile, clientSource)
            if (gateway !== activeGateway) {
                bufferedEvents.clear()
                reconciling = false
                return null
            }
            currentRuntimeSessionId = created.runtimeSessionId
            currentStoredSessionId = created.storedSessionId
            previousStoredId?.let { previousId ->
                migrateSessionBoundState(previousId, created.storedSessionId)
            }
            val previousSummary = mutableState.value.activeSummary
                ?: throw IllegalStateException("No draft conversation is open.")
            val updatedSummary = previousSummary.copy(id = created.storedSessionId, profile = created.profile)
            mutableState.value = mutableState.value.copy(
                activeSummary = updatedSummary,
                sessions = mutableState.value.sessions?.map { session ->
                    if (session.id == previousStoredId) updatedSummary else session
                },
                turnState = replacementTurnState,
                resumeExhausted = false,
                errorMessage = null,
            )
            val events = bufferedEvents.toList()
            bufferedEvents.clear()
            reconciling = false
            events.forEach(::applyEvent)
            if (drainQueue) drainQueuedPromptIfPossible()
            return created
        } catch (error: Throwable) {
            bufferedEvents.clear()
            reconciling = false
            throw error
        }
    }

    private fun scheduleReconnect(
        wasRunning: Boolean,
        immediate: Boolean = false,
        initialResumeFailures: Int = 0,
    ) {
        val activeGateway = gateway ?: return
        val storedSessionId = currentStoredSessionId ?: mutableState.value.activeSummary?.id ?: return
        if (mutableState.value.delegateAgents.isNotEmpty()) {
            mutableState.value = mutableState.value.copy(delegateAgents = emptyList())
        }
        if (mutableState.value.resumeExhausted) return
        if (reconnectJob?.isActive == true) return
        mutableState.value = mutableState.value.copy(
            turnState = TurnState.Reconnecting,
            resumeExhausted = false,
            loadingMessage = null,
            errorMessage = null,
        )
        reconnectJob = controllerScope.launch {
            var resumeFailures = initialResumeFailures
            while (gateway === activeGateway) {
                val delayMillis = if (immediate && reconnectAttempts == 0) {
                    0L
                } else {
                    reconnectDelayMillis(reconnectAttempts, wasRunning)
                }
                if (delayMillis > 0) delay(delayMillis)
                var resumeAttempted = false
                val result = runCatching {
                    activeGateway.connect()
                    retireRuntimeIfNeeded(activeGateway, storedSessionId)
                    resumeAttempted = true
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
                currentCoroutineContext().ensureActive()
                if (failure is AuthenticationRejected) {
                    val descriptor = currentDescriptor
                    reconnectJob = null
                    closeGateway()
                    invalidateReusableAuthentication(descriptor)
                    return@launch
                }
                if (resumeAttempted) {
                    resumeFailures += 1
                    if (resumeFailures > MAX_RESUME_RETRIES) {
                        reconnectJob = null
                        mutableState.value = mutableState.value.copy(
                            turnState = TurnState.Reconnecting,
                            resumeExhausted = true,
                            loadingMessage = null,
                            errorMessage = null,
                        )
                        return@launch
                    }
                }
                reconnectAttempts += 1
                mutableState.value = mutableState.value.copy(
                    turnState = TurnState.Reconnecting,
                    resumeExhausted = false,
                    errorMessage = null,
                )
            }
            reconnectJob = null
        }
    }

    private fun queuedPromptsFor(sessionId: String): List<QueuedPrompt> =
        queuedPromptsBySession[sessionId]?.toList().orEmpty()

    private fun activeComposerSessionId(): String =
        currentStoredSessionId ?: mutableState.value.activeSummary?.id ?: DRAFT_COMPOSER_SESSION_ID

    private fun composerAttachmentsFor(sessionId: String): List<ComposerAttachment> =
        composerAttachmentsBySession[sessionId]?.toList().orEmpty()

    private fun retainedAttachments(): List<ComposerAttachment> = buildList {
        composerAttachmentsBySession.values.forEach(::addAll)
        queuedPromptsBySession.values.flatten().forEach { prompt -> addAll(prompt.attachments) }
        directAttachmentsInFlightBySession.values.forEach(::addAll)
        uncertainDirectPromptsBySession.values.forEach { prompt -> addAll(prompt.attachments) }
    }.distinctBy(ComposerAttachment::id)

    private suspend fun retireRuntimeIfNeeded(
        activeGateway: GatewayConnection,
        storedSessionId: String,
    ) {
        val runtimeId = runtimeRetirementBySession[storedSessionId] ?: return
        activeGateway.closeRuntimeSession(runtimeId)
        runtimeRetirementBySession.remove(storedSessionId)
        clearSessionRuntimeStaging(storedSessionId)
    }

    private suspend fun stageAttachmentsWithSessionRecovery(
        submission: SubmissionLease,
    ): StagedAttachmentBatch {
        awaitAttachmentCleanup(submission)
        ensureSubmissionActive(submission)
        ensureFreshAttachmentRuntime(submission)
        var index = 0
        var recoveredStaleRuntime = false

        while (index < submission.attachments.size) {
            ensureSubmissionActive(submission)
            val attachment = submission.attachments[index]
            val staged = try {
                submission.gateway.stageAttachment(
                    runtimeSessionId = submission.runtimeSessionId,
                    attachment = attachment,
                    encodingDispatcher = attachmentEncodingDispatcher,
                )
            } catch (failure: Throwable) {
                if (
                    attachment.kind == ComposerAttachmentKind.Image &&
                    failure !is GatewayRpcException
                ) {
                    markRuntimeForRetirement(submission)
                    submission.attachments = clearImageStaging(submission.attachments)
                }
                if (recoveredStaleRuntime || !failure.isSessionNotFoundFailure()) throw failure
                val previousStoredSessionId = submission.storedSessionId
                if (currentSessionCanResume) {
                    val resumed = submission.gateway.resumeStoredSession(
                        submission.storedSessionId,
                        submission.profile,
                        clientSource,
                    )
                    currentCoroutineContext().ensureActive()
                    if (gateway !== submission.gateway || currentStoredSessionId != previousStoredSessionId) {
                        throw AttachmentSessionChangedException()
                    }
                    updateSubmissionIdentity(
                        submission = submission,
                        storedSessionId = resumed.storedSessionId,
                        runtimeSessionId = resumed.runtimeSessionId,
                    )
                    currentSessionCanResume = true
                } else {
                    val created = recreateBlankSession(
                        activeGateway = submission.gateway,
                        profile = submission.profile,
                        replacementTurnState = TurnState.Running,
                        drainQueue = false,
                    ) ?: throw AttachmentSessionChangedException()
                    updateSubmissionIdentity(
                        submission = submission,
                        storedSessionId = created.storedSessionId,
                        runtimeSessionId = created.runtimeSessionId,
                    )
                }
                submission.attachments = clearRuntimeStaging(submission.attachments)
                recoveredStaleRuntime = true
                index = 0
                continue
            }

            submission.attachments = submission.attachments.toMutableList().also { it[index] = staged }
            rememberStagedAttachments(
                sessionId = submission.storedSessionId,
                queuedPromptId = submission.queuedPromptId,
                attachments = submission.attachments,
            )
            if (!isSubmissionActive(submission)) {
                cleanupCancelledSubmission(submission)
                throw SubmissionSupersededException()
            }
            ensureSubmissionActive(submission)
            index += 1
        }

        return StagedAttachmentBatch(
            attachments = submission.attachments,
            runtimeSessionId = submission.runtimeSessionId,
            storedSessionId = submission.storedSessionId,
        )
    }

    private fun rememberStagedAttachments(
        sessionId: String,
        queuedPromptId: String?,
        attachments: List<ComposerAttachment>,
    ) {
        if (queuedPromptId == null) return
        queuedPromptsBySession[sessionId]?.replaceAll { prompt ->
            if (prompt.id == queuedPromptId) prompt.copy(attachments = attachments) else prompt
        }
        refreshActiveQueueProjection(sessionId)
    }

    private fun restoreComposerAttachmentsAfterFailure(
        sessionId: String,
        failedAttachments: List<ComposerAttachment>,
    ) {
        val currentSelection = composerAttachmentsBySession[sessionId].orEmpty()
        composerAttachmentsBySession[sessionId] = (failedAttachments + currentSelection)
            .distinctBy(ComposerAttachment::id)
            .toMutableList()
    }

    private fun migrateComposerAttachmentSessionState(fromSessionId: String, toSessionId: String) {
        if (fromSessionId == toSessionId) return
        val source = composerAttachmentsBySession.remove(fromSessionId).orEmpty()
        if (source.isNotEmpty()) {
            val target = composerAttachmentsBySession[toSessionId].orEmpty()
            composerAttachmentsBySession[toSessionId] = (source + target)
                .distinctBy(ComposerAttachment::id)
                .toMutableList()
        }
        directAttachmentsInFlightBySession.remove(fromSessionId)?.let { inFlight ->
            val target = directAttachmentsInFlightBySession[toSessionId].orEmpty()
            directAttachmentsInFlightBySession[toSessionId] = (inFlight + target)
                .distinctBy(ComposerAttachment::id)
        }
        uncertainDirectPromptsBySession.remove(fromSessionId)?.let { uncertain ->
            uncertainDirectPromptsBySession.putIfAbsent(toSessionId, uncertain)
        }
    }

    private fun ensureSubmissionActive(submission: SubmissionLease) {
        if (!isSubmissionActive(submission)) {
            throw SubmissionSupersededException()
        }
    }

    private fun isSubmissionActive(submission: SubmissionLease): Boolean =
        !submission.cancelled &&
            gateway === submission.gateway &&
            submissionsBySession[submission.storedSessionId] === submission &&
            currentStoredSessionId == submission.storedSessionId &&
            currentRuntimeSessionId == submission.runtimeSessionId

    private fun updateSubmissionIdentity(
        submission: SubmissionLease,
        storedSessionId: String,
        runtimeSessionId: String,
    ) {
        val previousStoredSessionId = submission.storedSessionId
        if (previousStoredSessionId != storedSessionId) {
            migrateSessionBoundState(previousStoredSessionId, storedSessionId)
        }
        submissionsBySession.entries.removeAll { it.value === submission }
        submission.storedSessionId = storedSessionId
        submission.runtimeSessionId = runtimeSessionId
        submissionsBySession[storedSessionId] = submission
        currentStoredSessionId = storedSessionId
        currentRuntimeSessionId = runtimeSessionId
    }

    private fun unregisterSubmission(submission: SubmissionLease) {
        submissionsBySession.entries.removeAll { it.value === submission }
    }

    private fun scheduleStagedImageRemoval(sessionId: String, attachment: ComposerAttachment) {
        val activeGateway = gateway ?: return
        val runtimeId = currentRuntimeSessionId ?: return
        val remotePath = attachment.remotePath?.takeIf(String::isNotBlank) ?: return
        if (
            attachment.kind != ComposerAttachmentKind.Image ||
            attachment.attachedRuntimeSessionId != runtimeId
        ) {
            return
        }
        val previousCleanup = attachmentCleanupBySession[sessionId]
        val cleanup = controllerScope.async {
            previousCleanup?.await()
            if (
                gateway !== activeGateway ||
                currentStoredSessionId != sessionId ||
                currentRuntimeSessionId != runtimeId
            ) {
                return@async
            }
            runCatching { activeGateway.detachImage(runtimeId, remotePath) }
                .onFailure { runtimeRetirementBySession[sessionId] = runtimeId }
        }
        attachmentCleanupBySession[sessionId] = cleanup
        controllerScope.launch {
            runCatching { cleanup.await() }
            if (attachmentCleanupBySession[sessionId] === cleanup) {
                attachmentCleanupBySession.remove(sessionId)
            }
        }
    }

    private suspend fun awaitAttachmentCleanup(submission: SubmissionLease) {
        attachmentCleanupBySession[submission.storedSessionId]?.await()
    }

    private suspend fun ensureFreshAttachmentRuntime(submission: SubmissionLease) {
        val runtimeToRetire = runtimeRetirementBySession[submission.storedSessionId] ?: return
        submission.gateway.closeRuntimeSession(runtimeToRetire)
        ensureSubmissionActive(submission)
        val previousStoredSessionId = submission.storedSessionId
        if (currentSessionCanResume) {
            val resumed = submission.gateway.resumeStoredSession(
                previousStoredSessionId,
                submission.profile,
                clientSource,
            )
            updateSubmissionIdentity(
                submission = submission,
                storedSessionId = resumed.storedSessionId,
                runtimeSessionId = resumed.runtimeSessionId,
            )
            currentSessionCanResume = true
        } else {
            val created = recreateBlankSession(
                activeGateway = submission.gateway,
                profile = submission.profile,
                replacementTurnState = TurnState.Running,
                drainQueue = false,
            ) ?: throw SubmissionSupersededException()
            updateSubmissionIdentity(
                submission = submission,
                storedSessionId = created.storedSessionId,
                runtimeSessionId = created.runtimeSessionId,
            )
        }
        runtimeRetirementBySession.remove(previousStoredSessionId)
        runtimeRetirementBySession.remove(submission.storedSessionId)
        clearSessionRuntimeStaging(submission.storedSessionId)
        submission.attachments = clearRuntimeStaging(submission.attachments)
    }

    private suspend fun cleanupCancelledSubmission(submission: SubmissionLease) {
        submission.attachments
            .filter { attachment ->
                attachment.kind == ComposerAttachmentKind.Image &&
                    attachment.attachedRuntimeSessionId == submission.runtimeSessionId &&
                    !attachment.remotePath.isNullOrBlank()
            }
            .forEach { attachment ->
                runCatching {
                    submission.gateway.detachImage(
                        submission.runtimeSessionId,
                        attachment.remotePath.orEmpty(),
                    )
                }.onFailure {
                    markRuntimeForRetirement(submission)
                }
            }
        submission.attachments = clearImageStaging(submission.attachments)
    }

    private fun markRuntimeForRetirement(submission: SubmissionLease) {
        runtimeRetirementBySession[submission.storedSessionId] = submission.runtimeSessionId
    }

    private fun clearSessionRuntimeStaging(sessionId: String) {
        composerAttachmentsBySession[sessionId]?.replaceAll { attachment ->
            clearRuntimeStaging(attachment)
        }
        queuedPromptsBySession[sessionId]?.replaceAll { prompt ->
            prompt.copy(attachments = clearRuntimeStaging(prompt.attachments))
        }
        directAttachmentsInFlightBySession[sessionId]?.let { attachments ->
            directAttachmentsInFlightBySession[sessionId] = clearRuntimeStaging(attachments)
        }
        uncertainDirectPromptsBySession[sessionId]?.let { prompt ->
            uncertainDirectPromptsBySession[sessionId] = prompt.copy(
                attachments = clearRuntimeStaging(prompt.attachments),
            )
        }
        refreshActiveQueueProjection(sessionId)
    }

    private fun clearRuntimeStaging(attachments: List<ComposerAttachment>): List<ComposerAttachment> =
        attachments.map(::clearRuntimeStaging)

    private fun clearRuntimeStaging(attachment: ComposerAttachment): ComposerAttachment = attachment.copy(
        attachedRuntimeSessionId = null,
        remotePath = if (attachment.kind == ComposerAttachmentKind.Image) null else attachment.remotePath,
    )

    private fun clearImageStaging(attachments: List<ComposerAttachment>): List<ComposerAttachment> =
        attachments.map { attachment ->
            if (attachment.kind == ComposerAttachmentKind.Image) {
                attachment.copy(attachedRuntimeSessionId = null, remotePath = null)
            } else {
                attachment
            }
        }

    private fun modelPromptText(
        text: String,
        attachments: List<ComposerAttachment>,
    ): String {
        val fileReferences = attachments.mapNotNull(ComposerAttachment::refText)
            .joinToString("\n")
        return listOf(fileReferences, text)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
    }

    private fun migrateQueuedSessionState(fromSessionId: String, toSessionId: String) {
        if (fromSessionId == toSessionId) return
        val sourceQueue = queuedPromptsBySession.remove(fromSessionId).orEmpty()
        if (sourceQueue.isNotEmpty()) {
            val targetQueue = queuedPromptsBySession[toSessionId].orEmpty()
            queuedPromptsBySession[toSessionId] = (sourceQueue + targetQueue)
                .distinctBy(QueuedPrompt::id)
                .toMutableList()
        }
        if (parkedQueueSessions.remove(fromSessionId)) parkedQueueSessions += toSessionId
        redirectStopGenerationBySession.remove(fromSessionId)?.let { generation ->
            redirectStopGenerationBySession[toSessionId] = maxOf(
                generation,
                redirectStopGenerationBySession.getOrDefault(toSessionId, 0L),
            )
        }
        if (queueDrainsInFlight.remove(fromSessionId)) queueDrainsInFlight += toSessionId
        if (queuedTurnAwaitingActivitySessionId == fromSessionId) {
            queuedTurnAwaitingActivitySessionId = toSessionId
        }
    }

    private fun migrateSessionBoundState(fromSessionId: String, toSessionId: String) {
        if (fromSessionId == toSessionId) return
        migratedSessionIds.entries.forEach { entry ->
            if (entry.value == fromSessionId) entry.setValue(toSessionId)
        }
        migratedSessionIds[fromSessionId] = toSessionId
        migrateQueuedSessionState(fromSessionId, toSessionId)
        migrateComposerAttachmentSessionState(fromSessionId, toSessionId)
        submissionsBySession.remove(fromSessionId)?.let { submission ->
            submission.storedSessionId = toSessionId
            submissionsBySession[toSessionId] = submission
        }
        attachmentCleanupBySession.remove(fromSessionId)?.let { cleanup ->
            attachmentCleanupBySession[toSessionId] = cleanup
        }
        runtimeRetirementBySession.remove(fromSessionId)?.let { runtimeId ->
            runtimeRetirementBySession[toSessionId] = runtimeId
        }
        val snapshot = mutableState.value
        val migratedSummary = snapshot.activeSummary
            ?.takeIf { it.id == fromSessionId }
            ?.copy(id = toSessionId)
        val migratedSessions = snapshot.sessions?.map { session ->
            if (session.id == fromSessionId) session.copy(id = toSessionId) else session
        }?.distinctBy(StoredSession::id)
        if (migratedSummary != null || migratedSessions != snapshot.sessions) {
            mutableState.value = snapshot.copy(
                activeSummary = migratedSummary ?: snapshot.activeSummary,
                sessions = migratedSessions,
            )
        }
    }

    private fun canonicalSessionId(sessionId: String?): String? {
        var current = sessionId ?: return null
        val visited = mutableSetOf<String>()
        while (visited.add(current)) {
            current = migratedSessionIds[current] ?: return current
        }
        return current
    }

    private fun markQueuedPromptDeliveryUncertain(
        sessionId: String,
        promptId: String,
        userMessageCountBeforeSubmit: Int,
    ) {
        val queue = queuedPromptsBySession[sessionId] ?: return
        queue.replaceAll { prompt ->
            if (prompt.id == promptId) {
                prompt.copy(
                    attachments = prompt.attachments,
                    deliveryUncertain = true,
                    userMessageCountBeforeSubmit = userMessageCountBeforeSubmit,
                )
            } else {
                prompt
            }
        }
        parkedQueueSessions += sessionId
        refreshActiveQueueProjection(sessionId)
    }

    private fun reconcileUncertainQueuedPrompts(resumed: ResumedSession) {
        val queue = queuedPromptsBySession[resumed.storedSessionId] ?: return
        queue.replaceAll { prompt ->
            if (prompt.deliveryUncertain) {
                prompt.copy(
                    attachments = attachmentsForResumedRuntime(
                        prompt.attachments,
                        resumed.runtimeSessionId,
                    ),
                )
            } else {
                prompt
            }
        }
        val representedLiveUserText = (
            sequenceOf(resumed.inflightUserText, resumed.queuedUserText) +
                resumed.inflightCorrections.asSequence().map { it.text }
        ).map(::normalizedPromptText).filter(String::isNotEmpty).toSet()
        val resumedUserMessages = resumed.messages.filter { it.role == "user" }
        val latestResumedUserText = resumedUserMessages.lastOrNull()?.text?.let(::normalizedPromptText).orEmpty()
        queue.removeAll { prompt ->
            if (!prompt.deliveryUncertain) return@removeAll false
            val normalizedModelText = normalizedPromptText(
                modelPromptText(prompt.text, prompt.attachments),
            )
            val normalizedVisibleText = normalizedPromptText(prompt.text)
            normalizedModelText in representedLiveUserText ||
                (
                    prompt.userMessageCountBeforeSubmit?.let { resumedUserMessages.size > it } == true &&
                        (normalizedModelText == latestResumedUserText || normalizedVisibleText == latestResumedUserText)
                    )
        }
        if (queue.isEmpty()) {
            queuedPromptsBySession.remove(resumed.storedSessionId)
            parkedQueueSessions.remove(resumed.storedSessionId)
        }
    }

    private fun reconcileUncertainDirectPrompt(resumed: ResumedSession): String? {
        val prompt = uncertainDirectPromptsBySession[resumed.storedSessionId] ?: return null
        val representedLiveUserText = sequenceOf(
            resumed.inflightUserText,
            resumed.queuedUserText,
        ).map(::normalizedPromptText).filter(String::isNotEmpty).toSet()
        val resumedUserMessages = resumed.messages.filter { it.role == "user" }
        val latestResumedUserText = resumedUserMessages.lastOrNull()?.text
            ?.let(::normalizedPromptText)
            .orEmpty()
        val normalizedModelText = normalizedPromptText(modelPromptText(prompt.text, prompt.attachments))
        val normalizedVisibleText = normalizedPromptText(prompt.text)
        val latestMessageMatches = normalizedModelText == latestResumedUserText ||
            normalizedVisibleText == latestResumedUserText ||
            (
                normalizedVisibleText.isEmpty() &&
                    prompt.attachments.isNotEmpty() &&
                    latestResumedUserText.isEmpty()
                )
        val accepted = normalizedModelText in representedLiveUserText ||
            (normalizedVisibleText.isNotEmpty() && normalizedVisibleText in representedLiveUserText) ||
            (
                resumedUserMessages.size > prompt.userMessageCountBeforeSubmit &&
                    latestMessageMatches
                )
        if (accepted) {
            uncertainDirectPromptsBySession.remove(resumed.storedSessionId)
            return null
        }
        if (resumed.running == true || resumed.hasLiveProjection) return null

        uncertainDirectPromptsBySession.remove(resumed.storedSessionId)
        restoreComposerAttachmentsAfterFailure(
            resumed.storedSessionId,
            attachmentsForResumedRuntime(prompt.attachments, resumed.runtimeSessionId),
        )
        return prompt.submittedDraft
    }

    private fun attachmentsForResumedRuntime(
        attachments: List<ComposerAttachment>,
        runtimeSessionId: String,
    ): List<ComposerAttachment> = attachments.map { attachment ->
        if (attachment.attachedRuntimeSessionId == runtimeSessionId) {
            attachment
        } else {
            clearRuntimeStaging(attachment)
        }
    }

    private fun removeQueuedPromptFromSession(sessionId: String, promptId: String) {
        val queue = queuedPromptsBySession[sessionId] ?: return
        queue.removeAll { it.id == promptId }
        if (queue.isEmpty()) {
            queuedPromptsBySession.remove(sessionId)
            parkedQueueSessions.remove(sessionId)
        }
        refreshActiveQueueProjection(sessionId)
    }

    private fun refreshActiveQueueProjection(sessionId: String) {
        val activeSessionId = currentStoredSessionId ?: mutableState.value.activeSummary?.id
        if (activeSessionId != sessionId) return
        val queuedPrompts = queuedPromptsFor(sessionId)
        mutableState.value = mutableState.value.copy(
            queuedPrompts = queuedPrompts,
            isQueuePaused = sessionId in parkedQueueSessions && queuedPrompts.isNotEmpty(),
        )
    }

    private fun clearAllQueuedPrompts() {
        submissionsBySession.values.forEach { it.cancelled = true }
        attachmentCleanupBySession.values.forEach { it.cancel() }
        submissionsBySession.clear()
        attachmentCleanupBySession.clear()
        runtimeRetirementBySession.clear()
        queuedPromptsBySession.clear()
        composerAttachmentsBySession.clear()
        directAttachmentsInFlightBySession.clear()
        uncertainDirectPromptsBySession.clear()
        parkedQueueSessions.clear()
        queueDrainsInFlight.clear()
        queuedTurnAwaitingActivitySessionId = null
    }

    private fun nextLocalMessageId(prefix: String): String {
        localMessageCounter += 1
        return "$prefix-$localMessageCounter"
    }

    private fun nextComposerAttachmentGeneration(): Long {
        composerAttachmentGenerationCounter += 1
        return composerAttachmentGenerationCounter
    }

    private fun closeGateway() {
        val activeGateway = gateway
        gateway = null
        reconnectJob?.cancel()
        reconnectJob = null
        taskProgressClearJob?.cancel()
        taskProgressClearJob = null
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
        currentSessionPublished = true
        activeGateway?.close()
    }

    private fun releaseResources() {
        if (resourcesReleased) return
        resourcesReleased = true
        connectionJob?.cancel()
        connectionJob = null
        sessionCatalog.invalidateConnection()
        closeGateway()
        dashboard.clearAuthentication()
    }

    internal fun close() {
        releaseResources()
        controllerJob.cancel()
    }

    companion object {
        private const val MAX_RESUME_RETRIES = 4
        private const val DRAFT_COMPOSER_SESSION_ID = "__draft_composer__"
        private const val TASK_PROGRESS_FINISHED_LINGER_MILLIS = 4_000L

        internal fun unpersistedInflightText(
            inflight: String,
            messages: List<ConversationMessage>,
        ): String {
            if (inflight.isBlank()) return ""
            val persisted = messages.lastOrNull {
                it.role == "assistant" && it.text.isNotBlank()
            }?.text?.trim().orEmpty()
            val recovered = inflight.trimStart()
            return if (persisted.isNotEmpty() && recovered.startsWith(persisted)) {
                recovered.removePrefix(persisted)
            } else {
                inflight
            }
        }
    }
}

private fun Throwable.isSessionNotFoundFailure(): Boolean =
    this is GatewayRpcException &&
        (code == 4001 || message.orEmpty().contains("session not found", ignoreCase = true))

private fun GatewayEvent.startsQueuedTurnActivity(): Boolean = when (type) {
    "message.start",
    "message.delta",
    "thinking.delta",
    "reasoning.delta",
    "reasoning.available",
    "tool.start",
    "clarify.request",
    -> true
    "session.busy" -> payload.boolean("busy") == true
    "session.info" -> payload.boolean("running") == true
    else -> false
}

private fun GatewayEvent.settlesTurn(): Boolean = when (type) {
    "message.complete",
    "error",
    "message.error",
    "message.interrupted",
    "session.interrupted",
    -> true
    "session.busy" -> payload.boolean("busy") == false
    "session.info" -> payload.boolean("running") == false
    else -> false
}

private fun GatewayEvent.isStaleQueuedSettleCandidate(): Boolean = when (type) {
    "message.complete" -> payload.string("status") != "error"
    "session.busy" -> payload.boolean("busy") == false
    "session.info" -> payload.boolean("running") == false
    else -> false
}

private fun normalizedPromptText(text: String): String = text.trim().replace(Regex("\\s+"), " ")
