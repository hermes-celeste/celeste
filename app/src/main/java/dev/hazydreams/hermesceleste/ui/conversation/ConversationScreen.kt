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
import androidx.compose.foundation.layout.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.RuntimeControlsLifecycle
import dev.hazydreams.hermesceleste.RuntimeControlsOperation
import dev.hazydreams.hermesceleste.RuntimeControlsUiState
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

@Composable
internal fun ConversationScreen(
    summary: StoredSession,
    messages: List<ConversationMessage>,
    streamingText: String,
    draft: String,
    turnState: TurnState,
    runtimeControls: RuntimeControlsUiState,
    loadingMessage: String?,
    errorMessage: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onReconnect: () -> Unit,
    onRuntimeControlsOpen: () -> Unit,
    onRuntimeModelSelected: (String, String) -> Unit,
    onRuntimeReasoningSelected: (String) -> Unit,
    onRuntimeControlsApply: () -> Unit,
    onRuntimeControlsCancel: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val transcriptKeys = remember(messages) { transcriptItemKeys(messages) }
    val visibleMessageCount = messages.size + if (streamingText.isNotBlank()) 1 else 0
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
                RuntimeControlsPill(
                    state = runtimeControls,
                    onClick = onRuntimeControlsOpen,
                )
                runtimeControls.message?.let { message ->
                    Text(
                        text = message,
                        color = if (runtimeControls.operation == RuntimeControlsOperation.Idle) {
                            CelesteMuted
                        } else {
                            CelesteGoldText
                        },
                        fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .semantics { stateDescription = message },
                    )
                }
                Spacer(Modifier.height(8.dp))
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
                            color = CelesteMuted,
                        )
                    },
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (draft.isNotBlank() && turnState == TurnState.Idle) {
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
                        text = when (turnState) {
                            TurnState.Idle -> ""
                            TurnState.Running -> "Responding"
                            TurnState.Synchronizing -> "Synchronizing"
                            TurnState.Reconnecting -> "Draft saved here"
                        },
                        color = CelesteMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    when (turnState) {
                        TurnState.Running -> OutlinedButton(
                            onClick = onInterrupt,
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CelesteCoral),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteCoral),
                        ) { Text("Stop", fontWeight = FontWeight.SemiBold) }

                        TurnState.Reconnecting -> OutlinedButton(
                            onClick = onReconnect,
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CelesteBlue),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteBlue),
                        ) { Text("Retry", fontWeight = FontWeight.SemiBold) }

                        else -> Button(
                            onClick = {
                                onSend()
                                focusManager.clearFocus()
                            },
                            enabled = draft.isNotBlank() && turnState == TurnState.Idle,
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(23.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CelesteInk,
                                contentColor = CelestePaper,
                                disabledContainerColor = CelesteHairline,
                                disabledContentColor = CelesteMuted,
                            ),
                        ) { Text("Send  →", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        if (runtimeControls.pickerOpen) {
            ModalBottomSheet(
                onDismissRequest = onRuntimeControlsCancel,
                sheetState = rememberModalBottomSheetState(),
            ) {
                RuntimeControlsSheet(
                    state = runtimeControls,
                    onModelSelected = onRuntimeModelSelected,
                    onReasoningSelected = onRuntimeReasoningSelected,
                    onApply = onRuntimeControlsApply,
                    onCancel = onRuntimeControlsCancel,
                )
            }
        }
    }
}

@Composable
private fun RuntimeControlsPill(
    state: RuntimeControlsUiState,
    onClick: () -> Unit,
) {
    val enabled = state.snapshot != null &&
        state.lifecycle == RuntimeControlsLifecycle.Available &&
        state.operation == RuntimeControlsOperation.Idle
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = state.accessibilityLabel
                stateDescription = when {
                    state.lifecycle == RuntimeControlsLifecycle.Reconnecting -> "Reconnecting"
                    state.lifecycle == RuntimeControlsLifecycle.Synchronizing -> "Synchronizing"
                    state.operation == RuntimeControlsOperation.Applying -> "Applying"
                    state.operation == RuntimeControlsOperation.Queued -> "Queued for next response"
                    state.operation == RuntimeControlsOperation.Checking -> "Checking current setting"
                    state.snapshot?.capabilities?.available != true -> "Unavailable"
                    else -> "Available"
                }
            },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CelesteHairline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = CelesteInk,
            disabledContentColor = CelesteMuted,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${state.modelLabel}  ·  ${state.reasoningLabel}",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text("Change", color = CelesteBlue, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RuntimeControlsSheet(
    state: RuntimeControlsUiState,
    onModelSelected: (String, String) -> Unit,
    onReasoningSelected: (String) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val snapshot = state.snapshot
    val draft = state.draft
    val inputsEnabled = state.operation == RuntimeControlsOperation.Idle &&
        state.lifecycle == RuntimeControlsLifecycle.Available &&
        !state.optionsLoading
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Conversation settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Effective now",
            color = CelesteMuted,
            fontSize = 12.sp,
        )
        Text(
            text = "${state.modelLabel}  ·  ${state.reasoningLabel}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics {
                contentDescription = "Effective ${state.accessibilityLabel}"
            },
        )
        if (state.optionsLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 1.7.dp,
                    color = CelesteBlue,
                )
                Spacer(Modifier.size(8.dp))
                Text("Checking supported settings…", color = CelesteMuted, fontSize = 12.sp)
            }
        }
        state.message?.let { message ->
            Text(
                text = message,
                color = CelesteGoldText,
                modifier = Modifier.semantics { stateDescription = message },
            )
        }
        Text("Model", style = MaterialTheme.typography.titleSmall)
        val modelOptions = snapshot?.capabilities?.modelOptions.orEmpty()
        if (modelOptions.isEmpty()) {
            Text(
                "Unavailable on this gateway. The current effective value remains readable above.",
                color = CelesteMuted,
                fontSize = 12.sp,
            )
        } else {
            modelOptions.forEach { option ->
                val selected = draft?.let {
                    it.provider == option.provider && it.model == option.model
                } == true
                TextButton(
                    onClick = { onModelSelected(option.provider, option.model) },
                    enabled = inputsEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            role = Role.RadioButton
                            stateDescription = if (selected) "Selected" else "Not selected"
                            contentDescription = "${option.provider} ${option.model}"
                        },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = if (selected) "✓ ${option.provider} / ${option.model}" else {
                            "${option.provider} / ${option.model}"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (selected) CelesteInk else CelesteMuted,
                    )
                }
            }
        }
        Text("Reasoning", style = MaterialTheme.typography.titleSmall)
        val efforts = snapshot?.capabilities?.reasoningEfforts.orEmpty()
        if (efforts.isEmpty() || snapshot?.capabilities?.available != true) {
            Text(
                "Unavailable on this gateway.",
                color = CelesteMuted,
                fontSize = 12.sp,
            )
        } else {
            efforts.forEach { effort ->
                val selected = draft?.reasoningEffort.equals(effort, ignoreCase = true)
                TextButton(
                    onClick = { onReasoningSelected(effort) },
                    enabled = inputsEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            role = Role.RadioButton
                            stateDescription = if (selected) "Selected" else "Not selected"
                            contentDescription = "Reasoning $effort"
                        },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = if (selected) "✓ ${effort.replaceFirstChar(Char::uppercase)}" else {
                            effort.replaceFirstChar(Char::uppercase)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (selected) CelesteInk else CelesteMuted,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCancel,
                enabled = true,
            ) { Text("Cancel") }
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = onApply,
                enabled = state.canApply && state.operation == RuntimeControlsOperation.Idle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CelesteInk,
                    contentColor = CelestePaper,
                    disabledContainerColor = CelesteHairline,
                    disabledContentColor = CelesteMuted,
                ),
            ) {
                if (state.operation == RuntimeControlsOperation.Applying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 1.7.dp,
                        color = CelestePaper,
                    )
                } else {
                    Text("Apply")
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
