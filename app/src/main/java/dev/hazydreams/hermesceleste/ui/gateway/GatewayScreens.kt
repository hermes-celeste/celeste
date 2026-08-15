package dev.hazydreams.hermesceleste.ui.gateway

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.ConnectionPhase
import dev.hazydreams.hermesceleste.UiNotice
import dev.hazydreams.hermesceleste.connection.SavedAuthMode
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.ui.CelesteBackdrop
import dev.hazydreams.hermesceleste.ui.CelesteBlue
import dev.hazydreams.hermesceleste.ui.CelesteCoral
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteGold
import dev.hazydreams.hermesceleste.ui.CelesteGoldText
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteInk
import dev.hazydreams.hermesceleste.ui.CelesteMuted
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelestePanelRaised
import dev.hazydreams.hermesceleste.ui.CelestePaper
import dev.hazydreams.hermesceleste.ui.CelestePaperRaised
import dev.hazydreams.hermesceleste.ui.CelesteSectionLabel
import dev.hazydreams.hermesceleste.ui.CelesteWordmark
import dev.hazydreams.hermesceleste.ui.EditorialDivider
import dev.hazydreams.hermesceleste.ui.StatusMessage
import dev.hazydreams.hermesceleste.ui.UiNoticeMessage

internal data class GatewaySettingsUiState(
    val dashboardUrl: String,
    val probe: DashboardProbeResult?,
    val savedAuthMode: SavedAuthMode?,
    val username: String,
    val password: String,
    val sessionToken: String,
    val connectionPhase: ConnectionPhase,
    val loadingMessage: String?,
    val notice: UiNotice?,
)

internal data class GatewaySettingsActions(
    val onUsernameChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onSessionTokenChange: (String) -> Unit,
    val onApplyAddress: (String) -> Unit,
    val onConnect: () -> Unit,
    val onRetry: () -> Unit,
    val onSignOut: () -> Unit,
    val onForgetConnection: () -> Unit,
    val onBack: (() -> Unit)?,
    val onSignIn: () -> Unit = {},
)

@Composable
internal fun ConnectionLoadingScreen() {
    CelesteBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 38.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CelesteWordmark(trailing = "PRIVATE", trailingColor = CelesteGoldText)
            Spacer(Modifier.weight(1f))
            CelesteHalo()
            Spacer(Modifier.height(26.dp))
            Text(
                text = "Finding your Hermes",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            StatusMessage("Restoring your private connection", CelesteBlue, showSpinner = true)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
internal fun ConnectionUnavailableScreen(
    notice: UiNotice?,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onSignIn: () -> Unit = {},
) {
    CelesteBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 38.dp),
        ) {
            CelesteWordmark(trailing = "OFFLINE", trailingColor = CelesteError)
            Spacer(Modifier.weight(0.72f))
            Text(
                text = "Hermes is\nout of reach.",
                style = MaterialTheme.typography.displayMedium,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                text = "Check the saved address or try again.",
                color = CelesteMuted,
                style = MaterialTheme.typography.bodyLarge,
            )
            notice?.let {
                Spacer(Modifier.height(26.dp))
                UiNoticeMessage(
                    notice = it,
                    onRetry = onRetry,
                    onSignIn = onSignIn,
                )
            }
            Spacer(Modifier.height(34.dp))
            CelestePrimaryButton(
                text = "Try again",
                loading = false,
                enabled = true,
                onClick = onRetry,
            )
            TextButton(
                onClick = onSettings,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Review Gateway settings", color = CelesteBlue)
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
internal fun SettingsScreen(
    dashboardUrl: String,
    connectionPhase: ConnectionPhase,
    onBack: () -> Unit,
    onGateway: () -> Unit,
) {
    CelesteBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 38.dp),
        ) {
            EditorialHeader(title = "Settings", onBack = onBack)
            Spacer(Modifier.height(44.dp))
            CelesteSectionLabel("Connection")
            Spacer(Modifier.height(12.dp))
            EditorialDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onGateway)
                    .padding(vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Gateway", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        dashboardUrl.ifBlank { "Not configured" },
                        color = CelesteMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusDot(connectionPhase)
                Spacer(Modifier.size(8.dp))
                Text(
                    connectionStatusLabel(connectionPhase),
                    color = CelesteMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(10.dp))
                Text("→", color = CelesteInk, fontSize = 18.sp)
            }
            EditorialDivider()
        }
    }
}

@Composable
internal fun GatewaySettingsScreen(
    state: GatewaySettingsUiState,
    actions: GatewaySettingsActions,
) {
    val dashboardUrl = state.dashboardUrl
    val probe = state.probe
    val savedAuthMode = state.savedAuthMode
    val username = state.username
    val password = state.password
    val sessionToken = state.sessionToken
    val connectionPhase = state.connectionPhase
    val loadingMessage = state.loadingMessage
    val notice = state.notice
    val onBack = actions.onBack
    var address by rememberSaveable(dashboardUrl) { mutableStateOf(dashboardUrl) }
    var confirmForgetConnection by remember { mutableStateOf(false) }
    val isConnected = connectionPhase == ConnectionPhase.Connected
    val addressChanged = address.trim().trimEnd('/') != dashboardUrl.trim().trimEnd('/')
    val effectiveProbe = probe?.takeIf { !addressChanged }
    val initialSetup = onBack == null && dashboardUrl.isBlank()

    CelesteBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 38.dp),
        ) {
            if (initialSetup) {
                CelesteWordmark(trailing = "PRIVATE", trailingColor = CelesteGoldText)
                Spacer(Modifier.height(108.dp))
                Text(
                    text = "Your Hermes,",
                    style = MaterialTheme.typography.displayMedium,
                )
                Text(
                    text = "carried forward.",
                    color = CelesteInk,
                    fontFamily = FontFamily.Serif,
                    fontSize = 41.sp,
                    lineHeight = 44.sp,
                    letterSpacing = (-1.2).sp,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Continue where you left off.",
                    color = CelesteMuted,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(104.dp))
            } else {
                EditorialHeader(title = "Gateway", onBack = onBack)
                Spacer(Modifier.height(42.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CelesteSectionLabel("Dashboard address", Modifier.weight(1f))
                if (!initialSetup) {
                    StatusDot(connectionPhase)
                    Spacer(Modifier.size(7.dp))
                    Text(
                        connectionStatusLabel(connectionPhase),
                        color = CelesteMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(11.dp))
            CelesteTextField(
                value = address,
                onValueChange = { address = it },
                placeholder = "https://hermes.example.net",
                semanticLabel = "Dashboard address",
            )


            if (!isConnected && effectiveProbe != null) {
                Spacer(Modifier.height(30.dp))
                EditorialDivider()
                Spacer(Modifier.height(24.dp))
                CelesteSectionLabel("Authentication")
                Spacer(Modifier.height(18.dp))
                when {
                    !effectiveProbe.authRequired -> CelesteLabeledField(
                        value = sessionToken,
                        onValueChange = actions.onSessionTokenChange,
                        label = "Session token",
                        placeholder = "Optional",
                        password = true,
                    )

                    effectiveProbe.supportsPassword -> {
                        CelesteLabeledField(
                            value = username,
                            onValueChange = actions.onUsernameChange,
                            label = "Username",
                            placeholder = "Username",
                        )
                        Spacer(Modifier.height(16.dp))
                        CelesteLabeledField(
                            value = password,
                            onValueChange = actions.onPasswordChange,
                            label = "Password",
                            placeholder = "Password",
                            password = true,
                        )
                    }

                    else -> StatusMessage("Browser sign-in isn’t available in this build.", CelesteError)
                }
            } else if (
                isConnected &&
                !addressChanged &&
                savedAuthMode != null &&
                savedAuthMode != SavedAuthMode.Open
            ) {
                Spacer(Modifier.height(30.dp))
                EditorialDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        CelesteSectionLabel("Authentication")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (savedAuthMode == SavedAuthMode.ProviderSession) {
                                username.ifBlank { "Signed in" }
                            } else {
                                "Session token"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    TextButton(onClick = actions.onSignOut) {
                        Text("Sign out", color = CelesteBlue)
                    }
                }
                EditorialDivider()
            }

            val visibleNotice = notice?.takeUnless {
                it.category == dev.hazydreams.hermesceleste.UiNoticeCategory.AuthenticationRequired
            }
            AnimatedVisibility(visibleNotice != null || loadingMessage != null) {
                Column(modifier = Modifier.padding(vertical = 22.dp)) {
                    loadingMessage?.let { StatusMessage(it, CelesteBlue, showSpinner = true) }
                    if (loadingMessage != null && visibleNotice != null) Spacer(Modifier.height(10.dp))
                    visibleNotice?.let {
                        UiNoticeMessage(
                            notice = it,
                            onRetry = actions.onRetry,
                            onSignIn = actions.onSignIn,
                        )
                    }
                }
            }

            val canSubmitAuthentication = when {
                effectiveProbe == null -> false
                !effectiveProbe.authRequired -> true
                effectiveProbe.supportsPassword -> username.isNotBlank() && password.isNotEmpty()
                else -> false
            }
            val showPrimaryAction = !isConnected || addressChanged
            if (showPrimaryAction) {
                Spacer(Modifier.height(if (visibleNotice == null && loadingMessage == null) 28.dp else 4.dp))
                CelestePrimaryButton(
                    text = when {
                        effectiveProbe == null -> "Find my Hermes"
                        effectiveProbe.authRequired -> "Sign in"
                        savedAuthMode != null || addressChanged -> "Save & reconnect"
                        else -> "Connect"
                    },
                    loading = loadingMessage != null,
                    enabled = if (effectiveProbe == null) {
                        address.isNotBlank()
                    } else {
                        canSubmitAuthentication
                    },
                    onClick = {
                        if (effectiveProbe == null) {
                            actions.onApplyAddress(address)
                        } else {
                            actions.onConnect()
                        }
                    },
                )
            } else if (loadingMessage == null) {
                Spacer(Modifier.height(20.dp))
                TextButton(
                    onClick = actions.onRetry,
                    modifier = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                ) {
                    Text("Reconnect →", color = CelesteBlue)
                }
            }

            if (connectionPhase == ConnectionPhase.RestoreFailed && loadingMessage == null) {
                TextButton(onClick = actions.onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("Try saved connection again", color = CelesteBlue)
                }
            }

            if (savedAuthMode != null) {
                Spacer(Modifier.height(34.dp))
                EditorialDivider()
                TextButton(
                    onClick = { confirmForgetConnection = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text("Forget connection", color = CelesteError)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmForgetConnection) {
        AlertDialog(
            containerColor = CelestePanel,
            onDismissRequest = { confirmForgetConnection = false },
            title = {
                Text("Forget this connection?", style = MaterialTheme.typography.headlineMedium)
            },
            text = {
                Text(
                    "This removes the dashboard address and saved sign-in from this device.",
                    color = CelesteMuted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForgetConnection = false
                        actions.onForgetConnection()
                    },
                ) {
                    Text("Forget", color = CelesteError)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForgetConnection = false }) {
                    Text("Cancel", color = CelesteBlue)
                }
            },
        )
    }
}

@Composable
private fun EditorialHeader(title: String, onBack: (() -> Unit)?) {
    Column {
        onBack?.let {
            TextButton(
                onClick = it,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
            ) {
                Text("←  Back", color = CelesteBlue, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
        }
        Text(title, style = MaterialTheme.typography.displayMedium)
    }
}

@Composable
private fun StatusDot(phase: ConnectionPhase) {
    Box(Modifier.size(7.dp).background(connectionStatusColor(phase), CircleShape))
}

private fun connectionStatusLabel(phase: ConnectionPhase): String = when (phase) {
    ConnectionPhase.Connected -> "Connected"
    ConnectionPhase.CheckingSavedConnection, ConnectionPhase.Restoring -> "Connecting"
    ConnectionPhase.RestoreFailed -> "Unavailable"
    ConnectionPhase.AuthenticationRequired -> "Sign in required"
    ConnectionPhase.ManualSetup -> "Not connected"
}

private fun connectionStatusColor(phase: ConnectionPhase): Color = when (phase) {
    ConnectionPhase.Connected -> CelesteCoral
    ConnectionPhase.CheckingSavedConnection, ConnectionPhase.Restoring -> CelesteGoldText
    ConnectionPhase.RestoreFailed -> CelesteError
    ConnectionPhase.AuthenticationRequired -> CelesteGoldText
    ConnectionPhase.ManualSetup -> CelesteMuted
}

@Composable
private fun CelesteLabeledField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    password: Boolean = false,
) {
    Text(
        text = label.uppercase(),
        color = CelesteMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.1.sp,
    )
    Spacer(Modifier.height(9.dp))
    CelesteTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        semanticLabel = label,
        password = password,
    )
}

@Composable
private fun CelesteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    semanticLabel: String,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = semanticLabel },
        placeholder = { Text(placeholder, color = CelesteMuted) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
        shape = RoundedCornerShape(15.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CelesteBlue,
            unfocusedBorderColor = CelesteHairline,
            focusedContainerColor = CelestePanel,
            unfocusedContainerColor = CelestePanel,
            cursorColor = CelesteBlue,
        ),
    )
}

@Composable
private fun CelestePrimaryButton(
    text: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = Modifier.fillMaxWidth().height(60.dp),
        shape = RoundedCornerShape(30.dp),
        border = BorderStroke(1.dp, if (enabled) CelesteInk else CelesteHairline),
        contentPadding = PaddingValues(horizontal = 22.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CelestePaperRaised,
            contentColor = CelesteInk,
            disabledContainerColor = CelestePanelRaised,
            disabledContentColor = CelesteMuted,
        ),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 1.8.dp,
                color = CelesteInk,
            )
        } else {
            Text("→", color = if (enabled) CelesteInk else CelesteMuted, fontSize = 19.sp)
        }
    }
}

@Composable
private fun CelesteHalo() {
    Canvas(modifier = Modifier.size(108.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.39f
        drawCircle(
            color = CelesteGold.copy(alpha = 0.24f),
            radius = radius,
            center = center,
            style = Stroke(width = 1.2.dp.toPx()),
        )
        drawCircle(
            color = CelesteGold.copy(alpha = 0.10f),
            radius = radius * 0.72f,
            center = center,
            style = Stroke(width = 0.8.dp.toPx()),
        )
        drawArc(
            color = CelesteGold,
            startAngle = 132f,
            sweepAngle = 76f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round),
        )
        drawCircle(
            color = CelesteGold,
            radius = 3.5.dp.toPx(),
            center = Offset(center.x + radius * 0.72f, center.y - radius * 0.52f),
        )
    }
}
