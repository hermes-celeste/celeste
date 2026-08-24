package dev.hazydreams.hermesceleste

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hazydreams.hermesceleste.connection.ConnectionStore
import dev.hazydreams.hermesceleste.connection.InMemoryConnectionStore
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.DashboardClient
import dev.hazydreams.hermesceleste.network.DashboardService
import dev.hazydreams.hermesceleste.network.DashboardUrlPolicy
import dev.hazydreams.hermesceleste.network.StoredSession
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Android lifetime adapter for the platform-neutral application controller. */
internal class CelesteViewModel(
    dashboard: DashboardService = DashboardClient(),
    connectionStore: ConnectionStore = InMemoryConnectionStore(),
    clientSource: String = "android",
    private val attachmentReader: AndroidAttachmentReader? = null,
    attachmentEncodingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    reconnectDelayMillis: (attempt: Int, wasRunning: Boolean) -> Long = { attempt, wasRunning ->
        if (wasRunning && attempt == 0) 100L else min(5_000L, 1_000L shl attempt.coerceAtMost(2))
    },
) : ViewModel() {
    private val composerFocusRequests = ComposerFocusRequests()
    private val attachmentImportMutex = Mutex()

    internal val controller = CelesteController(
        parentScope = viewModelScope,
        dashboard = dashboard,
        connectionStore = connectionStore,
        clientSource = clientSource,
        normalizeDashboardUrl = DashboardUrlPolicy::normalize,
        attachmentEncodingDispatcher = attachmentEncodingDispatcher,
        reconnectDelayMillis = reconnectDelayMillis,
    )

    val state = controller.state
    internal val composerFocusRequest = composerFocusRequests.pending

    fun updateDashboardUrl(value: String) = controller.updateDashboardUrl(value)

    fun updateUsername(value: String) = controller.updateUsername(value)

    fun updatePassword(value: String) = controller.updatePassword(value)

    fun updateSessionToken(value: String) = controller.updateSessionToken(value)

    fun updateDraft(value: String) = controller.updateDraft(value)

    fun attachmentImportBudget(): AttachmentImportBudget = controller.attachmentImportBudget()

    fun addPickedAttachments(
        attachments: List<PickedComposerAttachment>,
        expectedGeneration: Long = state.value.composerAttachmentGeneration,
    ) = controller.addPickedAttachments(attachments, expectedGeneration)

    fun removeComposerAttachment(attachmentId: String) =
        controller.removeComposerAttachment(attachmentId)

    fun reportAttachmentError(message: String) = controller.reportAttachmentError(message)

    fun importAttachments(
        uris: List<Uri>,
        kind: ComposerAttachmentKind,
        expectedGeneration: Long,
    ) {
        val reader = attachmentReader ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            attachmentImportMutex.withLock {
                if (state.value.composerAttachmentGeneration != expectedGeneration) {
                    return@withLock
                }
                val budget = attachmentImportBudget()
                val result = withContext(Dispatchers.IO) {
                    reader.read(
                        uris = uris,
                        kind = kind,
                        existingAttachmentCount = budget.attachmentCount,
                        existingAttachmentBytes = budget.byteSize,
                    )
                }
                if (state.value.composerAttachmentGeneration != expectedGeneration) {
                    return@withLock
                }
                if (result.attachments.isNotEmpty()) {
                    addPickedAttachments(result.attachments, expectedGeneration)
                }
                result.errors.firstOrNull()?.let(::reportAttachmentError)
            }
        }
    }

    fun selectProfile(name: String) = controller.selectProfile(name)

    fun findDashboard() = controller.findDashboard()

    fun loadSessions() = controller.loadSessions()

    fun loadMoreSessions() = controller.loadMoreSessions()

    fun updateSessionSearchQuery(value: String) = controller.updateSessionSearchQuery(value)

    fun retrySavedConnection() = controller.retrySavedConnection()

    fun useAnotherConnection() = controller.useAnotherConnection()

    fun signOut() = controller.signOut()

    fun forgetConnection() = controller.forgetConnection()

    fun openSession(summary: StoredSession) = controller.openSession(summary)

    fun setSessionPinned(summary: StoredSession, pinned: Boolean) =
        controller.setSessionPinned(summary, pinned)

    fun renameSession(
        summary: StoredSession,
        title: String,
        onComplete: (String?) -> Unit,
    ) = controller.renameSession(summary, title, onComplete)

    fun createNewConversation() {
        controller.createNewConversation()
        composerFocusRequests.request()
    }

    internal fun completeComposerFocusRequest(requestId: Long): Boolean =
        composerFocusRequests.complete(requestId)

    fun sendMessage() = controller.sendMessage()

    fun removeQueuedPrompt(promptId: String) = controller.removeQueuedPrompt(promptId)

    fun resumeQueuedPrompts() = controller.resumeQueuedPrompts()

    fun interrupt() = controller.interrupt()

    fun onBackground() = controller.onBackground()

    fun onForeground() = controller.onForeground()

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }

    companion object {
        internal fun unpersistedInflightText(
            inflight: String,
            messages: List<ConversationMessage>,
        ): String = CelesteController.unpersistedInflightText(inflight, messages)
    }
}
