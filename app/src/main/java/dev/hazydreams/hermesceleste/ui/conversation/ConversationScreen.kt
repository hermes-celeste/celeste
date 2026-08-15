package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.ConversationActionModel
import dev.hazydreams.hermesceleste.ConversationActivityCandidates
import dev.hazydreams.hermesceleste.TurnState
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
import kotlinx.coroutines.delay

@Composable
internal fun ConversationScreen(
    summary: StoredSession,
    activityScope: ConversationActivityScope,
    messages: List<ConversationMessage>,
    streamingText: String,
    draft: String,
    turnState: TurnState,
    errorMessage: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onReconnect: () -> Unit,
    onBack: () -> Unit,
    activityCandidates: ConversationActivityCandidates = ConversationActivityCandidates(),
    actionModel: ConversationActionModel = ConversationActionModel(),
    onActiveTurnAction: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val transcriptKeys = remember(messages) { transcriptItemKeys(messages) }
    val activity = selectConversationActivity(
        scope = activityScope,
        turnState = turnState,
        activityCandidates = activityCandidates,
        streamingText = streamingText,
        errorMessage = errorMessage,
        actionModel = actionModel,
    )
    var debouncedWorkingKey by remember(activityScope.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(activity.owner?.key) {
        debouncedWorkingKey = null
        if (activity.owner?.kind == ActivityOwnerKind.Working) {
            delay(NO_OUTPUT_ACTIVITY_DEBOUNCE_MILLIS)
            debouncedWorkingKey = activity.owner.key
        }
    }
    val visibleOwner = activity.owner?.takeUnless { owner ->
        owner.kind == ActivityOwnerKind.Working && debouncedWorkingKey != owner.key
    }
    val pendingToolIndex = activityCandidates.pendingTool?.index
    val interimAssistantIndex = activityCandidates.interimAssistant?.index
    val visibleMessageCount = messages.size +
        (if (streamingText.isNotBlank()) 1 else 0) +
        (if (visibleOwner?.kind == ActivityOwnerKind.Working) 1 else 0)
    val safeDrawingInsets = WindowInsets.safeDrawing
    val headerTopPadding = maxOf(
        34.dp,
        safeDrawingInsets.asPaddingValues().calculateTopPadding() + 10.dp,
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
                        modifier = Modifier.size(18.dp).clearAndSetSemantics {},
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
                        text = if (turnState == TurnState.Reconnecting) "Disconnected" else "Connected",
                        color = CelesteMuted,
                        fontSize = 12.sp,
                    )
                }
            }

            visibleOwner
                ?.takeUnless { owner ->
                    owner.kind == ActivityOwnerKind.Working ||
                        owner.kind == ActivityOwnerKind.Streaming ||
                        owner.kind == ActivityOwnerKind.Tool
                }
                ?.let { owner ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ConversationActivityStatus(owner)
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
                ) { index, message ->
                    val itemOwner = when {
                        visibleOwner?.kind == ActivityOwnerKind.Tool && index == pendingToolIndex -> visibleOwner
                        visibleOwner?.kind == ActivityOwnerKind.Streaming &&
                            streamingText.isBlank() && index == interimAssistantIndex -> visibleOwner
                        else -> null
                    }
                    MessageBubble(message, activityOwner = itemOwner)
                }
                if (streamingText.isNotBlank()) {
                    item(key = "${activityScope.key}:$STREAMING_TRANSCRIPT_KEY") {
                        MessageBubble(
                            ConversationMessage(role = "assistant", text = streamingText, pending = true),
                            activityOwner = visibleOwner?.takeIf {
                                it.kind == ActivityOwnerKind.Streaming
                            },
                        )
                    }
                }
                if (visibleOwner?.kind == ActivityOwnerKind.Working) {
                    item(key = visibleOwner.key) {
                        ConversationActivityStatus(visibleOwner)
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
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = activity.draftEnabled &&
                        (turnState != TurnState.Running || onActiveTurnAction != null),
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "Message Hermes",
                            color = CelesteMuted,
                        )
                    },
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (draft.isNotBlank()) {
                                when (activity.composerAction) {
                                    ConversationComposerAction.SendMessage -> {
                                        onSend()
                                        focusManager.clearFocus()
                                    }

                                    ConversationComposerAction.SteerWithMessage,
                                    ConversationComposerAction.QueueMessage -> {
                                        onActiveTurnAction?.invoke()
                                        focusManager.clearFocus()
                                    }

                                    else -> Unit
                                }
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
                    Spacer(Modifier.weight(1f))
                    when (activity.composerAction) {
                        ConversationComposerAction.StopResponse -> OutlinedButton(
                            onClick = onInterrupt,
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CelesteCoral),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteCoral),
                        ) { Text(activity.composerAction.label, fontWeight = FontWeight.SemiBold) }

                        ConversationComposerAction.RetryConnection -> OutlinedButton(
                            onClick = onReconnect,
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CelesteBlue),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteBlue),
                        ) { Text(activity.composerAction.label, fontWeight = FontWeight.SemiBold) }

                        ConversationComposerAction.Retry -> OutlinedButton(
                            onClick = onReconnect,
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CelesteError),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteError),
                        ) { Text(activity.composerAction.label, fontWeight = FontWeight.SemiBold) }

                        ConversationComposerAction.SteerWithMessage,
                        ConversationComposerAction.QueueMessage -> OutlinedButton(
                            onClick = {
                                onActiveTurnAction?.invoke()
                                focusManager.clearFocus()
                            },
                            enabled = draft.isNotBlank() && onActiveTurnAction != null,
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CelesteBlue),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteBlue),
                        ) { Text(activity.composerAction.label, fontWeight = FontWeight.SemiBold) }

                        ConversationComposerAction.SendMessage -> Button(
                            onClick = {
                                onSend()
                                focusManager.clearFocus()
                            },
                            enabled = activity.composerAction == ConversationComposerAction.SendMessage &&
                                draft.isNotBlank(),
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CelesteInk,
                                contentColor = CelestePaper,
                                disabledContainerColor = CelesteHairline,
                                disabledContentColor = CelesteMuted,
                            ),
                        ) { Text(activity.composerAction.label, fontWeight = FontWeight.Bold) }

                        ConversationComposerAction.Unavailable -> Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CelesteInk,
                                contentColor = CelestePaper,
                                disabledContainerColor = CelesteHairline,
                                disabledContentColor = CelesteMuted,
                            ),
                        ) { Text(activity.composerAction.label, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

private fun turnStateColor(turnState: TurnState): Color = when (turnState) {
    TurnState.Idle -> CelesteCoral
    TurnState.Running -> CelesteGoldText
    TurnState.Synchronizing, TurnState.Reconnecting -> CelesteBlue
}

@Composable
private fun ConversationActivityStatus(owner: ConversationActivityOwner) {
    StatusMessage(
        message = owner.label,
        color = activityOwnerColor(owner),
        showSpinner = owner.kind == ActivityOwnerKind.Synchronizing ||
            owner.kind == ActivityOwnerKind.Working ||
            owner.kind == ActivityOwnerKind.Reconnecting,
        modifier = Modifier.conversationActivitySemantics(owner),
    )
}

private fun activityOwnerColor(owner: ConversationActivityOwner): Color = when (owner.kind) {
    ActivityOwnerKind.Error -> CelesteError
    ActivityOwnerKind.Reconnecting -> CelesteBlue
    ActivityOwnerKind.Synchronizing -> CelesteBlue
    ActivityOwnerKind.Working -> CelesteGoldText
    ActivityOwnerKind.Streaming -> CelesteCoral
    ActivityOwnerKind.Tool -> CelesteGoldText
}

private const val NO_OUTPUT_ACTIVITY_DEBOUNCE_MILLIS = 250L
