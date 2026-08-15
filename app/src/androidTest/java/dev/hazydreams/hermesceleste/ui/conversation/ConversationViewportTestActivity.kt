package dev.hazydreams.hermesceleste.ui.conversation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.hazydreams.hermesceleste.TurnState
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.HermesCelesteTheme

internal class ConversationViewportTestActivity : ComponentActivity() {
    var backCalls: Int = 0
        private set

    private var contentFontScale by mutableFloatStateOf(1f)
    private var contentTurnState by mutableStateOf(TurnState.Idle)
    private var contentSummary by mutableStateOf(syntheticSummary)
    private var contentMessages by mutableStateOf(syntheticMessages)
    private var contentStreamingText by mutableStateOf("")
    private var contentProjectionGeneration by mutableLongStateOf(0L)
    private var contentDraft by mutableStateOf("")
    private var contentLoadingMessage by mutableStateOf<String?>(null)
    private var contentErrorMessage by mutableStateOf<String?>(null)
    private var contentBottomInsetPx by mutableIntStateOf(0)
    private var contentTopInsetPx by mutableIntStateOf(0)
    private var contentBottomInsetsSettled by mutableStateOf(true)
    private var contentUseProductionInsets by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, contentFontScale),
            ) {
                HermesCelesteTheme {
                    ConversationScreen(
                        summary = contentSummary,
                        messages = contentMessages,
                        streamingText = contentStreamingText,
                        projectionGeneration = contentProjectionGeneration,
                        draft = contentDraft,
                        turnState = contentTurnState,
                        loadingMessage = contentLoadingMessage,
                        errorMessage = contentErrorMessage,
                        onDraftChange = { contentDraft = it },
                        onSend = { sendCalls += 1 },
                        onInterrupt = { interruptCalls += 1 },
                        onReconnect = { reconnectCalls += 1 },
                        onBack = { backCalls += 1 },
                        bottomOcclusion = if (contentUseProductionInsets) {
                            null
                        } else {
                            androidx.compose.foundation.layout.WindowInsets(
                                0,
                                0,
                                0,
                                contentBottomInsetPx,
                            )
                        },
                        safeDrawingInsets = if (contentUseProductionInsets) {
                            null
                        } else {
                            androidx.compose.foundation.layout.WindowInsets(
                                16,
                                contentTopInsetPx,
                                16,
                                0,
                            )
                        },
                        bottomOcclusionSettled = if (contentUseProductionInsets) {
                            null
                        } else {
                            contentBottomInsetsSettled
                        },
                    )
                }
            }
        }
    }

    fun setContentFontScale(value: Float) {
        contentFontScale = value
    }

    fun setContentTurnState(value: TurnState) {
        contentTurnState = value
    }

    fun setContentMessages(value: List<ConversationMessage>) {
        contentMessages = value
    }

    fun setContentStreamingText(value: String) {
        contentStreamingText = value
    }

    fun setContentProjectionGeneration(value: Long) {
        contentProjectionGeneration = value
    }

    fun setContentDraft(value: String) {
        contentDraft = value
    }

    fun setContentStatus(loading: String?, error: String?) {
        contentLoadingMessage = loading
        contentErrorMessage = error
    }

    fun setContentInsets(bottom: Int, top: Int = contentTopInsetPx, settled: Boolean = true) {
        contentBottomInsetPx = bottom
        contentTopInsetPx = top
        contentBottomInsetsSettled = settled
    }

    fun useProductionInsets(enabled: Boolean = true) {
        contentUseProductionInsets = enabled
    }

    fun setContentSummary(value: StoredSession) {
        contentSummary = value
    }

    fun resetActionCounts() {
        backCalls = 0
        sendCalls = 0
        interruptCalls = 0
        reconnectCalls = 0
    }

    var sendCalls: Int = 0
        private set

    var interruptCalls: Int = 0
        private set

    var reconnectCalls: Int = 0
        private set
}

internal val syntheticSummary = StoredSession(
    id = "instrumentation-session",
    title = "Synthetic conversation",
    preview = "",
    startedAt = 0.0,
    messageCount = 24,
    source = "instrumentation",
)

internal val syntheticMessages = (0 until 24).map { index ->
    ConversationMessage(
        role = if (index % 2 == 0) "user" else "assistant",
        text = "Synthetic transcript row $index. This content exists only to exercise viewport geometry.",
        id = "instrumentation-row-$index",
    )
}
