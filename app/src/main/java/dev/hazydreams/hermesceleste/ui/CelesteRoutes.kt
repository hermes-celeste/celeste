package dev.hazydreams.hermesceleste.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.hazydreams.hermesceleste.CelesteUiState
import dev.hazydreams.hermesceleste.CelesteController
import dev.hazydreams.hermesceleste.ConnectionPhase
import dev.hazydreams.hermesceleste.ui.conversation.ConversationScreen
import dev.hazydreams.hermesceleste.ui.gateway.ConnectionLoadingScreen
import dev.hazydreams.hermesceleste.ui.gateway.ConnectionUnavailableScreen
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsActions
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsScreen
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsUiState
import dev.hazydreams.hermesceleste.ui.gateway.SettingsScreen
import dev.hazydreams.hermesceleste.ui.sessions.SessionNavigationDrawer
import kotlinx.coroutines.launch

@Composable
internal fun CelesteRoutes(ui: CelesteUiState, controller: CelesteController) {
    val activeSummary = ui.activeSummary
    val sessions = ui.sessions
    var destination by rememberSaveable { mutableStateOf(CelesteDestination.Content) }

    when (destination) {
        CelesteDestination.Settings -> {
            BackHandler { destination = CelesteDestination.Content }
            SettingsScreen(
                dashboardUrl = ui.dashboardUrl,
                connectionPhase = ui.connectionPhase,
                onBack = { destination = CelesteDestination.Content },
                onGateway = { destination = CelesteDestination.Gateway },
            )
        }

        CelesteDestination.Gateway -> {
            val canNavigateBack = sessions != null
            BackHandler(enabled = canNavigateBack) { destination = CelesteDestination.Settings }
            GatewaySettingsRoute(
                ui = ui,
                controller = controller,
                onBack = if (canNavigateBack) {
                    { destination = CelesteDestination.Settings }
                } else {
                    null
                },
            )
        }

        CelesteDestination.Content -> when {
            sessions != null -> {
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val drawerScope = rememberCoroutineScope()

                BackHandler(enabled = drawerState.isOpen) {
                    drawerScope.launch { drawerState.close() }
                }
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        SessionNavigationDrawer(
                            sessions = sessions.orEmpty(),
                            profiles = ui.profiles,
                            selectedProfile = ui.selectedProfile,
                            selectedSessionId = activeSummary?.id,
                            loadingMessage = ui.loadingMessage,
                            errorMessage = ui.errorMessage,
                            onProfileSelected = controller::selectProfile,
                            onNewConversation = {
                                drawerScope.launch {
                                    drawerState.close()
                                    controller.createNewConversation()
                                }
                            },
                            onSessionSelected = { session ->
                                drawerScope.launch {
                                    drawerState.close()
                                    controller.openSession(session)
                                }
                            },
                            onSettings = {
                                drawerScope.launch {
                                    drawerState.close()
                                    destination = CelesteDestination.Settings
                                }
                            },
                        )
                    },
                ) {
                    ConversationScreen(
                        conversationKey = activeSummary?.id ?: LOCAL_DRAFT_KEY,
                        title = activeSummary?.title ?: "New conversation",
                        messages = ui.messages,
                        streamingText = ui.streamingText,
                        draft = ui.draft,
                        turnState = ui.turnState,
                        loadingMessage = ui.loadingMessage,
                        errorMessage = ui.errorMessage,
                        onDraftChange = controller::updateDraft,
                        onSend = controller::sendMessage,
                        onInterrupt = controller::interrupt,
                        onReconnect = controller::reconnectNow,
                        onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                    )
                }
            }

            ui.connectionPhase == ConnectionPhase.CheckingSavedConnection ||
                ui.connectionPhase == ConnectionPhase.Restoring -> ConnectionLoadingScreen()

            ui.connectionPhase == ConnectionPhase.RestoreFailed -> ConnectionUnavailableScreen(
                errorMessage = ui.errorMessage,
                onRetry = controller::retrySavedConnection,
                onSettings = { destination = CelesteDestination.Gateway },
            )

            else -> GatewaySettingsRoute(ui = ui, controller = controller, onBack = null)
        }
    }
}

private enum class CelesteDestination {
    Content,
    Settings,
    Gateway,
}

private const val LOCAL_DRAFT_KEY = "local-draft"

@Composable
private fun GatewaySettingsRoute(
    ui: CelesteUiState,
    controller: CelesteController,
    onBack: (() -> Unit)?,
) {
    GatewaySettingsScreen(
        state = GatewaySettingsUiState(
            dashboardUrl = ui.dashboardUrl,
            probe = ui.probe,
            savedAuthMode = ui.savedAuthMode,
            username = ui.username,
            password = ui.password,
            sessionToken = ui.sessionToken,
            connectionPhase = ui.connectionPhase,
            loadingMessage = ui.loadingMessage,
            errorMessage = ui.errorMessage,
        ),
        actions = GatewaySettingsActions(
            onUsernameChange = controller::updateUsername,
            onPasswordChange = controller::updatePassword,
            onSessionTokenChange = controller::updateSessionToken,
            onApplyAddress = { address ->
                controller.updateDashboardUrl(address)
                controller.findDashboard()
            },
            onConnect = controller::loadSessions,
            onRetry = controller::retrySavedConnection,
            onSignOut = controller::signOut,
            onForgetConnection = controller::forgetConnection,
            onBack = onBack,
        ),
    )
}
