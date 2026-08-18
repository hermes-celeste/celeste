package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.TurnState
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteAccentContent
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelesteScreen
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceRaised
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary
import dev.hazydreams.hermesceleste.ui.StatusMessage
import kotlinx.coroutines.launch

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
    initiallyFollowLatest: Boolean = true,
    jumpToLatestVisibleOverride: Boolean? = null,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isReaderDragging = listState.interactionSource.collectIsDraggedAsState()
    var followLatest by remember(summary.id, initiallyFollowLatest) {
        mutableStateOf(initiallyFollowLatest)
    }
    val focusManager = LocalFocusManager.current
    val transcriptKeys = remember(messages) { transcriptItemKeys(messages) }
    val visibleMessageCount = messages.size + if (streamingText.isNotBlank()) 1 else 0
    val jumpToLatestVisible = remember(listState, visibleMessageCount) {
        derivedStateOf {
            shouldShowJumpToLatest(
                canScrollForward = listState.canScrollForward,
                visibleMessageCount = visibleMessageCount,
            )
        }
    }
    val safeDrawingInsets = WindowInsets.safeDrawing
    val headerTopPadding = maxOf(
        22.dp,
        safeDrawingInsets.asPaddingValues().calculateTopPadding() + 6.dp,
    )

    LaunchedEffect(listState, summary.id) {
        snapshotFlow {
            ScrollFollowObservation(
                readerDragging = isReaderDragging.value,
                scrolledBackward = listState.lastScrolledBackward,
                canScrollForward = listState.canScrollForward,
            )
        }.collect { observation ->
            followLatest = updatedFollowLatest(followLatest, observation)
        }
    }

    LaunchedEffect(visibleMessageCount, streamingText.length) {
        latestTranscriptIndex(visibleMessageCount)?.let { latestIndex ->
            if (followLatest) listState.animateScrollToLatest(latestIndex)
        }
    }

    CelesteScreen {
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
                    loadingMessage?.let { StatusMessage(it, CelesteAccent, showSpinner = true) }
                    errorMessage?.let { StatusMessage(it, CelesteError) }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
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
                        item(key = streamingTranscriptKey(summary.id)) {
                            MessageBubble(
                                ConversationMessage(role = "assistant", text = streamingText, pending = true),
                                streaming = true,
                            )
                        }
                    }
                }

                JumpToLatestButton(
                    visible = jumpToLatestVisibleOverride ?: jumpToLatestVisible.value,
                    onClick = {
                        followLatest = true
                        latestTranscriptIndex(visibleMessageCount)?.let { latestIndex ->
                            coroutineScope.launch { listState.animateScrollToLatest(latestIndex) }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            }

            ConversationComposer(
                draft = draft,
                turnState = turnState,
                onDraftChange = onDraftChange,
                onSend = {
                    followLatest = true
                    onSend()
                    focusManager.clearFocus()
                },
                onInterrupt = onInterrupt,
                onReconnect = onReconnect,
            )
        }
    }
}

internal fun latestTranscriptIndex(visibleMessageCount: Int): Int? =
    (visibleMessageCount - 1).takeIf { it >= 0 }

internal data class ScrollFollowObservation(
    val readerDragging: Boolean,
    val scrolledBackward: Boolean,
    val canScrollForward: Boolean,
)

internal fun updatedFollowLatest(
    current: Boolean,
    observation: ScrollFollowObservation,
): Boolean = when {
    observation.readerDragging && observation.scrolledBackward -> false
    !observation.canScrollForward -> true
    else -> current
}

internal fun remainingScrollToLatest(
    itemOffset: Int,
    itemSize: Int,
    viewportEndOffset: Int,
    afterContentPadding: Int,
): Int = (itemOffset + itemSize + afterContentPadding - viewportEndOffset).coerceAtLeast(0)

private suspend fun LazyListState.animateScrollToLatest(latestIndex: Int) {
    animateScrollToItem(latestIndex)
    val latestItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == latestIndex } ?: return
    val remaining = remainingScrollToLatest(
        itemOffset = latestItem.offset,
        itemSize = latestItem.size,
        viewportEndOffset = layoutInfo.viewportEndOffset,
        afterContentPadding = layoutInfo.afterContentPadding,
    )
    if (remaining > 0) animateScrollBy(remaining.toFloat())
}

internal fun shouldShowJumpToLatest(
    canScrollForward: Boolean,
    visibleMessageCount: Int,
): Boolean = canScrollForward && visibleMessageCount > 0

@Composable
private fun JumpToLatestButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (visible) {
        CelestePanel(
            modifier = modifier.size(48.dp),
            shape = CircleShape,
            containerColor = CelesteSurfaceRaised,
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    imageVector = JumpToLatestIcon,
                    contentDescription = "Jump to latest message",
                    modifier = Modifier.size(22.dp),
                    tint = CelesteTextMuted,
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
            Text("‹", color = CelesteTextPrimary, fontSize = 26.sp, lineHeight = 26.sp)
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
                    color = if (turnState == TurnState.Running) CelesteAccent else CelesteTextMuted,
                    fontSize = 11.sp,
                )
            }
        }
        if (turnState == TurnState.Running || turnState == TurnState.Synchronizing) {
            Box(
                modifier = Modifier
                    .background(CelesteTextPrimary, RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = "BUSY ON",
                    color = CelesteSurfacePrimary,
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
        CelestePanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            borderColor = if (activeTurn) CelesteAccent.copy(alpha = 0.55f) else CelesteHairline,
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
                                TurnState.Idle -> "Message Hermes…"
                                TurnState.Running -> "Hermes is responding…"
                                TurnState.Synchronizing -> "Synchronizing…"
                                TurnState.Reconnecting -> "Keep drafting while Hermes reconnects…"
                            },
                            color = CelesteTextMuted,
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
                        cursorColor = CelesteAccent,
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
                        border = BorderStroke(1.dp, CelesteHairline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteTextPrimary),
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
                        border = BorderStroke(1.dp, CelesteAccent),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteAccent),
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
                            containerColor = CelesteAccent,
                            contentColor = CelesteAccentContent,
                            disabledContainerColor = CelesteHairline,
                            disabledContentColor = CelesteTextMuted,
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
    TurnState.Idle -> CelesteAccent
    TurnState.Running -> CelesteAccent
    TurnState.Synchronizing -> CelesteAccent
    TurnState.Reconnecting -> CelesteError
}

private val JumpToLatestIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Jump to latest",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 4f)
            verticalLineTo(18f)
            moveTo(6.5f, 12.5f)
            lineTo(12f, 18f)
            lineTo(17.5f, 12.5f)
        }
    }.build()
}
