package dev.hazydreams.hermesceleste.ui.gateway

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.ConnectionPhase
import dev.hazydreams.hermesceleste.connection.SavedAuthMode
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.ui.CelesteBlue
import dev.hazydreams.hermesceleste.ui.CelesteCoral
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteGold
import dev.hazydreams.hermesceleste.ui.CelesteMuted
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelesteBackdrop
import dev.hazydreams.hermesceleste.ui.StatusMessage

internal data class GatewaySettingsUiState(
    val dashboardUrl: String,
    val probe: DashboardProbeResult?,
    val savedAuthMode: SavedAuthMode?,
    val username: String,
    val password: String,
    val sessionToken: String,
    val connectionPhase: ConnectionPhase,
    val loadingMessage: String?,
    val errorMessage: String?,
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
)

@Composable
internal fun ConnectionLoadingScreen() {
    CelesteBackdrop {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CelesteHalo()
            Spacer(Modifier.height(28.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = CelesteCoral,
            )
        }
    }
}

@Composable
internal fun ConnectionUnavailableScreen(
    errorMessage: String?,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
) {
    CelesteBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 54.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CelesteHalo()
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Can’t connect",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            CelesteCard {
                errorMessage?.let { StatusMessage(it, CelesteError) }
                if (errorMessage != null) Spacer(Modifier.height(18.dp))
                CelesteButton(
                    text = "Retry",
                    loading = false,
                    enabled = true,
                    onClick = onRetry,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = onSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Gateway settings")
                }
            }
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
                .padding(top = 46.dp, start = 20.dp, end = 20.dp),
        ) {
            SettingsHeader(title = "Settings", onBack = onBack)
            Spacer(Modifier.height(30.dp))
            Text("Connection", color = CelesteMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CelestePanel.copy(alpha = 0.94f), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
                    .clickable(onClick = onGateway)
                    .padding(horizontal = 18.dp, vertical = 17.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Gateway", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        dashboardUrl.ifBlank { "Not configured" },
                        color = CelesteMuted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier
                        .size(8.dp)
                        .background(connectionStatusColor(connectionPhase), CircleShape),
                )
                Spacer(Modifier.size(9.dp))
                Text(connectionStatusLabel(connectionPhase), color = CelesteMuted, fontSize = 13.sp)
                Spacer(Modifier.size(10.dp))
                Text("›", color = CelesteMuted, fontSize = 24.sp)
            }
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
    val errorMessage = state.errorMessage
    val onUsernameChange = actions.onUsernameChange
    val onPasswordChange = actions.onPasswordChange
    val onSessionTokenChange = actions.onSessionTokenChange
    val onApplyAddress = actions.onApplyAddress
    val onConnect = actions.onConnect
    val onRetry = actions.onRetry
    val onSignOut = actions.onSignOut
    val onForgetConnection = actions.onForgetConnection
    val onBack = actions.onBack
    var address by rememberSaveable(dashboardUrl) { mutableStateOf(dashboardUrl) }
    var confirmForgetConnection by remember { mutableStateOf(false) }
    val isConnected = connectionPhase == ConnectionPhase.Connected
    val addressChanged = address.trim().trimEnd('/') != dashboardUrl.trim().trimEnd('/')
    val effectiveProbe = probe?.takeIf { !addressChanged }

    CelesteBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 46.dp, start = 20.dp, end = 20.dp, bottom = 42.dp),
        ) {
            if (onBack == null && dashboardUrl.isBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CelesteHalo()
                    Spacer(Modifier.height(20.dp))
                    Text("Hermes Celeste", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Your Hermes, carried forward.", color = CelesteMuted, fontSize = 15.sp)
                }
                Spacer(Modifier.height(34.dp))
            } else {
                SettingsHeader(title = "Gateway", onBack = onBack)
                Spacer(Modifier.height(26.dp))
            }

            CelesteCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Status", color = CelesteMuted, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(connectionStatusColor(connectionPhase), CircleShape),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(connectionStatusLabel(connectionPhase), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(18.dp))
                CelesteTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = "Dashboard address",
                    placeholder = "100.x.x.x:9119",
                )

                if (isConnected && !addressChanged && loadingMessage == null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Reconnect")
                    }
                }
            }

            if (!isConnected && effectiveProbe != null) {
                Spacer(Modifier.height(16.dp))
                CelesteCard {
                    Text("Authentication", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(16.dp))
                    when {
                        !effectiveProbe.authRequired -> CelesteTextField(
                            value = sessionToken,
                            onValueChange = onSessionTokenChange,
                            label = "Session token",
                            placeholder = "Optional",
                            password = true,
                        )

                        effectiveProbe.supportsPassword -> {
                            CelesteTextField(
                                value = username,
                                onValueChange = onUsernameChange,
                                label = "Username",
                                placeholder = "Username",
                            )
                            Spacer(Modifier.height(12.dp))
                            CelesteTextField(
                                value = password,
                                onValueChange = onPasswordChange,
                                label = "Password",
                                placeholder = "Password",
                                password = true,
                            )
                        }

                        else -> StatusMessage("Browser sign-in isn’t available in this build.", CelesteError)
                    }
                }
            } else if (
                isConnected &&
                !addressChanged &&
                savedAuthMode != null &&
                savedAuthMode != SavedAuthMode.Open
            ) {
                Spacer(Modifier.height(16.dp))
                CelesteCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Authentication", color = CelesteMuted, fontSize = 13.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                if (savedAuthMode == SavedAuthMode.ProviderSession) {
                                    username.ifBlank { "Signed in" }
                                } else {
                                    "Session token"
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        TextButton(onClick = onSignOut) { Text("Sign out") }
                    }
                }
            }

            val visibleError = errorMessage.takeUnless {
                connectionPhase == ConnectionPhase.AuthenticationRequired
            }
            AnimatedVisibility(visibleError != null || loadingMessage != null) {
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp)) {
                    loadingMessage?.let { StatusMessage(it, CelesteBlue, showSpinner = true) }
                    if (loadingMessage != null && visibleError != null) Spacer(Modifier.height(10.dp))
                    visibleError?.let { StatusMessage(it, CelesteError) }
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
                Spacer(Modifier.height(4.dp))
                CelesteButton(
                    text = when {
                        effectiveProbe == null -> "Continue"
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
                            onApplyAddress(address)
                        } else {
                            onConnect()
                        }
                    },
                )
            }

            if (connectionPhase == ConnectionPhase.RestoreFailed && loadingMessage == null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("Retry")
                }
            }

            if (savedAuthMode != null) {
                Spacer(Modifier.height(30.dp))
                TextButton(
                    onClick = { confirmForgetConnection = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Forget connection", color = CelesteError)
                }
            }
        }
    }

    if (confirmForgetConnection) {
        AlertDialog(
            onDismissRequest = { confirmForgetConnection = false },
            title = { Text("Forget this connection?") },
            text = { Text("This removes the dashboard address and saved sign-in from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForgetConnection = false
                        onForgetConnection()
                    },
                ) {
                    Text("Forget", color = CelesteError)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForgetConnection = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsHeader(title: String, onBack: (() -> Unit)?) {
    Box(modifier = Modifier.fillMaxWidth()) {
        onBack?.let {
            TextButton(
                onClick = it,
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Text("Back")
            }
        }
        Text(
            title,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
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
    ConnectionPhase.CheckingSavedConnection, ConnectionPhase.Restoring -> CelesteGold
    ConnectionPhase.RestoreFailed -> CelesteError
    ConnectionPhase.AuthenticationRequired -> CelesteGold
    ConnectionPhase.ManualSetup -> CelesteMuted
}

@Composable
private fun CelesteCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CelestePanel.copy(alpha = 0.92f), RoundedCornerShape(28.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(28.dp))
            .padding(22.dp),
        content = content,
    )
}

@Composable
private fun CelesteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CelesteCoral,
            unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
            focusedLabelColor = CelesteCoral,
        ),
    )
}

@Composable
private fun CelesteButton(
    text: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CelesteCoral, contentColor = Color(0xFF07110D)),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF07110D))
        } else {
            Text(text, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CelesteHalo() {
    Canvas(modifier = Modifier.size(104.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.39f
        drawCircle(
            color = CelesteBlue.copy(alpha = 0.34f),
            radius = radius,
            center = center,
            style = Stroke(width = 2f),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.09f),
            radius = radius * 0.76f,
            center = center,
            style = Stroke(width = 1.5f),
        )
        drawArc(
            color = CelesteCoral,
            startAngle = 132f,
            sweepAngle = 76f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = 5f, cap = StrokeCap.Round),
        )
        drawCircle(
            color = CelesteGold,
            radius = 4.5f,
            center = Offset(center.x + radius * 0.72f, center.y - radius * 0.52f),
        )
    }
}

@Composable
private fun ConnectionResult(result: DashboardProbeResult) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusMessage("Hermes dashboard found", CelesteCoral)
        Text(
            buildString {
                result.version?.let { append("Hermes $it  •  ") }
                append(if (result.authRequired) "Sign-in protected" else "Direct or session-token access")
            },
            color = CelesteMuted,
            fontSize = 13.sp,
        )
    }
}
