package dev.hazydreams.hermesceleste.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.hazydreams.hermesceleste.CelesteUiState
import dev.hazydreams.hermesceleste.CelesteViewModel
import dev.hazydreams.hermesceleste.ConnectionPhase
import dev.hazydreams.hermesceleste.ui.conversation.ConversationScreen
import dev.hazydreams.hermesceleste.ui.gateway.ConnectionLoadingScreen
import dev.hazydreams.hermesceleste.ui.gateway.ConnectionUnavailableScreen
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsActions
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsScreen
import dev.hazydreams.hermesceleste.ui.gateway.GatewaySettingsUiState
import dev.hazydreams.hermesceleste.ui.gateway.SettingsScreen
import dev.hazydreams.hermesceleste.ui.sessions.SessionListScreen

@Composable
internal fun CelesteRoutes(ui: CelesteUiState, viewModel: CelesteViewModel) {
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
                viewModel = viewModel,
                onBack = if (canNavigateBack) {
                    { destination = CelesteDestination.Settings }
                } else {
                    null
                },
            )
        }

        CelesteDestination.Content -> when {
            activeSummary != null -> {
                BackHandler(onBack = viewModel::leaveConversation)
                ConversationScreen(
                    summary = activeSummary,
                    messages = ui.messages,
                    streamingText = ui.streamingText,
                    draft = ui.draft,
                    turnState = ui.turnState,
                    loadingMessage = ui.loadingMessage,
                    errorMessage = ui.errorMessage,
                    onDraftChange = viewModel::updateDraft,
                    onSend = viewModel::sendMessage,
                    onInterrupt = viewModel::interrupt,
                    onReconnect = viewModel::reconnectNow,
                    onBack = viewModel::leaveConversation,
                )
            }

            sessions != null -> SessionListScreen(
                sessions = sessions,
                profiles = ui.profiles,
                selectedProfile = ui.selectedProfile,
                loadingMessage = ui.loadingMessage,
                errorMessage = ui.errorMessage,
                onProfileSelected = viewModel::selectProfile,
                onNewConversation = viewModel::createNewConversation,
                onSessionSelected = viewModel::openSession,
                onSettings = { destination = CelesteDestination.Settings },
            )

            ui.connectionPhase == ConnectionPhase.CheckingSavedConnection ||
                ui.connectionPhase == ConnectionPhase.Restoring -> ConnectionLoadingScreen()

            ui.connectionPhase == ConnectionPhase.RestoreFailed -> ConnectionUnavailableScreen(
                errorMessage = ui.errorMessage,
                onRetry = viewModel::retrySavedConnection,
                onSettings = { destination = CelesteDestination.Gateway },
            )

            else -> GatewaySettingsRoute(ui = ui, viewModel = viewModel, onBack = null)
        }
    }
}

private enum class CelesteDestination {
    Content,
    Settings,
    Gateway,
}

@Composable
private fun GatewaySettingsRoute(
    ui: CelesteUiState,
    viewModel: CelesteViewModel,
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
            onUsernameChange = viewModel::updateUsername,
            onPasswordChange = viewModel::updatePassword,
            onSessionTokenChange = viewModel::updateSessionToken,
            onApplyAddress = { address ->
                viewModel.updateDashboardUrl(address)
                viewModel.findDashboard()
            },
            onConnect = viewModel::loadSessions,
            onRetry = viewModel::retrySavedConnection,
            onSignOut = viewModel::signOut,
            onForgetConnection = viewModel::forgetConnection,
            onBack = onBack,
        ),
    )
}
