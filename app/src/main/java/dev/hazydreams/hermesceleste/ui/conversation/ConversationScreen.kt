package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import dev.hazydreams.hermesceleste.ui.CelesteBlue
import dev.hazydreams.hermesceleste.ui.CelesteCoral
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteGold
import dev.hazydreams.hermesceleste.ui.CelesteMuted
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelesteBackdrop
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
