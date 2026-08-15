package dev.hazydreams.hermesceleste

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hazydreams.hermesceleste.connection.ConnectionBootstrapDecision
import dev.hazydreams.hermesceleste.connection.ConnectionStore
import dev.hazydreams.hermesceleste.connection.InMemoryConnectionStore
import dev.hazydreams.hermesceleste.connection.ReusableSecret
import dev.hazydreams.hermesceleste.connection.SavedAuthMode
import dev.hazydreams.hermesceleste.connection.SavedConnectionDescriptor
import dev.hazydreams.hermesceleste.connection.connectionBootstrapDecision
import dev.hazydreams.hermesceleste.attachments.AttachmentCapabilityState
import dev.hazydreams.hermesceleste.attachments.AttachmentDraft
import dev.hazydreams.hermesceleste.attachments.AttachmentErrorKind
import dev.hazydreams.hermesceleste.attachments.AttachmentOperationOwner
import dev.hazydreams.hermesceleste.attachments.AttachmentPreviewState
import dev.hazydreams.hermesceleste.attachments.AttachmentReducer
import dev.hazydreams.hermesceleste.attachments.AttachmentSource
import dev.hazydreams.hermesceleste.attachments.AttachmentStagingStore
import dev.hazydreams.hermesceleste.attachments.AttachmentValidator
import dev.hazydreams.hermesceleste.attachments.AttachmentTransferState
import dev.hazydreams.hermesceleste.attachments.AttachmentValidationException
import dev.hazydreams.hermesceleste.attachments.DraftOwner
import dev.hazydreams.hermesceleste.attachments.ImageOnlyCapabilityState
import dev.hazydreams.hermesceleste.attachments.MessageAttachment
import dev.hazydreams.hermesceleste.attachments.MAX_ATTACHMENT_RETRIES
import dev.hazydreams.hermesceleste.attachments.MAX_PENDING_ATTACHMENTS
import dev.hazydreams.hermesceleste.attachments.UserFacingAttachmentError
import dev.hazydreams.hermesceleste.attachments.UnavailableAttachmentStagingStore
import dev.hazydreams.hermesceleste.attachments.buildImagePrompt
import dev.hazydreams.hermesceleste.network.AuthenticationRejected
import dev.hazydreams.hermesceleste.network.AttachmentCapabilityAdvertisement
import dev.hazydreams.hermesceleste.network.AttachmentFailureClass
import dev.hazydreams.hermesceleste.network.AttachmentSessionOwner
import dev.hazydreams.hermesceleste.network.attachImageBytes
import dev.hazydreams.hermesceleste.network.classifyAttachmentFailure
import dev.hazydreams.hermesceleste.network.detachImage
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
import dev.hazydreams.hermesceleste.network.GatewayRequestTimeout
import dev.hazydreams.hermesceleste.network.ImageMediaReference
import dev.hazydreams.hermesceleste.network.ResumedSession
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.network.boolean
import dev.hazydreams.hermesceleste.network.createSession
import dev.hazydreams.hermesceleste.network.decodeAttachmentCapability
import dev.hazydreams.hermesceleste.network.interruptSession
import dev.hazydreams.hermesceleste.network.resumeStoredSession
import dev.hazydreams.hermesceleste.network.string
import dev.hazydreams.hermesceleste.network.submitPrompt
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
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
    val streamingText: String = "",
    val draft: String = "",
    val attachments: List<AttachmentDraft> = emptyList(),
    val editorGeneration: Long = 0L,
    val attachmentCapability: AttachmentCapabilityState = AttachmentCapabilityState.Unknown,
    val imageOnlyCapability: ImageOnlyCapabilityState = ImageOnlyCapabilityState.Unknown,
    val attachmentNotice: String? = null,
    val turnState: TurnState = TurnState.Idle,
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
    private val attachmentStagingStore: AttachmentStagingStore = UnavailableAttachmentStagingStore(),
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
    private val attachmentJobs = mutableMapOf<UUID, Job>()
    private var attachmentTransactionJob: Job? = null
    private var attachmentTransaction: AttachmentTransaction? = null
    private var unknownSubmit: UnknownSubmit? = null
    private var attachmentPickerToken: AttachmentPickerToken? = null
    private var transcriptPreviewJob: Job? = null
    private val attachmentRetryCounts = mutableMapOf<UUID, Int>()
    private val orphanedAttachmentReferences = mutableMapOf<DraftOwner, MutableSet<String>>()


    private data class AttachmentPickerToken(
        val owner: DraftOwner,
        val editorGeneration: Long,
        val runtimeSessionIdAtStart: String?,
    )

    private data class TranscriptPreviewTarget(
        val messageId: String,
        val attachmentId: String,
        val serverReference: String,
    )

    private data class AttachmentTransaction(
        val activeGateway: GatewayConnection,
        val owner: DraftOwner,
        val generation: Long,
        val runtimeSessionId: String,
        val text: String,
        val attachmentIds: List<UUID>,
        val localMessageId: String,
        val previousMessageIds: Set<String>,
        var activeAttachmentId: UUID? = null,
        var cancelledByUser: Boolean = false,
        val stagedReferences: MutableList<String> = mutableListOf(),
    )

    private data class UnknownSubmit(
        val activeGateway: GatewayConnection,
        val owner: DraftOwner,
        val generation: Long,
        val runtimeSessionIdAtStart: String,
        val text: String,
        val attachmentIds: List<UUID>,
        val previousMessageIds: Set<String>,
    )

    private class StaleAttachmentTransaction : CancellationException()

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
        val current = mutableState.value
        if (current.draft == value) return
        mutableState.value = current.copy(
            draft = value,
            editorGeneration = current.editorGeneration + 1,
            attachmentNotice = null,
        )
    }

    fun beginAttachmentPicker(): Boolean {
        val snapshot = mutableState.value
        if (snapshot.turnState != TurnState.Idle && snapshot.turnState != TurnState.Reconnecting) return false
        if (snapshot.attachmentCapability == AttachmentCapabilityState.Unsupported) {
            mutableState.value = snapshot.copy(
                attachmentNotice = "This gateway does not support image attachments.",
            )
            return false
        }
        attachmentPickerToken = AttachmentPickerToken(
            owner = currentAttachmentOwner(snapshot),
            editorGeneration = snapshot.editorGeneration,
            runtimeSessionIdAtStart = currentRuntimeSessionId,
        )
        return true
    }

    fun onAttachmentPickerResult(uris: List<Uri>) {
        onAttachmentPickerResult(null, uris)
    }

    fun onAttachmentPickerResult(resolver: ContentResolver?, uris: List<Uri>) {
        val token = attachmentPickerToken ?: return
        attachmentPickerToken = null
        val snapshot = mutableState.value
        if (
            !isCurrentAttachmentContext(snapshot, token.owner, token.editorGeneration)
        ) return
        if (uris.isEmpty()) return

        val remaining = (MAX_PENDING_ATTACHMENTS - snapshot.attachments.size).coerceAtLeast(0)
        if (remaining == 0) {
            mutableState.value = snapshot.copy(
                attachmentNotice = "You can attach up to four images.",
            )
            return
        }
        val acceptedUris = uris.take(remaining)
        val dropped = uris.size - acceptedUris.size
        val placeholders = acceptedUris.map { uri ->
            AttachmentDraft(
                displayName = null,
                mimeType = "image/*",
                source = AttachmentSource.PhotoPicker,
                owner = token.owner,
                generation = token.editorGeneration,
            )
        }
        mutableState.value = snapshot.copy(
            attachments = snapshot.attachments + placeholders,
            attachmentNotice = if (dropped > 0) {
                "$dropped image${if (dropped == 1) "" else "s"} not added; four is the maximum."
            } else {
                null
            },
        )
        acceptedUris.zip(placeholders).forEach { (uri, placeholder) ->
            val job = viewModelScope.launch {
                runCatching {
                    attachmentStagingStore.stageUri(
                        resolver = resolver,
                        uri = uri,
                        owner = token.owner,
                        generation = placeholder.generation,
                    )
                }.onSuccess { staged ->
                    val accepted = updateAttachmentIfCurrent(
                        owner = token.owner,
                        editorGeneration = token.editorGeneration,
                        attachmentId = placeholder.id,
                        expectedAttachmentGeneration = placeholder.generation,
                        operationRuntimeSessionIdAtStart = token.runtimeSessionIdAtStart,
                        allowRuntimeChangeAfterStoredOwnerCheck = true,
                    ) { current ->
                        current.copy(
                            displayName = staged.attachment.displayName,
                            mimeType = staged.attachment.mimeType,
                            byteSize = staged.attachment.byteSize,
                            localFileId = staged.attachment.localFileId,
                            previewBytes = staged.previewBytes,
                            preview = if (staged.previewBytes == null) {
                                AttachmentPreviewState.Unavailable
                            } else {
                                AttachmentPreviewState.Ready
                            },
                            transfer = AttachmentTransferState.Ready,
                            error = null,
                        )
                    }
                    if (!accepted) {
                        runCatching { attachmentStagingStore.delete(staged.attachment.localFileId) }
                    }
                }.onFailure { error ->
                    updateAttachmentIfCurrent(
                        owner = token.owner,
                        editorGeneration = token.editorGeneration,
                        attachmentId = placeholder.id,
                        expectedAttachmentGeneration = placeholder.generation,
                        operationRuntimeSessionIdAtStart = token.runtimeSessionIdAtStart,
                        allowRuntimeChangeAfterStoredOwnerCheck = true,
                    ) { current ->
                        current.copy(
                            transfer = AttachmentTransferState.Failed,
                            preview = AttachmentPreviewState.Unavailable,
                            error = attachmentError(error, AttachmentErrorKind.ReadFailed),
                        )
                    }
                }
                attachmentJobs.remove(placeholder.id)
            }
            attachmentJobs[placeholder.id] = job
        }
    }

    fun removeAttachment(attachmentId: UUID) {
        val snapshot = mutableState.value
        if (snapshot.turnState != TurnState.Idle && snapshot.turnState != TurnState.Reconnecting) return
        val attachment = snapshot.attachments.firstOrNull { it.id == attachmentId } ?: return
        if (attachment.owner != currentAttachmentOwner(snapshot)) return
        // A connection loss can expose the composer as Reconnecting while a send
        // transaction is still awaiting an upload response. Removing an item must
        // invalidate that whole transaction before any late completion can mutate state.
        val transaction = attachmentTransaction?.takeIf {
            it.owner == attachment.owner && attachmentId in it.attachmentIds
        }
        val orphanedReferences = if (transaction != null) {
            (
                transaction.stagedReferences +
                    snapshot.attachments.mapNotNull { it.serverReference?.takeIf(String::isNotBlank) }
                ).distinct()
        } else {
            emptyList()
        }
        if (transaction != null) {
            transaction.cancelledByUser = true
            attachmentTransactionJob?.cancel()
            rememberOrphanedReferences(transaction.owner, orphanedReferences)
            unknownSubmit = null
        }
        attachmentJobs.remove(attachmentId)?.cancel()
        attachmentRetryCounts.remove(attachmentId)
        mutableState.value = snapshot.copy(
            messages = transaction?.let { current ->
                snapshot.messages.filterNot { it.id == current.localMessageId }
            } ?: snapshot.messages,
            attachments = snapshot.attachments.filterNot { it.id == attachmentId },
            editorGeneration = snapshot.editorGeneration + 1,
            attachmentNotice = null,
            turnState = if (transaction != null) TurnState.Idle else snapshot.turnState,
        )
        if (attachment.localFileId.isNotBlank()) {
            viewModelScope.launch { runCatching { attachmentStagingStore.delete(attachment.localFileId) } }
        }
        val reference = attachment.serverReference
        val activeGateway = gateway
        val runtimeId = currentRuntimeSessionId
        val owner = attachment.owner
        val referencesToDetach = (orphanedReferences + listOfNotNull(reference)).distinct()
        if (referencesToDetach.isNotEmpty()) {
            // Keep references until detach succeeds; reconnect/context changes can defer cleanup.
            rememberOrphanedReferences(owner, referencesToDetach)
        }
        if (activeGateway != null && runtimeId != null && referencesToDetach.isNotEmpty()) {
            viewModelScope.launch {
                if (
                    gateway !== activeGateway ||
                    currentRuntimeSessionId != runtimeId ||
                    currentAttachmentOwner(mutableState.value) != owner
                ) return@launch
                detachReferences(activeGateway, owner, runtimeId, referencesToDetach)
            }
        }
    }

    fun retryAttachment(attachmentId: UUID) {
        val snapshot = mutableState.value
        if (snapshot.turnState != TurnState.Idle && snapshot.turnState != TurnState.Reconnecting) return
        val current = snapshot.attachments.firstOrNull { it.id == attachmentId } ?: return
        if (
            current.owner != currentAttachmentOwner(snapshot) ||
            current.localFileId.isBlank() ||
            current.error?.retryable != true ||
            current.transfer !in setOf(AttachmentTransferState.Failed, AttachmentTransferState.Unknown)
        ) return
        val attempts = attachmentRetryCounts.getOrDefault(attachmentId, 0)
        if (attempts >= MAX_ATTACHMENT_RETRIES) {
            mutableState.value = snapshot.copy(
                attachmentNotice = "Retry limit reached — select this image again.",
            )
            return
        }
        attachmentRetryCounts[attachmentId] = attempts + 1
        val nextGeneration = snapshot.editorGeneration + 1
        val owner = current.owner
        mutableState.value = snapshot.copy(
            editorGeneration = nextGeneration,
            attachmentNotice = null,
            attachments = snapshot.attachments.map {
                if (it.id == attachmentId) it.copy(
                    generation = nextGeneration,
                    transfer = AttachmentTransferState.Ready,
                    error = null,
                ) else it
            },
        )
        val job = viewModelScope.launch {
            try {
                val activeGateway = gateway
                val storedSessionId = currentStoredSessionId
                    ?: mutableState.value.activeSummary?.id
                if (activeGateway != null && storedSessionId != null) {
                    runCatching {
                        activeGateway.connect()
                        reconcile(activeGateway, storedSessionId)
                    }.getOrElse { error ->
                        updateAttachmentIfCurrent(
                            owner = owner,
                            editorGeneration = nextGeneration,
                            attachmentId = attachmentId,
                            expectedAttachmentGeneration = nextGeneration,
                            operationRuntimeSessionIdAtStart = currentRuntimeSessionId,
                        ) { item ->
                            item.copy(
                                transfer = AttachmentTransferState.Unknown,
                                error = UserFacingAttachmentError(AttachmentErrorKind.UploadStatusUnknown),
                            )
                        }
                        return@launch
                    }
                }
                val runtimeSessionIdAtStart = currentRuntimeSessionId
                runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = attachmentStagingStore.readBytes(current.localFileId)
                        AttachmentValidator.validate(bytes, current.mimeType, current.displayName)
                    }
                }.onSuccess {
                    updateAttachmentIfCurrent(
                        owner = owner,
                        editorGeneration = nextGeneration,
                        attachmentId = attachmentId,
                        expectedAttachmentGeneration = nextGeneration,
                        operationRuntimeSessionIdAtStart = runtimeSessionIdAtStart,
                    ) { item ->
                        item.copy(
                            preview = AttachmentPreviewState.Ready,
                            transfer = AttachmentTransferState.Ready,
                            error = null,
                        )
                    }
                }.onFailure { error ->
                    updateAttachmentIfCurrent(
                        owner = owner,
                        editorGeneration = nextGeneration,
                        attachmentId = attachmentId,
                        expectedAttachmentGeneration = nextGeneration,
                        operationRuntimeSessionIdAtStart = runtimeSessionIdAtStart,
                    ) { item ->
                        item.copy(
                            transfer = AttachmentTransferState.Failed,
                            preview = AttachmentPreviewState.Unavailable,
                            error = attachmentError(error, AttachmentErrorKind.ReadFailed),
                        )
                    }
                }
            } finally {
                if (attachmentJobs[attachmentId] === coroutineContext[Job]) attachmentJobs.remove(attachmentId)
            }
        }
        attachmentJobs[attachmentId] = job
    }

    fun selectProfile(name: String) {
        val snapshot = mutableState.value
        if (snapshot.profiles.none { it.name == name } || snapshot.selectedProfile == name) return
        discardComposerForContextSwitch()
        mutableState.value = mutableState.value.copy(selectedProfile = name)
    }

    fun findDashboard() {
        val rawUrl = mutableState.value.dashboardUrl
        if (rawUrl.isBlank()) return
        discardComposerForContextSwitch()
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
        discardComposerForContextSwitch()
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
        discardComposerForContextSwitch()
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
        discardComposerForContextSwitch()
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
            attachments = emptyList(),
            editorGeneration = snapshot.editorGeneration + 1,
            attachmentCapability = AttachmentCapabilityState.Unknown,
            imageOnlyCapability = ImageOnlyCapabilityState.Unknown,
            attachmentNotice = null,
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
        discardComposerForContextSwitch()
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
        discardComposerForContextSwitch()
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

    private fun currentAttachmentOwner(state: CelesteUiState): DraftOwner {
        val rawOrigin = state.probe?.baseUrl ?: state.dashboardUrl
        val origin = runCatching { DashboardUrlPolicy.normalize(rawOrigin) }.getOrDefault(rawOrigin.trim())
        val profile = state.activeSummary?.profile
            ?.takeIf(String::isNotBlank)
            ?: state.selectedProfile.ifBlank { "default" }
        val storedSession = currentStoredSessionId
            ?.takeIf(String::isNotBlank)
            ?: state.activeSummary?.id?.takeIf(String::isNotBlank)
            ?: "new-conversation"
        return DraftOwner(origin, profile, storedSession)
    }

    private fun isCurrentAttachmentContext(
        state: CelesteUiState,
        owner: DraftOwner,
        editorGeneration: Long,
    ): Boolean =
        state.editorGeneration == editorGeneration && currentAttachmentOwner(state) == owner

    private fun updateAttachmentIfCurrent(
        owner: DraftOwner,
        editorGeneration: Long,
        attachmentId: UUID,
        expectedAttachmentGeneration: Long,
        operationRuntimeSessionIdAtStart: String? = currentRuntimeSessionId,
        allowRuntimeChangeAfterStoredOwnerCheck: Boolean = false,
        transform: (AttachmentDraft) -> AttachmentDraft,
    ): Boolean {
        val current = mutableState.value
        if (!isCurrentAttachmentContext(current, owner, editorGeneration)) return false
        val attachment = current.attachments.firstOrNull { it.id == attachmentId } ?: return false
        val operation = AttachmentOperationOwner(
            draftOwner = owner,
            runtimeSessionIdAtStart = operationRuntimeSessionIdAtStart,
            editorGeneration = editorGeneration,
            attachmentId = attachmentId,
            attachmentGeneration = expectedAttachmentGeneration,
        )
        if (
            !AttachmentReducer.accepts(
                operation = operation,
                owner = owner,
                runtimeSessionId = currentRuntimeSessionId,
                editorGeneration = editorGeneration,
                attachment = attachment,
                allowRuntimeChangeAfterStoredOwnerCheck = allowRuntimeChangeAfterStoredOwnerCheck,
            )
        ) return false
        val updated = transform(attachment)
        if (updated == attachment) return true
        mutableState.value = current.copy(
            attachments = current.attachments.map {
                if (it.id == attachmentId) updated else it
            },
        )
        return true
    }

    private fun rememberOrphanedReferences(owner: DraftOwner, references: List<String>) {
        if (references.isEmpty()) return
        orphanedAttachmentReferences
            .getOrPut(owner) { mutableSetOf() }
            .addAll(references.filter(String::isNotBlank))
    }

    private suspend fun detachReferences(
        activeGateway: GatewayConnection,
        owner: DraftOwner,
        runtimeSessionId: String,
        references: List<String>,
    ) {
        references.distinct().forEach { reference ->
            runCatching {
                activeGateway.detachImage(
                    AttachmentSessionOwner(owner.storedSessionIdOrNewConversationId, runtimeSessionId),
                    reference,
                )
            }.onSuccess {
                orphanedAttachmentReferences[owner]?.remove(reference)
                if (orphanedAttachmentReferences[owner].isNullOrEmpty()) {
                    orphanedAttachmentReferences.remove(owner)
                }
            }
        }
    }

    private suspend fun cleanupOrphanedReferences(activeGateway: GatewayConnection) {
        val state = mutableState.value
        val owner = currentAttachmentOwner(state)
        val runtimeSessionId = currentRuntimeSessionId ?: return
        if (gateway !== activeGateway || owner.storedSessionIdOrNewConversationId == "new-conversation") return
        val references = orphanedAttachmentReferences[owner]?.toList().orEmpty()
        if (references.isNotEmpty()) {
            detachReferences(activeGateway, owner, runtimeSessionId, references)
        }
    }

    private fun attachmentError(
        error: Throwable,
        fallback: AttachmentErrorKind,
    ): UserFacingAttachmentError =
        (error as? AttachmentValidationException)?.userError
            ?: UserFacingAttachmentError(fallback)

    private fun discardComposerForContextSwitch() {
        attachmentPickerToken = null
        attachmentTransactionJob?.cancel()
        attachmentTransactionJob = null
        attachmentTransaction?.let { transaction ->
            transaction.cancelledByUser = true
            rememberOrphanedReferences(transaction.owner, transaction.stagedReferences)
        }
        attachmentTransaction = null
        unknownSubmit = null
        attachmentJobs.values.forEach { it.cancel() }
        attachmentJobs.clear()
        transcriptPreviewJob?.cancel()
        transcriptPreviewJob = null
        attachmentRetryCounts.clear()
        val current = mutableState.value
        rememberOrphanedReferences(
            currentAttachmentOwner(current),
            current.attachments.mapNotNull { it.serverReference?.takeIf(String::isNotBlank) },
        )
        val localFileIds = current.attachments
            .mapNotNull { it.localFileId.takeIf(String::isNotBlank) }
        mutableState.value = current.copy(
            draft = "",
            attachments = emptyList(),
            editorGeneration = current.editorGeneration + 1,
            attachmentCapability = AttachmentCapabilityState.Unknown,
            imageOnlyCapability = ImageOnlyCapabilityState.Unknown,
            attachmentNotice = null,
        )
        localFileIds.forEach { localFileId ->
            viewModelScope.launch { runCatching { attachmentStagingStore.delete(localFileId) } }
        }
    }

    fun openSession(summary: StoredSession) {
        val connection = mutableState.value.probe ?: return
        val activeCredential = credential ?: return
        discardComposerForContextSwitch()
        closeGateway()
        currentSessionCanResume = true
        mutableState.value = mutableState.value.copy(
            activeSummary = summary,
            messages = emptyList(),
            streamingText = "",
            draft = "",
            attachments = emptyList(),
            editorGeneration = mutableState.value.editorGeneration + 1,
            attachmentCapability = AttachmentCapabilityState.Unknown,
            imageOnlyCapability = ImageOnlyCapabilityState.Unknown,
            attachmentNotice = null,
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
        discardComposerForContextSwitch()
        closeGateway()
        mutableState.value = mutableState.value.copy(
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
                applyAttachmentCapability(newGateway)
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
                val events = bufferedEvents.toList()
                bufferedEvents.clear()
                reconciling = false
                mutableState.value = mutableState.value.copy(
                    sessions = listOf(summary) + mutableState.value.sessions.orEmpty()
                        .filterNot { it.id == summary.id },
                    activeSummary = summary,
                    turnState = TurnState.Idle,
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
        discardComposerForContextSwitch()
        closeGateway()
        mutableState.value = mutableState.value.copy(
            activeSummary = null,
            messages = emptyList(),
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
        if (snapshot.turnState != TurnState.Idle) return
        if (activeGateway.state.value != GatewayConnectionState.Connected) {
            mutableState.value = snapshot.copy(
                attachmentNotice = "Reconnect before sending this draft.",
            )
            return
        }

        val text = snapshot.draft.trim()
        val attachments = snapshot.attachments
        if (text.isBlank() && attachments.isEmpty()) return
        if (text.isBlank() && snapshot.imageOnlyCapability != ImageOnlyCapabilityState.Supported) {
            mutableState.value = snapshot.copy(
                attachmentNotice = "Add a caption before sending images; this gateway does not allow image-only prompts.",
            )
            return
        }

        val owner = currentAttachmentOwner(snapshot)
        if (attachments.any { it.owner != owner }) {
            mutableState.value = snapshot.copy(
                attachmentNotice = "These images belong to another conversation. Select them again.",
            )
            return
        }
        if (attachments.isNotEmpty() && snapshot.attachmentCapability == AttachmentCapabilityState.Unsupported) {
            mutableState.value = snapshot.copy(
                attachmentNotice = "This gateway does not support image attachments.",
            )
            return
        }
        if (
            attachments.any {
                it.localFileId.isBlank() ||
                    it.transfer !in setOf(AttachmentTransferState.Ready, AttachmentTransferState.Staged)
            }
        ) {
            mutableState.value = snapshot.copy(
                attachmentNotice = "Wait for each image to finish preparing before sending.",
            )
            return
        }
        val submittedGeneration = snapshot.editorGeneration
        val localId = "local-${localMessageCounter.incrementAndGet()}"
        val optimisticAttachments = attachments.map { attachment ->
            MessageAttachment(
                id = attachment.id.toString(),
                displayName = attachment.displayName,
                mimeType = attachment.mimeType,
                byteSize = attachment.byteSize,
                serverReference = attachment.serverReference,
                preview = attachment.preview,
                previewBytes = attachment.previewBytes,
            )
        }
        mutableState.value = snapshot.copy(
            messages = snapshot.messages + ConversationMessage(
                role = "user",
                text = text,
                id = localId,
                pending = true,
                attachments = optimisticAttachments,
            ),
            streamingText = "",
            turnState = TurnState.Running,
            errorMessage = null,
            attachmentNotice = null,
        )
        unknownSubmit = null
        val transactionState = AttachmentTransaction(
            activeGateway = activeGateway,
            owner = owner,
            generation = submittedGeneration,
            runtimeSessionId = runtimeId,
            text = text,
            attachmentIds = attachments.map(AttachmentDraft::id),
            localMessageId = localId,
            previousMessageIds = snapshot.messages.mapNotNull { it.id }.toSet(),
        )
        attachmentTransaction = transactionState
        // prompt.submit creates the durable row before work begins. From this point on,
        // uncertain delivery must reconcile by stored ID and must never create/resend.
        currentSessionCanResume = true
        val transaction = viewModelScope.launch {
            try {
                val references = mutableListOf<String>()
                for (attachment in attachments) {
                    transactionState.activeAttachmentId = attachment.id
                    ensureCurrentAttachmentTransaction(
                        activeGateway = activeGateway,
                        owner = owner,
                        runtimeId = runtimeId,
                        generation = submittedGeneration,
                        text = text,
                        attachmentIds = attachments.map(AttachmentDraft::id),
                    )
                    val stagedReference = attachment.serverReference
                    if (
                        attachment.transfer == AttachmentTransferState.Staged &&
                        stagedReference != null &&
                        stagedReference.isNotBlank()
                    ) {
                        references += stagedReference
                        transactionState.activeAttachmentId = null
                        continue
                    }
                    val uploadStateAccepted = updateAttachmentIfCurrent(
                        owner = owner,
                        editorGeneration = submittedGeneration,
                        attachmentId = attachment.id,
                        expectedAttachmentGeneration = attachment.generation,
                        operationRuntimeSessionIdAtStart = runtimeId,
                    ) { current ->
                        current.copy(transfer = AttachmentTransferState.Uploading, error = null)
                    }
                    if (!uploadStateAccepted) throw StaleAttachmentTransaction()
                    val bytes = attachmentStagingStore.readBytes(attachment.localFileId)
                    val validated = withContext(Dispatchers.Default) {
                        AttachmentValidator.validate(
                            bytes,
                            attachment.mimeType,
                            attachment.displayName,
                        )
                    }
                    ensureCurrentAttachmentTransaction(
                        activeGateway = activeGateway,
                        owner = owner,
                        runtimeId = runtimeId,
                        generation = submittedGeneration,
                        text = text,
                        attachmentIds = attachments.map(AttachmentDraft::id),
                    )
                    val attached = activeGateway.attachImageBytes(
                        owner = AttachmentSessionOwner(owner.storedSessionIdOrNewConversationId, runtimeId),
                        bytes = bytes,
                        filename = attachment.displayName,
                        mimeType = validated.mimeType,
                        clientAttachmentId = attachment.id.toString(),
                    )
                    references += attached.serverReference
                    transactionState.stagedReferences += attached.serverReference
                    val stagedStateAccepted = updateAttachmentIfCurrent(
                        owner = owner,
                        editorGeneration = submittedGeneration,
                        attachmentId = attachment.id,
                        expectedAttachmentGeneration = attachment.generation,
                        operationRuntimeSessionIdAtStart = runtimeId,
                    ) { current ->
                        current.copy(
                            byteSize = attached.byteSize,
                            mimeType = validated.mimeType,
                            serverReference = attached.serverReference,
                            transfer = AttachmentTransferState.Staged,
                            error = null,
                        )
                    }
                    if (!stagedStateAccepted) throw StaleAttachmentTransaction()
                    if (
                        gateway === activeGateway &&
                        currentRuntimeSessionId == runtimeId &&
                        isCurrentAttachmentContext(mutableState.value, owner, submittedGeneration)
                    ) {
                        mutableState.value = mutableState.value.copy(
                            attachmentCapability = AttachmentCapabilityState.Supported,
                        )
                    }
                    transactionState.activeAttachmentId = null
                }

                ensureCurrentAttachmentTransaction(
                    activeGateway = activeGateway,
                    owner = owner,
                    runtimeId = runtimeId,
                    generation = submittedGeneration,
                    text = text,
                    attachmentIds = attachments.map(AttachmentDraft::id),
                )
                val submittedText = buildImagePrompt(text, references)
                transactionState.activeAttachmentId = null
                activeGateway.submitPrompt(
                    runtimeSessionId = runtimeId,
                    text = submittedText,
                    allowEmptyCaption = text.isBlank(),
                )
                reconcile(activeGateway, currentStoredSessionId ?: owner.storedSessionIdOrNewConversationId)
                if (gateway === activeGateway) {
                    mutableState.value = mutableState.value.copy(
                        messages = mutableState.value.messages.map { message ->
                            if (message.id == localId) message.copy(pending = false) else message
                        },
                    )
                }
                clearSubmittedDraftIfCurrent(
                    activeGateway = activeGateway,
                    owner = owner,
                    generation = submittedGeneration,
                    expectedRuntimeSessionId = runtimeId,
                    text = text,
                    attachmentIds = attachments.map(AttachmentDraft::id),
                    allowRuntimeChangeAfterStoredOwnerCheck = true,
                )
            } catch (error: Throwable) {
                if (error !is GatewayRequestTimeout &&
                    error is CancellationException &&
                    error !is TimeoutCancellationException &&
                    error !is StaleAttachmentTransaction
                ) throw error
                if (
                    error is CancellationException &&
                    error !is TimeoutCancellationException &&
                    transactionState.cancelledByUser
                ) return@launch
                handleAttachmentTransactionFailure(
                    activeGateway = activeGateway,
                    owner = owner,
                    generation = submittedGeneration,
                    runtimeId = runtimeId,
                    text = text,
                    attachmentIds = attachments.map(AttachmentDraft::id),
                    localMessageId = localId,
                    failedAttachmentId = transactionState.activeAttachmentId,
                    previousMessageIds = transactionState.previousMessageIds,
                    error = error,
                )
            } finally {
                if (transactionState.cancelledByUser) {
                    rememberOrphanedReferences(transactionState.owner, transactionState.stagedReferences)
                }
                if (attachmentTransaction === transactionState) attachmentTransaction = null
                if (attachmentTransactionJob === coroutineContext[Job]) {
                    attachmentTransactionJob = null
                }
            }
        }
        attachmentTransactionJob = transaction
    }

    private fun ensureCurrentAttachmentTransaction(
        activeGateway: GatewayConnection,
        owner: DraftOwner,
        runtimeId: String,
        generation: Long,
        text: String,
        attachmentIds: List<UUID>,
    ) {
        val current = mutableState.value
        val ids = current.attachments.map(AttachmentDraft::id)
        if (
            gateway !== activeGateway ||
            currentRuntimeSessionId != runtimeId ||
            !isCurrentAttachmentContext(current, owner, generation) ||
            current.draft.trim() != text ||
            ids != attachmentIds
        ) {
            throw StaleAttachmentTransaction()
        }
    }

    private suspend fun clearSubmittedDraftIfCurrent(
        activeGateway: GatewayConnection,
        owner: DraftOwner,
        generation: Long,
        expectedRuntimeSessionId: String?,
        text: String,
        attachmentIds: List<UUID>,
        allowRuntimeChangeAfterStoredOwnerCheck: Boolean = false,
    ) {
        val current = mutableState.value
        if (
            gateway !== activeGateway ||
            currentStoredSessionId != owner.storedSessionIdOrNewConversationId ||
            !allowRuntimeChangeAfterStoredOwnerCheck && currentRuntimeSessionId != expectedRuntimeSessionId ||
            !isCurrentAttachmentContext(current, owner, generation) ||
            current.draft.trim() != text ||
            current.attachments.map(AttachmentDraft::id) != attachmentIds
        ) return
        val localFileIds = current.attachments.mapNotNull { it.localFileId.takeIf(String::isNotBlank) }
        mutableState.value = current.copy(
            draft = "",
            attachments = emptyList(),
            editorGeneration = current.editorGeneration + 1,
            attachmentNotice = null,
        )
        localFileIds.forEach { localFileId ->
            runCatching { attachmentStagingStore.delete(localFileId) }
        }
    }

    private suspend fun handleAttachmentTransactionFailure(
        activeGateway: GatewayConnection,
        owner: DraftOwner,
        generation: Long,
        runtimeId: String,
        text: String,
        attachmentIds: List<UUID>,
        localMessageId: String,
        failedAttachmentId: UUID?,
        previousMessageIds: Set<String>,
        error: Throwable,
    ) {
        val current = mutableState.value
        if (
            gateway !== activeGateway ||
            !isCurrentAttachmentContext(current, owner, generation) ||
            currentStoredSessionId != owner.storedSessionIdOrNewConversationId ||
            currentRuntimeSessionId != runtimeId &&
                !(currentAttachmentOwner(current) == owner) ||
            current.draft.trim() != text ||
            current.attachments.map(AttachmentDraft::id) != attachmentIds
        ) return

        if (failedAttachmentId != null) {
            val classification = if (error is AttachmentValidationException) {
                null
            } else if (error is StaleAttachmentTransaction) {
                AttachmentFailureClass.Unknown
            } else {
                classifyAttachmentFailure(error)
            }
            val userError = when {
                error is AttachmentValidationException -> error.userError
                classification == AttachmentFailureClass.Unsupported ->
                    UserFacingAttachmentError(AttachmentErrorKind.UnsupportedGateway)
                classification == AttachmentFailureClass.AuthRequired ->
                    UserFacingAttachmentError(AttachmentErrorKind.AuthenticationRequired)
                classification == AttachmentFailureClass.Unknown ->
                    UserFacingAttachmentError(AttachmentErrorKind.UploadStatusUnknown)
                else -> UserFacingAttachmentError(AttachmentErrorKind.UploadFailed)
            }
            val transfer = if (classification == AttachmentFailureClass.Unknown) {
                AttachmentTransferState.Unknown
            } else {
                AttachmentTransferState.Failed
            }
            val capability = when (classification) {
                AttachmentFailureClass.Unsupported -> AttachmentCapabilityState.Unsupported
                AttachmentFailureClass.AuthRequired -> AttachmentCapabilityState.AuthRequired
                AttachmentFailureClass.Unknown -> AttachmentCapabilityState.TransientFailure
                else -> current.attachmentCapability
            }
            mutableState.value = current.copy(
                messages = current.messages.filterNot { it.id == localMessageId },
                attachments = current.attachments.map { attachment ->
                    if (attachment.id == failedAttachmentId) {
                        attachment.copy(transfer = transfer, error = userError)
                    } else {
                        attachment
                    }
                },
                turnState = TurnState.Idle,
                attachmentCapability = capability,
                attachmentNotice = userError.message,
                errorMessage = null,
            )
            return
        }

        // prompt.submit has no idempotency key. Reconnect and reconcile once, but never resend.
        unknownSubmit = UnknownSubmit(
            activeGateway = activeGateway,
            owner = owner,
            generation = generation,
            runtimeSessionIdAtStart = runtimeId,
            text = text,
            attachmentIds = attachmentIds,
            previousMessageIds = previousMessageIds,
        )
        mutableState.value = current.copy(
            messages = current.messages.filterNot { it.id == localMessageId },
            turnState = TurnState.Reconnecting,
            attachmentNotice = "Send status unknown — reconnect and check history before retrying.",
            errorMessage = null,
        )
        val storedSessionId = currentStoredSessionId ?: return
        if (gateway !== activeGateway) return
        if (activeGateway.state.value != GatewayConnectionState.Connected) {
            scheduleReconnect(wasRunning = false, immediate = true)
            return
        }
        val reconciled = runCatching { reconcile(activeGateway, storedSessionId) }
        if (reconciled.isFailure) {
            mutableState.value = mutableState.value.copy(
                turnState = TurnState.Reconnecting,
                attachmentNotice = "Send status unknown — reconnect and check history before retrying.",
            )
            scheduleReconnect(wasRunning = false)
            return
        }
        reconcileUnknownSubmit(activeGateway)
    }

    private suspend fun reconcileUnknownSubmit(activeGateway: GatewayConnection) {
        val pending = unknownSubmit ?: return
        if (pending.activeGateway !== activeGateway || gateway !== activeGateway) return
        val current = mutableState.value
        if (
            currentStoredSessionId != pending.owner.storedSessionIdOrNewConversationId ||
            !isCurrentAttachmentContext(current, pending.owner, pending.generation) ||
            current.draft.trim() != pending.text ||
            current.attachments.map(AttachmentDraft::id) != pending.attachmentIds
        ) {
            unknownSubmit = null
            return
        }
        val accepted = current.messages.any { message ->
            message.role == "user" &&
                message.text.trim() == pending.text &&
                (message.id == null || message.id !in pending.previousMessageIds) &&
                (pending.attachmentIds.isEmpty() || message.attachments.isNotEmpty())
        }
        if (accepted) {
            unknownSubmit = null
            clearSubmittedDraftIfCurrent(
                activeGateway = activeGateway,
                owner = pending.owner,
                generation = pending.generation,
                expectedRuntimeSessionId = pending.runtimeSessionIdAtStart,
                text = pending.text,
                attachmentIds = pending.attachmentIds,
                allowRuntimeChangeAfterStoredOwnerCheck = true,
            )
        } else {
            mutableState.value = current.copy(
                turnState = TurnState.Idle,
                attachmentNotice = "Send not found — review the draft and retry explicitly.",
            )
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
            if (resumed.storedSessionId != storedSessionId) {
                throw IOException("Hermes resumed a different stored conversation.")
            }
            if (gateway !== activeGateway) return
            applyResumedSession(resumed)
            applyAttachmentCapability(activeGateway)
            loadTranscriptPreviews(activeGateway, resumed.messages)
            val events = bufferedEvents.toList()
            bufferedEvents.clear()
            reconciling = false
            events.forEach(::applyEvent)
            cleanupOrphanedReferences(activeGateway)
            reconcileUnknownSubmit(activeGateway)
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

    private fun applyAttachmentCapability(activeGateway: GatewayConnection) {
        if (gateway !== activeGateway) return
        val advertisement = activeGateway.readyPayload
            ?.let(::decodeAttachmentCapability)
            ?: AttachmentCapabilityAdvertisement()
        mutableState.value = mutableState.value.copy(
            attachmentCapability = advertisement.upload,
            imageOnlyCapability = advertisement.imageOnly,
        )
    }

    private fun loadTranscriptPreviews(
        activeGateway: GatewayConnection,
        messages: List<ConversationMessage>,
    ) {
        transcriptPreviewJob?.cancel()
        val snapshot = mutableState.value
        val activeCredential = credential ?: return
        val baseUrl = snapshot.probe?.baseUrl ?: snapshot.dashboardUrl
        val owner = currentAttachmentOwner(snapshot)
        val targets = messages.flatMap { message ->
            val messageId = message.id ?: return@flatMap emptyList()
            message.attachments.mapNotNull { attachment ->
                val reference = attachment.serverReference?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                TranscriptPreviewTarget(messageId, attachment.id, reference)
            }
        }
        if (targets.isEmpty()) return

        transcriptPreviewJob = viewModelScope.launch {
            targets.forEach { target ->
                val preview = try {
                    Result.success(
                        withContext(Dispatchers.IO) {
                            dashboard.readImageMedia(
                                baseUrl = baseUrl,
                                credential = activeCredential,
                                reference = ImageMediaReference(
                                    normalizedGatewayOrigin = owner.normalizedGatewayOrigin,
                                    profileId = owner.profileId,
                                    serverReference = target.serverReference,
                                ),
                            )
                        },
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Result.failure<ByteArray>(error)
                }
                if (
                    gateway !== activeGateway ||
                    currentStoredSessionId != owner.storedSessionIdOrNewConversationId ||
                    currentAttachmentOwner(mutableState.value) != owner
                ) return@launch
                val current = mutableState.value
                if (
                    current.messages.none { message ->
                        message.id == target.messageId &&
                            message.attachments.any {
                                it.id == target.attachmentId &&
                                    it.serverReference == target.serverReference
                            }
                    }
                ) return@launch
                mutableState.value = current.copy(
                    messages = current.messages.map { message ->
                        if (message.id != target.messageId) return@map message
                        message.copy(
                            attachments = message.attachments.map { attachment ->
                                if (attachment.id != target.attachmentId) {
                                    attachment
                                } else if (preview.isSuccess) {
                                    attachment.copy(
                                        preview = AttachmentPreviewState.Ready,
                                        previewBytes = preview.getOrNull(),
                                    )
                                } else {
                                    attachment.copy(
                                        preview = AttachmentPreviewState.Unavailable,
                                        previewBytes = null,
                                    )
                                }
                            },
                        )
                    },
                )
            }
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
            mutableState.value = mutableState.value.copy(
                activeSummary = updatedSummary,
                sessions = mutableState.value.sessions?.map { session ->
                    if (session.id == previousStoredId) updatedSummary else session
                },
                turnState = TurnState.Idle,
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
        activeGateway?.close()
    }

    override fun onCleared() {
        connectionJob?.cancel()
        connectionJob = null
        attachmentTransactionJob?.cancel()
        attachmentJobs.values.forEach { it.cancel() }
        attachmentJobs.clear()
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
