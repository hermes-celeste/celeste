package dev.hazydreams.hermesceleste

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.hazydreams.hermesceleste.network.AuthProvider
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.HermesCelesteTheme

private val previewSessions = listOf(
    StoredSession(
        id = "celeste-slice",
        title = "Hermes Celeste: first native slice",
        preview = "Port the dashboard protocol, preserve the shared session store, and make the mobile experience feel intentional.",
        startedAt = 1_786_104_000.0,
        messageCount = 18,
        source = "desktop",
    ),
    StoredSession(
        id = "visual-layer",
        title = "Visual direction and interaction language",
        preview = "A luminous editorial interface with warm restraint, clear hierarchy, and atmosphere that serves the content.",
        startedAt = 1_786_017_600.0,
        messageCount = 12,
        source = "desktop",
        profile = "work",
    ),
    StoredSession(
        id = "dashboard-notes",
        title = "Dashboard connection notes",
        preview = "Verified ticketed WebSocket authentication and resumed an existing Hermes conversation.",
        startedAt = 1_785_931_200.0,
        messageCount = 9,
        source = "cli",
    ),
    StoredSession(
        id = "release-checklist",
        title = "Android release checklist",
        preview = "Secure credentials, emulator coverage, streaming, reconnection, and accessibility remain before release.",
        startedAt = 1_785_844_800.0,
        messageCount = 21,
        source = "desktop",
    ),
)

private val previewMessages = listOf(
    ConversationMessage(
        role = "user",
        text = "I want the mobile client to continue the exact conversation I started on Desktop.",
        id = "preview-user-1",
    ),
    ConversationMessage(
        role = "assistant",
        text = "It now resumes the shared server-side session instead of maintaining a separate mobile copy.",
        id = "preview-assistant-1",
    ),
    ConversationMessage(
        role = "tool",
        toolName = "protocol_reference",
        text = "Compared the current Hermes gateway contract with the mobile reference client.",
        id = "preview-tool-1",
    ),
)

@PreviewTest
@Preview(name = "01 · Connect", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun ConnectPreviewScreenshot() {
    HermesCelesteTheme {
        ConnectScreen(
            dashboardUrl = "100.101.22.9:9119",
            onDashboardUrlChange = {},
            probe = null,
            username = "",
            onUsernameChange = {},
            password = "",
            onPasswordChange = {},
            sessionToken = "",
            onSessionTokenChange = {},
            loadingMessage = null,
            errorMessage = null,
            onProbe = {},
            onLoadSessions = {},
        )
    }
}

@PreviewTest
@Preview(name = "02 · Password sign-in", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun PasswordConnectPreviewScreenshot() {
    HermesCelesteTheme {
        ConnectScreen(
            dashboardUrl = "https://hermes.example.net",
            onDashboardUrlChange = {},
            probe = DashboardProbeResult(
                baseUrl = "https://hermes.example.net",
                authRequired = true,
                providers = listOf(AuthProvider("password", "Password", supportsPassword = true)),
                version = "0.20.0",
            ),
            username = "celeste",
            onUsernameChange = {},
            password = "preview-only",
            onPasswordChange = {},
            sessionToken = "",
            onSessionTokenChange = {},
            loadingMessage = null,
            errorMessage = null,
            onProbe = {},
            onLoadSessions = {},
        )
    }
}

@PreviewTest
@Preview(name = "03 · Conversations", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun SessionListPreviewScreenshot() {
    HermesCelesteTheme {
        SessionListScreen(
            sessions = previewSessions,
            profiles = listOf(
                DashboardProfile(name = "default", isDefault = true),
                DashboardProfile(name = "work", model = "Hermes 4"),
            ),
            selectedProfile = "work",
            loadingMessage = null,
            errorMessage = null,
            onBack = {},
            onProfileSelected = {},
            onNewConversation = {},
            onSessionSelected = {},
        )
    }
}

@PreviewTest
@Preview(name = "04 · New chat", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun NewConversationPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            summary = StoredSession(
                id = "new-work-chat",
                title = "New conversation",
                preview = "",
                startedAt = 0.0,
                messageCount = 0,
                source = "android",
                profile = "work",
            ),
            messages = emptyList(),
            turnState = TurnState.Idle,
        )
    }
}

@PreviewTest
@Preview(name = "05 · Composing", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun ComposingPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            draft = "Can you turn that into the first working chat milestone?",
            turnState = TurnState.Idle,
        )
    }
}

@PreviewTest
@Preview(name = "06 · Streaming", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun StreamingPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            messages = previewMessages + ConversationMessage(
                role = "user",
                text = "Build it with reconnection that cannot duplicate my prompt.",
                id = "preview-user-2",
            ),
            streamingText = "The socket now stays deliberately thin. A lifecycle-aware session controller owns retries, refreshes its one-use ticket, resumes the authoritative history, and then",
            turnState = TurnState.Running,
        )
    }
}

@PreviewTest
@Preview(name = "07 · Completed", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun CompletedPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            messages = previewMessages + listOf(
                ConversationMessage(
                    role = "user",
                    text = "Build it with reconnection that cannot duplicate my prompt.",
                    id = "preview-user-2",
                ),
                ConversationMessage(
                    role = "assistant",
                    text = "Done. Celeste reconciles against the server snapshot after reconnect, then replays only events received during that reconciliation window. It never blindly resends an uncertain prompt.",
                    id = "preview-assistant-2",
                ),
            ),
            turnState = TurnState.Idle,
        )
    }
}

@PreviewTest
@Preview(name = "08 · Reconnecting", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun ReconnectingPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            draft = "This draft stays here while the connection recovers.",
            turnState = TurnState.Reconnecting,
            errorMessage = "The dashboard connection closed before Hermes finished responding.",
        )
    }
}

@Composable
private fun PreviewConversation(
    summary: StoredSession = previewSessions[1],
    messages: List<ConversationMessage> = previewMessages,
    streamingText: String = "",
    draft: String = "",
    turnState: TurnState,
    errorMessage: String? = null,
) {
    ConversationScreen(
        summary = summary,
        messages = messages,
        streamingText = streamingText,
        draft = draft,
        turnState = turnState,
        loadingMessage = null,
        errorMessage = errorMessage,
        onDraftChange = {},
        onSend = {},
        onInterrupt = {},
        onReconnect = {},
        onBack = {},
    )
}
