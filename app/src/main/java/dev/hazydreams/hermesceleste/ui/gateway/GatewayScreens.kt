package dev.hazydreams.hermesceleste.ui.gateway

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.ConnectionPhase
import dev.hazydreams.hermesceleste.connection.SavedAuthMode
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.ui.CelesteAmberText
import dev.hazydreams.hermesceleste.ui.CelesteBackdrop
import dev.hazydreams.hermesceleste.ui.CelesteBlue
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteInk
import dev.hazydreams.hermesceleste.ui.CelesteLightTone
import dev.hazydreams.hermesceleste.ui.CelesteMuted
import dev.hazydreams.hermesceleste.ui.CelesteOrb
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelestePanelRaised
import dev.hazydreams.hermesceleste.ui.CelestePaper
import dev.hazydreams.hermesceleste.ui.CelesteSectionLabel
import dev.hazydreams.hermesceleste.ui.CelesteSuccess
import dev.hazydreams.hermesceleste.ui.CelesteSurface
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
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 38.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NavigationHeader(title = "Celeste", onBack = null)
            Spacer(Modifier.weight(1f))
            CelesteOrb(modifier = Modifier.size(84.dp))
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
    errorMessage: String?,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
) {
    CelesteBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 38.dp),
        ) {
            NavigationHeader(title = "Celeste", onBack = null)
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
            errorMessage?.let {
                Spacer(Modifier.height(26.dp))
                StatusMessage(it, CelesteError)
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
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 28.dp),
        ) {
            NavigationHeader(title = "Settings", onBack = onBack)
            Spacer(Modifier.height(34.dp))
            CelesteSectionLabel("Connection")
            Spacer(Modifier.height(12.dp))
            CelesteSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onGateway),
                tone = CelesteLightTone.Cool,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 17.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Gateway", style = MaterialTheme.typography.titleMedium)
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
                    Text("›", color = CelesteInk, fontSize = 18.sp)
                }
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
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 38.dp),
        ) {
            if (initialSetup) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(34.dp))
                    CelesteOrb(modifier = Modifier.size(84.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Your Hermes.",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "CARRIED FORWARD",
                        color = CelesteMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.8.sp,
                    )
                    Spacer(Modifier.height(42.dp))
                }
            } else {
                NavigationHeader(title = "Gateway", onBack = onBack)
                Spacer(Modifier.height(32.dp))
            }

            CelesteSurface(
                modifier = Modifier.fillMaxWidth(),
                tone = CelesteLightTone.Cool,
                emphasized = initialSetup,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CelesteSectionLabel("Dashboard URL", Modifier.weight(1f))
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
                    Spacer(Modifier.height(10.dp))
                    CelesteTextField(
                        value = address,
                        onValueChange = { address = it },
                        placeholder = "https://hermes.example.net",
                        semanticLabel = "Dashboard address",
                    )
                }
            }


            if (!isConnected && effectiveProbe != null) {
                Spacer(Modifier.height(20.dp))
                CelesteSurface(
                    modifier = Modifier.fillMaxWidth(),
                    tone = if (effectiveProbe.authRequired) CelesteLightTone.Warm else CelesteLightTone.Cool,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    Column {
                        CelesteSectionLabel("Authentication")
                        Spacer(Modifier.height(16.dp))
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
                    }
                }
            } else if (
                isConnected &&
                !addressChanged &&
                savedAuthMode != null &&
                savedAuthMode != SavedAuthMode.Open
            ) {
                Spacer(Modifier.height(20.dp))
                CelesteSurface(
                    modifier = Modifier.fillMaxWidth(),
                    tone = CelesteLightTone.None,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                }
            }

            val visibleError = errorMessage.takeUnless {
                connectionPhase == ConnectionPhase.AuthenticationRequired
            }
            AnimatedVisibility(visibleError != null || loadingMessage != null) {
                Column(modifier = Modifier.padding(vertical = 22.dp)) {
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
                Spacer(Modifier.height(if (visibleError == null && loadingMessage == null) 28.dp else 4.dp))
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
                HorizontalDivider(thickness = 1.dp, color = CelesteHairline)
                TextButton(
                    onClick = { confirmForgetConnection = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text("Forget connection", color = CelesteError)
                }
            }

            if (initialSetup) {
                Spacer(Modifier.height(92.dp))
                Text(
                    text = "Connects via HTTPS, private network, or Tailscale",
                    color = CelesteMuted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "•  Local-first",
                    color = CelesteSuccess,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
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
private fun NavigationHeader(title: String, onBack: (() -> Unit)?) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.Center),
        )
        onBack?.let { back ->
            TextButton(
                onClick = back,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Text("‹", color = CelesteInk, fontSize = 26.sp, lineHeight = 26.sp)
            }
        }
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
    ConnectionPhase.Connected -> CelesteSuccess
    ConnectionPhase.CheckingSavedConnection, ConnectionPhase.Restoring -> CelesteAmberText
    ConnectionPhase.RestoreFailed -> CelesteError
    ConnectionPhase.AuthenticationRequired -> CelesteAmberText
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
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(27.dp),
        border = BorderStroke(1.dp, if (enabled) CelesteInk else CelesteHairline),
        contentPadding = PaddingValues(horizontal = 22.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CelesteInk,
            contentColor = CelestePaper,
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
                color = CelestePaper,
            )
        } else {
            Text("↗", color = if (enabled) CelestePaper else CelesteMuted, fontSize = 17.sp)
        }
    }
}
