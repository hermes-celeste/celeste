package dev.hazydreams.hermesceleste.ui.conversation

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.ComposerAttachment
import dev.hazydreams.hermesceleste.QueuedPrompt
import dev.hazydreams.hermesceleste.TurnState
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.TaskProgress
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteAccentContent
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelesteScreen
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceRaised
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary
import dev.hazydreams.hermesceleste.ui.CelesteWarning
import dev.hazydreams.hermesceleste.ui.StatusMessage
import kotlinx.coroutines.launch

@Composable
internal fun ConversationScreen(
    conversationKey: String,
    title: String,
    messages: List<ConversationMessage>,
    taskProgress: TaskProgress?,
    streamingText: String,
    draft: String,
    composerAttachments: List<ComposerAttachment> = emptyList(),
    queuedPrompts: List<QueuedPrompt> = emptyList(),
    isQueuePaused: Boolean = false,
    turnState: TurnState,
    isCompacting: Boolean,
    resumeExhausted: Boolean,
    loadingMessage: String?,
    errorMessage: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickImages: () -> Unit = {},
    onPickFiles: () -> Unit = {},
    onRemoveComposerAttachment: (String) -> Unit = {},
    onRemoveQueuedPrompt: (String) -> Unit = {},
    onResumeQueuedPrompts: () -> Unit = {},
    onInterrupt: () -> Unit,
    onRetryResume: () -> Unit,
    onOpenDrawer: () -> Unit,
    composerFocusRequest: Long? = null,
    onComposerFocusRequestHandled: (Long) -> Unit = {},
    initiallyFollowLatest: Boolean = true,
    initiallyExpandedUserMessageIds: Set<String> = emptySet(),
    jumpToLatestVisibleOverride: Boolean? = null,
    onClarificationRespond: (messageId: String, requestId: String, answer: String) -> Unit = { _, _, _ -> },
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isReaderDragging = listState.interactionSource.collectIsDraggedAsState()
    var followLatest by remember(conversationKey, initiallyFollowLatest) {
        mutableStateOf(initiallyFollowLatest)
    }
    var transcriptViewportHeight by remember(conversationKey) { mutableIntStateOf(0) }
    var viewportRefollowRequest by remember(conversationKey) { mutableIntStateOf(0) }
    var openedInspectionMessageId by remember(conversationKey) { mutableStateOf<String?>(null) }
    var openedComposerAttachment by remember(conversationKey) { mutableStateOf<ComposerAttachment?>(null) }
    var tasksSheetOpen by remember(conversationKey) { mutableStateOf(false) }
    var queueSheetOpen by remember(conversationKey) { mutableStateOf(false) }
    LaunchedEffect(taskProgress) {
        if (taskProgress == null) tasksSheetOpen = false
    }
    LaunchedEffect(queuedPrompts) {
        if (queuedPrompts.isEmpty()) queueSheetOpen = false
    }
    val openedInspectionMessage = openedInspectionMessageId?.let { id ->
        messages.firstOrNull { message ->
            message.id == id && (message.role == "steps" || message.role == "process" || message.role == "changes")
        }
    }
    val focusManager = LocalFocusManager.current
    val transcriptKeys = remember(messages) { transcriptItemKeys(messages) }
    val streamingInsertionIndex = remember(messages) { streamingTranscriptInsertionIndex(messages) }
    val messagesBeforeStreaming = remember(messages, streamingInsertionIndex) {
        messages.subList(0, streamingInsertionIndex)
    }
    val messagesAfterStreaming = remember(messages, streamingInsertionIndex) {
        messages.subList(streamingInsertionIndex, messages.size)
    }
    val pendingClarificationFollowKey = remember(messages) { pendingClarificationFollowKey(messages) }
    val visibleMessageCount = messages.size +
        (if (streamingText.isNotBlank()) 1 else 0) +
        (if (isCompacting) 1 else 0) +
        (if (resumeExhausted) 1 else 0)
    val jumpToLatestVisible = remember(listState, visibleMessageCount) {
        derivedStateOf {
            shouldShowJumpToLatest(
                canScrollForward = listState.canScrollForward,
                visibleMessageCount = visibleMessageCount,
                followLatest = followLatest,
            )
        }
    }
    val safeDrawingInsets = WindowInsets.safeDrawing
    val headerTopPadding = maxOf(
        22.dp,
        safeDrawingInsets.asPaddingValues().calculateTopPadding() + 6.dp,
    )

    LaunchedEffect(listState, conversationKey) {
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

    LaunchedEffect(
        visibleMessageCount,
        streamingText.length,
        pendingClarificationFollowKey,
        viewportRefollowRequest,
    ) {
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
                title = title.ifBlank { "Conversation" },
                turnState = turnState,
                onOpenDrawer = onOpenDrawer,
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
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        val previousHeight = transcriptViewportHeight
                        transcriptViewportHeight = size.height
                        if (
                            shouldRequestViewportRefollow(
                                followLatest = followLatest,
                                previousHeight = previousHeight,
                                newHeight = size.height,
                            )
                        ) {
                            viewportRefollowRequest += 1
                        }
                    },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(
                        items = messagesBeforeStreaming,
                        key = { index, _ -> transcriptKeys[index] },
                    ) { _, message ->
                        MessageBubble(
                            message = message,
                            initiallyExpandedUserMessage = message.id in initiallyExpandedUserMessageIds,
                            onOpenInspection = { openedInspectionMessageId = message.id },
                            onClarificationRespond = onClarificationRespond,
                        )
                    }
                    if (streamingText.isNotBlank()) {
                        item(key = streamingTranscriptKey(conversationKey)) {
                            MessageBubble(
                                ConversationMessage(role = "assistant", text = streamingText, pending = true),
                                streaming = true,
                            )
                        }
                    }
                    itemsIndexed(
                        items = messagesAfterStreaming,
                        key = { index, _ -> transcriptKeys[streamingInsertionIndex + index] },
                    ) { _, message ->
                        MessageBubble(
                            message = message,
                            initiallyExpandedUserMessage = message.id in initiallyExpandedUserMessageIds,
                            onOpenInspection = { openedInspectionMessageId = message.id },
                            onClarificationRespond = onClarificationRespond,
                        )
                    }
                    if (isCompacting) {
                        item(key = "compaction-status:$conversationKey") {
                            Box(
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                    stateDescription = "Summarizing conversation"
                                },
                            ) {
                                StatusMessage(
                                    message = "Summarizing conversation…",
                                    color = CelesteAccent,
                                    showSpinner = true,
                                )
                            }
                        }
                    }
                    if (resumeExhausted) {
                        item(key = "resume-exhausted:$conversationKey") {
                            ResumeExhaustedCard(onRetry = onRetryResume)
                        }
                    }
                }
                if (jumpToLatestVisibleOverride ?: jumpToLatestVisible.value) {
                    JumpToLatestButton(
                        visible = true,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp),
                        onClick = {
                            followLatest = true
                            latestTranscriptIndex(visibleMessageCount)?.let { latestIndex ->
                                coroutineScope.launch { listState.animateScrollToLatest(latestIndex) }
                            }
                        },
                    )
                }
            }

            ConversationComposer(
                draft = draft,
                attachments = composerAttachments,
                turnState = turnState,
                taskProgress = taskProgress,
                queuedPrompts = queuedPrompts,
                isQueuePaused = isQueuePaused,
                onDraftChange = onDraftChange,
                onSend = {
                    followLatest = true
                    onSend()
                    focusManager.clearFocus()
                },
                onInterrupt = onInterrupt,
                onPickImages = onPickImages,
                onPickFiles = onPickFiles,
                onOpenAttachment = { openedComposerAttachment = it },
                onRemoveAttachment = onRemoveComposerAttachment,
                onOpenTasks = {
                    focusManager.clearFocus()
                    tasksSheetOpen = true
                },
                onOpenQueue = {
                    focusManager.clearFocus()
                    queueSheetOpen = true
                },
                focusRequest = composerFocusRequest,
                onFocusRequestHandled = onComposerFocusRequestHandled,
            )
        }
    }

    openedInspectionMessage?.let { message ->
        ConversationInspectionSheet(
            message = message,
            onDismiss = { openedInspectionMessageId = null },
        )
    }
    openedComposerAttachment?.let { attachment ->
        ComposerAttachmentPreviewSheet(
            attachment = attachment,
            onDismiss = { openedComposerAttachment = null },
        )
    }
    if (tasksSheetOpen && taskProgress != null) {
        TaskProgressInspectionSheet(
            progress = taskProgress,
            onDismiss = { tasksSheetOpen = false },
        )
    }
    if (queueSheetOpen && queuedPrompts.isNotEmpty()) {
        QueuedPromptsInspectionSheet(
            prompts = queuedPrompts,
            paused = isQueuePaused,
            onResume = onResumeQueuedPrompts,
            onRemove = onRemoveQueuedPrompt,
            onDismiss = { queueSheetOpen = false },
        )
    }
}

@Composable
private fun ResumeExhaustedCard(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CelesteSurfaceRaised)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Couldn’t load this conversation",
            color = CelesteTextPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Hermes couldn’t restore this conversation after several attempts. Check the connection, then try again.",
            color = CelesteTextMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(23.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CelesteAccent,
                contentColor = CelesteAccentContent,
            ),
        ) {
            Text("Retry", fontWeight = FontWeight.SemiBold)
        }
    }
}

internal fun latestTranscriptIndex(visibleMessageCount: Int): Int? =
    (visibleMessageCount - 1).takeIf { it >= 0 }

internal fun streamingTranscriptInsertionIndex(messages: List<ConversationMessage>): Int =
    messages.lastIndex.takeIf { index -> index >= 0 && messages[index].role == "changes" }
        ?: messages.size

internal fun pendingClarificationFollowKey(messages: List<ConversationMessage>): String? =
    messages.lastOrNull { message ->
        message.role == "clarification" &&
            message.pending &&
            message.clarification?.requestId?.isNotBlank() == true
    }?.clarification?.requestId

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

internal fun shouldRequestViewportRefollow(
    followLatest: Boolean,
    previousHeight: Int,
    newHeight: Int,
): Boolean = followLatest && previousHeight > 0 && newHeight > 0 && previousHeight != newHeight

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
    followLatest: Boolean,
): Boolean = !followLatest && canScrollForward && visibleMessageCount > 0

@Composable
private fun JumpToLatestButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (visible) {
        CelestePanel(
            modifier = modifier.size(CONVERSATION_CONTROL_SIZE),
            shape = CircleShape,
            containerColor = CelesteSurfaceRaised,
            borderColor = Color.Transparent,
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    imageVector = JumpToLatestIcon,
                    contentDescription = "Jump to latest message",
                    modifier = Modifier.size(CONVERSATION_ICON_SIZE),
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
    onOpenDrawer: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { stateDescription = turnStateAccessibilityLabel(turnState) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CelestePanel(
            modifier = Modifier.size(CONVERSATION_CONTROL_SIZE),
            shape = CircleShape,
            containerColor = CelesteSurfacePrimary,
            borderColor = Color.Transparent,
        ) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    imageVector = NavigationDrawerIcon,
                    contentDescription = "Open conversations",
                    modifier = Modifier.size(CONVERSATION_ICON_SIZE),
                    tint = CelesteTextPrimary,
                )
            }
        }
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(start = 13.dp, end = 10.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (turnState != TurnState.Idle) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(turnStateColor(turnState), CircleShape),
            )
        }
    }
}

@Composable
private fun ConversationComposer(
    draft: String,
    attachments: List<ComposerAttachment>,
    turnState: TurnState,
    taskProgress: TaskProgress?,
    queuedPrompts: List<QueuedPrompt>,
    isQueuePaused: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onOpenAttachment: (ComposerAttachment) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenQueue: () -> Unit,
    focusRequest: Long?,
    onFocusRequestHandled: (Long) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var attachmentMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(focusRequest, turnState) {
        val requestId = focusRequest ?: return@LaunchedEffect
        if (turnState != TurnState.Idle) return@LaunchedEffect
        focusRequester.requestFocus()
        keyboardController?.show()
        onFocusRequestHandled(requestId)
    }
    val canSubmitDraft = draft.isNotBlank() || attachments.isNotEmpty()
    val shouldQueueDraft = canSubmitDraft && (
        turnState == TurnState.Running ||
            turnState == TurnState.Reconnecting ||
            queuedPrompts.isNotEmpty()
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (taskProgress != null || queuedPrompts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                taskProgress?.let { progress ->
                    TaskProgressPill(
                        progress = progress,
                        onOpen = onOpenTasks,
                    )
                }
                if (queuedPrompts.isNotEmpty()) {
                    QueuedPromptsPill(
                        prompts = queuedPrompts,
                        paused = isQueuePaused,
                        onOpen = onOpenQueue,
                    )
                }
            }
        }
        CelestePanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            containerColor = CelesteSurfaceRaised,
            borderColor = Color.Transparent,
            contentPadding = PaddingValues(2.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (attachments.isNotEmpty()) {
                    ComposerAttachmentStrip(
                        attachments = attachments,
                        onOpen = onOpenAttachment,
                        onRemove = onRemoveAttachment,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        IconButton(
                            onClick = { attachmentMenuExpanded = true },
                            enabled = turnState != TurnState.Synchronizing,
                            modifier = Modifier.size(CONVERSATION_CONTROL_SIZE),
                        ) {
                            Icon(
                                imageVector = AddAttachmentIcon,
                                contentDescription = "Add attachment",
                                modifier = Modifier.size(CONVERSATION_ICON_SIZE),
                                tint = CelesteTextMuted,
                            )
                        }
                        DropdownMenu(
                            expanded = attachmentMenuExpanded,
                            onDismissRequest = { attachmentMenuExpanded = false },
                            containerColor = CelesteSurfaceRaised,
                        ) {
                            DropdownMenuItem(
                                text = { Text("Photos") },
                                onClick = {
                                    attachmentMenuExpanded = false
                                    onPickImages()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Files") },
                                onClick = {
                                    attachmentMenuExpanded = false
                                    onPickFiles()
                                },
                            )
                        }
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        enabled = turnState != TurnState.Synchronizing,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        minLines = 1,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (canSubmitDraft && turnState != TurnState.Synchronizing) onSend()
                            },
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = CelesteTextPrimary),
                        cursorBrush = SolidColor(CelesteAccent),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (draft.isEmpty()) {
                                    Text(
                                        text = when (turnState) {
                                            TurnState.Idle -> "Message Hermes…"
                                            TurnState.Running -> "Message Hermes…"
                                            TurnState.Synchronizing -> "Synchronizing…"
                                            TurnState.Reconnecting -> "Message Hermes…"
                                        },
                                        color = CelesteTextMuted,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    when {
                        turnState == TurnState.Running && shouldQueueDraft -> {
                            ComposerTextAction(
                                label = "Stop",
                                onClick = onInterrupt,
                                containerColor = CelesteSurfacePrimary,
                                contentColor = CelesteTextPrimary,
                                width = 48.dp,
                            )
                            Spacer(Modifier.width(4.dp))
                            ComposerTextAction(
                                label = "Queue",
                                onClick = onSend,
                                containerColor = CelesteAccent,
                                contentColor = CelesteAccentContent,
                                width = 56.dp,
                            )
                        }

                        turnState == TurnState.Running -> ComposerTextAction(
                            label = "Stop",
                            onClick = onInterrupt,
                            containerColor = CelesteSurfacePrimary,
                            contentColor = CelesteTextPrimary,
                            width = 54.dp,
                        )

                        shouldQueueDraft -> ComposerTextAction(
                            label = "Queue",
                            onClick = onSend,
                            containerColor = CelesteAccent,
                            contentColor = CelesteAccentContent,
                            width = 58.dp,
                        )

                        else -> Button(
                            onClick = onSend,
                            enabled = canSubmitDraft && turnState == TurnState.Idle,
                            modifier = Modifier.size(CONVERSATION_CONTROL_SIZE),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CelesteAccent,
                                contentColor = CelesteAccentContent,
                                disabledContainerColor = CelesteSurfacePrimary,
                                disabledContentColor = CelesteTextMuted,
                            ),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Icon(
                                imageVector = SendMessageIcon,
                                contentDescription = "Send message",
                                modifier = Modifier.size(CONVERSATION_ICON_SIZE),
                            )
                        }
                    }
                    Spacer(Modifier.width(2.dp))
                }
            }
        }
    }
}

@Composable
private fun ComposerTextAction(
    label: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    width: Dp,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(width)
            .height(CONVERSATION_CONTROL_SIZE),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val CONVERSATION_CONTROL_SIZE = 40.dp
private val CONVERSATION_ICON_SIZE = 18.dp

private fun turnStateAccessibilityLabel(turnState: TurnState): String = when (turnState) {
    TurnState.Idle -> "Connected"
    TurnState.Running -> "Hermes is responding"
    TurnState.Synchronizing -> "Conversation is synchronizing"
    TurnState.Reconnecting -> "Hermes is reconnecting"
}

private fun turnStateColor(turnState: TurnState): Color = when (turnState) {
    TurnState.Idle -> CelesteAccent
    TurnState.Running -> CelesteAccent
    TurnState.Synchronizing -> CelesteAccent
    TurnState.Reconnecting -> CelesteWarning
}

private val NavigationDrawerIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Conversations",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(5f, 7f)
            horizontalLineTo(19f)
            moveTo(5f, 12f)
            horizontalLineTo(19f)
            moveTo(5f, 17f)
            horizontalLineTo(15f)
        }
    }.build()
}

private val AddAttachmentIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Add attachment",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(12f, 5f)
            verticalLineTo(19f)
            moveTo(5f, 12f)
            horizontalLineTo(19f)
        }
    }.build()
}

private val SendMessageIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Send message",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 18f)
            verticalLineTo(6f)
            moveTo(7f, 11f)
            lineTo(12f, 6f)
            lineTo(17f, 11f)
        }
    }.build()
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
