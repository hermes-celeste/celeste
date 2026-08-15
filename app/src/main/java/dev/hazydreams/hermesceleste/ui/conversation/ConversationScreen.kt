package dev.hazydreams.hermesceleste.ui.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val configuration = LocalConfiguration.current
    val safeDrawingInsets = WindowInsets.safeDrawing
    val safeDrawingPadding = safeDrawingInsets.asPaddingValues()
    val safeTopPadding = safeDrawingPadding.calculateTopPadding()
    val headerTopPadding = maxOf(34.dp - safeTopPadding, 10.dp)
    val bottomOcclusion = remember {
        WindowInsets.ime.union(WindowInsets.navigationBars)
    }
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var composerFocused by remember(summary.id) { mutableStateOf(false) }
    var dockHeightPx by remember(summary.id) { mutableIntStateOf(0) }

    // ConversationScreen owns the second Back. The first one is consumed here
    // whenever the IME or the composer still owns focus, before route navigation.
    BackHandler {
        if (imeVisible || composerFocused) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        } else {
            onBack()
        }
    }

    CelesteBackdrop(showOrnament = false) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(safeDrawingInsets.only(WindowInsetsSides.Horizontal))
                .windowInsetsPadding(safeDrawingInsets.only(WindowInsetsSides.Top))
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
                        Modifier.size(6.dp).background(
                            turnStateColor(turnState),
                            androidx.compose.foundation.shape.CircleShape,
                        ),
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

            Box(Modifier.weight(1f).fillMaxWidth()) {
                ConversationViewport(
                    summaryId = summary.id,
                    messages = messages,
                    streamingText = streamingText,
                    dockClearance = with(density) { dockHeightPx.toDp() },
                    safeTopInsetPx = safeDrawingInsets.getTop(density),
                    safeLeftInsetPx = safeDrawingInsets.getLeft(density, layoutDirection),
                    safeRightInsetPx = safeDrawingInsets.getRight(density, layoutDirection),
                    configurationWidthDp = configuration.screenWidthDp,
                    configurationHeightDp = configuration.screenHeightDp,
                    configurationOrientation = configuration.orientation,
                    configurationFontScale = configuration.fontScale,
                    modifier = Modifier.fillMaxSize(),
                )
                ComposerDock(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    bottomOcclusion = bottomOcclusion,
                    draft = draft,
                    turnState = turnState,
                    onDraftChange = onDraftChange,
                    onSend = onSend,
                    onInterrupt = onInterrupt,
                    onReconnect = onReconnect,
                    onFocusChanged = { composerFocused = it },
                    onMeasured = { measuredHeight ->
                        if (dockHeightPx != measuredHeight) dockHeightPx = measuredHeight
                    },
                )
            }
        }
    }
}

@Composable
private fun ConversationViewport(
    summaryId: String,
    messages: List<ConversationMessage>,
    streamingText: String,
    dockClearance: Dp,
    safeTopInsetPx: Int,
    safeLeftInsetPx: Int,
    safeRightInsetPx: Int,
    configurationWidthDp: Int,
    configurationHeightDp: Int,
    configurationOrientation: Int,
    configurationFontScale: Float,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val listState = remember(summaryId) { LazyListState() }
    val transcriptKeys = remember(messages) { transcriptItemKeys(messages) }
    val hasStreamingRow = streamingText.isNotBlank()
    val itemCount = messages.size + if (hasStreamingRow) 1 else 0
    val itemKeys = remember(transcriptKeys, hasStreamingRow) {
        if (hasStreamingRow) transcriptKeys + STREAMING_TRANSCRIPT_KEY else transcriptKeys
    }
    val policy = remember(summaryId) { ConversationScrollPolicy() }
    var followsLatest by remember(summaryId) { mutableStateOf(true) }
    var transitionGeneration by remember(summaryId) { mutableLongStateOf(0L) }
    var transitionActive by remember(summaryId) { mutableStateOf(false) }
    var initialProjectionHandled by remember(summaryId) { mutableStateOf(false) }
    var pendingAnchor by remember(summaryId) { mutableStateOf<TranscriptAnchor?>(null) }
    var lastHistoryAnchor by remember(summaryId) { mutableStateOf<TranscriptAnchor?>(null) }
    var recoveryNeeded by remember(summaryId) { mutableStateOf(false) }
    var programmaticScrollGeneration by remember(summaryId) { mutableStateOf<Long?>(null) }
    var activeScrollJob by remember(summaryId) { mutableStateOf<Job?>(null) }
    var previousGeometry by remember(summaryId) { mutableStateOf<ViewportGeometry?>(null) }
    val geometry = ViewportGeometry(
        dockHeightPx = with(density) { dockClearance.roundToPx() },
        bottomInsetPx = activeBottomOcclusionPx(
            imeBottomPx = WindowInsets.ime.getBottom(density),
            navigationBottomPx = WindowInsets.navigationBars.getBottom(density),
        ),
        safeTopInsetPx = safeTopInsetPx,
        safeLeftInsetPx = safeLeftInsetPx,
        safeRightInsetPx = safeRightInsetPx,
        configurationWidthDp = configurationWidthDp,
        configurationHeightDp = configurationHeightDp,
        configurationOrientation = configurationOrientation,
        configurationFontScale = configurationFontScale,
    )
    val terminalDistanceDp by remember(listState, itemCount, density) {
        derivedStateOf {
            terminalDistanceDp(listState.layoutInfo, itemCount, density)
        }
    }
    val showJumpToLatest = itemCount > 0 && !followsLatest &&
        (recoveryNeeded || !policy.isNearBottom(terminalDistanceDp))

    fun captureAnchor(): TranscriptAnchor? = captureTranscriptAnchor(listState)

    fun markHistoryReading() {
        activeScrollJob?.cancel()
        activeScrollJob = null
        val decision = policy.decide(
            ScrollPolicyInput(
                followsLatest = followsLatest,
                deliberateDragAway = true,
                pendingGeneration = transitionGeneration,
                transitionGeneration = transitionGeneration,
                hasItems = itemCount > 0,
            ),
        )
        if (itemCount > 0) {
            val anchor = captureAnchor()
            if (anchor != null) {
                lastHistoryAnchor = anchor
                pendingAnchor = anchor
            }
        }
        followsLatest = decision.followsLatest
        transitionGeneration = decision.transitionGeneration + 1
        transitionActive = false
        programmaticScrollGeneration = null
    }

    suspend fun scrollForGeneration(generation: Long, action: suspend () -> Unit) {
        if (generation != transitionGeneration) return
        programmaticScrollGeneration = generation
        try {
            action()
        } finally {
            if (programmaticScrollGeneration == generation) {
                programmaticScrollGeneration = null
            }
        }
    }

    fun jumpToLatest() {
        val decision = policy.decide(
            ScrollPolicyInput(
                followsLatest = followsLatest,
                explicitJumpToLatest = true,
                transitionGeneration = transitionGeneration,
                hasItems = itemCount > 0,
            ),
        )
        if (decision.command != ScrollCommand.JumpToLatest || itemCount == 0) return
        followsLatest = decision.followsLatest
        recoveryNeeded = false
        pendingAnchor = null
        transitionActive = false
        transitionGeneration = decision.transitionGeneration + 1
        val generation = transitionGeneration
        activeScrollJob?.cancel()
        activeScrollJob = scope.launch {
            scrollForGeneration(generation) {
                listState.animateScrollToItem(itemCount - 1)
            }
        }
    }

    LaunchedEffect(listState, summaryId, followsLatest) {
        snapshotFlow { captureTranscriptAnchor(listState) }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { anchor ->
                if (!followsLatest) lastHistoryAnchor = anchor
            }
    }

    LaunchedEffect(
        listState,
        summaryId,
        followsLatest,
        transitionGeneration,
        transitionActive,
        programmaticScrollGeneration,
    ) {
        var previousPosition: ScrollPosition? = null
        snapshotFlow {
            ScrollPosition(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                isScrollInProgress = listState.isScrollInProgress,
            )
        }.distinctUntilChanged().collect { position ->
            val movedTowardHistory = previousPosition?.let {
                position.isEarlierThan(it)
            } == true
            if (position.isScrollInProgress &&
                movedTowardHistory &&
                programmaticScrollGeneration == null
            ) {
                markHistoryReading()
            } else if (!position.isScrollInProgress &&
                previousPosition?.isScrollInProgress == true &&
                programmaticScrollGeneration == null
            ) {
                val decision = policy.decide(
                    ScrollPolicyInput(
                        followsLatest = followsLatest,
                        nearBottomDistanceDp = terminalDistanceDp,
                        imeOrInsetTransition = transitionActive,
                        transitionSettled = !transitionActive,
                        transitionGeneration = transitionGeneration,
                        hasItems = itemCount > 0,
                        allowRelatch = true,
                    ),
                )
                if (decision.command == ScrollCommand.RelatchLatest) {
                    followsLatest = decision.followsLatest
                    transitionGeneration = decision.transitionGeneration + 1
                }
            }
            previousPosition = position
        }
    }

    LaunchedEffect(itemCount, summaryId) {
        if (initialProjectionHandled || itemCount == 0) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.totalItemsCount == itemCount &&
                listState.layoutInfo.viewportEndOffset > listState.layoutInfo.viewportStartOffset
        }.filter { it }.first()
        if (initialProjectionHandled) return@LaunchedEffect
        val decision = policy.decide(
            ScrollPolicyInput(
                followsLatest = followsLatest,
                initialProjectionReady = true,
                transitionGeneration = transitionGeneration,
                hasItems = true,
            ),
        )
        initialProjectionHandled = true
        followsLatest = decision.followsLatest
        if (decision.command == ScrollCommand.FollowLatest) {
            val generation = decision.transitionGeneration
            scrollForGeneration(generation) {
                listState.scrollToItem(itemCount - 1)
            }
        }
    }

    // A single immediate settle handles append, streaming deltas, and terminal-row
    // growth without animating once per token or touching a history reader.
    LaunchedEffect(
        listState,
        summaryId,
        itemCount,
        followsLatest,
        transitionGeneration,
        transitionActive,
    ) {
        if (!followsLatest || itemCount == 0) return@LaunchedEffect
        snapshotFlow {
            terminalLayoutSignature(listState.layoutInfo, itemCount)
        }.distinctUntilChanged().collectLatest {
            if (transitionActive) return@collectLatest
            val decision = policy.decide(
                ScrollPolicyInput(
                    followsLatest = followsLatest,
                    contentChanged = true,
                    transitionGeneration = transitionGeneration,
                    hasItems = true,
                ),
            )
            if (decision.command != ScrollCommand.FollowLatest) return@collectLatest
            withFrameNanos { }
            val generation = transitionGeneration
            scrollForGeneration(generation) {
                listState.scrollToItem(itemCount - 1)
            }
        }
    }

    LaunchedEffect(geometry, summaryId) {
        val previous = previousGeometry
        previousGeometry = geometry
        if (previous == null || previous == geometry) return@LaunchedEffect

        val generation = transitionGeneration + 1
        activeScrollJob?.cancel()
        activeScrollJob = null
        transitionGeneration = generation
        transitionActive = true
        val shouldRestoreAnchor = !followsLatest
        if (shouldRestoreAnchor) {
            pendingAnchor = lastHistoryAnchor ?: captureAnchor()
        }

        // Two settled frames let the dock, IME, and list report their new bounds
        // before a follow or anchor command is issued.
        withFrameNanos { }
        withFrameNanos { }
        if (generation != transitionGeneration) return@LaunchedEffect

        val anchor = pendingAnchor
        val anchorCanBeRestored = anchor != null && (
            itemKeys.contains(anchor.key) || anchor.index in itemKeys.indices
        )
        val decision = policy.decide(
            ScrollPolicyInput(
                followsLatest = followsLatest,
                imeOrInsetTransition = true,
                restorationPending = shouldRestoreAnchor,
                anchorAvailable = anchorCanBeRestored,
                transitionSettled = true,
                transitionGeneration = generation,
                hasItems = itemCount > 0,
            ),
        )
        transitionActive = false
        when (decision.command) {
            ScrollCommand.FollowLatest -> {
                scrollForGeneration(generation) {
                    listState.scrollToItem(itemCount - 1)
                }
            }

            ScrollCommand.RestoreAnchor -> {
                scrollForGeneration(generation) {
                    val restored = restoreTranscriptAnchor(
                        listState = listState,
                        itemKeys = itemKeys,
                        anchor = anchor,
                    )
                    if (decision.fallbackToLatest || !restored) {
                        listState.scrollToItem(itemCount - 1)
                        recoveryNeeded = true
                    }
                }
                pendingAnchor = null
            }

            else -> Unit
        }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "Conversation transcript"
                },
            contentPadding = PaddingValues(
                horizontal = 24.dp,
                top = 26.dp,
                bottom = dockClearance + 26.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(25.dp),
        ) {
            itemsIndexed(
                items = messages,
                key = { index, _ -> transcriptKeys[index] },
            ) { _, message ->
                MessageBubble(message)
            }
            if (hasStreamingRow) {
                item(key = STREAMING_TRANSCRIPT_KEY) {
                    MessageBubble(
                        ConversationMessage(role = "assistant", text = streamingText, pending = true),
                    )
                }
            }
        }

        if (showJumpToLatest) {
            TextButton(
                onClick = ::jumpToLatest,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = dockClearance + 8.dp)
                    .semantics { contentDescription = "Jump to latest" },
            ) {
                Text("Jump to latest", color = CelesteBlue, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ComposerDock(
    modifier: Modifier,
    bottomOcclusion: WindowInsets,
    draft: String,
    turnState: TurnState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onReconnect: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onMeasured: (Int) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CelestePanel)
            .border(1.dp, CelesteHairline)
            .windowInsetsPadding(bottomOcclusion.only(WindowInsetsSides.Bottom))
            .onGloballyPositioned { coordinates -> onMeasured(coordinates.size.height) }
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { contentDescription = "Message composer" },
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = turnState == TurnState.Idle || turnState == TurnState.Reconnecting,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChanged(it.isFocused) },
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

private data class TranscriptAnchor(
    val key: String,
    val index: Int,
    val relativeOffsetPx: Int,
)

private data class ViewportGeometry(
    val dockHeightPx: Int,
    val bottomInsetPx: Int,
    val safeTopInsetPx: Int,
    val safeLeftInsetPx: Int,
    val safeRightInsetPx: Int,
    val configurationWidthDp: Int,
    val configurationHeightDp: Int,
    val configurationOrientation: Int,
    val configurationFontScale: Float,
)

private data class ScrollPosition(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    val isScrollInProgress: Boolean,
) {
    fun isEarlierThan(other: ScrollPosition): Boolean =
        firstVisibleItemIndex < other.firstVisibleItemIndex ||
            (firstVisibleItemIndex == other.firstVisibleItemIndex &&
                firstVisibleItemScrollOffset < other.firstVisibleItemScrollOffset)
}

private data class TerminalLayoutSignature(
    val totalItemsCount: Int,
    val terminalIndex: Int,
    val terminalOffset: Int,
    val terminalSize: Int,
    val viewportEndOffset: Int,
)

private fun captureTranscriptAnchor(listState: LazyListState): TranscriptAnchor? {
    val layoutInfo = listState.layoutInfo
    val item = layoutInfo.visibleItemsInfo.firstOrNull() ?: return null
    return TranscriptAnchor(
        key = item.key.toString(),
        index = item.index,
        relativeOffsetPx = item.offset - layoutInfo.viewportStartOffset,
    )
}

private suspend fun restoreTranscriptAnchor(
    listState: LazyListState,
    itemKeys: List<String>,
    anchor: TranscriptAnchor?,
): Boolean {
    if (anchor == null || itemKeys.isEmpty()) return false
    val exactIndex = itemKeys.indexOf(anchor.key)
    val targetIndex = if (exactIndex >= 0) {
        exactIndex
    } else {
        anchor.index.takeIf { it in itemKeys.indices } ?: return false
    }
    listState.scrollToItem(targetIndex)
    val restoredItem = listState.layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == targetIndex }
    if (restoredItem != null) {
        val currentOffset = restoredItem.offset - listState.layoutInfo.viewportStartOffset
        listState.scrollBy((currentOffset - anchor.relativeOffsetPx).toFloat())
    }
    return true
}

private fun terminalLayoutSignature(
    layoutInfo: LazyListLayoutInfo,
    itemCount: Int,
): TerminalLayoutSignature {
    val terminal = layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemCount - 1 }
    return TerminalLayoutSignature(
        totalItemsCount = layoutInfo.totalItemsCount,
        terminalIndex = terminal?.index ?: -1,
        terminalOffset = terminal?.offset ?: 0,
        terminalSize = terminal?.size ?: 0,
        viewportEndOffset = layoutInfo.viewportEndOffset,
    )
}

private fun terminalDistanceDp(
    layoutInfo: LazyListLayoutInfo,
    itemCount: Int,
    density: androidx.compose.ui.unit.Density,
): Float? {
    if (itemCount == 0) return null
    val terminal = layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemCount - 1 }
        ?: return null
    val distancePx = abs(layoutInfo.viewportEndOffset - (terminal.offset + terminal.size))
    return with(density) { distancePx.toDp().value }
}

private fun turnStateColor(turnState: TurnState): Color = when (turnState) {
    TurnState.Idle -> CelesteCoral
    TurnState.Running -> CelesteGoldText
    TurnState.Synchronizing, TurnState.Reconnecting -> CelesteBlue
}
