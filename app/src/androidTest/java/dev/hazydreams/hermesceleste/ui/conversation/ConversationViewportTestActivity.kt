package dev.hazydreams.hermesceleste.ui.conversation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, contentFontScale),
            ) {
                HermesCelesteTheme {
                    var draft by remember { mutableStateOf("") }
                    ConversationScreen(
                        summary = syntheticSummary,
                        messages = syntheticMessages,
                        streamingText = "",
                        draft = draft,
                        turnState = contentTurnState,
                        loadingMessage = null,
                        errorMessage = null,
                        onDraftChange = { draft = it },
                        onSend = {},
                        onInterrupt = {},
                        onReconnect = {},
                        onBack = { backCalls += 1 },
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
