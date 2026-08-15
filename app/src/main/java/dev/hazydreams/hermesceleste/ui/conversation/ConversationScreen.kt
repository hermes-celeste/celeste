package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.ActiveTurnPayload
import dev.hazydreams.hermesceleste.BusyInputPolicy
import dev.hazydreams.hermesceleste.ComposerAction
import dev.hazydreams.hermesceleste.DeliveryStatus
import dev.hazydreams.hermesceleste.TurnState
import dev.hazydreams.hermesceleste.composerAction
import dev.hazydreams.hermesceleste.network.AttachmentDraft
import dev.hazydreams.hermesceleste.network.AttachmentReadiness
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.CelesteBackdrop
import dev.hazydreams.hermesceleste.ui.CelesteBlue
import dev.hazydreams.hermesceleste.ui.CelesteCoral
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteGoldText
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteInk
import dev.hazydreams.hermesceleste.ui.CelesteMuted
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelestePaper
import dev.hazydreams.hermesceleste.ui.StatusMessage

@Composable
internal fun ConversationScreen(
    summary: StoredSession,
    messages: List<ConversationMessage>,
    streamingText: String,
    draft: String,
    attachments: List<AttachmentDraft>,
    turnState: TurnState,
    busyInputPolicy: BusyInputPolicy,
    redirectSupported: Boolean,
    deliveryStatus: DeliveryStatus,
    lastAction: ComposerAction?,
    loadingMessage: String?,
    errorMessage: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSteer: () -> Unit,
    onQueue: () -> Unit,
    onRedirect: () -> Unit,
    onSelectBusyInputPolicy: (BusyInputPolicy) -> Unit,
    onInterrupt: () -> Unit,
    onReconnect: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    var actionMenuExpanded by remember { mutableStateOf(false) }
    val transcriptKeys = remember(messages) { transcriptItemKeys(messages) }
    val visibleMessageCount = messages.size + if (streamingText.isNotBlank()) 1 else 0
    val safeDrawingInsets = WindowInsets.safeDrawing
    val headerTopPadding = maxOf(
        34.dp,
        safeDrawingInsets.asPaddingValues().calculateTopPadding() + 10.dp,
    )
    val payload = ActiveTurnPayload(draft, attachments)
    val selectedAction = composerAction(
        turnState = turnState,
        payload = payload,
        policy = busyInputPolicy,
        redirectSupported = redirectSupported,
    )
    val primaryAction = if (
        deliveryStatus == DeliveryStatus.Pending || deliveryStatus == DeliveryStatus.Uncertain
    ) {
        if (selectedAction == ComposerAction.Stop) ComposerAction.Stop else ComposerAction.None
    } else {
        selectedAction
    }
    val hasReadyPayload = (draft.isNotBlank() || attachments.isNotEmpty()) &&
        attachments.all { it.readiness == AttachmentReadiness.Ready }
    val textActionAvailable = draft.isNotBlank() && attachments.isEmpty() &&
        deliveryStatus != DeliveryStatus.Pending && deliveryStatus != DeliveryStatus.Uncertain
    val queueActionAvailable = hasReadyPayload &&
        deliveryStatus != DeliveryStatus.Pending && deliveryStatus != DeliveryStatus.Uncertain
    val attachmentCopy = attachmentAccessibilityCopy(
        turnState = turnState,
        attachments = attachments,
        deliveryStatus = deliveryStatus,
        lastAction = lastAction,
    )
    LaunchedEffect(visibleMessageCount, streamingText.length) {
        if (visibleMessageCount > 0) listState.animateScrollToItem(visibleMessageCount - 1)
    }

    CelesteBackdrop(showOrnament = false) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(safeDrawingInsets.only(WindowInsetsSides.Horizontal))
                .padding(top = headerTopPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                ) {
                    Text("←  Back", color = CelesteBlue, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                if (turnState == TurnState.Running || turnState == TurnState.Synchronizing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 1.8.dp,
                        color = turnStateColor(turnState),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text(
                    summary.title.ifBlank { "Conversation" },
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(6.dp).background(turnStateColor(turnState), androidx.compose.foundation.shape.CircleShape),
                    )
                    Spacer(Modifier.size(7.dp))
                    Text(
                        text = when (turnState) {
                            TurnState.Idle -> "Connected"
                            TurnState.Running -> "Responding"
                            TurnState.Synchronizing -> "Synchronizing"
                            TurnState.Reconnecting -> "Reconnecting"
                            TurnState.UnsupportedGateway -> "Active-turn controls unavailable"
                        },
                        color = CelesteMuted,
                        fontSize = 12.sp,
                    )
                }
            }

            if (loadingMessage != null || errorMessage != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    loadingMessage?.let { StatusMessage(it, CelesteBlue, showSpinner = true) }
                    errorMessage?.let { StatusMessage(it, CelesteError) }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(25.dp),
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
                    .background(CelestePanel)
                    .border(1.dp, CelesteHairline)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (attachments.isNotEmpty()) {
                    Text(
                        text = attachmentCopy,
                        color = if (attachments.all { it.readiness == AttachmentReadiness.Ready }) {
                            CelesteMuted
                        } else {
                            CelesteError
                        },
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            .semantics {
                                contentDescription = attachmentCopy
                            },
                    )
                    Spacer(Modifier.height(7.dp))
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            when (turnState) {
                                TurnState.Idle -> "Message Hermes"
                                TurnState.Running -> "Guide Hermes while it responds…"
                                TurnState.Synchronizing -> "Synchronizing…"
                                TurnState.Reconnecting -> "Keep drafting while Celeste reconnects…"
                                TurnState.UnsupportedGateway -> "Draft locally; this gateway cannot steer yet…"
                            },
                            color = CelesteMuted,
                        )
                    },
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (primaryAction != ComposerAction.None && primaryAction != ComposerAction.Stop &&
                                (draft.isNotBlank() || attachments.isNotEmpty())
                            ) {
                                onSend()
                                focusManager.clearFocus()
                            }
                        },
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CelesteBlue,
                        unfocusedBorderColor = CelesteHairline,
                        focusedContainerColor = CelestePaper,
                        unfocusedContainerColor = CelestePaper,
                        disabledContainerColor = CelestePaper,
                        cursorColor = CelesteBlue,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = composerHelperText(turnState, deliveryStatus, lastAction),
                        color = CelesteMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (turnState == TurnState.Running) {
                        Box {
                            OutlinedButton(
                                onClick = { actionMenuExpanded = true },
                                enabled = true,
                                modifier = Modifier
                                    .height(48.dp)
                                    .semantics {
                                        contentDescription = "More actions for ${summary.title.ifBlank { "conversation" }}"
                                        stateDescription = if (actionMenuExpanded) "Expanded" else "Collapsed"
                                    },
                                shape = RoundedCornerShape(24.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, CelesteHairline),
                            ) {
                                Text("More  ⋯", fontWeight = FontWeight.SemiBold)
                            }
                            DropdownMenu(
                                expanded = actionMenuExpanded,
                                onDismissRequest = { actionMenuExpanded = false },
                                containerColor = CelestePanel,
                            ) {
                                DropdownMenuItem(
                                    text = { Text("✦  Steer active turn") },
                                    enabled = textActionAvailable,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Steer message into the active Hermes turn"
                                        stateDescription = if (textActionAvailable) "Available" else "Unavailable for this draft"
                                    },
                                    onClick = {
                                        onSelectBusyInputPolicy(BusyInputPolicy.Steer)
                                        actionMenuExpanded = false
                                        onSteer()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("＋  Queue for next turn") },
                                    enabled = queueActionAvailable,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Queue message for the next Hermes turn"
                                        stateDescription = if (queueActionAvailable) "Available" else "Unavailable until the payload is ready"
                                    },
                                    onClick = {
                                        onSelectBusyInputPolicy(BusyInputPolicy.Queue)
                                        actionMenuExpanded = false
                                        onQueue()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (redirectSupported) "↗  Redirect active turn"
                                            else "↗  Redirect unavailable for this gateway",
                                        )
                                    },
                                    enabled = redirectSupported && textActionAvailable,
                                    modifier = Modifier.semantics {
                                        contentDescription = if (redirectSupported) {
                                            "Redirect the active Hermes turn"
                                        } else {
                                            "Redirect unavailable for this gateway"
                                        }
                                        stateDescription = if (redirectSupported && textActionAvailable) {
                                            "Available"
                                        } else {
                                            "Unavailable for this gateway or draft"
                                        }
                                    },
                                    onClick = {
                                        onSelectBusyInputPolicy(BusyInputPolicy.Redirect)
                                        actionMenuExpanded = false
                                        onRedirect()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("■  Stop active turn") },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Stop the active Hermes turn"
                                        stateDescription = "Stops active work and clears the server queue"
                                    },
                                    onClick = {
                                        actionMenuExpanded = false
                                        onInterrupt()
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                    }
                    when {
                        turnState == TurnState.Reconnecting -> OutlinedButton(
                            onClick = onReconnect,
                            modifier = Modifier
                                .height(48.dp)
                                .semantics {
                                    contentDescription = "Retry the Hermes connection for ${summary.title.ifBlank { "conversation" }}"
                                    stateDescription = "Connection needs attention"
                                },
                            shape = RoundedCornerShape(24.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CelesteBlue),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteBlue),
                        ) { Text("Retry", fontWeight = FontWeight.SemiBold) }

                        primaryAction == ComposerAction.Stop -> OutlinedButton(
                            onClick = onInterrupt,
                            modifier = Modifier
                                .height(48.dp)
                                .semantics {
                                    contentDescription = "Stop the active Hermes turn for ${summary.title.ifBlank { "conversation" }}"
                                    stateDescription = "Stops active work and clears the server queue"
                                },
                            shape = RoundedCornerShape(24.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CelesteCoral),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteCoral),
                        ) { Text("■  Stop", fontWeight = FontWeight.SemiBold) }

                        else -> Button(
                            onClick = {
                                when (primaryAction) {
                                    ComposerAction.Send,
                                    ComposerAction.Steer,
                                    ComposerAction.Queue,
                                    ComposerAction.Redirect -> onSend()
                                    else -> Unit
                                }
                                focusManager.clearFocus()
                            },
                            enabled = primaryAction != ComposerAction.None,
                            modifier = Modifier
                                .height(48.dp)
                                .semantics {
                                    contentDescription = primaryActionDescription(primaryAction, summary)
                                    stateDescription = if (deliveryStatus == DeliveryStatus.Pending) {
                                        "Waiting for Hermes"
                                    } else {
                                        composerHelperText(turnState, deliveryStatus, lastAction)
                                    }
                                },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CelesteInk,
                                contentColor = CelestePaper,
                                disabledContainerColor = CelesteHairline,
                                disabledContentColor = CelesteMuted,
                            ),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(actionGlyph(primaryAction), fontWeight = FontWeight.Bold)
                                Spacer(Modifier.size(6.dp))
                                Text(actionLabel(primaryAction), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun attachmentAccessibilityCopy(
    turnState: TurnState,
    attachments: List<AttachmentDraft>,
    deliveryStatus: DeliveryStatus,
    lastAction: ComposerAction?,
): String {
    if (attachments.isEmpty()) return ""
    val count = attachments.size
    val noun = if (count == 1) "attachment" else "attachments"
    if (attachments.any { it.readiness != AttachmentReadiness.Ready }) {
        return "Attachment is not ready; finish uploading or retry before sending. Text will not be sent by itself."
    }
    return when {
        deliveryStatus == DeliveryStatus.Uncertain ->
            "$count $noun delivery is uncertain; the draft is preserved and will not be resent automatically."
        deliveryStatus == DeliveryStatus.Pending && lastAction == ComposerAction.Queue ->
            "$count $noun is being queued for the next turn."
        deliveryStatus == DeliveryStatus.Pending ->
            "$count $noun is being sent with this message."
        deliveryStatus == DeliveryStatus.Accepted && lastAction == ComposerAction.Queue ->
            "$count $noun queued for the next turn."
        turnState == TurnState.Running ->
            "$count $noun will be queued with the next turn."
        turnState == TurnState.Idle ->
            "$count $noun will be sent with this message."
        else ->
            "$count $noun draft is saved while Celeste reconnects; it will not be sent automatically."
    }
}

private fun composerHelperText(
    turnState: TurnState,
    deliveryStatus: DeliveryStatus,
    lastAction: ComposerAction?,
): String = when {
    deliveryStatus == DeliveryStatus.Pending -> "Sending…"
    deliveryStatus == DeliveryStatus.Accepted -> when (lastAction) {
        ComposerAction.Send -> "Message accepted"
        ComposerAction.Steer -> "Guidance accepted"
        ComposerAction.Queue -> "Queued for the next turn"
        ComposerAction.Redirect -> "Active turn redirected"
        ComposerAction.Stop -> "Turn stopped"
        null, ComposerAction.None -> "Action accepted"
    }
    deliveryStatus == DeliveryStatus.Uncertain -> "Delivery uncertain; reconnecting"
    deliveryStatus == DeliveryStatus.Rejected -> when (lastAction) {
        ComposerAction.Steer, ComposerAction.Redirect -> "Not accepted; choose Queue to try deliberately"
        ComposerAction.Queue, ComposerAction.Send -> "Not accepted; your draft is still here"
        ComposerAction.Stop -> "Stop was not confirmed; reconnect before trying again"
        null, ComposerAction.None -> "Not accepted; your draft is still here"
    }
    turnState == TurnState.Idle -> ""
    turnState == TurnState.Running -> "Active turn"
    turnState == TurnState.Synchronizing -> "Synchronizing"
    turnState == TurnState.Reconnecting -> "Draft saved here"
    turnState == TurnState.UnsupportedGateway -> "Active-turn actions require a compatible gateway"
    else -> ""
}

private fun primaryActionDescription(action: ComposerAction, summary: StoredSession): String {
    val title = summary.title.ifBlank { "conversation" }
    return when (action) {
        ComposerAction.Send -> "Send message to Hermes in $title"
        ComposerAction.Steer -> "Steer message into the active Hermes turn in $title"
        ComposerAction.Queue -> "Queue message for the next Hermes turn in $title"
        ComposerAction.Redirect -> "Redirect the active Hermes turn in $title"
        ComposerAction.Stop -> "Stop the active Hermes turn in $title"
        ComposerAction.None -> "Active-turn action unavailable for $title"
    }
}

private fun actionLabel(action: ComposerAction): String = when (action) {
    ComposerAction.Send -> "Send"
    ComposerAction.Steer -> "Steer"
    ComposerAction.Queue -> "Queue"
    ComposerAction.Redirect -> "Redirect"
    ComposerAction.Stop -> "Stop"
    ComposerAction.None -> "Unavailable"
}

private fun actionGlyph(action: ComposerAction): String = when (action) {
    ComposerAction.Send -> "→"
    ComposerAction.Steer -> "✦"
    ComposerAction.Queue -> "＋"
    ComposerAction.Redirect -> "↗"
    ComposerAction.Stop -> "■"
    ComposerAction.None -> "·"
}

private fun turnStateColor(turnState: TurnState): Color = when (turnState) {
    TurnState.Idle -> CelesteCoral
    TurnState.Running -> CelesteGoldText
    TurnState.Synchronizing, TurnState.Reconnecting, TurnState.UnsupportedGateway -> CelesteBlue
}
