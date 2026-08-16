package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.TurnState
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.CelesteActivityFrame
import dev.hazydreams.hermesceleste.ui.CelesteAmber
import dev.hazydreams.hermesceleste.ui.CelesteBackdrop
import dev.hazydreams.hermesceleste.ui.CelesteBlue
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteGoldText
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteInk
import dev.hazydreams.hermesceleste.ui.CelesteLightTone
import dev.hazydreams.hermesceleste.ui.CelesteMuted
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelestePaper
import dev.hazydreams.hermesceleste.ui.CelesteSurface
import dev.hazydreams.hermesceleste.ui.StatusMessage

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
    val safeDrawingInsets = WindowInsets.safeDrawing
    val headerTopPadding = maxOf(
        22.dp,
        safeDrawingInsets.asPaddingValues().calculateTopPadding() + 6.dp,
    )
    val activeTurn = turnState == TurnState.Running || turnState == TurnState.Synchronizing

    LaunchedEffect(visibleMessageCount, streamingText.length) {
        if (visibleMessageCount > 0) listState.animateScrollToItem(visibleMessageCount - 1)
    }

    CelesteBackdrop {
        CelesteActivityFrame(
            visible = activeTurn,
            moving = turnState == TurnState.Running,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(safeDrawingInsets.only(WindowInsetsSides.Horizontal))
                    .padding(top = headerTopPadding),
            ) {
                ConversationHeader(
                    title = summary.title.ifBlank { "Conversation" },
                    turnState = turnState,
                    onBack = onBack,
                )

                if (loadingMessage != null || errorMessage != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        loadingMessage?.let { StatusMessage(it, CelesteBlue, showSpinner = true) }
                        errorMessage?.let { StatusMessage(it, CelesteError) }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
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

                ConversationComposer(
                    draft = draft,
                    turnState = turnState,
                    onDraftChange = onDraftChange,
                    onSend = {
                        onSend()
                        focusManager.clearFocus()
                    },
                    onInterrupt = onInterrupt,
                    onReconnect = onReconnect,
                )
            }
        }
    }
}

@Composable
private fun ConversationHeader(
    title: String,
    turnState: TurnState,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text("‹", color = CelesteInk, fontSize = 26.sp, lineHeight = 26.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(turnStateColor(turnState), CircleShape),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = turnStateLabel(turnState),
                    color = if (turnState == TurnState.Running) CelesteBlue else CelesteMuted,
                    fontSize = 11.sp,
                )
            }
        }
        if (turnState == TurnState.Running || turnState == TurnState.Synchronizing) {
            Box(
                modifier = Modifier
                    .background(CelesteInk, RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = "BUSY ON",
                    color = CelestePanel,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                )
            }
        }
    }
}

@Composable
private fun ConversationComposer(
    draft: String,
    turnState: TurnState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onReconnect: () -> Unit,
) {
    val activeTurn = turnState == TurnState.Running || turnState == TurnState.Synchronizing

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        CelesteSurface(
            modifier = Modifier.fillMaxWidth(),
            tone = if (activeTurn) CelesteLightTone.Warm else CelesteLightTone.Cool,
            emphasized = activeTurn,
            shape = RoundedCornerShape(28.dp),
            contentPadding = PaddingValues(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = turnState == TurnState.Idle || turnState == TurnState.Reconnecting,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = when (turnState) {
                                TurnState.Idle -> "Message Celeste…"
                                TurnState.Running -> "Celeste is working…"
                                TurnState.Synchronizing -> "Synchronizing…"
                                TurnState.Reconnecting -> "Keep drafting while Celeste reconnects…"
                            },
                            color = CelesteMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    minLines = 1,
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (draft.isNotBlank() && turnState == TurnState.Idle) onSend()
                        },
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        cursorColor = CelesteBlue,
                    ),
                )
                Spacer(Modifier.width(4.dp))
                when (turnState) {
                    TurnState.Running -> OutlinedButton(
                        onClick = onInterrupt,
                        modifier = Modifier
                            .width(58.dp)
                            .height(46.dp),
                        shape = RoundedCornerShape(23.dp),
                        border = BorderStroke(1.dp, CelesteAmber),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteGoldText),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("Stop", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    TurnState.Reconnecting -> OutlinedButton(
                        onClick = onReconnect,
                        modifier = Modifier
                            .width(58.dp)
                            .height(46.dp),
                        shape = RoundedCornerShape(23.dp),
                        border = BorderStroke(1.dp, CelesteBlue),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteBlue),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("Retry", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    else -> Button(
                        onClick = onSend,
                        enabled = draft.isNotBlank() && turnState == TurnState.Idle,
                        modifier = Modifier.size(46.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CelesteInk,
                            contentColor = CelestePaper,
                            disabledContainerColor = CelesteHairline,
                            disabledContentColor = CelesteMuted,
                        ),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("↑", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

private fun turnStateLabel(turnState: TurnState): String = when (turnState) {
    TurnState.Idle -> "Connected"
    TurnState.Running -> "Thinking…"
    TurnState.Synchronizing -> "Synchronizing…"
    TurnState.Reconnecting -> "Reconnecting…"
}

private fun turnStateColor(turnState: TurnState): Color = when (turnState) {
    TurnState.Idle -> CelesteBlue
    TurnState.Running -> CelesteGoldText
    TurnState.Synchronizing -> CelesteBlue
    TurnState.Reconnecting -> CelesteError
}
