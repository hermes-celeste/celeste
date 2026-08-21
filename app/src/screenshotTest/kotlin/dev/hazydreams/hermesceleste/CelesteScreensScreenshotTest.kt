package dev.hazydreams.hermesceleste

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.hazydreams.hermesceleste.connection.SavedAuthMode
import dev.hazydreams.hermesceleste.network.AuthProvider
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.HermesCelesteTheme
import dev.hazydreams.hermesceleste.ui.CelesteScreen
import dev.hazydreams.hermesceleste.ui.conversation.ConversationScreen
import dev.hazydreams.hermesceleste.ui.gateway.ConnectionLoadingScreen
import dev.hazydreams.hermesceleste.ui.gateway.ConnectionUnavailableScreen
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsActions
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsScreen
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsUiState
import dev.hazydreams.hermesceleste.ui.gateway.SettingsScreen
import dev.hazydreams.hermesceleste.ui.sessions.SessionNavigationDrawer

private val previewSessions = listOf(
    StoredSession(
        id = "celeste-slice",
        title = "Hermes Celeste: first native slice",
        preview = "Port the dashboard protocol, preserve the shared session store, and make the mobile experience feel intentional.",
        startedAt = 1_786_104_000.0,
        messageCount = 18,
        source = "desktop",
        model = "Hermes 4",
        pinned = true,
    ),
    StoredSession(
        id = "visual-layer",
        title = "Visual direction and interaction language",
        preview = "A neutral dark interface with clear conversational hierarchy and restrained state treatment.",
        startedAt = 1_786_017_600.0,
        messageCount = 12,
        source = "desktop",
        profile = "work",
        model = "Hermes 4",
    ),
    StoredSession(
        id = "dashboard-notes",
        title = "Dashboard connection notes",
        preview = "Verified ticketed WebSocket authentication and resumed an existing Hermes conversation.",
        startedAt = 1_785_931_200.0,
        messageCount = 9,
        source = "cron",
        model = "Hermes 4",
    ),
    StoredSession(
        id = "release-checklist",
        title = "Android release checklist",
        preview = "Secure credentials, emulator coverage, streaming, reconnection, and accessibility remain before release.",
        startedAt = 1_785_844_800.0,
        messageCount = 21,
        source = "cron",
        profile = "work",
        model = "GPT-5.6",
        pinned = true,
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

private val gatewaySetupState = GatewaySettingsUiState(
    dashboardUrl = "",
    probe = null,
    savedAuthMode = null,
    username = "",
    password = "",
    sessionToken = "",
    connectionPhase = ConnectionPhase.ManualSetup,
    loadingMessage = null,
    errorMessage = null,
)

private val passwordSignInState = GatewaySettingsUiState(
    dashboardUrl = "https://hermes.example.net",
    probe = DashboardProbeResult(
        baseUrl = "https://hermes.example.net",
        authRequired = true,
        providers = listOf(AuthProvider("password", "Password", supportsPassword = true)),
        version = "0.20.0",
    ),
    savedAuthMode = SavedAuthMode.ProviderSession,
    username = "celeste",
    password = "preview-only",
    sessionToken = "",
    connectionPhase = ConnectionPhase.AuthenticationRequired,
    loadingMessage = null,
    errorMessage = "Sign in required.",
)

private val sessionTokenState = GatewaySettingsUiState(
    dashboardUrl = "http://100.64.0.12:9119",
    probe = DashboardProbeResult(
        baseUrl = "http://100.64.0.12:9119",
        authRequired = false,
        providers = emptyList(),
        version = "0.20.0",
    ),
    savedAuthMode = null,
    username = "",
    password = "",
    sessionToken = "",
    connectionPhase = ConnectionPhase.ManualSetup,
    loadingMessage = null,
    errorMessage = null,
)

private val connectedGatewayState = GatewaySettingsUiState(
    dashboardUrl = "https://hermes.example.net",
    probe = DashboardProbeResult(
        baseUrl = "https://hermes.example.net",
        authRequired = true,
        providers = listOf(AuthProvider("password", "Password", supportsPassword = true)),
        version = "0.20.0",
    ),
    savedAuthMode = SavedAuthMode.ProviderSession,
    username = "celeste",
    password = "",
    sessionToken = "",
    connectionPhase = ConnectionPhase.Connected,
    loadingMessage = null,
    errorMessage = null,
)

private fun gatewayPreviewActions(onBack: (() -> Unit)?): GatewaySettingsActions =
    GatewaySettingsActions(
        onUsernameChange = {},
        onPasswordChange = {},
        onSessionTokenChange = {},
        onApplyAddress = {},
        onConnect = {},
        onRetry = {},
        onSignOut = {},
        onForgetConnection = {},
        onBack = onBack,
    )

@PreviewTest
@Preview(name = "01 · Gateway setup", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun GatewaySetupPreviewScreenshot() {
    HermesCelesteTheme {
        GatewaySettingsScreen(
            state = gatewaySetupState,
            actions = gatewayPreviewActions(onBack = null),
        )
    }
}

@PreviewTest
@Preview(name = "02 · Password sign-in", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun PasswordConnectPreviewScreenshot() {
    HermesCelesteTheme {
        GatewaySettingsScreen(
            state = passwordSignInState,
            actions = gatewayPreviewActions(onBack = {}),
        )
    }
}

@PreviewTest
@Preview(name = "13 · Session token", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun SessionTokenGatewayPreviewScreenshot() {
    HermesCelesteTheme {
        GatewaySettingsScreen(
            state = sessionTokenState,
            actions = gatewayPreviewActions(onBack = null),
        )
    }
}

@PreviewTest
@Preview(name = "09 · Settings", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun SettingsPreviewScreenshot() {
    HermesCelesteTheme {
        SettingsScreen(
            dashboardUrl = "https://hermes.example.net",
            connectionPhase = ConnectionPhase.Connected,
            onBack = {},
            onGateway = {},
        )
    }
}

@PreviewTest
@Preview(name = "10 · Connected gateway", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun ConnectedGatewayPreviewScreenshot() {
    HermesCelesteTheme {
        GatewaySettingsScreen(
            state = connectedGatewayState,
            actions = gatewayPreviewActions(onBack = {}),
        )
    }
}

@PreviewTest
@Preview(name = "11 · Restoring connection", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun RestoringConnectionPreviewScreenshot() {
    HermesCelesteTheme {
        ConnectionLoadingScreen()
    }
}

@PreviewTest
@Preview(name = "12 · Gateway unavailable", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun RestoreFailedPreviewScreenshot() {
    HermesCelesteTheme {
        ConnectionUnavailableScreen(
            errorMessage = "Could not reach Hermes.",
            onRetry = {},
            onSettings = {},
        )
    }
}

@PreviewTest
@Preview(name = "18 · Navigation drawer", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun NavigationDrawerPreviewScreenshot() {
    HermesCelesteTheme {
        CelesteScreen {
            SessionNavigationDrawer(
                sessions = previewSessions,
                profiles = listOf(
                    DashboardProfile(name = "default", isDefault = true),
                    DashboardProfile(name = "work", model = "Hermes 4"),
                ),
                selectedProfile = "work",
                selectedSessionId = "visual-layer",
                loadingMessage = null,
                errorMessage = null,
                onProfileSelected = {},
                onNewConversation = {},
                onSessionSelected = {},
                onSettings = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "04 · New conversation", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun NewConversationPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            summary = null,
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
            messages = listOf(
                ConversationMessage(
                    role = "user",
                    text = "Build it with reconnection that cannot duplicate my prompt.",
                    id = "preview-user-2",
                ),
            ),
            streamingText = "The socket now stays **deliberately thin**. A lifecycle-aware session controller owns retries, resumes authoritative history, and then `reconciles",
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
            messages = listOf(
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
@Preview(name = "15 · Rich transcript", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun RichTranscriptPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            messages = richPreviewMessages,
            turnState = TurnState.Idle,
        )
    }
}

@PreviewTest
@Preview(name = "16 · Rich transcript narrow", widthDp = 320, heightDp = 844, showBackground = true)
@Composable
fun RichTranscriptNarrowPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            messages = richPreviewMessages,
            turnState = TurnState.Idle,
        )
    }
}

@PreviewTest
@Preview(name = "17 · Jump to latest", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun JumpToLatestPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            messages = previewMessages + richPreviewMessages,
            turnState = TurnState.Idle,
            initiallyFollowLatest = false,
            jumpToLatestVisible = true,
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

private val richPreviewMessages = listOf(
    ConversationMessage(
        role = "user",
        text = "Show me **the result** with the important details.",
        id = "preview-rich-user",
    ),
    ConversationMessage(
        role = "assistant",
        text = """
            ## Release check

            > Ready after review, with the raw source preserved.

            - **Markdown** renders natively
            - Links stay [safe](https://example.com)
            - [x] Streaming remains readable

            ```kotlin
            val stableProjection = reconcile(snapshot, pendingEvents)
            ```

            | State | Result | Owner | Notes |
            | --- | --- | --- | --- |
            | Reconnect | Tested | Client | Stable |
        """.trimIndent(),
        id = "preview-rich-assistant",
    ),
)

@Composable
private fun PreviewConversation(
    summary: StoredSession? = previewSessions[1],
    messages: List<ConversationMessage> = previewMessages,
    streamingText: String = "",
    draft: String = "",
    turnState: TurnState,
    errorMessage: String? = null,
    initiallyFollowLatest: Boolean = true,
    jumpToLatestVisible: Boolean? = null,
) {
    ConversationScreen(
        conversationKey = summary?.id ?: "local-draft",
        title = summary?.title ?: "New conversation",
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
        onOpenDrawer = {},
        initiallyFollowLatest = initiallyFollowLatest,
        jumpToLatestVisibleOverride = jumpToLatestVisible,
    )
}
