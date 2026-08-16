package dev.hazydreams.hermesceleste.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
internal fun CelesteBackdrop(
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            content()
        }
    }
}

@Composable
internal fun CelesteWordmark(
    trailing: String,
    modifier: Modifier = Modifier,
    trailingColor: Color = CelesteMuted,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "HERMES CELESTE",
            color = CelesteInk,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.8.sp,
        )
        Text(
            text = trailing.uppercase(),
            color = trailingColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        )
    }
}

@Composable
internal fun CelesteSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = CelesteMuted,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier,
    )
}

@Composable
internal fun EditorialDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(CelesteHairline))
}

@Composable
internal fun StatusMessage(message: String, color: Color, showSpinner: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.7.dp,
                color = color,
            )
        } else {
            Box(Modifier.size(7.dp).background(color, CircleShape))
        }
        Text(
            text = message,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
