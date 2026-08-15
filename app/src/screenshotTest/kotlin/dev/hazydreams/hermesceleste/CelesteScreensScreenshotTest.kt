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
import dev.hazydreams.hermesceleste.presentation.AssistantNameKey
import dev.hazydreams.hermesceleste.ui.HermesCelesteTheme
import dev.hazydreams.hermesceleste.ui.conversation.ConversationScreen
import dev.hazydreams.hermesceleste.ui.gateway.ConnectionLoadingScreen
import dev.hazydreams.hermesceleste.ui.gateway.ConnectionUnavailableScreen
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsActions
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsScreen
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsUiState
import dev.hazydreams.hermesceleste.ui.gateway.SettingsScreen
import dev.hazydreams.hermesceleste.ui.sessions.SessionListScreen

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
@Preview(name = "14 · Assistant name editor", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun AssistantNameEditorPreviewScreenshot() {
    HermesCelesteTheme {
        SettingsScreen(
            dashboardUrl = "https://hermes.example.net/hermes",
            connectionPhase = ConnectionPhase.Connected,
            assistantDisplayName = "Juno",
            assistantNameScope = AssistantNameKey("https://hermes.example.net/hermes", "default"),
            assistantNameEditor = AssistantNameEditState(
                isOpen = true,
                draft = "Juno",
            ),
            onBack = {},
            onGateway = {},
            onOpenAssistantName = {},
            onAssistantNameDraftChange = {},
            onSaveAssistantName = {},
            onCancelAssistantName = {},
        )
    }
}

@PreviewTest
@Preview(name = "15 · Assistant name invalid", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun InvalidAssistantNamePreviewScreenshot() {
    HermesCelesteTheme {
        SettingsScreen(
            dashboardUrl = "https://hermes.example.net/hermes",
            connectionPhase = ConnectionPhase.Connected,
            assistantDisplayName = "Hermes",
            assistantNameScope = AssistantNameKey("https://hermes.example.net/hermes", "work"),
            assistantNameEditor = AssistantNameEditState(
                isOpen = true,
                draft = "Juno\n",
                errorMessage = "Use a single line without control characters.",
            ),
            onBack = {},
            onGateway = {},
            onOpenAssistantName = {},
            onAssistantNameDraftChange = {},
            onSaveAssistantName = {},
            onCancelAssistantName = {},
        )
    }
}

@PreviewTest
@Preview(name = "16 · Custom assistant transcript", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun CustomAssistantConversationPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            assistantDisplayName = "Juno",
            messages = listOf(
                ConversationMessage(
                    role = "assistant",
                    text = "The local presentation name updates without changing the Hermes session.",
                    id = "preview-assistant-name",
                ),
            ),
            turnState = TurnState.Idle,
        )
    }
}

@PreviewTest
@Preview(name = "17 · Saved assistant name", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun SavedAssistantNameSettingsPreviewScreenshot() {
    HermesCelesteTheme {
        SettingsScreen(
            dashboardUrl = "https://hermes.example.net/hermes",
            connectionPhase = ConnectionPhase.Connected,
            assistantDisplayName = "Juno",
            assistantNameScope = AssistantNameKey("https://hermes.example.net/hermes", "default"),
            onBack = {},
            onGateway = {},
        )
    }
}

@PreviewTest
@Preview(name = "18 · Assistant name write error", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun AssistantNameWriteErrorPreviewScreenshot() {
    HermesCelesteTheme {
        SettingsScreen(
            dashboardUrl = "https://hermes.example.net/hermes",
            connectionPhase = ConnectionPhase.Connected,
            assistantDisplayName = "Juno",
            assistantNameScope = AssistantNameKey("https://hermes.example.net/hermes", "default"),
            assistantNameEditor = AssistantNameEditState(
                isOpen = true,
                draft = "Nova",
                errorMessage = "Couldn’t save assistant name on this device. Try again.",
                isRetryableError = true,
            ),
            onBack = {},
            onGateway = {},
            onOpenAssistantName = {},
            onAssistantNameDraftChange = {},
            onSaveAssistantName = {},
            onCancelAssistantName = {},
        )
    }
}

@PreviewTest
@Preview(name = "19 · Custom assistant responding", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun CustomAssistantRunningPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            assistantDisplayName = "Juno",
            messages = emptyList(),
            turnState = TurnState.Running,
        )
    }
}

@PreviewTest
@Preview(name = "20 · Assistant name large text", widthDp = 320, heightDp = 844, fontScale = 2f, showBackground = true)
@Composable
fun AssistantNameLargeTextNarrowPreviewScreenshot() {
    HermesCelesteTheme {
        SettingsScreen(
            dashboardUrl = "https://hermes.example.net/hermes",
            connectionPhase = ConnectionPhase.Connected,
            assistantDisplayName = "Juno",
            assistantNameScope = AssistantNameKey("https://hermes.example.net/hermes", "work"),
            assistantNameEditor = AssistantNameEditState(
                isOpen = true,
                draft = "🌙".repeat(40),
            ),
            onBack = {},
            onGateway = {},
            onOpenAssistantName = {},
            onAssistantNameDraftChange = {},
            onSaveAssistantName = {},
            onCancelAssistantName = {},
        )
    }
}

@PreviewTest
@Preview(name = "21 · Hermes fallback", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun AssistantNameFallbackPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            assistantDisplayName = "",
            messages = listOf(
                ConversationMessage(
                    role = "assistant",
                    text = "A missing local alias stays safe and readable as Hermes.",
                    id = "preview-assistant-fallback",
                ),
            ),
            turnState = TurnState.Idle,
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
            onProfileSelected = {},
            onNewConversation = {},
            onSessionSelected = {},
            onSettings = {},
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
            messages = listOf(
                ConversationMessage(
                    role = "user",
                    text = "Build it with reconnection that cannot duplicate my prompt.",
                    id = "preview-user-2",
                ),
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
    assistantDisplayName: String = "Hermes",
    turnState: TurnState,
    errorMessage: String? = null,
) {
    ConversationScreen(
        summary = summary,
        messages = messages,
        streamingText = streamingText,
        draft = draft,
        assistantDisplayName = assistantDisplayName,
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
