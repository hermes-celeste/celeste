package dev.hazydreams.hermesceleste.ui.conversation

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.onDispose
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val GEOMETRY_RETRY_DELAY_MS = 96L
private const val MAX_RETAINED_ANCHOR_KEY_LENGTH = 256

@Composable
internal fun conversationBottomOcclusionInsets(): WindowInsets =
    WindowInsets.ime
        .union(WindowInsets.navigationBars)
        .union(WindowInsets.systemGestures)

@OptIn(ExperimentalLayoutApi::class)
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
    projectionGeneration: Long = 0L,
    bottomOcclusion: WindowInsets? = null,
    safeDrawingInsets: WindowInsets? = null,
    bottomOcclusionSettled: Boolean? = null,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val configuration = LocalConfiguration.current
    val activeBottomOcclusion = bottomOcclusion ?: conversationBottomOcclusionInsets()
    val activeSafeDrawingInsets = safeDrawingInsets ?: WindowInsets.safeDrawing
    val activeBottomOcclusionSettled = bottomOcclusionSettled ?: if (bottomOcclusion == null) {
        val currentImeBottom = WindowInsets.ime.getBottom(density)
        val sourceImeBottom = WindowInsets.imeAnimationSource.getBottom(density)
        val targetImeBottom = WindowInsets.imeAnimationTarget.getBottom(density)
        currentImeBottom == sourceImeBottom && currentImeBottom == targetImeBottom
    } else {
        // Explicit inset seams are deterministic fixtures rather than the
        // platform animation stream, so their caller owns the settled bit.
        true
    }
    val safeDrawingPadding = activeSafeDrawingInsets.asPaddingValues()
    val safeTopPadding = safeDrawingPadding.calculateTopPadding()
    val headerTopPadding = maxOf(34.dp - safeTopPadding, 10.dp)
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var composerFocused by remember(summary.id) { mutableStateOf(false) }
    var previousImeVisible by remember(summary.id) { mutableStateOf(false) }
    var dockHeightPx by remember(summary.id) { mutableIntStateOf(0) }

    LaunchedEffect(summary.id, imeVisible, composerFocused) {
        // Some Android versions let the IME consume Back without clearing the
        // focused field. Clear that stale focus as soon as the IME actually
        // reports hidden, so the next Back can leave the conversation.
        if (previousImeVisible && !imeVisible && composerFocused) {
            focusManager.clearFocus(force = true)
        }
        previousImeVisible = imeVisible
    }

    fun handleConversationBack() {
        if (imeVisible || composerFocused) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        } else {
            onBack()
        }
    }

    // ConversationScreen owns the second Back. The first one is consumed here
    // whenever the IME or the composer still owns focus, before route navigation.
    BackHandler { handleConversationBack() }

    CelesteBackdrop(showOrnament = false) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(activeSafeDrawingInsets.only(WindowInsetsSides.Horizontal))
                .windowInsetsPadding(activeSafeDrawingInsets.only(WindowInsetsSides.Top))
                .padding(top = headerTopPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = ::handleConversationBack,
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = when (turnState) {
                                TurnState.Idle -> "Connected"
                                TurnState.Running -> "Responding"
                                TurnState.Synchronizing -> "Synchronizing"
                                TurnState.Reconnecting -> "Reconnecting"
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                    errorMessage?.let {
                        StatusMessage(
                            message = it,
                            color = CelesteError,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                                stateDescription = "Terminal error"
                            },
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                ConversationViewport(
                    summaryId = summary.id,
                    messages = messages,
                    streamingText = streamingText,
                    projectionGeneration = projectionGeneration,
                    bottomOcclusion = activeBottomOcclusion,
                    bottomInsetsSettled = activeBottomOcclusionSettled,
                    dockClearance = with(density) { dockHeightPx.toDp() },
                    safeTopInsetPx = activeSafeDrawingInsets.getTop(density),
                    safeLeftInsetPx = activeSafeDrawingInsets.getLeft(density, layoutDirection),
                    safeRightInsetPx = activeSafeDrawingInsets.getRight(density, layoutDirection),
                    configurationWidthDp = configuration.screenWidthDp,
                    configurationHeightDp = configuration.screenHeightDp,
                    configurationOrientation = configuration.orientation,
                    configurationFontScale = configuration.fontScale,
                    modifier = Modifier.fillMaxSize(),
                )
                ComposerDock(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    bottomOcclusion = activeBottomOcclusion,
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

private class RetainedConversationViewportState {
    // `retain` survives configuration recreation without entering SavedStateRegistry. A process
    // restart therefore starts with a fresh latest policy and never restores a server row key.
    // Only the list position, follow policy, and one bounded in-memory anchor cross rotation;
    // transient jobs and transition guards are rebuilt after the new geometry settles.
    val listState = LazyListState()
    val followsLatest = mutableStateOf(true)
    val lastHistoryAnchor = mutableStateOf<TranscriptAnchor?>(null)
}

@Composable
private fun ConversationViewport(
    summaryId: String,
    messages: List<ConversationMessage>,
    streamingText: String,
    projectionGeneration: Long,
    bottomOcclusion: WindowInsets,
    bottomInsetsSettled: Boolean,
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
    val retainedViewportState = retain(summaryId) { RetainedConversationViewportState() }
    val listState = retainedViewportState.listState
    var followsLatest by retainedViewportState.followsLatest
    val transcriptKeys = remember(messages) { transcriptItemKeys(messages) }
    val hasStreamingRow = streamingText.isNotBlank()
    val itemCount = messages.size + if (hasStreamingRow) 1 else 0
    val itemKeys = remember(transcriptKeys, hasStreamingRow) {
        if (hasStreamingRow) transcriptKeys + STREAMING_TRANSCRIPT_KEY else transcriptKeys
    }
    val policy = remember(summaryId) { ConversationScrollPolicy() }
    var transitionGeneration by remember(summaryId) { mutableLongStateOf(0L) }
    var transitionActive by remember(summaryId) { mutableStateOf(false) }
    var initialProjectionHandled by remember(summaryId) { mutableStateOf(false) }
    var pendingAnchor by remember(summaryId) { mutableStateOf<TranscriptAnchor?>(null) }
    var lastHistoryAnchor by retainedViewportState.lastHistoryAnchor
    var recoveryNeeded by remember(summaryId) { mutableStateOf(false) }
    var programmaticScrollGeneration by remember(summaryId) { mutableStateOf<Long?>(null) }
    var activeScrollJob by remember(summaryId) { mutableStateOf<Job?>(null) }
    var previousGeometry by remember(summaryId) { mutableStateOf<ViewportGeometry?>(null) }
    var previousItemKeys by remember(summaryId) { mutableStateOf<List<String>?>(null) }
    var previousProjectionGeneration by remember(summaryId) { mutableStateOf<Long?>(null) }
    var geometryRetryTick by remember(summaryId) { mutableLongStateOf(0L) }

    // Explicit scroll jobs are composition-scoped. The retained state only carries settled UI
    // policy and the list position across configuration recreation.
    DisposableEffect(summaryId, retainedViewportState) {
        onDispose {
            activeScrollJob?.cancel()
            activeScrollJob = null
            transitionActive = false
            pendingAnchor = null
            programmaticScrollGeneration = null
        }
    }

    val geometry = ViewportGeometry(
        dockHeightPx = with(density) { dockClearance.roundToPx() },
        bottomInsetPx = bottomOcclusion.getBottom(density),
        bottomInsetsSettled = bottomInsetsSettled,
        safeTopInsetPx = safeTopInsetPx,
        safeLeftInsetPx = safeLeftInsetPx,
        safeRightInsetPx = safeRightInsetPx,
        configurationWidthDp = configurationWidthDp,
        configurationHeightDp = configurationHeightDp,
        configurationOrientation = configurationOrientation,
        configurationFontScale = configurationFontScale,
    )
    val terminalDistanceDp by remember(listState, itemCount, density, geometry.dockHeightPx) {
        derivedStateOf {
            terminalDistanceDp(
                layoutInfo = listState.layoutInfo,
                itemCount = itemCount,
                density = density,
                dockHeightPx = geometry.dockHeightPx,
            )
        }
    }
    val showJumpToLatest = itemCount > 0 && !followsLatest &&
        (recoveryNeeded || !policy.isNearBottom(terminalDistanceDp))

    fun captureAnchor(): TranscriptAnchor? = captureTranscriptAnchor(listState)

    fun markHistoryReading() {
        activeScrollJob?.cancel()
        activeScrollJob = null
        // Deliberate drag/accessibility scroll owns the viewport immediately.
        // Mark the initial settle handled before it can resume after geometry
        // becomes usable, then advance the generation to invalidate its work.
        initialProjectionHandled = true
        val decision = policy.decide(
            ScrollPolicyInput(
                followsLatest = followsLatest,
                deliberateDragAway = true,
                pendingGeneration = programmaticScrollGeneration ?: transitionGeneration,
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
        transitionGeneration = decision.transitionGeneration
        transitionActive = false
        programmaticScrollGeneration = null
    }

    suspend fun scrollForGeneration(generation: Long, action: suspend () -> Unit): Boolean {
        if (generation != transitionGeneration) return false
        programmaticScrollGeneration = generation
        try {
            action()
            return generation == transitionGeneration &&
                programmaticScrollGeneration == generation
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
                if (ValueAnimator.areAnimatorsEnabled()) {
                    listState.animateScrollToItem(itemCount - 1)
                } else {
                    listState.scrollToItem(itemCount - 1)
                }
                settleTerminalRow(
                    listState = listState,
                    itemCount = itemCount,
                    dockHeightPx = geometry.dockHeightPx,
                )
            }
        }
    }

    LaunchedEffect(listState, summaryId, itemCount) {
        var previousPosition: ScrollPosition? = null
        snapshotFlow {
            ScrollPosition(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                isScrollInProgress = listState.isScrollInProgress,
                programmaticScrollGeneration = programmaticScrollGeneration,
            )
        }.distinctUntilChanged().collect { position ->
            val movedTowardHistory = previousPosition?.let {
                position.isEarlierThan(it)
            } == true
            val positionChanged = previousPosition?.let {
                position.firstVisibleItemIndex != it.firstVisibleItemIndex ||
                    position.firstVisibleItemScrollOffset != it.firstVisibleItemScrollOffset
            } == true
            val deliberateScrollBeforeInitialSettle = !initialProjectionHandled &&
                previousPosition != null &&
                positionChanged
            val userMovedAwayFromLatest = movedTowardHistory || (
                deliberateScrollBeforeInitialSettle &&
                    programmaticScrollGeneration == null
            )
            val interruptedProgrammaticScroll =
                (position.programmaticScrollGeneration != null ||
                    previousPosition?.programmaticScrollGeneration != null) &&
                    movedTowardHistory
            if (interruptedProgrammaticScroll ||
                (position.isScrollInProgress && userMovedAwayFromLatest)
            ) {
                // An accessibility scroll-to-index and a drag can cancel an
                // active animateScrollToItem between two snapshots. Treat the
                // historyward movement as input even while that generation is
                // still published, rather than allowing its completion to
                // relatch latest.
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
                } else if (!followsLatest) {
                    // Capture the live anchor once the gesture settles, rather than writing
                    // pixel offsets into state for every scroll sample.
                    captureAnchor()?.let { lastHistoryAnchor = it }
                }
            }
            previousPosition = position
        }
    }

    LaunchedEffect(
        itemCount,
        summaryId,
        geometry,
        projectionGeneration,
        geometryRetryTick,
        transitionGeneration,
    ) {
        if (initialProjectionHandled || itemCount == 0) return@LaunchedEffect
        val settlingGeneration = transitionGeneration
        val restoringHistory = !followsLatest
        val anchor = pendingAnchor ?: lastHistoryAnchor
        val anchorCanBeRestored = anchor != null && (
            (anchor.key.isNotEmpty() && itemKeys.contains(anchor.key)) ||
                anchor.index in itemKeys.indices
        )
        val settlement = settleViewportTransition(
            listState = listState,
            itemCount = itemCount,
            itemKeys = itemKeys,
            geometry = geometry,
            bottomOcclusion = bottomOcclusion,
            density = density,
            policy = policy,
            input = ScrollPolicyInput(
                followsLatest = followsLatest,
                restorationPending = restoringHistory,
                anchorAvailable = anchorCanBeRestored,
                initialProjectionReady = !restoringHistory,
                transitionGeneration = settlingGeneration,
                pendingGeneration = settlingGeneration,
                hasItems = true,
            ),
            anchor = anchor,
            withGeneration = { generation, action ->
                scrollForGeneration(generation, action)
            },
        )
        if (settlement.pendingGeometry) {
            delay(GEOMETRY_RETRY_DELAY_MS)
            if (settlingGeneration == transitionGeneration) geometryRetryTick += 1
            return@LaunchedEffect
        }
        if (initialProjectionHandled || settlingGeneration != transitionGeneration) {
            return@LaunchedEffect
        }
        val decision = settlement.decision ?: return@LaunchedEffect
        if (!settlement.completed || decision.command == ScrollCommand.CancelPendingFollow) {
            return@LaunchedEffect
        }
        initialProjectionHandled = true
        followsLatest = decision.followsLatest
        if (settlement.recoveryNeeded) recoveryNeeded = true
        if (decision.command == ScrollCommand.RestoreAnchor) pendingAnchor = null
    }

    // A single immediate settle handles append, streaming deltas, and terminal-row
    // growth without animating once per token or touching a history reader.
    LaunchedEffect(
        listState,
        summaryId,
        itemCount,
        followsLatest,
        initialProjectionHandled,
        transitionGeneration,
        transitionActive,
        projectionGeneration,
    ) {
        if (!followsLatest || !initialProjectionHandled || itemCount == 0) {
            return@LaunchedEffect
        }
        snapshotFlow {
            terminalLayoutSignature(listState.layoutInfo, itemCount)
        }.distinctUntilChanged().collectLatest {
            if (transitionActive) return@collectLatest
            if (!hasUsableViewportGeometry(
                    dockHeightPx = geometry.dockHeightPx,
                    viewportStartOffsetPx = listState.layoutInfo.viewportStartOffset,
                    viewportEndOffsetPx = listState.layoutInfo.viewportEndOffset,
                )
            ) return@collectLatest
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
                settleTerminalRow(
                    listState = listState,
                    itemCount = itemCount,
                    dockHeightPx = geometry.dockHeightPx,
                )
            }
        }
    }

    LaunchedEffect(geometry, itemKeys, summaryId, projectionGeneration, geometryRetryTick) {
        val previous = previousGeometry
        val previousKeys = previousItemKeys
        val previousProjection = previousProjectionGeneration
        previousGeometry = geometry
        previousItemKeys = itemKeys
        previousProjectionGeneration = projectionGeneration
        val geometryChanged = previous != null && previous != geometry
        val projectionChanged = previousKeys != null && previousKeys != itemKeys
        val generationChanged = previousProjection != null && previousProjection != projectionGeneration
        val retryingPendingGeometry = transitionActive &&
            !geometryChanged && !projectionChanged && !generationChanged
        if (!geometryChanged && !projectionChanged && !generationChanged && !retryingPendingGeometry) {
            return@LaunchedEffect
        }

        val generation = if (retryingPendingGeometry) {
            transitionGeneration
        } else {
            transitionGeneration + 1
        }
        activeScrollJob?.cancel()
        activeScrollJob = null
        transitionGeneration = generation
        transitionActive = true
        if (itemCount == 0) {
            pendingAnchor = null
            lastHistoryAnchor = null
            recoveryNeeded = false
            transitionActive = false
            return@LaunchedEffect
        }
        val shouldRestoreAnchor = !followsLatest
        if (shouldRestoreAnchor) {
            pendingAnchor = lastHistoryAnchor ?: captureAnchor()
        }

        // Keep this effect pending until two identical usable samples also
        // report settled inset state. A zero/stale inset or cancelled IME
        // animation must retry from a later sample, not clear the guard after
        // a fixed frame budget and lose the pending restoration.
        val anchor = pendingAnchor
        val anchorCanBeRestored = anchor != null && (
            (anchor.key.isNotEmpty() && itemKeys.contains(anchor.key)) ||
                anchor.index in itemKeys.indices
        )
        val settlement = settleViewportTransition(
            listState = listState,
            itemCount = itemCount,
            itemKeys = itemKeys,
            geometry = geometry,
            bottomOcclusion = bottomOcclusion,
            density = density,
            policy = policy,
            input = ScrollPolicyInput(
                followsLatest = followsLatest,
                imeOrInsetTransition = true,
                restorationPending = shouldRestoreAnchor,
                anchorAvailable = anchorCanBeRestored,
                transitionSettled = true,
                transitionGeneration = generation,
                hasItems = true,
            ),
            anchor = anchor,
            withGeneration = { actionGeneration, action ->
                scrollForGeneration(actionGeneration, action)
            },
        )
        if (settlement.pendingGeometry) {
            delay(GEOMETRY_RETRY_DELAY_MS)
            if (generation == transitionGeneration) geometryRetryTick += 1
            return@LaunchedEffect
        }
        if (generation != transitionGeneration) return@LaunchedEffect
        val decision = settlement.decision ?: return@LaunchedEffect
        if (!settlement.completed) return@LaunchedEffect
        transitionActive = false
        if (settlement.recoveryNeeded) recoveryNeeded = true
        if (decision.command == ScrollCommand.RestoreAnchor) pendingAnchor = null
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
            ) { index, message ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            if (!hasStreamingRow && index == itemCount - 1) {
                                contentDescription = "Terminal transcript row"
                            }
                        },
                ) {
                    MessageBubble(message)
                }
            }
            if (hasStreamingRow) {
                item(key = STREAMING_TRANSCRIPT_KEY) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Terminal transcript row" },
                    ) {
                        MessageBubble(
                            ConversationMessage(role = "assistant", text = streamingText, pending = true),
                        )
                    }
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
                    modifier = Modifier.defaultMinSize(minHeight = 46.dp),
                    shape = RoundedCornerShape(23.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CelesteCoral),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteCoral),
                ) { Text("Stop", fontWeight = FontWeight.SemiBold) }

                TurnState.Reconnecting -> OutlinedButton(
                    onClick = onReconnect,
                    modifier = Modifier.defaultMinSize(minHeight = 46.dp),
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
                    modifier = Modifier.defaultMinSize(minHeight = 46.dp),
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
    val bottomInsetsSettled: Boolean,
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
    val programmaticScrollGeneration: Long? = null,
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
    val anchorKey = item.key.toString()
    return TranscriptAnchor(
        key = anchorKey.takeIf { it.length <= MAX_RETAINED_ANCHOR_KEY_LENGTH } ?: "",
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
    val exactIndex = anchor.key.takeIf { it.isNotEmpty() }?.let(itemKeys::indexOf) ?: -1
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

private data class ViewportSettlement(
    val pendingGeometry: Boolean,
    val decision: ScrollPolicyDecision? = null,
    val recoveryNeeded: Boolean = false,
    val completed: Boolean = false,
)

/**
 * The single settled-transition owner for initial projection, inset changes,
 * composer growth, reconnect projection, and configuration changes. Geometry
 * must settle before the policy is allowed to issue a scroll command.
 */
private suspend fun settleViewportTransition(
    listState: LazyListState,
    itemCount: Int,
    itemKeys: List<String>,
    geometry: ViewportGeometry,
    bottomOcclusion: WindowInsets,
    density: androidx.compose.ui.unit.Density,
    policy: ConversationScrollPolicy,
    input: ScrollPolicyInput,
    anchor: TranscriptAnchor?,
    withGeneration: suspend (Long, suspend () -> Unit) -> Boolean,
): ViewportSettlement {
    if (!awaitStableUsableGeometry(listState, itemCount, geometry, bottomOcclusion, density)) {
        return ViewportSettlement(pendingGeometry = true)
    }

    val decision = policy.decide(input.copy(transitionSettled = true))
    var recoveryNeeded = false
    val completed = when (decision.command) {
        ScrollCommand.FollowLatest -> withGeneration(decision.transitionGeneration) {
            settleTerminalRow(
                listState = listState,
                itemCount = itemCount,
                dockHeightPx = geometry.dockHeightPx,
            )
        }

        ScrollCommand.RestoreAnchor -> withGeneration(decision.transitionGeneration) {
            val restored = restoreTranscriptAnchor(
                listState = listState,
                itemKeys = itemKeys,
                anchor = anchor,
            )
            if (decision.fallbackToLatest || !restored) {
                settleTerminalRow(
                    listState = listState,
                    itemCount = itemCount,
                    dockHeightPx = geometry.dockHeightPx,
                )
                recoveryNeeded = true
            }
        }

        else -> true
    }
    return ViewportSettlement(
        pendingGeometry = false,
        decision = decision,
        recoveryNeeded = recoveryNeeded,
        completed = completed,
    )
}

private data class GeometrySample(
    val dockHeightPx: Int,
    val bottomInsetPx: Int,
    val bottomInsetsSettled: Boolean,
    val totalItemsCount: Int,
    val viewportStartOffset: Int,
    val viewportEndOffset: Int,
)

private suspend fun awaitStableUsableGeometry(
    listState: LazyListState,
    itemCount: Int,
    geometry: ViewportGeometry,
    bottomOcclusion: WindowInsets,
    density: androidx.compose.ui.unit.Density,
): Boolean {
    var previous: GeometrySample? = null
    var stableFrames = 0
    repeat(12) {
        withFrameNanos { }
        val layoutInfo = listState.layoutInfo
        val sample = GeometrySample(
            dockHeightPx = geometry.dockHeightPx,
            bottomInsetPx = bottomOcclusion.getBottom(density),
            bottomInsetsSettled = geometry.bottomInsetsSettled,
            totalItemsCount = layoutInfo.totalItemsCount,
            viewportStartOffset = layoutInfo.viewportStartOffset,
            viewportEndOffset = layoutInfo.viewportEndOffset,
        )
        if (sample == previous) {
            stableFrames += 1
        } else {
            previous = sample
            stableFrames = 1
        }
        if (sample.totalItemsCount == itemCount &&
            hasSettledViewportGeometry(
                dockHeightPx = sample.dockHeightPx,
                viewportStartOffsetPx = sample.viewportStartOffset,
                viewportEndOffsetPx = sample.viewportEndOffset,
                bottomInsetsSettled = sample.bottomInsetsSettled,
            ) &&
            stableFrames >= 2
        ) {
            return true
        }
    }
    // Do not convert an invalid but stable sample into a completed transition.
    // The keyed caller schedules another frame window after a short yield so
    // cancelled/incomplete IME animations and late inset dispatch can recover
    // without keeping the recomposer permanently busy.
    return false
}

private suspend fun settleTerminalRow(
    listState: LazyListState,
    itemCount: Int,
    dockHeightPx: Int,
) {
    if (itemCount == 0) return
    listState.scrollToItem(itemCount - 1)
    withFrameNanos { }
    val layoutInfo = listState.layoutInfo
    val terminal = layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemCount - 1 }
        ?: return
    val clearance = terminalBottomClearancePx(
        terminalOffsetPx = terminal.offset,
        terminalSizePx = terminal.size,
        viewportEndOffsetPx = layoutInfo.viewportEndOffset,
        afterContentPaddingPx = layoutInfo.afterContentPadding,
        dockHeightPx = dockHeightPx,
    )
    if (clearance < 0) {
        listState.scrollBy(-clearance.toFloat())
    }
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
    dockHeightPx: Int,
): Float? {
    if (itemCount == 0) return null
    val terminal = layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemCount - 1 }
        ?: return null
    val distancePx = abs(
        terminalBottomClearancePx(
            terminalOffsetPx = terminal.offset,
            terminalSizePx = terminal.size,
            viewportEndOffsetPx = layoutInfo.viewportEndOffset,
            afterContentPaddingPx = layoutInfo.afterContentPadding,
            dockHeightPx = dockHeightPx,
        ),
    )
    return with(density) { distancePx.toDp().value }
}

private fun turnStateColor(turnState: TurnState): Color = when (turnState) {
    TurnState.Idle -> CelesteCoral
    TurnState.Running -> CelesteGoldText
    TurnState.Synchronizing, TurnState.Reconnecting -> CelesteBlue
}
