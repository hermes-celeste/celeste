package dev.hazydreams.hermesceleste.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
            BackHandler {
                if (ui.assistantNameEditor.isOpen) {
                    viewModel.cancelAssistantNameEdit()
                } else {
                    destination = CelesteDestination.Content
                }
            }
            SettingsScreen(
                dashboardUrl = ui.dashboardUrl,
                connectionPhase = ui.connectionPhase,
                assistantDisplayName = ui.assistantDisplayName,
                assistantNameScope = ui.assistantNameKey,
                assistantNameEditor = ui.assistantNameEditor,
                onBack = { destination = CelesteDestination.Content },
                onGateway = { destination = CelesteDestination.Gateway },
                onOpenAssistantName = viewModel::openAssistantNameEditor,
                onAssistantNameDraftChange = viewModel::updateAssistantNameDraft,
                onSaveAssistantName = viewModel::saveAssistantName,
                onCancelAssistantName = viewModel::cancelAssistantNameEdit,
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
                    assistantDisplayName = ui.assistantDisplayName,
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
    showOrnament: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        CelesteGold.copy(alpha = 0.08f),
                        CelesteGold.copy(alpha = 0.025f),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = size.height * 0.38f,
                ),
            )

            if (showOrnament) {
                val haloCenter = Offset(size.width - 8.dp.toPx(), 46.dp.toPx())
                val innerHaloRadius = 64.dp.toPx()

                listOf(
                    Triple(innerHaloRadius, 0.48f, 0.7.dp.toPx()),
                    Triple(87.dp.toPx(), 0.30f, 0.45.dp.toPx()),
                    Triple(111.dp.toPx(), 0.18f, 0.45.dp.toPx()),
                ).forEach { (radius, alpha, strokeWidth) ->
                    drawCircle(
                        color = CelesteGold.copy(alpha = alpha),
                        radius = radius,
                        center = haloCenter,
                        style = Stroke(width = strokeWidth),
                    )
                }

                drawArc(
                    color = CelesteGold.copy(alpha = 0.58f),
                    startAngle = 116f,
                    sweepAngle = 54f,
                    useCenter = false,
                    topLeft = Offset(
                        haloCenter.x - innerHaloRadius,
                        haloCenter.y - innerHaloRadius,
                    ),
                    size = Size(innerHaloRadius * 2f, innerHaloRadius * 2f),
                    style = Stroke(width = 0.85.dp.toPx(), cap = StrokeCap.Round),
                )

                val star = Offset(size.width - 101.dp.toPx(), 118.dp.toPx())
                drawLine(
                    color = CelesteGold.copy(alpha = 0.54f),
                    start = star - Offset(0f, 6.5.dp.toPx()),
                    end = star + Offset(0f, 6.5.dp.toPx()),
                    strokeWidth = 0.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = CelesteGold.copy(alpha = 0.54f),
                    start = star - Offset(4.5.dp.toPx(), 0f),
                    end = star + Offset(4.5.dp.toPx(), 0f),
                    strokeWidth = 0.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
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
