package dev.hazydreams.hermesceleste

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.HermesCelesteTheme
import dev.hazydreams.hermesceleste.ui.CelesteBlue
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteGold
import dev.hazydreams.hermesceleste.ui.CelesteCoral
import dev.hazydreams.hermesceleste.ui.CelesteMuted
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelestePanelRaised
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HermesCelesteTheme {
                HermesCelesteApp()
            }
        }
    }
}

@Composable
private fun HermesCelesteApp(viewModel: CelesteViewModel = viewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val activeSummary = ui.activeSummary
    val sessions = ui.sessions
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel, activeSummary?.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.onForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.onForeground()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
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

        sessions != null -> {
            BackHandler(onBack = viewModel::leaveSessionList)
            SessionListScreen(
                sessions = sessions,
                profiles = ui.profiles,
                selectedProfile = ui.selectedProfile,
                loadingMessage = ui.loadingMessage,
                errorMessage = ui.errorMessage,
                onBack = viewModel::leaveSessionList,
                onProfileSelected = viewModel::selectProfile,
                onNewConversation = viewModel::createNewConversation,
                onSessionSelected = viewModel::openSession,
            )
        }

        else -> ConnectScreen(
            dashboardUrl = ui.dashboardUrl,
            onDashboardUrlChange = viewModel::updateDashboardUrl,
            probe = ui.probe,
            username = ui.username,
            onUsernameChange = viewModel::updateUsername,
            password = ui.password,
            onPasswordChange = viewModel::updatePassword,
            sessionToken = ui.sessionToken,
            onSessionTokenChange = viewModel::updateSessionToken,
            loadingMessage = ui.loadingMessage,
            errorMessage = ui.errorMessage,
            onProbe = viewModel::findDashboard,
            onLoadSessions = viewModel::loadSessions,
        )
    }
}

@Composable
internal fun ConnectScreen(
    dashboardUrl: String,
    onDashboardUrlChange: (String) -> Unit,
    probe: DashboardProbeResult?,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    sessionToken: String,
    onSessionTokenChange: (String) -> Unit,
    loadingMessage: String?,
    errorMessage: String?,
    onProbe: () -> Unit,
    onLoadSessions: () -> Unit,
) {
    CelesteBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 54.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CelesteHalo()
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Hermes Celeste",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.8).sp,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                text = "Your Hermes, carried forward.",
                color = CelesteMuted,
                fontSize = 16.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))

            CelesteCard {
                Text("Connect to Hermes", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(7.dp))
                Text(
                    "Use the dashboard address running on your Hermes machine.",
                    color = CelesteMuted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(20.dp))
                CelesteTextField(
                    value = dashboardUrl,
                    onValueChange = onDashboardUrlChange,
                    label = "Dashboard address",
                    placeholder = "100.x.x.x:9119",
                )
                Spacer(Modifier.height(14.dp))

                if (probe == null) {
                    CelesteButton(
                        text = "Find my Hermes",
                        loading = loadingMessage != null,
                        enabled = dashboardUrl.isNotBlank(),
                        onClick = onProbe,
                    )
                } else {
                    ConnectionResult(probe)
                    Spacer(Modifier.height(18.dp))
                    when {
                        !probe.authRequired -> {
                            CelesteTextField(
                                value = sessionToken,
                                onValueChange = onSessionTokenChange,
                                label = "Dashboard session token (optional)",
                                placeholder = "Leave empty for open loopback",
                                password = true,
                            )
                        }

                        probe.supportsPassword -> {
                            CelesteTextField(
                                value = username,
                                onValueChange = onUsernameChange,
                                label = "Username",
                                placeholder = "Hermes username",
                            )
                            Spacer(Modifier.height(12.dp))
                            CelesteTextField(
                                value = password,
                                onValueChange = onPasswordChange,
                                label = "Password",
                                placeholder = "Hermes password",
                                password = true,
                            )
                        }

                        else -> StatusMessage(
                            "This dashboard uses browser sign-in. OAuth arrives in the next connection milestone.",
                            CelesteBlue,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    CelesteButton(
                        text = "Load my conversations",
                        loading = loadingMessage != null,
                        enabled = when {
                            !probe.authRequired -> true
                            probe.supportsPassword -> username.isNotBlank() && password.isNotEmpty()
                            else -> false
                        },
                        onClick = onLoadSessions,
                    )
                }

                AnimatedVisibility(errorMessage != null) {
                    Column {
                        Spacer(Modifier.height(16.dp))
                        StatusMessage(errorMessage.orEmpty(), CelesteError)
                    }
                }
                AnimatedVisibility(loadingMessage != null) {
                    Text(
                        loadingMessage.orEmpty(),
                        color = CelesteMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Celeste connects directly to your dashboard. No relay, copied history, or separate account.",
                color = CelesteMuted.copy(alpha = 0.78f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
    }
}

@Composable
internal fun SessionListScreen(
    sessions: List<StoredSession>,
    profiles: List<DashboardProfile>,
    selectedProfile: String,
    loadingMessage: String?,
    errorMessage: String?,
    onBack: () -> Unit,
    onProfileSelected: (String) -> Unit,
    onNewConversation: () -> Unit,
    onSessionSelected: (StoredSession) -> Unit,
) {
    var profileMenuExpanded by remember { mutableStateOf(false) }
    CelesteBackdrop {
        Column(Modifier.fillMaxSize().padding(top = 46.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedButton(onClick = onBack, shape = RoundedCornerShape(14.dp)) { Text("Back") }
                Column(Modifier.weight(1f)) {
                    Text("Conversations", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text("${sessions.size} from this Hermes", color = CelesteMuted, fontSize = 13.sp)
                }
                Box(Modifier.size(10.dp).background(CelesteCoral, CircleShape))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { profileMenuExpanded = true },
                        enabled = loadingMessage == null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Profile: ${selectedProfile.replaceFirstChar(Char::uppercase)}")
                    }
                    DropdownMenu(
                        expanded = profileMenuExpanded,
                        onDismissRequest = { profileMenuExpanded = false },
                    ) {
                        profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (profile.isDefault) "${profile.name} · default" else profile.name,
                                    )
                                },
                                onClick = {
                                    onProfileSelected(profile.name)
                                    profileMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                Button(
                    onClick = onNewConversation,
                    enabled = loadingMessage == null,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("New chat")
                }
            }
            if (loadingMessage != null || errorMessage != null) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    loadingMessage?.let { StatusMessage(it, CelesteBlue, showSpinner = true) }
                    errorMessage?.let { StatusMessage(it, CelesteError) }
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CelestePanel.copy(alpha = 0.94f), RoundedCornerShape(20.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
                            .clickable(enabled = loadingMessage == null) { onSessionSelected(session) }
                            .padding(18.dp),
                    ) {
                        Text(
                            session.title.ifBlank { "Untitled conversation" },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (session.preview.isNotBlank()) {
                            Spacer(Modifier.height(7.dp))
                            Text(
                                session.preview,
                                color = CelesteMuted,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "${session.messageCount} messages  •  ${session.profile}  •  ${session.source.ifBlank { "Hermes" }}",
                            color = CelesteBlue.copy(alpha = 0.86f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

internal const val STREAMING_TRANSCRIPT_KEY = "streaming:assistant"

internal fun transcriptItemKeys(messages: List<ConversationMessage>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return messages.mapIndexed { index, message ->
        val id = message.id?.takeIf(String::isNotBlank)
            ?: return@mapIndexed "transcript:fallback:$index"
        val base = "transcript:id:${id.length}:$id"
        val occurrence = occurrences.getOrDefault(base, 0) + 1
        occurrences[base] = occurrence
        if (occurrence == 1) base else "$base:occurrence:$occurrence"
    }
}

@Composable
internal fun ConversationScreen(
    summary: StoredSession,
    messages: List<ConversationMessage>,
    streamingText: String,
    draft: String,
    turnState: TurnState,
    loadingMessage: String?,
    errorMessage: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onReconnect: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val transcriptKeys = remember(messages) { transcriptItemKeys(messages) }
    val visibleMessageCount = messages.size + if (streamingText.isNotBlank()) 1 else 0
    LaunchedEffect(visibleMessageCount, streamingText.length) {
        if (visibleMessageCount > 0) listState.animateScrollToItem(visibleMessageCount - 1)
    }

    CelesteBackdrop {
        Column(Modifier.fillMaxSize().padding(top = 46.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedButton(onClick = onBack, shape = RoundedCornerShape(14.dp)) { Text("Back") }
                Column(Modifier.weight(1f)) {
                    Text(
                        summary.title.ifBlank { "Conversation" },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when (turnState) {
                            TurnState.Idle -> "Connected to your shared Hermes session"
                            TurnState.Running -> "Hermes is responding"
                            TurnState.Synchronizing -> "Synchronizing conversation"
                            TurnState.Reconnecting -> "Reconnecting to Hermes"
                        },
                        color = when (turnState) {
                            TurnState.Idle -> CelesteCoral
                            TurnState.Running -> CelesteGold
                            TurnState.Synchronizing, TurnState.Reconnecting -> CelesteBlue
                        },
                        fontSize = 12.sp,
                    )
                }
                if (turnState == TurnState.Running || turnState == TurnState.Synchronizing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = if (turnState == TurnState.Running) CelesteGold else CelesteBlue,
                    )
                }
            }

            if (loadingMessage != null || errorMessage != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    loadingMessage?.let { StatusMessage(it, CelesteBlue, showSpinner = true) }
                    errorMessage?.let { StatusMessage(it, CelesteError) }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(
                    items = messages,
                    key = { index, _ -> transcriptKeys[index] },
                ) { _, message ->
                    MessageBubble(message)
                }
                if (streamingText.isNotBlank()) {
                    item(key = STREAMING_TRANSCRIPT_KEY) {
                        MessageBubble(
                            ConversationMessage(role = "assistant", text = streamingText, pending = true),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CelestePanel.copy(alpha = 0.96f))
                    .border(1.dp, Color.White.copy(alpha = 0.07f))
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = turnState == TurnState.Idle || turnState == TurnState.Reconnecting,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            when (turnState) {
                                TurnState.Idle -> "Message Hermes"
                                TurnState.Running -> "Hermes is responding…"
                                TurnState.Synchronizing -> "Synchronizing…"
                                TurnState.Reconnecting -> "Keep drafting while Celeste reconnects…"
                            },
                        )
                    },
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (draft.isNotBlank() && turnState == TurnState.Idle) {
                                onSend()
                                focusManager.clearFocus()
                            }
                        },
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CelesteCoral,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (turnState) {
                            TurnState.Idle -> "Shared with Hermes Desktop"
                            TurnState.Running -> "Streaming live"
                            TurnState.Synchronizing -> "Refreshing history"
                            TurnState.Reconnecting -> "Draft kept on this screen"
                        },
                        color = CelesteMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    when (turnState) {
                        TurnState.Running -> OutlinedButton(
                            onClick = onInterrupt,
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("Stop") }

                        TurnState.Reconnecting -> OutlinedButton(
                            onClick = onReconnect,
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("Retry") }

                        else -> Button(
                            onClick = {
                                onSend()
                                focusManager.clearFocus()
                            },
                            enabled = draft.isNotBlank() && turnState == TurnState.Idle,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CelesteCoral,
                                contentColor = Color(0xFF07110D),
                            ),
                        ) { Text("Send", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ConversationMessage) {
    val isUser = message.role == "user"
    val isAssistant = message.role == "assistant"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.88f else 0.94f)
                .background(
                    when {
                        isUser -> CelesteBlue.copy(alpha = 0.16f)
                        isAssistant -> CelestePanel
                        else -> CelestePanelRaised.copy(alpha = 0.72f)
                    },
                    RoundedCornerShape(18.dp),
                )
                .border(
                    1.dp,
                    if (message.pending && isAssistant) {
                        CelesteGold.copy(alpha = 0.34f)
                    } else {
                        Color.White.copy(alpha = 0.06f)
                    },
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (message.role) {
                        "user" -> "You"
                        "assistant" -> "Hermes"
                        "tool" -> message.toolName ?: "Tool"
                        else -> message.role.replaceFirstChar(Char::uppercase)
                    },
                    color = if (isUser) CelesteBlue else CelesteCoral,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (message.pending) {
                    Spacer(Modifier.size(7.dp))
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(if (isAssistant) CelesteGold else CelesteBlue, CircleShape),
                    )
                }
            }
            if (message.text.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(message.text, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun CelesteBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 32.dp, end = 4.dp)
                .size(210.dp)
                .blur(72.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                .background(CelesteBlue.copy(alpha = 0.18f), CircleShape),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(180.dp)
                .blur(64.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                .background(CelesteCoral.copy(alpha = 0.13f), CircleShape),
        )
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
        ) {
            content()
        }
    }
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

@Composable
private fun StatusMessage(message: String, color: Color, showSpinner: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        if (showSpinner) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
        } else {
            Box(Modifier.size(8.dp).background(color, CircleShape))
        }
        Text(message, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
