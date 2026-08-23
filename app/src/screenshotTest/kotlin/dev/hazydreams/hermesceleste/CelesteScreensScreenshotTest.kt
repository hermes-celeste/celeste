package dev.hazydreams.hermesceleste

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import dev.hazydreams.hermesceleste.connection.SavedAuthMode
import dev.hazydreams.hermesceleste.network.AuthProvider
import dev.hazydreams.hermesceleste.network.BackgroundProcessResult
import dev.hazydreams.hermesceleste.network.BackgroundProcessState
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.ConversationStep
import dev.hazydreams.hermesceleste.network.ConversationStepKind
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.FileEditOperation
import dev.hazydreams.hermesceleste.network.FileEditState
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.network.TaskItemStatus
import dev.hazydreams.hermesceleste.network.TaskProgress
import dev.hazydreams.hermesceleste.network.TaskProgressItem
import dev.hazydreams.hermesceleste.ui.HermesCelesteTheme
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelesteScreen
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceRaised
import dev.hazydreams.hermesceleste.ui.conversation.ConversationScreen
import dev.hazydreams.hermesceleste.ui.conversation.ChangesSheetSurface
import dev.hazydreams.hermesceleste.ui.conversation.ProcessResultSheetSurface
import dev.hazydreams.hermesceleste.ui.conversation.StepsSheetSurface
import dev.hazydreams.hermesceleste.ui.conversation.TaskProgressSheetSurface
import dev.hazydreams.hermesceleste.ui.gateway.ConnectionLoadingScreen
import dev.hazydreams.hermesceleste.ui.gateway.ConnectionUnavailableScreen
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsActions
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsScreen
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsUiState
import dev.hazydreams.hermesceleste.ui.gateway.SettingsScreen
import dev.hazydreams.hermesceleste.ui.sessions.SessionNavigationDrawer
import dev.hazydreams.hermesceleste.ui.sessions.RenameConversationDialog
import dev.hazydreams.hermesceleste.ui.sessions.SessionRowActionItems

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
        unread = true,
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

private val previewStepsMessage = ConversationMessage(
    role = "steps",
    text = "",
    id = "preview-steps-1",
    steps = listOf(
        ConversationStep(
            id = "preview-reasoning-1",
            kind = ConversationStepKind.Reasoning,
            detail = "I’ll compare the current gateway contract before changing the mobile projection.",
        ),
        ConversationStep(
            id = "preview-tool-1",
            kind = ConversationStepKind.Tool,
            toolName = "read_file",
            context = "GatewaySessionApi.kt",
            summary = "Read the current resume projection.",
        ),
        ConversationStep(
            id = "preview-reasoning-2",
            kind = ConversationStepKind.Reasoning,
            detail = "Reasoning and tool activity belong in one chronological mobile view.",
        ),
    ),
)

private val previewPendingStepsMessage = ConversationMessage(
    role = "steps",
    text = "",
    id = "preview-steps-active",
    pending = true,
    steps = listOf(
        ConversationStep(
            id = "preview-reasoning-active",
            kind = ConversationStepKind.Reasoning,
            detail = "Checking the gateway projection.",
            pending = true,
        ),
    ),
)

private val previewProcessResult = BackgroundProcessResult(
    processId = "proc_42",
    state = BackgroundProcessState.Completed,
    status = "Completed normally",
    command = "./gradlew :app:testDebugUnitTest",
    output = """> Task :app:compileDebugKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 1m 18s
27 actionable tasks: 21 executed, 6 up-to-date""",
    exitCode = 0,
)

private val previewProcessMessage = ConversationMessage(
    role = "process",
    text = "",
    id = "process:proc_42",
    processResult = previewProcessResult,
)

private val previewChangesMessage = ConversationMessage(
    role = "changes",
    text = "",
    id = "preview-changes",
    fileEdits = listOf(
        FileEditOperation(
            toolId = "edit-conversation",
            paths = listOf("ui/conversation/ConversationScreen.kt"),
            diff = """a/ui/conversation/ConversationScreen.kt → b/ui/conversation/ConversationScreen.kt
@@ -88,6 +88,7 @@
 internal fun ConversationScreen(
+    taskProgress: TaskProgress?,
     streamingText: String,""",
            summary = "Updated the conversation surface",
            state = FileEditState.Completed,
        ),
        FileEditOperation(
            toolId = "edit-work-surfaces",
            paths = listOf("ui/conversation/ConversationWorkSurfaces.kt"),
            diff = """a/ui/conversation/ConversationWorkSurfaces.kt → b/ui/conversation/ConversationWorkSurfaces.kt
@@ -1,2 +1,4 @@
+internal fun TaskProgressPill() {
+    // compact session progress
+}""",
            summary = "Added work surfaces",
            state = FileEditState.Completed,
        ),
        FileEditOperation(
            toolId = "edit-tests",
            paths = listOf("ConversationEventReducerTest.kt"),
            summary = "Added focused projection coverage",
            state = FileEditState.Completed,
        ),
    ),
)

private val previewTaskProgress = TaskProgress(
    items = listOf(
        TaskProgressItem("design", "Settle the compact pill composition", TaskItemStatus.Completed),
        TaskProgressItem("projection", "Project live and restored work state", TaskItemStatus.Completed),
        TaskProgressItem("compose", "Build the mobile work surfaces", TaskItemStatus.InProgress),
        TaskProgressItem("verify", "Review focused screenshots and tests", TaskItemStatus.Pending),
    ),
)

private val previewCompletedTaskProgress = TaskProgress(
    items = previewTaskProgress.items.map { it.copy(status = TaskItemStatus.Completed) },
)

private val previewMessages = listOf(
    ConversationMessage(
        role = "user",
        text = "I want the mobile client to continue the exact conversation I started on Desktop.",
        id = "preview-user-1",
    ),
    previewStepsMessage,
    ConversationMessage(
        role = "assistant",
        text = "It now resumes the shared server-side session instead of maintaining a separate mobile copy.",
        id = "preview-assistant-1",
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
                hasMoreSessions = true,
                isLoadingMoreSessions = true,
                sessionPageError = null,
                searchQuery = "",
                searchResults = emptyList(),
                isSearchingSessions = false,
                sessionSearchError = null,
                sessionActionError = null,
                onProfileSelected = {},
                onSearchQueryChange = {},
                onNewConversation = {},
                onSessionSelected = {},
                onSessionPinnedChange = { _, _ -> },
                onSessionRename = { _, _, onComplete -> onComplete(null) },
                onLoadMoreSessions = {},
                onSettings = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "19 · Conversation actions menu", widthDp = 390, heightDp = 300, showBackground = true)
@Composable
fun ConversationActionsMenuPreviewScreenshot() {
    HermesCelesteTheme {
        CelesteScreen {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                CelestePanel(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Text("Dashboard connection notes", style = MaterialTheme.typography.bodyMedium)
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 58.dp)
                        .width(164.dp),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 6.dp,
                ) {
                    androidx.compose.foundation.layout.Column {
                        SessionRowActionItems(
                            pinned = false,
                            onPinnedChange = {},
                            onRename = {},
                        )
                    }
                }
            }
        }
    }
}

@PreviewTest
@Preview(name = "20 · Rename conversation", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun RenameConversationPreviewScreenshot() {
    HermesCelesteTheme {
        CelesteScreen {
            RenameConversationDialog(
                sessionId = "dashboard-notes",
                currentTitle = "Dashboard connection notes",
                onDismiss = {},
                onRename = { _, _ -> },
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "21 · Rename conversation · narrow large text",
    widthDp = 320,
    heightDp = 700,
    fontScale = 1.3f,
    showBackground = true,
)
@Composable
fun RenameConversationNarrowPreviewScreenshot() {
    RenameConversationPreviewScreenshot()
}

@PreviewTest
@Preview(name = "22 · Pin failure", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun SessionPinFailurePreviewScreenshot() {
    HermesCelesteTheme {
        CelesteScreen {
            SessionNavigationDrawer(
                sessions = previewSessions,
                profiles = listOf(DashboardProfile(name = "default", isDefault = true)),
                selectedProfile = "default",
                selectedSessionId = "visual-layer",
                loadingMessage = null,
                errorMessage = null,
                hasMoreSessions = false,
                isLoadingMoreSessions = false,
                sessionPageError = null,
                searchQuery = "",
                searchResults = emptyList(),
                isSearchingSessions = false,
                sessionSearchError = null,
                sessionActionError = "Could not update that pin.",
                onProfileSelected = {},
                onSearchQueryChange = {},
                onNewConversation = {},
                onSessionSelected = {},
                onSessionPinnedChange = { _, _ -> },
                onSessionRename = { _, _, onComplete -> onComplete(null) },
                onLoadMoreSessions = {},
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
@Preview(name = "23 · Conversation steps sheet", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun ConversationStepsSheetPreviewScreenshot() {
    HermesCelesteTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            PreviewConversation(
                messages = previewMessages,
                turnState = TurnState.Idle,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f)),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = CelesteSurfaceRaised,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            ) {
                StepsSheetSurface(previewStepsMessage)
            }
        }
    }
}

@PreviewTest
@Preview(name = "24 · Conversation steps entry", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun ConversationStepsEntryPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            messages = previewMessages,
            turnState = TurnState.Idle,
        )
    }
}

@PreviewTest
@Preview(name = "25 · Background process entry", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun BackgroundProcessEntryPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            messages = listOf(
                ConversationMessage(
                    role = "user",
                    text = "Run the focused Android checks.",
                    id = "preview-process-user",
                ),
                previewProcessMessage,
                ConversationMessage(
                    role = "assistant",
                    text = "The focused checks passed.",
                    id = "preview-process-assistant",
                ),
            ),
            turnState = TurnState.Idle,
        )
    }
}

@PreviewTest
@Preview(name = "26 · Background process sheet", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun BackgroundProcessSheetPreviewScreenshot() {
    HermesCelesteTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            PreviewConversation(
                messages = listOf(previewProcessMessage),
                turnState = TurnState.Idle,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f)),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = CelesteSurfaceRaised,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            ) {
                ProcessResultSheetSurface(previewProcessResult)
            }
        }
    }
}

@PreviewTest
@Preview(name = "27 · Active thinking entry", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun ActiveThinkingEntryPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            messages = listOf(
                ConversationMessage(
                    role = "user",
                    text = "Inspect the process completion path.",
                    id = "preview-thinking-user",
                ),
                previewPendingStepsMessage,
            ),
            turnState = TurnState.Running,
        )
    }
}

@PreviewTest
@Preview(name = "28 · Work surface pills", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun WorkSurfacePillsPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            messages = listOf(
                ConversationMessage(
                    role = "user",
                    text = "Give code changes and task progress some personality.",
                    id = "preview-work-user",
                ),
                previewStepsMessage,
                ConversationMessage(
                    role = "assistant",
                    text = "I kept reasoning calm while making durable work easy to inspect.",
                    id = "preview-work-assistant",
                ),
                previewChangesMessage,
            ),
            taskProgress = previewTaskProgress,
            turnState = TurnState.Running,
        )
    }
}

@PreviewTest
@Preview(name = "29 · Changes sheet", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun ChangesSheetPreviewScreenshot() {
    PreviewWorkInspectionSheet {
        ChangesSheetSurface(previewChangesMessage)
    }
}

@PreviewTest
@Preview(name = "30 · Active tasks sheet", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun ActiveTasksSheetPreviewScreenshot() {
    PreviewWorkInspectionSheet {
        TaskProgressSheetSurface(previewTaskProgress)
    }
}

@PreviewTest
@Preview(name = "31 · Completed tasks sheet", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun CompletedTasksSheetPreviewScreenshot() {
    PreviewWorkInspectionSheet {
        TaskProgressSheetSurface(previewCompletedTaskProgress)
    }
}

@PreviewTest
@Preview(
    name = "32 · Work surfaces · narrow large text",
    widthDp = 320,
    heightDp = 700,
    fontScale = 1.3f,
    showBackground = true,
)
@Composable
fun WorkSurfacesNarrowLargeTextPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            messages = listOf(
                ConversationMessage(
                    role = "assistant",
                    text = "The same compact work surfaces remain readable at a narrow width.",
                    id = "preview-work-narrow-assistant",
                ),
                previewChangesMessage,
            ),
            taskProgress = previewTaskProgress,
            turnState = TurnState.Running,
        )
    }
}

@Composable
private fun PreviewWorkInspectionSheet(content: @Composable () -> Unit) {
    HermesCelesteTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            PreviewConversation(
                messages = listOf(previewChangesMessage),
                taskProgress = previewTaskProgress,
                turnState = TurnState.Running,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f)),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = CelesteSurfaceRaised,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            ) {
                content()
            }
        }
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
        )
    }
}

@PreviewTest
@Preview(name = "19 · Summarizing conversation", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun CompactionStatusPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            messages = listOf(
                ConversationMessage(
                    role = "user",
                    text = "Keep going after the context is summarized.",
                    id = "preview-compaction-user",
                ),
                ConversationMessage(
                    role = "steps",
                    text = "",
                    id = "preview-compaction-steps",
                    pending = false,
                    steps = listOf(
                        ConversationStep(
                            id = "preview-compaction-reasoning",
                            kind = ConversationStepKind.Reasoning,
                            detail = "Reviewing the conversation so far.",
                            pending = false,
                        ),
                    ),
                ),
            ),
            turnState = TurnState.Running,
            isCompacting = true,
        )
    }
}

@PreviewTest
@Preview(name = "18 · Resume exhausted", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun ResumeExhaustedPreviewScreenshot() {
    HermesCelesteTheme {
        PreviewConversation(
            draft = "This draft remains ready while I restore the conversation.",
            turnState = TurnState.Reconnecting,
            resumeExhausted = true,
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
    taskProgress: TaskProgress? = null,
    streamingText: String = "",
    draft: String = "",
    turnState: TurnState,
    isCompacting: Boolean = false,
    resumeExhausted: Boolean = false,
    errorMessage: String? = null,
    initiallyFollowLatest: Boolean = true,
    jumpToLatestVisible: Boolean? = null,
) {
    ConversationScreen(
        conversationKey = summary?.id ?: "local-draft",
        title = summary?.title ?: "New conversation",
        messages = messages,
        taskProgress = taskProgress,
        streamingText = streamingText,
        draft = draft,
        turnState = turnState,
        isCompacting = isCompacting,
        resumeExhausted = resumeExhausted,
        loadingMessage = null,
        errorMessage = errorMessage,
        onDraftChange = {},
        onSend = {},
        onInterrupt = {},
        onRetryResume = {},
        onOpenDrawer = {},
        initiallyFollowLatest = initiallyFollowLatest,
        jumpToLatestVisibleOverride = jumpToLatestVisible,
    )
}
