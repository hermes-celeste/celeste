package dev.hazydreams.hermesceleste

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

/** Android lifetime adapter for the platform-neutral application controller. */
internal class CelesteViewModel(
    dashboard: DashboardService = DashboardClient(),
    connectionStore: ConnectionStore = InMemoryConnectionStore(),
    clientSource: String = "android",
    reconnectDelayMillis: (attempt: Int, wasRunning: Boolean) -> Long = { attempt, wasRunning ->
        if (wasRunning && attempt == 0) 100L else min(5_000L, 1_000L shl attempt.coerceAtMost(2))
    },
) : ViewModel() {
    internal val controller = CelesteController(
        parentScope = viewModelScope,
        dashboard = dashboard,
        connectionStore = connectionStore,
        clientSource = clientSource,
        normalizeDashboardUrl = DashboardUrlPolicy::normalize,
        reconnectDelayMillis = reconnectDelayMillis,
    )

    val state = controller.state

    fun updateDashboardUrl(value: String) = controller.updateDashboardUrl(value)

    fun updateUsername(value: String) = controller.updateUsername(value)

    fun updatePassword(value: String) = controller.updatePassword(value)

    fun updateSessionToken(value: String) = controller.updateSessionToken(value)

    fun updateDraft(value: String) = controller.updateDraft(value)

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

    fun createNewConversation() = controller.createNewConversation()

    fun sendMessage() = controller.sendMessage()

    fun interrupt() = controller.interrupt()

    fun reconnectNow() = controller.reconnectNow()

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
